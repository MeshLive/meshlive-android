# Testing and benchmarks

The reference behind the [Run the tests](testing.md) quick start.

## Scope: what a complete test covers

Every capable phone should act as sender, receiver and middle viewer/forwarder (or supported opaque relay).
Eligibility comes from each phone's exported capabilities and a current preflight, not from a registry entry. Encoding
and decoding are different capabilities: a phone that can decode VP9 but not encode it is a receiver for VP9, and you
must not request a missing encoder and count a fallback. Family compatibility protections stay in force, but any
requested versus effective difference (a lower rate, a different encoder, direct fallback) is reported and cannot
pass the original request.

The project's reference phones are listed in [../tools/benchmark_scope.json](../../tools/benchmark_scope.json):
S24 (SM-S921B), S22 (SM-S901E), M31 (SM-M315F), ASUS (ASUS_I01WD), A15s (OPPO CPH2179) and Realme (RMX5256) are the
fixed required phones (`required_phones`); Xiaomi (Redmi 23053RN02I) and A55 (SM-A556E) extend the inventory
(`extension_phones`): their request ids are compiled and kept, and a run does not need them. They are examples of the
span of Android versions, SoCs and Bluetooth stacks the app should handle. Your phones extend the same inventory; they
do not replace anything. `carrier_eligibility` lists the phones measured for each Wi-Fi carrier, and
`carrier_eligibility_provenance` cites the calls behind each listing.

### Roles and topologies

| Flow | Required proof |
| --- | --- |
| A -> B | Direct Camera2 plus audio, native recorded raw encoding and finite replay as separate inputs; startup, motion, controls, teardown and rejoin |
| A -> B and A -> C | Source fan-out; independent healthy and slow child queues, admission and delivery |
| A -> B -> C | B plays locally and forwards; C authenticates B as parent and A as the original source; score both hops |
| Longer linear chains | Actual ordered parents, source and stream identity, TTL, each middle phone's playback and child health |
| Direct viewer plus chain, or two branches | Local admission and healthy-branch isolation under measured overlapping media |
| A -> B -> (C, D) | A separate group/relay diagnostic; the normal viewer/forwarder admits one child |
| Diamond or alternative parents | One active parent, same-source recovery, hysteresis, duplicates, temporary cycles, TTL expiry |
| A <-> B | Simultaneous independent video and audio; both call setup roles and both outgoing profiles |
| A <-> relay B <-> C | An actual one-hop opaque call relay; both endpoint directions, adjacent liveness and queues |
| Disjoint calls or chains | Measured coexistence and media overlap; launching simultaneously is not enough |

No silent transport fallback. For N phones there are N x (N-1) directed pairs and N!/(N-K)! ordered K-phone chains,
each multiplied by the compatible transport and profile assignments. The inventory compiler therefore represents large
products by dimensions and counts. Unsupported mixed edges, branching or relay runners stay explicit obligations, not
executable-looking passes. A branch diagnostic does not approve normal branching in the UI. Recovery straight to the
source fails a requested chain: direct fallback is a failed requested chain.

### Settings coverage

| Dimension | Coverage |
| --- | --- |
| Codec | Every offered sender encoder and compatible receiver decoder; hardware, software and native paths distinguished |
| Allocation | Automatic, CBR, VBR and CBR-FD where actually offered; actual selected mode, bursts and keyframes, permitted dropping |
| Camera and geometry | Back and front, native dimensions and FPS, orientation and mirroring, preview and framing, flip, Surface recreation |
| Load | Low, high and boundary resolution, FPS and rate; 300/500/900/1000 kbps checkpoints where offered; picker maxima Classic 1500, CoC 1200, GATT 1100 kbps |
| Playback | Low 30 ms, Balanced 250 ms, High 500 ms; each receiver's choice independent from the sender; `auto` (adaptive 250-500 ms) is the app default |
| Audio | Normal mono 48 kHz Opus 32 kbps, permissions, mute, startup, drift, lip sync; audio-only and video-only are diagnostics |
| Thermal | Toggle on and off, observed legitimate states and restoration; a cold toggle does not measure real heat |
| Persistence and forwarding | Before-join forwarding, supported active changes, late or slow children, relaunch, role and call hand-over |
| UI | Stable discovery, Connecting and Recovering states, truthful settings, no stale or duplicate entries, no unwanted paired-device picker |

A back-camera capability export does not prove front-camera or flip eligibility. Advertised modes are not sustained
viability. Thermal tests verify the source-local policy (including supported Camera2 range differences, warnings,
critical stop and restoration) using safe policy fixtures or observed heat; never heat a phone deliberately to the
critical state.

At the time of writing the reference scope compiles to about 26,800 exact cases (7,075 broadcast, 17,910 call and 1,800
chain cases) plus 2,395 explicit 45-second settings-sweep diagnostics. Everything starts NOT RUN. The sweep covers
every individually feasible offered geometry, FPS and rate value and the declared boundary combinations; it does not
exhaust the residual product of all settings. High FPS never approves low FPS, because cadence, packet spacing and
burst behaviour change. Plan JSON reports selected and total requested media seconds, excluding setup, cleanup,
scoring, conflicts and reruns.

### Lifecycle and faults

For each supported flow, keep the planned commands and the actual authenticated acknowledgements in each phone's own
clock, with cumulative counters, PID or generation boundaries and ambiguous intervals.

Cover, for each supported flow: fresh permissions, Bluetooth off and on, discovery and list retention; select,
authenticate and negotiate through first surface or audio, with cancel and back at each stage; broadcast Start, Pause,
Resume and End (paused liveness, resumed keyframe, downstream acknowledgement); call Invite, Ring, Accept, Decline,
Cancel and Hang-up, with availability reusable at once; mute, video off and on, flip, orientation and rapid controls;
Home, lock and unlock, preview destruction, OS interruptions and process death (Home and return do not approve
reconstruction after process death); settings at supported boundaries; late join and rejoin, child, middle or source
loss, no-route intervals beyond 15 s, slot freeing and cooldown expiry; slow-child isolation, bounded queues,
authenticated withdrawal and no reconnect or scan loops; decoder, keyframe and codec-config recovery, bursts and
controlled malformed or failing authentication that fail closed; call and broadcast hand-over with nothing stranded;
and, separately, matched, randomized, role-reversed 15-30 minute endurance, real heat and controlled range or
interference.

Required security and relay work includes wrong-identity, unauthorized, replayed and tampered controls, branch key
changes and expiry, and relay endpoint versus adjacent identity separation. Future product and platform prerequisites
are tracked in [roadmap.md](roadmap.md) and stay unmeasured here until implemented and executed.

## Common acceptance gates

The same gates apply to every phone and every supported role. Capability- or family-specific implementation and
focused bug regressions may exist when evidence supports them; they never create a different acceptance standard for
one phone. Never silently change a requested bitrate, FPS, resolution, allocation, encoder, audio, transport or buffer
to earn a pass. Missing or invalid measurements stay UNMEASURED, and every failed, interrupted, pending, missing or
unmeasured case stays visible in the results.

Two acceptance profiles exist. Each result records the profile it was scored under (`thresholds_version`) and is never
re-labelled. `device-run-v3` is the default profile of the earliest results. `device-run-v4-user` is the user-centred
profile adopted on 2026-10-09 and selected by `acceptance_profile` in
[../tools/benchmark_scope.json](../../tools/benchmark_scope.json).

### `device-run-v3` (engineering profile)

Startup is at most 3000 ms and discovery at most 1500 ms (tightened on 2026-10-06; earlier results keep the thresholds
recorded with them).

| Measurement | Target |
| --- | --- |
| Actual frame intervals | p95 at most 1.25 frame periods; p99 at most 2 periods. A rendered interval is a whole number of display refreshes, so the comparison allows half a refresh period of the receiving phone's screen (8.33 ms at 60 Hz); a frame shown a whole refresh late still fails. The refresh rate is the one declared for the phone (`display_refresh_hz`), else the app's `BENCHMARK display: refreshHz=` line, else 60 Hz; each gate records `tolerance`, `display_refresh_hz` and `display_refresh_source` |
| Encoder and visible FPS | At least 95% of the selected FPS |
| Unexpected loss and freezes | Zero; keep input, transport, forwarder, audio, decoder and queue causes separate |
| Startup and recovery | At most 3000 ms; full fault-to-first-surface time, including the search gap |
| Discovery | At most 1500 ms |
| Offered load | Clean-window median encoded payload at least 90% of the selected bitrate |
| Aligned full-source picture | VMAF at least 90, SSIM at least 0.98, PSNR at least 35 dB |

### `device-run-v4-user` (user-centred profile)

People judge how old the picture and sound are, how long they stop, and whether speech is intact; they do not judge
VMAF or an FPS fraction. The v4 gates measure the former. The initial targets are to be revised from measured
distributions, never lowered to make a failure pass.

