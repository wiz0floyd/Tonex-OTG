package dev.tonexotg.app.ui.screens.parameters

import dev.tonexotg.protocol.ParameterId
import dev.tonexotg.protocol.PresetIndex
import dev.tonexotg.protocol.PresetInfo
import dev.tonexotg.protocol.TonexError
import dev.tonexotg.protocol.TonexEvent
import dev.tonexotg.protocol.TonexResult
import dev.tonexotg.protocol.params.SelfWideningParameterBounds
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioral coverage for [ParameterEditorViewModel] against a [FakeTonexController] — issue
 * #22's own explicit test list: clamping, model-switch disclosure, write-throttling/conflation,
 * numeric-entry round-trip, revert wiring, and first-destructive-write gating.
 *
 * Per [ParameterWriteThrottlerTest]'s KDoc, every test here pumps with
 * [kotlinx.coroutines.test.runCurrent] rather than `advanceUntilIdle()` — the view model's
 * internal state flow and its [ParameterWriteThrottler] workers all run on `backgroundScope`
 * (matching real usage), and `advanceUntilIdle()` is a documented no-op for purely-background
 * work when nothing foreground is pending, which is every case below.
 */
class ParameterEditorViewModelTest {

    private val gain = ParameterId(20) // MODEL_GAIN, RANGE 0..10 — quick tier "Gain"
    private val bass = ParameterId(11) // EQ_BASS, RANGE 0..10 — quick tier "Bass"
    private val virMic1 = ParameterId(27) // VIR_MIC_1, SELECT 0..2, unlabeled -> stepper

    @Test
    fun `slider drag clamps a structurally out-of-range value before it ever reaches the controller`() = runTest {
        val controller = FakeTonexController()
        val vm = ParameterEditorViewModel(controller, backgroundScope)
        runCurrent()

        vm.onRangeDrag(gain, 999f) // MODEL_GAIN's registered range is 0..10
        runCurrent()

        assertEquals(listOf(gain to 10f), controller.setParameterCalls)
        vm.onCleared()
    }

    @Test
    fun `slider drag clamps below the minimum too`() = runTest {
        val controller = FakeTonexController()
        val vm = ParameterEditorViewModel(controller, backgroundScope)
        runCurrent()

        vm.onRangeDrag(gain, -50f)
        runCurrent()

        assertEquals(listOf(gain to 0f), controller.setParameterCalls)
        vm.onCleared()
    }

    @Test
    fun `stepper change clamps an out-of-range index`() = runTest {
        val controller = FakeTonexController()
        val vm = ParameterEditorViewModel(controller, backgroundScope)
        runCurrent()

        vm.onStepperChange(virMic1, 99) // VIR_MIC_1's registered range is 0..2
        runCurrent()

        assertEquals(listOf(virMic1 to 2f), controller.setParameterCalls)
        vm.onCleared()
    }

    @Test
    fun `numeric entry set re-clamps regardless of what the sheet already clamped`() = runTest {
        val controller = FakeTonexController()
        val vm = ParameterEditorViewModel(controller, backgroundScope)
        runCurrent()

        vm.onNumericEntryOpen(gain)
        runCurrent()
        assertEquals(gain, vm.uiState.value.numericEntryTarget?.id)

        vm.onNumericEntrySet(9001f) // structurally impossible for MODEL_GAIN even if the sheet's own clamp were bypassed
        runCurrent()

        assertEquals(listOf(gain to 10f), controller.setParameterCalls)
        assertNull("the sheet must close after Set", vm.uiState.value.numericEntryTarget)
        vm.onCleared()
    }

