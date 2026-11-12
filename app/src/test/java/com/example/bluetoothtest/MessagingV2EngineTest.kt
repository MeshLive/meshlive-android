package com.example.bluetoothtest

import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Messaging v2 end to end over real sessions: text, inline and segmented files, receipts, quotas and hostile content. */
class MessagingV2EngineTest {
    /** Safety net: a test that hangs fails after this instead of holding the whole run (a legitimate run on a loaded host takes seconds to a minute or two). */
    @get:org.junit.Rule
    val perTestTimeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(600)

    /** A hung exchange fails the test instead of the build. */
    @get:org.junit.Rule
    val timeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(120)

    private val rig = SfRig()
    private val random = SecureRandom()

    @After
    fun cleanUp() = rig.cleanUp()

    private fun pair(
        aLimits: MessagingV2Limits = MessagingV2Limits(),
        bLimits: MessagingV2Limits = MessagingV2Limits(),
        bFree: () -> Long = { Long.MAX_VALUE },
    ): Pair<SfRig.Phone, SfRig.Phone> {
        val a = rig.Phone(v2Limits = aLimits)
        val b = rig.Phone(v2Limits = bLimits, freeSpace = bFree)
        rig.contacts(a, b)
        return a to b
    }

    private fun send(from: SfRig.Phone, to: SfRig.Phone, bytes: ByteArray, name: String = "doc.bin", mime: String = "application/octet-stream") =
        from.engine.sendFile(to.nodeId, { ByteArrayInputStream(bytes) }, bytes.size.toLong(), Presentation.FILE, name, mime)

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun incoming(phone: SfRig.Phone, from: SfRig.Phone) =
        phone.engine.threads.forPeer(from.nodeId.toString()).filter { it.direction == MessageDirection.IN }

    // ---- crafted content (a hostile or buggy sender) ----------------------------------------------------------------

    private fun newId() = ByteArray(16).also(random::nextBytes)

    private fun craft(
        sender: SfRig.Phone,
        recipient: SfRig.Phone,
        message: MessageV2,
        cls: Int = 0,
        ttl: Int = 72,
        nowMs: Long = rig.clock(),
    ): StoredBundle {
        val contact = requireNotNull(sender.node.contacts.get(recipient.nodeId))
        val sealed = StoreForwardSeal.sealV3(
            message.encode(), cls, sender.node.mailbox, sender.nodeId, contact.card.mailboxKey, recipient.nodeId,
            sender.node.pairSecret(contact), nowMs, ttl, random,
        )
        return sender.node.store.putOwn(sealed, recipient.nodeId, sender.node.limits)
    }

    private fun textFor(
        sender: SfRig.Phone,
        recipient: SfRig.Phone,
        text: String = "crafted",
        messageId: ByteArray = newId(),
        conversation: ByteArray = ConversationIds.of(sender.nodeId, recipient.nodeId),
        createdMs: Long = rig.clock(),
        flags: Int = 0,
        kind: V2Kind = V2Kind.TEXT,
        body: ByteArray = text.toByteArray(),
    ) = MessageV2(kind, flags, createdMs, messageId, conversation, null, 0, body)

    /** Seals a file the way the engine does, with hooks to make it hostile. Returns the manifest bundle and the file ID. */
    private fun craftFile(
        sender: SfRig.Phone,
        recipient: SfRig.Phone,
        data: ByteArray,
        declaredSha: ByteArray = sha(data),
        tamper: (Int, SegmentBody) -> SegmentBody = { _, body -> body },
        fileId: ByteArray = newId(),
        listSegments: Boolean = true,
    ): Pair<StoredBundle, ByteArray> {
        val now = rig.clock()
        val key = ByteArray(32).also(random::nextBytes)
        val contact = requireNotNull(sender.node.contacts.get(recipient.nodeId))
        val tag = StoreForwardTags.tag(sender.node.pairSecret(contact), StoreForwardFormat.dayOfHour(StoreForwardFormat.hourOf(now)))
        val count = SegmentMath.count(data.size.toLong())
        val refs = List(count) { index ->
            val length = SegmentMath.dataLength(index, data.size.toLong())
            val body = tamper(index, SegmentBody(fileId, index, data.copyOfRange(index * MessagingV2Format.SEGMENT_DATA_BYTES, index * MessagingV2Format.SEGMENT_DATA_BYTES + length)))
            val message = MessageV2(V2Kind.SEGMENT, 0, now, newId(), ByteArray(16), null, 0, body.encode())
            val segKey = SegmentMath.segmentKey(key, fileId, index)
            val sealed = StoreForwardSeal.sealBulk(message.encode(), StoreForwardFormat.paddedLength(message.encode().size).let {
                if (body.data.size == MessagingV2Format.SEGMENT_DATA_BYTES) message.encode().size + 4 else it
            }, segKey, tag, now, 336)
            if (listSegments) sender.node.store.putOwn(sealed, recipient.nodeId, sender.node.limits)
            SegmentRef(sealed.header.bundleId, sealed.header.chunkCount)
        }
        val manifest = AttachmentManifest(fileId, Presentation.FILE, data.size.toLong(), declaredSha, key, "crafted.bin", "application/octet-stream", 0, 0, 0, ByteArray(0), null, refs)
        val message = MessageV2(V2Kind.ATTACHMENT, 0, now, newId(), ConversationIds.of(sender.nodeId, recipient.nodeId), null, 0, manifest.encode())
        return craft(sender, recipient, message, 0, 336, now) to fileId
    }

    // ---- text and inline files -------------------------------------------------------------------------------------

    @Test
    fun textTravelsAsAVersion3BundleAndIsDeliveredWithoutAReceiptBundle() {
        val (alice, bob) = pair()
        val sent = alice.engine.sendText(bob.nodeId, "meet at the north gate")
        assertEquals(3, alice.node.store.get(sent.bundleIds.single())!!.header.version)
        val logs = ArrayList<String>()
        val result = rig.sync(alice, bob, logs = logs)
        assertEquals(StoreForwardSyncOutcome.COMPLETE, result.initiator.outcome)
        assertEquals(StoreForwardSyncOutcome.COMPLETE, result.responder.outcome)
        assertTrue(logs.any { it.contains("proto=2") })
        val received = result.responder.v2Messages.single()
        assertEquals("meet at the north gate", received.text)
        assertEquals(MessageDirection.IN, received.direction)
        assertEquals(alice.nodeId.toString(), received.peerNode)
        // The direct link reports delivery (DELIVERED_LOCAL): no receipt bundle is created for it.
        assertTrue(alice.engine.threads.get(sent.id)!!.delivered)
        assertEquals(BundleState.HANDED, alice.node.store.get(sent.bundleIds.single())!!.state)
        assertEquals(0, bob.node.store.all().count { it.role == BundleRole.OWN })
        assertEquals(0, bob.engine.receiptOutbox.size())
        // The v1 archive is untouched: v2 messages live in their own store.
        assertEquals(0, bob.node.archive.count())
        // Nothing is offered twice.
        val again = rig.sync(alice, bob)
        assertEquals(0, again.responder.v2Messages.size)
        assertEquals(1, incoming(bob, alice).size)
    }

