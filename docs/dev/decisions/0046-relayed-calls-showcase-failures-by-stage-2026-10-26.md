# Relayed calls: showcase failures by stage (2026-10-26)

One-relay voice and low-resolution video calls on CoC and GATT between the M31, A15s, ASUS and S24, rotating IDs on, exactly one relay required. Every call through the S24 as helper set up. The failures split by stage:

| Failure | Stage and evidence | Cause | Change |
| --- | --- | --- | --- |
| A15s caller via an ASUS helper, 4 of 4 (CoC and GATT) | First hop: `setup_stall` at 3 s on 2M, `setup_retry_acl_wait dropped=false`, then the 1M retry redialed the helper's old address ("BLE control did not respond", "Timed out connecting GATT video"); the ASUS logged EOF about 4-7 s after the close and re-advertised. | The known A15s -> ASUS 2M setup stall (see "Stalled-setup rediscovery budget"); the 1M retry rescanned only for direct offers, and the helper hop had no bad-2M memory. | `relayed_first_hop_direct` (SWITCHES): rescan for the selected helper's own offer, helper memory read and written like a direct offer before SETUP. |
| A15s helper to an ASUS callee, 3 of 4 setups (CoC) | Child hop: CoC connected in 20-40 ms, the 2M PHY update completed, then no byte either way until the 12-16 s deadline; the ASUS's ACL stayed up (no 0x08) and its pre-setup watchdog closed at 20 s. A15s helpers reached the M31 callee. | Consistent with the same A15s -> ASUS 2M stall, but the helper is also a peripheral of the caller at 15 ms ("Relay dual-role scheduling"); not separable without a snoop. A stall retry cannot fit: the ASUS keeps the dead link 7-9 s. | Experimental `relayed_hop_1m` (off) to separate the two; helper memory lookup by recognised contact. |
| M31 helper to ASUS or A15s callees ("no route") | `relayed_setup: role=helper result=failed reason=no route` is FAIL 1 for any I/O error while dialing the next hop: two supervision timeouts (0x08) on the M31 -> ASUS child (dual-role starvation, as in "Relay dual-role scheduling"), and once a CoC connect that failed after 341 ms on a fresh ACL. No reach entry was missing: the helper had admitted the SETUP. | Exynos dual-role scheduling (radio limit) and one fast connect failure. | `relayed_hop: event=failed \| stage=...` names the stage; `relayed_hop_retry` repeats a fast connect failure once. |
| Call ended at 18 s, "Unexpected secure record sequence 13; expected 12" | The M31's Bluetooth stack logged `reassemble_and_dispatch Dropping L2CAP packet with invalid length (65281)` 1.5 s before; the only such line in the run. | Stack-level loss of one whole CoC record on the A15s -> M31 hop; the sequence check did its job. | None (a skipped sequence must not be accepted). |
| A15s as relay: records lost or corrupted mid-call (4 of 4 chains) | Every error was read on the hop the A15s transmits to its parent while it is also central to its child: "sequence 13; expected 12" (M31), "Invalid secure record size 655678673" (ASUS), "decrypt failed ... sequence=68 ciphertextBytes=1843" (ASUS relay), "sequence 3; expected 4" (ASUS). No receiving phone saw an error on a hop the S24, M31 or ASUS transmitted, and the A15s never saw one on what it received. The forwarding code is the same on every relay and keeps one writer per hop; a full-duplex JVM run of it over 245-byte SDUs and random short reads delivers every record once, in order. The M31 stack logged `Dropping L2CAP packet with invalid length (65281)` 1.5 s before its error. | Below the app on the A15s (MediaTek, Android 10) with two LE links: a lost SDU explains the gap, the desync and the decrypt failure; a repeated sequence needs a duplicated PDU (link layer). AOSP 46bd51ed (Android 13) fixed CoC data loss on busy sockets for stacks before it. The 2026-10-16 Xiaomi (MediaTek) relay failed the same way. | None in the app (the checks must fail closed). Experimental `coc_coalesce_sdus` (fewer queued SDUs on Android <= 11) is the cheap A/B on the A15s relay; candidate iteration lets the next call pick another helper. |
| GATT S24 -> ASUS -> M31 ended at 22 s | The M31's notifications to its ASUS parent failed (status 257) 5.4 s into media, the ASUS writes timed out 15 s in. | Link-level stall of the relay's GATT child hop; in-call link renewal covers direct GATT calls only. | None now. |
| Relayed video 352x288/15/100 kbps, callee saw "peer stopped responding" | The call ran its 90 s; the caller's hang-up queued behind 9.3 s of late media (callee `lateMaxMs=9323`) after the helper's child hop moved to 1M at 112 kbps, and the helper closed both hops 0.4 s after the caller left. | Offered load above one relay radio's capacity under the no-loss policy. | None now (`relayed_call_measured_cap` exists). |
| A15s caller via M31, "no route" in 1.5 s | The M31 logged no SETUP at all. | The caller's helper was another phone; the dial line did not name it. | `relayed_route: role=caller \| helper=...` names the selected helper ID. |

