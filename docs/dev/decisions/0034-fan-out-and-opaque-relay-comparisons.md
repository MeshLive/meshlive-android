# Fan-out and opaque-relay comparisons

Historical 2026-08-24 VP9 640x360/15 counts are latest samples, not synchronized stop totals. Opaque relay forwards endpoint-sealed records without encoder/decoder; normal middle is a trusted viewer.

| Direct fan-out target | Rendered / received | Isolation |
| --- | --- | --- |
| Classic 300 | S22 14378/14379; S24 14381/14388; ~15 FPS, empty queues, source 0 drops | Killed S24 leaves S22 healthy -> 15363; dynamic rejoin |
| GATT 250, 2M/MTU 517 | S22 14910/14911; A55 15097/15098; ~15 FPS, empty queues, source 0 drops | Killed A55 leaves S22 healthy; dynamic rejoin |
| CoC 100, 2M/MPS 65,535 | S22 13865/13866; S24 13938/13959; ~15 FPS, empty queues/drops 0 | DEBUG 200 ms packet delay -> S24 4.8-4.9 FPS while S22 15; slow child times out/reconnects independently |

CoC used an explicit two-child override under the old 220 budget; the MTU fix replaced that budget with 700. Not current normal branching proof.

| S22 -> A55 opaque relay -> S24 | Rendered / received | Relay |
| --- | --- | --- |
| CoC 100 | 14234/14235, 14.9 FPS, transport 0/1, decoder 1/7, drops 0 | 2M/MPS 65,535, queue 0-1/60, drops 0, TTL 7; setup release removed slowdown |
| GATT 250 | 14136/14137, 14.8-15 FPS, GATT 0/512, decoder 1/7, drops 0 | 2M/MTU 517, queue 0-1/60, drops 0, TTL 7 |
| Classic 300, software VP9 source | 14133/14135 at 15 m 39 s, 15 FPS, transport 0/1, decoder 1/7, drops 0 | Both hops authenticated, queue 0-1/60, drops 0, TTL 7, no encode/decode |

Earlier S22-as-CoC-relay repeatedly failed with controller 0x22/LMP response timeout on the second link. Pre-endpoint S24 -> A55 -> S22 reached 3384/3385 at 15 FPS/~4 m/relay 0 drops; superseded as protocol proof.

## Background delivery and instrumentation

The 2026-08-24 background GATT run used the normal discovery/request UI and then Home on both phones: S24 (SM-S921B) -> S22 (SM-S901E), hardware VP9 surface 640x360/15 FPS/150 kbps, signed identities, endpoint encryption and congestion feedback. MTU 517 and negotiated LE 2M TX/RX remained throughout; no disconnect. The historical report recorded a 900-second interval followed by final cumulative counters of 14626 received/14625 rendered, reported 15.1 FPS, RX pipe 0/512 and TX queue 0/30 with 0 drops. Those totals are not a newly aligned source-clock interval score. Source thermal status moved 0 (36.2 C) -> 2 (39.9 C); observed peak 40.4 C. Receiver stayed at status 0, 31.2 C -> 32.0 C. Foreground service types were source 0x50 (camera|connectedDevice) and receiver 0x10 (connectedDevice). Moderate heat did not reduce FPS/rate or stop the session. This is ordinary background continuity, not energy, standardized scene quality or process-death restoration proof.

The 2026-08-25 instrumentation check used the same phone models with encrypted GATT/LE 2M, VP9 640x360/15 FPS/300 kbps and Balanced 250. Selection to first Texture update was 5563.4 ms: beyond the current 5000 ms startup target. There were 2178 updates, 6 gaps over 250 ms, 1197.9 ms freezes and 0.81% freeze ratio; decoder ~15 FPS, queue 0-1/4, 0 drops and ~66.7 ms cadence did not erase those display gaps.

A 20-second resource collection and a final five-second smoke proved recording and a sticky starting temperature of 37.9 C only; they support no efficiency claim. Five connected CPU samples separated app/codec/audio/Bluetooth CPU after startup, not energy use. An S22 software VP9 30-frame field verification covered p99 output latency, two keyframe sizes, delta sizes, caller CPU and core-equivalent percentage; the short low-resolution case does not rank codec efficiency. The optical analyzer's generated 60 FPS fixture paired nine events with six-frame offsets and reported mean/p50/p95/min/max 100.0 ms at 16.67 ms recording resolution. That validates the calculation on synthetic data; real optical delay still requires filming both phones.

## Relay dual-role scheduling (2026-10-11)

HCI snoops of a relay that is LE peripheral to its parent and LE central to its child, both at 15 ms: the M31 (Exynos 9611) and the ASUS (Qualcomm SM8150) in saved relay runs. Reproduce with `python -B tools/relay_dual_role_snoop.py LOG...` (anchors are inferred from host timestamps, +-0.5-1 ms).

| Child connection attempts | Never established (0x3E) | Silent until 0x08 | Other (carried, low rate, closed by the relay) |
| --- | ---: | ---: | ---: |
| M31 relay (CoC and GATT), 55 | 22 | 9 | 24 |
| ASUS relay, 29 | 5 | 0 | 24 |

