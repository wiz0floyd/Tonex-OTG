@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.tonexotg.protocol.connection

import app.cash.turbine.test
import dev.tonexotg.protocol.ConnectionState
import dev.tonexotg.protocol.PresetIndex
import dev.tonexotg.protocol.PresetSlot
import dev.tonexotg.protocol.TonexError
import dev.tonexotg.protocol.TonexEvent
import dev.tonexotg.protocol.TonexResult
import dev.tonexotg.protocol.codec.DecodedMessage
import dev.tonexotg.protocol.codec.MessageHeaderCodec
import dev.tonexotg.protocol.codec.MessageType
import dev.tonexotg.protocol.framing.HdlcFrame
import dev.tonexotg.protocol.message.FirmwareCapabilities
import dev.tonexotg.protocol.state.StateBlobOffsets
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Step 8 of the S9 build order — the highest-blast-radius piece: `selectPreset()`, per §9 of the
 * architecture plan. This is the whole-device write path, gated on a mandatory re-read
 * immediately before every patch.
 */
class DefaultTonexControllerSelectPresetTest {

    // ---- the single most important test in the story -------------------------------------------

    @Test
    fun `selectPreset re-reads state immediately before EVERY patch - never reuses an earlier read`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(scope = backgroundScope, capabilities = FirmwareCapabilities.NONE_CONFIRMED)
        val connectDeferred = async { controller.connect(fake) }
        driveToReady(fake, activeSlot = PresetSlot.A, a = 0, b = 1, c = 2)
        connectDeferred.await()
        val writesBefore = fake.writtenMessages().size

        // First selectPreset: target unassigned (5) -> re-read, then a state write.
        val first = async { controller.selectPreset(PresetIndex(5)) }
        testScheduler.runCurrent()
        fake.emitMessage(stateUpdateMessage(plausibleBlob(activeSlot = PresetSlot.A, a = 0, b = 1, c = 2)))
        testScheduler.runCurrent()
        first.await().let { assertIs<TonexResult.Success<Unit>>(it) }

        // Second selectPreset: another unassigned target (6) -> re-read AGAIN, then a state write.
        val second = async { controller.selectPreset(PresetIndex(6)) }
        testScheduler.runCurrent()
        fake.emitMessage(stateUpdateMessage(plausibleBlob(activeSlot = PresetSlot.A, a = 5, b = 1, c = 2)))
        testScheduler.runCurrent()
        second.await().let { assertIs<TonexResult.Success<Unit>>(it) }