**Follow-up: the same loss on an M31 helper (2026-10-28).** A relayed video call A15s -> M31 -> ASUS over LE CoC ended at
76 s when the ASUS's hop reader failed the tag of one record: the header was intact (magic, type, exact next sequence,
legal size) and the body did not authenticate. The record came from the M31's own hop writer (it re-seals each record it
forwards), the M31 had authenticated every record it read from the A15s, no rekey, link renewal or reordering was involved,
and the ASUS's last good record arrived 68 ms earlier. So bytes were lost or changed below the app on the M31 -> ASUS hop.
The M31 runs Android 12, so the same upstream data loss (46bd51ed, before Android 13: stale poll entries while several
sockets are busy, data dropped for a congested channel) is the leading candidate; it is not proven (no HCI snoop, and the
M31's stack does not log congestion). Across the relayed-call runs every LE CoC hop-integrity failure (tag failures,
skipped sequences, damaged headers) had a forwarding phone on Android 10-12 as the transmitter (M31 three times, A15s the
rest), none was on a direct link, and the ASUS (Android 11) relayed 17 CoC calls without one. The GATT failures through
MediaTek helpers are a different cause (single bytes changed in queued notifications) and the 2026-10-16 Xiaomi relay runs
on Android 15.

- *Fail closed stays.* The tag is verified before any plaintext is released, the sequence does not advance, the call ends
  without a crash and the error says nothing a radio observer does not already see. A failed record ends the link because
  `BMS1` requires the exact next sequence (PROTOCOL "Hop and endpoint records"). Dropping only the failed record would not
  have saved this call anyway: a lost SDU shifts the stream, so the next header is misaligned.
- *Diagnostics (always on, no behaviour change).* A failed hop record logs `hop_integrity` with its type, sequence and size
  and whether the next 17 bytes, if already available, form a valid header with the next sequence (`valid_n1`: a byte
  changed in place; `invalid`: the stream shifted, a lost SDU). Each relay direction logs its read and forwarded counts and
  its hop writer's sequence when it stops. The type, sequence and size left the user-visible message.
- *Experiment (off).* `test_relay_coc_coalesce` (SWITCHES) makes forwarding CoC writers on Android 12 and older use SDUs of
  up to 8,000 bytes instead of 245-byte single-PDU SDUs, so the SDU-counted queue reaches its congestion quota less often.
  The registry proposal `pre-android-13-coc-congested-write-loss` scopes it (low confidence, off-switch). Promotion needs
  an A/B with the M31 and the A15s as CoC helpers in both directions and an HCI snoop on helper and receiver.
- *Design question (not started): authenticated hop resynchronisation.* On a call or forwarding hop, after the call is
  active, a reader that meets a tag or header failure would skip forward at most one maximum record to the next `BMS1`
  header whose sequence is above the last accepted one (within a small window) and whose tag verifies, accept it with a
  gap, and fail closed after a small failure budget (in the spirit of the DTLS 1.3 integrity limits); handshake and relay
  setup records never resynchronise, and a video gap at the endpoint asks for a keyframe. Replay and reordering stay
  impossible, but a radio attacker who can inject packets gains selective deletion of hop records without ending the
  link, which PROTOCOL now rules out for hops (a relay already has that power). This changes PROTOCOL's hop rules, so it
  needs a design record, the wire rules and a dual review before any code; the per-chunk integrity and re-request planned
  for GATT is the alternative.
