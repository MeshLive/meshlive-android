package com.example.bluetoothtest

/**
 * Experimental (default off, `test_call_prewarm`, [DebugTunables.callPrewarm]): while a private video call rings, each
 * phone prepares what needs neither camera nor microphone, so the work after Accept is only the camera itself.
 *
 * Why (JT1, 2026-11-10): user-launch calls (un2g/un2ghw/un2w1a15sc*-20261110n) held, but dial to first picture took
 * 3.1-8.9 s against the 3.0 s gate; the S22 hardware-HEVC -> ASUS call failed only that gate (4.3 / 5.1 s). Bluetooth
 * setup was about 1.0-1.3 s on a healthy GATT pair; after Accept each phone cold-started camera and encoder (0.8-2.1 s
 * to the first encoded frame, the largest stage) while the link sat ready. A stream source already overlaps that work
 * with link setup ([SourceMediaPrewarm]); a call could not, because its camera may open only after Accept.
 *
 * What is prepared, and when:
 * - Video encoder ([CallEncoderPrewarm]): at Ringing on both phones (the caller while it waits for Accept, the callee
 *   while its ring shows, which is after the block check: a blocked or refused caller never reaches Ringing), for the
 *   profile media start would use. [CameraVideoEncoder.prepare] creates, configures and starts the codec (input surface
 *   or buffers ready) and reads camera metadata; it opens no camera device and starts no capture. Packets the codec emits
 *   before the call adopts it (codec configuration only: no frame can exist without input) are held in order and handed
 *   over at adoption ([PrewarmPacketSink]).
 * - Video decoder: when the peer's call header names its codec and size (the peer sends it at its own Accept, ahead of
 *   its camera start, about a link round trip after Accept), the decoder component is allocated off the receive thread
 *   ([PreparedDecoderComponent]) and the first key frame configures it. No earlier moment exists without a protocol change.
 * - Not prepared: the microphone, the camera, and the audio route (communication mode and speaker would change the
 *   phone's audio while the ringtone plays); the Opus decoder needs the peer's audio configuration, which arrives with
 *   the audio.
 *
 * Where the camera and microphone gate is (unchanged): the camera opens only in [CameraVideoEncoder.start], called only
 * from MainActivity.startPrivateCallCamera; the microphone only in MainActivity.startPrivateCallMedia. Both run only from
 * AndroidPrivateVideoCall.startLocalMedia, which runs after the local Accept and the callee's media decision
 * (CalleeMediaStart.onAccepted / onRevealAdmitted: the caller's verified, unblocked reveal), or after the peer's Accept
 * once the blocked-callee latch is clear. The pre-warm never calls [CameraVideoEncoder.start].
 *
 * Release: decline, cancel, ring timeout, failure, end (phase ENDED), a voice answer, a profile that changed before
 * media start (the call then builds its encoder as today), or [MAX_HOLD_MS] after preparing, whichever comes first;
 * the encoder closes on the camera executor (CameraVideoEncoder.close bounds each codec call).
 */
internal object CallPrewarm {
    /** Longest a prepared encoder is held without a media start (a ring with its timeout switched off). */
    const val MAX_HOLD_MS = 60_000L

    /** Prepare at this phase: the two ringing phases only (a voice call prepares no video encoder). */
    fun preparesAt(enabled: Boolean, phase: PrivateCallPhase, video: Boolean): Boolean =
        enabled && video && (phase == PrivateCallPhase.OUTGOING_RINGING || phase == PrivateCallPhase.INCOMING_RINGING)

    /** Release whatever is still held at this phase: the call is answered (media start took it or did not) or over. */
    fun releasesAt(phase: PrivateCallPhase): Boolean =
        phase == PrivateCallPhase.ACTIVE || phase == PrivateCallPhase.ENDED
}

/**
 * Packets and a failure from a prepared encoder before the call adopts it. Codec configuration is held (bounded) and
 * replayed in order at [attach]; anything else before [attach] is counted and dropped (there is no camera input yet).
 */
internal class PrewarmPacketSink {
    private val early = ArrayList<Triple<Int, Long, ByteArray>>()
    private var earlyBytes = 0
    private var target: ((Int, Long, ByteArray) -> Unit)? = null
    private var failureTarget: ((Throwable) -> Unit)? = null

