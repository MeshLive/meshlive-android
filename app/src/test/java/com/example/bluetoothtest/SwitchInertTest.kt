package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SwitchInertTest {
    private fun inert(vararg extras: Pair<String, Any>): List<SwitchInert.Inert> {
        val map = extras.toMap()
        return SwitchInert.inert(SwitchInert.Extras { map[it] })
    }

    @Test
    fun aSeedArmUnderLinkPacingOffIsReportedOnceWithItsParent() {
        val result = inert("test_link_pacing" to false, "test_join_probe_seed" to true)
        assertEquals(listOf(SwitchInert.Inert("join_probe_seed", "link_pacing=false")), result)
        assertEquals("BENCHMARK switch_inert: join_probe_seed | because link_pacing=false", result.single().line())
    }

    @Test
    fun anArmAtItsDefaultOrWithItsParentOnIsNotAnArm() {
        assertTrue(inert("test_link_pacing" to false, "test_link_pacer_seed" to true).isEmpty())
        assertTrue(inert("test_join_probe_seed" to true).isEmpty())
        assertTrue(inert("test_relay_time_queue" to true, "test_relay_drain_sustained" to true).isEmpty())
    }

    @Test
    fun theKnownMaskedArmsOfTheAuditAreCaught() {
        assertEquals("relay_time_queue=false", inert("test_relay_drain_sustained" to true).single().parent)
        assertEquals(
            "bounded_media_age=true",
            inert("test_bounded_media_age" to true, "test_relay_child_media_age" to false).single().parent,
        )
        assertEquals(
            "call_video_fragments=false",
            inert("test_call_fragment_airtime_ms" to 40).single().parent,
        )
        assertTrue(inert("test_call_fragment_airtime_ms" to 40, CallHeadOfLine.EXTRA to "on").isEmpty())
        assertEquals("relayed_calls=false", inert(RelayedCallsFeature.EXTRA_REQUIRE to 2).single().parent)
        assertEquals("call_native_opus=false", inert("test_call_native_opus_dtx" to true).single().parent)
        assertEquals(
            "stable_adv_address=false",
            inert("test_stable_adv_address" to false, "test_adv_inplace_boost" to false).single().parent,
        )
    }

    @Test
    fun theChildSideArmsAreMaskedOnlyWhileTheirWatchOrPreferenceIsOff() {
        // relay_child_reanchor=false still turns the child-side watch off (it follows it when absent); the same-parent
        // preference is off by default since build 4, so the grace is masked until test_relay_prefer_parent=true.
        assertEquals(
            "relay_parent_watch=false",
            inert("test_relay_child_reanchor" to false, "test_relay_parent_trickle" to false).single().parent,
        )
        assertEquals(
            "relay_prefer_parent=false",
            inert("test_relay_recovery_grace" to false).single().parent,
        )
        // Set apart, the trickle and the grace can now be tested without the relay-side close.
        assertTrue(
            inert(
                "test_relay_child_reanchor" to false, "test_relay_parent_watch" to true, "test_relay_parent_trickle" to false,
                "test_relay_prefer_parent" to true, "test_relay_recovery_grace" to false,
            ).isEmpty(),
        )
        assertEquals("playout_time_target=false", inert("test_playout_time_budget_ms" to 3_000).single().parent)
    }

    @Test
    fun aWifiArmWithoutTheCarrierSwitchIsReportedInert() {
        assertEquals(
            listOf(SwitchInert.Inert("wifi_icm", "wifi_carrier=false")),
            inert("test_wifi_icm" to true),
        )
        assertTrue(inert("test_wifi_carrier" to true, "test_wifi_icm" to true).isEmpty())
    }
}
