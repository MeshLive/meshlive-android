# Design QV: a Windows PC watches Android live streams and audio-only radio (draft 2026-10-20, revision 2 after review 1, no wire code)

**Status.** Draft for review (DECISIONS). Nothing here is built. Revision 2 answers review 1 (R1 to R9); each finding is marked **[R#]** where the text changed, and the table at the end lists them.

- The Windows libraries it reuses exist and are unit-tested (listed under "Windows side").
- Every new on-air form is behind default-off switches until the review and gates V1 to V3 pass.
- Scope: viewer only. The PC signs `maxChildren = 0`, never forwards, never goes live and never relays in this design.
- **Revision 3 (2026-10-21, after the user chose Q-WATCH option A) replaces option C as the plan and adds Go live from Windows; see "Revision 3" at the end of this design.**

Android citations are `file:line` under `app/src/main/java/com/example/bluetoothtest/`, from the Android working tree of 2026-10-20 (accurate to about ±3 lines). "MA" means `MainActivity.kt`.

## Problem

A viewer joins a stream by **advertising a viewer request**. The parent (source or forwarder) scans for requests, picks one and **dials the viewer** (PROTOCOL "Experimental rotating discovery IDs", Phase 1b items 2 to 5; CF-RELAY-04; CF-LINK-37).

- On GATT the viewer is the GATT server: it hosts `c8d5a105` (write `c8d5a103`, notify `c8d5a104`).
- The parent connects as central and initiates the BMH2 link handshake. Stream hops use **BMH2 only**; BMH3 is used for calls only.
- Android code: `AndroidVideoReceiver.kt:507-545` (`receiveGattLoop`), `GattVideoTransport.kt:1303-1351,1534-1600` (`GattVideoServer`), `BluetoothVideoSender.kt:262-285,327-332`.

So a PC can watch only if an Android parent can **hear the PC's request and connect to it**.

Windows cannot send Android's request form. That form is a legacy connectable advert with `c8d5a101` in the primary PDU and the 22-byte `0xFFFF` manufacturer announcement in the scan response (`GattVideoTransport.kt:1335-1351`, `StableAdvertisingSet.kt:495-512`). The Design QW measurements found three blockers:

- The publisher refuses 128-bit service UUIDs.
- A manufacturer-data advert can only be non-connectable.
- A `GattServiceProvider` advert carries only its own service UUID, plus service data on extended advertising.

The PC's only proven on-air form is the Design QW call offer: service data under `c8d5a10a`, 9 bytes, kind 0 only. The QW review (R1) ruled that viewer requests "need their own design".

Scanning is not the problem. The Windows scanner already reads Android's stream offers: versions 2 and 5, compact v2 and the audio-only marker (`src/discovery.rs`, `src/bluetooth/scan.rs`).

## What Windows must scan (no wire change)

1. **Stream offers.** Versions 2 and 5 and compact v2 (version 5 uses the role-1 RDI; its route token is the logical token XOR mask(RDI)). Each offer carries a codec summary. The audio-only marker is `0x40` with zero geometry (CF-MEDIA-80).
   - Group rows by `LogicalStreamTarget(logical token, summary)`. Pick the best route: joinable first, then fewest hops.
   - **[R5]** A route is joinable only if its offer is a GATT offer (flag `0x08` set, `0x10` clear): the PC's request is always a GATT request, and a source selects only requests of its own transport (`StreamGattFallback.acceptsViewerRequest`). LE CoC and Classic routes are listed as "not reachable from this PC".
   - Drop offers older than 12 s. Dial only routes heard within 10 s, and discard a route after a failed attempt (CF-DISC-41, CF-RELAY-05).
   - Show codecs the PC cannot decode as dimmed and not joinable. The decode mask comes from Media Foundation (CF-HS-02).
   - Android: `MA:6935-6996` (`refreshNearbyStreams`), `MA:7008-7040` (staleness and suppression), `StreamDiscovery.kt:23,74-127`, `MA:7126-7260` (rows and title precedence).
2. **Title pages, version 3** (optional in the first build). Reassemble by (token, digest, count) within 30 s. At most 3 pages and 256 tokens; a contested title is blanked for 60 s (CF-DISC-43, CF-DISC-45).
   - Android: `TitlePage.kt:27-157`, decoded before the per-address dedup (`BleL2capControl.kt:597-609`).
   - The stream-title text rule (48 bytes) already exists in `src/names.rs`.
3. **Full and paused bits are hints only.** The full bit (`SEQUENCE_SOURCE_FULL`) and the paused bit are untrusted hints (CF-RELAY-03, CF-LIFE-03). A full source is not selected (`MA:10252-10262`), except to reconnect to the same source after a loss, as Android does **[R9]**.

## What Windows must advertise

### Recommended: option C, the compact viewer-request UUID as the provider's service UUID (no Android change)

Android already decodes iPhone compact viewer requests (kind 1) in the source's request scanner, and compact discovery is **on by default**:

- default: `ConnectivityFeatureDefaults.IPHONE_DISCOVERY = true` (`ConnectivityFeatures.kt:25`);
- the masked `C8D6…` service-UUID filter: `CompactDiscoveryAnnouncement.kt:93-94,150-160` and `BleL2capControl.kt:528-545`;
- the request scan and accept predicate: `MA:6667-6770`, accept at `:6730-6738`.

Two Design QW measurements suggest Windows can produce the iPhone form:

- Gate 2: **Windows emits a legacy UUID-list advert alongside the extended payload advert, from a different random address.**
- Gate 1: the legacy advert carries the provider's service UUID in the 128-bit UUID list.

So a `GattServiceProvider` whose service UUID **is** the compact viewer-request UUID should put the compact viewer-request UUID on air in a form Android's compact filter matches. Default Android sources would then find it and connect to the PC's `c8d5a105` data service, with no Android change at all. **[R4]** This is not radio-level indistinguishable from an iPhone: Windows adds its own UUIDs (QW gate 1 saw `0x180A`) and sends a legacy and an extended advert at once from two addresses. The PC logs its form as "compact viewer", and nothing in this design depends on looking like an iPhone.

**The UUID.** PROTOCOL "Experimental iPhone compact discovery", version 2 (rotating), kind 1, with these fields:

- prefix: the PC's role-2 RDI for the current epoch;
- bit 59 (GATT): 1;
- codec bits: 0 (a version 5 request carries summary 0);
- route cost and route-token bits 30..0: copied from the newest selected offer;
- bits 18..12: the bitrate in 25 kbps units;
- bits 11..0: zero.

**What already exists.** The encoder is in `advertise::compact_uuid` and `discovery::compact`. The provider form `Form::CompactAlias` (`src/bluetooth/peripheral.rs`) is Experimental, was built for call offers, and has never been shown to reach a phone.

**Rotation [R4].** Break-before-make, as Android item 6: at an epoch boundary while waiting, the request provider stops, stays silent for a uniform 1-8 s, and starts as a new provider with the new UUID (new address). The role-2 ID is pinned when a connection is accepted and stays openable for 60 s after it left the air (Phase 1b item 7). It stops when a parent is admitted. The data service stays up. Within one epoch the RDI is the same on every address Windows uses (including its own 15-minute address change), so those addresses are linkable to each other for that epoch, as on Android.

**Limits.** The same as an iPhone viewer's:

- No recovery hint (the compact form has no field for it). A PC that loses a forwarder parent sends an ordinary request.
- Only version 5 offers are joinable. **[R7]** This is a privacy policy, not a technical limit: compact version 1 can carry the PC's fixed prefix for a version 2 offer, but that would broadcast a fixed identifier, so the PC does not do it.
- A phone with "Find iPhones" turned off cannot see the PC.

**What must be proven (gate V1):**

- the legacy UUID-list advert with a compact UUID is **connectable**;
- a connection made through it reaches the `c8d5a105` provider;
- its address changes with every restart;
- Android sources accept the request.

- the exact UUID bytes appear on air (raw observer, not only an advertising status).

Gate 1 saw the UUID list only for the `c8d5a10a` UUID. QW item 2 showed status 4 with a compact UUID, but no phone was observing at the time.

### Fallback: option A, PC announcement kind 1 (needs an Android change)

If V1 fails, use the same service-data form as Design QW: UUID `c8d5a10a`, connectable extended advert, secondary PHY LE 1M. Byte 0 keeps the version and kind nibbles. Today's strict Android decoder checks the length first, so a 14- or 20-byte payload is rejected as `Length`, before its kind is read **[R9]** (`PcAnnouncement.kt:98-104`); either way an old phone drops it safely.

```text
byte 0       version (7..4) = 1, kind (3..0) = 1 viewer request
bytes 1..6   the viewer's role-2 RDI for the current epoch
byte 7       flags: bit 0 GATT (required), bit 3 route recovery (only with a hint); other bits zero
byte 8       bits 0..2 zero (summary 0), bits 3..6 route cost copied from the offer, bit 7 zero
bytes 9..12  route token copied from the newest selected offer (big endian, bit 31 set)
byte 13      bitrate in 25 kbps units (display only)
bytes 14..19 recovery hint, present exactly when bit 3 is set (length 20), else absent (length 14)
```

**Android receiving side.** Decoding plus a mapping in `ScanRecordForms` (`PcAnnouncement.kt:200-215`): kind 1 becomes an ordinary version 5 viewer-request `DiscoveryAnnouncement`. This sits behind "Find PCs" (`test_find_pcs`, default off) plus a new Experimental switch.

**Cost:** only sources with "Find PCs" on can see the PC, and only through extended scanning (Design QW amendment 1).

**Gain:** a recovery hint.

**Size:** 35 or 41 bytes on air. Extended advertising has room, but nothing above 9 bytes has ever been observed (gate V1A).

### Why the compact form's fields are enough

Every admission rule is unchanged and keys on fields both forms carry:

- the transport filter (`StreamGattFallback.acceptsViewerRequest`);
- the route-token window: the current token plus two retired tokens, each for 60 s (`RotatingStreams.kt:19-72`; `MA:613,6622,6733`);
- admission keyed by the announced bytes plus advertising address (`r:<6 bytes>@<address>`, `BluetoothVideoBroadcaster.kt:736-749`), then by node ID; a second link from the same node replaces the first (`:539-560,713`);
- the capacity cooldown of 30 s (`SourceCapacityRejectionPolicy.kt`; CF-RELAY-21) and the GATT pool backoff (`BluetoothVideoBroadcaster.kt:162-251`);
- request staleness of 12 s (`MA:6830,13798`);
- the role-2 BDO1 opening check and pins, within the two-epoch window.

### Rejected alternatives

- **The PC dials the parent.** No Android parent accepts an inbound viewer connection; the parent always dials (CF-RELAY-04). Changing that would be a protocol change for every phone.
- **A discoverable advert.** It broadcasts the PC's friendly name (QW option B).
- **A phone tethering media to the PC.** USB and Wi-Fi are development transports only.

## After the connection (no wire change; the PC is the listener and responder)

1. **Accept.** The PC accepts one central within 15 s (`CONNECTION_WAIT_TIMEOUT_MS`, `AndroidVideoReceiver.kt:1862`).
   - Source side: connect timeout 12 s, 2 GATT open attempts, MTU 517 requested.
   - Chunks are `type 1 | seq u32 | payload`, with payload = min(MTU−3, 512) − 5.
   - Media goes out as writes without response to `c8d5a103`. The PC sends its uplink as notifications on `c8d5a104` (`GattVideoTransport.kt:550-552,1514-1521,1747-1752`).
   - The PC already serves this direction for incoming calls (the `Peripheral` carrier used by `incoming::respond` and `verify_scan`).
2. **Link handshake (BMH2 responder).**
   - Link hello bit 3 is set on LE stream hops when rotating IDs are on and `maxChildren <= 1` (`RotatingStreams.kt:124-134`).
   - With both hellos carrying it, the first hop record each way is `BDO1`: the parent sends its role-1 entries, the PC its pinned role-2 entries (`RotatingDiscoveryRuntime.kt:392-413`).
   - The PC checks the parent's role-1 entry against the six bytes of the offer it selected (RV15, two-epoch window; `AndroidVideoReceiver.kt:755-806`).
3. **Endpoint handshake.**
   - The PC sends its real decode mask, `maxChildren = 0`, and bit 5 when labels are on.
   - The parent checks the stream's codec against the mask (audio-only is exempt).
   - For version 5, the endpoint peer must equal the link peer (`BluetoothVideoSender.kt:380-440`, `AndroidVideoReceiver.kt:785-799`).
4. **Forwarded stream.** The first endpoint record is the 68-byte `BFG1` grant (`GROUP_KEY`). The PC checks:
   - the grant's stream ID against the `BMV1` stream ID;
   - the pinned source on a replacement route;
   - the verified-contact gate on the source node ID.

   Media then opens with the granted key (`ReceiverStreamForwarder.kt:592-600`, `AndroidVideoReceiver.kt:806-842`; CF-RELAY-07, CF-REC-34). Windows already has this in `src/mesh.rs` and `watch::Reader::forwarded`.
5. **Labels [R6].** Only when both endpoint hellos carry bit 5 and the link peer is the endpoint peer: then exactly one `LABELS` record arrives at a fixed position, before the header from a direct source, right after `GROUP_KEY` from a forwarder, and it may hold zero labels. Without bit 5 on both sides the PC expects no `LABELS` record, and any record at that position is read as the next one.
   - It is a kind 2 title bound to the stream ID (or the zero ID), verified after the header (`BluetoothVideoSender.kt:471-481`, `AndroidVideoReceiver.kt:817-838`).
   - The receive code exists (`src/labels.rs`, 45c2f6c). The PC sends no labels on a stream hop.
6. **Header.** `BTV3` or `BTL1`, dispatched by magic (`StreamProtocol.kt:198-233`). The offer's marker and the header kind must agree, or the link fails closed (`AudioLiveProtocol.kt:127`).
   - **[R1]** A mismatch penalises only the attempted route (advertising address, announced prefix, route token) for 2 min. The PC never penalises the node ID: the advert is unauthenticated, so a node-wide penalty would let anyone who copies a source's RDI and token with the other marker get the honest source banned. Android's code also penalises only the route (it passes no node, `MainActivity.kt` about 11886). PROTOCOL's node rule disagrees with the code; reconciling it belongs to PROTOCOL's owner.
   - `BTL1` checks: the signer block (the identity's node ID equals the authenticated source, then the certificate), per-record signatures, `seq`, the Opus 20 ms unit rule, ingress budgets and clock rules (`AudioLiveProtocol.kt:20-190,238-290,483-552`; CF-MEDIA-80/82, CF-REC-07). Windows: `src/audio_live.rs`.
7. **Media.**
   - Direct `BMS1` type 5 or mesh `BMV1`, never both in one session (CF-REC-13; `watch.rs`).
   - Video: Media Foundation decode with keyframe gating and discontinuities (`viewer.rs`; CF-MEDIA-53/54).
   - Audio: on the shared playout clock (`listen.rs`, `playout.rs`), with the call path's catch-up speed (`time_stretch`, CF-MEDIA-68).
   - Output: `playback.rs`, including the reopen after a write timeout (31201d7).
8. **Uplink** (`session::ViewerUplink`). The PC sends:
   - **[R2]** a link report every 2 s: version 2 (6 bytes: arrival-train kbps, flags with "link late", added delay ms) from the ArrivalMeter already used by calls (`call_rate.rs`) and `link.rs`. Android's source pacer needs these: without capacity reports its rate decays after about 4 s (`AndroidVideoReceiver.kt` about 1190, `LinkSelfMeasurement.kt` about 538). Version 1 only before the first report is measurable; version 3 only under the experimental stream delivery reports, which need every phone in the stream to accept it;
   - keyframe requests as an empty BME1 type 5 (never for audio-only);
   - `STREAM_END_ACK` after `ENDED`;
   - `ROUTE_CONTROL_ACK` within 4 s of a withdrawal.

   A parent never sends heartbeats. A silent GATT link closes after 6 s (`AndroidVideoReceiver.kt:1133-1190,1887-1908`; CF-LIFE-01, CF-REC-30).
9. **Lifecycle.**
   - `PAUSED` (repeated every 2 s) keeps the link.
   - `ENDED` returns the PC to the list and that broadcast is never retried.
   - An unexpected loss keeps the last frame and searches for the same logical stream: break-before-make, same authenticated source.
   - A capacity withdrawal suppresses the route for 30 s (CF-LIFE-03/05/06, CF-RELAY-12).
   - Media-state records 4 to 6 are shown.

## Windows side

Already built (unit-tested; the server side is used by incoming calls; never on air for streams):

- discovery and advertising: `discovery.rs`, `bluetooth/scan.rs`, `bluetooth/peripheral.rs` (provider, one subscriber, admission slot, `Form::CompactAlias`), `advertise.rs` (compact encoders);
- handshakes: `rotating.rs` (role-2 RDI, BDO1, pins), the `bmh2.rs` responder;
- the viewer pipeline: `watch.rs`, `viewer.rs`, `listen.rs`, `playout.rs`, `session.rs` (viewer uplink), `link.rs`, `mesh.rs`, `audio_live.rs`, `labels.rs`, `time_stretch.rs`, `playback.rs`, the video surface.

Missing, in build order:

1. **The viewer-request provider** (option C, compact kind-1 UUID): start, rotate while waiting, stop on admission. Behind `MESHLIVE_WATCH=1` (Experimental, default off) and the existing `qw_policy` allowlist.
2. **The join state machine.** Select an offer, request, accept one central within 15 s, then BMH2 responder with role-2 BDO1, endpoint responder, reader pipeline, suppression windows and the recovery loop.
3. **A Watch tab.** Rows, freshness, dimmed unsupported codecs, title board.
4. **Stream output.** Audio and video from the reader to `playback` and the surface.
5. **A headless `watch_phone` tool** for the phone gates, like `answer_phone`.

Option A would add a kind-1 codec with shared vectors (Rust, Kotlin, Swift) and the Android `PcAnnouncement`/`ScanRecordForms` change behind its own switch.

## Gaps and open questions

1. **Option C on air (blocking).** Is the legacy UUID-list advert connectable, and does a connection through it reach `c8d5a105`? If not, use option A and its Android change.
2. **Two announcements at once (answered by review 1: pause first).** While the PC waits for a parent, its call-offer announcement is paused (the PC is not callable for at most the 15 s wait). Keeping both on air (two providers, up to four adverts) is a later change after gate V2 measures it.
3. **The class marker (answered by review 1: acceptable).** Reusing the compact form is acceptable; the PC logs it as "compact viewer" and claims no radio-level indistinguishability **[R4]**.
4. **No recovery hint under option C (answered: acceptable initially).** After losing a forwarder, the PC queues behind ordinary requests and may wait for a capacity slot.
5. **Version 2 offers are not joinable (answered: acceptable privacy policy) [R7].** A source with rotating IDs off, or inside a Visible-by-handle window, cannot be watched from a PC: a request for it would have to carry the PC's fixed prefix.
6. **Forwarding from the PC is out of scope.** A PC stream offer needs about 22 bytes (token, sequence, geometry) and its own design.
7. **Audio-only defaults [R9].** Audio-only is on by default in the Android code (`AudioLiveConfig.kt:8-10`, `ENABLED = true`), and PROTOCOL already says so (revision 1 misread a stale heading). The PC's `BTL1` reader has no switch and fails closed.
8. **Media-state records 4 to 6.** Older readers drop the link on them; the PC reads them.
9. **Address residual, as on Android [R4].** A PC that waits across a boundary keeps its GATT service. Windows' own 15-minute address timer can split one epoch across two addresses; the shared RDI links those two addresses for that epoch (it links nothing across epochs).
10. **Source-side flood bounds [R3].** Admission resistance is inherited from Android, not proven: fresh RDI/address pairs bypass per-request cooldowns and use pre-authentication slots, and the rejection maps have no size bound or expiry sweep (`BluetoothVideoBroadcaster.kt`, `SourceCapacityRejectionPolicy.kt`). Gate V8 measures the bound; an Android-side cap is its own fix item, not part of this design.

## Test plan

Gates run in order; each is a hard stop. No phone runs happen without the coordinator's lane assignment.

- **V1, option C on air (Windows plus two Android raw observers, no app).**
  - Tool: `qv_gate --approved --minutes 20 --restart-at 600` (examples/qv_gate.rs, built 466c948). It publishes `c8d5a105` and a compact kind-1 request provider (1 byte of service data, LE 1M secondary), logs the exact UUID and its on-air LSB-first bytes, both providers' status events, and every subscription and write on each service with the peer tag, then restarts the request once with a new RDI and token.
  - Record the AD bytes of both adverts, the PDU type, the connectable bit, the address type and its rotation, and the status events.
  - Then make a plain GATT connect from a phone (nRF Connect) to the legacy advert's address and check that `c8d5a105` is listed.
  - Then subscribe to `c8d5a104` and write one byte to `c8d5a103` from nRF Connect: the tool must log `data_c8d5a105 event=subscribed` and `event=write` for that peer.
  - Pass (all required; an advertising status alone never passes): the observers show the exact UUID bytes logged by the tool; the PDU is connectable; the phone's connection lists `c8d5a105` and its subscribe and write reach the data service; the address changes at the restart; observers are unbonded to the PC. Per adapter: the result holds only for the adapter it ran on (`qw_policy` allowlist).
- **V1A, only if V1 fails.** Option A sizes (14 and 20 bytes) on the extended advert.
- **V2, two providers.** Call offer and request on air at once, both connectable. If this fails, the request pauses the call offer.
- **V3, Android source acceptance** (one phone, default settings, logs only). The source's request scan decodes the PC's compact request and dials it. Evidence:
  - the `compact` counters;
  - a `rotid_open: role=viewer` result;
  - a BMH2 link with the headless `watch_phone` responder.
- **V4, Windows unit tests (before V3) [R8].** The join state machine with fakes:
  - the 15 s accept window and one central;
  - role-2 BDO1;
  - the BFG1 checks;
  - the marker/header mismatch penalty;
  - `ENDED` versus an unexpected loss;
  - the suppression windows;
  - negative cases: a BDO1 that does not open the selected ID, a stale pin, a token outside the grace window, a BFG1 with the wrong stream ID or source, a central that connects and stays silent, a second central during a join, recovery when the source is full, and switch-off preservation (with `MESHLIVE_WATCH` off nothing new is advertised or scanned);
  - the v2 report cadence and contents (R2).
- **V5, phones, direct stream [R8].**
  - Every eligible required phone in turn as the source: S22, S24, M31, ASUS, Xiaomi, A55. VP9 and AV1 where offered, at the source's own settings, 5 min each. TESTING's startup gate applies (first picture within 3 s of the link), not the 15 s accept window.
  - The PC joins within the window and plays. It survives pause and resume, stops on `ENDED`, its keyframe requests are answered, and its heartbeats hold the link.
  - Audio-only radio at the Voice and Music presets on two phones.
  - A labelled, planned kill of the source: the PC searches, and does not retry after `ENDED`.
- **V6, phones, forwarded stream.** Source, then a forwarding phone, then the PC. Check:
  - the BFG1 grant;
  - the title label is byte-identical;
  - a replacement route keeps the original source;
  - after a forwarder loss, the PC's ordinary request is admitted.
- **V7, privacy.** An observer timeline over 30 min and two boundaries:
  - the request's address changes with its RDI;
  - no name goes on air;
  - nothing fixed is on air beyond what an iPhone request also shows.
- **V8, negative cases.**
  - A copied request from another address fails at the opening and does not starve the PC.
  - **[R3]** A flood: 50 compact requests per minute from fresh RDI/address pairs for 5 min against one source; record the source's pre-authentication slot use, its rejection-map size and whether the real PC viewer is still admitted within 60 s.
  - A version 2 offer row says why it cannot be joined.

Measurements stay separate (AGENTS): join time, frame-timing proxies, audio continuity and lifecycle are each their own result. Decoder timestamps are proxies, not screen latency.

## Review 1 findings and where they are addressed

| # | Finding | Addressed in revision 2 |
|---|---|---|
| R1 | Node-wide mismatch penalty lets an attacker ban an honest source | Header item 6: route-only penalty, as Android's code |
| R2 | Viewer must send v2 capacity reports | Uplink item 8: v2 reports every 2 s from the ArrivalMeter |
| R3 | Flood resistance inherited, not proven | Open question 10; V8 flood measurement |
| R4 | "Exactly like an iPhone" / "links nothing" overclaims; rotation details | Option C text, Rotation, open questions 3 and 9 |
| R5 | Transport in the join filter | Scan item 1 |
| R6 | LABELS only when negotiated; may be empty | After-connection item 5 |
| R7 | v5-only is a privacy policy | Limits, open question 5 |
| R8 | Gate order and coverage | V4 before V3 with negative cases; V5 every eligible phone and the 3 s startup gate |
| R9 | Length before Kind; full-source reconnection; audio-only already documented | Option A text, scan item 3, open question 7 |

## Review 1 (2026-10-20): option C conditionally; two blockers

| # | Severity | Finding | Disposition |
|---|---|---|---|
| R1 | blocker | A node-wide 10-minute penalty on an offer/header mismatch lets an attacker who copies a source's RDI/token with a different audio marker get the honest source banned (the advert is not authenticated). Android passes `null` for the node and penalises only the route (`MainActivity.kt` about 11886); PROTOCOL states the node rule. | Revise: route-only penalty, as Android does; reconcile PROTOCOL with the code (owner: PROTOCOL). |
| R2 | blocker | The viewer uplink sends only v1 heartbeats; Android's pacer needs v2 capacity reports or its rate decays after about 4 s (`AndroidVideoReceiver.kt` about 1190, `LinkSelfMeasurement.kt` about 538). | Revise: send normal v2 reports (v3 stays separately gated). |
| R3 | should-fix | Flood resistance is inherited, not proven: fresh RDI/address pairs bypass per-request cooldowns and use pre-auth slots; rejection maps have no size bound or expiry sweep (`BluetoothVideoBroadcaster.kt`, `SourceCapacityRejectionPolicy.kt`). | Add quantified V8 bounds; the Android-side bound is its own fix item. |
| R4 | should-fix | "Exactly like an iPhone" / "links nothing" overclaim: QW shows extra UUIDs and simultaneous legacy/extended adverts; an RDI links addresses within its epoch. | Reword; specify break-before-make rotation with the 1-8 s silence, accept-time pins, 60 s grace; observe with unbonded observers. |
| R5 | should-fix | Join eligibility must include transport (GATT request vs CoC offer). | Add to the join filter. |
| R6 | should-fix | LABELS is conditional on both hellos carrying bit 5 and may carry zero labels. | Read it only when negotiated. |
| R7 | should-fix | Compact v1 supports fixed-prefix v2 offers; v5-only is a privacy policy, not a technical limit. | Correct the reason; keep v5-only. |
| R8 | should-fix | Gates: join-state unit tests before V3; BDO1/pin/token/BFG1 negative cases, silent centrals, duplicate connections, full-capacity recovery, switch-off preservation; TESTING's 3 s startup gate, not 15 s; every eligible required phone. | Add to the test plan. |
| R9 | nit | Option A's 14/20-byte payload fails Length-before-Kind; the full-source check exempts reconnection; PROTOCOL already says audio-only is on. | Correct. |

Open questions: (1) C conditionally, after V1 proves exact bytes, connection and data-service access per adapter; (2) pause the call offer while joining at first; (3) reusing the iPhone form is acceptable, but log it as "compact viewer" and claim no radio-level indistinguishability; (4) no recovery hint is acceptable initially; (5) v5-only is an acceptable privacy policy. Status: revise, then a second review before code.

## Review 2 (2026-10-20, second reviewer): approve for gated Windows implementation

R1 yes; R2 partly (cadence); R3 partly (V8 has no pass bound; the Android cap is fix/viewer-admission-bounds, merged); R4-R7 yes; R8 partly (gate order); R9 yes. New: N1 remove the stale "PROTOCOL disagrees" sentence, add the 64-entry penalty bound and the pre-dial check (MA about 458); N2 PROTOCOL said compact discovery is off by default, but it is on since 2026-10-17 (fixed in PROTOCOL the same day); V5 records `compact_filter: event=dropped` and compact counters per source phone; N3 copy Android's report rule (at most 1/s, on new trains, a late flip or a 50 ms added-delay change, plus on-time kbps 0; `AndroidVideoReceiver.kt` 1180-1225) instead of "every 2 s", V5 checks reports (not heartbeats) and that the source's paced rate does not decay over 5 min; N4 gate order V1, V1A, V2, V4, V3; N5 the PC is on air twice (legacy UUID list + extended), so V1/V3 record which advert Android decodes and the design states duplicate dials are harmless (one central accepted; `replaceSameNode`); N6-N7 citation and format nits. Verdict: approve for gated implementation (`MESHLIVE_WATCH` default off + `qw_policy` allowlist) after V1 passes on the radio and the N1-N5 text changes; the option (C vs A) is the user's Q-WATCH.

## Revision 3 and 4 (2026-10-21): Q-WATCH option A, and Go live from Windows (draft, no code)

**Revision 4** applies the independent review of revision 3 (approve with changes). Changes are marked **[I#]**, and the table "Independent review of revision 3" at the end lists them.

**Status.** Design only, for review. The user chose option A: a PC-specific viewer request under the PC announcement, not the iPhone compact request. Phones update in-app, so the PC does not inherit iPhone limits. The user also asked for Go live on Windows (the PC as a stream source). This revision replaces option C above as the plan. Option C stays recorded as the measured alternative; it is not built, and it is not deleted. Wire rules: PROTOCOL "Experimental PC announcement".

**One idea.** The PC announcement carries a PC viewer request (kind 1) or a PC stream offer (kind 2). Each is exactly the 22-byte version 5 announcement an Android phone would send for that role, behind one header byte. An Android phone with "Find PCs" maps it to the same `DiscoveryAnnouncement` it already builds from its own scans. Admission, handshakes, keys and media rules are therefore unchanged. Only the advert's container is new.

### What Windows can and cannot do (sources)

| Limit | Kind | Source |
|---|---|---|
| The publisher refuses Service Data (0x16/0x20/0x21), every service-UUID list (0x02-0x07), Flags and local name; it allows manufacturer data | documented | Microsoft Learn, `BluetoothLEAdvertisementPublisher` remarks (system-reserved types) |
| Publisher adverts went on air only as non-connectable | measured (QW item 4); not documented | DECISIONS Design QW |
| `GattServiceProvider` service data is added "if space is available", from Windows 10 1903 (SDK 18362); a non-connectable provider never starts | documented / measured (QW gate 2) | Microsoft Learn, `GattServiceProviderAdvertisingParameters.ServiceData` |
| Service data survives only on the extended advert; the legacy twin keeps flags, `0x180A` and the provider UUID list, from a second random address | measured (QW gates 1-2, Design CI) | this file |
| No API for LE credit-based (L2CAP CoC) channels: `Windows.Devices.Bluetooth` exposes GATT, RFCOMM and advertising only | documented by absence | Microsoft Learn, `Windows.Devices.Bluetooth` namespace (classes list) |
| No API to read or choose the advertising or initiator address; the PC's outgoing connections used the public address | measured (QW R5) | this file |

**Consequences.**
- Everything a PC sends is GATT. A PC offer has `FLAG_GATT_TRANSPORT`, and a PC request is a GATT request. A PC can watch only GATT sources (CoC and Classic rows say "Not reachable from this PC"). An Android viewer of a PC stream requests GATT, because a viewer mirrors the offer's transport.
- A PC is seen only by phones that scan extended adverts (`setLegacy(false)` where `isLeExtendedAdvertisingSupported`). The M31, which has no extended scanning, can neither serve a PC viewer nor watch a PC stream under option A. That phone is ineligible for these cases, and its rows stay explicit.
- **Viewers never make inbound connections to a source.** The parent always dials the viewer (CF-RELAY-04). So for Go live the PC is the GATT **central**, as in its outgoing calls (`central.rs`, `FromBluetoothAddressWithBluetoothAddressTypeAsync`). It needs no inbound slot. As a viewer, the PC accepts exactly one inbound central on `c8d5a105`, as it does for incoming calls.

### (a) PC as viewer

1. **Advert.** A kind 1 payload (23 bytes) on the connectable extended advert of a request provider, secondary PHY LE 1M (`UseLowEnergyUncoded1MPhyAsSecondaryPhy`), `IsDiscoverable=false`. The inner bytes are the version 5 viewer request:
   - the PC's role-2 RDI;
   - `FLAG_ACCEPTING_CONNECTIONS | FLAG_GATT_TRANSPORT`, plus `FLAG_ROUTE_RECOVERY` only with a hint;
   - summary 0;
   - the route token and cost copied from the newest selected offer;
   - the recovery hint or six zero bytes;
   - the bitrate.

   Rotation is as in revision 2: break-before-make at an epoch boundary, with a uniform 1-8 s of silence. The ID is pinned at accept time and openable for 60 s after it leaves the air. The advert stops once a parent is admitted. The data service `c8d5a105` stays up.
2. **How an Android source finds and dials it.** This applies only with "Find PCs" on (`test_find_pcs`, default off) plus a new Experimental switch `test_pc_streams` (default off, both required).
   - The source's viewer-request scanner carries the PC service-data filter (`PcAnnouncement.UUID`, empty data) and extended scan settings, with the same guard and drop order (`PcFilterGuard`). If that scanner is not one of the discovery scanners that already add the filter, the filter is added there.
   - `ScanRecordForms` maps kind 1 to an ordinary version 5 viewer request.
   - The source then dials the advertising address of the extended advert with the LE 1M PHY mask, never 2M only (see the M31 status-133 row near the top of this file).
   - Duplicate dials are harmless: the PC accepts one central, and `replaceSameNode` applies.
3. **Admission and limits.** These are the same as for a phone viewer, with no PC special case:
   - the transport filter;
   - the route-token window (current plus two retired tokens, 60 s each);
   - request staleness of 12 s;
   - the admission key `r:<6 bytes>@<address>`, then the node ID;
   - the 30 s capacity cooldown and the GATT pool backoff;
   - the viewer-admission bounds fix;
   - the BDO1 role-2 opening.
4. **Authentication and keys (no new crypto).** As revision 2 "After the connection":
   - BMH2 responder with role-2 BDO1;
   - the endpoint responder with the PC's real decode mask and `maxChildren = 0`;
   - for a forwarded stream, the `BFG1` grant (source ID, stream ID, media-open key) checked against `BMV1`;
   - LABELS only when negotiated;
   - BTV3 or BTL1 by magic, with a route-only mismatch penalty.
5. **Recovery hints.** Now possible, unlike option C. After an unexpected loss of a forwarder, the PC's next request sets `FLAG_ROUTE_RECOVERY` and carries the hint exactly as an Android viewer does (PROTOCOL rotating IDs, Phase 1b item 5). The search stays break-before-make, keeps the same authenticated original source and starts from a fresh keyframe.
6. **Old Android builds [I1].** A build without kind 1 support never selects the request: without "Find PCs" it never sees the advert, and with an older "Find PCs" its strict decoder rejects the payload. **No advert bit marks a source that serves PCs.** Revision 3 proposed stream-offer `capabilitySummary` bit 3, which is dropped, for two reasons:
   - `LogicalStreamTarget.matches` compares the whole summary byte, and `ReceiverStreamForwarder` (about line 207) rebuilds a forwarded offer's summary without that bit. A direct offer and its forwarded copies would then stop matching, which breaks reconnect and route recovery on every current viewer.
   - The bit would also be a stable "Find PCs on" fingerprint.

   Instead the PC uses a timeout. After two unanswered 15 s requests to the same logical stream, the row says "Couldn't join. Update MeshLive on that phone, and turn on Find PCs." The request keeps running in the background while the row stays open. A later admission clears the text.

### (b) PC as source (Go live from Windows)

1. **Offer.** A kind 2 payload (23 bytes) on the connectable extended advert of an offer provider. A non-connectable provider never starts (QW gate 2); a stray central that connects is closed after 15 s without a hop handshake. The inner bytes are the version 5 stream offer:
   - the PC's role-1 RDI;
   - `FLAG_STREAM_AVAILABLE | FLAG_GATT_TRANSPORT`, plus `FLAG_STREAM_PAUSED` while paused;
   - the summary: the codec bit of the stream's encoder, `0x40` for audio only;
   - the route token, as `logical XOR mask(RDI)`;
   - cost 0;
   - the sequence;
   - width, height, FPS and bitrate.

   The PC never sets `FLAG_RELAY_CAPABLE`, because it does not forward. It restarts at every RDI change with 1-8 s break-before-make, and on every offer change (pause, settings), as Android does. **No public title pages in v1.** A title travels only as the signed kind 2 label after the join.
2. **How Android viewers find and join.**
   - With both switches on, the call-list and watch scanners map kind 2 to an ordinary version 5 stream offer. The row reads "Live from <generated name>" like any rotating source.
   - The viewer sends its ordinary version 5 GATT viewer request: legacy advert with `c8d5a101`, 22-byte scan response. Every Windows build since `scan.rs` reads it, using active scanning with scan-response joining.
   - The PC applies Android's source rules to requests (transport, token window, staleness, admission budget, capacity cooldown, and recovery hints to prefer a viewer that lost its parent). It then dials the viewer's `c8d5a105` as central and runs the BMH2 initiator with role-1 BDO1 and the endpoint initiator. It checks the stream codec against the viewer's decode mask (audio only is exempt), sends the BTV3 or BTL1 header and serves media.
   - An Android viewer that forwards the PC's stream issues `BFG1` to its own children with the PC as the original source, unchanged.
3. **Codecs.** Android viewers decode VP9 and AV1, and only those are offered. Video comes from VP9 or AV1 Media Foundation hardware encoder MFTs, with a fallback to the pinned software libvpx 1.17.0 VP9 (`video/encoder.rs`, `video/encode_vpx.rs`, already used by calls). It uses CBR and low latency, with a keyframe on request and at least every 60 s, as Android's encoder rules. AV1 is offered only when a hardware AV1 MFT starts. H.264 is never offered: the Android decoders ignore that bit, so a stream needs VP9 or AV1. Audio is Opus 48 kHz mono in 20 ms units, two units per `BTA1` (`opus.rs` libopus encoder, `live.rs` pairing). The Android settings range applies: 100-1500 kbps, the three resolutions and 15/24/30 FPS. Nothing is silently changed: an unavailable request fails with a reason.
4. **Capture.** `call_camera.rs` handles the camera: one STA thread with Media Foundation, newest NV12 at the stream's size. `call_audio.rs` handles the WASAPI microphone (the communications default device). Both are used unchanged. A camera already in use fails closed with a message.
5. **Fan-out and pacing.** Already built (`live.rs`, `fanout.rs`, `bluetooth/pacer.rs`): encode once, one ordered queue per child, each child paced by its own reports, a slow child withdrawn with LOCAL_CAPACITY, PAUSED repeated every 2 s, and ENDED with up to 5 s for acknowledgements. Admission stays inside the measured budget. The child count starts at 1 and grows only as gate W6 measures it, with no hard-coded maximum.
6. **Radio limits.**
   - While live, the PC advertises the offer as two sets (the split), scans actively for requests, and holds one central link per viewer on one radio.
   - The call offer pauses while live, as Android leaves call readiness while streaming. That leaves one provider, two sets.
   - The scan duty cycle and the number of concurrent central links the adapter sustains are unknown, so gate W6 measures them.
   - **Outgoing links use the public address (QW R5) [I3].** A public address is fixed for the adapter's life and starts with the vendor's OUI. A passive sniffer can therefore link every stream (and outgoing call) of that PC across days, and tell the adapter maker. Viewers learn the source's identity in the handshake anyway; the exposure is to bystanders.
     - **Per-session consent:** Go live on Windows shows, every time before it starts: "While you're live, nearby Bluetooth scanners can recognise this computer from day to day." Choices: "Go live" and "Cancel". No "don't ask again".
     - W7 measures the initiator address type on every dial. If a Windows build is shown to use a resolvable private address for central links, a DeviceFamilyPolicy entry records it and the prompt names the residual less strongly; the prompt never disappears without that evidence.
   - **Recorded alternative [I3]: viewers dial the PC source** (an exception to CF-RELAY-04, not chosen).
     - *How:* the PC's offer advert is connectable already. A viewer connects to it as central, and BMH2 keeps its roles by stream role (the source still initiates the link handshake), not by who connected.
     - *Gain:* the PC never initiates, so its public address never goes on air; only its rotating random advertising address does.
     - *Cost:*
       - a new viewer code path on every phone (central toward a source, a role split between connection and handshake);
       - the PC must accept many inbound centrals on one `GattServiceProvider`, and any stranger can take a slot before admission, because admission now follows the connection;
       - notify throughput from Windows to several subscribers is unmeasured;
       - older phones cannot join this way at all.
     - Revisit if W7 confirms the public address and the consent prompt proves unacceptable.

### iPhone interop [I5]

- **PC as viewer of an iPhone source.** The Windows scanner already decodes compact v2 stream offers, so iPhone rows appear. But an iPhone source cannot see a kind 1 request: iOS reads no PC service data today, and would read it only in the foreground once added (QW R10). So an iPhone row is dimmed with "This iPhone can't serve a computer yet". The PC does not fall back to the compact request, per the user's Q-WATCH choice.
- **PC as source for an iPhone viewer.** An iPhone cannot see a kind 2 offer, for the same reason, so it never sends a request. The PC's request scanner still decodes compact v2 viewer requests (kind 1 compact, GATT) and applies the same source rules, so an iOS build that later learns kind 2 works without a PC change.
- Both directions need an iOS change behind its own switch, and a gate (W9).

### Gates (in order; each is a hard stop; no phone run without a lane assignment)

- **W1, sizes on air.** Windows plus two unbonded raw observers. For kinds 0, 1 and 2, with 9, 23 and 23 bytes:
  - the exact AD bytes, PDU type, connectable bit, address type and its rotation at a restart;
  - Started, not `StartedWithoutAllAdvertisementData`.

  If 23 bytes do not fit, the fallback is revision 2's 14/20-byte kind 1 layout and a reduced kind 2 (`kind | RDI | flags | summary | token | sequence`, 13 bytes, with geometry left to the header). That fallback needs its own review.
- **W2, connect through the extended advert.** An extended-capable phone connects to the extended advert's address, lists `c8d5a105`, subscribes and writes, and the PC logs both. This is done first with nRF Connect, then with the app's own `GattVideoClient` dial path (the connect timeout, MTU request, LE 1M PHY mask and GATT open attempts a source would use) **[I6]**.
- **W3, JVM and Rust vectors.** A shared vector file covers:
  - kind 0 unchanged, kinds 1 and 2 valid, and every rejection (Length, Version, Kind, Fields) in the new check order;
  - **[I6]** kind/role confusion: request flags in a kind 2 payload, offer flags in a kind 1 payload, a call offer inside either, a version 2 or 8 inner announcement, a non-zero kind 1 summary, kind 2 summary bits outside the codec bits and `0x40`, kind 2 with cost above 0 or the relay flag;
  - with `test_pc_streams` off, kinds 1 and 2 dropped and counted, never mapped;
  - the switch-off scan byte-identical;
  - **[I6]** a mapped kind 2 offer and a forwarded copy of the same stream still matching under `LogicalStreamTarget.matches` (direct versus forwarder reconnect).
- **W4, Windows unit tests.** Prerequisite **[I7]:** a persisted owner for the PC's epoch clock (`rotating::EpochClock`), built and unit-tested first, because every BDO1 window depends on it. Then the revision 2 V4 list for the viewer. For the source: request-acceptance parity with Android, using the same vectors as the `MainActivity` predicate, plus the dial and handshake order, the stray-central close and the pause/end offer changes.
- **W5, phones, PC watches.** Each eligible extended-capable required phone as the source (S22, S24, ASUS, Xiaomi, A55); M31 is recorded as ineligible. The phone uses its own settings, 5 min, video and audio only. TESTING's 3 s startup gate applies. Also a forwarded case and a recovery after a planned, labelled forwarder kill (the hint is used).
- **W6, phones, PC goes live.** Each eligible phone watches. Then two, then three viewers at once, recording the PC's paced rate per child, scan duty and link count. It covers pause/resume, ENDED, keyframe requests and capacity reports (no rate decay over 5 min), and an Android viewer forwarding the PC's stream.
- **W7, privacy.** An observer timeline over 30 min and two boundaries:
  - the extended advert's address changes at every provider restart, and with the RDI **[I4]**;
  - no name goes on air;
  - **[I3]** the address type of the initiator on every PC dial (public or random), from the phone's logs and a raw observer;
  - **[I4]** after a connect, whether GATT Device Name (`0x2A00`, the Windows computer name) and the Device Information service (`0x180A`) are readable by the connected phone. If the computer name is readable, that is a privacy defect to fix (block or neutralise it) before the gate passes. The residuals are recorded.
- **W8, negative cases.**
  - A flood of kind 1 requests: 50 per minute from fresh RDI/address pairs, against a phone source.
  - **[I6]** A real kind 1 request replayed from an attacker's address. It must not use up the real viewer's admission budget, capacity cooldown or GATT pool backoff: these are keyed by announced bytes plus address, and the replay fails at BDO1 opening.
  - **[I6]** A kind 2 flood against the viewers' row tables: bounded rows, no eviction of real rows that are still heard, no crash.
  - A copied kind 2 offer from another address.
  - A malformed kind.
  - An old build: the PC shows the update text and nothing crashes.

- **W9, iPhone (after an iOS change) [I5].** An iPhone watches the PC (kind 2 parse, foreground), and the PC watches an iPhone source (kind 1 parse on iOS). Until then the PC rows show the iPhone text above.

### Review 1 (self, skeptical, 2026-10-21)

| # | Severity | Risk | Disposition |
|---|---|---|---|
| S1 | blocker | 23 bytes on air is unproven. Windows adds service data "if space is available", and only 9 bytes have been observed. | W1 first; fallback layouts named, with their own review |
| S2 | blocker | A phone connecting through the extended advert's address to `c8d5a105` is unproven; only the legacy twin has carried a call (M31). | W2 before any app code |
| S3 | should-fix | Option A excludes phones without extended scanning (M31) in both directions; option C would have reached them through the legacy twin. | User's choice; the rows stay explicit. A later, separately decided legacy fallback (C as a second advert) is possible, off by default |
| S4 | blocker (raised by I3) | The PC's outgoing dials use the public address: linkable across days by any sniffer, plus the vendor OUI. | Per-session consent before Go live; W7 measures the initiator address type; the "viewers dial the PC source" alternative is recorded |
| S5 | resolved (I1) | Stream-offer summary bit 3 would break `LogicalStreamTarget` matching between direct and forwarded offers, and would fingerprint "Find PCs on". | Dropped; the timeout text replaces it |
| S6 | should-fix | Radio load while live: two adverts, active scanning and N central links on one adapter, untested. Windows' central throughput (write without response, PDU size) at more than one child is unknown. | W6 measures; admission stays inside the measured budget, with no fixed maximum |
| S7 | should-fix | Flood surface: extended scanning plus a filter that matches any payload under `c8d5a10a` lets anyone send cheap kind 1 requests to a source. | Same admission and bounds as phone requests; W8 quantifies; both switches default off |
| S8 | prerequisite (I7) | Epoch alignment: the PC's epoch clock (`rotating::EpochClock`) still has no persisted owner, and a wrong wall clock breaks BDO1 windows. | The owner is built and tested before W4; W4 covers clock skew within the two-epoch window |
| S9 | note | Hardware VP9/AV1 MFTs are missing on many PCs; software VP9 at 720p30 may cost too much CPU. | Offer only what starts; the settings table follows the encoder actually opened; record CPU in W6 |
| S10 | note | MSIX packaging may change Bluetooth behaviour (the `bluetooth` capability, provider start). | Re-run W1/W2 under the packaged identity before the Store release |
| S11 | note | No public title pages for PC streams in v1. | The signed title after the join; title pages later |
| S12 | should-fix (I7) | Source phones with Find PCs on scan extended adverts (`setLegacy(false)`) for the whole broadcast: radio duty and battery cost per phone are unmeasured, and some controllers do not offload extended filters. | W5 records battery drain and scan duty per source phone against the switch off; filter-offload status logged |
| S13 | should-fix (I7) | On the PC as viewer, up to three providers coexist (data service, call offer, request), each split into legacy and extended sets, on one adapter. | The call offer pauses while joining (revision 2 open question 2); W1 runs with all providers up and records set count and start status |

### Independent review of revision 3 (2026-10-21): approve with changes

| # | Finding | Revision 4 |
|---|---|---|
| I1 | The serves-PC summary bit breaks direct-versus-forwarded matching (`LogicalStreamTarget.matches` compares the whole summary; `ReceiverStreamForwarder` rebuilds it without 0x08) and fingerprints Find PCs | Dropped; timeout text (a) item 6 |
| I2 | Strict decoder: exact flags per kind, summaries, cost and relay rules, check order version → kind → length, kinds 1/2 dropped with `test_pc_streams` off | PROTOCOL "Experimental PC announcement", strict decoding |
| I3 | Public address on PC dials is linkable across days with an OUI | S4 raised; per-session consent; W7 initiator address type; the viewer-dials alternative recorded |
| I4 | Device Name and Device Information readable after connect; address rotation at every restart | W7 |
| I5 | iPhone interop unstated | "iPhone interop"; W9 |
| I6 | Vector, flood and dial-path gaps | W2, W3, W8 |
| I7 | Epoch clock owner before W4; extended-scan battery; provider coexistence | W4 prerequisite; S8, S12, S13 |

Status: revision 4 answers the independent review; the coordinator merges the design and schedules W1 and W2 before any app code. Switches named here (`test_pc_streams` on Android; `MESHLIVE_WATCH` and `MESHLIVE_GO_LIVE` on Windows, all default off) go into SWITCHES when built.
