package com.example.bluetoothtest

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanPolicyTest {
    @After
    fun reset() {
        ScanSwitches.reset()
        DebugTunables.devicePolicyOff = emptySet()
    }

    private fun traits(sdk: Int, manufacturer: String = "") = DeviceTraits("qcom", "board", sdk, manufacturer = manufacturer)

    @Test
    fun everyEntryCarriesTheRequiredMetadata() {
        val ids = ScanPolicy.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        for (entry in ScanPolicy.entries) {
            assertTrue(entry.id, entry.id.matches(Regex("[a-z0-9]+(-[a-z0-9]+)*")))
            assertTrue(entry.id, entry.reviewed.matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
            assertTrue(entry.id, entry.evidence.isNotEmpty())
            assertTrue(entry.id, entry.behaviour.isNotBlank() && entry.title.isNotBlank() && entry.origin.isNotBlank())
            assertTrue(entry.id, entry.confidenceNote.isNotBlank() && entry.fallback.isNotBlank())
            assertEquals("test_device_policy_off=${entry.id}", entry.offSwitch)
            assertTrue("${entry.id} needs a source", entry.sources.isNotEmpty())
            entry.sources.forEach { source ->
                assertTrue("${entry.id}: ${source.reference}", source.reference.startsWith("https://") && source.note.isNotBlank())
            }
            assertTrue(entry.id, entry.effects.isNotEmpty())
            if (entry.sourceStatus == PolicySourceStatus.EVIDENCE_ONLY) {
                assertTrue(entry.id, entry.confidence != PolicyConfidence.HIGH)
            }
            if (entry.validation == PolicyValidation.LOCALLY_REPRODUCED) {
                assertTrue(entry.id, entry.evidence.any { it.startsWith("benchmarks/") || it.startsWith("docs/") })
            }
        }
    }

    @Test
    fun eachIdIsListedExactlyOnceInTheRegistry() {
        val registry = DevicePolicyRegistry.entries.map { it.id }
        for (entry in ScanPolicy.entries) assertEquals(entry.id, 1, registry.count { it == entry.id })
    }

    @Test
    fun experimentalArmsAreProposedSoTheRegistryNeverReportsThemActive() {
        val proposed = ScanPolicy.entries.filter { it.proposed }.map { it.id }.toSet()
        for (id in listOf("le-scan-timeout-renewal", "scan-hardware-filter-fallback",
            "hyperos-deepdoze-scan-block", "oem-screenoff-scan-pause-vivo-poco-oppo")) {
            assertTrue(id, id in proposed)
        }
        // The default-on behaviours are implemented entries (Bluetooth restart recovery ships on, switch audit A5).
        val active = ScanPolicy.entries.filterNot { it.proposed }.map { it.id }.toSet()
        assertEquals(
            setOf(
                "android10-11-location-switch-scan-gate", "scan-silence-watchdog", "bluetooth-process-restart-recovery",
                "android10-scanner-leak-on-process-death",
            ),
            active,
        )
        // Experimental entries never claim local validation.
        ScanPolicy.entries.filter { it.proposed && it.id.startsWith("le-") }.forEach {
            assertEquals(PolicyValidation.EXPERIMENTAL, it.validation)
        }
    }

    @Test
    fun matchersFollowTheBacklog() {
        val byId = ScanPolicy.entries.associateBy { it.id }
        val location = byId.getValue("android10-11-location-switch-scan-gate").matcher
        assertTrue(location.matches(traits(29)) && location.matches(traits(30)))
        assertFalse(location.matches(traits(31)) || location.matches(traits(34)))
        val leak = byId.getValue("android10-scanner-leak-on-process-death").matcher
        assertTrue(leak.matches(traits(29)))
        assertTrue("lane11 ASUS dumpsys: Android 11 leaked scanner registrations of force-killed processes", leak.matches(traits(30)))
        assertFalse(leak.matches(traits(31)))
        assertTrue(byId.getValue("scan-silence-watchdog").matcher.universal)
        val hyperos = byId.getValue("hyperos-deepdoze-scan-block").matcher
        assertTrue(hyperos.matches(traits(34, "Xiaomi")))
        assertFalse(hyperos.matches(traits(34, "samsung")))
        val screenOff = byId.getValue("oem-screenoff-scan-pause-vivo-poco-oppo").matcher
        assertTrue(screenOff.matches(traits(35, "vivo")))
        assertFalse(screenOff.matches(traits(35, "samsung")))
    }

    @Test
    fun theOffSwitchWorksWhetherOrNotTheRootHasAddedTheEntries() {
        assertTrue(ScanPolicy.enabled("scan-silence-watchdog"))
        assertTrue(ScanPolicy.isOff("x", setOf("x")))
        assertFalse(ScanPolicy.isOff("x", setOf("y")))
        if (!BuildConfig.DEBUG) {
            // test_device_policy_off is DEBUG-only: a release build always keeps every entry.
            DebugTunables.devicePolicyOff = setOf("all")
            assertTrue(ScanPolicy.enabled("scan-silence-watchdog") && ScanPolicy.enabled("bluetooth-process-restart-recovery"))
            return
        }
        DebugTunables.devicePolicyOff = setOf("scan-silence-watchdog")
        assertFalse(ScanPolicy.enabled("scan-silence-watchdog"))
        assertTrue(ScanPolicy.enabled("bluetooth-process-restart-recovery"))
        DebugTunables.devicePolicyOff = setOf("all")
        assertFalse(ScanPolicy.enabled("bluetooth-process-restart-recovery"))
    }

    @Test
    fun everyExperimentalSwitchIsOffByDefaultAndReadsItsExtra() {
        ScanSwitches.reset()
        assertFalse(ScanSwitches.renewalLegacy)
        assertFalse(ScanSwitches.renewalAll)
        assertFalse(ScanSwitches.staleRestart)
        assertFalse(ScanSwitches.softwareFilterFallback)
        assertFalse(ScanSwitches.probeUngated) // the quota fix is the shipped behaviour; ungated is the control arm
        val asked = ArrayList<String>()
        ScanSwitches.apply { key, _ -> asked += key; true }
        assertEquals(
            listOf(
                "test_scan_renewal_legacy", "test_scan_renewal_all", "test_scan_stale_restart",
                "test_scan_sw_filter_fallback", "test_scan_probe_ungated",
            ),
            asked,
        )
        // A release build ignores launch extras: every experiment stays off.
        assertEquals(BuildConfig.DEBUG, ScanSwitches.renewalAll && ScanSwitches.staleRestart && ScanSwitches.probeUngated)
        assertTrue(BuildConfig.DEBUG || !(ScanSwitches.renewalAll || ScanSwitches.staleRestart || ScanSwitches.probeUngated))
        ScanSwitches.applyLaunchExtras(null) // a launch without extras restores every default
        assertFalse(ScanSwitches.renewalAll || ScanSwitches.staleRestart || ScanSwitches.softwareFilterFallback)
    }

    // ---- S-05: scan sites keep a non-empty filter and never LOW_POWER on discovery ----

    private fun source(name: String): String {
        val candidates = listOf(
            File("src/main/java/com/example/bluetoothtest/$name"),
            File("app/src/main/java/com/example/bluetoothtest/$name"),
        )
        return candidates.first { it.exists() }.readText().replace("\r\n", "\n")
    }

    @Test
    fun everyScanSiteHandsTheStackANonEmptyServiceFilter() {
        // Discovery: the only empty list is software matching behind ScanFilterFallback.softwareFor(): the Experimental
        // S-06 stage (screen on only) or one scanner's own option, which the foreground, time-limited Meet scan sets
        // (F11: the Xiaomi's hardware filter dropped a flags-0x02 advert that an unfiltered scan receives), and the
        // visible Nearby call list only behind its Experimental switch (test_call_list_software_filter, default off).
        val control = source("BleL2capControl.kt")
        // The filter list is described by DiscoveryScanPlan (pure, JVM-tested); the scanner only maps it to ScanFilter objects.
        val plan = source("PcAnnouncement.kt").substringAfter("internal object DiscoveryScanPlan").substringBefore("fun settings(")
        assertTrue(plan.contains("add(ScanFilterSpec.ServiceUuid(BleL2capControlProtocol.SERVICE_UUID))"))
        assertEquals("only the software stage may return an empty filter list", 1, Regex("emptyList\\(\\)").findAll(plan).count())
        assertTrue(plan.indexOf("if (software)") in 0 until plan.indexOf("emptyList()"))
        val filters = control.substringAfter("private fun scanFilters()").substringBefore("private fun scanSettings")
        assertEquals("the scanner adds no empty list of its own", 0, Regex("emptyList\\(\\)").findAll(filters).count())
        assertTrue(filters.contains("DiscoveryScanPlan.filters(ScanFilterFallback.softwareFor(softwareFilter)"))
        assertTrue(source("ScanFilterFallback.kt").contains("fun softwareFor(scannerOption: Boolean): Boolean = scannerOption || softwareFiltering()"))
        // Every discovery start passes scanFilters(); no call passes null.
        val discoveryStarts = Regex("\\.startScan\\(([^,]+),").findAll(control).map { it.groupValues[1].trim() }.toList()
        assertTrue(discoveryStarts.isNotEmpty())
        assertTrue(discoveryStarts.toString(), discoveryStarts.all { it == "scanFilters()" })
        // The probes filter on the service UUID too.
        val gap = source("GapNameProbe.kt")
        assertTrue(gap.contains("scanner.startScan(\n            listOf(ScanFilter.Builder().setServiceUuid("))
        val inbound = source("InboundProbe.kt")
        assertTrue(inbound.contains("scanner.startScan(listOf(filter), settings, scanCallback)"))
        assertTrue(inbound.contains("ScanFilter.Builder().setServiceUuid(ParcelUuid(InboundProbe.SERVICE_UUID)).build()"))
    }

    @Test
    fun aDiscoveryScanIsLowPowerOnlyForTheIdleRelayHelperAndNeverWhileAPersonWaits() {
        // android.bluetooth.le.ScanSettings: SCAN_MODE_LOW_POWER 0, SCAN_MODE_BALANCED 1, SCAN_MODE_LOW_LATENCY 2.
        assertEquals(2, DiscoveryScanPlan.mode(startLowLatency = true, lowPower = false))
        assertEquals("a waiting person outranks the helper's saving", 2, DiscoveryScanPlan.mode(startLowLatency = true, lowPower = true))
        assertEquals(1, DiscoveryScanPlan.mode(startLowLatency = false, lowPower = false))
        assertEquals(0, DiscoveryScanPlan.mode(startLowLatency = false, lowPower = true))
        // The helper asks for low power only when its phone is idle: screen off and not charging (RelayHelperPolicy).
        val idle = RelayHelperInputs(
            featureOn = true, helpOn = true, rotatingIdsOn = false, leTransport = true, bluetoothReady = true, callReady = true,
            batteryPercent = 80, charging = false, batterySaver = false, thermalSevere = false, ownActivity = false, screenOn = false,
        )
        fun scanMode(inputs: RelayHelperInputs) =
            DiscoveryScanPlan.mode(startLowLatency = false, lowPower = RelayHelperPolicy.plan(inputs).scan == HelperScan.LOW_POWER)
        assertEquals(0, scanMode(idle))
        assertEquals(1, scanMode(idle.copy(screenOn = true)))
        assertEquals(1, scanMode(idle.copy(charging = true)))
    }

}
