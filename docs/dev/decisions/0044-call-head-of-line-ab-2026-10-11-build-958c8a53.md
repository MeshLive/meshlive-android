# Call head-of-line A/B (2026-10-11, build 958c8a53)

Question: does writing call video as authenticated fragments (and giving audio the gaps between them) cut the time call audio waits behind a video frame, and does it cost video? Arms: A control; B `test_call_video_fragments` (fixed 2 KB chunks); C = B + `test_call_speech_led_playout` (late video may grow the shared call delay by at most 150 ms); D = B + `test_call_fragment_airtime_ms=40` (chunk = link rate x 40 ms, 675-4096 B). Calls were isolated 45 s bidirectional 300 kbps recorded-motion calls.

Method. Per cell (caller role x transport x direction) every arm has up to three repeats. Same-arm repeat spread (max-min) is the noise band, as in `tools/analyze_ab_arms.py`: an arm differs only when the mean delta exceeds the larger spread and the repeat ranges do not overlap. Counts below are cells (GATT 8, CoC 4). All 65 measured cases FAIL the device-run-v3 gates in every arm (profile mismatch, surface p95/p99, A15s FPS), so this is a comparison, not a pass. Not measured: received-audio PCM continuity/underruns, A/V offset, mouth-to-ear delay, CPU, battery, on-air order (HCI snoop did not run).

Measurement note. The `BENCHMARK call pipeline stage=audio_queue` series samples only audio taken by the normal dequeue path, not audio written between fragments, so in arms B/C/D it reads far too low (M31<->ASUS GATT max 447 ms control vs 35 ms B, while `audioWaitMaxMs` in B averages 147 ms of window maxima). The comparable audio queue age is the `media_age call_tx audioQueueAgeMaxMs` window series (the fragment writer records interleaved audio into it). Use that; the pipeline numbers are kept only as a supplementary column.

Setup: 65 of 86 cases measured, 13 blocked in r1 (S24 not in a verified quiet state / offline; 10 re-run in r1b, 3 S24-calls-A15s CoC never re-run), 8 setup failures (A15s CoC 5; ASUS-calls-M31 CoC control r1 socket read failed; A15s-calls-S24 GATT C r1 ADB pidof failed on A15s; r3 D A15s-calls-S24 A15s never reached the outgoing phase). Per arm, attempted-and-set-up: A 17/18, B 16/16, C 17/18, D 15/16 (excluding A15s CoC and blocked); no failure depends on the arm. Not run: M31<->ASUS GATT B and D in r3 (the four snoop cases), so those arms have 2 repeats on that pair; A15s<->S24 C and D have 2 repeats in some cells.

## GATT results (pooled per pair; mean over both caller roles, both directions, all repeats)

| M31<->ASUS GATT (A/B/C/D samples 12/8/12/8) | A | B | C | D |
| --- | --- | --- | --- | --- |
| audio queue age p50 / p95 / max over windows (ms) | 36 / 457 / 486 | 32 / 140 / 151 | 31 / 141 / 152 | 29 / 136 / 144 |
| audio wait behind video, max / mean (ms) | - | 147 / 50 | 135 / 48 | 109 / 21 |
| shared call playout delay p50 (ms) | 459 | 480 | 364 | 470 |
| audio drops (rx and tx) | 0 | 0 | 0 | 0 |
| visible FPS | 14.1 | 14.0 | 14.0 | 14.0 |
| freeze events per call / frozen % | 1.9 / 1.4 | 1.4 / 1.2 | 1.3 / 1.5 | 1.6 / 1.4 |
| longest stall (ms) | 392 | 471 | 461 | 454 |
| fragment overhead % / chunk mean, min, max (B) / fragments per s | - | 4.15 / 1189, 187, 2048 / 6.5 | 4.15 / 1175, 187, 2048 / 6.4 | 5.23 / 1487, 815, 2068 / 13.7 |

