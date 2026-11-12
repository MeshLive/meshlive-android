# User guide

For people who use MeshLive: how to install, update and verify it, what it can do, how to use it, and common questions. Privacy is covered in [privacy.md](privacy.md).

- [Install, update and verify](#install-update-and-verify)
- [Features](#features)
- [Use the app](#use-the-app)
- [FAQ](#faq)

## Install, update and verify

### Install

On Google Play: tap **[Get it on Google Play](https://play.google.com/store/apps/details?id=io.github.meshlive.app)**. Google Play installs and updates it for you. Or install the file from the releases page:

1. On your phone, tap **[Download MeshLive](https://github.com/MeshLive/meshlive-android/releases/latest/download/MeshLive.apk)**.
2. Open the file. If Android asks, allow your browser to **install unknown apps**, then tap **Install**.
3. Open MeshLive and allow **Nearby devices** (on Android 10 and 11: **Location**), **Microphone** and, on
   Android 13 and newer, **Notifications**. It asks for the **Camera** the first time you use video.

The phone must run Android 10 or newer, and both people need MeshLive. The rest of this section covers updates (automatic, inside the app, or from a friend with no internet), the file list, and how to check a download.

### Other ways to install and update

#### Get updates automatically

Install [Obtainium](https://github.com/ImranR98/Obtainium), then tap
**[Get it on Obtainium](https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/MeshLive/meshlive-android)** (or add `https://github.com/MeshLive/meshlive-android` in Obtainium by hand).
Obtainium checks the releases page for new versions and updates MeshLive for you.

#### Updates inside the app (release-page builds)

MeshLive can check for a newer version itself, but only after you say yes: the first time you open it, it asks "Check for updates when online?". Until you answer yes it makes no internet request at all. With yes it asks GitHub's release API (`api.github.com`) at most once a day while you are online; GitHub sees your phone's internet address and that you asked about MeshLive, and nothing else is sent (no account, no cookie, no device or version information, no tracker). It downloads nothing until you tap, and it installs a file only if all of these hold: it is signed with the MeshLive release key that is built into the app (the fingerprint under "Verifying a release" below), it is newer than your copy, it is for your phone type, and its SHA-256 equals the one the release publishes. Android then asks you to confirm; nothing is ever installed silently or downgraded. Settings > Check for updates changes your answer (a "yes" restored from a backup onto another phone is dropped and asked again: the answer has a marker in the no-backup folder). While it is off, nothing on the Updates screen can contact GitHub. Google Play and F-Droid builds contain none of this (the store updates them) and do not ask for the install permission.

#### A friend has a newer version

When two phones connect, each tells the other which MeshLive release it runs (in the signed greeting phones already exchange; never in what a phone broadcasts). On a live stream that greeting is not encrypted, so anyone listening nearby can read the release number too. If a phone this phone connected to (a call, a stream or a relay you started; a stranger who connects to you is not counted) says it runs a newer release you see a quiet note on the home screen. It is only that phone's claim: nothing is downloaded or checked until you pick a file yourself. It explains how to get the file from that phone (Settings > About and updates > Share this app on its screen, or any other way you like), and **Choose the file** checks it the same way before offering to install it. Settings > Tell me about newer versions nearby turns this off, in both directions, with one exception: while "Use Wi-Fi for faster connections" is on, your phone still tells its release on live streams and when it relays a call.

#### No internet at all

A friend who already has MeshLive can send it to you: in MeshLive's **Help**, they tap **Share this app** and send it with Quick Share, Nearby Share or Bluetooth. The share screen shows the APK hash and the signing-certificate hash, so you can compare them with the release page. An experimental "Share app nearby" screen serves the file over a local Wi-Fi Direct link instead.

#### Other files

On the [releases page](https://github.com/MeshLive/meshlive-android/releases): MeshLive is an
unofficial open-source build (GPL-3.0-or-later), signed with the project's own key (Google Play delivers MeshLive with the same key). Each
release has one APK per CPU type (`MeshLive-<version>-<abi>.apk`); `MeshLive.apk` is the same file as the
arm64-v8a one.

| File | Use it on |
| --- | --- |
| `MeshLive.apk` or `...-arm64-v8a.apk` | Almost every phone since about 2016. **Pick this if unsure.** |
| `...-armeabi-v7a.apk` | Older or 32-bit-only ARM phones (some Android Go models). |
| `...-universal.apk` | Both in one larger file, for when you do not know. |

Updating works only from an APK signed with the same key: do not mix builds from other sources on one phone.

Before installing, check the download as described in [Verify a release](#verifying-a-release).

### Verifying a release

A release is a set of per-ABI APKs signed with the project's release key, published with `SHA256SUMS` and
`SOURCE-MANIFEST.txt`. The signature is what proves who built the APK; the manifest and its attestation prove which
source it was built from. Signing happens only on the maintainer's machine: CI
(`.github/workflows/release-attest.yml`) holds no signing key and builds no APK.

These verification commands run on a computer, not on the Android phone. Download the APK and the verification files (`SHA256SUMS` and `SOURCE-MANIFEST.txt`) from the same [release](https://github.com/MeshLive/meshlive-android/releases/latest) to that computer first. The certificate check needs Android SDK Build-Tools (`apksigner`); the digest check uses `sha256sum`; and the attestation check needs the [GitHub CLI](https://cli.github.com/). If you cannot install these tools, do not treat skipping a check as a pass.

Quick check with the [GitHub CLI](https://cli.github.com/) (replace the file name with the one you downloaded):

```bash
gh attestation verify MeshLive-<version>-universal.apk --repo MeshLive/meshlive-android
gh attestation verify SOURCE-MANIFEST.txt --repo MeshLive/meshlive-android
```

A passing result says this repository's `Release attestation` workflow recorded that exact file digest for a tagged
commit. It does not replace the signing-certificate check below. The full procedure follows.

Signing certificate SHA-256 (also in each release's notes and `signing-cert.sha256`):

```
2E:EF:C0:5A:44:45:22:52:9D:9F:75:D7:EB:CE:FD:08:86:47:07:F2:1A:79:04:1D:B5:1E:BF:F8:36:49:E2:13
```

1. **Certificate.** `apksigner verify --print-certs app.apk` (Android SDK build-tools) must print
   `Signer #1 certificate SHA-256 digest` equal to the fingerprint above, with no other signer. The app's About screen
   shows the same fingerprint for the installed copy (a fuller in-app release check is planned; see [roadmap.md](dev/roadmap.md)). A different fingerprint means a different publisher: do not install it.
2. **APK digest.** `sha256sum -c SHA256SUMS --ignore-missing` against the `SHA256SUMS` file of the release.
3. **Source manifest attestation.** `gh attestation verify SOURCE-MANIFEST.txt --repo MeshLive/meshlive-android`
   confirms the manifest was produced by this repository's workflow. If the APK was uploaded before publishing,
   `gh attestation verify MeshLive-<version>-<abi>.apk --repo MeshLive/meshlive-android` confirms the same digest was
   recorded. The attestation is a record of the digest; trust in the APK itself still comes from step 1.
4. **Source.** `SOURCE-MANIFEST.txt` lists the git commit, tree hash and the SHA-256 of every tracked file. Clone,
   `git checkout <commit>`, then `python tools/make_release.py --manifest-only --output mine.txt` and
   `diff mine.txt SOURCE-MANIFEST.txt` (must be identical). To check one file,
   `git show <commit>:<path> | sha256sum`. The manifest binds the source set; for the APK bytes see the reproducibility
   check under "Distribution and releases" in [architecture.md](dev/architecture.md#distribution-and-releases) (verified for the unsigned APKs on one OS only, so it is not yet a published claim).

## Features

- **Live video broadcast.** Stream your camera (AV1, VP9, HEVC or H.264 video, mono Opus audio) to nearby phones.
  The app picks the most efficient video format each phone supports. Pause, resume and stop with authenticated
  state. Choose the encoder, rate, resolution, FPS and buffer in Settings; the app shows requested and effective
  values and never changes them silently.
- **Relay chains.** A viewer can pass the stream to one more phone ("Relay video for others",
  on by default, pauses itself on low battery or in Battery Saver). Each phone talks only to its parent and its direct
  children, and the receiver checks that the stream really comes from the original source.
- **Private calls.** Voice or video calls between two phones, started from a list of people nearby. Both directions
  are independent; mute, camera off and flip only affect your own side. Call audio focus, ringer and Do Not Disturb
  rules, and a proximity screen-off lock are built in.
  When the other person's picture has a different shape from your screen (a laptop camera on a phone held upright),
  the whole picture is shown with black bars; similar shapes fill the screen. Double-tap their video to switch for that call.
- **Three Bluetooth transports.** LE GATT (the default, needs no pairing), Bluetooth Classic (higher throughput,
  one-time pairing) and LE L2CAP CoC (experimental). Discovery is always Bluetooth LE.
- **Authenticated and encrypted.** Every phone has its own signing key. Links use signed handshakes and ordered
  AES-256-GCM records, with a second end-to-end layer so a relay in the middle cannot read or alter your media.
  Compare a QR code or a safety code in person to verify a contact.
- **Voice calls** (hold Mute to talk, like an intercom), with a ringing tone, answer and decline from the
  notification and over the lock screen, and the camera pausing while the phone is at your ear. On Android 14
  and newer some phones turn off full-screen call alerts for apps that are not the phone's dialer; the Calls
  screen then asks once ("Allow full-screen incoming calls") and Settings keeps the link to Android's page.
- **A call can move to a new Bluetooth link.** On a direct Bluetooth LE (GATT) call, if one direction goes quiet (you can be heard but cannot hear, or the other way round), the app can set up a new link to the same person by itself and keep the same call going, with the same keys. It is not a redial and it never calls anyone else. If the new link cannot be set up within about 12 seconds, the call ends as before. Relayed, Classic and L2CAP calls are not moved.
- **Wi-Fi (optional, off by default).** The top of Settings has one row, "Use Wi-Fi when it helps", with two options under
  it. "Use Wi-Fi when it helps" lets calls and live streams move to Wi-Fi after they connect over Bluetooth, when both phones support it;
  Bluetooth stays connected and takes over if Wi-Fi stops. Turned on during a
  call or live stream, it works from the next one. A call that stays on Bluetooth says why in Link health ("Wi-Fi not
  used: ..." with the reason, such as battery saver, heat, a 2.4 GHz network, a hotspot or the other phone). While a call or stream is on Wi-Fi, the other phone can reach other
  apps on your phone that accept network connections; on a live stream that other phone can be any stranger who watches
  your stream or whose stream you watch, so keep the switch off when you stream with people you do not know. While it is
  on, anyone listening near a live stream you take part in, and the phones next to yours when it relays a call, can see
  which MeshLive version you use. On Android 11 and 12 it also needs "Wider reach over
  Wi-Fi". "Wider reach over Wi-Fi" also finds and connects to people over Wi-Fi, even when Bluetooth is blocked; it uses
  more battery. On Android 11 and 12 it finds people over Wi-Fi but connects only over Bluetooth, because the phone cannot
  check that a Wi-Fi link would use the 5 GHz band. Turning it off stops Wi-Fi discovery at once; a Wi-Fi link already open stays until its call or stream
  ends. "Wi-Fi Direct" is for phones without Wi-Fi Aware; turning it on shows what it does and this phone's own Wi-Fi Direct
  name, as other phones see it, and waits for "Turn on"; turning it off moves Wi-Fi Direct calls and streams back to
  Bluetooth at once (a call whose Bluetooth link is not working stays on Wi-Fi until Bluetooth is back or the call ends,
  with a notice). Turning a switch on asks for the Android permission it needs: "Nearby Wi-Fi devices" on Android 13 and
  newer. On Android 10 to 12, "Wider reach over Wi-Fi" (Android 11 and 12) and "Wi-Fi Direct" ask for Location instead
  (choose "Precise") and need Location turned on, because Android allows Wi-Fi discovery only then; the app never reads
  where you are. If you refuse, the switch stays off, and a switch whose permission was removed later says "Not active". Android 10 phones cannot use Wi-Fi Aware. What each switch shows to nearby devices is in the privacy
  notes.
- **Experimental, off unless you turn them on in Settings > Experiments: store-and-forward messages** ("Chats (beta)":
  text, voice notes, photos between accepted contacts) and **calls through up to four relays** ("Relayed calls (beta)").
- **Names and titles.** An optional name (Settings > Your name) and an optional stream title on Go live. Leave them
  empty if you like. Your name is never broadcast: it goes, encrypted, only to the people "Who sees my name" allows. A
  stream title is public: everyone nearby can read it.
- **Audio-only live (radio).** Go live with sound only: announcements, an event, a neighbourhood
  channel. It uses a fraction of the Bluetooth capacity of video, reaches more phones, and keeps playing with the
  screen off. Choose it from Go live.
- **Privacy by default.** A rotating Bluetooth ID, an identity-hiding call handshake and throwaway identities for
  strangers are always on; they have no switch. They protect calls. Live streams are different: while you send, watch
  or pass on a live stream (video or audio-only), anyone nearby with radio equipment can record your phone's long-term
  ID. If you must not be linked to a stream, do not watch it or pass it on. Details: [privacy.md](privacy.md).
- **One name .** "Your name" (Settings) is the name others see, and "Who sees my name" decides who: people you call or who call
  you, only your contacts, or nobody. It is sent encrypted inside the call, never broadcast; a stranger you call gets it only after they answer and their phone proves who it is, and never if you blocked them; a stranger you answer gets it the same way, only after their phone proves who it is. Your QR code carries it, so a new
  contact is saved under it (they can rename you). Phones whose name you cannot see show a made-up name such as "Blue Otter".
  "Hide phone name" (Settings > Privacy) is on too, after you say yes on the first start.
- **Offline app sharing.** Hand the installed APK to a nearby phone (see [No internet at all](#no-internet-at-all)).
- **Delete everything.** Settings > Delete everything (the red row at the bottom) deletes the app's keys and data:
  type DELETE, then tap Delete everything. For a faster way, turn on Settings > Privacy > "Triple-tap to delete
  everything" (off by default). After that, tap the word "MeshLive" at the top of the Live tab three times quickly: a
  3-second countdown with Undo appears, then everything is deleted. It works only on the Live tab.
- **No tracking.** No account, no ads, no analytics, no internet connection for media.
- **Device-family policy registry.** Known Bluetooth-stack and codec quirks are handled in one registry where each
  entry cites official sources and can be switched off.

## Use the app

Install the same APK on the phones. Grant Nearby devices (Location on Android 10 and 11), Microphone and, on
Android 13 and newer, Notifications through Android's prompts on the first start, and enable Bluetooth. The Camera prompt comes the first
time you use video.

- **Tabs.** The bottom bar has Live (the live streams near you, with Go live pinned at the bottom), Calls (Nearby:
  everyone in range with voice and video buttons; Recent: your call history, tap to call back), Contacts (the people
  you saved, with Add contact pinned at the bottom) and Settings (a short list: your name, Allow calls, Relay video for
  others, Video quality, with pages for Video and Privacy, an Advanced label further down with five rows:
  Connection, Manual video, Playback, Experiments and Help and feedback, each opening one page, then About and updates
  (About this app, the update switches, Check now, Share this app) and a red Delete everything at the bottom). The small status at the top right of every tab is a green dot when
  all is well, or the one thing that needs you (Bluetooth off, permissions, Hidden, battery saver, phone hot); tap it
  for details. Your code and ID are shown only on Add contact (Show my code).
- **Hide your phone's name.** On the first start, after the permissions, one step asks whether to hide your phone's
  name. **Hide name** (the default) renames the phone to "Phone" for Bluetooth: nearby devices no longer see its real
  name over Bluetooth (the Wi-Fi Direct switch and Share app nearby still show the phone's own device name), and a Mac
  or PC can call you without a pairing prompt; headphones, your car and other Bluetooth devices also show "Phone".
  **Keep my name** leaves it alone. Change it any time in Settings > Privacy > Hide phone name; turning it off restores
  your original name. If MeshLive may not use Nearby devices, the switch shows off with the reason. People updating
  from an older version who never chose are asked once.
- **Keeping the app helping in the background.** On the first start a short step ("Work with the screen off")
  explains the cost and offers Android's one-tap "allow background running" dialog; "Not now" is fine and the same offer
  comes back once, with the reason, when you switch on Relay video for others or Allow calls, and any time from
  Settings. A phone that relays can lose battery noticeably faster and may get warm in a bag. The top of Settings > Advanced > Help and feedback shows only what
  needs you: the battery allowance, the phone maker's own battery manager (with the steps for your brand), a recent phone
  update that may have reset those settings, a Bluetooth search the phone maker is pausing, and a note when Bluetooth
  itself looks stuck ("turn Bluetooth off and on"). A healthy phone shows "No background limits found".
- **Closing the app stops calls.** You can be called only while MeshLive is running. With Allow calls on, leaving it
  with the Home button keeps it running, and its notification says "Reachable for calls". If you swipe MeshLive away from the recent-apps
  list, if the phone maker's battery manager stops it, or after the phone restarts, your phone stops announcing itself:
  nobody can call you, a stream you were sending, watching or passing on stops, and a call in progress is cut (the
  other person sees a lost connection, not a hang-up). Nothing tells you this has happened; the sign is that the
  notification is gone. Open MeshLive again to be reachable.
- **Android 10 and 11: turn Location on.** On those versions Android finds nearby phones over Bluetooth only while the
  phone's Location switch is on, and delivers results in the background only through a notification of type "location".
  MeshLive never reads or stores where you are. If Location is off, nothing on the main screens tells you: the status
  at the top right still shows "Ready" and Calls says "No one found yet". Only Settings > Advanced > Help and feedback
  says "Turn on Location so Android lets this phone find nearby phones", with a Fix button that opens Android's
  Location settings. If nobody shows up on an Android 10 or 11 phone, check the Location switch first.
- **Video quality: Auto.** Auto chooses the picture size, frame rate and bitrate from how much the Bluetooth
  link to the other phone can carry, starting at 360p for a typical link, and moves up or down while you stream
  or talk; on a very weak link it keeps the audio and drops the video. (Experimental: the measured Auto ladder
  is off in normal builds until its phone tests pass.) Manual video settings under Advanced stay exactly as you set
  them, and the app warns when a size, frame rate and bitrate you picked are too far apart to look clear.
- **Call and live controls.** One row of round buttons with the red End in the middle. A call: Mute, Camera, End, Flip,
  Speaker (Flip works once the camera is on). Speaker switches between the earpiece and the loudspeaker; when a headset is plugged in
  it opens a short list of where the sound can play. A video live: Mute, Camera, End, Flip, Pause. Mute stops your sound and the video
  goes on; Camera off stops your picture and the sound goes on. Viewers see "Muted" or "Camera off" when you and they use a version
  that supports it (Settings > Experiments > Show Muted and Camera off to viewers; off by default). An audio-only live has Mute, End and
  Pause, and Switch to video ends the radio and starts a video live with the same title.
- **Broadcast.** Tap Go live, then Go live again on the camera screen. Viewers see it under Live. Pause keeps the authenticated link alive; Resume
  continues media; Stop sends an authenticated End.
- **Audio only (radio).** Go live asks Video and audio
  or Audio only (the last choice is remembered). Audio only has no camera: add an optional title, pick Voice, Low data
  or Music, tap Start, and watch the level meter and the listener count. Mute keeps the microphone open and sends
  silence, so Android's microphone indicator stays on; Pause stops the microphone; End stops the broadcast. The
  first time, the app tells you that the livestream is public: anyone nearby with MeshLive can listen, it can be
  passed on to people further away, and listeners, and anyone nearby with radio equipment, can tell it is your phone.
  The same is true of each listener's phone, and the listening screen says so ([privacy.md](privacy.md#audio-only-live-streams)). A reminder appears after 30 minutes without a listener or two hours on the
  air, and the broadcast stops five minutes after an unanswered reminder. Listeners see the broadcast under Live with a
  radio icon and "Audio only"; tapping it opens a listening screen (title, route, state, level meter, Mute, Stop,
  Block this person) that keeps playing with the screen off. The volume is your phone's media volume; the app never
  changes it. An older MeshLive shows the row as not supported.
- **Receive and forward.** Saved sender settings stay independent from reception. Settings "Relay video for
  others" decides, at each join, whether a viewer offers one downstream child. It is on by default
  for normal launches and pauses itself at that join below 20% battery (when not charging) or in Battery Saver.
- **Private call.** Open the nearby-people browser on both phones, select the other identity and accept or decline on
  the callee. Capture starts after acceptance. Both endpoints send independently selected video and audio.
- **Contacts and names.** Contacts > Add contact (or the lock in a call) checks someone in person by scanning
  each other's QR code or comparing the safety code. When it matches, the app asks "Save contact as"; the name stays
  on your phone, and leaving it empty keeps their ID. On Add contact your code works once and for five minutes:
  one of you scans the other's code, then the other scans back. After both scans your phones recognise each other, so
  Contacts calls them directly whenever they are nearby. If only one of you scanned, Contacts says "Ask <name> to scan
  your code too" and offers Show my code. The Contacts tab lists the people you verified, shows each
  full ID, renames or forgets them, and "Add contact" starts the same check. Everyone you have not verified keeps
  showing as an ID or, for phones that change their ID, as a made-up two-word name such as "Gold Fox" (a name for picking a
  phone out of a room, not proof of who it is). A name another person gave themselves shows with a tilde, "~Rahul"; the tilde
  means they said it and nobody checked. Your own name for a contact always wins. 0.2.0-beta phones do not show these
  names, cannot read the new verification code and do not take part in the names features. If you scan the code of a
  phone that still shows the older kind of code (MeshLive 0.3.0 or older), the contact is saved only as "Checked by
  code": your phone does not recognise that person nearby and Contacts cannot call them. Ask them to update, then scan
  each other again.
- **Messages (Experimental).** With Settings › Experiments › Chats (beta) on, every person in Contacts is also a conversation: the row shows the
  last message (or Photo, Voice note, File), its time, how many are unread and, for your own last message, a small mark for its state.
  Tap a row to open the conversation. The top bar has Back, the name with the verified check, "Nearby" or "Not nearby", voice
  and video call buttons, and a menu for the conversation (disappearing messages, read receipts, the contact's own options,
  comparing safety numbers, delete). Yours are on the right, theirs on the left. A message you write while the other phone is away
  waits and goes when you are near each other; the mark beside its time tells you where it is (a clock: waiting, an arrow: sending, one
  check: delivered, two checks: downloaded, two bright checks: read) and a tap on it says so in words. A message that could not be
  delivered says "Not sent" with Retry. The paperclip sends a photo or video (the picture is made smaller and loses its location unless you
  switch on "Send original"; a JPEG original still loses its location) or any file up to 64 MB. Hold the microphone to record a voice note
  (up to two minutes), slide left to cancel. A file you receive shows its name and size: tap Download for a big one, Show picture to see a
  picture, Open for a document, audio or video your other apps can show, and Save for anything else (programs are never opened from
  Messages). Nothing downloads or shows by itself unless you allow it: Settings > Privacy has Download files automatically (up to 8 MB
  by default) and Read receipts (off; both of you must turn them on). A banner appears when something stops messages: a person who is not yet a
  contact, a safety number you still need to compare, keys that changed (nothing is sent until you compare again or accept the new keys), or
  an older app on their side. A new message gives one notification per conversation (channel "Messages" in the system settings, where you choose its sound); a locked phone shows only "New message" unless you allowed names on the lock screen, and the text appears only on an unlocked phone. Tap it to open that conversation; "Mark as read" appears when the notification showed the whole message. Android asks once for permission to show notifications. With the switch off, Contacts keeps its single contact sheet.
- **Blocking calls.** Settings > Block unknown callers (off by default) declines, without ringing, every call from someone who is not a verified contact; it shows in Recent as "Blocked call", and the caller sees an ordinary decline. It does not stop a stranger from tying up your phone: each declined attempt keeps your phone busy for 3 to 7 seconds (so that the caller cannot tell the decline was automatic), and while it is busy nobody else can call you. Someone who keeps calling can keep you hard to reach for your real contacts; the only sign is the "Blocked call" rows in Recent. Settings > Blocked people lists everyone you blocked and unblocks them; block from a Recent row (press and hold), from a contact, or with "Decline and block" on an incoming call. A blocked person's calls are declined, their own live streams and radio are hidden from the list (a copy another phone relays can stay listed, because the list only knows who sends a stream it hears directly; opening it stops it as soon as the app knows who is sending it, and one that is playing stops too), your phone exchanges no messages with theirs, relays nothing for them and sends them none of your streams, and if they were a contact who could recognise your phone nearby, that stops too (your other contacts then recognise your phone again after your next call with each of them). A block follows the person's real ID, which a phone shows before ringing only to its verified contacts and to phones that do not hide their ID; a stranger calls under a one-time ID that changes every call, so for strangers use Block unknown callers. In plain words: blocking a person who is not your verified contact does not stop their phone from ringing yours. "Report" (in the Block dialog and on a nearby person's row) does the same as Block and also keeps the person hidden on your phone; it tells nobody, unless you then tap Tell MeshLive and send the email yourself. If you answer a stranger who turns out to be someone you blocked, the call ends within a few seconds without your camera or microphone having been on, and Recent shows a blocked call; if you call a stranger who turns out to be someone you blocked, the call ends within a few seconds before your phone shows them who you are (your name is not sent to them either). If you call someone whose phone shows it is someone you blocked, their phone rings briefly and the call then ends within a few seconds like any call that is not answered; your name, camera and microphone are not sent to them. If the saved block list can't be read, Settings and Blocked people show "Block list can't be read" with Reset: until you reset it nobody is blocked and calls ring as usual, and Reset unblocks everyone so you can block people again.
- **Transport and pairing.** LE GATT is the pairing-free default. Classic needs the two phones paired once in Android
  Bluetooth settings (a mutual bond: discovery is BLE, whose rotating private addresses only a bond can resolve, and
  Android hides a phone's own Classic address from apps), unless both phones turn on the experimental Classic without
  pairing. Classic without pairing cannot call a stranger who hides their ID (the app could not tell which phone answered);
  use LE for that. LE CoC is experimental. The app does not silently replace the selected transport.

| Setting | Meaning |
| --- | --- |
| Encoder / allocation | Offered encoder and Automatic, CBR, VBR or CBR-FD; encoding and decoding support are separate. |
| Broadcast rate | Requested video rate. The picker maximum is this phone's measured link minus audio and headers (Classic 1500, CoC 1200, GATT 1100 kbps until a first measurement); a choice above the measurement is labelled, never lowered. In Automatic the sender lowers the effective rate only on sustained backlog or late-arrival reports from its own child. |
| Playback buffer | Adaptive local playout ranges: Low 100-600, Balanced 250-1200, High 500-2500 ms, Auto 250-2000 ms (default). The delay grows only while this link delivers late and shrinks after calm; frames and audio are never dropped to stay inside it. Calls keep their own, shorter budgets. |
| Fill / full frame | Aspect-preserving local crop or fit; coded size and rate stay the same. |
| Thermal reduction | Source-local limits; requested versus effective settings stay visible; a critical thermal status stops streaming and turns call video off, while voice calls still ring, start and continue. |

Normal broadcast audio is mono 48 kHz Opus at 32 kbps. Some phone families carry visible call protections that can
reduce effective video or audio and change buffering (older Qualcomm Android 10-12 stacks are the main case). They
are policy limits that still need measured challenges, not hardware ceilings; the reasons are in
[decisions](dev/decisions/README.md) under "Legacy-family protections".

## FAQ

**Does it need the internet or an account?** No. There are no accounts and no servers: calls and live streams go
directly from phone to phone, never through the internet. They use Bluetooth only, unless you turn on a Wi-Fi option
(Settings > "Use Wi-Fi when it helps"; it and its two options are off by default). With one on, a call or live stream
can also use a direct Wi-Fi link between the two phones (Wi-Fi Aware or Wi-Fi Direct), still without a router or the
internet; see "Wi-Fi (optional, off by default)" above. The app declares Android's internet and Wi-Fi permissions for
three things: those direct Wi-Fi links (Android requires the internet permission for any network connection, even one
between two phones), the experimental "Share app nearby" screen, which serves the app file over a local Wi-Fi Direct
link, and, in release-page builds, the update check, which runs only after you say yes (see "Updates inside the app").
Android asks you for a Wi-Fi permission only when you turn a Wi-Fi switch on or open the share screen: "Nearby Wi-Fi
devices" on Android 13 and newer, Location on Android 10 to 12 (for "Wider reach over Wi-Fi", "Wi-Fi Direct" and the
share screen). It is used to find the other phone over Wi-Fi and connect to it; the app never reads where you are.

**Why is my call on Wi-Fi?** If you turned on Wi-Fi in Settings, a call can move to Wi-Fi after it connects. The call screen's
Link health sheet says "Connection: Wi-Fi" while it is. If Bluetooth is not connected the call stays on Wi-Fi until
Bluetooth is back or the call ends, even if you turn the Wi-Fi switch off meanwhile; the call shows a notice.

**How far does it reach?** Bluetooth reaches one site, not a city: typically tens of metres in the open and much
less through walls, and it depends on the two phones. We have not yet run a controlled range test (see
[roadmap.md](dev/roadmap.md)). An experimental LE Coded PHY option is meant for long-range, low-rate links such
as voice, but both phones must support it. Relaying extends range, at a cost in quality.

**How many hops can a stream take?** Each forwarding viewer passes the stream to one more phone, and a source can
serve several direct viewers within its measured link budget. Every hop spends airtime twice, so quality drops
quickly: in a multi-phone relay test (2026-10-08) relayed or fanned-out viewers played at 0.9-7.6 FPS at 300 kbps.
Short chains of low-rate video are realistic; long chains of high-rate video are not. Private calls can cross up to
four relays in the protocol, but the call relay is experimental and off unless you turn on Settings > Experiments >
Relayed calls (beta).

**Does it work with an iPhone or a Mac?** Only in part, and not out of the box. The iPhone and Mac app is a source
build that you compile yourself (see the Download table in the [README](../README.md)). Android
looks for iPhones by default ("Find iPhones" under Settings > Advanced > Connection); an iPhone whose app is in the
background cannot be found. The Apple app as currently published has its three privacy options (rotating IDs, hidden
identities, pseudonyms toward strangers) off, and with them off it does not see an Android phone running this version
and neither can call the other. Turn all three on in the Apple app first. This Android version cannot turn its own
off to meet it.

**What about older MeshLive versions?** Update both phones when you can. A phone on 0.4.4 or older whose owner turned
its private (rotating) Bluetooth ID switch off does not see a phone with this version at all, and nothing on that
phone says why; your phone still lists it. A version from before 0.2.0-beta, or an older version with its
identity-hiding switches off, cannot call or be called by this version. A contact added by scanning the code of a
0.3.0 or older phone is not recognised nearby (see Contacts and names above).

**What about battery?** Bluetooth LE is frugal, but live video keeps the camera, encoder and radio busy, so expect
substantial drain while broadcasting. Forwarding pauses itself below 20% battery (when not charging) or in Battery
Saver. With Battery Saver on, or when the phone is already warm, calls and streams start on the phone's video hardware
instead of the app's own software encoder. We have not yet run matched unplugged endurance tests, so there is no
battery-life figure to quote.

**Why does the app ask to run in the background, and why is Location needed on Android 10 and 11?** Passing a call or
video along with the screen off only works while Android lets the app keep running; the allowance is optional and can be
undone in Android's battery settings. On Android 10 and 11 the system hides Bluetooth search results from apps unless
Location is on, even though no location is used. Details: [privacy.md](privacy.md).

**Which phones were tested?** The seven reference phones in [devices.md](devices.md): Samsung S24, S22, A55 and M31, a
Redmi (Xiaomi), an ASUS and an OPPO A15s, running Android 10 to 16. Other phones may work or may hit Bluetooth-stack
bugs. Please send results with the [device test report](https://github.com/MeshLive/meshlive-android/issues/new?template=device-report.yml) form and, if you can, an Export diagnostics block.

**Why is GATT the default? Why does Classic ask me to pair?** LE GATT works on the most phones without pairing, but it
is the slowest. Classic has the highest throughput but needs a one-time mutual pairing (an experimental option removes
that). LE CoC is experimental because some phone stacks have serious bugs with it.

**Can I give the app to a friend with no internet?** Yes; see [No internet at all](#no-internet-at-all).

## Glossary

- **GATT (Generic Attribute Profile):** Bluetooth Low Energy communication used by MeshLive by default. It works without pairing on most phones, but has lower throughput than Bluetooth Classic.
- **CoC (L2CAP connection-oriented channel):** a direct Bluetooth Low Energy data channel, an experimental transport in MeshLive. Support varies across phone Bluetooth stacks.
- **Relay:** A phone that receives a call or stream and forwards it to another nearby phone.
- **Hop:** One Bluetooth link from one phone to the next. A relay chain adds a hop each time the stream passes to another phone.
- **Attestation:** A signed record tying a file digest to a build workflow and source revision. It provides build provenance; it does not replace checking the APK signing certificate.

## Contact us

For questions, problems or safety concerns, tap **Settings > Contact us** to draft an email to
**meshlive.dev@gmail.com**, with subject **MeshLive** and an empty body. After reporting someone locally,
tap **Tell MeshLive** in the confirmation to draft an email with subject **MeshLive: report a concern**
and body **What happened:**. Local reporting blocks and hides on your phone; it does not send a report
by itself. Neither draft adds identifiers, logs or attachments. Review and send from your own email app.
If no email app is installed, the dialog shows the address and offers **Copy**.