        val newWrites = fake.writtenMessages().drop(writesBefore)
        // [RequestState, SetState, RequestState, SetState] plus one more: the second re-read
        // legitimately reports the active preset moving 0 -> 5 (slot A now holds what the first
        // selectPreset just assigned it), so S9b re-snapshots -- a fifth, preset-details request,
        // issued once selectPreset(6) releases operationMutex.
        assertEquals(5, newWrites.size, "expected [RequestState, SetState, RequestState, SetState, RequestPresetDetails]")
        assertRequestState(newWrites[0])
        assertStateUpdateWrite(newWrites[1])
        assertRequestState(newWrites[2])
        assertStateUpdateWrite(newWrites[3])
        assertEquals(MessageType.Unknown(0x0300L), newWrites[4].header.type, "the S9b re-snapshot capture request")
    }

    // ---- failed re-read never writes ------------------------------------------------------------

    @Test
    fun `a timed-out re-read fails selectPreset and writes no state update`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(scope = backgroundScope, capabilities = FirmwareCapabilities.NONE_CONFIRMED)
        val connectDeferred = async { controller.connect(fake) }
        driveToReady(fake)
        connectDeferred.await()
        val writesBefore = fake.writtenMessages().size

        val selectDeferred = async { controller.selectPreset(PresetIndex(5)) }
        testScheduler.runCurrent() // re-read request written, awaiting response
        testScheduler.advanceTimeBy(ConnectionTimeouts.DEFAULT.stateReadMillis + 1)
        testScheduler.runCurrent()

        val result = selectDeferred.await()
        val error = (result as TonexResult.Failure).error
        assertIs<TonexError.Timeout>(error)
        assertEquals("state-read", (error as TonexError.Timeout).operation)

        val newWrites = fake.writtenMessages().drop(writesBefore)
        assertEquals(1, newWrites.size, "only the RequestState re-read should have been written")
        assertRequestState(newWrites[0])
    }

    @Test
    fun `a CRC-corrupt re-read response fails selectPreset and writes no state update`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(scope = backgroundScope, capabilities = FirmwareCapabilities.NONE_CONFIRMED)
        val connectDeferred = async { controller.connect(fake) }
        driveToReady(fake)
        connectDeferred.await()
        val writesBefore = fake.writtenMessages().size

        val selectDeferred = async { controller.selectPreset(PresetIndex(5)) }
        testScheduler.runCurrent()
        val corruptFrame = HdlcFrame.encode(stateUpdateMessage(plausibleBlob())).also {
            it[it.size - 2] = (it[it.size - 2].toInt() xor 0x01).toByte()
        }
        fake.emitRaw(corruptFrame)
        testScheduler.runCurrent()

        val result = selectDeferred.await()
        val error = (result as TonexResult.Failure).error
        assertIs<TonexError.CrcMismatch>(error)

        val newWrites = fake.writtenMessages().drop(writesBefore)
        assertEquals(1, newWrites.size, "only the RequestState re-read should have been written")
    }

    // ---- byte-exactness of the state write --------------------------------------------------

    @Test
    fun `the state write is byte-identical to the re-read blob except the patched offsets`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(scope = backgroundScope, capabilities = FirmwareCapabilities.NONE_CONFIRMED)
        val connectDeferred = async { controller.connect(fake) }
        driveToReady(fake, activeSlot = PresetSlot.A, a = 0, b = 1, c = 2)
        connectDeferred.await()

        val rereadBlob = plausibleBlob(activeSlot = PresetSlot.A, a = 0, b = 1, c = 2)
        val selectDeferred = async { controller.selectPreset(PresetIndex(9)) } // unassigned -> two bytes change
        testScheduler.runCurrent()
        fake.emitMessage(stateUpdateMessage(rereadBlob))
        testScheduler.runCurrent()
        selectDeferred.await().let { assertIs<TonexResult.Success<Unit>>(it) }

        val written = fake.writtenMessages().last()
        assertEquals(rereadBlob.size, written.payload.size)
        for (i in rereadBlob.indices) {
            val changedOffsets = setOf(
                rereadBlob.size - StateBlobOffsets.END_SLOT_A_PRESET, // slot A now holds preset 9 (was active+unassigned)
                rereadBlob.size - StateBlobOffsets.END_CURRENT_SLOT,
            )
            if (i !in changedOffsets) {
                assertEquals(rereadBlob[i], written.payload[i], "byte $i must be unchanged from the re-read blob")
            }
        }
        assertEquals(9.toByte(), written.payload[rereadBlob.size - StateBlobOffsets.END_SLOT_A_PRESET])
        assertEquals(PresetSlot.A.ordinal.toByte(), written.payload[rereadBlob.size - StateBlobOffsets.END_CURRENT_SLOT])
    }

    // ---- selectPreset slot policy: three branches -------------------------------------------

    @Test
    fun `target already on the active slot - Success, zero writes after the re-read`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(scope = backgroundScope, capabilities = FirmwareCapabilities.NONE_CONFIRMED)
        val connectDeferred = async { controller.connect(fake) }
        driveToReady(fake, activeSlot = PresetSlot.A, a = 7, b = 1, c = 2)
        connectDeferred.await()
        val writesBefore = fake.writtenMessages().size

        val selectDeferred = async { controller.selectPreset(PresetIndex(7)) } // already active slot A's preset
        testScheduler.runCurrent()
        fake.emitMessage(stateUpdateMessage(plausibleBlob(activeSlot = PresetSlot.A, a = 7, b = 1, c = 2)))
        testScheduler.runCurrent()

        val result = selectDeferred.await()
        assertIs<TonexResult.Success<Unit>>(result)
        val newWrites = fake.writtenMessages().drop(writesBefore)
        assertEquals(1, newWrites.size, "only the RequestState re-read - no state write when already active")
    }

    @Test
    fun `target on another slot - exactly one byte changes, at the active-slot offset`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(scope = backgroundScope, capabilities = FirmwareCapabilities.NONE_CONFIRMED)
        val connectDeferred = async { controller.connect(fake) }
        driveToReady(fake, activeSlot = PresetSlot.A, a = 0, b = 8, c = 2)
        connectDeferred.await()

        val rereadBlob = plausibleBlob(activeSlot = PresetSlot.A, a = 0, b = 8, c = 2)
        val selectDeferred = async { controller.selectPreset(PresetIndex(8)) } // held by slot B
        testScheduler.runCurrent()
        fake.emitMessage(stateUpdateMessage(rereadBlob))
        testScheduler.runCurrent()

        selectDeferred.await().let { assertIs<TonexResult.Success<Unit>>(it) }
        val written = fake.writtenMessages().last()
        val changedOffset = rereadBlob.size - StateBlobOffsets.END_CURRENT_SLOT
        for (i in rereadBlob.indices) {
            if (i != changedOffset) assertEquals(rereadBlob[i], written.payload[i], "byte $i must be unchanged")
        }
        assertEquals(PresetSlot.B.ordinal.toByte(), written.payload[changedOffset])
    }

    @Test
    fun `target not assigned anywhere - exactly two bytes change, active slot and its assignment`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(scope = backgroundScope, capabilities = FirmwareCapabilities.NONE_CONFIRMED)
        val connectDeferred = async { controller.connect(fake) }
        driveToReady(fake, activeSlot = PresetSlot.C, a = 0, b = 1, c = 2)
        connectDeferred.await()

        val rereadBlob = plausibleBlob(activeSlot = PresetSlot.C, a = 0, b = 1, c = 2)
        val selectDeferred = async { controller.selectPreset(PresetIndex(15)) } // not held by any slot
        testScheduler.runCurrent()
        fake.emitMessage(stateUpdateMessage(rereadBlob))
        testScheduler.runCurrent()

        selectDeferred.await().let { assertIs<TonexResult.Success<Unit>>(it) }
        val written = fake.writtenMessages().last()
        val presetOffset = rereadBlob.size - StateBlobOffsets.END_SLOT_C_PRESET // active slot is C
        val slotOffset = rereadBlob.size - StateBlobOffsets.END_CURRENT_SLOT
        for (i in rereadBlob.indices) {
            if (i != presetOffset && i != slotOffset) assertEquals(rereadBlob[i], written.payload[i], "byte $i must be unchanged")
        }
        assertEquals(15.toByte(), written.payload[presetOffset])
        assertEquals(PresetSlot.C.ordinal.toByte(), written.payload[slotOffset])
    }

    // ---- duplicate slot assignments (issue #86) --------------------------------------------------

    @Test
    fun `target held by both the active slot and another slot - prefers the active slot, zero writes after the re-read`() = runTest {
        // Two slots (A and B) both hold preset 5; B is active. Before the #86 fix,
        // assignments.entries.firstOrNull { it.value == index } returned whichever slot iterates
        // first (A, by PresetSlot.entries order) rather than the active one (B), so the
        // holdingSlot == activeSlot short-circuit never fired and a needless write (switching the
        // active slot A -> A, a no-op preset-wise) landed instead.
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(scope = backgroundScope, capabilities = FirmwareCapabilities.NONE_CONFIRMED)
        val connectDeferred = async { controller.connect(fake) }
        driveToReady(fake, activeSlot = PresetSlot.B, a = 5, b = 5, c = 2)
        connectDeferred.await()
        val writesBefore = fake.writtenMessages().size

        val selectDeferred = async { controller.selectPreset(PresetIndex(5)) }
        testScheduler.runCurrent()
        fake.emitMessage(stateUpdateMessage(plausibleBlob(activeSlot = PresetSlot.B, a = 5, b = 5, c = 2)))
        testScheduler.runCurrent()

        val result = selectDeferred.await()
        assertIs<TonexResult.Success<Unit>>(result)
        val newWrites = fake.writtenMessages().drop(writesBefore)
        assertEquals(1, newWrites.size, "only the RequestState re-read - the active slot already holds the target preset")
    }

    @Test
    fun `selecting a preset already active via a duplicate slot does NOT arm the latch - two subsequent external changes are BOTH reported`() = runTest {
        // Reproduces issue #86's exact trace: A and B both hold preset 5, B active. The buggy
        // selectPreset resolved holdingSlot to A (first match, not the active slot), took the
        // "switch to another slot" branch (a real, if pointless, write), and armed
        // selfInitiatedPreset = 5 unconditionally. Because the confirming push still reports preset
        // 5 (previous == idx), applyStateUpdate's `previous != idx` branch never runs and the latch
        // is never consumed - it stays armed at 5 indefinitely, silently swallowing the NEXT genuine
        // external change back to preset 5.
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(scope = backgroundScope, capabilities = FirmwareCapabilities.NONE_CONFIRMED)
        val connectDeferred = async { controller.connect(fake) }
        driveToReady(fake, activeSlot = PresetSlot.B, a = 5, b = 5, c = 2)
        connectDeferred.await()

        val selectDeferred = async { controller.selectPreset(PresetIndex(5)) }
        testScheduler.runCurrent()
        fake.emitMessage(stateUpdateMessage(plausibleBlob(activeSlot = PresetSlot.B, a = 5, b = 5, c = 2)))
        testScheduler.runCurrent()
        selectDeferred.await().let { assertIs<TonexResult.Success<Unit>>(it) }

        val externalChanges = mutableListOf<PresetIndex>()
        val eventsJob = launch(start = CoroutineStart.UNDISPATCHED) {
            controller.events.collect { if (it is TonexEvent.ExternalPresetChange) externalChanges += it.newIndex }
        }

        // First genuine external change: footswitch press moves the active slot B -> C (preset 2).
        fake.emitMessage(stateUpdateMessage(plausibleBlob(activeSlot = PresetSlot.C, a = 5, b = 5, c = 2)))
        testScheduler.runCurrent()

        // Second genuine external change: footswitch press moves C -> A, which holds the SAME
        // preset (5) selectPreset targeted - exactly the transition a stranded latch would swallow.
        fake.emitMessage(stateUpdateMessage(plausibleBlob(activeSlot = PresetSlot.A, a = 5, b = 5, c = 2)))
        testScheduler.runCurrent()

        assertEquals(
            listOf(PresetIndex(2), PresetIndex(5)),
            externalChanges,
            "both external footswitch presses must be reported, including the return to preset 5",
        )
        eventsJob.cancel()
    }

    // ---- rejected before Ready ------------------------------------------------------------------

    @Test
    fun `selectPreset before Ready is rejected with ProtocolStateViolation naming the actual state`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(scope = backgroundScope, capabilities = FirmwareCapabilities.NONE_CONFIRMED)

        val result = controller.selectPreset(PresetIndex(0))

        val error = (result as TonexResult.Failure).error
        assertIs<TonexError.ProtocolStateViolation>(error)
        assertEquals(ConnectionState.Idle, (error as TonexError.ProtocolStateViolation).state)
    }

    // ---- FR6: externally-changed preset is reported as an event ---------------------------------

    @Test
    fun `an unsolicited StateUpdate reporting a different active preset emits ExternalPresetChange`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(scope = backgroundScope, capabilities = FirmwareCapabilities.NONE_CONFIRMED)
        val connectDeferred = async { controller.connect(fake) }
        driveToReady(fake, activeSlot = PresetSlot.A, a = 0, b = 1, c = 2)
        connectDeferred.await()
        assertEquals(PresetIndex(0), controller.activePreset.value)

        controller.events.test {
            fake.emitMessage(stateUpdateMessage(plausibleBlob(activeSlot = PresetSlot.B, a = 0, b = 1, c = 2)))
            testScheduler.runCurrent()

            val event = awaitItem()
            assertIs<TonexEvent.ExternalPresetChange>(event)
            assertEquals(PresetIndex(1), (event as TonexEvent.ExternalPresetChange).newIndex)
        }
        assertEquals(PresetIndex(1), controller.activePreset.value)
    }

    // ---- self-initiated change is NOT reported as external ---------------------------------------

    @Test
    fun `selectPreset then the confirming StateUpdate does NOT emit ExternalPresetChange`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(scope = backgroundScope, capabilities = FirmwareCapabilities.NONE_CONFIRMED)
        val connectDeferred = async { controller.connect(fake) }
        driveToReady(fake, activeSlot = PresetSlot.C, a = 0, b = 1, c = 2)
        connectDeferred.await()

        var externalChangeSeen = false
        val eventsJob = launch(start = CoroutineStart.UNDISPATCHED) {
            controller.events.collect { if (it is TonexEvent.ExternalPresetChange) externalChangeSeen = true }
        }

        val rereadBlob = plausibleBlob(activeSlot = PresetSlot.C, a = 0, b = 1, c = 2)
        val selectDeferred = async { controller.selectPreset(PresetIndex(19)) }
        testScheduler.runCurrent()
        fake.emitMessage(stateUpdateMessage(rereadBlob))
        testScheduler.runCurrent()
        selectDeferred.await().let { assertIs<TonexResult.Success<Unit>>(it) }

        // The pedal's confirming push, reporting the NEW active preset (19, still on slot C).
        fake.emitMessage(stateUpdateMessage(plausibleBlob(activeSlot = PresetSlot.C, a = 0, b = 1, c = 19)))
        testScheduler.runCurrent()

        assertEquals(PresetIndex(19), controller.activePreset.value)
        assertTrue(!externalChangeSeen, "a self-initiated preset change must not be reported as external")
        eventsJob.cancel()
    }

    // ---- the re-read's own StateUpdate is not mistaken for self-initiated (selfInitiatedPreset ordering) ----

    @Test
    fun `an external change right before selectPreset's re-read is still reported, not suppressed`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(scope = backgroundScope, capabilities = FirmwareCapabilities.NONE_CONFIRMED)
        val connectDeferred = async { controller.connect(fake) }
        driveToReady(fake, activeSlot = PresetSlot.A, a = 0, b = 1, c = 2)
        connectDeferred.await()
        assertEquals(PresetIndex(0), controller.activePreset.value)

        controller.events.test {
            // The pedal's active preset changed externally (footswitch) to slot B's preset, moments
            // before selectPreset(5) is called.
            val selectDeferred = async { controller.selectPreset(PresetIndex(5)) }
            testScheduler.runCurrent() // selectPreset's re-read request is now in flight

            // The re-read's response reports the OLD active preset having already changed externally
            // (slot B) - this is exactly the scenario selfInitiatedPreset's ordering must handle: it
            // is set AFTER the re-read completes, so this response must still be seen as external.
            fake.emitMessage(stateUpdateMessage(plausibleBlob(activeSlot = PresetSlot.B, a = 0, b = 1, c = 2)))
            testScheduler.runCurrent()

            val event = awaitItem()
            assertIs<TonexEvent.ExternalPresetChange>(event)
            assertEquals(PresetIndex(1), (event as TonexEvent.ExternalPresetChange).newIndex)

            selectDeferred.await() // let the operation finish (whatever it settles on)
        }
    }

    // ---- helpers --------------------------------------------------------------------------------

    /**
     * `RequestStateMessage`'s outbound envelope type is `0x0000` (Unknown) -- identify it by its
     * fixed opaque payload literal (`b9 02 81 06 03 0b`) instead.
     */
    private fun assertRequestState(msg: DecodedMessage) {
        assertEquals(byteArrayOf(0xB9.toByte(), 0x02, 0x81.toByte(), 0x06, 0x03, 0x0B).toList(), msg.payload.toList())
    }

    /** `SetStateMessage`'s outbound envelope type IS `0x0306` (`MessageType.StateUpdate`) -- see its KDoc. */
    private fun assertStateUpdateWrite(msg: DecodedMessage) {
        assertEquals(MessageType.StateUpdate, msg.header.type)
    }
}
