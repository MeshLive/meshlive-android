# Design WF: Wi-Fi Aware / Wi-Fi Direct transport (revision 2 after review 1; superseded by revision 3 of 2026-11-04, not built)

**Status 2026-11-04.** Revision 3 (PROTOCOL "Wi-Fi transport"; DECISIONS "Multi-radio mesh (2026-11-04)") extends this design from direct calls on Android 13 and newer to every hop, adds a Wi-Fi discovery plane (Wider reach), makes Wi-Fi Direct a user opt-in and adds Windows. The research, the census, the measurement plan and the review table below stay valid; where this section says "stage 1", "Android 13 and newer only", "Wi-Fi Direct in DEBUG measurement builds only" or "no Wi-Fi Direct choice in user settings", revision 3 replaces it. The questions at the end are answered as marked.

**Design review required.** Revision 1 (2026-10-27) was reviewed by two independent reviewers the same day; revision 2 below addresses every finding (table "Review 1"). A second review of this revision comes before any code. No code, no phone run. Wire rules: PROTOCOL "Draft: Wi-Fi transport". It realises the "Faster bearers" decision of "Product direction (2026-10-09)" and the plan item "Wi-Fi Direct / Aware bearer"; it reuses the Classic upgrade's carrier switch, as the earlier notes ("Wi-Fi Direct / Aware bearer (later)" below, Design CX "Not designed here") anticipated.

## Goal and boundaries

- **Goal.** Much more throughput and lower delay for video between two nearby phones (Wi-Fi Aware reaches tens of Mbit/s where our measured Bluetooth LE links carry about 1 Mbit/s or less), with the same offline promise, the same identities and the same encryption. Both pillars benefit: video calls directly; voice indirectly (a call can outlive a Bluetooth stall on the Wi-Fi carrier, and Bluetooth airtime is freed for others nearby).
- **Bluetooth stays the plane for discovery, control and security.** Wi-Fi is only an optional second carrier for an already authenticated call, negotiated inside it.
- **Owner rules applied.** Privacy and security never weaker than BLE: the Wi-Fi carrier is untrusted transport, and a bearer is used only where no stable identifier goes on air that Bluetooth would not also show (this rule is why Wi-Fi Direct is out of user sessions). Fallbacks only on the failing link: a Wi-Fi failure returns that call to Bluetooth, and peer-caused failures never change another call's eligibility. Hop-local: stage 1 is direct calls only. Unavoidable risk on the attacker: replays and hostile peers cost the requester bounded work on that one call. No other project's code (ideas are cited).
- **Stage 1 (narrowed by review 1).** Android 13+ to Android 13+ direct video calls over Wi-Fi Aware. Wi-Fi Direct and Android 10 to 12 are DEBUG measurement only. Emergency calls, SOS, resumed (`BER1`) and verification sessions are excluded.

## Research: what the platforms offer (sources checked 2026-10-27)

**Android Wi-Fi Aware (NAN).** Sources: Android developer guide "Wi-Fi Aware overview" (developer.android.com/develop/connectivity/wifi/wifi-aware); AOSP framework sources and public API surfaces (`packages/modules/Wifi/framework`, `main` and the `android-12.0.0_r1` and `android-13.0.0_r1` tags: `WifiAwareNetworkSpecifier`, `DiscoverySession`, `PublishConfig`, `Characteristics`, `WifiAwareManager`, `WifiAwareNetworkInfo`); AOSP "Wi-Fi Aware" device page (source.android.com/docs/core/connect/wifi-aware); the `setPmk`, `setChannelFrequencyMhz` and `getChannelInfoList` references.

