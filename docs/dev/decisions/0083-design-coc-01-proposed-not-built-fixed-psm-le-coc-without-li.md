# Design COC-01 (proposed, not built): fixed-PSM LE CoC without link encryption (Android 16)

**Status.** A draft from the device-family research; **needs a design review and a discovery/wire change before any
code**. Nothing in the app uses it. Default off and Experimental if it is ever built.

**What Android 16 adds.** `BluetoothSocketSettings` lets a listener ask for an LE CoC socket **without** link-layer
encryption or authentication and on a **fixed PSM in the dynamic range 128-255** (aosp-bt-versions COC-01). Today a
server takes whatever PSM the stack allocates (round-robin over those 128 values, shared with every app) and publishes it
in a GATT characteristic that the client must read before it can open the channel, after which the "GATT first, CoC on a
fresh ACL" sequence is needed (the stack drops the ACL about 1 s after the last GATT client closes, CONN-08/COC-05).

**What it would remove for an Android 16 pair.** The GATT PSM read and the control link behind it, the fresh-ACL timing
dance, and one connection setup round (about 1-3 s of setup and a source of 133 failures). An unencrypted LE CoC is not a
loss of confidentiality here: MeshLive's own handshake and AES-256-GCM records already protect every byte, and the
Android-level link key is not relied on (PRIVACY "No authenticated radio link on LE").

**What it needs (why it is not a drop-in).** (1) Discovery must say that a phone listens on the fixed PSM without a
GATT control service, a capability bit in the announcement or the first record, which touches the uniform adverts (Design
E) and the interoperability rules for older phones and iPhones, so a pair uses it only when both ends advertise it. (2) A
fixed PSM is a collision risk: another app may hold it, so the listener needs a defined fallback to the dynamic PSM and
the GATT read. (3) Rotating IDs and the address-privacy rules must still hold (the connect target is the same rotating
address). (4) Android 16 only, so a wire-compatible fallback to today's flow stays mandatory. (5) Phone tests: connect
time and failure rate with and without the GATT read, on the Android 16 phones (S24 and others) and against the stack's
socket-exception codes.

**Open questions for the review.** Does a capability bit leak anything a scanner can use (it is one more linkable
capability bit, PRIVACY)? Is the PSM fixed per app version or negotiated per pair? Does an unencrypted CoC change what an
active attacker can do beyond dropping and jamming (it should not, since records are sealed end to end)? Should the first
build be a measured A/B only, behind a DEBUG switch, before any protocol bit is assigned?
