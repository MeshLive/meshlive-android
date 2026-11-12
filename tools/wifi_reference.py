#!/usr/bin/env python3
"""Wi-Fi transport (Design WF revision 3): executable reference and shared test-vector generator.

Normative companion of docs/dev/protocol/README.md "Wi-Fi transport" (revision 3). Host tooling, not app code. It implements the
BWF1 hop records (kinds 1-11), the BWU1 binding frames, the MWD1/MWK1/MWA1 Wi-Fi Aware discovery messages, every key
derivation and the role-tuple choice exactly as the document specifies, and it writes the byte-exact vectors that the
Android JVM tests (`WifiWireTest`, `WifiKeysTest`, `WifiRolesTest`) and the Windows Rust tests replay.

    python -B tools/wifi_reference.py                       run the self-checks and print a summary
    python -B tools/wifi_reference.py --json FILE           write the vectors as JSON (app/src/test/resources/wifi-vectors.json)

Needs the third-party `cryptography` package (for P-256 point multiplication and ECDH). Everything is deterministic:
fixed exporter, fixed ticket, fixed private scalars, fixed nonces.
"""
from __future__ import annotations

import base64
import hashlib
import hmac
import json
import re
import struct
import sys

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec

# ---------------------------------------------------------------- constants (PROTOCOL revision 3)
DOMAIN = b"BluetoothVideoMesh/WifiUpgrade/1/"
INFO_KEY = DOMAIN + b"key"
SALT_HOP = DOMAIN + b"hop"
BIND_LABEL = DOMAIN + b"bind"
INFO_G2H = DOMAIN + b"g2h"
INFO_H2G = DOMAIN + b"h2g"
DISCOVERY_DOMAIN = b"BluetoothVideoMesh/WifiDiscovery/1"
NATIVE_DOMAIN = b"BluetoothVideoMesh/WifiNative/1"
SSID_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"   # 31 characters: OfflineAppShare.UNAMBIGUOUS exactly

BWF1 = b"BWF1"
BWU1 = b"BWU1"
MWD1 = b"MWD1"
MWK1 = b"MWK1"
MWA1 = b"MWA1"
MAX_BWF1_PLAINTEXT = 96
MIN_5GHZ_MHZ = 4900
MAX_HOST_VALID_MS = 20_000

BODY_BYTES = {1: 4, 2: 16, 3: 9, 4: 24, 5: 8, 6: 9, 7: 8, 8: 28, 9: 28, 10: 1, 11: 14}

BEARER_AWARE, BEARER_DIRECT = 1, 2
CAPS_AWARE, CAPS_AWARE_ICM, CAPS_DIRECT_GO, CAPS_DIRECT_CLIENT = 1, 2, 4, 8


class WireError(Exception):
    """`error` is one of LENGTH, MAGIC, KIND, BODY_LENGTH, FIELD, VERSION (the Kotlin enum WifiWireError)."""

    def __init__(self, error: str, message: str = ""):
        super().__init__(f"{error}: {message}")
        self.error = error


def sha256(data: bytes) -> bytes:
    return hashlib.sha256(data).digest()


def hmac256(key: bytes, *parts: bytes) -> bytes:
    h = hmac.new(key, digestmod=hashlib.sha256)
    for p in parts:
        h.update(p)
    return h.digest()


def hkdf_extract(salt: bytes, ikm: bytes) -> bytes:
    return hmac256(salt, ikm)


def hkdf_expand32(prk: bytes, info: bytes) -> bytes:
    """One 32-byte HKDF-Expand block: HMAC(prk, info | 0x01) (Bmh3Crypto.expand)."""
    return hmac256(prk, info, b"\x01")


def b32(data: bytes) -> str:
    return base64.b32encode(data).decode("ascii").lower().rstrip("=")


# ---------------------------------------------------------------- keys
def uw_call(exporter: bytes) -> bytes:
    assert len(exporter) == 32
    return hkdf_expand32(exporter, INFO_KEY)


def uw_hop(key_a: bytes, key_b: bytes) -> bytes:
    assert len(key_a) == 32 and len(key_b) == 32 and key_a != key_b
    lo, hi = (key_a, key_b) if key_a < key_b else (key_b, key_a)
    return hkdf_expand32(hkdf_extract(SALT_HOP, lo + hi), INFO_KEY)


def service_name(uw: bytes, ticket: bytes) -> str:
    return "m" + b32(hmac256(uw, b"svc", ticket))[:15]


