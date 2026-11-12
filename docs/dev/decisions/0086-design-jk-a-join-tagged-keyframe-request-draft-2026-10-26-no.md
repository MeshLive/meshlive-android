# Design JK: a join-tagged keyframe request (draft 2026-10-26, not reviewed, not built)

**Status: draft for review; no code until approved.** This is a wire change: an optional one-byte reason in the authenticated `KEY_FRAME_REQUEST` content. It is negotiated, because old readers expect it empty.

**Problem.** A viewer joining through a relay waits for a keyframe from upstream. The forwarder regenerates its child's request toward its parent, and the source honours unforced requests only after the shared recovery back-off (1 s doubling to 8 s; `KeyFrameRequestBackoff`). Authenticated-to-stream-header is a median 402 ms on direct joins but 1127 ms through one relay (p90 3484 ms) and 2248 ms through two. A direct join already gets a forced keyframe (`onPeerReady`). A chain join cannot, because the source cannot tell a join from a loss recovery.

**Proposal.** A request may carry reason `1 = join`. Only these send it:
- a viewer whose decoder has never had a keyframe in this session
- a forwarder regenerating such a request for a child added less than 10 s ago

A source honours a join request at once, bypassing the recovery back-off, at most once per direct child per 2 s. A forwarder that already holds an outstanding join request absorbs later ones, as it does now. A capability bit in the endpoint hello says "understands the join reason"; without it, the empty request is sent as today.

**Wire-compatible alternative, built 2026-10-26 behind `test_relay_join_key_frame` (Experimental, off; SWITCHES).** The source forces the first request from a relay-capable direct child after 2 s without one from that child. This needs no wire change but cannot tell a join from a recovery. Build JK only if its A/B shows the heuristic misses joins or forces too many keyframes; the DEBUG lines `key_frame_request honored=` and `relay_child_join waitMs=` show both.

**Expected saving.** Up to about 0.7 s median on chain joins, and more in crowds, where other viewers' recovery requests have raised the back-off.
