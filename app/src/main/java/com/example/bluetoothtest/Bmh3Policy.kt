package com.example.bluetoothtest

/**
 * Experimental BMH3 identity-hiding handshake: the switch, the negotiation rules, the pin memory, the signing budget
 * and the option builders the call endpoint uses (PROTOCOL "Experimental BMH3 identity-hiding link handshake").
 * Pure Kotlin, so every rule is JVM-tested. With the switch off nothing here runs on a call path and every byte on
 * air and on the link is unchanged.
 */
object Bmh3Feature {
    /** DEBUG benchmark launch extra of the master switch (Settings > Features "Hidden identities"). */
    const val EXTRA = "test_bmh3"
    const val PREF = "experimental_bmh3"

    /** DEBUG extras: an initiator never speaks BMH2 / a responder reveals itself only to a holder of its lease key. */
    const val EXTRA_ONLY = "test_bmh3_only"
    const val EXTRA_CONTACTS_ONLY = "test_bmh3_contacts_only"

    /** DEBUG extra: 64 hex digits, a fixed lease key shared by the test phones (stands in for real leases in T14). */
    const val EXTRA_TEST_LEASE = "test_bmh3_test_lease"

    /** The effective master switch (benchmark launch: only [EXTRA]; any other launch: the user's choice or the default). */
    val enabled: Boolean get() = FeatureCatalog.isEnabled(ConnectivityFeatures.bmh3.id)

    @Volatile var only: Boolean = false
        private set
    @Volatile var contactsOnly: Boolean = false
        private set
    @Volatile var testLeaseKey: ByteArray? = null
        private set

    /** Called at launch; the sub-options exist only in a DEBUG benchmark launch (release builds pass false/null). */
    fun configureDebug(only: Boolean, contactsOnly: Boolean, testLeaseHex: String?) {
        this.only = only
        this.contactsOnly = contactsOnly
        this.testLeaseKey = testLeaseHex?.let { hex ->
            if (hex.length == 64 && hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                ByteArray(32) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            } else {
                null
            }
        }
    }
}

/**
 * The announcement hint (`capabilitySummary` bit 5) of a **version 2** call offer. A version 5 (rotating-ID) offer
 * carries none: a phone with BMH3 on always speaks BMH3 to it ([Bmh3Choice]), so the bit would only add a stable
 * quasi-identifier to the privacy-mode advertisement.
 */
object Bmh3Hint {
    const val SUMMARY_BIT = 1 shl 5

    /** The shape that may carry the hint: a private-call offer with the fixed prefix. Stream offers never do. */
    fun mayCarry(offer: DiscoveryAnnouncement): Boolean = offer.isPrivateCallOffer() && !offer.rotating

    /** True only for an offer of the carrying shape whose summary has the bit; elsewhere the bit means nothing. */
    fun hasHint(offer: DiscoveryAnnouncement): Boolean = mayCarry(offer) && offer.capabilitySummary and SUMMARY_BIT != 0

    /**
     * The offer a callee advertises: [base] with the hint when the feature is on, the offer is a version 2 call offer
     * at route cost 0 and not a relay-helper offer; otherwise [base] itself (the same instance), so a switch-off
     * offer is byte-identical by construction. A DEBUG call relay mirrors its child's summary one hop further.
     */
    fun offerFor(base: DiscoveryAnnouncement, enabled: Boolean): DiscoveryAnnouncement {
        if (!enabled || !mayCarry(base) || base.routeCost != 0) return base
        if (base.flags and DiscoveryAnnouncement.FLAG_RELAY_CAPABLE != 0) return base
        return base.copy(capabilitySummary = base.capabilitySummary or SUMMARY_BIT)
    }
}

/** Which handshake an initiator speaks, decided once before the first byte and never revisited (PROTOCOL "Negotiation"). */
object Bmh3Choice {
    enum class Reason { OFF, ROTATING, ONLY, HINT, PIN, NONE }

    class Decision(val bmh3: Boolean, val reason: Reason) {
        val version: Int get() = if (bmh3) 3 else 2
    }

    /**
     * [offer] is the call offer the user selected (null for a peer with no offer). With the switch on: a version 5
     * offer is always BMH3 (a stripped hint cannot downgrade it, and a BMH2 hello to a fake or replayed rotating
     * advert would hand the caller's identity over); a version 2 offer is BMH3 with the hint, a pinned prefix or
     * [only], otherwise BMH2 exactly as today.
     */
    fun decide(enabled: Boolean, only: Boolean, offer: DiscoveryAnnouncement?, pinned: Boolean): Decision = when {
        !enabled -> Decision(false, Reason.OFF)
        offer != null && offer.rotating -> Decision(true, Reason.ROTATING)
        only -> Decision(true, Reason.ONLY)
        offer != null && Bmh3Hint.hasHint(offer) -> Decision(true, Reason.HINT)
        pinned -> Decision(true, Reason.PIN)
        else -> Decision(false, Reason.NONE)
    }
}