def pmk(uw: bytes, ticket: bytes) -> bytes:
    return hmac256(uw, b"pmk", ticket)


def port(uw: bytes, ticket: bytes) -> int:
    """The Wi-Fi Aware host's TCP port: 49152 + (u32be(HMAC(UW, "port" | ticket)[0:4]) mod 16384), 49152 to 65535."""
    word = int.from_bytes(hmac256(uw, b"port", ticket)[:4], "big")
    return 49152 + word % 16384


def knock(uw: bytes, ticket: bytes, g_n: bytes) -> bytes:
    """Guest knock message to one discovered handle: gN | MAC (32 bytes, 04-REVIEW-FIXES F-1)."""
    assert len(g_n) == 16
    return g_n + hmac256(uw, b"knock", ticket, g_n)[:16]


def aware_challenge(uw: bytes, ticket: bytes, g_n: bytes, c: bytes) -> bytes:
    """Host challenge message bound to the knock's gN: c | MAC (32 bytes, F-1)."""
    assert len(g_n) == 16 and len(c) == 16
    return c + hmac256(uw, b"chal", ticket, g_n, c)[:16]


def direct_ssid(uw: bytes, ticket: bytes) -> str:
    b = hmac256(uw, b"ssid", ticket)
    chars = [SSID_ALPHABET[((b[2 * i] << 8) | b[2 * i + 1]) % 31] for i in range(8)]  # big-endian u16 per character
    return "DIRECT-" + "".join(chars[:2]) + "-" + "".join(chars[2:8])


def direct_psk(uw: bytes, ticket: bytes) -> str:
    return base64.urlsafe_b64encode(hmac256(uw, b"psk", ticket)).decode("ascii").rstrip("=")[:43]


def wider_service_name(epoch: int) -> str:
    return "m" + b32(sha256(DISCOVERY_DOMAIN + struct.pack(">Q", epoch)))[:15]


def native_pmk(epoch: int, nonce: bytes) -> bytes:
    assert len(nonce) == 8
    return hmac256(sha256(NATIVE_DOMAIN + struct.pack(">Q", epoch)), b"pmk", nonce)


def pre(uw: bytes, ticket: bytes, challenge: bytes, e_g: bytes, n_g: bytes) -> bytes:
    return hmac256(uw, b"pre", ticket, challenge, e_g, n_g)[:16]


def transcript_hash(f1: bytes, f2: bytes, f3: bytes) -> bytes:
    return sha256(BIND_LABEL + b"\x00" + f1 + f2 + f3)


def bind_keys(uw: bytes, ticket: bytes, th: bytes, dh: bytes) -> tuple[bytes, bytes, bytes, bytes]:
    salt = hmac256(uw, b"bind", ticket, th)
    k = hkdf_extract(salt, dh)
    return salt, k, hkdf_expand32(k, INFO_G2H), hkdf_expand32(k, INFO_H2G)


# ---------------------------------------------------------------- P-256 helpers
P256_P = 0xFFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF
P256_N = 0xFFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551
P256_B = 0x5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B


def scalar(label: str) -> int:
    return int.from_bytes(sha256(b"wifi test vector scalar/" + label.encode()), "big") % (P256_N - 1) + 1


def point_of(k: int) -> bytes:
    key = ec.derive_private_key(k, ec.SECP256R1())
    return key.public_key().public_bytes(serialization.Encoding.X962, serialization.PublicFormat.UncompressedPoint)


def ecdh(k: int, peer_point: bytes) -> bytes:
    key = ec.derive_private_key(k, ec.SECP256R1())
    return key.exchange(ec.ECDH(), ec.EllipticCurvePublicKey.from_encoded_point(ec.SECP256R1(), peer_point))


def on_curve(point: bytes) -> bool:
    if len(point) != 65 or point[0] != 4:
        return False
    x, y = int.from_bytes(point[1:33], "big"), int.from_bytes(point[33:], "big")
    if x >= P256_P or y >= P256_P:
        return False
    return (y * y - (x * x * x - 3 * x + P256_B)) % P256_P == 0


# ---------------------------------------------------------------- BWF1
def u16(v: int) -> bytes:
    return struct.pack(">H", v)


def u32(v: int) -> bytes:
    return struct.pack(">I", v)


def u64(v: int) -> bytes:
    return struct.pack(">Q", v)


