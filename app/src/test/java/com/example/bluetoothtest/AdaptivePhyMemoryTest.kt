package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptivePhyMemoryTest {
    private val day = 24L * 60 * 60 * 1000

    @Test
    fun aBad2MEntryExpiresAfterSevenDays() {
        val at = 1_000_000L
        assertTrue(AdaptivePhyMemory.fresh(at, at))
        assertTrue(AdaptivePhyMemory.fresh(at, at + 7 * day - 1))
        assertFalse(AdaptivePhyMemory.fresh(at, at + 7 * day))
        assertFalse(AdaptivePhyMemory.fresh(0L, at))
        // A recording from the future (clock moved back) is not trusted.
        assertFalse(AdaptivePhyMemory.fresh(at, at - 1))
    }

    @Test
    fun earlyLossIsA2MSingleLinkLossWithinFifteenSeconds() {
        // A15s -> ASUS: the 2M link died 1-8 s after authentication (xpair-asusa15s-405fffae/0003).
        assertTrue(AdaptivePhyMemory.isEarlyLoss(10_000, 18_000, wasTwoM = true, sendLinks = 1))
        assertTrue(AdaptivePhyMemory.isEarlyLoss(10_000, 25_000, wasTwoM = true, sendLinks = 0))
        assertFalse(AdaptivePhyMemory.isEarlyLoss(10_000, 25_001, wasTwoM = true, sendLinks = 1))
        assertFalse(AdaptivePhyMemory.isEarlyLoss(10_000, 18_000, wasTwoM = false, sendLinks = 1))
        // Never learned while sending on two or more links (shared airtime, not the pair's 2M).
        assertFalse(AdaptivePhyMemory.isEarlyLoss(10_000, 18_000, wasTwoM = true, sendLinks = 2))
        // Never before authentication: the hop peer is unknown.
        assertFalse(AdaptivePhyMemory.isEarlyLoss(0, 8_000, wasTwoM = true, sendLinks = 1))
    }

    @Test
    fun entriesAreKeyedByTransportAndAuthenticatedNode() {
        assertEquals("bad2m2.BLE_GATT.a1b2c3d4e5f6", AdaptivePhyMemory.key(BluetoothTransportOption.BLE_GATT, "a1b2c3d4e5f6"))
        assertEquals("bad2m2.BLE_L2CAP.a1b2c3d4e5f6", AdaptivePhyMemory.key(BluetoothTransportOption.BLE_L2CAP, "a1b2c3d4e5f6"))
    }

    @Test
    fun theOneSetupRetryIsBoundedAndOnlyFrom2MOnADirectLeLink() {
        fun eligible(
            enabled: Boolean = true, running: Boolean = true, le: Boolean = true, direct: Boolean = true,
            firstOnTwoM: Boolean = true, elapsedMs: Long = 5_000,
        ) = adaptivePhySetupRetryEligible(enabled, running, le, direct, firstOnTwoM, elapsedMs)
        assertTrue(eligible())
        assertFalse(eligible(enabled = false))
        assertFalse(eligible(running = false))
        assertFalse(eligible(le = false))
        // A relayed call's advertised prefix is not the hop peer.
        assertFalse(eligible(direct = false))
        // Already on 1M (remembered or retried): no second retry.
        assertFalse(eligible(firstOnTwoM = false))
        assertFalse(eligible(elapsedMs = 15_001))
    }

    @Test
    fun aFirst2MAttemptWithoutHopAuthenticationAfterTheGuardIsStalled() {
        // A15s central -> ASUS: after LE PHY Update Complete (2M) the hello never completed
        // (snoop-asusa15s-1 0002, snoop-asusa15s-phy-1 0001); a healthy hop handshake takes 0.1-0.3 s.
        val guard = AdaptivePhyMemory.SETUP_STALL_MS
        assertTrue(adaptivePhySetupStalled(guard, authenticated = false, sinceOpenMs = guard))
        assertFalse(adaptivePhySetupStalled(guard, authenticated = false, sinceOpenMs = guard - 1))
        assertFalse(adaptivePhySetupStalled(guard, authenticated = true, sinceOpenMs = guard + 5_000))
        // Guard off (control arm, 1M attempts, or no retry left): never stalled.
        assertFalse(adaptivePhySetupStalled(0L, authenticated = false, sinceOpenMs = 60_000))
        // The stall close plus the peer's re-advertising must still leave the 1M retry inside its window.
        assertTrue(AdaptivePhyMemory.SETUP_STALL_MS + 2_000 < AdaptivePhyMemory.EARLY_LOSS_MS)
    }

    @Test
    fun theRetryRediscoversOnlyTheSameNodePrefixAndRouteCost() {
        val prefix = ByteArray(6) { 0x10 }
        fun announcement(nodePrefix: ByteArray, routeCost: Int) = DiscoveryAnnouncement(
            nodeIdPrefix = nodePrefix,
            flags = DiscoveryAnnouncement.FLAG_ACCEPTING_CONNECTIONS or DiscoveryAnnouncement.FLAG_PRIVATE_CALL_AVAILABLE,
            capabilitySummary = NodeCapabilities.CODEC_VP9,
            streamToken = 0,
            routeCost = routeCost,
            sequence = 0,
        )
        // The ASUS re-advertised under a new random address; the node prefix is what identifies it.
        assertTrue(matchesRediscoveredPeer(prefix, 0, announcement(prefix.copyOf(), 0)))
        assertFalse(matchesRediscoveredPeer(prefix, 0, announcement(ByteArray(6) { 0x11 }, 0)))
        // A relay advertising the same far prefix is a different hop.
        assertFalse(matchesRediscoveredPeer(prefix, 0, announcement(prefix.copyOf(), 1)))
    }

    @Test
    fun onlyADirectOfferRescansForTheRetrySoARelayedOfferKeepsItsRequestedParent() {
        // Every relay one hop from the callee advertises the callee's prefix at the same cost: a rescan
        // could pick another relay. A direct offer's advertiser is the callee, verified end to end.
        assertTrue(adaptivePhyRetryRediscovers(guardOn = true, routeCost = 0))
        assertFalse(adaptivePhyRetryRediscovers(guardOn = true, routeCost = 1))
        assertFalse(adaptivePhyRetryRediscovers(guardOn = true, routeCost = 2))
        // Control arm: the retry reconnects to the original advertisement exactly as before.
        assertFalse(adaptivePhyRetryRediscovers(guardOn = false, routeCost = 0))
    }

    @Test
    fun aHandshakeFinishingAtTheStallDeadlineIsEitherKeptOrClosedNeverBoth() {
        // Authentication wins the race: the guard must not close the link afterwards.
        val authenticatedFirst = CallSetupStallState()
        assertTrue(authenticatedFirst.authenticated())
        assertFalse(authenticatedFirst.claimStall())
        assertFalse(authenticatedFirst.stalled)
        // The guard wins: the late authentication is refused, so setup stops and the 1M retry runs.
        val stalledFirst = CallSetupStallState()
        assertTrue(stalledFirst.claimStall())
        assertFalse(stalledFirst.authenticated())
        assertTrue(stalledFirst.stalled)
        // An attempt that already failed for another reason is never claimed (no spurious setup_stall line).
        val endedFirst = CallSetupStallState()
        endedFirst.ended()
        assertFalse(endedFirst.claimStall())
        assertFalse(endedFirst.stalled)
        // Leaving establish after authentication keeps the authenticated state.
        val kept = CallSetupStallState()
        assertTrue(kept.authenticated())
        kept.ended()
        assertFalse(kept.claimStall())
    }

    @Test
    fun theStallDeadlineKeepsMarginOverMeasuredHealthyHopAuthentication() {
        // 353 saved outgoing GATT call setups (PHY report -> "Encrypted private-call link"): healthy p90
        // S24 221, ASUS 229, M31 171, A15s 1050 ms; worst healthy 1050 ms. Only the A15s -> ASUS 2M defect
        // was slower (1.8 s, then 5.5-18.8 s).
        val worstHealthyMs = 1_050L
        assertTrue(AdaptivePhyMemory.SETUP_STALL_MS >= 2 * worstHealthyMs)
    }

    @Test
    fun theOneTimePurgeDropsOnlyGattEntriesLearnedBeforeGattFanOutCounted() {
        // mesh4-659efc38 0003 left the ASUS as a bad-2M GATT peer on the M31 from a two-child window.
        val keys = listOf(
            "bad2m2.BLE_GATT.a1b2c3d4e5f6",
            "bad2m2via.BLE_GATT.8a1b2c3d4e5f",
            "bad2m2.BLE_L2CAP.a1b2c3d4e5f6",
            "bad2m2via.BLE_L2CAP.8a1b2c3d4e5f",
            AdaptivePhyMemory.GATT_FANOUT_PURGED,
        )
        assertEquals(
            listOf("bad2m2.BLE_GATT.a1b2c3d4e5f6", "bad2m2via.BLE_GATT.8a1b2c3d4e5f"),
            AdaptivePhyMemory.gattFanOutPollutedKeys(keys),
        )
    }

    @Test
    fun aRelayChildLinkMovesOnlyInTheOnePduBandNotTheCollisionBand() {
        val oneEventAt15Ms = AdaptivePhyPolicy.oneEventKbps(15.0)
        // mesh4-8211dc8f 0004: ASUS relay -> A15s at 121 kbps on 2M.
        assertTrue(relayOnePduBand(121, oneEventAt15Ms))
        assertTrue(relayOnePduBand(66, oneEventAt15Ms))
        // relaylink-gattdef-7a6c401c: an anchor collision left 4-37 kbps.
        assertFalse(relayOnePduBand(37, oneEventAt15Ms))
        assertFalse(relayOnePduBand(4, oneEventAt15Ms))
        assertFalse(relayOnePduBand(121, 0))
    }

    @Test
    fun aLinkToAHopThatRelaysTheCallIsNotBad2MEvidence() {
        // relaycall-f40e9f42 0002: ASUS caller -> S24 relay at 122 kbps recorded the S24 as bad-2M.
        assertTrue(AdaptivePhyMemory.skipsRelayingHop(hopRelays = true, guard = true))
        assertFalse(AdaptivePhyMemory.skipsRelayingHop(hopRelays = false, guard = true))
        // test_relay_phy_guard=false is the control arm.
        assertFalse(AdaptivePhyMemory.skipsRelayingHop(hopRelays = true, guard = false))
    }

    @Test
    fun aCallEndpointKnowsItsHopRelaysFromTheOfferTheInviteOrTheSignedHello() {
        // relaycall-b9726176-loop4-1 0004/0005: endpoints recorded the S24/ASUS relays after cascaded closes.
        assertTrue(AdaptivePhyMemory.callHopRelays(offerVia = true, relayHops = 0, hopTransportMask = 0))
        assertTrue(AdaptivePhyMemory.callHopRelays(offerVia = false, relayHops = 2, hopTransportMask = 0))
        // 0005: the A15s callee lost its relay hop before the invitation arrived.
        val relayHello = 0x03 or PrivateCallRelayChain.CALL_RELAY_CAPABILITY
        assertTrue(AdaptivePhyMemory.callHopRelays(offerVia = false, relayHops = 0, hopTransportMask = relayHello))
        // A direct call keeps recording its early losses.
        assertFalse(AdaptivePhyMemory.callHopRelays(offerVia = false, relayHops = 0, hopTransportMask = 0x03))
    }

    @Test
    fun aDirectCallToAnIdleHelperRecordsItsEarlyLoss() {
        // relay-showcase-20261026 trace-s4-voice-a15s-asus-direct-gatt: the A15s dialed the ASUS's own offer; the ASUS helper was
        // RUNNING, so its link hello carried the call-relay bit and the A15s logged bad2m=not_recorded | hop_relays=true on every call.
        val idleHelperHello = 0x03 or RelayedCalls.LINK_HELLO_SETUP_BIT or PrivateCallRelayChain.CALL_RELAY_CAPABILITY
        val callerDialedDirect = AdaptivePhyMemory.callHopIsCallPeer(
            enabled = true, linkPeerIsEndpointPeer = false, callerDialedDirect = true,
        )
        val calleeHopIsCaller = AdaptivePhyMemory.callHopIsCallPeer(
            enabled = true, linkPeerIsEndpointPeer = true, callerDialedDirect = false,
        )
        assertFalse(
            AdaptivePhyMemory.skipsRelayingHop(
                AdaptivePhyMemory.callHopRelays(false, 0, idleHelperHello, hopIsCallPeer = callerDialedDirect),
                guard = true,
            ),
        )
        assertFalse(AdaptivePhyMemory.callHopRelays(false, 0, idleHelperHello, hopIsCallPeer = calleeHopIsCaller))
        // Control arm test_direct_call_hop_memory=false: the bit alone skips the memory again.
        val controlArm = AdaptivePhyMemory.callHopIsCallPeer(enabled = false, linkPeerIsEndpointPeer = true, callerDialedDirect = true)
        assertTrue(AdaptivePhyMemory.callHopRelays(false, 0, idleHelperHello, hopIsCallPeer = controlArm))
    }

    @Test
    fun aRelayedCallStillSkipsTheMemoryWhateverTheHopProves() {
        val relayHello = 0x03 or PrivateCallRelayChain.CALL_RELAY_CAPABILITY
        // 0005: the callee's hop is the relay, not the authenticated caller, so the bit still counts.
        val calleeBehindRelay = AdaptivePhyMemory.callHopIsCallPeer(enabled = true, linkPeerIsEndpointPeer = false, callerDialedDirect = false)
        assertTrue(AdaptivePhyMemory.callHopRelays(false, 0, relayHello, hopIsCallPeer = calleeBehindRelay))
        // A relayed offer or a relayed invitation count even if the hop were somehow proven to be the call peer.
        assertTrue(AdaptivePhyMemory.callHopRelays(offerVia = true, relayHops = 0, hopTransportMask = 0, hopIsCallPeer = true))
        assertTrue(AdaptivePhyMemory.callHopRelays(offerVia = false, relayHops = 1, hopTransportMask = relayHello, hopIsCallPeer = true))
    }

    @Test
    fun theOneTimeRelayedCallPurgeDropsOnlyViaEntries() {
        // relaycall-b9726176-loop4-1 0003: the M31 caller's via entry for the A15s put its ASUS hop on 1M.
        val keys = listOf(
            "bad2m2.BLE_GATT.a1b2c3d4e5f6",
            "bad2m2via.BLE_GATT.8a1b2c3d4e5f",
            "bad2m2.BLE_L2CAP.8a1b2c3d4e5f",
            "bad2m2via.BLE_L2CAP.8a1b2c3d4e5f",
            AdaptivePhyMemory.RELAYED_CALL_PURGED,
        )
        assertEquals(
            listOf("bad2m2via.BLE_GATT.8a1b2c3d4e5f", "bad2m2via.BLE_L2CAP.8a1b2c3d4e5f"),
            AdaptivePhyMemory.relayedCallViaKeys(keys),
        )
    }

    @Test
    fun aSlowAuthenticated2mHopSetupPastTheArmIsTreatedAsAStall() {
        // AP7 F2: q14/hb late-but-successful 2M setups (789, 1115, 2267, 2359 ms) released the 3 s guard and died later.
        assertTrue(slowAuthStall(thresholdMs = 500, hopAuthMs = 500, onTwoM = true))
        assertTrue(slowAuthStall(thresholdMs = 500, hopAuthMs = 501, onTwoM = true))
        assertTrue(slowAuthStall(thresholdMs = 500, hopAuthMs = 2_359, onTwoM = true))
        assertFalse(slowAuthStall(thresholdMs = 500, hopAuthMs = 499, onTwoM = true))
    }

    @Test
    fun theSlowAuthArmIsOffAtZeroAndNeverOnOneM() {
        assertFalse(slowAuthStall(thresholdMs = 0, hopAuthMs = 60_000, onTwoM = true))
        assertFalse(slowAuthStall(thresholdMs = 500, hopAuthMs = 60_000, onTwoM = false))
    }

    @Test
    fun theSlowAuthArmStaysBelowTheExistingSetupGuard() {
        // At or above the 3 s arm the existing guard claims the attempt first; the switch can never pre-empt it.
        assertTrue(500L < AdaptivePhyMemory.SETUP_STALL_MS)
        assertTrue(AdaptivePhyMemory.SETUP_STALL_MS >= 2 * 1_050L)
    }

    @Test
    fun theSlowAuthArmIsOffAtZeroDebugOnlyAndClampedToTheExistingGuard() {
        DebugTunables.stallSlowAuthMs = 500L
        if (!BuildConfig.DEBUG) {
            // A release build ignores the launch extra: the experiment stays off.
            assertEquals(0L, DebugTunables.stallSlowAuthMs)
            return
        }
        assertEquals(500L, DebugTunables.stallSlowAuthMs)
        DebugTunables.stallSlowAuthMs = 10_000L
        assertEquals(AdaptivePhyMemory.SETUP_STALL_MS, DebugTunables.stallSlowAuthMs)
        DebugTunables.stallSlowAuthMs = -1L
        assertEquals(0L, DebugTunables.stallSlowAuthMs)
        DebugTunables.stallSlowAuthMs = 0L
        assertEquals(0L, DebugTunables.stallSlowAuthMs)
    }
}
