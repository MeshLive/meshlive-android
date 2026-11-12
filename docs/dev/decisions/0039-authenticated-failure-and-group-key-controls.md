# Authenticated failure and group-key controls

BMH2 signed adjacent/endpoint identities, BMS1/BME1 ordered AES-GCM, authenticated parent/original source remain mandatory. Trusted normal viewer-middle and content-blind opaque relay are different roles. Hop-local keepalive/keyframe/End never becomes graph-wide encoder feedback. Removed plaintext/BTV1/feedback v2 acceptance stays removed. Metadata visibility, human trust, re-key/revocation and independent audit limits remain explicit. See [protocol reference](../protocol/README.md) for the wire and security rules.

| 2026-08-23 post-cutover S24 -> S22 | Retained result |
| --- | --- |
| GATT 2M/MTU 517 | 78/79 renders at 15.4 FPS, TX 0/30/drops 0; preceding 155/156 at 15.4; both identities match |
| CoC 2M | 77/78 renders at 15.3 FPS, TX 0/30/drops 0 |
| Classic request-first UI | 228/229 at 15.0 FPS, TX 0/30/drops 0, prefixes match auth; earlier 153/154 at 15.2 |
| Endpoint tamper | Both handshakes complete, first altered video BAD_DECRYPT, 0 accepted frames |

JVM tests reject replay/wrong-recipient/tamper/cross-domain hello; Android/Windows match a fixed AES-GCM vector. Windows Release/CNG identity/self-tests/advertise are historical only; the receiver's video-only BTV2 path still needs BTV3 audio/discovery.

Other unique controls: pre-endpoint CoC 78/79 at 15.5 FPS; same-stream reconnect 154/155 at 15.2; pre-endpoint GATT 153/154 at 15.2; GATT 700 target underproduced 392-413 kbps, 305/306 at 15.1/TX 0/30/drops 0/Moderate. Normal GATT 150 discovery 184/185 at 15 FPS. Fixed-hop Classic 300 pause 18 s exceeded the 8 s heartbeat, 634 received/632 rendered; resume 15 FPS/all percentiles 66.7/TX 0/33/drops 0/clean End. CoC 300: 249/248 at 15.1/TX 0/33/drops 0/>17 s; GATT 150: 15 FPS/all percentiles 66.7/0 freezes/TX 0/33/RX 0-1/512/>20 s. An easy 20-30 kbps scene does not prove 150 load.

Four-phone CoC group S24 -> A55 opaque relay -> (S22, M31) distributed one epoch key separately to authenticated endpoints; relay content-blind. Source 15 FPS/drops 0; S22 12-14 FPS/M31 2-4; planned M31 kill left S22 connected/recovered; S22 End ack. Key distribution/isolation proof only, joint quality FAIL. A later RECEIVER_LEFT member/terminal code passed JVM/build but the physical membership-End rerun was not completed; dynamic revocation/re-key are open. Older blind GATT relay 34-40/40, 1211 drops, 3.6 FPS FAIL; Classic opaque 15 FPS/three freezes/69 audio functional only. Normal CoC preflight 14.8-15 FPS/0 audio-decoder/RX 0-1 was not full chain coverage.

Private CoC S24 -> M31 opaque -> S22 proved endpoint identity through the relay radio and >45 s A/V, then load/framing FAIL; the 110 kbps/15 FPS starting budget is not a ceiling. GATT relay rollback 401 versus 425 / 1130 versus 1221 byte-ownership fixes extended one 86 s interval at 15 FPS/decoder 0; final post-second-fix endurance not run. Unbonded adjacent Classic relay did not run; missing pairing is not PASS.