def encode_bwf1(kind: int, body: bytes) -> bytes:
    return BWF1 + bytes([kind]) + body


def decode_bwf1(data: bytes) -> tuple[int, dict]:
    """Returns (kind, fields). Check order: LENGTH, MAGIC, KIND, BODY_LENGTH, FIELD."""
    if len(data) < 5 or len(data) > MAX_BWF1_PLAINTEXT:
        raise WireError("LENGTH", f"{len(data)} bytes")
    if data[:4] != BWF1:
        raise WireError("MAGIC")
    kind = data[4]
    if kind not in BODY_BYTES:
        raise WireError("KIND", str(kind))
    body = data[5:]
    if len(body) != BODY_BYTES[kind]:
        raise WireError("BODY_LENGTH", f"kind {kind}: {len(body)}")
    f: dict = {}
    if kind == 1:
        # Forward compatibility (04-REVIEW-FIXES X5): bits 4-7 and the reserved byte are sent 0 and ignored.
        f = dict(bearers=body[0] & 0x0F, freeHost=body[1], freeGuest=body[2])
        if f["freeHost"] > 7 or f["freeGuest"] > 7:
            raise WireError("FIELD", "caps")
    elif kind == 2:
        status, bearer = body[0], body[1]
        valid, freq, reserved = struct.unpack(">HHH", body[10:16])
        f = dict(status=status, bearer=bearer, ticket=body[2:10].hex(), validMs=valid, freqMhz=freq)
        if status not in (0, 1) or bearer not in (1, 2):  # reserved: sent 0, ignored (X5)
            raise WireError("FIELD", "host")
        if status == 1 and not (1 <= valid <= MAX_HOST_VALID_MS):
            raise WireError("FIELD", "host validMs")
        if status == 0 and (valid != 0 or freq != 0):
            raise WireError("FIELD", "host status 0")
        if bearer == 1 and freq != 0:
            raise WireError("FIELD", "host aware freq")
        if bearer == 2 and freq != 0 and freq < MIN_5GHZ_MHZ:
            raise WireError("FIELD", "host direct freq")
    elif kind == 3:
        f = dict(ticket=body[:8].hex(), reason=body[8])
        if not 1 <= body[8] <= 7:
            raise WireError("FIELD", "decline reason")
    elif kind == 4:
        f = dict(ticket=body[:8].hex(), challenge=body[8:].hex())
    elif kind == 5:
        f = dict(ticket=body.hex())
    elif kind == 6:
        f = dict(role=body[0], ticket=body[1:].hex())
        if body[0] not in (1, 2):
            raise WireError("FIELD", "bind role")
    elif kind == 7:
        a, b, c, reserved = struct.unpack(">HHHH", body)
        f = dict(recvCeilingKbps=a, familyCapKbps=b, transportBudgetKbps=c)  # reserved: sent 0, ignored (X5)
    elif kind in (8, 9):
        gen = struct.unpack(">I", body[:4])[0]
        a, b = struct.unpack(">QQ", body[12:28])
        names = ("e2eBoundary", "meshBoundary") if kind == 8 else ("e2eNext", "meshNext")
        f = dict(gen=gen, ticket=body[4:12].hex(), **{names[0]: a, names[1]: b})
        if gen == 0 or a >> 63 or b >> 63:
            raise WireError("FIELD", "switch/resume")
    elif kind == 10:
        f = dict(reason=body[0])
    elif kind == 11:
        port = struct.unpack(">H", body[8:10])[0]
        f = dict(ticket=body[:8].hex(), port=port, ipv4=".".join(str(x) for x in body[10:14]))
        if port == 0 or body[10:14] == b"\x00\x00\x00\x00":
            raise WireError("FIELD", "creds")
    return kind, f


# ---------------------------------------------------------------- BWU1
def bwu1_frame1(challenge: bytes) -> bytes:
    assert len(challenge) == 16
    return BWU1 + b"\x01" + challenge


def bwu1_frame2(ticket: bytes, e_g: bytes, n_g: bytes, pre_: bytes) -> bytes:
    assert len(ticket) == 8 and len(e_g) == 65 and len(n_g) == 32 and len(pre_) == 16
    return BWU1 + b"\x02" + ticket + e_g + n_g + pre_


def bwu1_frame3(e_h: bytes, n_h: bytes) -> bytes:
    assert len(e_h) == 65 and len(n_h) == 32
    return BWU1 + b"\x03" + e_h + n_h