/**
 * Node prefixes (6 bytes) that completed a BMH3 handshake with this phone, newest last, at most [capacity], each
 * valid for [ttlMs] after its last success. A later version 2 offer with a pinned prefix is contacted with BMH3 even
 * when its hint was stripped; the expiry bounds how long a peer that turned the feature off stays unreachable.
 * The storage is a sealed file in no-backup storage ([SealedTextFile]), which the emergency wipe deletes with the rest of
 * the app data. Only version 2 offers outside pseudonym mode are pinned ([Bmh3AttemptPlan.pinAllowed]).
 */
class Bmh3Pins(
    private val storage: Storage,
    private val capacity: Int = 256,
    private val ttlMs: Long = TTL_MS,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    interface Storage {
        /** The stored text or null; throws when it cannot be read now (then nothing is loaded and nothing written). */
        fun read(): String?
        fun write(value: String)
    }

    /** Insertion order is recency order (a re-pin moves the entry to the end). */
    private val entries = LinkedHashMap<String, Long>()
    private var loaded = false

    /**
     * False while the storage cannot be read now (security review 2026-10-27, item 6): the memory stays unloaded, nothing
     * is written over the stored pins, and the next access retries.
     */
    @Synchronized
    private fun load(): Boolean {
        if (loaded) return true
        val text = try {
            storage.read()
        } catch (error: Exception) {
            return false
        }
        loaded = true
        text?.lineSequence()?.forEach { line ->
            val parts = line.split(' ')
            val at = parts.getOrNull(1)?.toLongOrNull()
            if (parts.size == 2 && parts[0].length == HEX_CHARS && parts[0].all(::isHex) && at != null) entries[parts[0]] = at
        }
        expire()
        return true
    }

    private fun expire() {
        val now = clockMs()
        val before = entries.size
        entries.entries.removeAll { now - it.value > ttlMs || it.value > now + CLOCK_SLACK_MS }
        while (entries.size > capacity) entries.remove(entries.keys.first())
        if (entries.size != before) save()
    }

    private fun save() = storage.write(entries.entries.joinToString("\n") { "${it.key} ${it.value}" })

    enum class PinState { PINNED, UNPINNED, UNKNOWN }

    /**
     * Review 2026-10-27 (finding 8): a pin store that cannot be read now is UNKNOWN, never "not pinned". The caller
     * then treats the prefix as pinned (BMH3 for that attempt), so a stripped hint plus a passing Keystore failure cannot
     * make this phone send its identity in a first BMH2 hello.
     */
    @Synchronized
    fun pinState(prefix: ByteArray): PinState {
        if (!load()) return PinState.UNKNOWN
        expire()
        return if (hex(prefix) in entries) PinState.PINNED else PinState.UNPINNED
    }

    /** Loads (and so migrates an earlier build's plain file); true when loaded. */
    @Synchronized
    fun warmUp(): Boolean = load()

    @Synchronized
    fun isPinned(prefix: ByteArray): Boolean {
        if (!load()) return false
        expire()
        return hex(prefix) in entries
    }

    @Synchronized
    fun pin(prefix: ByteArray) {
        if (!load()) return
        val key = hex(prefix)
        entries.remove(key)
        entries[key] = clockMs()
        expire()
        save()
    }

    @Synchronized
    fun clear() {
        loaded = true
        entries.clear()
        save()
    }

    private fun hex(prefix: ByteArray): String {
        require(prefix.size >= DiscoveryAnnouncement.NODE_PREFIX_BYTES)
        return prefix.copyOf(DiscoveryAnnouncement.NODE_PREFIX_BYTES).joinToString("") { "%02x".format(it) }
    }

    private fun isHex(c: Char): Boolean = c in '0'..'9' || c in 'a'..'f'

    companion object {
        /** The plaintext file of builds before 2026-10-27: migrated once into [SEALED_FILE_NAME] and deleted. */
        const val FILE_NAME = "hidden-identity-pins.txt"
        const val SEALED_FILE_NAME = "hidden-identity-pins.bin"
        const val TTL_MS = 30L * 24 * 3_600_000L
        private const val CLOCK_SLACK_MS = 24L * 3_600_000L
        private const val HEX_CHARS = DiscoveryAnnouncement.NODE_PREFIX_BYTES * 2
    }
}

