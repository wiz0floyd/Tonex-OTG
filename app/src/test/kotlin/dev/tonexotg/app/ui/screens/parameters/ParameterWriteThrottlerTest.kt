package dev.tonexotg.app.ui.screens.parameters

import dev.tonexotg.protocol.ParameterId
import dev.tonexotg.protocol.TonexResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NFR2 / D3 §3, §6.1: "Continuous slider drags must not queue an unbounded write backlog.
 * Throttle or conflate in-flight parameter writes — the last value wins." These tests pin the
 * exact conflation behavior [ParameterWriteThrottler] exists to provide — issue #22 calls this
 * out as "the trickiest [test], write a test that simulates rapid value changes and asserts the
 * controller only receives the conflated final value, not every intermediate one."
 *
 * ## `runCurrent()`, not `advanceUntilIdle()`
 *
 * Every test below pumps the scheduler with [kotlinx.coroutines.test.runCurrent] rather than the
 * more commonly reached-for [kotlinx.coroutines.test.TestScope.advanceUntilIdle] — deliberately,
 * not a style choice. [ParameterWriteThrottler] is constructed with `scope = backgroundScope`
 * (matching its real caller, [dev.tonexotg.app.ui.screens.parameters.ParameterEditorViewModel],
 * which does the same), and each worker it starts is a `backgroundScope` coroutine tagged
 * "background," not "foreground," by `kotlinx-coroutines-test`'s own bookkeeping. Per that
 * library's `TestCoroutineScheduler.advanceUntilIdle` (decompiled and confirmed against the
 * exact `1.11.0` jar this project resolves): it loops only while at least one **foreground**
 * event remains queued and stops immediately once none does — it does not run purely-background
 * work at all when nothing foreground is pending, which is every case in this file (nothing here
 * ever launches on the bare `TestScope` itself). `advanceUntilIdle()` in that situation is a
 * silent no-op: the very bug this comment exists to prevent someone from reintroducing by
 * "simplifying" `runCurrent()` back to the more familiar call. [runCurrent] runs every
 * currently-ready event regardless of foreground/background tagging, which is what these tests
 * actually need — nothing here uses `delay()`, so there is no virtual time to additionally
 * advance.
 */
class ParameterWriteThrottlerTest {

    private val gain = ParameterId(20)

    @Test
    fun `rapid submits while a write is in flight conflate to only the last value`() = runTest {
        val received = mutableListOf<Float>()
        val firstWriteStarted = Channel<Unit>(Channel.UNLIMITED)
        val releaseFirstWrite = CompletableDeferred<Unit>()
        var writeCount = 0

        val throttler = ParameterWriteThrottler(
            scope = backgroundScope,
            write = { _, value ->
                writeCount++
                received.add(value)
                if (writeCount == 1) {
                    // Hold the first write "in flight" so every subsequent submit below happens
                    // while it has not yet returned - exactly the "continuous slider drag" case.
                    firstWriteStarted.trySend(Unit)
                    releaseFirstWrite.await()
                }
                TonexResult.Success(Unit)
            },
        )

        throttler.submit(gain, 1f)
        firstWriteStarted.receive() // deterministic: don't proceed until the first write has actually started

        // Simulate a fast drag: many intermediate values arrive while write #1 is still in flight.
        throttler.submit(gain, 2f)
        throttler.submit(gain, 3f)
        throttler.submit(gain, 4f)
        throttler.submit(gain, 5f)
        throttler.submit(gain, 6.4f) // the drag's final, released position

        releaseFirstWrite.complete(Unit)
        runCurrent()

        // Exactly two writes ever reach the controller: the one already in flight when the flood
        // started, and the conflated *last* value - never 2f, 3f, 4f, or 5f individually, and
        // never six separate writes for six submits.
        assertEquals(listOf(1f, 6.4f), received)
        assertEquals(2, writeCount)
    }

    @Test
    fun `many rapid submits before any write starts still produce only one write of the last value`() = runTest {
        val received = mutableListOf<Float>()
        val throttler = ParameterWriteThrottler(
            scope = backgroundScope,
            write = { _, value ->
                received.add(value)
                TonexResult.Success(Unit)
            },
        )

        for (v in 0..99) {
            throttler.submit(gain, v.toFloat())
        }
        runCurrent()

        // Bounded backlog: whatever the worker happens to pick up first, at most 2 writes ever
        // happen for a burst offered before any write starts (one dequeued+in-flight, one
        // conflated-and-replaced repeatedly) - and the very last value submitted is always the
        // last one written.
        assertTrue("expected at most 2 writes for a burst, got ${received.size}: $received", received.size <= 2)
        assertEquals(99f, received.last())
    }

    @Test
    fun `submits for different parameter ids are never conflated against each other`() = runTest {
        val bass = ParameterId(11)
        val received = mutableListOf<Pair<ParameterId, Float>>()
        val throttler = ParameterWriteThrottler(
            scope = backgroundScope,
            write = { id, value ->
                received.add(id to value)
                TonexResult.Success(Unit)
            },
        )

        throttler.submit(gain, 7f)
        throttler.submit(bass, 3f)
        runCurrent()

        assertEquals(setOf(gain to 7f, bass to 3f), received.toSet())
    }

    @Test
    fun `onResult observes the value that was actually written`() = runTest {
        val observed = mutableListOf<Float>()
        val throttler = ParameterWriteThrottler(
            scope = backgroundScope,
            write = { _, value -> TonexResult.Success(Unit) },
            onResult = { _, value, result ->
                check(result is TonexResult.Success)
                observed.add(value)
            },
        )

        throttler.submit(gain, 5f)
        runCurrent()

        assertEquals(listOf(5f), observed)
    }

    @Test
    fun `cancelAll drops a buffered value that was never picked up`() = runTest {
        val received = mutableListOf<Float>()
        val firstWriteStarted = Channel<Unit>(Channel.UNLIMITED)
        val releaseFirstWrite = CompletableDeferred<Unit>()
        var writeCount = 0

        val throttler = ParameterWriteThrottler(
            scope = backgroundScope,
            write = { _, value ->
                writeCount++
                received.add(value)
                if (writeCount == 1) {
                    firstWriteStarted.trySend(Unit)
                    releaseFirstWrite.await()
                }
                TonexResult.Success(Unit)
            },
        )

        throttler.submit(gain, 1f)
        firstWriteStarted.receive()
        throttler.submit(gain, 2f) // buffered, not yet picked up

        throttler.cancelAll() // D3 §6.3: an external preset change mid-drag drops the pending value
        releaseFirstWrite.complete(Unit)
        runCurrent()

        assertEquals(listOf(1f), received) // 2f was never sent
    }
}