    @Test
    fun conversationsRunBothWaysInOneExchangeAndOrderByTime() {
        val (alice, bob) = pair()
        alice.engine.sendText(bob.nodeId, "one")
        rig.clockMs.addAndGet(1_000)
        bob.engine.sendText(alice.nodeId, "reply")
        rig.clockMs.addAndGet(1_000)
        alice.engine.sendText(bob.nodeId, "two")
        val logs = ArrayList<String>()
        val result = rig.sync(alice, bob, logs = logs)
        // The outcomes first, with both sessions' lines: a count alone does not say how an exchange ended.
        val sessions = synchronized(logs) { logs.filter { "store_forward session" in it || "deadline" in it } }
        assertEquals("initiator: $sessions", StoreForwardSyncOutcome.COMPLETE, result.initiator.outcome)
        assertEquals("responder: $sessions", StoreForwardSyncOutcome.COMPLETE, result.responder.outcome)
        assertEquals("responder: $sessions", 2, result.responder.v2Messages.size)
        assertEquals("initiator: $sessions", 1, result.initiator.v2Messages.size)
        assertEquals(listOf("one", "reply", "two"), alice.engine.threads.forPeer(bob.nodeId.toString()).map { it.text })
        assertEquals(listOf("one", "reply", "two"), bob.engine.threads.forPeer(alice.nodeId.toString()).map { it.text })
    }

