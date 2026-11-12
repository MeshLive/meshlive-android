# Design HC: HEVC (H.265) and hardware H.264 video codecs (approved 2026-10-27; revision 3 after reviews 1 and 2, ready for implementation)

**Design decision (2026-10-27).** Add HEVC and rank it above VP9. Hardware H.264 (AVC) comes in the same design as the last hardware rung, which Q39 already allowed, because its plumbing is almost the same: Annex B, parameter sets, no B-frames.

Automatic order: hardware AV1, then hardware HEVC, then hardware VP9, then hardware H.264, then today's software choices.

This revises Q39, which ranked HEVC below VP9 and asked for a long A/B first. The review judged a smoke test and one thermal comparison enough.

Wire rules: PROTOCOL "Draft: HEVC and H.264 video codecs". Numbers: PROTOCOL "Draft number registry". The H.264 numbers are the ones pencilled in "iPhone interoperability". Nothing is built. With both master switches off, no HEVC or H.264 bit is set anywhere and every byte is today's.

**Why.** Four of the seven test phones (S22, Xiaomi, ASUS, A15s; Qualcomm and MediaTek) have no hardware VP9 or AV1 encoder. Today they encode in software, which heats them during long calls and streams:
- Streams and calls use software AV1.
- ASUS calls use software VP9, because of its family protection `qcom-msmnile-call`.

The codec census (2026-10-27) measured three phones:

| Phone | Hardware encoders | HEVC hardware encoder |
| --- | --- | --- |
| ASUS (Snapdragon msmnile, Android 11) | HEVC and H.264; no VP9 or AV1 | `OMX.qcom.video.encoder.hevc`: Main, CBR and VBR, 183-194 FPS at 720p |
| M31 (Exynos 9611, Android 12) | HEVC and VP9 | `OMX.Exynos.HEVC.Encoder`: Main, 100-180 FPS at 720p, declares no bitrate modes (like its VP9 encoder) |
| A15s (MT6765, Android 10) | H.264 only (`OMX.MTK.VIDEO.ENCODER.AVC`, up to 1080p30) | None |

- All three have hardware HEVC, VP9 and H.264 decoders.
- Android's software `c2.android.hevc.encoder` stops at 512x512 or 960x544, and the ASUS `.hevc.cq` variant is CQ-only up to 512x512. Neither can be used.
- The ASUS's Codec2 H.264 encoder measured 26-56 FPS at 720p, against about 200 for its OMX one. The choice between components of one codec must therefore use measured or achievable frame rates (`getSupportedPerformancePoints`, `getAchievableFrameRatesFor`), not list order.
- The S22 and Xiaomi are not censused yet. They are likely Qualcomm HEVC and H.264 hardware, and must be checked before the smoke test.


HEVC compresses about as well as VP9, or slightly better. H.264 needs noticeably more bits for the same picture.

**Decode coverage without an add-on.** This table drives the stream order (Q-HC1). It is kept as data in code (`StreamCodecCoverage`) and changed only by a reviewed update when a platform's coverage changes.

| Codec | Android (API 29+) | Apple (iOS 16+, macOS 13+) | Windows | Every MeshLive platform without an add-on? |
| --- | --- | --- | --- | --- |
| VP9 | Required (CDD [5.3/H-0-5]) | Bundled libvpx, or VideoToolbox | Built in, through the preinstalled VP9 Video Extensions | Yes |
| H.264 | Required ([5.3/H-0-1]) | VideoToolbox | Built in (not on "N" editions without the Media Feature Pack) | Yes |
| HEVC | Required ([5.3/H-0-2], Android 10 and 15 CDDs checked; CDD 5.3.5 Main level 3 covers 854x480 at 30 FPS) | VideoToolbox (hardware on every supported iPhone, A9 and later) | Only with Microsoft's **paid** HEVC Video Extensions (or a PC maker's preinstalled copy) | No |
| AV1 | Platform decoder | Hardware only on A17 Pro and M3 or later, until Design AD | Only with Microsoft's **free** AV1 Video Extension, until the Windows dav1d fallback ships (Q-AD1 answered A) | No |

**What changes for each mode.**

| Mode | Automatic order (first eligible encoder the session can use) |
| --- | --- |
| Calls (each sender, its own direction) | Hardware AV1, hardware HEVC, hardware VP9, hardware H.264, then today's software order. Only codecs in the peer's end-to-end decode mask count, so both ends must decode. Two receiver preferences apply, in this order: bit 7 (prefer VP9, legacy Qualcomm) puts VP9 first, below only a hardware HEVC encoder the peer decodes (see "Bit 7 and hardware HEVC"); then the new bit 4 ("my AV1 decoding is software") moves AV1 below a hardware HEVC or hardware VP9 encoder the sender has. A sender whose family policy asks for VP9 in calls (`CallVp9Compatibility`, now covering codecs 3, 4 and 5) sends VP9 |
| Streams | Q-HC1 (recommended A): hardware AV1 first (design decision 2026-10-27), then the hardware encoders whose codec is decodable everywhere without an add-on, in efficiency order (VP9), then hardware HEVC, then hardware H.264, then software. When HEVC becomes decodable everywhere without an add-on, the stream order becomes the call order |
| Open broadcast (Design OB) | Unchanged: VP9 or AV1 only |

