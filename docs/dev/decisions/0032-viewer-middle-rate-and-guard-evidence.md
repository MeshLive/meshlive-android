# Viewer-middle rate and guard evidence

S24 -> S22 (local view + forward) -> M31, hardware VP9 640x360/15, Opus 32, Balanced 250. The numerical observations are recorded below; private video pixels are retired.

| Transport / allocation / selected kbps | Historical scoped result | Measured boundary |
| --- | --- | --- |
| CoC CBR 375, 4 KiB | Short PASS | All 15 FPS, final A/V/freezes 0, relay 433-438 kbps, queue 0-1/32, write p95 ~3 ms |
| CoC VBR 375 | FAIL | Writes 0.7-1.3 s, queue 26/32, 109 drops, final ~11 FPS, >2000 audio |
| CoC CBR 400 /450 | FAIL | 400 source saturation/both ~4 FPS; 450 final ~11 FPS; 4 KiB passed where 8/16/32 degraded |
| GATT CBR 400 | 20 s PASS | Both 15 FPS, loss/freezes 0, source wire 458, relay write p95=25-27 ms, queue 1-2/32 |
| GATT VBR 400 /CBR 450 | FAIL | VBR<8 FPS/source saturation; 450 source drops, middle ~11 FPS/final disconnect |
| Classic CBR 400 | Short PASS | All 15 FPS, source 0 drops, relay 0-1/32, write p95=1.4-3.3 ms, final A/V/freezes 0 |
| Classic CBR 500 | Aggressive retry | One final-hop disconnect/rejoin, eight join relay drops; then 15 FPS/final A/V/freezes 0 |
| Classic CBR 600 | FAIL | Actual 600 -> final 13.7-14 FPS, 328 audio, two freezes/~785 ms |
| Classic VBR 400 | Interrupted | Both 15 FPS/audio 0, one 234 ms gap versus none in CBR |

CBR-FD was not offered or tested by S24 hardware. A short CBR advantage does not remove offered VBR or prove a universal 375/400 limit. Direct 1280x720/15/1000 CBR produced 974-1035 kbps, secure 1050-1059, 15 FPS/66.7 ms p50/p95, queues 0-1, drops 0; screen recording perturbed continuity. Chain 720x480/15/500 produced 510-527, source 0 drops, middle 15 FPS/0 A/V, relay wire 517, queue 0-2/32/one pre-keyframe join drop; M31 ~15 FPS/decoder 0 but 51 late audio and GOP-related visible gaps. Transport capacity only, not final smoothness.

M31 received 1280x720/15 prerecorded VP9 through S22 at 400 with 15 FPS/0 freezes, and live 720x480/15 + Opus directly over CoC 500 with 0 A/V/freezes. S22 direct was smooth; simultaneous live receive + forward raised middle p95 from 66.7 to 117-170 ms and burst-fed the child despite small queues/fast writes. Evidence supports a topology/controller scheduling boundary rather than model/PHY/decoder blame; initiating RF cause not established.

| Classic profile | Middle cadence | Child / policy |
| --- | --- | --- |
| 720x480/15/500 | Post-child p95=170.2; graceful-close repeat 121.7 ms, bounded queues | Bursty before detach; middle returns toward 66.7, source unchanged |
| 640x360/15/400 | 15 FPS, p50/p95=66.7/67.1, 0 A/V/freezes, relay 456 kbps, queue 0-1/32 | Child 14.9-15 FPS, p95 ~84 ms, 0 A/V/freezes; retained |

Guards compare fresh pre/post-child cadence at actual FPS, sustained new Opus loss and repeated overflow across keyframe clears. CoC 700 overload proved LOCAL_CAPACITY/ack/exact-route suppression/no loop; CoC 375/GATT 400 controls retained the child. GATT 600 withdrew at 26/32 after 44 drops; a depth-only reset defect was fixed. Pink/green recovery lasted beyond 2.27 s; pressure detection begins after the fresh-keyframe boundary, local cover clears after keyframe render, source request throttle 2.5 -> 1 s. Later healthy child/middle-loss runs were clean; direction-specific blocked CoC still FAIL.
