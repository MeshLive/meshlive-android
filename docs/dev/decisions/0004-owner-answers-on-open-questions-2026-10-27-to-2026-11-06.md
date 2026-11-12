# Owner answers on open questions (2026-10-27 to 2026-11-06)

- **Apple P17 interop defaults promoted (2026-11-06, Mac p17-ab 8fb076f vs flag-off arm, M31 88a946b8 at
  test_call_head_of_line=gatt_default, GATT, 60 s video, ABAB):** video fragments
  not worse (playout p50 250/263 ms Mac->M31, 250/250 M31->Mac; off arm had 502/455 ms outliers; negotiated in every
  readable on call); lost-GATT-chunk recovery under 2% injected loss kept 3/6 calls alive vs 0/6 off (M31->Mac 3/3;
  Mac->M31 still ended at 33-54 s: follow-up); audio coalescing not worse but unvalidated on clean links (coalesced in
  2/6) - kept on as Android (owner rule: behaviours like Android).
- **A caller's name waits for the reveal (2026-11-06, "as you recommend for all"):** on every platform a call to a
  stranger sends the caller's name only after the callee reveals and passes the caller's block check, so a blocked
  person never receives it; the callee sees "Unknown caller" until then (timing only, no wire change). Replaces
  Android's names-before-answer and the Windows parity port.
- **Unreadable block list (2026-11-06, "as you recommend for all"):** if the saved block list cannot be read, every
  platform keeps failing open (calls ring) but shows a visible "Block list can't be read - Reset" note and logs it; an
  attacker cannot make the file unreadable, and failing closed would stop every call for the genuine user (CF-CALL-24).
- **Relay admission without a measured ceiling (2026-11-06, "as you recommend for all"):** a relay with no fresh
  measured ceiling gets a budget of at least 2 x the per-link load (as at cold start), so a phone that once proved a
  small stream is no longer refused a larger relay that a never-measured phone would get; the measured overload guard
  stays once a real ceiling exists. Experimental switch first, then an A/B on the S24 and ASUS.
- **Wi-Fi Aware band rule (2026-11-06, "as you recommend for all"):** judge an Aware data path by its widest/primary
  data channel, treat a 2437 MHz discovery-window slot as informational, and add a measured goodput guard that falls
  back to Bluetooth when Wi-Fi is slower; Experimental switch first. Today's strict rule refuses every S22+S24 attempt
  (5745 MHz 80 MHz data plus the mandatory 2437 MHz slot).