/**
 * How much unrecognised work a stranger-facing R_FIRST responder does: a token bucket for the one expensive step, the
 * Keystore signature of msg2 (PROTOCOL "Responder cost guards"). [capacity] signatures in a burst, one more every
 * [refillMs]. An exhausted bucket makes the next caller wait for its token, at most [maxWaitMs]; beyond that the
 * connection closes silently. A lease holder (tag matched) never spends a token. Unlike the escalating RV8 tarpit
 * this delays nobody while the rate is ordinary, and it does not punish contacts who cannot yet prove they are one.
 * Review 2026-10-27 (finding 13): the clock is the monotonic elapsed time ([MonotonicClock]); with the wall clock a time
 * change (a user, a network time update, a test harness) could refill the bucket at once or stall it for hours.
 */
class Bmh3SigningBudget(
    private val capacity: Int = 8,
    private val refillMs: Long = 4_000L,
    private val maxWaitMs: Long = 4_000L,
    private val clockMs: () -> Long = MonotonicClock::elapsedMs,
    private val sleep: (Long) -> Unit = Thread::sleep,
) {
    private var tokens = capacity.toDouble()
    private var lastMs = clockMs()

    /** Takes a token, sleeping if it is not there yet; throws [Bmh3Failure] when the wait would pass [maxWaitMs]. */
    fun spend() {
        val wait = reserve()
        if (wait > 0) sleep(wait)
    }

    @Synchronized
    private fun reserve(): Long {
        val now = clockMs()
        tokens = minOf(capacity.toDouble(), tokens + (now - lastMs).coerceAtLeast(0L).toDouble() / refillMs)
        lastMs = now
        if (tokens >= 1.0) {
            tokens -= 1.0
            return 0L
        }
        val wait = ((1.0 - tokens) * refillMs).toLong() + 1L
        if (wait > maxWaitMs) throw Bmh3Failure("budget")
        tokens -= 1.0
        return wait
    }
}

/**
 * Milliseconds of `SystemClock.elapsedRealtime()` (monotonic, counts deep sleep, unaffected by wall-clock changes). A JVM
 * test has no Android runtime, so there the same monotonic source is `System.nanoTime()`.
 */
internal object MonotonicClock {
    private val source: () -> Long = try {
        android.os.SystemClock.elapsedRealtime()
        ({ android.os.SystemClock.elapsedRealtime() })
    } catch (_: Throwable) {
        ({ System.nanoTime() / 1_000_000L })
    }

    fun elapsedMs(): Long = source()
}

/**
 * Where the contact tags come from. Phase 1 has none in a release build (every responder is Everyone mode, every
 * initiator sends a random tag). The DEBUG fixed test lease stands in for real presence leases in phone test T14;
 * wiring the real lease keys (`PresenceLeases`, the recognition hit's epoch and contact) is Phase 2.
 */
internal object Bmh3ContactTags {
    /** The binding of a selected advert (PROTOCOL "Contact tag"): the RDI for version 5, the prefix and wall epoch for 2. */
    fun bindingFor(selected: DiscoveryAnnouncement, wallEpoch: Long): ByteArray = Bmh3Crypto.advertBinding(
        selected.nodeIdPrefix,
        RotatingDiscoveryIds.ROLE_CALL_OFFER,
        if (selected.rotating) null else wallEpoch,
    )

    /**
     * The tag an initiator sends toward [selected]; null (a random tag) without a lease for it. The DEBUG fixed test
     * lease wins when it is set; otherwise [source] supplies the lease of the contact the advert was recognised as,
     * and only where the answerer is the advert's owner (not at the link layer toward a relay, route cost above 0).
     */
    fun initiatorTag(
        selected: DiscoveryAnnouncement?,
        wallEpoch: Long,
        source: Bmh3LeaseSource? = null,
        toAdvertOwner: Boolean = true,
    ): Bmh3ContactTag? {
        selected ?: return null
        // A relay hop never carries a tag, not even the DEBUG test lease: a tag makes the initiator present its real key
        // (rule P), which must never go toward a relay (review CP-1 item 4).
        if (!toAdvertOwner) return null
        Bmh3Feature.testLeaseKey?.let { return Bmh3ContactTag(Bmh3Crypto.contactTagKey(it), bindingFor(selected, wallEpoch)) }
        if (source == null || !selected.rotating) return null
        val grant = source.leaseFor(selected) ?: return null
        return Bmh3ContactTag(Bmh3Crypto.contactTagKey(grant.leaseKey), bindingFor(selected, wallEpoch), expectedPeer = grant.contact)
    }