    @Test
    fun `rapid slider drags conflate through the throttler before the controller ever sees them`() = runTest {
        val controller = FakeTonexController()
        val vm = ParameterEditorViewModel(controller, backgroundScope)
        runCurrent()

        // A fast drag: every intermediate value is submitted before the worker gets a chance to
        // drain any of them (no runCurrent() between submits).
        vm.onRangeDrag(gain, 1f)
        vm.onRangeDrag(gain, 2f)
        vm.onRangeDrag(gain, 3f)
        vm.onRangeDrag(gain, 6.4f)
        runCurrent()

        assertEquals("only the last dragged value should ever reach the controller", listOf(gain to 6.4f), controller.setParameterCalls)
        vm.onCleared()
    }

    @Test
    fun `optimistic override shows the dragged value while its write is still in flight`() = runTest {
        val controller = FakeTonexController()
        val vm = ParameterEditorViewModel(controller, backgroundScope)
        runCurrent()

        val gate = FakeTonexController.SetParameterGate()
        controller.nextSetParameterGate = gate
        vm.onRangeDrag(gain, 8f)
        runCurrent() // runs the worker up to gate.release.await() - the write is in flight, not yet landed
        assertTrue(gate.started.isCompleted)

        // The controller itself does not know about 8f yet, but the UI must already show it
        // (D3's sub-200ms responsiveness) via the optimistic override, not a stale prior value.
        assertTrue(controller.parameterValues.value[gain] != 8f)
        val card = vm.uiState.value.quickTier.first { it.row.id == gain }
        assertEquals(8f, card.row.value)

        gate.release.complete(Unit)
        runCurrent()
        vm.onCleared()
    }

    @Test
    fun `an older throttled write landing does not clobber a newer optimistic override (issue 84)`() = runTest {
        val controller = FakeTonexController()
        val vm = ParameterEditorViewModel(controller, backgroundScope)
        runCurrent()

        // v1's write starts and blocks in flight.
        val gate1 = FakeTonexController.SetParameterGate()
        controller.nextSetParameterGate = gate1
        vm.onRangeDrag(gain, 1f)
        runCurrent()
        assertTrue(gate1.started.isCompleted)

        // While v1 is still in flight, the drag continues past two more values; the throttler
        // conflates them down to just v3, and onRangeDrag's own optimistic override is now v3.
        vm.onRangeDrag(gain, 2f)
        vm.onRangeDrag(gain, 3f)
        runCurrent()

        // Arm a second gate so v3's write also blocks in flight, right after v1's write lands -
        // this is the exact window where the bug showed a one-frame jump back to v1.
        val gate2 = FakeTonexController.SetParameterGate()
        controller.nextSetParameterGate = gate2
        gate1.release.complete(Unit)
        runCurrent()

        assertEquals("v1 has landed on the controller", 1f, controller.parameterValues.value[gain])
        val cardMidTransition = vm.uiState.value.quickTier.first { it.row.id == gain }
        assertEquals(
            "v1 landing must not clobber the newer v3 override with the stale controller value",
            3f,
            cardMidTransition.row.value,
        )

        gate2.release.complete(Unit)
        runCurrent()

        assertEquals(listOf(gain to 1f, gain to 3f), controller.setParameterCalls)
        assertEquals(3f, controller.parameterValues.value[gain])
        vm.onCleared()
    }

    @Test
    fun `model select swaps the disclosed rows to only the newly selected model`() = runTest {
        val controller = FakeTonexController()
        val vm = ParameterEditorViewModel(controller, backgroundScope)
        runCurrent()

        val reverbBefore = vm.uiState.value.categories.filterIsInstance<CategoryUiState.Banked>().first { it.title == "Reverb" }
        // REVERB_MODEL defaults to 0 (Spring 1) -> Spring 1's own ids (39..42).
        assertEquals(listOf(39, 40, 41, 42), reverbBefore.modelRows.map { it.id.index })

        vm.onModelSelect(ParameterCatalog.reverbBank.selectorId, 5) // Plate
        runCurrent()

        val reverbAfter = vm.uiState.value.categories.filterIsInstance<CategoryUiState.Banked>().first { it.title == "Reverb" }
        assertEquals(listOf(59, 60, 61, 62), reverbAfter.modelRows.map { it.id.index })
        assertTrue(
            "no Spring 1 rows may remain once Plate is selected - D3 §2.1 'hidden entirely, not grayed'",
            reverbAfter.modelRows.none { it.id.index in 39..42 },
        )
        vm.onCleared()
    }

