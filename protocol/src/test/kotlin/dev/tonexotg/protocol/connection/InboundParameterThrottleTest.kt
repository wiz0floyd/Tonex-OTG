@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.tonexotg.protocol.connection

import dev.tonexotg.protocol.ParameterId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Issue #104, scope item 4: the inbound `ParameterChanged` stream arrives at ~100 Hz during a knob
 * turn and must not drive one map copy and one recomposition per frame — while still applying a
 * single, one-off frame synchronously (see [InboundParameterThrottle]'s KDoc for why that matters).
 */
class InboundParameterThrottleTest {

    private val gain = ParameterId(20)
    private val bass = ParameterId(21)
    private val interval = InboundParameterThrottle.DEFAULT_INTERVAL_MILLIS

    @Test
    fun `the first submission in an idle window applies synchronously, before submit returns`() = runTest {
        val batches = mutableListOf<Map<ParameterId, Float>>()
        val throttle = InboundParameterThrottle(backgroundScope, interval) { batches += it }

        throttle.submit(gain, 1f)

        // No runCurrent()/advanceTimeBy(): this must already have happened.
        assertEquals(listOf(mapOf(gain to 1f)), batches)
    }

    @Test
    fun `submissions within one window coalesce per id into a single flush carrying the last value`() = runTest {
        val batches = mutableListOf<Map<ParameterId, Float>>()
        val throttle = InboundParameterThrottle(backgroundScope, interval) { batches += it }

        throttle.submit(gain, 1f)
        throttle.submit(gain, 2f)
        throttle.submit(gain, 3f)
        testScheduler.advanceTimeBy(interval + 1)
        testScheduler.runCurrent()

        assertEquals(2, batches.size, "expected the leading apply plus exactly one coalesced flush")
        assertEquals(mapOf(gain to 1f), batches[0])
        assertEquals(mapOf(gain to 3f), batches[1], "the flush must carry the LAST value, not an intermediate one")
    }

    @Test
    fun `different ids in one window flush together as a single batch`() = runTest {
        val batches = mutableListOf<Map<ParameterId, Float>>()
        val throttle = InboundParameterThrottle(backgroundScope, interval) { batches += it }

        throttle.submit(gain, 1f)
        throttle.submit(bass, 5f)
        throttle.submit(gain, 2f)
        testScheduler.advanceTimeBy(interval + 1)
        testScheduler.runCurrent()

        assertEquals(2, batches.size)
        assertEquals(mapOf(gain to 2f, bass to 5f), batches[1])
    }

    @Test
    fun `a sustained stream flushes about once per interval, not once per submission`() = runTest {
        val batches = mutableListOf<Map<ParameterId, Float>>()
        val throttle = InboundParameterThrottle(backgroundScope, interval) { batches += it }

        // 100 frames at the pedal's observed ~10 ms cadence = 1 second of knob turning.
        repeat(100) { i ->
            throttle.submit(gain, i.toFloat())
            testScheduler.advanceTimeBy(10)
            testScheduler.runCurrent()
        }
        testScheduler.advanceTimeBy(interval * 2)
        testScheduler.runCurrent()

        assertTrue(batches.size <= 40, "100 frames over ~1s must not produce ${batches.size} applies")
        assertEquals(99f, batches.last().getValue(gain), "the final value must still land")
    }

    @Test
    fun `after a window closes, the next submission applies synchronously again`() = runTest {
        val batches = mutableListOf<Map<ParameterId, Float>>()
        val throttle = InboundParameterThrottle(backgroundScope, interval) { batches += it }

        throttle.submit(gain, 1f)
        testScheduler.advanceTimeBy(interval * 3)
        testScheduler.runCurrent()
        batches.clear()

        throttle.submit(gain, 9f)

        assertEquals(listOf(mapOf(gain to 9f)), batches)
    }

    @Test
    fun `cancelAll drops what is pending instead of flushing it into a cleared session`() = runTest {
        val batches = mutableListOf<Map<ParameterId, Float>>()
        val throttle = InboundParameterThrottle(backgroundScope, interval) { batches += it }

        throttle.submit(gain, 1f)
        throttle.submit(gain, 2f)
        throttle.cancelAll()
        testScheduler.advanceTimeBy(interval * 3)
        testScheduler.runCurrent()

        assertEquals(1, batches.size, "the pending value must be dropped, not flushed after teardown")
    }
}
