# Legacy-family protections and measured scope

Qualcomm qcom/msmnile-sm8150-family/API<=32 Classic source continuous low-latency BLE scanning caused 300-700 ms delivery gaps despite 66.6 ms encoder PTS. Credit/packet ordering/VP9/small frames/1000 ms playout did not solve it and were removed. Retained scope changes source BLE scheduling only: 8 s acquisition then balanced; GATT/CoC, newer Qualcomm/Exynos unaffected. Profile: 640x480/15/220 AV1 + Opus 32/High 500:

| Direction | Historical result |
| --- | --- |
| ASUS -> S24, rule active | 15.0 FPS/66.6 ms, 0 freezes/sender/decoder/audio drops, 5-7 buffered frames |
| S24 -> ASUS, rule inactive | 15.0 FPS/~66.7 ms, 0 freezes/sender/decoder/audio drops |

The measured historical legacy private-GATT fallback used flexible-YUV VP9 426x240/up-to 15 FPS/24 kbps plus Opus 16 when selected AV1 was incompatible and VP9 was offered. Each current run must verify its effective rate. This does not cover requested AV1/Opus 32; newer stacks and CoC/Classic retain independent normal choices.

| ASUS/S22 direct historical path | Sustained / controls |
| --- | --- |
| GATT | 109 s full-duplex: ASUS VP9 15 FPS/24 kbps/Opus 16, S22 AV1 ~30 FPS/180/Opus 32, decoder 0; first status 133 returns discovery/retry connects; End availability ~2 s |
| CoC | 67 s reverse setup, normal AV1/Opus 32, 15/30 FPS, decoder 0; caller End/availability |
| Classic | >4 m bonded full-duplex, 15/30 FPS, >8000/4000 received, decoder 0; mute/unmute changes matching 50 audio packets/s; End discovery |

Historical Redmi Note 9 Pro (atoll/API 30) direct to S22 exercised all three; legacy GATT only on Redmi 426x240/15/24/Opus 16 versus S22 AV1 640x360/30/180/Opus 32, CoC normal AV1 15/30, Classic ~3 m 15/30/decoder 0/camera/mic controls. Unpaired Classic failed after ~7 s with a pairing instruction. ASUS -> S22 opaque relay -> Redmi:

| Transport | Historical motion / lifecycle |
| --- | --- |
| Classic | Both 15 FPS/~66 ms, decoder 0, >1 m; adjacent bonds; 400 ms drain changed Broken pipe to Peer End |
| CoC | 110 kbps still record-rate overloaded; pairing two 20 ms Opus only in relayed CoC changed 50 -> 25 records/s. Repeat 641/647 frames/~45 s, 15 FPS, 66.3/66.6 ms, decoder 0; one status 133 retry/one cache refresh reached 2M/MTU 517/MPS 65,535; clean End |
| GATT | Both legacy VP9 426x240/15/24/Opus 16, 747/741 received, 15 FPS/~66 ms, decoder 0; server-close first changed availability ~10 s timeout -> 1.94-2.00 s |

Final ASUS teardown raced ImageReader input against a released MediaCodec; a handler-owned release and late-callback guard retained PID/Peer End/2.38 s re-advertise, without lowering successful Samsung settings.