| M31 15 ms stage (child anchor after the parent anchor; gap to the next parent anchor) | Outcome |
| --- | --- |
| 3.7 ms  ; 7.9 ; 9.40 and 9.41  ; 10.21  ; 10.28  ; gaps 11.3-4.7 ms | carried 126-252 kbps |
| 11.51 and 11.35  ; 11.63 and 11.38  ; gaps 3.4-3.65 ms | silent: last completed packet 77-92 ms after the update to 15 ms, then nothing for 5.03-5.04 s, `Disconnection_Complete 0x08`; 19 completed-packet events and 55 ACL plus completed-packet records in each of the four |
| 11.98, 12.14, 13.52, 13.73  ; gaps 3.0-1.3 ms | alive but 3-31 kbps (no ATT MTU exchange, so no data-length update: 27-byte PDUs) |
| 6.1 and 6.6  | 17-29 kbps for the same reason (no data-length update), not a phase effect |

What the traces show. (1) The parent link is untouched: during the 5 s of silence it kept receiving 1.2-2.5 KB per 50 ms (busy 11-34% of the time), so the controller keeps the peripheral link and gives the child no event. (2) Every death is in the equal-interval stage: the 45 ms and 7.5 ms stages of the same connections never died (the 7.5 ms child has two events per parent event; the stack's own service-discovery lock sets it, l2c_ble_conn_params.cc), and the dead links all follow the `LE Data Length Change (251 octets, 2120 us)` that the MTU exchange triggers 16-31 ms after the update to 15 ms, with the 2M PHY update still pending (1M-forced arms died alike). (3) The ASUS controller carried a child at the same or worse phase (12.33-13.29 ms, 351-366 kbps), so this is a controller scheduling difference, not an arithmetic limit. (4) The 5 s timeout is not the problem: 333 intervals with zero completed exchanges; a longer timeout only keeps a dead link up longer, and the app cannot set it (`GattService.connectionParameterUpdate` hard-codes 500 units). (5) The phase at the 15 ms stage is the 45 ms stage's phase plus a window offset the controller chooses (mostly forward, +0 to +3.5 ms observed), so it is a fresh draw per connection and per update; an update with unchanged parameters is a no-op (immediate Update Complete, no new anchor).

Hypothesis consistent with all 21 M31 stages, not proven (no sniffer, no Exynos document): the controller admits a central event only if the exchange its negotiated data length allows (maximum PDU time each way, 2 x 2.12 ms + 2 x 150 us = 4.5 ms at 1M and 251 octets; 2.4 ms at 2M) fits before the next committed parent event. The healthy/dead boundary then lies at a 4.5 ms gap (healthy 4.7-11.3, dead 3.4-3.65). Predictions the A/B can break: 2M or a smaller data length moves the boundary to about 2.5 ms; a parent at 30 ms leaves one of two child events free; the child at 7.5 ms never dies.

Options. (a) A child interval that is a multiple of the parent's does not help: both intervals stay phase-locked and the child has one chance per parent event; the direction that helps is a parent interval at least twice the child's. The app cannot choose an interval: HIGH (11.25-15 ms) resolves to 15 ms on the Exynos, Qualcomm and S24 controllers, BALANCED (30-50 ms) to 45-50 ms on the Exynos (the balanced arms carried 36-184 kbps, 24-30% frozen, and re-anchored 2-3 times), and the hidden `requestLeConnectionUpdate` throws NoSuchMethodException . DCK (API 34) is 30 ms in AOSP, so a source on Android 14+ can make its relay-capable child's link 30 ms: built as the Experimental `test_relay_upstream_dck`. (b) Supervision timeout: rejected, see (4). (c) Role reversal (PLAN design): it removes the 22 central-initiation failures (the relay's controller would place the child's first anchor right after its own advertising event, in a gap it knows) but not the later collision: the child central still draws the update's window offset without knowing the relay's parent schedule, and two peripheral links at 15 ms still share one radio; it also needs a PROTOCOL change and a CoC-server on the relay. Not built; decide after the A/B. (d) A DeviceFamilyPolicy entry (`exynos9611-dual-role-equal-interval-child-starvation`) is recorded as an inert proposal, LOW confidence: one Exynos phone, no vendor document; Nordic's controller documentation shows the contrasting design (a peripheral connection about to time out gets the highest priority). What shipped as a generic fix is only the stall guard: it shortens a dead attempt from 5.9 s to about 3 s and lets the existing establishment fallback count it; it does not make the link carry anything.

Follow-up (2026-10-14). A smaller MTU on the relay's child dial (100 instead of 517; the data length followed) did not change the dead rate: 6 of 16 chains never joined in each arm. Silent child deaths split at a child-anchor-to-next-parent-anchor gap of about 4 ms (85 of 86 measured links), and the boundary did not move with smaller PDUs, so the PDU-time explanation is falsified. The phase is not redrawn per connection: across fresh runs it drifts by about 0.1 ms per minute (about 1.6 ppm), so dead stretches last tens of minutes and redialing, relaunching, an MTU change or a Bluetooth toggle on the child do not move it. A separate failure (every dial ends 0x3E while the child is advertising) is not explained by phase. This removes the "independent draw per dial" premise of the role-reversal design until measured. The MTU request stays at 517; `test_gatt_request_mtu` stays an Experimental diagnostic.
