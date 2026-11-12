# Design CI: one sender for a legacy and extended twin of a compact call offer (draft 2026-10-20; reviewed 2026-10-27, review 1)

**Status.**
- Built ahead of review on 2026-10-21: default on, DEBUG control arm `test_copied_id_pc_pair=false` (CopiedIdTracker, AdvertTwinAds in StrangerNames.kt).
- Two independent reviews followed on 2026-10-27 (review 1 below).
- After that review, the T5 handover fix and the kept-form fix were built with their JVM tests.
- **Decision: the default stays on while V1 runs.** If V1 finds `0x180A` in an iPhone or Mac compact advert, the default goes off.
- V1, V2 and V3 are open. The rule has not yet run on phones.