    @Test
    fun `model selection is itself an ordinary immediate write, not throttled`() = runTest {
        val controller = FakeTonexController()
        val vm = ParameterEditorViewModel(controller, backgroundScope)
        runCurrent()

        vm.onModelSelect(ParameterCatalog.reverbBank.selectorId, 5)
        runCurrent()

        assertEquals(listOf(ParameterCatalog.reverbBank.selectorId to 5f), controller.setParameterCalls)
        vm.onCleared()
    }

    @Test
    fun `quick tier reverb mix resolves to the active reverb models own mix parameter`() = runTest {
        val controller = FakeTonexController()
        controller.seedParameterValue(ParameterCatalog.reverbBank.selectorId, 5f) // Plate
        controller.seedParameterValue(ParameterId(62), 42f) // REVERB_PLATE_MIX
        val vm = ParameterEditorViewModel(controller, backgroundScope)
        runCurrent()

        val reverbMixCard = vm.uiState.value.quickTier.first { it.row.label == "Reverb Mix" }
        assertEquals(ParameterId(62), reverbMixCard.row.id)
        assertEquals(42f, reverbMixCard.row.value)
        assertNotNull(reverbMixCard.resolvedCaption)
        vm.onCleared()
    }

    @Test
    fun `first destructive write event surfaces the notice, and dismiss clears it`() = runTest {
        val controller = FakeTonexController()
        val vm = ParameterEditorViewModel(controller, backgroundScope)
        runCurrent()
        assertFalse(vm.uiState.value.firstWriteWarningVisible)

        controller.emitEvent(TonexEvent.FirstDestructiveWrite(PresetIndex(0)))
        runCurrent()
        assertTrue(vm.uiState.value.firstWriteWarningVisible)

        vm.onFirstWriteWarningDismiss()
        runCurrent()
        assertFalse(vm.uiState.value.firstWriteWarningVisible)
        vm.onCleared()
    }

    @Test
    fun `external preset change cancels an in-flight drag - a buffered value never reaches the controller`() = runTest {
        val controller = FakeTonexController()
        val vm = ParameterEditorViewModel(controller, backgroundScope)
        runCurrent()

        // Hold bass's first write "in flight" so the second, still-buffered value is genuinely
        // pending (not yet dispatched to a worker) when the external change arrives - the same
        // deterministic pattern ParameterWriteThrottlerTest's own "cancelAll" test uses.
        val gate = FakeTonexController.SetParameterGate()
        controller.nextSetParameterGate = gate
        vm.onRangeDrag(bass, 1f)
        runCurrent()
        assertTrue(gate.started.isCompleted)

        vm.onRangeDrag(bass, 7f) // buffered - the worker hasn't picked it up yet
        controller.emitEvent(TonexEvent.ExternalPresetChange(PresetIndex(1)))
        runCurrent() // processes the event -> cancelAll() tears down bass's worker, discarding the buffered 7f

        gate.release.complete(Unit) // release the original (now-orphaned) write; nothing is left to pick up 7f
        runCurrent()

        assertEquals(
            "D3 §6.3: a stale in-flight drag must not send a write for the preset it left behind",
            listOf(bass to 1f),
            controller.setParameterCalls,
        )
        vm.onCleared()
    }

    @Test
    fun `external preset change closes an open numeric-entry sheet with no write`() = runTest {
        val controller = FakeTonexController()
        val vm = ParameterEditorViewModel(controller, backgroundScope)
        runCurrent()

        vm.onNumericEntryOpen(gain)
        runCurrent()
        assertNotNull(vm.uiState.value.numericEntryTarget)

        controller.emitEvent(TonexEvent.ExternalPresetChange(PresetIndex(1)))
        runCurrent()

        assertNull(vm.uiState.value.numericEntryTarget)
        assertTrue(controller.setParameterCalls.none { it.first == gain })
        vm.onCleared()
    }

