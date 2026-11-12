package com.example.bluetoothtest

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Android glue of the experimental BMH3 handshake: the pin memory in no-backup storage, the log sink and the option
 * builders the call endpoint and the call relay (relayed calls) use. Everything here runs only while
 * [Bmh3Feature.enabled] (PROTOCOL "Experimental BMH3 identity-hiding link handshake").
 */
internal object Bmh3Android {
    @Volatile private var pins: Bmh3Pins? = null

    /**
     * The pin memory of this process: a file in `noBackupFilesDir`, so it is never part of a cloud backup or a device
     * transfer, and the emergency wipe deletes it with the rest of the app data. Sealed with the verified-contacts key
     * since 2026-10-27 (review finding 6); the plain `hidden-identity-pins.txt` of earlier builds is read once and deleted.
     */
    fun pins(context: Context): Bmh3Pins = pins ?: synchronized(this) {
        pins ?: run {
            val dir = context.applicationContext.noBackupFilesDir
            val sealed = SealedTextFile(
                File(dir, Bmh3Pins.SEALED_FILE_NAME),
                { IdentityVerification.atRestCipher() },
                legacyPlain = File(dir, Bmh3Pins.FILE_NAME),
            )
            Bmh3Pins(
                object : Bmh3Pins.Storage {
                    override fun read(): String? = sealed.read()
                    override fun write(value: String) {
                        runCatching { sealed.write(value) }
                    }
                },
            ).also { pins = it }
        }
    }

    /**
     * Pins are written off the handshake (security review 2026-10-27, item 14): the Keystore seal and the fsync of the pin
     * file used to run inside the initiator's responder check, on the critical path of every BMH3 call setup.
     */
    private val pinWriter = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Bmh3PinWriter").apply { isDaemon = true }
    }

    /**
     * Bumped by the emergency wipe (review 2026-10-27, finding 4): pin work queued before a wipe never touches the
     * storage after it, even when an interrupted wipe is completed at the next start and the process goes on.
     */
    @Volatile private var wipeGeneration = 0

    fun pinLater(context: Context, prefix: ByteArray) {
        val copy = prefix.copyOf()
        val store = pins(context)
        val generation = wipeGeneration
        pinWriter.execute { if (generation == wipeGeneration && !WipeGuard.active) runCatching { store.pin(copy) } }
    }

    /**
     * At every start, whatever the switches (security review 2026-10-27, item 12): the plain `hidden-identity-pins.txt` of
     * an earlier build is moved into the sealed file and deleted, also on a phone that turned BMH3 off since. Called by the
     * main screen after a pending emergency wipe was completed (review finding 4), never before.
     */
    fun migrateLegacyPins(context: Context) {
        val store = pins(context)
        val generation = wipeGeneration
        pinWriter.execute { if (generation == wipeGeneration && !WipeGuard.active) runCatching { store.warmUp() } }
    }

    /** Emergency wipe: forget the loaded pins and fence any pin work queued before it. */
    internal fun clearForWipe() {
        synchronized(this) {
            wipeGeneration++
            pins = null
        }
    }

    /** One log line per event; DEBUG builds tag it for the benchmark tools. No identifiers are ever logged. */
    fun log(line: String) {
        Log.i("BluetoothVideo", (if (BuildConfig.DEBUG) "BENCHMARK " else "") + line)
    }
}

/**
 * What a call endpoint (or the call relay) passes to the handshakes. Null means BMH2 exactly as before, so with the
 * switch off, or toward a version 2 peer with no hint and no pin, nothing changes on the wire.
 */
internal object Bmh3Wiring {
    /**
     * The one BMH3 plan of an outgoing attempt toward [selected] (null when it has no offer): every handshake of the
     * attempt (link, end-to-end, a Classic connector's) takes its options from it ([Bmh3AttemptPlan], review 2026-10-27
     * finding 11 and S1). [pseudonyms] false keeps the real identity (the call relay).
     */
    fun plan(
        context: Context,
        selected: DiscoveryAnnouncement?,
        rotatingIds: Boolean = false,
        pseudonyms: Boolean = true,
        /** BVMID2: scanning a verification code (the scanner presents its real key; PROTOCOL "Scanner"). */
        qrScan: Bvmid2Payload? = null,
        /** Amendment CP (LE only): the attempt's link goes to the first helper of a relayed route ([Bmh3Policy.relayLinkOptions]). */
        relayEdges: Boolean = false,
        relayHop: DiscoveryAnnouncement? = null,
        contactHelper: DiscoveryAnnouncement? = null,
        wifiMarker: Boolean = false,
    ): Bmh3AttemptPlan {
        if (qrScan == null && !Bmh3Feature.enabled) return Bmh3AttemptPlan.OFF
        val pins = Bmh3Android.pins(context)
        val pseudonymMode = pseudonyms && Bmh3PseudonymFeature.active(rotatingIds)
        return Bmh3AttemptPlan.create(
            enabled = true,
            only = Bmh3Feature.only,
            selected = selected,
            // Unknown (the pin store cannot be read now) counts as pinned: BMH3, never a BMH2 hello (review finding 8).
            pinned = selected != null &&
                runCatching { pins.pinState(selected.nodeIdPrefix) != Bmh3Pins.PinState.UNPINNED }.getOrDefault(true),
            pseudonymMode = pseudonymMode,
            qrScan = qrScan,
            pin = { prefix -> Bmh3Android.pinLater(context, prefix) },
            leaseSource = if (rotatingIds) PresenceLeaseSource else null,
            log = Bmh3Android::log,
            relayRoute = Bmh3AttemptPlan.RelayRoute(relayEdges, relayHop, contactHelper),
            wifiMarker = wifiMarker,
        )
    }

