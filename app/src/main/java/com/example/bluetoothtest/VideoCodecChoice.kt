package com.example.bluetoothtest

/** Which session the encoder is chosen for (Design HC: calls and streams have different Automatic orders). */
internal enum class CodecChoiceMode(val label: String) { CALL("call"), STREAM("stream") }

/** The masters and prefer arms one choice reads (see [VideoCodecSwitches.current]). */
internal data class CodecChoiceSwitches(
    val hevc: Boolean,
    val h264: Boolean,
    val preferHwAv1: Boolean = true,
    val preferHevc: Boolean = true,
    val preferHwAvc: Boolean = true,
    /** Design VX: Automatic may pick the bundled libvpx VP9 encoder (off: manual choice only; shipped on since 0.4.2). */
    val preferLibvpx: Boolean = false,
    /**
     * Peer bit 7 keeps this phone's hardware HEVC encoders (that the peer decodes) ahead of VP9; false is the DEBUG
     * control arm `test_bit7_hw_hevc=false`: VP9 goes first even over hardware HEVC, as the first Design HC draft did.
     */
    val bit7AllowsHwHevc: Boolean = true,
    /** Switch `codec_power_aware` ([CodecPowerAware]); false is the DEBUG control arm `test_codec_power_aware=false`. */
    val powerAware: Boolean = true,
    /** The power state read at session start ([CodecPowerAware.read]); normal outside a session start. */
    val power: CodecPowerState = CodecPowerState.NORMAL,
    /** Experimental `test_sw_shortfall_avc` ([SoftwareEncoderShortfall]); false (the default) leaves every order as it is. */
    val swShortfall: Boolean = false,
    /** Software encoders remembered short for this session ([SoftwareShortfallStore.slowEncoders]), read at session start. */
    val slowSoftware: Set<String> = emptySet(),
) {
    /** Either master on: Design HC rules; both off: exactly today's two-codec rules. */
    val anyNew: Boolean get() = hevc || h264

    companion object {
        val LEGACY = CodecChoiceSwitches(hevc = false, h264 = false)
    }
}

/** The session the choice must fit (its maximum size and FPS); null skips the per-session eligibility checks. */
internal data class CodecSession(val width: Int, val height: Int, val fps: Int) {
    companion object {
        fun of(settings: VideoSettings) = CodecSession(settings.resolution.width, settings.resolution.height, settings.fps)
    }
}

/**
 * DECISIONS "Design HC", table "Decode coverage without an add-on", kept as data: the codecs every MeshLive platform
 * (Android, Apple, Windows) decodes without an add-on. It drives the stream order (open question Q-HC1, option A) and
 * changes only by a reviewed update when a platform's coverage changes. Today: VP9 and H.264; HEVC needs Windows'
 * paid extension and AV1 needs hardware or an add-on on Apple and Windows.
 */
internal object StreamCodecCoverage {
    val UNIVERSAL: Set<Int> = setOf(StreamProtocol.CODEC_VP9, StreamProtocol.CODEC_H264)

    /** Compression class, lower is better: AV1; then HEVC and VP9 (VP9-class); then H.264. */
    fun efficiencyClass(codecId: Int): Int = when (codecId) {
        StreamProtocol.CODEC_AV1 -> 0
        StreamProtocol.CODEC_HEVC, StreamProtocol.CODEC_VP9 -> 1
        else -> 2
    }
}

/** One choice: what is sent, the ordered list fallback walks, why, and what was requested. */
internal class CodecChoice(
    val sent: VideoCodec?,
    /** The ordered, filtered list for this direction; fallback walks only this list. */
    val ordered: List<VideoCodec>,
    val reason: String,
    val requested: VideoCodec,
    /** Extra log notes, e.g. `bit7_without_vp9_ignored`. */
    val notes: List<String> = emptyList(),
    /** `codec_power_aware` changed the Automatic order of this session ([CodecPowerAware.reorder]). */
    val powerApplied: Boolean = false,
    /** `test_sw_shortfall_avc` put hardware H.264 ahead of a remembered-short software encoder ([SoftwareEncoderShortfall.reorder]). */
    val shortfallApplied: Boolean = false,
)