BWU1_SIZES = {1: 21, 2: 126, 3: 102}


def decode_bwu1(frame_type: int, data: bytes) -> dict:
    """Check order: LENGTH (exact for the frame type), MAGIC, KIND (type byte), FIELD (0x04 point prefix)."""
    if len(data) != BWU1_SIZES[frame_type]:
        raise WireError("LENGTH", f"{len(data)}")
    if data[:4] != BWU1:
        raise WireError("MAGIC")
    if data[4] != frame_type:
        raise WireError("KIND", str(data[4]))
    if frame_type == 1:
        return dict(challenge=data[5:].hex())
    if frame_type == 2:
        if data[13] != 4:
            raise WireError("FIELD", "e_G prefix")
        return dict(ticket=data[5:13].hex(), eG=data[13:78].hex(), nG=data[78:110].hex(), pre=data[110:126].hex())
    if data[5] != 4:
        raise WireError("FIELD", "e_H prefix")
    return dict(eH=data[5:70].hex(), nH=data[70:102].hex())


# ---------------------------------------------------------------- MWD1 / MWK1 / MWA1
# Rotating-ID announcement versions that may travel in MWD1: 5 (rotating) and 8 (Visible-by-name window offer).
MWD1_ANNOUNCEMENT_VERSIONS = (5, 8)


def mwd1(announcement: bytes) -> bytes:
    assert len(announcement) == 22 and announcement[0] in MWD1_ANNOUNCEMENT_VERSIONS
    return MWD1 + b"\x01" + announcement


def decode_mwd1(data: bytes) -> dict:
    if len(data) != 27:
        raise WireError("LENGTH", f"{len(data)}")
    if data[:4] != MWD1:
        raise WireError("MAGIC")
    if data[4] != 1:
        raise WireError("VERSION", str(data[4]))
    if data[5] not in MWD1_ANNOUNCEMENT_VERSIONS:  # a fixed-prefix or unknown announcement never travels on Wi-Fi (X8)
        raise WireError("FIELD", f"announcement version {data[5]}")
    return dict(announcement=data[5:].hex())


def mwk1(nonce: bytes) -> bytes:
    assert len(nonce) == 8
    return MWK1 + nonce


def decode_mwk1(data: bytes) -> dict:
    if len(data) != 12:
        raise WireError("LENGTH", f"{len(data)}")
    if data[:4] != MWK1:
        raise WireError("MAGIC")
    return dict(nonce=data[4:].hex())


def mwa1(nonce: bytes, port: int) -> bytes:
    assert len(nonce) == 8
    return MWA1 + nonce + u16(port)


def decode_mwa1(data: bytes) -> dict:
    if len(data) != 14:
        raise WireError("LENGTH", f"{len(data)}")
    if data[:4] != MWA1:
        raise WireError("MAGIC")
    port = struct.unpack(">H", data[12:14])[0]
    if port == 0:
        raise WireError("FIELD", "port")
    return dict(nonce=data[4:12].hex(), port=port)


# ---------------------------------------------------------------- role tuples (Windows is always host: it sets only bit 2)
def choose_roles(local_bearers: int, local_fh: int, local_fg: int, peer_bearers: int, peer_fh: int, peer_fg: int,
                 local_is_initiator: bool):
    """(bearer, localIsHost) or None. Aware first (bit 0 on both; free counts decide), then Direct (bit 2 host, bit 3
    guest; the free counts are not used). For each bearer: host = link responder first, then the reverse."""
    if local_is_initiator:
        init = (local_bearers, local_fh, local_fg)
        resp = (peer_bearers, peer_fh, peer_fg)
    else:
        init = (peer_bearers, peer_fh, peer_fg)
        resp = (local_bearers, local_fh, local_fg)
    if init[0] & CAPS_AWARE and resp[0] & CAPS_AWARE:
        host_is_responder = None
        if resp[1] > 0 and init[2] > 0:
            host_is_responder = True
        elif init[1] > 0 and resp[2] > 0:
            host_is_responder = False
        if host_is_responder is not None:
            return BEARER_AWARE, host_is_responder != local_is_initiator
    host_is_responder = None
    if resp[0] & CAPS_DIRECT_GO and init[0] & CAPS_DIRECT_CLIENT:
        host_is_responder = True
    elif init[0] & CAPS_DIRECT_GO and resp[0] & CAPS_DIRECT_CLIENT:
        host_is_responder = False
    if host_is_responder is None:
        return None
    return BEARER_DIRECT, host_is_responder != local_is_initiator


