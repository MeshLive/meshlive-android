# Design C: relayed private calls and multi-hop discovery

**Design review required.** Default off (`test_relayed_calls`, Settings > Advanced "Experimental: relayed calls"). The
design was reviewed independently on 2026-10-11 and builds on the earlier draft (R14-R22) and on these design
decisions: hop-local reading accepted, relay helping on by default while the app runs once the feature is enabled,
consent asked once and preselected, a radius of 2 relays. Wire rules: PROTOCOL.md "Experimental relayed calls and
multi-hop discovery". With the switch off, every byte on air and on the link is unchanged.

**Goal.** A caller A reaches a call-ready phone D that is one or two relay phones away, and an ordinary phone relays
calls in the background without being told which call to take. Before this, only DEBUG relays existed (a launch extra
names the callee).

- **Roles and reach table.** Target D (call-ready, consents), relay helper R (opted in, call endpoint idle), caller A.
  Each helper keeps a table of targets fed only by adverts it scanned itself: D's own call offer gives a cost-1 entry, a
  neighbour's reach-page entry gives cost + 1, up to the cap (default 2, DEBUG `test_relayed_calls_max_relays`, at most
  4). Reach pages are a second legacy non-connectable advertising set (one page per second).
- **On-demand setup.** A connects to R1's call listener, authenticates the hop, and sends `SETUP(target, budget,
  remaining time, setup ID)` as the first hop record (hop type 12). R1 checks its table, connects to the next hop
  itself and regenerates the same message downstream; `READY` and `FAIL` return one hop at a time. After READY the
  existing opaque call relay copies the ordered end-to-end records, and A runs the ordinary end-to-end handshake
  through the chain.
- **Hop-local, with stated stretch.** A phone re-advertises only what its direct neighbours told it, and setup is
  regenerated at every hop with a new ID, smaller budget and shorter deadline. Reachability spreads by repeated
  re-advertisement (a distance vector with an age), and the target's identifier travels the chain inside the SETUP, so
  each relay on the path learns its adjacent links, the target and (from the signed end-to-end hello it copies) both
  endpoint identities. A passive observer near a relay learns that D is within N hops of it.
- **Identity and abuse limits.** A relay cannot read or forge content (end-to-end keys). A requires the end-to-end
  peer to match the target it selected (the full fingerprint for a verified contact); each helper link identity must
  equal the helper offer that was selected; the last relay accepts only the target or another relay as its child.
  Absolute deadlines from READY (10 s end-to-end handshake, 20 s Invite) and a 60 s ringing cap stop a caller from
  pinning helpers.
- **Rotating IDs.** With both experiments on, relaying uses role-0 rotating IDs only (reach pages version 6, `SETUP`
  message 4, every hop checked with `BDO1`/`BDX1`); DEBUG `test_relayed_rotating=false` restores the earlier exclusion.
- **Battery and pre-emption.** Helping runs only while the phone is call-ready and none of these hold: battery below
  20% and not charging, Battery Saver, severe thermal status, Bluetooth off, the phone's own exchange. A relayed call
  already running is not cut by a pause. Starting or answering the phone's own call or stream ends any relay in
  progress. The foreground notification reads "Helping a call nearby" with a Stop action. Idle scanning cost per hour is
  a gate to measure on each reference phone before any default is considered.
- **Failure and fallback.** A shows the route ("Nearby", or "Via 2 phones, heard 8 s ago, unconfirmed"), picks the
  fewest relays then the freshest, and skips a failed pair for 30 s. A chain that breaks mid-call ends the call;
  automatic re-routing is future work. Direct fallback is a failed requested chain: `test_relayed_calls_require=<n>`
  accepts only a chain of exactly n relays. One bearer end to end; only LE transports are supported on demand. iPhones
  can neither help nor be relayed targets until the compact amendment.
- **Deferred:** automatic candidate iteration inside one attempt, the embedded single-entry carrier for phones without a free advertising set, Classic on-demand relays, mid-call re-routing, extended-advertising reach lists and an iPhone caller. Phone-probe history: [decisions](README.md) "Relayed calls: first phone probe".
- **Phone verification queue** (one helper at a time, `test_relayed_calls=true` on every participant): run
  `python -B tools/dex_api_scan.py` on the APK first; then one helper on GATT with `test_relayed_calls_require=1`,
  rotating IDs off and on (S24 -> ASUS -> M31 and the reverse, then each phone as helper; the M31's dual-role limit
  decides which may help), then the same on LE CoC. Record setup time (invite to ringing) and compare quality with the
  DEBUG chain at the same settings (relayed video cap 15 FPS / 110 kbps).

## Amendment CP: which identity each relayed-call edge uses under BMH3 pseudonyms (revised after review CP-1; built 2026-10-27)

**Broken (measured).** With the shipped defaults (BMH3 on, "Pseudonymous toward strangers" on), a call to a contact through one helper failed at the caller-to-helper link: the caller logged `bmh3: event=link | role=initiator | order=R_FIRST | result=pseudonymous_answerer_to_a_real_caller` and the helper only `Incoming call failed: EOFException`; it never reached `relayed_setup`. With BMH3 off (benchmark launches) the same call works.

**Cause (code).** Toward a relay the caller's link handshake already used a throwaway (`Bmh3Policy.initiatorOptions`, `towardRelay`: no contact tag, `identityOverride`), but that throwaway did not declare the `PSEUDONYM` extension, so it counted as a real key. The helper heard no tag it knew, answered with its connection pseudonym (rule P), and the caller's defence-in-depth check "a real key never answers a throwaway" (`Bmh3Initiator.onMessage2`) refused it. Behind that edge three more stopped the call: the helper's listener refused a relay request on any pseudonymous link (PR2/PR8), the caller required the helper's link identity to open the selected helper offer (a pseudonymous helper withholds its opening), and the helper dialed its next hop with BMH2, which a phone in pseudonym mode refuses.

**What a relay can and cannot do (premise corrected by review CP-1).** A relay forwards end-to-end records it cannot read. In a **contact call** the caller tags the target and requires the contact's full node ID before it presents its real key, and the callee answers with its real key only to a matching tag, so a relay can drop, delay, observe sizes and timing and learn the target's current ID, but cannot pose as either end. In a **stranger call** (no tag) a relay can sit in the middle: it answers the caller as the callee and dials the callee as the caller. The caller stays protected (it reveals only after verifying the callee's reveal, which is bound to the session it was made in, so a relay cannot pass it on), but if the callee accepts, its reveal and its media go to whoever holds the call, which may be the relay. Encryption stays on everywhere; it does not decide who the other end is.

**Rules (built).**

| Edge or step | Rule |
| --- | --- |
| Caller to first helper (link) | BMH3, never BMH2 while pseudonym mode is on. No contact tag (not even the DEBUG test lease). A throwaway that declares `PSEUDONYM`, fresh per connection (never the end-to-end attempt key, so the helper cannot link the two layers); nothing pinned or admitted. A hop that answers with a real key must open the selected helper offer (an impostor with a real key fails); a hop that declared a throwaway is not bound. |
| Helper to next helper, helper to target (links) | The same, decided once per duty so that no retry (the fast connect retry or the 2M to 1M retry) falls back to BMH2. A real key must be the selected offer; a declared throwaway is not bound, and the caller's end-to-end check authenticates the target. |
| Caller and target, end to end | Unchanged: the contact tag and full-fingerprint check for a verified contact, otherwise the attempt pseudonym and the reveal on accept. |
| Contact-helper arm (Experimental, default off) | A caller that recognises the helper's offer as exactly one contact tags that helper's own offer and runs real/real with the opening check. Privacy cost: the helper learns who is calling. A throwaway answer fails that candidate (no anonymous retry inside it). |
| Helper dispatch | The first record of a link with a throwaway on either side may be `SETUP` (pseudonymous edges on and this phone a helper; its own switch, pause state and limits decide next) or an end-to-end handshake (a direct stranger call). Store-and-forward stays refused; a verification-code link never carries `SETUP`. A rotating helper answers `SETUP` message 1 (a fixed-prefix target) with FAIL 5. |
| Helper limits | The per-parent limit (3 per minute) is keyed on the link's remote address for a pseudonymous parent, an unknown address sharing one bucket, never logged in release builds; the overall 10 per minute and one relay at a time stay. A `SETUP` refunds no BMH3 signing tokens and clears no replay protection (BMH3 runs its signing budget, not the RV8 tarpit). |
| FAIL codes and route marks | A `SETUP` for the helper's own ID answers FAIL 1 (no route), like a missing entry; FAIL 6 is for parent and child identity loops only. A closure before READY is FAIL 4 (timeout), not FAIL 1, which would drop a working pair's entry. The caller marks a route used only after the target's identity checked out (its reveal or its end-to-end opening). |
| Target | Any relayed call (the authenticated link peer is not the call peer, or the Invite says relayed) is declined before it rings unless this phone's relayed calls and relay consent are both on. A relayed stranger call rings only with relayed stranger calls on, with the status "via a phone nearby" and the corrected wording. Every relayed ring is capped at 60 s; endpoint caller blocking before the ring is unchanged. A strict Contacts-only target (BMH3 contacts-only) is unreachable through relays: an untagged helper meets I_FIRST, which it refuses. Consent is an advert and table rule (honest helpers check the bit) plus this target-side decline. |
| Link opening (`BDO1`) | A responder skips the link opening only when it presented a throwaway itself; a real responder answering a throwaway initiator exchanges, as the initiator does. This also fixes a direct call from a pseudonym caller to a phone with pseudonyms off. |

**Privacy (corrected).** On an all-BMH3 route without the contact-helper arm, relays receive no endpoint long-term identity in plaintext and no hop shows a long-term key on air. Exceptions and residuals: the contact-helper arm shows the caller to that helper; a hop that runs BMH2 (pseudonym mode off) shows keys on air; every helper learns the target's current ID, the adjacent addresses, timing and sizes and its own forwarding relationship; a rotating ID stays linkable within its epoch. This is opportunistic encrypted transport, not anonymity: an unknown helper can observe and suppress.

**Security.** A helper that declared a throwaway is not authenticated: a phone that copies a helper's advert can take its place (drop, delay, observe, learn the target ID, forge hop-local READY or FAIL). Accepted for opportunistic helpers; the contact-helper arm gives first-hop trust. Future work: an epoch relay verification key committed by the helper's advert and proven over the BMH3 transcript (an advert change). The draft's opening-salt MAC is withdrawn: the caller cannot verify it without the salt.

**Compatibility.** Switch off: today's behaviour, under which relayed calls fail at the first link with the pseudonym defaults. A helper or target with pseudonyms off answers with its real key and is bound to the selected offer. An older helper refuses `SETUP` on a pseudonymous link: that candidate fails and candidate iteration tries another helper. A phone with BMH3 off cannot be reached through a helper in pseudonym mode (no BMH2 on a relay edge).

**Switches.** `test_relayed_pseudonymous_edges` (on; `false` is the control arm), `test_relayed_stranger_calls` (on within the relayed-calls experiment; `false` is the control arm, contacts only), `test_relayed_contact_helper` (Experimental, off). The relayed-calls setting itself takes effect only while Advanced settings are visible (`RelayedCallsFeature.resolve`).

**Design decisions.** 2026-10-26: helpers relay calls for stranger callers (reverses the relay part of PR2/PR8); message exchanges with strangers stay refused. 2026-10-27, after review CP-1 corrected the premise (a relay can sit in the middle of a stranger call): stranger relaying approved with its safeguards, the "via a phone nearby" ring label and the wording "revealed to whoever holds the call, which may be a relaying phone"; encryption stays on everywhere.

**Review CP-1 (2026-10-27; two independent reviews, both "approve with changes", and a separate Design C review).**

| Finding | Resolution |
| --- | --- |
| 1 (high) BMH2 on a relay edge while pseudonym mode is on; draft fell back to BMH2 for version 2 offers | No BMH2 on any relay edge in pseudonym mode (`relayHopUsesBmh3` uses BMH3-only or pseudonym mode as the direct calls do); a rotating helper refuses SETUP message 1 (FAIL 5); the hop choice is made once per duty, so no retry falls back to BMH2. |
| 2 Overclaims: a relay can sit in the middle of a stranger call; "no long-term key on any hop" | Premise and privacy text corrected above; callee wording and the "via a phone nearby" ring; stranger relaying on its own switch. |
| 3 Bind hops that answer with a real key | Bound to the selected offer whenever the answer is a real key; skipped only for a declared throwaway (as `initiatorOptions.checkResponder`). |
| 4 Relay hops: no tag, no pin, no admission; fresh throwaway per connection; test-lease ordering | `relayHopOptions`/`relayLinkOptions`; `initiatorTag` returns no tag toward a relay before the DEBUG test lease is considered. |
| 5 BDO1 asymmetry | `skipsLinkOpening`: a responder skips only when it presented a throwaway. |
| 6 Contact-helper arm | Experimental, default off; its privacy cost stated; a throwaway answer fails the candidate. |
| 7 Dispatch and limiter | SETUP or an end-to-end handshake as a pseudonymous link's first record, store-and-forward refused, verification-code links never carry SETUP, helper switch gates; address-keyed limiter with a shared unknown bucket; no signing-token refund; the RV8 tarpit requirement dropped (BMH3 does not run it). |
| 8 Target side | Caller blocking before the ring kept; the 60 s cap on every relayed ring; strict Contacts-only targets documented as unreachable; consent documented as an advert and table rule plus the target-side decline (finding B). |
| 9 FAIL classes and route marks | Closures before READY are FAIL 4; a route is marked used only after the target's identity checked out. |
| 10 Docs | Public-safe text outside the private-marked block; the salt MAC withdrawn; the advert-committed relay key noted as future work. |
| A (Design C) An own-ID SETUP answered FAIL 6, telling the asker that a consent-off phone is one hop away and linking its IDs | Answered FAIL 1, like a missing entry; FAIL 6 kept for parent and child loops. |
| B (Design C) A target with the experiment off still rang a relayed call (a dishonest helper ignores the consent bit) | Declined before ringing unless relayed calls and relay consent are both on. |

**Tests.** JVM (`RelayPseudonymousEdgesTest`, real BMH3 handshakes on pipes): the control arm reproduces the phone failure; a declared throwaway reaches a pseudonymous helper and no real key signs; a pseudonyms-off helper answers real and is bound, an impostor with a real key is refused; the onward edge binds a real target, refuses an impostor and accepts a throwaway target; every relay connection gets its own throwaway, never the end-to-end key; no tag toward a relay even with the test lease; the contact-helper arm is real/real and fails on a throwaway; no BMH2 in pseudonym mode and the message-1 refusal; a relay posing as the callee cannot get the caller to reveal; the dispatch matrix; many throwaways from one address share its limit and the global cap holds; closures before READY are FAIL 4; the target's decision. `RelayHelperPolicyTest`: an own-ID request is FAIL 1. Phones (to run): contact and stranger targets through one and two helpers on the defaults, the mixed modes, and a snoop check that no long-term key appears on a relay edge.
