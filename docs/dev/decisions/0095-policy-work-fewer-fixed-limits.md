# Policy work: fewer fixed limits

Fixed numbers (1100/800/700/500/400 kbps ceilings, 20% margins, 5 s keyframes) were wrong for some phone or link every
time, and fixes kept circling the same freezes. Each workstream removes a root cause instead of tuning a constant; each
step freezes a build, records what broke, what changed and the evidence in [decisions](README.md), and is
compared on the same coverage cases.

| Workstream | Root cause removed | Design | Done when |
| --- | --- | --- | --- |
| A. No frame loss (the default; under A/B against bounded media age, below) | Every congestion response dropped frames: a queue overflow dropped all queued video and waited for a recovery keyframe (the largest frame), overloading the same link again; temporal-layer thinning cost about 2.5 VMAF | Latency is the elastic resource: ordered sender and relay queues, receiver queues sized for the adaptive delay with backpressure, late media played, audio kept on the clock by a small pitch-preserving speed change; a relay child that stays far behind is withdrawn so it can reroute (hop-local), never thinned | No queue drops; freezes and audio drops at or below the previous builds at the same settings; latency inside the playback profile |
| B. Self-measured links | Link capacity differs per model, peer, Wi-Fi state and concurrent links; no API exposes it | Every large record is a packet train: the receiver times its arrival and reports the estimate on the control channel; GATT senders also time large writes; estimates feed the learned per-phone, per-peer capacity | Estimates match probe maxima within about 15% and track Wi-Fi and concurrency changes |
| C. Burst-free encoding | Keyframes of 40-136 KB need over a second of airtime on any Bluetooth link | Native AV1 caps frame size relative to the measured link; hardware encoders use intra-refresh where supported, keeping full keyframes only for joins | No scheduled frame above about 250 ms of measured link time |
| D. No fixed limits left | Picker maxima and priors are constants | The bitrate control shows the measured link estimate; priors become first measurements | Remaining constants are bounded fallbacks used only before the first measurement |

Open: the policy for calls beyond a link's capacity (lower call frame rate, longer call delay, or the current video skip); forced CBR on the M31 and the 15-20 s encoder trim, judged by same-build VMAF; relay child joins (start the child from the relay's next natural keyframe, or grow the relay's playout delay before it connects a child); replacing the 20% per-link planning margin and the 0.9 ceiling headroom. The measured status behind each workstream is in [decisions](README.md) under "Open-defect investigation notes".
