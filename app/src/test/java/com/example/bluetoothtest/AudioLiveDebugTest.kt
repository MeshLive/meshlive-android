package com.example.bluetoothtest

import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/** DEBUG instrumentation of the audio-only live stream: autostart extras, verification and digest lines, codec choice. */
class AudioLiveDebugTest {
    private val lines = CopyOnWriteArrayList<String>()
    private var savedEmit: ((Int, String, Throwable?) -> Unit)? = null

    @Before
    fun capture() {
        savedEmit = AudioLiveLog.emit
        AudioLiveLog.emit = { _, line, _ -> lines += line }
    }

    @After
    fun restore() {
        savedEmit?.let { AudioLiveLog.emit = it }
        AudioLiveTunables.unitDigests = false
    }

    // --- Autostart ----------------------------------------------------------------------------------------------

    @Test
    fun autostartNeedsItsExtraAndReadsPresetAndTitle() {
        val none = AudioLiveAutoStart.parse({ _, d -> d }, { null })
        assertNull(none)
        fun parse(preset: String?, title: String? = null) = AudioLiveAutoStart.parse(
            { name, d -> if (name == AudioLiveAutoStart.EXTRA_AUTOSTART) true else d },
            { name -> if (name == AudioLiveAutoStart.EXTRA_PRESET) preset else if (name == AudioLiveAutoStart.EXTRA_TITLE) title else null },
        )
        assertEquals(AudioLive.PRESET_VOICE, parse(null)!!.preset)
        assertEquals(AudioLive.PRESET_VOICE, parse("voice")!!.preset)
        assertEquals(AudioLive.PRESET_LOW_DATA, parse("lowdata")!!.preset)
        assertEquals(AudioLive.PRESET_LOW_DATA, parse("LowData")!!.preset)
        assertEquals(AudioLive.PRESET_MUSIC, parse("music")!!.preset)
        assertEquals(AudioLive.PRESET_VOICE, parse("nonsense")!!.preset) // unknown: the default, never a crash
        assertEquals("Fire drill", parse("voice", "Fire drill")!!.title)
        assertNull(parse("voice", "  ")!!.title)
        // The extra false is the same as absent.
        assertNull(AudioLiveAutoStart.parse({ _, _ -> false }, { "music" }))
    }

    // --- Verification and digest lines --------------------------------------------------------------------------------

    private val streamId = UUID.randomUUID()
    private val identity = KeyPairSigningIdentity(generateEcKeyPair())
    private val signer = AudioLiveSigner(identity, streamId, AudioLive.PRESET_VOICE)

    private fun guard(clock: () -> Long = { 0L }): AudioLiveStreamGuard {
        val header = EndToEndStreamProtocol.decodeHeader(signer.headerBytes, allowAudioOnly = true)
        return AudioLiveStreamGuard(AudioLiveVerifier.fromHeader(header, streamId, identity.nodeId), clock).also {
            it.label = streamId.toString().take(8)
        }
    }

    private val opusHead = ByteArray(19).also {
        "OpusHead".forEachIndexed { index, c -> it[index] = c.code.toByte() }
        it[8] = 1
        it[9] = 1
    }

    private fun unit(tag: Int) = ByteArray(40) { tag.toByte() }.also { it[0] = 0xf8.toByte() }

    /** A signed record of [count] units starting at unit [start]; returns the record and the unit payloads. */
    private fun record(start: Int, count: Int = 10): Pair<StreamProtocol.Packet, List<ByteArray>> {
        val units = List(count) { unit(start + it) }
        val packets = units.mapIndexed { i, bytes ->
            StreamProtocol.Packet(StreamProtocol.FLAG_AUDIO, (start + i) * AudioLive.FRAME_US, bytes)
        }
        val body = StreamProtocol.batchAudioPackets(packets)
        val signed = signer.sign(body.flags, body.presentationTimeUs, body.payload)
        return StreamProtocol.Packet(signed.flags, signed.presentationTimeUs, signed.payload) to units
    }

