"""wifi_reference: the executable Wi-Fi transport specification reproduces, rejects what it must and matches the JSON that
the Android JVM tests and the Windows Rust tests replay."""
from pathlib import Path
import hashlib
import hmac
import json
import struct
import sys
import unittest

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

try:
    import wifi_reference as ref
    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.primitives.kdf.hkdf import HKDFExpand
except ImportError:  # the third-party `cryptography` package is optional host tooling
    ref = None

FIXTURE = Path(__file__).resolve().parents[2] / "app/src/test/resources/wifi-vectors.json"


@unittest.skipIf(ref is None, "needs the cryptography package")
class WifiReferenceTest(unittest.TestCase):
    def test_the_vectors_are_deterministic_and_the_committed_file_is_the_generator_output(self):
        first = ref.build_vectors()
        self.assertEqual(first, ref.build_vectors())
        self.assertEqual(first, json.loads(FIXTURE.read_text(encoding="utf-8")))

    def test_the_one_block_expand_equals_a_library_hkdf_expand_of_32_bytes(self):
        prk = ref.sha256(b"prk")
        info = ref.INFO_KEY
        self.assertEqual(ref.hkdf_expand32(prk, info), HKDFExpand(hashes.SHA256(), 32, info).derive(prk))

    def test_every_invalid_vector_is_rejected_with_the_listed_error_and_every_valid_one_decodes(self):
        vectors = ref.build_vectors()
        for item in vectors["bwf1"]:
            kind, fields = ref.decode_bwf1(bytes.fromhex(item["hex"]))
            self.assertEqual(kind, item["kind"], item["name"])
            self.assertEqual(fields, item["decoded"], item["name"])
        for item in vectors["bwf1Invalid"]:
            with self.assertRaises(ref.WireError, msg=item["name"]) as caught:
                ref.decode_bwf1(bytes.fromhex(item["hex"]))
            self.assertEqual(caught.exception.error, item["error"], item["name"])

    def test_the_hop_key_does_not_depend_on_which_directional_key_is_called_a_or_b(self):
        self.assertEqual(ref.uw_hop(ref.KEY_A, ref.KEY_B), ref.uw_hop(ref.KEY_B, ref.KEY_A))
        self.assertNotEqual(ref.uw_hop(ref.KEY_A, ref.KEY_B), ref.uw_call(ref.EXPORTER))

    def test_names_and_passwords_have_the_specified_shape(self):
        uw = ref.uw_call(ref.EXPORTER)
        name = ref.service_name(uw, ref.TICKET)
        self.assertEqual(len(name), 16)
        self.assertTrue(name[0] == "m" and all(c in "abcdefghijklmnopqrstuvwxyz234567" for c in name))
        ssid = ref.direct_ssid(uw, ref.TICKET)
        self.assertRegex(ssid, r"^DIRECT-[a-z2-9]{2}-[a-z2-9]{6}$")
        self.assertNotRegex(ssid, r"[lo01]")
        self.assertEqual(len(ref.direct_psk(uw, ref.TICKET)), 43)
        self.assertNotEqual(name, ref.service_name(uw, ref.TICKET2))
        self.assertNotEqual(ref.wider_service_name(1), ref.wider_service_name(2))

    def test_the_aware_port_is_an_unsigned_big_endian_reduction_into_the_dynamic_range(self):
        uw, hop = ref.uw_call(ref.EXPORTER), ref.uw_hop(ref.KEY_A, ref.KEY_B)
        word = struct.unpack(">I", hmac.new(uw, b"port" + ref.TICKET, hashlib.sha256).digest()[:4])[0]
        self.assertEqual(49152 + word % 16384, ref.port(uw, ref.TICKET))
        ports = [ref.port(uw, ref.TICKET), ref.port(uw, ref.TICKET2), ref.port(hop, ref.TICKET)]
        self.assertTrue(all(49152 <= p <= 65535 for p in ports), ports)
        self.assertEqual(3, len(set(ports)))
        self.assertEqual(0, 2 ** 32 % 16384)  # every port is equally likely

    def test_a_pre_proof_is_bound_to_the_challenge_and_the_ticket(self):
        uw = ref.uw_call(ref.EXPORTER)
        e_g = ref.point_of(ref.scalar("x"))
        a = ref.pre(uw, ref.TICKET, ref.CHALLENGE, e_g, ref.N_G)
        self.assertEqual(len(a), 16)
        self.assertNotEqual(a, ref.pre(uw, ref.TICKET, ref.CHALLENGE2, e_g, ref.N_G))
        self.assertNotEqual(a, ref.pre(uw, ref.TICKET2, ref.CHALLENGE, e_g, ref.N_G))

    def test_both_ends_derive_the_same_carrier_keys(self):
        v = ref.build_vectors()["bind"]
        kg, kh = int(v["guestScalar"], 16), int(v["hostScalar"], 16)
        dh = ref.ecdh(kg, bytes.fromhex(v["hostPoint"]))
        self.assertEqual(dh, ref.ecdh(kh, bytes.fromhex(v["guestPoint"])))
        self.assertNotEqual(v["kG2H"], v["kH2G"])
        self.assertFalse(ref.on_curve(bytes.fromhex(v["offCurveGuestPoint"])))

    def test_the_role_choice_is_symmetric_and_windows_is_always_host(self):
        for lb in (0, 1, 4, 8, 12):
            for pb in (0, 1, 4, 8, 12):
                for lf in range(4):
                    for pf in range(4):
                        for init in (True, False):
                            me = ref.choose_roles(lb, lf & 1, lf >> 1, pb, pf & 1, pf >> 1, init)
                            you = ref.choose_roles(pb, pf & 1, pf >> 1, lb, lf & 1, lf >> 1, not init)
                            self.assertEqual(me is None, you is None)
                            if me is not None:
                                self.assertEqual(me[0], you[0])
                                self.assertNotEqual(me[1], you[1])
        windows, phone = 0x04, 0x0C
        for init in (True, False):
            tuple_ = ref.choose_roles(windows, 0, 0, phone, 0, 0, init)
            self.assertEqual(tuple_, (ref.BEARER_DIRECT, True))
            self.assertEqual(ref.choose_roles(phone, 0, 0, windows, 0, 0, not init), (ref.BEARER_DIRECT, False))


if __name__ == "__main__":
    unittest.main()
