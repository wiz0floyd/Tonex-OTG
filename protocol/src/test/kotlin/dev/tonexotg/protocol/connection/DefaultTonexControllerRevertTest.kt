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

    // ---- issue #45: reselecting the aborted preset and retrying is now safe ---------------------

    @Test
    fun `after an aborted revert, reselecting the same preset keeps the original snapshot intact and a retry succeeds`() = runTest {
        val fake = FakeTonexTransport()
        val store = InMemorySnapshotStore()
        val controller = DefaultTonexController(
            scope = backgroundScope,
            capabilities = FirmwareCapabilities(supportsSingleParameterWrite = true),
            snapshotStore = store,
        )
        val originalValues = snapshotValues()
        connectToReady(controller, fake) // activeSlot=A, a=0,b=1,c=2 -> activeIndex == PresetIndex(0); captures originalValues
        val writesBefore = fake.written.size
        val changeoverAt = 40
        val newActive = PresetIndex(5)

        // Abort the revert mid-replay, exactly as in the ActivePresetChangedDuringRevert test above:
        // an external event moves the pedal's active preset to 5 partway through restoring preset 0.
        fake.writeBehavior = { bytes ->
            if (fake.written.size - writesBefore == changeoverAt) {
                fake.emitMessage(
                    stateUpdateMessage(plausibleBlob(activeSlot = PresetSlot.B, a = 0, b = newActive.value, c = 2)),
                )
                testScheduler.runCurrent()
            }
            bytes.size
        }
        val aborted = controller.revertActivePreset()
        assertIs<TonexError.ActivePresetChangedDuringRevert>((aborted as TonexResult.Failure).error)
        fake.writeBehavior = { it.size } // clear the induced side effect for the reselect below

        // Preset 0 is now left half-reverted (40 of 109 values restored). Its ORIGINAL snapshot is
        // still exactly what it was on first arrival -- the abort itself never touched the store.
        assertSnapshotValuesEqual(originalValues, store.snapshotFor(activeIndex), "preset 0's snapshot right after the abort")

        // The abort's own external preset-change (0 -> 5, detected mid-replay) is ITSELF preset 5's
        // first arrival this session (applyStateUpdate, unconditionally, per issue #14) -- it queues
        // a first-arrival capture for preset 5 the moment operationMutex frees up, i.e. the instant
        // revertActivePreset() above returns. Drain it before doing anything else below, or it would
        // race the reselect for the same lock (and this test would hang on the reselect's own
        // request never being written until the capture's own request times out).
        testScheduler.runCurrent()
        val fiveValues = alteredSnapshotValues(offset = 6)
        fake.emitMessage(presetDetailsSummaryWithParameters("Preset 5", fiveValues))
        testScheduler.runCurrent()
        assertSnapshotValuesEqual(fiveValues, store.snapshotFor(newActive), "preset 5's own first-arrival snapshot, captured mid-recovery")

        // The recovery a user would naturally reach for: reselect preset 0 and try again. The pedal
        // is currently active on slot B (preset 5); preset 0 is still assigned to slot A -- nothing
        // in this scenario ever unassigned it -- so selectPreset takes its "already assigned to
        // another footswitch slot" branch and merely flips the active slot back to A.
        val selectDeferred = async { controller.selectPreset(activeIndex) }
        testScheduler.runCurrent()
        // The mandatory re-read reports the pedal's current state: active on slot B (preset 5),
        // preset 0 still sitting on slot A untouched.
        fake.emitMessage(stateUpdateMessage(plausibleBlob(activeSlot = PresetSlot.B, a = 0, b = newActive.value, c = 2)))
        testScheduler.runCurrent()
        assertIs<TonexResult.Success<Unit>>(selectDeferred.await())
        val writesBeforeConfirmation = fake.writtenMessages().size

        // The pedal's confirming push: slot A (still holding preset 0, unchanged throughout) is
        // active again -- slot assignments untouched, only the active-slot byte flipped.
        fake.emitMessage(stateUpdateMessage(plausibleBlob(activeSlot = PresetSlot.A, a = 0, b = newActive.value, c = 2)))
        testScheduler.runCurrent()
        assertEquals(activeIndex, controller.activePreset.value)

        // The re-arrival launches exactly one re-read of preset 0's now half-reverted live state --
        // answer it with something that visibly differs from the original snapshot, to prove that
        // if the retention guard were broken, the assertion below would catch it.
        val newWrites = fake.writtenMessages().drop(writesBeforeConfirmation)
        assertEquals(1, newWrites.size, "the re-arrival should trigger exactly one re-read request")
        val halfRevertedLookingValues = alteredSnapshotValues(offset = 4)
        fake.emitMessage(presetDetailsSummaryWithParameters("Preset 0, mid-recovery", halfRevertedLookingValues))
        testScheduler.runCurrent()

        // The core assertion (issue #45/#46): reselecting the preset did NOT overwrite its original,
        // still-good snapshot with the half-reverted state's fresh read.
        assertSnapshotValuesEqual(originalValues, store.snapshotFor(activeIndex), "preset 0's snapshot after reselecting it")

        // And the obvious recovery now genuinely works: a second revert restores the true originals.
        val retryWritesBefore = fake.writtenMessages().size
        val retryResult = controller.revertActivePreset()
        assertIs<TonexResult.Success<Unit>>(retryResult)
        val retryWrites = fake.writtenMessages().drop(retryWritesBefore)
        assertEquals(109, retryWrites.size)
        for ((i, msg) in retryWrites.withIndex()) {
            val decoded = SingleParameterPayloadCodec.decode(msg.payload)
            assertIs<TonexResult.Success<SingleParameterPayload>>(decoded)
            assertEquals(originalValues[i], decoded.value.value, 1e-3f, "write $i should restore the ORIGINAL snapshot")
        }
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
        connectToReady(controller, fake) // normal capture: records a real snapshot under the live session
        // Overwrite it with an out-of-range snapshot, but stamped with the REAL live SessionId (not
        // an unrelated SessionId.create()) -- since revertActivePreset now rejects a snapshot whose
        // sessionId doesn't match the current session (Opus review should-fix #2, issue #46 PR),
        // this test's own injected snapshot must carry a genuine one to still reach the
        // pre-validation logic it's actually testing.
        val liveSessionId = requireNotNull(store.snapshotFor(activeIndex)).sessionId
        val badValues = snapshotValues()
        badValues[2] = 500f // NOISE_GATE_THRESHOLD's range is -100..0
        store.record(PresetSnapshot(activeIndex, liveSessionId, badValues))
        val writesBefore = fake.writtenMessages().size

        val result = controller.revertActivePreset()

        val error = (result as TonexResult.Failure).error
        val outOfRange = assertIs<TonexError.ParameterValueOutOfRange>(error)
        assertEquals(ParameterId(2), outOfRange.id)
        assertEquals(500f, outOfRange.value)
        assertEquals(fake.writtenMessages().size, writesBefore, "zero writes when pre-validation rejects the snapshot")
    }

    // ---- a snapshot from a foreign session is rejected, not replayed (Opus review non-blocking ---
    // ---- gap, issue #46 PR: revert-side depth on top of the capture-side sessionId guard) --------

    @Test
    fun `a snapshot stamped with a foreign session fails NoSnapshotAvailable, zero writes`() = runTest {
        val fake = FakeTonexTransport()
        val store = InMemorySnapshotStore()
        val controller = DefaultTonexController(
            scope = backgroundScope,
            capabilities = FirmwareCapabilities(supportsSingleParameterWrite = true),
            snapshotStore = store,
        )
        connectToReady(controller, fake) // normal capture: records a real snapshot under the live session
        // Overwrite it with an otherwise-valid snapshot stamped with an UNRELATED session -- exactly
        // what a cross-session phantom snapshot (the capture-side race captureSnapshotLocked's own
        // sessionId guard closes) would look like if one ever did land in the store. revertActivePreset
        // must refuse to replay it rather than silently restoring a previous session's values onto
        // this session's preset -- belt-and-braces on top of the capture-side fix, so this guard
        // needs its own test or a future edit could delete it with the suite staying green.
        store.record(PresetSnapshot(activeIndex, SessionId.create(), snapshotValues()))
        val writesBefore = fake.writtenMessages().size

        val result = controller.revertActivePreset()

        val error = (result as TonexResult.Failure).error
        val noSnapshot = assertIs<TonexError.NoSnapshotAvailable>(error)
        assertEquals(activeIndex, noSnapshot.presetIndex)
        assertEquals(fake.writtenMessages().size, writesBefore, "zero writes when the snapshot belongs to a foreign session")
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