A stream source still checks no viewer. A viewer that lacks the codec sees the offer dimmed with "cannot decode this stream's codec" and is refused at join, exactly like AV1 today. Exception: on the PC announcement's kind 2 (Design QV), strict readers without HEVC (before Android 0.4.2, or with the master off) hide a `0x80` offer rather than dim it; with the master on, Android 0.4.2 lists it. Old builds read the new bits as "no common codec" (PROTOCOL), so they never try.

**Bit 4, "my AV1 decoding is software"** (end-to-end call hellos only, like bit 7):
- A receiver sets it when every AV1 decoder it would use is software and it also decodes HEVC or VP9. Examples: Android phones without a hardware AV1 decoder, and Apple or Windows devices using dav1d.
- A sender honours it only by choosing its hardware HEVC or hardware VP9 encoder (that the peer decodes) instead of AV1.
- When the sender's alternatives are only software encoders or hardware H.264, it still sends AV1, because software decoding at these sizes is cheap and software encoding is not.
- Bit 7 is tested first. Bit 7 counts only when the peer also sets VP9 bit 0; a preference without the capability is ignored and logged.

**Bit 7 and hardware HEVC (2026-10-28, after the Block 1 smoke).** In the first draft, bit 7 put VP9 first for every sender. In the smoke, no HEVC ever reached the ASUS: the S24 and M31 both sent VP9 to it (`forced` by bit 7), although the ASUS decodes HEVC in hardware and advertised it (decode mask `0x9b`: VP9, AV1, HEVC, bits 4 and 7).
- Why bit 7 exists: the legacy Qualcomm call profile first advertised VP9-only call decoding, and bit 7 replaced that so AV1-only senders could still call. Its evidence is call failures on the legacy Bluetooth stack before pacing, plus the CDD rule that every phone decodes VP9. It names no decoder fault that HEVC would share, and the ASUS decoded AV1 640x480 with 0 freezes in 12 stream cases.
- Rule now: bit 7 still puts VP9 above AV1 (hardware too), H.264 and every software encoder, but a hardware HEVC encoder the peer decodes stays first. A peer without HEVC decode, or a sender without hardware HEVC, gets VP9 as before. The ASUS's own sending profile (`CallVp9Compatibility`: the ASUS sends VP9) is unchanged.
- The log reason for a bit-7 VP9 choice is now `peer_prefers_vp9` (it was `peer_no_av1_decode`). The DEBUG control arm `test_bit7_hw_hevc=false` restores the first draft's order.
- To measure: HEVC into the ASUS (S24 -> ASUS, M31 -> ASUS calls), its decoder hold time and freezes against the VP9 calls of the same smoke.

