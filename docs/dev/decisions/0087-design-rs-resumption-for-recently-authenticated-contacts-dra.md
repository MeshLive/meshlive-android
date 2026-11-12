# Design RS: resumption for recently authenticated contacts (draft 2026-10-26, not reviewed; recommendation: do not build yet)

**Idea.** After a completed BMH2/BMH3 with a verified contact, both phones keep a short-lived resumption secret. The next connection to that contact within, for example, 10 minutes runs a 1-RTT PSK plus ECDHE handshake: no signatures, and fresh ECDH for forward secrecy.

**Why not now.**
- Measured gain is small: the whole hop handshake is a median 180 ms (p90 255 ms), most of it round trips at the slow creation interval rather than signature compute.
- The cost is a privacy risk: a resumption ticket presented before identity is an identifier that links sessions across rotating IDs. It would have to be sealed to the responder's static key (Noise IK-like) and single-use.
- It also adds a second handshake family to review.

Revisit only after Designs GD/CD and DH, if the hop handshake then dominates.
