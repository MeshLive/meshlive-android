# Callee scan yield promoted (2026-10-27)

A reachable phone whose Nearby or Live list scanned at low latency without pause often missed a caller's connection
(status 133, HCI 0x3E "failed to be established"). With the yield (low latency for 8 s, then balanced) calls were better
or equal on every measured pair and never worse: S22/ASUS 10/10 vs 8/10 (status 133: 2 vs 8; the HCI snoop confirmed
the callee scanned at 20-25% duty in the yield arm), M31/A15s 4/5 vs 2/5, S24/M31 6/6 vs 3/6 (connection-callback
failures 0 vs 5), ASUS/A15s 3/6 vs 3/6; median tap to ring 1.9 vs 2.5 s. Promoted to default on after
review; `test_reachable_scan_yield=false` keeps the old behaviour as the control arm. Xiaomi and A55 are still to be
measured.