    @Test
    fun `external preset change surfaces a snackbar message naming the new preset`() = runTest {
        val controller = FakeTonexController()
        controller.seedPresets(listOf(PresetInfo(PresetIndex(1), "Lead Tone")))
        val vm = ParameterEditorViewModel(controller, backgroundScope)
        runCurrent()

        controller.emitEvent(TonexEvent.ExternalPresetChange(PresetIndex(1)))
        runCurrent()

        val message = vm.uiState.value.externalPresetChangeMessage
        assertNotNull(message)
        assertTrue(message!!.contains("Lead Tone"))

        vm.onExternalPresetChangeMessageShown()
        runCurrent()
        assertNull(vm.uiState.value.externalPresetChangeMessage)
        vm.onCleared()
    }

    @Test
    fun `revert requested then confirmed calls the controller and clears the confirmation dialog`() = runTest {
        val controller = FakeTonexController()
        val vm = ParameterEditorViewModel(controller, backgroundScope)
        runCurrent()

        vm.onRevertRequested()
        runCurrent()
        assertTrue(vm.uiState.value.revertConfirmVisible)

        vm.onRevertConfirmed()
        runCurrent()

        assertEquals(1, controller.revertCallCount)
        assertFalse(vm.uiState.value.revertConfirmVisible)
        vm.onCleared()
    }

    @Test
    fun `revert cancelled never calls the controller`() = runTest {
        val controller = FakeTonexController()
        val vm = ParameterEditorViewModel(controller, backgroundScope)
        runCurrent()

        vm.onRevertRequested()
        vm.onRevertCancelled()
        runCurrent()

        assertEquals(0, controller.revertCallCount)
        assertFalse(vm.uiState.value.revertConfirmVisible)
        vm.onCleared()
    }

    @Test
    fun `revert failure surfaces a readable error rather than silently pretending success`() = runTest {
        val controller = FakeTonexController()
        controller.revertResult = TonexResult.Failure(TonexError.NoSnapshotAvailable(PresetIndex(0)))
        val vm = ParameterEditorViewModel(controller, backgroundScope)
        runCurrent()

        vm.onRevertConfirmed()
        runCurrent()

        assertNotNull(vm.uiState.value.revertError)
        vm.onRevertErrorDismissed()
        runCurrent()
        assertNull(vm.uiState.value.revertError)
        vm.onCleared()
    }

    // ---- self-widening effective bounds (issue #80) --------------------------------------------

    @Test
    fun `a genuinely out-of-range SELECT read is displayed as its true raw value, not silently coerced`() = runTest {
        // MODULATION_CHORUS_TS (index 67), a non-allowlisted unlabeled SELECT with static max 17,
        // in the default-selected Chorus model's rows (ParameterCatalog.modulationBank defaults
        // to model 0). A value of 25 here can only mean a decode bug -- the old bug silently
        // displayed this as a falsely-clamped 17; the fix must show 25 verbatim.
        val controller = FakeTonexController()
        controller.seedParameterValue(ParameterId(67), 25f)
        val vm = ParameterEditorViewModel(controller, backgroundScope)
        runCurrent()

        val modulation = vm.uiState.value.categories.filterIsInstance<CategoryUiState.Banked>().first { it.title == "Modulation" }
        val row = modulation.modelRows.filterIsInstance<ParameterRow.Stepper>().first { it.id == ParameterId(67) }

        assertEquals("the displayed value must be the true raw read, never silently clamped", 25, row.value)
        assertTrue("range must widen to keep the stepper consistent with the value it displays", 25 in row.range)
        vm.onCleared()
    }

