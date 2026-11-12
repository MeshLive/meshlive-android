# Design PB: what a sniffer and a fake answerer still learn under BMH3 pseudonyms (draft 2026-10-27, not reviewed, not built)

Two findings of the 2026-10-27 privacy review need a wire or protocol change, so they are recorded here as drafts instead
of being fixed with the rest of that review (which changed no wire byte). Both need a design review before any code.

**PB1: the opening records' sizes show who has a rotating ID and a window open (review finding 9, S3).** BMH3 pads its
identity block to a fixed 112-byte extension area, but the records that follow it do not: the link `BDO1` (`count 0..2`
entries of 43 bytes) and the first end-to-end `BDX1` `OPENING` carry the same entries again in records whose lengths a
sniffer reads off the air. The length says that rotating IDs are on, how many IDs the responder pinned (one, or two in the
minute after a boundary or while a Visible-by-name window is open) and, by elimination, when a pseudonymous responder sent
none. PROTOCOL lists this as residual BR7. Options:

| Option | What changes | Cost | Risk |
| --- | --- | --- | --- |
| A. Retire both records when both ends speak BMH3 (recommended) | The `OPENING` extension inside the encrypted, padded block already proves the same entries before the initiator reveals itself (and the `REVEAL` carries them for a pseudonym). A new link-hello capability bit "BMH3 opening is enough" lets two such ends skip `BDO1` and the `BDX1` `OPENING` entirely; an older peer keeps today's exchange | One capability bit, both drivers and the relay's child-facing hello; RV15 checks move to the extension for BMH3 links | A relay that mirrors an offer must still open it to the caller: the relay's own block carries no callee entries, so relayed version 5 calls keep `BDX1` `OPENING` end to end (padded, option B) |
| B. Fixed-size padding | `BDO1` and `BDX1` `OPENING` always carry two entry slots (an empty slot is 43 zero bytes with count still 0..2, or a new version with a fixed count) | Every receiver must accept the padded form, so it needs a version bump of both records | Smaller change, but the records still exist and their presence still says "rotating IDs on" |
| C. Keep and document | Nothing | None | The residual stays in the honest list |

Wire impact: A adds a capability bit and changes which records are sent; B changes the record formats. Either needs the
PROTOCOL text, test vectors and the Windows and Apple ports. Open question for the review: whether option A can also drop
the role 1 and 2 stream-hop `BDO1` (streams do not run BMH3 yet, so probably not in the same step).

**PB2: a caller's abort tells a fake answerer whom it counts as a contact (review finding 10).** When the initiator
recognised the selected advert as a contact it sends a real contact tag and expects exactly that contact's key; any other
answer (a throwaway, another key) is "not the expected contact" and the initiator closes right after msg2. A stranger
call instead continues with msg3 under the attempt pseudonym. Someone who copies a contact's advert (it is broadcast) and
answers every connection with a throwaway therefore learns from close-versus-continue whether the caller holds a lease for
that advert's owner, that is, whether the two people are contacts. The tag itself stays secret; the behaviour leaks it.
Options:

| Option | What changes | Cost | Risk |
| --- | --- | --- | --- |
| A. Continue like a stranger, then end like a stranger (recommended) | On a mismatch the initiator completes msg3 with its attempt pseudonym (never the real key) and the end-to-end handshake, then ends the attempt the way a caller ends any stranger call it gives up on (no Invite, close after a randomised short delay drawn from the distribution of ordinary cancels) and reports "not the expected contact" locally | A few hundred milliseconds of handshake per failed attempt; the stranger-presentation key is already minted per attempt | Timing and the missing Invite still separate it from a real stranger call unless the close also follows the stranger path's timing; needs a measured close-delay distribution |
| B. Always send the real tag's twin | Callers send a tag for every call (a random one for strangers) and never abort on msg2 alone; the binding check moves after the end-to-end handshake for every call | Changes when a contact call learns that the wrong phone answered, after more work on both sides | The responder learns nothing new, but a contact call to a cloned advert runs the full end-to-end handshake with a pseudonym before it fails |
| C. Keep and document | Nothing | None | The honest list gains "a phone that answers a copied advert can tell whether the caller counts its owner as a contact" |

Wire impact: none for A and C (the messages are the ones a stranger call already sends); B changes when checks run but no
format. A is a behaviour change on the caller only and needs a phone check that a contact whose lease expired still falls
back to the stranger path as today.