# ---------------------------------------------------------------- vectors
EXPORTER = sha256(b"wifi test vector exporter")
TICKET = bytes.fromhex("a1b2c3d4e5f60718")
TICKET2 = bytes.fromhex("0102030405060708")
KEY_A = sha256(b"wifi test vector hop key A")
KEY_B = sha256(b"wifi test vector hop key B")
CHALLENGE = sha256(b"wifi test vector challenge")[:16]
CHALLENGE2 = sha256(b"wifi test vector challenge 2")[:16]
KNOCK_NONCE = sha256(b"wifi test vector knock nonce")[:16]
KNOCK_NONCE2 = sha256(b"wifi test vector knock nonce 2")[:16]
N_G = sha256(b"wifi test vector nonce G")
N_H = sha256(b"wifi test vector nonce H")
# a version 5 (rotating) discovery announcement, 22 bytes: version, 6-byte id, flags, capability summary, token (4),
# route cost, 6 compact bytes, bitrate (2)
ANNOUNCEMENT = bytes.fromhex("05" + "44e69c0a7a7d" + "49" + "03" + "00000000" + "00" + "000000000000" + "0000")
EPOCH = 123456
NATIVE_NONCE = bytes.fromhex("1122334455667788")


def h(b: bytes) -> str:
    return b.hex()


def valid_bwf1_vectors() -> list:
    t = TICKET
    items = [
        ("caps: aware + icm, 1 host, 2 guest", 1, bytes([0x03, 1, 2, 0])),
        ("caps: all clear (switches off)", 1, bytes([0, 0, 0, 0])),
        ("caps: direct go + client, free counts at the maximum", 1, bytes([0x0C, 7, 7, 0])),
        ("caps: windows (direct group owner only)", 1, bytes([0x04, 0, 0, 0])),
        ("host: aware offered, 20 s", 2, bytes([1, 1]) + t + u16(20_000) + u16(0) + u16(0)),
        ("host: direct offered with a 5 GHz group frequency", 2, bytes([1, 2]) + t + u16(20_000) + u16(5745) + u16(0)),
        ("host: direct offered by windows (frequency 0)", 2, bytes([1, 2]) + t + u16(15_000) + u16(0) + u16(0)),
        ("host: nothing offered", 2, bytes([0, 1]) + t + u16(0) + u16(0) + u16(0)),
        ("decline: busy", 3, t + bytes([2])),
        ("decline: data path not on 5 GHz", 3, t + bytes([7])),
        ("pick", 4, t + CHALLENGE),
        ("armed", 5, t),
        ("bind: guest", 6, bytes([1]) + t),
        ("bind: host", 6, bytes([2]) + t),
        ("ready", 7, u16(2000) + u16(0) + u16(6000) + u16(0)),
        ("ready: family cap present", 7, u16(65535) + u16(900) + u16(1) + u16(0)),
        ("switch", 8, u32(1) + t + u64(1234) + u64(0)),
        ("switch: large boundaries", 8, u32(0xFFFFFFFF) + t + u64((1 << 63) - 1) + u64(77)),
        ("resume", 9, u32(2) + t + u64(1300) + u64(5)),
        ("close: hang-up", 10, bytes([1])),
        ("creds", 11, t + u16(40000) + bytes([192, 168, 137, 1])),
        ("caps: future bits 4-7 and reserved byte are ignored", 1, bytes([0xF1, 0, 0, 9])),
        ("host: reserved field is ignored", 2, bytes([1, 1]) + t + u16(100) + u16(0) + u16(1)),
        ("ready: reserved field is ignored", 7, u16(1) + u16(1) + u16(1) + u16(1)),
    ]
    out = []
    for name, kind, body in items:
        raw = encode_bwf1(kind, body)
        k, fields = decode_bwf1(raw)
        assert k == kind
        # roundTrip false: re-encoding the decoded fields writes 0 where the record carried ignored bits (X5).
        out.append(dict(name=name, kind=kind, hex=h(raw), decoded=fields, roundTrip="ignored" not in name))
    return out


