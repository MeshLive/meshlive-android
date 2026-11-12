# First-principles audit (2026-11-10): corrections and decisions

A whole-system audit (eleven independent read-only reviews plus phone lanes) checked earlier conclusions against the
code and the raw run data. The unfinished work it confirmed is in [roadmap.md](../roadmap.md); this section records what was
decided or corrected on the day.

- **Promoted defaults now reach release builds.** The first call frame ramp, audio work off the main thread and "a
  delivered recovery control write counts as the first write" were promoted to default on, but each kept the setter of
  a default-off experiment, which a release build resolves to off. Every phone test uses a debug build and the unit
  suite ran only the debug variant, so nothing showed it; no release was built in between. The three setters now keep
  the default in release, and a test that runs on the release variant checks that assigning any switch its own value
  never changes it. Rule from now on: a promotion changes the setter with the default and gets one release-build smoke
  call.
- **The Wi-Fi Aware goodput guard is log-only until redesigned.** As first written it compared the bytes a phone wrote
  to its socket with the configured video ceiling, so an encoder below its ceiling (the Automatic rate ramp after a
  move to Wi-Fi, a calm scene, a software encoder) read as a slow link: replayed on measured encoder output it would
  return a healthy call to Bluetooth about 15 s after the move and never try Wi-Fi again. The guard now only logs each
  5 s window and where it would have acted; the existing queue-age rule still returns a hop that Wi-Fi cannot carry.
  A redesign must compare delivery with what was offered and with what Bluetooth could carry, then pass a phone A/B.
- **2.4 GHz-only Wi-Fi Aware ships off (Experimental).** The 2026-11-09 answer stands as the intended behaviour, but
  no test pair can produce a 2.4 GHz-only Aware data path today and "this phone carries no other Bluetooth media link"
  has no single counter in the app, so the acceptance stays behind a default-off switch with a conservative fact
  (anything unknown counts as "other Bluetooth media").
- **Corrections to three promotion records (the promotions stand).** (1) First-write rule: the mechanism is real (the
  server's cancel-and-reregister fired in 6 of 6 control calls and 0 of 6 with the rule), but "133 failures 10 vs 83"
  counted log lines; failed connection attempts were 2 vs 17, and "6 of 6 vs 4 of 6" is too few calls to claim a
  connect-rate gain; connect time was unchanged. (2) First-frame ramp: it saves 0.5-0.8 s on the older LE CoC pair
  and about 0.14 s on one GATT pair; on the third pair the control arm already showed its frame in 0.05-0.15 s, so the
  recorded -0.31/-0.35 s and -0.6 s were scatter; unmeasured cost: video at about two-thirds speed for up to 2.4 s
  and picture ahead of sound by up to 0.8 s. (3) Audio off the main thread: no audio measurement was taken, one pair,
  one transport; total setup did not improve, and the audio open itself still runs on the main thread. All three were
  measured with a benchmark launch and fixed discovery IDs, not as a user launch.
- **What the test evidence does and does not show.** A benchmark launch turns off every feature the case does not
  name (rotating discovery IDs, hidden identities and pseudonyms, video fragments on GATT, start-up fallbacks), calls
  filmed a flat scene (first key frames of a few hundred bytes), and only debug builds were measured. Numbers recorded
  before this date describe that control path. The formal scorer could not return "pass" for a call, so its FAIL
  states carried no signal. The relayed-call results before this date used a test-only relay shape; the on-demand
  path users take had never run (the runner stopped before the caller launched; fixed).
- **Wi-Fi facts for the release decision.** Wi-Fi Aware hardware is present on three of the six test phones; the
  other three (one of them on the newest Android) have none, while Wi-Fi Direct is present on all six and is the only
  carrier that has carried calls so far (15 of 15 on two pairs). No Aware data path has carried media in any saved
  run. A call on Wi-Fi keeps the Bluetooth quality ladder (the 2026-11-04 answer "until measured").
- **Implementation agents (2026-11-10, owner).** The external low-cost implementer is retired: bounded packages are
  implemented by separate implementation agents in their own worktree (same brief, allowlist and review discipline),
  and those agents stay on the main model with the effort matched to the task.

## Owner answers to the audit questions (2026-11-10, "recommended for all")

Wi-Fi

- **What ships as Wi-Fi.** Every Wi-Fi switch stays off by default. Wi-Fi Direct is the user opt-in, labelled
  Experimental, with a confirmation that shows the phone's Wi-Fi Direct name before it turns on (nearby devices can
  see that name). Wi-Fi Aware stays Experimental and is not announced as a feature until it has carried a call in
  both directions on a pair of Android 13+ phones and survived Wi-Fi being switched off mid-call.
