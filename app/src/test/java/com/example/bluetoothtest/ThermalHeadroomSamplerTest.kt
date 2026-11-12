package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class ThermalHeadroomSamplerTest {
    private class Fake(var values: ArrayDeque<Float>) {
        var now = 0L
        val callTimes = mutableListOf<Long>()
        val requestedSeconds = mutableListOf<Int>()
        var thresholdsEmpty: Boolean? = null
        var fallback = 0.5f

        fun read(seconds: Int): Float {
            callTimes += now
            requestedSeconds += seconds
            return values.removeFirstOrNull() ?: fallback
        }

        fun sampler(config: ThermalHeadroomSampler.Config = ThermalHeadroomSampler.Config()) =
            ThermalHeadroomSampler(::read, { now }, config, { thresholdsEmpty })
    }

    private fun fake(vararg v: Float) = Fake(ArrayDeque(v.toList()))

    @Test
    fun callsAreTwoSecondsApartAndAskForTheTenSecondForecast() {
        val f = fake()
        val sampler = f.sampler()
        for (t in 0L..10_000L step 250L) {
            f.now = t
            sampler.tick()
        }
        assertEquals(listOf(0L, 2_000L, 4_000L, 6_000L, 8_000L, 10_000L), f.callTimes)
        assertTrue(f.requestedSeconds.all { it == 10 })
    }

    @Test
    fun theClientRateLimitIsNeverBreachedEvenWithATinyConfiguredInterval() {
        val f = fake()
        val sampler = f.sampler(ThermalHeadroomSampler.Config(sampleIntervalMs = 10))
        for (t in 0L..2_000L step 100L) {
            f.now = t
            sampler.tick()
        }
        assertEquals(listOf(0L, 500L, 1_000L, 1_500L, 2_000L), f.callTimes)
        assertEquals(500L, sampler.config.effectiveSampleIntervalMs)
    }

    @Test
    fun valuesInTheFirstThreeSecondsAreDiscardedThenTheFirstDecisionIsImmediate() {
        val f = fake(0.1f, 0.2f, 0.9f)
        val sampler = f.sampler()
        f.now = 0
        val first = sampler.tick()
        assertTrue(first.called)
        assertFalse(first.decisionDue)
        f.now = 2_000
        assertFalse(sampler.tick().decisionDue)
        assertEquals(2, sampler.warmupDiscarded)
        assertNull(sampler.latestHeadroom)
        f.now = 4_000
        val third = sampler.tick()
        assertTrue(third.decisionDue)
        assertEquals(0.9f, third.decisionHeadroom!!, 0f)
        assertEquals(0.9f, sampler.latestHeadroom!!, 0f)
    }

    @Test
    fun laterDecisionsComeOnlyEveryTenSeconds() {
        val f = fake()
        val sampler = f.sampler()
        val decisionTimes = mutableListOf<Long>()
        for (t in 0L..30_000L step 500L) {
            f.now = t
            if (sampler.tick().decisionDue) decisionTimes += t
        }
        assertEquals(listOf(4_000L, 14_000L, 24_000L), decisionTimes)
    }

    @Test
    fun aGapLongerThanTheServersInactivityThresholdRestartsTheWarmup() {
        val f = fake()
        val sampler = f.sampler()
        for (t in listOf(0L, 2_000L, 4_000L)) {
            f.now = t
            sampler.tick()
        }
        assertEquals(0.5f, sampler.latestHeadroom!!, 0f)
        f.now = 16_001 // the server cleared its samples after 10 s without a forecast call
        sampler.tick()
        assertNull(sampler.latestHeadroom)
        f.now = 18_001
        sampler.tick()
        assertNull(sampler.latestHeadroom)
        f.now = 20_001
        sampler.tick()
        assertEquals(0.5f, sampler.latestHeadroom!!, 0f)
    }

    @Test
    fun nanTwiceInARowMeansUnsupportedAndThreeTimesLatchesNoSkinThresholds() {
        val f = fake(Float.NaN, Float.NaN, Float.NaN, 0.7f)
        val sampler = f.sampler()
        f.now = 0
        val one = sampler.tick()
        assertFalse(sampler.forecastUnsupported)
        assertFalse(one.decisionDue)
        f.now = 2_000
        val two = sampler.tick()
        assertTrue(sampler.forecastUnsupported)
        assertFalse(sampler.noSkinThresholds)
        // The consumer is told once that there is no forecast.
        assertTrue(two.decisionDue)
        assertNull(two.decisionHeadroom)
        f.now = 4_000
        sampler.tick()
        assertTrue(sampler.noSkinThresholds)
        assertTrue(sampler.stopped)
        // Sampling stops: no more reads after the latch.
        val calls = f.callTimes.size
        f.now = 6_000
        assertFalse(sampler.tick().called)
        assertEquals(calls, f.callTimes.size)
    }

    @Test
    fun aFiniteValueClearsTheNanStreakBeforeTheLatch() {
        val f = fake(Float.NaN, Float.NaN, 0.6f, Float.NaN)
        val sampler = f.sampler()
        for (t in listOf(0L, 2_000L, 4_000L, 6_000L)) {
            f.now = t
            sampler.tick()
        }
        assertFalse(sampler.noSkinThresholds)
        assertFalse(sampler.forecastUnsupported)
        assertEquals(1, sampler.consecutiveNan)
        assertEquals(3, sampler.nanReadings)
    }

    @Test
    fun anEmptyThresholdMapLatchesWithoutReadingAnything() {
        val f = fake()
        f.thresholdsEmpty = true
        val sampler = f.sampler()
        val result = sampler.tick()
        assertFalse(result.called)
        assertTrue(result.decisionDue)
        assertNull(result.decisionHeadroom)
        assertTrue(sampler.noSkinThresholds)
        assertEquals(0, f.callTimes.size)
    }

    @Test
    fun resetStartsAFreshSession() {
        val f = fake(Float.NaN, Float.NaN, Float.NaN)
        val sampler = f.sampler()
        for (t in listOf(0L, 2_000L, 4_000L)) {
            f.now = t
            sampler.tick()
        }
        assertTrue(sampler.noSkinThresholds)
        sampler.reset()
        assertFalse(sampler.noSkinThresholds)
        assertEquals(0, sampler.calls)
        f.now = 5_000
        assertTrue(sampler.tick().called)
    }

    @Test
    fun legacyArmReproducesTheOldTenSecondCadenceWithoutWarmup() {
        // The legacy cadence is a DEBUG control arm; a release build keeps the shipped cadence.
        assumeTrue("DEBUG arm", BuildConfig.DEBUG)
        val f = fake()
        ThermalSwitches.forecastLegacy10sCadence = true
        try {
            val sampler = f.sampler(ThermalSwitches.samplerConfig())
            val decisions = mutableListOf<Long>()
            for (t in 0L..30_000L step 500L) {
                f.now = t
                if (sampler.tick().decisionDue) decisions += t
            }
            assertEquals(listOf(0L, 10_000L, 20_000L, 30_000L), f.callTimes)
            assertEquals(listOf(0L, 10_000L, 20_000L, 30_000L), decisions)
        } finally {
            ThermalSwitches.forecastLegacy10sCadence = false
        }
    }

    @Test
    fun benchmarkLineProvesTheForecastWasActive() {
        val f = fake()
        val sampler = f.sampler()
        for (t in listOf(0L, 2_000L, 4_000L)) {
            f.now = t
            sampler.tick()
        }
        val line = sampler.benchmarkLine()
        assertTrue(line, line.startsWith("BENCHMARK thermal_sampler: sampleMs=2000 | warmupMs=3000 | decisionMs=10000 | forecastSeconds=10 | calls=3"))
        assertTrue(line, "warmupDiscarded=2" in line && "latest=0.50" in line && line.endsWith("noSkinThresholds=false"))
    }

    // ---- driver -------------------------------------------------------------------------------

    private class FakeScheduler : TickScheduler {
        val queue = ArrayDeque<Pair<Long, Runnable>>()
        override fun postDelayed(delayMs: Long, task: Runnable) {
            queue.addLast(delayMs to task)
        }

        fun runNext(): Long? {
            val (delay, task) = queue.removeFirstOrNull() ?: return null
            task.run()
            return delay
        }
    }

    @Test
    fun driverLoopsAtTheSamplerCadenceAndStopsWhenTheSessionEnds() {
        val f = fake()
        val sampler = f.sampler()
        val scheduler = FakeScheduler()
        var active = true
        val decisions = mutableListOf<Float?>()
        val driver = ThermalHeadroomSamplerDriver(sampler, scheduler, { active }, { h, _ -> decisions += h })
        driver.ensureRunning()
        driver.ensureRunning() // idempotent
        assertEquals(1, scheduler.queue.size)
        assertEquals(0L, scheduler.runNext())
        assertEquals(2_000L, scheduler.queue.first().first)
        f.now = 2_000
        scheduler.runNext()
        f.now = 4_000
        scheduler.runNext()
        assertEquals(listOf(0.5f), decisions)
        active = false
        f.now = 6_000
        scheduler.runNext()
        assertFalse(driver.isRunning)
        assertEquals(0, scheduler.queue.size)
        assertEquals(0, sampler.calls) // reset for the next session
        // A new session starts a new warm-up.
        active = true
        driver.ensureRunning()
        assertTrue(driver.isRunning)
    }

    @Test
    fun driverStopsForGoodWhenThereAreNoSkinThresholds() {
        val f = fake(Float.NaN, Float.NaN, Float.NaN)
        val sampler = f.sampler()
        val scheduler = FakeScheduler()
        val decisions = mutableListOf<Float?>()
        val driver = ThermalHeadroomSamplerDriver(sampler, scheduler, { true }, { h, _ -> decisions += h })
        driver.ensureRunning()
        for (t in listOf(0L, 2_000L, 4_000L)) {
            f.now = t
            scheduler.runNext()
        }
        assertTrue(sampler.noSkinThresholds)
        assertFalse(driver.isRunning)
        assertEquals(0, scheduler.queue.size)
        driver.ensureRunning() // does not restart a latched sampler
        assertFalse(driver.isRunning)
        // The consumer hears "unsupported" at the second NaN and "no skin thresholds" at the latch.
        assertEquals(listOf<Float?>(null, null), decisions)
    }
}
