# MeshLive architecture

For developers and curious readers: how MeshLive is put together (modules, radio links, sender and receiver pipelines, relay chains, calls, identity at a high level, distribution) and a summary of the experimental switches. Wire formats and security rules are in [protocol reference](protocol/README.md); building is in [building.md](building.md); testing is in [testing.md](testing.md); unfinished work is in [roadmap.md](roadmap.md).

Some words used throughout:

| Word | Meaning |
| --- | --- |
| Source / broadcaster | The phone that captures the camera and microphone and sends the stream. |
| Viewer | A phone that receives and plays a stream. |
| Forwarder / relay | A viewer that also passes the stream on to one more phone. Together they form a **relay chain**. |
| Hop | One Bluetooth link between two neighbouring phones. A stream that crosses two links has two hops. |
| Hop-local | A phone only ever talks to its **parent** (the phone it receives from) and its **direct children** (the phones it sends to). Nothing about the rest of the chain is known or controlled from here. |
| Node ID | The first 16 bytes of SHA-256 over a phone's public identity key. The first 6 bytes are the **prefix** shown in discovery. |
| RFCOMM | Bluetooth Classic's serial-port style byte stream. |
| LE | Bluetooth Low Energy. |
| L2CAP CoC | LE "connection-oriented channel": a byte stream over LE with its own flow control (a credit-based L2CAP channel). |
| GATT | The LE attribute protocol. MeshLive builds a bounded byte stream out of GATT writes and notifications when CoC is not available or wanted. |
| Bounded media age | An experimental policy that limits how old video may become by degrading it (lower rate, skipped frames, audio-only) instead of letting delay grow without limit. The default is "no frame loss": delay grows and playout absorbs it. |

## Contents

