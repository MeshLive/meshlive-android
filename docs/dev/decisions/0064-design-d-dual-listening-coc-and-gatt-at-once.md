# Design D: dual listening, CoC and GATT at once

**Design review required.** Default off (`test_dual_listening`, Settings > Advanced > Connection "Listen on both links"). Wire rules: PROTOCOL.md "Experimental dual listening".

**Problem.** A call endpoint advertises one transport. The A15s stack stops accepting LE CoC listens and connects after
about 15 registrations per Bluetooth process. The safety net (automatic GATT when LE CoC fails) moves the failing phone
to GATT, but only phones that also scan GATT see it, and once it is a GATT endpoint it lists only GATT offers, so it can
no longer call a CoC phone.

**Design.** A CoC-primary call offer carries one more bit (`capabilitySummary` bit 3, "also answers over GATT"); the
endpoint hosts the existing GATT server beside the CoC server (one advertising set, one database); a caller tries CoC
first for a bounded 9 s, then GATT to the same device from the same sighting; the endpoint keeps its primary transport
and gets a per-link override. Out of scope: streams, store-and-forward as the dialing side, relay hops, Classic,
GATT-primary dual, and the iPhone compact form. Cost: one more GATT server registration and six ATT handles per dual
endpoint. An independent review (14 findings) was adopted.

**Phone test.** With the switch off, the scan response and behaviour are unchanged; with it on, calls between mixed
pairs set up within 1 s of the off matrix, the A15s forced to GATT lists CoC phones and completes 20 consecutive call
cycles, and the first 30 s of a call over the second transport match a native call of that transport. The S22, Xiaomi
and A55 run it as soon as they are available.

**Open design question:** the monotonic epoch of rotating IDs never returns after a backward clock step (see "Discovery
privacy").
