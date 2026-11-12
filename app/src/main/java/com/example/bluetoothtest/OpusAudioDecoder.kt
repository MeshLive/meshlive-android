package com.example.bluetoothtest

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.AudioTimestamp
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Process
import android.util.Log
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal fun isTraceablePcmFormat(sampleRate: Int, channelCount: Int, pcmEncoding: Int?): Boolean =
    sampleRate == 48_000 && channelCount == 1 &&
        (pcmEncoding == null || pcmEncoding == AudioFormat.ENCODING_PCM_16BIT)

class OpusAudioDecoder(
    private val header: StreamProtocol.Header,
    codecSpecificData: List<ByteArray>,
    private val playbackDelayProfile: PlaybackDelayProfile = PlaybackDelayProfile.BALANCED,
    private val playoutClock: MediaPlayoutClock = playbackDelayProfile.newPlayoutClock(),
    private val communicationMode: Boolean = false,
    private val debugTraceContext: Context? = null,
    private val debugTraceSession: String? = null,
    /** Experimental starvation concealment and silence-gated catch-up (AudioConcealment); calls only. */
    concealment: Boolean = false,
    /** Audio-only live (Design AL): the 0..1 level of each decoded chunk, for the listening meter. */
    private val onLevel: ((Float) -> Unit)? = null,
    /** DEBUG stream viewers: `BENCHMARK stream_audio_stats` counters (null for calls and release builds). */
    private val streamAudioStats: StreamAudioStats? = null,
) : Closeable {
    private data class PcmPacket(
        val bytes: ByteArray,
        val presentationTimeUs: Long,
        /** Native PLC/FEC audio standing in for a missing unit: not a received unit played. */
        val concealed: Boolean = false,
    )

    private val running = AtomicBoolean(true)
    private val failure = AtomicReference<Throwable?>()
    private val packets = ArrayBlockingQueue<StreamProtocol.Packet>(
        playbackDelayProfile.audioQueueCapacity(),
    )
    // Sized for the profile's whole adaptive delay: nothing is dropped when audio arrives in bursts.
    private val pcmPackets = ArrayBlockingQueue<PcmPacket>(
        playbackDelayProfile.audioQueueCapacity() * PCM_PACKETS_PER_ENCODED_PACKET,
    )
    private val playoutSync = AudioPlayoutSync()
    private val sourcePts = SourcePtsQueue()
    /** Sender PTS just after the last PCM written to the track (null before the first write). */
    private var nextExpectedPtsUs: Long? = null
    private val senderGapSilenceTotalMs = AtomicLong()
    private val senderGaps = AtomicLong()
    private var framesWritten = 0L
    @Volatile var playbackSpeed = 1.0f
        private set
    private val drops = AtomicLong()
    private val partialWriteDrops = AtomicLong()
    /** Mono only (call audio is mono 48 kHz); null keeps the behaviour before 2026-10-10. */
    private val concealer = if (concealment && header.audioChannelCount == 1) AudioConcealer(header.audioSampleRate) else null
    private var concealEventQueuedMs = 0L
    private var lastChunkRms = 0.0
    /** Experimental call fast recovery ([CallFastRecovery]): quiet chunks left out while the call clock is armed. */
    private val quietSkipper = if (communicationMode && playoutClock.hasFastRecovery) QuietAudioSkipper() else null
    /** A chunk the fast-recovery look-ahead took but did not merge: it plays next. */
    private var heldPcm: PcmPacket? = null
    private val fastRecoveryLeftOutMs = AtomicLong()
    private val codec: MediaCodec?
    /** Experimental native libopus call decoder (DEBUG `test_call_native_opus`); null = MediaCodec. */
    private val nativeDecoder: NativeOpusDecoder?
    private var nativePreSkipRemaining = 0
    private val nativePackets = AtomicLong()
    private val nativePlcFrames = AtomicLong()
    private val nativeFecFrames = AtomicLong()
    private val nativeFecPackets = AtomicLong()
    private val nativeUncoveredGaps = AtomicLong()
    private val audioTrack: AudioTrack
    private val decoderWorker: Thread
    private val playbackWorker: Thread
    @Volatile private var audioStarted = false
    private var pendingStartTimeNs: Long? = null
    private var prebufferedDurationUs = 0L
    private var debugAudioTrace: DebugAudioTrace? = null
    private val debugAudioTimestamp = if (BuildConfig.DEBUG && debugTraceSession != null) AudioTimestamp() else null

    val decoderName: String
    val droppedPackets: Long
        get() = drops.get()

    fun dropReasonSummary(): String =
        "partial write ${partialWriteDrops.get()} | sender gaps ${senderGaps.get()} " +
            "realigned with ${senderGapSilenceTotalMs.get()} ms silence" +
            (concealer?.let { " | concealed ${it.concealedSamples * 1_000L / header.audioSampleRate} ms in ${it.events} events" } ?: "") +
            (quietSkipper?.let { " | fast recovery left out ${fastRecoveryLeftOutMs.get()} ms of quiet" } ?: "")

    init {
        require(header.audioCodecId == StreamProtocol.AUDIO_CODEC_OPUS) {
            "Unsupported audio codec ${header.audioCodecId}"
        }
        val opusConfig = OpusCodecConfig.normalize(codecSpecificData)
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_OPUS,
            header.audioSampleRate,
            header.audioChannelCount,
        ).apply {
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_ENCODED_PACKET_BYTES)
            setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            opusConfig.forEachIndexed { index, bytes ->
                setByteBuffer("csd-$index", ByteBuffer.wrap(bytes))
            }
        }
        // Radio (audio-only live) has its own switch, independent of the call codec setting.
        val radio = playbackDelayProfile == PlaybackDelayProfile.RADIO
        val nativeRequested = (communicationMode && DebugTunables.callNativeOpus) || (radio && AudioLiveTunables.nativeOpus)
        nativeDecoder = if (nativeRequested) startNativeDecoder() else null
        if (nativeDecoder != null) {
            decoderName = "libopus ${NativeOpusBridge.nativeOpusVersion()}"
            codec = null
            // Same start trim as the platform decoders (csd-1 codec delay / OpusHead pre-skip).
            nativePreSkipRemaining = NativeOpusConfig.preSkip(opusConfig.first())
            Log.i(
                "BluetoothVideo",
                "BENCHMARK audio_codec: role=decoder | impl=native | requested=native | name=$decoderName | " +
                    "fec=on | plc=on | max_conceal_ms=${OpusGapConcealment.DEFAULT_MAX_CONCEAL_US / 1_000} | " +
                    "pre_skip=$nativePreSkipRemaining",
            )
        } else {
            // The platform's own decoder first, then any other that starts: on the A15s the listed
            // OMX.google.opus.decoder failed while c2.android.opus.decoder was also installed.
            val candidates = opusDecoderCandidates(format)
            check(candidates.isNotEmpty()) { "This phone has no Opus audio decoder" }
            var started: Pair<String, MediaCodec>? = null
            var lastError: Exception? = null
            for (name in candidates) {
                val candidate = runCatching { MediaCodec.createByCodecName(name) }.getOrNull() ?: continue
                try {
                    candidate.configure(format, null, null, 0)
                    candidate.start()
                    started = name to candidate
                    break
                } catch (error: Exception) {
                    lastError = error
                    runCatching { candidate.release() }
                }
            }
            val (startedName, startedCodec) = started
                ?: throw IllegalStateException("No Opus decoder could start: ${lastError?.message}", lastError)
            decoderName = startedName
            codec = startedCodec
            if (communicationMode) {
                Log.i(
                    "BluetoothVideo",
                    "BENCHMARK audio_codec: role=decoder | impl=mediacodec | " +
                        "requested=${if (nativeRequested) "native" else "mediacodec"} | name=$decoderName | " +
                        "fec=off | plc=off",
                )
            }
        }
        Log.i("BluetoothVideo", "Opus decoder $decoderName (${opusConfig.size} config buffers)")
        if (radio) {
            Log.i(
                "BluetoothVideo",
                AudioLiveCodecChoice.line(
                    "decoder",
                    if (nativeDecoder != null) "native" else "mediacodec",
                    if (AudioLiveTunables.nativeOpus) "native" else "mediacodec",
                    decoderName,
                    reason = if (nativeDecoder == null && AudioLiveTunables.nativeOpus) {
                        "native_unavailable: ${NativeOpusBridge.loadError?.message ?: "decoder could not start"}"
                    } else {
                        null
                    },
                ),
            )
        }

        val channelMask = if (header.audioChannelCount == 1) {
            AudioFormat.CHANNEL_OUT_MONO
        } else {
            AudioFormat.CHANNEL_OUT_STEREO
        }
        val minimumBuffer = AudioTrack.getMinBufferSize(
            header.audioSampleRate,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        MinBufferCheck.playbackProblem(minimumBuffer, header.audioSampleRate)?.let { error(it) }
        val playoutBytes = header.audioSampleRate * header.audioChannelCount * 2 *
            playbackDelayProfile.audioBufferMs / 1_000
        val audioBufferBytes = maxOf(minimumBuffer, playoutBytes)
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(
                        if (communicationMode) AudioAttributes.USAGE_VOICE_COMMUNICATION
                        else AudioAttributes.USAGE_MEDIA,
                    )
                    .setContentType(
                        if (communicationMode) AudioAttributes.CONTENT_TYPE_SPEECH
                        else AudioAttributes.CONTENT_TYPE_MOVIE,
                    )
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(header.audioSampleRate)
                    .setChannelMask(channelMask)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(audioBufferBytes)
            // Radio is buffered by 600 ms or more already: the deep buffer saves power with the screen off.
            .also { if (radio) it.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_POWER_SAVING) }
            .build()
        require(audioTrack.state == AudioTrack.STATE_INITIALIZED) {
            "Audio output could not be initialized"
        }
        DebugTunables.playbackGain?.let(audioTrack::setVolume)
        if (header.audioSampleRate == 48_000 && header.audioChannelCount == 1) {
            debugAudioTrace = DebugAudioTrace.startIfRequested(debugTraceContext, debugTraceSession, "receiver")
        }
        playbackWorker = Thread(::playbackLoop, "OpusAudioPlayout")
        decoderWorker = if (nativeDecoder != null) {
            Thread({ nativeDecodeLoop(nativeDecoder) }, "OpusAudioDecoder")
        } else {
            Thread(::decodeLoop, "OpusAudioDecoder")
        }
        playbackWorker.start()
        decoderWorker.start()
    }

    /**
     * Late audio is played, never discarded (user direction 2026-10-07); a full queue applies
     * backpressure to the receive loop instead of dropping the oldest packet.
     */
    fun offer(packet: StreamProtocol.Packet): Boolean {
        failure.get()?.let { throw IllegalStateException("Audio pipeline failed", it) }
        if (!running.get()) return false
        streamAudioStats?.onUnitArrived(packet.presentationTimeUs)
        if (packets.offer(packet, BACKPRESSURE_WAIT_MS, TimeUnit.MILLISECONDS)) return true
        throw IllegalStateException("Audio decoder stopped consuming for $BACKPRESSURE_WAIT_MS ms")
    }

    private fun startNativeDecoder(): NativeOpusDecoder? {
        val failure = NativeOpusBridge.loadError ?: run {
            val decoder = runCatching { NativeOpusDecoder(header.audioSampleRate, header.audioChannelCount) }
            decoder.getOrNull()?.let { return it }
            decoder.exceptionOrNull()
        }
        Log.w(
            "BluetoothVideo",
            "BENCHMARK audio_codec: role=decoder | impl=mediacodec | requested=native | " +
                "reason=native_unavailable | error=${failure?.message ?: failure?.javaClass?.simpleName}",
        )
        return null
    }

    /**
     * Experimental native decode: a packet after a gap first gets libopus concealment for the missing
     * frames (PLC, the last one from this packet's in-band FEC when present), up to
     * [OpusGapConcealment.DEFAULT_MAX_CONCEAL_US]; any longer remainder is left to [alignAfterSenderGap].
     * Concealed chunks carry the missing sender PTS so playout timing is unchanged, and are counted
     * separately from received packets.
     */
    private fun nativeDecodeLoop(decoder: NativeOpusDecoder) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        var expectedPtsUs: Long? = null
        var lastFrameUs = StreamProtocol.AUDIO_PACKET_DURATION_MS * 1_000L
        try {
            while (running.get()) {
                val packet = packets.poll(ACTIVE_POLL_MS, TimeUnit.MILLISECONDS) ?: continue
                val payload = packet.payload
                if (payload.isEmpty()) continue
                val hasFec = decoder.packetHasFec(payload)
                if (hasFec) nativeFecPackets.incrementAndGet()
                val plan = OpusGapConcealment.plan(expectedPtsUs, packet.presentationTimeUs, lastFrameUs, hasFec)
                if (plan.concealFrames > 0) {
                    val frameSamples = (plan.frameUs * header.audioSampleRate / 1_000_000L).toInt()
                    for (index in 0 until plan.concealFrames) {
                        val fec = plan.fecForLast && index == plan.concealFrames - 1
                        val pcm = if (fec) decoder.recoverFromFec(payload, frameSamples) else decoder.conceal(frameSamples)
                        if (fec) nativeFecFrames.incrementAndGet() else nativePlcFrames.incrementAndGet()
                        emitNativePcm(pcm, plan.firstConcealPtsUs + index * plan.frameUs, trim = false, concealed = true)
                    }
                    val concealEvents = nativePlcFrames.get() + nativeFecFrames.get()
                    if (concealEvents <= CONCEAL_LOG_FIRST || concealEvents % CONCEAL_LOG_EVERY == 0L) {
                        Log.i(
                            "BluetoothVideo",
                            "BENCHMARK audio_plc: gap_ms=${(packet.presentationTimeUs - (expectedPtsUs ?: 0L)) / 1_000} | " +
                                "plc_frames=${plan.plcFrames} | fec_frame=${plan.fecForLast} | " +
                                "uncovered_ms=${plan.uncoveredUs / 1_000} | total_plc=${nativePlcFrames.get()} | " +
                                "total_fec=${nativeFecFrames.get()}",
                        )
                    }
                }
                if (plan.uncoveredUs >= SENDER_GAP_MIN_MS * 1_000L) nativeUncoveredGaps.incrementAndGet()
                val decodedPcm = decoder.decode(payload)
                nativePackets.incrementAndGet()
                emitNativePcm(decodedPcm, packet.presentationTimeUs, trim = true)
                val samples = decodedPcm.size / bytesPerAudioFrame
                if (samples > 0) lastFrameUs = samples * 1_000_000L / header.audioSampleRate
                expectedPtsUs = packet.presentationTimeUs + lastFrameUs
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: Throwable) {
            fail(error)
        } finally {
            Log.i("BluetoothVideo", "BENCHMARK audio_codec_stats: ${nativeCodecSummary()}")
            releaseCodec()
        }
    }

    /** Native decoder counters; concealed frames are never counted as received packets. */
    fun nativeCodecSummary(): String =
        "role=decoder | impl=${if (nativeDecoder != null) "native" else "mediacodec"} | " +
            "packets=${nativePackets.get()} | plc_frames=${nativePlcFrames.get()} | " +
            "fec_frames=${nativeFecFrames.get()} | fec_packets=${nativeFecPackets.get()} | " +
            "uncovered_gaps=${nativeUncoveredGaps.get()}"

    private fun emitNativePcm(pcm: ByteArray, presentationTimeUs: Long, trim: Boolean, concealed: Boolean = false) {
        var bytes = pcm
        if (trim && nativePreSkipRemaining > 0) {
            val skipBytes = minOf(bytes.size, nativePreSkipRemaining * bytesPerAudioFrame)
            nativePreSkipRemaining -= skipBytes / bytesPerAudioFrame
            bytes = bytes.copyOfRange(skipBytes, bytes.size)
        }
        if (bytes.isEmpty()) return
        require(bytes.size <= MAX_PCM_OUTPUT_BYTES) { "Decoded audio is too large (${bytes.size} bytes)" }
        // Backpressure, not loss: playback drains this queue in real time.
        if (!pcmPackets.offer(PcmPacket(bytes, presentationTimeUs, concealed), BACKPRESSURE_WAIT_MS, TimeUnit.MILLISECONDS)) {
            throw IllegalStateException("Audio playback stopped consuming for $BACKPRESSURE_WAIT_MS ms")
        }
    }

    private fun decodeLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val codec = checkNotNull(codec)
        val info = MediaCodec.BufferInfo()
        var pendingPacket: StreamProtocol.Packet? = null
        try {
            while (running.get()) {
                if (pendingPacket == null) {
                    pendingPacket = packets.poll(ACTIVE_POLL_MS, TimeUnit.MILLISECONDS)
                }
                val packet = pendingPacket
                if (packet != null) {
                    val inputIndex = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val input = checkNotNull(codec.getInputBuffer(inputIndex))
                        input.clear()
                        require(packet.payload.size <= input.remaining()) {
                            "Opus packet is too large (${packet.payload.size} bytes)"
                        }
                        input.put(packet.payload)
                        codec.queueInputBuffer(
                            inputIndex,
                            0,
                            packet.payload.size,
                            packet.presentationTimeUs,
                            0,
                        )
                        sourcePts.onInput(packet.presentationTimeUs)
                        pendingPacket = null
                    }
                }

                while (running.get()) {
                    val outputIndex = codec.dequeueOutputBuffer(info, 0)
                    if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED && debugAudioTrace != null) {
                        val actual = codec.outputFormat
                        val pcmEncoding = if (actual.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            actual.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        } else null
                        // MediaFormat defines an absent pcm-encoding key as signed PCM16.
                        Log.i("BluetoothVideo", "BENCHMARK audio decoder format: $actual | " +
                            "pcmEncoding=${pcmEncoding ?: AudioFormat.ENCODING_PCM_16BIT} | " +
                            "encodingBasis=${if (pcmEncoding == null) "MediaFormat-default" else "explicit"}")
                        if (!isTraceablePcmFormat(actual.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                                actual.getInteger(MediaFormat.KEY_CHANNEL_COUNT), pcmEncoding)
                        ) debugAudioTrace?.invalidate("Actual decoder PCM format is not proven mono PCM16 at48000 Hz: $actual")
                    }
                    if (outputIndex < 0) break
                    val output = checkNotNull(codec.getOutputBuffer(outputIndex))
                    output.position(info.offset)
                    output.limit(info.offset + info.size)
                    if (info.size > 0) {
                        require(info.size <= MAX_PCM_OUTPUT_BYTES) {
                            "Decoded audio is too large (${info.size} bytes)"
                        }
                        val pcm = ByteArray(info.size).also(output::get)
                        val decoded = PcmPacket(pcm, sourcePts.onOutput(info.presentationTimeUs))
                        // Backpressure, not loss: playback drains this queue in real time.
                        if (!pcmPackets.offer(decoded, BACKPRESSURE_WAIT_MS, TimeUnit.MILLISECONDS)) {
                            throw IllegalStateException("Audio playback stopped consuming for $BACKPRESSURE_WAIT_MS ms")
                        }
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: Throwable) {
            fail(error)
        } finally {
            releaseCodec()
        }
    }

    private fun playbackLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        try {
            while (running.get()) {
                streamAudioStats?.tick(::streamAudioCounters)?.let { Log.i("BluetoothVideo", it) }
                val concealer = concealer
                if (concealer == null) {
                    val packet = recoverQuietAudio(nextPcm(ACTIVE_POLL_MS) ?: continue)
                    onLevel?.invoke(AudioLiveLevel.rms(packet.bytes))
                    // Late audio at startup is played, not discarded (no media loss): a viewer that joined
                    // behind a relay's backlog dropped every packet (463) because each one arrived after
                    // its start deadline. The playout clock grows its delay for late media (calls within
                    // their conversational bound) and the playback speed follows it.
                    writePcm(packet.bytes, packet.bytes.size, packet.presentationTimeUs)
                    if (!packet.concealed) streamAudioStats?.onUnitPlayed(packet.presentationTimeUs)
                    startAudioIfReady()
                    continue
                }
                // Experimental concealment: poll briefly so a nearly dry AudioTrack gets concealment
                // before it underruns; real audio is cross-faded in from the concealment.
                val packet = nextPcm(CONCEAL_POLL_MS)?.let(::recoverQuietAudio)
                if (packet == null) {
                    concealStarvation(concealer)
                    continue
                }
                val samples = pcm16ToShorts(packet.bytes)
                val concealed = concealer.merge(samples)
                val bytes = if (concealed > 0) shortsToPcm16(samples) else packet.bytes
                if (concealed > 0) logConcealEvent(concealer, concealed)
                lastChunkRms = rmsOf(samples)
                writePcm(bytes, bytes.size, packet.presentationTimeUs)
                if (!packet.concealed) streamAudioStats?.onUnitPlayed(packet.presentationTimeUs)
                concealer.observe(samples)
                startAudioIfReady()
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: Throwable) {
            fail(error)
        } finally {
            releaseAudioTrack()
        }
    }

    private fun nextPcm(timeoutMs: Long): PcmPacket? =
        heldPcm?.also { heldPcm = null } ?: pcmPackets.poll(timeoutMs, TimeUnit.MILLISECONDS)

    /**
     * Experimental call fast recovery ([CallFastRecovery]): while the call clock is armed after an isolated late burst, a
     * quiet chunk inside a pause is left out (crossfaded into the next quiet chunk) and the clock moves earlier by its
     * length from the next chunk's PTS on, so video skips at the same point. Speech, speech tails, concealment and any
     * chunk while the clock is not armed play unchanged ([QuietAudioSkipper]); nothing is time-stretched.
     */
    private fun recoverQuietAudio(packet: PcmPacket): PcmPacket {
        val skipper = quietSkipper ?: return packet
        val samples = pcm16ToShorts(packet.bytes)
        val rms = rmsOf(samples)
        val chunkMs = exactChunkMs(packet.bytes.size)
        val pendingMs = if (audioStarted && !packet.concealed) playoutClock.fastRecoveryPendingMs() else 0L
        val verdict = if (pendingMs > 0L) {
            skipper.consider(rms, chunkMs, pendingMs, queuedPlaybackNs() / 1_000_000L)
        } else {
            QuietAudioSkipper.Verdict.PLAY
        }
        if (verdict == QuietAudioSkipper.Verdict.PLAY_SPEECH) playoutClock.noteFastRecoverySpeech()
        if (verdict != QuietAudioSkipper.Verdict.CANDIDATE) {
            skipper.onPlayed(rms, chunkMs)
            return packet
        }
        val next = pcmPackets.poll(QuietAudioSkipper.LOOKAHEAD_WAIT_MS, TimeUnit.MILLISECONDS)
        if (next == null) {
            skipper.onPlayed(rms, chunkMs)
            return packet
        }
        val nextSamples = pcm16ToShorts(next.bytes)
        val nextRms = rmsOf(nextSamples)
        if (next.concealed || !skipper.nextAllowsDrop(nextRms) ||
            !playoutClock.consumeFastRecovery(chunkMs, next.presentationTimeUs)
        ) {
            heldPcm = next
            skipper.onPlayed(rms, chunkMs)
            return packet
        }
        skipper.onDropped()
        fastRecoveryLeftOutMs.addAndGet(chunkMs)
        skipper.onPlayed(nextRms, exactChunkMs(next.bytes.size))
        val merged = crossfadeOverDroppedChunk(
            samples,
            nextSamples,
            header.audioChannelCount,
            header.audioSampleRate * QuietAudioSkipper.CROSSFADE_MS / 1_000,
        )
        return PcmPacket(shortsToPcm16(merged), next.presentationTimeUs)
    }

    /** A chunk's length in whole ms, or 0 when it is not a whole number of ms (never left out then). */
    private fun exactChunkMs(sizeBytes: Int): Long {
        val frames = sizeBytes.toLong() / bytesPerAudioFrame
        return if (frames * 1_000L % header.audioSampleRate == 0L) frames * 1_000L / header.audioSampleRate else 0L
    }

    /**
     * Writes 10 ms of concealment while the AudioTrack holds less than the low-water mark. The
     * samples stand in for audio that has not arrived: they are not traced as accepted media and do
     * not move the sender timeline ([nextExpectedPtsUs]).
     */
    private fun concealStarvation(concealer: AudioConcealer) {
        if (!audioStarted || !running.get()) return
        val queuedMs = queuedPlaybackNs() / 1_000_000L
        if (!ConcealmentSchedule.shouldConceal(audioStarted, concealer.primed, queuedMs)) return
        if (!concealer.concealing) concealEventQueuedMs = queuedMs
        val count = header.audioSampleRate * ConcealmentSchedule.CHUNK_MS / 1_000
        val pcm = shortsToPcm16(concealer.conceal(count))
        val written = audioTrack.write(pcm, 0, pcm.size, AudioTrack.WRITE_NON_BLOCKING)
        if (written > 0) framesWritten += written / bytesPerAudioFrame
    }

    private fun logConcealEvent(concealer: AudioConcealer, concealedSamples: Int) {
        if (!BuildConfig.DEBUG) return
        val rate = header.audioSampleRate
        Log.i(
            "BluetoothVideo",
            "BENCHMARK audio_conceal: event=end | concealed_ms=${concealedSamples * 1_000L / rate} | " +
                "queued_ms_at_start=$concealEventQueuedMs | period_samples=${concealer.period} | " +
                "voicing=${"%.2f".format(java.util.Locale.US, concealer.voicing)} | events=${concealer.events} | " +
                "total_ms=${concealer.concealedSamples * 1_000L / rate} | expand_ms=${concealer.expandSamples * 1_000L / rate} | " +
                "comfort_ms=${concealer.comfortSamples * 1_000L / rate} | speed=$playbackSpeed",
        )
    }

    private fun fail(error: Throwable) {
        if (!failure.compareAndSet(null, error)) return
        running.set(false)
        decoderWorker.interrupt()
        playbackWorker.interrupt()
    }

    private fun writePcm(bytes: ByteArray, size: Int, presentationTimeUs: Long) {
        var offset = 0
        if (!audioStarted) {
            if (pendingStartTimeNs == null) {
                pendingStartTimeNs = playoutClock.targetFor(presentationTimeUs).targetTimeNs
            }
            val written = audioTrack.write(bytes, 0, size, AudioTrack.WRITE_NON_BLOCKING)
            require(written >= 0) { "Audio prebuffer failed ($written)" }
            traceAcceptedPcm(bytes, offset, written, presentationTimeUs)
            offset += written
            framesWritten += written / bytesPerAudioFrame
            recordPrebufferedDuration(written)
            startAudioIfReady(force = offset < size)
        }
        if (audioStarted) {
            alignAfterSenderGap(presentationTimeUs)
            followPlayoutClock(presentationTimeUs)
            while (running.get() && offset < size) {
                val written = audioTrack.write(
                    bytes,
                    offset,
                    size - offset,
                    AudioTrack.WRITE_BLOCKING,
                )
                require(written >= 0) { "Audio playback failed ($written)" }
                traceAcceptedPcm(bytes, offset, written, presentationTimeUs)
                offset += written
                framesWritten += written / bytesPerAudioFrame
                if (written == 0) break
            }
        }
        if (offset < size) {
            partialWriteDrops.incrementAndGet()
            drops.incrementAndGet()
        }
        nextExpectedPtsUs = presentationTimeUs +
            size.toLong() / bytesPerAudioFrame * 1_000_000L / header.audioSampleRate
    }

    /**
     * The sender left a gap (mute, push-to-talk release, a dropped packet): start the next spurt on
     * its clock target by writing silence first ([senderGapSilenceMs]). No media is dropped or
     * altered; the silence only stands in for audio the sender did not send. DEBUG control arm:
     * `test_audio_gap_align=false`.
     */
    private fun alignAfterSenderGap(presentationTimeUs: Long) {
        val expectedUs = nextExpectedPtsUs ?: return
        val gapMs = (presentationTimeUs - expectedUs) / 1_000L
        if (gapMs < SENDER_GAP_MIN_MS) return
        senderGaps.incrementAndGet()
        if (!DebugTunables.audioGapAlignment) return
        val nowNs = System.nanoTime()
        val lagMs = (nowNs + queuedPlaybackNs() - playoutClock.targetFor(presentationTimeUs, nowNs).targetTimeNs) / 1_000_000L
        val silenceMs = senderGapSilenceMs(lagMs, playoutSync.baselineLagMs, gapMs, playbackDelayProfile.adaptiveMaxDelayMs)
        if (silenceMs <= 0L) return
        val frames = silenceMs * header.audioSampleRate / 1_000L
        val silence = ByteArray((frames * bytesPerAudioFrame).toInt())
        var offset = 0
        while (running.get() && offset < silence.size) {
            val written = audioTrack.write(silence, offset, silence.size - offset, AudioTrack.WRITE_BLOCKING)
            if (written <= 0) break
            offset += written
        }
        framesWritten += offset / bytesPerAudioFrame
        senderGapSilenceTotalMs.addAndGet(silenceMs)
        Log.i("BluetoothVideo", "BENCHMARK audio_gap_align: gap_ms=$gapMs | lag_ms=$lagMs | " +
            "baseline_ms=${playoutSync.baselineLagMs} | silence_ms=$silenceMs")
    }

    private fun queuedPlaybackNs(): Long {
        val headFrames = audioTrack.playbackHeadPosition.toLong() and 0xFFFF_FFFFL
        val queuedFrames = (framesWritten - headFrames).coerceAtLeast(0L)
        return (queuedFrames * 1_000_000_000L / header.audioSampleRate / playbackSpeed).toLong()
    }

    private fun traceAcceptedPcm(bytes: ByteArray, offset: Int, count: Int, ptsUs: Long) {
        val trace = debugAudioTrace ?: return
        if (count <= 0) return
        val stamp = debugAudioTimestamp
        val anchored = stamp != null && runCatching { audioTrack.getTimestamp(stamp) }.getOrDefault(false)
        trace.record("track_accepted", ptsUs + offset / 2 * 1_000_000L / 48_000, bytes, offset, count,
            if (anchored) stamp?.framePosition else null, if (anchored) stamp?.nanoTime else null,
            runCatching { audioTrack.underrunCount }.getOrNull())
    }

    private fun waitUntil(targetTimeNs: Long) {
        while (running.get()) {
            val remainingNs = targetTimeNs - System.nanoTime()
            if (remainingNs <= 0) return
            LockSupport.parkNanos(minOf(remainingNs, MAX_START_WAIT_SLICE_NS))
            if (Thread.interrupted()) return
        }
    }

    /**
     * How much later than the shared clock the audio being written will play: what is still queued
     * in the AudioTrack (at the current speed) plus now, against this chunk's clock target.
     * Read-only: the receiver already timed this packet at network arrival; a late PCM write is
     * playback lag for the speed controller, not late media.
     */
    private fun followPlayoutClock(presentationTimeUs: Long) {
        val nowNs = System.nanoTime()
        val queuedNs = queuedPlaybackNs()
        val targetNs = playoutClock.targetFor(presentationTimeUs, nowNs).targetTimeNs
        val lagMs = (nowNs + queuedNs - targetNs) / 1_000_000L
        val requested = playoutSync.onSample(lagMs)
        val speed = if (concealer == null) {
            requested ?: return
        } else {
            // Experimental silence-gated catch-up: faster than 1.0x only in quiet chunks (or a large backlog).
            val gated = SilenceGatedCatchUp.speed(
                requested ?: playoutSync.speed,
                lastChunkRms,
                lagMs - (playoutSync.baselineLagMs ?: 0L),
            )
            if (gated == playbackSpeed) return
            gated
        }
        runCatching {
            audioTrack.playbackParams = audioTrack.playbackParams.setSpeed(speed).setPitch(1.0f)
            playbackSpeed = speed
        }.onFailure { error ->
            Log.w("BluetoothVideo", "Audio playback speed $speed rejected: ${error.message}")
        }
    }

    private val bytesPerAudioFrame: Int
        get() = header.audioChannelCount * 2

    private fun recordPrebufferedDuration(written: Int) {
        val bytesPerAudioFrame = StreamProtocol.AUDIO_CHANNEL_COUNT * 2
        prebufferedDurationUs += written.toLong() / bytesPerAudioFrame * 1_000_000L /
            StreamProtocol.AUDIO_SAMPLE_RATE
    }

    private fun startAudioIfReady(force: Boolean = false) {
        if (audioStarted || !running.get()) return
        val startTimeNs = pendingStartTimeNs ?: return
        val nowNs = System.nanoTime()
        if (
            !force &&
            prebufferedDurationUs < playbackDelayProfile.audioPrebufferMs * 1_000L &&
            nowNs < startTimeNs
        ) {
            return
        }
        while (running.get()) {
            val remainingNs = startTimeNs - System.nanoTime()
            if (remainingNs <= 0) break
            LockSupport.parkNanos(minOf(remainingNs, MAX_START_WAIT_SLICE_NS))
            if (Thread.interrupted()) return
        }
        if (!running.get()) return
        audioTrack.play()
        audioStarted = true
    }

    /** The listener's own Mute and audio-focus loss silence the output; the link and the clock keep running. */
    fun setMuted(muted: Boolean) {
        runCatching { audioTrack.setVolume(if (muted) 0f else (DebugTunables.playbackGain ?: 1f)) }
    }

    /** This decoder's cumulative counters for [StreamAudioStats]. */
    private fun streamAudioCounters(): StreamAudioDecoderCounters = StreamAudioDecoderCounters(
        concealedFrames = nativePlcFrames.get() + nativeFecFrames.get(),
        concealedMs = concealer?.let { it.concealedSamples * 1_000L / header.audioSampleRate } ?: 0L,
        senderGaps = senderGaps.get(),
        gapSilenceMs = senderGapSilenceTotalMs.get(),
        drops = drops.get(),
        underruns = runCatching { audioTrack.underrunCount.toLong() }.getOrNull()?.takeIf { it >= 0L },
    )

    override fun close() {
        // Before the track is released: the final line still reads its underrun count (written once).
        streamAudioStats?.finish(streamAudioCounters())?.let { Log.i("BluetoothVideo", it) }
        if (!running.getAndSet(false)) return
        // A timed, serialized audio command (AudioTrack.stop/release can stall on some Exynos phones);
        // `test_audio_off_main` runs it off the caller's main thread.
        AudioCommandQueue.shared.offload("opus_decoder_close") { closeNow() }
    }

    private fun closeNow() {
        concealer?.let {
            if (BuildConfig.DEBUG) Log.i(
                "BluetoothVideo",
                "BENCHMARK audio_conceal: event=summary | events=${it.events} | " +
                    "total_ms=${it.concealedSamples * 1_000L / header.audioSampleRate} | " +
                    "expand_ms=${it.expandSamples * 1_000L / header.audioSampleRate} | " +
                    "comfort_ms=${it.comfortSamples * 1_000L / header.audioSampleRate}",
            )
        }
        packets.clear()
        pcmPackets.clear()
        runCatching { audioTrack.stop() }
        decoderWorker.interrupt()
        playbackWorker.interrupt()
        runCatching { decoderWorker.join(1_000) }
        runCatching { playbackWorker.join(1_000) }
        releaseCodec()
        releaseAudioTrack()
    }

    @Synchronized
    private fun releaseCodec() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { nativeDecoder?.close() }
    }

    @Synchronized
    private fun releaseAudioTrack() {
        debugAudioTrace?.close()
        debugAudioTrace = null
        runCatching { audioTrack.stop() }
        runCatching { audioTrack.flush() }
        runCatching { audioTrack.release() }
    }

    private companion object {
        const val MAX_ENCODED_PACKET_BYTES = 4 * 1_024
        const val MAX_PCM_OUTPUT_BYTES = 64 * 1_024
        const val INPUT_TIMEOUT_US = 2_000L
        const val ACTIVE_POLL_MS = 100L
        /** Concealment polls this often so a nearly dry AudioTrack is topped up before it underruns. */
        const val CONCEAL_POLL_MS = 5L
        /** An Opus packet pair decodes to up to two PCM buffers. */
        const val PCM_PACKETS_PER_ENCODED_PACKET = 2
        const val BACKPRESSURE_WAIT_MS = 3_000L
        const val MAX_START_WAIT_SLICE_NS = 5_000_000L
        const val CONCEAL_LOG_FIRST = 50L
        const val CONCEAL_LOG_EVERY = 50L
    }

    private fun opusDecoderCandidates(format: MediaFormat): List<String> {
        val decoders = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.filter { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_AUDIO_OPUS, ignoreCase = true) }
        }.map { it.name }
        val preferred = MediaCodecList(MediaCodecList.ALL_CODECS).findDecoderForFormat(format)
        val policyFirst = decoders.filter {
            DeviceFamilyPolicy.componentEffects<PreferredOpusDecoder>(it, DeviceTraits.current()).isNotEmpty()
        }
        return (policyFirst + listOfNotNull(preferred) + decoders).distinct()
    }
}
