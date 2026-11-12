# Relay child links: the setup PHY update landed between the two priority updates (2026-10-27; not confirmed 2026-10-28)

Symptom: relayed video calls with the M31 (Exynos 9611, Android 12) as the helper failed in about half the tries in both
directions (A15s or ASUS as the callee): the callee rang, the call never became active, and both ends ended with "Call link
lost: EOFException". In every failure the hop that died was the helper's own link to the callee (the helper is the LE
central there), by supervision timeout (0x08), with the callee's controller reporting the same. The caller's hop, the
end-to-end handshake and media were not involved, and 2M itself was not the cause: the same pairs carried 60 s calls on 2M.

Hypothesis (2026-10-27). Android starts GATT service discovery on every GATT client connect to a peer it has no cached
database for, and it caches only bonded peers (`bta_gattc_act.cc`, `bta_gattc_conn`). Discovery locks connection updates;
the stack first moves the link to 7.5 ms (`l2c_ble_conn_params.cc`, `l2cble_start_conn_update`, `BTM_BLE_CONN_INT_MIN`; a new
link always qualifies, because `l2cble_conn_comp` marks its creation interval as non-default) and sends the deferred request
when discovery ends. One HIGH request is therefore two connection updates: 7.5 ms, then 15 ms. An Android 12 or 13 CoC
central reconnects on a fresh ACL (EATT entry), reattaches a GATT client, which rediscovers, and requested the PHY at a fixed
600 ms, which sometimes landed between the two updates. Among the helper's child links, PHY update before the 15 ms update:
4 of 8 died; 15 ms update first: 0 of 11 (Fisher p = 0.02). The switch `phy_after_priority` held the setup PHY until the
link reached the requested window.

Result: not confirmed. In the interleaved phone A/B (23 relayed CoC calls through the M31 helper, both directions, plus GATT
and direct calls) child-link deaths were 2 of 12 calls with the hold and 1 of 11 without (p = 1.0), and all failures 3 of 12
and 3 of 11. The hold did what it was meant to (no PHY update before the 15 ms update on any link with it on), and links
still died; in the control arm the PHY update landed first on 6 child links and 5 of them lived. `phy_after_priority` is now Experimental and off; no harm was shown, so it stays as an arm (SWITCHES).

What the deaths are. Every death in the A/B and in the earlier set came 0.01-0.42 s after that link's own update to 15 ms
(last contact = disconnect time minus the 5 s supervision timeout), with or without a PHY update, while the helper was a
15 ms peripheral of the caller. In each dead run both of the helper's links to the callee died that way: the PSM-read link
and, after it, the fresh CoC link. In every passing run both lived. GATT relay children, which have no PSM-read link and no
early close, died with the same timing. This is the signature of "Relay dual-role scheduling": a child link at the same 15 ms
interval as the parent keeps a fixed anchor phase, and at a bad phase the Exynos controller gives the child no completed
exchange; the phase is a standing relation between the two phones that drifts slowly, so both links of one call share it,
and dead calls cluster in time (in the A/B three silent failures about 35 and 60 minutes apart, plus one dial that never
established, the same state's 0x3E form). Only the A15s > M31 > ASUS direction showed silent deaths in the A/B.

Two things that looked like causes are consequences. (1) The PSM-read link's ACL stayed up past the 2 s fresh-ACL wait in
every dead run and dropped in about 1.07 s in every passing one, a perfect separator. The link was already dead when GATT's
1 s idle timer sent the disconnect, so the controller could only end it at the supervision timeout. The CoC connect then
waited: AOSP keeps a channel request made on a disconnecting link and opens a new connection when the disconnect completes
(`l2c_api.cc` `L2CA_ConnectLECocReq` "RETRY LATER", `l2c_link.cc` `l2c_link_hci_disc_comp` "Restarting pending ACL
request"). The new link gets the freed handle number again; it is not the dead link reused. (2) The PHY order. In the dead
runs the fresh link's service discovery took 0.69-0.76 s against 0.55-0.67 s in live runs, consistent with a 7.5 ms child
that loses the events that collide with the parent's at the bad phase; a slower discovery pushes the 15 ms update past a PHY
request sent at a fixed 600 ms. PHY-first was a marker of the bad phase, not its cause (inferred, no sniffer).

Why the first analysis went wrong. It compared the order of the PHY and connection updates on every link and found a
significant split, but it did not time each death against every procedure on that link and on the helper's other link, and
it did not test the correlate before calling it the cause. Timing every death against the link's own 15 ms update would have
shown the pattern of the existing dual-role record, and the split would have read as a marker. Rules kept: a death is timed
against every procedure on the dying link and on the phone's other links before a cause is named, and a correlate is a
hypothesis until an A/B on phones moves the outcome. The sequencing rule still stands as engineering practice, not as the
cause here: link procedures that use an instant (connection update, PHY update) are sequenced on the stack's completion
callbacks, not fixed delays, because the Core specification allows one such procedure at a time (Core v5.4 Vol 6 Part B 5.3)
and the earlier CoC setups measured the collision (status 42 when both ends asked at once).

Next. The open fix belongs to "Relay dual-role scheduling" (the Experimental parent-relief and DCK arms). The Experimental
`psm_read_link_quiet` (off) keeps the PSM-read link away from the 15 ms stage; its A/B tells the two readings apart: if the late
ACL drop were at fault, calls pass with it; if the phase is, the fresh link still dies at its 15 ms update and the call only
fails about 5 s sooner.
