# Time-based relay queues and viewer buffers

Decision 2026-10-14: relay queues and viewer buffers are sized in milliseconds of media from the measured drain rate and jitter, as an A/B arm; a relay link slower than the stream keeps shedding to keyframes with the paced backlog, and the lead cap gets an A/B. Two default-off arms and the existing lead cap are built; none has run on a phone.

**What exists today (the control arm).**

| Buffer | Limit | Where | Why it is a constant |
| --- | --- | --- | --- |
| Relay child queue (`RelayChildQueue`) | No count or byte cap. Sheds to the next keyframe when the oldest video is older than 2 000 ms minus 250 ms (assumed Auto start) minus the child's reported added playout delay, never below 400 ms (`BoundedMediaAge.streamQueueBudgetMs`, relay-child skip-to-keyframe, on by default). Cooldown 4 s, keyframe grace 3 s. The `queueCapacity` constructor argument of `ReceiverNodeForwardingCore` is only validated | `MeshCore.kt`, `BoundedMediaAge.kt` | 2 s is the largest Auto playout; the 400 ms floor is `LinkRateController.CONGESTED_BACKLOG_MS` so a scene-cut drain does not shed every keyframe |
| Relay withdrawal | `RelayChildHealthPolicy` hard backlog (the child is withdrawn so it reroutes) | `ReceiverStreamForwarder.kt` | Last resort when shedding cannot keep a link |
| Link pacer | Writes at the child-measured arrival rate times 1.2, 50 ms burst; the Experimental lead cap holds the in-flight surplus to 250 ms while the child reports late media (`test_relay_pace_lead_cap`) | `LinkSelfMeasurement.kt` | Keeps the backlog in the app queue, where it can be shed, instead of the Android socket buffer |
| Viewer playout (`MediaPlayoutClock`) | Starts at the profile delay (Auto 250 ms), grows by the late frame's lateness plus 50 ms up to the profile maximum (Auto 2 s), shrinks 1 ms per 100 ms after 60 s without late media | `MediaPlayoutClock.kt`, `PlaybackDelayProfile.kt` | Measured 2026-10-08/09: Bluetooth bursts of 104-245 ms and scene cuts of 0.65-1.3 s |
| Viewer decoder queue (`EncodedFrameQueue`) | fps x (profile maximum + 500 ms) + 3 frames (2-240), back-pressure when full | `PlaybackDelayProfile.videoQueueCapacity` | Already derived from time at construction; left as is |

**What is built (all default off; DEBUG launch extras, benchmark tunables in `tools/run_device_benchmark.py`).**

