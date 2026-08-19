@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.tonexotg.protocol.connection

import dev.tonexotg.protocol.PresetIndex
import dev.tonexotg.protocol.PresetSlot
import dev.tonexotg.protocol.TonexResult
import dev.tonexotg.protocol.diagnostics.ElapsedClock
import dev.tonexotg.protocol.diagnostics.measureLatency
import dev.tonexotg.protocol.message.FirmwareCapabilities
import dev.tonexotg.protocol.params.ParameterRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * S21 (issue #26): proves [measureLatency] correctly measures elapsed time across a REAL
 * [DefaultTonexController] operation (`setParameter`/`selectPreset`), not just around a bare
 * suspend lambda (see `LatencyTest` for that). Uses [FakeTonexTransport.writeDelayMillis] (a
 * virtual, `runTest`-clock-advancing delay — never a real sleep) as the "real USB latency" stand-in
 * this codebase's test discipline calls for, and `testScheduler.currentTime`
 * as the injected [ElapsedClock] so the assertions are exact and deterministic, matching every
 * other test in this suite's `testScheduler.runCurrent()` discipline (see `ConnectionTestFixtures`).
 *
 * This is instrumentation-correctness coverage only. It says nothing about real hardware latency —
 * that number can only come from a real pedal (issue #26's cloud-environment limitation), which is
 * exactly why `dev.tonexotg.app.probe.ProbeSession` exists to wrap these same real controller calls
 * for a human to run.
 */
class DefaultTonexControllerLatencyTest {

    private val presetParam = requireNotNull(ParameterRegistry.byIndex(2)) // NOISE_GATE_THRESHOLD, PRESET-scoped

    @Test
    fun `measureLatency around setParameter equals the fake transport's single injected write delay`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(
            scope = backgroundScope,
            capabilities = FirmwareCapabilities(supportsSingleParameterWrite = true),
        )
        val connectDeferred = async { controller.connect(fake) }
        driveToReady(fake)
        assertIs<TonexResult.Success<Unit>>(connectDeferred.await())

        fake.writeDelayMillis = 75
        val clock = ElapsedClock { testScheduler.currentTime }

        val timed = measureLatency(clock) { controller.setParameter(presetParam.id, presetParam.min) }

        assertIs<TonexResult.Success<Unit>>(timed.value)
        // setParameter issues exactly one transport write (no response is awaited) -- one 75ms delay.
        assertEquals(75L, timed.elapsedMillis)
    }

    @Test
    fun `measureLatency around a failed setParameter still reports the elapsed time`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(
            scope = backgroundScope,
            capabilities = FirmwareCapabilities(supportsSingleParameterWrite = true),
        )
        val connectDeferred = async { controller.connect(fake) }
        driveToReady(fake)
        assertIs<TonexResult.Success<Unit>>(connectDeferred.await())

        fake.writeDelayMillis = 20
        fake.writeThrows = java.io.IOException("wedged") // throws BEFORE the delay -- see FakeTonexTransport.write
        val clock = ElapsedClock { testScheduler.currentTime }

        val timed = measureLatency(clock) { controller.setParameter(presetParam.id, presetParam.min) }

        assertIs<TonexResult.Failure>(timed.value)
        // writeThrows fires before the injected delay, so this proves measureLatency reports a
        // real (here, near-zero) elapsed time on the failure path too, not just the happy path.
        assertEquals(0L, timed.elapsedMillis)
    }

    @Test
    fun `measureLatency around selectPreset sums the re-read write delay and the state write delay`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(scope = backgroundScope, capabilities = FirmwareCapabilities.NONE_CONFIRMED)
        val connectDeferred = async { controller.connect(fake) }
        driveToReady(fake, activeSlot = PresetSlot.A, a = 0, b = 1, c = 2)
        assertIs<TonexResult.Success<Unit>>(connectDeferred.await())

        fake.writeDelayMillis = 30
        val clock = ElapsedClock { testScheduler.currentTime }

        // Preset 5 is unassigned in the a=0/b=1/c=2 fixture, so this takes the mandatory
        // re-read-then-patch-then-write path: two transport writes (RequestState, then SetState),
        // each independently delayed -- see DefaultTonexControllerSelectPresetTest's identical setup.
        val timedDeferred = async { measureLatency(clock) { controller.selectPreset(PresetIndex(5)) } }
        testScheduler.runCurrent()
        fake.emitMessage(stateUpdateMessage(plausibleBlob(activeSlot = PresetSlot.A, a = 0, b = 1, c = 2)))
        testScheduler.runCurrent()
        val timed = timedDeferred.await()

        assertIs<TonexResult.Success<Unit>>(timed.value)
        assertEquals(60L, timed.elapsedMillis, "expected two 30ms writes (re-read request, then the state write)")
    }
}
