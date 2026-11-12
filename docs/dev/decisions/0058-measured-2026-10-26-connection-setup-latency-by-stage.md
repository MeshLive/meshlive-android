# Measured 2026-10-26: connection setup latency by stage

Every saved phone log was read offline (12,537 logs; all builds; calls are measured on the caller unless stated). Medians / p90 in ms. Clocks of different phones are never compared: every stage is a difference on one phone.

| Flow | Stage | n | Median | p90 |
| --- | --- | --- | --- | --- |
| Direct call, GATT | dial waits for a sighting newer than the tap | 2238 | 160 | 591 |
| | tap to GATT connect request | 1076 | 17 | 59 |
| | LL connect (request to connected) | 1074 | 497 | 1124 |
| | MTU wait (the stack's own database walk; our MTU request is queued behind it) | 966 | 596 | 1342 |
| | discovery and CCCD after the MTU | 963 | 37 | 88 |
| | hop handshake (BMH2/BMH3, `hopAuthMs`) | 722 | 180 | 255 |
| | hop authenticated to end-to-end handshake done | 104 | 166 | 190 |
| | end-to-end done to OUTGOING_RINGING (invite round trip) | 104 | 113 | 133 |
| | **tap to ring** (after the sighting wait) | 1013 | 1836 | 3485 |
| | accept to ACTIVE, caller / callee | 981 / 3133 | 481 / 593 | 779 / 983 |
| | ACTIVE to first remote surface, caller / callee | 644 / 1961 | 524 / 640 | 1454 / 1874 |
| | calls needing a second GATT attempt (setup stall) | 154 of 1076 | tap to ring 3573 | 10800 |
| Direct call, LE CoC | tap to ring | 16 | 1427 | 2506 |
| | control GATT connected to MTU (the walk) | 16 | 369 | 1481 |
| | CoC connect | 16 | 49 | 70 |
| | fresh-ACL wait (Android 12/13 protection) | 24 | 1038 | 1085 |
| Relayed call (1 relay) | tap to relayed READY | 67 | 5432 | 9761 |
| | hop authenticated to READY | 64 | 1596 | 5232 |
| | helper: SETUP accepted to READY | 69 | 1509 | 5049 |
| | **tap to ring** | 66 | 6119 | 10847 |
| Stream join, direct (viewer) | selection to viewer advert | 2233 | 143 | 496 |
| | advert to connected (source sighting and LL connect) | 2233 | 496 | 1637 |
| | connected to MTU (the source's walk of the viewer) | 2230 | 654 | 1353 |
| | MTU to authenticated (both handshakes) | 2205 | 376 | 541 |
| | authenticated to stream header (source media and keyframe) | 2891 | 402 | 1135 |
| | header to first surface | 2967 | 161 | 1413 |
| | **selection to first surface** | 2967 | 2938 | 4749 |
| Stream join through 1 relay | advert to connected | 478 | 1054 | 4254 |
| | authenticated to stream header (keyframe from upstream) | 557 | 1127 | 3484 |
| | **selection to first surface** | 581 | 4444 | 9726 |
| Stream join through 2 relays | selection to first surface | 10 | 6814 | 10974 |

Two findings change where to look:
- The call's ACTIVE phase and first remote picture wait for the UI thread. The peer's stream header reaches the endpoint thread a few tens of ms after the accept. The UI handles it a median 584 ms later (p90 988 ms, 4475 calls), because the UI thread is starting the local microphone ("Starting two-way" to "Microphone audio" median 648 ms). The camera starts on its own thread (median 54 ms) and is not the cost.
- The GATT "MTU wait" is the platform's discovery of the peer's whole database. It runs at the creation interval until our priority request applies, and our own `discoverServices` afterwards takes 6 ms. Its median per caller and peer pair ranges from 174 ms to 1428 ms; peers with large databases (Samsung) take about 1.3-1.4 s. Only Design GD (a stable database, so the stack's hash cache hits) or Design CD (no GATT client before CoC) removes it.

Gaps that no saved line measures, now logged by DEBUG builds:
- `call_ui_lag event=remote_header|phase_*` (UI-thread wait)
- `key_frame_request honored= | sinceLastMs= | intervalMs=` (the source's recovery back-off at a join)
- `relay_child_join event=first_media waitMs=` (a forwarder's new child waiting for upstream media)

Still unlogged:
- the LL procedures inside the connect: the HCI create-connection to connection-complete time and the advertising events missed. These need the HCI snoop.
- the individual BMH2/BMH3 messages.
- Windows, iPhone and Mac peers: no saved stage lines.

**Ideas for the stages that Designs GD and CD do not touch** (2026-10-27): the LL connect (median 0.5 s, p90 1.1 s direct) and the relay hop connect (3-3.6 s in the relay examples). None needs a wire change. They are ordered by expected value:
1. **The callee's own radio time.** The callee's list scanners run at 100% duty while the screen is on. The HCI profile shows the caller getting Connection Complete while the callee never received the CONNECT_IND (the fast 0x3E class, about 1 call in 10). A/B the existing switches `test_reachable_scan_yield` (scans go balanced after 8 s) and `test_fast_0x3e_dials` (jittered redials; built). Metric: the HCI create-connection to complete time and 0x3E counts per pair.
2. **The relay hop's held link.** A helper that accepted the caller's hop at HIGH priority (7.5-15 ms interval) leaves its controller little time to scan for its onward connection. Proposal `test_relayed_defer_priority` (not built): the caller requests HIGH on the first hop only after READY. Until then the hop carries only the handshake and SETUP, so the helper's initiator gets most of the air time. Measure the helper's accepted-to-onward-connected time against the switch off. The same applies to a forwarder admitting a child while it holds its parent link.
3. **Callee advert interval while reachable.** The call listener advertises at 100 ms for 10 s, then at 250 ms. Proposal `test_reachable_adv_low_latency` (not built): keep 100 ms while the screen is on and the app is in the foreground. Cost: advertising energy, about 2.5 times the events, which is small next to a 100%-duty scan. Expected: the advert wait drops from about 125 ms to 50 ms and fewer CONNECT_INDs are missed. Measure crowd impact with 6 phones.
4. **No other app scan during a dial.** The relayed helper scan paused only 0.7 s after the tap (`relayed_helper: state=PAUSED`). Pause every app scan before `connectGatt`, not after.
5. **Connecting while the user is still choosing.** When a contact's call sheet opens, open the GATT client at once: no handshake, bounded to 10 s, cancelled if the user does not tap. The LL connect and the walk then overlap the user's decision. Costs:
   - It holds a GATT slot on the callee.
   - The callee sees an unauthenticated connection from a rotating address.
   - Battery.

   This needs a design note: a privacy review, and a crowd-robustness review of slot use.
6. **Connection parameters before the walk.** No public API sets the initial interval or the Data Length; the conn update requested at connection lands about 0.4 s later (HCI: sent at 180 ms, 7.5 ms only at 594 ms). Nothing app-side beyond requesting HIGH at once, which is already done. GD/CD remove the walk instead.

**Bonded test phones.** Bonded pairs pay a Service Changed rediscovery that most unbonded users never see, so every setup A/B records the bond state (`bond_state_changed` / `Encrypt the link if device is bonded` in the stack log). Two arms are needed:
- **Unbonded (primary):** the phone owner removes the bond in Settings > Bluetooth before the run; no tool exists for this yet.
- **Bonded:** for Classic-without-pairing and existing users.

Required pairs that are bonded today give setup numbers that are about 0.7 s pessimistic.
