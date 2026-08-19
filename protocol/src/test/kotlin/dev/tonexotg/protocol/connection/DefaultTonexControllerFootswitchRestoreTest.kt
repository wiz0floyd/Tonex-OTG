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
 * Issue #36: [DefaultTonexController.restoreFootswitches] — captures the three footswitch slot
 * assignments (A/B/C) at handshake, before any write, and restores them on explicit request.
 * Complements [DefaultTonexControllerSelectPresetTest] (the write this story exists to undo the
 * side effect of).
 */
class DefaultTonexControllerFootswitchRestoreTest {

    // ---- capture at handshake, byte-exact restore --------------------------------------------

    @Test
    fun `a destructive selectPreset that reassigns slot A, then restoreFootswitches, returns exactly the captured bytes`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(scope = backgroundScope, capabilities = FirmwareCapabilities.NONE_CONFIRMED)
        connectToReady(controller, fake, activeSlot = PresetSlot.A, a = 0, b = 1, c = 2)

        // selectPreset(15): not held by any slot -> assigned to the active slot (A), clobbering the
        // A/B/C configuration captured at handshake -- exactly the destructive side effect issue
        // #36 exists to undo.
        val rereadForSelect = plausibleBlob(activeSlot = PresetSlot.A, a = 0, b = 1, c = 2)
        val selectDeferred = async { controller.selectPreset(PresetIndex(15)) }
        testScheduler.runCurrent()
        fake.emitMessage(stateUpdateMessage(rereadForSelect))
        testScheduler.runCurrent()
        selectDeferred.await().let { assertIs<TonexResult.Success<Unit>>(it) }

        // The pedal's confirming push, reporting the NEW state after selectPreset's write landed --
        // this is what actually moves _activePreset (0 -> 15) and launches S9b's own re-snapshot
        // capture read on a separate coroutine, competing for operationMutex with whatever runs
        // next. Answer that capture read (rather than leaving it to time out) so it releases the
        // mutex deterministically before restoreFootswitches starts; its own success/failure is
        // irrelevant to this test.
        fake.emitMessage(stateUpdateMessage(plausibleBlob(activeSlot = PresetSlot.A, a = 15, b = 1, c = 2)))
        testScheduler.runCurrent()
        fake.emitMessage(presetDetailsSummaryWithParameters("Preset 15", snapshotValues()))
        testScheduler.runCurrent()
        assertEquals(PresetIndex(15), controller.activePreset.value)

        // Now restore: the pedal currently reports A=15 (clobbered), B=1, C=2, active=A.
        val rereadForRestore = plausibleBlob(activeSlot = PresetSlot.A, a = 15, b = 1, c = 2)
        val restoreDeferred = async { controller.restoreFootswitches() }
        testScheduler.runCurrent()
        fake.emitMessage(stateUpdateMessage(rereadForRestore))
        testScheduler.runCurrent()
        restoreDeferred.await().let { assertIs<TonexResult.Success<Unit>>(it) }

