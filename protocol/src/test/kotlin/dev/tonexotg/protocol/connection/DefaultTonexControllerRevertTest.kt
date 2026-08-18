@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.tonexotg.protocol.connection

import app.cash.turbine.test
import dev.tonexotg.protocol.ParameterId
import dev.tonexotg.protocol.PresetIndex
import dev.tonexotg.protocol.PresetSlot
import dev.tonexotg.protocol.PresetSnapshot
import dev.tonexotg.protocol.SessionId
import dev.tonexotg.protocol.TonexError
import dev.tonexotg.protocol.TonexEvent
import dev.tonexotg.protocol.TonexResult
import dev.tonexotg.protocol.codec.MessageType
import dev.tonexotg.protocol.message.FirmwareCapabilities
import dev.tonexotg.protocol.message.SingleParameterPayload
import dev.tonexotg.protocol.message.SingleParameterPayloadCodec
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * §H.6 of the S9b architecture plan: `revertActivePreset()`'s replay (§G) — the highest-blast-
 * radius piece of this story, since it is the only path that writes the pedal's saved state back
 * without a save step or an undo. Complements [DefaultTonexControllerSnapshotTest] (capture) and
 * [DefaultTonexControllerFirstDestructiveWriteTest] (the warning signal).
 */
class DefaultTonexControllerRevertTest {

    private val activeIndex = PresetIndex(0)

    // ---- happy path: exactly 109 per-parameter writes, ascending, never a whole-state write ----

    @Test
    fun `revert issues exactly 109 single-parameter writes, all KIND_PARAMETER, in ascending index order`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(
            scope = backgroundScope,
            capabilities = FirmwareCapabilities(supportsSingleParameterWrite = true),
        )
        connectToReady(controller, fake)
        val writesBefore = fake.writtenMessages().size

        val result = controller.revertActivePreset()

