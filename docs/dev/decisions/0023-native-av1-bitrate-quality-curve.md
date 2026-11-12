# Native AV1 bitrate-quality curve

All cases: cpu-used 7, 3 threads, 5-second GOP, 150 identical frames.

| Requested kbps | Actual kbps | VMAF | SSIM | p95 / p99 | Meaning |
| ---: | ---: | ---: | ---: | ---: | --- |
| 350 | 357.62 | 95.981 | 0.979647 | 20.1 / 28.6 ms | Lower-rate mesh option; visible motion softness expected. |
| 400 | 408.19 | 97.153 | 0.981645 | 23.1 / 29.7 ms | Detail-first mesh baseline. |
| 450 | 458.39 | 98.017 | 0.983403 | 21.6 / 34.6 ms | Aggressive mesh/direct transition. |
| 500 | 508.77 | 98.572 | 0.984665 | 24.3 / 29.3 ms | Stronger direct quality; branching headroom falls. |
| 700 | 707.43 | 99.438 | 0.988152 | 24.9 / 49.8 ms | Direct/detail option, not a multi-child default. |

The curve proves that 400 kbps is a genuine visual-quality constraint. Receiver buffers cannot create
the detail that the encoder was not allowed to send.
