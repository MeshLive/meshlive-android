# Measurement adapters still missing

The harness has host regressions for native input binding, marker decoding, recording import, content-ROI diagnostics,
raster calibration, sound-cue analysis, AudioTrack clock estimates, pipeline, lifecycle, resource and overlap
analysis, and controlled impairment (see [testing.md](../testing.md)); none creates a phone pass by itself. What remains:

| Remaining work | Required result |
| --- | --- |
| Native stress and calibration evidence | Prepare only the native variants selected for the next comparison; characterize spatial, temporal and chroma demand and measure the selected encoder's actual time, bursts and queue growth. Validate the framing detector and tolerances at captured viewports; generated checks do not establish physical colour, optics or sufficient stress |
| Actual output capture and clocks | Verify acquisition modes, sensor and audio clock skew, exposure, rolling shutter, room path and dual-channel isolation on a real camera and microphone instrument, with independently measured cross-phone clock anchors. Decoder or Texture callbacks cannot approve physical cadence, blur or optical latency |
| Reference colour | Verify the requested range, matrix, transfer and chroma through each actual encoder and decoder |
| Acoustic experience | Complete clock coverage and diagnose timestamp and underrun changes against planned pauses. Waveform fidelity, speech quality, routing, echo and double-talk remain open; verify mute and independent audio and video phases over the full lifecycle |
| Lifecycle and trace preservation | Extend the generated-input pause, resume and End path to Camera2, foreground, lock, process and thermal cases and real remote acknowledgements; process-death reconstruction is separate product work |
| Radio and endurance | Run declared controlled stalls and loss at actual offered loads, keeping both call directions and every hop; separately measure real radio interference and range and matched 15-30 minute unplugged endurance |

Write focused tests for each adapter's failure cases before using it to close a benchmark request, and keep results in
the suite's own state rather than a second ledger.