        assertIs<TonexResult.Success<Unit>>(result)
        val newWrites = fake.writtenMessages().drop(writesBefore)
        assertEquals(109, newWrites.size, "asserted count, not sampled")
        for ((i, msg) in newWrites.withIndex()) {
            val decoded = SingleParameterPayloadCodec.decode(msg.payload)
            assertIs<TonexResult.Success<SingleParameterPayload>>(decoded)
            assertEquals(SingleParameterPayloadCodec.KIND_PARAMETER, decoded.value.kind, "write $i")
            // Read payload[4] directly, NOT decoded.value.index: SingleParameterPayloadCodec's
            // write side and read side are deliberately not inverses for a nonzero index (its own
            // KDoc, "Why encode and decode are not inverses") -- decode would recover i*256, not i.
            assertEquals(i, msg.payload[4].toInt() and 0xFF, "write $i should target ParameterId($i)")
        }
    }

    @Test
    fun `revert issues no whole-state write`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(
            scope = backgroundScope,
            capabilities = FirmwareCapabilities(supportsSingleParameterWrite = true),
        )
        connectToReady(controller, fake)
        val writesBefore = fake.writtenMessages().size

        controller.revertActivePreset()

        val newWrites = fake.writtenMessages().drop(writesBefore)
        assertTrue(
            newWrites.none { it.header.type == MessageType.StateUpdate },
            "revert must never issue a whole-state (0x0306) write",
        )
    }

    @Test
    fun `revert of an unmodified preset still writes all 109 - deliberate, no diff-based skipping`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(
            scope = backgroundScope,
            capabilities = FirmwareCapabilities(supportsSingleParameterWrite = true),
        )
        connectToReady(controller, fake)
        val writesBefore = fake.writtenMessages().size

        val result = controller.revertActivePreset() // nothing was ever changed via setParameter

        assertIs<TonexResult.Success<Unit>>(result)
        assertEquals(109, fake.writtenMessages().drop(writesBefore).size)
    }

    @Test
    fun `revert returns Success when every write succeeds`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(
            scope = backgroundScope,
            capabilities = FirmwareCapabilities(supportsSingleParameterWrite = true),
        )
        connectToReady(controller, fake)

        val result = controller.revertActivePreset()

        assertIs<TonexResult.Success<Unit>>(result)
    }

    // ---- restores exact captured values, even after later edits --------------------------------

    @Test
    fun `revert restores the exact captured values after setParameter changed them`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(
            scope = backgroundScope,
            capabilities = FirmwareCapabilities(supportsSingleParameterWrite = true),
        )
        connectToReady(controller, fake)
        val captured = snapshotValues()

        // Change three parameters away from their captured values.
        for (i in listOf(2, 30, 90)) {
            controller.setParameter(ParameterId(i), captured[i] + changeDelta(i))
        }
        val writesBefore = fake.writtenMessages().size

        val result = controller.revertActivePreset()

        assertIs<TonexResult.Success<Unit>>(result)
        val newWrites = fake.writtenMessages().drop(writesBefore)
        for ((i, msg) in newWrites.withIndex()) {
            val decoded = SingleParameterPayloadCodec.decode(msg.payload)
            assertIs<TonexResult.Success<SingleParameterPayload>>(decoded)
            assertEquals(captured[i], decoded.value.value, 1e-3f, "write $i should restore the captured value")
        }
        for (i in ParameterId.PRESET_RANGE) {
            assertEquals(captured[i], controller.parameterValues.value.getValue(ParameterId(i)), 1e-3f, "ParameterId($i)")
        }
    }

    // ---- partial failure: abort at the first failure, RevertIncomplete, snapshot retained ------

    @Test
    fun `a write failure at parameter 47 aborts with RevertIncomplete carrying appliedCount 47, no further writes`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(
            scope = backgroundScope,
            capabilities = FirmwareCapabilities(supportsSingleParameterWrite = true),
        )
        connectToReady(controller, fake)
        val writesBefore = fake.written.size
        // The 48th write after the baseline (0-indexed 47, i.e. ParameterId(47)) short-writes.
        fake.writeBehavior = { bytes ->
            if (fake.written.size - writesBefore == 48) bytes.size - 1 else bytes.size
        }

        val result = controller.revertActivePreset()

        val error = (result as TonexResult.Failure).error
        val incomplete = assertIs<TonexError.RevertIncomplete>(error)
        assertEquals(activeIndex, incomplete.presetIndex)
        assertEquals(47, incomplete.appliedCount)
        assertEquals(PresetSnapshot.PARAMETER_COUNT, incomplete.totalCount)
        assertEquals(ParameterId(47), incomplete.failedParameter)
        assertEquals(48, fake.written.size - writesBefore, "no writes should be issued after the failing one")
    }

    @Test
    fun `after a partial revert, parameterValues holds restored values for the applied prefix only`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(
            scope = backgroundScope,
            capabilities = FirmwareCapabilities(supportsSingleParameterWrite = true),
        )
        connectToReady(controller, fake)
        val captured = snapshotValues()
        val preRevertValues = controller.parameterValues.value.toMap()
        val writesBefore = fake.written.size
        fake.writeBehavior = { bytes ->
            if (fake.written.size - writesBefore == 48) bytes.size - 1 else bytes.size
        }

        val result = controller.revertActivePreset()
        assertIs<TonexError.RevertIncomplete>((result as TonexResult.Failure).error)

        for (i in 0..46) {
            assertEquals(captured[i], controller.parameterValues.value.getValue(ParameterId(i)), 1e-3f, "applied prefix, ParameterId($i)")
        }
        for (i in 47..108) {
            assertEquals(
                preRevertValues.getValue(ParameterId(i)),
                controller.parameterValues.value.getValue(ParameterId(i)),
                1e-3f,
                "unapplied remainder, ParameterId($i), must be unchanged from before revert",
            )
        }
    }

    @Test
    fun `the snapshot is retained after a partial revert, and a retry succeeds`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(
            scope = backgroundScope,
            capabilities = FirmwareCapabilities(supportsSingleParameterWrite = true),
        )
        connectToReady(controller, fake)
        val writesBefore = fake.written.size
        fake.writeBehavior = { bytes ->
            if (fake.written.size - writesBefore == 48) bytes.size - 1 else bytes.size
        }
        assertIs<TonexError.RevertIncomplete>((controller.revertActivePreset() as TonexResult.Failure).error)

        fake.writeBehavior = { it.size } // clear the induced failure
        val retryWritesBefore = fake.writtenMessages().size
        val retryResult = controller.revertActivePreset()

        assertIs<TonexResult.Success<Unit>>(retryResult)
        assertEquals(109, fake.writtenMessages().drop(retryWritesBefore).size, "the retry re-issues everything from scratch")
    }

    // ---- mid-replay active-preset change: abort loudly, remaining writes withheld ---------------

    @Test
    fun `the pedal's active preset changing mid-replay aborts with ActivePresetChangedDuringRevert and withholds the remaining writes`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(
            scope = backgroundScope,
            capabilities = FirmwareCapabilities(supportsSingleParameterWrite = true),
        )
        connectToReady(controller, fake) // activeSlot=A, a=0, b=1, c=2 -> activeIndex == PresetIndex(0)
        val writesBefore = fake.written.size
        val changeoverAt = 40 // mirrors the review's repro (footswitch mid-replay at write #40 of 109)
        val newActive = PresetIndex(5)

        // The injection point: after the `changeoverAt`th write reaches the fake transport, push an
        // externally-triggered StateUpdate naming a different active preset and drive the reader
        // coroutine to process it with runCurrent() before returning from write(). This mirrors how
        // a real footswitch/MIDI/external-editor active-preset change arrives on the reader
        // coroutine, independent of operationMutex (DefaultTonexController.kt applyStateUpdate,
        // :263-283) -- not gated by, and not synchronized with, the replay loop's writes.
        fake.writeBehavior = { bytes ->
            if (fake.written.size - writesBefore == changeoverAt) {
                fake.emitMessage(
                    stateUpdateMessage(plausibleBlob(activeSlot = PresetSlot.B, a = 0, b = newActive.value, c = 2)),
                )
                testScheduler.runCurrent()
            }
            bytes.size
        }

        val result = controller.revertActivePreset()

        val error = (result as TonexResult.Failure).error
        val changed = assertIs<TonexError.ActivePresetChangedDuringRevert>(error)
        assertEquals(activeIndex, changed.intendedPreset)
        assertEquals(newActive, changed.observedPreset)
        assertEquals(changeoverAt, changed.appliedCount, "the write in flight when the change was detected should still count as applied")
        assertEquals(PresetSnapshot.PARAMETER_COUNT, changed.totalCount)
        assertEquals(ParameterId(changeoverAt), changed.nextParameter, "the withheld write is the very next one after the changeover")

        // Assertion (b) — the one that actually proves the fix closes the bug: the remaining 69
        // writes genuinely never reached the transport. An error-type-only assertion would pass
        // even if the loop had kept writing all 109 regardless of the guard.
        assertEquals(
            changeoverAt,
            fake.written.size - writesBefore,
            "only the writes issued before the changeover should have reached the transport -- the " +
                "remaining ${PresetSnapshot.PARAMETER_COUNT - changeoverAt} must be withheld, not just error-flagged",
        )
    }

    // ---- pre-validation: out-of-range snapshot value refuses the WHOLE revert, zero writes ------

    @Test
    fun `a snapshot value outside its registry range fails before any write is issued`() = runTest {
        val fake = FakeTonexTransport()
        val store = InMemorySnapshotStore()
        val controller = DefaultTonexController(
            scope = backgroundScope,
            capabilities = FirmwareCapabilities(supportsSingleParameterWrite = true),
            snapshotStore = store,
        )
        connectToReady(controller, fake, store = store) // captureValues = null: this test injects its own
        val badValues = snapshotValues()
        badValues[2] = 500f // NOISE_GATE_THRESHOLD's range is -100..0
        store.record(PresetSnapshot(activeIndex, SessionId.create(), badValues))
        val writesBefore = fake.writtenMessages().size

        val result = controller.revertActivePreset()

        val error = (result as TonexResult.Failure).error
        val outOfRange = assertIs<TonexError.ParameterValueOutOfRange>(error)
        assertEquals(ParameterId(2), outOfRange.id)
        assertEquals(500f, outOfRange.value)
        assertEquals(fake.writtenMessages().size, writesBefore, "zero writes when pre-validation rejects the snapshot")
    }

    // ---- revert never emits FirstDestructiveWrite -----------------------------------------------

    @Test
    fun `revert does not emit FirstDestructiveWrite, even though 109 writes land`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(
            scope = backgroundScope,
            capabilities = FirmwareCapabilities(supportsSingleParameterWrite = true),
        )
        connectToReady(controller, fake)

        controller.events.test {
            val result = controller.revertActivePreset()
            assertIs<TonexResult.Success<Unit>>(result)
            expectNoEvents()
        }
    }

    // ---- the firmware-fallback prohibition wins even with a snapshot present --------------------

    @Test
    fun `revert with NONE_CONFIRMED still fails UnsupportedByFirmware even with a snapshot present, zero writes`() = runTest {
        val fake = FakeTonexTransport()
        val store = InMemorySnapshotStore()
        val controller = DefaultTonexController(
            scope = backgroundScope,
            capabilities = FirmwareCapabilities.NONE_CONFIRMED,
            snapshotStore = store,
        )
        connectToReady(controller, fake, store = store)
        store.record(PresetSnapshot(activeIndex, SessionId.create(), snapshotValues()))
        val writesBefore = fake.writtenMessages().size

        val result = controller.revertActivePreset()

        val error = (result as TonexResult.Failure).error
        assertIs<TonexError.UnsupportedByFirmware>(error)
        assertEquals("revert-active-preset", (error as TonexError.UnsupportedByFirmware).operation)
        assertEquals(fake.writtenMessages().size, writesBefore, "guard 2 must win over guard 4 - zero writes")
    }

    // ---- helpers --------------------------------------------------------------------------------

    /** A deterministic, distinct-per-index offset so each changed parameter's new value is unique. */
    private fun changeDelta(index: Int): Float = 1f + (index % 5)

    private suspend fun TestScope.connectToReady(
        controller: DefaultTonexController,
        fake: FakeTonexTransport,
        activeSlot: PresetSlot = PresetSlot.A,
        a: Int = 0,
        b: Int = 1,
        c: Int = 2,
        store: InMemorySnapshotStore? = null,
    ) {
        val connectDeferred = async { controller.connect(fake) }
        // Tests that inject their own pre-loaded snapshot pass captureValues = null so the normal
        // capture (which would otherwise overwrite the injected snapshot with in-range values) never
        // records one; every other test uses the default in-range capture.
        driveToReady(fake, activeSlot = activeSlot, a = a, b = b, c = c, captureValues = if (store != null) null else snapshotValues())
        val result = connectDeferred.await()
        assertIs<TonexResult.Success<Unit>>(result)
    }
}
