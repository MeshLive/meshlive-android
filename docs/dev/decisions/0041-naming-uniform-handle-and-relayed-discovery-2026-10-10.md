# Naming, uniform handle and relayed discovery (2026-10-10)

Design: the naming and relayed-discovery design was reviewed independently (findings R1-R22) and these decisions were taken:

1. Hop-local reading: a phone re-advertises only what its direct neighbours told it (with cost and age), which counts as hop-local.
2. Relay helping is ON by default while the app runs. It pauses automatically below 20% battery or in Battery Saver, and the person can switch it off. Active calls and streams always relay. A reviewer proposed "only while charging"; that was rejected because off-grid users cannot be expected to stay plugged in.
3. Consent to be discoverable through relays: asked once, preselected on.
4. Stream titles are public by default, with an in-app note.
5. The uniform handle derives from the existing 48-bit discovery prefix (Crockford Base32; already on air, so no new tracking surface). Strangers are shown with the full handle.
6. Discovery radius: 2 relays.
