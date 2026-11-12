# Design CH: compact headers and aggregation (draft 2026-10-27, not reviewed, not built)

**Design review required (two independent reviews before code).** Wire rules: PROTOCOL "Draft: compact headers and aggregation `CH1`". Nothing is built. Proposed switch: `test_compact_headers` (Experimental, DEBUG, default off). With it off, no bit is signed and the wire is exactly today's.

**Problem (overhead audit 2026-10-26).** Media travels in many small records, and each one pays fixed framing:
- `BMS1` 17 + 16, `BME1` 18 + 16 and the inner packet 13: 80 bytes per record.
- A mesh stream adds `BMV1` 50: 130 bytes.

Several of those bytes are redundant:
- the magics (12 bytes per mesh record);
- the hop sequence, which both sides already know because it must be exactly the next one;
- the 8-byte endpoint and mesh sequences, where 2 bytes rebuild them;
- three 4-byte lengths, where the outer one already bounds the record;
- a repeated flag byte;
- an absolute 8-byte timestamp;
- and in `BMV1` a 16-byte stream ID plus a 16-byte source node ID on every record of one link.

At call and stream rates this framing is a large share of the link:

| Flow (one direction, app bytes) | Payload | On the link today | Framing share |
| --- | ---: | ---: | ---: |
| Voice call, 32 kbps Opus, single 20 ms units | 32 kbps | 65 kbps | 51% |
| Video call 360p 400 kbps + voice, GATT | 432 kbps | 484 kbps | 11% |
| Mesh stream hop, 200 kbps video + 40 ms audio pairs | 232 kbps | 276 kbps | 16% |
| Audio-only live, Voice preset 24 kbps (mesh, signed) | 24 kbps | 34.5 kbps | 30% |

**Goal.** Fewer bytes and airtime per record with exactly today's security:
- the same AEAD keys, nonces, tags and associated data;
- the same ordering, replay, duplicate, admission and overload rules;
- interoperability with every older Android, iOS and Windows build, which keep the current wire.

## Options

- **A. Recommended: per-feature compact encodings, each self-describing by its first byte and negotiated by one signed hello bit per scope (`CH1`).**
  - The hop bit is link-hello decode-mask bit 6; the end-to-end bit is end-to-end transport-mask bit 6.
  - Readers that advertised a bit accept both forms on every record, so there is no switch-over point.
  - The canonical full header is rebuilt and used as associated data, so the AEAD covers the same fields.
  - Any node can rewrite a compact header into the full one without keys, so mixed chains work.
- **B. A new capability-block version or new hello magic.** Rejected: `HandshakeHelloCodec` is exact-length, so every older build (including published 0.1.x/0.2.x and the iOS and Windows ports) would fail the handshake.
- **C. A first-record opening (as `BDO1`).** Rejected: an older reader rejects any unknown first record and closes the link, and the opening itself needs a hello bit anyway.
- **D. Generic compression (DEFLATE) of plaintext.** Rejected: media is already compressed, and compressing before encryption leaks plaintext structure through record sizes (CRIME-style).
- **E. Truncated GCM tags (12 or 8 bytes).** Rejected: it lowers forgery resistance, and the audit's rule is never to trade security for bytes.

## The parts (wire layouts in PROTOCOL)

- **P1 compact headers.**
  - `BMS2` hop header: type plus a LEB128 length, 2 to 4 bytes instead of 17.
  - `BME2` endpoint header: type, flags, low 16 bits of the sequence and a length, 5 to 8 bytes instead of 18.
  - A compact media packet (content type 13, calls only): a zigzag PTS delta, about 3 bytes instead of 13.
  - The 16-bit sequence is rebuilt as the smallest value above the last accepted one, so drops by the sender, an old relay or a forwarder keep working. Up to 65,535 consecutive missing records are tolerated, and a replay rebuilds to the wrong nonce and fails.
- **P2 `BMV2` stream index.**
  - The first envelope of a stream on a link is a full `BMV1`. It binds the next index (0..15) to the (stream ID, source node ID) pair in hop order.
  - Later envelopes carry the index, the low 16 bits of the mesh sequence, TTL and flags: 5 bytes instead of 50.
  - Each forwarder rebuilds the full envelope and encodes per child.
- **P4 hop aggregation, `BUNDLE` (hop type 15).**
  - Several queued hop records (endpoint content, heartbeats, mesh envelopes) go under one hop sequence and one tag.
  - It is used only for records already queued at a flush, so it adds no delay.
  - It complements the Experimental call audio coalescing (no wire change), which merges queued audio into existing `BTA2` records.