| Fact | Detail | Consequence |
| --- | --- | --- |
| Availability | API 26+, only where the maker enabled it: `FEATURE_WIFI_AWARE`; `isAvailable()` false while Wi-Fi is off; "some devices may not support Wi-Fi Aware if Wi-Fi Direct, SoftAP, or tethering is in use"; `ACTION_WIFI_AWARE_STATE_CHANGED` means every session is gone | Eligibility from live checks; a lost-availability broadcast is a carrier death |
| Who else is attached | `isDeviceAttached()` (public in the API 31 surface): whether any client holds Aware now | Eligibility: no other client, so this phone's attach enables Aware and draws fresh addresses |
| Permissions | `NEARBY_WIFI_DEVICES` (`neverForLocation`) on API 33+; `ACCESS_FINE_LOCATION` (with coarse on 31 and 32) and Location turned on up to API 32, where publish and subscribe are also gated on the location app-op in the background; `CHANGE_NETWORK_STATE` to request the network (not yet in the manifest) | Table "Permissions" below |
| Data path | `Builder(session, peerHandle)` API 29; public `setPmk` on the builder from API 30 (the API 29 public surface has only the deprecated session methods); any-peer `Builder(publishSession)` API 31, one security configuration for all its paths (so not usable for several children with pairwise keys); `setPort` only on the publisher and only on a secured path; without a PMK or passphrase the path is open; `setChannelFrequencyMhz` (API 33) is honoured only if `isSetChannelOnDataPathSupported()`, otherwise firmware chooses; the actual channels are readable from `WifiAwareNetworkInfo.getChannelInfoList()` (API 33) | Stage 1 needs API 33: a PMK, a channel hint and a verifiable band |
| Instant communication mode | `setInstantCommunicationModeEnabled(enabled, band)` API 33 if supported; faster discovery and setup, more power, 30 s | A/B arm |
| Suspend and resume | `@SystemApi` with `MANAGE_WIFI_NETWORK_SELECTION` (API 34) | Not available to this app |
| Pairing | `initiatePairingRequest`, `setPairingConfig` API 34 (public), Wi-Fi Aware 4.0 section 7.6; `isAwarePairingSupported()` | Not needed Android to Android; the iPhone path depends on it |
| Addresses | AOSP requires random discovery (NMI) and data (NDI) addresses, re-randomised when Aware is enabled and every 30 minutes by default, and permits deferring re-randomisation while a data path exists | Eligibility rule 3, attach closed after every call, census measures it |
| Service names on air | Service ID = 48 bits of SHA-256 of the name; `PUBLISH_TYPE_SOLICITED` publishes nothing unsolicited and answers matching subscriptions in unicast | Per-attempt names; no "silent host" claim |
| Limits | Clustering is device-wide and costs battery; `getAvailableAwareResources()` (API 31) reports free paths; reports: one data interface per initiator request on recent Pixels, firmware budget 8 paths, older phones 2 | One carrier per phone in stage 1 |

**Android Wi-Fi Direct (P2P).** Sources: `WifiP2pManager` and `WifiP2pConfig` (AOSP `main`), AOSP "Wi-Fi Direct" device page (source.android.com/docs/core/connect/wifi-direct), Android "Wi-Fi Direct" guide.