- **Windows Wi-Fi Direct (2026-11-06, "as you recommend for all"):** the phone hosts the group and the PC joins (the
  Windows host refused the phone's association in W0b, and Normal discoverability puts the PC name on air).
- **Apple Wi-Fi guest join AW1 (2026-11-06, "as you recommend for all"):** the Hotspot Configuration capability is
  enabled on the App ID once the Apple team is paid (Mac-only tests until then); iOS's own "Join Wi-Fi network?" prompt
  on each attempt is accepted for an Experimental switch; the Mac's one-time Location request when "Join Wi-Fi Direct
  groups" is turned on is accepted (Android 10-12 asks too).
- **"Can see when I'm nearby" starts as not yet asked (2026-11-06, "as you recommend for all"):** on every platform the
  per-contact choice has a not-yet-asked state that counts as off until the user answers (replaces Android's default
  on).
- **Apple plan questions P12F and P14-P16 (2026-11-06, "as you recommend for all"):** (a) Android first blocks
  " · seen" inside names, then Android and Apple both switch to the "· seen nearby" wording; (b) the Apple copied-ID
  exemption for Windows PCs is decided after a Windows PC test; (c) Apple keeps its current broadcaster microphone
  behaviour; (d) a small follow-up package adds camera-at-onboarding, the microphone note with Mac Pause, and Mac Wi-Fi
  advice.
- **Apple gap audit 2, Q2-Q5 (2026-11-06, "as you recommend for all"):** Android un-gates its Experiments rows from
  Manual video (Apple is not gated); Apple turns on Android's interop defaults only after a Mac <-> Android A/B with 6
  calls per arm (done for P17, above); the Mac 2.4 GHz Wi-Fi warning is Mac only and asks for no Location; an Apple
  viewer that loses a stream searches like Android, including another relay.
- **Windows Wi-Fi calls leave the PC's network (2026-11-07):** with the phone hosting and the PC joining, a single-adapter PC
  leaves its own Wi-Fi network (and internet) for the length of a Wi-Fi-carried call. Owner: allowed, opt-in only (off by
  default), and Settings says so plainly. The development laptop keeps its Wi-Fi on, so no Windows Wi-Fi device checks
  run on it.
- **Microsoft Store publisher (2026-11-06, "as you recommend for all"; Q-STORE-PUBLISHER):** MeshLive is published
  from a separate Partner Center account (or a changed publisher display name) under the MeshLive project name, so the
  owner's real name is not on the listing; reserve "MeshLive" only after that.
- **Stream join gate (2026-11-06, "as you recommend for all"; Q-STARTUP-GATE):** the 3 s join gate measures
  selection-to-first-surface; launch-to-surface (cold start plus browsing, median 1,713 ms of the old figure) is
  reported separately with its own budget (about 5 s).
- **Audio after a call stall (2026-11-06, "as you recommend for all"; Q-AUDIO-STALL):** after a stall longer than about
  2 s, audio older than the playout budget is dropped and the drop is logged; normal jitter still plays every packet
  (refines the 2026-10-07 "late audio is played" rule).
- **Desktop apps (2026-11-06, "as you recommend"; Q-DESK):** a Mac app first, Windows later. Already overtaken: the Mac
  and Windows apps both exist.
- **Intercom order and design questions (2026-11-06, "as you recommend for all"; Q7, Q9):** order 1) audio-only calls,
  2) push-to-talk in calls and to one peer, 3) store-and-forward messages, 4) group calls, with a 3-person group-call
  prototype before messages v2 (QO1); 3 and 4 get a design and a security review first. Design A and B questions take
  the first option: carrying opt-in with a 64 MB quota and a 50 MB/day cap paused below 20% battery (QA1), contacts only
  (QA2), 1:1 delivery receipts with the time coarsened to the hour, none for groups, no read receipts (QA3), 3-day
  default and 14-day maximum lifetime (QA4), expiry only then telling the adjacent carrier in v3 (QA5), a persistent
  mailbox link identity now and rotating later (QA6), deniable sender proof (QA7), quotas plus contacts only (QA8),
  voice 2 min, video 60 s at about 200 kbps, 2 MiB bundles (QA9); a mixing star (QB1), active speaker only with at
  most 4 people (QB2), first-come-first-served floor with 60 s maximum (QB3), a deputy with a new key when the host
  leaves (QB4), rekey on join too (QB5), speaker misattribution accepted and disclosed in the UI (QB6), hop-local relay
  trees after the star is measured (QB7), the hub admits from a QR roster (QB8), earpiece or headset with a speaker
  toggle (QB9). Where Design M (answered 2026-10-12) decides the same point, Design M wins; Q10 (forward secrecy) was
  answered there as Q-M5.
- **Wi-Fi bearer (2026-11-06, Q11):** later and opt-in, as recommended; carried out by Design WF.
- **Camera target for formal runs (2026-11-06, "as you recommend for all"; Q13):** the owner points the test phones'
  cameras at a chart or moving object and says so, then root runs the formal live-Camera2 queue.
- **GATT PHY split (2026-11-06, "as you recommend for all"; Q14):** build an adaptive split: on `one_pdu_per_event` or
  a one-way stall move only the failing direction to 1M and keep the other on 2M, behind a new Experimental switch,
  A/B on ASUS <-> A15s (the fix) and S24 <-> Xiaomi / A55 <-> M31 (no cost); a device-family entry stays the fallback
  if it is not feasible, and `test_gatt_phy_split` stays Experimental.