    @Test
    fun anInlineFileArrivesWithItsMessageAndLivesOnlyInTheVault() {
        val (alice, bob) = pair()
        val bytes = rig.bytes(300_000, 11)
        send(alice, bob, bytes, "report.pdf", "application/pdf")
        val result = rig.sync(alice, bob)
        val message = result.responder.v2Messages.single()
        val info = message.attachment!!
        assertEquals(FileState.STORED, info.state)
        assertEquals("report.pdf", info.name)
        assertEquals("application/pdf", info.mime)
        assertEquals(300_000L, info.totalSize)
        assertArrayEquals(bytes, bob.engine.vault.read(info.vaultId!!, 0, 300_000))
        // No transfer, no key, nothing staged: an inline file is one bundle.
        assertEquals(0, bob.engine.transfers.all().size)
        assertEquals(0, bob.engine.keys.count())
        assertEquals(0, bob.node.store.all().count { it.role == BundleRole.INBOX })
        // The sealed archive and the thread record hold no file bytes: scan everything for a slice of the file.
        val needle = bytes.copyOfRange(1000, 1032)
        bob.dir.walkTopDown().filter { it.isFile }.forEach { file ->
            assertFalse(file.path, indexOf(file.readBytes(), needle) >= 0)
        }
        // The sender keeps its own copy in its vault.
        val own = alice.engine.threads.all().single().attachment!!
        assertArrayEquals(bytes, alice.engine.vault.read(own.vaultId!!, 0, 300_000))
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    @Test
    fun theSmallestClassGoesFirstWithinAnExchange() {
        val (alice, bob) = pair()
        alice.engine.sendFile(bob.nodeId, { ByteArrayInputStream(rig.bytes(900_000, 3)) }, 900_000, Presentation.VIDEO, "clip.mp4", "video/mp4")
        alice.engine.sendText(bob.nodeId, "short text")
        alice.engine.sendFile(bob.nodeId, { ByteArrayInputStream(rig.bytes(100_000, 4)) }, 100_000, Presentation.VOICE, "note.m4a", "audio/mp4")
        val result = rig.sync(alice, bob)
        val order = result.responder.v2Messages.map { it.attachment?.name ?: it.text }
        assertEquals(listOf("short text", "note.m4a", "clip.mp4"), order)
    }

    // ---- segmented files and the receiver pull ---------------------------------------------------------------------

    @Test
    fun aSegmentedFileIsOnlyPulledAfterTheReceiverApproves() {
        val (alice, bob) = pair(bLimits = MessagingV2Limits(autoDownloadMaxBytes = 0))
        val bytes = rig.bytes(2_500_000, 21)
        val sent = send(alice, bob, bytes, "archive.zip", "application/zip")
        // The manifest is small and arrives first; the segments are never listed in a SUMMARY.
        val first = rig.sync(alice, bob)
        val card = first.responder.v2Messages.single()
        assertEquals(FileState.AWAITING_APPROVAL, card.attachment!!.state)
        assertEquals(3, card.attachment!!.segmentCount)
        assertEquals(0, bob.node.store.all().count { it.header.isBulk })
        val second = rig.sync(alice, bob)
        assertEquals("nothing is pulled without approval", 0, second.responder.chunksReceived)
        assertEquals(0, second.initiator.chunksSent)
        // Approve: the vault file is allocated and the segments are pulled at the next exchange.
        bob.engine.approveDownload(card.id)
        assertEquals(FileState.DOWNLOADING, bob.engine.threads.get(card.id)!!.attachment!!.state)
        val third = rig.sync(alice, bob)
        assertEquals(StoreForwardSyncOutcome.COMPLETE, third.initiator.outcome)
        val done = bob.engine.threads.get(card.id)!!.attachment!!
        assertEquals(FileState.COMPLETE, done.state)
        assertArrayEquals(bytes, bob.engine.vault.read(done.vaultId!!, 0, bytes.size))
        // The receiver's file key is erased as soon as the verified file is complete.
        assertEquals(0, bob.engine.keys.count())
        assertEquals(TransferState.DONE, bob.engine.transfers.all().single().state)
        assertEquals(0, bob.node.store.all().count { it.header.isBulk })
        // "Downloaded" travels back as receipt status 3 on the next exchange; the sender then drops its sealed segments and key.
        assertTrue(alice.node.store.all().count { it.header.isBulk } == 3)
        assertEquals(1, alice.engine.keys.count())
        rig.sync(bob, alice)
        val ownMessage = alice.engine.threads.get(sent.id)!!
        assertTrue(ownMessage.downloaded)
        assertEquals(0, alice.node.store.all().count { it.header.isBulk })
        assertEquals(0, alice.engine.keys.count())
    }

    @Test
    fun smallSegmentedFilesDownloadAutomaticallyUpToTheChosenLimit() {
        val (alice, bob) = pair()
        val bytes = rig.bytes(1_500_000, 22)
        send(alice, bob, bytes)
        val result = rig.sync(alice, bob)
        assertEquals(StoreForwardSyncOutcome.COMPLETE, result.initiator.outcome)
        val info = incoming(bob, alice).single().attachment!!
        assertEquals("the default 8 MB limit pulled it within the same exchange", FileState.COMPLETE, info.state)
        assertArrayEquals(bytes, bob.engine.vault.read(info.vaultId!!, 0, bytes.size))
        // A call or stream running blocks the automatic pull.
        val (carol, dave) = pair()
        dave.engine.autoDownloadAllowed = { false }
        send(carol, dave, bytes)
        rig.sync(carol, dave)
        assertEquals(FileState.AWAITING_APPROVAL, incoming(dave, carol).single().attachment!!.state)
    }

    /** Waits for an event without a time assumption (a slow machine only waits longer); fails after a generous bound. */
    private fun spinUntil(condition: () -> Boolean) {
        val end = System.nanoTime() + 120_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < end) { "the awaited event never happened" }
            Thread.sleep(1)
        }
    }

    @Test
    fun cancelStopsTheSenderAtAChunkBoundaryAndAResumeAsksForOnlyWhatIsMissing() {
        val (alice, bob) = pair(bLimits = MessagingV2Limits(autoDownloadMaxBytes = 0))
        val bytes = rig.bytes(2_300_000, 23)
        send(alice, bob, bytes)
        val card = rig.sync(alice, bob).responder.v2Messages.single()
        bob.engine.approveDownload(card.id)
        var cancelledAt = -1
        // No wall-clock timing: the sender's writer is held after chunk 40 until the receiver stored all 40 (so nothing is in
        // flight) and, after the CANCEL, until the sender's reader has acted on it. The CANCEL grace window is frozen so a slow
        // machine can never let a late chunk count as a duplicate.
        val first = rig.open(alice, bob, onAChunk = { session, sent, _ ->
            if (sent == 40) {
                cancelledAt = sent
                spinUntil { bob.node.store.all().filter { it.header.isBulk }.sumOf { it.presentCount } >= 40 }
                bob.engine.cancelDownload(card.id)
                // Two segments are asked for at a time, so two CANCELs reach the sender.
                spinUntil { session.cancelsServed >= 2 }
            }
        }, monotonicMs = { 0L }).join()
        assertEquals(40, cancelledAt)
        assertEquals(0, first.responder.poisonedChunks)
        assertEquals(0, first.responder.duplicateChunks)
        assertEquals("the sender stopped at the chunk boundary of the CANCEL", 40, first.initiator.chunksSent)
        assertEquals(FileState.AWAITING_APPROVAL, bob.engine.threads.get(card.id)!!.attachment!!.state)
        val held = bob.node.store.all().filter { it.header.isBulk }.sumOf { it.presentCount }
        assertEquals(40, held)
        // Approve again in the same session setting: progress is kept, so only the missing chunks come.
        bob.engine.approveDownload(card.id)
        val second = rig.sync(alice, bob)
        assertEquals(StoreForwardSyncOutcome.COMPLETE, second.initiator.outcome)
        val totalChunks = alice.node.store.all().filter { it.header.isBulk }.sumOf { it.chunkCount } +
            0 // segments of the sender's own copy
        assertTrue(second.initiator.chunksSent <= totalChunks - held + 1)
        val info = bob.engine.threads.get(card.id)!!.attachment!!
        assertEquals(FileState.COMPLETE, info.state)
        assertArrayEquals(bytes, bob.engine.vault.read(info.vaultId!!, 0, bytes.size))
    }

    @Test
    fun aDroppedLinkResumesAtTheNextMissingChunkAcrossARestart() {
        val (alice, bob) = pair()
        val bytes = rig.bytes(2_100_000, 24)
        send(alice, bob, bytes)
        val clock = rig.slowClock()
        val first = rig.sync(alice, bob, onAChunk = { _, sent, close -> if (sent == 150) close() }, monotonicMs = clock)
        assertTrue(first.responder.outcome != StoreForwardSyncOutcome.COMPLETE)
        val partial = bob.node.store.all().filter { it.header.isBulk }
        assertTrue(partial.sumOf { it.presentCount } > 0)
        assertEquals(TransferState.ACTIVE, bob.engine.transfers.all().single().state)
        // The recipient's process dies and restarts: the vault file, the transfer record, the key and the bitmaps survive.
        val bobAgain = bob.reopen()
        assertEquals(1, bobAgain.engine.keys.count())
        val log = mutableListOf<String>()
        val second = rig.sync(alice, bobAgain, logs = log, monotonicMs = clock)
        assertEquals(log.filter { "deadline" in it }.joinToString(System.lineSeparator()), StoreForwardSyncOutcome.COMPLETE, second.initiator.outcome)
        val info = incoming(bobAgain, alice).single().attachment!!
        assertEquals(FileState.COMPLETE, info.state)
        assertArrayEquals(bytes, bobAgain.engine.vault.read(info.vaultId!!, 0, bytes.size))
        assertEquals(0, bobAgain.engine.keys.count())
    }

    @Test
    fun withdrawingASentFileLeavesTheReceiverWithNoLongerAvailable() {
        val (alice, bob) = pair(bLimits = MessagingV2Limits(autoDownloadMaxBytes = 0))
        val sent = send(alice, bob, rig.bytes(1_400_000, 25))
        val card = rig.sync(alice, bob).responder.v2Messages.single()
        alice.engine.deleteMessage(sent.id)
        assertEquals(0, alice.node.store.all().count { it.header.isBulk })
        assertEquals(0, alice.engine.keys.count())
        bob.engine.approveDownload(card.id)
        rig.sync(alice, bob)
        val info = bob.engine.threads.get(card.id)!!.attachment!!
        assertEquals(FileState.UNAVAILABLE, info.state)
        assertNull(info.vaultId)
        assertEquals(0, bob.engine.keys.count())
        assertEquals(0, bob.engine.vault.ids().size)
    }

    // ---- hostile content ---------------------------------------------------------------------------------------------

    @Test
    fun aManifestNamingSegmentsThatDoNotExistIsHarmlessAndEndsUnavailable() {
        val (alice, bob) = pair(bLimits = MessagingV2Limits(autoDownloadMaxBytes = 0))
        craftFile(alice, bob, rig.bytes(1_300_000, 26), listSegments = false)
        val card = rig.sync(alice, bob).responder.v2Messages.single()
        bob.engine.approveDownload(card.id)
        val result = rig.sync(alice, bob)
        assertEquals(StoreForwardSyncOutcome.COMPLETE, result.initiator.outcome)
        assertEquals(FileState.UNAVAILABLE, bob.engine.threads.get(card.id)!!.attachment!!.state)
        assertEquals(0, bob.engine.keys.count())
    }

    @Test
    fun aWrongWholeFileHashDeletesTheFileAndCountsAViolation() {
        val (alice, bob) = pair()
        val bytes = rig.bytes(1_300_000, 27)
        craftFile(alice, bob, bytes, declaredSha = ByteArray(32) { 9 })
        rig.sync(alice, bob)
        val info = incoming(bob, alice).single().attachment!!
        assertEquals(FileState.DAMAGED, info.state)
        assertNull(info.vaultId)
        assertEquals(0, bob.engine.vault.ids().size)
        assertEquals(0, bob.engine.keys.count())
        assertEquals(TransferState.DAMAGED, bob.engine.transfers.all().single().state)
        assertEquals(1, bob.node.ledger.today("violations.${alice.nodeId}").bundlesIn)
    }

    @Test
    fun aSegmentThatNamesAnotherPositionOrFileFailsTheDownload() {
        val (alice, bob) = pair()
        craftFile(alice, bob, rig.bytes(1_300_000, 28), tamper = { index, body -> if (index == 1) SegmentBody(body.fileId, 0, body.data) else body })
        rig.sync(alice, bob)
        assertEquals(FileState.DAMAGED, incoming(bob, alice).single().attachment!!.state)
        val (carol, dave) = pair()
        craftFile(carol, dave, rig.bytes(1_300_000, 29), tamper = { index, body -> if (index == 0) SegmentBody(ByteArray(16), 0, body.data) else body })
        rig.sync(carol, dave)
        assertEquals(FileState.DAMAGED, incoming(dave, carol).single().attachment!!.state)
        val (erin, frank) = pair()
        craftFile(erin, frank, rig.bytes(1_300_000, 30), tamper = { index, body -> if (index == 1) SegmentBody(body.fileId, 1, body.data.copyOf(body.data.size - 1)) else body })
        rig.sync(erin, frank)
        assertEquals(FileState.DAMAGED, incoming(frank, erin).single().attachment!!.state)
    }

    @Test
    fun aSegmentIsNeverAcceptedForAFileThatWasNotApprovedOrFromAnotherPhone() {
        val (alice, bob) = pair(bLimits = MessagingV2Limits(autoDownloadMaxBytes = 0))
        craftFile(alice, bob, rig.bytes(1_300_000, 31))
        val card = rig.sync(alice, bob).responder.v2Messages.single()
        val segmentIds = bob.engine.transfers.all().single().segmentIds
        // Not approved yet: a class 4 SUMMARY-less WANT is not even made, and a forged HEADER is refused.
        assertNull(bob.engine.expectedSegment(segmentIds[0], alice.nodeId))
        bob.engine.approveDownload(card.id)
        assertNotNull(bob.engine.expectedSegment(segmentIds[0], alice.nodeId))
        val carol = rig.Phone()
        assertNull("only the manifest's sender may supply its segments", bob.engine.expectedSegment(segmentIds[0], carol.nodeId))
    }

    @Test
    fun textWithAnotherContactsConversationIdIsRefusedAndNothingIsFiled() {
        val (alice, bob) = pair()
        val carol = rig.Phone()
        craft(alice, bob, textFor(alice, bob, conversation = ConversationIds.of(carol.nodeId, bob.nodeId)))
        val result = rig.sync(alice, bob)
        assertEquals(0, result.responder.v2Messages.size)
        assertTrue(result.responder.completed.single() is StoreForwardCompletion.Rejected)
        assertEquals(0, bob.engine.threads.count())
        // The refusal is told to the sender (REFUSE invalid) and counted; the link stays up.
        assertEquals(StoreForwardSyncOutcome.COMPLETE, result.responder.outcome)
        assertEquals(1, result.responder.refusals[StoreForwardRefusal.INVALID])
    }

    @Test
    fun aMessageTimeOutsideTheBundlesCreatedHourIsRefused() {
        val (alice, bob) = pair()
        craft(alice, bob, textFor(alice, bob, createdMs = rig.clock() - 3 * 3_600_000L))
        craft(alice, bob, textFor(alice, bob, createdMs = rig.clock() + 5 * 3_600_000L))
        val result = rig.sync(alice, bob)
        assertEquals(0, result.responder.v2Messages.size)
        assertEquals(2, result.responder.completed.count { it is StoreForwardCompletion.Rejected })
    }

    @Test
    fun reservedFlagBitsUnknownKindsAndBadBodiesAreRefusedWithoutAHalfMessage() {
        val (alice, bob) = pair()
        val good = textFor(alice, bob).encode()
        // A reserved flag bit set by a newer sender.
        val flagged = StoreForwardSeal.sealV3(
            good.copyOf().also { it[5] = 2 }, 0, alice.node.mailbox, alice.nodeId,
            alice.node.contacts.get(bob.nodeId)!!.card.mailboxKey, bob.nodeId, alice.node.pairSecret(alice.node.contacts.get(bob.nodeId)!!),
            rig.clock(), 72, random,
        )
        alice.node.store.putOwn(flagged, bob.nodeId, alice.node.limits)
        // A legacy kind (photo = 3) and an unknown kind.
        listOf(3, 99).forEach { kind ->
            val sealed = StoreForwardSeal.sealV3(
                good.copyOf().also { it[4] = kind.toByte() }, 0, alice.node.mailbox, alice.nodeId,
                alice.node.contacts.get(bob.nodeId)!!.card.mailboxKey, bob.nodeId, alice.node.pairSecret(alice.node.contacts.get(bob.nodeId)!!),
                rig.clock(), 72, random,
            )
            alice.node.store.putOwn(sealed, bob.nodeId, alice.node.limits)
        }
        // Text above 8000 bytes, an empty text, and prekeys (phase 2) are refused too.
        craft(alice, bob, textFor(alice, bob, body = ByteArray(8001) { 'a'.code.toByte() }))
        craft(alice, bob, textFor(alice, bob, body = ByteArray(0)))
        craft(alice, bob, textFor(alice, bob, kind = V2Kind.PREKEYS, body = ByteArray(10)))
        val result = rig.sync(alice, bob)
        assertEquals(StoreForwardSyncOutcome.COMPLETE, result.responder.outcome)
        assertEquals(0, result.responder.v2Messages.size)
        assertEquals(6, result.responder.completed.count { it is StoreForwardCompletion.Rejected })
        assertEquals(0, bob.engine.threads.count())
        assertEquals(0, bob.node.store.all().count { it.role == BundleRole.INBOX })
        // The next exchange does not resurrect them (tombstoned) and does not fail.
        val again = rig.sync(alice, bob)
        assertEquals(0, again.responder.completed.size)
    }

    @Test
    fun aReSealedCopyOfAnOpenedMessageIsDroppedByItsMessageId() {
        val (alice, bob) = pair()
        val id = newId()
        craft(alice, bob, textFor(alice, bob, "first", messageId = id))
        rig.sync(alice, bob)
        assertEquals(1, bob.engine.threads.count())
        // A different bundle (new ephemeral key, new bundle ID) carrying the same logical message.
        craft(alice, bob, textFor(alice, bob, "replacement", messageId = id))
        val result = rig.sync(alice, bob)
        assertEquals(listOf(StoreForwardCompletion.V2Duplicate), result.responder.completed)
        assertEquals(1, bob.engine.threads.count())
        assertEquals("first", bob.engine.threads.all().single().text)
        // The sender still learns it was delivered, so it stops offering.
        assertEquals(0, alice.node.offers(bob.nodeId, false, true).size - alice.node.offers(bob.nodeId, false, true).count { it.state != BundleState.QUEUED })
    }

    @Test
    fun aReplyToAMessageOfAnotherThreadIsNotHonoured() {
        val (alice, bob) = pair()
        val carol = rig.Phone()
        rig.contacts(alice, carol)
        val inCarolThread = alice.engine.sendText(carol.nodeId, "private to carol")
        val message = MessageV2(
            V2Kind.TEXT, 0, rig.clock(), newId(), ConversationIds.of(alice.nodeId, bob.nodeId),
            hexToBytes(inCarolThread.messageIdHex), 0, "re: ?".toByteArray(),
        )
        craft(alice, bob, message)
        val result = rig.sync(alice, bob)
        assertNull(result.responder.v2Messages.single().replyToHex)
    }

    // ---- quotas and physical accounting ----------------------------------------------------------------------------

    @Test
    fun aContactCannotFillMoreThanItsShareOfTheVault() {
        val limits = MessagingV2Limits(vaultCapBytes = 1_000_000, perContactVaultFraction = 0.25)
        val (alice, bob) = pair(bLimits = limits)
        send(alice, bob, rig.bytes(400_000, 41), "too-big.bin")
        send(alice, bob, rig.bytes(100_000, 42), "fits.bin")
        val result = rig.sync(alice, bob)
        assertEquals(listOf("fits.bin"), result.responder.v2Messages.map { it.attachment!!.name })
        assertTrue(result.responder.refusals.containsKey(StoreForwardRefusal.QUOTA))
        // The refused message stays queued at the sender: nothing is lost, nothing was stored at the receiver.
        assertEquals(1, alice.node.store.all().count { it.role == BundleRole.OWN && it.state == BundleState.QUEUED })
        assertTrue(bob.engine.vault.usedBytes() < 250_000)
    }

    @Test
    fun theFreeSpaceFloorAndReservationsStopNewTransfers() {
        var free = 200_000_000L + 100_000L
        val (alice, bob) = pair(bFree = { free })
        send(alice, bob, rig.bytes(200_000, 43))
        val refused = rig.sync(alice, bob)
        assertEquals(0, refused.responder.v2Messages.size)
        assertTrue(refused.responder.refusals.containsKey(StoreForwardRefusal.QUOTA))
        free = 200_000_000L + 50_000_000L
        val accepted = rig.sync(alice, bob)
        assertEquals(1, accepted.responder.v2Messages.size)
        // Reservations are released when the exchange ends.
        assertEquals(0, bob.engine.reservationCount())
    }

    @Test
    fun reservationsSumAcrossTransfersInFlightAndCapTheBundlesInFlight() {
        val (alice, bob) = pair(bLimits = MessagingV2Limits(maxInFlightBundles = 3))
        repeat(5) { alice.engine.sendText(bob.nodeId, "message $it") }
        var peak = 0
        val result = rig.sync(alice, bob, onAChunk = { _, _, _ -> peak = maxOf(peak, bob.engine.reservationCount()) })
        // All five arrive in one exchange, but never more than three are requested at once.
        assertEquals(5, result.responder.v2Messages.size)
        assertTrue("peak in flight $peak", peak in 1..3)
        assertEquals(0, bob.engine.reservationCount())
    }

    @Test
    fun theOwnQueueIsBoundedPerContactAndByWaitingMessages() {
        val (alice, bob) = pair(aLimits = MessagingV2Limits(waitingPerContact = 3, ownQueueBytesPerContact = 1_500_000))
        repeat(3) { alice.engine.sendText(bob.nodeId, "m$it") }
        assertThrows(MessagingV2QuotaException::class.java) { alice.engine.sendText(bob.nodeId, "one too many") }
        val (carol, dave) = pair(aLimits = MessagingV2Limits(ownQueueBytesPerContact = 1_500_000))
        send(carol, dave, rig.bytes(900_000, 44))
        assertThrows(MessagingV2QuotaException::class.java) { send(carol, dave, rig.bytes(900_000, 45)) }
        // A failed send leaves no half-written pieces behind.
        assertEquals(1, carol.engine.threads.count())
        assertEquals(1, carol.node.store.all().count { it.role == BundleRole.OWN })
        assertEquals(1, carol.engine.vault.ids().size)
    }

    @Test
    fun aSegmentedSendThatFailsPartwayCleansUpEverything() {
        val (alice, bob) = pair(aLimits = MessagingV2Limits(ownQueueBytesTotal = 1_500_000))
        assertThrows(MessagingV2QuotaException::class.java) { send(alice, bob, rig.bytes(2_500_000, 46)) }
        assertEquals(0, alice.node.store.all().size)
        assertEquals(0, alice.engine.keys.count())
        assertEquals(0, alice.engine.vault.ids().size)
        assertEquals(0, alice.engine.transfers.all().size)
        assertThrows(IllegalArgumentException::class.java) {
            alice.engine.sendFile(bob.nodeId, { ByteArrayInputStream(ByteArray(10)) }, 100)
        }
        assertEquals(0, alice.engine.keys.count())
    }

    @Test
    fun globalIngressCapsHoldAgainstIdentityChurnAndRollOverEachDay() {
        val limits = MessagingV2Limits(nonContactDailyBytes = 1_000_000, nonContactHourlyBytes = 400_000, contactsDailyBytes = 5_000_000)
        val file = File.createTempFile("ingress", ".props").also { it.delete(); it.deleteOnExit() }
        val ingress = GlobalIngress(file, rig.clock, AesGcmAtRestCipher { javax.crypto.spec.SecretKeySpec(ByteArray(32), "AES") }, limits)
        assertEquals(400_000, ingress.remaining(false))
        assertEquals(5_000_000, ingress.remaining(true))
        // 100 rotating link identities each use a little of the same global pool.
        repeat(100) { ingress.add(false, 4_000) }
        assertEquals(0, ingress.remaining(false))
        assertEquals("contacts have their own pool", 5_000_000, ingress.remaining(true))
        rig.clockMs.addAndGet(3_600_000)
        assertEquals(400_000, ingress.remaining(false).coerceAtMost(400_000))
        rig.clockMs.addAndGet(24 * 3_600_000L)
        assertEquals(400_000, ingress.remaining(false))
        ingress.add(true, 2_000_000)
        assertEquals(3_000_000, ingress.remaining(true))
        // Persisted across a restart.
        val reopened = GlobalIngress(file, rig.clock, AesGcmAtRestCipher { javax.crypto.spec.SecretKeySpec(ByteArray(32), "AES") }, limits)
        assertEquals(3_000_000, reopened.remaining(true))
    }

    @Test
    fun theMetadataCapsRefuseNewBundlesWhenTablesAreFull() {
        val (alice, bob) = pair(bLimits = MessagingV2Limits(maxArchiveRows = 2, maxSeenEntries = 100))
        repeat(4) { alice.engine.sendText(bob.nodeId, "m$it") }
        val result = rig.sync(alice, bob)
        assertEquals(2, result.responder.v2Messages.size)
        assertEquals(2, bob.engine.threads.count())
        assertTrue(result.responder.refusals.containsKey(StoreForwardRefusal.QUOTA))
        val (carol, dave) = pair(bLimits = MessagingV2Limits(maxSeenEntries = 2))
        repeat(4) { carol.engine.sendText(dave.nodeId, "m$it") }
        assertEquals(2, rig.sync(carol, dave).responder.v2Messages.size)
        assertEquals(2, dave.engine.seenIds.size())
    }

    // ---- receipts -------------------------------------------------------------------------------------------------------

    @Test
    fun deliveredDownloadedAndReadAreIndependentFlagsSetAtMostOnce() {
        val (alice, bob) = pair()
        val sent = alice.engine.sendText(bob.nodeId, "flags")
        val bundle = sent.bundleIds.single()
        val engine = alice.engine
        // Read arrives before delivered: it is not suppressed, and neither implies the other.
        assertTrue(engine.applyReceipt(bob.nodeId, ReceiptStatus.READ, bundle))
        assertFalse(engine.applyReceipt(bob.nodeId, ReceiptStatus.READ, bundle))
        var message = engine.threads.get(sent.id)!!
        assertTrue(message.read)
        assertFalse(message.delivered)
        assertTrue(engine.applyReceipt(bob.nodeId, ReceiptStatus.DELIVERED, bundle))
        assertFalse("deduplicated by (bundle, status)", engine.applyReceipt(bob.nodeId, ReceiptStatus.DELIVERED, bundle))
        assertTrue(engine.applyReceipt(bob.nodeId, ReceiptStatus.DOWNLOADED, bundle))
        message = engine.threads.get(sent.id)!!
        assertTrue(message.delivered && message.downloaded && message.read)
        // Key-gone is phase 2 and changes nothing here.
        assertFalse(engine.applyReceipt(bob.nodeId, ReceiptStatus.KEY_GONE, bundle))
    }

    @Test
    fun aReceiptIsValidOnlyFromTheContactTheMessageWasAddressedTo() {
        val (alice, bob) = pair()
        val carol = rig.Phone()
        rig.contacts(alice, carol)
        val sent = alice.engine.sendText(bob.nodeId, "to bob")
        assertFalse(alice.engine.applyReceipt(carol.nodeId, ReceiptStatus.DELIVERED, sent.bundleIds.single()))
        assertFalse("unknown bundle", alice.engine.applyReceipt(bob.nodeId, ReceiptStatus.DELIVERED, ByteArray(32).sfHex()))
        assertFalse(alice.engine.threads.get(sent.id)!!.delivered)
    }

    @Test
    fun aLateReceiptStillSetsItsFlagAfterTheMessageWasMarkedNotSent() {
        val (alice, bob) = pair()
        val sent = alice.engine.sendText(bob.nodeId, "late", ttlHours = 2)
        rig.clockMs.addAndGet(5 * 3_600_000L)
        alice.node.sweep()
        assertTrue(alice.engine.threads.get(sent.id)!!.notSent)
        assertTrue(alice.engine.applyReceipt(bob.nodeId, ReceiptStatus.DELIVERED, sent.bundleIds.single()))
        val message = alice.engine.threads.get(sent.id)!!
        assertTrue(message.delivered)
        assertTrue("the highest applicable state wins; the not-sent mark stays visible to the UI", message.notSent)
    }

    @Test
    fun readReceiptsNeedBothPhonesToTurnThemOnAndBatchUpToSixtyFour() {
        val (alice, bob) = pair()
        alice.engine.readReceipts = true
        bob.engine.readReceipts = true
        repeat(70) { alice.engine.sendText(bob.nodeId, "m$it") }
        // 70 texts exceed the 100-waiting cap? No: the cap is 100. All are queued; the receiver's tables hold them.
        rig.sync(alice, bob)
        val received = incoming(bob, alice)
        assertEquals(70, received.size)
        assertTrue(received.all { it.flags and MessagingV2Format.WANT_READ_RECEIPT != 0 })
        received.forEach { bob.engine.markRead(it.id) }
        assertEquals(70, bob.engine.receiptOutbox.size())
        val flushed = bob.engine.flushReceipts(alice.nodeId, force = true)
        assertEquals("64 + 6", 2, flushed.size)
        assertEquals(0, bob.engine.receiptOutbox.size())
        rig.sync(bob, alice)
        assertEquals(70, alice.engine.threads.all().count { it.read })
        // Receipts are not archived and are never answered by another receipt.
        assertEquals(0, bob.engine.threads.all().count { it.kind == V2Kind.RECEIPT })
        assertEquals(0, alice.engine.receiptOutbox.size())
    }

    @Test
    fun noReadReceiptWhenEitherSwitchIsOff() {
        val (alice, bob) = pair()
        alice.engine.readReceipts = true
        bob.engine.readReceipts = false
        alice.engine.sendText(bob.nodeId, "a")
        rig.sync(alice, bob)
        incoming(bob, alice).forEach { bob.engine.markRead(it.id) }
        assertEquals(0, bob.engine.receiptOutbox.size())
        // The sender's switch off: the message never asks.
        val (carol, dave) = pair()
        dave.engine.readReceipts = true
        carol.engine.sendText(dave.nodeId, "b")
        rig.sync(carol, dave)
        incoming(dave, carol).forEach { dave.engine.markRead(it.id) }
        assertEquals(0, dave.engine.receiptOutbox.size())
    }

    @Test
    fun receiptsHaveTheirOwnAllowanceAndDoNotEatTheMessageAllowance() {
        val limits = MessagingV2Limits(contactDailyMessages = 3, contactDailyReceipts = 50)
        val (alice, bob) = pair(aLimits = limits, bLimits = limits)
        repeat(3) { alice.engine.sendText(bob.nodeId, "m$it") }
        rig.sync(alice, bob)
        val key = NeighborLedger.senderKey(alice.nodeId.toString())
        assertEquals(3, bob.node.ledger.today(key).bundlesIn)
        // Bob answers with receipts: each one is refunded from alice's message count and counted as a receipt.
        bob.engine.readReceipts = true
        incoming(bob, alice).forEach { bob.engine.receiptOutbox.queue(alice.nodeId.toString(), ReceiptStatus.READ, it.bundleIds.single(), rig.clock()) }
        bob.engine.flushReceipts(alice.nodeId, force = true)
        rig.sync(bob, alice)
        assertEquals(0, alice.node.ledger.today(NeighborLedger.senderKey(bob.nodeId.toString())).bundlesIn)
        assertEquals(1, alice.node.ledger.today("receipts.${bob.nodeId}").bundlesIn)
    }

    // ---- retention, timers, housekeeping ------------------------------------------------------------------------------

    @Test
    fun disappearingTimersStartAtFirstDeliveryOnTheSenderAndAtReadOnTheReceiver() {
        val (alice, bob) = pair()
        val sent = alice.engine.sendText(bob.nodeId, "vanishing", disappearSeconds = 7_200)
        assertEquals(0, sent.disappearAtMs)
        rig.sync(alice, bob)
        val own = alice.engine.threads.get(sent.id)!!
        assertEquals(rig.clock() + 7_200_000, own.disappearAtMs)
        val inbound = incoming(bob, alice).single()
        assertEquals(0, inbound.disappearAtMs)
        bob.engine.markRead(inbound.id)
        assertEquals(rig.clock() + 7_200_000, bob.engine.threads.get(inbound.id)!!.disappearAtMs)
        rig.clockMs.addAndGet(7_300_000)
        alice.node.sweep()
        bob.node.sweep()
        assertEquals(0, alice.engine.threads.count())
        assertEquals(0, bob.engine.threads.count())
    }

    @Test
    fun aTimerBelowTheFloorIsRaisedAndMarkedAndKeepCancelsIt() {
        val (alice, bob) = pair()
        alice.engine.sendText(bob.nodeId, "one second", disappearSeconds = 1)
        rig.sync(alice, bob)
        val inbound = incoming(bob, alice).single()
        assertEquals(3_600, inbound.disappearSeconds)
        assertTrue(inbound.timerRaised)
        bob.engine.markRead(inbound.id)
        assertTrue(bob.engine.threads.get(inbound.id)!!.disappearAtMs > 0)
        bob.engine.keep(inbound.id)
        assertEquals(0, bob.engine.threads.get(inbound.id)!!.disappearAtMs)
    }

    @Test
    fun messagesStayUntilDeletedEvenAfterTheBundleLifetimeEnds() {
        val (alice, bob) = pair()
        alice.engine.sendText(bob.nodeId, "keep me")
        rig.sync(alice, bob)
        rig.clockMs.addAndGet(30L * 24 * 3_600_000)
        bob.node.sweep()
        alice.node.sweep()
        assertEquals("retention is separate from delivery lifetime", 1, bob.engine.threads.count())
        assertEquals(1, alice.engine.threads.count())
    }

    @Test
    fun anOutgoingPreparationInterruptedByACrashIsCleanedUpAtTheNextSweep() {
        val (alice, bob) = pair()
        val transfer = TransferRecord(
            "deadbeef", MessageDirection.OUT, bob.nodeId.toString(), "", "00".repeat(16), "", 2_000_000, ByteArray(32),
            mutableListOf("", ""), mutableListOf(0, 0), 999_999, TransferState.ACTIVE, java.util.BitSet(), alice.engine.vault.create("out:x", 2_000_000),
        )
        alice.engine.keys.put("deadbeef", ByteArray(32))
        alice.engine.transfers.save(transfer)
        alice.node.sweep()
        assertEquals(0, alice.engine.transfers.all().size)
        assertEquals(0, alice.engine.keys.count())
        assertEquals(0, alice.engine.vault.ids().size)
    }

    @Test
    fun orphanVaultFilesAreCollectedAfterAnHourAndExpiredTransfersEraseTheirKey() {
        val (alice, bob) = pair(bLimits = MessagingV2Limits(autoDownloadMaxBytes = 0))
        val orphan = bob.engine.vault.create("in:x", 1000)
        send(alice, bob, rig.bytes(1_300_000, 51))
        rig.sync(alice, bob)
        assertEquals(1, bob.engine.keys.count())
        bob.node.sweep()
        assertTrue("a fresh orphan is kept for now", bob.engine.vault.ids().contains(orphan))
        File(bob.dir, "v2/vault/$orphan").setLastModified(rig.clock() - 2 * 3_600_000L)
        rig.clockMs.addAndGet(15L * 24 * 3_600_000)
        bob.node.sweep()
        assertFalse(bob.engine.vault.ids().contains(orphan))
        assertEquals("an expired transfer erases its file key", 0, bob.engine.keys.count())
        assertEquals(FileState.UNAVAILABLE, incoming(bob, alice).single().attachment!!.state)
    }

    @Test
    fun finishedTransferRecordsAreForgottenOnceTheirBundlesCouldNoLongerBeOffered() {
        val (alice, bob) = pair()
        send(alice, bob, rig.bytes(1_300_000, 54))
        rig.sync(alice, bob)
        assertEquals(TransferState.DONE, bob.engine.transfers.all().single().state)
        bob.node.sweep()
        assertEquals("kept while the bundle could still be offered", 1, bob.engine.transfers.all().size)
        rig.clockMs.addAndGet(15L * 24 * 3_600_000)
        bob.node.sweep()
        assertEquals(0, bob.engine.transfers.all().size)
        assertEquals(FileState.COMPLETE, incoming(bob, alice).single().attachment!!.state)
    }

    @Test
    fun deletingAReceivedFileRemovesTheVaultFileTheKeyAndTheStagedSegments() {
        val (alice, bob) = pair(bLimits = MessagingV2Limits(autoDownloadMaxBytes = 0))
        send(alice, bob, rig.bytes(1_300_000, 52))
        val card = rig.sync(alice, bob).responder.v2Messages.single()
        bob.engine.approveDownload(card.id)
        rig.sync(alice, bob, onAChunk = { _, sent, close -> if (sent == 30) close() })
        assertTrue(bob.node.store.all().any { it.header.isBulk })
        bob.engine.deleteMessage(card.id)
        assertEquals(0, bob.engine.threads.count())
        assertEquals(0, bob.engine.keys.count())
        assertEquals(0, bob.engine.vault.ids().size)
        assertEquals(0, bob.engine.transfers.all().size)
        assertEquals(0, bob.node.store.all().count { it.header.isBulk })
    }

    @Test
    fun aSegmentIndexIsSealedOnceAndSealedBytesAreNeverRewritten() {
        val (alice, bob) = pair()
        val record = TransferRecord(
            "t1", MessageDirection.OUT, bob.nodeId.toString(), "", "00".repeat(16), "", 2_000_000, ByteArray(32),
            mutableListOf("", ""), mutableListOf(0, 0), 99, TransferState.ACTIVE, java.util.BitSet(), null,
        )
        val key = ByteArray(32) { 3 }
        val fileId = ByteArray(16) { 4 }
        val contact = alice.node.contacts.get(bob.nodeId)!!
        val tag = StoreForwardTags.tag(alice.node.pairSecret(contact), StoreForwardFormat.dayOfHour(StoreForwardFormat.hourOf(rig.clock())))
        val data = rig.bytes(MessagingV2Format.SEGMENT_DATA_BYTES, 8)
        val sealed = alice.engine.sealSegment(record, key, fileId, 0, data, tag, rig.clock(), 336)
        assertEquals(128, sealed.chunks.size)
        // The same index under the same file key would reuse every chunk nonce: refused, not sealed again.
        assertThrows(IllegalStateException::class.java) { alice.engine.sealSegment(record, key, fileId, 0, data, tag, rig.clock(), 336) }
        // The next index has its own derived key.
        alice.engine.sealSegment(record, key, fileId, 1, rig.bytes(500, 2), tag, rig.clock(), 336)
        // A stored chunk is written once: a repeat is a duplicate, a different payload is poison, and the bytes stay put.
        val big = alice.engine.sendFile(bob.nodeId, { ByteArrayInputStream(rig.bytes(300_000, 5)) }, 300_000)
        val own = alice.node.store.get(big.bundleIds.single())!!
        val store = bob.node.store
        store.beginReceive(own.header, BundleRole.INBOX, alice.nodeId.toString())
        val original = alice.node.store.readChunk(own.idHex, 0)!!
        assertEquals(ChunkWriteResult.ACCEPTED, store.writeChunk(own.idHex, 0, original))
        assertEquals(ChunkWriteResult.DUPLICATE, store.writeChunk(own.idHex, 0, original))
        val tampered = original.copyOf().also { it[10] = (it[10].toInt() xor 1).toByte() }
        assertEquals(ChunkWriteResult.POISONED, store.writeChunk(own.idHex, 0, tampered))
        assertArrayEquals(original, store.readChunk(own.idHex, 0))
        // Re-offering a queued bundle sends the very same sealed bytes every time.
        assertArrayEquals(original, alice.node.store.readChunk(own.idHex, 0))
    }

    @Test
    fun theDebugControlsSendApproveReadAndReportMatchingChecksumsWithoutAScreen() {
        val (alice, bob) = pair(bLimits = MessagingV2Limits(autoDownloadMaxBytes = 0))
        val sent = MessagingV2Debug.send(alice.engine, bob.nodeId, 1_500_000, 1, seed = 5)
        MessagingV2Debug.send(alice.engine, bob.nodeId, 500, 2)
        assertEquals(1, sent.size)
        assertNotNull(sent.single().attachment)
        rig.sync(alice, bob)
        assertEquals(1, MessagingV2Debug.approveAll(bob.engine))
        rig.sync(alice, bob)
        assertEquals(3, MessagingV2Debug.readAll(bob.engine))
        val aliceLines = MessagingV2Debug.statusLines(alice.node)
        val bobLines = MessagingV2Debug.statusLines(bob.node)
        fun field(line: String, key: String) = Regex("$key=(\\S+)").find(line)!!.groupValues[1]
        val outFile = aliceLines.first { it.contains("message dir=out") && it.contains("kind=file") }
        val inFile = bobLines.first { it.contains("message dir=in") && it.contains("kind=file") }
        assertEquals(field(outFile, "sha256"), field(inFile, "actual"))
        assertEquals("complete", field(inFile, "file"))
        assertTrue(bobLines.first().contains("threads=3") && bobLines.first().contains("complete=1"))
        assertTrue(aliceLines.first().contains("outDelivered=3"))
        assertEquals("BENCHMARK message_v2 status enabled=false", MessagingV2Debug.statusLines(rig.Phone(v2 = false).node).single())
        // The generated file is deterministic for a seed and streams without holding it in memory.
        val a = MessagingV2Debug.PatternStream(10_000, 3).readBytes()
        assertArrayEquals(a, MessagingV2Debug.PatternStream(10_000, 3).readBytes())
        assertEquals(10_000, a.size)
    }

    @Test
    fun everyV2FileLivesUnderTheStoreRootSoTheEmergencyWipeReachesIt() {
        val (alice, bob) = pair()
        send(alice, bob, rig.bytes(100_000, 53))
        alice.engine.sendText(bob.nodeId, "t")
        rig.sync(alice, bob)
        val root = bob.dir.canonicalFile
        val files = root.walkTopDown().filter { it.isFile }.toList()
        assertTrue(files.any { it.path.contains("v2") && it.path.contains("vault") })
        assertTrue(files.any { it.path.contains("threads") })
        files.forEach { assertTrue(it.canonicalPath.startsWith(root.path)) }
        val outsideRoot = File(System.getProperty("java.io.tmpdir")).listFiles { f -> f.name.contains("vault-test") && f.absolutePath.startsWith(root.path) }
        assertTrue(outsideRoot.isNullOrEmpty())
    }
}
