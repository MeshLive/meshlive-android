# A15s caller over GATT: the write timeout is a dead 2M link, and direct calls stopped remembering it (2026-10-27)

Symptom: direct calls dialed by the A15s (MT6765, Android 10) to the ASUS failed on GATT (voice after 20 s, video after
8 s, `Call send failed: GATT video write timed out`) and crawled or failed on LE CoC, while ASUS-to-A15s calls passed.
The GATT write path is not the cause. The client keeps one write outstanding and waits for `onCharacteristicWrite`
before the next (Android 10 `BluetoothGatt` refuses a second operation while one is pending). When an ATT write
command crosses the L2CAP quota the stack accepts it but reports `GATT_CONGESTED` (143), and Android 10's GattService
holds the write callback until the link reports uncongested (`GattService.onWriteCharacteristic` /
`onClientCongestion`). On this link the controller hardly completed any packets in either direction: the ASUS received
one to six write commands per second, the ASUS's own notifications to the A15s timed out first, the A15s L2CAP queue
sat at 14 of 20 buffers, and the ACL finally ended by supervision timeout (0x08). The held callback then hits the 8 s
write timeout. That is the known A15s-as-central symmetric-2M failure ("A15s as a sender", 2026-10-13: 1M and
asymmetric links clean).

Why every call failed: the remedy is the bad-2M memory (the next call to the peer starts on 1M), and it was never
written. Since helpers sign the call-relay capability bit in every link hello (relayed calls on, helper running), the
2026-10-10 rule "a hop that signs the bit is a relay" treated the ASUS on a direct call as a relay and logged
`bad2m=not_recorded | hop_relays=true`, so the video call three minutes later dialed 2M again. The bit says the hop
can relay, not that it relays this call. Fix (switch `direct_call_hop_memory`, default on): an endpoint that knows its
hop is the call's own peer (the hop authenticated as the end-to-end peer, or the caller dialed the callee's own offer)
ignores the bit; relayed offers, relayed invitations and a callee behind a relay still skip the memory. The first call
of a new bad pair still fails or retries on 1M as before; a device-family "A15s central starts on 1M" entry stays
unproposed until a second MediaTek phone or an official source supports it.