    /**
     * The checks a Contacts-only responder runs on msg1: each of its lease keys against each advert it has on air or
     * just left ([rotatingEntries] for version 5, the fixed [fixedPrefix] at wall epochs -1..+1 otherwise).
     */
    fun responderChecks(
        rotatingEntries: List<OpeningEntry>,
        fixedPrefix: ByteArray,
        wallEpoch: Long,
        source: Bmh3LeaseSource? = null,
    ): List<Bmh3ContactTag> {
        val lease = Bmh3Feature.testLeaseKey ?: return realChecks(rotatingEntries, source)
        val key = Bmh3Crypto.contactTagKey(lease)
        val bindings = rotatingEntries.map { Bmh3Crypto.advertBinding(it.rdi, RotatingDiscoveryIds.ROLE_CALL_OFFER, null) } +
            (-1L..1L).map { Bmh3Crypto.advertBinding(fixedPrefix.copyOf(6), RotatingDiscoveryIds.ROLE_CALL_OFFER, wallEpoch + it) }
        return bindings.map { Bmh3ContactTag(key, it) }
    }

    /** One check per call-offer entry on air: this phone's own day key for the entry's day against the entry's RDI. */
    private fun realChecks(entries: List<OpeningEntry>, source: Bmh3LeaseSource?): List<Bmh3ContactTag> {
        source ?: return emptyList()
        return entries.mapNotNull { entry ->
            val dayKey = source.ownDayKey(entry) ?: return@mapNotNull null
            val key = Bmh3Crypto.contactTagKey(dayKey)
            dayKey.fill(0)
            Bmh3ContactTag(key, Bmh3Crypto.advertBinding(entry.rdi, RotatingDiscoveryIds.ROLE_CALL_OFFER, null))
        }
    }
}

/** Builders of the option objects for the call endpoint's handshakes. */
internal object Bmh3Policy {
    enum class Layer { LINK, ENDPOINT }

    /** The shared replay cache and signing budget of this process (PROTOCOL "Responder cost guards"). */
    val replayCache = Bmh3ReplayCache()
    val signingBudget = Bmh3SigningBudget()

    /**
     * Review 2026-10-27 (finding 13): the Meet screen's code attempts sign with the real key, so they get their own small
     * bucket ([QrCodeDisplay]). Strangers draining the shared bucket can no longer delay a scan, and a held code (a
     * photographed screen) can no longer drain the bucket every other connection uses. Three per code is the attempt cap.
     */
    val meetBudget = Bmh3SigningBudget(capacity = QrCodeDisplay.MAX_ATTEMPTS, refillMs = 10_000L)

    /** The throwaway keys of this process (PROTOCOL "Pseudonym keys"). */
    val pseudonymKeys = Bmh3PseudonymKeys()

    /** Per-epoch replay cache and signing budget of stranger-facing responders in pseudonym mode (PROTOCOL "Abuse and limits"). */
    val epochGuards = Bmh3EpochGuards()

    /** The `OPENING` extension value for the entries a callee pinned at accept, or null when it has none. */
    fun openingValue(entries: List<OpeningEntry>): ByteArray? {
        if (entries.isEmpty()) return null
        val bdo1 = DiscoveryOpeningCodec.encode(entries)
        return bdo1.copyOfRange(DiscoveryOpeningCodec.MAGIC.size + 1, bdo1.size)
    }

    /**
     * The caller's check before it reveals itself (PROTOCOL checks 2): the peer must open the selected advert.
     * At the link layer it applies only at route cost 0 (above 0 the link peer is a relay); at the end-to-end layer
     * it always applies to the callee. Throws with the message the BMH2 checks use.
     */
    fun checkSelected(selected: DiscoveryAnnouncement, layer: Layer, peer: Bmh3Peer) {
        if (layer == Layer.LINK && selected.routeCost > 0) return
        val result = DiscoverySelectionCheck.check(
            selected,
            RotatingDiscoveryIds.roleFor(selected),
            peer.nodeId.toByteArray(),
            peer.openingEntries(),
        )
        require(result == DiscoverySelectionCheck.Result.PASS) {
            if (layer == Layer.LINK) "Peer identity changed between discovery and connection" else "Call peer identity changed between discovery and connection"
        }
    }

