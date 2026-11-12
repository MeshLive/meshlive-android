# Open defects in shipped behaviour

These are open across every phone combination that uses the affected behaviour. The benchmark suite's case state (see
[testing.md](../testing.md)) owns pass and fail history and retest requirements.

| Work | What must be established before closing it |
| --- | --- |
| Uneven playback and picture quality | Separate source pacing, encoder output, Bluetooth stalls, receive queues, decoder processing and presentation. Correlate traces with run, session and PID identity and each phone's clock; compare the same input and settings. Frame-timing proxies and short clean prefixes cannot approve screen smoothness. The retained scheduler analysis has initial-only clock snapshots, so obtain the missing stage and clock evidence before promoting another display policy |
| Connecting/Recovering flashes and reconnect-to-scan loops | Trace each setup, scan, cancellation, disconnect and completion against its owning attempt and session. Establish a stable UI state, bounded retries, truthful discovery and reusable availability after End, loss and cancellation; keep planned losses distinct from unexpected ones. Causes found offline from saved viewer logs (2026-10-11): the recovery cover (a 70% black layer) followed the decoder's recovery flag with no hysteresis and flashed on and off about a dozen times in 30 s on older builds; a failed join returns to the browser by design while its route cools down. Fix built (default on, control arm `test_receiver_ui_stability=false`, not yet tested on phones). Still open: a failed join still shows the playback screen for 0.25-0.4 s, some "Connecting" overlays wait the full 15 s with no progress text, and planned versus unexpected loss is not distinguished in the UI |
| Real three-phone chains | Diagnose the retained decrypt failures, missing completions and guard withdrawals without assuming that event order across different phone clocks proves the cause. Preserve the authenticated original source, requested immediate parent, ordered encryption, admission and overload protection; direct-source fallback fails the requested chain |
| Bidirectional calls and reverse setup | Resolve the reverse-caller GATT setup that queued MTU work without completing it; trace request, callback, teardown and stale-session ownership. Native inputs reject a negotiated geometry or FPS mismatch before media starts, so prepare exact matching native call requests or report ineligibility. Improve both directions, local controls, framing and recovery independently. Causes found offline (2026-10-11): the original wedge was MTU, PHY and priority work queued within 1 ms of the connection callback (fixed by the setup order MTU, services, subscription, then the client PHY); the stalls that remain (21 of 1,167 saved setup attempts with an MTU request and no callback) are either a dropped request (the stack logs `configureMTU() - No connection`, all before the deferred GATT close on SDK <= 30) or a request queued behind the stack's own service discovery (A15s median 1.34 s, a 5th relay child 3.2 s). Fix built (default on, control arm `test_gatt_mtu_probe=false`, not yet tested on phones): at 0.75 s without a callback one `readPhy` proves the request is only queued and the wait runs to 8 s; no callback keeps the 3 s verdict. Still open: the retry onto a half-closed ACL, and one A15s case that received the MTU response with no app callback |
| Relayed viewers hold seconds of media | A viewer behind a relay showed 2.7-7.5 s held age with its playout pinned at 2000 ms (direct 250-345 ms). Traced per stage (S24 -> M31 -> ASUS, LE CoC, 200 kbps offered): the source and the first hop add tens of milliseconds, the relay forwards the original frames, the relay's app queue holds 1.3-1.9 s, the Android socket buffer and stack below it 3.2-4.4 s, the air carries a flat 144 kbps (one PDU per 15 ms event), and the viewer's playout then holds 2 s because it cannot shrink while media is late. The relay skipped its child pacer whenever audio was queued anywhere, which is always during a backlog. Built 2026-10-11: `test_relay_pace_head` (default on, control arm false) and Experimental `test_relay_pace_lead_cap` (see the register in ARCHITECTURE.md). Open: a phone A/B on one frozen build (control / head fix / head fix + lead cap, CoC and GATT, 3 repeats, a direct control); a policy for a relay link slower than the stream (shed, withdraw and reroute, lower the sender rate, or batch audio records); a relay holds its own screen 1562 ms (the child-join reserve) |
| Relay dual-role scheduling (M31 relay child link) | A relay is LE peripheral to its parent and LE central to its child, both at 15 ms, so their anchors keep one fixed phase. On the M31 (Exynos 9611) a child anchor 11.3-13.7 ms after the parent anchor got no completed exchange (9 of 55 child attempts silent until disconnect reason 0x08, 22 never established with 0x3E; ASUS: 0 and 5 of 29); the 7.5 ms and 45 ms stages never died. Built: default-on `test_relay_child_stall_guard` (closes a silent LE child link after 2 s, counted by the establishment fallback), Experimental `test_relay_upstream_dck` (an Android 14+ parent requests DCK, 30 ms, for a relay-capable child so the relay's 15 ms child link has two events per parent event), an inert device-policy proposal, and `tools/relay_dual_role_snoop.py`. Open: the phone A/B with HCI snoops on both relay candidates (relay under test and control relay, GATT and CoC, planned viewer restarts labelled, 3 repeats, at least 15 child attempts per arm; DCK passes when the parent link shows 30 ms, no silent timeouts and no worse leaf freezes; the stall guard passes when a dead attempt ends within 3.5 s and no healthy join is closed). Evidence: DECISIONS "Relay dual-role scheduling" |
| Call speech behind video writes (head-of-line) | GATT call audio waited 620-1193 ms behind one video frame; the audio-headroom fix only stopped queue overflow. A four-arm A/B (control, fragments, fragments + speech-led playout, fragments + airtime-sized chunks) on M31 <-> ASUS and S24 <-> A15s is in DECISIONS "Call head-of-line A/B"; fragments with speech-led playout are on for direct GATT calls in user launches since 0.2.0-beta. Open: received-audio continuity and A/V offset with speech-led playout, CPU and battery cost on the M31 sender, a third repeat on two arms, A15s over CoC, and an HCI-level check that audio really goes on air between fragments |
| Visible age of the last received frame | A frozen frame must never be presented as live. Built behind the default-off switch `test_viewer_frame_age` (Settings > Features): stale-picture dim and badge, an honest "Last update" label (this phone's last picture, not camera-to-screen delay) and `viewer_frame_age` log lines. Remaining: a phone test in the relay loss/rejoin lane, then flip the default. Still open: a stall that keeps the link up replays its backlog late by the no-loss design (the bounded-age experiment is the separate answer); a clock-synced end-to-end age would need a protocol design |
| Stream link role reversal (design, needs a PROTOCOL review) | Today a relay or source is the LE central and connects to the advertising child. A relay that is a busy peripheral on its parent link cannot always establish that second, central connection (an M31 snoop shows ten LE Connection Complete events at 45 ms, each ending 0x3E after 6 events). Root fix: the child connects to the relay as central and the relay accepts the CoC, with the same authentication, grant and hop-local control. It would remove the central-initiation failures but not the equal-interval collision above, so do not build it before the dual-role A/B shows how many failures remain with `test_relay_upstream_dck`. Before coding, record in PROTOCOL.md the advertisement and accept flow and its admission check, discovery and privacy impact, and behaviour on Android 10-14 stacks |
| Requested versus effective settings | Use the register of concrete limits and the capability evidence to challenge call budgets, legacy protections and other limits. DEBUG higher-rate comparisons stay experiments. Expose every difference in encoder, allocation, geometry, rate, FPS, audio and buffer rather than silently substituting settings |

## Policy workstream status (build 454cf485, 2026-10-07 and later)

Status (build `454cf485`, 2026-10-07, plus later notes):

- **A.** No media is dropped for streams; native AV1 pairs showed 0 freezes and 0 audio drops up to a CoC offer 1200
  kbps above the link (measured 2026-10-07). Open: the policy for calls beyond the link's capacity (lower call frame
  rate, longer call delay, or the current video skip) and visible-FPS dips while an overloaded link's delay grows.
- **B.** Hop-local 6-byte reports (arrival rate, pinned delay, added delay); learned ceilings persist with a 6-hour
  re-check.
- **C.** Native inter frames are capped at 250 ms of the per-viewer link share. The M31's OMX VP9 encoder ignores
  video-QP bounds and offers only VBR, and its large frames are four-frame scene-change bursts (about 210 KB), so
  keyframe shaping (a DEBUG A/B) is not promoted. Next: forced CBR on the M31 (DEBUG `test_bitrate_mode`) and the
  15-20 s encoder trim, judged by same-build VMAF.
- **Relay child joins.** Cause found: the joining child's keyframe request crosses the parent link exactly when it
  loses airtime to the new child link (S24 VP9 keyframe of 55.7 KB, link 791 -> 269 kbps, a 1.7 s gap). Options: start
  the child from the relay's next natural keyframe, or grow the relay's playout delay before it connects a child.
- **D.** The picker uses the measured link (fixed maxima are fallbacks). Still to replace: the 20% per-link planning
  margin and the 0.9 ceiling headroom.

## Interim bounded-age evidence

Interim bounded-age evidence (not conclusive; repeats were still running): with the A15s sending over a weak
libaom/AV1 link, the bounded arm paused video one to three times per call (longest stall 3.8-9.2 s, 26-58% frozen,
against 0.4-2.7 s and 3-18% for no-loss) and did not improve speech; on M31 <-> ASUS calls at 1000 kbps it was worse
(CoC held-age p95 1479 against 1108 ms) or had about twice the freezes (GATT); S24-sending directions and M31 <-> ASUS
streams were about equal. Likely cause: the call arm assumes a 500 ms peer base delay (a 100 ms queue budget) and
requested keyframes re-create bursts. Current recommendation: keep no-loss as the default and bounded age as an
off-by-default experiment; a fairer variant would exchange the playback profile in call setup, make audio-only the last
resort and recover with intra-refresh or the next natural keyframe.

## Discovery privacy status (2026-10-12)

Status (2026-10-12): rotating discovery IDs, the BMH3 identity-hiding handshake and pseudonyms toward strangers are on
by default since 0.2.0-beta (one constant each; an explicit stored choice wins). The design was reviewed on 2026-10-10
(findings RV1-RV19). Coded: phase 1 (LE call-ready presence), the Visible-by-handle window, phase 1b (stream offers,
viewer requests, forwarders, call-relay chains), phase 2 (presence leases, `BDX1`, the recognition table), reach pages
version 6 for relayed calls and non-discoverable connectable sets on Android 14+; formats and vectors are in PROTOCOL.md
under "Experimental rotating discovery IDs". Measured once, on four phones: no field carried over any of three ID
changes (PRIVACY.md "Measurements"). Not measured: battery cost, the Bluetooth name read on the A15s, an
active attacker holding a contact's key, fake adverts, relays with the new handshake and abuse limits. Open design
question: the monotonic epoch never returns after a backward clock step, so a lead of 3 to 96 epochs leaves a phone
unable to receive a version 5 call until it recovers. The list below was the plan; items 0 and 1 were exercised in that
session, the rest follow:

## Relayed calls: what the Nearby list shows (design decision 2026-10-26)

Question: should people reachable only through other phones be listed up front? Decision: no for strangers. The Nearby list shows phones in direct range plus saved contacts that are reachable through relays; tapping Call on a contact searches every route (direct, one or two relays) and either calls or says the contact cannot be found. The background reach tables stay, because they make the tap fast (3-8 s measured when helpers already know a route, 12-35 s from cold). Both other options stay as experiments for an A/B: listing every routed stranger ("via 1 phone", `test_relayed_list_strangers`), and an on-demand route query with no background tables (needs its own design record and review before code).

## Design C status

- **Status.** The first phone probe (2026-10-11) found a helper crash from an API 33 call (fixed; see DECISIONS
  "Relayed calls: first phone probe"). In the 0.2.0-beta combined privacy session, one-helper calls completed on GATT
  and LE CoC; two helpers never formed a route. Deferred: automatic candidate iteration inside one attempt, the embedded
  single-entry carrier for phones without a free advertising set, Classic on-demand relays, mid-call re-routing,
  extended-advertising reach lists and an iPhone caller.

## Design F rationale, reviews and measurements

**Why.** Rotating discovery IDs hide the advertisement only; the BMH2 link handshake sends both identity keys in the
clear on every hop and answers any connector with its own. BMH3 is a SIGMA-style handshake in both reveal orders using
the existing primitives and the signing-only Keystore key, with a lease-keyed contact tag bound to the selected advert.
Phase 2 gives a stranger who connects a throwaway software identity per connection; the real identity follows only for
verified contacts or after the callee presses Accept, in a signed `REVEAL` bound to the session.

**Reviews.** Two independent security reviews (findings SR1-SR14 and BR1-BR11 for the handshake, PR1-PR15 for the
pseudonyms) were adopted or rejected with reasons in the design record; the executable specification is
`tools/bmh3_reference.py` with byte-exact vectors in the JVM tests.

**Measured** (the combined privacy session of 2026-10-11, build 3996bfb2): a snoop of the BMH2 control call showed both
identities in the clear and none in three stranger calls; the reveal followed Accept and never followed Decline. Not
measured: battery, the A15s name read, an active lease-key prober, fake adverts, relays with BMH3, message exchanges and
abuse limits.

## Stalled-setup rediscovery budget (2026-10-15, `test_stall_rediscover_ms`)

**Broken (measured):** lane11-stats-20261015/b2-calls-a15s-asus, A15s to ASUS private calls on 2M: 5 of 6 failed. The 2M setup stalled (`setup_stall`, action=close at 3 s); the A15s controller sometimes never delivered LL_TERMINATE, so the ASUS saw supervision timeout 0x08 about 7-9 s after the close (instead of 0x13 after 1-3 s) and re-advertised only 1.6 s after that, 8.6-12.6 s after the close. `rediscoverPeer` budgeted min(8 s, 15 s early-loss window minus elapsed), ended `rediscover=none` and dialed the stale address, which timed out after 12 s. In all 7 successful rediscoveries the address had changed (`sameAddress=false`), so the stale dial cannot succeed.

**Changed (Experimental, default off):** `test_stall_rediscover_ms` (8000 = unchanged; A/B arm 20000). After a `CallSetupStalledException` the scan is decoupled from the early-loss clamp and runs up to the configured total (60 s at most), a found fresh address is dialed at once, the stale address only when nothing was found. Bad-2M memory, the 1M retry, hop-local rules and the relayed-offer rule are untouched. Files: AdaptivePhy.kt (`StallRediscover`), AndroidPrivateVideoCall.kt, DebugTunables.kt, MainActivity.kt; tests `StallRediscoverTest`.

**Re-check:** phone A/B on A15s to ASUS (6 calls per arm, learned state reset, one frozen APK): calls reaching active, `BENCHMARK call_rediscover` waitedMs and dialed=fresh|stale; preservation: S24/M31 pairs must not change (the stall path is rare there). Affects every direct call dial that stalls on 2M, not only this pair.

## Duplicate role-reversal row

| Stream link role reversal (**design review required**, PROTOCOL change) | Today a relay or source is the LE central and connects to the advertising child. A relay that is a busy peripheral on its parent link cannot always establish that second, central connection: an M31 relay snoop (2026-10-10) shows ten LE Connection Complete events at a 45 ms interval, each ending 0x3E after 6 events. Root fix: the child connects to the relay as central, and the relay advertises its offer and accepts the CoC, with the same hop and end-to-end authentication, grant and hop-local control; the busy relay then never initiates, and each phone is central for at most its own parent link. Before coding, record in [protocol reference](../protocol/README.md): the advertisement and accept flow and its admission check before accept; discovery and privacy impact; behaviour on Android 10-14 stacks (CoC server on the relay); how the child chooses or keeps the 2M/1M PHY; and A/B evidence against `relay_establish_fallback` on the same topologies. |
