# Design SG (Option A coded 2026-10-15 behind `coc_auto_gatt` + `test_coc_auto_gatt_streams`, off; phone tests not run): automatic GATT fallback for streams when a receiver cannot open an LE CoC listener


**Design review required** (security and wire sections below). Default off; it would ship as an Experimental sub-switch
that needs `coc_auto_gatt` (Settings > Features "Switch to Bluetooth LE (GATT) when L2CAP fails") and has its own DEBUG
extra (`test_coc_auto_gatt_streams`). Today `coc_auto_gatt` covers private calls only.

**Problem.** A receiver on a stack that stops accepting LE CoC listens (the Android 10 A15s leak: about 15 registrations
per Bluetooth process, closing sockets does not free them) cannot start a stream receive over BLE L2CAP at all. For calls
the phone detects the failed listen and restarts on GATT (`onCocFailure(AVAILABILITY_FAILED)`), and its offer is listed by
GATT-aware callers. For streams nothing happens: the receiver reports a startup failure and stays unreachable. The fix is
not symmetric, because a stream receiver is not a call endpoint: it advertises a viewer request and the source dials it.

**Evidence (code and logs).**
- The receiver's listen failure is the `STARTUP` stop cause in `AndroidVideoReceiver` (any exception before or while
  accepting); `MainActivity` then reports a startup notice and has no transport retry. The call path retries through
  `CocRegistrations.session.onCocFailure(...)` (`CocAutoGatt.kt`).
- The source selects viewers with `announcement.transportOption() == outgoingTransport` (the viewer-request scan filter in
  `startViewerRequestDiscovery`). A GATT viewer request (announcement flags `0x09`: accepting plus GATT, PROTOCOL's
  version 5 vector) is therefore invisible to an L2CAP source, so a receiver that restarted on GATT alone would never be
  dialed.
- `BluetoothVideoBroadcaster.connect(...)` takes one session-wide transport, which feeds admission (`fanOutChildren`,
  `admissionTransport`), `LinkCapacity.budgetKbps(transport, FAN_OUT, perLink)` and the starvation withdrawal.
  `BluetoothVideoSender.connect(...)` already takes the transport per child, so a mixed fan-out is possible at the link
  level; the planning and the encoder rate are what is session-wide.
- Call evidence that the mechanism works where it exists: with `coc_auto_gatt` on, the A15s rang and went active over GATT
  in 22/22 calls, and 0/22 with it off.
- A GATT link carries a lower sustained rate than LE CoC (`BluetoothTransportOption.maxVideoBitrateKbps` differs by
  transport), so a GATT child cannot take the stream of a CoC-rate source unchanged.
- The default-on `CocListenerHub` (one long-lived listener per role) already removes most of the registration pressure, so
  the fallback is a safety net for the case where a listen still fails.

**Options.**

| Option | What happens | Gain | Cost / risk |
| --- | --- | --- | --- |
| **A (recommended). Receiver falls back; the source adds a separate GATT child pool (mixed fan-out)** | The receiver restarts on GATT after a `STARTUP` failure on L2CAP (once per adapter session, same ledger as calls, visible notice naming requested and effective transport). A source whose own transport is L2CAP and which has the sub-switch on also accepts GATT viewer requests, dials them as GATT children with their own pool (initially 1) and charges them to the aggregate budget at their measured GATT per-link rate. Never the reverse: a GATT or Classic source never accepts L2CAP children. | The fallen-back phone receives; other children, the encoder request and the requested transport of the session are untouched; reuses the per-child transport parameter and the signed link hello. | Mixed-transport admission and accounting; a GATT child cannot take the CoC bitrate, so it needs a thinned layer or the lowest layer (Design RT relay child thinning, `layerShareLabel`) or it is refused with a visible reason; shared radio (a GATT child costs airtime of the CoC children). |
| B. Source restarts the whole session on GATT when a viewer can only do GATT | One transport per session, as today. | Simple admission. | Every healthy viewer is cut down to GATT rates and the session restarts; it changes a requested transport mid-run (against the benchmark rule: never silently change a requested transport). Acceptable only when the source has no other child; not recommended as the general rule. |
| C. Do nothing on the air; tell the user | A clear receiver notice: this phone's Bluetooth cannot receive L2CAP streams; turn Bluetooth off and on (a 20-25 s toggle restored L2CAP for one cycle). | No protocol or admission change, no new radio mix. | The phone stays out of every L2CAP stream until the user acts; weak for the off-grid user who cannot diagnose it. |
| D. Source advertises dual transport (Design D for streams) | The source hosts a GATT server beside its CoC server and says so in a hint bit. | Reuses the Design D idea. | Wrong direction for streams: the viewer is the advertiser and the source is the central, so the source needs a GATT client, not a server; the hint adds a linkable bit to stream offers (PRIVACY). Rejected. |

