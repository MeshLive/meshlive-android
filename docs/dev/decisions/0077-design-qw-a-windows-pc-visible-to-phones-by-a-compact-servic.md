# Design QW: a Windows PC visible to phones by a compact service-data announcement (Q-WIN1 option A; draft 2026-10-19, not reviewed, not built)

**Design review required.** Draft for the Q-WIN1 decision. Nothing changes until the user picks A and the review below is done. The Android part stays default-off (`test_pc_service_data`, Settings > Features "Find PCs", Experimental) until its phone test passes. The BMH2/BMH3, BDO1/BDX1, rotating-ID, admission and overload rules do not change. Discovery parsing is the only change.

**Problem.** A Windows PC can place calls, but no phone can find it. Today a PC cannot be called, cannot watch, cannot go live and cannot be a relay. Measured on the development laptop (unpackaged Rust exe, Windows 11, Intel adapter; evidence `meshlive-windows-rust/.artifacts/radio-check-1.txt`, `radio-check-2.txt`, `gatt-matrix-1.txt`, `gatt-matrix-2.txt`):

1. A `GattServiceProvider` that is connectable, not discoverable and has no `ServiceData` never leaves `Created` (radio-check-2, gatt-matrix-1 case 1). Windows has nothing to advertise for it.
2. The same provider with `ServiceData` starts. It reports `Aborted`, then `StartedWithoutAllAdvertisementData` (status 4). It did this with a 22-byte payload under `c8d5a101` (gatt-matrix-1 case 2) and also with the compact call UUID `c8d64ebd-54dc-065e-c840-000000000000` as the service UUID with 1 byte of data, 19 bytes in all (gatt-matrix-2). Which bytes were left out is not known.
3. `IsDiscoverable=true` is the documented way to get the service UUID into the advert (Microsoft Learn, "Bluetooth GATT Server": "When a service is both Discoverable and Connectable, the system will add the Service Uuid to the advertisement packet"). It also broadcasts the PC's friendly name, which is option B.
4. `BluetoothLEAdvertisementPublisher` refuses a 128-bit service UUID (`c8d5a101`: `E_INVALIDARG`). It does put the 22-byte `0xFFFF` manufacturer announcement on air (radio-check-2: Started), but only as a non-connectable advert.
5. Default Android finds MeshLive by the `c8d5a101` service-UUID list filter (`BleL2capControl.kt:464`). With "Find iPhones" on, it also uses a masked compact-UUID list filter (`BleL2capControl.kt:465-473`, `CompactDiscoveryAnnouncement.kt:98-99`). Both filters match only the advert's service-UUID list (AD types 0x02..0x07), never service data. So Android sees none of the forms in items 1-4.

**Wire form (option A as asked).** The PC hosts its normal GATT database: `c8d5a105` with `c8d5a103`/`c8d5a104`, the existing `central::SERVICE/WRITE/NOTIFY`. It adds one more "announcement" provider. That provider's service UUID is the role's compact 128-bit UUID (PROTOCOL "Experimental iPhone compact discovery", magic `0xC8D6`, version 2 for rotating IDs). Its `ServiceData` is empty, or 1 byte if Windows refuses empty (to be measured). The advert is then expected to carry one 128-bit service-data AD:

```text
02 01 06                                   flags (added by Windows for a connectable advert)
11 21 <16 bytes, compact UUID LSB first>   AD 0x21 "Service Data - 128-bit UUID", no data
                                           (12 21 ... 00 with the 1-byte fallback)
total 3 + 18 = 21 bytes (22 with the fallback), inside the 31-byte legacy PDU
```

Example: call offer, version 2, RDI `ebd54dc065ec`, GATT, VP9, cost 0 = UUID `c8d64ebd-54dc-065e-c840-000000000000`. On air it is the 16 bytes `00 00 00 00 00 00 40 c8 5e 06 dc 54 bd 4e d6 c8`. The advert itself never carries a name, a node prefix or anything fixed beyond what the compact UUID already carries (the address is a separate question, under Privacy). The announcement provider is recreated for each new epoch and RDI. It is stopped (`StopAdvertising`) for `advertise::boundary_silence_ms` around a boundary, as Android does.

