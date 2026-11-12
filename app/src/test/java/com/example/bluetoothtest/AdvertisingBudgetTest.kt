package com.example.bluetoothtest

import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertisingSetCallback
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The learned advertising-set budget (AdvertisingBudget.kt, device-family backlog A-02). */
class AdvertisingBudgetTest {
    private val tooMany = AdvertisingSetCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS
    private var now = 1_000L
    private val lines = mutableListOf<String>()
    private var main = false

    private fun budget() = AdvertisingBudget(
        clock = { now },
        sleeper = { ms -> now += ms },
        onMainThread = { main },
        log = { lines += it },
    )

    @After
    fun restoreSwitches() {
        AdvertisingSwitches.reset()
    }

    @Test
    fun aRefusalWhileOwningOneSetMeansABudgetOfOneAndTheOptionalSetsStayOff() {
        val budget = budget()
        val stable = Any()
        budget.acquire(stable, AdvertisingSetRole.STABLE)
        val verdict = budget.noteRefused(AdvertisingSetRole.TITLE, tooMany, ownedBefore = 1, sdk = 33)
        assertTrue(verdict.tooMany)
        assertEquals(1, budget.ceiling())
        assertEquals("budget_1", budget.skipReason(AdvertisingSetRole.TITLE))
        assertEquals("budget_1", budget.skipReason(AdvertisingSetRole.REACH))
        assertNull("the session-critical set is never skipped", budget.skipReason(AdvertisingSetRole.STABLE))
        assertFalse("one refusal with a set owned is not the stuck signature", verdict.stuck)
    }

    @Test
    fun anOptionalSetNeedsRoomForItselfPlusOneSpare() {
        val budget = budget()
        budget.noteProbe(free = 2, ownedNow = 1, refused = true)
        assertEquals("1 owned + 2 free", 3, budget.ceiling())
        budget.acquire("stable", AdvertisingSetRole.STABLE)
        assertNull("owned 1 + the set + a spare = 3 fits", budget.skipReason(AdvertisingSetRole.TITLE))
        budget.acquire("reach", AdvertisingSetRole.REACH)
        assertEquals("owned 2 + 2 > 3", "budget_3", budget.skipReason(AdvertisingSetRole.TITLE))
    }

    @Test
    fun aProbeThatReachedItsMaximumLearnsNoCeilingButRaisesTheProvenCount() {
        val budget = budget()
        budget.noteProbe(free = 4, ownedNow = 1, refused = false)
        assertNull(budget.ceiling())
        assertEquals(5, budget.provenSets)
        assertNull(budget.skipReason(AdvertisingSetRole.TITLE))
    }

    @Test
    fun aLearnedCeilingExpiresAndASuccessfulStartAboveItClearsIt() {
        val budget = budget()
        budget.noteRefused(AdvertisingSetRole.TITLE, tooMany, ownedBefore = 1, sdk = 33)
        assertEquals(1, budget.ceiling())
        now += AdvertisingBudget.CEILING_TTL_MS + 1
        assertNull("other apps' advertisers come and go: relearn", budget.ceiling())

        budget.noteRefused(AdvertisingSetRole.TITLE, tooMany, ownedBefore = 1, sdk = 33)
        budget.acquire("a", AdvertisingSetRole.STABLE)
        budget.acquire("b", AdvertisingSetRole.REACH)
        assertNull("two sets run: a ceiling of one was wrong", budget.ceiling())
    }

    @Test
    fun otherFailureCodesTeachNothing() {
        val budget = budget()
        val verdict = budget.noteRefused(
            AdvertisingSetRole.STABLE, AdvertisingSetCallback.ADVERTISE_FAILED_INTERNAL_ERROR, ownedBefore = 0, sdk = 33,
        )
        assertFalse(verdict.tooMany)
        assertNull(budget.ceiling())
        assertNull(budget.stuck())
    }

    @Test
    fun theLowestPriorityOptionalSetIsReleasedFirstAndTheSessionCriticalOneNever() {
        val budget = budget()
        val released = mutableListOf<String>()
        budget.acquire("stable", AdvertisingSetRole.STABLE) { released += "stable" }
        budget.acquire("reach", AdvertisingSetRole.REACH) { released += "reach" }
        budget.acquire("title", AdvertisingSetRole.TITLE) { released += "title" }
        assertEquals(AdvertisingSetRole.TITLE, budget.preemptFor(AdvertisingSetRole.STABLE))
        assertEquals(AdvertisingSetRole.REACH, budget.preemptFor(AdvertisingSetRole.STABLE))
        assertNull("nothing below the stable set is left", budget.preemptFor(AdvertisingSetRole.STABLE))
        assertEquals(listOf("title", "reach"), released)
        assertEquals(1, budget.owned())
    }

    @Test
    fun aReachSetMayTakeTheTitlesInstanceButNotTheStableOnes() {
        val budget = budget()
        budget.acquire("stable", AdvertisingSetRole.STABLE) { error("never released") }
        budget.acquire("title", AdvertisingSetRole.TITLE) {}
        assertEquals(AdvertisingSetRole.TITLE, budget.preemptFor(AdvertisingSetRole.REACH))
        assertNull(budget.preemptFor(AdvertisingSetRole.REACH))
    }