- **Calls at critical heat (2026-11-06, "as you recommend"):** on every platform a phone at the critical thermal state
  still places and answers voice calls; only video is refused (camera stopped, video start refused with the heat text).
  Supersedes Android's earlier refuse-every-call behaviour and the Apple P12F port of it.
- **Apple video settings and contacts (2026-11-05, "lets keep those behaviours like android"):** iPhone and Mac follow
  Android: the camera stops at the critical thermal state (serious lowers quality); "Fill screen with video" is on by default
  on both iPhone and Mac; after a code scan Apple shows Android's "Let <name>'s phone recognise yours nearby" consent and
  Call now (a separate package after P7 and P8 merge; reviewer writes the lease gate); the contact sheet's Voice and Video rows
  stay hidden as on Android; Android's per-transport call caps (GATT 180 kbps, L2CAP 200 kbps) apply on Apple, shown as a cap.
- **Wi-Fi (2026-11-04, "do recommended for all"):** a route over a Wi-Fi hop keeps the same route cost as Bluetooth until
  Wi-Fi hops are measured (ranking it better risks flapping between parents); Wi-Fi children count against the Bluetooth
  fan-out budget until measured; on a relay that can hold one Wi-Fi data path, the downstream hop wins (it carries the
  stream to more phones); `CAPS` is sent once per hop and a switch turned on mid-call takes effect from the next call; a
  Wider-reach native hop uses 5 GHz only (2.4 GHz Wi-Fi halved M31 Bluetooth throughput); the OnePlus 8 call cuts get a
  user-started "Send diagnostics" export of the last call's log (no media), behind a switch.
- **MediaTek GATT relay corruption (2026-10-27, "as you recommend for all"):** a MediaTek phone offers relay duty only
  over CoC (DeviceFamilyPolicy entry, off-switch, low confidence: local evidence, no official MediaTek source); a later
  per-chunk integrity and re-request design fixes the cause on the wire. Evidence: A15s (MT6765, Android 10) as GATT
  middle held 0/8 in every arm; single bytes changed inside queued notifications while the chunks stayed contiguous; never
  on single-link calls (228 direct GATT calls, 0 desyncs); Redmi 12 (MediaTek) 3/10 as GATT middle; non-MediaTek middles
  0/72 failures; a CoC middle held 7/8.
- **Stay with the previous relay parent (`relay_prefer_parent`), 2026-10-27:** default off. Middle phone killed and
  relaunched on chain S22 > M31 > ASUS: off re-anchored 5/5 (freeze median 2.9 s), default 4/5 (about 4 s, one never
  recovered), parent watch off 3/4. Small n; confirm with a further batch.
- **Relayed rows missing after an ID rotation (2026-10-27):** every phone flushes its rotating reach table at the 15-minute
  boundary, so cost-2 rows rebuild hop by hop for 30-60 s. Rely on search-on-tap (Design OR) for calls just after a
  boundary, once OR leaves DEBUG; carrying the previous epoch's entries was rejected for now (it links IDs across the
  boundary).
- **Call audio coalescing (2026-10-27, owner "lets keep it on"):** stays on by default, with the evidence corrected: the
  747-drop, 3.7 s audio-age spike that motivated the promotion was one call in the then-off arm; with 6 calls per arm on
  M31 <-> ASUS and S24 <-> M31 (build 5) video and audio rates were identical and rare queue spikes appeared in both arms
  (3 of 36 calls: 1 off, 2 on). No measured harm, fewer records sent; the switch remains as the control arm.
- **PC stream forwarding (2026-11-07, owner "do A"):** deferred. Letting a Windows PC pass a phone's live stream on
  to another device would change two wire rules (a PC stream announcement carrying the relay flag with a cost above 0,
  and the PC offering one child to its parent) and would expose the PC's fixed Bluetooth address, which makes a PC relay
  recognisable from day to day, unlike an Android relay. It waits until the Windows link-renewal and Wi-Fi guest phone
  checks are done; then it gets a design record and an independent dual review before any code.
