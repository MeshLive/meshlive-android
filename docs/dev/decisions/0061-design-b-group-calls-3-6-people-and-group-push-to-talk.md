# Design B: group calls (3-6 people) and group push-to-talk

**Design review required.** Design only; default off (`test_group_call`). Existing 1:1 calls, streams, BMH2, BMS1, BME1
and the static BGK1 group-key harness stay unchanged.

**Airtime.** A 20 ms Opus frame at 32 kbps costs about 64 kbps on the link after packet, BME1 and BMS1 overhead (about
50 kbps with the existing 40 ms audio-pair format). Planning uses a = 50 kbps per audio stream and v = 120 kbps per
low-rate video stream. Opus DTX cuts a silent talker to near zero, so real load scales with how many people speak; the
worst case assumes everyone sends. A hub's total radio airtime is the limit, not one link (an M31 GATT two-link relay
tops out at 625 kbps, and in a fan-out test leaves dropped to 0.9-7.6 FPS).

| Topology | Busiest phone, audio (N = 3 / 4 / 6) | Links per phone | Notes |
| --- | --- | --- | --- |
| **Mixing star** (hub decodes, mixes "everyone but you", re-encodes) | 2(N-1)a = **200 / 300 / 500 kbps** | hub N-1, leaf 1 | Small CPU cost; adds about 40-60 ms and one transcode |
| Forwarding star | N(N-1)a = 300 / 600 / **1500 kbps** | hub N-1, leaf 1 | Above the M31-class ceiling at N of 4 or more |
| Full mesh | 2(N-1)a per phone | N-1 (5 at N = 6) | 15 links to keep alive; weak stacks fail on the second or third link |
| Mixing relay tree | 2(c+1)a per node | parent plus c children | Hop-local, extends range; each hop adds about 60-100 ms and a transcode |
| PTT forwarding star (one talker) | at most (N-1)a = 100 / 150 / 250 kbps | as star | No mixing while one person holds the floor |
| Active-speaker video via star | +Nv = 360 / 480 / 720 kbps | as star | One video at a time; gallery video (3.6 Mbps at N = 6) is impossible |

**Recommendation: a mixing star, built so a star is a depth-1 mixing tree.** Hub load grows linearly, leaves need one
link (legacy stacks fail on extra links), and existing hop-local liveness and admission apply unchanged. A leaf hosting
its own children can reuse the mixer later, after the star is measured. Video is active-speaker only, N of 4 or fewer,
admitted only when the hub's measured capacity covers audio plus Nv, forwarded and never mixed.

- **Audio.** Leaves send Opus with DTX over per-edge endpoint sessions; the hub keeps one jitter buffer per leaf, mixes
  the three loudest active streams and encodes one mix-minus per leaf; with exactly one active stream it forwards that
  packet unchanged (re-sealed per edge). Same-room echo is the main acoustic risk: use the VOICE_COMMUNICATION source
  with platform echo cancellation, earpiece or headset by default and a visible speaker toggle.
- **Floor control (the hub arbitrates).** First request wins; a talker captures into a local buffer of at most 400 ms;
  maximum talk time 60 s; a lost holder frees the floor.
- **Keys.** Per-edge endpoint sessions mean removing a member immediately cuts access to new media. A group epoch key
  `BGK2` (increasing epoch, roster hash, controller and succession list, expiry, controller signature) is wrapped per
  member; a new epoch is issued on every join and leave, the old one accepted for 2 s, and a child that does not
  acknowledge within 2 s is closed. The hub hears all audio and can misattribute who is speaking in a mix.
- **When the host leaves.** The signed roster names a succession list (deputy first, by measured capacity, battery and
  signal). A planned handover or an unplanned loss moves leaves to the deputy with fresh authentication and a new epoch
  (target at most 10 s of silence, to be measured); a partition is not merged automatically in v1.
- **Wire changes (all new, all fail closed):** a discovery version-3 group kind with a keyed 5-minute token; BME1 type
  9 `GROUP_CONTROL` (`BGC1`); BME1 type 7 payload `BGK2`; a `BTV3` header flag for mixed audio plus an origin index for
  passthrough PTT; a group-invite reason on the call invite.

Open design questions (recommendation first): topology (mixing star; or forwarding star, full mesh, relay tree); group
video (active speaker only, N of 4 or fewer; or audio only); PTT floor (first come first served; or a leader who can
interrupt, a next-speaker queue); host leaves (a deputy takes over with a new key; or the call ends); rekey on join as
well as leave; speaker misattribution (accept and disclose in the UI; or signed talk-spurts); relay trees for range
(after the star is measured); who adds members (the hub admits from a QR roster; or any member, a fixed roster).

Risks: hub aggregate airtime over N-1 links is unmeasured; legacy stacks (M31 GATT 625 kbps relay ceiling, A15s CoC
slot exhaustion, S22 second-link timeouts) limit who can host; same-room echo; tandem-transcode quality in trees; hub
battery drain (rotate via succession); a mouth-to-ear target of at most 400 ms, to be measured physically; split-brain
and rekey storms under churn. Prototype order: (1) an audio-only mixing star, N = 3 over GATT, measuring hub
throughput, per-leaf media age, mouth-to-ear latency and hub CPU and battery; (2) N of 4-6 with DTX on and off; (3) PTT
passthrough with the floor state machine; (4) `BGK2` rekey on join and leave with JVM tests. Video and trees come after.