    @Test
    fun aSetWithoutAReleaseHookIsNotPreempted() {
        val budget = budget()
        budget.acquire("probe", AdvertisingSetRole.PROBE)
        assertNull(budget.preemptFor(AdvertisingSetRole.STABLE))
    }

    @Test
    fun theDropOrderOfThePlanIsProbeThenTitleThenReachAndStableStays() {
        val all = listOf(AdvertisingSetRole.TITLE, AdvertisingSetRole.STABLE, AdvertisingSetRole.REACH)
        assertEquals(AdvertisingBudget.Plan(listOf(AdvertisingSetRole.STABLE, AdvertisingSetRole.REACH, AdvertisingSetRole.TITLE), emptyList()),
            AdvertisingBudget.planFor(all, null))
        assertEquals(AdvertisingBudget.Plan(listOf(AdvertisingSetRole.STABLE, AdvertisingSetRole.REACH, AdvertisingSetRole.TITLE), emptyList()),
            AdvertisingBudget.planFor(all, 4))
        assertEquals("three instances: the stable set, one optional set, one spare",
            AdvertisingBudget.Plan(listOf(AdvertisingSetRole.STABLE, AdvertisingSetRole.REACH), listOf(AdvertisingSetRole.TITLE)),
            AdvertisingBudget.planFor(all, 3))
        assertEquals("a single instance carries the stable set alone",
            AdvertisingBudget.Plan(listOf(AdvertisingSetRole.STABLE), listOf(AdvertisingSetRole.REACH, AdvertisingSetRole.TITLE)),
            AdvertisingBudget.planFor(all, 1))
        assertEquals(AdvertisingBudget.Plan(listOf(AdvertisingSetRole.STABLE), listOf(AdvertisingSetRole.REACH, AdvertisingSetRole.TITLE)),
            AdvertisingBudget.planFor(all, 2))
        assertEquals("even a zero budget keeps the session-critical set requested",
            listOf(AdvertisingSetRole.STABLE), AdvertisingBudget.planFor(all, 0).run)
    }

    @Test
    fun twoRefusalsWhileOwningNothingAreTheStuckSignatureAndAreReportedOnce() {
        val budget = budget()
        val reports = mutableListOf<AdvertisingBudget.StuckReport>()
        budget.addStuckListener { reports += it }
        assertFalse(budget.noteRefused(AdvertisingSetRole.STABLE, tooMany, ownedBefore = 0, sdk = 34).stuck)
        assertNull(budget.stuck())
        now += 2_000
        val verdict = budget.noteRefused(AdvertisingSetRole.STABLE, tooMany, ownedBefore = 0, sdk = 34)
        assertTrue(verdict.stuck)
        assertNotNull(budget.stuck())
        assertEquals(1, reports.size)
        budget.noteRefused(AdvertisingSetRole.STABLE, tooMany, ownedBefore = 0, sdk = 34)
        assertEquals("reported once", 1, reports.size)
        val message = reports.single().message
        assertTrue(message, "Turn Bluetooth off and on" in message)
        assertFalse(message, "Android 15" in message)
    }

    @Test
    fun onAndroid15TheStuckReportNamesTheKnownFaultAndALeakIsSuspectedWithOneOwnedSet() {
        val budget = budget()
        assertTrue(budget.noteRefused(AdvertisingSetRole.TITLE, tooMany, ownedBefore = 1, sdk = 35).suspectedA15Leak)
        assertFalse(budget.noteRefused(AdvertisingSetRole.TITLE, tooMany, ownedBefore = 1, sdk = 34).suspectedA15Leak)
        assertFalse("two owned sets is just a small budget", budget.noteRefused(AdvertisingSetRole.TITLE, tooMany, ownedBefore = 2, sdk = 35).suspectedA15Leak)
        budget.noteRefused(AdvertisingSetRole.STABLE, tooMany, ownedBefore = 0, sdk = 35)
        budget.noteRefused(AdvertisingSetRole.STABLE, tooMany, ownedBefore = 0, sdk = 35)
        assertTrue("Android 15" in budget.stuck()!!.message)
    }

    @Test
    fun aSuccessfulStartEndsTheStuckState() {
        val budget = budget()
        repeat(2) { budget.noteRefused(AdvertisingSetRole.STABLE, tooMany, ownedBefore = 0, sdk = 33) }
        assertNotNull(budget.stuck())
        budget.acquire("stable", AdvertisingSetRole.STABLE)
        assertNull(budget.stuck())
    }

    @Test
    fun refusalsWhileOwningASetNeverMakeTheStuckSignature() {
        val budget = budget()
        repeat(5) { budget.noteRefused(AdvertisingSetRole.TITLE, tooMany, ownedBefore = 1, sdk = 33) }
        assertNull(budget.stuck())
    }