- **Better picture on Wi-Fi.** The Wi-Fi quality ladder is built now behind a default-off switch: delivery feedback on
  Wi-Fi, rungs chosen by encoder capability (hardware encoders up to 720p30 at about 2 Mbps, software-only up to
  480p30 at about 1 Mbps), and a clean step-down when the call leaves Wi-Fi. It is promoted only after a phone A/B on
  two pairs with a moving scene. A fixed user setting never changes. This carries out the 2026-11-04 answer "Auto
  keeps the Bluetooth ladder until measured".
  Owner 2026-11-10 ("whatever you recommend"): the top rung is 720p30 at 1500 kbps for now (2500 kbps only as a test arm
  until the throughput and heat runs exist); the ladder stays off in this release and is promoted when two pairs pass
  picture, fallback and heat; when a call leaves Wi-Fi the picture is back within the rung-change gap plus 300 ms and
  never later than 1.5 s (a 500 ms line needs a follow-up that scales the capture instead of restarting the camera);
  Wi-Fi Direct and Wi-Fi Aware alike, on 5 GHz or 6 GHz only.
- **Goodput guard and 2.4 GHz-only Aware.** As recorded above: log-only until redesigned; default off.
- **Aware derived port.** Promoted for release after a short PROTOCOL note and an independent dual review (both ends
  compute the port; no key or authentication change). Evidence
  (2026-11-10, first calls ever carried by Wi-Fi Aware, Samsung S22 and S24, 5 GHz access point): without the rule the
  S22 as Aware guest never receives the host's port from its platform (0 of 3 calls, 6 of 6 attempts ended `no_port`;
  a later platform update still had no port, so waiting would not help), while the S24 as guest moved to Aware in 3 of
  3; with the rule both directions moved on the first attempt (1 of 1 each; the S22 connected 0.34 s after its data
  path came up, retrying while the host was still binding). Two independent reviews approved with changes and found
  no blocker: the port is an HMAC output under the upgrade key with its own label (no bias, nothing learnt about the
  key, authentication unchanged); no capability bit is needed because no published build implements Wi-Fi (calls opt
  in by the signed `WIFI` marker, stream hops by the build tag), so a release must never be cut from a commit before
  the rule; wider-reach native hops had the same lost-port defect (the port in `MWA1` was dropped) and are fixed with
  it; release builds strip the Wi-Fi log lines, so the release smoke call is judged by the link-health sheet showing
  Wi-Fi. Recorded as "promoted on mechanism" (fewer than 12 calls per arm).
- **Live streams follow the call identity rule (owner 2026-11-10).** A phone's permanent identity (node ID and identity
  key) is never sent in clear and never shown to a stranger, in streams exactly as in calls: strangers see the generated
  pseudonym, the name follows the user's "who can see my name" setting, and only contacts can recognise and verify the
  real identity, inside the encrypted link. Viewers and forwarders join each stream with a fresh throwaway identity. There
  is no separate "anonymous" mode: hiding is the default for everyone. Older versions are expected to update: once this
  ships, stream links use only the new handshake and an older app is told to update rather than given a fallback that
  would expose the identity again.