| S24<->A15s GATT (A/B/C/D samples 12/12/10/10) | A | B | C | D |
| --- | --- | --- | --- | --- |
| audio queue age p50 / p95 / max over windows (ms) | 93 / 281 / 314 | 70 / 170 / 195 | 68 / 131 / 151 | 50 / 89 / 95 |
| audio wait behind video, max / mean (ms) | - | 94 / 18 | 116 / 20 | 72 / 9 |
| shared call playout delay p50 (ms) | 582 | 589 | 469 | 595 |
| audio drops | 0 | 0 | 0 | 0 |
| visible FPS (limit 14.25) | 7.5 | 7.9 | 8.8 | 8.7 |
| freeze events per call / frozen % | 12.8 / 15.0 | 11.7 / 12.9 | 14.1 / 13.1 | 12.9 / 11.0 |
| longest stall (ms) | 1728 | 1657 | 1145 | 928 |
| fragment overhead % / chunk mean, min, max / fragments per s | - | 4.32 / 1280, 96, 2048 / 7.5 | 4.34 / 1246, 75, 2048 / 7.4 | 6.22 / 1309, 680, 2195 / 13.8 |

Beyond-noise counts on GATT (better/worse/within noise out of 8 cells, vs control A): audio queue age p95: B 4/0/4, C 4/0/4, D 6/0/2; audio queue age max: B 4/0/4, C 4/0/4, D 5/0/3; shared playout delay p50: B 1/0/7, C 4/0/4, D 2/0/6 (C vs B 5/0/3); audio drops 0/0/8 everywhere; visible FPS B 0/1/7, C 0/1/7, D 1/1/6; freeze events B 1/0/7, C 0/0/8, D 0/0/8; frozen share B 1/0/7, C 0/0/8, D 0/0/8; longest stall B 0/1/7, C 0/1/7, D 1/1/6. D vs B: audio wait mean lower in 8 of 8 cells (about half), audio wait max 3/0/5, audio queue age max 1/1/6. The one consistently worse cell is M31 sending to ASUS (ASUS calls M31): all three fragment arms show 0.2-0.3 FPS less (13.4-13.5 vs 13.7) and a 90-110 ms longer longest stall (642-658 vs 550 ms) than control, with disjoint repeat ranges (control 3 repeats, B and D 2, C 3).

## LE CoC results (M31<->ASUS only, 4 cells)

CoC has no head-of-line problem to fix: control audio queue age is p50 1 ms, p95 median 4 ms, max median 5 ms (one 350 ms outlier). Fragments move almost nothing: B/C fragment about 5 frames per call (D 120) and write 0 to 1 interleaved audio packets. Audio queue age max medians: A 5, B 9.5, C 6.5, D 13 ms (C and D within noise in 4 of 4 cells; B worse in 1, within noise in 3). D doubles the framing cost on CoC (overhead 7.9% vs 4.0% for B/C; chunk mean about 1.0 KB, fragments per s 8.9 vs 5.8) and, in the M31-calls-ASUS direction ASUS->M31, showed a 1.0-1.2 s video queue p95 and 1.1-1.2 s longest stall in 2 of 3 repeats (control once 0.35 s video queue p95, B once 0.16 s). Audio drops 0, visible FPS 10.2-10.3 pooled mean in every arm, shared playout delay p50 564-589 ms in every arm.

## Verdict per arm

