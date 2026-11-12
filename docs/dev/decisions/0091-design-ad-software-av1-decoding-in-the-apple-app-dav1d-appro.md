# Design AD: software AV1 decoding in the Apple app (dav1d; approved 2026-10-27; revision 3 after reviews 1 and 2, ready for implementation)

**Problem.** VideoToolbox decodes AV1 only in hardware, on A17 Pro and M3 or later. Older iPhones and Macs advertise no AV1 decode. They cannot watch AV1 streams, and cannot receive AV1 in calls from senders that only encode AV1: today the four software-AV1 phones, PCs on libaom, and future hardware-AV1 phones.

**Decision.** Add dav1d (BSD-2-Clause, VideoLAN) to the Apple app as a pinned library dependency built from source. It is not copied or adapted code.
- It is used only when no VideoToolbox AV1 decoder exists. The app then advertises AV1 decode (bit 1), plus Design HC's end-to-end bit 4 ("my AV1 decoding is software"), so that a sender with hardware HEVC or VP9 uses that instead.
- No wire change.

**Dependency rules (pinned before any build).** These follow the Apple app's `Scripts/prepare-libvpx.sh` and the Android app's pinned libaom.
- **Pin.** `Scripts/prepare-dav1d.sh` holds exact constants committed in a reviewed dependency change: release 1.5.3 (any later pin must be 1.4.0 or newer), the tag's commit, the official archive URL and the expected SHA-256.
  - The constants are checked against VideoLAN's published checksum and the tag when that commit is made.
  - The script fails closed before extraction when the download does not match.
  - Changing them is another reviewed dependency change.
  - Every XCFramework slice is built from the one verified tree.
- **Build.** Meson and ninja: static, 8-bit only (`-Dbitdepths=8`), assembly on, tools and tests off.
- **Targets.** Explicit cross files with deployment minima: iPhone arm64, simulator arm64 and x86_64, macOS arm64 and x86_64 (NASM only for x86).
- **Output.** An ignored `Vendor/DAV1D.xcframework`, with a "not linked" stub when absent, like VPX.
- **Source.** No changes to dav1d; MeshLive adds only its own C shim.
- **Notices.** `Vendor/DAV1D-COPYING` reproduces upstream COPYING. The THIRD_PARTY_NOTICES entry gives version, URL, hash and BSD-2-Clause. It also says AOMedia members license their AV1-essential patents royalty-free under the AOMedia Patent License 1.0; that licence covers its licensors' patents, not every third party's.

**Decoder adapter (bounded; review 2 F9).**
- **One pump.** One serial pump per session owns the `Dav1dContext`. Every operation runs on it, in order: send data, drain pictures (`EAGAIN` handled by draining, then resending the same data, which dav1d still owns), flush, close and configure. A generation number is bumped at stop, flush and reconfigure, and work from an older generation is discarded.
- **Input bound.**
  - Pending input is at most 4 temporal units and 1 MiB.
  - On overflow, queued input is dropped, a keyframe is requested and decoding resumes at the next keyframe.
  - A temporal unit has at most 64 OBUs.
  - The sequence header is read with `dav1d_parse_sequence_header`, not with the Swift OBU scanner (`VideoBitstream.swift`). That scanner copies the remaining input for every OBU, so its work grows quadratically, and it must not be on this path.
- **Output bound.** A capped pool of 4 NV12 `CVPixelBuffer`s. A picture finding no free buffer is dropped and counted. Copies go by source and destination stride, from dav1d's default aligned planar pictures.
- **Session envelope.**
  - Sequence profile 0 (Main), 8-bit, 4:2:0 only.
  - The sequence header's `max_frame_width_minus_1 + 1` and `max_frame_height_minus_1 + 1` must fit the `BTV3` header's maximum size per axis.
  - `frame_size_limit` is that session's maximum pixel count (at most 2,088,960, that is 1920x1088).
  - Anything else fails closed, like the VideoToolbox path.
- **Settings.** `n_threads` 2, `max_frame_delay` 1 (no added frame latency), `operating_point` 0, `all_layers` 0, `strict_std_compliance` 1.
- **Configuration and discontinuity.** The codec-config sequence header is fed before the next keyframe temporal unit. A discontinuity flushes.
- **Output.** Colour attachments from the sequence header, so the existing renderer is unchanged.
- **Advertising.** AV1 decode is advertised only after two checks, and the result is cached per app version and device:
  - an embedded AV1 fixture decodes hash-exact at startup;
  - a 2 s decode benchmark of the embedded 854x480/30 and 640x360/15 fixtures runs at least 1.5 times real time.

  The largest size that passes is the advertised envelope. A device that passes neither advertises no AV1.
- **Switch.** `-NearbyDav1d 0` (DEBUG control: no software AV1 decode).
- **Logs.** `decoder=dav1d`, decode ms p50/p95, input drops, output drops.

**Cost.** Not estimated: the measurement below decides it. It runs only on devices without AV1 hardware.

**Measurement.** On a pre-A17 iPhone and a pre-M3 Mac:
- 5 min each, watching an AV1 stream from a software-AV1 phone at 640x360/15 and at 854x480/30, and one AV1 call direction;
- decode ms p50/p95, CPU, input and output drops, late frames, thermal state, memory high-water mark, and pixel hashes against the Android and Windows fixtures;
- the combined policy with Design HC (a hardware-AV1 sender, HEVC available, bit 4 set) before using it as a power argument.

**Windows equivalent.** The Windows app decodes AV1 through Media Foundation, which needs Microsoft's free "AV1 Video Extension" or a hardware decoder exposed by it. Its notices already say dav1d is not added.

**Q-AD1, bundle dav1d on Windows too? Answered: A (design decision 2026-10-27, "built in software AV1, sure, go ahead").** dav1d is bundled in the Windows app and used only when no Media Foundation AV1 decoder starts. The options as they were put:
- **A. Yes, as a fallback only when no MF AV1 decoder starts (recommended).** AV1 streams and calls then play on every PC, including offline PCs that cannot reach the Store (the off-grid user).
  - Cost: a meson/NASM static-CRT build step pinned in `sources.lock.json` under the same pinning rules, about 1-2 MB, and one more notice.
  - The same adapter bounds apply.
- **B. No; show "install the AV1 Video Extension".** No build cost, but PCs without the Store or the network stay unable to watch.
- **C. dav1d always, even with MF.** One code path, but it loses the hardware decoding MF offers.