/**
 * The one choice operation per direction (Design HC review 2 F12). With both masters off every call delegates to the
 * two-codec rules ([AutomaticEncoderChoice.order], [privateCallCodecForPeer]), so VP9/AV1 behaviour is byte-identical.
 *
 * With a master on:
 * - Calls: hardware AV1, hardware HEVC, hardware VP9, hardware H.264, then today's software order; only codecs the
 *   peer's end-to-end decode mask names count. Precedence: the sender's family `CallVp9Compatibility` (codecs 3, 4, 5)
 *   gives VP9; then peer bit 7 (only with peer VP9 bit 0) puts VP9 first when this phone encodes it, below only this
 *   phone's hardware HEVC encoders the peer decodes (bit 7 demotes AV1, H.264 and software, not hardware HEVC); then
 *   peer bit 4 moves AV1 below this phone's hardware HEVC and hardware VP9 encoders the peer decodes, and only below
 *   those.
 * - Streams (Q-HC1 A): hardware AV1, then hardware codecs by compression class with the universally decodable codec
 *   first inside a class ([StreamCodecCoverage]): AV1, VP9, HEVC, H.264, then software. If HEVC becomes universal the
 *   stream order equals the call order.
 * - Bundled libvpx VP9 (Design VX, `prefer_libvpx`): after every hardware AV1, HEVC and VP9 encoder and before hardware
 *   H.264, in calls and streams alike (streams: hw AV1 > hw VP9 > hw HEVC > libvpx > hw H.264); libaom and the other
 *   software encoders stay last, the resort when nothing above can start.
 * - `codec_power_aware` ([CodecPowerAware]): battery saver or a warm phone at session start moves the usable hardware
 *   encoders ahead of a software first choice (after the peer filter), never over a family VP9 or bit 7 rule or a
 *   manual choice.
 * - A prefer arm that is off removes its codec from the Automatic list; software and ineligible HC components are never
 *   in it. A manual choice the peer decodes is sent as chosen (bits 4 and 7 never override it; the family VP9 policy
 *   still applies); one the peer cannot decode falls back along the Automatic list.
 */
internal object VideoCodecChoice {
    private fun isHardware(codec: VideoCodec) = codec.backend == VideoEncoderBackend.MEDIA_CODEC && codec.hardwareAccelerated

    private fun callRank(codecId: Int): Int = when (codecId) {
        StreamProtocol.CODEC_AV1 -> 0
        StreamProtocol.CODEC_HEVC -> 1
        StreamProtocol.CODEC_VP9 -> 2
        StreamProtocol.CODEC_H264 -> 3
        else -> 4
    }

    /** Hardware codec ranking of [mode] (lower first). */
    fun rankKey(mode: CodecChoiceMode, codecId: Int): List<Int> = when (mode) {
        CodecChoiceMode.CALL -> listOf(callRank(codecId))
        CodecChoiceMode.STREAM -> listOf(
            StreamCodecCoverage.efficiencyClass(codecId),
            if (codecId == StreamProtocol.CODEC_AV1 || codecId in StreamCodecCoverage.UNIVERSAL) 0 else 1,
            callRank(codecId),
        )
    }

    private val rankComparator: (CodecChoiceMode) -> Comparator<Int> = { mode ->
        Comparator { a, b ->
            val ka = rankKey(mode, a)
            val kb = rankKey(mode, b)
            ka.zip(kb).map { (x, y) -> x.compareTo(y) }.firstOrNull { it != 0 } ?: 0
        }
    }

    /** The prefer arm of [codecId] is on (HEVC, H.264; AV1's arm demotes rather than removes). */
    private fun preferred(codecId: Int, switches: CodecChoiceSwitches): Boolean = when (codecId) {
        StreamProtocol.CODEC_HEVC -> switches.hevc && switches.preferHevc
        StreamProtocol.CODEC_H264 -> switches.h264 && switches.preferHwAvc
        else -> true
    }

    /** Why [codec] is not in the Automatic list, or null when it is. */
    private fun automaticExclusion(
        codec: VideoCodec,
        switches: CodecChoiceSwitches,
        session: CodecSession?,
        excluded: (VideoCodec) -> Boolean,
    ): String? {
        if (!codec.isHcCodec) return null
        if (!preferred(codec.protocolId, switches)) {
            return if (codec.protocolId == StreamProtocol.CODEC_HEVC) "hevc_not_preferred" else "avc_not_preferred"
        }
        if (!isHardware(codec)) return "ineligible:${HcEncoderEligibility.BLOCK_SOFTWARE}"
        codec.automaticBlock?.let { return "ineligible:$it" }
        if (excluded(codec)) return "ineligible:policy"
        session?.let { s -> HcEncoderEligibility.sessionBlock(codec, s.width, s.height, s.fps)?.let { return "ineligible:$it" } }
        return null
    }

