# Native AV1 encrypted live motion

All four runs used the actual camera, native AV1, 32 kbps Opus, application encryption, Classic
RFCOMM, the asynchronous decoder, and the 500 ms receiver profile. Start/stop beeps marked a common
20-second aggressive hand-pan window. Both phones were already hot; automatic thermal reduction was
disabled for isolation.

| Live profile | Encoder result during motion | Receiver result during motion | Visual/product result |
| --- | --- | --- | --- |
| 720x480/15/400, speed 7/3 | 15.0 FPS; 396-407 kbps; encode p95 47.6 ms; zero capture/transport drops | p50/p95 66.7/66.7 ms; zero decoder/audio drops; empty BT queue | Coherent and stable, but fast pans retain blur/smear. Detail-first mesh option. |
| 640x360/24/400, speed 7/2 | 24.0 FPS; 401-437 kbps; encode p95 35.7 ms; zero capture/transport drops | p50/p95/p99 41.7/41.7/41.7 ms; zero decoder/audio drops | Much smoother cadence, but visibly more spatial blockiness. Motion-first mesh option, not a universal default. |
| 720x480/24/700, speed 8/3 | 24.0 FPS; 680-714 kbps; encode p95 35.0 ms; zero capture/transport drops | p50/p95 41.7/41.7 ms; zero decoder/audio drops; queues bounded | Measured direct balance; subjective observation is not an acceptance gate. |
| 720x480/30/900, speed 9/3 | 29.9-30.0 FPS; 890-903 kbps; encode p95 26.4 ms; one camera-frame miss; zero transport drops | p50/p95/p99 33.3/33.3/33.3 ms; zero decoder/audio drops; queues bounded | Measured high-rate direct mode. Costs more radio/CPU and leaves less burst headroom than 700/24. |

The receiver's cumulative debug continuity counter initially included the intentional multi-second
gaps while the sender was stopped between profile changes. Those historical "freeze events" are not
stream freezes and are excluded above. The tracker now closes and resets on every new stream header.
