# Design DH: one handshake for a direct call or direct stream (draft 2026-10-26, not reviewed, not built)

**Status: draft for review; no code until the coordinator and the user approve it.** This is a wire change: a new capability bit and a new KDF label. Switch when built: `test_direct_single_handshake` (Experimental, default off, both phones).

**Problem.** A direct call runs the BMH2 (or BMH3) link handshake and then, inside BMS1, a second full endpoint handshake with the same peer. Its signatures, ECDH and round trips take a median 166 ms on GATT calls (p90 190 ms; "Measured 2026-10-26: connection setup latency by stage"). Direct streams pay the same twice: "Authenticating the final video receiver" follows the link handshake.

**Proposal.**
1. *Signal.* Both hellos of the link handshake carry a new capability bit, "endpoint is this hop". A phone sets it only when it is the endpoint itself: never a relay helper, a forwarder toward its child, a QR verification session or a store-and-forward exchange.
2. *Endpoint keys without a second handshake.* When both hellos set the bit and the dialled advert is a route-cost-0 direct offer, both sides derive the BME1 endpoint keys from the completed link handshake with HKDF and a new label, `BME1-direct`. The inputs are the link transcript hash, the ECDH secret and both identity keys. The BMS1 and BME1 directions keep separate keys, as today.
3. *Kept rules.* The endpoint checks that need the endpoint identity still run on the link identity: session check against verified contacts, the selected-advert binding (`checkSelectedPeer`), the opening records BDO1 and BDX1, and the endpoint capability exchange (one authenticated record instead of the hello).
4. *BMH3.* The link layer can be pseudonymous while the endpoint layer reveals. Stage 1 therefore applies only when **neither** side is pseudonymous at the link layer (BMH2, or BMH3 with both identities revealed at the link). Every pseudonymous or QR-matched link keeps the two handshakes.

**Security review items.**
- Key separation: the link keys and the endpoint keys come from one secret under different labels. That is standard, but it removes the second fresh ECDH; forward secrecy stays per connection.
- The capability bit must be covered by both signatures, so a relay cannot set it to merge a relayed session. A relay never sets it, and an endpoint that sees route cost greater than 0 or a relay flag refuses the bit.
- Downgrade: a man in the middle who clears the bit only costs time.

**Interoperability.** An old phone never sets the bit, so both handshakes run as today.

**Expected saving.** About 0.17 s of tap-to-ring on direct calls and a similar amount of a direct join. Small next to the LL connect and the database walk; schedule after Design GD/CD.

**Test plan.** JVM tests for the following, then a phone A/B of tap-to-ring and authenticated-to-header on GATT and CoC, both directions:
- key derivation vectors
- a relay that sets the bit is refused
- the bit with route cost greater than 0 is refused
- BMH3 pseudonymous keeps two handshakes
- interop between an old and a new build in both directions
