# Allocation and bidirectional call-rate evidence

M31 allocation proof: build 8EDC native AV1/ParkJoy 640x360/15/500 CoC retained CBR/VBR/CBR-FD/Automatic through start, Pause/Resume and rejoin; median rates 499.5/575/499.5/500 kbps and minimum encoder 15/15/14.9/14.9 FPS establish allocation, not picture/timing/Camera2. Negative startup tests reject an unknown encoder/allocation and unavailable hardware-CBR.

Same-pan call-rate comparison: M31 caller/ASUS callee, ASUS software VP9 CBR/M31 hardware VP9 VBR 320x240/15, Opus 16/32, Balanced 250 ms, thermal OFF, all active slots/tails. Losses/freezes were 0 but timing/discovery failed. A lower effective rate does not measure the selected 250 kbps request; 500 picture proof applies only to that pan.

| Selected / effective kbps | Actual ASUS / M31 median kbps | Full ASUS -> M31 VMAF / SSIM / PSNR dB | Full M31 -> ASUS VMAF / SSIM / PSNR dB | Scoped result |
| --- | --- | --- | --- | --- |
| 250 /150-180 | 152.5 /180 | 93.156496 /0.959582 /38.007029 | 96.178402 /0.967227 /39.029018 | Both SSIM FAIL; requested 250 UNMEASURED |
| 250 /250-250 | 252 /250 | 98.309527 /0.975223 /40.742249 | 98.292415 /0.975506 /40.477562 | Both SSIM FAIL |
| 500 /500-500 | 499.5 /500.5 | 99.597340 /0.987616 /42.877168 | 99.548516 /0.987486 /43.467101 | Complete reference-decoder picture PASS only |

| ReadySetGo private-call evidence | Result |
| --- | --- |
| M31 caller 500 controls (build 8EDC) | Actual ASUS/M31=500/500.5 kbps, zero final source/raw/queue/audio/decoder loss/freezes, all 13 lifecycle gates PASS. ASUS p95=83.9 ms/discovery 4569 ms FAIL. Complete ASUS -> M31: 1296 slots/1293 rx, 99.630487/0.900486/31.416801; reverse 1287/1286, 99.615471/0.901407/31.922182. Both picture scopes FAIL SSIM/PSNR despite high VMAF. |
| ASUS caller (build BE31) | No MTU completion before existing setup deadline, no media. Setup FAIL; both media directions UNMEASURED. Rotation correction cannot approve an unstarted connection. |
| M31 caller repeat (build BE31) | Actual 499/500 kbps, 13 lifecycle gates PASS. ASUS incoming p95/p99=84.0/86.9 ms and one unexpected freeze FAIL; M31=68.4/83.8 ms, zero freezes. Complete picture/optical scoring remains separate; this does not cover ASUS-caller setup. |
| Six setup/Decline attempts and setup-order audit | ASUS normal fails twice; late-priority fails once/succeeds once; M31 caller normal/late succeed. PHY status 42 also appears in successful setup. Missing MTU callbacks before 12 s stay failed. Deferring priority is not a complete setup fix; Decline success has no media scope. |

Received-frame colour audit: source/RX decoded pixels were identical for 1293/1286 received frames on build 8EDC and 1340/1289 on BE31, mismatches 0; missing tails remain scored. BE31 Y/U/V PSNR: ASUS 30.385/36.039/37.087 dB, M31 30.799/35.060/36.067; SSIM: ASUS 0.917/0.858/0.882, M31 0.925/0.833/0.864. Controls reject a simple UV swap or +/-1-3-frame offset; input-copy loss versus encoder compression remains unresolved.