    /**
     * An initiator's options toward [selected]. At the link layer toward a relay (route cost above 0) the phone
     * presents a throwaway key ([throwawayIdentity]); the end-to-end layer always uses the real identity.
     */
    fun initiatorOptions(
        selected: DiscoveryAnnouncement?,
        layer: Layer,
        log: (String) -> Unit,
        wallEpoch: Long = RotatingDiscoveryIds.wallEpoch(System.currentTimeMillis() / 1000L),
        pinResult: ((Bmh3Peer) -> Unit)? = null,
        entropy: Bmh3Entropy = SecureBmh3Entropy,
        throwawayIdentity: () -> SigningIdentity = { KeyPairSigningIdentity(generateEcKeyPair()) },
        leaseSource: Bmh3LeaseSource? = null,
        pseudonyms: Boolean = false,
        pseudonymKeys: Bmh3PseudonymKeys = Bmh3Policy.pseudonymKeys,
        /** The caller's throwaway for this attempt when the link and end-to-end handshakes must show one stranger. */
        strangerIdentity: SigningIdentity? = null,
        /**
         * BVMID2: this connection scans a verification code. The tag is the code's `K_tag` (both layers), the scanner
         * presents its real key, a pseudonym answer is refused and the answerer's fingerprint must equal the code's.
         */
        qrScan: Bvmid2Payload? = null,
        /**
         * Review 2026-10-27 (finding 8): the verified-contact check of a real-key answerer at the LINK layer of a direct
         * connection ([PeerIdentityGate.admit]; throws on `MISMATCH`). It used to run only at the end-to-end layer, after
         * this phone had already presented its real key to the link peer. The end-to-end layer runs it in its own driver.
         */
        admitLinkPeer: (Bmh3Peer) -> Unit = { peer -> PeerIdentityGate.admit(peer.nodeId, peer.spki) },
        /** Amendment CP: the link toward a relay presents a declared throwaway ([relayLinkOptions]). */
        relayEdges: Boolean = false,
        /** Amendment CP: the selected first hop's own offer, checked when the hop answers with a real key. */
        relayHop: DiscoveryAnnouncement? = null,
        /** Experimental contact-helper arm: [relayHop] recognised as exactly one contact; real/real with a tag. */
        contactHelper: DiscoveryAnnouncement? = null,
    ): Bmh3InitiatorOptions {
        val towardRelay = layer == Layer.LINK && selected != null && selected.routeCost > 0
        if (towardRelay && relayEdges && qrScan == null) {
            return relayLinkOptions(relayHop, contactHelper, wallEpoch, leaseSource, entropy, throwawayIdentity, pinResult, log)
        }
        if (qrScan != null) {
            return Bmh3InitiatorOptions(
                contactTag = QrScanPolicy.tag(qrScan),
                checkResponder = { peer ->
                    QrScanPolicy.checkResponder(qrScan, peer)
                    if (selected != null) checkSelected(selected, layer, peer)
                    pinResult?.invoke(peer)
                },
                entropy = entropy,
                log = log,
            )
        }
        return Bmh3InitiatorOptions(
            contactTag = Bmh3ContactTags.initiatorTag(selected, wallEpoch, leaseSource, toAdvertOwner = !towardRelay),
            checkResponder = { peer ->
                // A pseudonymous answerer cannot open the selected ID yet (its salt stays with it until the user accepts);
                // the binding runs on its reveal. This phone presents a pseudonym too then: Bmh3Initiator refuses a real
                // key toward a throwaway, so skipping the binding here can never hand a real identity to an unbound peer.
                // A real-key answerer of a direct link: a verified contact's prefix with another key closes the link here,
                // before this phone's msg3 (its real key, when it holds the contact's lease) leaves.
                if (layer == Layer.LINK && !towardRelay && !peer.pseudonymous) admitLinkPeer(peer)
                if (selected != null && !peer.pseudonymous) checkSelected(selected, layer, peer)
                if (!peer.pseudonymous) pinResult?.invoke(peer)
            },
            entropy = entropy,
            identityOverride = if (towardRelay) throwawayIdentity() else null,
            // The link toward a relay already uses a throwaway; a stranger presentation is for the advert's owner only.
            strangerIdentity = if (pseudonyms && selected != null && !towardRelay) {
                strangerIdentity ?: pseudonymKeys.fresh()
            } else {
                null
            },
            log = log,
        )
    }

