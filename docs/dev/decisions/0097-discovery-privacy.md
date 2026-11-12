# Discovery privacy

**Design review required.** Today a phone advertises a stable 48-bit identity prefix, so a passive scanner can follow it
between places. The goal: discovery stops broadcasting a stable prefix, using contact-scoped rotating identifiers and
explicit discoverability windows. In-person contact verification (QR code or safety code; the verification API exists
and its UI binding is still to come) and key-change warnings come before the app is positioned for at-risk users.

Status: rotating discovery IDs, the BMH3 handshake and pseudonyms toward strangers are on by default since 0.2.0-beta (an explicit stored choice wins); formats and vectors are in [protocol reference](../protocol/README.md) and measurements in [privacy.md](../../privacy.md). Not measured: battery cost, the Bluetooth name read on the A15s, an active attacker holding a contact's key, fake adverts, relays with the new handshake and abuse limits. Open design question: the monotonic epoch never returns after a backward clock step, so a lead of 3 to 96 epochs leaves a phone unable to receive a version 5 call until it recovers. Phone-test plan:

0. **Phase 0 measurements** on every reference phone (`tools/rotid_phase0.py`): does an advertising-set restart change
   the address; are the GAP Device Name and PSM characteristic readable by an unpaired central; do SCAN_REQ and
   CONNECT_IND addresses equal a set address; which OS beacons rotate when; do established links survive a set stop.
   On iOS: does a stop and start change the iPhone address (RV17).
1. **Phone cases for phase 1:** discovery latency and call setup equal to switch-off; boundaries while idle and during
   a call; a passive-tracker scanner sees zero address or ID carry-over across boundaries (RV6); logged boundary
   offsets between phones (RV12); restarts inside an epoch keep the epoch ID (RV4).
2. **Streams and viewers on version 5 (phase 1b):** role 1 and 2 rotating IDs, stream token grace (60 s),
   admission-key translation, the recovery hint with `Khint` (RV18), forwarders and relay-chain mirroring.
3. **Wire contacts with presence leases (phase 2):** mutual verified contacts only, with a per-contact switch "Can see
   when I'm nearby" on by default (RV11); a store sealed with the verified-contacts Keystore key; an unconfirmed "seen
   nearby" line without a check mark; a new generation on Forget, Reset and wipe; no automatic connection from a match
   until BMH3.
4. **Contacts-only mode** (labelled limited until BMH3, RV13), the Visible-by-handle window for streams, iPhone
   compact v2 advertising and reach pages version 6 with naming phase 2.
5. **Separate designs:** the BMH3 identity-hiding handshake (SIGMA), uniform advertisements and a primary-PDU
   announcement (RV7).

Key-change handling and safety numbers for store-and-forward contact cards (Design A) belong here too.