- [The big picture](#the-big-picture)
- [Where the code is](#where-the-code-is)
- [Screens and navigation](#screens-and-navigation)
- [Transports](#transports)
- [The sender pipeline](#the-sender-pipeline)
- [The receiver pipeline](#the-receiver-pipeline)
- [Relay chains and mesh](#relay-chains-and-mesh)
- [Private calls and intercom](#private-calls-and-intercom)
- [Identity and encryption (high level)](#identity-and-encryption-high-level)
- [Store-and-forward messages (Experimental)](#store-and-forward-messages-experimental)
- [Audio-only live (Experimental)](#audio-only-live-experimental)
- [Offline app sharing](#offline-app-sharing)
- [Device-family policy registry](#device-family-policy-registry)
- [Background, lifecycle and power](#background-lifecycle-and-power)
- [Diagnostics](#diagnostics)
- [The desktop receiver and file sender](#the-desktop-receiver-and-file-sender)
- [Host tools and the benchmark harness](#host-tools-and-the-benchmark-harness)
- [Distribution and releases](#distribution-and-releases)
- [Switches and experiments](#switches-and-experiments)

## The big picture

```text
  Source phone                     Viewer + forwarder                 Viewer
  ------------                     ------------------                 ------
  Camera2 / raw input              BLE scan finds the stream          BLE scan finds the stream
    -> encoder (VP9/AV1)           -> link handshake (BMH2)           -> link handshake with its parent
    -> Opus microphone audio       -> endpoint handshake (BME1)       -> endpoint handshake
    -> BTV3 packets                -> decrypt, decode, play           -> decrypt, decode, play
    -> end-to-end seal (BME1)      -> copy the same ciphertext to
    -> per-hop seal (BMS1)            ONE child (new hop seal)
    -> one ordered queue per child
         |                                 |                                |
         +------ Bluetooth link 1 ---------+------ Bluetooth link 2 --------+
```

- Discovery is always Bluetooth LE advertising and scanning, even when the media will travel over Classic.
- Every link has two cryptographic layers: a **hop** layer between the two adjacent phones and an **endpoint** layer
  between the original source and each viewer (or between the two people in a call).
- A forwarding viewer decrypts for its own screen and passes the **same encrypted media** to its child. It does not
  re-encode, so a relay chain adds no codec delay and no quality loss per hop.
- A private call is a separate session with two independent producers and decoders on one authenticated
  full-duplex link.
- Tapping a person to call waits up to 10 s for that person's next Bluetooth sighting (a scanner restart, for example
  after returning to the app, can delay the first sighting by several seconds), shows "Looking for <name>…", and on
  expiry says the person is no longer reachable (their phone stopped offering calls) and removes the row at once, or that
  this phone's scanner is unavailable. It adds no advertising and sends nothing extra over the air.
- Calling a saved contact (Contacts, Recent, a conversation) that is not in the call list yet uses the same 10 s wait when
  this phone can recognise them: the call scan is started (Recent opens Contacts), "Looking for <name>…" shows with Cancel,
  and a sighting of their fixed prefix or of a rotating ID recognised as exactly that contact starts the call. Only after
  the wait does the "not found nearby" guidance appear; a contact this phone cannot recognise gets that guidance at once.
- A call that arrives while the user's own call is still looking for its person (`DialCollision`) is declined at once
  with Busy, without ringing, and the user's call goes on: the endpoint is rebuilt quietly and the dial waits for it. A
  notice names who called. A call from the person being dialled rings and ends the user's own dial, so two phones
  dialling each other connect. A caller that receives Busy says "Busy: they are calling someone else or are in another
  call".
- A Nearby call row leaves the list after three missed sightings of its call offer, measured per phone as the largest of
  its last 16 gaps between sightings, between 4 s and 12 s (12 s until four gaps are measured), so a phone that went into a
  call or a stream, or closed the app, stops being tappable within seconds. A BLE setup that ends in GATT status 133
  says their phone did not accept the connection (in another call, restarting or out of range).

## Where the code is

Everything under `app/src/main/java/com/example/bluetoothtest/` (the Kotlin package keeps its original name; the
product is MeshLive). Native code is in `app/src/main/cpp/`.

| Area | Main files | What they do |
| --- | --- | --- |
| Screens | `MainActivity`, `HomeUi`, `ContactActions`, `PeopleList`, `SettingsUi`, `StatusIndicator`, `CallScreenUi`, `CallScreenController`, `MessagesActivity`, `AboutActivity`, `OnboardingActivity`, `SupportUi`, `SecurityUi`, `VerifyContactActivity` | UI and session ownership. Sessions live with the activity, not a background service. |
| Discovery | `BleDiscoveryAdvertiser`, `BleL2capControl` (scanner and control server), `StreamDiscovery`, `MeshControlMessages`, `CompactDiscoveryAnnouncement`, `RotatingDiscovery`, `TitleAdvertiser`, `StableAdvertisingSet`, `PeerAddressBook`, `PeopleList` | The 22-byte announcement, scanning with a start quota and failure recovery, and the "People nearby" and "Nearby broadcasts" lists. |
| Transports | `ClassicConnector`, `ClassicTransport`, `ClassicPeerConnection`, `BleCocClient`, `CocListenerHub`, `CocSocketOutput`, `GattVideoTransport`, `AdaptivePhy`, `BlePhyPreference`, `LeConnectionPriorityKeeper`, `GattClientTeardown`, `BluetoothTransportPolicy` | The three media transports, PHY and connection-parameter handling, and stack-bug workarounds. |
| Security | `SecureBluetoothLink`, `EndToEndVideoSecurity`, `ForwardedStreamSecurity`, `GroupBroadcastSecurity`, `AndroidNodeIdentityStore`, `IdentityVerification`, `EmergencyWipe`, `NotificationPrivacy` | Handshakes, record sealing, identity, verified contacts and the wipe. |
| Sender | `CameraVideoEncoder`, `NativeAomBridge`, `MicrophoneOpusEncoder`, `NativeOpusCodec`, `VideoCodec`, `EncoderBitrateAdjuster`, `NativeEncoderSpeedGovernor`, `BluetoothVideoBroadcaster`, `BluetoothVideoSender` | Camera and raw input, encoders, rate adjustment, framing, per-child queues. |
| Receiver | `AndroidVideoReceiver`, `VideoStreamDecoder`, `EncodedFrameQueue`, `OpusAudioDecoder`, `MediaPlayoutClock`, `AudioPlayoutSync`, `PlaybackDelayProfile`, `VideoSurfaceTransform` | Read, authenticate, queue, decode and present. |
| Mesh and relay | `ReceiverStreamForwarder`, `BluetoothVideoRelay`, `MeshCore`, `MeshBandwidth`, `RelayChildHealthPolicy`, `ReceiverNodeHealthPolicy`, `FanOutStarvationPolicy`, `SourceCapacityRejectionPolicy`, `RelayChildReanchor`, `LinkCapacity`, `LinkRateController` | Hop-local forwarding, admission, overload withdrawal, learned link capacity and rate control. |
| Calls | `AndroidPrivateVideoCall`, `PrivateCallSession`, `PrivateCallMedia`, `PrivateCallAudioSession`, `PrivateCallRelayChain`, `BluetoothPrivateCallRelay`, `IntercomCall`, `CallPlayoutPolicy`, `CallAudioFocus`, `CallRinger`, `CallResolutionLadder`, `AudioConcealment` | Call signalling and media, relay chains, intercom features and audio behaviour. |
| Messages | `StoreForwardNode`, `StoreForwardLink`, `StoreForwardStore`, `StoreForwardCrypto`, `StoreForwardAutoSync`, `MessagesActivity` | Experimental store-and-forward messages. |
| Messages v2 screens | `ThreadScreen`, `MessagingV2ThreadModel`, `ConversationRowsController`, `MessagingV2PrivacyRows`, `VoiceNoteRecorder`, `ThreadSettings` | Conversation rows on Contacts, the Chats tab and the thread view (on whenever Messages is on) (see "Store-and-forward messages"). |
| App sharing | `AppShare`, `OfflineAppShare`, `OfflineAppShareActivity` | Passing the APK to a nearby phone. |
| Device policy | `DeviceFamilyPolicy`, `DevicePolicyRegistry`, `CodecPolicy`, `YuvPolicy`, `BluetoothTransportPolicy`, `BackgroundPolicy`, `MemoryThermalPolicy`, `NativeCompat` | The registry of phone-family specific behaviour. |
| Power and lifecycle | `ActiveVideoSessionService`, `BackgroundReliability`, `DozeWatchdog`, `ThermalGuardPolicy`, `WifiCoexistence` | Foreground services, restriction reporting, thermal limits. |
| Debug and test hooks | `DebugTunables`, `IvfVideoReplay`, `ReferenceRecorder`, `ReferencePcmInput`, `MediaImpairmentPlan` | `test_*` launch extras and benchmark inputs, active only in debug launches. |

Outside the Android app:

| Location | What it is |
| --- | --- |
| `receiver/`, `shared/` | A Windows C# receiver and the C# protocol code it shares with the file sender. |
| `tools/` | Python host tools: benchmark harness, scoring, release tooling, policy report, diagnostics parser. `tools/BluetoothVideoFileSender/` is a separate C# project. |
| `tools/video-stress-generator/` | A standalone test-video generator (MIT licensed). |
| `benchmarks/` | Small reusable inputs and catalog definitions for the harness. |
| `distribution/`, `fastlane/` | F-Droid and IzzyOnDroid metadata, store text and changelogs. |

## Screens and navigation

The look is "Plain Signal": black ground, Manrope (four bundled weights), Material Symbols Rounded icons, white
primary actions, red only for live, hang up and decline, green only for answer and the verified check, plain rows
instead of cards. The tokens are in `res/values/colors.xml` and `themes.xml`.

`MainActivity` owns every screen as a sibling view and a bottom navigation (`mainNav`) shown on the four tab
screens only: Live, Calls, Contacts, Settings. Live is the stream browser itself (`AppScreen.RECEIVER_BROWSER`): it is
the landing screen, scans for nearby streams while it is visible, lists them as plain rows (tap joins) and pins the
one Go live button above the tab bar; back from it leaves the app. Calls has a Nearby | Recent switch: Nearby is the
call browser (`AppScreen.CALL_BROWSER`, discovery running): everyone in range with voice and video buttons, contacts
first, strangers as "Someone nearby"; Recent is the history of calls (`RecentCalls`) in the idle Home state, where a
tap calls back and a long press blocks. Contacts lists the saved (verified) people, "Nearby now" for those in range
(the call browser runs there too), a tap opens the contact sheet (`ContactActions`: call, video, rename, block, remove,
full ID; with Messages v2 on it opens the conversation and the sheet is in the thread's menu), and Add contact is the one pinned button. Add contact (`VerifyContactActivity`) has a Scan their code | Show
my code switch and is the only place this phone's code and ID are shown. Reachability follows `CallReachability`: Home (Calls > Recent, Settings from Home) and the Live tab while it only browses (`ReachabilityScreen.LIVE_BROWSING`, scanning needs no advertising set) keep the idle call endpoint, so the phone rings on the landing screen; joining a stream (`ReachabilityScreen.WATCH`) or going live closes the endpoint so the viewer request, stream offer and title set keep their advertising sets, and returning restores it. An incoming call while browsing stops the stream scan, shows the call, and returns to the Live tab afterwards. The switch is `ReachableWhileBrowsingFeature` (SWITCHES). Every tab's title bar ends with a status indicator (`StatusKind`): a green dot when
all is well, otherwise the one highest-priority problem with a short word (Bluetooth off, permissions, Hidden, battery
saver pausing relay, heat lowering quality), computed from the existing readiness, reachability, battery and thermal
logic; a tap fixes Bluetooth/permissions or opens the status sheet (Bluetooth, Allow calls, Relay video for others,
battery and temperature). The broadcasting and watching screens share one top bar (back, title, red-dot LIVE badge,
timer when broadcasting). Control rows are one row of equal columns with an odd count and the red End circle (72 dp, the others 64 dp)
exactly in the middle (`HomeLayoutTest` asserts it): a call, voice or video, is Mute, Camera, End, Flip, Speaker; a video live is Mute,
Camera, End, Flip, Pause (`LiveControlSlots`); an audio-only live is Mute, End, Pause with Switch to video as a plain action under it.
A control with nothing to do keeps its slot (hidden or disabled) so End never moves. Flip is Material Symbols `cameraswitch`.
Speaker toggles the earpiece and loudspeaker (voice calls start on the earpiece, video calls on the loudspeaker); once a wired headset is
present (or a Bluetooth audio device, behind the experimental `call_bluetooth_output` switch) it opens the audio output sheet
(`CallOutputSheet`: Phone speaker, Earpiece, the device by name). The routing rules (`callOutputFor`, `PrivateCallAudioSession`) are unchanged
except that an explicit pick in the sheet is honoured while that output exists. A video live's Mute drops the Opus access units from
the microphone encoder (the codec configuration still passes; the microphone stays open as in calls) and Camera releases the camera and
encoder (a keyframe-first restart when it comes back); both work with or without the `live_media_state` switch, which only decides
whether viewers are told (a state record, PROTOCOL "Experimental live media state"). Flip while live restarts the stream camera on
the other lens when that lens offers the stream's current size and frame rate, otherwise it explains and changes nothing. Switch to video
in an audio-only live ends the radio, then starts a new video live with the same title (a new stream, never converted in place). Settings is the fourth tab: one scrolling top level whose rows open
one-level pages (`SettingsPage`: Video, Privacy, Calls, Share app offline, Experiments); Advanced is a section label followed by exactly five
plain rows (Connection, Manual video, Playback, Experiments, Help and feedback), each opening one page of that group's options
(`SettingsPage.CONNECTION`, `MANUAL`, `PLAYBACK`, `EXPERIMENTS`, `HELP`, all with the top level as parent), so nothing nests two levels. Every setting and experiment still exists; a settings row says what it does and shows at most a
current value, and its explanation opens from an (i) (`SettingsInfo`). Lists show contact names or "Someone nearby",
never an ID.

## Transports

MeshLive carries media over one of three Bluetooth transports. The user picks one in Settings; the app never
silently swaps it for another (the one exception is the experimental automatic GATT fallback in the register).

| Transport | Default? | Pairing | Notes |
| --- | --- | --- | --- |
| **Bluetooth LE GATT** | Yes | None | A bounded byte stream over GATT writes and notifications (about 1100 kbps picker maximum before the phone has measured its own link). Works on the most phones and needs no pairing, but one large frame occupies the link for its whole air time. |
| **Bluetooth Classic RFCOMM** | No | A one-time pairing (mutual bond) on both phones, unless the experimental Classic-without-pairing option is on | Highest throughput (about 1500 kbps picker maximum). Discovery is still BLE, whose rotating private addresses only a bond can resolve. |
| **Bluetooth LE L2CAP CoC** | No (Experimental) | None | An LE byte stream with credit-based flow control (about 1200 kbps picker maximum). The listening phone publishes its dynamic PSM in a small GATT characteristic; the caller reads it and opens the channel. Some phone stacks have serious CoC bugs, which is why it stays experimental. |

How they are used:

- **Discovery** advertises a fixed-size announcement as BLE manufacturer data (company ID `0xFFFF`) plus a service
  UUID: the 6-byte node prefix, flags (stream available, private-call available, accepting connections, route
  recovery), capability summary, a stream token, the route cost (how many hops from the source), a sequence byte and
  small stream hints (size, FPS, bitrate). Scanning is rate limited by Android (a sixth scan start inside 30 seconds
  is refused), so scan starts go through a gate and failures back off. An iPhone-style one-UUID compact form is
  decoded behind an experimental switch.
- **Control** for LE transports runs over a GATT service. For CoC it only carries the PSM; for GATT it carries the
  media chunks as well.
- **Media** always goes through the same authenticated, ordered record layer (BMS1 and BME1 in
  [protocol reference](protocol/README.md)), regardless of transport.
- Wi-Fi and USB are used only for development (adb). The one exception is the experimental "Share app nearby"
  screen, which uses a local Wi-Fi Direct group to hand over the APK, never media.

When a live stream sender moves its own link from 2M to 1M PHY (`one_pdu_per_event` or `stalled_one_way`), video queued for
longer than twice the viewer's playout (at least 4 s) is dropped once, codec-safely, and the sender waits for a keyframe
(`PhyMoveSkip`). This is on by default for streams with a fixed (manual) bitrate and off with Auto quality, calls and
audio-only live (SWITCHES, "Skip the backlog at a PHY move"). A separate flood guard drops media older than 8 s at the
same moment.

Bluetooth stacks differ a lot between Android versions and makers. Workarounds for known stack bugs (deferred GATT
close on Android 10/11, a shared LE CoC listener on Android 10, a fresh ACL for CoC on Android 12/13, write-busy
retries on Android 13+, the scan start quota and others) are entries in the device policy registry described
below, each with an upstream source.

### Carriers: Bluetooth and, optionally, Wi-Fi

A **hop** (a call link, a stream parent-to-child link, a relayed-call hop) is authenticated over Bluetooth as always. With
the Wi-Fi switches on, a hop may add a second **carrier**, a Wi-Fi Aware (or Wi-Fi Direct) socket that carries the same
sealed records; the Bluetooth link stays as the standby. One `CarrierSet` per hop owns the carriers, one writer and one
reader thread per carrier and one ordered consumer; a pure `CarrierCutover` state machine decides the switch and the
fallback (hold 1 s, no switch while the old queue is late, 30 s cooldown after a fallback, at most two fallbacks). Two
quality fallbacks (counted even when this direction never moved) end Wi-Fi attempts for the call.
`WifiRadio` owns the phone-wide Wi-Fi resources on one handler thread; `WifiEligibility` is a pure function from facts to
what this phone may do now. Bluetooth discovery is unchanged; with *Wider reach* a Wi-Fi Aware discovery plane carries the
same announcement and a phone seen only over Wi-Fi can be called or joined over Wi-Fi (the hop then starts on Wi-Fi). Every
Wi-Fi switch is off by default and listed in [switches.md](switches.md#wi-fi-carriers-and-wider-reach-design-wf-all-off-by-default).

**Hop carriers.** A stream parent-to-child hop and each hop of a relayed call are a `HopLink`: a pass-through to the raw Bluetooth session until both ends' `CAPS` prove a feasible Wi-Fi tuple, then the same `CarrierSet` and `CarrierCutover` as a call link. `HopLink` is activated once, lazily, on the thread that reads the hop, and from then on reads all carriers through one ordered delivery queue; a fallback leaves the stream or relay running on Bluetooth. A relay terminates two independent `HopLink`s (upstream and downstream), each with its own keys, attempts and `CarrierSet`. Closing a hop closes its raw link before the session wipes its keys, and a closed record writer or reader refuses to seal or open, so no record ever leaves under a wiped key; an activation that races the close is torn down by whichever side sees the other, and a failure while activating leaves the hop on Bluetooth. A hop's send-side Bluetooth load accounting follows the carrier it sends on, so Wi-Fi bytes never teach the Bluetooth capacity keys.

**Wi-Fi Direct carrier.** `DirectGroup` runs one attempt's group on the radio thread (create or join by derived credentials, band check, address, teardown); `DirectBearerDriver` plugs it into the per-hop attempt owner and the carrier it hands over is the same socket carrier as for Wi-Fi Aware; one group per phone (the radio ledger refuses a second one, and refuses it while a Wi-Fi Aware link is held; Share app nearby takes the same claim); a sealed list of the group names the app created lets it remove a stranded group after a crash without touching another app's group. A group counts as gone only when the platform reports that no group is formed: below Android 13 the group read returns nothing while the app is in the background or Location is off, so an unreadable group is removed only when this run of the app created or joined it, and is otherwise kept in the quarantine until a read can tell. After a removal the phone re-reads for up to two seconds before it quarantines the group.

## The sender pipeline

```text
Camera2 preview surface ----> MediaCodec hardware encoder (VP9 or AV1), surface input
Camera2 -> SurfaceTexture -> GL ----> MediaCodec hardware encoder, surface input   [experimental GPU scale ladder]
Camera2 ImageReader (YUV) --> MediaCodec byte-buffer input   (software/compatibility encoders)
                          \-> libaom "optimized software AV1" (JNI, CBR / VBR / CBR with frame dropping)
                          \-> libvpx "optimized software VP9" (JNI, same modes; Design VX)
Microphone -----------------> Opus (MediaCodec, mono 48 kHz, 32 kbps)  [experimental: native libopus for calls]
        |
        v
 BTV3 packets (13-byte header: length, flags, shared presentation timestamp) -> BME1 seal -> BMS1 seal
        |
        v
 one ordered queue per child -> link pacer -> Bluetooth transport
```

- **Input.** Live Camera2 is the real product path. Debug builds can also encode a supplied raw I420 file or replay
  a finished IVF file, so the same encoder runs on a repeatable, license-clean picture. These are different tests
  and are never confused with each other.
- **Encoders.** The app lists what the phone offers: hardware MediaCodec encoders first, then AV1 before VP9 when
  both exist, surface input before byte-buffer input, then the optimized software AV1 encoder (libaom, always built
  in), then the optimized software VP9 encoder (libvpx, always built in; Design VX). libvpx is a manual choice unless
  the `prefer_libvpx` switch is on (on since 0.4.2, SWITCHES), which places it after the hardware AV1, HEVC and VP9
  encoders and before hardware H.264 and libaom, for calls and streams alike. With battery saver on or a warm phone
  at session start, `codec_power_aware` moves the hardware encoders ahead of a software first choice (`CodecPowerAware`). The selection is shown to the user and never changed silently. Encoder-specific workarounds (for example
  quantizer bounds that OMX encoders ignore, or an OMX VP9 encoder that overshoots its target rate) are policy
  entries or experimental arms listed below.
- **Rate.** The user picks a requested rate (100-1500 kbps in 25 kbps steps; the picker maximum comes from this
  phone's measured link). In Automatic the source lowers the *effective* rate only on sustained backlog or
  late-arrival reports from its **own** children; requested and effective values are both visible. An adjuster
  ported from WebRTC's design corrects encoders that miss their target.
- **Defaults.** 240p (426x240) at 15 FPS and 200 kbps for calls and streams (see DECISIONS "Default video
  settings"); resolutions 240p/360p/480p, FPS 15/24/30. Keyframes are sent on demand
  (join, loss recovery) and every 60 seconds as a safety net, because a scheduled keyframe on detailed content is
  the biggest single burst on a Bluetooth link.
- **Measured Auto ladder (Experimental, off by default; `test_auto_ladder`).** With Video quality on Auto, the
  start geometry, frame rate and rate come from the measured link instead of the fixed default, and the same
  rungs are then walked up and down while the session runs. `AutoQualityLadder` is a pure policy: link goodput
  (85% utilisation; a call direction plans half the measured link; unmeasured plans 300 kbps), codec, encoder
  class and the wire model `1.076 x (video + 0.64 x fps) + audio wire` give a rung (below the lowest it is audio
  only). The request becomes a climb bound (a call: the transport maximum; a stream: the learned fan-out budget for
  one link, with admission still planned at the start rate) and the call header the class maximum geometry, so
  nothing in Auto is a fixed ceiling. `LinkRateController` starts at the plan's rate and climbs only on clean evidence;
  `CallResolutionLadder` (calls and live streams) follows its target with the measured limits, one rung up after
  1.25x headroom held 10 s and 15 s between changes, down after 1.5 s; `FrameRateLadder.curve` cuts frame rate
  above 15 FPS first and below 15 FPS only after 240p. A stream restarts its camera encoder at a rung change
  (never while paused; the first frame is a keyframe carrying the new size in-band) and follows the weakest direct
  child, so a source never outruns its tightest child (relays handle their own downstream hop). Manual video
  settings are never touched; the picker only warns when bits per pixel per frame fall below the fitted floor.
  Evidence and phone plan: DECISIONS "Measured Auto ladder".
- **GPU scale ladder (Experimental, off by default; `test_gpu_scale_ladder`, with the Auto ladder).** A rung change
  no longer restarts the camera. `GpuScaleCapture` opens the camera once at one capture mode (`GpuScaleMath.chooseCapture`:
  the smallest mode that covers the session's largest picture in its aspect, cropped on the GPU when the sensor has none)
  into a SurfaceTexture; `GpuFrameRenderer` (one EGL context on its own thread) draws each frame into the encoder's input
  surface at the rung size, the crop and scale folded into the texture matrix, with the camera's sensor timestamp as the
  presentation time (so timestamps stay continuous across encoder resets). Frames stay in sensor orientation exactly as
  when the camera fed the encoder directly, so the display-rotation flag and the viewer's transform are unchanged. Rungs
  are 720/540/480/360/240/180/144p in the session aspect, each side rounded to the encoder's alignment with the few
  pixels of difference cropped, never stretched. A rung change is `old encoder close()` (the capture stays open for
  2.5 s) then `new CameraVideoEncoder.start()`; its first keyframe carries the size change marker as before, so
  viewers rebuild their decoder. Software YUV-input encoders, raw fixtures, benchmark reference frames, Manual video
  settings and camera flip/off keep or release the camera as before.
- **Framing.** Video and Opus audio share one monotonic timestamp inside the encrypted `BTV3` packet, so the
  receiver can lip-sync them. Flags mark codec configuration, keyframes, rotation, audio and discontinuities.
- **Queues.** A source encodes once and gives each child an independent ordered queue with admission and
  withdrawal. By default no queue drops media for congestion: a slow link builds latency that the viewer's
  adaptive playout absorbs.
- **Pacing.** Writes are paced from a learned single-link capacity so that backlog shows up in the app (where it is
  measured) rather than hidden inside the Android Bluetooth stack.

## The receiver pipeline

```text
Bluetooth transport -> BMS1 open -> BME1 open -> BTV3 parse
   video -> EncodedFrameQueue -> MediaCodec decoder -> TextureView   (FILL or FIT, aspect preserved)
   audio -> Opus decoder ------> AudioTrack, kept on the same playout clock
                  both follow one MediaPlayoutClock (source timestamp + adaptive delay)
```

- **Playout clock.** One clock maps source timestamps onto a local presentation timeline. The delay starts at a
  profile minimum and grows only while this link delivers late (by the measured lateness plus a margin), then
  shrinks slowly after a calm period. Profiles: Low 100-600 ms, Balanced 250-1200 ms, High 500-2500 ms, Auto
  250-2000 ms (default). Streams remember a burst for 60 seconds before shrinking, because scene cuts on Bluetooth
  arrive in bursts that take 0.6-1.3 s. Private calls keep their own, shorter budgets.
- **No frame loss by default.** Audio stays continuous: when it drifts from the clock, playback speed changes
  slightly with pitch preserved, instead of dropping samples. The encoded-frame queue is sized for the whole
  adaptive range and applies backpressure to the link instead of discarding queued frames. Only frames that cannot
  be decoded anyway (before the first keyframe, or after a marked discontinuity) are skipped.
- **Size changes mid-stream.** A ladder restart (Auto stream ladder, call resolution ladder) changes the coded size; the
  viewer reads each keyframe's size from its bitstream and, when the size or the codec configuration differs, releases its
  decoder and creates one for it (held new csd, recovery with the newest csd, adaptive-playback maximum, display follows).
  The sender marks the first keyframe of a size-changing restart as a discontinuity; late joiners get only the current
  encoder's csd. Switch and detail: SWITCHES.md, PROTOCOL.md "Mid-stream size changes".
- **Bounded media age (Experimental).** When the delay must not grow without limit (calls, or a slow relay branch),
  an experimental policy degrades video in a fixed order: lower rate, skip verified non-reference frames, skip to
  the next keyframe, pause video and keep audio. Audio is never dropped. It is under A/B comparison against the
  no-loss default; the register lists the switches.
- **Link measurement.** A receiver tells its **immediate** sender how it is doing (arrival rate, lateness, bytes
  delivered and link clock) in the same authenticated heartbeat that proves liveness. This report is never
  forwarded, so no queue, bitrate, FPS or thermal metric crosses more than one link.
- **Decoder handling.** The app logs the decoder it requested and the one it got; a silent substitution would
  invalidate a test. Decoder start failures can fall back to the next component (user sessions only), and a
  recoverable codec exception re-creates the call decoder at the next keyframe.

## Relay chains and mesh

MeshLive is a tree of hop-local relationships, not a routed network.

- **One parent, direct children.** A viewer has one parent. A forwarding viewer offers **one** downstream child. A
  source may accept several direct viewers within its measured link budget. Each phone acts only on its parent and
  its direct children. Forwarding is controlled by the Settings switch "Help pass streams and calls to others
  nearby": on by default, it is re-evaluated at each join and pauses itself below 20% battery (not charging) or in
  Battery Saver.
- **What a forwarder does.** It authenticates its child with fresh link and endpoint handshakes, wraps the current
  media-open key in a `BFG1` grant that names the original source and stream, and copies upstream ciphertext
  unchanged. The child checks that the stream really comes from the originally announced source. Child keyframe
  requests and acknowledgements are authenticated locally and regenerated toward the upstream parent, because each
  adjacent session has its own keys.
- **Admission and overload guards.** A source or relay learns the load its links actually carried and admits another
  child only inside that measured ceiling (planned links carry a 20% margin). A child that stays behind (more than
  2.5 seconds, or starved for most of a window while a sibling is healthy) is released: the parent sends an authenticated
  `LOCAL_CAPACITY` withdrawal, waits for the acknowledgement, closes that one link, and the child avoids that route
  for 30 seconds before searching again. The source's encoder is never slowed for one child's sake.
- **Per-child thinning (Experimental, off by default).** With the relay child thinning switch on, a forwarder gives
  its child one of three levels, chosen from that child's own queue age, the rate this phone delivered to it and the
  child's late-media report (nothing from any phone beyond the next hop). *Full* forwards every record. *Base*
  leaves out the frames the source marked discardable, which it does only for frames whose compressed data proves that
  no later frame refers to them, so the child sees half the frame rate and every frame it gets decodes: no re-encode,
  no keyframe, and stepping back up needs nothing. *Audio only* leaves out all video while audio, configuration and
  stream-state records continue, so a link too weak even for the base layer keeps speech instead of stalling at a
  fraction of a frame per second; the child shows "Video paused: weak link", and the way back asks for a keyframe
  with the same back-off the bounded-age shedding uses. A step down is immediate on a severe backlog and otherwise
  needs the backlog to persist; a step up needs a calm period and a hold that doubles after each failed probe
  (8 s to 60 s), and does not start if the child's measured capacity cannot carry the next level. Only whole records
  are dropped, so the end-to-end checks are unchanged: each record is sealed with its own sequence, flags in its
  authenticated header, and the receiver accepts any increasing sequence (a gap), refusing replay and reordering. The
  source encodes the second layer for the whole stream while the switch is on (about 2.5 VMAF at equal bitrate),
  because it cannot see a weak link two hops away.
- **Losing a parent.** An unexpected parent loss keeps the last frame on screen and searches for the **same logical
  stream**. The replacement can be the source or another forwarder, but its authenticated identity must match its
  announcement and the original source must match the one first accepted. Playback resumes at a fresh keyframe. The
  recovery has a gap (break-before-make). An explicit End from the source returns the viewer to discovery.
  Experimentally (Settings > Video and calls "Warn when video freezes", default off, see the register) the
  preserved frame is dimmed and labelled "Signal lost - reconnecting" with how long ago the last picture arrived, so
  it is never mistaken for live video.
- **Liveness.** Immediate socket errors and authenticated heartbeats about every second end a dead link within 6-8
  seconds. Heartbeats stop at the immediate parent.
- **Direct fallback is a failure.** If a chain was requested and the viewer ends up connected straight to the
  source, that is reported as a failed chain, not a pass.
- **What measurements show.** Each hop spends airtime twice (a relay receives and sends on one radio), so
  multi-hop and fanned-out video is much weaker than a single link: in a multi-phone relay test on 2026-10-08,
  relayed or fanned-out leaves played at 0.9-7.6 FPS at 300 kbps. Short chains of low-rate video are realistic;
  long chains of high-rate video are not.
- **Blind relay (debug).** A debug-only relay holds only adjacent-link keys and cannot open endpoint content. It
  exists to test the trust model and to carry private calls; it has no multi-child user interface and static group
  keys do not implement live revocation.

## Private calls and intercom

- **Calls.** Both people run an independent producer (camera encoder, Opus microphone) and an independent decoder
  on one authenticated, full-duplex link. Signalling (`BMC2`: Invite, Ringing, Accept, Decline, Cancel, Hang-up,
  camera and microphone state) travels inside the encrypted endpoint session, and capture starts only after
  acceptance. Mute, camera off/on, flip and End affect only the local side.
- **Call budgets.** Calls keep much shorter playout budgets than streams and their own rate control. Call video
  starts at the rate that peer link last sustained (or, for an unknown peer, a conservative share of this phone's
  measured link, because both call directions share it) and climbs from there. It steps its resolution down when the
  target rate cannot support the current size. Call audio has its own queue headroom so one large video frame
  cannot starve it.
- **Call relay chains.** A call can cross up to four opaque relays between the two endpoints. A relay copies both
  encrypted directions without a camera, microphone or codec; only the two endpoints hold content keys. Admission is
  hop-local (route cost, signed relay capacity, loop-free by construction). The relay itself is currently a debug
  feature; the protocol, admission rules and tests are in place (see [protocol reference](protocol/README.md), "Private-call
  relay chains"). Broadcast forwarding is not evidence that call relaying works.
- **Intercom features:** voice calls, hold-Mute-to-talk, names and stream titles are on by default (no Settings switch since 2026-10-17); store-and-forward messages stay an Experimental, default-off switch; see the
  register. A voice call starts no camera, encoder or decoder and uses no new wire value.
- **Behaviour that is on in normal use:** transient voice-communication audio focus (music pauses during a call, a
  cellular call puts the call on hold), a ringer policy that follows silent/vibrate/Do Not Disturb, a proximity
  screen-off lock while audio is on the earpiece, and being reachable for calls while the app is open.
- **Legacy-family protections.** Some phone families (notably older Qualcomm Android 10-12 stacks) carry call
  limits such as a 426x240 cap, 110-160 kbps limits, batched audio and a forced software VP9 decoder. These are
  visible policy limits recorded in the registry and in [decisions](decisions/README.md), not hardware ceilings, and
  an experimental switch lifts them for testing.

## Identity and encryption (high level)

[protocol reference](protocol/README.md) is the reference. In short:

- Each phone has a persistent P-256 signing identity in the Android Keystore. Its node ID is derived from the public
  key. There are no accounts and no server.
- Every Bluetooth connection starts with a signed handshake (`BMH2`) with fresh ephemeral ECDH keys. After it,
  every record between the two adjacent phones is sealed with directional AES-256-GCM, ordered and
  replay-protected (`BMS1`).
- A second, independent handshake runs end to end between the source and each viewer (or the two call endpoints)
  with its own signature domain. Media and call signals are sealed with those keys (`BME1`), so a relay in the
  middle can forward and measure sizes and timing but not read or alter content.
- Discovery gives a phone's 6-byte node prefix. The link and endpoint identities must match it. That is a
  convenience check, not proof of who owns a phone: for that, two people compare a QR code or a 60-digit safety code
  in person. A verified contact whose key later changes (same prefix, different key) is refused by default.
- There is an in-app **emergency wipe** (confirmed, or a triple-tap gesture that is off by default) that deletes
  every Keystore key first and then the app data. Its limits (flash remanence, copies outside the app) are listed
  in [protocol reference](protocol/README.md), "Emergency wipe".
- Android backup is off (`allowBackup="false"` plus explicit exclude-all `dataExtractionRules` for cloud backup and device-to-device
  transfer, because a phone maker's transfer tool need not honour `allowBackup`): the keys are Keystore-bound, so a restored
  copy would be unreadable, and nothing is sent to cloud backup or to a new phone.
- Call and stream notifications carry the peer's name in their full version only (shown on an unlocked phone); the
  lock-screen version is name-free (`NotificationPrivacy`, `ActiveVideoSessionService`). The opt-in "Show caller names on
  the lock screen" makes a named notification public.
- An unreadable verified-contact store fails open: calls and streams continue and nobody shows as verified. The home
  screen shows "Verified contacts couldn't be loaded" (`VerifiedStoreBannerController`). A temporary Keystore or I/O
  error is retried with backoff (2 s doubling to 60 s, `VerifiedStoreRecovery`) and the banner clears by itself; a
  corrupt file is kept untouched and never overwritten, and the banner offers "Reset verified contacts".
- An independent security review has not happened yet.

## Store-and-forward messages (Experimental)

Off by default. When two phones are in range, they can exchange small encrypted messages (text, a voice note up to
two minutes, a photo up to 1280 px) for contacts the user has accepted, so a message can wait for a phone that is
not nearby right now. Version 1 covers two **directly adjacent** phones only.

- Each identity has a static mailbox key; contacts swap signed contact cards (`BCC1`), compare a short safety
  number, and accept them.
- A message becomes a sealed bundle (`BSB1` header visible to carriers, `BSM1` content readable only by the
  recipient) with a lifetime of 1, 3, 7 or 14 days. The inbox and sent list are encrypted at rest.
- An exchange (`BSF1`, hop type 11) runs only when both phones have the Call screen open and one taps the other, and
  it yields immediately to a call, stream or relay. An automatic sync mode (debug extra) uses the same exchange with
  per-peer back-off.
- A carrier role, where a third phone ferries sealed bundles for others, exists in code but is hard-off.
- The phone that dials gets the call dial's 2M protections (`MessageDialPhy`, switch `test_message_adaptive_phy`): a first 2M link whose
  hop handshake stalls is closed after 3 s and retried once on 1M, and a peer that needed that, or whose 2M exchange died early
  with no answer, starts on 1M next time. A plain exchange keeps its 120 s total limit only while no transfer moves: it is extended
  (up to 15 minutes) while chunks keep flowing, and the 10 s liveness and progress deadlines stay strict (`test_mv2_progress_total`).
  A responder gives the 60 s spacing back when an admitted exchange is cut by the link before anything moved (the hourly cap stays).

See [protocol reference](protocol/README.md), "Store-and-forward messages", and [decisions](decisions/README.md), "Design A", for the design.

**Messages v2 attachments (switch `test_messaging_v2`; on whenever Messages is on).** Phase 1b of Design M lets a message carry any file. On send, `AttachmentPreparer` turns a picked photo, video or file into a `PreparedAttachment` for `MessagingV2Engine.sendFile`: pictures are re-encoded (1600 px, JPEG 80, no metadata) or, as "Original", a JPEG is rewritten without any `APPn`/`COM` segment or trailer (`JpegMetadataFilter`); videos are remuxed without their location atoms and scrubbed again (`Mp4Scrubber`); any other file is sent as it is. On receive, nothing is parsed or decoded automatically: `FileSniffer` decides from the bytes what the file is (the sender's MIME type is only a label), `FileActionPolicy` offers Open only for pictures, audio, video, PDF and plain text that are not polyglots, and everything else is Save only behind a warning. Open hands the viewer a revocable one-file grant on a non-exported `MessagingV2OpenProvider` that decrypts from the file vault on demand through a proxy file descriptor (`OpenGrantTable`, `VaultRangeReader`); a picture is decoded only after a tap, in `MessagingV2ImageService`, an isolated-process service. The details and limits are in [protocol reference](protocol/README.md) "Implementation notes (phase 1b)".

**Messages v2 conversations (switch `test_messaging_v2`; on whenever Messages is on).** Phase 1c puts the engine behind two screens, both views of `MainActivity` rather than activities, because the thread link (phase 1d) is dialled from the discovery that runs while the Call screen (and so the Contacts tab) is showing; a second activity would stop that scan. With the switch off the Contacts tab is unchanged and a row still opens the contact sheet.
- *Contacts rows* (`ConversationRowsController`, `item_conversation_row`): each saved person is one row with the name and verified check, a one-line preview (or the kind: Photo, Voice note, File), the time, the unread count, the state glyph of the newest own message and "Nearby now". Rows with activity sort first (newest first), then the Contacts order. They are computed off the main thread from `ConversationIndex` and refreshed every 3 s while the tab shows. A tap opens the thread; the contact actions (call, video, rename, block, remove) live in the thread's top bar and its info sheet (`ContactActions`).
- *Thread* (`ThreadScreen`, `view_thread`): top bar (back, name with verified check, "Nearby" or "Not nearby", voice, video, more), a banner for the link state, the message list, the composer. Yours are raised-surface bubbles on the right, theirs plain-surface on the left, with date separators and an unread marker; a tap on a bubble's glyph shows its sentence ("Delivered to Rahul, 14:00"). The states are the engine's: Waiting, Sending, Delivered, Downloaded, Read, and Not sent with Retry (`MessagingV2Engine.retry` seals the message again from its words or from the copy the vault keeps). A file is a card (type icon, name, size) with Download, Cancel, Open, Save or Show picture as `FileActionPolicy` allows; a picture is decoded only after a tap. The banner covers `ConversationLinkState`: not a contact or card pending (Add contact, Compare), keys changed (Compare, Accept new keys after a confirmation, Dismiss; the composer stays off), and an older peer app. The composer has a text field, an attach sheet (Photo or video with "Send original", File) and a microphone that records a voice note while held (slide left to cancel; AAC in MP4, two minutes). The info sheet holds the conversation's disappearing timer and read-receipt choice (`ThreadSettings`, sealed), the contact actions, the safety-number comparison and Delete conversation. Opening the thread is `ChatPresence.threadOpened`; leaving it, or pausing the app, is `threadClosed` and revokes every open grant of the conversation. The window is `FLAG_SECURE` while the thread shows (a DEBUG launch with `test_allow_screenshots` is exempt).
- *Structure:* everything the screens show is built by pure code in `MessagingV2ThreadModel.kt` (row text, glyph mapping, banners and composer state, file cards, the list with separators), over a `ThreadText` that reads string resources by name (`strings_messaging_v2_ui.xml`), so the JVM tests check the real words. Settings > Privacy gains "Read receipts" (off) and "Download files automatically" (up to 8 MB) when the switch is on (`MessagingV2PrivacyRows`, `MessagingV2Options`).

## Audio-only live (Experimental)

On by default since 2026-10-17 (`AudioLiveFeatureDefaults.ENABLED`; no Settings switch, `test_audio_live=false` is the off arm). A radio is a stream kind of its own, not
video with the camera off. Wire rules: [protocol reference](protocol/README.md) "Experimental audio-only live streams"; reasons and
measurements to take: [decisions](decisions/README.md) "Design AL".

```text
microphone -> AudioLiveEncoder (libopus, MediaCodec fallback) -> AudioLiveBatcher (10 x 20 ms)
  -> AudioLiveSigner (one signature per record) -> BluetoothVideoBroadcaster -> senders (per-child queue)
  -> BMS1/BME1 mesh envelopes -> [relay: copy, shed stale whole records] -> listener
listener: reader verifies the source's signatures -> OpusAudioDecoder (RADIO clock, power-saving AudioTrack)
```

- **Source.** `AudioLiveSource` owns capture and the signer; `BluetoothVideoSender` writes the `BTL1` header and
  signed records and applies the age bound and the withdrawal rules per child; the source caps direct listeners at 4.
- **Listener and relay.** `AndroidVideoReceiver` branches after the header: no video decoder, surface or keyframe;
  a relay admits itself from the preset, joins its child at the next record and sheds stale records with the exact
  mesh-flag predicate. Playout uses the `RADIO` profile (start 600 ms, adaptive to 3 s). Audio focus loss mutes
  playout and keeps the link (a permanent loss leaves after 60 s).
- **Background.** A listener runs under the media-playback foreground service with a media-style notification and
  a framework media session (neutral lock-screen text, no metadata); a broadcaster runs under the microphone type.
  The service stops with the receiver.
- **UI.** `AudioLiveController` and its layouts (Go live choice sheet, broadcast screen, listening screen) are new
  files with small hooks in `MainActivity` marked "AUDIO-LIVE hook".

## In-app updates

The GitHub flavour uses `FlavourUpdater`, `UpdateEngine`, `UpdateHttp` and `UpdateActivity`. Checks require explicit
consent and a project-signed installed copy. Downloads are private, hash-bound and opt-in; Android asks the user to
approve installation. F-Droid and Play use their distribution channels. A valid signing-key rotation must prove its
lineage; an unrelated signer is always refused. Version checks never authorize a downgrade.

### Update edge cases

The table separates the code before this audit from the hardened path. ?Retained? means the existing protection
was correct in code, not that a phone has passed it. Android and OEM integration still needs the checks below.

| Case | Expected user-visible behaviour | Before this audit (code) | Hardened behaviour |
| --- | --- | --- | --- |
| No internet / captive portal | Explain that internet is unavailable; retry without losing the installed app | Requires INTERNET + VALIDATED connectivity; shows offline | Retained; no captive-portal credentials or HTTP downgrade |
| DNS / connect / TLS failure | Offline message; no crash or incomplete install | Connection failures normalized as TransportException | Retained; failed checks retain the retry schedule |
| Timeout while reading JSON/APK; very slow trickle | Exit with a retryable message; cancel remains safe | Body IOException could escape check worker or appear as storage failure; read timeout alone allowed endless trickle | Body errors use the network path; 15 s connect / 30 s idle-read limits retained, 60 s metadata-body / 10 min APK-body budget added; an in-flight read may take its idle timeout to notice cancel |
| GitHub API 403 / 429 | Explain wait; taps must respect server backoff | Manual checks ignored notBefore; Download metadata bypassed stored backoff; generic server message after check | Manual checks and download metadata honor/persist reset; missing rate headers conservatively wait an hour (a 403 explicitly reporting quota remaining remains a server error); numeric Retry-After/reset bounded to a day |
| Malformed / missing JSON, HTML portal response | No update claim or install; readable server failure | StrictJson, required tag/assets and safe URL rules reject it | Retained |
| No APK / multiple APKs | Explain no compatible file; choose the declared ABI when several ABI assets exist | Selects exact version/ABI filename; first duplicate filename won | Exact ABI selection retained; duplicate matching APK or checksum asset names refuse selection; conflicting checksum lines refuse verification |
| Newer / same / older versions | Offer only newer; never reinstall or downgrade | Semver offer plus strictly higher APK versionCode and release number | Retained |
| Pre-release tags | Respect the Latest channel; compare beta tags correctly | Rejects GitHub prerelease=true/draft=true; accepts a beta tag published as Latest; semver orders beta below stable | Latest channel retained; large numeric prerelease parts now compare without Int overflow, leading-zero numeric tags are rejected; no automatic switch to the prerelease feed |
| Signature differs / invalid or unreadable APK | Refuse before Android confirmation; retain installed app/data | Installed signer or verified rotation required; online download also requires pin | Retained; Android performs final APK/signature validation too |
| Corrupt / truncated / wrong SHA-256 | Discard candidate; explain failure; never commit it | Release size/hash, bounded copy and installer-session hash checked; truncated response called offline | Retained refusal; input I/O no longer confused with disk errors; rename-failure partial files removed |
| Wrong package | Explain different app; refuse | Checks exact installed package name | Retained |
| Wrong ABI / too-high minSdk | Explain incompatibility before installer | ABI checked only through release versionCode digit; minSdk left to Android | Also inspect native-library ABI directories and archive minSdk; specific Android-version refusal; Android remains final compatibility authority |
| Disk full (download / session write) | Storage message; no committed partial session | I/O failure deletes download partial or abandons installer session | Retained; network-read errors now remain distinct |
| User cancels download / file picker / installer | Stop safely; no silent install; retry possible | Picker no-op; download cancel flag; installer abort ignored as user choice | Retained; file-operation reservation released on every failure/cancel; a Cancel tap while the verified result is queued discards its uniquely owned candidate; no claim of instant interruption of a blocking read |
| Killed mid-download, then relaunched | Restart download or choose file again; never trust a partial as Ready | Private .part; process-start sweep discards leftovers; prepared UI state not restored | Retained restart policy; cleanup cannot sweep an active file operation; no background download or resume promise |
| Unknown-app install permission denied, then granted | Explain per-app permission; open Settings; require a fresh Install tap | canRequestPackageInstalls + Settings intent; returning renders verified file when activity survives | Retained; if Android recreates the activity, choose/download again rather than restoring unverified Ready state |
| Android 10 vs 14+ vs 16, Samsung / HyperOS | Platform confirmation, cancellation and failure; no silent replacement | API 29 archive signature flags; API 31+ USER_ACTION_REQUIRED; API 33+ package source; mutable explicit callback; receiver attempted background activity launch | API branches retained; confirmation queued for the resumed update screen because a receiver launch can be silently blocked; callback session ID checked; no OEM-specific bypass |
| Update during call / live stream / relay | Keep session running; ask to finish it first | No engine/screen install guard; update could replace active process | Entry/download/install gated by the existing media-owner check; download cancels if media starts; precommit recheck; automatic check defers; queued confirmation stays closed while media is active |
| Repeated taps / another update screen | One writer and one pending installation; no abandoned first confirmation | Local busy flag only; reset after commit; next install abandoned all sessions | Process-wide file-operation reservation; pending installer session blocks repeat; screen stays busy until terminal callback; stale session callbacks ignored |
| Daily job / battery | Opt-in, infrequent, network/battery-constrained, no startup crash | GitHub manifest already has ACCESS_NETWORK_STATE; schedule exception contained; daily job + battery-not-low; no download | Retained Android 14 fix; RESULT_FAILURE now also treated as refusal; short metadata-only work, no notification or wake lock |
| Notifications denied | Manual update and confirmation still work | No update notifications required; home notice uses stored tag | Retained; foreground update screen handles confirmation independently of notification permission |

[Android's job constraints](https://developer.android.com/about/versions/14/behavior-changes-14#jobscheduler)
require network-state permission for network-constrained jobs. The
[PackageInstaller Session contract](https://developer.android.com/reference/android/content/pm/PackageInstaller.Session#commit(android.content.IntentSender))
requires a mutable status PendingIntent for targets 35+ and reports pending user action. Version precedence follows the [SemVer rules](https://semver.org/#spec-item-11). These contracts do not prove
Samsung or HyperOS UI behaviour. Once the user is already in Android's confirmation UI, MeshLive cannot revoke that
UI atomically against an arriving call; verify the incoming-call race on phones, and finish the call before approving.

## Offline app sharing

MeshLive can hand its own APK to a nearby phone when there is no internet (a pattern also used by Briar and
Bitchat, re-implemented from a description, with no code copied).

- **Share sheet.** `AppShare` copies the installed base APK to a private cache directory and offers it through the
  Android share sheet by a non-exported FileProvider scoped to that one directory. The share screen shows the APK's
  SHA-256 and the signing-certificate SHA-256, so the recipient can compare it with the release page. Split
  installs and builds signed with the Android debug key are refused. Copies are deleted after 30 minutes. Stock
  Android Bluetooth refuses to send or accept `.apk` files, so Quick Share/Nearby Share, a file manager or an
  OEM Bluetooth stack are the usual routes.
- **Share app nearby (Experimental).** A Wi-Fi Direct group with a random name and passphrase serves a read-only HTTP
  page and the APK under a random 20-character path, shown as two QR codes (Wi-Fi join and URL). It stops after ten
  minutes, when the screen closes, or when any stream or call exists.

### Nearby-sharing edge cases

The receiver joins the displayed Wi-Fi network and opens the token URL with an ordinary browser. Wi-Fi and URL QR
codes are connection instructions; the page and sender screen show the APK hash and signing-certificate fingerprint.
Those values must be compared with a trusted release source, because the sender controls its page.

| Case | Expected user-visible behaviour | Before this audit (code) | Hardened behaviour |
| --- | --- | --- | --- |
| Android 10/11 permissions | Explain/request fine location for Wi-Fi Direct | permissionsFor(29/30) asks fine location | Retained |
| Android 12/12L permissions | Ask fine + coarse together; approximate-only is insufficient | Both requested; fine checked | Retained; permission callback rechecks actual grants, including empty/stale result maps |
| Android 13+ permissions | Request Nearby Wi-Fi devices without requiring location mode | NEARBY_WIFI_DEVICES with neverForLocation; location-mode check only below 33 | Retained; no extra location requirement added |
| Permission denied | Explain denial; close without a group/server | Denial message and Close | Retained; grant in Settings and reopen sharing |
| Wi-Fi off | Explain; offer Wi-Fi Settings; recheck on return | Checks WifiManager and uses Settings panel | Retained; no programmatic toggle |
| Location off on Android 10-12 | Explain; offer location Settings; recheck on return | LocationManager check below 33 | Retained |
| Unsupported Wi-Fi Direct / hotspot restrictions | Explain unsupported; no server or forced hotspot toggle | Feature/manager check; P2P_UNSUPPORTED gives up | Retained; failed channel initialization now exits clearly; tethering support is not assumed to imply P2P support |
| Busy / another app owns P2P | Leave other app's network intact; explain busy | Unconditionally removed the existing group before starting | Query and refuse an existing group; never remove it; bounded BUSY retry/fallback remains for createGroup failures |
| Group creation fails / no callback / channel lost | Bounded retries, then clear failure and release our resources | 5 GHz -> auto (always a temporary group with our own name and passphrase; the persistent no-config createGroup is never called); exponential BUSY retries, 20 group-info probes; no overall setup deadline | Retained bounded fallback; 60 s setup watchdog also checks for a new media session; channel failures terminate |
| Server port in use / bind fails | Fail clearly and remove only our group | Requests ephemeral port 0; server_failed path closes group; failed bind leaked the unbound socket | Ephemeral allocation retained; failed bind closes socket; fixed-port conflict regression uses a real occupied loopback listener |
| Receiver on iPhone/iPad | Clear Android-only explanation; never suggest APK installation on iOS | Page only said how to install on Android | Page explicitly says Android only and that iPhone/iPad cannot install this APK |
| Play / split APK install | Never offer base.apk as an installable app | Play removes screen/provider/permission; AppShare.apkInfo refuses splitSourceDirs | Retained; same refusal for any non-Play split install; use a single-file release |
| Verify shared file/signature/version | Show exact version/code, APK SHA-256, certificate SHA-256 and trust warning | Page escaped values and displayed all verification data; Wi-Fi/URL QR codes | Retained; hash text is not an independent attestation; downloaded file must match the shown hash |
| Rotation / background / screen off mid-transfer | State clear lifetime; close transfer/group rather than leave hidden sharing | Portrait manifest; configuration recreation destroys old screen and stops it; background intentionally stops; late create success after Stop could leak group | Foreground-only policy retained; rotation/recreation interrupts and requires reopening/reconnecting; late success now removes its own group before channel close; no background service or transfer-resume promise across a new token |
| Stop / Back / timeout | Close listener and active HTTP sockets; remove owned group; no stale callback restart | Server close and group removal, but create callback after Stop was ignored; accept/close race | Tested creation/stop state handles late success/failure; server accept/close synchronized; idempotent cleanup; OS group-removal refusal is logged and asks the user to check Wi-Fi Settings; actual radio cleanup remains a real-phone check |
| Call / stream / relay active or starts during setup | Refuse/stop sharing; preserve media | Gate at entry and once sharing ticked; gap while hashing/creating group | Recheck before group creation and during setup; existing sharing tick stops on media; failing owner check now blocks conservatively |
| Battery / abandoned setup | No hidden long-running server or indefinite screen wake | Ten-minute sharing timer, keep-screen-on while visible; no setup deadline | Retained ten-minute lifetime, added bounded setup; leaving screen clears wake flag and closes resources; battery cost remains unmeasured |

Permission branches follow Android's [Nearby Wi-Fi permission guide](https://developer.android.com/develop/connectivity/wifi/wifi-permissions)
and [Wi-Fi Direct guidance](https://developer.android.com/develop/connectivity/wifi/wifi-direct). There is no assumption
that an OEM implements every P2P operation successfully. If the system never returns a create callback, the screen
stops at the deadline but cleanup of a possible late system group cannot be proven until that callback or a phone
inspection. Reopen only after the previous group is gone; an existing group is deliberately not taken over.

## Device-family policy registry

Phones differ in SoC, Bluetooth stack version, vendor skin and codec components, and many "it works on my phone"
problems are stack bugs. MeshLive keeps every phone-family specific decision in one registry
(`DevicePolicyRegistry.entries`, built from `DevicePolicyRegistry.kt` plus the per-area files `CodecPolicy`,
`YuvPolicy`, `BluetoothTransportPolicy`, `BackgroundPolicy`, `MemoryThermalPolicy`, `NativeCompat`, `ScanPolicy`,
`ServiceFamilyPolicy`, `AudioPolicyEntries`, `CocPolicy`, `CameraPolicy`, `AdvertisingPolicy` and `PhyLinkPolicy`). Code that
used to test `Build` fields asks the resolved policy for an effect instead.

Each entry has:

- **Matcher.** A declarative rule on API level, `Build.HARDWARE`/board, SoC family, SoC model, manufacturer, OEM skin
  family, Bluetooth stack lineage (platform `system/bt` before Android 13, the Mainline Bluetooth module from 13),
  controller features (2M PHY, Coded PHY, extended advertising) or a codec component name. Matchers never use
  `Build.MODEL`: a family is a platform property, not a product name.
- **Typed effects.** What changes: a call video cap, deferred GATT close, a preferred decoder, a longer queue and
  so on.
- **Behaviour in plain words,** official **sources** (AOSP source and commits, Android CDD and API reference, vendor
  developer documents, Bluetooth SIG specifications), **evidence** from our own test runs, a **confidence**
  (high, medium, low) with a note on what is still unproven, and a review date.
- **Validation status.** `UPSTREAM_DOCUMENTED` (an upstream source documents the behaviour but no local phone has
  reproduced it; never counted as a pass), `LOCALLY_REPRODUCED`, or `EXPERIMENTAL`.
- **A fallback and an off switch.** In debug builds `test_device_policy_off=<id>[,<id>]` or `all` turns entries off
  for an A/B comparison; release builds ignore it.
- **Proposals.** Sourced but unimplemented entries are carried as *inert proposals*: the resolver only reports which
  proposals would match the phone, so test runs collect evidence before anything is promoted. At the 2026-10-10
  revision the registry had 75 entries (55 active, 20 proposals).

**Matchers, areas and service types added in 2026-10-14.** A matcher can now also name a security-patch window (so a
stack bug fixed in a monthly patch stops matching), one of five Bluetooth-stack eras (Android 10-11 legacy stack, Android 12 legacy stack with
optional new modules, Android 13 new core, Android 14 flag-heavy, Android 15+ aconfig flags) and *runtime traits* read once at start by
`DeviceTraitsProbe` (ROM version properties, supported ABIs, page size, memory, low-RAM flag, Bluetooth package
versions, audio features, camera hardware level on first use). A trait that could not be read is "unknown" and never
satisfies a constraint. Entries belong to a `PolicyArea` (codec, YUV, Bluetooth transport, graphics, background,
memory and thermal, native, camera, audio). Each area file exposes `entries` and is listed once in the aggregate;
three areas fold a sub-list into their own `entries` instead (GATT link entries inside the transport area, decoder entries inside
the codec area, phone-maker entries inside the background area, and the thermal family inside memory and thermal), so
no id is counted twice. The foreground service decides its types in one place (`ServiceTypePolicy`): camera and
microphone only for a session that uses them, `connectedDevice` for the Bluetooth session, `mediaPlayback` for audio-only
live, and the optional `location` type only on Android 10-11 and only while Location is on; a refusal drops the optional
type first and is reported as requested versus held (`fgs_start`). Every area has its own switches object
(`XxxSwitches.applyLaunchExtras`), parsed once in the launch code; all non-default arms are DEBUG-only and listed in
[switches.md](switches.md).

Two rules keep this honest. Acceptance gates are identical for every phone and never depend on a policy entry. And
a phone-specific protection is a visible limit, never a silent change to a requested bitrate, FPS, resolution,
encoder, transport or buffer. `python tools/device_policy_report.py` prints the registry with its sources and
gaps, and `python tools/parse_user_diagnostics.py report.txt` turns a user's exported diagnostics block into a
draft evidence suggestion for a human to check (it never edits the registry, and a user report never raises
confidence by itself).

## Background, lifecycle and power

- Typed foreground services (camera, microphone, connected device) protect ordinary app switching and screen-off. They
  do not recreate a session after the process is killed.
- `BackgroundReliability` reports Android's documented restriction states (battery optimisation, background
  restriction, standby bucket, power-save) and phone-maker guidance with settings links, and builds the Settings
  "Connection reliability" card. It adds no keep-alive hacks. The one system action it offers is Android's own battery
  exemption dialog, in onboarding, in Settings and when an always-on role is switched on (design decision Q38); the
  process-exit history, a phone-update stamp and the scan watchdog's suspect feed the card.
- `DozeWatchdog` observes Doze, light Doze and Low Power Standby and shows "Phone is in deep sleep; calls may be
  delayed." It changes no behaviour.
- Thermal limits reduce FPS and rate at source on Android's thermal status; a critical status stops streaming.
  Calls are never refused for heat: at a critical status a phone still places and answers voice calls, a video call
  placed or answered there goes on as voice (the callee answers with `ACCEPT, CAMERA_OFF`), a running call's camera
  turns off through the camera-off path and turning it back on is refused with the heat text (DECISIONS "Calls at
  critical heat").
  Requested and effective settings stay visible.
- FIFO audio, immutable GATT buffers, codec-configuration and keyframe recovery, and capture-age-preserving
  timestamp conversion are compatibility invariants covered by focused regression tests.

## Diagnostics

- **Log lines.** The app writes `BENCHMARK <topic>: key=value | ...` lines that the host scorers parse (decoder
  requested versus effective, link PHY requested versus effective, call setup, admission decisions, pacing and so
  on). Release builds strip verbose and info logs (R8) and keep warnings and errors; the device policy and
  doze-state lines (they carry the build fingerprint) are written by debug builds only.
- **Debug launch extras.** Benchmark behaviour is selected with `test_*` launch extras and benchmark tunables
  (the bracketed names in the register). They exist only in debug launches; normal sessions never read them.
- **Export diagnostics.** About > Export diagnostics copies one bounded text block (never media, messages, contact
  or peer names, addresses or full keys) for GitHub issue forms. The exact contract is under "Diagnostics block" below.

## The desktop receiver and file sender

`receiver/` is a Windows C# receiver (Windows Bluetooth RFCOMM) that authenticates a phone, writes the stream to an
`.ivf` capture and can play it. It implements the older video-only `BTV2` path, so current `BTV3` audio and normal
discovery integration are unfinished; it is a development tool, not a finished desktop client. `shared/` holds the
C# implementation of the same handshakes and records, with an offline self-test
(`dotnet run --project receiver/BluetoothVideoReceiver.csproj -- --self-test`). `tools/BluetoothVideoFileSender/`
sends a prepared file to a phone for repeatable input.

## Host tools and the benchmark harness

`tools/` holds the Python harness that plans, runs and scores phone tests (inventory, case state, runner, scorers, release and icon scripts). It never touches a phone unless a run is explicitly started. Scope, acceptance gates, commands and result locations are in [testing.md](testing.md).

## Distribution and releases

For maintainers and packagers. Users: see [Install, update and verify](../user-guide.md#install-update-and-verify).

- **Channels.** GitHub Releases (primary, signed APKs), F-Droid and IzzyOnDroid, and Google Play (the `play` flavour's
  app bundle; being prepared, not yet published). Draft F-Droid
  metadata: `distribution/fdroid/<applicationId>.yml`; IzzyOnDroid checklist: `distribution/izzyondroid-notes.txt`;
  store text and changelogs: `fastlane/metadata/android/en-US/` (`changelogs/<versionCode>.txt` is required for each
  published APK code, and `make_release.py` refuses a release without them; `images/phoneScreenshots/` is empty until
  real screenshots exist).
- **ABI split.** Release builds use `-PsplitAbis=true -PenableArmeabiV7a=true` (set by `make_release.py`; default
  off, so debug and benchmark builds stay one arm64 APK). Outputs: arm64-v8a, armeabi-v7a and optionally universal.
  `versionCode` in `app/build.gradle.kts` is the base; each APK's code is `base * 10 + digit` (universal 0,
  armeabi-v7a 1, arm64-v8a 2: the Signal/F-Droid convention, so a device that supports both prefers 64-bit).
  Example: base 1 gives 10, 11, 12. `make_release.py` cross-checks Gradle's `output-metadata.json`, each APK's native
  ABIs, the 16 KB page-size check for 64-bit libraries (`tools/check_apk_16kb.py`) and that every APK has exactly the
  expected single signer.
- **Flavours.** `github` is the release-page build (`tools/make_release.py`, `:app:assembleGithubRelease`);
  only it contains the opt-in updater and `REQUEST_INSTALL_PACKAGES`. `fdroid` is the F-Droid recipe's
  build (`gradle: [fdroid]`). `play` is the Google Play bundle (`:app:bundlePlayRelease`; add
  `-PenableArmeabiV7a=true` for 32-bit ARM), with no updater or install permission. All three offer
  **Share app nearby** (`BuildConfig.APP_SHARE_AVAILABLE=true`) with the same feature control, screen,
  file provider and `NEARBY_WIFI_DEVICES` permission. Outbound sharing does not update the sending phone;
  split installs still refuse because a base APK alone is incomplete. Play omits the `location` FGS type
  and permission (`LOCATION_FGS_AVAILABLE=false`) pending a supported justification for old Android
  background Bluetooth scanning. This can reduce screen-off discovery reliability on Android 10-11;
  Play's published FGS examples are non-exhaustive, not an exclusive list of allowed uses.
  All flavours carry the newer-version-nearby notice, but Play's notice points only to Play updates.
  Play release versionCode is base * 10; coordinate it with the per-ABI GitHub codes before switching.
  `github`/local `fdroid` use the release key, while `play` signs its bundle with a separate upload key
  (`-PplayUploadKeystoreProperties=<file>`, default `~/.meshlive-play-upload/keystore.properties`) and is
  unsigned without one. The installed Play signer is selected in Play App Signing, not by the upload
  key: using the existing project key permits compatible cross-store updates; a distinct key prevents
  them. F-Droid normally signs its repository build unless reproducible developer-signed publication is
  configured. Same package, compatible signer/lineage, versionCode and configuration remain necessary.
  No flavour embeds a build time; `tools/check_reproducible_build.py` defaults to F-Droid.
- **Deterministic version.** `versionName`/`versionCode` are literals (no date or git hash). The APK carries no Google dependency-info block (`dependenciesInfo` off). **Reproducible builds.** `python -B tools/check_reproducible_build.py build --tree-a DIR_A --tree-b DIR_B -- -PsplitAbis=true -PenableArmeabiV7a=true -PuniversalApk=false` builds the unsigned release (release key never read; build cache off; sequential; refuses to start when memory use is high) in two clean checkouts at different paths and compares every APK entry; `compare A.apk B.apk` compares two existing APKs ignoring the META-INF signature files, as F-Droid does. Result 2026-10-11 (AGP 8.13.2, Gradle 8.13, JDK 21, Windows): both ABIs bit-identical in two checkouts at different paths. The first run differed only in `libbluetooth_video_native.so`, because libopus `__FILE__`/assert strings embedded the checkout path; `app/src/main/cpp/CMakeLists.txt` now maps the libopus, libaom, app-source and build directories with `-ffile-prefix-map`. Result 2026-10-28 (AGP 9.4.1, Gradle 9.6.0, Windows): an unsigned rebuild of the public `v0.4.1-beta` tag in a fresh clone against the signed 0.4.1-beta APKs had identical native libraries, resources, zip order and zip metadata, and `apksigcopier compare` still failed for three reasons: the release was built from the private tree, whose Kotlin string constants the public export rewrites (`classes.dex`); `BuildConfig.VERSION_CODE` in `classes.dex` is the code of the variant's main output (the universal APK when it is built, otherwise armeabi-v7a), so the gradle properties must be equal; and AGP 9 wrote `META-INF/version-control-info.textproto` (the commit in a git clone, `NO_VALID_GIT_FOUND` in a worktree), now off with `vcsInfo.include = false` in the release build type. The F-Droid recipe therefore names separate fdroid-flavour assets (`MeshLive-<version>-fdroid-<abi>.apk`) in per-ABI `binary` lines with `AllowedAPKSigningKeys`; they must be built from the public tag with the recipe's gradle properties. Not yet verified: a Linux build (the F-Droid buildserver, Linux NDK) against a Windows build.
- **Making a release.** `python tools/make_release.py` (clean tree, signing key present, fingerprint must match) writes `release/<versionName>-<versionCode>/`: the signed APKs, `SHA256SUMS`, `signing-cert.sha256`, `apksigner-certs.txt`, `SOURCE-MANIFEST.txt` and a `RELEASE-NOTES.md` template to finish by hand. Nothing is uploaded. The checklist is in [testing.md](testing.md) under "Release checklist".
- **Application ID.** The app name is MeshLive and the application ID is `io.github.meshlive.app` (the draft
  F-Droid metadata is `distribution/fdroid/io.github.meshlive.app.yml`). The benchmark harness uses the same ID;
  set `MESHLIVE_APP_ID` to drive a build with another ID. The Kotlin package, `namespace`, proguard rules and the
  FileProvider authority (`${applicationId}`) need no change; `-PappId=<id>` builds a trial APK with another ID. A
  new ID is a different app to Android: installs of the old ID are not updated, and the signing key stays the same.
  For a later name change edit `app_name` in `app/src/main/res/values/strings.xml`, `RELEASE_BASENAME` in
  `tools/make_release.py` and `fastlane/metadata/android/en-US/title.txt`.
- **Native sources for a clean checkout.** libaom, libvpx and libopus are not in Git: `python tools/fetch_native_sources.py`
  downloads the pinned versions (libvpx's generated build configuration is in Git, `tools/generate_libvpx_config.py`) (see the README, "Build from source"). Needs NDK 28.2.13676358, CMake
  3.22.1, JDK 21 and Perl.
- **Dependency licences.** [THIRD_PARTY_NOTICES.md](../../THIRD_PARTY_NOTICES.md), "Runtime dependency licences". All
  Apache-2.0 except libaom (BSD-2-Clause plus the AOM patent licence), libvpx (BSD-3-Clause plus the WebM patent
  grant) and libopus (BSD-3-Clause); nothing proprietary.
- **Open items before an F-Droid request.** Attach fdroid-flavour signed APKs built from the public tag to the release (or drop the recipe's `binary` and `AllowedAPKSigningKeys` lines so F-Droid signs its own build), and run the recipe through `fdroid build` on Linux (the fdroiddata merge request's CI does this). `fdroid readmeta`, `lint`, `rewritemeta` and the source scanner pass with fdroidserver 2.4.5; the srclib `distribution/fdroid/srclibs/libaom.yml` goes to fdroiddata too (fdroiddata already has `libvpx` and `opus` srclibs).

### Diagnostics block (contract for "Export diagnostics")

The About screen's Export diagnostics button copies one text block that users paste into the GitHub issue forms;
`python tools/parse_user_diagnostics.py report.txt` turns it into a DeviceFamilyPolicy evidence suggestion (a draft
for a human to check against official sources; it never edits the registry and a user report never raises
confidence by itself). The parser is an allowlist and rejects any other field.

Never exported: media, message content, contact or peer names, Bluetooth or Wi-Fi addresses, full identity or session
keys, account or location data. Identity is only a non-reversible short hash: the first 6 bytes (12 lowercase hex) of
SHA-256 over `"bluetoothvideo-diag-v1:"` + the phone's own public node key; each peer is the same hash of that peer's
public node key.

Layout: a header line `=== BLUETOOTH-VIDEO DIAGNOSTICS v1 ===`, the lines below in any order, then
`=== END (lines=N) ===` where N counts the lines between (detects truncated pastes). At most 16 KB.

| Line | Content |
| --- | --- |
| `app.version`, `app.build`, `app.build_type` | versionName, versionCode, `release` or `debug` |
| `app.signing_sha256` | SHA-256 of the installed APK's signing certificate, colon-separated uppercase (`PackageManager` signing info) |
| `device.manufacturer`, `device.model`, `device.soc` | `Build.MANUFACTURER`, `Build.MODEL`, SoC manufacturer + model (`Build.SOC_*`, API 31+, else `unknown`) |
| `device.android`, `device.sdk`, `device.security_patch` | release string, API level, `Build.VERSION.SECURITY_PATCH` |
| `bluetooth.le_2m`, `le_coded`, `le_extended_advertising`, `le_coc` | `true`/`false` from `BluetoothAdapter` (`isLe2MPhySupported`, `isLeCodedPhySupported`, `isLeExtendedAdvertisingSupported`) and API level 29+ for CoC |
| `bluetooth.le_max_adv_data_length` | optional, `getLeMaximumAdvertisingDataLength` |
| `identity.short_hash` | the phone's own short hash (above) |
| `device_policy` | `matched=id1,id2 \| registry=N`: ids of DeviceFamilyPolicy entries that match this phone (`matched=none` if none) and the registry size |
| `conn` (up to 50, newest last) | ` \| `-separated `key=value`: `t` (UTC ISO-8601), `transport` (`gatt`/`coc`/`classic`), `role` (`central`/`peripheral`), `peer` (short hash), `status` (stack status code or `ok`), `phy_req`, `phy_obs` (`1M`/`2M`/`coded`), `mtu_req`, `mtu_obs`, `interval_req_ms`, `interval_obs_ms`, `outcome` (`connected`/`failed`/`timeout`/`disconnected`), `reason` (short token) |
| `adaptive_phy` (up to 50) | `t`, `link`, `from`, `to`, `reason` (as the `BENCHMARK adaptive_phy` line), `kbps`, `blocked` |
| `codec` (up to 20) | `t`, `role` (`tx`/`rx`), `mime`, `component` (codec name), `hw` (`true`/`false`), `req` and `eff` (requested and effective `WxH@fps/kbps`) |

Example start: `app.version: 1.0`, `app.build: 1`, `device.model: SM-S901B`, `device_policy: matched=none | registry=4`,
`conn: t=2026-10-10T10:00:00Z | transport=coc | role=central | peer=abcdef012345 | status=42 | phy_req=2M | phy_obs=1M | outcome=failed | reason=phy_unavailable`.

### Build notes

Extra notes for the build steps in [building.md](building.md).

- **Flavours.** Three distribution flavours (`app/build.gradle.kts` `productFlavors`): `github` (what the release page ships, with the opt-in updater and `REQUEST_INSTALL_PACKAGES`), `fdroid` (what the F-Droid recipe builds; no updater, no install permission) and `play` (Google Play; no updater, no install permission, no location service type; see "Distribution and releases"). Name the flavour in build tasks: `:app:assembleGithubDebug` (APK `app/build/outputs/apk/github/debug/app-github-debug.apk`, the one `tools/deploy_build.py` installs), `:app:assembleGithubRelease`, `:app:assembleFdroidRelease`, `:app:bundlePlayRelease`. `:app:testDebugUnitTest` is an alias (AGP defines none once flavours exist): the github flavour's unit tests run once, and the fdroid and play flavours' main and test code must compile; one class: `:app:testGithubDebugUnitTest --tests <class>`; the play flavour's own tests: `:app:testPlayDebugUnitTest`. The plain `assembleDebug`/`assembleRelease` aggregates would compile the native libraries three times, so avoid them. `:app:processGithubReleaseManifest :app:processFdroidReleaseManifest :app:processPlayReleaseManifest` followed by `python -B -m pytest tools/tests/test_flavour_manifests.py` checks the merged manifests.
- **Application ID.** `io.github.meshlive.app`. The Kotlin package and `namespace` stay `com.example.bluetoothtest`,
  so launch components are `io.github.meshlive.app/com.example.bluetoothtest.MainActivity`. See
  "Distribution and releases" above.
- **Pinned toolchain.** The Gradle wrapper checks `distributionSha256Sum` against every Gradle download.
  `gradle/verification-metadata.xml` holds the SHA-256 of every plugin and dependency artifact and Gradle fails a build
  whose download differs. `buildscript-gradle.lockfile` (plugins) and `app/gradle.lockfile` (every `:app`
  configuration) lock resolved versions. To change a version, update them together: (1) for Gradle, change
  `distributionUrl` and set `distributionSha256Sum` to the official `.sha256` for that distribution; (2) for
  dependencies, edit `gradle/libs.versions.toml` and run
  `./gradlew --no-configuration-cache --write-locks --write-verification-metadata sha256 help :app:assembleGithubDebug :app:assembleFdroidDebug :app:assembleGithubRelease :app:assembleFdroidRelease :app:testDebugUnitTest`
  (add any other task you run, because tools such as AAPT2 and lint are resolved only when their task runs; a new
  Android Gradle Plugin needs the command on each OS or the published checksums added by hand); (3) review the lockfile
  and verification diff and confirm an `--offline` build.
- **Release logging.** `proguard-release-logs.pro` applies to the minified release build only: R8 removes
  `Log.v/d/i`, so release logcat keeps warnings and errors, and no device-identifying line. Debug builds keep every
  line, including `BENCHMARK` lines.
- **Desktop receiver.** The Windows C# receiver implements the older video-only `BTV2` path; current `BTV3` audio and
  normal discovery integration are unfinished. Its offline cryptographic self-test is
  `dotnet run --project receiver/BluetoothVideoReceiver.csproj -- --self-test`.
- **Test-video generator.** `tools/video-stress-generator/` (Python package `videostress`; run
  `python -m videostress --help` from that folder, or set `VIDEO_STRESS_GENERATOR` to point elsewhere).
- **Benchmarks.** Copy `tools/benchmark_devices.example.json` to `tools/benchmark_devices.json` (git-ignored) and fill
  in your own phones' adb serials and aliases; `tools/benchmark_scope.json` lists the reference aliases. See
  [testing.md](testing.md).

## Play battery settings

The Play distribution has no in-app updater or package-install permission and removes the direct battery-exemption
permission. Its FlavourBattery opens Android's battery optimisation list, with the explanation "Find MeshLive and
choose Don't optimise". GitHub and F-Droid retain their direct exemption prompt and list fallback.

## Local reports

Report uses the existing local block path, then remembers the reported fingerprint and, when watching, the logical
stream token in the wiped app preferences. It hides the person's chats, message notifications, Recent entries and
attributable live offers on this phone. Unblocking does not clear a report; Delete everything clears both.
Reporting sends nothing. `report_url` is an empty string resource; a configured HTTPS form appears only as an
optional action after local success. An unanswered one-time caller has no stable identity to block; the existing
Block unknown callers explanation applies. Relayed video may expose only a source node ID, without a full
fingerprint: individual blocking then requires a previously authenticated or saved source identity.

## Switches and experiments

Behaviour that is not the plain default falls into four kinds: experiments that stay off until their phone tests pass, the losing arm of an A/B comparison kept for later comparison, optional features the user can turn on under Settings > Features, and DEBUG-only tunables used by the benchmark harness. The rule behind this (design rule, 2026-10-08/09): an experiment, or the losing arm of an A/B comparison, is kept, off by default and labelled Experimental, unless it is impossible by first principles. A "control arm" switch restores the previous behaviour exactly, so an A/B can run in either direction.

How defaults work. Each promoted feature has one default constant (`CallFeatureDefaults`, `ConnectivityFeatureDefaults`, `ViewerFeatures`, `UpdateFeatureDefaults`; privacy features are on by default for user sessions since 0.2.0-beta) and, where it still has one, a switch in Settings (voice calls, ringback, camera pause at the ear, lock-screen calls, call notifications, names and titles and audio-only live have none since 2026-10-17). `FeatureCatalog` (pure Kotlin) holds each feature's id, preference key, default, benchmark extra and texts, and the Settings card is generated from it; `AppFeatures.registerAll` registers every spec, at process start and before each refresh, and one `features:` line logs the snapshot. An untouched switch follows the constant and an explicit choice sticks. One resolve rule (`FeatureCatalog.resolve`) serves every feature; the exceptions are options stated in the spec: a constant (no preference key, no Settings row), dependencies (`dependsOn`: the row greys out while one is off, for example Find PCs and stranger pseudonyms under rotating IDs), a benchmark default, aliases of an extra, an extra that decides in any DEBUG launch, and a stored choice that counts only while Manual settings is on (relayed calls).

How benchmark extras work. A DEBUG benchmark launch (any `test_*` extra) reads only its extras, default off, so a case ID names exactly one behaviour no matter what the phone's Settings say. Release builds ignore the extras unless an entry names a Settings switch. Because of that, every release-ON feature runs OFF in a benchmark case unless its extra is passed; the runner's `test_user_launch` variant runs the shipped defaults (TESTING). Benchmark tunables are listed in square brackets in the register; [testing.md](testing.md) says how a case carries them.

The full list, with each switch's default, extra, purpose and measured status, is the register in [switches.md](switches.md). Add a row there whenever a switch is added, promoted or retired.