    @Test
    fun theReceiverLogsOneVerificationLinePerFiftyRecordsAndOneForEveryRejection() {
        val g = guard()
        g.digests = false
        g.open(StreamProtocol.Packet(StreamProtocol.FLAG_AUDIO or StreamProtocol.FLAG_CODEC_CONFIG, 0, signer.sign(
            StreamProtocol.FLAG_AUDIO or StreamProtocol.FLAG_CODEC_CONFIG, 0, opusHead,
        ).payload))
        repeat(48) { g.open(record(it * 10).first) }
        assertTrue(lines.none { it.startsWith("BENCHMARK audio_live_verify") })
        g.open(record(480).first) // the 50th verified record (the configuration counted first)
        val progress = lines.single { it.startsWith("BENCHMARK audio_live_verify") }
        assertTrue(progress, progress.contains("event=progress") && progress.contains("verified=50") && progress.contains("failures=0"))
        assertTrue(progress.contains("stream=${streamId.toString().take(8)}"))
        // A forged record is rejected, counted and logged at once; the count in the next line includes it.
        val forged = AudioLiveSigner(KeyPairSigningIdentity(generateEcKeyPair()), streamId, AudioLive.PRESET_VOICE)
        val body = StreamProtocol.batchAudioPackets(List(3) { StreamProtocol.Packet(StreamProtocol.FLAG_AUDIO, (600L + it) * AudioLive.FRAME_US, unit(1)) })
        val bad = forged.sign(body.flags, body.presentationTimeUs, body.payload)
        assertThrows(IllegalArgumentException::class.java) {
            g.open(StreamProtocol.Packet(bad.flags, bad.presentationTimeUs, bad.payload))
        }
        val rejected = lines.last { it.startsWith("BENCHMARK audio_live_verify") }
        assertTrue(rejected, rejected.contains("event=rejected") && rejected.contains("failures=1") && rejected.contains("reason=Audio record signature does not verify"))
        // Verification keeps counting afterwards.
        repeat(50) { g.open(record(490 + it * 10).first) }
        assertTrue(lines.last { it.startsWith("BENCHMARK audio_live_verify") }.contains("failures=1"))
    }

    @Test
    fun txAndRxDigestLinesJoinOnSeqAndCarryTheSameUnitHashes() {
        // Digest lines are a DEBUG measurement switch; a release build never writes them (see the test above).
        assumeTrue("DEBUG arm", BuildConfig.DEBUG)
        AudioLiveTunables.unitDigests = true
        val g = guard()
        assertTrue("the guard follows the switch", g.digests)
        val (packet, units) = record(7, count = 10)
        val seq = java.nio.ByteBuffer.wrap(packet.payload, 0, 4).int.toLong() and 0xffff_ffffL
        val txBody = packet.payload.copyOfRange(4, packet.payload.size - 64)
        val tx = AudioLiveDigest.line(
            "tx", streamId.toString().take(8), seq, packet.presentationTimeUs,
            AudioLiveDigest.unitsOf(packet.flags, packet.presentationTimeUs, txBody),
        )
        g.open(packet)
        val rx = lines.single { it.startsWith("BENCHMARK audio_live_units: role=rx") }
        fun field(line: String, name: String) = line.substringAfter("$name=").substringBefore(" |")
        assertEquals(field(tx, "h"), field(rx, "h"))
        assertEquals(field(tx, "seq"), field(rx, "seq"))
        assertEquals(field(tx, "pts"), field(rx, "pts"))
        assertEquals("10", field(rx, "n"))
        assertEquals(units.joinToString(",") { AudioLiveDigest.unitHash(it) }, field(rx, "h"))
        // Different bytes give different hashes.
        assertTrue(AudioLiveDigest.unitHash(unit(1)) != AudioLiveDigest.unitHash(unit(2)))
        assertEquals(8, AudioLiveDigest.unitHash(unit(1)).length)
    }

    @Test
    fun digestLinesAreOffByDefaultAndTheSwitchIsAnExtra() {
        val g = guard()
        assertFalse(g.digests)
        g.open(record(0).first)
        assertTrue(lines.none { it.startsWith("BENCHMARK audio_live_units") })
        try {
            AudioLiveTunables.applyExtras({ name, d -> if (name == "test_audio_live_digests") true else d }, { _, d -> d }, { null })
            // A release build ignores the extra: no digest lines.
            assertEquals(BuildConfig.DEBUG, AudioLiveTunables.unitDigests)
        } finally {
            AudioLiveTunables.applyExtras({ _, d -> d }, { _, d -> d }, { null })
        }
        assertFalse(AudioLiveTunables.unitDigests)
    }

    // --- Codec choice -----------------------------------------------------------------------------------------------

    @Test
    fun theCodecChoiceLineStatesRequestedAndEffectiveImplementation() {
        assertEquals(
            "BENCHMARK audio_live_codec_choice: role=decoder | impl=native | requested=native | name=libopus 1.5.2",
            AudioLiveCodecChoice.line("decoder", "native", "native", "libopus 1.5.2"),
        )
        val fallback = AudioLiveCodecChoice.line("encoder", "mediacodec", "native", "c2.android.opus.encoder", "native_unavailable")
        assertTrue(fallback, fallback.endsWith("| reason=native_unavailable"))
        assertNotNull(fallback)
    }
}
