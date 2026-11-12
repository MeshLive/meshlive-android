# Historical conservative lifecycle and recovery

Hardware VP9 640x360/15/150 CBR, Opus 32, Balanced 250, thermal OFF isolates lifecycle, not capacity.

| Transport / chain | Final-child loss | Middle-loss -> source recovery | Historical scope |
| --- | --- | --- | --- |
| GATT S24 -> S22 -> M31 | 2 s heartbeat/6 s stale removes at 6.36 s; middle 15 FPS/0 A/V/decoder/freezes | 2.4-2.9 s, 15 FPS/decoder 0 | This direction |
| CoC S24 -> M31 -> S22 | Control EOF 85 ms; middle 15 FPS/0 A/V/freezes | 2.27 s, 15 FPS/A/V/decoder 0 | This direction |
| Classic S24 -> S22 -> M31 | Balanced advertiser/scanner fixed child-loss stalls; middle 15 FPS, maximum interval < 250 ms, 0 A/V/decoder | 4.65 s, 15 FPS/A/V 0 | This direction |

Opposite CoC S24 -> S22 -> M31 blocked a second-hop write **9.76 s**, filled the 32-record queue and withdrew despite 2M/MPS 65,535/middle 15 FPS. Recovery has no search expiry; End does not reconnect. Discovery 22 bytes; recovery reuses six profile bytes to name the failed node; source offer 1.45 s after loss, remaining gap = CoC/authentication/keyframe/Balanced. Cached inactive-parent request skipped. Signed prefix, not rotating address, keys admitted peers. Free source slots admit authenticated recovery immediately; full slots protected. Per-join timing preserves cumulative continuity.

Later Classic 150 full fault-to-surface 5194 ms versus 4647.7 ms from later selection: strict recovery FAIL. Authenticated M31 parent S22/replacement S24 prove fallback. Revised attribution retains one real pre-transition freeze and a recovery-overlapping gap without erasing counters.

| Normal CoC one-child gate | Historical measured result |
| --- | --- |
| Late join | Middle 14.9-15 FPS/child 15; A/V +119 to +150 ms, queues 0-1/video+transport drops 0 |
| Child isolation | Middle 15 FPS persists; stable hop-specific slot returns; one earlier small teardown gap |
| Rejoin | 3.19 s, A/V +120.1 ms, 15 FPS, 0 audio/decoder/freezes |
| Pause/Resume | Links retained; middle 66.7/child 66.8 ms; middle 6 audio discards around resume/child 0 |
| End | Child ack -> middle -> source; accepted run 0 freeze events |
| Source isolation | After hop-token fix, source never dials final directly; BFG1/BMM1 retain original identity |

Hop tokens prevented duplicate answers; cached header/config + fresh upstream keyframe replaced old-keyframe late join with -3.56 s A/V/decoder error. Queue close wakes the writer to republish one slot. Normal middle has one child; group/opaque diagnostics are separate.