def invalid_bwf1_vectors() -> list:
    t = TICKET
    cases = [
        ("too short: magic only", BWF1, "LENGTH"),
        ("too long: 97 bytes", BWF1 + b"\x0a" + bytes(92), "LENGTH"),
        ("wrong magic", b"BCX1" + b"\x01" + bytes(4), "MAGIC"),
        ("kind 0", encode_bwf1(0, bytes(4)), "KIND"),
        ("kind 12", encode_bwf1(12, bytes(4)), "KIND"),
        ("caps body 3 bytes", encode_bwf1(1, bytes(3)), "BODY_LENGTH"),
        ("caps body 5 bytes", encode_bwf1(1, bytes(5)), "BODY_LENGTH"),
        ("host body 15 bytes", encode_bwf1(2, bytes(15)), "BODY_LENGTH"),
        ("switch body 27 bytes", encode_bwf1(8, bytes(27)), "BODY_LENGTH"),
        ("caps freeHost 8", encode_bwf1(1, bytes([0x01, 8, 0, 0])), "FIELD"),
        ("caps freeGuest 8", encode_bwf1(1, bytes([0x01, 0, 8, 0])), "FIELD"),
        ("host status 2", encode_bwf1(2, bytes([2, 1]) + t + u16(1) + u16(0) + u16(0)), "FIELD"),
        ("host bearer 0", encode_bwf1(2, bytes([1, 0]) + t + u16(1) + u16(0) + u16(0)), "FIELD"),
        ("host bearer 3", encode_bwf1(2, bytes([1, 3]) + t + u16(1) + u16(0) + u16(0)), "FIELD"),
        ("host validMs 20001", encode_bwf1(2, bytes([1, 1]) + t + u16(20_001) + u16(0) + u16(0)), "FIELD"),
        ("host status 1 validMs 0", encode_bwf1(2, bytes([1, 1]) + t + u16(0) + u16(0) + u16(0)), "FIELD"),
        ("host status 0 with validMs", encode_bwf1(2, bytes([0, 1]) + t + u16(5) + u16(0) + u16(0)), "FIELD"),
        ("host aware with a frequency", encode_bwf1(2, bytes([1, 1]) + t + u16(100) + u16(5745) + u16(0)), "FIELD"),
        ("host direct on 2.4 GHz", encode_bwf1(2, bytes([1, 2]) + t + u16(100) + u16(2437) + u16(0)), "FIELD"),
        ("decline reason 0", encode_bwf1(3, t + bytes([0])), "FIELD"),
        ("decline reason 8", encode_bwf1(3, t + bytes([8])), "FIELD"),
        ("bind role 0", encode_bwf1(6, bytes([0]) + t), "FIELD"),
        ("bind role 3", encode_bwf1(6, bytes([3]) + t), "FIELD"),
        ("switch generation 0", encode_bwf1(8, u32(0) + t + u64(1) + u64(0)), "FIELD"),
        ("switch boundary above 2^63", encode_bwf1(8, u32(1) + t + u64(1 << 63) + u64(0)), "FIELD"),
        ("resume mesh next above 2^63", encode_bwf1(9, u32(1) + t + u64(1) + u64(1 << 63)), "FIELD"),
        ("creds port 0", encode_bwf1(11, t + u16(0) + bytes([192, 168, 137, 1])), "FIELD"),
        ("creds address 0.0.0.0", encode_bwf1(11, t + u16(1) + bytes(4)), "FIELD"),
    ]
    out = []
    for name, raw, error in cases:
        try:
            decode_bwf1(raw)
        except WireError as e:
            assert e.error == error, (name, e.error, error)
        else:
            raise AssertionError(f"{name} was accepted")
        out.append(dict(name=name, hex=h(raw), error=error))
    return out