- **P5 `BTA3` audio batch (calls).** Implicit, evenly spaced timestamps and 1-2 byte unit lengths: 6 bytes per unit become about 1.2. Uneven spacing (DTX, gaps) keeps `BTA1`/`BTA2`.
  - Streams and radio keep `BTA2`: one source sealing reaches every descendant, so a stream would need a stream-level capability first (open question 4).
- **P8 GATT chunk type 3.** A 16-bit chunk sequence, 3 bytes per value instead of 5. It is used only after the peer's link hello carried the hop bit.
- **P9 longer Opus packets (calls).** Packets of 10 to 60 ms between `CH1` call endpoints: 60 ms means 17 units per second instead of 50, and slightly better coding efficiency at low rates (unmeasured here). The cost is +20 to +40 ms of algorithmic and packetisation delay, so it suits voice-only calls on weak links, chosen by the sender.

## Savings (computed from the record formats; one direction, per link)

Per record: `BMS2` saves about 15 bytes, `BME2` about 12, the compact media packet about 10 (calls) and `BMV2` 45 (mesh). `BUNDLE` saves about 16 bytes per merged record and only under backlog. GATT type 3 saves 2 bytes per value.

| Flow | Records/s | Today | With `CH1` (P1, P2, P5, P8) | Saving |
| --- | ---: | ---: | ---: | ---: |
| Direct voice call, single units | 50 | 65 kbps | 50 kbps | -15 kbps (-23%) |
| Direct voice call, 40 ms pairs (Experimental `test_call_audio_pairs`) | 25 | 52 kbps | 42 kbps | -10 kbps (-19%, including `BTA3`) |
| Direct video call 360p 400 kbps, GATT | 65 | 484 kbps | 462 kbps | -22 kbps (-4.6%, including P8 -3) |
| Relayed call, each hop (relayed cap 110 kbps) | 65 | 184 kbps | 165 kbps | -19 kbps (-10%) on every hop link |
| Direct stream leaf, 300 kbps (no `BMV1`) | 40 | 360 kbps | 351 kbps | -9 kbps (-2.4%) |
| Mesh chain, each hop, 200 kbps / 300 kbps | 40 | 276 / 376 kbps | 253 / 353 kbps | -23 kbps (-8.3% / -6.1%) on every hop link |
| Source fan-out to N mesh children | 40 x N | | | -23 kbps x N on the source radio |
| Audio-only live Voice 24 kbps, mesh | 5 | 34.5 kbps | 31.6 kbps | -2.9 kbps (-8.3%); a relay saves both links, -5.8 kbps |
| Heartbeat or report record | 1 | 34-47 B | 19-32 B | negligible |

- A relay or forwarder spends airtime twice (receive and send on one radio), so its share of the saving doubles.
- On a link that moves one LE data PDU per connection event, the deciding quantity is whether a record fits in one 251-octet PDU.
  - A voice record with `BMS2`/`BME2` and the compact media packet is 80 + 43 = 123 bytes instead of 160.
  - A `BTA3` pair of 80-byte units is about 211 bytes of records (223 with the GATT chunk, ATT and L2CAP headers), under the 251-octet PDU limit, so it takes one PDU instead of two: half the events per audio second.

## Interoperability and downgrade

- **Older peers.** An older peer never signs the bits, so it is only ever sent today's wire.
  - A mixed chain works hop by hop: `BMS2`, `BMV2`, `BUNDLE` and GATT type 3 are hop-local.
  - `BME2` is rewritten to `BME1` by the forwarder or relay in front of a peer without the end-to-end bit; the ciphertext is unchanged.
  - Compact media packets and `BTA3` are used only between call endpoints that both signed the bit. A relay copies them opaquely.
- **Downgrade.** The bits are inside signed hellos and bound into the session keys. A man in the middle cannot strip them without failing the handshake, and stripping would only cost bytes: no security property depends on `CH1`.
- **Other platforms.** iOS and Windows implement the same layouts behind their own switches. Until then they never sign the bits and see no change.

## Security notes

- **AEAD coverage is unchanged.** Associated data is the canonical full header rebuilt by the reader: magic, type, absolute sequence, flags, length. The nonce is the same sequence-derived nonce, and the tag stays 16 bytes.
  - The compact bytes themselves are not associated data, so every variable field must use its minimal encoding and any other encoding fails closed. Each record then has exactly one encoding, and a changed length or index fails the rebuild or the tag.
