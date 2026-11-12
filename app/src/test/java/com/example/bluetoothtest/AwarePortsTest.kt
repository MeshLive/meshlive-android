package com.example.bluetoothtest

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The responder port: 49152-65535, drawn from the caller's Random (a secure one by default). */
class AwarePortsTest {
    @Test
    fun everyDrawIsInRange() {
        val random = Random(11)
        repeat(10_000) {
            val port = AwarePorts.randomPort(random)
            assertTrue("port $port", port in AwarePorts.MIN..AwarePorts.MAX)
        }
    }

    @Test
    fun theSameSeedGivesTheSamePort() {
        assertEquals(AwarePorts.randomPort(Random(7)), AwarePorts.randomPort(Random(7)))
    }
}