**Recommendation: A, in stages, with C's notice shipped first.** Stage 0 is the receiver notice (plus a
`stream_listen_failed` BENCHMARK line carrying the stop cause and the CoC registration count), which costs nothing and needs
no review. Stage 1 is the receiver-side restart on GATT behind the sub-switch with the source unchanged, tested only to
prove that a GATT viewer request appears and that the flag-off path is byte-identical. Stage 2 is the source's separate GATT
child pool; it is the part that needs this record's review and the phone tests below. A is preferred over B because B turns
one phone's stack bug into every viewer's quality loss.

**Wire and PROTOCOL impact.** None on the air for stages 0 and 1. A GATT viewer request is already encodable (flags
`0x09`); no new bit, field or UUID. Stage 2 changes only the source's local filter: with the sub-switch on and an L2CAP
source, a viewer request with `FLAG_GATT_TRANSPORT` and an accepted stream token is also selected, and the child link is
opened by the existing GATT path with the existing signed link hello (which already carries the transport bit), so the
hello bytes of every existing case stay identical. PROTOCOL gets one sentence in its viewer-request section stating the
source filter rule and that the transport of a child link is the transport of the request that selected it. The
rotating-ID rule (a version 5 viewer request mirrors the offer's version) is unchanged: the request is built by the same
code, with the GATT flag.

**Security and authentication (nothing is relaxed).**
- Hop authentication and ordered AES-256-GCM records are the same on a GATT child as on any hop; the link hello and the
  end-to-end stream identity checks (authenticated original source and requested parent, stream token binding) run
  unchanged. A fallen-back child is a child like any other: admission, the starvation guard and overload withdrawal apply,
  and a direct fallback to the source from a requested relay chain is still a failed chain (no silent route change).
- No downgrade by a third party: the fallback is decided locally by the receiver from its own failed listen (never from a
  received advertisement), and the source's acceptance of GATT requests requires the same stream-token window as an L2CAP
  request. A nearby attacker can at most request a GATT slot, which is bounded by the separate pool, the token check and
  the authentication that follows; it cannot raise the pool or lower another child's transport.
- Privacy: no new announcement bit and no address change; the GATT viewer request is the existing form, so the discovery
  rules (Design E, Design F) hold. The notice and the BENCHMARK lines name transports, never identities.

**Phone tests (not run; a design is not a result).** The A15s (Android 10) is the leak phone and is not one of the six
required phones; required-phone coverage uses a DEBUG listen-failure injection (`test_coc_listen_inject=stream`, to be
added with stage 1, off by default) on a healthy stack, so the S22, M31, ASUS, Xiaomi and A55 can run it.
1. Off-preservation: sub-switch off, the same launches produce the same viewer-request scan filter, admission lines and
   stream bytes as the frozen baseline build, on S24 to M31 and S24 to ASUS (L2CAP) and on a GATT source.
2. Receiver fallback: injection or real leak on the receiver; within the normal join time plus one GATT setup the receiver
   restarts on GATT with `requested=BLE_L2CAP effective=BLE_GATT reason=availability_failed` logged once; with the
   sub-switch off the failure is unchanged.
3. Mixed fan-out: an L2CAP source with one healthy L2CAP child and one fallen-back GATT child: both receive; the healthy
   child's throughput, frame age and loss stay within the noise of the same source without the GATT child (same settings,
   same APK, three repeats); admission lines show two pools and the aggregate charge; the GATT child gets a layer it can
   carry, or a visible refusal.
4. Refusals: GATT slots full; the GATT child cannot carry the lowest layer; a second GATT request. Each is visible and
   nothing is substituted.
5. Topologies: the fallen-back phone as a direct child, as a relay's child, and as a relay (its own children over L2CAP
   from a GATT parent), checking the authenticated original source and requested parent; a requested chain that falls
   back to direct is recorded as failed.
6. Recovery: Bluetooth off/on on the receiver returns it to L2CAP on the next join (the ledger and forced reason reset).
7. Concurrency: the call matrix pairs (both directions) with a call and a stream at once on the fallen-back phone, since
   GATT servers and clients share the app-wide GATT slot pool.

**Open points for the review.** The initial GATT pool size and the aggregate charge rule are conservative guesses until a
mixed run is measured; whether thinning is available for a direct child of a source (it exists for relays) decides if
stage 2 ships with the lowest-layer rule only; a viewer that is already a relay child of a GATT parent is stage 2 work.