    /**
     * The Automatic order of [mode] before any peer filter. Legacy (both masters off): [AutomaticEncoderChoice.order].
     * HC components that Automatic may not use are absent; a policy-excluded hardware AV1 component moves to the end as
     * today.
     */
    fun automatic(
        available: List<VideoCodec>,
        mode: CodecChoiceMode,
        switches: CodecChoiceSwitches,
        session: CodecSession?,
        excluded: (VideoCodec) -> Boolean,
    ): List<VideoCodec> {
        val candidates = automaticCandidates(available, switches.preferLibvpx)
        if (!switches.anyNew) return AutomaticEncoderChoice.order(candidates, switches.preferHwAv1) { name -> excluded(available.first { it.encoderName == name }) }
        val usable = candidates.filter { automaticExclusion(it, switches, session, excluded) == null }
        val blockedAv1 = usable.filter { isHardware(it) && it.protocolId == StreamProtocol.CODEC_AV1 && excluded(it) }
        val hardware = usable.filter { isHardware(it) && it !in blockedAv1 }
            .sortedWith(compareBy(rankComparator(mode)) { it.protocolId })
        val software = usable.filter { !isHardware(it) && it !in blockedAv1 }
        val ordered = if (switches.preferHwAv1) {
            hardware
        } else {
            hardware.filter { it.protocolId != StreamProtocol.CODEC_AV1 } + hardware.filter { it.protocolId == StreamProtocol.CODEC_AV1 }
        }
        // Design VX (prefer_libvpx on): libvpx VP9 after the hardware AV1/HEVC/VP9 encoders and before hardware H.264
        // and every other software encoder (libaom included), for calls and streams alike. Q-HC1 A puts hardware VP9
        // above hardware HEVC in streams, not software VP9: libvpx costs about 4x the CPU of hardware HEVC (ASUS heat
        // arms, DECISIONS "Automatic codec order after the 2026-10-28 phone measurements").
        val libvpx = software.filter { it.backend == VideoEncoderBackend.NATIVE_LIBVPX }
        val avcAt = ordered.indexOfFirst { it.protocolId == StreamProtocol.CODEC_H264 }.let { if (it < 0) ordered.size else it }
        return ordered.take(avcAt) + libvpx + ordered.drop(avcAt) + (software - libvpx.toSet()) + blockedAv1
    }

    /**
     * The picker list (streams use its first entry when nothing was picked by hand): the stream Automatic order, then
     * every other offered encoder (software HC, prefer-arm and ineligible HC components) for a manual choice.
     * Legacy: exactly [AutomaticEncoderChoice.order].
     */
    fun pickerOrder(
        available: List<VideoCodec>,
        switches: CodecChoiceSwitches,
        session: CodecSession?,
        excluded: (VideoCodec) -> Boolean,
    ): List<VideoCodec> {
        val automatic = automatic(available, CodecChoiceMode.STREAM, switches, session, excluded)
        return automatic + available.filter { it !in automatic }
    }

    private fun bestVp9(available: List<VideoCodec>, switches: CodecChoiceSwitches): VideoCodec? =
        automaticCandidates(available, switches.preferLibvpx)
            .filter { it.protocolId == StreamProtocol.CODEC_VP9 }.minByOrNull(VideoCodec::selectionPriority)

