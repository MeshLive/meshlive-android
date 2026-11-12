# Design E: uniform adverts, capability fields behind the handshake

**Design review required. Designed 2026-10-11; not coded.** A version 5 advert hides who the phone is, but the rest of
its bytes are the same in every epoch of one phone: the transport bit, the two codec bits, the relay-helper flag, the
relay-consent and dual-listening bits, and for streams the resolution, frame rate and bitrate. Together they are a few
bits of per-phone state that split a crowd into groups (GAEN and DULT require everything that differs between devices to
rotate with the address or be identical for all). PROTOCOL.md "Fields that stay constant across epochs" lists the
inventory and the reductions already made.

What has to stay in the advert: kind, transport, accepting/paused/full, route cost, sequence. What can move behind the
handshake (its hello already carries the codec masks, bitrate and `maxChildren`): the codec masks, the helper flag, the
consent bit, the dual-listening hint and the stream's resolution, FPS and bitrate. The cost: a caller can no longer hide a
phone with no common codec before connecting (an incompatible call fails after the handshake with a clear message, after
one connection of 2-4 s), and a relay helper learns the consent only on connecting.

| Option | What changes | Tradeoff |
| --- | --- | --- |
| A | Measure first: count the distinct version 5 layouts per kind on the reference phones before flipping defaults | No code now; the decision comes while changing version 5 is still free |
| B (recommended if more than one layout is populated) | Redefine version 5: the capability summary of calls and streams becomes a constant, and the codec check, helper flag, consent bit and dual hint move into the signed hello | One more failure path for calls with no common codec; a protocol version change |
| C | Leave the bits and disclose them as a few bits of quasi-identifier | Nothing to build; a crowd is split into a few groups |
