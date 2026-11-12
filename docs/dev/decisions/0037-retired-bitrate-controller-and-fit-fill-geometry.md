# Retired bitrate controller and fit-fill geometry

The old adaptive controller/feedback v2 was removed 2026-08-27; these downshift controls explain the persisted selection, not the current fixed-rate policy.

| Historical hardware VP9 CBR picture control | Offered / actual | Observation |
| --- | --- | --- |
| Classic before, 1280x720/15/1100 | 0.9-1.1 Mbps, empty queues/drops 0; a short FPS sample cut 1100 -> 902 kbps | Unjustified downshift |
| Classic after, same | 1024/1028/1097 kbps, empty queue/drops 0, 14-16.5 FPS, no downshift | No downshift |
| GATT old floor, 1280x720/15/1100 max | 2M, 0.69-0.84 Mbps, backlog target 552, recovery 979, drops 0 | Better than former 345 floor |

Retired 0.04 bits/pixel/frame floor changed 1280x720/15 from 345.6 to 552.96 kbps. Relaunch previously returned 640x360/15/150 (Home wire 182); persisted resolution/FPS/rate and then 300 default fixed lost selection. The old screenshots captured different instants/display crops and did not establish aligned VMAF/SSIM. The numeric observations above remain the historical evidence.

Aspect controls: 1280x720 replay on 1080x2340; FILL uniform crop circle 802x802/square 906x907; FIT four edges, boundary 1.7946 versus 1.7955 reference. Sender FIT surface 1080x1920 centered on 1080x2340. These prove local uniform fit/fill, not all display shapes. Historical split allowed sender FIT/receiver FILL; later one-local-phone preference selects FIT if either former key was FIT. Encoded bytes/load unchanged.