    /**
     * Amendment CP, the caller's link toward its first helper. Default: no tag, a throwaway that declares PSEUDONYM, fresh per
     * connection (never the end-to-end attempt pseudonym, so the helper cannot link the two layers), nothing pinned or
     * admitted; a hop that answers with a real key must open [hop], a hop that declared a throwaway is not bound (its only
     * powers are those of any relay). Experimental [contactHelper]: tag that helper's own offer and require its real key
     * and opening; a throwaway answer fails this candidate (no anonymous retry).
     */
    fun relayLinkOptions(
        hop: DiscoveryAnnouncement?,
        contactHelper: DiscoveryAnnouncement?,
        wallEpoch: Long,
        leaseSource: Bmh3LeaseSource?,
        entropy: Bmh3Entropy = SecureBmh3Entropy,
        throwawayIdentity: () -> SigningIdentity = { KeyPairSigningIdentity(generateEcKeyPair()) },
        pinResult: ((Bmh3Peer) -> Unit)? = null,
        log: (String) -> Unit = {},
    ): Bmh3InitiatorOptions {
        if (contactHelper != null) {
            val tag = Bmh3ContactTags.initiatorTag(contactHelper, wallEpoch, leaseSource, toAdvertOwner = true)
            if (tag != null) {
                return Bmh3InitiatorOptions(
                    contactTag = tag,
                    checkResponder = { peer ->
                        if (peer.pseudonymous) throw Bmh3Failure("contact helper answered with a throwaway")
                        checkSelected(contactHelper, Layer.LINK, peer)
                        pinResult?.invoke(peer)
                    },
                    entropy = entropy,
                    log = log,
                )
            }
        }
        return relayHopOptions(hop, entropy, throwawayIdentity, log)
    }

    /**
     * Amendment CP, any initiator of a relay edge without a contact tag (a caller toward its first helper, a helper toward
     * the next helper or the target): a fresh declared throwaway, no tag, no pin, no admission; [hop] (cost 0: the next
     * phone's own offer) is checked only when the answer is a real key.
     */
    fun relayHopOptions(
        hop: DiscoveryAnnouncement?,
        entropy: Bmh3Entropy = SecureBmh3Entropy,
        throwawayIdentity: () -> SigningIdentity = { KeyPairSigningIdentity(generateEcKeyPair()) },
        log: (String) -> Unit = {},
    ): Bmh3InitiatorOptions = Bmh3InitiatorOptions(
        contactTag = null,
        checkResponder = { peer -> if (!peer.pseudonymous && hop != null) checkSelected(hop, Layer.LINK, peer) },
        entropy = entropy,
        strangerIdentity = throwawayIdentity(),
        log = log,
    )

    /**
     * Amendment CP: whether a relay edge toward [hop] speaks BMH3. With pseudonym mode on (or BMH3-only) always: an
     * unauthenticated version 2 advert must never draw a BMH2 hello (review CP-1 item 1); otherwise the direct-call choice.
     */
    fun relayHopUsesBmh3(bmh3Enabled: Boolean, only: Boolean, pseudonymMode: Boolean, hop: DiscoveryAnnouncement, pinned: Boolean): Boolean =
        bmh3Enabled && Bmh3Choice.decide(true, only || pseudonymMode, hop, pinned).bmh3

