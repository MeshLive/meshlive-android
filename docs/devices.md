# Device compatibility

For users and testers: which phones MeshLive has been tested on, and how it went. The app needs Android 10 or newer.

MeshLive is in **beta**: it has been tested on seven phones from Samsung, Xiaomi, ASUS and OPPO (Android 10 to 16) and with a first iPhone, and results vary by phone pair. Do not rely on it where your safety depends on it.

## Reference phones

The maintainers test on these phones, by alias:

| Alias | Model | Android |
| --- | --- | --- |
| S24 | Samsung SM-S921B | 16 |
| S22 | Samsung SM-S901E | 16 |
| A55 | Samsung SM-A556E | 16 |
| Xiaomi | Redmi 23053RN02I | 15 |
| M31 | Samsung SM-M315F | 12 |
| ASUS | ASUS_I01WD | 11 |
| A15s | OPPO CPH2179 | 10 |

The iPhone app is on the [App Store](https://apps.apple.com/app/id6834136798) (source in `MeshLive/meshlive-apple`); it was tested on an iPhone 12 with these Android phones.

## Results

**Please add your phone.** Fill in the [device test report](https://github.com/MeshLive/meshlive-android/issues/new?template=device-report.yml)
(about two minutes) and we will add it to this table. Reports where everything worked are as useful as failures.

How to read it: **pass** = met all of our quality and reliability checks; **partial** = it ran and carried audio or
video in our tests, but missed at least one of our own checks or worked only with some phone combinations; **fail** =
did not work in our tests; **not tested** = we have no recorded test.

So far **no phone has met all of our checks, so there is no "pass" yet.** The results below come from the maintainers'
tests on development builds shortly before 0.1.0-beta (2 to 10 October 2026). Fixes made since then, for example to
stream relaying, have not yet been re-measured on phones. We have not recorded the Bluetooth version or chipset of
these phones; reports from users will fill that in.

**What "partial" and the benchmark FAIL verdicts mean.** In the public
[benchmark data](https://github.com/MeshLive/meshlive-benchmarks), about three in four failed executions (930 of 1,261)
delivered media to every phone that was meant to receive it and then missed at least one deliberately strict gate, for
example first picture within 3 seconds, at least 95% of the requested frame rate in every report interval, zero
freezes, or VMAF 90 against the reference. About 9% reached only some of the receiving phones (mostly relayed
streams) and about 15% never connected, mostly in calls and three quarters of them in runs that include the A15s.
So "partial" means "works, with measurable shortfalls" for most rows; it does not mean the feature is broken.

| Phone | Android | Voice call | Video call | Livestream | Relay through one phone | What we saw |
| --- | --- | --- | --- | --- | --- | --- |
| Samsung SM-S921B (Galaxy S24) | 16 | partial | partial | partial | partial | Voice connected with the M31 and ASUS; some voice calls to the A15s failed to set up reliably |
| Samsung SM-S901E (Galaxy S22) | 16 | not tested | not tested | partial | partial | Middle phone in the S24, S22, A55 relay tests |
| Samsung SM-A556E (Galaxy A55) | 16 | not tested | not tested | partial | partial | Relay chain with S24 and S22 formed, but timing and overload checks failed |
| Xiaomi Redmi 23053RN02I | 15 | not tested | not tested | partial | fail | Relay runs failed after the parent phone had been identified |
| Samsung SM-M315F (Galaxy M31) | 12 | partial | partial | partial | partial | Video call with the ASUS at 500 kbps worked both ways but missed picture and timing checks; its hardware VP9 encoder sends large bursts that can freeze GATT video |
| ASUS ZenFone 6 (ASUS_I01WD) | 11 | partial | partial | partial | partial | Some calls started from this phone did not finish setting up |
| OPPO CPH2179 (A15s) | 10 | partial | partial | partial | partial | Slow when sending video (0.4 to 6 frames per second in call tests); LE L2CAP CoC stops working after roughly 15 uses in one Bluetooth session (an Android stack limit) |

"Relay through one phone" means a livestream passed on by a viewer phone to a second viewer. In our multi-phone relay
test on 8 October, viewers reached through a relay or a fan-out played at 0.9 to 7.6 frames per second. Relaying a
private call is still a debug feature and is not available in normal use. All three transports (GATT, LE CoC and
Classic) were exercised between these phones; a pass or fail for one phone pair does not carry over to other pairs.
Detailed measurements are in [decisions](dev/decisions/README.md) and the benchmark data linked from this repository.