    /**
     * The codec this phone sends to a call peer whose authenticated end-to-end decode mask is [peerDecodeMask].
     * [requested] is the picker's choice ([userChoice]) or, for Automatic, any entry (the list decides); [familyVp9]
     * is this phone's `CallVp9Compatibility` on the call transport.
     */
    fun forCall(
        requested: VideoCodec,
        userChoice: Boolean,
        available: List<VideoCodec>,
        peerDecodeMask: Int,
        switches: CodecChoiceSwitches,
        familyVp9: Boolean,
        session: CodecSession?,
        excluded: (VideoCodec) -> Boolean,
        staticIneligibility: Map<Int, String> = emptyMap(),
    ): CodecChoice {
        if (!switches.anyNew) {
            val sent = privateCallCodecForPeer(requested, available, peerDecodeMask, switches.preferLibvpx)
            return CodecChoice(sent, listOfNotNull(sent), legacyReason(sent ?: requested, requested, available, userChoice, switches, peerDecodeMask, excluded), requested)
        }
        fun decodes(codec: VideoCodec) = peerDecodeMask and codecCapabilityMask(codec.protocolId) != 0
        val notes = ArrayList<String>()
        val bit7 = peerDecodeMask and NodeCapabilities.DECODE_PREFERS_VP9 != 0
        val bit7Counts = bit7 && peerDecodeMask and NodeCapabilities.CODEC_VP9 != 0
        if (bit7 && !bit7Counts) notes += "bit7_without_vp9_ignored"
        val bit4 = peerDecodeMask and NodeCapabilities.DECODE_AV1_SOFTWARE != 0
        val vp9 = bestVp9(available, switches)
        val auto = automatic(available, CodecChoiceMode.CALL, switches, session, excluded)
        val automaticList = auto.filter(::decodes).let { if (bit4) demoteAv1(it) else it }

        if (userChoice) {
            val start = if (familyVp9 && requested.protocolId in CALL_VP9_COMPATIBILITY_CODECS && vp9 != null) vp9 else requested
            if (decodes(start)) {
                return CodecChoice(start, listOf(start) + automaticList.filter { it != start }, if (start != requested) "family_vp9_compatibility" else "user_choice", requested, notes)
            }
            notes += "user_choice_not_decodable"
            val sent = automaticList.firstOrNull()
            return CodecChoice(sent, automaticList, "user_choice_fallback", requested, notes)
        }

        var ordered: List<VideoCodec> = automaticList
        var forced: String? = null
        val top = auto.firstOrNull()
        if (familyVp9 && top != null && top.protocolId in CALL_VP9_COMPATIBILITY_CODECS && vp9 != null && decodes(vp9)) {
            ordered = listOf(vp9) + ordered.filter { it != vp9 }
            forced = "family_vp9_compatibility"
        } else if (bit7Counts && vp9 != null) {
            // Bit 7 exists for the legacy Qualcomm call profile (a VP9 compatibility rule, CDD: every peer decodes VP9);
            // its evidence is about AV1 and the link, not a decoder fault HEVC would share, so a hardware HEVC encoder
            // the peer decodes stays ahead (DECISIONS "Design HC", "Bit 7 and hardware HEVC").
            val hwHevc = if (switches.bit7AllowsHwHevc) {
                ordered.filter { isHardware(it) && it.protocolId == StreamProtocol.CODEC_HEVC }
            } else {
                emptyList()
            }
            if (hwHevc.isNotEmpty()) notes += "bit7_hw_hevc_first"
            ordered = hwHevc + vp9 + ordered.filter { it != vp9 && it !in hwHevc }
            forced = "peer_prefers_vp9"
        }
        // codec_power_aware: decided here, once per call (the caller passes the session-start power state).
        val powered = if (forced == null) CodecPowerAware.reorder(ordered, switches.power, switches.powerAware, excluded) else null
        if (powered != null) ordered = powered
        // test_sw_shortfall_avc (Experimental, off): a remembered-short software first choice yields to hardware H.264 the
        // peer decodes; never over a family VP9 or bit 7 rule, and not when the power-aware start already reordered.
        val shortfall = if (forced == null && powered == null) {
            SoftwareEncoderShortfall.reorder(ordered, switches.swShortfall, switches.slowSoftware, excluded)
        } else {
            null
        }
        if (shortfall != null) ordered = shortfall
        val sent = ordered.firstOrNull()
        val reason = when {
            sent == null -> "no_common_codec"
            forced != null && sent == vp9 -> forced
            powered != null -> CodecPowerAware.reason(sent)
            shortfall != null -> SoftwareEncoderShortfall.REASON
            else -> automaticReason(sent, available, CodecChoiceMode.CALL, switches, session, excluded, peerDecodeMask, staticIneligibility)
        }
        return CodecChoice(sent, ordered, reason, requested, notes, powerApplied = powered != null, shortfallApplied = shortfall != null)
    }

