# Design decisions 2026-10-21

- With Messages on, chat uses Messaging v2 (`MessagingV2Defaults.ENABLED = true`; control arm DEBUG `test_messaging_v2=false`).
  Transition, the least risky of the options: nothing is converted in place. v1 messages stay readable, read-only, as
  legacy rows of the same chat, and messages still waiting unsent in the v1 outbox are resealed once in the v2 format
  (`MessagingV2Migration`, crash safe). A contact still on v1 is not an error: the chat shows "Their app is older" and
  what it sent arrives as a legacy row. No change to adverts; what is stored and exchanged with a contact is in PRIVACY.

- Ring timeouts harmonised across platforms: the caller gives up at 60 s and sends Cancel ("No answer"); the callee shows
  "Missed call" when that Cancel arrives, and its own safety stop moves from 75 s to 65 s, so a lost Cancel ends the
  ringing within 5 s instead of 15 (Android `CallRingTimeout.CALLEE_MS`; Windows does the same).

- In-call link renewal (Design SR) on by default (Q-SR-DEFAULT A): stalled direct GATT calls survived 13/16 with
  renewal vs 0/16 without, 0 false renewals. Open: the A15s-as-peripheral connect failure.
- GATT lost-chunk recovery GR1 on by default (Q-GR1-DEFAULT A), Android and Windows: 160/161 forced losses recovered.
- Source media pre-warm on by default (Q-PREWARM A): joins 0.8 s faster, 16/16 clean.
- Android 11 slot sharing stays as today, with the "Bluetooth needs a restart" hint (Q-SLOT-SHARE B): receiving calls
  matters more than searching while the phone-wide table is full.
- Windows watching (Q-WATCH): option A, a PC-specific viewer request under the PC announcement, not the iPhone's
  compact request (user: phones can update in-app, so do not inherit iPhone limits; make Windows the best version).
  Also build Go live on Windows (the PC as a stream source). Needs Design QV revision + review before code.
- Design QL revision 2 (user, 2026-10-21): Q-QL2a the scan's "Let <name>'s phone recognise yours nearby" checkbox
  defaults ON; Q-QL2b accept the revocation cascade with a clear warning line when visibility is turned off; Q-QL2c
  keep the 4-week lease expiry. Ship the consent UI, result screen and Call now on by default once its tests pass.
- Design QV gates (2026-10-21): this PC's adapter keeps only one announcing GATT provider on air (a second is Aborted
  for its life, any UUID/size), so the PC carries kind 1/2 by switching its single provider (F1, no wire change; the
  call offer pauses while watching or live). W1 PASS (206/206 byte-exact 23 B payloads, status 4, continuous, new
  address per restart, A15s+ASUS) and W2 PASS (both phones' production GATT client reached first_write, pdu 514) on
  testhooks 12c2bff0 + Android debug 6d0c962b. Fixed on the way: a 10-40 ms Windows advert blip no longer counts as
  a drop (only 1 s off air does), which could have left the PC unreachable for calls.