def build_vectors() -> dict:
    uw = uw_call(EXPORTER)
    uw_h = uw_hop(KEY_A, KEY_B)
    assert uw_h == uw_hop(KEY_B, KEY_A)
    kg, kh = scalar("bind guest ephemeral"), scalar("bind host ephemeral")
    e_g, e_h = point_of(kg), point_of(kh)
    assert on_curve(e_g) and on_curve(e_h)
    pre_ = pre(uw, TICKET, CHALLENGE, e_g, N_G)
    pre_second = pre(uw, TICKET, CHALLENGE2, e_g, N_G)
    f1, f2, f3 = bwu1_frame1(CHALLENGE), bwu1_frame2(TICKET, e_g, N_G, pre_), bwu1_frame3(e_h, N_H)
    dh = ecdh(kg, e_h)
    assert dh == ecdh(kh, e_g) and len(dh) == 32
    th = transcript_hash(f1, f2, f3)
    salt, k, k_g2h, k_h2g = bind_keys(uw, TICKET, th, dh)
    off_curve = bytearray(e_g)
    off_curve[64] ^= 1
    assert not on_curve(bytes(off_curve))

    role_rows = []
    sets = [0, CAPS_AWARE, CAPS_DIRECT_GO, CAPS_DIRECT_CLIENT, CAPS_DIRECT_GO | CAPS_DIRECT_CLIENT]
    for lb in sets:
        for pb in sets:
            for lfh in (0, 1):
                for lfg in (0, 1):
                    for pfh in (0, 1):
                        for pfg in (0, 1):
                            for init in (0, 1):
                                r = choose_roles(lb, lfh, lfg, pb, pfh, pfg, bool(init))
                                role_rows.append([lb, lfh, lfg, pb, pfh, pfg, init,
                                                  0 if r is None else r[0], 0 if r is None else int(r[1])])

    mwd_vec = mwd1(ANNOUNCEMENT)
    invalid_wire = []

    def bad(name, fn, data, error, **extra):
        try:
            fn(data)
        except WireError as e:
            assert e.error == error, (name, e.error)
        else:
            raise AssertionError(name)
        invalid_wire.append(dict(name=name, hex=h(data), error=error, **extra))

    bad("bwu1 frame 1: 20 bytes", lambda d: decode_bwu1(1, d), f1[:20], "LENGTH", frame=1)
    bad("bwu1 frame 1: wrong magic", lambda d: decode_bwu1(1, d), b"BWU2" + f1[4:], "MAGIC", frame=1)
    bad("bwu1 frame 1: type 2", lambda d: decode_bwu1(1, d), f1[:4] + b"\x02" + f1[5:], "KIND", frame=1)
    bad("bwu1 frame 2: 125 bytes", lambda d: decode_bwu1(2, d), f2[:125], "LENGTH", frame=2)
    bad("bwu1 frame 2: type 3", lambda d: decode_bwu1(2, d), f2[:4] + b"\x03" + f2[5:], "KIND", frame=2)
    bad("bwu1 frame 2: e_G does not start with 0x04", lambda d: decode_bwu1(2, d), f2[:13] + b"\x02" + f2[14:], "FIELD", frame=2)
    bad("bwu1 frame 3: 103 bytes", lambda d: decode_bwu1(3, d), f3 + b"\x00", "LENGTH", frame=3)
    bad("bwu1 frame 3: e_H does not start with 0x04", lambda d: decode_bwu1(3, d), f3[:5] + b"\x03" + f3[6:], "FIELD", frame=3)
    bad("mwd1: 26 bytes", decode_mwd1, mwd_vec[:26], "LENGTH", frame=0)
    bad("mwd1: wrong magic", decode_mwd1, b"MWD2" + mwd_vec[4:], "MAGIC", frame=0)
    bad("mwd1: version 2", decode_mwd1, mwd_vec[:4] + b"\x02" + mwd_vec[5:], "VERSION", frame=0)
    bad("mwd1: fixed-prefix announcement (version 2)", decode_mwd1, mwd_vec[:5] + b"\x02" + mwd_vec[6:], "FIELD", frame=0)
    bad("mwd1: unknown announcement version 9", decode_mwd1, mwd_vec[:5] + b"\x09" + mwd_vec[6:], "FIELD", frame=0)
    bad("mwk1: 13 bytes", decode_mwk1, mwk1(NATIVE_NONCE) + b"\x00", "LENGTH", frame=0)
    bad("mwk1: wrong magic", decode_mwk1, b"MWA1" + NATIVE_NONCE, "MAGIC", frame=0)
    bad("mwa1: 13 bytes", decode_mwa1, mwa1(NATIVE_NONCE, 5)[:13], "LENGTH", frame=0)
    bad("mwa1: port 0", decode_mwa1, mwa1(NATIVE_NONCE, 0), "FIELD", frame=0)

    return {
        "name": "MeshLive Wi-Fi transport vectors, Design WF revision 3 (BWF1, BWU1, MWD1, MWK1, MWA1, keys, roles)",
        "version": 1,
        "generator": "tools/wifi_reference.py (do not edit this file by hand; regenerate and review the diff)",
        "errors": "LENGTH | MAGIC | KIND | BODY_LENGTH | FIELD | VERSION",
        "keys": {
            "exporter": h(EXPORTER), "uwCall": h(uw),
            "hopKeyA": h(KEY_A), "hopKeyB": h(KEY_B), "uwHop": h(uw_h),
            "ticket": h(TICKET), "ticket2": h(TICKET2),
            "serviceName": service_name(uw, TICKET), "serviceNameHop": service_name(uw_h, TICKET),
            "serviceNameTicket2": service_name(uw, TICKET2),
            "pmk": h(pmk(uw, TICKET)), "knockNonce": h(KNOCK_NONCE), "knock": h(knock(uw, TICKET, KNOCK_NONCE)),
            "knockNonce2": h(KNOCK_NONCE2), "knock2": h(knock(uw, TICKET, KNOCK_NONCE2)),
            "awareChallengeNonce": h(CHALLENGE), "awareChallenge": h(aware_challenge(uw, TICKET, KNOCK_NONCE, CHALLENGE)),
            "awareChallengeOtherKnock": h(aware_challenge(uw, TICKET, KNOCK_NONCE2, CHALLENGE)),
            "ssid": direct_ssid(uw, TICKET), "psk": direct_psk(uw, TICKET),
            "ssidTicket2": direct_ssid(uw, TICKET2), "pskTicket2": direct_psk(uw, TICKET2),
            "port": port(uw, TICKET), "portTicket2": port(uw, TICKET2), "portHop": port(uw_h, TICKET),
            "epoch": EPOCH, "widerServiceName": wider_service_name(EPOCH),
            "widerServiceNameNextEpoch": wider_service_name(EPOCH + 1),
            "nativeNonce": h(NATIVE_NONCE), "nativePmk": h(native_pmk(EPOCH, NATIVE_NONCE)),
        },
        "bind": {
            "challenge": h(CHALLENGE), "challenge2": h(CHALLENGE2),
            "guestScalar": f"{kg:064x}", "guestPoint": h(e_g), "guestNonce": h(N_G),
            "hostScalar": f"{kh:064x}", "hostPoint": h(e_h), "hostNonce": h(N_H),
            "pre": h(pre_), "preWithSecondChallenge": h(pre_second),
            "frame1": h(f1), "frame2": h(f2), "frame3": h(f3),
            "th": h(th), "dh": h(dh), "salt": h(salt), "K": h(k), "kG2H": h(k_g2h), "kH2G": h(k_h2g),
            "offCurveGuestPoint": h(bytes(off_curve)),
        },
        "bwf1": valid_bwf1_vectors(),
        "bwf1Invalid": invalid_bwf1_vectors(),
        "discovery": {
            "announcement": h(ANNOUNCEMENT), "mwd1": h(mwd_vec),
            "announcementWindow": h(b"\x08" + ANNOUNCEMENT[1:]), "mwd1Window": h(mwd1(b"\x08" + ANNOUNCEMENT[1:])),
            "mwk1": h(mwk1(NATIVE_NONCE)), "mwa1": h(mwa1(NATIVE_NONCE, 40123)), "mwa1Port": 40123,
        },
        "wireInvalid": invalid_wire,
        "roles": {
            "fields": "localBearers, localFreeHost, localFreeGuest, peerBearers, peerFreeHost, peerFreeGuest, "
                      "localIsLinkInitiator(0/1), resultBearer(0 none, 1 aware, 2 direct), localIsHost(0/1)",
            "rows": role_rows,
        },
    }


def main(argv: list) -> int:
    vectors = build_vectors()
    if vectors != build_vectors():
        raise SystemExit("vectors are not deterministic")
    if "--json" in argv:
        path = argv[argv.index("--json") + 1]
        with open(path, "w", encoding="utf-8", newline="\n") as handle:
            text = json.dumps(vectors, indent=1, sort_keys=False)
            # one line per role row instead of one line per number
            text = re.sub(r"\[\s+(-?\d+(?:,\s+-?\d+)*)\s+\]", lambda m: "[" + re.sub(r"\s+", "", m.group(1)) + "]", text)
            handle.write(text + "\n")
        print(f"wrote {path}")
    else:
        k = vectors["keys"]
        print("uwCall", k["uwCall"], "\nserviceName", k["serviceName"], "\nssid", k["ssid"], "\npsk", k["psk"])
        print("bwf1", len(vectors["bwf1"]), "invalid", len(vectors["bwf1Invalid"]), "roles", len(vectors["roles"]["rows"]))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