- `WifiP2pConfig.Builder` (API 29) creates or joins a group from a network name and passphrase with a band or frequency, without peer discovery; its default is a temporary group, while `createGroup(channel, listener)` without a config creates a persistent one.
- AOSP: randomisation of the Wi-Fi Direct device address is a device feature the maker must enable (`config_wifi_p2p_mac_randomization_supported` and the supplicant's `setMacRandomization`); where enabled, the device address is re-drawn on interface creation only if no persistent group is saved, and kept otherwise; the group address is re-drawn per connection. Apps cannot delete persistent groups (`deletePersistentGroup` is `@SystemApi`) or set the device name (`setDeviceName` is `@SystemApi`), which Wi-Fi Direct carries in its information elements. An app can read its own name and address with `requestDeviceInfo` (API 29).
- Android 16 adds Wi-Fi Direct R2 (WPA3-SAE); not needed here.

**Apple.** Sources: Apple "Wi-Fi Aware" framework documentation and "Adopting Wi-Fi Aware" (developer.apple.com/documentation/wifiaware); Apple developer forum threads 790195 and 792143; an independent cross-platform report (hackmd.io/@grjte/cross-platform-wifi-aware).

- Wi-Fi Aware exists on iOS and iPadOS 26 (iPhone 12 and later), not on macOS. Devices are paired first through DeviceDiscoveryUI or AccessorySetupKit (a persistent relationship, like a Bluetooth bond); services are declared statically in `Info.plist` (`WiFiAwareServices`, a fixed name of at most 15 characters plus `_tcp`/`_udp`) with the `com.apple.developer.wifi-aware` entitlement; the data path is a Network-framework connection; `WASharedSecret` gives a per-connection secret.
- Apple's accessory guidelines require Wi-Fi Aware 4.0 pairing (PIN bootstrapping, NCS-PK-PASN-128), solicited publish and active subscribe. As of those threads, Android-to-iPhone pairing has not been shown to work.
- Multipeer Connectivity and the Network framework's peer-to-peer option (AWDL) are Apple-only. An iPhone can also join an Android Wi-Fi Direct group as an ordinary Wi-Fi client (`NEHotspotConfiguration`, which needs the Hotspot Configuration entitlement and shows a system prompt on every attempt), but that takes over its only Wi-Fi client connection (it leaves its current network) and carries the Wi-Fi Direct exposures above.

**Windows.** Sources: `Windows.Devices.WiFiDirect` reference (learn.microsoft.com), notably `WiFiDirectAdvertisementPublisher`, `WiFiDirectConnectionListener`, `WiFiDirectDevice` and `WiFiDirectLegacySettings` (the PC as a normal access point with an app-chosen SSID and passphrase; Mobile Hotspot takes precedence and stops it). No public Windows API for Wi-Fi Aware was found. Joining an Android group as a legacy client (`Windows.Devices.WiFi.WiFiAdapter.ConnectAsync`, `wiFiControl` capability) takes over the PC's only Wi-Fi client connection.

**Ideas from other projects (ideas only, no code).**

- *Google Nearby Connections* (bandwidth upgrade): authenticate on Bluetooth, send the upgrade credentials over the authenticated channel, keep the new channel paused until the old one drains. We keep the drain through the Classic cutover's boundary marker, keep Bluetooth as the standby instead of closing it, and give the new carrier its own keys.
- *Knit* (Wi-Fi Aware plus BLE, its design notes): always time-box `requestNetwork`; a `PeerHandle` is valid only on the session that produced it; one data interface per initiator request; availability flaps and the same app's Wi-Fi Direct gets `BUSY` with no availability edge; location gating on API 29 to 32 in the background; on one phone model every initiated data path also dropped the phone's own Wi-Fi connection, so local repeated failures must stop the phone from initiating; a Wi-Fi Direct group survives a process death; about 70 MB/s on 5 GHz Wi-Fi Direct between two Pixels; about nine times worse throughput when the group's channel differs from the phone's Wi-Fi network.
- *Briar* and our Share app nearby: random group names and passphrases, nothing naming the app.
- *Bridgefy* (the published attacks on it): no stable identifier in anything broadcast.

## Our phones: expected support and the census that decides

Expectations only; **the census on each phone is the authority**.

| Phone | Model, SoC, Android | Wi-Fi Aware expected | In stage 1 if Aware works | Basis |
| --- | --- | --- | --- | --- |
| S24 | SM-S921B, Exynos 2400, 16 | likely yes | yes | Samsung flagships have shipped Wi-Fi Aware since at least the S10 (Ditto's checker article); developer-forum reports of Aware use on the S23 and S25 |
| S22 | SM-S901E, Snapdragon 8 Gen 1, 16 | likely yes | yes | as above |
| A55 | SM-A556E, Exynos 1480, 16 | unknown | yes | no public report found |
| Xiaomi | Redmi 23053RN02I, Helio G88, 15 | unknown, lean no | yes | budget MediaTek; no public report found |
| M31 | SM-M315F, Exynos 9611, 12 | unknown, lean no | no (Android 12: DEBUG only) | no public report found |
| ASUS | ASUS_I01WD (ZenFone 6), Snapdragon 855, 11 | unknown | no (Android 11: DEBUG only) | the SoC supports it (the Pixel 4 on the same SoC ships Aware); ASUS enablement unknown |
| A15s | OPPO CPH2179, Helio P35, 10 | unknown, lean no | no (Android 10: DEBUG only) | budget MediaTek |

Wi-Fi Direct is expected on all seven (DEBUG measurement only). Wi-Fi Aware 4.0 pairing (iPhones) is expected on none.

**Capability and privacy census (phone lane; read-only part first, Wi-Fi state recorded and restored).** Per phone, into the run's own output. Addresses and names are never stored: each is replaced by `SHA-256(run salt | value)[0:8]` with a fresh random salt per run that is discarded after the comparison, so only equality within the run is kept.

```text
adb -s <serial> shell getprop ro.product.model
adb -s <serial> shell getprop ro.build.version.sdk
adb -s <serial> shell getprop ro.soc.model                         (API 31+; empty below)
adb -s <serial> shell pm has-feature android.hardware.wifi.aware   (prints true/false)
adb -s <serial> shell pm has-feature android.hardware.wifi.direct
adb -s <serial> shell pm has-feature android.hardware.wifi.rtt
adb -s <serial> shell cmd wifi status                               (Wi-Fi on/off, connected band; hash SSID/BSSID)
adb -s <serial> shell dumpsys wifiaware                             (keep: mUsageEnabled, availability, attached clients,
        maxNdiInterfaces, maxNdpSessions, maxPublishes, maxSubscribes, supportedCipherSuites,
        isInstantCommunicationModeSupported, isNanPairingSupported, isSuspensionSupported, is6gSupported, isHeSupported)
adb -s <serial> shell dumpsys wifip2p                               (keep: state, whether a persistent group is saved,
        the device-address randomisation setting if printed; hash the device address)
adb -s <serial> shell dumpsys wifi                                  (keep: STA + Aware / STA + P2P / P2P + Aware
        concurrency lines of the chip interface combinations, if printed)
```

The Aware capability block is filled only after Wi-Fi has been on once since boot. The owner of the phone lane may turn Wi-Fi on for the census (allowed on test phones), records it and restores it. The active part needs a DEBUG build (`test_wifi_probe`, never upgrades): `Characteristics`, `getAvailableAwareResources()`, `isSetChannelOnDataPathSupported()`, attach success and time; the **Wi-Fi Direct device address** (salted hash, from `requestDeviceInfo`) across two group cycles of this app and across a Wi-Fi off/on, and whether a persistent group is saved; and, with a monitor-mode capture (M7), the Aware discovery and data addresses across two calls. Results feed `DeviceFamilyPolicy` entries (matcher, behaviour, sources, census evidence, confidence, off-switch) that turn a bearer off where its addresses do not rotate.

## Permissions (what each API level needs before `CAPS` may set a bearer bit)

| API | Wi-Fi Aware | Wi-Fi Direct (DEBUG) | Background use during a call |
| --- | --- | --- | --- |
| 29, 30 | DEBUG only: fine location, Location turned on; PMK needs 30 | fine location, Location on | the call service's `location` type is declared only for these levels, and not in the Play flavour: attempts only while the call screen is in the foreground |
| 31, 32 | DEBUG only: fine and coarse location, Location turned on | fine and coarse location, Location on | no `location` service type: attempts only in the foreground |
| 33+ | `NEARBY_WIFI_DEVICES` (runtime, requested when the user turns the switch on), `CHANGE_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`, `INTERNET` | `NEARBY_WIFI_DEVICES` | no location gate; the existing call service suffices (checked in M1) |

`CHANGE_NETWORK_STATE` is a normal permission to add to the manifest when built. A missing or revoked grant clears the bearer bits at the next check and the call stays on Bluetooth. When the app targets API 37, Android's local-network protection (`ACCESS_LOCAL_NETWORK`) must be checked against the Aware data path's link-local sockets.

## Design (phased)

1. **Stage 1: direct video calls, Wi-Fi Aware, Android 13+.** The caller's link handshake carries a build marker `WIFI` inside its encrypted block; after the call is answered (and, with pseudonyms, after both reveals) both send `CAPS`, which carries the actual willingness and eligibility. The bearer is fixed for the call from the two `CAPS`. The host publishes under a per-attempt name derived from the call session; the guest's discovery handle is pinned through a challenge echoed over Bluetooth (`PICK`), so only the real peer's handle gets a data path; both request the data path with a derived PMK, check it is on 5 GHz, open a socket bound to the data interface and the expected peer, and bind it with a challenge-first key exchange keyed by the call session. The Classic cutover machine then moves media to Wi-Fi and back.
2. **Later stages, each with its own review.** Stream parent-to-child hops (streams must speak BMH3 first, question Q-WF6; several children need either a data path per child with pairwise keys, within the measured path budget, or a separately designed group credential with per-child binding and revocation; one shared any-peer request or group with one child's key is not allowed). Relay hops (link-scoped keys; in-call renewal does not apply to relayed calls). Windows and iPhone (below).
3. **Privacy.** Per-attempt names and keys derived from the call session; no unsolicited publication; no other Aware client attached (fresh addresses per call), attach closed after every attempt and call; Wi-Fi Direct out of user sessions. Rotating discovery IDs are unaffected: Wi-Fi is used only after a link exists and the user answered, never in an advertisement. Residuals (PROTOCOL "What is on air while it is used"): Aware addresses stay the same for the life of one call (as the call's Bluetooth connection address does), NAN capability attributes narrow the chipset family, and a sniffer can correlate the Bluetooth link and the data path. The census verifies the address behaviour per phone family before any user build enables Aware there.
4. **Security.** Channel binding to the call session (no signature or identity on Wi-Fi), challenge-first binding (no ticket consumed before a keyed proof), sockets bound to the data interface and the peer address only, and the unchanged record layer. Downgrade costs only quality. Denial of service is bounded per call and attributed locally versus to the peer.
5. **Battery, heat and coexistence.** Aware clusters cost battery while attached, so this app attaches only for an attempt and a call; suspend and resume are not available to apps. The thermal guard keeps its caps. Bluetooth and 2.4 GHz Wi-Fi share the band and often the antenna and time-slice: on the M31 a Wi-Fi connection on 2472 MHz cut GATT from 0.89 to 0.36-0.46 Mbps and an S24-to-M31 600 kbps stream from 13.0 to 1.9 FPS, while 5 GHz was unaffected ("2026-10-06/07 change ledger" above; `WifiCoexistence`). Stage 1 therefore requires a data path verified on 5 GHz and declines locally when the phone's own Wi-Fi client is on 2.4 GHz; M6 measures the Bluetooth standby with Aware attached, with both phones' channels recorded.
6. **Cross-platform.** Android to Android first. iPhone: Wi-Fi Aware only after Android-to-iPhone Aware 4.0 pairing works on our phones, with a one-time system pairing per device and a fixed `Info.plist` service name (a weak app marker during an attempt); the Wi-Fi Direct client path is out for the same privacy reasons as on Android and would also cost the iPhone its current Wi-Fi network and a prompt per attempt. Mac: no Wi-Fi Aware; AWDL between Apple devices later. Windows: Wi-Fi Direct only, so not until question Q-WF2 is answered; joining as a legacy client takes over the PC's Wi-Fi connection. The binding and carrier-switch records are socket-level and reused by any later bearer.
7. **Switches, measurements, census first.** Below.

## Settings switch for users

- **Settings > Advanced > Connection: "Use Wi-Fi for faster video calls"**, subtitle "When both phones support it and have this on. Bluetooth still finds and connects, and takes over if Wi-Fi stops." Privacy note: "While Wi-Fi is in use, nearby Wi-Fi devices can see that this phone has a Wi-Fi connection, and its temporary Wi-Fi address for as long as the call lasts. Calls stay end-to-end encrypted, as on Bluetooth." (No claim that nothing repeats.) Shown only on phones that pass eligibility rules 2 and 5; turning it on asks for the Nearby devices permission.
- **Wi-Fi Direct (revision 3):** a separate opt-in switch that names the exposure (see "Multi-radio mesh (2026-11-04)"). Revision 2 had no Wi-Fi Direct choice in user settings (question Q-WF2).
- **Default: off until measured** (question Q-WF1).
- **Only one side on:** no upgrade; both sides still exchange `CAPS` (with the bits clear on the phone that has it off), so neither learns which reason applies.
- **Turned off during a call:** no new attempt from that moment; with Bluetooth alive the call returns to it (planned fallback) and Wi-Fi is released; if Wi-Fi is the only connection left, the call continues on it with a notice and Wi-Fi is released when Bluetooth is back or the call ends (question Q-WF10). Turned on during a call: the next call uses it.
- **In-call indicator:** the existing transport label shows "Wi-Fi" while media uses it.

**DEBUG switches (all Experimental, off by default; entered in SWITCHES when coded).** `test_wifi_upgrade` (master), `test_wifi_aware`, `test_wifi_direct` (measurement only), `test_wifi_legacy_api` (allow Android 10 to 12 in measurement builds), `test_wifi_probe` (census only, never upgrades), `test_wifi_icm` (instant communication mode A/B), `test_wifi_ble_standby_low_power` (lower Bluetooth priority while Wi-Fi carries media, A/B), `test_wifi_audio_only` (Q-WF3), `test_wifi_allow_24ghz` (Q-WF5, measured arm), `test_wifi_auto_ladder` (Q-WF4), `test_wifi_share_attach` (allow attaching while another client holds Aware, Q-WF9, measured arm).

## Measurement plan (same gates as every transport)

Wi-Fi bearers are new values of the transport dimension, so the inventory gains new case IDs when they are built; every Wi-Fi case starts NOT RUN.

| Step | What | Phones | Result |
| --- | --- | --- | --- |
| M0 | Capability census, read-only part | all seven | which phones are Aware candidates |
| M1 | Setup: `HOST` to first media record on Wi-Fi; instant mode on and off; permissions and background behaviour on API 33+ | best Aware pair (expected S24 and S22) | setup time, failure reasons by local versus peer fault |
| M2 | Throughput and delay ceiling on the bound carrier (DEBUG bulk test) | same | Mbit/s and round trip at 5 GHz, two distances |
| M3 | Video call A/B at identical settings: Bluetooth only versus with Wi-Fi | same, both setup roles, both directions | media age, longest stall, frozen share, speech success; higher Wi-Fi-only rates as separate cases |
| M4 | Fallback and renewal: switch off mid-call (Bluetooth alive and Bluetooth gone), Wi-Fi off on one phone, out of Wi-Fi range, in-call renewal while on Wi-Fi, the peer's process killed | same | call survives where a carrier lives; receiver pause (target 200 ms, measured); keyframe recovery; no leaked request or attach afterwards |
| M5 | Battery and heat: 10-minute call at the same bitrate, Bluetooth versus Wi-Fi | same | charge-counter mAh, skin temperature, thermal status |
| M6 | Coexistence: Bluetooth standby health with Aware attached and with a 5 GHz carrier; phone Wi-Fi on 2.4 GHz (the M31 condition, expected local decline); Wi-Fi client drops and time-slicing recorded | M31 or another 2.4 GHz phone beside the Aware pair | standby capacity, fallback time, client drops |
| M7 | Privacy, over the air: a monitor-mode capture (for example the Mac's Wireless Diagnostics sniffer or `tcpdump -I`) across several 15-minute rotating-ID epochs, two successive calls with different peers, process death; per-attempt service IDs differ; Aware addresses differ between calls; no persistent group saved; for Wi-Fi Direct (DEBUG) the device address across two group cycles and a Wi-Fi toggle, and what a third phone's peer list shows | three phones and the capture host | the residual list confirmed or corrected; per-family off-switches where an address does not rotate |
| M8 | Crowd and isolation: three Wi-Fi calls at once beside Bluetooth-only calls; one call with a peer that fails on purpose while another call upgrades | six phones | no harm to the others; failures stay with their call |

## Review 1 (2026-10-27: reviewer Opus and reviewer, independent) and where revision 2 answers it

| Finding | Reviewer, severity | Revision 2 |
| --- | --- | --- |
| In-call link renewal (SR) interaction: rights tied to one link; call liveness ignores Wi-Fi; model without SR; emergency sessions in scope | Opus 1, high | `UW` and the `CAPS` rights belong to the call session (from the end-to-end exporter of a direct call); precedence rule (a renewal wins, never closes the Wi-Fi carrier); a "standby replaced" event with idempotent re-sending of `SWITCH`/`RESUME`; carrier-aware call liveness (no Bluetooth-only liveness rule ends a call while Wi-Fi lives); model test extended with renewal; emergency calls, SOS, `BEC1`/`BER1` and verification sessions excluded from stage 1 |
| Wi-Fi Direct: device name broadcast; device address randomisation a maker opt-in, only at interface creation, pinned by any persistent group | Opus 2, high; reviewer 1, high | Wi-Fi Direct removed from user sessions (DEBUG measurement only); census of the address across group cycles and a Wi-Fi toggle with salted hashes; `DeviceFamilyPolicy` off-switch where it does not rotate; question Q-WF2 rewritten (an exception would need the owner's explicit decision, showing the actual name and "nearby devices can recognise this phone across calls by its Wi-Fi Direct name and address") |
| Bearer could change between attempts | Opus 3 | The bearer is fixed per call from `CAPS`; a failed Aware attempt never retries on Wi-Fi Direct; Direct (DEBUG) only when Aware is unavailable locally |
| Socket confinement: wildcard listener, unscoped guest, hostile network | Opus 4; reviewer 10 | Host listener bound to the data interface's link-local address and scope, accepting only the `WifiAwareNetworkInfo` peer; guest socket from the network's factory; only those count toward budgets; no process binding, saved network, route, tethering or bridging; Direct (DEBUG): group-interface bind and subnet check |
| Aware setup order and the knock: the guest could request before the host; knock replayable; host request not time-boxed | Opus 5; reviewer 2 | Order: host publishes, guest knocks, host answers each verifying handle with a fresh challenge (cap 4), guest echoes it over Bluetooth (`PICK`), host registers a time-boxed request for that one handle and says `ARMED` over Bluetooth, guest requests; a replayed knock earns only a challenge. The reviewer's "ready" follow-up is the Bluetooth `ARMED` record (authenticated and not lost like a follow-up frame) |
| Permissions incomplete; PMK needs API 30; location rules on 29 to 32 | Opus 6; reviewer 7 | Stage 1 is API 33+; `CHANGE_NETWORK_STATE` added; per-API table; `NEARBY_WIFI_DEVICES` requested at the switch; 29 to 32 DEBUG only and foreground-only; `ACCESS_LOCAL_NETWORK` noted for target 37 |
| 5 GHz not enforced; unknown band; bit 1 undefined; STA on 2.4 GHz | Opus 7; reviewer 6 | Channel hint where supported, then the actual channels checked before any media (`DECLINE` 7, no penalty); a phone whose Wi-Fi client is on 2.4 GHz declines locally; band unknown means no upgrade (API 33 needed); the 5 GHz `CAPS` bits removed; M6 measures the standby with Aware attached and logs both phones' channels |
| `WIFI` extension leaked the switch state; sent to relays; pseudonyms; wrong size rationale | Opus 8 | `WIFI` is a build marker sent whatever the switch; willingness only in `CAPS` after admission (after both reveals with pseudonyms); never toward a relay; rationale corrected (the responder block would fit, 107 + 4 of 112; initiator-only is a privacy rule) |
| Stranded-group cleanup could remove someone else's group | Opus 9; reviewer 8 | Removal only when the group's name matches this app's sealed no-backup record |
| Phone-wide cooldown let an attacker turn Wi-Fi off for other calls | Opus 10; reviewer 4, medium | Peer-caused failures count per call only; the phone-wide pause counts only local platform failures; M8 tests a failing call beside a healthy one |
| Planned fallback order | Opus 11 | Restore Bluetooth priority, retarget the encoder, `RESUME`, first video after it a keyframe on Bluetooth |
| Resource ownership: one group per phone, one bearer type per phone, pending phases, release on hang-up and wipe; stale callbacks | Opus 12; reviewer 8, medium | Attempt owner keyed by call session, bearer, ticket and generation; atomic reservation; every callback and timer stamped; full release contract (unregister network callbacks, close sessions, attach and sockets); one carrier or attempt and one bearer type per phone; no global reset |
| Address persistence across epochs; randomisation deferred during a data path; beacons; capability attributes; SSID pattern; Settings text | Opus 13; reviewer 3, high | Eligibility rule 3 (no other Aware client) and the attach closed after every attempt and call; the residual stated honestly (addresses kept for one call, like a Bluetooth connection); beacons and capability attributes listed; Settings text no longer claims nothing repeats; DEBUG Direct SSIDs share Share app nearby's pattern |
| M7 needs an over-the-air capture; M6 should record client drops | Opus 14 | M7 uses a monitor-mode capture across epochs, peers and process death; M6 records client drops and time-slicing |
| Registry and the kinds statement | Opus 15; reviewer registry check (no collision) | Rows entered on the registry branch in their own commit; kinds renumbered so that 7 to 10 match `BCX1` 7 to 10 exactly |
| Apple and Windows client-join caveats | Opus 16 | Added: the join takes over the only Wi-Fi client connection, needs entitlements or capabilities, and prompts on the iPhone every attempt; both paths carry the Wi-Fi Direct exposures, so they wait for Q-WF2 |
| Switch off with Wi-Fi as the only carrier; replies after switch-off | reviewer 5, high | A serialised transition: new attempts stop at once; with Bluetooth alive, planned fallback then release; with Wi-Fi the only carrier, the call continues with a notice until Bluetooth returns or the call ends, still answering every cutover record (question Q-WF10) |
| Binding consumed the ticket before a keyed proof | reviewer 9, medium | `BWU1` now starts with a host challenge and a `UW`-keyed `pre` over ticket, challenge and the guest's ephemeral key, checked before the ticket is consumed or any ECDH |
| Heartbeat equal to the timeout | reviewer 11, low | Wi-Fi carrier heartbeat every 500 ms when idle, death after 2.5 s without a complete authenticated record; the call's own heartbeat (1 s in the code) is untouched |
| Role election could pick an unsupported role pair; stale resources | reviewer 12, medium | Explicit tuples from separate `freeHost` and `freeGuest` counts; local reservation just before start; a stale `CAPS` answers `HOST` 0 or `DECLINE` without a penalty |
| One any-peer responder or group cannot carry several pairwise keys | reviewer 13, medium (later stages) | Stage 1 is pairwise and uses only the per-peer builder; the fan-out claim removed; later stages need their own credential design |

## Alternatives considered

| Alternative | Why not (now) |
| --- | --- |
| Reuse `BCX1` (hop type 14) with new kinds | Its kinds 1 to 5 and its gating (verified contacts, link-hello bit 5) are Classic-specific; a separate hop type whose kinds 7 to 10 match `BCX1`'s keeps one cutover machine without mixing admission rules |
| A fresh BMH3 handshake on the Wi-Fi socket | Costs a signature and re-presents an identity on a new carrier; the call-session binding gives the same assurance with nothing identity-bearing on Wi-Fi |
| `UW` from the link exporter (revision 1) | An in-call renewal replaces the link with keys that have no BMH3 exporter, which would strand the rights; the call session survives renewals |
| A Wi-Fi Aware follow-up "ready" message | Best effort and unauthenticated on air; the Bluetooth `ARMED` record is neither |
| A hello or advertisement capability bit | No free link-hello bit; a cleartext bit is a stable marker for sniffers |
| Wi-Fi Aware for discovery instead of BLE | A second discovery plane with its own identifiers and battery cost; iPhones need pairing first |
| UDP datagrams | `BMS1` needs exact-next order; a datagram variant can follow once TCP's head-of-line delay is measured on Wi-Fi |
| Google Nearby Connections or another library | Play-services dependency and the no-external-code rule |
| Wi-Fi Direct with peer discovery | Broadcasts the device name in discovery and asks the user to confirm on many phones |

## Side finding (existing feature, not Wi-Fi transport)

Share app nearby's last fallback arm calls `createGroup(channel, listener)` without a config, which AOSP creates as a **persistent** group (`NETWORK_ID_PERSISTENT`). On phones whose maker enabled Wi-Fi Direct address randomisation, a saved persistent group stops the address from being re-drawn, and an app cannot delete it. Proposed: drop that arm, or keep it only behind an explicit choice; the two `WifiP2pConfig.Builder` arms already create temporary groups. Needs its own small change record and a phone check (`dumpsys wifip2p` before and after).

## Questions for the owner (recommendation first)

- **Q-WF1. Default of the Settings switch. ANSWERED 2026-11-04: A.** (A, recommended) Off until M3 to M5 pass on at least two pairs and M7 confirms the address behaviour on those phone families; then decide whether Wi-Fi Aware becomes on by default there. (B) On by default as soon as built. (C) Always opt-in. Evidence: no phone has run it; the address behaviour is a per-maker fact.
- **Q-WF2 (changed by review 1). Wi-Fi Direct for users. ANSWERED 2026-11-04: B for Windows; for phones C (a separate opt-in switch on every phone) that names the Wi-Fi Direct name and address exposure (DECISIONS "Multi-radio mesh (2026-11-04)").** (A, recommended) Not in user sessions under the current rule "never weaker than Bluetooth": it broadcasts the phone's Wi-Fi Direct name, and its address rotates only where the maker enabled it and no persistent group exists; DEBUG measurement only, and phones without Wi-Fi Aware stay on Bluetooth. (B) An explicit exception you decide: only on phone families where M7 shows the address rotating, behind a sub-switch that shows the phone's actual Wi-Fi Direct name and says "nearby devices can recognise this phone across calls by its Wi-Fi Direct name and address". (C) As in revision 1 (a sub-switch on every phone). Evidence: AOSP Wi-Fi Direct page; `setDeviceName` and `deletePersistentGroup` are system APIs; both reviewers.
- **Q-WF3. Audio-only calls. ANSWERED 2026-11-04: A.** (A, recommended) Stay on Bluetooth. (B) Upgrade them too, for range and resilience. (C) Push-to-talk groups later.
- **Q-WF4. Auto quality on Wi-Fi. ANSWERED 2026-11-04: until measured, Automatic keeps the Bluetooth ladder on Wi-Fi; no code raises a rate because the carrier is faster (option B).** (A, recommended) Auto may climb above the Bluetooth ladder (for example 720p at 1.5 to 2 Mbps) while on Wi-Fi and drops back before a planned fallback; fixed user settings never change. (B) Same quality as on Bluetooth.
- **Q-WF5 (sharpened by review 1). 2.4 GHz. ANSWERED 2026-11-04: A (on Android 11 and 12 the band cannot be read, so the phone's own Wi-Fi client must be off or on 5 GHz and the data path is closed when its goodput stays below twice the learned Bluetooth capacity for 5 s).** (A, recommended) No upgrade unless the data path is verified on 5 GHz, and a phone whose Wi-Fi client is on 2.4 GHz declines locally; a measured DEBUG arm only. (B) Allow when measured at least twice the pair's Bluetooth capacity. (C) Always allow. Evidence: the M31 2.4 GHz measurements.
- **Q-WF6. Streams before they speak BMH3. SETTLED IN THE PLAN 2026-11-04 (not an owner answer): the signed build tag already in BMH2 link hellos gates the new records; no new hello bit.** (A, recommended) Wait until stream hops speak BMH3. (B) A BMH2 link-hello decode-mask bit (cleartext, read by any sniffer). (C) A post-handshake probe record on BMH2 links.
- **Q-WF7 (changed by review 1). Order of work. SETTLED IN THE PLAN 2026-11-04 (not an owner answer): the package order of the multi-radio plan, Android first, Windows after a feasibility probe.** (A, recommended) M0 census now on idle phones; then stage 1 as narrowed (Android 13+, Wi-Fi Aware, direct calls) after a second review; then stream hops, relays; Windows and iPhone wait for Q-WF2 and for Aware 4.0 pairing interop. (B) Windows or iPhone earlier through Wi-Fi Direct (needs Q-WF2 B). (C) Pause until relays are finished.
- **Q-WF8 (new). Android versions. ANSWERED 2026-11-04: A for level Private (Android 13 and newer), B for level Wider reach (Android 11 and 12, Location on, with the local band rule of Q-WF5); Android 10 gets no Wi-Fi Aware.** (A, recommended) Stage 1 on Android 13+ only (the data-path band can be checked, no location gate; excludes the M31, ASUS and A15s, which are unlikely to have Wi-Fi Aware anyway); Android 10 to 12 DEBUG only. (B) Android 11+ with location on and no band check.
- **Q-WF9 (new). Another app already uses Wi-Fi Aware. SETTLED IN THE PLAN 2026-11-04 (not an owner answer): A at level Private; at level Wider reach the rule is not applied because this app holds the attach for discovery.** (A, recommended) No upgrade while another client is attached, because the Wi-Fi address would then be shared with that app's sessions and possibly earlier calls. (B) Allow it (measured arm `test_wifi_share_attach`).
- **Q-WF10 (new). Switch turned off while Wi-Fi is the only connection. ANSWERED 2026-11-04: A.** (A, recommended) Keep the call on Wi-Fi with a notice until Bluetooth returns or the call ends. (B) End the call at once (the switch wins). (C) Ask the user in the notice.

## Reviews and next steps

A second review of revision 2 (both reviewers: the call-session binding and its interaction with renewal, the `PICK` pinning, the challenge-first `BWU1`, eligibility rule 3, the switch-off transition), then the M0 census, then the pure parts first: the derivation and binding vectors, the role-tuple choice, and the extended cutover model with renewal, stale callbacks, switch-off and a failing second link. No Android wiring before the second review and the answers to Q-WF1, Q-WF2, Q-WF8 and Q-WF10. Answers received 2026-11-04 (above); the pure parts of revision 3 (wire codecs, key derivations, shared vectors, role choice, eligibility, the generalised cutover model) are built first, and the second review of revision 3 happens at the end of each package.
