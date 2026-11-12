# S22 (Android 16) Bluetooth main-thread wedge under high-rate LE CoC streams (2026-11-07)

A Samsung S22 on Android 16 receiving an LE CoC stream of small SDUs (an ASUS sender, 245-byte SDUs, 1280x720 at 30 fps,
1200 kbps) hangs its Bluetooth main thread: the scanner reads nothing, listens stop allocating and the phone is silently
unreachable until Bluetooth restarts. No app resource leaks (listens, sockets, GATT and scanner registrations balance).
Isolated causal A/B, the stack restarted before every case: no picture (wedge) 5 of 8 with SDU coalescing off versus
0 of 8 with the existing sender-side coalescing on (Fisher one-sided p about 0.013); the per-second stack event rate fell
about 3.8 times. Tradeoff: with coalescing the delivered picture froze more (2-15 events per minute). Next: a design
record for a receiver-side capability bit that asks the sender to coalesce (a wire change, so it needs a design and a
review), then an experimental DeviceFamilyPolicy entry, off by default; until then the call-listen watchdog and the
"Calls unavailable" status make the stall visible.