    @Test
    fun aBluetoothRestartVoidsEverythingLearned() {
        val budget = budget()
        budget.acquire("stable", AdvertisingSetRole.STABLE)
        budget.noteRefused(AdvertisingSetRole.TITLE, tooMany, ownedBefore = 1, sdk = 33)
        budget.noteStopRequested("x")
        budget.noteConnectedAdvertising(false)
        AdvertisingSetProbe.lastFree = 3
        budget.onAdapterState(BluetoothAdapter.STATE_ON)
        assertEquals("turning on changes nothing", 1, budget.ceiling())
        budget.onAdapterState(BluetoothAdapter.STATE_OFF)
        assertNull(budget.ceiling())
        assertEquals(0, budget.owned())
        assertEquals(0, budget.pendingStopCount())
        assertEquals(0, budget.provenSets)
        assertNull(budget.connectedAdvertising)
        assertNull("the probe result is stale after a restart", AdvertisingSetProbe.lastFree)
        assertEquals(1, budget.epoch)
    }

    @Test
    fun aStopThatIsNeverConfirmedBlocksAStartOnlyForTheSettleCap() {
        val budget = budget()
        budget.noteStopRequested("old")
        assertEquals(1, budget.pendingStopCount())
        assertEquals(AdvertisingBudget.SETTLE_MAX_MS, budget.awaitPendingStops())
    }

    @Test
    fun theWaitEndsAsSoonAsTheStopIsConfirmed() {
        lateinit var budget: AdvertisingBudget
        budget = AdvertisingBudget(
            clock = { now },
            sleeper = { ms ->
                now += ms
                if (now >= 1_060L) budget.noteStopped("old")
            },
            onMainThread = { false },
            log = { lines += it },
        )
        budget.noteStopRequested("old")
        val waited = budget.awaitPendingStops()
        assertTrue("waited $waited", waited in 40L..80L)
        assertEquals(0, budget.pendingStopCount())
    }

    @Test
    fun aStopWhoseCallbackNeverComesStopsBlockingAfterItsLifetime() {
        val budget = budget()
        budget.noteStopRequested("lost")
        now += AdvertisingBudget.PENDING_STOP_TTL_MS + 1
        assertEquals("callbacks lost to a Bluetooth crash are not waited for", 0, budget.pendingStopCount())
        assertEquals(0L, budget.awaitPendingStops())
    }

    @Test
    fun theWaitNeverRunsOnTheMainThread() {
        main = true
        val budget = budget()
        budget.noteStopRequested("old")
        assertEquals(0L, budget.awaitPendingStops())
        assertEquals("the stop stays pending", 1, budget.pendingStopCount())
    }

    @Test
    fun theSimulatedControllerRefusesOnceItsInstancesAreHeldOrStillReleasing() {
        val budget = budget()
        assertFalse("off by default", budget.simulatedRefusal())
        AdvertisingSwitches.budgetCap = 1
        if (!BuildConfig.DEBUG) {
            // A DEBUG test arm: a release build keeps no simulated cap and never refuses on its own.
            assertEquals(0, AdvertisingSwitches.budgetCap)
            assertFalse(budget.simulatedRefusal())
            return
        }
        assertEquals(1, budget.ceiling())
        assertFalse(budget.simulatedRefusal())
        budget.acquire("stable", AdvertisingSetRole.STABLE)
        assertTrue(budget.simulatedRefusal())
        budget.release("stable")
        budget.noteStopRequested("stable")
        assertTrue("an unconfirmed stop still holds its instance", budget.simulatedRefusal())
        budget.noteStopped("stable")
        assertFalse(budget.simulatedRefusal())
        assertEquals("a one-instance controller leaves no room for an optional set", "budget_1", budget.skipReason(AdvertisingSetRole.TITLE))
    }

    @Test
    fun theConnectedAdvertisingAnswerIsCachedAndLoggedOnChange() {
        val budget = budget()
        assertNull(budget.connectedAdvertising)
        budget.noteConnectedAdvertising(false)
        budget.noteConnectedAdvertising(false)
        assertEquals(false, budget.connectedAdvertising)
        assertEquals(1, lines.count { "connected_advertising" in it })
    }

    @Test
    fun theTitleDecisionAsksThePolicyThenTheBudgetThenTheProbe() {
        assertEquals(
            TitleAdvertisingDecision.Outcome.Skip("device_policy"),
            TitleAdvertisingDecision.decide(policyAllows = false, freeSets = 4, budgetSkip = "budget_1"),
        )
        assertEquals(
            TitleAdvertisingDecision.Outcome.Skip("budget_1"),
            TitleAdvertisingDecision.decide(policyAllows = true, freeSets = 4, budgetSkip = "budget_1"),
        )
        assertEquals(
            TitleAdvertisingDecision.Outcome.Skip("probe_found_1"),
            TitleAdvertisingDecision.decide(policyAllows = true, freeSets = 1, budgetSkip = null),
        )
        assertEquals(TitleAdvertisingDecision.Outcome.Start, TitleAdvertisingDecision.decide(policyAllows = true, freeSets = null))
    }

    @Test
    fun theRoleOrderIsTheBacklogPriority() {
        assertEquals(
            listOf(AdvertisingSetRole.STABLE, AdvertisingSetRole.REACH, AdvertisingSetRole.TITLE, AdvertisingSetRole.PROBE),
            AdvertisingSetRole.entries.toList(),
        )
    }
}
