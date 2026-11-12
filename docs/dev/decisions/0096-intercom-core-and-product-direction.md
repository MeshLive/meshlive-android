# Intercom core and product direction

Intercom (voice, push-to-talk) and live video are equal parts of the product; the reasoning is in
[decisions](README.md) under "Product direction".

| Work | Done when |
| --- | --- |
| Bounded media-age experiment (**Experimental**, default off) | Calls keep speech within about 300-400 ms by lowering bitrate or resolution, dropping codec-safe non-reference frames, then going audio-only; streams buffer up to about 2 s, then shed or resync while keeping authenticated order. A randomized A/B against no-loss (M31 <-> ASUS, S24 <-> A15s, both directions, 150-1200 kbps with bursts) reports media age, stall length, frozen share and speech continuity, and the maintainers choose the default from it. No-loss stays the default meanwhile |
| User-centred gates | The `device-run-v4-user` profile in [testing.md](../testing.md) is built around media age, longest stall, frozen share, audio continuity and task success. Remaining: a decided media-age target, and adapters for task success and speech comprehension |
| Intercom core | Reliable 1:1 voice and push-to-talk in small groups, text, photos and location with time and accuracy; screen-off operation and reconnect proven on phones. Later: store-and-forward voice and video messages (Design A) |
| Signing mode | A legibility-first video profile (at least 20 FPS target for hands and faces), validated with fluent signers before any accessibility claim |

Current recommendation: keep no-loss as the default and bounded age as an off-by-default experiment. The interim evidence is in [decisions](README.md) under "Open-defect investigation notes".