- **Ordering and replay are unchanged.**
  - The hop sequence stays exact-next (now implicit).
  - Endpoint and mesh sequences stay strictly increasing; the 16-bit rebuild never moves backwards.
  - A replayed, reordered or duplicated record rebuilds to a different absolute sequence and fails its tag. The mesh duplicate window still runs on rebuilt sequences.
- **`BMV2` binding.** The index binding travels inside the authenticated, ordered hop stream. A forged binding needs the hop key, and the reader re-checks source and stream identity on the full envelope exactly as today.
- **Bundles.** A bundle is one hop record. Its items are processed as consecutive records and pass every per-type check; bundles cannot carry handshake, opening, setup or route records.
- **Metadata.** Hop and mesh headers are inside link encryption, as today. Record sizes on air shrink, but still reveal media timing and approximate sizes, as today.

## P3 (separate section, needs security review): one AEAD layer on direct calls

- **Idea.** When the call is direct, the hop peer is the endpoint peer: the link and endpoint handshakes authenticated the same node ID.
  - Both endpoints would sign a further end-to-end bit (to be assigned), and endpoint content would travel as a hop record of a new type: the logical `BME1` header plus plaintext, protected only by the hop AEAD.
  - This saves the 16-byte inner tag plus the `BME2` header (about 22 bytes per record) and one AES-GCM pass per record per endpoint: about 11 kbps on a voice call and 14 kbps on a 400 kbps video call, plus CPU.
- **Why a separate review.** It removes the second layer of defence for direct calls (two independent key schedules today) and couples endpoint continuity to the hop link. That conflicts with:
  - in-call link renewal (Design SR), which replaces the hop link while endpoint keys continue;
  - the Classic cutover draft (Design CX), whose carrier switch relies on endpoint sealing;
  - any future relay insertion.
- **Possible shape.** Allowed only for direct calls without SR or CX, and refused once a relay or renewal is in play. Before shipping it would need a review of key separation, renewal and failure modes.

## P6 (separate section, needs security review): amortised signatures on audio-only live records

- **Today.** Every 200 ms record carries a 64-byte ECDSA P-256 signature plus a 4-byte sequence: 2.7 kbps of a 34.5 kbps Voice stream, one ECDSA sign per record at the source and one verify per record at every hop.
- **Options.**
  - (a) Sign every k-th record over a hash chain of the previous k record digests. Listeners buffer k x 200 ms before playing, and relays forward only verified records (the 2026-10-27 forward-after-verify fix).
  - (b) A TESLA-style delayed key disclosure: a per-record MAC with a later-disclosed key, which needs loose time sync.
  - (c) Ed25519 signatures: same size, faster.
- **Saving.** With k = 5 about -2.2 kbps (-6%) and about 80% of the ECDSA work, but +0.8 to 1 s of listener delay or a window in which unverified audio plays.
- **Recommendation.** Not before the radio's delay budget and threat model are reviewed; the byte saving is small.

## Open questions for the reviews

1. Is link-hello decode-mask bit 6 safe on every shipped reader (Android, iOS, Windows), and are the end-to-end hello bits 6 truly unused on all three? Or should both be carried as one capability through a different signed field?
2. Should `CH1` be one bit per scope, or one bit per part so P2, P4 and P8 can be A/B tested separately?
3. Is 16 bits enough sequence for a forwarder that sheds a long backlog (Design RT child thinning), or should a forwarder always send one full header after a long drop?
4. Streams and radio: does `BTA3` and the compact media packet justify a stream-level capability (a header flag older readers ignore does not exist: `BTL1` fails on a non-zero reserved byte)?
5. Does `BUNDLE` interfere with the arrival-train meter (records of 8,000 bytes or more), link pacing or the `GR1` lost-chunk recovery accounting?
6. P9: playout, concealment and native PLC assume 20 ms. Which receivers need changes for 40/60 ms packets?

## Verification plan (when built)

- JVM vectors per layout.
- Round-trip and rewrite equivalence: compact to full to compact is byte-identical, and the AEAD opens.
- Fail-closed tests: non-minimal varints, a wrong index, replay, a 65,536-record gap, unknown bundle inner types.
- Mixed-build chains (new-old-new) for calls and streams.
- Then the phone lane: bytes per second per link, PDUs per connection event, FPS and audio continuity, against the same build with the switch off.