| Measurement | Decides | Target and evidence |
| --- | --- | --- |
| Longest stall | Every link | Calls at most 500 ms, streams at most 1 s. The largest gap between rendered surface updates from the receiver's rolling 256-gap report window; PASS needs every report interval to add at most 256 gaps, otherwise UNMEASURED. The tail after the last frame is not included |
| Frozen-time share | Every link | At most 1% of scored rendered time in unexpected freezes (gaps beyond the freeze threshold, less one frame period each); planned actions overlapping a freeze make it UNMEASURED |
| Effective-profile cadence | Every link | At most 5% of the clean report time below 95% of the FPS in force (from the received stream header, time-weighted across a change; the requested FPS before any header), judged together with the longest-stall gate. The time resolution is the report interval. The earlier rule (the slowest clean report interval at least 95%) stays in the row as `legacy_slowest_window`, a labelled comparison that never decides. Requested-profile shortfall and share of time degraded are diagnostics |
| Stream join | Every stream receiver | Selection to first rendered surface at most 3000 ms (`selection_to_first_surface_ms`, the app's `Receiver startup: selection-to-first-surface` line) and discovery at most 1500 ms. Without the selection line the gate is UNMEASURED, never a pass. Launch to first surface (cold start plus browsing, about 5 s) is computed and reported as the non-gating `launch_to_first_surface_ms` row (DIAGNOSTIC, its verdict kept as `measured_status`). A runner connection timeout stays a failure. Private-call setup-to-first-surface is unchanged |
| Private-call join (release gate) | Every user-launch call, decided over the suite | Two app-clock stages from an idle callee: the caller's dial to ringing plus the receiving phone's Accept to the first non-black picture (`release_join_gate`, below). The target stays 3 s; the recorded interim line is 90% of calls within 6 s and none over 15 s. A build without the `call_join_stage` lines leaves it UNMEASURED |
| Audio continuity | Every link | Zero audio drops. Speech-gap metrics need an accepted-PCM adapter and stay UNMEASURED (`acoustic`) |
| Media age | Every link | Capture to release or surface acknowledgement of the same source PTS across two phones, only through measured clock anchors (`score_pipeline_trace.py` `media_age` plan; compared as a value plus clock uncertainty). No target is decided yet, so the value is reported and the gate stays UNMEASURED. Local queue ages are proxies, never media age |
| Unexpected freezes, loss counters, p95/p99 frame intervals | Healthy link only | Zero, and the v3 targets. On an impaired link they are DIAGNOSTIC |
| Task success, speech comprehension | None yet | Explicit UNMEASURED families: no human or acoustic adapter exists. A required full case cannot PASS while they are UNMEASURED |

A link is *healthy* unless the request carries `media_impairment(s)` or the manifest declares
`acceptance.link_class: "impaired"` (for example offered load above the measured link capacity). It is never inferred
from the result. Identity, requested-profile faithfulness and unexpected-event gates are unchanged; the
stream join gate is defined in the table above. VMAF, SSIM, PSNR, the visible-FPS fraction, encoder FPS and offered load are DIAGNOSTIC rows: reported with
their v3 verdict as `measured_status`, never deciding a gate, family or case. Screen framing keeps its own gates. Call
limits apply to private-call receivers; stream limits to broadcast receivers.

The inventory writes the profile and its `required_families` into every bundle group (v4 drops `picture` from
the settings-sweep diagnostics and adds `media_age`, `task_success` and `speech_comprehension` to full cases). The
profile is not part of a request, so no case ID changes. To rescore retained evidence under another profile:

```
python -B tools/run_benchmark_suite.py analyze --profile device-run-v4-user --manifest PATH/manifest.json --output benchmarks/runs/RESCORE_NEW
```

`tools/score_device_run.py --profile` scores a single manifest.

### Release-gate join metric

The release gate for joining a call is a suite-level rule over user-launch calls. Join time keeps 3 s as the target and
ships against a recorded interim line: at least 90% of the calls show a real picture within 6 s, and none takes more
than 15 s.

Per call and per receiving phone the score records two stages from the app's own `BENCHMARK call_join_stage` lines
(`stage=dial|ringing|accept|first_frame_shown|...`, `role`, `sinceStartMs`, `atMs` = the phone's elapsedRealtime,
`callId`; `first_frame_shown` also carries `black=<bool>` and `meanLuma`):

- stage 1, on the caller's clock: `dial` to `ringing`;
- stage 2, on the receiving phone's own clock: that phone's `accept` to its first `first_frame_shown` with `black=false`;
- join = stage 1 + stage 2. A stage is a difference of two `atMs` values of one phone, so no clock of one phone is
  subtracted from another's. The call's join is the slower of its two receiving directions, and it needs both measured.

The score keeps both stages and the sum per direction (`release_join.directions`, and the diagnostic row
`release_join_ms` of each receiving phone). `setup_to_first_surface_ms` stays as a diagnostic against the 3 s target; in
a user-launch call it is reported (`measured_status`, `target_ms`) and never a release FAIL.

Unobservable cases are explicit. A first shown picture that is black counts only when the app logs a later non-black
one; the app logs the stage once, so a black first picture, or `black=unknown`, is UNMEASURED with that reason. A build
without the stage lines is UNMEASURED, never FAIL. A build with them whose call never rang or never showed a picture is
a failed join (the call did not connect), unless logd reported dropping that phone's lines, which makes it UNMEASURED.

`suite-result.json` carries `release_join_gate` (`tools/release_join.py suite_gate`, also shown on `report.html`): PASS
when at least 90% of the counted calls are at most 6 s and none is over 15 s; FAIL when fewer are, or when any call is
over 15 s or never joined (a measured outlier decides before the minimum is reached); UNMEASURED when fewer than 20
calls are counted. Twenty is the smallest selection where one slow call moves the share by 5 points and an outlier has
company; the release selection is about 120 calls. The gate reports the count, share, p90, median, max, the calls over
6 s and over 15 s, and the calls it did not count (UNMEASURED, benchmark-launch control arms, callees that were not
idle), each with its reason. Only user-launch calls from an idle callee count.

Idle callee. A case option `callee_idle_s` (number above 0, at most 300; direct calls only) makes the runner wait that
long, after the callee's launch has produced its `call available` line (and, in a user launch, its `rotid_own` line),
before the caller dials, so the call goes into the idle state a user calls into and not the launch burst. The caller is
launched and has discovered the callee during the wait. The manifest records `callee_idle` (anchor, requested and actual
seconds on the callee's clock); the score treats a callee as idle from 11 s. The app's fast window after an endpoint
starts is 8 s of full-duty low-latency scan (`LOW_LATENCY_SCAN_BURST_MS`) and 10 s of 100 ms advertising
(`LOW_LATENCY_ADVERTISE_BURST_MS`), then the in-place balanced step (`adv_set_churn: event=balanced_in_place`, 10.1 s
after the advertising start). Release-gate cases use `callee_idle_s: 12`
(`release_join.RELEASE_CALLEE_IDLE_S`), just beyond that window. `callee_idle_s` is part of the request, so a case that
adds it is a new case ID.

Join diagnostics and call conditions (JT2 P0). The app's DEBUG `call_join_stage: stage=tap` line (written by the dial that
follows a tap, `sinceStartMs` negative on purpose, `tapToDialMs`, `source=user|debug`) and its camera sub-stages
(`camera_open_requested`, `preview_surface_ready`, `camera_opened`, `camera_session_configured`, and `camera_first_frame` with
`sensorLagMs`, `aeState`, `exposureUs`, `iso`) parse like any stage line. `release_join` reports `tap_to_invite_ms` (tap to the
dial stage) and `tap_to_ringing_ms` on the caller's clock, the stage-1 rows (`stage1_substages`: dial to link up, link up to
authenticated, authenticated to ringing, the link description, the first `GATT video MTU` line and its delay after the link
came up, the count of status-133 setup failures between dial and ringing) and each sender's camera sub-stages
(`sender_camera`) as diagnostics: none of them enters the join or the gate. A direction whose picture stayed black (the app
logged `first_nonblack_shown ... reached=false`, every shown picture was black, or the first real picture came only after
the app's 15 s dark bound behind a black first picture) is `SCENE_DARK` (UNMEASURED, `reason_code` SCENE_DARK, listed under
the suite's `not_counted.unmeasured`), never a join value. Three case options keep old case IDs unchanged when absent:
`dial_via: intent|broadcast` (default intent), `callee_state: foreground|background|screen_off` (default foreground) and
`launch_order: [caller, callee]`. `dial_via: broadcast` sends the invite with
`am broadcast -a com.example.bluetoothtest.debug.DIAL -p <package> --es test_control call:<prefix>` so a backgrounded or
screen-off caller is reached without an activity start; the case is BLOCKED (`dial_via_not_handled`) unless the app logs
`BENCHMARK control_broadcast: control=<control> | handled=true` for it. `callee_state` puts the idle callee in the state a
user's call finds it (Home key, or KEYCODE_SLEEP) after the idle wait and before the dial and proves it from `dumpsys`
(`callee_state` in the manifest; an unreached state blocks as `callee_state_not_reached`); `screen_off` is refused at
planning unless `tools/benchmark_devices.json` says `secure_lock: false` for the callee (the ASUS has a pattern, the S24 a
PIN: both are `true`), and the runner wakes the screen again at the end. Without `launch_order` a direct call launches
phones the registry marks `slow_start` (the A15s) first, then the callee, then the caller.

The release selection comes from `tools/release_gate_selection.py --date YYYY-MM-DD` (new catalog directory
`benchmarks/catalog/release-gate-DATE/`: one suite, a bundle per lane, `selection.json` with the composition, seed,
timing basis and commands). It writes 120 `user_launch` calls tagged `release_gate` (selection id, lane, window), each
direct call with `callee_idle_s: 12` and a moving-scene environment note, in two windows because six phones cannot make
three disjoint lanes that also relay: window `direct` runs three phone pairs at once (`lane1` S24/S22 with the Aware
calls, `lane2` M31/Realme, `lane3` ASUS/A15s; every transport the pair offers at the app's default video rate, both
directions, Wi-Fi calls with `user_settings.wifi` on the pairs the scope's `carrier_eligibility` lists for that carrier),
then window `relay` runs two chains at once (`relay1` S22 via ASUS to A15s,
`relay2` S24 via M31 to Realme; on-demand, `user_settings.relayed_calls`, no `callee_idle_s`, so the join gate does not
count them). The estimate comes from the gaps of saved runs and must fit 110 minutes (`BUDGET_S`) or generation stops.
Each lane's order is a deterministic shuffle seeded `seed:lane` and the suite lists cases in run order: plan and run each
lane bundle with its `--phones` and without `--state` (the state queue re-prioritises and would undo the order).
The selection id hashes every input that shapes a request and prefixes every case id, so a changed input is a new
selection with new ids. Every endpoint of the selection takes the app's own video choice (`profile_source: app_default`,
below), and generation stops when the scope's `required_phones` are not the six release phones.

In a user launch the peer is authenticated by the call's own status lines: the link line names the peer, or, with BMH3
pseudonyms, says "an unverified stranger" until the reveal, and the line `The other phone's identity is confirmed: <node>`
that follows the verified reveal names the real node. `authenticated_peer` compares every named node with the requested
peer and stays UNMEASURED when only a stranger link was logged; `selected_transport` reads the link description either way.

### How a private-call score gets its state

A private-call score (`score.json`, `overall_status`) follows its automated gates, the ones read from the app logs
and the runner's own records:

- **PASS**: every deciding gate was measured and passed.
- **FAIL**: at least one deciding gate failed. `failed_gates` names each one with its phone and section.
- **UNMEASURED**: nothing failed, but at least one deciding gate could not be measured. `unmeasured_gates` names each
  one with its reason. Missing evidence never reads as a pass.

DIAGNOSTIC rows never decide. The score covers the log gates only: `unmeasured_families` lists the measurement
families that need their own adapter (picture, camera, acoustic, physical latency, endurance and the user families),
`quality_status` stays UNMEASURED and `full_experience_status` can never be PASS without those adapters. The suite
result keeps its own rule: a case is PASS only when the call score and every required family are PASS.

### Rules that apply to every gate

These are project engineering targets, not perceptual certification. A scoped pass does not approve another request,
build or input, or the whole experience. The unified report requires all measurement families by default; narrowing
`required_families` produces a diagnostic, not full acceptance. Changing a numerical target needs a recorded reason
and a reviewed scope and inventory change, never a lower target picked to pass a failure.

- **Separate measurements.** Picture fidelity, callback jitter and lag, frame timing, lifecycle, Camera2 behaviour,
  acoustic quality and optical (glass-to-glass) latency are separate measurements. Decoder and Texture timestamps and
  notification rates do not prove unique physical frames, screen pixels, optical blur or latency. Do not invent
  missing clock anchors; keep unknown or repeated identities and every PID, session and generation boundary.
- **Whole timeline.** Score every planned active source-clock slot and phase, including initial gaps, missing-frame
  holds and terminal tails. Prefix-only fidelity is supplemental. A planned kill does not waive an earlier freeze. Keep
  original scores and make justified attribution corrections in named revisions.
- **Offered load is measured, not assumed.** Measure the real offered load, both directions of a private call, each
  chain hop and the full planned lifecycle, and prove the authenticated original source and the requested parent.
- **Input demand.** Characterize natural and synthetic inputs at the tested geometry and FPS with both spatial and
  temporal complexity (SI/TI), keep lower-complexity controls and distinct content workloads, and measure the selected
  encoder's frame, 100 ms and 1 s bursts: an easy scene that produces fewer bits does not cover a high selected rate.
  Keep CBR preservation and explicit VBR burst experiments separate.
- **Same-input comparison.** Fixed raw or reference hashes support same-input comparisons; Camera2 without repeatable
  pixels supports only request-level comparison.

### Screen framing and resolved detail

Use a deterministic native-size calibration input with declared square pixels, distinct orientation landmarks, known
border and interior coordinates, a circle and a square. Bind its exact reference hash and the expected
rotation and mirroring. Record the actual video viewport, its screen coordinates and the selected FIT or FILL mode.
After the declared orientation, FIT uses the smaller viewport-to-content scale and preserves every edge; FILL uses the
larger uniform scale and crops the excess. Measure rotation, mirroring, horizontal and vertical scale, crop, bars and
landmark error independently against that mapping. Predeclare tolerances and validate the detector before assigning
acceptance.

Screenshots are captured output, not the reference. A screenshot's pixel dimensions do not establish the encoded
resolution or the resolved picture detail, and static framing does not measure sharpness, smoothness or latency;
native line-pair and detail charts need separate measurement. Repeat framing after orientation, video off and on, and
Surface or lifecycle changes in each eligible display role. The raster calibration scorer and the DEBUG viewport trace
exist; framing acceptance still needs the supplied native calibration chart, a captured viewport with the intended
mapping, and validated detector tolerances. Until then it remains UNMEASURED.

## Setting up your phones

### On the computer

- Android platform-tools (`adb`) on your `PATH`. The tools find `adb` on `PATH`, or under `ANDROID_HOME` or
  `ANDROID_SDK_ROOT`; pass `--adb PATH_TO_ADB` to choose another.
- Python 3.11 or newer. For scoring: `python -m pip install -r tools/requirements-measurements.txt`, and FFmpeg and
  ffprobe on your `PATH` (full reference fidelity uses libvmaf, SSIM and PSNR).
- A USB cable per phone. Wi-Fi debugging works, but USB is more predictable for long runs.

### On each phone

1. Enable Developer options and USB debugging, then authorize the computer when the prompt appears.
2. Install the debug APK you are testing and open the app once. Grant the permissions it asks for: camera,
   microphone, notifications, and nearby devices (Bluetooth scan, connect, advertise) on Android 12 and newer, or
   location on older versions. The suite preflight blocks a phone whose camera, microphone or Bluetooth permissions
   are denied.
3. Turn Bluetooth on. Keep the screen awake and unlocked for the whole run (Developer options "Stay awake" while
   charging is fine). Preflight needs explicit unlocked evidence, not only an awake screen.
4. Check the Wi-Fi band. 2.4 GHz Wi-Fi roughly halved Bluetooth throughput on one reference phone (M31 GATT about 0.4
   Mbps against about 0.9 Mbps with Wi-Fi off). Each preflight records the participants' Wi-Fi band as a radio
   condition; report it with your results and check it before blaming the hardware.
5. Close other apps that use the camera, microphone or Bluetooth, and make sure no stale MeshLive process is running
   on a phone you are not testing (isolated cases check this for every registered phone).
6. Do not attach photos or screenshots of other people to results. For live Camera2 tests point the camera at a
   controlled, non-private target such as a chart or a moving object.

### The device registry

The registry maps your aliases to adb serials and public node prefixes. It is local and Git ignores it.

```
cp tools/benchmark_devices.example.json tools/benchmark_devices.json
```

Edit the copy: one entry per phone under `devices`, keyed by an alias (lower case, for example `pixel_a`), with the
phone's adb `serial` (`adb devices`; the mDNS name for wireless debugging) and its `node_prefix`, the first 12 hex digits
of the phone's MeshLive node ID. The app shows the node ID and logs it as `BENCHMARK call available: node=...`; the
capability export below records `node_prefix` too. The example's values are placeholders. Aliases must match the
scope (`tools/benchmark_scope.json`) and `tools/device_policy_registry.json`, so add your own alias to both when you add a
phone. Reinstalling with data cleared creates a new identity, so refresh the prefix after an uninstall or reset. A registry entry is never proof that a phone is available; preflight checks it every run.

### Capability export

The suite plans cases from each phone's exported capabilities (encoders, decoders, camera modes, bitrate and
transport limits, playback options). Build and run the instrumentation test on one idle phone at a time:

```
./gradlew :app:assembleDebugAndroidTest
adb -s SERIAL install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s SERIAL shell am instrument -w -r -e class com.example.bluetoothtest.DeviceBenchmarkCapabilitiesTest io.github.meshlive.app.test/androidx.test.runner.AndroidJUnitRunner
adb -s SERIAL pull /sdcard/Android/data/io.github.meshlive.app/files/benchmark-capabilities.json benchmarks/catalog/capabilities/ALIAS.json
```

(The package name is the application ID, `io.github.meshlive.app`; test classes keep the
`com.example.bluetoothtest` Kotlin package.) Add the file to a copy of `tools/benchmark_scope.json`
under `capabilities` and add the alias to `required_phones`, then compile your inventory from that copy with
`--scope`. Keep the old capability file when you refresh one: capability refreshes keep their original evidence and
need a current eligibility check. For a phone pair you can also generate a settings matrix with
`tools/generate_device_matrix.py`.

### Verify the build and identity before every run

A phone is ready only when all of these are proven for the current run, and the read-only preflight of
`run_benchmark_suite.py` checks them for you:

- adb state is `device` and the alias maps to the serial you expect;
- the authenticated public node ID prefix reported by the app equals the registry's `node_prefix`;
- the installed APK's SHA-256 equals the frozen candidate. Compute yours with `sha256sum app-debug.apk` (or
  `certutil -hashfile app-debug.apk SHA256` on Windows). Preflight also pulls the installed base APK and rehashes it;
- the capability export's build fingerprint equals the phone's `ro.build.fingerprint`;
- Bluetooth is on, the screen is awake and unlocked, and the camera, microphone and Bluetooth permissions are granted;
- the offered capabilities include what the case requests;
- for LE L2CAP (CoC) cases, the phone's Bluetooth process is running, Android's Bluetooth manager reports the state ON,
  no earlier execution recorded that Bluetooth process as wedged, and on Android 10 fewer than 11 app processes have
  already listened on it (Android 10 never frees a closed LE CoC server, so about 12 app processes use it up).

`python -B tools/deploy_build.py --phones ALIAS1,ALIAS2 --apk PATH --adb PATH_TO_ADB` copies the built APK to
`benchmarks/builds/<first 16 hex of SHA-256>/` and installs it on the phones in parallel. A normal reinstall and an
uninstall or reset have different identity and persistence consequences; check the node prefix and installed hash
again after any retry.

## The benchmark workflow

All phone-facing orchestration is `tools/run_benchmark_suite.py` with five commands: `plan`, `run`, `analyze`,
`review` and `finish-analysis`. Compiling the inventory, binding supplied inputs, joining case state, planning,
analyzing saved recordings and reviewing outputs are all offline. Only `run` (and the focused diagnostics later in this
document) touches a phone.

### Where things live

Four groups, all under `benchmarks/`:

```text
benchmarks/
  inputs/INPUT_ID/                 reusable native media, declarations and licences
  builds/APK_SHA_PREFIX/           frozen APK, its verification, logs and change impacts
  catalog/                         inventory, case state, plans and capability provenance
  runs/EXECUTION_OR_MEASUREMENT/   results, manifests, raw evidence and analysis together
```

Git tracks source, documents, tools and small benchmark definitions (capability exports, the workflow example).
`benchmarks/runs/`, `benchmarks/builds/` and large `benchmarks/inputs/` content stay local and are git-ignored. Report
numbers, phone aliases and models, and short build hashes in your pull request; do not commit raw captures, traces or
media.

Each suite writes one directory `benchmarks/runs/EXECUTION_ID/` with `suite-result.json` (authoritative results,
family verdicts, limits and evidence), `report.html` and the cases' physical evidence: `manifest.json` (actual
identities, build, settings, input, actions, clocks and PID scopes), per-case `analysis.json`, CSV samples, raw logs
and any captured media. `state.json` and `remaining.json` in the catalog hold exact completion and retest state. A
single score file cannot approve a whole case. A fresh directory identifies each distinct input, build, execution or
measurement, and every capture or helper writes to an explicit output it owns, never to loose files at the project
root. Host scratch and review worksheets belong in the operating system's temporary directory. Keep final results,
manifests, request, input and build identities, licences and useful decisions; keep extra files only while a named
calculation, unresolved failure or comparison needs them.

### Step 1: compile the inventory

```
python -B tools/generate_benchmark_inventory.py --scope tools/benchmark_scope.json --output benchmarks/catalog/INVENTORY_NEW
```

This reads the capability exports named in the scope and the device registry, performs no device I/O, and writes
`benchmark-bundle.json`, the suites, `inventory.json`, `summary.json`, `settings-sweep-bundle.json` and a native
`input_requirements.json` (the media variants the cases need). A full compile of the reference scope writes about 27
MB and takes under a minute. Check the profile and assignment coverage, blocked reasons and the capability path, hash,
fingerprint and node of each phone. `--phones a,b` limits which cases are *executable* while keeping the omitted
assignments and their reasons; it is a selection, not live availability. Missing capability evidence, incompatibility
and unavailability are distinct reasons. The scope's optional `carriers` and `carrier_eligibility` lists add Wi-Fi carrier requests as new case IDs; a scope without them compiles to exactly the earlier inventory. `wifi_aware_native` requests are generated and shown BLOCKED (the runner cannot turn Bluetooth off).

A case keeps every part of its request explicit: ordered sender, receiver and middle assignments, both call setup
roles, both outgoing profiles, transport, encoder and allocation, geometry, FPS and rate, buffers, lifecycle and input
type. Large residual products are represented by dimensions and counts and stay UNMEASURED; a generated subset does
not complete them. Lifecycle templates (pause and resume, background and foreground, receiver or middle kill and
rejoin, call controls, decline and cancel) run for at least 120 s and do not cover every profile-by-fault
combination, front camera, real heat or endurance. Generated suites default to Camera2; raw-media cases are separate
requests (see Inputs).

### Step 2: join the case state

```
python -B tools/benchmark_case_state.py state --suite benchmarks/catalog/INVENTORY_NEW/benchmark-bundle.json --devices tools/benchmark_devices.json --phones ALIAS1,ALIAS2 --apk-sha256 APK_SHA256 --output benchmarks/catalog/STATE_NEW
```

State joins the inventory with any finished reports and recorded changes and writes `state.json` and `remaining.json`
(the output directory must be new). `--phones` names the phones you will use; availability never deletes scope, so
cases that need other phones stay in the state as NOT RUN. Repeat `--reports` with earlier `suite-result.json` files
and `--changes` with change-impact files to carry history forward. Exact keys, participant identities, referenced
manifest hashes, APK and PID scopes guard reuse; resemblance is not enough.

Case states:

| State | Meaning |
| --- | --- |
| NOT RUN | No qualifying exact execution; schedule when eligible |
| PASS / FAIL | A verified execution met or failed the gates on its recorded build |
| NEEDS RETEST | A later change affects an executed case; the old result, build, evidence and change reason are kept |
| UNMEASURED | Required evidence is missing or invalid; the missing adapter is named |
| BLOCKED | A concrete prerequisite failed; retry explicitly after new proof |
| INTERRUPTED | Execution or scoring stopped; partial evidence is kept and a fresh output is used |
| RUNNING / ANALYZING | Transient checkpoints, not completion |

Last measured result, freshness and availability are independent. A blocked retry keeps the earlier result and its
retest requirement; an untested affected case stays NOT RUN. A pass on a prior build is never labelled a pass on the
candidate build. A generated case, a handshake, a first frame, a fallback, changed settings or a partial prefix is
never a PASS.

### Step 3: plan

```
python -B tools/run_benchmark_suite.py plan --suite benchmarks/catalog/INVENTORY_NEW/benchmark-bundle.json --devices tools/benchmark_devices.json --phones ALIAS1,ALIAS2 --state benchmarks/catalog/STATE_NEW/state.json --apk-sha256 APK_SHA256 --parallel 1 --output benchmarks/catalog/PLAN_NEW
```

`plan.json` lists the selected cases, jobs and batches, and the requested media seconds. Selection puts failures and
NEEDS RETEST first, then untested and unmeasured work, and favours less-used phones and roles so coverage rotates
across transports, loads and topologies. Read it for gaps before you run. An empty `--phones=` preserves every case
and selects none; it is not completion. State filtering never replaces live preflight.

### Step 4: run

```
python -B tools/run_benchmark_suite.py run --suite BUNDLE --devices tools/benchmark_devices.json --owner YOUR_NAME --phones ALIAS1,ALIAS2 --state benchmarks/catalog/STATE_NEW/state.json --apk-sha256 APK_SHA256 --adb PATH_TO_ADB --parallel 1 --output benchmarks/runs/RUN_NEW
```

`run` repeats the preflight, launches the participants, drives the planned actions, collects traces, stops the
participants during cleanup and independently verifies that no app process remains before it releases the phones.
Offline, erroring or still-live processes keep the phones' leases quarantined until you resolve that exact lease. Omit
`--case` to take the assigned queue, or repeat `--case CASE_ID` for a focused comparison or an explicit retry of a
blocked case. A bundle can select original case IDs without copying them:

```json
{"suites":[{"path":"PATH_TO_SUITE.json","case_ids":["EXACT_CASE_ID"],"execution":"isolated"}]}
```

An explicitly empty `case_ids` selects nothing and blocks an empty run. Generated bundles mark cases concurrent;
causal bundles declare them isolated. Isolated cases also check that every registered non-participant is reachable and
has no MeshLive process; an unknown quiet state blocks.

- `--defer-analysis` does the phone work, capture and cleanup, then exits with each case marked `PENDING_ANALYSIS`
  (neither scored nor passed). Start the next suite on the freed phones and score in the background with
  `run_benchmark_suite.py finish-analysis --output benchmarks/runs/RUN_NEW [--parallel N]`. Picture scoring uses
  `BENCH_SCORE_THREADS` threads (default 4); scores are identical at any thread count.
