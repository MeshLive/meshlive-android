# Device-family research 2026-10-14

Twelve batches of device-family fixes were written in parallel from a research pass over AOSP (Bluetooth module,
framework, media and camera), the vendor developer documents, Android's CDD and about thirty reference apps and
libraries, then integrated on one branch. Everything below is **upstream-documented or unit-tested, not phone-tested**:
the registry entries say so (`UPSTREAM_DOCUMENTED` or `EXPERIMENTAL`), no gate depends on them, and each new
behaviour has an off switch or is an Experimental arm that is off by default. The switch rows are in
[switches.md](../switches.md#device-family-research-batches-integrated-2026-10-14); the wire and security rules did not change.

**Batches.** R registry and runtime traits (patch-window and stack-era matchers, `DeviceTraitsProbe`, camera and audio
areas); S scanning and adapter state (Location gate on Android 10-11, SDK-aware scan restart, silence watchdog,
Bluetooth-restart epoch, scanner-leak mitigation); G GATT links and capacity (link budget, resource registry, close
deferral, link-silence classification, connect compat); C CoC and Classic (bounded CoC connect, MTU exchange before CoC
traffic, error codes, Classic guards); P PHY and link-layer procedures (procedure gate, change rollback, dual-role
probe); A advertising (budget, watchdog, preemption); B1 foreground service, manifest, onboarding (location type on
Android 10-11, battery-exemption offer, exit reasons, wake lock, boot resume); B2 reliability card and phone-maker
guidance; T thermal, memory and native (corrected forecast sampling, battery-temperature fallback, memory tiers, SIMD
safe mode); V camera and encoder configuration; D decoder robustness; U audio capture, routing and focus. Phase 2
a design record and review first.

**Review decisions (2026-10-14).**

- **G-03, busy-write wedge detection: default `legacy`.** The check ends a link on one busy answer while nothing is in
  flight on SDK 33-34, which covers three of the four lane phones (A15s, ASUS, M31). Without a phone A/B under load that
  is not behaviour-safe, so `test_gatt_busy_wedge=recover` is the Experimental arm and the 25 ms retry step lives only
  there. Closing a GATT client only after its first callback (G-02, default on, SDK 34 and lower) stays: it affects only
  clients that never call back.
- **Classic RFCOMM six-peer cap: log only.** Refusing the seventh peer without phone evidence, while incoming peers
  were undercounted until the accept paths feed the registry, is not safe. The default writes a `classic_rfcomm_cap` line;
  `test_classic_rfcomm_cap=6` is the Experimental refusal. The accept paths now feed the registry.
- **Bounded CoC connect stays on (12 s).** The stack's own channel-connect timer is 60 s; a failure falls into the
  existing retry and automatic GATT path.
- **Ear-proximity pause hysteresis: Experimental.** Near held 1.5 s and 3 s between toggles changes a shipped feature for
  every phone without evidence; `test_proximity_pause_hysteresis` keeps it off until the 50-cycle ear check on the ASUS.
  The encoder-init fallback (after a failure only, user sessions only) and the camera wait for the old session's close
  (Qualcomm, SDK 31 and lower) stay on.

**Premises the research corrected** (registry text and code changed with them): the scan-start quota is silent, no
`onScanFailed(6)` ever arrives, so the app's own start budget is the only protection; the "Samsung 5 s direct connect"
timer is not an AOSP timer (the stack's is 30 s; the 5 s on a Samsung link is the supervision timeout that
`requestConnectionPriority` sets); the CoC connect stall is 60 s, not 30 s; a write that returned busy can stay busy for the
life of that client object on Android 12 to 14, so a longer retry cannot help; LE Data Length rises to 251 only after an ATT
MTU exchange, which is the leading explanation of the 17-29 kbps CoC stages (Experimental MTU exchange before CoC
traffic); One UI gives no foreground-service keep-alive guarantee.

**NEEDS RETEST: every earlier `test_thermal_forecast=true` A/B run.** Those runs sampled `getThermalHeadroom(10)` once
per 10 s. The platform watcher keeps its samples only while calls arrive within 10 s of each other, needs three samples
for a forecast and clears them after a longer gap, so each call found it idle and returned the current value: the runs
measured the current headroom labelled as a forecast. The sampler now calls every 2 s, discards the first 3 s and hands a
decision every 10 s; the old cadence is kept as `test_thermal_forecast_legacy_10s`. The planned re-run is S24 and ASUS
(`BENCHMARK thermal_sampler` proves the forecast was active).

**Left out of the integration, with reasons** (each is wired when its owner next edits the seam): the dual-role probe
events (parent and child lifecycle seams in the relay code), the credit-stall feed (the CoC writer has no
"acknowledged by the peer" counter), the ADPF hint update and the lag-frame cap (a requested benchmark parameter must
not be capped silently), `trustedExperimentalPhy` in the capacity code (no per-link PHY at that level), the 1 s session
heartbeat for timer lateness (no such timer exists), link-silence stages and ACL waits in the PSM reader, dial-start
scan pauses, unexplained-drop budget learning, the decoded-frame colour check, the in-app volume control while the
proximity screen-off blocks the keys, and the Settings opt-in for resume after a restart (waits for Q41 and a headless
relay host).
