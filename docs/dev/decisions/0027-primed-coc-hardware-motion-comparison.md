# Primed CoC hardware motion comparison

Historical CoC observations: S24 c2.exynos.vp9.encoder -> S22 c2.qti.vp9.decoder, CBR, Opus 32, High 500, thermal reduction OFF, 12 s cues, thermal state Light/Moderate. Clock preflight had tracks 8.4 hours apart; normalized starting A/V delta 138-274 ms and a bounded 250 ms startup reserve removed the recovery loop without changing the 500 ms target. A 24 FPS preflight held 23.9 FPS, 41.7 ms p50/p95, drops 0.

| Requested geometry/FPS/kbps | Actual FPS / encoded kbps | Historical p50 / p95 ms | Loss/freeze / visual observation |
| --- | --- | --- | --- |
| 640x360/15/600 | 15.0 /591-622 | 66.7 /66.8 | 0; less fluid motion |
| 640x360/24/600 | 24.0 /571-606 | 41.7 /41.8 | 0; 360p balance |
| 640x360/30/300 | 30.0 /288-303 | 33.3 /33.3 | 0; most smear/blocks |
| 640x360/30/450 | 30.0 /448-452 | 33.3 /33.3 | 0; improved over 300 |
| 640x360/30/600 | 30.0 /594-607 | 33.3 /33.4 | 0; cleanest 360p30 in set |
| 720x480/15/600 | 15.0 /564-637 | 66.7 /66.7 | 0; detail/bitrate compromise |
| 1280x720/15/600 | 15.0 /586-615 | 66.7 /66.8 | 0; fast-pan blur remains |
| 720x480/24/700 | 24.0 /603-713 | 41.7 /41.9 | 0; secure-wire briefly 852 kbps, near ceiling |

Queues 0-1 and transport/audio/decoder drops 0 are timing evidence. Different hand paths are not aligned picture scores. This short direct matrix did not promote CoC or establish stable chain capacity.