- `--hci-snoop {off,auto,on-failure,required}` (default `auto`) pulls each participant's HCI snoop log for the case
  window after cleanup. It is diagnosis evidence, never a pass or fail, and only `required` blocks a phone whose snoop
  is disabled. Snoop files hold every Bluetooth packet in the window, including from unrelated devices: keep them only
  for a named failure diagnosis. `--hci-snoop-scratch DIR` (or `HCI_SNOOP_SCRATCH`) holds the large bug-report zips.
- A failed broadcast run tries End only on its recorded live source process before pulling traces. Every run has a
  unique trace session ID.
- Android's logger can drop app lines under a burst from another process (an Android 11 phone lost them at camera open).
  liblog then writes the count to the events buffer under the tag `liblog`. Beside each app log, the runner captures
  that process's drop reports, merges the end-of-run events-buffer dump and hash-binds the file in the manifest. A
  one-time line that never arrived (call phase ACTIVE, a control acknowledgement, a relay join, the first surface) is
  UNMEASURED with the reason "log lines dropped by logd" when the process reported drops where that line was due: a
  report stamped after the point the line became due (the previous call phase, the control request or the launch)
  and, when a later line such as ENDED was stored, no later than that line. Without such a report
  the missing line stays a failure. A present line counts as before. ACTIVE is judged on caller and callee alike. A
  drop that the process never reported, because it wrote nothing more before it exited, stays invisible.
- In a private-call score a missing line is never a failed call by itself. A gate that is UNMEASURED because a
  line of that phone is absent (identity, profile, first surface, final counters) carries the reason "log lines
  dropped by logd" when the phone's drop capture reports drops where the line was due (after the launch for
  launch-time lines, after the dial for call lines), with its earlier reason kept as `detail`. A control whose
  acknowledgement the runner never saw is UNMEASURED, not a failed control, when the phone reported drops after the
  request. A dropped ACTIVE line is proven by the peer when the peer's log shows this phone's call picture
  (`source: peer_rendered_frames`); without that proof it stays UNMEASURED, and without a drop report a missing
  line stays a failure. A counter the app never logs is not blamed on log drops.
- A call case never skips its dial silently. When the runner stops before sending the invite, the manifest records
  `call_not_dialed` (the step it stopped at and the reason), the score carries `call_dialed: false` and `not_dialed`,
  and the suite's case result repeats both. No call gate is judged for a call that was not dialed (each reads
  UNMEASURED "The call was not dialed"); the runner entry alone decides: UNMEASURED when the stop was a confirmed log
  drop, BLOCKED for the phone's own Bluetooth stack, FAIL otherwise.
- During a private call the runner stops the case on an end line (`Private call ended:` or its UI teardown record)
  that it did not plan. Only an end of the measured call counts: the measured call is the first one that went ACTIVE
  after the runner's dial, and an end line stamped before that moment on the same phone closed an earlier setup
  attempt (a first attempt that failed before any ring, followed by a retry that connected). Such a line is recorded
  as a `setup_attempt_failed` runner event and the case runs on to its planned End; the score lists it under the
  endpoint's `setup_attempts_failed`, the unexpected-event gate still counts it, and the join time still includes the
  failed attempt. When logd dropped the ACTIVE line the first media evidence the runner waited for on that phone is
  the proof, and with neither only lines from before the dial are set aside. A call that dropped and came back is
  still an unexpected termination. A decline or cancel is proven only by an end line after the phone saw the
  dialled attempt.
