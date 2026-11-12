# Bluetooth headsets

Wired and USB headsets are honoured: a plugged one replaces the earpiece, and the loudspeaker unless the person
explicitly chose it. Bluetooth headsets (SCO and LE Audio) are deliberately never selected as the call output, because
they take airtime from the same radio that carries the call's own Bluetooth media link. The rule is the single call
output decision (`callOutputFor` in `PrivateCallAudioSession.kt`), covered by JVM tests.

That is a protective default, not a measured limit: no headset-coexistence measurements exist yet. Remaining work:

1. Measure, per Bluetooth stack and transport, what a connected headset costs a live call (throughput, stalls, media
   age, audio continuity) with the headset idle, in A2DP, in SCO and in LE Audio, against the same call without it,
   recording the radio conditions.
2. If the cost is acceptable on some stacks, offer headset output as an explicit, labelled opt-in (default off,
   Experimental) with a visible warning, scoped by a device-family policy entry with sources and an off-switch. Never
   change the call's media-link settings to make headset output look better.
3. Keep LE Audio broadcast paths out of scope unless a public API, hardware and actual output evidence all exist (see
   "New Bluetooth and API facilities" below).
