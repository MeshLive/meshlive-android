# Audio and asynchronous decoder controls

Live camera 48 kHz mono Opus 32, 20 ms units/about 50 packets/s, same elapsed clock, both encryption layers; replay has no mic. Historical hardware VP9 640x360/15/300 S24 -> S22:

| Transport | Audio / video observation |
| --- | --- |
| Classic | 50 audio packets/s, drops 0; AudioFlinger 48 kHz mono CAMCORDER active/not silenced, speaker started/underruns 0; TX 0/30/drops 0/~15 FPS |
| GATT | 2M/MTU 517, 50 audio packets/s, drops 0; TX 0/30/drops 0/15.5-16 FPS |
| CoC | 2M/MPS 65,535, 50 audio packets/s, drops 0; TX 0/30/drops 0/~15 FPS |

Shared playout anchors before decoder startup; Low 30/Balanced 250/High 500 targets are not physical delay. Historical 15 FPS capacities 9/11/15 include 250 ms reserve; PCM queue 1 s. Dedicated capture/encode/decode/paced AudioTrack threads retained. Forced low-latency track mode produced queue loss; full prebuffer before play could block a stopped track; authenticated 1 KiB video splits did not fix audio loss. These were removed without assuming an API-label or head-of-line cause.

| Older 300-request Low camera | Received / rendered | FPS | Video / audio / sender losses | Scheduled-video p95 ms |
| --- | --- | ---: | --- | ---: |
| Classic | 254 /253 | 15.2 | 0 /0 /0 | 115 |
| GATT | 298 /297 | 15.1 | 0 /0 /0 | 101 |
| CoC | 298 /297 | 15.0 | 0 /0 /0 | 77 |

| Later 150-request Balanced | Received / rendered | FPS | Video / audio / sender losses | Scheduled-video p95 ms |
| --- | --- | ---: | --- | ---: |
| Classic | 205 /204 | 15.1 | 0 /0 /0 | 123.3 |
| GATT | 236 /235 | 15.1 | 0 /0 /0 | 140.3 |
| CoC | 225 /224 | 15.6 | 0 /0 /0 | 354.0 |

Queues empty except pending decoder/chunks; scene, run order and a warm (Severe) source were not controlled. Scheduled-video = decoder output -> requested presentation, not glass/audio delay; not a transport ranking.

| Earlier async 640x360/15/300 (actual 20-24 kbps) | Received / rendered | FPS | Decoder queue / age / drops | Input p95 / scheduled playout p95 ms |
| --- | --- | ---: | --- | --- |
| Classic | 314 /313 | 15.0 | 1/7 /0 /0 | 1.2 /277 |
| CoC | 222 /221 | 15.0 | 1/7 /0 /0 | 1.2 /274 |
| GATT | 157 /156 | 15.2 | 1/7 /0 /0 | 1.2 /290 |

Hardware VP9 VBR mode 1 short 220/221 at 15 FPS, queue 1/7/age 0/drops 0. CBR-FD unavailable; the old automatic CBR mode 2 fallback was functional, not requested CBR-FD coverage. S22 decoder lacked the optional Android low-latency feature, so the flag was disabled.

GATT overflow regression 720x480/30/1100: 117/118 at 23.4 FPS then 64-chunk pipe overflow while retired feedback 871 kbps/23.3 FPS. The 512-MTU chunk pipe/real occupancy/EOF teardown reached 244/245 at 30.3 FPS, RX 0/512/no overflow, then hot Severe stop. Historical thermal-adaptive 660 kbps/native 15 FPS lasted 5 m 13 s, 4692/4693, source 0 drops/RX 0/512, 39.7 -> 41.0 C/Moderate; not 1100 capacity/current thermal policy.
