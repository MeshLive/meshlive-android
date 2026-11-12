# Design VX: bundled libvpx VP9 encoder (design decision 2026-10-28; built on Android behind a default-off switch, untested on phones)

**Design decision (2026-10-28).** Build a software VP9 encoder into the Android app (libvpx, pinned and built from source like libaom), so phones without a hardware AV1, HEVC or VP9 encoder can send VP9 instead of hot software AV1 or bandwidth-hungry hardware H.264. H.264 is not ruled out; the bundled VP9 encoder is measured against it.

**Why.** Some phones have no usable VP9 encoder at all, not even Android's software one:
- The A15s (MediaTek MT6765, Android 10) has only a hardware H.264 encoder. Its vendor codec list disables Android's software VP9 encoder (`c2.android.vp9.encoder ... enabled="false"`), so today it can send only software AV1 (libaom, which needs its fastest presets there) or, with Design HC on, hardware H.264.
- Where Android's software VP9 encoder exists it is slow: the ASUS declares 20 FPS for it at 720p, and it has no real-time speed control.
- libvpx is the reference VP9 encoder, has a real-time mode with NEON kernels, and VP9 is decoded by every MeshLive platform without an add-on (Design HC, "Decode coverage"). Its output is standard VP9, so nothing on the wire changes.

**What is built.**
- Source: libvpx v1.17.0, the commit of the release tag `v1.17.0`, fetched and verified like libaom (commit plus a SHA-256 manifest of the committed tree, checked before the build uses it). Licence BSD-3-Clause plus the WebM project's additional IP rights grant (`PATENTS`), both in THIRD_PARTY_NOTICES and the app's licence assets.
- Build: libvpx builds with its own `configure` and make, not CMake. As Android's and Chromium's own builds do, its configure is run once per ABI in "external build" mode by a repository tool, and its output (configuration headers, run-time CPU dispatch headers and the enabled source list) is checked in; the app's CMake compiles exactly those files. A check mode regenerates and compares, so a stale configuration is found.
- Configuration: VP9 encoder only (no decoder: Android decodes VP9 with MediaCodec; no VP8), real-time only, 8-bit only, no tools or examples, a 16384x16384 size limit. arm64-v8a: NEON baseline, with dotprod and i8mm kernels chosen at run time from the CPU's reported features (SVE kernels are not built). armeabi-v7a (optional build): NEON intrinsics. The app builds no x86 ABI, so neither does libvpx.
- Encoder wrapper: written for this app from libvpx's public API (`vpx_encoder.h`, `vp8cx.h`) and shaped like the libaom wrapper, so both share the camera and raw-file input path, the rate trim, the link frame budget, the speed governor, the thermal floor and the logs.

