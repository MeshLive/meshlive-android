# Design A: store-and-forward messages (delay tolerant)

**Design review required.** Default off (`test_store_forward`, Experimental setting). The v1 wire format and its
differences from the original draft are in PROTOCOL.md under "Experimental store-and-forward messages". Nothing here
changes BMH2, BMS1 or BME1 or live sessions.

**Goal.** Text, voice (Opus 16-24 kbps), photo (about 1280 px) and short video (about 200 kbps, at most 60 s) reach a
contact or group without a live link, carried by phones that meet. A walk-past contact lasts about 28 s and leaves about
0.7-1.5 MB per pass, so transfers go small-first, are resumable and use a 2 MiB maximum bundle.

**Design in brief.** Each identity has a mailbox key and a contact card exchanged by QR or inside an authenticated
session; there is no directory. A bundle is sealed end to end (the recipient authenticates the sender, carriers learn
nothing about it, no transferable signature), padded, split into 8 KiB chunks with a hash list in an immutable header,
and addressed by a pairwise daily recipient tag. Carriers store bundles under quotas (64 MB foreign storage, at most
25% per neighbour), verify every chunk without decrypting, and hand copies over the existing authenticated, ordered hop
link (BMS1 hop type 11). Live media always has priority, and forwarding stays hop-local.

| Phase | Scope | Done when |
| --- | --- | --- |
| v1 two-phone | Cards, `BSB1`/`BSM1`/`BSF1`, hop type 11, chunk, resume, dedup, expiry, receipts, storage, live preemption; direct link only. Coded, JVM-tested only | JVM vectors pass, and a phone pair completes all classes at 150-1200 kbps over GATT and Classic; a mid-chunk kill resumes; a live call preempts within one chunk |
| v2 one carrier | Opt-in carrier role, quotas, fairness, per-neighbour limits, a single-copy token, carrier battery budget | A -> B -> C with B unable to open the content (asserted in test); delivery latency, B's overnight battery and quota eviction measured |
| v3 gossip | Spray-and-wait (default 6 copies), a version-3 discovery digest, decoy WANTs, optional proof of work and tombstones | 4-6 phones in two groups plus a walking carrier: delivery ratio, latency, duplicate bytes and per-phone airtime measured, with no live-session regression |

Open design questions (recommendation first; each needs review before v2 ships):

| Question | Recommendation | Alternatives |
| --- | --- | --- |
| Who carries others' messages? | Opt-in, 64 MB quota, 50 MB/day cap, paused below 20% battery | On for everyone; contacts' groups only |
| Messages from non-contacts? | Contacts only | A capped request folder; public signed bulletins |
| Delivery receipts? | On for 1:1 with time coarsened to the hour, off for groups, no read receipts | All off; all on |
| Message lifetime? | 3 days default, 14 days maximum | 1 and 7 days; 7 and 30 days |
| Clearing delivered copies? | Expiry only, then tell the adjacent carrier in v3 | Gossip tombstones (bends the hop-local rule) |
| Mailbox link identity? | Persistent now, rotating later | Ephemeral now |
| Sender proof? | Deniable | Third-party provable |
| Spam control? | Quotas plus contacts only | Proof of work on everything |
| Message size? | Voice 2 min, video 60 s at about 200 kbps, 2 MiB bundles | Larger |
| **Forward secrecy** | Add it before carrying (v2) ships: one-time prekeys in the contact card or refreshed during live contact, a fresh key per message, prekeys deleted after use, and user authentication on the key-wrapping key where supported. Cost: more complex cards and prekey refresh | Keep v1 as is (direct only, nothing retained on carriers) and add it only for v2 carriers; or accept and disclose the limitation in the UI |

The limitation itself is described in PROTOCOL.md ("Forward-secrecy limitation"): both slot keys derive from the
recipient's long-term mailbox key, so a later compromise of that key decrypts retained bundles and reveals senders.

Risks: contact windows may be too short on slow stacks; mailbox ECDH splits at API 31; clock skew affects day tags and
expiry; Sybil storage abuse and colluding-carrier traffic analysis (inherent to delay-tolerant networks, so stated in
user-facing text); OEM background-scan limits; seized-phone forensics (see "Emergency wipe" in PROTOCOL.md). Prototype
first: a 1 MB bundle between two phones with a forced disconnect and a live-call preemption decides the 8 KiB chunk and
2 MiB limits.
