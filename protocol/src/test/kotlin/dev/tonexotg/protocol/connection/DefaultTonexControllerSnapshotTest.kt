@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.tonexotg.protocol.connection

import app.cash.turbine.test
import dev.tonexotg.protocol.ConnectionState
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
import dev.tonexotg.protocol.message.PresetNameExtractor
import dev.tonexotg.protocol.message.PresetParameterExtractor
import dev.tonexotg.protocol.message.SingleParameterPayload
import dev.tonexotg.protocol.message.SingleParameterPayloadCodec
import dev.tonexotg.protocol.params.ParameterRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * §H.5 of the S9b architecture plan: snapshot capture — the read half of the write-safety net
 * (issue #14). Complements [DefaultTonexControllerRevertTest] (the replay) and
 * [DefaultTonexControllerFirstDestructiveWriteTest] (the warning signal).
 */
class DefaultTonexControllerSnapshotTest {

    private val masterVolumeSpec = requireNotNull(ParameterRegistry.byEnumName("MASTER_VOLUME"))

    // ---- connect() captures the initial active preset -------------------------------------------

    @Test
    fun `connect captures a snapshot of the initial active preset`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(
            scope = backgroundScope,
            capabilities = FirmwareCapabilities(supportsSingleParameterWrite = true),
        )
        val connectDeferred = async { controller.connect(fake) }
        driveToReady(fake)
        connectDeferred.await()

        val captured = snapshotValues()
        for (i in ParameterId.PRESET_RANGE) {
            assertEquals(captured[i], controller.parameterValues.value.getValue(ParameterId(i)), 1e-3f, "ParameterId($i)")
        }

        val result = controller.revertActivePreset()
        assertIs<TonexResult.Success<Unit>>(result, "a snapshot should now exist for the initial active preset")
    }

    @Test
    fun `the capture request is a SUMMARY preset-details request for the active preset index`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(scope = backgroundScope, capabilities = FirmwareCapabilities.NONE_CONFIRMED)
        val connectDeferred = async { controller.connect(fake) }
        driveToReady(fake, activeSlot = PresetSlot.A, a = 7, b = 1, c = 2)
        connectDeferred.await()

        val last = fake.writtenMessages().last()
        assertEquals(MessageType.Unknown(0x0300L), last.header.type)
        // Payload prefix is fixed (b9 04 0b 01); trailing two bytes are index, kind. Assert kind is
        // 0x00 (SUMMARY) -- NOT 0x01 (FULL) -- pinned as a literal so it cannot silently regress to
        // the FULL request the S9b plan's section 0 explicitly rejects.
        assertEquals(7, last.payload[4].toInt() and 0xFF, "capture should ask for the active preset (index 7)")
        assertEquals(0, last.payload[5].toInt() and 0xFF, "capture must request SUMMARY (0x00), never FULL (0x01)")
    }

    // ---- capture failure: best-effort, records nothing, connect still succeeds ------------------

    @Test
    fun `capture timeout leaves no snapshot and connect still succeeds`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(
            scope = backgroundScope,
            capabilities = FirmwareCapabilities(supportsSingleParameterWrite = true),
        )
        val connectDeferred = async { controller.connect(fake) }
        driveToReady(fake, captureValues = null)
        val result = connectDeferred.await()

        assertIs<TonexResult.Success<Unit>>(result)
        assertEquals(ConnectionState.Ready, controller.connectionState.value)
        assertEquals(20, controller.presets.value.size)
        val revertResult = controller.revertActivePreset()
        assertIs<TonexError.NoSnapshotAvailable>((revertResult as TonexResult.Failure).error)
    }

    @Test
    fun `a capture response with a corrupt parameter block leaves no snapshot`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(
            scope = backgroundScope,
            capabilities = FirmwareCapabilities(supportsSingleParameterWrite = true),
        )
        val connectDeferred = async { controller.connect(fake) }
        testScheduler.runCurrent()
        fake.emitMessage(helloResponse())
        testScheduler.runCurrent()
        fake.emitMessage(stateUpdateMessage(plausibleBlob()))
        for (i in PresetIndex.VALID_RANGE) {
            testScheduler.runCurrent()
            fake.emitMessage(presetDetailsSummary("Preset $i"))
        }
        testScheduler.runCurrent()
        fake.emitMessage(masterVolumeChanged(0f))
        testScheduler.runCurrent()
        fake.emitMessage(corruptPresetDetailsSummaryWithParameters())
        testScheduler.runCurrent()

        val result = connectDeferred.await()

        assertIs<TonexResult.Success<Unit>>(result)
        val revertResult = controller.revertActivePreset()
        assertIs<TonexError.NoSnapshotAvailable>((revertResult as TonexResult.Failure).error)
        assertTrue(
            controller.parameterValues.value.keys.none { it.index in ParameterId.PRESET_RANGE },
            "a failed capture must leave no PRESET-scoped entries",
        )
    }

    // ---- re-snapshot on active-preset change: external, self-initiated, both -------------------

    @Test
    fun `an external preset change captures a snapshot on first arrival`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(
            scope = backgroundScope,
            capabilities = FirmwareCapabilities(supportsSingleParameterWrite = true),
        )
        val connectDeferred = async { controller.connect(fake) }
        driveToReady(fake, activeSlot = PresetSlot.A, a = 0, b = 1, c = 2)
        connectDeferred.await()
        val writesBefore = fake.writtenMessages().size
        val newValues = snapshotValues().also { it[0] = 12.3f }

        controller.events.test {
            fake.emitMessage(stateUpdateMessage(plausibleBlob(activeSlot = PresetSlot.B, a = 0, b = 1, c = 2)))
            testScheduler.runCurrent()
            val event = awaitItem()
            assertIs<TonexEvent.ExternalPresetChange>(event)
            assertEquals(PresetIndex(1), (event as TonexEvent.ExternalPresetChange).newIndex)
        }

        val captureRequests = fake.writtenMessages().drop(writesBefore)
        assertEquals(1, captureRequests.size, "the external change should trigger exactly one first-arrival capture request")
        assertEquals(1, captureRequests[0].payload[4].toInt() and 0xFF, "should capture the NEW active preset (1)")

        fake.emitMessage(presetDetailsSummaryWithParameters("New Active", newValues))
        testScheduler.runCurrent()

        assertEquals(PresetIndex(1), controller.activePreset.value)
        for (i in ParameterId.PRESET_RANGE) {
            assertEquals(newValues[i], controller.parameterValues.value.getValue(ParameterId(i)), 1e-3f, "ParameterId($i)")
        }
    }

    @Test
    fun `a self-initiated selectPreset also captures a snapshot on first arrival, without emitting ExternalPresetChange`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(
            scope = backgroundScope,
            capabilities = FirmwareCapabilities(supportsSingleParameterWrite = true),
        )
        val connectDeferred = async { controller.connect(fake) }
        driveToReady(fake, activeSlot = PresetSlot.C, a = 0, b = 1, c = 2)
        connectDeferred.await()

        controller.events.test {
            val selectDeferred = async { controller.selectPreset(PresetIndex(19)) }
            testScheduler.runCurrent()
            // The re-read reports the OLD (unchanged) active preset - no trigger from this response.
            fake.emitMessage(stateUpdateMessage(plausibleBlob(activeSlot = PresetSlot.C, a = 0, b = 1, c = 2)))
            testScheduler.runCurrent()
            selectDeferred.await().let { assertIs<TonexResult.Success<Unit>>(it) }
            val writesBeforeConfirmation = fake.writtenMessages().size

            // The pedal's confirming push, reporting the NEW active preset.
            fake.emitMessage(stateUpdateMessage(plausibleBlob(activeSlot = PresetSlot.C, a = 0, b = 1, c = 19)))
            testScheduler.runCurrent()

            val newWrites = fake.writtenMessages().drop(writesBeforeConfirmation)
            assertEquals(1, newWrites.size, "the confirmation should trigger exactly one first-arrival capture request")
            assertEquals(19, newWrites[0].payload[4].toInt() and 0xFF, "should capture the newly active preset (19)")
            expectNoEvents()
        }
    }

    // ---- values dropped synchronously, before the re-capture completes --------------------------

    @Test
    fun `preset-scoped values are dropped the instant the active preset changes, before the capture answers`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(
            scope = backgroundScope,
            capabilities = FirmwareCapabilities(supportsSingleParameterWrite = true),
        )
        val connectDeferred = async { controller.connect(fake) }
        driveToReady(fake, activeSlot = PresetSlot.A, a = 0, b = 1, c = 2)
        connectDeferred.await()
        assertTrue(controller.parameterValues.value.containsKey(masterVolumeSpec.id))

        fake.emitMessage(stateUpdateMessage(plausibleBlob(activeSlot = PresetSlot.B, a = 0, b = 1, c = 2)))
        testScheduler.runCurrent() // the capture request is written but NOT yet answered

        assertTrue(
            controller.parameterValues.value.keys.none { it.index in ParameterId.PRESET_RANGE },
            "PRESET-scoped values must already be gone before the re-capture answers",
        )
        assertTrue(controller.parameterValues.value.containsKey(masterVolumeSpec.id), "GLOBAL values must survive")
    }

    // ---- a capture superseded by a second preset change before its response arrives -------------

    @Test
    fun `a capture whose preset stops being active before the response arrives is discarded`() = runTest {
        val fake = FakeTonexTransport()
        val store = InMemorySnapshotStore()
        val controller = DefaultTonexController(
            scope = backgroundScope,
            capabilities = FirmwareCapabilities(supportsSingleParameterWrite = true),
            snapshotStore = store,
        )
        val connectDeferred = async { controller.connect(fake) }
        driveToReady(fake, activeSlot = PresetSlot.A, a = 0, b = 1, c = 2)
        connectDeferred.await()

        // Active preset moves 0 -> 3 (capture for 3 launched, request written, unanswered)...
        fake.emitMessage(stateUpdateMessage(plausibleBlob(activeSlot = PresetSlot.B, a = 0, b = 3, c = 2)))
        testScheduler.runCurrent()
        // ...then 3 -> 7, before that capture's response ever arrives.
        fake.emitMessage(stateUpdateMessage(plausibleBlob(activeSlot = PresetSlot.C, a = 0, b = 3, c = 7)))
        testScheduler.runCurrent()

        // Answer whatever is still pending -- regardless of which in-flight capture consumes it,
        // preset 3 can never end up with a recorded snapshot: either this capture's own active-
        // preset re-check discards it (activePreset is now 7), or it is never delivered to
        // capture-for-3's awaiter at all.
        fake.emitMessage(presetDetailsSummaryWithParameters("Whichever", snapshotValues()))
        testScheduler.runCurrent()

        assertNull(store.snapshotFor(PresetIndex(3)), "preset 3's capture must have been discarded, not recorded")
    }

    // ---- disconnect clears snapshots ---------------------------------------------------------------

    @Test
    fun `disconnect clears snapshots - a fresh connect has none until its own capture lands`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(
            scope = backgroundScope,
            capabilities = FirmwareCapabilities(supportsSingleParameterWrite = true),
        )
        val connectDeferred = async { controller.connect(fake) }
        driveToReady(fake)
        connectDeferred.await()
        assertIs<TonexResult.Success<Unit>>(controller.revertActivePreset(), "a snapshot exists before disconnect")

        controller.disconnect()

        val freshFake = FakeTonexTransport()
        val reconnectDeferred = async { controller.connect(freshFake) }
        driveToReady(freshFake, captureValues = null)
        reconnectDeferred.await()

        val result = controller.revertActivePreset()
        assertIs<TonexError.NoSnapshotAvailable>((result as TonexResult.Failure).error)
    }

    // ---- aliasing, exercised end-to-end through the controller (issue #14's own acceptance test) -

    @Test
    fun `the snapshot survives a setParameter that changes a captured value`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(
            scope = backgroundScope,
            capabilities = FirmwareCapabilities(supportsSingleParameterWrite = true),
        )
        val connectDeferred = async { controller.connect(fake) }
        driveToReady(fake)
        connectDeferred.await()
        val originalValue = snapshotValues()[2]
        val spec = requireNotNull(ParameterRegistry.byIndex(2))
        val changedValue = spec.min // in range, and distinct from originalValue by construction

        controller.setParameter(ParameterId(2), changedValue)
        assertEquals(changedValue, controller.parameterValues.value.getValue(ParameterId(2)), 1e-3f)

        val revertResult = controller.revertActivePreset()

        assertIs<TonexResult.Success<Unit>>(revertResult)
        assertEquals(originalValue, controller.parameterValues.value.getValue(ParameterId(2)), 1e-3f)
        assertFalse(controller.parameterValues.value.getValue(ParameterId(2)) == changedValue)
    }

    // ---- first-arrival-wins retention (issue #46's own bug-report scenario, verbatim) ------------

    @Test
    fun `select preset 3, tweak, switch to preset 7, switch back - preset 3's snapshot still holds the original values`() = runTest {
        val fake = FakeTonexTransport()
        val store = InMemorySnapshotStore()
        val controller = DefaultTonexController(
            scope = backgroundScope,
            capabilities = FirmwareCapabilities(supportsSingleParameterWrite = true),
            snapshotStore = store,
        )
        val connectDeferred = async { controller.connect(fake) }
        val originalValues = snapshotValues()
        // Start on preset 3 (slot A); preset 7 lives on slot B.
        driveToReady(fake, activeSlot = PresetSlot.A, a = 3, b = 7, c = 19, captureValues = originalValues)
        connectDeferred.await()
        assertEquals(PresetIndex(3), controller.activePreset.value)
        assertSnapshotValuesEqual(originalValues, store.snapshotFor(PresetIndex(3)), "preset 3's first-arrival snapshot")

        // Tweak preset 3's parameters -- still on preset 3, no active-preset change yet.
        val spec5 = requireNotNull(ParameterRegistry.byIndex(5))
        controller.setParameter(ParameterId(5), spec5.min)
        assertEquals(spec5.min, controller.parameterValues.value.getValue(ParameterId(5)), 1e-3f)

        // Switch to preset 7 -- its FIRST arrival this session, so it gets its own snapshot recorded.
        val sevenValues = alteredSnapshotValues(offset = 3)
        fake.emitMessage(stateUpdateMessage(plausibleBlob(activeSlot = PresetSlot.B, a = 3, b = 7, c = 19)))
        testScheduler.runCurrent()
        fake.emitMessage(presetDetailsSummaryWithParameters("Preset 7", sevenValues))
        testScheduler.runCurrent()
        assertEquals(PresetIndex(7), controller.activePreset.value)
        assertSnapshotValuesEqual(sevenValues, store.snapshotFor(PresetIndex(7)), "preset 7's first-arrival snapshot")

        // Switch back to preset 3. The pedal auto-saved the earlier tweak, so its live read now
        // reports the TWEAKED state, not the original -- exactly the shape of the real bug report.
        val tweakedValues = originalValues.copyOf().also { it[5] = spec5.min }
        fake.emitMessage(stateUpdateMessage(plausibleBlob(activeSlot = PresetSlot.A, a = 3, b = 7, c = 19)))
        testScheduler.runCurrent()
        fake.emitMessage(presetDetailsSummaryWithParameters("Preset 3 again", tweakedValues))
        testScheduler.runCurrent()
        assertEquals(PresetIndex(3), controller.activePreset.value)

        // The DISPLAYED values reflect what the pedal just reported (the tweak) -- the live read
        // still happens on every arrival, only the retained snapshot does not.
        assertEquals(spec5.min, controller.parameterValues.value.getValue(ParameterId(5)), 1e-3f)

        // The core assertion: preset 3's RETAINED snapshot is still the ORIGINAL, pre-tweak values
        // captured on first arrival -- not overwritten by the re-arrival's fresh (tweaked) read.
        assertSnapshotValuesEqual(originalValues, store.snapshotFor(PresetIndex(3)), "preset 3's snapshot after returning to it")

        // And therefore revertActivePreset() restores the ORIGINAL values, not the tweaked ones.
        val writesBefore = fake.writtenMessages().size
        val result = controller.revertActivePreset()
        assertIs<TonexResult.Success<Unit>>(result)
        val newWrites = fake.writtenMessages().drop(writesBefore)
        assertEquals(109, newWrites.size)
        for ((i, msg) in newWrites.withIndex()) {
            val decoded = SingleParameterPayloadCodec.decode(msg.payload)
            assertIs<TonexResult.Success<SingleParameterPayload>>(decoded)
            assertEquals(originalValues[i], decoded.value.value, 1e-3f, "write $i should restore the ORIGINAL pre-tweak value")
        }
    }

    // ---- cross-session phantom-snapshot race (Opus review must-fix #1, issue #46 PR) --------------

    @Test
    fun `a snapshot stamped with a foreign session is replaced, not pinned, by this session's own first-arrival capture`() = runTest {
        val fake = FakeTonexTransport()
        val store = InMemorySnapshotStore()
        // Simulate the end-state of the cross-session race the review found: onTransportEnded's
        // teardown runs on `scope` without operationMutex, so `session = null` then
        // `snapshotStore.clear()` can interleave with a capture from a DIFFERENT (dead) session that
        // already passed its own liveness check on another thread of a caller-supplied multithreaded
        // scope -- landing a snapshot stamped with that dead session's SessionId here, for the same
        // preset index the fresh connect below is about to arrive at.
        val foreignValues = alteredSnapshotValues(offset = 2)
        store.record(PresetSnapshot(PresetIndex(0), SessionId.create(), foreignValues))

        val controller = DefaultTonexController(
            scope = backgroundScope,
            capabilities = FirmwareCapabilities(supportsSingleParameterWrite = true),
            snapshotStore = store,
        )
        val connectDeferred = async { controller.connect(fake) }
        val realValues = snapshotValues()
        driveToReady(fake, activeSlot = PresetSlot.A, a = 0, b = 1, c = 2, captureValues = realValues)
        connectDeferred.await()

        // The presence-only guard this fix replaced (`snapshotFor(index) == null`) would have seen a
        // non-null entry for preset 0 and skipped recording -- permanently pinning the foreign
        // session's phantom values for the entire lifetime of the new session. The sessionId-scoped
        // guard must instead treat "belongs to a different session" the same as "nothing recorded
        // yet" and let this session's own first-arrival capture through.
        assertSnapshotValuesEqual(
            realValues,
            store.snapshotFor(PresetIndex(0)),
            "this session's own first-arrival capture must replace the foreign session's phantom snapshot",
        )
    }

    // ---- helpers --------------------------------------------------------------------------------

    /** A [MessageType.PresetDetailsSummary] payload with a valid name field but a corrupt parameter block. */
    private fun corruptPresetDetailsSummaryWithParameters(): ByteArray {
        val nameField = ByteArray(PresetNameExtractor.NAME_FIELD_LENGTH)
        "Corrupt".toByteArray(Charsets.UTF_8).copyInto(nameField)
        val block = presetParameterBlock(snapshotValues())
        // Corrupt the first parameter's marker byte (right after PresetParameterExtractor.marker()).
        block[PresetParameterExtractor.marker().size] = 0x00
        val payload = PresetNameExtractor.marker() + nameField + block
        return encodeInboundFrame(MessageType.PresetDetailsSummary, unknown = 11L, payload = payload)
    }
}
