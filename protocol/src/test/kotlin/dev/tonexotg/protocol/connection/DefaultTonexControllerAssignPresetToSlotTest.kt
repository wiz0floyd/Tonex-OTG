@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.tonexotg.protocol.connection

import dev.tonexotg.protocol.ConnectionState
import dev.tonexotg.protocol.PresetIndex
import dev.tonexotg.protocol.PresetSlot
import dev.tonexotg.protocol.TonexError
import dev.tonexotg.protocol.TonexEvent
import dev.tonexotg.protocol.TonexResult
import dev.tonexotg.protocol.message.FirmwareCapabilities
import dev.tonexotg.protocol.state.StateBlobOffsets
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Issue #85: `assignPresetToSlot`'s move/swap semantics — "presets can only occupy one assignment
 * at a time," enforced by moving the preset (swapping the two affected slots' bytes) rather than
 * refusing the write. Complements [DefaultTonexControllerSelectPresetTest] (the sibling
 * whole-device write path this one shares its mandatory-re-read discipline with) and
 * [DefaultTonexControllerFootswitchRestoreTest] (the existing consumer of the
 * [dev.tonexotg.protocol.state.StateBlobPatcher.restoreSlotAssignments] three-slot write this
 * story's swap case reuses).
 */
class DefaultTonexControllerAssignPresetToSlotTest {

    // ---- swap case: target already held by a DIFFERENT slot ---------------------------------

    @Test
    fun `preset already on another slot - swaps the two slots' bytes, third slot and active-slot byte untouched`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(scope = backgroundScope, capabilities = FirmwareCapabilities.NONE_CONFIRMED)
        // Active slot is C, deliberately NOT one of the two slots this call touches (A, B) - proves
        // the swap leaves an uninvolved active preset alone.
        connectToReady(controller, fake, activeSlot = PresetSlot.C, a = 5, b = 10, c = 15)

        // preset 10 currently sits on slot B; assign it to slot A (which currently holds 5).
        val rereadBlob = plausibleBlob(activeSlot = PresetSlot.C, a = 5, b = 10, c = 15)
        val assignDeferred = async { controller.assignPresetToSlot(PresetSlot.A, PresetIndex(10)) }
        testScheduler.runCurrent()
        fake.emitMessage(stateUpdateMessage(rereadBlob))
        testScheduler.runCurrent()
        assignDeferred.await().let { assertIs<TonexResult.Success<Unit>>(it) }

        val written = fake.writtenMessages().last()
        assertEquals(rereadBlob.size, written.payload.size)
        val offsetA = rereadBlob.size - StateBlobOffsets.END_SLOT_A_PRESET
        val offsetB = rereadBlob.size - StateBlobOffsets.END_SLOT_B_PRESET
        val offsetC = rereadBlob.size - StateBlobOffsets.END_SLOT_C_PRESET
        val offsetActive = rereadBlob.size - StateBlobOffsets.END_CURRENT_SLOT

        // Diff the WHOLE array, not just the touched offsets.
        for (i in rereadBlob.indices) {
            if (i != offsetA && i != offsetB) {
                assertEquals(rereadBlob[i], written.payload[i], "byte $i must be unchanged from the re-read blob")
            }
        }
        assertEquals(10.toByte(), written.payload[offsetA], "slot A now holds the moved preset (10)")
        assertEquals(5.toByte(), written.payload[offsetB], "slot B inherits what slot A used to hold (5) - the swap")
        assertEquals(15.toByte(), written.payload[offsetC], "slot C is the untouched third slot, unchanged")
        assertEquals(PresetSlot.C.ordinal.toByte(), written.payload[offsetActive], "active-slot byte is never touched by this write")
    }

    @Test
    fun `swapping the ACTIVE slot's own assignment changes activePreset and is self-initiated`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(scope = backgroundScope, capabilities = FirmwareCapabilities.NONE_CONFIRMED)
        // Active slot is A this time - the swap's target slot IS the active slot.
        connectToReady(controller, fake, activeSlot = PresetSlot.A, a = 5, b = 10, c = 15)

        var externalChangeSeen = false
        val eventsJob = launch(start = CoroutineStart.UNDISPATCHED) {
            controller.events.collect { if (it is TonexEvent.ExternalPresetChange) externalChangeSeen = true }
        }

        val rereadBlob = plausibleBlob(activeSlot = PresetSlot.A, a = 5, b = 10, c = 15)
        val assignDeferred = async { controller.assignPresetToSlot(PresetSlot.A, PresetIndex(10)) } // held by B
        testScheduler.runCurrent()
        fake.emitMessage(stateUpdateMessage(rereadBlob))
        testScheduler.runCurrent()
        assignDeferred.await().let { assertIs<TonexResult.Success<Unit>>(it) }

        // The pedal's confirming push, reporting the swap having landed (A=10, B=5).
        fake.emitMessage(stateUpdateMessage(plausibleBlob(activeSlot = PresetSlot.A, a = 10, b = 5, c = 15)))
        testScheduler.runCurrent()

        assertEquals(PresetIndex(10), controller.activePreset.value, "slot A is active and now holds preset 10")
        assertTrue(!externalChangeSeen, "the caller's own assignPresetToSlot must not be reported as an external change")
        eventsJob.cancel()
    }

    // ---- the swap's SOURCE slot (not the target) is the active one - the exact bug an Opus review
    // caught pre-merge: selfInitiatedPreset must be armed with the SOURCE slot's new value
    // (targetCurrent), not [preset], or the latch either fires a spurious ExternalPresetChange or,
    // worse, is left stranded (never armed with the right value to match, so applyStateUpdate's
    // `selfInitiatedPreset == idx` check misses, the latch never clears at that check site, and a
    // LATER genuine external change to the same preset is silently swallowed - issue #86's failure
    // mode). Both halves matter: half 1 alone (no spurious event on the confirming push) still
    // passes even with a stranded, wrongly-valued latch that happens not to collide this once.
    @Test
    fun `swap where the ACTIVE slot is the SOURCE (not target) - no spurious event, and a later external change still fires`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(scope = backgroundScope, capabilities = FirmwareCapabilities.NONE_CONFIRMED)
        // Active slot is B, which holds preset 10 - the SOURCE slot for this call, not the target (A).
        connectToReady(controller, fake, activeSlot = PresetSlot.B, a = 5, b = 10, c = 15)
        assertEquals(PresetIndex(10), controller.activePreset.value)

        val externalChanges = mutableListOf<PresetIndex>()
        val eventsJob = launch(start = CoroutineStart.UNDISPATCHED) {
            controller.events.collect { if (it is TonexEvent.ExternalPresetChange) externalChanges += it.newIndex }
        }

        // Move preset 10 (currently on B, the active slot) onto A. B (the source, still active)
        // inherits A's old value (5) - the active preset changes from 10 to 5, self-initiated.
        val rereadBlob = plausibleBlob(activeSlot = PresetSlot.B, a = 5, b = 10, c = 15)
        val assignDeferred = async { controller.assignPresetToSlot(PresetSlot.A, PresetIndex(10)) }
        testScheduler.runCurrent()
        fake.emitMessage(stateUpdateMessage(rereadBlob))
        testScheduler.runCurrent()
        assignDeferred.await().let { assertIs<TonexResult.Success<Unit>>(it) }

        // The pedal's confirming push, reporting the swap having landed (A=10, B=5, active still B).
        fake.emitMessage(stateUpdateMessage(plausibleBlob(activeSlot = PresetSlot.B, a = 10, b = 5, c = 15)))
        testScheduler.runCurrent()

        assertEquals(PresetIndex(5), controller.activePreset.value, "slot B is active and now holds preset 5")
        assertEquals(
            emptyList<PresetIndex>(),
            externalChanges,
            "half 1: the caller's own move must not be reported as an external change",
        )

        // Half 2: a LATER genuine external change (footswitch press B -> C) must still fire. If the
        // latch had been armed with the wrong value (preset=10 instead of the source slot's actual
        // new value, 5) it would never have been consumed by applyStateUpdate's `== idx` check
        // above, and would still be stranded here - silently swallowing this genuine event too.
        fake.emitMessage(stateUpdateMessage(plausibleBlob(activeSlot = PresetSlot.C, a = 10, b = 5, c = 15)))
        testScheduler.runCurrent()

        assertEquals(
            listOf(PresetIndex(15)),
            externalChanges,
            "half 2: a later genuine external change must still be reported - proves the latch was consumed, not stranded",
        )
        eventsJob.cancel()
    }

    // ---- fresh-assign case: preset not on any slot -------------------------------------------

    @Test
    fun `preset not on any slot - exactly one byte changes, everything else byte-identical`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(scope = backgroundScope, capabilities = FirmwareCapabilities.NONE_CONFIRMED)
        connectToReady(controller, fake, activeSlot = PresetSlot.A, a = 0, b = 1, c = 2)

        val rereadBlob = plausibleBlob(activeSlot = PresetSlot.A, a = 0, b = 1, c = 2)
        val assignDeferred = async { controller.assignPresetToSlot(PresetSlot.B, PresetIndex(7)) } // not held anywhere
        testScheduler.runCurrent()
        fake.emitMessage(stateUpdateMessage(rereadBlob))
        testScheduler.runCurrent()
        assignDeferred.await().let { assertIs<TonexResult.Success<Unit>>(it) }

        val written = fake.writtenMessages().last()
        val changedOffset = rereadBlob.size - StateBlobOffsets.END_SLOT_B_PRESET
        for (i in rereadBlob.indices) {
            if (i != changedOffset) assertEquals(rereadBlob[i], written.payload[i], "byte $i must be unchanged")
        }
        assertEquals(7.toByte(), written.payload[changedOffset])
    }

    // ---- already-in-target case: no-op ---------------------------------------------------------

    @Test
    fun `preset already assigned to the target slot - Success, zero writes after the re-read`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(scope = backgroundScope, capabilities = FirmwareCapabilities.NONE_CONFIRMED)
        connectToReady(controller, fake, activeSlot = PresetSlot.A, a = 0, b = 1, c = 2)
        val writesBefore = fake.writtenMessages().size

        val assignDeferred = async { controller.assignPresetToSlot(PresetSlot.B, PresetIndex(1)) } // already on B
        testScheduler.runCurrent()
        fake.emitMessage(stateUpdateMessage(plausibleBlob(activeSlot = PresetSlot.A, a = 0, b = 1, c = 2)))
        testScheduler.runCurrent()

        val result = assignDeferred.await()
        assertIs<TonexResult.Success<Unit>>(result)
        val newWrites = fake.writtenMessages().drop(writesBefore)
        assertEquals(1, newWrites.size, "only the RequestState re-read - no state write when already assigned")
    }

    // ---- slotAssignments StateFlow reflects the swap ------------------------------------------

    @Test
    fun `slotAssignments reflects the swap once the pedal's confirming StateUpdate arrives`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(scope = backgroundScope, capabilities = FirmwareCapabilities.NONE_CONFIRMED)
        connectToReady(controller, fake, activeSlot = PresetSlot.C, a = 5, b = 10, c = 15)
        assertEquals(
            mapOf(PresetSlot.A to PresetIndex(5), PresetSlot.B to PresetIndex(10), PresetSlot.C to PresetIndex(15)),
            controller.slotAssignments.value,
        )

        val rereadBlob = plausibleBlob(activeSlot = PresetSlot.C, a = 5, b = 10, c = 15)
        val assignDeferred = async { controller.assignPresetToSlot(PresetSlot.A, PresetIndex(10)) }
        testScheduler.runCurrent()
        fake.emitMessage(stateUpdateMessage(rereadBlob))
        testScheduler.runCurrent()
        assignDeferred.await().let { assertIs<TonexResult.Success<Unit>>(it) }

        // The pedal's own confirming push, reflecting the swap having landed.
        fake.emitMessage(stateUpdateMessage(plausibleBlob(activeSlot = PresetSlot.C, a = 10, b = 5, c = 15)))
        testScheduler.runCurrent()

        assertEquals(
            mapOf(PresetSlot.A to PresetIndex(10), PresetSlot.B to PresetIndex(5), PresetSlot.C to PresetIndex(15)),
            controller.slotAssignments.value,
        )
    }

    // ---- rejected before Ready -------------------------------------------------------------------

    @Test
    fun `assignPresetToSlot before Ready is rejected with ProtocolStateViolation naming the actual state`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(scope = backgroundScope, capabilities = FirmwareCapabilities.NONE_CONFIRMED)

        val result = controller.assignPresetToSlot(PresetSlot.A, PresetIndex(0))

        val error = (result as TonexResult.Failure).error
        assertIs<TonexError.ProtocolStateViolation>(error)
        assertEquals(ConnectionState.Idle, (error as TonexError.ProtocolStateViolation).state)
    }

    // ---- mandatory re-read never reused / a failed re-read writes nothing -----------------------

    @Test
    fun `a timed-out re-read fails assignPresetToSlot and writes no state update`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(scope = backgroundScope, capabilities = FirmwareCapabilities.NONE_CONFIRMED)
        connectToReady(controller, fake)
        val writesBefore = fake.writtenMessages().size

        val assignDeferred = async { controller.assignPresetToSlot(PresetSlot.B, PresetIndex(5)) }
        testScheduler.runCurrent() // re-read request written, awaiting response
        testScheduler.advanceTimeBy(ConnectionTimeouts.DEFAULT.stateReadMillis + 1)
        testScheduler.runCurrent()

        val result = assignDeferred.await()
        val error = (result as TonexResult.Failure).error
        assertIs<TonexError.Timeout>(error)
        assertEquals("state-read", (error as TonexError.Timeout).operation)

        val newWrites = fake.writtenMessages().drop(writesBefore)
        assertEquals(1, newWrites.size, "only the RequestState re-read should have been written")
    }

    // ---- helpers --------------------------------------------------------------------------------

    private suspend fun TestScope.connectToReady(
        controller: DefaultTonexController,
        fake: FakeTonexTransport,
        activeSlot: PresetSlot = PresetSlot.A,
        a: Int = 0,
        b: Int = 1,
        c: Int = 2,
    ) {
        val connectDeferred = async { controller.connect(fake) }
        driveToReady(fake, activeSlot = activeSlot, a = a, b = b, c = c)
        val result = connectDeferred.await()
        assertIs<TonexResult.Success<Unit>>(result)
    }
}
