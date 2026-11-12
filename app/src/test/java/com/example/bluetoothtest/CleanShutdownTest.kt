package com.example.bluetoothtest

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ASUS uncleanExits=99: test drivers force-stopped the app while it scanned; the DEBUG clean shutdown comes first. */
class CleanShutdownTest {
    @Test
    fun theDoneLineCarriesTheNonceAndWhatIsStillRegistered() {
        val residue = CleanShutdown.Residue(scanners = 0, advertisers = 1, gattClients = 0, gattServers = 2)
        assertFalse(residue.clear)
        assertTrue(CleanShutdown.Residue(0, 0, 0, 0).clear)
        assertEquals(
            "BENCHMARK clean_shutdown: done | nonce=r42 | settled=false | scanners=0 | advertisers=1 | gattClients=0 | " +
                "gattServers=2 | awareAttach=0 | awareNetworks=0 | wifiSockets=0 | directGroups=0 | elapsedMs=2500",
            CleanShutdown.doneLine(residue, settled = false, elapsedMs = 2_500L, requestNonce = "r42"),
        )
    }

    @Test
    fun aWifiResidueKeepsTheShutdownUnsettled() {
        assertFalse(CleanShutdown.Residue(0, 0, 0, 0, awareAttach = 1).clear)
        assertTrue(CleanShutdown.Residue(0, 0, 0, 0).clear)
    }

    @Test
    fun aNonceCannotInjectIntoTheLogLine() {
        assertEquals("r42", CleanShutdown.sanitizeNonce("r42"))
        assertEquals("abdone", CleanShutdown.sanitizeNonce("a|b done"))
        assertEquals("none", CleanShutdown.sanitizeNonce(null))
        assertEquals("none", CleanShutdown.sanitizeNonce("| ;"))
        assertEquals(32, CleanShutdown.sanitizeNonce("x".repeat(100)).length)
    }

    private fun source(path: String): String = listOf(File(path), File("app/$path"), File("../$path"))
        .first { it.exists() }.readText().replace("\r\n", "\n")

    @Test
    fun theReceiverIsDebugOnlyAndTheTeardownLogsTheDoneLine() {
        val main = source("src/main/java/com/example/bluetoothtest/MainActivity.kt")
        val registration = main.substringAfter("setContentView(R.layout.activity_main)").substringBefore("LinkCapacity.attach(")
        assertTrue(registration.contains("if (BuildConfig.DEBUG) {"))
        assertTrue(registration.contains("CleanShutdown.ACTION"))
        val onDestroy = main.substringAfter("override fun onDestroy() {").substringBefore("private data class SurfaceVideoFormat")
        val done = onDestroy.indexOf("CleanShutdown.awaitAndLog(")
        assertTrue(done > onDestroy.indexOf("stopPrivateCallEndpoint()"))
        assertTrue(done > onDestroy.indexOf("stopStreaming(\"Stopped.\")"))
        assertTrue(onDestroy.indexOf("ScanRegistry.stopAll()") in 0 until done)
        val control = main.substringAfter("\"shutdown\" -> {").substringBefore("finishAndRemoveTask()")
        assertTrue(control.contains("CleanShutdown.begin("))
    }

    @Test
    fun theRunnerUsesTheSameAction() {
        val runner = source("../tools/run_device_benchmark.py")
        assertTrue(runner.contains("CLEAN_SHUTDOWN_ACTION = \"${CleanShutdown.ACTION}\""))
    }
}