    @Volatile var failure: Throwable? = null
        private set
    @Volatile var discarded = 0
        private set

    fun onPacket(flags: Int, timeUs: Long, bytes: ByteArray) {
        synchronized(this) {
            if (target == null) {
                if (flags and StreamProtocol.FLAG_CODEC_CONFIG != 0 && early.size < MAX_EARLY_PACKETS &&
                    earlyBytes + bytes.size <= MAX_EARLY_BYTES
                ) {
                    early += Triple(flags, timeUs, bytes)
                    earlyBytes += bytes.size
                } else {
                    discarded++
                }
                return
            }
            // Under the lock, so a live packet never overtakes the replay of the held ones.
            requireNotNull(target).invoke(flags, timeUs, bytes)
        }
    }

    fun onFailure(error: Throwable) {
        val forward = synchronized(this) {
            failureTarget ?: run {
                if (failure == null) failure = error
                null
            }
        }
        forward?.invoke(error)
    }

    /** The call adopts the encoder: the held configuration first, then every later packet and failure. */
    fun attach(onPacket: (Int, Long, ByteArray) -> Unit, onFailure: (Throwable) -> Unit) {
        val missed = synchronized(this) {
            early.forEach { (flags, timeUs, bytes) -> onPacket(flags, timeUs, bytes) }
            early.clear()
            earlyBytes = 0
            target = onPacket
            failureTarget = onFailure
            failure
        }
        // A failure between the call taking the encoder and this attach still ends the call's camera as today.
        missed?.let(onFailure)
    }

    private companion object {
        const val MAX_EARLY_PACKETS = 8
        const val MAX_EARLY_BYTES = 64 * 1024
    }
}

/**
 * At most one encoder prepared for one call. [run] is the camera executor (serial: a prepare finishes before the
 * adopted encoder's start, and a release before the next prepare); [prepare] must not open the camera.
 */
internal class CallEncoderPrewarm<K : Any, E : Any>(
    private val run: (Runnable) -> Unit,
    private val nowMs: () -> Long,
    private val log: (String) -> Unit,
    private val prepare: (E) -> Unit,
    private val release: (E) -> Unit,
) {
    class Taken<E>(val encoder: E, val sink: PrewarmPacketSink)

    private class Held<K, E>(val key: K, val encoder: E, val sink: PrewarmPacketSink, val preparedAtMs: Long) {
        @Volatile var ready = false
        @Volatile var prepareError: Throwable? = null
    }

    private var held: Held<K, E>? = null

    val holding: Boolean
        @Synchronized get() = held != null

    /** While ringing: create (with its packet sink) and prepare an encoder for [key]; nothing when one is held for it. */
    @Synchronized
    fun offer(key: K, create: (PrewarmPacketSink) -> E): Boolean {
        held?.let { current ->
            if (current.key == key) return false
            releaseLocked("profile_changed")
        }
        val sink = PrewarmPacketSink()
        val encoder = runCatching { create(sink) }.getOrElse { error ->
            log("BENCHMARK call_prewarm: event=skipped | reason=create_failed(${error.javaClass.simpleName})")
            return false
        }
        val entry = Held(key, encoder, sink, nowMs())
        held = entry
        log("BENCHMARK call_prewarm: event=prepare | key=$key")
        run(
            Runnable {
                val startedMs = nowMs()
                runCatching { prepare(encoder) }
                    .onSuccess {
                        entry.ready = true
                        log("BENCHMARK call_prewarm: event=ready | prepareMs=${nowMs() - startedMs}")
                    }
                    .onFailure { error ->
                        entry.prepareError = error
                        log("BENCHMARK call_prewarm: event=prepare_failed | error=${error.javaClass.simpleName}")
                    }
            },
        )
        return true
    }

    /**
     * Media start (after Accept and the media decision): the encoder prepared for exactly [key], or null when there is
     * none, it failed, or the profile changed (released here); the caller then builds its encoder as today. A taken
     * encoder may still be preparing: [run] is serial, so the caller's start on the same executor follows the prepare.
     */
    @Synchronized
    fun take(key: K): Taken<E>? {
        val current = held ?: return null
        val reason = when {
            current.key != key -> "profile_changed"
            current.prepareError != null -> "prepare_failed"
            current.sink.failure != null -> "encoder_failed"
            else -> null
        }
        if (reason != null) {
            releaseLocked(reason)
            return null
        }
        held = null
        log(
            "BENCHMARK call_prewarm: event=adopted | ready=${current.ready} | heldMs=${nowMs() - current.preparedAtMs} | " +
                "discardedEarly=${current.sink.discarded}",
        )
        return Taken(current.encoder, current.sink)
    }

    @Synchronized
    fun release(reason: String) {
        releaseLocked(reason)
    }

    /** The bounded hold: releases an encoder prepared [CallPrewarm.MAX_HOLD_MS] or more ago. */
    @Synchronized
    fun expire() {
        val current = held ?: return
        if (nowMs() - current.preparedAtMs >= CallPrewarm.MAX_HOLD_MS) releaseLocked("held_too_long")
    }

    private fun releaseLocked(reason: String) {
        val current = held ?: return
        held = null
        log("BENCHMARK call_prewarm: event=released | reason=$reason | heldMs=${nowMs() - current.preparedAtMs}")
        run(Runnable { runCatching { release(current.encoder) } })
    }
}