    /**
     * A call listener's options. [opening] are the role-0 entries pinned when the connection was accepted (version 5
     * only), [fixedPrefix] this phone's own node prefix (the version 2 advert and the DEBUG test lease). A
     * Contacts-only responder (DEBUG) never accepts BMH2; an Everyone-mode one accepts it, because refusing it would
     * protect nobody (the initiator's hello is already sent, and anyone can still connect with BMH3).
     */
    fun responderOptions(
        opening: List<OpeningEntry>,
        fixedPrefix: ByteArray,
        admitInitiator: (Bmh3Peer, Boolean) -> Boolean,
        log: (String) -> Unit,
        wallEpoch: Long = RotatingDiscoveryIds.wallEpoch(System.currentTimeMillis() / 1000L),
        budget: Bmh3SigningBudget = signingBudget,
        entropy: Bmh3Entropy = SecureBmh3Entropy,
        leaseSource: Bmh3LeaseSource? = null,
        pseudonyms: Boolean = false,
        pseudonymKeys: Bmh3PseudonymKeys = Bmh3Policy.pseudonymKeys,
        /** The phone's current discovery epoch; scopes the replay cache and the budget in pseudonym mode. */
        epoch: Long? = null,
        guards: Bmh3EpochGuards = epochGuards,
        /**
         * BVMID2: the displayed verification code and this connection's gate (one per connection, shared by its link and
         * end-to-end handshakes). While a code is on screen its `K_tag` is one more tag check.
         */
        qrDisplay: QrCodeDisplay? = null,
        qrGate: QrConnectionGate? = null,
        /**
         * The phone is call-ready only because the Meet screen is open (security review 2026-10-27, item 10): a BMH2 hello
         * is refused and an untagged msg1 is closed before this phone draws a key or signs anything.
         */
        screenOnlyReachable: Boolean = qrDisplay?.screenOnlyReachable == true,
    ): Bmh3ResponderOptions {
        val contactsOnly = Bmh3Feature.contactsOnly
        // Rule P fails closed (review PR4): in pseudonym mode every untagged connection meets a throwaway, with or
        // without call-offer entries pinned (an advert set that stopped, a Classic listener).
        val stranger = if (pseudonyms) pseudonymKeys.fresh() else null
        val scoped = if (stranger != null && epoch != null) guards.forEpoch(epoch) else null
        val connectionBudget = scoped?.budget ?: budget
        // Tags decide the presentation (rule P) as well as the order, so they are checked in Everyone mode too.
        val qrCheck = if (qrDisplay != null && qrGate != null) qrDisplay.tagCheck(qrGate) else null
        val checkTags = contactsOnly || stranger != null
        return Bmh3ResponderOptions(
            mode = if (contactsOnly) Bmh3ResponderMode.CONTACTS_ONLY else Bmh3ResponderMode.EVERYONE,
            tagChecks = (if (checkTags) Bmh3ContactTags.responderChecks(opening, fixedPrefix, wallEpoch, leaseSource) else emptyList()) +
                listOfNotNull(qrCheck),
            opening = openingValue(opening),
            replayCache = scoped?.replayCache ?: replayCache,
            beforeReply = { matched ->
                if (screenOnlyReachable && qrGate?.linkReserved != true) {
                    log("bmh3: event=qr_screen_only_refused")
                    throw Bmh3Failure("qr screen only")
                }
                if (!matched) connectionBudget.spend()
            },
            admitInitiator = { peer, matched ->
                val qrAttempt = qrGate?.linkReserved == true
                if (qrDisplay?.screenOnlyReachable == true && !qrAttempt) {
                    // This phone is call-ready only because the Meet screen is open: it accepts nothing but the code.
                    log("bmh3: event=qr_screen_only_refused")
                    false
                } else if (!qrAttempt && matched && Bmh3Feature.testLeaseKey == null && leaseSource != null && !leaseSource.deliveredTo(peer.nodeId)) {
                    // A tag only a contact of this phone can compute: anyone else holding it means a lease reached
                    // someone unintended (the DEBUG fixed lease is shared by design and exempt). A code tag is not a lease.
                    log("bmh3: event=lease_misuse")
                    false
                } else {
                    admitInitiator(peer, matched)
                }
            },
            // A BMH2 hello is answered with the real key: refused in pseudonym mode, as in Contacts-only (review PR1).
            acceptBmh2 = !contactsOnly && stranger == null && !screenOnlyReachable,
            entropy = entropy,
            strangerIdentity = stranger,
            log = log,
        )
    }
}

/**
 * One BMH3 decision per outgoing attempt (review 2026-10-27, finding 11 and S1). The link handshake, the end-to-end
 * handshake and a Classic connector's handshake of one attempt used to decide separately, each with its own fresh caller
 * throwaway: a Classic call showed the callee two different strangers (the callee then took the call for a relayed one),
 * and a pin written by the link layer could change the end-to-end layer's choice. The plan fixes the choice
 * ([Bmh3Choice]), the caller's throwaway and whether a success may be pinned once, before the first byte.
 *
 * Pinning (finding 6): a pin exists only to keep speaking BMH3 to a **version 2** advert whose hint was stripped. A
 * rotating (version 5) offer is always BMH3 anyway, and in pseudonym mode every stranger link is a throwaway, so a pin
 * there would only be a list of the contacts this phone called. A success is pinned only for a version 2 offer with
 * pseudonym mode off.
 */
