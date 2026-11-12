# Source scanning and GATT liveness comparisons

An isolated scan ABBA (four runs, 2026-10-03) fixed ASUS native AV1 640x480/15/1000 CBR -> ordinary M31 leaf, pan + microphones, Opus 32, Balanced 250 ms, thermal OFF and default liveness. Only source search while admission was full changed. Heartbeat receipts 46/48/42/46 and maximum gaps 2027/2026/2019/2023 ms show no false 6 s disconnect. Pause B missed the 30 s first-surface wait before pause could act. All four failed; pause remains experimental pending middle/recovery/Camera2 preservation.

| Search / run | Actual kbps | Source loss / freezes / audio loss | Complete slots / received; held / terminal | VMAF / SSIM / PSNR dB |
| --- | ---: | --- | --- | --- |
| Normal A | 1002 | 342 /13 /246 | 1368 /1001; 367 /25 | 75.057100 /0.927047 /22.087648 |
| Pause A | 1003.5 | 27 /1 /16 | 1417 /1383; 34 /7 | 97.856398 /0.986541 /32.381456 |
| Pause B | 1003 | 27 /1 /17 | 1250 /1216; 34 /7 | 97.263075 /0.985365 /33.763580 |
| Normal B | 998 | 343 /12 /198 | 1372 /1018; 354 /10; one input hole | 75.658156 /0.928807 /22.130469 |

A heartbeat OFF/ON comparison at 1000 kbps emitted 1001/1002, lost 341/350 source and 180/212 audio frames, and froze 13/12 times. Full-source slots 1371/1374, received 1002/1016 and held 369/358 scored 75.407718/0.930562/22.812916 versus 75.795273/0.929948/22.366589: both fail. ON received 46 heartbeats with maximum gap 2024 ms and no false disconnect. At 500, ON emitted 501, received 46 heartbeats/max 2029 ms, losses/freezes 0, 1375/1374 slots/received plus one encoder tail scored 98.906669/0.985739/44.286051, a scoped fidelity pass; p95=83.8 ms still failed. Callback-after-API p95 ~49-50 ms, API generally < 2 ms and wakeup < 0.5 ms do not establish receiver delivery or RF/controller/OEM/Binder cause. Writer-monitor/between-operation waits remain unresolved; multiple in-flight writes caused busy rejection.