- `test_relay_time_queue` / `relay_time_queue`: the relay child queue's shed budget becomes `clamp(keyframe drain time + 2 frame intervals + worst drain stall of the last minute, floor, min(latency budget, fixed budget))`. Drain rate is the child's own arrival report for this link (hop-local); the keyframe drain time is the last keyframe's bytes over it; a stall is how long a record waited for the link beyond its own arrival and the predicted airtime of the record before it (10% tolerance). In a sustained overload (offered above 1.1 times the drain over 5 s) the stall term is dropped. No drain report yet: the fixed budget. Class `RelayTimeQueueSizer` (`TimeBasedSizing.kt`).
- `test_playout_time_target` / `playout_time_target`: a stream viewer's playout grows in one step to the 99th percentile of the arrival spread of the last minute plus 30 ms (up to the latency budget) before a frame is late, and calm shrinking stops there. The lateness rule above still applies up to the profile maximum. Class `PlayoutJitterTarget`, used by `MediaPlayoutClock`; calls and audio-only live are excluded.
- `test_time_queue_budget_ms` (default 1 500, 300-5 000) and `test_time_queue_floor_ms` (default 400, 100-1 000): the shared latency budget and the relay queue floor.
- Unchanged in both arms: the codec-safe shed itself, keyframe recovery with back-off, cooldown, keyframe grace, withdrawal, no wire byte, audio never shed. The lead cap A/B (`test_relay_pace_lead_cap`) already existed and is a separate factor.
- Log lines: `BENCHMARK relay_time_queue` (both arms; also on a change of `drainSource` or `overload` and at least every 10 s while the child queue is non-empty, with `trigger=change|periodic`, because a blocked write silenced the writer loop's own line: lane10 b5-q31-drain-193409 got 1-2 lines per 3 minutes), `BENCHMARK playout_time_target` (arm on), `BENCHMARK time_queue_arms` (at launch).

**JVM model result (injected clocks; a model, not a phone measurement).** One relay child queue, 15 FPS of 5 000-byte frames plus 50 audio records/s, a keyframe on every shed request. Mild overload (about 704 kbps offered into 650, 60 s): oldest queued video peaked at 1 850 ms with the fixed budget (3 sheds, 84 video records shed) and 522 ms with the time target (14 sheds, 112 records shed): lower latency, about four times the keyframe requests. Heavy overload (720 into 500, 40 s): 1 868 ms against 1 547 ms, 7 against 10 sheds, because the fixed cooldown dominates. A link that keeps up (1 000 kbps) sheds nothing in either arm. A 700 ms pause every 5 s into a link at twice the stream rate: the fixed budget never sheds; the time target sheds once (the unmeasured first pause), then holds a 924 ms target (700 ms stall + keyframe + 2 frames) and never sheds again. Viewer playout against a repeating 0-300 ms jitter for 300 s: the earlier rule was late on 35 frames (it shrinks after each calm minute and is late again), the time target on 1 (delay 327 ms). Whether fewer late frames or fewer keyframes is the better trade on a phone is the A/B's question.

**Known limits, stated before the test.** The first stall of a link is unmeasured and can cost one shed that the fixed budget would have ridden out. The cooldown (4 s) and keyframe grace (3 s) are fixed and bound how fast a time target can act in a heavy overload: the sawtooth peak there is the cooldown times the overload ratio in both arms, so the arm should show its effect in mild overload (offered 1.1-1.3 times the drain) and on links with repeating stalls, not in a link at half the stream rate. The child's train median reads 10-30% below the link, so the keyframe drain time is slightly long (conservative). A join backlog inside the viewer's window can raise its target for a minute.

**Phone A/B plan (not run; same gates for every phone; eligibility follows capabilities).** One frozen debug APK on all four phones; arms are launch extras only, so the build is identical between arms. Streams use a fixed requested bitrate, resolution and FPS (rate control and the Auto ladder off, as in the relay pacing runs) from a declared generated source, 5 minutes per run, three repeats per cell, cells in a randomised but recorded order; a quiet window per chain (no parallel radio load: runs of different chains do not overlap).

| Factor | Levels |
| --- | --- |
| Chain | C1 ASUS -> M31 -> A15s (dual-role relay, weak last hop); C2 S24 -> ASUS -> A15s (GATT last hop, 44-88 kbps); C3 S24 -> ASUS -> M31 (CoC both hops, strong; preservation); where a chain is unavailable another eligible three-phone chain stands in and the substitution is recorded |
| Offered load | Above the slow hop's measured capacity (about 1.2-1.3 times the child's `arrivalKbps`, so mild overload) and below it (about 0.7 times) |
| Arm | A0 control (everything off); A1 `relay_time_queue` on every relay; A2 `playout_time_target` on the viewer; A3 A1 + A2; A4 `relay_pace_lead_cap` only; A5 A1 + A4 |

Cells: C1 and C2 get A0, A1, A3, A4 at both loads first (16 cells x 3 repeats), then A2 and A5 where A1 or A4 moved a gate; C3 gets A0 and A3 at the below-capacity load. Offered load is confirmed from `stream_tx` before any comparison, and each log is checked for the arm (`time_queue_arms`, `relay_time_queue arm=time`).

Gates, identical for every phone and every cell (a cell without a valid measurement is UNMEASURED, not passed):

| Gate | Measurement | Arm passes when |
| --- | --- | --- |
| Latency to live | Viewer held age (`media_age: role=stream_rx` `heldAgeMaxMs`, p50 and p95 per run) and the relay's `queueAgeMs`; a proxy, not optical latency | Above capacity: p95 lower than A0 by at least 10% in the median of the repeats; below capacity: not higher than A0 by more than 10% |
| Frozen share | Share of run time with the rendered frame rate below 95% of the effective rate | At most 5%, and no more than A0 plus one percentage point |
| Longest stall | Longest gap between rendered frames | Not longer than A0 by more than 250 ms |
| Frames shed and keyframe requests | `relay_time_queue shedFrames`, `media_age keyFrameRequests` | Below capacity: no more than A0 plus one shed event per run; above capacity: reported, not gated |
| Audio | Audio records offered and received, audio shed (must be 0) | No loss against A0 |
| Preservation | Withdrawals, route changes, re-anchors, picture quality at equal bitrate | No new withdrawal or fallback; the strong chain C3 unchanged within noise |

A run that violates a gate keeps its place in the table. If A1 or A3 fails the below-capacity shed gate but passes above capacity, the floor (`test_time_queue_floor_ms`) is the next A/B factor; if the viewer arm raises held age on joins, a shorter window is.