    /** Peer bit 4: AV1 entries move below this phone's hardware HEVC and hardware VP9 entries (those the peer decodes). */
    private fun demoteAv1(list: List<VideoCodec>): List<VideoCodec> {
        val av1 = list.filter { it.protocolId == StreamProtocol.CODEC_AV1 }
        if (av1.isEmpty()) return list
        val lastPreferred = list.indexOfLast {
            isHardware(it) && (it.protocolId == StreamProtocol.CODEC_HEVC || it.protocolId == StreamProtocol.CODEC_VP9)
        }
        if (lastPreferred < 0) return list
        val before = list.subList(0, lastPreferred + 1).filter { it !in av1 }
        val after = list.subList(lastPreferred + 1, list.size)
        return before + av1.filter { list.indexOf(it) <= lastPreferred } + after
    }

    /** The stream source's choice: the picker's entry by hand, else the first Automatic entry. */
    fun forStream(
        requested: VideoCodec,
        userChoice: Boolean,
        available: List<VideoCodec>,
        switches: CodecChoiceSwitches,
        session: CodecSession?,
        excluded: (VideoCodec) -> Boolean,
        staticIneligibility: Map<Int, String> = emptyMap(),
    ): CodecChoice {
        if (!switches.anyNew) {
            return CodecChoice(requested, listOf(requested), legacyReason(requested, requested, available, userChoice, switches, null, excluded), requested)
        }
        val auto = automatic(available, CodecChoiceMode.STREAM, switches, session, excluded)
        if (userChoice) return CodecChoice(requested, listOf(requested) + auto.filter { it != requested }, "user_choice", requested)
        CodecPowerAware.reorder(auto, switches.power, switches.powerAware, excluded)?.let { powered ->
            return CodecChoice(powered.first(), powered, CodecPowerAware.reason(powered.first()), requested, powerApplied = true)
        }
        SoftwareEncoderShortfall.reorder(auto, switches.swShortfall, switches.slowSoftware, excluded)?.let { shortfall ->
            return CodecChoice(shortfall.first(), shortfall, SoftwareEncoderShortfall.REASON, requested, shortfallApplied = true)
        }
        val sent = auto.firstOrNull() ?: requested
        return CodecChoice(sent, auto, automaticReason(sent, available, CodecChoiceMode.STREAM, switches, session, excluded, null, staticIneligibility), requested)
    }

    private fun legacyReason(
        sent: VideoCodec,
        requested: VideoCodec,
        available: List<VideoCodec>,
        userChoice: Boolean,
        switches: CodecChoiceSwitches,
        peerDecodeMask: Int?,
        excluded: (VideoCodec) -> Boolean,
    ): String = AutomaticEncoderChoice.reason(
        sent, requested, available, userChoice, switches.preferHwAv1, peerDecodeMask,
    ) { name -> available.firstOrNull { it.encoderName == name }?.let(excluded) ?: false }.label

    private fun peerSkip(codecId: Int): String = when (codecId) {
        StreamProtocol.CODEC_AV1 -> "peer_no_av1_decode"
        StreamProtocol.CODEC_HEVC -> "peer_no_hevc_decode"
        StreamProtocol.CODEC_H264 -> "peer_no_avc_decode"
        else -> "peer_no_vp9_decode"
    }

