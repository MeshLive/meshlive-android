# BMH3 identity-hiding handshake: protocol choice (2026-10-11)

Question: which handshake hides both identity keys from a sniffer while keeping the signing-only Keystore identity, the discovery-binding checks and hop-local relays? Wire rules: PROTOCOL "Experimental BMH3 identity-hiding link handshake"; design reviews and the phase plan: PLAN "Design F".

| Protocol | How it hides identities | Who is protected from an active attacker | Fit here |
| --- | --- | --- | --- |
| TLS 1.3 / IKEv2 (SIGMA-I) | ephemeral key share first, the server and then the client certificate encrypted under handshake keys | the client (initiator); the server only against passive attackers (ECH needs a published HPKE key) | the chosen shape for Everyone-mode responders (R_FIRST) |
| JFKr (SIGMA-R) | the initiator reveals first, the responder only to an admitted peer | the responder | the chosen shape for Contacts-only responders (I_FIRST) |
| Noise XX | `e`, then `e, ee, s, es`, then `s, se` | initiator (Noise 7.8 level 8, "encrypted with forward secrecy to an authenticated party"); responder's static to any active initiator (level 1) | same hiding as R_FIRST, but static Diffie-Hellman needs a second static key per identity; the Keystore identity key only signs, and iOS Secure Enclave signing and key-agreement keys are distinct types |
| Noise IK / WireGuard | initiator static encrypted to the responder's known static; WireGuard `mac1` keyed by the responder's public key silences the responder to anyone without it | responder hidden from scanners; initiator identity not forward secret | needs the responder's key in advance (contacts only); its `mac1` idea is the contact tag |
| Signal X3DH, sealed sender | server-hosted prekeys | not a link handshake | no server off grid |
| Briar | connections only between contacts, per-period transport tags, ephemeral plus static keys with a proof of ownership | both, contacts only | the same prior-knowledge idea as the contact tag |
| bitchat (Noise XX) | static keys encrypted in the handshake, but announcements publish them in cleartext | initiator only | the lesson: hiding in the handshake is pointless while the advertisement or an announce carries the key |

Choice: SIGMA in both orders (the responder's global mode picks the order) with a TLS 1.3 shaped schedule and a lease-keyed contact tag bound to the selected advert. Rejected: Noise XX or IK (second static key), a signed "BMH3 capable" bit in BMH2 hellos (readable only after the initiator's hello is sent), an escalating tarpit on the BMH3 path (it delays contacts that cannot prove they are one; a signing budget replaced it), a responder-side refusal of BMH2 in Everyone mode (protects nobody). Not solved: an Everyone-mode responder reveals its identity to any active prober; hiding it needs prior shared knowledge (Contacts-only) or per-epoch pseudonymous identities for strangers (Design F, Phase 2, under "Design records").
