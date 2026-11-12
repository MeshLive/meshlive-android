# Bounded payload and CoC priming

| Initial 2026-08-22 S24 -> S22 | Bounded Mbps samples | Central Mbps |
| --- | --- | ---: |
| Classic BR/EDR | 1.141-1.159 (3) | 1.149 |
| CoC 2M, unprimed | 0.221-0.254 (3) | 0.242 |
| GATT 2M/MTU 517 | 0.426-0.428 (2) | 0.427 |

Unprimed S22 -> S24 reproduced 0.263 Mbps with 16 KiB/four-packet window and 0.266 with 65,535-byte SDU despite 2M/MPS 65,535. Dumps showed set_max_data_length_for_lecoc disabled. A public same-ACL GATT MTU 517 exchange before CoC removed the plateau; the hidden requestMaximumTxDataLength was not used. Controller data length is not exposed, so link priming is a measured effect, not direct DLE observation.

| Primed CoC control | Sender / receiver Mbps |
| --- | --- |
| S22 -> S24, setup GATT retained | 0.801 /0.804 |
| S24 -> S22, setup GATT retained | 0.810 /0.811 |
| S22 -> S24, setup released after connect | 0.848 /0.851 |
| Same direction, DCK instead of HIGH | 0.813 /0.815 |

Constant about 64 KiB ACK horizon, 4/8/16/32/65.5 KiB SDUs yielded 0.819-0.851 Mbps; 16 KiB retained. Two/four primed CoCs aggregate 0.862/0.843 Mbps, without striping benefit. No-window-ACK reported a false 1.838 Mbps interim sender rate, then **33.4 s** to deliver 3.5 MB at 0.841 receiver-timed. Private calls retain CoC control until teardown according to their own lifecycle evidence.

| Bounded transport / observed mode | Payload Mbps |
| --- | ---: |
| Classic BR/EDR | 1.193 |
| CoC 2M, MTU 517 primed | 0.80-0.86 |
| CoC 1M, unprimed | 0.193-0.194 |
| CoC Coded S2 /S8 | 0.081 /0.040 |
| GATT client writes 2M, tuned | 0.74-0.86 |
| GATT notifications 2M | 0.776-0.781 receiver-timed |
| GATT 1M | 0.320 |
| GATT Coded S2 /S8 | 0.154 /0.047 |

Unprimed CoC 2M is about 32% faster than 1M; bounded GATT 2M about 37.5% faster than 1M. Primed 1M/Coded unmeasured. The headline 1.4 Mbps LE 2M application figure is not an Android guarantee.

| Actual concurrent run | Classic Mbps | LE Mbps | Sum Mbps |
| --- | ---: | ---: | ---: |
| Classic+CoC 2M | 0.405 | 0.220 | 0.625 |
| Classic+GATT 2M | 0.407 | 0.373-0.375 | 0.780-0.782 |

Classic alone 1.193 Mbps; same-pair shared-controller contention was not additive. Parallel independent sessions also share radio conditions; their actual media overlap must be recorded.
