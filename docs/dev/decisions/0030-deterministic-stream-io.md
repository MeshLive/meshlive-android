# Deterministic stream I/O

Same 150-frame 1280x720/15 VP9 replay at about 414 kbps secure-wire; accepted Classic runs about 15 FPS, empty queues, drops 0:

| RFCOMM policy | Trials | Sender frame-call p95 ms | Flush p95 ms | Scheduled playout p95 ms |
| --- | ---: | ---: | ---: | ---: |
| Per-frame, 32 KiB | 3 | 3.8 mean | 1.0 mean | 286 mean |
| 5 ms deadline, 32 KiB | 3 | 2.7 mean | 0.4 mean | 226 mean |
| 10 ms deadline, 32 KiB | 1 | 2.9 | 0.6 | 258 |
| 16 KiB or 50 ms | 1 | 2.8 | 0.9 | 268 |
| 32 KiB or 50 ms | 1 | 3.3 | 0.7 | 292 |

Fixed per-frame, 4/16/32/64 KiB buffers gave 3.7/3.6/3.5/3.7 ms, one trial each, no useful size trend. Event-driven 5 ms replaced a 200 wakeups/s loop; final frame-call/flush p95=1.2/0.3 ms, 142/143 renders at 14.8 FPS, drops 0. Production 32 KiB/event-driven 5 ms retained. CoC 207 kbps replay, three trials each: 5 ms versus per-frame sender p95=3.2/4.3 ms, scheduled playout 308/333 ms, queues/drops 0; two setup attempts never opened the data channel and remain failures. GATT 699 kbps wire: three 5 ms trials complete, two of three per-frame complete, one BAD_DECRYPT; no causal flush claim. Final camera GATT 141/142 at 15.1 FPS, empty queues/drops 0. Two-hop CoC 4 KiB exception has separate evidence below.
