# Distribution and field reports

- **F-Droid and IzzyOnDroid.** The repository carries the metadata and recipe (`fastlane/`, `distribution/`).
  IzzyOnDroid serves developer-signed APKs from the GitHub release unchanged; F-Droid builds from source. Remaining:
  real phone screenshots in `fastlane/` and the inclusion requests (the application ID is already the final
  `io.github.meshlive.app`).
- **Release verification and field reports.** The About screen shows the app version and the signing-certificate
  fingerprint, and Export diagnostics copies the block defined in [architecture.md](../architecture.md#diagnostics-block-contract-for-export-diagnostics) (no media,
  messages, contact names or full keys). The current builder is a minimal fallback; the full block must come from
  bounded in-memory records the app keeps (connection, adaptive-PHY and codec events, device-policy state), never from
  logcat: release builds strip `Log.v/d/i` (`app/proguard-release-logs.pro`), and other apps' logs are not readable.
- **Google Play** would also need Play App Signing, a privacy policy and Data safety form, and a decision on the
  Bluetooth, camera and microphone permission declarations. None is started.
