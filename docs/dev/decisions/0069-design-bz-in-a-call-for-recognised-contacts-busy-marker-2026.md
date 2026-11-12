# Design BZ: "in a call" for recognised contacts (busy marker, 2026-10-19; design only, not built)

**Status: design for review; no code until the coordinator and the user approve it.** Wire rule: PROTOCOL "Draft: busy marker (Design BZ)".

**Problem.** A phone that rings or is in a call stops advertising its call offer, because its call endpoint serves one connection. A caller then sees nothing: the person drops out of Nearby, and a Contacts call waits 10 s and ends with "not found nearby" (phone runs 2026-10-19: the S22 calling the S24 while the S24 rang from another phone). In a room of six phones this is common, and the caller cannot tell "busy" from "gone".

**Rule (option A, recommended).** While its call endpoint is occupied by a call (ringing, connecting or active, either direction), a phone that has delivered a current presence lease to at least one contact advertises a **busy marker**. The marker is a version 5 announcement whose six ID bytes are the **role 4** ID: the same formula as every rotating ID, keyed by the same day key, so only a contact that holds this phone's lease can compute it. Every other field is zero (no call or stream flag, no transport, codec, token, cost or sequence). It goes out on a non-connectable, scannable set in the slot the call listener freed. A contact's recognition table also computes role 4 for each lease it holds. A hit shows "<name> is in a call" on the contact's row, and a Contacts or Recent call then says so at once and keeps waiting for the contact's call offer. Strangers see an all-zero version 5 advert they cannot resolve, and every list ignores it.

**Privacy.**
- *Strangers* learn that some phone nearby is in a call, as an epoch-bound pseudonym. They learn that already: the phone's call offer vanishes at the same moment, and the LE connection is visible to a sniffer. The marker has one constant shape for every phone (no per-phone bits). On a legacy-only controller (address reuse, PROTOCOL RH7) the marker can share an address with the call offer it replaces, which again tells no more than the offer's disappearance. A Visible-by-handle window never sends a marker (a fixed-prefix busy signal would name the phone).
- *Contacts* that hold a lease learn **when this phone is in calls** while they are in range. Before, they could only see it disappear. That is new information, so it is tied to the existing per-contact switch "Can see when I'm nearby": with that switch off the contact gets no lease, so nothing changes for them. It must be disclosed in PRIVACY and USER_GUIDE, and the open question Q-BZ1 below decides whether it needs its own switch.
- *Replay.* Anyone who copies a marker within its epoch can make the contact look busy to that contact's other contacts. A marker that exists only in a recognised call offer's absence is a hint, never proof: when the same contact's call offer is recognised, the offer wins and the row shows them as free. Same residual as recognition hits (RV3).

**Compatibility.** Android builds before rotating IDs reject version 5. Builds with version 5 decode it, but every list requires a call or stream flag, so the marker is dropped (`rejected`), and their recognition table has no role 4. iPhone and Windows must keep the same rule: a list shows only adverts with a kind flag. The conformance vectors get an all-zero version 5 advert that every platform must ignore and a role 4 vector. An iPhone cannot advertise manufacturer data, so it sends no marker in this design; compact v2 has no free role bits. A later version may add one.

**Cost.** One non-connectable set at the balanced interval for the length of a call, in the advertising slot the call listener does not use then. It is preempted before any session set (A-03 budget) and never runs without a delivered lease. Airtime in a crowd: one extra advert per busy phone.

**Alternatives.**
- *(B) A public busy bit in the role 0 call offer:* tells every stranger in range, keeps a connectable offer that cannot be answered, and gives strangers nothing useful.
- *(C) Keep advertising and accept a second connection only to decline as busy:* needs a second endpoint or GATT slot per phone, costs a connection and a handshake per attempt, and a decline after BMH2 identifies the callee to a stranger.
- *(D) Status quo plus clearer text:* "not found nearby" stays ambiguous.

A is the only option that tells exactly the people who can already recognise the phone.

**Open questions for the user.**
- *Q-BZ1:* should "contacts can see when I'm in a call" be its own switch? Recommendation: no extra switch. It follows "Can see when I'm nearby" (default on) and is disclosed. The alternative is a separate default-on switch under Privacy.
- *Q-BZ2:* should a Contacts call to a busy contact keep waiting? Recommendation: yes, up to 60 s, starting the call when the contact's offer returns, with Cancel.

**Tests (when approved).** JVM:
- role 4 vectors;
- every acceptance rule ignores the all-zero version 5 shape;
- the recognition table resolves role 4 only with a lease;
- an offer of the same contact beats a marker;
- the marker starts only with a delivered current lease and never in a Visible-by-handle window or a verification session;
- set budget and preemption.

Phone: B1, a contact calls a phone that is ringing and sees "in a call" within 2 s, while a stranger sees no row and an older build is unaffected. B2, the marker stops and the offer returns within 2 s of the call's end.

**Review (2026-10-19, partial).**
- *Confirmed:* the Android parser accepts the all-zero version 5 payload without error, and the call, stream and viewer-request filters reject it. The other consumers that read a decoded announcement only after acceptance are the reach table (call offers only), PeerAddressBook and the address log.
- *Finding BZ-R1, adopted:* the "a contact holds a current lease" condition cannot be read from `deliveredGeneration`. That field records that some lease of this generation was delivered, not that a contact still holds **today's** key, because a lease covers only 28 days from its delivery. The rule therefore needs the delivery day stored next to `deliveredGeneration`: a new field in the presence store, written in the same locked step as the delivery. The condition becomes "a contact with the switch on whose last delivery of the current generation covers today". The alternative is to drop the condition and send whenever any contact has the switch on, at the cost of markers nobody can read.
- *Not yet reviewed:* items 1, 2, 4 and 5 of the review brief (stranger linkability, replay and denial, budget in a crowd, the full wire text). This needs a second review before approval.
