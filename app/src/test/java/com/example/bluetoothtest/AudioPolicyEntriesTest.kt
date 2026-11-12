package com.example.bluetoothtest

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioPolicyEntriesTest {
    private val entries = AudioPolicyEntries.entries

    @After
    fun restore() {
        DebugTunables.devicePolicyOff = emptySet()
        AudioSwitches.applyExtras { _, default -> default }
    }

    @Test
    fun everyEntryCarriesTheRequiredMetadata() {
        assertEquals(entries.map { it.id }.toSet().size, entries.size)
        for (entry in entries) {
            assertTrue(entry.id, entry.id.matches(Regex("[a-z0-9]+(-[a-z0-9]+)*")))
            assertTrue(entry.id, entry.reviewed.matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
            assertTrue(entry.id, entry.evidence.isNotEmpty())
            assertTrue(entry.id, entry.behaviour.isNotBlank() && entry.title.isNotBlank() && entry.origin.isNotBlank())
            assertTrue("${entry.id} needs a confidenceNote", entry.confidenceNote.isNotBlank())
            assertTrue("${entry.id} needs a fallback", entry.fallback.isNotBlank())
            assertEquals("test_device_policy_off=${entry.id}", entry.offSwitch)
            assertTrue("${entry.id} needs sources", entry.sources.isNotEmpty())
            assertTrue(entry.id, entry.sourceStatus != PolicySourceStatus.EVIDENCE_ONLY)
            entry.sources.forEach { source ->
                assertTrue("${entry.id}: ${source.reference}", source.reference.startsWith("https://"))
                assertTrue("${entry.id}: ${source.reference}", source.note.isNotBlank())
            }
            assertEquals(AUDIO_POLICY_AREA, entry.area)
            if (entry.proposed) {
                assertTrue("${entry.id}: a proposal has no effect", entry.effects.isEmpty())
                assertEquals(PolicyValidation.EXPERIMENTAL, entry.validation)
            } else {
                assertTrue("${entry.id}: implemented entries change behaviour", entry.effects.isNotEmpty())
                // Active audio entries are runtime-conditional, so they match every phone (no model key).
                assertTrue("${entry.id}: active entries use a universal matcher", entry.matcher.universal)
                assertTrue(entry.id, entry.confidence != PolicyConfidence.HIGH || entry.sourceStatus == PolicySourceStatus.OFFICIAL)
            }
            entry.effects.forEach { assertTrue(entry.id, it.describe().isNotBlank()) }
        }
    }

    @Test
    fun eachIdIsListedExactlyOnceInTheRegistryAndTheCodeUsesTheDeclaredIds() {
        val registryList = DeviceFamilyPolicy.entries.map { it.id }
        entries.forEach { assertEquals("${it.id} must be listed once", 1, registryList.count { id -> id == it.id }) }
        val registry = registryList.toSet()
        val ids = entries.filterNot { it.proposed }.map { it.id }.toSet()
        listOf(
            CALL_FOCUS_ENTRY_ID,
            CAPTURE_WATCHDOG_ENTRY_ID,
            OPUS_ENCODER_FALLBACK_ID,
            ECHO_HINT_ENTRY_ID,
            EFFECT_LOGGING_ENTRY_ID,
            "call-route-set-result-and-reassert",
        ).forEach { assertTrue(it, it in ids) }
    }

    @Test
    fun theExperimentalArmsAreProposalsAndAllSwitchesDefaultOff() {
        val proposals = entries.filter { it.proposed }.map { it.id }.toSet()
        assertEquals(
            setOf(
                "audio-capture-source-fallback-chain",
                "audio-off-main-thread-commands",
                "call-audio-mode-change-wait",
                "call-usb-headset-mode-normal",
                "opus-complexity-governor",
            ),
            proposals,
        )
        AudioSwitches.applyExtras { _, default -> default }
        assertFalse(AudioSwitches.captureSourceFallback)
        assertTrue("audio work off the main thread is the shipped behaviour (2026-11-09)", AudioSwitches.offMainThread)
        assertFalse(AudioSwitches.modeChangeWait)
        assertFalse(AudioSwitches.usbHeadsetModeNormal)
        assertFalse(AudioSwitches.complexityGovernor)
        assertTrue("the re-assert is the shipped behaviour; false is the control arm", AudioSwitches.routeReassert)
    }

    @Test
    fun theUsbHeadsetProposalMatchesOnlyOneUiAndTheM31ProposalOnlyOldExynos() {
        val usb = entries.first { it.id == "call-usb-headset-mode-normal" }
        assertTrue(usb.matcher.matches(DeviceTraits("exynos2200", "", 33, manufacturer = "samsung")))
        assertFalse(usb.matcher.matches(DeviceTraits("qcom", "", 33, manufacturer = "google")))
        val anr = entries.first { it.id == "audio-off-main-thread-commands" }
        assertTrue(anr.matcher.matches(DeviceTraits("exynos9611", "", 31, socManufacturer = "Samsung")))
        assertFalse(anr.matcher.matches(DeviceTraits("exynos9611", "", 34, socManufacturer = "Samsung")))
        assertFalse(anr.matcher.matches(DeviceTraits("qcom", "", 31, socManufacturer = "QTI")))
    }
}