- The freeze the FINAL continuity report counts at the planned hang-up (the gap since the peer's last frame) is
  planned, not an unexpected freeze: only a FINAL report logged inside the phone's confirmed planned-end interval, and
  only one freeze event of at most 1.5 freeze thresholds, is listed as `planned_end_freezes` instead of counted. A
  freeze in a periodic report before the End, a longer one, or a call with no confirmed planned End stays unexpected.
  A Wi-Fi `BENCHMARK carrier_death:` line counts as an unexpected event (`unexpected_events`) unless it is the close of the
  planned hang-up: reason `read failed: EOFException` or `SocketException`, logged inside the phone's confirmed
  planned-end interval or up to 1 s before it (the phone that did not hang up sees the peer close the socket 0.1-0.3 s
  before its own `call phase ENDED`; when the other phone's terminal records were lost and no interval is confirmed, the
  phone's own last `call phase ENDED` with an acknowledged runner End control, within 1 s either side, is enough, and
  the hang-up phone's ENDED must also lie within 2 s of its End acknowledgement). A `write stalled` or `silence` death, a death anywhere else, and any death in a call
  with no confirmed planned End stay unexpected.
  The central's own clean close of the GATT link (`BENCHMARK ll_gate: ... event=link_lost | status=0 | class=clean`) is
  planned inside the same windows; another status or class, or the same line mid-call, stays unexpected. When logd
  confirmably dropped the app's `Private call ended` or `call available` line after the End, the runner records it in the
  End control's `terminal_log_loss` and keeps the case running: `ended_and_available` is UNMEASURED with the log-loss
  reason and the manifest has no `runner_error`; a missing line with no confirmed drop is still a runner error.
  With such a lost end proof no planned End is confirmed for either phone, so a single short FINAL-report freeze (the one
  the planned-end rule would have judged) makes `unexpected_freeze_events` UNMEASURED with the log-loss reason instead of
  a failure (listed as `unjudged_end_freezes`); freezes in earlier periodic reports and longer ones are still counted.
  `relay_unplanned_closures` counts every `RELAY_CLOSED` line in the relay's own log that is not after a planned
  terminal control (`end`, `kill`, `decline`, `cancel`), compared on the host clock (the relay's launch plus the line's
  offset against the control's host timestamp); a closure that cannot be placed is UNMEASURED, never PASS.
  `app_crash_or_anr` reads one bounded dump of each participant's crash and system log buffers taken when the case
  ends (`<phone>-crash-events.log`, hash-bound in the manifest's `crash_logs`) and fails the phone with a Java crash,
  ANR or native crash of the app inside the case window; an unreadable dump is UNMEASURED, and manifests recorded
  before this capture carry no such gate. The runner presses volume-down eight times on every participant once the call
  is accepted (never volume-up); a phone that refuses shell input is recorded as `volume_down_failed` and the call
  goes on.
- An experiment arm must be seen applied. After each launch the runner reads the app's `BENCHMARK launch_extras:`
  echo and the effect lines it knows (`applied_arms.EFFECT_LINES`) and records, per requested `test_` extra,
  `delivered` and `applied` (`applied` is `null` where no effect line is verified, never true by default) in the
  manifest's `applied_arms`. An extra the echo does not list, or an effect line that shows another value, blocks the
  case before the dial (`runner_blocked` reason `arm_not_delivered` or `arm_not_applied`); a missing echo is unknown.
  Preflight scans the frozen APK's dex for each requested tunable's extra name and blocks with `tunable not in this
  build` when it is absent (`tunables_in_build` in `preflight.json`). Before a case starts, preflight reads every
  participant's thermal status and waits (30 s polls, at most 10 minutes) while any is MODERATE (2) or higher, then
  blocks the case with `blocked_reason: thermal`; the status at the start and each poll are recorded in
  `preflight.json` (`thermal_gate`). The APK pull wakes the phone first and is retried once after a timeout.
- A phone's Bluetooth stack can stop a call by itself. On one phone the stack thread that reads LE CoC data stopped
  during a call while other stack threads kept logging; the app then ended the call as "Call peer stopped responding",
  and every later listen in that Bluetooth process hung inside the stack. After each call run the runner checks the
  Bluetooth process log: when that thread was silent for 5 s or more before such an end while other threads logged,
  the run fails with the reason `local_bt_stack_wedged` instead of blaming the peer, and later CoC preflights block
  that Bluetooth process. While waiting for `call available`, the runner stops early with BLOCKED
  `coc_listen_stalled_in_stack` when the app reports a stalled listen or the stack began a listen for the app and
  allocated no socket within 3 s, and BLOCKED `coc_listener_exhausted` when the listen threw `IOException: Error: -1`
  (Android 10). A stack that wedged during the run's own call keeps that call's failure.
  The runner never repairs a phone: restarting Bluetooth belongs to the phone's owner. On Android 12 and older use
  the debug Bluetooth restart receiver and check it reports a verified LE reset; elsewhere turn Bluetooth off and on,
  or restart the phone if Bluetooth does not come back.
- A requested arm that cannot act is refused, not scored: a switch can be inert because its parent is off or because
  another switch already decides the same thing (the register's "Requires / masked by" notes list the parents).
- If a maintainer asks you to stop or pause physical testing, stop all device activity, keep the interrupted evidence
  and continue offline.

### Step 5: analyze

```
python -B tools/run_benchmark_suite.py analyze --manifest benchmarks/runs/ORIGINAL_RUN/CASE/physical/manifest.json --output benchmarks/runs/ANALYSIS_NEW
```

Repeat `--manifest` for more recordings; add `--baseline PATH/suite-result.json` to compare, `--measurements FILE` to
attach observation adapters, and `--profile` to score under another acceptance profile. Analysis reads originals
without a phone and never rewrites saved evidence. The unified output holds the plan, selection and execution
records, `suite-result.json`, `coverage.json`, `report.html` and per-case evidence. Read each family's gates and
reasons: picture, smoothness, connection, transport, lifecycle, topology, camera, acoustic, physical latency and
endurance. Missing Camera2, acoustic, optical, endurance and radio-impairment evidence stays in the report; adding an
adapter cannot erase another family's failure. A call needs both setup roles and both media directions; a chain needs
every edge.

### Step 6: purpose review of a new output

When all writers stop, a run or analysis freezes a review of its fresh files inside `suite-result.json`. Result and
identity files are protected; unreferenced logs, XML, command output, CSV, media and empty files start PENDING instead
of being kept forever. Read each pending file, then consume its exact relative path or name a remaining purpose:

```
python -B tools/run_benchmark_suite.py review --output benchmarks/runs/RUN_NEW --consume CASE/setup.log --purpose 'Setup inspected; no remaining use'
python -B tools/run_benchmark_suite.py review --output benchmarks/runs/RUN_NEW --retain CASE/comparison.json --purpose 'Compare unresolved decoder failure at the same input and settings'
python -B tools/run_benchmark_suite.py review --output benchmarks/runs/RUN_NEW
```

Take paths from `artifact_review.files`. Changed bytes, traversal, links and evidence that something still needs block
consumption before deletion. A PENDING review exits non-zero; COMPLETE describes storage review, not a phone pass.
Empty files can be meaningful failure evidence, so neither zero bytes nor a file extension decides retention.

### Step 7: checkpoint (when you keep a shared ledger)

The project's maintainers keep a local machine ledger (`benchmarks/catalog/ledger.json`, not in Git) and a derived
readable view, `benchmarks/index.html`. A fresh clone has neither, and `plan`, `run` and `analyze` do not need
them. If you keep your own, update it after each finalized, reviewed execution:

```
python -B tools/benchmark_checkpoint.py update --phones ALIAS1,ALIAS2 --apk-sha256 APK_SHA256 --report benchmarks/runs/LATEST_RUN/suite-result.json --changes PATH/change-impact.json
python -B tools/benchmark_results.py
```

`benchmark_checkpoint.py` verifies the complete matching state and the evidence before it replaces the state, the
remaining selection, the ledger and the index, and it rejects dropped measured history, invented or stale passes and
incomplete purpose reviews. `check` verifies without writing. `benchmark_results.py` refreshes `index.html`, which
checks the ledger's hashes and counts; it executes nothing, rescores nothing and is a derived view, not a second
results ledger. Do not keep a second Markdown status ledger.

### Changes and retesting

Before scheduling preservation tests for a behaviour change, record its ID, the actual time with a UTC offset, the
changed files, the reason and the affected scope:

```json
{"changes":[{"id":"CHANGE_ID","at":"ACTUAL_ISO8601_TIME_WITH_OFFSET","files":["EXACT_CHANGED_FILE"],"reason":"Changed behaviour, measured basis and preservation scope"}]}
```

Without `affects`, every earlier executed case conservatively needs a retest. A narrower selector uses non-empty exact
lists for `transports`, `phones`, `encoders` or `carriers` (all given dimensions must match; phones match any participant; encoders
match the outgoing profiles), and the `reason` must give the evidence for excluding other combinations. A change to a
shared stage (receive, decrypt, decoder, controls) affects every combination using that stage, not only the failing
pair. A change that touches no measured behaviour (documentation, test sources, DEBUG logging that only reports) may
declare `"affects": "none"` with an `"affects_none_reason"` saying why; it is recorded but retests nothing, and
behaviour changes never use it. Rescoring old recordings is not an execution after a fix. Pass the file to state joining or the checkpoint with
`--changes`; a duplicate ID with a different definition is rejected.

Adding a phone extends the registry and scope and preserves unrelated case IDs. Changing settings, topology, controls,
duration or input creates a new case with no inherited pass. Retire obsolete requirements explicitly in the scope's
`retired_requirements` with an ID and reason, and keep meaningful old evidence.

A gate change (a new acceptance profile) changes no request, so it retires gate requirements rather than case IDs:
set `acceptance_profile` in the scope, regenerate into a fresh catalog directory and review the diff (case IDs
unchanged); record a change with `"acceptance_profile":"device-run-v4-user"`, naming the retired targets in its
reason; each affected case becomes NEEDS RETEST until a result scored under the new profile exists, keeping its old
result, build and evidence (an offline `analyze --profile` of the retained manifest resolves it without a phone);
then checkpoint with the rescore report. A v3 PASS is never shown as a v4 PASS. Write the measured reason in
[decisions](decisions/README.md).

## Inputs: supplied raw video and audio

New source workloads use declared generated raw media, licensed natural footage or a controlled non-private scene.
Keep synthetic, natural-stress and live Camera2 coverage separate: generated or raw playback does not exercise Camera2
sensor, stride or exposure behaviour. The input table below says what each input can and cannot measure.

| Input | Can measure | Separate requirements |
| --- | --- | --- |
| Synthetic raw | Selected encoder, delivery and decoder, declared stress and events | Natural demand, Camera2, physical pixels, optics, acoustics |
| Natural recorded raw | Repeatable real encoding and natural demand | Camera2 controls, physical blur and delay, acoustic recording |
| Finite encoded IVF replay | Transport and decoder isolation | Live encoding, Camera2 and microphone |
| Live Camera2 and microphone | Capture, control and the A/V pipeline | Aligned reference, physical delay, blur and acoustic quality |

Raw can loop with declared seams and frame maps; encoded replay cannot, so it must outlast setup plus measurement.
Recorded raw and replay orientation is distinct from sensor and display rotation. Native 50 -> 30 FPS sampling
produces non-uniform content steps; integer 120 -> 30 sampling is different, so keep those transformations when you
compare motion. Invalid dimensions or an exhausted replay cannot pass quality or freeze gates. Private calls reject
replay (their runner has none) and never silently fall back to Camera2.

### The raw-media contract

Current raw phone input is tightly packed 8-bit I420 with even geometry and an integer profile FPS: `width * height *
3 / 2 * frame_count` bytes. Broadcasts use `raw_fixture_file`, calls use `raw_fixture_files` keyed by each endpoint, and
chains bind the original source. Other formats or fractional FPS need a declared conversion and transformed hashes;
they are not reinterpreted. A correct file size alone does not validate colour, geometry or display: the runner passes the declared geometry as `test_raw_width` and `test_raw_height`, and the app refuses (`BENCHMARK raw_source: status=REFUSED`) a file that is not a whole number of those frames or an encoder that runs at another size, never reinterpreting or rescaling. The refusal also logs `BENCHMARK setup_refused: reason=raw_fixture_geometry | requested=WxH | effective=WxH | detail=...` on the sender (the receiver only sees a stream end: the lifecycle record carries no reason). A DEBUG launch that names an encoder (`test_codec`) or bitrate allocation (`test_rate_control`) the phone lacks logs `BENCHMARK setup_refused: reason=encoder_unavailable|rate_control_unavailable|rate_control_unknown|...` and finishes the task; it never substitutes another encoder or mode. A DEBUG `voice_call`/`call`/`invite` control waits up to 10 s for the target's next sighting and always ends in a `BENCHMARK call_invite:` line (`refreshing_address`, `waiting`, `peer_seen`, `refused`, `failed` with `reason=refresh_timeout|peer_not_seen|scanner_unavailable|...`); a user tap on a person uses the same 10 s wait, survives a discovery restart (onResume) and logs the same lines in DEBUG builds (`userDial=true` on `refreshing_address`); an "applied" control line means the dial was accepted, not that an invite started. Each variant
needs:

- a stable pattern ID, generator and version, seed, parameters, provenance and licence, and a reproducible schedule;
- a lossless native reference with the exact frame count, byte layout and SHA-256 of every file (declare any
  conversion, crop, scale, rotation or subsampling);
- width, height, rational FPS and timebase, plane order and stride, chroma location, bit depth and the original colour
  range, primaries, matrix and transfer (or an explicit `UNSPECIFIED`); no hidden interpolation or speed change;
- per-frame IDs and event sidecars for motion, cuts, flashes, audio and loop boundaries;
- robust visible markers with checksum and redundancy, pixel bounds, the exact marked reference and a content ROI
  declared in advance (never change the ROI after seeing a bad score);
- for audio, the reference sample rate, channels, count, hash and event sample indices. A reference does not prove
  injection, routing, clock alignment, echo, lip sync or acoustic quality.

A `raw-fixtures.json` declares your files. Relative paths resolve beside the manifest:

```json
{
  "schema_version": 1,
  "fixtures": [{
    "width": 1280, "height": 720, "fps": 30,
    "format": "I420", "bit_depth": 8,
    "path": "native-1280x720-30.yuv", "sha256": "EXACT_RAW_SHA256", "frame_count": 150,
    "color": {"range": "limited", "primaries": "bt709", "matrix": "bt709", "transfer": "bt709", "chroma_location": "left"},
    "sidecars": {"frames": {"path": "frame-ids.csv", "sha256": "EXACT_SIDECAR_SHA256"}}
  }]
}
```

Replace every example value with real evidence. Sidecars are optional for integrity binding, but the full measurement
contract still needs the appropriate frame, event and audio evidence. Declare a lossless reference as
`sidecars.reference` with its hash. Include both geometry/FPS variants for an asymmetric call. Duplicate variants are
ambiguous; bind different clips separately.

### Binding inputs to cases

```
python -B tools/bind_benchmark_inputs.py --suite BUNDLE --case EXACT_CAMERA2_CASE_ID --fixtures benchmarks/inputs/MY_INPUT/raw-fixtures.json --devices tools/benchmark_devices.json --output benchmarks/catalog/BOUND_NEW
python -B tools/benchmark_case_state.py state --suite benchmarks/catalog/BOUND_NEW/benchmark-bundle.json --devices tools/benchmark_devices.json --phones ALIAS1,ALIAS2 --apk-sha256 APK_SHA256 --output benchmarks/catalog/BOUND_NEW
python -B tools/run_benchmark_suite.py plan --suite benchmarks/catalog/BOUND_NEW/benchmark-bundle.json --devices tools/benchmark_devices.json --phones ALIAS1,ALIAS2 --state benchmarks/catalog/BOUND_NEW/state.json --apk-sha256 APK_SHA256 --parallel 1 --output benchmarks/catalog/BOUND_NEW
```

The binder accepts explicitly selected, unbound Camera2 cases whose native geometry and FPS match (repeat `--case` for
more). It checks the file SHA, whole-frame bytes, frame count, layout and non-empty original colour declarations, and
writes a raw suite and bundle, a binding manifest and an exact `source-fixtures.json` copy. It does not generate,
resize or convert media, change FPS, or validate marker, ROI, frame-ID, audio or encoder colour behaviour. New raw case
IDs start NOT RUN and are separate coverage from the original Camera2 cases, which stay intact. Replay, camera-flip,
mixed and already-bound requests are rejected, and raw hashes are rechecked before app launch. Add
`--with-reference-audio` only to create separate A/V reference cases: it needs `sidecars.reference_audio`, and the
runner stages and rehashes native mono PCM16 at 48 kHz with no mixing, resampling, gain change or microphone fallback;
the WAV length must equal the raw-video loop in whole samples.

### The test-video generator

`tools/video-stress-generator/` (MIT licensed, Python package `videostress`) deterministically renders native-size
stress video with exact RGB and I420 references, per-frame IDs, events, optional PCM audio and SI/TI measurements. It
verifies source media only; encoding, transport and acceptance belong to this app's tools. It needs Python 3.11 or
newer, NumPy and Pillow (FFmpeg is optional).

```
cd tools/video-stress-generator
python -m pip install -e ".[test]"
python -m videostress --help
python -m videostress dry-run --config examples/sustained.json --mode 720x480@30
python -m videostress generate --config examples/sustained.json --mode 720x480@30 --output OUTPUT_NEW
python -m videostress validate OUTPUT_NEW/raw-fixtures.json
```

`dry-run` estimates bytes, memory and work first (a 60 s 720x480 at 30 FPS I420 reference is about 0.93 GB);
`generate` needs an unused output directory; `validate` re-checks hashes, the file contract, reference pixels, sidecars
and audio. `--mode` takes `WIDTHxHEIGHT@FPS` (even sizes, integer FPS) or a preset and can be repeated. Generate only
the modes your next comparison needs: one native source serves many phone roles, bitrates and encoders. Recipes in
`examples/`: `sustained` (60 s in six 10-second sections from quiet to an entropy burst), `mosaic` (60 s, four native
competing panels), `framing` (12 s calibration chart), `quick-mosaic` and `coverage` (20 s). Sustained and mosaic carry
twenty audiovisual cues and 40 s of seeded PCM activity. A duration that the selected FPS cannot represent exactly is
rejected, never rounded.

`tools/prepare_native_workload.py` wraps the generator for the benchmark workflow: it freezes the generator source and
recipe hashes (and refuses a generator that differs from the frozen implementation), checks the byte estimate before
writing, then generates and validates one selected mode. It uses `tools/video-stress-generator/` by default; set the
`VIDEO_STRESS_GENERATOR` environment variable (or pass `--producer`) to use a checkout elsewhere.

```
python -B tools/prepare_native_workload.py plan --recipe sustained --mode 720x480@30
python -B tools/prepare_native_workload.py prepare --recipe sustained --mode 720x480@30 --max-bytes 1900000000 --output benchmarks/inputs/SUSTAINED_INPUT_NEW
```

Planning writes nothing and opens no hardware; the estimate is not a disk quota. Reuse one input for several phone,
codec and rate cases. A 45 s settings sweep cannot cover a 60 s programme, so pick a planned active window that does.
Changing duration or settings creates a new case; loop seams stay scored and longer endurance is separate.

### Speech reference audio (speech-v1)

The synthetic loop (sines, chirps, noise) stays the timing and gap reference. `speech-v1` is a separate generated input
of openly licensed recorded speech for Opus at speech rates and decoded-PCM quality: a 60 s and a 20 s file, PCM16
mono 48 kHz, described in [../benchmarks/inputs/speech-v1/README.md](../../benchmarks/inputs/speech-v1/README.md). The
audio and its sources are committed with their licences (attribution in
[../THIRD_PARTY_NOTICES.md](../../THIRD_PARTY_NOTICES.md)). Sources: LibriSpeech test-clean (15 voices; CC BY 4.0, Copyright 2014
Vassil Panayotov, https://www.openslr.org/12/ -- keep this attribution with any copy or derived file), LibriVox German
and Spanish readings (public domain) and a Kimiko Ishizaka Goldberg Variations clip (CC0). URLs, hashes, transcripts, the
mixing recipe and the per-segment map are in `speech-v1.provenance.json`.

```
python -m pip install -r tools/requirements-audio.txt
python -B tools/build_speech_reference.py fetch --output benchmarks/inputs/speech-v1-sources
python -B tools/build_speech_reference.py build --sources benchmarks/inputs/speech-v1-sources --output benchmarks/inputs/speech-v1-local
python -B tools/build_speech_reference.py verify --sources benchmarks/inputs/speech-v1-sources --build benchmarks/inputs/speech-v1-local
```

`fetch` needs the network (it streams a 347 MB archive once and keeps about 2.4 MB). `build` is deterministic from the
cached sources, and `verify` rebuilds in temporary scratch and compares every hash with the provenance. The tools write
only to new directories.

Loop rule: the runner needs reference-audio samples to equal the raw-video loop in whole samples. Pair the 60 s file
with 60 s native fixtures and the 20 s file with 20 s fixtures; nothing is padded, trimmed or tiled, so other loop
lengths (such as the 12 s framing recipe) have no speech reference. `build_speech_reference.py attach --fixtures
GENERATOR_OUTPUT/raw-fixtures.json --speech DIR --output NEW_DIR` points `sidecars.reference_audio` at the matching WAV
(excluded fixtures are listed); then bind with `--with-reference-audio`. Speech cases carry no cue schedule, so they
measure audio quality and dropouts, not A/V sync.

`tools/score_audio_quality.py` scores one finalized receiver PCM trace against the exact reference: gap events and
share, speech-frame dropout share, level error, the loudness-step response, and per-item STOI/ESTOI and PESQ. There are
no validated acceptance tolerances, so results are diagnostics, and speaker, microphone and air-path quality stays
UNMEASURED. ViSQOL runs only through `--visqol-binary` with an external build.

**PESQ warning (opt-in).** PESQ lives in a separate file, `tools/requirements-audio-pesq.txt`. The `pesq` package wraps
the ITU-T P.862 reference code, which carries ITU-T licence restrictions (read them: they limit modification,
commercial use and redistribution) that differ from this project's GPL-3.0-or-later licence. It is not part of the app
and `tools/requirements-audio.txt` does not install it. Run `python -m pip install -r tools/requirements-audio-pesq.txt`
only if you accept those terms; without `pesq` the PESQ score is reported as UNMEASURED.

## Measurement adapters and joining observations

The observation tools turn recordings and traces into hash-bound measurements that join a run. Install the
dependencies with `python -m pip install -r tools/requirements-measurements.txt`; FFmpeg and ffprobe must be on your
`PATH`. All inputs and specs are hash-bound and outputs must be fresh. Host regressions use temporary directories and
never add phone passes.

| Tool | Input and result |
| --- | --- |
| `score_input_complexity.py` | Unmarked native I420 reference and candidate with equal declarations; per-frame Sobel SI and temporal-difference TI (median, p95, maximum, CSVs). A numeric comparison cannot approve natural demand, encoder load or a phone |
| `score_visual_markers.py` | Native frames with frame, event and layout sidecars; decodes the fixed marker cells with checksum and ambiguity handling; counts missing, repeated and out-of-order frames, pacing, freezes and recovery. Screen sampling needs an independent calibrated mapping |
| `extract_measurement_capture.py` | A hashed recording becomes RGB24, original frame timestamps, a native PCM16 48 kHz WAV and `capture.json`; multichannel input needs an explicit `channel_index`; no resize, frame-rate synthesis or autorotation |
| `prepare_audio_track_observation.py` | The actual receiver WAV, chunk CSV and summary with run, device, PID, session and clock anchors. Missing, reset or stale timestamps forbid interpolation; AudioTrack estimates need recorded clock brackets, never an invented zero-time anchor |
| `score_audio_sync.py` | Injected reference WAV and events against a captured WAV with bounded cue searches; reports missing or ambiguous cues, clipping, A/V offset and content versus clock drift. Acoustic quality stays separate |
| `score_screen_geometry.py` | Declared calibration landmarks, an actual raster capture, an independent viewport and FIT or FILL mapping; measures orientation, aspect, crop and borders. Acceptance needs retained detector-validation evidence |
| `score_reference_regions.py` | Run by whole-phase fidelity when native sidecars exist: per-slot scores with the fixed content mask and per-panel PSNR and SSIM for a mosaic; supplemental only |
| `capture_benchmark_observation.py`, `score_physical_av.py` | Independent camera and microphone recording and its physical A/V observation (original PTS, two native ROIs, predeclared detector, search and interval). Generated, proxy or unregistered evidence cannot pass physical gates |
| `score_pipeline_trace.py` | Capture, encode, queue, write, receive, decode and release spans, load windows, lifecycle recovery, resource coverage, media overlap and optional cross-phone `media_age`. Unanchored clocks, duplicate PTS and unfinalized scopes keep it UNMEASURED |
| `analyze_perfetto_trace.py`, `score_impairment_trace.py` | A retained scheduler trace with a hash-frozen trace processor (never an RF or socket cause); a controlled-impairment log recomputed against its declared config (a harness result, not streaming performance) |

Observation scorers take `--spec SPEC.json --output OUTPUT_NEW.json`; a spec belongs beside the evidence it measures.
Imported recordings need their actual capture provenance recorded separately, because the importer cannot prove which
phone or session produced a file.

### Attaching adapters to a run

Prepare explicit bindings so you do not copy run, case and APK hashes by hand:

```json
{"schema":"benchmark-measurement-bindings-v1","adapters":[{"id":"receiver-markers","kind":"visual_markers","spec":"markers-spec.json","extraction":"capture/capture.json","scope":{"device":"RECEIVER_ALIAS","source":"SOURCE_ALIAS","pid":123,"session":"ACTUAL_TRACE_SESSION"}}]}
```

```
python -B tools/prepare_benchmark_measurements.py --manifest benchmarks/runs/ORIGINAL/CASE/physical/manifest.json --bindings benchmarks/runs/ORIGINAL/measurements/bindings.json --output benchmarks/runs/ORIGINAL/analysis/bindings
python -B tools/run_benchmark_suite.py analyze --manifest benchmarks/runs/ORIGINAL/CASE/physical/manifest.json --measurements benchmarks/runs/ORIGINAL/analysis/bindings/measurements.json --output benchmarks/runs/ANALYSIS_NEW
```

The helper validates the execution and capture scope and writes `measurements.json` and `preparation.json`; it never
infers source, PID, session, clocks, mapping or tolerances. READY means a valid binding, not a measured gate; INVALID
adapters stay visible and exit non-zero. `kind` is `visual_markers`, `audio_sync`, `screen_geometry`, `physical_av`,
`pipeline` or `impairment`. Adapter failures stay UNMEASURED with reasons, and measured values without validated
tolerances stay diagnostics. Native received IVFs and WAVs bind through finalized receiver `media_traces`. For another
recording or image, record `manifest.measurement_captures` (path, SHA-256, origin `DEVICE_CAPTURE` or
`EXTERNAL_RECORDING`, device, source, direction, PID, session, capture method, original clock CSV, and for framing the
viewport and frozen `requested_mapping`) before finalizing the run. Source files and generated charts are not captures.

Timing rules: captured access units use stage `offline_decode` and their source PTS can measure missing content but
never pass screen pacing; derived visual timing keeps the registered original capture clock; a video off and on starts
a new raw-video phase while audio continues, and the sound reference is never restarted to make a lifecycle test look
aligned. Cross-lane overlap needs `benchmark-active-media-intervals-v1` and measured `benchmark-clock-anchors-v1`
pairs; nominal job overlap stays UNMEASURED.

### Independent camera and microphone observation (optional)

A second device that watches the screens and speakers is the only evidence for picture timing and optical latency. A
bundle group may add `observation_capture:{path,sha256}`, a frozen `benchmark-observation-capture-v1` spec declaring
`controlled_nonprivate_target: true`, an independent `recorder` and container `clock`, hashed `ffmpeg` and `ffprobe`,
a native `video` and optional `audio` input, and per-direction `observation_plan` (ROI, search and interval controls,
frozen before recording and never narrowed after seeing a result). Device names are explicit DirectShow names.
`python -B tools/capture_benchmark_observation.py --spec CAPTURE_SPEC.json` validates the plan offline. A normal run
leases the camera and microphone with the phones, starts recording before the runner and stops and registers it before
release; host brackets are not camera or phone clock anchors. A failed recording stays INTERRUPTED with its bytes. A
recorder whose exit cannot be confirmed keeps its leases quarantined and blocks finalization: verify the actual process
stopped, resolve that exact lease and use a fresh execution.

A `physical_av` spec declares the hashed recording, `source_roi`, `receiver_roi`, `delay_search`, `detector` thresholds
and the `planned` interval; the source interval includes every intended brightness edge and the delay tail. Physical
acceptance needs hashed `benchmark-physical-av-validation-v1` evidence (calibration recordings, detector controls,
measured exposure, rolling shutter and acquisition A/V skew). Source timestamps, AudioTrack estimates, decoded WAVs,
generated checks and unregistered captures cannot supply it. The default cue patch repeats every 3 s with no unique ID,
so predeclare appropriately spaced optical events, and use absolute thresholds for short flashes.

For a quick optical estimate, film the source events and receiver pixels with a 120 or 240 FPS camera, use at least 20
matched events after startup and repeat at 1, 10 and 30 minutes for drift:

```
python -B tools/analyze_optical_latency.py --video OPTICAL_RUN/recording.mp4 --source-roi X,Y,W,H --receiver-roi X,Y,W,H --minimum-ms 0 --maximum-ms 6000 --output OPTICAL_RUN/optical-result.json
```

It uses the original per-frame container timestamps, reports each pair's sampling bounds and leaves repeated or
contested events ambiguous instead of pairing them greedily. Exposure, rolling shutter and thresholds still need
evidence, and scheduled playout is never physical latency.

### Controlled impairment

A controlled application impairment is an explicit new request: a broadcast `media_impairment={path,sha256}`, or call
`media_impairments` keyed by outgoing endpoint, with `media_trace=true`. The config is `benchmark-media-impairment-v1`
with a seed, `max_wait_ms` and ordered, non-overlapping phases `{id,start_ms,end_ms,delay_ms,jitter_ms,rate_bps,drop_every,burst_every,burst_length}`.
It changes the declared application load before encryption, not the radio conditions or normal settings, and it
validates its declared simulation separately from streaming performance.

## Tunables, link capacity and focused diagnostics

A case may carry `tunables`, exact debug launch extras applied to every participant: `stream_buffer_kib`,
`stream_flush`, `playback_delay_ms`, `decoder_low_latency`, `release_video_at_playout`, `source_gatt_priority`,
`forwarder_gatt_priority` and `decoder`. Unknown names fail the launch, and different tunables are different case IDs.
The LE link tunables that the switch register ([switches.md](switches.md)) describes (`coc_sequenced_phy`, `gatt_request_mtu`,
`gatt_aligned_writes`, `ble_phy`, `link_pacer_peer_seed`) work the same way. Every LE link logs `BENCHMARK le_conn_params`
(interval, latency, supervision timeout, status and payload bounds) whenever the stack reports a connection update; read
it before attributing an LE throughput result to a phone. Voice calls and push-to-talk (voice calls are on in the app; a
benchmark launch reads only the extra, default off) use the tunables `voice_calls`, `push_to_talk` and `ptt_mode`; a call case with `call_type: "voice"` measures no video
gate, proves zero video packets in both directions and scores audio continuity in whole 2 s report windows. Acoustic
quality and gaps under one report window stay UNMEASURED.

A benchmark launch (any `test_*` extra) reads only its extras, so every release-ON feature (rotating IDs, BMH3 and its
pseudonyms, Find iPhones, voice calls, names, the call resolution ladder, reachability, audio focus and ringer, and the
GATT call fragments plus speech-led playout) runs OFF unless the case passes its extra. By default an inventory case
therefore does not measure what users run. The runner tunable `user_launch=true` (`test_user_launch`) launches the phone
as a person would, with the shipped defaults, while keeping the case's other extras; use it, and name it in the case,
when the shipped configuration is the thing under test.

Rotating-ID test P3 needs mutual verified contacts, which normally take two people scanning QR codes. On DEBUG builds `python -B tools/debug_verify_contacts.py --phones A,B` reads each phone's `BENCHMARK identity fingerprint=` log line (the apps must have been launched once), writes a one-shot token file plus the peer's fingerprint into each app's private storage with `run-as` (no other phone action), and prints the `--es test_verify_contact <token>` launch extras; the owning runner starts each phone with them and checks `BENCHMARK contact_verified source=debug` before the call. `--forget` prepares `test_forget_contact` for the Forget and Reset cases. The day roll uses the tunable `presence_day_offset` (extra `test_presence_day_offset`, same value on both phones, cold start only): it shifts the discovery wall clock by whole days instead of waiting for 00:00 UTC, so record it as a simulated clock, not a natural roll, and clear app data afterwards. ARCHITECTURE register row "Debug contact verification and day-roll clock" has the details.

Rotating-ID boundary tests can shorten the epoch: launch every phone of the run with `--ei test_rotid_epoch_s 60` (tunable `rotid_epoch_s`, 60-900, DEBUG builds only; the same value on all phones, otherwise rotating adverts fail the two-epoch check as stale) so three boundaries take about 3 minutes, and run `rotid_tracker.py --epoch-s 60`. Check `BENCHMARK rotid_epoch_s: ... applied=true` on each phone first; the extra is ignored in a process whose rotating discovery already started, so force-stop before the launch. It tests the boundary and restart machinery, not 900 s timing (Android's own 7-15 minute address timer does not scale, so the same-address-inside-an-epoch information is moot; the tracker's grace and early offsets default to a third and a sixth of the epoch). Results from short epochs are labelled with the value and never replace the 900 s cases. Afterwards a normal launch restarts the epoch counter by itself.

Stranger (pseudonymous) calls: set tunable `rotating_ids` (`test_rotating_ids`) on BOTH phones of a private-call case. The call runner then waits for the callee's own `BENCHMARK rotid_own: role=call | epoch=N | rdi=<12 hex>` line (DEBUG builds with that line only; a build without it fails the wait with a plain message), dials that rotating ID (`call:<rdi>`), and accepts only a `call discovered` row for an ID the peer itself logged. Another phone's rotating ID is never accepted, a boundary during the wait or before the dial moves the wait to the peer's new ID, and the manifest records a `rotating_peer_bound` event per bound direction. Static relay chains are not supported with rotating IDs, and the on-demand shape accepts the `rotating_ids` tunable never; it runs with rotating IDs only as a user launch (below).

User-launch cases (release gate and promotion evidence, package UL1). Set the tunable `user_launch: true` (launch extra `test_user_launch`) and the debug build resolves everything as a person's launch does: rotating discovery IDs, hidden identities, pseudonyms, video fragments and start-up fallbacks are on as shipped, and every opt-in feature reads the person's stored Settings choice instead of its benchmark extra (the extras are ignored; the benchmark launch stays the named control arm, with its case IDs and tunables unchanged). A user-launch case needs no rotating-ID tunable: the call runner treats every user-launch phone as rotating, waits for its own `rotid_own` line, scans and dials `call:<rdi>` for the ID that line announced, and the scorer binds the scanned ID to the peer through the peer's own log (an ID in a helper's SETUP or a caller's route line must also be one the requested phone announced). The opt-in switches are off in a person's launch, so the case states the choice a person would make in the case field `user_settings`, for example `"user_settings": {"relayed_calls": true}` for a relayed call (Settings "Experimental: relayed calls") or `"user_settings": {"wifi": true}` for a case with `carrier` (Settings "Use Wi-Fi for faster connections"; it also switches on the sub-switch the carrier needs: Wi-Fi Direct for `wifi_direct`, Wider reach for `wifi_aware_wider`). Names: `relayed_calls`, `relayed_calls_help`, `relayed_calls_consent`, `wifi_carrier`, `wifi_wider_reach`, `wifi_direct`, `wifi`; values are booleans, and `user_settings` without `user_launch` is refused at planning. Before each user-launch phone starts (relay helpers included), the runner stops the app and writes exactly the booleans the Settings switch writes into the app's own preferences file with `run-as` (debuggable build, that package only; other stored values are kept; for relayed calls the first switch-on dialog's four values), reads the file back, and records the requested settings, the stored keys and the read-back per phone in the manifest's `user_settings`. After the launch the applied-arm check compares each choice with the app's own line (`BENCHMARK relayed_calls: enabled=`, `BENCHMARK wifi_switches: carrier=|wider=|direct=`); a line that shows another value blocks the case before the dial (`runner_blocked` reason `user_setting_not_applied`, or `user_setting_not_stored` when the file could not be written or read back). A request that cannot work as a user launch is BLOCKED before any phone is launched (stage `precheck`): a relayed route or a `carrier` case without the matching `user_settings` (`user_setting_missing`), a shipped privacy default turned off by a tunable (`rotating_ids`, `bmh3` or `bmh3_pseudonyms` set to false: `user_launch_default_overridden`), and a static relay chain, which finds the callee by a fixed prefix (`user_launch_unsupported_route`; use `relay_shape: on_demand`). The benchmark-only route controls of a relayed case (`relayed_calls_require`, the helper and next-hop pins, `relayed_calls_no_direct`) are read only by a benchmark launch, so a user-launch relayed call takes the app's own shortest route: where the callee is in the caller's direct range the call goes direct, and `confirm_on_demand_route` and the scorer fail it as a missing relay. Example (relayed, S22 via ASUS to A15s): `{"id": "ul-gatt-s22-via-asus-to-a15s", "caller": "s22", "callee": "a15s", "relay": "asus", "relay_shape": "on_demand", "transport": "gatt", "profiles": {...}, "tunables": {"user_launch": true, "reset_learned_state": true}, "user_settings": {"relayed_calls": true}}`; Wi-Fi Direct: `{"caller": "s22", "callee": "asus", "transport": "ble", "carrier": "wifi_direct", "tunables": {"user_launch": true}, "user_settings": {"wifi": true}, ...}`. A user-launch call's `discovery_ms` gate takes the first scan of any ID the peer announced.

A user-launch stream source has confirmed the one-time "Before you go live" notice, as a person who goes live has: the runner stores `video_live_disclosed` true on the source unless the case states `"user_settings": {"live_disclosed": false}` (the first-run notice itself). The app logs no line for this choice, so its read-back is the evidence. Call endpoints are unchanged.

Planned Wi-Fi toggles on a call endpoint: the actions `wifi_off` and `wifi_on` (`{"phone": "b", "action": "wifi_off", "at_s": 40}`) run `svc wifi disable|enable` on that phone and are acknowledged only when its `wifi_on` setting reads back the requested state (otherwise the action fails). They alternate per phone and start with `wifi_off`. After a `wifi_off` the runner records the time to that phone's `carrier_switch: to=BLUETOOTH` line (`back_on_bluetooth`, reported, not a gate), and every phone a `wifi_off` names gets Wi-Fi back at the end of the case whatever ended it (`wifi_restored`; a phone that cannot be read back on is UNCONFIRMED in the suite, like an unrestored settings file). The scorer counts a carrier death after a planned `wifi_off` as planned when its reason names the toggle (`wifi_off`, `aware_down_wifi_off`) or it is a socket read failure within 2 s of the action (the peer sees the close); any other carrier death stays unexpected. Each phone's score lists `wifi_off_events` with the death, the time back on Bluetooth and the freeze counters across the switch (reported; the freeze gates count as everywhere else).

Battery Saver and Wi-Fi. While Android's Battery Saver is on the app withholds Wi-Fi for the whole call
(`WifiEligibility.batteryOrThermalBlocks`; its log shows `doze_state ... powerSave=true`, `wifi_caps ... reason=BATTERY_OR_THERMAL`
and `bearers=0x00`), so a Wi-Fi request silently runs on Bluetooth. Battery Saver is the owner's setting; the harness only
reads it. The preflight parses the `dumpsys power` text it already records (`Battery Saver is currently: ON|OFF`): a case
that asks for a Wi-Fi carrier (`carrier`) or the Wi-Fi switches (`user_settings.wifi`) is BLOCKED with reason
`battery_saver_on` naming the phone, before the dial (`blocked_reason` of the attempt). Every execution records
`battery_saver` per device in the manifest's `devices` (true, false, or null when the dump had no such line), and the
scorer never counts a Wi-Fi case as a pass when a participant's app log shows `powerSave=true`: the gate
`wifi_battery_saver` is UNMEASURED with that reason.

The app's own video choice (`profile_source: app_default`). A person's call runs the size, frame rate and bitrate stored
in Settings and the encoder the app's Automatic choice picks; a case that pins them measures a lab setting instead. A
user-launch endpoint can therefore take the app's choice: `"profiles": {"s24": {"profile_source": "app_default"}, ...}`
(per endpoint; the entry holds no other key, and the value is refused without `user_launch: true` on that phone). For
such an endpoint the runner sends no resolution, frame rate, bitrate, encoder, allocation, playback-buffer or thermal
extra (the transport stays the case's own dimension); the app then falls back to its stored values
(`MainActivity.applyDebugLaunchExtras` reads each `test_width`, `test_height`, `test_fps` and `test_bitrate_kbps` with the
stored value as its default, a launch without `test_codec` leaves the encoder picker on Automatic, and one without
`test_rate_control` and `test_playback_delay` keeps the mode's own values). Because a lab phone's stored choices can
differ from the shipped defaults, the runner stops the app, reads the stored video keys back with `run-as` (the same
file and route as the boolean choices above) and records them per phone in the manifest's `user_settings[].video`
(`stored`, `shipped_default`, `stated`, `differs`). `video_width` 426, `video_height` 240, `video_fps` 15,
`video_bitrate_kbps` 200 (stored in bits per second as `video_bitrate`), `advanced_settings` false,
`automatic_thermal_reduction` true, and, while Advanced settings are on, `manual_rate_control` CBR and
`receiver_playback_delay` AUTO are the shipped values. A stored value that is not the shipped default (or the case's own
statement) blocks the case before the dial: `runner_blocked` reason `video_setting_not_default`. The two manual keys are
read only while `advanced_settings` is on, so a stored `manual_rate_control` or `receiver_playback_delay` is not compared
while Advanced is off. The stored size also counts as the shipped default when it is the phone's camera-mapped equivalent
of 426x240 (tools/video_default_equivalents.json, one entry per phone with the app's own `BENCHMARK mode_mapping:
requested=426x240 effective=WxH` line as its source): the app's mode pickers store the camera's nearest mode and the next
settings save persists it, so a person's phone that has opened the app holds that size. Only the whole pair counts (one
half, any other size and a case that states its own size stay strict), a phone without an entry keeps the strict check,
and the manifest's `user_settings[].video` records `default_equivalent` (true only when the equivalent was what passed),
`default_equivalent_size` and `default_equivalent_source`. A case that wants a
person's non-default choice states it in `user_settings` (`video_width`, `video_height`, `video_fps`,
`video_bitrate_kbps`, `advanced_settings`, `automatic_thermal_reduction`, `manual_rate_control`,
`receiver_playback_delay`), which is written, read back and restored like the other stored choices. `user_settings` is
one map for every phone of the case, or the same map plus `"per_phone": {"<alias>": {...}}`, whose values replace the
shared ones for that phone alone (each phone's values are validated, written, read back and restored on its own; an alias
outside the call, or a video value for a phone that is not an `app_default` endpoint, is refused at planning). The manifest's
`app_chosen_profiles` and each device score's `app_chosen_profile` record the encoder, codec, size, frame rate, bitrate,
allocation, playback buffer and encoder-choice reason the app logged (`BENCHMARK call effective`, `call selected` and
`encoder_choice role=call`), so a result stays exact; the scorer matches no request for that endpoint
(`selected_profile_mismatches` and `actual_allocation_mismatches` are absent, `requested_profile_coverage` is
`APP_DEFAULT`). Recorded raw input and impairment declare a geometry and cannot take the app default. Every case that
states a profile is unchanged. A case with a changed `profiles` is a new case ID.

A user-launch relayed call (relay_shape `on_demand`) names its callee at the last relay by a per-call pseudonym that the
callee never logs, so the relay's join line ("Relaying an endpoint-encrypted private call between <parent> and <child>")
cannot be compared with the registry prefix. The runner (`confirm_relayed_join`) and the scorer
(`relay_adjacent_child_identity`) bind the child from the callee's own log (`bind_relay_child`), and an unbound child is
still a failed chain. Method `pseudonym_own`, preferred when the app logs it: the callee's DEBUG line
`BENCHMARK pseudonym_own: role=call | link=<hop> | id=<12 hex>` names the id the relay joined, inside the window before one
of its ringing lines. Method `rotid_target`: the last helper SETUP accepted before the join named `next=target:<id>`, `<id>`
is the callee's own `rotid_own` of the epoch in force at its ringing, and the callee's own log shows the incoming relayed
call (its link line, `call relayed_cap: relayHops=<requested relays>` and `INCOMING_RINGING`, within 15 s of each other
on the callee's clock). Another phone's id, an older epoch's id, a callee that never rang or one that rang without the
requested relay count does not bind. The manifest records `relay_child_binding` per relay (the method) and the hop score
records the binding with its evidence line.

The planned media window of a call is timed from after the surface wait and the volume presses; the manifest's
`measurement_window` records `planned_s`, `actual_s`, `overrun_s`, `pre_window_s` (accept to window start) and
`slowest_health_check_s`. A host or adb stall inside the window cannot be interrupted, but the remaining phones' health
checks are skipped once the window has elapsed, and an overrun above 5 s is a `measurement_window_overrun` runner event, so
a longer call is visible instead of silently scored as the planned duration.

`python -B tools/link_capacity.py probe|relay|report` measures integrity-checked link maxima (GATT write and notify, CoC
primed, Classic), two-link relay airtime and each phone's learned link capacity. Probes restart the app on the
participating phones. `tools/deploy_build.py` freezes and installs a build (above).

These diagnostics support a failed stage; they are not competing acceptance entry points. They need exclusive access
to the phones they use and do not replace suite eligibility, frozen-build checks or run-bound observations.

- [../tools/run_device_benchmark.py](../../tools/run_device_benchmark.py) and
  [../tools/run_private_call_benchmark.py](../../tools/run_private_call_benchmark.py) define the supported actions; the
  latter also runs opaque relayed calls (case field `relays`, in hop order, up to four relays). An active call without
  every requested join is a failed shortcut or direct fallback. The app logs no relay forwarding counters or queues.
- Prefer recorded raw input when operator motion is not needed; otherwise announce the phones and duration, cue the
  source with a beep or vibration, signal unexpected loss and label planned kills. Input rejection is a terminal
  failure: requested raw geometry and FPS are never reinterpreted using negotiated dimensions.
- Trace the request and completion, read, decrypt and dispatch, writer and queue, audio lateness, decoder offer and
  output, and controls, with monotonic clocks and attempt, session, PID and generation identities, before claiming a
  cause. Capture Perfetto only during proven media; sleeping threads or HCI packet masks do not prove an RF, lock or
  socket cause, and observer overhead must be compared at fixed input.
- Single-run offline scorers: `score_device_run.py`, `score_motion_timing.py`, `score_presentation_timing.py` and
  `score_media_delivery.py --run-manifest RUN/manifest.json --receiver ALIAS --output-directory RUN/full-quality`
  (add `--sender ALIAS` for call fidelity). Codec-only diagnostics: `tools/run_android_encoder_case.ps1`,
  `tools/score_encoded_file.py`, `tools/calculate_bd_rate.py`.
- Resource and endurance runs retain the DEBUG extras (`--ez test_session_metrics true --es test_session_name
  CASE_PHONE --ei test_metrics_warmup_seconds 60 --ei test_metrics_duration_seconds 1800 --ei
  test_metrics_interval_seconds 1`), then `tools/pull_session_metrics.ps1` and `tools/collect_connected_cpu.ps1`. Use
  matched scene, brightness, ambient and start state and a randomized, role-reversed 30-minute A/B/A. USB charging or
  nominal Low Energy does not approve unplugged efficiency or endurance.
- Diagnostic payload and write-buffer overrides do not change acceptance; larger buffers or delays need explicit
  latency evidence.

## Message transfer measurement (Design M phase 0, v1 store-and-forward)

Measures what the v1 Messages exchange actually delivers, so the time table, the 1 MB inline limit and the chunk size in DECISIONS "Design M" rest on numbers instead of call-stream rates, and records whether messages arrive while the app is in the background (T-M8). It needs no taps (adb taps are ignored on the M31 and A15s) and runs on the v1 code: `--ez test_messaging_v2 false` is passed (an absent extra now follows the Messaging v2 default, on whenever Messages is on, so a launch with `test_store_forward` alone runs v2). It is not part of the benchmark suite inventory and creates no case IDs; a lane that uses it owns the phones exclusively and keeps its logs in a run-owned folder.

## Messaging v2 phone procedure

## Running several people or sessions in parallel

Two testers (or one tester with a script) can use disjoint phones at the same time. The rules keep the evidence honest.

- **Exclusive ownership.** Each phone has exactly one owner at a time. Only the owner installs, instruments,
  launches, controls, force-stops or captures it. Write down who owns which aliases, cases and APK hash, and do not
  touch someone else's phone, adb sessions, pairing keys or router settings.
- **Clean shutdown before a force-stop.** Android 10-11 keep the scanner and GATT registrations of a process killed
  while it scans, and the phone-wide table of 32 fills within minutes. Before any `am force-stop` of a running DEBUG
  build, send `adb -s SERIAL shell am broadcast -a com.example.bluetoothtest.debug.CLEAN_SHUTDOWN -p io.github.meshlive.app --es nonce N`
  (N: letters and digits, new each time), wait up to 3 s for `clean_shutdown: done | nonce=N |` in
  `adb -s SERIAL shell logcat -d -s BluetoothVideo:I -t 400`, then force-stop. The broadcast never starts an app
  that is not running. `run_device_benchmark.py` does this in `graceful_stop` and falls back to the older
  `--es test_control shutdown` launch for builds without the receiver.
- **One frozen APK per comparison.** Record its SHA-256. If the APK changes, the comparison restarts; a pass on an
  older build never carries over.
- **Isolated A/B versus concurrent radio.** Parallel independent sessions share radio conditions, so record the
  actual first and last media intervals, participants and transports in comparable clocks. A scenario name or an
  intended overlap proves nothing, and `actual_media_overlap` stays UNMEASURED until an adapter measures it. During an
  isolated A/B (A/B/B/A order, only the declared variable changing), everyone else stays off the radio and does
  offline work such as scoring.
- **The runner's lanes.** `--parallel N` bounds active jobs. Disjoint concurrent cases refill a free lane only after
  cleanup and lease release; the first waiting isolated case is a barrier that runs alone. Leases reject aliases that
  name the same physical phone but do not control manual sessions; inspect a stale lease's owner and PID instead of
  clearing it. Hand a phone over only after the previous owner saved evidence, stopped the participants and released
  the leases, then re-plan and preflight.
- Do not repeat an unchanged failure or a missing-adapter case; change something, record why, and retest.

## Temporary debug images

Use [../tools/debug_image_lifecycle.py](../../tools/debug_image_lifecycle.py) for temporary screenshots and inspection
sheets. Each output has one exclusive owner. Reserve a simple image name before the capture, register the finished
bytes, review them, then consume:

```
python -B tools/debug_image_lifecycle.py reserve --output benchmarks/runs/RUN_NEW/CASE --name ui-check.png --purpose 'Inspect connecting text'
python -B tools/debug_image_lifecycle.py register --output benchmarks/runs/RUN_NEW/CASE --name ui-check.png
python -B tools/debug_image_lifecycle.py consume --output benchmarks/runs/RUN_NEW/CASE --name ui-check.png
```

Write the capture to the printed `.debug-images` path between `reserve` and `register`. Python readers can use
`with DebugImages(output).review(name) as path:`; a successful return deletes the image, while a failed or interrupted
review keeps it PENDING. If a measurement still needs the pixels, `retain --purpose 'SPECIFIC_USE'` keeps them until
explicitly consumed. Changed bytes need another register and review. Never use the helper on original evidence or
reference inputs: the suite finalizes only its own fresh outputs and never sweeps source folders.

## Release checklist

Publishing is separate from acceptance: it changes no gate, and release notes must say which benchmark build IDs the
release was measured on. Signing happens only on the maintainer's machine (a keystore properties file outside the
repository); CI holds no signing key.

1. Everything for the release is committed and the tree is clean; `versionCode` is bumped in `app/build.gradle.kts`
   (`make_release.py` refuses to overwrite an existing `release/<versionName>-<versionCode>/`).
2. `./gradlew :app:testDebugUnitTest :app:compileGithubReleaseKotlin :app:compileFdroidReleaseKotlin :app:processGithubReleaseManifest :app:processFdroidReleaseManifest`, `MESHLIVE_REQUIRE_FLAVOUR_MANIFESTS=1 python -B -m pytest -q tools/tests/test_flavour_manifests.py` (the F-Droid manifest must have no install permission; without the variable the test skips when no manifest was merged) and `python -B -m unittest discover -s tools/tests` pass, and `python tools/fetch_native_sources.py --verify-only` reports libaom and libopus OK (without `--verify-only` it downloads and verifies the pinned sources; `make_release.py` refuses a release otherwise).
2b. Before a release that changes Gradle files, native code or toolchain pins, run `python -B tools/check_reproducible_build.py build ...` (ARCHITECTURE "Distribution and releases", reproducible builds) in two clean worktrees at different paths; any differing entry must be explained or fixed. Never during a phone window or next to another Gradle build.
2c. On every APK that goes to a phone or a release (frozen benchmark build, `make_release.py` output), run `python -B tools/dex_api_scan.py APK`: it reads the dex against the SDK's api-versions.xml and must report 0 unreviewed `java/`/`javax/` calls above minSdk (an `--allow` file lists reviewed ones; `android/` candidates are informational and must sit behind `SDK_INT` checks). The unit tests run on a newer Java than the phones, and a missing platform method is an `Error` that ends the app process (DECISIONS "Relayed calls: first phone probe"). `ApiLevelGuardTest` is the cheap source-level guard.
3. `python tools/make_release.py`. Publish the release as **Latest** (not a pre-release): the in-app update check reads `releases/latest`, which ignores pre-releases and drafts, and it needs the per-ABI APKs plus `SHA256SUMS` (README "Updates inside the app"). It builds the split `assembleGithubRelease` (the github flavour: opt-in updater and install permission; the F-Droid recipe builds the `fdroid` flavour) with `--no-daemon`, verifies every APK (single signer, expected certificate, native ABIs, 16 KB check on 64-bit libraries, versionCode = base * 10 + ABI digit, fastlane changelog present), and writes the per-ABI APKs, `MeshLive.apk` (a copy of the arm64-v8a APK; the README download button links `releases/latest/download/MeshLive.apk`), `SHA256SUMS`, `apksigner-certs.txt`, `signing-cert.sha256`, `SOURCE-MANIFEST.txt` (with the native-source pins) and a `RELEASE-NOTES.md` template to `release/<versionName>-<versionCode>/` (gitignored). Finish the notes (changes, benchmark build ids, known limits) by hand.
4. Install that exact APK on one idle phone and check that the About screen's signing-certificate fingerprint equals
   `signing-cert.sha256`, and that it matches the fingerprint published in the USER_GUIDE.md section "Verifying a release". Do
   no phone work during someone else's test window.
5. Tag `v<versionName>`, create the release as a draft, upload the APKs and `SHA256SUMS` first, then publish.
   `release-attest.yml` recomputes the manifest, attests it and attests the uploaded APK by digest. Publishing with no
   APK uploaded attests only the manifest.
6. Check the workflow run, then `gh attestation verify SOURCE-MANIFEST.txt --repo OWNER/REPO` and the same for the APK;
   paste the certificate fingerprint into the release notes.
7. Never put the keystore, its properties file or passwords in a commit, a release asset, CI secrets or a log. If the
   key may be exposed, stop releasing and rotate with a new application-signing plan (Android requires the same key
   for updates, so rotation needs Play key rotation or a new package).

The application ID is `io.github.meshlive.app`; the harness follows it and `MESHLIVE_APP_ID` overrides it for a
build with another ID. Never change it in a feature branch: a new ID cannot update installs of the old one.
