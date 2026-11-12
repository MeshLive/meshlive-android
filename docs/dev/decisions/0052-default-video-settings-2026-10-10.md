# Default video settings (2026-10-10)

Calls and streams default to 426x240 at 15 FPS and 200 kbps (was 640x360 at 300 kbps). WebRTC's VP9 singlecast start limits put 360p at about 350 kbps, so 300 kbps under-fed 360p, and real Bluetooth links (especially relays) often deliver less. 360p and 480p remain choices, and the call resolution ladder can step up when the link allows. Benchmark launches pass explicit settings, so they are unaffected.