    @Test
    fun `a widened self-widening bounds instance raises both the UI range and the write-clamp for VIR_CABINET_MODEL`() = runTest {
        val virCabinetModel = ParameterId(25)
        val effectiveBounds = SelfWideningParameterBounds()
        effectiveBounds.observeRead(virCabinetModel, 42f) // simulates the same widening a genuine controller read would perform

        val controller = FakeTonexController()
        controller.seedParameterValue(ParameterId(24), 1f) // CABINET_TYPE = VIR, discloses ids 25-33
        controller.seedParameterValue(virCabinetModel, 42f) // the same value the (fake) controller "observed"
        val vm = ParameterEditorViewModel(controller, backgroundScope, effectiveBounds)
        runCurrent()

        val cabinet = vm.uiState.value.categories.filterIsInstance<CategoryUiState.Banked>().first { it.title == "Cabinet" }
        val row = cabinet.modelRows.filterIsInstance<ParameterRow.Stepper>().first { it.id == virCabinetModel }
        assertEquals(42, row.value)
        assertEquals("range must reach exactly the widened ceiling, not the static max 38", 42, row.range.last)

        // The write-clamp (onStepperChange -> onDiscreteChange) must track the same widened ceiling.
        vm.onStepperChange(virCabinetModel, 42)
        runCurrent()
        assertEquals(listOf(virCabinetModel to 42f), controller.setParameterCalls)

        vm.onStepperChange(virCabinetModel, 43) // one above the widened ceiling
        runCurrent()
        assertEquals(
            "a value above the widened ceiling must still be clamped, exactly at that ceiling",
            listOf(virCabinetModel to 42f, virCabinetModel to 42f),
            controller.setParameterCalls,
        )
        vm.onCleared()
    }

    @Test
    fun `with the default STATIC bounds, VIR_CABINET_MODEL's write-clamp and UI range stay at the static max`() = runTest {
        val virCabinetModel = ParameterId(25)
        val controller = FakeTonexController()
        controller.seedParameterValue(ParameterId(24), 1f) // CABINET_TYPE = VIR
        val vm = ParameterEditorViewModel(controller, backgroundScope) // no effectiveBounds supplied -> STATIC
        runCurrent()

        val cabinet = vm.uiState.value.categories.filterIsInstance<CategoryUiState.Banked>().first { it.title == "Cabinet" }
        val row = cabinet.modelRows.filterIsInstance<ParameterRow.Stepper>().first { it.id == virCabinetModel }
        assertEquals("unchanged behaviour with the default bounds: range still ends at the static max 38", 38, row.range.last)

        vm.onStepperChange(virCabinetModel, 50)
        runCurrent()
        assertEquals(listOf(virCabinetModel to 38f), controller.setParameterCalls)
        vm.onCleared()
    }

    // ---- silent parameter-write failure (issue #22 follow-up bug fix) --------------------------

    @Test
    fun `a failed parameter write surfaces a readable error instead of silently reverting with no feedback`() = runTest {
        val controller = FakeTonexController()
        controller.nextSetParameterResult = TonexResult.Failure(TonexError.TransportFailure(RuntimeException("USB disconnected")))
        val vm = ParameterEditorViewModel(controller, backgroundScope)
        runCurrent()

        vm.onNumericEntryOpen(gain)
        runCurrent()
        vm.onNumericEntrySet(9f) // a discrete, non-throttled write path; MODEL_GAIN's registry default is 5f

        runCurrent()

        assertNotNull("a failed write must surface an error, not fail silently", vm.uiState.value.writeError)
        // The optimistic override must still be cleared on failure - it must fall back to the
        // registry default (5f), not keep pretending the rejected write of 9f actually landed.
        val gainCard = vm.uiState.value.quickTier.first { it.row.id == gain }
        assertEquals(5f, gainCard.row.value)

        vm.onWriteErrorDismissed()
        runCurrent()
        assertNull(vm.uiState.value.writeError)
        vm.onCleared()
    }

