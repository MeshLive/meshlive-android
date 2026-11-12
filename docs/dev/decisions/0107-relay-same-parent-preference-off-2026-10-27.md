# Relay same-parent preference off (2026-10-27)

After a link loss, a relayed stream child used to try its previous relay first and wait up to 2 s for that relay's offer.
When the middle phone of a chain had gone (app killed and relaunched), that wait delayed or blocked recovery. Stream chain
S22 > M31 > ASUS, middle phone killed and relaunched, valid kills only: preference off 5/5 re-anchored, freeze median 2.9 s;
preference on (the old default) 4/5, about 4 s, one never recovered. Turned off by default after review
(build 4); DEBUG `test_relay_prefer_parent=true` brings the old behaviour back. Only the preference and its grace change:
the relay-side starved-link close and the child's parent-link watch still follow `relay_child_reanchor`.