internal class Bmh3AttemptPlan(
    val selected: DiscoveryAnnouncement?,
    val decision: Bmh3Choice.Decision,
    /** Rule P is in force for this attempt: strangers meet [callerAlias]. */
    val pseudonymMode: Boolean,
    /** The caller's one throwaway for every handshake of this attempt (null outside pseudonym mode). */
    val callerAlias: SigningIdentity?,
    private val qrScan: Bvmid2Payload? = null,
    /** Records a pin (the peer's node prefix); null when this attempt must not pin ([pinAllowed]). */
    private val pin: ((ByteArray) -> Unit)? = null,
    private val leaseSource: Bmh3LeaseSource? = null,
    private val log: (String) -> Unit = {},
    /** Amendment CP (LE only): the relayed route this attempt's link opens, kept in the plan so no layer drops it. */
    val relayRoute: RelayRoute = RelayRoute.NONE,
    /**
     * Wi-Fi upgrade: sign the `WIFI` build marker in the LINK block of this attempt, toward a direct callee or a relay edge;
     * never in an END_TO_END handshake and never in a verification scan. [R M-3]
     */
    private val wifiMarker: Boolean = false,
) {
    /**
     * The relayed-route fields of Amendment CP: the link toward the first helper presents a declared fresh throwaway and
     * binds a real-key helper to its own offer ([Bmh3Policy.relayLinkOptions]); [contactHelper] is the experimental
     * contact-helper arm. Only the LINK handshake toward a relay (route cost above 0) uses them.
     */
    class RelayRoute(val relayEdges: Boolean, val relayHop: DiscoveryAnnouncement?, val contactHelper: DiscoveryAnnouncement?) {
        companion object {
            val NONE = RelayRoute(false, null, null)
        }
    }

    /** True when the attempt's handshakes speak BMH3 (null options otherwise: BMH2 exactly as before). */
    val bmh3: Boolean get() = qrScan != null || decision.bmh3

    private val pinResult: ((Bmh3Peer) -> Unit)? = pin?.let { record -> { peer -> runCatching { record(peer.nodeId.toByteArray()) } } }

    /** The options of [layer]'s handshake, or null for BMH2. */
    fun initiator(layer: Bmh3Policy.Layer): Bmh3InitiatorOptions? {
        val options = initiatorWithoutMarker(layer) ?: return null
        val mark = wifiMarker && layer == Bmh3Policy.Layer.LINK && qrScan == null // [R M-3] relay edges sign it too
        return options.withWifiMarker(mark)
    }

    private fun initiatorWithoutMarker(layer: Bmh3Policy.Layer): Bmh3InitiatorOptions? {
        if (qrScan != null) return Bmh3Policy.initiatorOptions(selected, layer, log = log, pinResult = pinResult, qrScan = qrScan)
        if (!decision.bmh3) return null
        return Bmh3Policy.initiatorOptions(
            selected,
            layer,
            log = log,
            pinResult = pinResult,
            leaseSource = leaseSource,
            pseudonyms = pseudonymMode,
            strangerIdentity = callerAlias,
            relayEdges = relayRoute.relayEdges,
            relayHop = relayRoute.relayHop,
            contactHelper = relayRoute.contactHelper,
        )
    }

    companion object {
        fun pinAllowed(selected: DiscoveryAnnouncement?, pseudonymMode: Boolean): Boolean =
            selected != null && !selected.rotating && !pseudonymMode

        /**
         * [enabled]/[only] are the BMH3 switches, [pinned] whether [selected]'s prefix is pinned, [pseudonymMode] whether
         * rule P applies. [newAlias] mints the caller throwaway (only in pseudonym mode).
         */
        fun create(
            enabled: Boolean,
            only: Boolean,
            selected: DiscoveryAnnouncement?,
            pinned: Boolean,
            pseudonymMode: Boolean,
            qrScan: Bvmid2Payload? = null,
            pin: (ByteArray) -> Unit = {},
            leaseSource: Bmh3LeaseSource? = null,
            log: (String) -> Unit = {},
            newAlias: () -> SigningIdentity = Bmh3Policy.pseudonymKeys::fresh,
            relayRoute: RelayRoute = RelayRoute.NONE,
            wifiMarker: Boolean = false,
        ): Bmh3AttemptPlan {
            // In pseudonym mode the caller never speaks BMH2 either: a BMH2 hello hands the real key to whoever answers,
            // before any ring (PROTOCOL "BMH2 is refused"). Cost: phones with BMH3 off cannot be called.
            val decision = if (qrScan != null) Bmh3Choice.Decision(true, Bmh3Choice.Reason.ONLY) else {
                Bmh3Choice.decide(enabled, only || pseudonymMode, selected, pinned)
            }
            if (qrScan == null) log(Bmh3Log.choice(decision.version, decision.reason.name.lowercase()))
            val alias = if (qrScan == null && decision.bmh3 && pseudonymMode) newAlias() else null
            return Bmh3AttemptPlan(
                selected,
                decision,
                pseudonymMode = qrScan == null && pseudonymMode,
                callerAlias = alias,
                qrScan = qrScan,
                pin = pin.takeIf { pinAllowed(selected, pseudonymMode) },
                leaseSource = leaseSource,
                log = log,
                relayRoute = relayRoute,
                wifiMarker = wifiMarker,
            )
        }

        /** No BMH3 at all (the switch is off): every handshake of the attempt is BMH2. */
        val OFF = Bmh3AttemptPlan(null, Bmh3Choice.Decision(false, Bmh3Choice.Reason.OFF), pseudonymMode = false, callerAlias = null)
    }
}