- B fragments: fixes the GATT audio queue (p95 and max down 38-69%, from 457/486 to 140/151 ms on M31<->ASUS and 281/314 to 170/195 ms on S24<->A15s, beyond noise in 4 of 8 cells and never worse), at 4.2-4.3% framing overhead and no change to the offered video load (M31<->ASUS 284.6 vs 283.7 kbps). Does not move the shared playout delay (480 vs 459 and 589 vs 582 ms; it stays pinned at 600 ms on S24<->A15s), so the speech path still pays for late video. Cost: a small M31-sender video loss (0.3 FPS, +100 ms stall). No CoC benefit.
- C fragments + speech-led: same audio queue gain as B (151/152 ms on M31<->ASUS, 131/151 on S24<->A15s) plus the only arm that lowers the shared call playout delay: p50 459 to 364 ms (M31<->ASUS) and 582 to 469 ms (S24<->A15s), beyond noise in 4 of 8 cells vs control and 5 of 8 vs B, with no cell worse on playout delay. No audio drop, no extra freeze events or frozen share vs control beyond noise (C vs B: frozen share worse in 1 cell, the M31-sender cell above; visible FPS and longest stall worse in that same cell vs control), longest stall otherwise equal (S24<->A15s 1145 vs 1728 ms, within noise). On CoC it is neutral (the cleanest CoC arm, audio queue max median 6.5 ms).
- D fragments + airtime 40 ms: best audio wait (mean about half of B in 8 of 8 GATT cells; S24<->A15s queue age p95/max 89/95 ms vs control 281/314), no change to the 600 ms playout delay, 1.1-1.5x B's overhead on GATT (5.2-6.2% vs 4.2-4.3%) and about 2x the fragment rate (13.7 vs 6.5-7.5 per s), and clearly worse on CoC (overhead 7.9%, 120 fragmented frames per call, two 1.1 s video stalls). Residual audio wait on M31<->ASUS is about 145 ms max in both B and D, so fragment size is not the only floor there.

## Recommendation

1. Promote arm C's behaviour for GATT direct calls to the default (fragments plus speech-led playout), as one switchable policy so each half keeps its own off-switch: fragments are the only way to remove the 0.3-0.5 s audio queue wait, and speech-led playout is the only thing that lowers the 600 ms shared delay. Keep fragments off on LE CoC (no head-of-line wait to remove, small added queue age, no gain) by gating on transport or on measured audio wait.
2. Keep D (airtime) experimental and off: better mean wait but more overhead, twice the fragment rate and a CoC video-stall risk. Re-evaluate D+C (airtime plus speech-led, an arm not run) and D only on GATT after the snoop.
3. Do not call this a pass. Every case fails the formal gates in every arm, S24<->A15s video is bound by A15s sending (video queue p95 about 3 s, 7.5-8.8 FPS, about 12-14 freeze events per call) and fragments do not change that. The head-of-line fix improves audio and does not make A15s video acceptable.
4. Before promoting, close the gaps: received-audio continuity/underruns and A/V offset with speech-led playout (the 150 ms video growth cap is a lip-sync trade the logs cannot see), CPU and battery cost of 2x records on the M31 sender, a rerun of M31<->ASUS GATT B and D for the third repeat, S24<->A15s on C/D for the missing repeats, A15s CoC once its setup works, and a build with the pipeline `audio_queue` stage fixed to include interleaved audio.

Caveats: 3 repeats (2 in some arms), noise band from 2-3 values, so "within noise" is under-powered and "better" is a weak claim at n=2; two phone pairs, one of them with a weak libaom/AV1 sender; GATT and CoC differ by an order of magnitude in baseline wait, so pooled GATT numbers are not CoC predictions; counts of cells are not independent (each pair/direction shares a radio environment); radio conditions across repeats were not controlled.

What the M31<->ASUS GATT snoop (the snoop bundle, B and D, both caller roles, full HCI snoop on both phones) would have verified, and why it matters: (a) that audio writes really go on air between two video fragments, in the order the app logged (ATT Write Command order and Number-of-Completed-Packets timing), rather than sitting in the Android GATT queue behind several queued fragments; (b) the true head-of-line wait from write call to on-air transmission, which the app's `audioWaitMaxMs` (queue to write call) cannot see and which would explain the 145 ms floor shared by B and D; (c) how many ACL packets and connection events each fragment takes, i.e. whether D's smaller chunks waste events on the 7.5-45 ms connection interval and what the true overhead is with L2CAP and ATT headers on air; (d) receive-side arrival gaps for audio and video, an independent audio delay measurement. It did not run (r3 executed with `--hci-snoop off` and omitted those four cases), so nothing here is verified at HCI level and the app-side wait numbers remain unconfirmed by an independent clock.
