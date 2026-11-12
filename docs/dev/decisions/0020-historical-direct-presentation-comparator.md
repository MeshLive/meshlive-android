# Historical direct presentation comparator

The 2026-08-25 S24 -> S22 Classic hardware VP9 720x480/30/1100 CBR + Opus source sustained 29.9-30.0 FPS, PTS p50/p95=33.3/33.4 ms, max 33.6, queues 0-1/drops 0. A 20 s replay isolated transport/decode scheduling. These old decoder shown/timestamp values are not current Texture or physical-pixel proof.

| Local playback | FPS | Ideal interval ms | Historical shown p50 / p95 / p99 / worst ms |
| --- | ---: | ---: | --- |
| Low 30 ms | 15 | 66.7 | 66.7 /130.1 /270.9 /367.0 |
| Low 30 ms | 24 | 41.7 | 41.7 /131.5 /200.6 /251.8 |
| Low 30 ms | 30 | 33.3 | 33.3 /42.6 /109.6 /177.9 |
| Balanced 250 ms | 15 | 66.7 | 66.7 /66.7 /151.2 /367.2 |
| Balanced 250 ms | 24 | 41.7 | 41.7 /41.7 /142.7 /247.7 |
| Balanced 250 ms | 30 | 33.3 | 33.3 /33.3 /33.3 /33.3 |
| High 500 ms | 15 | 66.7 | 66.7 /66.7 /66.7 /66.7 |
| High 500 ms | 24 | 41.7 | 41.7 /41.7 /41.7 /41.7 |
| High 500 ms | 30 | 33.3 | 33.3 /33.3 /33.3 /33.3 |

Final High 15/24/30 rendered 283/283, 293/293, 350/350, drops 0. Earlier 400/500 ms trials collided with the 500 ms schedule-ahead ceiling; a bounded 2 s ceiling removed that collision while the target stayed 500 ms. This justifies the scheduling fix, not extra buffering as a motion-quality fix.
