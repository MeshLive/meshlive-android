# Design F: BMH3 identity-hiding handshake and stranger pseudonyms

**Coded 2026-10-11; on by default since 0.2.0-beta** (Settings > Privacy > Advanced privacy "Hide my ID from strangers" and its child
"New identity for each stranger"; DEBUG `test_bmh3`, `test_bmh3_pseudonyms`). Wire rules: PROTOCOL.md "Experimental
BMH3 identity-hiding link handshake" and "Phase 2: pseudonymous stranger identities". The protocol choice and the
rejected alternatives are in DECISIONS "BMH3 identity-hiding handshake: protocol choice" and "Pseudonymous strangers:
design choice".

Rationale, reviews and measurements are in [decisions](README.md) ("BMH3 identity-hiding handshake: protocol choice", "Pseudonymous strangers: design choice"); the executable specification is `tools/bmh3_reference.py`.

**Still open.** Streams answer BMH2, so a source's identity stays visible to viewers. A phone with pseudonyms on refuses
the old handshake, so it cannot call or be called by older versions or by a phone with the switches off. Message
exchanges and relayed calls work only with verified contacts while pseudonyms are on. A stranger who calls or answers
still learns who you are once you accept. Binding the verification QR to a live handshake is a follow-up.