**Real-time settings** (libvpx's documented one-pass fields; values as WebRTC documents for real-time VP9, implemented here, not copied):
- `VPX_DL_REALTIME`, no lag, one pass, keyframes only when the app asks (interval or a request), forced-keyframe quantizer floored near the last inter frame (the libaom rule).
- CBR (VBR and CBR-FD on request), 50% undershoot and overshoot, 500/600/1000 ms initial/optimal/total buffer, quantizer 4..63 (libvpx's full range; see "Rate control fix"), intra frames capped at 300% of an average frame, cyclic-refresh AQ, static threshold 1.
- Error resilient on: frames do not depend on backward-adapted probability contexts, so a lost frame does not corrupt the probability contexts of later frames. It costs some compression; the offline benchmark can turn it off (`benchmark_vpx_error_resilient=false`) to measure that.
- Threads by core count and frame size (the libaom rule), with row-based multithreading and one tile column per two threads.
- Frame dropping off in Automatic (the "no frame loss" preference, same as libaom); only an explicit CBR-FD allows encoder drops (then also post-encode drop).
- Speed: the libaom governor's design with libvpx's own range, cpu-used 5 (slowest real-time) to 9 (fastest). A per-mode starting table (7 at 640x360 up to 24 FPS, 8 to 640x360 or 720x480 up to 20 FPS, else 9), the governor's measured trials, and remembered speeds under their own key. A libaom-scale thermal floor is held at libvpx's fastest speed (9).

**Automatic order.** Switch `prefer_libvpx` (shipped constant `LIBVPX_AUTOMATIC`: false when built, true since 0.4.2, see "Automatic codec order after the 2026-10-28 phone measurements"; DEBUG `test_prefer_libvpx=true|false` decides one launch).
- Off (shipped until 0.4.2; a benchmark launch without the extra): libvpx is a manual choice only. It appears last in the picker and no Automatic or fallback path picks it (stream Automatic, call order, call fallback to the peer's codecs, the family VP9 call policy). Every other order is unchanged.
- On: hardware AV1 > hardware HEVC > hardware VP9 > libvpx VP9 > hardware H.264 > libaom AV1 > Android's software encoders. So libvpx only matters on phones without a hardware AV1, HEVC or VP9 encoder. Streams since 0.4.2 (Q-HC1 A): hardware AV1 > hardware VP9 > hardware HEVC > libvpx VP9 > hardware H.264 > libaom.
- This order is a hypothesis to measure before the switch is turned on: (1) heat and sustained FPS of libvpx against libaom on the A15s and ASUS at the same size, rate and FPS; (2) quality per bit of libvpx against the A15s's hardware H.264 at the same bitrate. If libvpx loses (2) at the A15s's call sizes, its slot moves below hardware H.264.

**Advertised capability.** A phone with libvpx can now encode VP9, so its encode mask gains the VP9 bit (receivers already require it for a VP9 stream from that sender). The A15s therefore advertises VP9 encoding even with the switch off, because a hand-picked libvpx must be receivable. No new bit, header or codec number.

**Droppable frames: deferred.** The libaom temporal layering (an experimental, default-off switch) uses explicit AV1 reference slots, re-creates the encoder to return to its own references, and checks each AV1 frame header. A VP9 version would set the no-update reference flags per frame and check VP9's `refresh_frame_flags`; that is a separate design with its own A/B, so on libvpx the switch does nothing and every frame is a reference frame.

**Bench and benchmarks.** The radio-free encoder bench takes `test_bench_codec=libvpx-vp9` (or `libvpx`); its existing cpu-used and threads pins apply (cpu-used 5..9, else refused). The offline file benchmark takes `benchmark_codec=libvpx` and writes IVF `VP90` with the same frame trace. `test_codec=libvpx-vp9` picks it for a stream or call. "auto", "vp9" and the VP9 MIME type keep meaning a MediaCodec VP9 encoder, so existing cases keep their meaning.

**APK size.** The native library (stored uncompressed in the APK, as Android loads it in place) grows by about 1.0 MB per ABI: arm64-v8a 7.77 MB to 8.80 MB (+1,029,784 bytes) against the same build without libvpx. A per-ABI release APK grows by about that much; a universal APK by about twice that.

**Tests.** JVM tests for the order, the switch, fallback, bench resolution and the speed range; an instrumented smoke test encodes 30 generated I420 frames and checks one VP9 profile 0 keyframe, error-resilient headers, one packet per frame and a full decode by the phone's own VP9 decoder; native builds for both ABIs.

**Rate control fix (2026-10-28).** The first phone bench asked libvpx for 400 kbps CBR on the 640x480 coverage input and got 871 kbps on the ASUS and 888-890 on the A15s: about 400 on the simple segments, 1,500-1,700 on the complex ones. Android's own VP9 encoder (also libvpx) gave 400.0 and libaom 372-399 on the same input.
- Cause: the quantizer ceiling of 56. WebRTC caps it (52) because it also drops frames and lowers the resolution to hold the rate. This path keeps every frame in Automatic, so the cap left rate control nowhere to go. VP9's quantizer 56 is q-index 224, whose step is 55% of the step at 63 (q-index 255: 1,007 against 1,828), so complex frames cost about twice the bits at the cap.
- Units and timing were checked and are right: the target is in kbit/s, the time base is microseconds, and each frame's duration is 1/FPS.
- Host reproduction with the same libvpx settings (FFmpeg's libvpx-vp9 on the same input file, 600 frames): cap 56 gave 941.6 kbps for 400; cap 63 gave 399.4. A 90-frame generated panning texture at 320x240 and 300 kbps gives 486.9 against 311.8.
- Fix: the full range 4..63, as libvpx's default and Android's `C2SoftVpxEnc` use. The instrumented smoke test now also requires CBR within 15% over those 90 frames. The offline benchmark keeps the old cap as an arm (`benchmark_vpx_max_q=56`).

**Open risks.**
- Not measured on any phone: heat, FPS, quality per bit, and whether every receiver's VP9 decoder accepts error-resilient libvpx output (it is standard VP9 profile 0).
- The checked-in configuration must be regenerated whenever the pin moves (the tool's check mode finds a mismatch; the build also refuses another libvpx version).
- APK size grows by the encoder (see above).
