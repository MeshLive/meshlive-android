package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WifiBindingTest {
    private val uw = ByteArray(32) { (it + 1).toByte() }
    private val ticket = ByteArray(8) { (it + 11).toByte() }
    private val otherTicket = ByteArray(8) { (it + 21).toByte() }

    private fun ready(seed: Int) = Bwf.Ready(1_000 + seed, seed, 500 + seed)

    private fun loopback(): InetAddress = InetAddress.getLoopbackAddress()

    private class HostOut(val result: WifiBinding.HostResult, val socket: Socket)

    private class PairRun(
        val host: WifiBinding.HostResult,
        val guest: WifiBinding.GuestResult,
        val hostSocket: Socket,
        val guestSocket: Socket,
    ) {
        fun closeAll() {
            runCatching { hostSocket.close() }
            runCatching { guestSocket.close() }
        }
    }

    private fun runPair(
        gate: TicketGate = TicketGate(ticket),
        hostReady: Bwf.Ready = ready(1),
        guestReady: Bwf.Ready = ready(2),
    ): PairRun {
        val server = ServerSocket(0, 2, loopback())
        val executor = Executors.newSingleThreadExecutor()
        val acceptedAt = System.nanoTime()
        val hostFuture = executor.submit(Callable {
            val socket = server.accept()
            HostOut(WifiBinding.host(socket, acceptedAt, uw, gate, ticket, hostReady), socket)
        })
        val client = Socket()
        client.connect(InetSocketAddress(loopback(), server.localPort), 2_000)
        val guestResult = WifiBinding.guest(client, System.nanoTime(), uw, ticket, guestReady)
        val hostOut = hostFuture.get(20, TimeUnit.SECONDS)
        executor.shutdownNow()
        server.close()
        return PairRun(hostOut.result, guestResult, hostOut.socket, client)
    }

    /** Runs [WifiBinding.host] on a loopback socket while [guestAction] drives the client side. The elapsed time is measured inside the host task. */
    private fun runRawHost(
        gate: TicketGate = TicketGate(ticket),
        guestAction: (Socket) -> Unit,
    ): Pair<WifiBinding.HostResult, Long> {
        val server = ServerSocket(0, 1, loopback())
        val executor = Executors.newSingleThreadExecutor()
        val acceptedAt = System.nanoTime()
        val hostFuture = executor.submit(Callable {
            val socket = server.accept()
            try {
                val result = WifiBinding.host(socket, acceptedAt, uw, gate, ticket, ready(1))
                result to (System.nanoTime() - acceptedAt) / 1_000_000L
            } finally {
                socket.close()
            }
        })
        val client = Socket()
        client.connect(InetSocketAddress(loopback(), server.localPort), 2_000)
        try {
            guestAction(client)
        } finally {
            runCatching { client.close() }
        }
        val (result, elapsed) = hostFuture.get(30, TimeUnit.SECONDS)
        executor.shutdownNow()
        server.close()
        return result to elapsed
    }

    private fun readExactly(input: InputStream, size: Int): ByteArray {
        val bytes = ByteArray(size)
        var read = 0
        while (read < size) {
            val n = input.read(bytes, read, size - read)
            if (n < 0) throw IllegalStateException("EOF after $read of $size")
            read += n
        }
        return bytes
    }

    @Test
    fun bothSidesDeriveTheSameKeysAndExchangeRecords() {
        val run = runPair()
        val host = run.host
        val guest = run.guest
        assertTrue(host is WifiBinding.HostResult.Ok)
        assertTrue(guest is WifiBinding.GuestResult.Ok)
        host as WifiBinding.HostResult.Ok
        guest as WifiBinding.GuestResult.Ok
        assertEquals(ready(2), host.bound.peerReady)
        assertEquals(ready(1), guest.bound.peerReady)
        host.bound.disarm()
        guest.bound.disarm()
        guest.bound.writer.write(SecureRecordType.BWF1, "from-guest".toByteArray())
        guest.bound.writer.flush()
        val atHost = host.bound.reader.read()
        assertEquals(SecureRecordType.BWF1, atHost.type)
        assertEquals("from-guest", String(atHost.payload))
        host.bound.writer.write(SecureRecordType.BWF1, "from-host".toByteArray())
        host.bound.writer.flush()
        val atGuest = guest.bound.reader.read()
        assertEquals("from-host", String(atGuest.payload))
        run.closeAll()
    }

    @Test
    fun frameSizesAndOrder() {
        val (result, _) = runRawHost { client ->
            val input = client.getInputStream()
            val frame1 = readExactly(input, WifiWire.BWU1_FRAME1_BYTES)
            assertEquals("BWU1", String(frame1.copyOfRange(0, 4)))
            assertEquals(1, frame1[4].toInt())
            client.getOutputStream().write(ByteArray(WifiWire.BWU1_FRAME2_BYTES) { 0x7f })
            client.getOutputStream().flush()
            client.soTimeout = 500
            val extra = runCatching { input.read() }.getOrNull()
            assertTrue(extra == null || extra == -1)
        }
        assertTrue(result is WifiBinding.HostResult.Rejected)
        assertEquals("frame2_format", (result as WifiBinding.HostResult.Rejected).stage)
    }

    @Test
    fun aWrongPreIsRejectedWithoutTouchingTheGate() {
        val gate = TicketGate(ticket)
        val server = ServerSocket(0, 2, loopback())
        val executor = Executors.newFixedThreadPool(2)
        val first = executor.submit(Callable {
            val socket = server.accept()
            try {
                WifiBinding.host(socket, System.nanoTime(), uw, gate, ticket, ready(1))
            } finally {
                socket.close()
            }
        })
        val raw = Socket()
        raw.connect(InetSocketAddress(loopback(), server.localPort), 2_000)
        val input = raw.getInputStream()
        val f1 = readExactly(input, WifiWire.BWU1_FRAME1_BYTES)
        val challenge = WifiWire.decodeBwu1Frame1(f1).challenge
        val eph = SecureBmh3Entropy.ephemeral()
        val nonce = SecureBmh3Entropy.nonce()
        val bogus = ByteArray(WifiWire.PRE_BYTES) { 0x42 }
        raw.getOutputStream().write(Bwu1Frame2(ticket, eph.publicBytes, nonce, bogus).encode())
        raw.getOutputStream().flush()
        val firstResult = first.get(20, TimeUnit.SECONDS)
        raw.close()
        assertTrue(firstResult is WifiBinding.HostResult.Rejected)
        assertEquals("pre", (firstResult as WifiBinding.HostResult.Rejected).stage)
        assertFalse(gate.consumed)

        val second = executor.submit(Callable {
            val socket = server.accept()
            try {
                WifiBinding.host(socket, System.nanoTime(), uw, gate, ticket, ready(1))
            } finally {
                socket.close()
            }
        })
        val client = Socket()
        client.connect(InetSocketAddress(loopback(), server.localPort), 2_000)
        val guest = WifiBinding.guest(client, System.nanoTime(), uw, ticket, ready(2))
        assertTrue(guest is WifiBinding.GuestResult.Ok)
        assertTrue(second.get(20, TimeUnit.SECONDS) is WifiBinding.HostResult.Ok)
        client.close()
        executor.shutdownNow()
        server.close()
        // silence an unused warning: the challenge is only used to build the frame above
        assertNotNull(challenge)
    }

    @Test
    fun aValidPreForAnotherTicketIsRejectedAndNotConsumed() {
        val gate = TicketGate(ticket)
        val (result, _) = runRawHost(gate) { client ->
            val input = client.getInputStream()
            val f1 = readExactly(input, WifiWire.BWU1_FRAME1_BYTES)
            val challenge = WifiWire.decodeBwu1Frame1(f1).challenge
            val eph = SecureBmh3Entropy.ephemeral()
            val nonce = SecureBmh3Entropy.nonce()
            val pre = WifiKeys.pre(uw, otherTicket, challenge, eph.publicBytes, nonce)
            client.getOutputStream().write(Bwu1Frame2(otherTicket, eph.publicBytes, nonce, pre).encode())
            client.getOutputStream().flush()
        }
        assertTrue(result is WifiBinding.HostResult.Rejected)
        assertEquals("ticket", (result as WifiBinding.HostResult.Rejected).stage)
        assertFalse(gate.consumed)
        assertTrue(gate.tryConsume(ticket))
    }

    @Test
    fun onlyOneOfTwoRacingSocketsConsumesTheTicket() {
        val gate = TicketGate(ticket)
        val server = ServerSocket(0, 2, loopback())
        val executor = Executors.newFixedThreadPool(2)
        val results = (0 until 2).map {
            executor.submit(Callable {
                val socket = server.accept()
                try {
                    WifiBinding.host(socket, System.nanoTime(), uw, gate, ticket, ready(1))
                } finally {
                    socket.close()
                }
            })
        }
        val guests = (0 until 2).map {
            val client = Socket()
            client.connect(InetSocketAddress(loopback(), server.localPort), 2_000)
            client to WifiBinding.guest(client, System.nanoTime(), uw, ticket, ready(2))
        }
        val hostResults = results.map { it.get(20, TimeUnit.SECONDS) }
        assertEquals(1, hostResults.count { it is WifiBinding.HostResult.Ok })
        assertEquals(1, hostResults.count { it is WifiBinding.HostResult.Rejected && it.stage == "ticket" })
        assertEquals(1, guests.count { it.second is WifiBinding.GuestResult.Ok })
        assertTrue(gate.consumed)
        guests.forEach { runCatching { it.first.close() } }
        executor.shutdownNow()
        server.close()
    }

    @Test
    fun frame2DeadlineIsAbsolute() {
        val (result, elapsed) = runRawHost { client ->
            val input = client.getInputStream()
            readExactly(input, WifiWire.BWU1_FRAME1_BYTES)
            val output = client.getOutputStream()
            repeat(12) {
                runCatching {
                    output.write(byteArrayOf(0x01))
                    output.flush()
                }
                Thread.sleep(300)
            }
            assertNotNull(input)
        }
        assertTrue(result is WifiBinding.HostResult.Rejected)
        assertEquals("frame2", (result as WifiBinding.HostResult.Rejected).stage)
        assertTrue("elapsed=$elapsed", elapsed in 1_400..3_200)
    }

    @Test
    fun silenceIsRejected() {
        val (result, elapsed) = runRawHost { client ->
            client.soTimeout = 4_000
            readExactly(client.getInputStream(), WifiWire.BWU1_FRAME1_BYTES)
            Thread.sleep(3_000) // say nothing: the host must give up on its own clock
        }
        assertTrue(result is WifiBinding.HostResult.Rejected)
        assertEquals("frame2", (result as WifiBinding.HostResult.Rejected).stage)
        assertTrue("elapsed=$elapsed", elapsed in 1_400..3_500)
    }

    @Test
    fun malformedFramesAreRejectedAtOnce() {
        fun variant(payload: ByteArray, expected: String) {
            val (result, _) = runRawHost { client ->
                client.getInputStream().let { readExactly(it, WifiWire.BWU1_FRAME1_BYTES) }
                client.getOutputStream().write(payload)
                client.getOutputStream().flush()
            }
            assertTrue(result is WifiBinding.HostResult.Rejected)
            assertEquals(expected, (result as WifiBinding.HostResult.Rejected).stage)
        }
        variant(ByteArray(WifiWire.BWU1_FRAME2_BYTES), "frame2_format")
        variant(ByteArray(WifiWire.BWU1_FRAME2_BYTES) { if (it == 4) 0x05 else 0x7f }, "frame2_format")
        val (short, _) = runRawHost { client ->
            client.getInputStream().let { readExactly(it, WifiWire.BWU1_FRAME1_BYTES) }
            client.getOutputStream().write(ByteArray(125) { 0x7f })
            client.getOutputStream().flush()
            client.shutdownOutput()
        }
        assertTrue(short is WifiBinding.HostResult.Rejected)
        assertEquals("frame2", (short as WifiBinding.HostResult.Rejected).stage)
    }

    @Test
    fun bytesBeforePreAreBounded() {
        val (result, _) = runRawHost { client ->
            client.getInputStream().let { readExactly(it, WifiWire.BWU1_FRAME1_BYTES) }
            client.getOutputStream().write(ByteArray(WifiWire.BWU1_FRAME2_BYTES) { 0x11 })
            client.getOutputStream().write(ByteArray(1_000) { 0x22 })
            client.getOutputStream().flush()
            client.soTimeout = 2_000
            val closed = runCatching { client.getInputStream().read() }.fold(
                onSuccess = { it == -1 },
                onFailure = { it is SocketException || it is SocketTimeoutException },
            )
            assertTrue(closed)
        }
        assertTrue(result is WifiBinding.HostResult.Rejected)
        assertEquals("frame2_format", (result as WifiBinding.HostResult.Rejected).stage)
    }

    private inner class RawGuest(val writer: SecureRecordWriter, val reader: SecureRecordReader) {
        fun close() {
            runCatching { writer.close() }
            runCatching { reader.close() }
        }
    }

    private fun rawGuest(client: Socket, framesTicket: ByteArray = ticket): RawGuest {
        val input = client.getInputStream()
        val f1 = readExactly(input, WifiWire.BWU1_FRAME1_BYTES)
        val challenge = WifiWire.decodeBwu1Frame1(f1).challenge
        val eph = SecureBmh3Entropy.ephemeral()
        val nonce = SecureBmh3Entropy.nonce()
        val pre = WifiKeys.pre(uw, framesTicket, challenge, eph.publicBytes, nonce)
        val f2 = Bwu1Frame2(framesTicket, eph.publicBytes, nonce, pre).encode()
        client.getOutputStream().write(f2)
        client.getOutputStream().flush()
        val f3 = readExactly(input, WifiWire.BWU1_FRAME3_BYTES)
        val frame3 = WifiWire.decodeBwu1Frame3(f3)
        val dh = WifiKeys.dh(eph.privateKey, frame3.ephemeral)
        val th = WifiKeys.transcriptHash(f1, f2, f3)
        val keys = WifiKeys.bindKeys(uw, framesTicket, th, dh)
        dh.fill(0)
        val writer = SecureRecordWriter(BufferedOutputStream(client.getOutputStream(), 64 * 1024), keys.sendKey(true))
        val reader = SecureRecordReader(input, keys.receiveKey(true)).also { it.acceptWifi = true }
        keys.close()
        return RawGuest(writer, reader)
    }

    @Test
    fun bindRoleOrTicketMismatchFailsAfterTicket() {
        val gate = TicketGate(ticket)
        val (result, _) = runRawHost(gate) { client ->
            val raw = rawGuest(client)
            raw.writer.write(SecureRecordType.BWF1, Bwf.Bind(WifiWire.ROLE_HOST, ticket).encode())
            raw.writer.flush()
            runCatching { raw.reader.read() } // the host's BIND, written before it reads ours
            raw.close()
        }
        assertTrue(result is WifiBinding.HostResult.FailedAfterTicket)
        assertEquals("bind_check", (result as WifiBinding.HostResult.FailedAfterTicket).stage)
        assertTrue(gate.consumed)

        val gate2 = TicketGate(ticket)
        val (result2, _) = runRawHost(gate2) { client ->
            val raw = rawGuest(client)
            raw.writer.write(SecureRecordType.BWF1, Bwf.Bind(WifiWire.ROLE_GUEST, otherTicket).encode())
            raw.writer.flush()
            runCatching { raw.reader.read() }
            raw.close()
        }
        assertTrue(result2 is WifiBinding.HostResult.FailedAfterTicket)
        assertEquals("bind_check", (result2 as WifiBinding.HostResult.FailedAfterTicket).stage)
        assertTrue(gate2.consumed)
    }

    @Test
    fun invalidPointAfterValidPre() {
        val gate = TicketGate(ticket)
        val (result, _) = runRawHost(gate) { client ->
            val input = client.getInputStream()
            val f1 = readExactly(input, WifiWire.BWU1_FRAME1_BYTES)
            val challenge = WifiWire.decodeBwu1Frame1(f1).challenge
            val point = ByteArray(WifiWire.POINT_BYTES) { if (it == 0) 0x04 else 0x00 }
            val nonce = SecureBmh3Entropy.nonce()
            val pre = WifiKeys.pre(uw, ticket, challenge, point, nonce)
            client.getOutputStream().write(Bwu1Frame2(ticket, point, nonce, pre).encode())
            client.getOutputStream().flush()
        }
        assertTrue(result is WifiBinding.HostResult.FailedAfterTicket)
        assertEquals("point", (result as WifiBinding.HostResult.FailedAfterTicket).stage)
        assertTrue(gate.consumed)
    }

    @Test
    fun aFailureAfterTheTicketWasSpentIsReportedAsAfterTheTicket() {
        // The host's own ephemeral key fails after the pre verified and the ticket was spent: the attempt must end
        // (FailedAfterTicket), not keep waiting for another socket that can never bind.
        val failingEntropy = object : Bmh3Entropy {
            override fun ephemeral(): Bmh3Ephemeral = throw IllegalStateException("no key")
            override fun nonce(): ByteArray = SecureBmh3Entropy.nonce()
            override fun randomTag(): ByteArray = SecureBmh3Entropy.randomTag()
        }
        val gate = TicketGate(ticket)
        val server = ServerSocket(0, 1, loopback())
        val executor = Executors.newSingleThreadExecutor()
        val acceptedAt = System.nanoTime()
        val hostFuture = executor.submit(Callable {
            server.accept().use { socket -> WifiBinding.host(socket, acceptedAt, uw, gate, ticket, ready(1), failingEntropy) }
        })
        val client = Socket()
        client.connect(InetSocketAddress(loopback(), server.localPort), 2_000)
        val guest = WifiBinding.guest(client, System.nanoTime(), uw, ticket, ready(2))
        val result = hostFuture.get(20, TimeUnit.SECONDS)
        executor.shutdownNow()
        server.close()
        client.close()
        assertTrue(result is WifiBinding.HostResult.FailedAfterTicket)
        assertTrue(gate.consumed)
        assertTrue(guest is WifiBinding.GuestResult.Failed)
    }

    @Test
    fun guestFailsOnABadFrame1() {
        val server = ServerSocket(0, 1, loopback())
        val executor = Executors.newSingleThreadExecutor()
        executor.submit(Callable {
            val socket = server.accept()
            try {
                socket.getOutputStream().write(ByteArray(20) { 0x55 })
                socket.getOutputStream().flush()
            } finally {
                socket.close()
            }
        })
        val client = Socket()
        client.connect(InetSocketAddress(loopback(), server.localPort), 2_000)
        val result = WifiBinding.guest(client, System.nanoTime(), uw, ticket, ready(2))
        assertTrue(result is WifiBinding.GuestResult.Failed)
        assertEquals("frame1", (result as WifiBinding.GuestResult.Failed).stage)
        client.close()
        executor.shutdownNow()
        server.close()
    }

    @Test
    fun bindAndReadyDeadlineIs5Seconds() {
        val gate = TicketGate(ticket)
        val (result, elapsed) = runRawHost(gate) { client ->
            val raw = rawGuest(client)
            raw.writer.write(SecureRecordType.BWF1, Bwf.Bind(WifiWire.ROLE_GUEST, ticket).encode())
            raw.writer.flush()
            // read the host's BIND and READY, then never answer READY
            runCatching { raw.reader.read() }
            runCatching { raw.reader.read() }
            Thread.sleep(6_000)
            raw.close()
        }
        assertTrue(result is WifiBinding.HostResult.FailedAfterTicket)
        assertEquals("ready", (result as WifiBinding.HostResult.FailedAfterTicket).stage)
        assertTrue("elapsed=$elapsed", elapsed in 4_200..6_500)
    }

    @Test
    fun socketIsNotBlockedAfterDisarm() {
        val run = runPair()
        assertTrue(run.host is WifiBinding.HostResult.Ok)
        assertTrue(run.guest is WifiBinding.GuestResult.Ok)
        val host = (run.host as WifiBinding.HostResult.Ok).bound
        val guest = (run.guest as WifiBinding.GuestResult.Ok).bound
        host.disarm()
        guest.disarm()
        assertEquals(0, run.hostSocket.soTimeout)
        assertEquals(0, run.guestSocket.soTimeout)
        run.closeAll()
    }

    @Test
    fun aDisarmedStreamPassesReadsThrough() {
        val run = runPair()
        assertTrue(run.host is WifiBinding.HostResult.Ok)
        assertTrue(run.guest is WifiBinding.GuestResult.Ok)
        val host = (run.host as WifiBinding.HostResult.Ok).bound
        val guest = (run.guest as WifiBinding.GuestResult.Ok).bound
        host.disarm()
        guest.disarm()
        val writer = Thread {
            Thread.sleep(300)
            guest.writer.write(SecureRecordType.BWF1, "late".toByteArray())
            guest.writer.flush()
        }
        writer.start()
        val record = host.reader.read()
        writer.join(5_000)
        assertEquals("late", String(record.payload))
        run.closeAll()
    }
}
