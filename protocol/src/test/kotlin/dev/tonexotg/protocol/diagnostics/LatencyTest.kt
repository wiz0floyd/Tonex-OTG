package dev.tonexotg.protocol.diagnostics

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * S21 (issue #26): pure timing-arithmetic tests for [measureLatency] and [BurstStats]. The
 * real-hardware-facing "did this correctly measure a real USB operation" question is answered by
 * `DefaultTonexControllerLatencyTest`, which wires [measureLatency] around a real
 * [dev.tonexotg.protocol.connection.DefaultTonexController] call against a delayed
 * [dev.tonexotg.protocol.connection.FakeTonexTransport]. This file only proves the arithmetic
 * itself — clock injection and percentile/growth math — is correct, independent of any transport.
 */
class LatencyTest {

    // ---- measureLatency ------------------------------------------------------------------

    @Test
    fun `measureLatency reports elapsed time from an injected clock, not wall-clock time`() = runTest {
        var now = 1_000L
        val clock = ElapsedClock { now }

        val result = measureLatency(clock) {
            now += 42
            "the value"
        }

        assertEquals("the value", result.value)
        assertEquals(42L, result.elapsedMillis)
    }

    @Test
    fun `measureLatency with the default clock returns a non-negative elapsed time`() = runTest {
        val result = measureLatency { 7 }

        assertEquals(7, result.value)
        assertTrue(result.elapsedMillis >= 0, "elapsed time must never be negative")
    }

    @Test
    fun `measureLatency propagates an exception thrown by block without catching it`() = runTest {
        val clock = ElapsedClock { 0L }
        val boom = IllegalStateException("boom")

        val thrown = assertFailsWith<IllegalStateException> {
            measureLatency(clock) { throw boom }
        }
        assertEquals(boom, thrown)
    }

    // ---- BurstStats.of ----------------------------------------------------------------------

    @Test
    fun `BurstStats of a single sample reports that sample for every statistic`() {
        val stats = BurstStats.of(listOf(50L))

        assertEquals(1, stats.count)
        assertEquals(50L, stats.minMillis)
        assertEquals(50L, stats.maxMillis)
        assertEquals(50.0, stats.meanMillis)
        assertEquals(50L, stats.p50Millis)
        assertEquals(50L, stats.p90Millis)
        assertEquals(50L, stats.p99Millis)
    }

    @Test
    fun `BurstStats of rejects an empty sample list`() {
        assertFailsWith<IllegalArgumentException> { BurstStats.of(emptyList()) }
    }

    @Test
    fun `BurstStats percentiles use nearest-rank over ten ascending samples`() {
        // 10 samples 10..100 (already ascending); nearest-rank p50 = ceil(0.5*10)=5th -> 50,
        // p90 = ceil(0.9*10)=9th -> 90, p99 = ceil(0.99*10)=10th -> 100.
        val samples = (1..10).map { it * 10L }

        val stats = BurstStats.of(samples)

        assertEquals(10, stats.count)
        assertEquals(10L, stats.minMillis)
        assertEquals(100L, stats.maxMillis)
        assertEquals(55.0, stats.meanMillis)
        assertEquals(50L, stats.p50Millis)
        assertEquals(90L, stats.p90Millis)
        assertEquals(100L, stats.p99Millis)
    }

    @Test
    fun `BurstStats percentiles do not depend on input order`() {
        val ascending = BurstStats.of((1..10).map { it * 10L })
        val shuffled = BurstStats.of(listOf(30L, 100L, 10L, 70L, 50L, 20L, 90L, 40L, 80L, 60L))

        assertEquals(ascending.minMillis, shuffled.minMillis)
        assertEquals(ascending.maxMillis, shuffled.maxMillis)
        assertEquals(ascending.p50Millis, shuffled.p50Millis)
        assertEquals(ascending.p90Millis, shuffled.p90Millis)
        assertEquals(ascending.p99Millis, shuffled.p99Millis)
    }

    @Test
    fun `BurstStats flags backlogSuspected when the last third is much slower than the first third`() {
        // 9 samples: first third steady at 20ms, last third grown to 80ms (4x) -- a clear backlog signal.
        val samples = listOf(20L, 20L, 20L, 30L, 40L, 50L, 80L, 80L, 80L)

        val stats = BurstStats.of(samples)

        assertEquals(20.0, stats.firstThirdMeanMillis)
        assertEquals(80.0, stats.lastThirdMeanMillis)
        assertEquals(4.0, stats.growthRatio)
        assertTrue(stats.backlogSuspected, "a 4x growth ratio must be flagged")
    }

    @Test
    fun `BurstStats does not flag backlogSuspected when latency holds steady across the burst`() {
        val samples = List(9) { 25L } // perfectly flat

        val stats = BurstStats.of(samples)

        assertEquals(1.0, stats.growthRatio)
        assertFalse(stats.backlogSuspected)
    }

    @Test
    fun `BurstStats growthRatio is NaN and backlogSuspected is false when the first third is all zero`() {
        val samples = listOf(0L, 0L, 0L, 5L, 5L, 5L, 10L, 10L, 10L)

        val stats = BurstStats.of(samples)

        assertTrue(stats.growthRatio.isNaN(), "division by a zero first-third mean must not silently become Infinity")
        assertFalse(stats.backlogSuspected, "backlogSuspected must treat NaN as 'not flagged', not as true")
    }
}
