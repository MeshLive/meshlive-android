# Run the tests

*Engineering log and process for contributors; you do not need it to use or build the app.*

This is the contributor guide to testing MeshLive: what you can check without a phone, what the acceptance gates
mean, how to set up two or more Android phones of your own, how to run and read the benchmark suite, and how to
make a release. It is written for someone who owns a few phones and a computer, not a lab.

**What you need for what**

| You have | You can do |
| --- | --- |
| Only a computer | Quick checks (next section), compile and plan the benchmark inventory, bind supplied inputs, analyze recordings someone else made |
| Two or more Android phones | Everything above, plus run workflow checks and real streaming/call cases between your phones |
| A camera or microphone pointed at a screen and speaker | The optional independent measurements (picture timing, optical latency, acoustic cues) |

You do not need the six reference phones to contribute. Say which phones you used, test them against the same gates,
and keep your results labelled with their real scope. A passing run on two of your own phones is a real result for
those phones; it is not a pass for any other phone or build.

Terms used below:

- **Hop**: one Bluetooth link between two adjacent phones. A **relay chain** is source -> middle phone(s) -> receiver.
- **Case**: one exact, fully specified test request (who sends, who receives, which transport, encoder, bitrate,
  frame size and rate, buffers, lifecycle actions, input). Its **case ID** is derived from that request.
- **Transport**: `classic` = Bluetooth Classic RFCOMM, `ble` = LE L2CAP connection-oriented channel (CoC), `gatt` = LE
  GATT (the slow, widely supported attribute protocol, used as a byte stream).
- **Carrier**: the optional second radio a hop may move to after it connects (`wifi_aware`, `wifi_aware_wider`,
  `wifi_direct`; `wifi_aware_native` for a hop that starts on Wi-Fi). A request without a `carrier` key is Bluetooth only.
  `transport` still names the Bluetooth transport the hop starts on. A requested Wi-Fi carrier that ran on Bluetooth is a
  failed case, shown by `BENCHMARK carrier: ... requested= effective=`.
- **Frozen APK**: one built APK, identified by its SHA-256, used for a whole comparison.

## Quick checks (no phone needed)

Run these from the repository root before opening a pull request.

```
python tools/fetch_native_sources.py
./gradlew :app:testDebugUnitTest :app:assembleGithubDebug
python -B -m pytest -q tools
```

- `python tools/fetch_native_sources.py` downloads the pinned libaom and libopus sources into the git-ignored
  `reference-repos/` folder. Run it first on a fresh clone; the native build needs them. `--verify-only` checks what is
  there without downloading.
- `./gradlew :app:testDebugUnitTest :app:assembleGithubDebug` runs the JVM unit tests and builds the debug APK (use
  `gradlew.bat` on Windows). The toolchain is JDK 21, the Android SDK with NDK 28.2 and CMake 3.22.1, and Perl (the
  libaom build uses it). Add `:app:compileGithubReleaseKotlin :app:compileFdroidReleaseKotlin` when you touch code that differs between debug and release or between the two distribution flavours (ARCHITECTURE "Build notes").
- `python -B -m pytest -q tools` runs the host-tool tests (Python 3.11 or newer). They use temporary directories and
  never contact a phone.
- Optional, for the C# desktop receiver and file sender (needs the .NET SDK, no Bluetooth):
  `dotnet run --project receiver/BluetoothVideoReceiver.csproj -c Release -- --self-test` and
  `dotnet run --project tools/BluetoothVideoFileSender/BluetoothVideoFileSender.csproj -c Release -- --self-test`.

What these checks prove: the stated unit or protocol check passes. JVM tests do not run Camera2, native phone codecs,
the radio, the display or the speaker, and they do not establish hardware maximums or optimal settings. Tests of
picker ceilings, priority thresholds, budgets and timeouts protect configured behaviour; they are not measurements.
Keep policy snapshots separate from failure regressions and metric validation. A change in native YUV plane copying
additionally needs `NativeYuvPlaneCopyTest` (an instrumentation test) on a phone of each affected family. Documentation-only
edits need no build.

When you fix a bug: reproduce it first, add only a useful focused regression, make a narrow fix, run the checks above,
freeze one APK and compare at the same settings (see "Changes and retesting").

## Example: the small workflow check

[../benchmarks/catalog/workflow-smoke/](../../benchmarks/catalog/workflow-smoke/) is a tiny, committed example you can
plan without running the full inventory. It contains six workflow-only cases (`workflow_only: true`): five 12-second
broadcasts that pause at 3 s and resume at 5 s (S24 -> M31, M31 -> ASUS and ASUS -> S24 over Classic; S24 -> M31 and
M31 -> ASUS over GATT), and one 16-second Classic call between S24 and ASUS with mute and video-off controls. They check that the plumbing works (launch,
authentication, controls, teardown, trace collection), not that quality passes. The input and suite paths inside it are
relative placeholders: the raw fixtures are not committed, so generate or bind your own (see Inputs) and edit the
paths and the phone aliases to match your registry before you run it.

```
python -B tools/run_benchmark_suite.py plan --suite benchmarks/catalog/workflow-smoke/benchmark-bundle.json --devices tools/benchmark_devices.json --phones ALIAS1,ALIAS2 --parallel 1 --output benchmarks/catalog/WORKFLOW_PLAN_NEW
```

Cases whose participants are not all in `--phones` are left out of the selection but stay visible. Use the plan to
confirm your aliases and fixture paths, then `run` with an exact `--apk-sha256`. A workflow check passing means the
harness worked on your phones; it is never a PASS under the acceptance gates.


## Everything else

Scope, acceptance gates, phone setup, the benchmark workflow, measurements and the release checklist are in the [testing reference](testing-reference.md).