**What Android must parse, and where.**

- `ScanRecord.getServiceData()` (AOSP `android.bluetooth.le.ScanRecord.parseFromBytes`) already returns 128-bit service-data entries (AD 0x21) keyed by UUID on every supported Android (minSdk 29). The app reads it nowhere today.
- Parsing would be added in `BleL2capControl.addResult` (`BleL2capControl.kt:481-556`), in the branch without manufacturer data (`:539-555`). Today that branch tries `CompactDiscoveryAnnouncement.firstCandidate(serviceUuids)` (`:541`). With the switch on, it would also try `firstCandidate(result.scanRecord?.serviceData?.keys?.map(ParcelUuid::getUuid))`. The decoder stays `CompactDiscoveryAnnouncement.decode` (`CompactDiscoveryAnnouncement.kt:113`), unchanged. The resulting `DiscoveryAnnouncement` goes through `acceptAnnouncement` exactly like an iPhone's.
- **The filter is the hard part.** AOSP `ScanFilter.Builder.setServiceUuid(uuid, mask)` matches only `ScanRecord.getServiceUuids()`. `setServiceData(uuid, data, mask)` matches only an exact service-data UUID (`ScanFilter.matches`: `scanRecord.getServiceData(mServiceDataUuid)`). The compact UUID changes per offer and per epoch, so no offloaded filter can select option A's form. Android would need an unfiltered scan, and Android stops unfiltered scans while the screen is off (since 8.1). The app already has such a path: `ScanFilterFallback.softwareFiltering()` (`ScanFilterFallback.kt:26`, S-06, screen on only) with a software match in `addResult` (`BleL2capControl.kt:486-490`). So option A works only while the phone's screen is on and the list is open, and an unfiltered scan wakes the CPU for every advert nearby (the crowd cost).
- **Recommended amendment A2 (same idea, filterable).** Use one fixed 128-bit service-data UUID for "PC announcement" (proposed `c8d5a10a-9f88-4f65-a03d-7f5a2fb921fa`, in the app's `c8d5a1xx` block) with a short payload: `kind (1) | RDI or prefix (6) | flags (1) | codec+cost (1)`, 9 bytes. On air: 3 + 1 + 1 + 16 + 9 = 30 bytes, within 31. Android adds `ScanFilter.Builder().setServiceData(ParcelUuid(PC_UUID), ByteArray(0)).build()` next to the existing filters (`BleL2capControl.kt:460-474`). This is offloadable, works with the screen off, and costs one filter slot, like the compact filter. It fits call offers and viewer requests. A stream offer (token, sequence, geometry) does not fit 9 bytes and would carry only `kind | prefix | token (4)` with the details behind the handshake (Design E style). Recommendation: **A2 rather than A**, unless the review prefers to reuse the exact compact UUID.

**Privacy.**

- **No name.** `IsDiscoverable` stays false (`peripheral.rs:82`), so the friendly name never goes on air. The payload carries what an iPhone's compact advert carries and nothing more.
- **Linkability: the open risk.** Rotating IDs are worthless if the advertising address is fixed. Windows does not expose the advertising address to apps, and the PC's outgoing connections arrived at the ASUS from the adapter's public address (ASUS logcat, win-userflow-20261019 asus-r3-noscan: `peer=...94:60` = public `44:38:e8:23:94:60`). If the GATT provider advert also uses the public address, the PC is trackable across epochs by its address, whatever RDI it shows.
- **Rule.** The PC advertises only if the unfiltered observer below shows a resolvable/random address that changes at least as often as Android's. Otherwise the feature stays off and the UI says why, the same fail-closed rule as Android's "Address reuse on legacy-only controllers". The bonded-phone residual (CF-PLAT-05) is unchanged.
- **The 0xFFFF publisher form (item 4)** would add a second, non-connectable advert from possibly another address. It is not used.

**Old versions.** Android builds without the switch ignore service data, so the PC stays invisible to them, as today. Nothing they do changes, and no wire or record changes. An Android with the switch on that sees a malformed entry counts it in `compactInvalid` and drops it (strict decoder).

**iOS impact.** iOS reads service data in `advertisementData[CBAdvertisementDataServiceDataKey]`. A foreground iOS scan without service filters receives it, but iOS's background scan needs a service UUID filter and does not match service data, so iOS would find a PC only in the foreground. The iOS app needs the same parse (A or A2) behind its own switch. Nothing changes for an iPhone advertising.

**Windows side.**

Ready:
- GATT server (`bluetooth::peripheral`: provider creation, connectable not discoverable, one pending subscriber, admission slot).
- Compact encoders (`advertise::compact_uuid`, `discovery::compact`, rotating offers, route tokens, boundary silence).
- Capability gate (`bluetooth::gate`, `PeripheralProof` from an actual start).
- The responder side of the handshakes (`bmh3::handshake::Waiting3`, `bmh2::Pending` responder) and the call session's incoming constructor (`CallSession::incoming`).
- The verification-session scope (`bvmid2::Scope`).
- The PC's own BVMID2 code text (`radio_backend::own_code`, shown in Add contact).

Missing:
1. The announcement provider. Its `ServiceData` per A or A2 is set with `GattServiceProviderAdvertisingParameters.ServiceData` (Windows 10 1903+). It is restarted per epoch, and its status is logged and latched in the gate.
2. The incoming-call path in the app backend: accept the GATT subscriber, the responder handshakes with the presence tag checks, `QrTagGate` single-use reservations for the displayed code (the displayer side of BVMID2), ring, Accept/Decline, Busy while in a call (CF-CALL-04: close before the hop handshake).
3. The UI's Receive calls / Watch / Go live gates, which already report "unavailable" with the reason.
4. The persisted epoch clock (`rotating::EpochClock` has no owner yet).

Roughly items 1 and 3 are small; item 2 is the bulk and reuses tested modules.

**Test plan.**

1. **On-air observer (gates everything).** One run per form (A empty, A 1-byte, A2) with the observer below. Record the exact AD bytes, the address type and its rotation over 30 min including an epoch boundary, and the Windows status events.
2. **Android parsing (JVM).** The decoder vectors from `CompactDiscoveryAnnouncementTest` are fed as service-data keys (A), or A2 payload vectors are added. A switch-off run must be byte-identical, with no new counters.
3. **Phone.** Switch on; the PC appears in Nearby within 1.5 s (CF-OUT-01) screen-on (A) or also screen-off (A2), and a phone calls the PC (ACTIVE, audio both ways). This is repeated after an epoch boundary, then with 5 other phones advertising (crowd), then with Bluetooth off/on on the PC.
4. **Privacy.** The observer confirms no name and no fixed address. Two phones with the switch on in different places see different RDIs and addresses for the same PC across epochs.

**Open measurement: what is on air.** The S22 HCI snoop was inconclusive because MeshLive's offloaded filters drop non-matching adverts before the host sees them. Recommended observer, in order:

- **(1)** nRF Connect for Mobile (Nordic) on a test phone: Scanner tab, no filters, "Show raw data". It lists every advert with the address, address type and raw AD bytes, and is a free, widely used reference tool. It needs a user tap, scheduled with root.
- **(2)** A second Windows PC running a passive `BluetoothLEAdvertisementWatcher` with no filters, logging `BluetoothAddress`, `BluetoothAddressType`, `AdvertisementType` and the raw `DataSections` (an `examples/observe_adverts` tool can be written now, run-gated by `--approved`).
- **(3)** An nRF52840 dongle with nRF Sniffer for Bluetooth LE in Wireshark, which shows the exact PDUs including TxAdd (random/public) independently of any host stack. This is the strongest evidence, if the hardware is available.

The address-type question (public vs random) needs (1) or (3); (2) shows what another Windows stack reports.

**Alternatives.**
- (B) A discoverable advert: documented and works with today's Android filters, but broadcasts the PC's name.
- (C) Keep the PC outgoing-only, with phones as the hubs.
- (D) A non-connectable `0xFFFF` publisher announcement plus a separate connectable provider. This matches Android's manufacturer path, but the two adverts may come from different addresses, and the publisher form cannot be connected to.
- (A2) Recommended, as above.

**Review questions.**
- Is a fixed "PC announcement" UUID (A2) acceptable as a class marker? It tells observers "a MeshLive PC" the same way `c8d5a101` tells them "a MeshLive phone".
- Should a viewer request and a stream offer from a PC be in v1, or calls only?
- Is the screen-on limit of plain A acceptable if A2 is rejected?

**Review of Design QW (independent fresh-context review, 2026-10-19): go with fixes.** Adopted:

| # | Finding | Severity | Resolution |
|---|---|---|---|
| R1 | A2's 9 bytes fit only call offers; viewer requests (route token + bitrate) and the reduced stream offer exceed the 31-byte legacy advert, and the payload had no version, relay-consent or paused bit | blocker | v1 = rotating call offers only; exact bytes: version+kind nibbles, RDI 6, flags (GATT, relay-capable, consent), codec 3 bits + cost 4 bits; viewer/stream offers need their own design |
| R2 | Windows reported "started without all advertisement data" at 19-22 bytes, so a 30-byte advert is unproven; the gate logged Aborted and hid the later status | blocker | hard gate before any Android code: an observer size sweep (0, 1, 5, 9 data bytes) with the raw AD bytes; only Started + exact bytes passes |
| R3 | an extended-advertising PDU is invisible to Android's legacy-only scan settings | should-fix | the observer records the PDU type; extended needs setLegacy(false) per phone |
| R4 | empty-data service-data filters match any payload on API 29+, but offload support differs and slot exhaustion falls back to host matching | should-fix | own filter guard and drop order (never takes discovery down), unit test, screen-off test on all phones with offload status logged |
| R5 | Windows exposes no advertising-address API; outgoing connections use the public address | should-fix (hard gate) | fail-closed DeviceFamilyPolicy allowlist (adapter, driver, OS build) with evidence; the address must change at the same moment as the RDI; outgoing-call public address recorded as a residual; the legacy-only Android rule is a warning, not fail-closed (citation corrected) |
| R6 | the fixed UUID marks "a MeshLive PC" | note | acceptable; stated in what nearby phones can see |
| R7 | spoofing/overload | should-fix | same strict decoder, dedup and acceptAnnouncement; own counters; precedence manufacturer > compact UUID > PC service data; exactly 9 bytes |
| R8 | A2 needs a new codec, not CompactDiscoveryAnnouncement.decode; rotating acceptance must be on | should-fix | new codec with shared Kotlin/Swift/Rust vectors; both switches required, UI hint |
| R9 | the per-epoch provider restart must not drop an active call | should-fix | tests: call across a boundary, restart latency vs boundary silence, Bluetooth off/on in a call |
| R10 | iOS background matching of service data is unverified | note | iOS foreground-only until measured |
| R11 | test-plan gaps | should-fix | observer per form (TxAdd, PDU type), screen-off latency per phone, slot exhaustion, spoof flood, switch-off byte-identical, address-vs-RDI timeline at 3 boundaries |

**Design QW gate 1 measured (2026-10-20, two Android raw observers, 16 provider cases): PASS on extended advertising only.** With a legacy advert, Windows puts flags, the 16-bit 0x180A Device Information UUID and the announcement UUID in the 128-bit UUID list, and drops the service data ("started without all advertisement data"). With an extended advert (secondary PHY LE 1M), the full 9-byte service data arrives intact. Every provider start used a new random address (address type random). Consequences: the announcement uses extended advertising, Android scanners use setLegacy(false) where isLeExtendedAdvertisingSupported, and the UUID-list form is at most a payload-free "a PC is near" hint for legacy-only phones. Gate 2 (one advert held across an epoch: does Windows rotate the address on its own timer?) is still open.

**Design QW gate 2 measured (2026-10-20, two Android raw observers, connectable extended announcement): PASS.** A non-connectable announcement never starts on Windows (status Aborted), so the provider must be connectable. Held for 20 min, Windows advertised from a random address and rotated it on its own after exactly 15 min (its own private-address timer, not aligned with our epochs). A stop/start changed the address at the same moment as the payload. No address ever carried two payloads. Rule: the PC restarts the announcement provider at every rotating-ID change. Windows' own extra rotation mid-epoch only splits one epoch across two addresses, which links nothing. Windows emits a legacy UUID-list advert and the extended payload advert at the same time, from different random addresses.
