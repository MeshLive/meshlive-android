# Complete-source role and lifecycle controls

These comparisons (build 17732675, 2026-10-03) preserve actual load, missing slots and authenticated parents, including failed requested chains.

| Original evidence | Complete result |
| --- | --- |
| Xiaomi source 500 | Actual 501.5 kbps, 1374 slots/1373 rx, one transport terminal tail, no interior holes, loss/freezes 0; 99.303901/0.985725/44.421919 scoped picture PASS, p95=84.0 ms FAIL. Actual offered AV1 encoding and VP9 decoding are distinct capabilities. |
| Xiaomi middle 500 | After child join: source 136 drops; Xiaomi 8 freezes/40 audio/31 forward drops, p95/p99=177.6/489.7 ms; M31 five freezes/185 audio. Guard withdraws; three authenticated direct-source probes rejected. The stricter parent gate retains probes even without a later Receiving status; FAIL. |
| GATT actual 150 chain | M31 -> ASUS -> Xiaomi hardware VP9 640x360/15/150 VBR: actual 150, parents/source correct 90 s, source/raw/audio/decoder loss/freezes 0, ASUS forwarding drops 2. 1455 slots; ASUS 1454 rx, 75.938763/0.948341/35.169191; Xiaomi 1366 rx, 88 initial black + one tail, 71.132523/0.923284/23.536754. No interior holes; picture FAIL. |
| Classic actual 500 chain | S24 -> S22 -> M31, actual 504, parents/source correct. S22 all 1511 rx, p95=99.5/two forward drops, 98.180147/0.979798/42.392126 narrowly SSIM FAIL. M31 1377/1511 rx, 89.723430/0.940402/21.897214, p95/p99=117.2/201, minimum visible 14.0233, one freeze/audio drop, start 6522.1 ms: FAIL. |
| Live profile default lifecycle, raw full source | Live actual 1108, loss/freezes 0, Pause/Resume/rejoin works, but p95/p99=50.8/68, start/rejoin 5554/5998 ms FAIL. Raw 1838 slots/1837 encoded + rx, one interior input hole, no transport holes, 97.153880/0.983231/42.901578 picture PASS; one raw miss/p95-p99=50.8/67.1 FAIL. |

Earlier high-load failures remain: build 7051 live 133 source/2445 audio/five freezes, p95/p99=118.0/234.6 ms; scan-paused 0 source/audio/one freeze, 84.6/167.2 ms; restored older 362 live 222 source/1483 audio/12 freezes at 1092 kbps; 7359 live 572 source/3352 audio/14 freezes, 116.9/252 ms, start 5468 ms. Faster YUV still left ASUS -> Xiaomi 500 with two freezes totalling 910.3 ms, 11 audio, p95/p99=78.7/122.7 ms, min visible 7.91 FPS. Concurrent GATT 1000 chain emitted 998, lost 599 source and withdrew at middle p95=299.9 ms/18 audio. A 1000 request emitting 97 or VBR 1000 emitting 671.5 kbps is insufficient offered-load proof. Live GATT 1100 emitted 1044 but lost 1127 source/3705 audio.
