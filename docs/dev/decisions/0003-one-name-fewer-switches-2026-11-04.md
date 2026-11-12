# One name, fewer switches (2026-11-04)

- **Private Bluetooth ID (rotating discovery IDs) is always on for users.** With the switch off a phone used the old
  fixed ID and could not find phones that had it on: on the published 0.4.4, a phone with it off showed "No one found yet"
  for over a minute while the other phone listed it as "Someone nearby". Every current version uses rotating IDs, so the
  switch only offered a way to become unreachable. An "off" stored by 0.4.4 or older is ignored; the DEBUG benchmark arm
  `test_rotating_ids` stays (SWITCHES).
- **Friendly names are always shown.** The switch was display only (the name is computed from the public rotating ID
  that every scanner sees), so off gave no privacy and only showed every stranger, and the phone itself, as "Nearby
  phone". Its info text ("Off: the plain ID is shown") described an older build.
- **One name.** "Your name" is the name others see; "Who sees my name" (People I call or who call me / Only my contacts /
  Nobody) is the one choice. "Show my name before they answer" has no switch (on; the audience decides). Visible by name
  starts with "Your name" filled in and no second confirmation when it is kept; it still says anyone nearby can read it
  and can be changed to a nickname. The contact QR carries "Your name" unless the audience is Nobody (it is shown face to
  face to add a contact), and "Save contact as" is prefilled from it when no session name arrived; the person saving can
  still rename. The nearby details sheet no longer shows the phone's Bluetooth name. The phone's Bluetooth name stays out
  of naming: it is broadcast to every Bluetooth device and never changes, so it is never set to "Your name".
- **Fewer switches (2026-11-04).** Settings > Privacy keeps only real choices: Show names on lock screen, Hide phone
  name (it renames the phone for every app, so it stays a choice that asks first), Triple-tap to delete everything,
  Block unknown callers and Blocked people, Read receipts, Download files automatically, Reset private ID. The Advanced
  privacy page is gone. Hide my ID from strangers, New identity for each stranger and expiring verification codes are
  always on, and older verification codes are never accepted as scanned (they still add a contact, shown as checked by
  code): turning any of them off only made the person easier to track or impersonate. Under Connection, Fall back to
  compatible link, Listen on both links and Call audio with loss recovery have no switch (each follows its measured
  default). Find PCs, PC live streams, Visible by name and Let older app versions find me moved to Experiments. An
  "off" or "on" stored by an older build for a removed switch is ignored; every DEBUG launch extra stays for A/B tests.
