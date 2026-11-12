# Privacy and security

For users: what other people can learn about you when you use MeshLive, what is hidden, and what is not. The wire-level rules are in [protocol reference](dev/protocol/README.md).

- [In short](#in-short)
- [What nearby phones can see](#what-nearby-phones-can-see)
- [Audio-only live streams](#audio-only-live-streams)
- [Staying reliable in the background: permissions and what the app keeps on the phone](#staying-reliable-in-the-background-permissions-and-what-the-app-keeps-on-the-phone)
- [Measurements](#measurements)
- [Security model and limitations](#security-model-and-limitations)

## In short

- The ID your phone broadcasts is random and changes every 15 minutes, so a listener cannot follow a phone that is
  idle or in a call by that ID. Other traces remain; they are listed under "Still stable or exposed".
- Calls hide both phones' identities from anyone listening in. A stranger sees a throwaway identity until you answer.
- **Live streams do not have this protection.** While you send, watch or pass on a live stream (video or audio-only),
  anyone nearby with radio equipment can record your phone's long-term ID. It is the same ID on every stream and the
  one your contacts know you by, and it stays the same until you delete everything in the app. Calls do not have this
  exposure. If you must not be linked to a stream, do not watch it or pass it on.
- Your name, if you set one, is never broadcast. A stranger you call gets it only after they answer and their phone
  proves who it is, and never if you blocked them. A stranger you answer learns it the same way: only after their phone
  proves who it is, and never if you blocked them. "Only people I verified" and "Nobody" are unchanged. A stream title is public, so everyone nearby can read it.

Limits, plainly: a live stream shows the long-term ID of everyone taking part (the person streaming, every viewer and
every phone passing it on) to anyone listening nearby; on Android 13 and older a phone that connects
to yours can read its Bluetooth name; anyone whose call you accept learns your long-term ID when you answer, and your name only after their phone proves who it is (never if you blocked them); there has been no independent
security audit. The sections below give the detail.

## What nearby phones can see

Anyone with a Bluetooth scanner in range can read what MeshLive broadcasts. They do not need to connect or pair. The
**content** of calls, streams and messages is encrypted. What the announcements reveal depends on the settings and the
app versions below.

**What a phone broadcasts.** When a phone offers a call or a stream it sends a 22-byte Bluetooth LE announcement (or an
equivalent service UUID when the experimental "find iPhones" switch is on) containing:

- a **six-byte ID**. With rotating discovery IDs on (the default) it is a random-looking 48-bit ID
  that changes every 15 minutes. It is derived from a key that never leaves the phone, so it says nothing about who
  the phone is until someone connects (or holds a contact lease, below). Only the cases under "Still stable or
  exposed" use the fixed node-ID prefix instead (48 bits, the first 6 bytes of the SHA-256 of the phone's identity
  key; it does not change until the identity is wiped or reset);
- the Bluetooth transport it offers, which video codecs it supports, and flags such as "accepting connections",
  "can relay", "call available", "stream available" and "paused";
- the route cost (how many relay hops away the source is);
- for a stream: a 4-byte route token that follows the ID, a sequence number, and the resolution, frame rate and
  bitrate. A phone searching for a lost stream advertises a request that carries its own current ID and a hint that
  only the stream's source can match.

**What rotation changes.** Android changes a phone's own Bluetooth LE address from time to time so that scanners
cannot follow it. Older versions of the app put a fixed prefix in the payload, so a scanner that recorded
prefixes could link all of those changing addresses to the same phone, across places and days. Every advertising
set of the app (call-ready presence, stream offer, waiting viewer request, forwarder offer, title pages) stops at each
UTC quarter hour, stays silent for a random 1 to 8 seconds and restarts on a new address with a new ID. A call or
stream already connected keeps its link. In normal use the setting **Allow calls** is on, which means the
phone advertises that it can take a call while the app is open and, through its session service, in the background;
turning it off stops that, at the cost that others cannot call you unless your Call screen is open. Rotating IDs are
always on (no switch since 0.4.5: a phone with them off could not find phones that had them on); the identity-hiding
call handshake and the throwaway identities for strangers are always on too and have no switch. Your QR code shows "Your name" (unless "Who sees my name" is Nobody), because it is shown face to face to add a
contact.

**Wi-Fi (off by default).** With every Wi-Fi switch off nothing is sent over Wi-Fi: what is on air is exactly the Bluetooth
announcement above. Between two phones that both include the Wi-Fi feature, the encrypted Bluetooth link also carries a short note
that the build supports Wi-Fi and a short capabilities note with every Wi-Fi bit clear, so a peer cannot tell why Wi-Fi is not
offered. *Use Wi-Fi when it helps* (the Private level) moves a link to Wi-Fi only after a Bluetooth link has
authenticated both phones, and the Wi-Fi data path is encrypted with keys only those two phones can derive. During each move
to Wi-Fi both phones briefly advertise a one-time Wi-Fi Aware name derived from those keys, exchange short Wi-Fi Aware
messages and show a random Wi-Fi Aware address on air; the name is new for every attempt and means nothing to others. While a
Wi-Fi link is open, the other phone can reach other apps on this phone that accept network connections from any network.
On a call, the other phone is the person you are talking to, or the relaying phone next to yours. On a live stream it can
be anyone: every phone that watches or passes on your stream, and the phone you get a stream from, which may be a stranger
or a decoy. A stream link proves that the other phone holds a MeshLive key, not who it is. So with this switch on, a
stranger who joins your stream, or whose stream you tap, becomes a Wi-Fi neighbour of your phone. If you stream to, or
watch streams from, people you do not know, keep this switch off.
On a live stream the first greeting between phones is not encrypted, so anyone listening nearby, not only the phones you
stream with, can read which MeshLive version you use: whenever "Tell me about newer versions nearby" is on (the default),
and, while this Wi-Fi switch is on, even if you turned that off. The phones next to this one when it relays a call can
also see the version while this switch is on. A switch turned on during a call or live stream
works from the next one. *Wider reach over Wi-Fi* also publishes the same 22-byte announcement that Bluetooth broadcasts,
inside a Wi-Fi Aware service whose name changes with the rotating ID, so it shows nothing that Bluetooth does not already
show; nearby Wi-Fi devices can additionally see that the phone uses Wi-Fi Aware. Names rotate at least every 30 minutes, and
the phone takes a new Wi-Fi Aware address with a new name whenever it can; while a Wi-Fi link is open the phone's Wi-Fi
address may not change. To connect to a phone seen only over Wi-Fi the phones use a data-path key that anyone can compute
from the public service name, so any nearby phone can open such a link before it proves who it is, and that layer only
protects against passive listeners that do not run MeshLive; the signed, encrypted link handshake is what protects the call,
exactly as on Bluetooth. Until that handshake completes, the device that opened the link is a Wi-Fi neighbour of your phone
and can reach other apps in the way described above; the app serves one such device at a time and closes the link if the
handshake has not completed about 10 seconds after the connection was accepted, and a device can try again. A phone that completes that handshake counts as connected for the rest of that call or stream join,
even if it used a throwaway identity made just for it, the same as on Bluetooth: the handshake proves the other phone holds a
MeshLive key, not whose phone it is, so check the person before you trust them. On Android 11 and 12 the phone cannot check
which Wi-Fi band such a link would use, so it finds people over Wi-Fi but does not open Wi-Fi-only links. *Wi-Fi Direct* shows the phone's Wi-Fi Direct name to everyone nearby while a group is active (on both phones: the phone that joins shows its name too; "Hide phone name" changes the Bluetooth name only, so this can still be the name you gave the phone; the switch's description shows the name when Android reports it), its
network name has a pattern that identifies MeshLive, a nearby device can send the phone an invitation to connect, and the
Wi-Fi Direct address may be recognised across calls where the phone's maker does not randomise it.

**Wi-Fi Direct cleanup.** Groups created for calls are removed when the call or carrier ends. The app keeps a sealed list of the names of its own groups only to clean up after a crash; the list holds no keys and no addresses. If a group cannot be closed, the app says so and asks you to turn Wi-Fi off and on. With an always-on VPN that blocks connections outside the VPN, a Wi-Fi Direct link may not connect; the call stays on Bluetooth.

**Who learns who you are.** With the defaults, a call link carries both phones' identity keys encrypted. A phone that
is not one of your verified contacts meets a throwaway identity instead of your real one. Your real identity goes only
to verified contacts and, after you press Accept, to whoever holds the call you accept, which may be a relaying phone; a declined call reveals nothing.
Still true: accepting a call from a stranger tells them who you are, and calling a stranger tells whoever answers who
you are; live streams still use the old handshake on every link, and it sends each phone's identity key unencrypted, so
the source, every viewer and every phone that passes the stream on show their long-term identity to each other and to
anyone listening nearby, and a device that connects to a phone waiting for a stream is given that phone's identity too;
and this version refuses the old handshake for calls, so it cannot call, or be called by, a phone whose app does not use
the identity-hiding handshake (a version from before 0.2.0-beta, or a later older version whose owner turned those
switches off). Message exchanges work only with verified contacts. Calls relayed through other phones
(experimental) use a throwaway identity on every relay link; in a call between strangers a relaying phone could sit in
the middle, so a relayed call from a stranger is marked "via a phone nearby".

**Still stable or exposed.**

- **Older app versions.** A version from before rotating IDs, and a later older version (up to 0.4.4) whose owner
  turned its rotating-ID switch off, cannot see a phone with this version at all, and nothing on that phone says why.
  This phone still sees them, by their fixed prefix. A
  rotating viewer that joins an older source shows its own fixed prefix while its request is on air (the app says so),
  and anyone can advertise a decoy old-style offer to make a viewer do the same.
- **Visible by handle.** The Settings button advertises the fixed prefix for 10 to 25 minutes so older builds and
  people who know your handle can find your streams. Every such window can be linked to every other window and to the
  minutes just before it. A phone with pseudonyms on still refuses calls from older versions.
- **Live streams** (video and audio-only) show the long-term identity of every phone taking part: the person
  streaming, every viewer and every phone passing the stream on. The first greeting on a stream link is not encrypted,
  so anyone nearby with a Bluetooth sniffer can record these identities without joining, and can recognise the same
  phones at any later stream. A device that connects to a phone asking for a stream is given that phone's identity as
  well. Rotating IDs do not prevent this, and the app shows no warning when you watch. Calls do not have this exposure.
  What you can do: do not watch or pass on a stream you must not be linked to. "Delete everything" gives the phone a
  new identity afterwards; your contacts then have to verify you again. A stream also stays linkable by its route
  token while it runs, and a stream title, if you set one, is advertised openly.
- **Classic Bluetooth** uses the phone's permanent hardware address. A first Classic contact with an unpaired phone
  shows its name and address while it is discoverable.
- **Paired phones.** A phone that is paired with yours through Android can work out your rotating LE addresses.
- **The Bluetooth device name** can be read by any phone that connects, before the handshake, and is shown by Classic
  lookups. MeshLive never renames the phone without asking. With rotating IDs on, connectable announcements are
  non-discoverable on Android 14 and newer, which makes the phone refuse the name to an unpaired phone that connects
  through them. In the test the S24 (Android 16) sent them that way and the ASUS (Android 11) and M31 (Android 12) did
  not, so on Android 13 and older the name stays readable. Links your phone opens itself still show the name to the
  other side. "Hide phone name" (Settings > Privacy) uses the neutral name "Phone" instead. It is the default: the
  first time you open the app it asks "Hide your phone's name" (Hide name / Keep my name), and nothing is renamed
  until you answer. It renames the phone for everything (headphones, car, watch, PC), so those devices show "Phone";
  nearby phones and scanners no longer see your real name over Bluetooth. It does not change the device name the phone
  shows over Wi-Fi while the Wi-Fi Direct switch or Share app nearby is in use; an app cannot change that name. It shows your original name so you can restore it, turning
  it off restores that name, and if you uninstall the app while it is on the name stays "Phone" until you rename the
  phone in Android settings. While it is
  on, the call-ready and viewer-request announcements let an unpaired device that connects read the name, so a Mac can
  call without asking to pair; the name it reads is "Phone" and nothing else is exposed. If the name changes away from
  "Phone", those announcements hide it again at once.
- **Capability bits.** The codec set, transport and relay hint bits stay the same across IDs: a few bits that can
  single out a phone when few others are nearby.
- **Verified contacts.** After a call you both answered, mutual verified contacts exchange keys that let each recognise
  the other's IDs nearby, but only when you allowed it ("Can see when I'm nearby" on their contact screen, or "Let
  <name>'s phone recognise yours nearby" when you scan their code; until you answer it counts as off)
  ("seen nearby, unconfirmed", never a check mark) for up to four weeks or until you forget the
  contact or reset the presence key. A phone read on day D reveals its IDs up to 27 days ahead, so use **Reset presence
  key** after a phone was out of your hands.
- **Legacy chips.** A phone whose Bluetooth chip only supports legacy advertising may keep its address when the ID
  changes and shows a note saying so.
- **Other Bluetooth radios.** Android's own beacons (Find My Device, offline finding, Fast Pair), the phone's
  connection-request addresses, and the size and timing of encrypted data on a link are outside the app's control; they
  show that a call or stream is happening, and roughly of what kind.
- A stream title is public: anyone nearby can read it. Your optional name is sent only inside the encrypted session, to the people Settings > Your ID > Who sees my name allows.

To reduce exposure further: switch Bluetooth off when you do not need it, switch **Allow calls** off when you are
not expecting calls, leave your name and stream titles empty, keep "Hide phone name" on, and
verify the people you call often (QR code or safety code).

## Audio-only live streams

Go live > Audio only. A radio broadcast is encrypted between phones like a video
stream, but it is for anyone: every phone that completes the handshake can listen, and a phone that passes the stream
on hears it too.

- **Who learns what.** A listener (and a relay) learns the broadcaster's long-term identity key, because streams do
  not use a throwaway identity: broadcasts from different days can be linked to one phone, and a verified contact
  is recognised. It works the other way too: a listener's phone shows its own long-term identity key to the phone it
  gets the broadcast from, and both are readable by anyone nearby with a Bluetooth sniffer, so listening on different
  days can be linked to one phone as well. If you must not be linked to a broadcast, do not listen to it. The broadcaster sees how many listeners are connected, and a relay sees its one child; neither sees
  more of the chain. Titles from people you have not verified show as unconfirmed.
- **Signed audio.** The broadcaster signs every audio record with a key that is certified by its identity key, so a
  phone that passes the stream on cannot alter or forge the audio. It can still stop passing it on, or present
  itself as the source.
- **Announcements.** An audio-only offer carries a marker and no video size, so a scanner can tell a radio from a
  video stream. Nothing else about the content is visible.
- **The microphone.** Mute keeps the microphone open (Android's indicator stays on) and sends silence; nothing
  recorded before the tap is sent afterwards. Pause releases the microphone. The app never records a broadcast.
- **The lock screen.** The notification of a broadcast or a listening session says only "Broadcasting audio" or
  "Listening to a live broadcast", never a name or a title. The media controls carry no title either.

## Staying reliable in the background: permissions and what the app keeps on the phone

None of this is sent anywhere. These are the extra permissions and local records that keep calls and relaying working
when the screen is off, and what each one can and cannot reveal.

- **"Always run in the background" prompt.** The app declares `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` so it can open
  Android's own one-tap dialog, in onboarding, in Settings and when you switch on relaying or "Allow calls".
  It only opens the system dialog; nothing changes without your tap. It affects battery use, not what is shared.
- **Location type on Android 10 and 11 only.** Those versions deliver Bluetooth search results to an app in the
  background only if its foreground service declares the location type, so the app declares `FOREGROUND_SERVICE_LOCATION`
  and uses the type on Android 10-11, and only while the phone's Location switch is on. MeshLive never reads,
  stores or sends your location, and it requests no background-location permission. On those versions Android also needs
  the Location switch on for Bluetooth searches at all. When it is off, only Settings > Advanced > Help and feedback
  says so; the status dot still shows "Ready" and the Calls list only says "No one found yet".
- **Network permissions for the Wi-Fi option.** The app declares `CHANGE_NETWORK_STATE` and `ACCESS_NETWORK_STATE`, two
  install-time permissions without a prompt, so the optional Wi-Fi carrier can ask Android for a direct Wi-Fi link to the
  phone you are already connected to. They do not give the app internet access and are not used while the Wi-Fi switches are off.
  The app also holds Android's `INTERNET` permission, which any app needs to open a network connection of any kind, local ones included.
- **Nearby Wi-Fi devices and Location, for the Wi-Fi switches.** The app asks only when you turn a Wi-Fi switch on. On
  Android 13 and newer every Wi-Fi switch asks for "Nearby Wi-Fi devices". On Android 10 to 12, Android allows Wi-Fi
  discovery only with the Location permission, so *Wider reach over Wi-Fi* (Android 11 and 12) and *Wi-Fi Direct*
  (Android 10 to 12) ask for Location instead ("Precise" is needed, "Approximate" is not enough) and need the Location
  switch on. *Use Wi-Fi when it helps* asks for nothing before Android 13. The app never reads where you are.
- **Start after a restart.** `RECEIVE_BOOT_COMPLETED` is declared for an opt-in "resume relaying after a restart".
  The receiver is disabled in the manifest and stays inert unless you turn that option on, and Settings does not offer
  it. So after the phone restarts, MeshLive does not run, and nobody can call you, until you open it again.
- **Why the app stopped.** At each start the app reads Android's own record of why the previous process ended (for
  example the system freed memory, the phone maker's battery manager stopped it, or you swiped it away) and keeps the newest
  timestamp and the last unexpected reason on the phone, in its private storage and its local log, to explain a missed
  call in plain words. It is never uploaded, and an emergency wipe or clearing the app's data removes it.
- **Crash reports.** When the app closes because of an error, it writes a short report to its private storage. The report holds
  where in the app's code the error happened, the error type, the app version, the Android version and the phone model. Anything
  in an error message that looks like a name, quoted text, an address, an ID, a key, a number, a link or a file path is replaced
  before it is saved. At the next start the app asks whether to share it; Share opens Android's share sheet with Export
  diagnostics, and nothing is sent unless you pick an app and send it. The report is kept for 7 days for Export diagnostics.
  "Don't ask again" in that question deletes it and stops new reports. A wipe or clearing the app's data removes it.
- **Phone-update stamp.** To say "your phone was updated recently; re-check its background settings", the app stores the
  phone's build fingerprint, security-patch date and boot count locally (the same values any app can read). The stamp
  stays on the phone and is removed by a wipe.

## Contacting us

**Settings > Contact us** and **Report > Tell MeshLive** open a draft to **meshlive.dev@gmail.com** in
your own email app. Nothing is sent until you choose to send it. MeshLive adds no identifiers, logs or
attachments. We only see what you write and the sender information your email app sends, including
your email address. If no email app is available, you can copy the address instead.

**Report** by itself tells nobody. It blocks the person and hides them on this phone ("Blocked and hidden on this
phone"); MeshLive and the person you reported learn nothing unless you then tap **Tell MeshLive** and send the email
yourself.

## Measurements

Early results from the maintainers' own phones. They show the design works; they are not proof that nobody can track you.

### Scanner test

In the combined privacy session of 2026-10-11 (build 3996bfb2, four phones), four phones (Galaxy S24, ASUS, Galaxy M31 and Galaxy A15s) ran
rotating IDs with the identity-hiding handshake and pseudonyms. A passive-tracker tool read full Bluetooth logs of
three of them and the phones' own logs: 66 advertising sets decoded across three quarter-hour boundaries (23:15, 23:30
and 23:45 IST) inside a 40-minute window in which only rotating announcements were on air. Result: **no field
carried over any of the three boundaries**: no ID, route token, capability hint or page field repeated, and no phone
reused its advertising address for a new ID. The only allowed repeat was a stream's own logical token, which follows the
stream. The phones switched IDs within 80 ms of the boundary and stayed silent 1.3 to 7.7 seconds. With the tool's
default parameters the same data raised six findings, each explained and none a failure of rotation (two old-style
announcements from the previous test scene still on air, two address resolutions of a bonded phone pair, and two
restarts of a stream source 11 seconds before a boundary that kept the same ID). The same session ran a 4 minute 51
second call across a boundary, a stream found and viewed by its rotating ID (a viewer waiting across a boundary joined
7 seconds after the source returned), a lease exchange between verified contacts, and calls relayed through one phone.
Calls relayed through two phones formed no route in that session.

### Identity test

Bluetooth logs of two phones showed both phones' identities in the clear in a control call with the switches off, and none of them in three calls with a stranger (accepted, declined, accepted after an app restart).

### Not measured

Battery cost; reading the Bluetooth name on the A15s; Bluetooth logs of the A15s and
the whole time of the M31; an active attacker holding a contact's key, fake announcements, relays with the new
handshake, message exchange and abuse limits; and the residuals the tool reports but cannot remove: one phone alone in
an area can be followed by the timing of its announcements, and a few capability bits stay the same across IDs. This was
one session on four phones. 
## Security model and limitations

What MeshLive protects, and what it does not. The full rules are in [protocol reference](dev/protocol/README.md); to report a
problem privately, read [SECURITY.md](../SECURITY.md).

**Encrypted and authenticated.**

- Every phone has a persistent P-256 signing identity in the Android Keystore; there are no accounts or servers.
- Every Bluetooth connection starts with a signed handshake with fresh ephemeral keys. After it, records are sealed
  with AES-256-GCM, ordered and replay-protected. Tampering, replay, reordering and a wrong key fail closed. There is
  no plaintext mode and no silent downgrade.
- Media and call signals are additionally sealed end to end between the source and each viewer (or the two call
  endpoints). A relay in the middle forwards ciphertext without being able to read, forge or reorder it. A viewer that
  forwards is an admitted viewer and can decrypt its own copy.
- Store-and-forward message content is sealed to the recipient and stored encrypted at rest. The in-app emergency wipe
  deletes the keys first. With Messages on, chats use the Messaging v2 format: messages, files and voice notes are kept
  in an encrypted thread store and file vault on the phone, read receipts are off unless you turn them on, and a contact
  connected over an authenticated link can tell that your app supports the newer format (nothing about it is in any
  advertisement). Older messages stay readable as they were.
- Your call history (Recent) and the small memories the app keeps about phones it met are also encrypted at rest with a
  Keystore key: which phones speak the newer handshake, Classic Bluetooth addresses, which links were slow on 2M, the
  link speeds learned per phone, the people you reported, the live streams you hid, the chats you muted and the Call back
  of a missed call. Several of them name the other phone by the first characters of its ID; for a stranger in a call that
  is a throwaway ID, for a verified contact and for every phone on a live stream it is the long-term ID. Each is kept only
  as long as it is used. A learned link speed is deleted a day after it was measured while the app runs; if the app was
  closed or Android paused it, that happens when it runs again. A missed call's Call back is kept while its notification
  shows (checked when the app starts and at the next missed call or Call back) and at most a week, again counted while
  the app runs or at its next start. A mute stays until you unmute; reports and hidden streams stay until you delete
  everything. They stay in the app's private storage and are never sent.
  The emergency wipe, or clearing the app's data, deletes all of these.
- After you accept a call from a stranger, the other side must prove the identity of whoever holds the call (which may
  be a relaying phone) within seconds, more through relays, or your phone ends the call; until then nothing it sends is
  played and your camera and microphone stay off.
- When you call a stranger, your name (if you set one) waits until they answer and their phone proves who it is, and it
  is never sent to someone on your block list. When you answer a stranger, your name waits the same way: it goes only
  after their phone proves who it is, and never to someone on your block list. If the saved block list cannot be read, nobody is blocked until you reset
  it: Settings and Blocked people say "Block list can't be read" and offer Reset, and the app logs it. The app keeps
  calls working rather than stopping every call over a file it cannot read; nobody else can make that file unreadable.
- **No cloud or device-to-device backup.** The app opts out of Android backup (`allowBackup="false"`) and excludes all its data from cloud backup and from phone-to-phone transfer (Android 12+ data-extraction rules). Its keys live in
  the hardware-backed Keystore and cannot be restored on another phone, so a backup would be useless, and nothing
  about your calls, contacts or messages (for example call history by peer prefix) is sent to Google's backup.
- **Names on the lock screen.** Call and stream notifications show the other person's name only while the phone is
  unlocked; a locked screen shows just the app name and "Active" or "Incoming call". Settings > Features "Show caller
  names on the lock screen" is an opt-in that also shows the caller's name there.

**What is not hidden.**

- **Discovery is public.** Anyone with a Bluetooth scanner can see that a MeshLive phone is nearby, whether it offers a
  stream or accepts calls, and the stream's rough size and bitrate.
- **Rotating IDs, with limits.** Announcements carry a random ID that changes every 15 minutes (on by default), so a passive scanner could not follow a phone across the three ID changes we measured. The identity of
  everyone taking part in a live stream (source, viewers and phones passing it on), Classic Bluetooth addresses, paired phones, a lone phone's announcement timing and a few capability
  bits remain linkable; see [What nearby phones can see](#what-nearby-phones-can-see).
- **Bluetooth metadata.** Bluetooth names and addresses, the existence of a connection, and the size and timing of
  encrypted records are visible to a radio observer and to every relay on the path. That reveals "a call is
  happening" and often "voice only" versus "video". A first Classic contact with an unpaired phone can expose its name
  and address while it is discoverable.
- **No authenticated radio link on LE.** LE GATT and CoC (and Classic without pairing) have no Android-level
  authenticated link key. MeshLive's own handshakes do not depend on one, so an active attacker between the phones
  cannot read or forge content, but can drop, delay or jam traffic.
- **A prefix is not an owner.** Discovery binds a connection to a 48-bit prefix of the node ID, which a determined
  attacker could try to grind a key for. Compare the QR code or safety code with a contact in person; a verified
  contact whose key changes is refused by default.
- **Relays can disrupt.** A malicious relay or advertiser can drop, delay or refuse a stream or call, attract it to a
  longer route or report a false hop count, but it cannot impersonate an endpoint or open the media.
- **Names and titles** are signed and sent inside the encrypted session, but a stream title is also
  advertised publicly to everyone nearby. Leave it empty to advertise nothing.

**Not done yet.**

- **No independent security audit.** The primitives are standard (P-256, ECDH, HKDF-SHA-256, AES-256-GCM), but the
  protocol composed from them has not been reviewed by outsiders and is not a certification.
- Human ownership verification beyond comparing codes, live group re-key or revocation, and trust-on-first-use pinning
  of unverified peers are open work ([roadmap.md](dev/roadmap.md)).
- The emergency wipe cannot undo flash remanence, copies outside the app (backups, screenshots, the system log, other
  people's phones), or malware already on the phone.
- Video over Bluetooth is slow and its recovery after link loss has a gap; do not depend on it for safety.