    @Test
    fun `a throttled slider write that succeeds after an earlier failure on the same parameter clears the write error`() = runTest {
        val controller = FakeTonexController()
        controller.nextSetParameterResult = TonexResult.Failure(TonexError.TransportFailure(RuntimeException("USB disconnected")))
        val vm = ParameterEditorViewModel(controller, backgroundScope)
        runCurrent()

        // First conflated write to Gain fails.
        vm.onRangeDrag(gain, 3f)
        runCurrent()
        assertNotNull("the failed drag must surface an error", vm.uiState.value.writeError)

        // A later drag on the SAME parameter succeeds (nextSetParameterResult was consumed by the
        // first call, so this one hits the fake's default Success) - this is the throttler's
        // normal case (conflated writes to one slider), not an edge case, per the reported bug
        // being specifically about knobs/sliders.
        vm.onRangeDrag(gain, 7f)
        runCurrent()

        assertNull(
            "a later successful write for the same parameter must clear its own earlier failure",
            vm.uiState.value.writeError,
        )
        vm.onCleared()
    }

    @Test
    fun `a successful write for one parameter does not clobber a still-unresolved failure for a different parameter`() = runTest {
        val controller = FakeTonexController()
        val vm = ParameterEditorViewModel(controller, backgroundScope)
        runCurrent()

        // Gain's write fails and is surfaced.
        controller.nextSetParameterResult = TonexResult.Failure(TonexError.TransportFailure(RuntimeException("USB disconnected")))
        vm.onRangeDrag(gain, 3f)
        runCurrent()
        assertNotNull(vm.uiState.value.writeError)

        // Bass's write succeeds - single error slot, but it is tagged with Gain's id, so Bass
        // succeeding must not silently wipe out Gain's still-live failure dialog.
        vm.onRangeDrag(bass, 4f)
        runCurrent()

        assertNotNull(
            "an unrelated parameter's success must not clear a still-unresolved failure for a different parameter",
            vm.uiState.value.writeError,
        )
        vm.onCleared()
    }

    @Test
    fun `dragging one of the 6 issue-83 global range parameters now reaches the controller (PR #100 added its write path)`() = runTest {
        val controller = FakeTonexController()
        val vm = ParameterEditorViewModel(controller, backgroundScope)
        runCurrent()

        val tuningReference = ParameterId(114) // TUNING_REFERENCE - GLOBAL scope, :protocol write path since PR #100
        vm.onRangeDrag(tuningReference, 450f)
        runCurrent()

        assertEquals(listOf(tuningReference to 450f), controller.setParameterCalls)
        assertNull(vm.uiState.value.writeError)
        vm.onCleared()
    }

    @Test
    fun `toggling one of the 6 issue-83 global switch parameters now reaches the controller (PR #100 added its write path)`() = runTest {
        val controller = FakeTonexController()
        val vm = ParameterEditorViewModel(controller, backgroundScope)
        runCurrent()

        val cabsimBypass = ParameterId(112) // CABSIM_BYPASS - GLOBAL scope, :protocol write path since PR #100
        vm.onSwitchToggle(cabsimBypass, true)
        runCurrent()

        assertEquals(listOf(cabsimBypass to 1f), controller.setParameterCalls)
        assertNull(vm.uiState.value.writeError)
        vm.onCleared()
    }

    @Test
    fun `master volume, the one GLOBAL parameter protocol can write, is not blocked as unsupported`() = runTest {
        val controller = FakeTonexController()
        val vm = ParameterEditorViewModel(controller, backgroundScope)
        runCurrent()

        vm.onRangeDrag(ParameterCatalog.masterVolumeId, -6f)
        runCurrent()

        assertEquals(listOf(ParameterCatalog.masterVolumeId to -6f), controller.setParameterCalls)
        assertNull(vm.uiState.value.writeError)
        vm.onCleared()
    }

    // ---- issue #104: pedal-initiated changes must not fight the user's own drag -----------------