/** Everything the call's camera encoder is built from (MainActivity.newPrivateCallCameraEncoder); logged without identity. */
internal data class CallPrewarmKey(
    val codec: VideoCodec,
    val settings: VideoSettings,
    val lens: CameraLens,
    val rateControl: EncoderRateControl,
    val scaleMode: VideoScaleMode,
    val gpuScale: GpuScaleRequest?,
    val rawInput: String?,
) {
    override fun toString(): String =
        "${codec.encoderName}/${settings.resolution.width}x${settings.resolution.height}@${settings.fps}/" +
            "${settings.bitRate / 1_000}kbps/${lens.name.lowercase()}" + (if (gpuScale != null) "/gpu" else "")
}

/**
 * Experimental call pre-warm, decoder half: one decoder component allocated off the receive thread (by [allocate], on
 * [runAsync]) when the peer's call header names its codec and size, handed to the call's first decoder build when that
 * asks for the same component, and released otherwise: another name, a build that came before it was ready, or [close].
 */
internal class PreparedDecoderComponent<C : Any>(
    private val runAsync: (Runnable) -> Unit,
    private val allocate: () -> Pair<String, C>?,
    private val release: (C) -> Unit,
    private val log: (String) -> Unit,
) {
    private var name: String? = null
    private var component: C? = null
    private var settled = false

    /** Starts the allocation; at most once. */
    fun prepare() {
        runAsync(
            Runnable {
                val allocated = runCatching { allocate() }.getOrNull()
                val keep = synchronized(this) {
                    if (settled || allocated == null) {
                        false
                    } else {
                        name = allocated.first
                        component = allocated.second
                        true
                    }
                }
                if (allocated != null && !keep) {
                    runCatching { release(allocated.second) }
                    log("BENCHMARK call_prewarm_decoder: event=unused | decoder=${allocated.first} | reason=late")
                } else if (allocated != null) {
                    log("BENCHMARK call_prewarm_decoder: event=allocated | decoder=${allocated.first}")
                }
            },
        )
    }

    /** The first decoder build asks for [wanted]: the component when it is ready under that name, else null. */
    fun take(wanted: String): C? {
        val (heldName, held) = synchronized(this) {
            settled = true
            val result = name to component
            name = null
            component = null
            result
        }
        if (held == null) return null
        if (!heldName.equals(wanted, ignoreCase = true)) {
            runCatching { release(held) }
            log("BENCHMARK call_prewarm_decoder: event=unused | decoder=$heldName | wanted=$wanted | reason=other_decoder")
            return null
        }
        log("BENCHMARK call_prewarm_decoder: event=used | decoder=$heldName")
        return held
    }

    fun close() {
        val held = synchronized(this) {
            settled = true
            component.also { component = null; name = null }
        }
        if (held != null) {
            runCatching { release(held) }
            log("BENCHMARK call_prewarm_decoder: event=unused | reason=closed")
        }
    }
}
