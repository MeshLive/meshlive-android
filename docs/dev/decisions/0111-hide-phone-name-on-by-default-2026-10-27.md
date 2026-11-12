# Hide phone name on by default (2026-10-27)

"Hide phone name" (the neutral Bluetooth name "Phone") is on by default from 0.4.1, design decision. Reasons: with the
neutral name, B2 ("Mac name read: B2 shipped first") lets a Mac or Windows PC read the GAP Device Name without pairing,
so it never shows a pairing prompt (measured Mac to S22 with the discoverable set and the neutral name: no SMP pairing,
link open 1.7-2.3 s); and the phone stops broadcasting its real Bluetooth name to everyone nearby. Cost: the phone shows
as "Phone" in other people's Bluetooth lists (headphones, cars). Rules: the default is one constant; a stored choice
always wins, so users who turned it off keep it off; the phone is renamed only after the user answers (onboarding page
"Hide your phone's name", or a one-time dialog after an update for someone who never chose), because renaming affects
every Bluetooth device the phone talks to; the saved-name restore on switch-off is unchanged; benchmark launches still
read only `test_neutral_bluetooth_name`. Not yet measured on phones with the default.