    /** One handshake's options from a one-handshake plan (the call relay's hops), or null for BMH2. */
    fun initiator(
        context: Context,
        selected: DiscoveryAnnouncement?,
        layer: Bmh3Policy.Layer,
        rotatingIds: Boolean = false,
        pseudonyms: Boolean = true,
        /** Amendment CP: this is the link toward the first helper of a relayed route ([Bmh3Policy.relayLinkOptions]). */
        relayEdges: Boolean = false,
        relayHop: DiscoveryAnnouncement? = null,
        contactHelper: DiscoveryAnnouncement? = null,
        wifiMarker: Boolean = false,
    ): Bmh3InitiatorOptions? =
        plan(
            context, selected, rotatingIds, pseudonyms, relayEdges = relayEdges, relayHop = relayHop,
            contactHelper = contactHelper, wifiMarker = wifiMarker,
        ).initiator(layer)

    /**
     * Amendment CP: the options factory of a helper's onward relay edge toward [hop] (BMH3, a declared throwaway fresh per
     * connection), or null for BMH2, which is chosen only while pseudonym mode is off and the direct-call choice allows it.
     * Decided once per duty, so a retry never falls back from BMH3 to BMH2.
     */
    fun relayHop(
        context: Context,
        hop: DiscoveryAnnouncement,
        rotatingIds: Boolean,
        wifiMarker: Boolean = false,
    ): (() -> Bmh3InitiatorOptions)? {
        if (!Bmh3Feature.enabled) return null
        val pseudonymMode = Bmh3PseudonymFeature.active(rotatingIds)
        val pinned = runCatching { Bmh3Android.pins(context).pinState(hop.nodeIdPrefix) != Bmh3Pins.PinState.UNPINNED }.getOrDefault(true)
        val bmh3 = Bmh3Policy.relayHopUsesBmh3(true, Bmh3Feature.only, pseudonymMode, hop, pinned)
        Bmh3Android.log("bmh3: event=relay_hop | version=${if (bmh3) 3 else 2} | pseudonymMode=$pseudonymMode")
        if (!bmh3) return null
        return { Bmh3Policy.relayHopOptions(hop, log = Bmh3Android::log).withWifiMarker(wifiMarker) }
    }

    /**
     * A listener's options, or null while the switch is off (BMH2 only, a BMH3 first frame is rejected as before).
     * [pseudonyms] false for the call relay, which stays a real identity in its own right.
     */
    fun responder(
        opening: List<OpeningEntry>,
        fixedPrefix: ByteArray,
        rotatingIds: Boolean = false,
        pseudonyms: Boolean = true,
        qrDisplay: QrCodeDisplay? = null,
        qrGate: QrConnectionGate? = null,
        /** Design OR (DEBUG prototype): the `RELAY_SEEK` bits to sign when the block is built; null signs nothing. */
        relaySeek: (() -> Int?)? = null,
    ): Bmh3ResponderOptions? {
        if (!Bmh3Feature.enabled) return null
        val pseudonymMode = pseudonyms && Bmh3PseudonymFeature.active(rotatingIds)
        return Bmh3Policy.responderOptions(
            opening = opening,
            fixedPrefix = fixedPrefix,
            admitInitiator = { _, _ -> true },
            log = Bmh3Android::log,
            leaseSource = if (rotatingIds) PresenceLeaseSource else null,
            pseudonyms = pseudonymMode,
            epoch = if (pseudonymMode) runCatching { RotatingDiscovery.currentEpoch() }.getOrNull() else null,
            qrDisplay = qrDisplay,
            qrGate = qrGate,
            screenOnlyReachable = QrMeet.display.screenOnlyReachable,
        ).also { options -> relaySeek?.let { options.relaySeek = it } }
    }
}

/**
 * The real contact tags (PROTOCOL "Real lease keys"): the recognition table says which contact an advert is, the
 * presence vault holds that contact's lease for the day and this phone's own day keys.
 */
internal object PresenceLeaseSource : Bmh3LeaseSource {
    override fun leaseFor(selected: DiscoveryAnnouncement): Bmh3LeaseGrant? {
        val vault = RotatingDiscovery.presenceVault() ?: return null
        val hit = RotatingDiscovery.recognize(selected).singleOrNull() ?: return null
        val lease = vault.contact(hit.contact)?.presence?.leasesByDay?.get(PresenceLeases.dayOf(hit.epoch)) ?: return null
        return Bmh3LeaseGrant(hit.contact, lease)
    }

    override fun ownDayKey(entry: OpeningEntry): ByteArray? {
        val vault = RotatingDiscovery.presenceVault() ?: return null
        val current = runCatching { RotatingDiscovery.currentEpoch() }.getOrNull() ?: return null
        return vault.dayKey(entry.epoch, current)
    }

    override fun deliveredTo(peer: NodeId): Boolean {
        val vault = RotatingDiscovery.presenceVault() ?: return false
        val generation = vault.generation()
        return generation > 0 && vault.contact(peer)?.deliveredGeneration == generation
    }
}