- **Call pre-warm: judge each switch on the stage it acts on (measured 2026-11-10).** Preparing the encoder and decoder
  while the phone rings (camera and microphone still closed until Accept) took effect on every call, but the whole join
  looked unchanged because both slow first connections of that run (a status-133 retry costs 1.3-1.8 s) fell in the
  pre-warm arm. On the stage it changes, Accept to the first shown picture, it saved 0.14-0.17 s on the slower direction.
  It stays Experimental (off) and is measured again per stage with the camera-first start, on a lit moving scene. Join
  A/Bs report every stage separately so a rare event in another stage cannot hide or fake a gain.
- **Wi-Fi switch fixes SW1 promoted (measured 2026-11-11, 12 calls per arm).** Two fixes at the moment a call moves from Bluetooth to Wi-Fi (a record already sealed for Bluetooth keeps its place in that queue, and the reader marks the link as heard before it waits for the call's record lock) took the S22 and the A15s from 11 of 12 Wi-Fi Direct calls reaching Wi-Fi, with 4 failed switches and 4 freeze-gate failures, to 12 of 12 with none. On two other pairs (ASUS and A15s; S22 and ASUS) the failure did not occur in either arm and nothing got worse. Both are on by default from B17; release builds keep them on and a DEBUG launch with `=false` is the control arm. The release-build smoke call is still owed (release signing is an owner step). A fourth pair after the promotion, the M31 and the A15s (12 calls, M31 on 2.4 GHz home Wi-Fi), showed no difference: every call in both arms reached Wi-Fi and none lost its link (1 failed switch with the fixes, 0 without).
- **Call fast recovery promoted (measured 2026-11-12).** After one short glitch (a 526 ms pause at the sender) a call
  used to stay late for most of the rest of the call. With fast recovery the receiver leaves out quiet audio in pauses
  (never speech) and the call clock follows: across two such pauses per call, the time a call stayed 100 ms or more late
  fell from about 64 s to about 8 s, in both directions between the S22 and the M31. The peak delay right after a pause
  was the same (about 330-350 ms) and the per-call picture gates were no worse. Promoted on mechanism for the next
  release (6 calls per arm).
- **0.5.0 ships with one known failing pair (owner, 2026-11-12).** The release gate on five phones failed on one call
  direction only: the A15s calling the ASUS (7 of 12 calls never rang, the others took 11-24 s), the same defect as in
  0.4.4. Every other measured direction passed (S22 <-> S24 26 of 26 within 4 s; the ASUS calling the A15s 12 of 12
  joined, 3 over 6 s; the M31 calling the S24 joined in 4-9 s, 3 of 8 calls over 6 s); without that direction 87% of
  calls joined within 6 s (the line is 90%) and none over 15 s. The release notes name both; the narrow fix the Design UP review proposed (an authenticated phone pair that failed on 2M starts on
  1M next time) is the next change.
- **The 1M-first call start stays Experimental for 0.5.0 (owner, 2026-11-12).** It fixes the A15s calling the ASUS, but
  holding 1M costs healthy pairs on both LE transports (the two entries below), and asking for 2M on a link that cannot
  carry it loses the call. No pair gets worse with it off; the fix to build is a call that survives a failed 2M upgrade
  (Design UP), after which a call can ask for 2M right after its first picture.
- **Holding a 1M-first GATT call on 1M costs a healthy pair too (measured 2026-11-12).** When the S22 calls the ASUS over GATT with the 1M-first start, the sender reached its link limit in all 3 calls and the call stayed on 1M. The ASUS still received about 15 frames a second with as few long gaps as on 2M, but the picture queued: 90 % of frames arrived within 131-414 ms of the best frame against 47-50 ms on 2M, and 3.6-23.7 s of each 50 s call arrived more than 150 ms late against 0.5-0.9 s. The ASUS calling the S22 never reached the link limit. So the hold costs healthy pairs on both LE transports whenever the call does not fit 1M, and a 1M start should not be promoted until a call can survive a failed 2M upgrade (Design UP).
- **Holding a 1M-first CoC call on 1M costs a healthy pair (measured 2026-11-11).** When the S22 calls the A15s over LE CoC, the 1M-first start rang as fast as the 2M start (1.5-3.9 s against 1.4-3.7 s), but the sender reported its link limit in all 3 calls, and holding 1M left the S22's picture with one freeze per call (0.2-0.75 s) and its added delay at the 350 ms cap; on 2M none of the 3 froze. The A15s calling the S22 was unaffected. So the link-limit hold that saves the A15s calling the ASUS costs healthy pairs whose call does not fit 1M. Asking for 2M at the link limit instead recovers the call (3 of 3 upgraded and ran to their end, 1 short freeze), but only after the call's added delay has reached the 350 ms cap and slowly shrunk back: about 35 of 50 s spent 100 ms or more late, against 0-16 s with the 2M start. On GATT the link limit did not fire in the first 18 healthy-pair calls; it then fired in every S22 -> ASUS GATT call (entry above). So a 1M start costs healthy CoC pairs the first half-minute of the call; the real fix for CoC is a call that survives a failed 2M upgrade, so that it can ask for 2M right after the first picture.
- **The 1M-first call start fixes the A15s calling the ASUS on both Bluetooth LE transports (measured 2026-11-11, 12 calls per arm each).** On GATT every 1M-first call rang in 1.7-4.0 s against 9 of 12 with the default 2M start, all after 6-22 s; on LE CoC every 1M-first call rang in 1.5-4.0 s against 7 of 12 in 3.5-21 s. Two 1M-first calls in earlier builds were lost mid-call after asking for 2M at the sender's link limit; since that upgrade is held, the two calls in which it would have fired ran to their planned end. On healthy pairs the start costs no setup time (ASUS and M31; S22 and M31), but on the S22 calling the M31 the picture on 1M showed occasional single-frame gaps (worst 150 ms against 106 ms on 2M). The owner kept it Experimental for 0.5.0 (entry above).
- **Wi-Fi Aware instant communication mode does not move calls to Wi-Fi faster (measured 2026-11-11).** On the S22 and S24, every call reached Wi-Fi Aware with or without it, about 5 s after the call became active (median 5.3 s with it, 4.9 s without, 6 calls each). The calls with it had a little more trouble (one call with two short freezes, three with an unexpected event; none without), which may be chance. It stays off and Experimental; its battery effect, the other half of the question, was not measured. The app does not yet log whether a phone supports and applied the mode, so the next build adds that line.
- **Starting a CoC call on LE 1M rescues the A15s calling the ASUS on LE CoC too (measured 2026-11-11).** With the Experimental 1M-first start for CoC calls, every A15s CoC call to the ASUS rang, in 1.5-4.0 s (median 1.9 s), and all 6 ran to their planned end with no 2M request; with the default 2M start 3 of 6 rang (3.5, 14 and 21 s), with 4 setup stalls, and the rest failed to connect. This is faster than the in-place 1M move (8-15 s), because the stall never happens. Together with the GATT result, the 1M-first start is the fix for this pair; it reaches the 12 calls per arm of an outcome claim with the next batch.
- **Moving a stalled 2M call link to 1M in place rescues the A15s calling the ASUS on LE CoC, not on GATT (measured 2026-11-11).** With the Experimental in-place move (a direct call whose first 2M link stalls or crawls during the handshake asks the same link for 1M before closing it), all 3 A15s CoC calls to the ASUS rang, in 8-15 s, against none of 3 without it: without it the first 2M link stalled and the 1M retry then failed on its fresh control link too. On GATT it made no difference (3 of 3 rang both ways, 7-18 s with it and 4-11 s without), and there the 1M-first start rings in about 2 s. The controller confirmed the move in only 1 of 6 calls. The next step is a 1M-first start for CoC calls (Experimental), which avoids the stall instead of recovering from it. With 3 calls per arm per transport this is direction, not an outcome claim.
- **The call fast-recovery A/B needs a staged gap: natural gaps were too rare (measured 2026-11-11).** With the Experimental fast recovery on, none of 12 calls had a late burst, so it never armed; without it 2 of 12 calls had one and the added call delay stayed until the end (up to 350 ms, the playout cap, in a call with four bursts in 90 s; the slow shrink between bursts never finished). The comparison is repeated with the same short send pause staged in both arms by a DEBUG-only injection.
- **Starting a call on LE 1M rescues the A15s calling the ASUS on GATT (measured 2026-11-11).** With the Experimental 1M-first call setup (the call opens on LE 1M and asks for 2M only after its first picture, when the link needs it) every A15s call to the ASUS rang, in about 2 s, against 4 of 6 calls ringing after 15-21 s with the default 2M start. It covers GATT calls only. It is a promotion candidate once the reverse direction and two healthy pairs show no cost; the LE CoC side is measured separately with the in-place 1M move.
- **A shorter incoming handshake bound does not rescue the A15s calling the ASUS (measured 2026-11-11).** When the A15s
  calls the ASUS, their LE 2M link often stalls or crawls during the end-to-end handshake; after a stall the caller
  retries on 1M but must find the callee again, while the callee waits out its 20 s handshake bound without advertising.
  Ending the callee's wait at 10 s (the Experimental incoming hop-authentication timer) made it worse: 4 of 6 calls rang
  with it against 6 of 6 without, because the bound also closed slow handshakes that were still progressing. The A15s
  calling the S22 showed no stall at all, so the fault belongs to this pair, not to the A15s alone. The timer stays off;
  the open fix is to leave a slow 2M handshake for 1M on the same link, or to start this kind of pair on 1M.
- **Faster call start: camera first, a quicker idle advert and a fast first retry together (measured 2026-11-11).**
  Opening the camera at Accept before the rest of the call setup, advertising faster while the app waits for a call, and
  retrying a slow first connection sooner, all three on together, joined calls 0.9-1.2 s sooner from Accept and brought
  ringing 0.8 s sooner when the slower phone called. Accept to the camera request fell from 0.66-1.01 s to 0.31-0.40 s.
  No crashes, ANRs or audio-start timeouts in either arm. The owner promoted all three together on 2026-11-12, on
  mechanism, before the quicker idle advert's battery cost is measured (first-principles estimate +0.1-0.3 mA): the first
  measurement could not answer it (the screen stayed on, about 200 mA, and only one control block was valid), and the
  advert switch goes back off if the screen-off rerun shows more than +0.5 mA. The release-gate run measures the faster
  joins.
- **Stream identity details (2026-11-10, "whatever you recommend").** Within the rule above:
  - Wi-Fi on a stream link needs both ends' choice before any Wi-Fi path is used: the source allows viewers over Wi-Fi
    for that broadcast, and the viewer chooses Wi-Fi on the watch sheet. Recognising the source as a contact never turns
    Wi-Fi on by itself: the source would see the difference and learn that the viewer knows it. Without both, a stream request or offer is never published over Wi-Fi Aware, a knock is not answered and a
    request seen only over Wi-Fi is not dialled; Wider reach is no exception. What other apps on a phone can reach over
    the Wi-Fi link only changes the wording, not the rule.
  - Forwarded video shows "<contact>, via a phone nearby" without the verified mark and never counts as a contact for
    the Wi-Fi rule, because a past viewer can copy a broadcast's contact proof onto its own video. Direct links and
    audio-only streams (signed end to end) keep the mark.
  - A public stream cannot block a viewer: every viewer joins with a fresh key. The block screen says so.
  - Recognising a contact's stream ("Alice is live") before joining is a follow-up with its own offer version and
    review; until then a contact's stream is recognised after joining.
  - The name setting "Everyone I call or answer" also covers streams and reads "...or stream to".
  - An older source gets one update notice while the notice switch is on: a single old-handshake hello from a one-off
    key carrying only the build tag, then the link closes. No stream media ever uses the old handshake.
- **Windows: the local trace stays readable in release builds (2026-11-10, as recommended).** The trace (a
  `trace.on` file or `MESHLIVE_TRACE`) is a local opt-in log, wiped by Delete everything and never exported; the packaged
  app can only be traced this way and the release checklist's rotation check reads it. Its rotating-ID/payload pairs could
  link IDs if someone plants the file, but whoever can plant it already runs as the user and can read far more. Every other
  protection-off control arm is ignored in release builds and refused by the packaging check.
- **Apple call, live and diagnostics screens (2026-11-10, as recommended).** Accept is green as on Android (Android
  is the colour reference; green Accept and red Decline is the phone convention). Pause keeps the microphone open on
  iPhone and Mac, because iOS suspends a background app whose audio session stops and a paused radio would lose its
  listeners; nothing is sent while paused and the note and PRIVACY say so. The in-app "Keep going" reminder stays (iOS
  shows no banner for the app in front). Diagnostics export what Android exports today; the per-device identity hash
  lines come on both platforms together after Android implements and reviews them. Report a bug stays hidden until one
  bug page is chosen for both platforms.
- **A stalled call video decoder is rebuilt in place by default (2026-11-10).** On one pair (Samsung S22 receiving the
  Oppo A15s's AV1 stream over Wi-Fi Direct) the S22's software AV1 decoder silently stopped completing work right after
  the switch to Wi-Fi, and the call ended about 11 s after it connected. A randomised user-launch A/B, 12 calls per arm,
  same build and settings: without stall recovery 8 of 12 calls survived (all 4 stalls ended the call); with the existing
  Experimental stall recovery (rebuild the same decoder at the next key frame, exclude it on a repeat stall) 12 of 12
  survived, all 4 stalls recovered, the picture back 1.3-2.0 s after the rebuild (one-sided Fisher p = 0.047). Every stall
  began 0.1-0.2 s after a stale record was dropped at the switch to Wi-Fi, so the cutover timing is the likely trigger and
  is investigated separately. Known cost: during the stall the voice froze 3.3-4.9 s (the receive path waits for room in the
  video queue); the separate Experimental "call video restart" shortens that wait and is not yet measured (it saw no stall in
  its 12 calls). Promoted with its setter (release builds keep it on); a release-build smoke call follows.
- **Relayed calls: a legacy-Qualcomm helper no longer uses acknowledged GATT writes on its next hop by default
  (2026-11-10).** The device-family entry that made such a helper write with response at 24 KB/s had no recorded source
  or evidence. Its first measurement, interleaved on one build (caller, legacy-Qualcomm helper, older receiving phone):
  with the entry, 0 of 3 calls lasted their 60 s; each time the receiving phone dropped a write response, the helper's
  write timed out and almost nothing was forwarded before the relay closed. Without it, 2 of 3 lasted the full call with
  the picture flowing, and none of the three showed the stall; the third ended on the other hop for an unrelated reason
  (one case, under investigation). With the same phones in another order (a different relay), the hop into the same
  receiver held. The entry is kept as an Experimental arm, off by default.
- **A second Android 13+ phone with Wi-Fi Aware joins the test bench** when the owner can add one; until then Aware
  results come from one pair of phones of the same maker and are labelled so.
- **Where Wi-Fi appears.** One row on the top Settings page, "Use Wi-Fi when it helps" (off for this release), with
  the two sub-options under it; the call shows "Now on Wi-Fi" and "Back on Bluetooth"; Link health shows "Wi-Fi not
  used:" with the reason (heat, battery saver, a 2.4 GHz network, hotspot, busy).

Evidence and release gate

- **Tests run as a user launch.** Phone tests for the release gate and for any promotion run the app as a user's
  launch (rotating IDs, hidden identities, pseudonyms, video fragments and start-up fallbacks on). The older benchmark
  launch stays as a named control arm. Every promotion gets one release-build smoke call.
- **Required phones and the gate.** The five phones on the bench (S24, S22, M31, ASUS, A15s) are the required set
  since 2026-11-12, when the owner retired the Realme for good (its requests stay compiled as an extension phone, so their
  IDs and results are kept); other phones extend it when present. Five phones make two parallel pairs instead of three,
  so the gate is about 80 calls under two hours (about 115 calls would take about three hours), with the M31 calling the
  S24 beside the relay chain. Earlier: the six phones on the bench (S24, S22, M31, ASUS, A15s, Realme) were the required
  set. The release gate is a tagged selection of about 120 user-launch calls on
  three lanes in under two hours. Join time keeps 3 s as the target and ships against a recorded interim line (90% of
  calls show a real picture within 6 s, none over 15 s), measured as two app-clock stages from an idle callee: dial to
  ringing, and Accept to the first non-black picture. The scorer must be able to return "pass" and its smoothness
  limits move off the 60 Hz grid.
- **Promotion claims.** A promotion on mechanism is allowed when the log shows the mechanism in every case and no arm
  is worse, and the record says so. Outcome claims (connect rate, seconds saved) need 12 or more calls per arm in
  randomised order.

Privacy

- **Stream participants (design review required).** Viewers and forwarders will join with a throwaway key per join
  while the source keeps its real key, and stream hops move to the hidden-identity handshake, after a design record
  and an independent dual review. The privacy page and the watch and go-live text say today's exposure plainly until
  then.
- **Wi-Fi on stream hops.** The exposure to other apps on the phone is measured first (a plain listener app on the
  source, a viewer connecting to it over the Wi-Fi link); if other apps are reachable, stream hops use Wi-Fi only with
  verified contacts or on the viewer's explicit tap.
- **One connection must not hold the call slot (design review required).** A design keeps the advert and listener up
  until hop authentication and holds off repeat offenders; meanwhile a shorter silent-peer bound ships behind a
  switch.
- **The callee's name** waits for the caller's block check, as the camera and microphone already do.
- **Apple log.** Release builds write no log file and mirror nothing to the system log; existing logs are deleted on
  update.

Calls and screens

- **Callable when the app is not open.** Two steps: now, a call ends with a proper hang-up when the screen component
  is destroyed and the user is told when the app closes while "Reachable for calls" is on; next, the endpoint and the
  call move into the service, with boot resume offered at onboarding.
- **Camera refused at answer time.** The call is answered as voice automatically, with a notice; a refused microphone
  shows a message with a path to Settings.
- **Join speed.** Encoder, decoder and audio are prepared while ringing and the camera opens only at Accept; calls
  connect on LE 1M and move to 2M once the call is up. Both are default-off arms until their phone A/B.
- **Small defaults.** The "Bluetooth may be stuck" guidance is on for the phone family that needs it; a viewer's
  search for a lost stream ends after 60 s with "The stream was lost" and Try again; after a video decoder error the
  voice continues and video restarts in place (behind a switch first); developer rows leave the normal Settings pages
  in release builds.
- **Language.** User text moves from code into resources now (no behaviour change); the release ships in English and
  translation is the next milestone.
- **Idle wake and the boundary silence** are measured before anything changes.

Versions and project

- **Old versions.** The support floor is 0.4.0. A standing phone gate runs the newest published build against each
  release candidate on two pairs and six flows (call, video call, stream view, relay, contact add, block). A quiet
  hint tells a user when the other phone runs an older MeshLive.
- **Apple.** The Apple line with rotating IDs, hidden identities and pseudonyms on is published with or right after
  this Android release; until then the README says an older Apple build cannot call a current Android phone.
- **Wi-Fi release number.** It is set with the version bump of the release that first ships the Wi-Fi reader, with a
  unit check that the build's own number is not below it.
- **One source line.** The documents-and-tools line and the app line are merged into one.
- **Inventory and ledger.** The large inventory and its state rebuilds are paused and kept read-only; the release gate
  above is the working selection.
- **Backup and cleanup.** The evidence gets a second physical copy; finished worktrees and scratch folders are
  removed from an exact reviewed list.
