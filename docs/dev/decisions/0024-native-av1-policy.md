# Native AV1 policy

| Profile | Speed / threads | Encode FPS | p95 / p99 | Caller CPU | VMAF | Decision |
| --- | --- | ---: | ---: | ---: | ---: | --- |
| 640x360/20/400 | 7 / 2 | 94.18 | 18.1 / 23.4 ms | 222% | 97.598 | Best measured 20 FPS implementation; same bitstream/VMAF as 3 threads with about 11% less CPU. |
| 640x360/20/400 | 7 / 3 | 94.77 | 17.6 / 26.8 ms | 250% | Not reported | Rejected: no quality gain over 2 threads. |
| 640x360/20/400 | 7 / 4 | 96.91 | 17.4 / 24.8 ms | 293% | Not reported | Rejected: extra CPU and -0.169 VMAF. |
| 640x360/24/400 | 7 / 3 | 99.10 | 16.8 / 27.8 ms | 250% class | 96.704 | Valid motion-first mesh profile; physical app uses 2 threads after the thread result. |
| 640x360/30/500 | 7 / 3 | 95.87 | 17.4 / 34.0 ms | 252% | 96.817 | Good quality but p99 slightly crosses 33.3 ms. |
| 640x360/30/500 | 8 / 2 | 92.24 | 18.2 / 23.0 ms | 239% | 95.614 | Retained safe speed/thread policy for this mode. |
| 720x480/20/550 | 7 / 3 | 74.12 | 24.7 / 36.2 ms | 263% | 97.261 | Retained through 20 FPS. |
| 720x480/20/550 | 8 / 3 | 87.14 | 19.9 / 27.8 ms | 258% | 95.737 | Rejected: speed 7 is safely real-time and +1.524 VMAF. |
| 720x480/24/700 | 7 / 3 | 68.86 | 27.5 / 38.2 ms | 259% | 97.479 | Highest offline quality but limited hot-phone margin. |
| 720x480/24/700 | 8 / 3 | 84.00 | 20.5 / 33.6 ms | 259% | 96.285 | Retained direct profile: about -1.19 VMAF for much safer live margin. |
| 720x480/24/700 | 8 / 4 | 83.53 | 21.1 / 30.0 ms | 300% | 95.926 | Rejected: fourth thread raises CPU and lowers quality. |
| 720x480/30/900 | 7 / 3 | 71.52 | 25.2 / 38.3 ms | 262% | 98.037 | Rejected for live: p99 exceeds the 33.3 ms deadline. |
| 720x480/30/900 | 8 / 3 | 80.80 | 18.5 / 26.9 ms | 258% | 97.303 | Valid, but no useful quality advantage over the faster policy. |
| 720x480/30/900 | 9 / 3 | 97.93 | 16.6 / 22.5 ms | 260% | 97.405 | Retained maximum-direct policy; much more hot-phone margin than speed 7. |
| 720x480/30/900 | 9 / 4 | 86.31 | 17.8 / 26.4 ms | 308% | 97.173 | Rejected: fourth thread is slower, hotter and lower quality. |

The retained automatic native policy is therefore:

- through 640x360/24: cpu-used 7, 2 threads;
- 640x360 above 24 FPS: cpu-used 8, 2 threads;
- 720x480 through 20 FPS: cpu-used 7, 3 threads;
- 720x480 at 24 FPS: cpu-used 8, 3 threads;
- 720x480 at 30 FPS: cpu-used 9, 3 threads.

Larger modes stay conservative until they receive their own exact-source and sustained-hot tests. (A later speed governor, described in the change ledger, may try slower presets within the frame budget.)
