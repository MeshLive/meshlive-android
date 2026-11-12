# Native AV1 keyframe tradeoff

All cases: 720x480, 15 FPS, 400 kbps, cpu-used 7, 3 threads, 150 identical frames.

| GOP | Actual kbps | Keyframes | VMAF | SSIM | Encode p95 / p99 | Decision |
| ---: | ---: | ---: | ---: | ---: | ---: | --- |
| 2 s | 409.09 | 5 | 96.453 | 0.980533 | 20.2 / 33.3 ms | Rejected: frequent keyframes consume useful motion budget. |
| 5 s | 408.19 | 2 | 97.153 | 0.981645 | 23.1 / 29.7 ms | Retained: gains 0.70 VMAF with bounded join/recovery. |
| 10 s | 408.35 | 1 | 97.205 | 0.981703 | 22.7 / 32.7 ms | Rejected: only +0.052 VMAF over 5 s for twice the natural recovery wait. |

The product uses a normal 5-second interval. A joining or recovering receiver can still request
an immediate keyframe, so this does not force it to wait for the next periodic keyframe. (The later safety-keyframe interval for streams is described in the change ledger above.)
