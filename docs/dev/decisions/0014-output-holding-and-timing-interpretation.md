# Output holding and timing interpretation

An isolated OFF/ON/ON/OFF comparison (S24 -> S22, 2026-10-03) changed only DEBUG holding, with the actual media intervals of each run recorded. ON A discarded 46 held outputs across 27 generations; ON B discarded 2 across 3. Different failure stages prevent a general cause claim; holding remains OFF.

| Run / holding | Median kbps | Source / raw / decoder / audio losses | Freezes | Worst rolling surface p95 / p99 ms | Startup ms |
| --- | ---: | --- | ---: | --- | ---: |
| OFF A | 1095 | 0 /2 /0 /0 | 0 | 50.5 /67.9 | 5431.9 |
| ON A | 1105 | 0 /0 /1049 /0 | 17 | 1656.9 /4165.9 | 4600.0 |
| ON B | 1093 | 173 /0 /0 /452 | 4 | 101.6 /250.5 | 3333.3 |
| OFF B | 1093 | 0 /0 /0 /0 | 0 | 50.3 /51.8 | 5062.9 |

| Original evidence | Retained finding |
| --- | --- |
| Same-profile observer ABBA | Four same-profile recorded-camera-pan runs: source/raw/audio/decoder loss 0, freezes 0, complete active-source fidelity about 97/0.983/43 dB PASS; all timing FAIL. Observer ON RMS notification interval error 7.318/7.090 ms; pooled p95=50.143/50.302 ms versus 33.333 ms period. |
| Motion timing findings | Actual callback intervals/RMS jitter, signed callback-target lag, phase/boundary gaps and unknown identities remain separate from reported codec/Texture timestamps. Focused timing regressions protect this distinction. No physical-screen/latency proof. |
| Earlier holding controls | Earlier hold OFF/ON/ON/OFF: source losses 950/1089/847/1025, audio 1329/1421/1637/1274, freezes 8/13/13/6 despite 30 FPS/about 1100 kbps and raw misses 0. Older-source and both-older runs also fail: source 1268/1156, audio 1623/1687, freezes 10/7. Both older runs encoded 1917/received 768/absent 1149. Failures on the old APK prevent attributing this to the newer holding build. |
| Samsung transport forensics and scheduler findings | Allowed Classic packet masks exclude the 3 Mbps family, but do not measure throughput/retransmissions. Sleeping threads alone do not prove RF, lock or socket cause. Request/completion, PID/session identity and exact pipeline stages remain required. |
| Active S22 Perfetto evidence | Real 30 s receive window: main draw max 9.940 ms; Bluetooth-read sleeps 326.443/279.815/272.303 ms woken by bt_main_thread. No demonstrated long UI block; upstream starvation's initiating cause unresolved. A later trace retained 22.902583 s of 30 in 32 MiB, overwrote 10,997,760 bytes; 685 app frame timelines on-time, main draw max 5.848 ms/runnable wait 4.459 ms. Full 30 s trace and video-pixel cadence UNMEASURED. |