        val written = fake.writtenMessages().last()
        assertEquals(rereadForRestore.size, written.payload.size)
        val changedOffset = rereadForRestore.size - StateBlobOffsets.END_SLOT_A_PRESET
        for (i in rereadForRestore.indices) {
            if (i != changedOffset) {
                assertEquals(rereadForRestore[i], written.payload[i], "byte $i must be unchanged from the re-read blob")
            }
        }
        // Slot A restored to its handshake-captured value (0), B and C written back unchanged
        // (restoreSlotAssignments always writes all three, unconditionally), active slot untouched.
        assertEquals(0.toByte(), written.payload[rereadForRestore.size - StateBlobOffsets.END_SLOT_A_PRESET])
        assertEquals(1.toByte(), written.payload[rereadForRestore.size - StateBlobOffsets.END_SLOT_B_PRESET])
        assertEquals(2.toByte(), written.payload[rereadForRestore.size - StateBlobOffsets.END_SLOT_C_PRESET])
        assertEquals(PresetSlot.A.ordinal.toByte(), written.payload[rereadForRestore.size - StateBlobOffsets.END_CURRENT_SLOT])
    }

    @Test
    fun `restoreFootswitches never touches the active-slot byte even when it changes the active preset`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(scope = backgroundScope, capabilities = FirmwareCapabilities.NONE_CONFIRMED)
        connectToReady(controller, fake, activeSlot = PresetSlot.C, a = 4, b = 5, c = 6)

        // Nothing has changed since handshake; restore is still available and idempotent-in-content
        // (this function always writes, per its own KDoc "no no-op short-circuit").
        val rereadBlob = plausibleBlob(activeSlot = PresetSlot.C, a = 4, b = 5, c = 6)
        val restoreDeferred = async { controller.restoreFootswitches() }
        testScheduler.runCurrent()
        fake.emitMessage(stateUpdateMessage(rereadBlob))
        testScheduler.runCurrent()
        restoreDeferred.await().let { assertIs<TonexResult.Success<Unit>>(it) }

        val written = fake.writtenMessages().last()
        assertEquals(
            PresetSlot.C.ordinal.toByte(),
            written.payload[rereadBlob.size - StateBlobOffsets.END_CURRENT_SLOT],
            "restoreFootswitches must never change which slot is active",
        )
    }

    // ---- mandatory re-read before every patch -------------------------------------------------

    @Test
    fun `a timed-out re-read fails restoreFootswitches and writes no state update`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(scope = backgroundScope, capabilities = FirmwareCapabilities.NONE_CONFIRMED)
        connectToReady(controller, fake)
        val writesBefore = fake.writtenMessages().size

        val restoreDeferred = async { controller.restoreFootswitches() }
        testScheduler.runCurrent() // re-read request written, awaiting response
        testScheduler.advanceTimeBy(ConnectionTimeouts.DEFAULT.stateReadMillis + 1)
        testScheduler.runCurrent()

        val result = restoreDeferred.await()
        val error = (result as TonexResult.Failure).error
        assertIs<TonexError.Timeout>(error)
        assertEquals("state-read", (error as TonexError.Timeout).operation)

        val newWrites = fake.writtenMessages().drop(writesBefore)
        assertEquals(1, newWrites.size, "only the RequestState re-read should have been written")
    }

    // ---- available at any point during Ready, not only right after a selectPreset ------------

    @Test
    fun `restoreFootswitches succeeds immediately after connect, with no prior selectPreset`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(scope = backgroundScope, capabilities = FirmwareCapabilities.NONE_CONFIRMED)
        connectToReady(controller, fake, activeSlot = PresetSlot.B, a = 0, b = 1, c = 2)

        val rereadBlob = plausibleBlob(activeSlot = PresetSlot.B, a = 0, b = 1, c = 2)
        val restoreDeferred = async { controller.restoreFootswitches() }
        testScheduler.runCurrent()
        fake.emitMessage(stateUpdateMessage(rereadBlob))
        testScheduler.runCurrent()

        assertIs<TonexResult.Success<Unit>>(restoreDeferred.await())
    }

    // ---- rejected before Ready -----------------------------------------------------------------

    @Test
    fun `restoreFootswitches before Ready is rejected with ProtocolStateViolation naming the actual state`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(scope = backgroundScope, capabilities = FirmwareCapabilities.NONE_CONFIRMED)

        val result = controller.restoreFootswitches()

        val error = (result as TonexResult.Failure).error
        assertIs<TonexError.ProtocolStateViolation>(error)
        assertEquals(ConnectionState.Idle, (error as TonexError.ProtocolStateViolation).state)
    }

    // ---- fail loud, never silently no-op, when nothing was captured --------------------------

    @Test
    fun `with the captured snapshot cleared, restoreFootswitches fails with NoFootswitchSnapshotAvailable rather than silently no-opping`() = runTest {
        // Guard 3 (the session-scoped snapshot check) is not reachable through the public API in
        // ordinary operation: footswitchSnapshot is captured, synchronously, from the exact same
        // handshake blob bytes connect()'s own initialActive decode already required to succeed --
        // so whenever connectionState reports Ready, footswitchSnapshot is guaranteed non-null (see
        // captureFootswitchSnapshot's KDoc). This proves the guard's own typed-error behaviour
        // directly, mirroring how StateBlobPatcherTest proves otherwise-JVM-boundary-only-reachable
        // defense-in-depth checks via reflection: reachable in principle if that coupling ever
        // changes, or via the narrow onTransportEnded/operationMutex race documented on teardown()'s
        // own KDoc (teardown clears this field without taking operationMutex, and publishes the
        // final ConnectionState LAST) -- either way, this is the code path issue #36's "surfaces a
        // typed error rather than silently no-opping" acceptance criterion is actually about.
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(scope = backgroundScope, capabilities = FirmwareCapabilities.NONE_CONFIRMED)
        connectToReady(controller, fake)

        val field = DefaultTonexController::class.java.getDeclaredField("footswitchSnapshot")
        field.isAccessible = true
        field.set(controller, null)
        val writesBefore = fake.writtenMessages().size

        val result = controller.restoreFootswitches()

        val error = (result as TonexResult.Failure).error
        assertEquals(TonexError.NoFootswitchSnapshotAvailable, error)
        assertEquals(writesBefore, fake.writtenMessages().size, "this must fail before ever writing to the wire, not even the re-read")
    }

    // ---- session scoping: a reconnect captures a FRESH snapshot, not the old session's ---------

    @Test
    fun `disconnect then reconnect captures a fresh footswitch snapshot for the new session`() = runTest {
        val fake1 = FakeTonexTransport()
        val controller = DefaultTonexController(scope = backgroundScope, capabilities = FirmwareCapabilities.NONE_CONFIRMED)
        connectToReady(controller, fake1, activeSlot = PresetSlot.A, a = 0, b = 1, c = 2)

        controller.disconnect()
        assertEquals(ConnectionState.Idle, controller.connectionState.value)

        // A restore attempt while disconnected must fail loudly (lifecycle guard), not resurrect the
        // old session's snapshot.
        val disconnectedResult = controller.restoreFootswitches()
        assertIs<TonexError.ProtocolStateViolation>((disconnectedResult as TonexResult.Failure).error)

        val fake2 = FakeTonexTransport()
        connectToReady(controller, fake2, activeSlot = PresetSlot.B, a = 9, b = 10, c = 11)

        val rereadBlob = plausibleBlob(activeSlot = PresetSlot.B, a = 9, b = 10, c = 11)
        val restoreDeferred = async { controller.restoreFootswitches() }
        testScheduler.runCurrent()
        fake2.emitMessage(stateUpdateMessage(rereadBlob))
        testScheduler.runCurrent()
        restoreDeferred.await().let { assertIs<TonexResult.Success<Unit>>(it) }

        val written = fake2.writtenMessages().last()
        // Restored to the SECOND session's captured values (9/10/11), not the first (0/1/2).
        assertEquals(9.toByte(), written.payload[rereadBlob.size - StateBlobOffsets.END_SLOT_A_PRESET])
        assertEquals(10.toByte(), written.payload[rereadBlob.size - StateBlobOffsets.END_SLOT_B_PRESET])
        assertEquals(11.toByte(), written.payload[rereadBlob.size - StateBlobOffsets.END_SLOT_C_PRESET])
    }

    // ---- self-initiated: restoring the active slot's own assignment is not reported as external ----

    @Test
    fun `restoring an assignment that changes the active preset is not reported as ExternalPresetChange`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(scope = backgroundScope, capabilities = FirmwareCapabilities.NONE_CONFIRMED)
        connectToReady(controller, fake, activeSlot = PresetSlot.A, a = 0, b = 1, c = 2)

        // Clobber slot A via a destructive selectPreset(15). This triggers S9b's own re-snapshot
        // capture read on a separate coroutine; answer it (see the byte-exact restore test's
        // comment for why) rather than racing it with what runs next.
        val rereadForSelect = plausibleBlob(activeSlot = PresetSlot.A, a = 0, b = 1, c = 2)
        val selectDeferred = async { controller.selectPreset(PresetIndex(15)) }
        testScheduler.runCurrent()
        fake.emitMessage(stateUpdateMessage(rereadForSelect))
        testScheduler.runCurrent()
        selectDeferred.await()

        // The pedal's confirming push, reporting the NEW state after selectPreset's write landed --
        // this is what actually moves _activePreset (0 -> 15) and launches S9b's own re-snapshot
        // capture read; answer it rather than racing it with what runs next.
        fake.emitMessage(stateUpdateMessage(plausibleBlob(activeSlot = PresetSlot.A, a = 15, b = 1, c = 2)))
        testScheduler.runCurrent()
        fake.emitMessage(presetDetailsSummaryWithParameters("Preset 15", snapshotValues()))
        testScheduler.runCurrent()
        assertEquals(PresetIndex(15), controller.activePreset.value)

        var externalChangeSeen = false
        val eventsJob = launch(start = CoroutineStart.UNDISPATCHED) {
            controller.events.collect { if (it is TonexEvent.ExternalPresetChange) externalChangeSeen = true }
        }

        // Restore: A goes back to 0 -- the active slot (A) is still active, so this changes what
        // _activePreset reports (15 -> 0), self-initiated by this very call.
        val rereadForRestore = plausibleBlob(activeSlot = PresetSlot.A, a = 15, b = 1, c = 2)
        val restoreDeferred = async { controller.restoreFootswitches() }
        testScheduler.runCurrent()
        fake.emitMessage(stateUpdateMessage(rereadForRestore))
        testScheduler.runCurrent()
        restoreDeferred.await().let { assertIs<TonexResult.Success<Unit>>(it) }

        // The pedal's confirming push, reporting the restored active preset (0, still on slot A).
        fake.emitMessage(stateUpdateMessage(plausibleBlob(activeSlot = PresetSlot.A, a = 0, b = 1, c = 2)))
        testScheduler.runCurrent()

        assertEquals(PresetIndex(0), controller.activePreset.value)
        assertTrue(!externalChangeSeen, "a self-initiated restore must not be reported as an external preset change")
        eventsJob.cancel()
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
