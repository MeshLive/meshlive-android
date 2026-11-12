# CoC windows and GATT completion discipline

| Unprimed CoC variable | Values | Mbps result |
| --- | --- | --- |
| Payload, four-packet ACK | 4/8/16 KiB | 0.248 /0.253 /0.239 |
| ACK window, 8 KiB | 1/2/4/8/16 packets | 0.229 /0.23 /0.253 /0.247 /0.245 |
| Read buffer | 4/8/16/64 KiB | 0.238-0.249, no monotonic effect |
| Write buffer | 4/8/16/64 KiB | 0.247-0.253, no material effect |
| Large payload | 32 KiB x 1 /65,535 B x 1 /65,535 B x 2 | 0.252 /0.241 /0.247 |
| DCK | 8 KiB x 4 | 0.260 short sample |

16-packet and 65,535-byte x 2 cases took 8.5 s for a 6 s request, increasing drain without gain.

| Unprimed CoC channels (8 KiB x 4, DCK, 2M) | Sender / receiver Mbps | Completion sender / receiver s |
| --- | --- | --- |
| 1 | 0.245 /0.246 | 8.6 /8.5 |
| 2 | 0.264 /0.270 | 9.9 /9.7 |
| 4 | 0.258 /0.261 | 9.1 /9.0 |

Four channels were uneven, 0.064-0.086 Mbps each. Earlier unbounded writes queued 1.8 MB while 0.8 MB arrived before close; an ordered-End/final-ACK test took 52 s to deliver 1.7 MB at 0.264 Mbps after 8 s enqueue. This and the primed 33.4 s drain reject enqueue speed as video throughput.

GATT MTU 517, 511 benchmark payload bytes/value, one outstanding Android operation; historical HIGH fence spacing:

| Data writes / acknowledged fence | Mbps |
| ---: | ---: |
| 1 | 0.107 |
| 4 | 0.302 |
| 8 | 0.445 |
| 16 | 0.541 |
| 32 | 0.635 |
| 64 | 0.685 |
| 128 | 0.672 |
| 256 | 0.730 |
| 512 | 0.766 |
| 1024 | 0.734 |
| 2048 | 0.776 |

Two/four/eight in-flight no-response writes caused 1500-1700 busy rejections per 8 s, no gain; the prototype was removed. A 15 s HIGH 0.766/0.740, mean 0.753 versus reversed DCK 0.855/0.815, mean 0.835 (10.9% scoped gain; best 0.855 about 61% of the 1.4 headline). These are controls, not power rankings. Production retains one completion outstanding and scoped HIGH/DCK/legacy pacing rules; callback return is not receiver delivery.

| Reverse GATT (MTU 517/2M, 15 s) | Transmitter / receiver Mbps |
| --- | --- |
| Notifications, HIGH | 0.803 /0.781 |
| Notifications, DCK | 0.823 /0.776 |
| Client writes, DCK, same-session | 0.818 /0.820 |

No notification-direction benefit; roles unchanged. API 36 measurements do not establish API 37 HDT/temporal-layer/LE Audio support.