**Encoder level (2026-10-28).** The Exynos HEVC encoders signalled `general_level_idc` 180 (level 6.0) in the smoke, which the receiver logs as `au_unusual` and accepts (log only, as designed).
- The encoders did not ignore `KEY_LEVEL`: the first rule requested the highest level each encoder lists, and the Exynos and Qualcomm HEVC encoders list Main profile up to High tier 6.
- Level 6 is harmless to decoders that list it (Exynos, Qualcomm), but it overstates the stream to those that stop at 5.x (Android's software HEVC decoder lists 5.2).
- Rule now: the lowest standard level whose limits cover the session's size, frame rate and bitrate, never above the listed maximum. For HEVC that is Main tier level 3 for 640x480 and 854x480, the level the CDD requires every HEVC decoder to support (5.3.5), and 3.1 for 720p. H.264 at 640x480 and 15 FPS gets level 2.2.
- `hc_encoder` logs `level`, `level_idc` and `level_listed`. The DEBUG control arm `test_hc_session_level=false` keeps the listed maximum.
- Bit 4 never moves AV1 below hardware H.264. Decision 2026-10-27: H.264 costs much more airtime on Bluetooth, and software AV1 decoding at 640x480 is cheap.
- Old senders ignore the bit (codec tests are masks), and old receivers never set it.

**Effect on the test phones** (if Q-HC1 is answered A):

| Phone | Calls | Streams |
| --- | --- | --- |
| ASUS | Sends software VP9: the `qcom-msmnile-call` protection now also covers HEVC and H.264. Receives hardware HEVC from senders that have it (bit 7 rule of 2026-10-28) | Software AV1 is replaced by HEVC |
| A15s | Software AV1 is replaced by H.264 | Software AV1 is replaced by H.264 |
| M31 | Hardware VP9 changes to HEVC | Stays VP9 |
| S24, A55 (hardware VP9) | HEVC if they have a hardware HEVC encoder | Stay VP9 |
| S22, Xiaomi | Expected HEVC, after the census | Expected HEVC, after the census |

The way to lift the ASUS protection for HEVC is a later A/B, "msmnile HEVC calls": the same settings and transports as the protection's evidence, with HEVC against VP9.

**Encoder eligibility, from capabilities rather than names.**
- Hardware only (`isHardwareAccelerated`).
- Profile: HEVC Main; for H.264, High, Main or Constrained Baseline, whichever the encoder lists first in that order, always with no B-frames.
- **Decode bits mean the whole wire envelope (review 2 F8).**
  - The H.264 bit means the receiver decodes Constrained Baseline, Main and High (8-bit 4:2:0) at its advertised maximum size and FPS. A decoder that lacks one of them does not advertise H.264.
  - The HEVC bit means Main at that size and FPS.
  - A sender never infers decoder support from its own encoder list.
- The size range covers the session's maximum size, and the measured or achievable frame rate reaches the session's FPS.
- Bitrate modes. `BITRATE_MODE_CQ` is queried explicitly, so CQ-only is not mistaken for "no modes". Manual and DEBUG `test_bitrate_mode` requests stay exact.

  | Encoder declares | Automatic requests | Notes |
  | --- | --- | --- |
  | CBR | CBR | |
  | CBR_FD but not CBR | CBR_FD | |
  | VBR but no CBR or CBR_FD | VBR, with `EncoderBitrateAdjuster` | Today's path for VP9 when CBR is missing. The log records requested CBR and effective VBR |
  | CQ only | Not eligible | This excludes the ASUS `.hevc.cq` |
  | No mode at all (Exynos OMX: the M31's VP9 and HEVC) | No `KEY_BITRATE_MODE` (the encoder's default), with the adjuster | Today's null path, not a new CBR request. The log records requested CBR and effective encoder default. The M31 HEVC output is checked in the smoke test, not assumed from VP9 |

- Codecs 4 and 5 never get the DEBUG temporal-layer request (`android.generic.2`), and the requested and effective values are logged.
- A component that misbehaves gets a `NoAutomaticHardwareHevc` or `NoAutomaticHardwareAvc` DeviceFamilyPolicy entry. The entry is component-scoped like `NoAutomaticHardwareAv1`, needs official sources and evidence, and none exists yet.

**Bitstream (summary; normative in PROTOCOL).**
- **Picture subset for the first release.** One slice per picture, HEVC IRAP only as IDR (types 19 and 20; CRA is deferred until its recovery state is specified), only I and P slices, and `BTV3` flag bit 7 (discardable) never set. Wider subsets need a later revision with vectors.
- **Activation (review 2 F5).** Parameter sets change only inside an IDR access unit. The receiver validates the whole AU, then makes its sets the active configuration atomically and rebuilds the decoder if they differ. A rejected keyframe leaves the old active configuration, and the frames after it are dropped until the next valid IDR. Three rejected keyframes in a row within 10 s close the session as an unsupported stream.
- **Keyframe requests (review 2 F10).** The response must be an IDR, decided by parsing the emitted NAL types, never by the platform's keyframe flag. Closed GOP is configured where offered (VideoToolbox `AllowOpenGOP` false). If no IDR follows within 1 s, the request is repeated. After 3 misses the video ends with `no_idr` and the component is ineligible for this process.
- No config packet for codecs 4 and 5. Every keyframe carries its parameter sets in-band: the sender's packetiser prepends the cached sets, and the encoder is never asked to (`KEY_PREPEND_HEADER_TO_SYNC_FRAMES` is not used). Non-keyframes carry none. The keyframe flag comes from the NAL type.
- One picture per access unit, with no B-frames or reordering. The receiver enforces this from the bitstream:
  - HEVC: `sps_max_num_reorder_pics` 0.
  - H.264: no B slices, and POC type 2 or a VUI `max_num_reorder_frames` of 0, so no decoder holds frames for reordering.
- Decoders run in low-latency mode where offered (Android `KEY_LOW_LATENCY`, Media Foundation `CODECAPI_AVLowLatencyMode`). The smoke test measures how long each decoder holds a frame.
  - Qualcomm decoders that do not offer `KEY_LOW_LATENCY` get Qualcomm's own extension, `vendor.qti-ext-dec-low-latency.enable=1` (registry entry `qcom-decoder-vendor-low-latency`, added 2026-10-28). The sources are Qualcomm's AOSP decoder source and ACodec, which passes vendor keys at configure from Android 8.
  - Reason: in the smoke, the ASUS H.264 decoder ran without low latency and held every frame 68-69 ms (one frame at 15 FPS).
- A keyframe request gets an IDR.

**Patents, distribution and the decoder surface.**
- MeshLive uses only the platform's own HEVC and H.264 encoders and decoders: MediaCodec, VideoToolbox, Media Foundation. It never ships, bundles or downloads an HEVC or H.264 codec of its own (no x265, x264, OpenH264, libde265, FFmpeg or similar).
- Its code only reads NAL headers and a few parameter-set and slice-header fields, and splits access units into packets.
- The platform vendor licenses the codec it ships, which is why Windows sells HEVC decoding as an extension.
- F-Droid: the build adds no binary, library or non-free dependency, and calling MediaCodec is ordinary platform API use.
- Feeding HEVC to the GPU through D3D11 video acceleration without Microsoft's extension would make MeshLive an HEVC decoder front-end (bitstream into acceleration structures). That raises patent and no-external-code concerns. It is not proposed; open question Q-HC3 below is open.
- This is the project's engineering position, not legal advice.
- **Threats (L9).** Forwarded stream video is sealed for the audience but not signed by the source, so any admitted forwarder can hand crafted access units to its children. A call peer can do the same.
  - The validator is a structural gate, not a sanitizer: it bounds sizes and shapes and rejects features we do not use, but a bitstream that passes still reaches a platform decoder.
  - Android runs codecs outside the app process (the media codec services). Media Foundation MFTs load inside the Windows app's process, so a decoder bug there is a bug in our process.
  - Residual: platform decoder updates are the only fix. Moving Windows decoding out of process is a later option.

**Switches.**
- Masters, off until the smoke test passes, then on. While a master is off, its bits, picker entry and logs do not appear.
  - `hevc_codec`: shipped constant `ConnectivityFeatureDefaults.HEVC_CODEC`, DEBUG `test_hevc_codec`.
  - `h264_codec`: `ConnectivityFeatureDefaults.H264_CODEC`, DEBUG `test_h264_codec`.
- Control arms (DEBUG):
  - `test_prefer_hevc=false` and `test_prefer_hw_avc=false`: the codec stays advertised and selectable by hand, but Automatic never picks it.
  - `test_av1_soft_decode_bit=false`: bit 4 is not sent.
- Apple: the launch arguments `-NearbyHEVC`, `-NearbyH264`, `-NearbyPreferHEVC 0` and `-NearbyPreferAVC 0`, the same pattern as `-NearbyKeepCameraWhenOff`. Windows: its existing debug-switch mechanism, with the same meanings.
- SWITCHES rows "HEVC and hardware H.264 codecs" and "Prefer hardware HEVC / H.264".

**Measurement plan (short).**
1. *Host checks:*
   - Access-unit validator vectors: valid units from each platform's hardware encoder, plus one negative per rule. They include long in-band parameter sets, malformed escapes, overlong Exp-Golomb codes, exact boundary sizes, same-ID replacement at an IDR, a rejected replacement, and the discardable flag.
   - Negotiation cases, in both setup roles, direct and through one and two call relays:
     - asymmetric directions (A sends HEVC to B while B sends VP9 to A);
     - an old peer with decode mask `0x03`;
     - a VP9|HEVC|bit-7 peer, which gets VP9;
     - an HEVC|bit-7 peer without VP9, whose preference is ignored;
     - bit 4 against hardware HEVC, VP9 and H.264;
     - fallback after the top choice is refused;
     - each control arm, family VP9 and a manual choice.
   - A stream viewer without HEVC behind an HEVC-capable forwarder stays refused.
   - The old-reader behaviour of summary bits 2 and 7; legacy and compact offers of one stream normalising to the same summary.
   - A CoC call offer from an HEVC-capable phone with dual listening off, whose summary must have bit 3 clear.
2. *Census:* S22 and Xiaomi (the census tool's `dumpsys media.player` and app probe, with performance points), before the smoke test.
3. *Smoke, every available required phone:* a 60 s call each direction in both setup roles, and a 60 s stream to one viewer. Each must log `encoder_choice` with the expected codec, decode with no decoder errors, serve a keyframe request with an IDR, decode one ladder size change, fall back when the peer runs with the masters off, and log decoder hold time.
4. *Quality per bit, offline (no radio):* the encoder matrix harness with the declared generated raw motion input at 426x240/15 and 640x360/15, 150/200/300/450 kbps. It measures VMAF and PSNR BD-rate, achieved against requested bitrate, and encoder FPS. Pairs:
   - ASUS: hardware HEVC against software AV1.
   - M31: hardware HEVC against hardware VP9.
   - A15s: hardware H.264 against software AV1.
5. *One thermal comparison (ASUS, censused; S22 if the ASUS is busy):* a stream, because ASUS calls stay VP9. The same viewer, distance, brightness and generated input, unplugged. Each arm streams 10 min at 640x360/24/400 kbps, and the second arm starts within 0.5 °C of the first one's start temperature. Arm A is hardware HEVC, arm B today's software AV1. Battery temperature, thermal status and charge counter are logged every 10 s, along with encoder FPS, app CPU and encoder input-to-output delay (a proxy, not glass-to-glass latency).
6. *Reporting:* one AB pass, reported as a comparison and not as a promotion A/B. The numbers go here. On a measured phone family, HEVC running hotter than software AV1, or scoring lower VMAF than hardware VP9 at the same rate, leads to a policy entry for that family.

**Open questions.**

- **Q-HC1, which codec a stream uses.** A source cannot know which viewers a stream will reach. Recommendation: **A**.

  | Option | Effect | Cost |
  | --- | --- | --- |
  | **A. Streams: hardware AV1, then universally decodable hardware codecs (VP9), then hardware HEVC, then hardware H.264, then software; follows the coverage table (recommended)** | Phones without hardware VP9/AV1 stop software encoding for streams, the long-running case where heat matters most. Phones with hardware VP9 keep VP9. H.264 streams (the A15s) play everywhere. Apple viewers before A17 gain the HEVC streams, because they cannot decode AV1 until Design AD ships | A Windows PC without the paid HEVC Video Extensions cannot watch HEVC streams from the ASUS, S22 and Xiaomi; its row says the PC needs Microsoft's HEVC Video Extensions. Today those streams are software AV1, for which Windows needs the free AV1 extension or hardware. PC sources: NVIDIA and AMD have no hardware VP9 encoder, so an NVIDIA RTX 40 or AMD RX 7000 PC streams hardware AV1, an older NVIDIA or AMD PC streams hardware HEVC, and an Intel PC with hardware VP9 (Ice Lake and later) streams VP9 |
  | B. Streams use the call order (HEVC above hardware VP9) | Best compression on the hardware VP9 phones too (M31, A55, S24) | Windows viewers without the paid extension also lose streams from those phones, which they can watch today |
  | C. Streams prefer H.264 to HEVC | Every viewer decodes every stream | More bits for the same picture on the ASUS, S22 and Xiaomi |
  | D. Streams never use HEVC or H.264 | No viewer loses anything | The four phones keep heating while streaming |

- **Q-HC2, the compact (iPhone) codec bit.** The compact UUID's three codec bits are all assigned: 54 VP9, 55 AV1, 56 H.264 (pencilled first, never sent). Recommendation: **A**.

  | Option | Effect | Cost |
  | --- | --- | --- |
  | **A. Move bit 56 to HEVC (recommended)** | iPhones can announce HEVC streams, so an iPhone streams with its hardware HEVC encoder instead of software VP9. Shipped Android and Windows decoders map bit 56 to summary bit 2, which no shipped codec test matches, so old builds show the offer as not decodable, which is correct. Compact senders (iPhone, PC) always have something better than H.264 for streams | H.264 has no compact bit. An iPhone or PC never streams H.264; calls still negotiate H.264 in the hello |
  | B. Bit 56 stays H.264; HEVC gets no compact bit | HEVC still works in iPhone calls (negotiated in the hello; compact call offers keep their VP9/AV1 bits) | iPhone streams stay software VP9 (heat on the iPhone) |
  | C. A new compact version | Room for both | Every shipped decoder rejects the UUID, so iPhones become invisible to older builds. Rejected |

- **Q-HC3 (open; asked on 2026-10-27: why not use the GPU decoder directly).** Recommendation: **A, keep the extension**.
  - Why the GPU is not simply used: D3D11 video decoding has no "decode this HEVC stream" call. The application must parse every slice and parameter set into the DXVA picture and slice structures and manage the reference pictures, which is the front half of an HEVC decoder. MeshLive would then implement HEVC decoding itself. That is patent exposure that Microsoft's paid extension licenses, and either new decoder code or adapted external code, which the project's rules forbid. It is also a large parser in the app's own process.
  - **A. Keep Microsoft's HEVC Video Extensions (recommended).** No decoder code of our own; PCs without the extension cannot decode HEVC (the Q-HC1 cost).
  - **B. Our own DXVA HEVC front end.** HEVC on every PC with a capable GPU, at the cost of months of decoder work, patent exposure and attack surface.
  - **C. Make PC viewers irrelevant to HEVC.** Q-HC1 option A already keeps hardware-VP9 sources on VP9, and H.264 or AV1 (dav1d, Q-AD1) cover the rest wherever the source has them.

**Implementation checklist** (after review; numbers from the registry).

*Android:*
1. Add `StreamProtocol.CODEC_H264 = 4` and `CODEC_HEVC = 5`, each accepted by the `BTV3` header check only with its master on.
2. Add `NodeCapabilities.CODEC_H264 = 1 shl 2`, `CODEC_HEVC = 1 shl 3` and `DECODE_AV1_SOFTWARE = 1 shl 4` (end-to-end call hellos only, never link hellos), plus `codecCapabilityMask` for 4 and 5. Add both codec bits to `CALL_CODEC_MASK` (`privateCallEndpointDecodeMask`).
3. One explicit codec-to-summary map: VP9 `0x01`, AV1 `0x02`, H.264 `0x04`, HEVC `0x80`. It replaces every place that copies a hello mask into a summary:
   - `AndroidPrivateVideoCall.kt` call-offer summaries (`encodeCodecMask and decodeCodecMask`, two places);
   - `BluetoothVideoRelay.kt` relay offer (`capabilities.decodeCodecMask`);
   - `ReceiverStreamForwarder.kt` (`AudioLive.offerSummary`);
   - the source's stream offer;
   - `MainActivity.supportsAdvertisedCodec` (summary to local decode mask) and the source offer around `MainActivity.kt` line 7010, which uses `codecCapabilityMask` directly.
   - Route equality compares normalised summaries, so a legacy offer and a compact offer of one stream match after Q-HC2.

   Without the map, hello bit 3 would land on the dual-listening hint, and bit 4 on relay consent.
4. Codec assumptions that today know only two codecs:
   - `VideoCodec.selectionPriority`, `privateCallCodec` and `privateCallCodecForPeer` become **one choice operation per direction (review 2 F12)**.
     - Inputs: the eligible components, the mode, the masters and prefer switches, a manual choice, the family policy and the authenticated end-to-end decode mask of the peer.
     - Output: one ordered, filtered list, and fallback walks that list only.
     - A control arm removes its codec from the Automatic list entirely, so no fallback can pick it.
     - A manual choice the peer decodes is sent as chosen. Bit 4 never overrides it, and neither does bit 7, which is a change from today. A manual choice the peer cannot decode falls back along the list, logged as requested and effective. The family VP9 policy applies to manual choices as today.
   - `privateCallCodec` swaps to VP9 for `CallVp9Compatibility` when the selected codec is 3, 4 or 5 (today only 3).
   - `AutoQualityLadder.acceptableBppf` and `CallResolutionLadder` limits get H.264 and HEVC rows. These are stated assumptions until step 4 of the measurement plan measures them: HEVC uses the VP9 table, and H.264 the VP9 table times 1.3 bits per pixel (design decision 2026-10-27).
   - `VideoStreamDecoder.decoderCodecSpecificData`: for 4 and 5, the newest sets only, from the last keyframe. H.264 gets csd-0 SPS and csd-1 PPS; HEVC gets csd-0 VPS+SPS+PPS.
   - `encoder_choice` labels.
5. `VideoCodec` entries for `video/hevc` and `video/avc` with the eligibility rules. Each component is picked by measured or achievable FPS. Settings: profile, bitrate mode (as above), `KEY_MAX_B_FRAMES` 0, the existing keyframe interval and `PARAMETER_KEY_REQUEST_SYNC_FRAME`. No `KEY_PREPEND_HEADER_TO_SYNC_FRAMES`, and no temporal layering (`CameraVideoEncoder` excludes 4 and 5). Software encoders appear only in the manual picker, labelled software.
6. `AutomaticEncoderChoice.order` takes the mode (call or stream), the coverage table, `preferHevc` and `preferHwAvc`. New reasons: `hw_hevc_preferred`, `hw_avc_preferred`, `peer_no_hevc_decode`, `peer_no_avc_decode`, `peer_av1_software`, `family_vp9_compatibility`, `hevc_not_preferred`, `avc_not_preferred` and `ineligible:<cbr|cq_only|size|fps|profile>`.
7. Sender: one Annex B packetiser for both codecs. It merges consecutive codec-config buffers into the cached sets, prepends them to every keyframe, strips them from non-keyframes, strips SEI, AUD and filler, sets the keyframe flag from the NAL type, sends no config packet, checks that PTS strictly increases (a violation ends video with `reorder` and marks the component ineligible for this process), and reads the coded size from the SPS for the ladder discontinuity.
8. Receiver: pure `HevcAccessUnitValidator` and `AvcAccessUnitValidator` (JVM tests with the PROTOCOL rules). The decoder is low-latency where offered, and is rebuilt on a size or parameter-set change.
9. Advertise each decode bit when a decoder supports the codec at the session's maximum size. Set bit 4 by the rule above.
10. Add the policy effect types, the switches, the SWITCHES rows and the README/USER_GUIDE codec list.
11. Compact mapping, only if Q-HC2 is A: bit 56 maps to summary `0x80` in both `CompactDiscoveryAnnouncement` directions, and the vectors are updated.

*Apple (meshlive-apple; its H.264 scaffolding `codecH264` and `h264Enabled` exists):*
1. Add `StreamProtocol.codecHEVC = 5`, `NodeCapabilities.codecHEVC = 1 << 3` and `decodeAV1Software = 1 << 4`.
2. Use one explicit summary-codec mask, separate from `NodeCapabilities.codecMask`, everywhere a summary is built or read: `Discovery.swift`, `CallRelay.swift` (today it reuses `codecMask` as a summary mask), `App/AppModel.swift` (two offer builders) and `App/Controllers.swift`. In `Discovery.swift`, The compact encoder shifts the codec bits `<< 54`, so summary `0x80` would land in node-prefix bit 61: map `0x80` to bit 56 and bit 56 to `0x80` explicitly, in both directions (only with Q-HC2 A).
3. Delete the `decodePrefersH264` branch in `CallEngine.swift` (codec choice); bit 6 is not used.
4. Encoder: `VTCompressionSession` with `kVTVideoEncoderSpecification_RequireHardwareAcceleratedVideoEncoder` for HEVC Main and H.264 High.
   - Session settings: `RealTime` true, `AllowFrameReordering` false, `AllowOpenGOP` false, `MaxKeyFrameInterval`, and `kVTEncodeFrameOptionKey_ForceKeyFrame` for keyframe requests. Try `EnableLowLatencyRateControl` where the session accepts it.
   - Rate: `ConstantBitRate` when accepted, otherwise `AverageBitRate` plus `DataRateLimits`, with the requested and effective rate modes logged.
   - Convert the length-prefixed output to Annex B and prepend the format description's parameter sets to each keyframe.
5. Decoder: a format description from each keyframe's parameter sets (`CMVideoFormatDescriptionCreateFromHEVCParameterSets` or `...FromH264ParameterSets`), length-prefixed samples without the sets, and a rebuild when they change. Decode is advertised after a probe session is created.
6. Order: in calls, hardware HEVC goes above libvpx VP9 when the peer decodes HEVC. Streams follow Q-HC1; an iPhone streams HEVC only with Q-HC2 A, and never H.264.
7. The validators in NearbyCore share the Android vectors.

*Windows (meshlive-windows-rust):*
1. Add codecs 4 and 5, mask bits 2 and 3, bit 4, and the explicit summary map. The places are:
   - `discovery.rs`: `& 7`, the compact `(low >> 54) & 7`, the encode rule;
   - `stream_watch.rs`: the decodable test and its notice, and the relayed path's hard-coded `7`. Both intersect a summary with a hello mask, so the summary must be normalised to hello bits first;
   - `stream_backend.rs`: the joined-codec check `if h.codec == 2 { 1 } else { 2 }`, which assumes two codecs;
   - `pc_announcement.rs`: `OFFER_SUMMARY` gains `0x80` when Design QV kind 2 is built. The kind-0 call offer's packed three-bit codec field (byte 8, bits 0-2; review 2 F11) keeps VP9, AV1 and H.264 hints only. HEVC is never put there (bit 3 is route cost), and is negotiated only after the authenticated hello. Every PC has a representable fallback (it decodes VP9).
2. Encoder: hardware MFTs only (`MFT_ENUM_FLAG_HARDWARE`) with output HEVC Main or H.264, driven by `encoder.rs`'s existing async event pump, D3D manager ownership, deadlines and backpressure (not by the sync decoder loop). Property results and the emitted NAL types are checked. Settings: CBR, `CODECAPI_AVEncMPVDefaultBPictureCount` 0, `CODECAPI_AVLowLatencyMode` and `CODECAPI_AVEncVideoForceKeyFrame`. Advertised only when the encoder starts.
3. Decoder:
   - `decoder.rs` today chooses only between VP9 and AV1 and refuses async MFTs. The enumeration must find the Store-installed HEVC decoder: check which flags it needs, for example `MFT_ENUM_FLAG_UNTRUSTED_STOREMFT`, and whether it is async.
   - Low-latency mode on.
   - Each codec is advertised only after the whole usable path is probed: input type, output NV12, start, and decoding the embedded keyframe fixture. `SetInputType` alone is not enough.

**Review 1 (2026-10-27) and where it is addressed.**

| Finding | Where |
| --- | --- |
| H1 ASUS family protection | Eligibility: `CallVp9Compatibility` covers 3, 4 and 5; phone table; "msmnile HEVC calls" A/B |
| H2 receiver AV1 decode cost | End-to-end bit 4, precedence after bit 7 |
| M3 summary copy sites | Android item 3; host test |
| M4 validator loosening, no `KEY_PREPEND...`, merged config buffers | PROTOCOL validation; Android item 7 |
| M5 H.264 reorder and decoder latency | PROTOCOL H.264 SPS rule; low-latency decoders; hold time in the smoke |
| M6 temporal layers | Eligibility; Android item 5 |
| M7 two-codec assumptions | Android item 4; Windows item 1 |
| M8 Apple compact shift, `decodePrefersH264` | Apple items 2 and 3 |
| L9 threats | "Patents, distribution and the decoder surface" |
| L10 no config packet | Bitstream; PROTOCOL |
| L11 VideoToolbox open GOP and low latency; Store decoder enumeration; ASUS AVC FPS | Apple item 4; Windows item 3; census and eligibility |
| L12 dav1d | Design AD |
| L13 docs | PROTOCOL dual-listening table and compact layout, PLAN, OB, PC kind 2 |
| Q-HC1 | Coverage table; PC sources; Q-HC3 |

**Review 2 (2026-10-27, reviewer, of revision 1) and where it is addressed.**

| Finding | Disposition |
| --- | --- |
| F1 dav1d not pinned before the build | Fixed in revision 3: Design AD "Dependency rules" |
| F2 the optional `KEY_PREPEND_HEADER_TO_SYNC_FRAMES` key can fail configuration | Already fixed in revision 2: the key is never requested and the packetiser prepends (Bitstream; Android item 7) |
| F3 one picture and slice-to-parameter-set binding | Fixed in revision 3: a single-slice subset, the slice header read up to the PPS ID (and HEVC `slice_type`), and the PPS-SPS-VPS chain resolved within the keyframe AU or the active set (PROTOCOL rules 4 and 5) |
| F4 cropping and area-only checks | Fixed in revision 3: each coded dimension is bounded against the session envelope, with checked crop arithmetic and legal crop units (PROTOCOL rules 8 and 9). **Partly not adopted:** level limits are not enforced. The root decided log-only up to the specification maximum, and the per-axis envelope with a DPB of at most 16 pictures already bounds allocation more tightly than a level would |
| F5 atomic parameter-set activation | Mostly covered by revision 2 (no config packet, sets only in keyframes). Revision 3 adds staged activation at an IDR, retention after a rejected keyframe and the error budget (Bitstream; PROTOCOL rule 5) |
| F6 in-band parameter-set and EBSP bounds | The size limit for every set was already in revision 2. Revision 3 adds the escape rules, the parse budget with reject-if-a-needed-field-lies-beyond-it, and the delegation wording (PROTOCOL validation) |
| F7 rate-mode table | Fixed in revision 3: the complete table, CQ queried explicitly, and the no-mode path stated as today's (Encoder eligibility) |
| F8 one H.264 bit and several profiles | Fixed in revision 3: a decode bit means the whole wire envelope |
| F9 dav1d backlog | Fixed in revision 3: Design AD "Decoder adapter" |
| F10 IDR from generic keyframe controls | Partly in revision 2 (`AllowOpenGOP` false, flag from NAL type). Revision 3 adds IDR-only HEVC, CRA deferred, the request deadline and failure path |
| F11 PC kind-0 packed codec field | Fixed in revision 3: Windows item 1, and a registry row |
| F12 one choice operation through fallback | Fixed in revision 3: Android item 4 |
| Cross-checks (Windows `stream_watch.rs` normalisation, Apple `CallRelay.swift`/`AppModel.swift`/`Controllers.swift`, `MainActivity.kt` 7010, discardable flag, negotiation cases) | Fixed in revision 3: the checklists and host checks |
| "Avoid new preference bits unless needed" | Not adopted: bit 4 is the design decision on review 1 H2. It saves receiver battery on software-AV1 decoders at no airtime cost |

**Answers to the round-2 questions (2026-10-27).**
- Bit 4 does not put AV1 below hardware H.264.
- Level and DPB limits are log-only up to the specification maximum.
- The ladders use the VP9 tables, with H.264 at 1.3 times the bits per pixel, as stated assumptions until measured.

**Implementation work split.** One implementer per platform lane. Each lane uses the registry numbers and the shared vectors, which the Android lane captures from hardware encoders and places in each repository's fixtures.

| Lane | Scope | When |
| --- | --- | --- |
| 1. Android | All Android items, both validators, the choice operation, mappings and switches. It produces the shared vectors | First, while the phones are available |
| 2. Windows | All Windows items: mappings including kind 0, the hardware MFT encoder on the async pump, the full decoder probe, the HEVC extension notice | After the vectors exist |
| 3. Apple | All Apple items, with the validators in NearbyCore | Code only, unbuilt and untested until the Mac is back |
| 4. Apple dav1d | Design AD | Same as lane 3 |
| 5. Windows dav1d | Q-AD1 answered A (design decision 2026-10-27): pinned dav1d, used only when no MF AV1 decoder starts | Any time; it ships after its smoke test |

**HEVC coded-size padding (2026-10-28, before HEVC shipped anywhere).** The Windows lane measured NVIDIA's hardware
HEVC encoder padding each coded side to a multiple of 32 and cropping back: 426x240 coded 448x256, 640x360 coded
640x384, 854x480 coded 864x480. The first rule 6 allowed padding only up to a multiple of 16 (H.264 macroblocks), so
every receiver would have rejected those keyframes. HEVC's rule 6 is widened, H.264's is unchanged:
- each coded side must be a multiple of 8 (the smallest legal MinCbSizeY) and exceed its cropped side by less than 64
  (the largest coding tree block);
- the cropped size must fit within the `BTV3` header size per axis (either orientation; a smaller Auto-ladder rung under
  a header that names the maximum size is accepted); crop arithmetic and the 2-sample
  crop unit are still checked, and the cropped sides stay at least 16;
- memory stays bounded: a coded side is below the header side plus 64, and the DPB limit of 16 pictures is unchanged.