- **Windows Wi-Fi in the next release (2026-11-09, owner "Skip Windows Wi-Fi for now"):** the Windows Wi-Fi Direct
  guest role ships hidden (off, test switches only). Testing it needs a PC that may leave its Wi-Fi network for a call,
  and the lab laptop keeps its Wi-Fi; the release focuses on Android Wi-Fi first.
- **Wi-Fi Aware band rule (2026-11-09, owner "Judge the 5 GHz data channel"):** an Aware data path whose channel list
  holds a 5 GHz data channel plus the 2.4 GHz discovery (social) channel is judged by the 5 GHz data channel, not
  refused, with a goodput guard: if the measured throughput on Wi-Fi stays too low the hop returns to Bluetooth. A data
  path with no 5 GHz channel is still refused. Reason: the strict "any channel below 5 GHz" rule refused every S22<->S24
  attempt (data path 5745 + 2437 MHz) although the data itself ran on 5 GHz.
- **Wi-Fi Aware on 2.4 GHz (2026-11-09, owner "whatever you recommend"):** Aware is preferred over Bluetooth whenever it
  is available. 5 GHz first; a 2.4 GHz-only Aware data path is also accepted when this phone carries no other Bluetooth
  media link (it is not relaying to Bluetooth children or serving another Bluetooth viewer), because once the media
  moves to Wi-Fi the Bluetooth standby carries only small control records and 2.4 GHz coexistence (which halved M31
  Bluetooth) costs the call nothing. The same goodput guard applies: if Wi-Fi does not deliver more than the hop's
  request, the hop returns to Bluetooth. A phone with other Bluetooth media links keeps the 5 GHz rule.
- **First call frame with a playout ramp promoted (2026-11-09, owner "Turn it on"):** a call shows its first decoded
  frame at once and then eases into the normal playout delay over about 2 s instead of holding the first frame to the
  500 ms target. Measured off vs on, 60 s calls, n=5-6 per arm and direction: M31<->ASUS BLE CoC total
  setup-to-first-surface -0.9/-1.1 s, Realme<->M31 GATT -0.31/-0.35 s, S22<->S24 GATT -0.6/-0.17 s; zero freezes in the
  first 5 s in every arm (the plain "first frame now" arm had 12 early freezes); every ramp caught up in about 2 s;
  worst start lead 161 ms. Cost: about 2 s of slightly slowed video at call start, up to the lead ahead of audio. The
  off switch stays as the control arm.
- **Audio work off the main thread promoted (2026-11-09, owner "if gain there then do it"):** call audio setup and
  teardown run on one serialised command thread instead of the UI thread. Measured M31<->ASUS BLE CoC calls, n=6 per arm
  and direction: accept to ACTIVE -0.08 s (ASUS dials) and -0.23 s (M31 dials), the remote-header UI lag down, total
  setup-to-first-picture unchanged within the scatter, 12/12 calls active in both arms, no freezes. An earlier run's
  4/12 on-arm misses were missing log evidence (ASUS log drops occur in both arms), not failed calls. The off switch stays
  as the control arm.
- **A delivered recovery control write counts as the first write (2026-11-10, owner direction "if gain there then do
  it"):** on Android 10-11 GATT servers, the stage-2 first-write watch no longer treats a link whose authenticated
  recovery hello arrived as "writes never reach the app", so it stops cancelling and re-registering a working link.
  A15s (Android 10) dialing ASUS (Android 11), GATT, n=6 per arm: calls active 6/6 vs 4/6 and 5/6 vs 4/6, GATT 133
  failures 10 vs 83 and 5 vs 19; ASUS dialing A15s unchanged. Small n on one pair; the off switch stays as the
  control arm. Related: a user's second call to the same peer starts on the remembered 1M PHY and connects in about
  1.9 s instead of the first call's 13 s (the first call pays the 2M stall and retry once).
