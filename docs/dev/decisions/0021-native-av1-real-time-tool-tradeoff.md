# Native AV1 real-time tool tradeoff

These comparisons used libaom from the main branch at commit `0006e96cdb595ff88950a2c664115335ca5517bf` (later than the 3.14.1 release tag `03087864`) with per-frame CSV, device and score data recorded at the time; the private IVF pixels have since been retired. The 2026-08-26 encoder was S22; receiver S24 used c2.exynos.av1.decoder. Within each offline resolution group, cases used identical I420 frames. VMAF is comparable only within that source-resolution group. Live cases used Classic with both encryption layers, Opus 32 and High 500 playback on already-warm phones, with thermal reduction disabled for isolation. These are bounded historical comparisons, not universal defaults or ceilings.

The custom encoder now has a WebRTC-derived real-time tool profile. It disables expensive AV1 tools
that the current WebRTC libaom encoder also disables for real-time communication: TPL, order hints,
palette, OBMC, warped/global/ref-frame motion vectors, several intra/inter prediction tools, trellis
quantization, restoration, rectangular transforms, TX64 and related search work. This is an encoder
speed/compression trade, not receiver buffering.

All cases: 720x480, 15 FPS, 400 kbps, 75 identical frames.

| cpu-used / threads | Tools | Encode FPS | p95 / p99 | Caller CPU | VMAF | Decision |
| --- | --- | ---: | ---: | ---: | ---: | --- |
| 6 / 4 | Off | 27.94 | 59.3 / 103.1 ms | 342% | 97.394 | Rejected: tail latency exceeds the 66.7 ms live deadline. |
| 6 / 4 | On | 35.17 | 45.2 / 54.0 ms | 342% | 97.068 | Tools save the tail for only 0.326 VMAF. |
| 6 / 3 | On | 31.99 | 57.6 / 63.6 ms | 279% | 97.266 | Rejected for live use: too little hot-phone margin. |
| 7 / 3 | On | 60.94 | 24.8 / 35.7 ms | 275% | 96.176 | Retained at 720x480 up to 20 FPS. |
| 7 / 4 | On | 63.21 | 25.4 / 37.0 ms | 322% | 95.884 | Rejected: fourth thread costs CPU and quality. |
| 8 / 4 | On | 71.08 | 23.5 / 37.1 ms | 329% | 94.312 | Rejected at 15 FPS: little tail gain for excess quality loss. |

The important result is not that cpu-used 7 is universally best. It is the measured point where this
phone gains large real-time and CPU headroom for about a one-point VMAF cost, preventing motion from
turning a nominally good average into missed live deadlines.
