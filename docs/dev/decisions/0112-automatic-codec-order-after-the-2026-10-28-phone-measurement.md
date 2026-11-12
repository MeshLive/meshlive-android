# Automatic codec order after the 2026-10-28 phone measurements

**Decision (2026-10-28; release 0.4.2).** Turn on HEVC and hardware H.264 (Design HC masters), the bundled
libvpx VP9 encoder in Automatic (Design VX, `prefer_libvpx`) and a new start-of-session rule, `codec_power_aware`.
- Calls: hardware AV1 > hardware HEVC > hardware VP9 > libvpx VP9 > hardware H.264 > libaom AV1 > Android's software
  encoders, filtered by what the peer decodes (the family VP9 and peer "prefers VP9" rules are unchanged).
- Streams (open question Q-HC1, option A): hardware VP9 goes ahead of hardware HEVC because every MeshLive platform
  decodes VP9 without an add-on: hardware AV1 > hardware VP9 > hardware HEVC > libvpx VP9 > hardware H.264 > libaom.
  Q-HC1 A ranks hardware VP9, not software VP9, above HEVC: as in the original Design HC order, libvpx stays below
  every hardware AV1, VP9 and HEVC encoder. A whole stream at about 4x the CPU (ASUS: hardware HEVC 29% and +1.0 C
  against libvpx 123% and +2.0 C, below) is a bad default, and a viewer that cannot decode HEVC (Windows without the
  extension) is told clearly at join. So a phone with hardware HEVC but no hardware VP9 streams HEVC.
- libaom stays in Automatic only as the last resort: it is used when nothing above it can start.
- `codec_power_aware`: when battery saver is on (`PowerManager.isPowerSaveMode()`) or the phone is already warm when a
  call or stream starts (`getCurrentThermalStatus()` at or above `THERMAL_STATUS_LIGHT`, read with the same battery
  fallback and forecast inputs as the thermal adaptation), the usable hardware encoders move ahead of a software first
  choice. Only a phone whose Automatic choice would otherwise be a software encoder changes (in practice hardware H.264
  instead of libvpx, since every other hardware encoder already ranks above libvpx). It is decided once at session
  start, never during a call or stream, and never overrides a manual choice or a family VP9 or peer bit 7 rule. DEBUG
  control arm `test_codec_power_aware=false`; `encoder_choice` logs `power=saver|warm|normal` and reason `power_prefers_hw_avc`.
- A benchmark launch keeps extras-only behaviour: without `test_hevc_codec`, `test_h264_codec`, `test_prefer_libvpx` or
  `test_codec_power_aware` each stays off, so cases measured before 0.4.2 keep their Automatic encoder.

**Quality at the same rate** (offline file encode at about 400 kbps, VMAF):
libaom AV1 70.66, Qualcomm hardware HEVC 69.41, libvpx VP9 69.05, Qualcomm hardware H.264 67.51, MediaTek hardware
H.264 65.19. libvpx is within half a point of hardware HEVC and ahead of both hardware H.264 encoders; libaom is best
per bit but costs the most CPU (below).

**Heat and CPU** (10-minute encoder arms at the same size, rate and FPS):
- ASUS (Snapdragon 855): hardware HEVC 29% app CPU and +1.0 C; libvpx 123% and +2.0 C; libaom 181% and dropped 35
  frames.
- A15s (MediaTek MT6765): hardware H.264 about 36% (app plus codec process), libvpx about 160%, libaom about 220%; all
  held 15 FPS with no drops and thermal status 0. The A15s temperature sensor reports in 1 C steps, so its temperature
  change is not resolved.
- So hardware encoders are several times cheaper than libvpx; libvpx is clearly cheaper than libaom and kept every
  frame where libaom dropped frames on the ASUS. That is why libvpx replaces libaom in Automatic, and why battery saver
  or a warm phone prefers hardware H.264 over libvpx (a few VMAF points for about a quarter of the CPU).

**Calls with HEVC.** M31 <-> ASUS and M31 <-> S24 HEVC calls: 0 freezes, decoder hold 1-8 ms.

**Limits.** The ASUS heat arms ran behind the keyguard, so their latency is not comparable with unlocked runs (heat and
CPU are). The power rule's battery benefit is inferred from CPU share, not measured as battery drain; no unplugged
endurance run compares the arms yet. A stream from a phone with hardware HEVC but no hardware VP9 is HEVC, which a
viewer without HEVC decode cannot join (refused with a clear message, as for any stream codec).