    @Test
    fun `a pedal-initiated change to the parameter being dragged does not move the thumb`() = runTest {
        val controller = FakeTonexController()
        val vm = ParameterEditorViewModel(controller, backgroundScope)
        runCurrent()

        vm.onRangeDrag(gain, 7f)
        runCurrent() // the mid-drag throttled write completes and clears its own override

        // The pedal reports its own knob moving for the SAME parameter, mid-gesture.
        controller.seedParameterValue(gain, 1f)
        runCurrent()

        assertEquals(7f, gainValue(vm), 1e-4f)
        vm.onCleared()
    }

    @Test
    fun `after the drag ends, the parameter follows the controller again`() = runTest {
        val controller = FakeTonexController()
        val vm = ParameterEditorViewModel(controller, backgroundScope)
        runCurrent()

        vm.onRangeDrag(gain, 7f)
        runCurrent()
        vm.onRangeDragEnd(gain)
        controller.seedParameterValue(gain, 1f)
        runCurrent()

        assertEquals(1f, gainValue(vm), 1e-4f)
        vm.onCleared()
    }

    @Test
    fun `a pedal-initiated change to a different parameter lands immediately during a drag`() = runTest {
        val controller = FakeTonexController()
        val vm = ParameterEditorViewModel(controller, backgroundScope)
        runCurrent()

        vm.onRangeDrag(gain, 7f)
        runCurrent()
        controller.seedParameterValue(bass, 9f)
        runCurrent()

        // Suppression is per-id and per-gesture: this is the whole feature, not an exception to it.
        assertEquals(9f, rowValue(vm, bass), 1e-4f)
        assertEquals(7f, gainValue(vm), 1e-4f)
        vm.onCleared()
    }

    @Test
    fun `releasing the slider does not snap back to the last completed write while one is in flight`() = runTest {
        val controller = FakeTonexController()
        val vm = ParameterEditorViewModel(controller, backgroundScope)
        runCurrent()

        vm.onRangeDrag(gain, 4f)
        runCurrent() // 4f lands on the controller
        controller.nextSetParameterGate = FakeTonexController.SetParameterGate() // hold the final write open
        vm.onRangeDrag(gain, 9f)
        runCurrent()

        vm.onRangeDragEnd(gain)
        runCurrent()

        // The final write has not completed, so the override must survive: dropping it here would
        // show 4f for a frame and then jump to 9f -- PR #102's bug, reintroduced.
        assertEquals(9f, gainValue(vm), 1e-4f)
        vm.onCleared()
    }

    @Test
    fun `a drag whose final write already landed does not leave the control pinned`() = runTest {
        val controller = FakeTonexController()
        val vm = ParameterEditorViewModel(controller, backgroundScope)
        runCurrent()

        vm.onRangeDrag(gain, 9f)
        runCurrent() // the write completes DURING the gesture, so nothing is in flight at release
        vm.onRangeDragEnd(gain)
        runCurrent()

        // A stale override here would mask every later pedal-initiated change for the session.
        controller.seedParameterValue(gain, 2f)
        runCurrent()

        assertEquals(2f, gainValue(vm), 1e-4f)
        vm.onCleared()
    }

    @Test
    fun `onRangeDragEnd is idempotent - it doubles as the row's disposal hook`() = runTest {
        val controller = FakeTonexController()
        val vm = ParameterEditorViewModel(controller, backgroundScope)
        runCurrent()

        // ParameterEditorScreen fires this from DisposableEffect.onDispose for rows that were never
        // dragged; it must be harmless, and it must un-stick a gesture disposal interrupted.
        vm.onRangeDragEnd(gain)
        vm.onRangeDragEnd(gain)
        runCurrent()
        controller.seedParameterValue(gain, 6f)
        runCurrent()

        assertEquals(6f, gainValue(vm), 1e-4f)
        vm.onCleared()
    }

    private fun gainValue(vm: ParameterEditorViewModel): Float = rowValue(vm, gain)

    private fun rowValue(vm: ParameterEditorViewModel, id: ParameterId): Float =
        vm.uiState.value.quickTier.first { it.row.id == id }.row.value
}
