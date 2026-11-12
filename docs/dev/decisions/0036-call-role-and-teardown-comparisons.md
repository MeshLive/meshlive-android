# Call role and teardown comparisons

Three-phone call runs (2026-10-01) kept profiles, identities, failures and the native crash buffer; normal selections, family limits and security were preserved.

| M31 -> S22 opaque relay -> ASUS unless stated | Measured motion / terminal result |
| --- | --- |
| CoC before camera/scan fix | M31 no video; reverse 5-6 FPS/late audio/freezes: FAIL |
| Classic before incoming-scan fix | Both 15 FPS, but ~19% visible frozen, gaps 1.27 s: FAIL |
| Classic after scan fix | 125 s, ASUS/M31 visible 1845/1856, 0 freezes/decoder/late audio, 66.7/66.8 ms; mute works/clean End/re-advertise < 1 s; short regression |
| CoC after camera/scan, before control retention | ~3 m, 2713/2740 visible, 15 FPS/0 freezes/audio/decoder; End EOF/separate 0x22 setup loss: FAIL |
| GATT after camera/scan | ~3.5 m, 3107/3057 visible, 0 audio/decoder; ASUS 0 freezes/M31 three 751 ms (0.36%), p95 ~134 ms: timing FAIL; clean End |
| M31 CoC retry | OS Bluetooth SIGSEGV libbluetooth.so/l2c_csm_indicate_connection_open before media; no proven workaround |
| Reverse ASUS -> S22 -> M31, before retention | Injected recovery then M31 had 321 freezes/55.42% frozen/late audio/drop cycles; 400 ms drain insufficient, reset: FAIL |
| Same reverse, retained control | ~5 m, M31 4532 visible/0 freezes, ASUS 4495/one 1069 ms injected gap (0.36%), 0 audio/decoder; Peer End, availability 600/772 ms |
| Direct M31/S22 CoC | VP9 640x360/15/150 each, ~3 m 40 s, 3300/3312 visible, 0 freezes/decoder; S22 startup 16 audio losses then unchanged/M31 0; clean End < 1 s |
| Direct S22/M31 GATT | Same VP9, ~13 m 20 s, 11994/11987 visible, 0 freezes/audio/decoder; S22 66.7/M31 p95-max 83.4/99.4 ms; clean End ~2 s; optical unmeasured |

Earlier warm S24/M31 broadcast 640x360/15/150 CBR + Opus:

| Transport | Functional result |
| --- | --- |
| GATT | 2M/MTU 517, both handshakes, source 0 drops, >=450 renders/15 FPS, 0 freezes/A/V/decoder; source loss -> recovery |
| CoC | 2M/MPS 65,535, 291/297 rendered/received, 15 FPS, 0 freezes/audio/decoder, source 0 drops |
| Classic | Bonded identity, >400 renders/~15 FPS, 0 A/V/decoder/source drops; one 334 ms freeze, functional only |

Two-way calls worked on all three; End/Hang-up returns availability. Atomic admission advanced M31 receive 1 -> 65 -> 126 -> >2000/decoder 0. On an Android 15 iQOO phone a BLUETOOTH_SCAN SecurityException led to an actual permission recheck at the discovery boundary; the final Samsung matrix does not prove iQOO media.

## Classic identity boundary

A rotating privacy address with an SDP/page timeout and no stable bond is a Classic identity boundary, not a decoder cause. Classic RFCOMM has to page a BR/EDR address, but discovery is BLE: phones advertise with resolvable private addresses that only a bond's identity-resolving key can resolve, and Android hides a phone's own address from apps. Without a mutual bond a phone therefore cannot learn the address to page, and Classic setup S24 -> M31 paged unresolved candidates until the broadcaster's 12 s timeout. Android's secure RFCOMM sockets also require an authenticated link key on both phones, so a stale one-sided bond fails too. The experimental bond-free Classic mode (default off, Advanced setting; see the register in [architecture.md](../architecture.md)) addresses both: it uses insecure RFCOMM with the app's own authentication and ordered encryption, remembers the address at which an authenticated identity was reached, and uses a Classic inquiry for first contact (which finds only a discoverable phone).

Historical 2026-08-27 S22/S24 direct two-way matrix used BMC1; current strict BMC2 adds a validated relay hop. Native AV1/hardware VP9 directions independent:

| Transport | Historical cap / result |
| --- | --- |
| Classic | 250 kbps per direction, both A/V, bounded queues, decoder 0, mute/video off/on/flip/hangup |
| CoC | 200 per direction, 2M/MTU 517/MPS 65,535, 30/15 FPS, 50 audio packets/s, decoder 0 |
| GATT | 180 per direction, 2M/MTU 517, 30/15 FPS, bounded queues, decoder 0 |

GATT admission requires the notification channel, ignores unrelated system probes and rejects a second active channel. Fixed Decline/Cancel returns availability without stale frames; a 15 s Home test advanced both directions under connectedDevice/camera/microphone service. Screen lock/process death/endurance are separate.
