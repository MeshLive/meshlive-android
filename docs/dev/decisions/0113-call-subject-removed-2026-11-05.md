# Call subject removed (2026-11-05)

The optional call subject (signed label kind 3, shown under the caller's name while ringing) is removed from every
client, design decision: it was unnecessary overhead. Since 2026-10-17 no client asked for one when dialling, so only a
debug launch could still send it, while it kept a second label kind, its own timing rule and a ring-screen line in the
code. Rules: no client sends a call subject or shows one; kind 3 is retired and never reused; a receiver decodes a kind 3
label from an older build and drops it silently like an unknown kind (no error, never shown, never forwarded), so calls
with older versions are unchanged apart from the missing line. The `BNL1` text length limit stays 64 bytes so those
labels still decode. Stream titles are unchanged. Wire rules: PROTOCOL "Peer labels".