    /**
     * Why Automatic sent [sent]: the first better-ranked hardware codec that was skipped names the reason; with none,
     * `hw_av1_preferred`, `hw_hevc_preferred`, `hw_avc_preferred`, or `no_hw_av1` for VP9 and software.
     */
    fun automaticReason(
        sent: VideoCodec,
        available: List<VideoCodec>,
        mode: CodecChoiceMode,
        switches: CodecChoiceSwitches,
        session: CodecSession?,
        excluded: (VideoCodec) -> Boolean,
        peerDecodeMask: Int?,
        staticIneligibility: Map<Int, String> = emptyMap(),
    ): String {
        val codecs = listOf(StreamProtocol.CODEC_AV1, StreamProtocol.CODEC_HEVC, StreamProtocol.CODEC_VP9, StreamProtocol.CODEC_H264)
            .filter { it == StreamProtocol.CODEC_VP9 || it == StreamProtocol.CODEC_AV1 ||
                it == StreamProtocol.CODEC_HEVC && switches.hevc || it == StreamProtocol.CODEC_H264 && switches.h264 }
            .sortedWith(rankComparator(mode))
        val sentIsHardware = isHardware(sent)
        for (codecId in codecs) {
            if (sentIsHardware && codecId == sent.protocolId) break
            val hardware = available.filter { isHardware(it) && it.protocolId == codecId }
            if (hardware.isEmpty()) {
                staticIneligibility[codecId]?.let { return "ineligible:$it" }
                continue
            }
            val component = hardware.first()
            if (codecId == StreamProtocol.CODEC_AV1) {
                if (!switches.preferHwAv1) return EncoderChoiceReason.HW_AV1_NOT_PREFERRED.label
                if (excluded(component)) continue
            } else {
                automaticExclusion(component, switches, session, excluded)?.let { return it }
            }
            if (peerDecodeMask != null) {
                if (peerDecodeMask and codecCapabilityMask(codecId) == 0) return peerSkip(codecId)
                if (codecId == StreamProtocol.CODEC_AV1 && peerDecodeMask and NodeCapabilities.DECODE_AV1_SOFTWARE != 0) {
                    return "peer_av1_software"
                }
            }
        }
        return when {
            sentIsHardware && sent.protocolId == StreamProtocol.CODEC_AV1 -> EncoderChoiceReason.HW_AV1_PREFERRED.label
            sentIsHardware && sent.protocolId == StreamProtocol.CODEC_HEVC -> "hw_hevc_preferred"
            sentIsHardware && sent.protocolId == StreamProtocol.CODEC_H264 -> "hw_avc_preferred"
            else -> EncoderChoiceReason.NO_HW_AV1.label
        }
    }

    /**
     * Production exclusion: an active component-scoped NoAutomaticHardware{Av1,Hevc,Avc} entry names the encoder, or
     * (HC) this process found the component unusable ([HcComponentHealth]: `no_idr`, `reorder`, unsupported bitstream).
     */
    fun policyExcluded(codec: VideoCodec): Boolean = when (codec.protocolId) {
        StreamProtocol.CODEC_AV1 -> AutomaticEncoderChoice.policyExcluded(codec.encoderName)
        StreamProtocol.CODEC_HEVC -> DeviceFamilyPolicy.componentEffects<NoAutomaticHardwareHevc>(codec.encoderName).isNotEmpty() ||
            HcComponentHealth.isIneligible(codec.encoderName)
        StreamProtocol.CODEC_H264 -> DeviceFamilyPolicy.componentEffects<NoAutomaticHardwareAvc>(codec.encoderName).isNotEmpty() ||
            HcComponentHealth.isIneligible(codec.encoderName)
        else -> false
    }

    /** One `BENCHMARK encoder_choice` line (DEBUG). Legacy: exactly the two-codec line. */
    fun logLine(
        role: String,
        choice: CodecChoice,
        switches: CodecChoiceSwitches,
        peerDecodeMask: Int?,
    ): String {
        val sent = choice.sent ?: choice.requested
        val base = "BENCHMARK encoder_choice: role=$role | encoder=${sent.encoderName} | " +
            "codec=${VideoCodecMap.label(sent.protocolId)} | " +
            "hardware=${sent.hardwareAccelerated} | prefer_hw_av1=${switches.preferHwAv1} | " +
            "peer_decode_mask=${peerDecodeMask ?: "-"} | reason=${choice.reason}" +
            // Design VX: only when libvpx is in play, so every other line keeps its exact format.
            if (switches.preferLibvpx || sent.backend == VideoEncoderBackend.NATIVE_LIBVPX) " | prefer_libvpx=${switches.preferLibvpx}" else ""
        if (!switches.anyNew) return base
        return base + " | hevc=${switches.hevc} | h264=${switches.h264} | prefer_hevc=${switches.preferHevc} | " +
            "prefer_hw_avc=${switches.preferHwAvc} | requested=${choice.requested.encoderName} | " +
            "list=${choice.ordered.joinToString(",") { it.encoderName }.ifEmpty { "none" }}" +
            (sent.envelope?.let { " | fps_points=${it.label()}" } ?: "") +
            " | power=${switches.power.label} | codec_power_aware=${switches.powerAware}" +
            // test_sw_shortfall_avc: only while the switch is on, so every other line keeps its exact format.
            (if (switches.swShortfall) " | sw_shortfall=true | slow=${switches.slowSoftware.sorted().joinToString(",").ifEmpty { "none" }}" else "") +
            (if (choice.notes.isEmpty()) "" else " | notes=${choice.notes.joinToString(",")}")
    }
}
