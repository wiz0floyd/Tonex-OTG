@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.tonexotg.protocol.connection

import dev.tonexotg.protocol.ConnectionState
import dev.tonexotg.protocol.ParameterId
import dev.tonexotg.protocol.PresetIndex
import dev.tonexotg.protocol.PresetSlot
import dev.tonexotg.protocol.TonexError
import dev.tonexotg.protocol.TonexResult
import dev.tonexotg.protocol.message.FirmwareCapabilities
import dev.tonexotg.protocol.message.MasterVolumeMessage
import dev.tonexotg.protocol.message.SingleParameterPayload
import dev.tonexotg.protocol.message.SingleParameterPayloadCodec
import dev.tonexotg.protocol.params.ParameterRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Step 9 of the S9 build order: `setParameter()` (§10.1) and `revertActivePreset()` (§10.2 — S9's
 * deliberate stopping point; see issue #14 / S9b for what comes next). Production code landed as
 * part of step 5.
 */
class DefaultTonexControllerSetParameterTest {

    /** A PRESET-scoped parameter: NOISE_GATE_THRESHOLD, range -100..0 dB. */
    private val presetParam = requireNotNull(ParameterRegistry.byIndex(2))

    /** A GLOBAL parameter other than master volume: BPM, range 40..240. */
    private val bpmParam = requireNotNull(ParameterRegistry.byIndex(110))

    private val masterVolumeSpec = requireNotNull(ParameterRegistry.byEnumName("MASTER_VOLUME"))

    // ---- rejected before Ready ------------------------------------------------------------------

    @Test
    fun `setParameter before Ready is rejected with ProtocolStateViolation`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(scope = backgroundScope, capabilities = FirmwareCapabilities.NONE_CONFIRMED)

        val result = controller.setParameter(presetParam.id, 0f)

        val error = (result as TonexResult.Failure).error
        assertIs<TonexError.ProtocolStateViolation>(error)
        assertEquals(ConnectionState.Idle, (error as TonexError.ProtocolStateViolation).state)
    }

    @Test
    fun `revertActivePreset before Ready is rejected with ProtocolStateViolation`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(scope = backgroundScope, capabilities = FirmwareCapabilities.NONE_CONFIRMED)

        val result = controller.revertActivePreset()

        assertIs<TonexError.ProtocolStateViolation>((result as TonexResult.Failure).error)
    }

    // ---- range rejection --------------------------------------------------------------------

    @Test
    fun `a value above max is rejected with ParameterValueOutOfRange, no write issued`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(
            scope = backgroundScope,
            capabilities = FirmwareCapabilities(supportsSingleParameterWrite = true),
        )
        connectToReady(controller, fake)
        val writesBefore = fake.writtenMessages().size

        val result = controller.setParameter(presetParam.id, presetParam.max + 0.1f)

        val error = (result as TonexResult.Failure).error
        assertIs<TonexError.ParameterValueOutOfRange>(error)
        assertEquals(fake.writtenMessages().size, writesBefore, "no write should be issued for an out-of-range value")
    }

    @Test
    fun `a value below min is rejected with ParameterValueOutOfRange`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(
            scope = backgroundScope,
            capabilities = FirmwareCapabilities(supportsSingleParameterWrite = true),
        )
        connectToReady(controller, fake)

        val result = controller.setParameter(presetParam.id, presetParam.min - 0.1f)

        assertIs<TonexError.ParameterValueOutOfRange>((result as TonexResult.Failure).error)
    }

    @Test
    fun `boundary values min and max exactly are accepted`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(
            scope = backgroundScope,
            capabilities = FirmwareCapabilities(supportsSingleParameterWrite = true),
        )
        connectToReady(controller, fake)

        assertIs<TonexResult.Success<Unit>>(controller.setParameter(presetParam.id, presetParam.min))
        assertIs<TonexResult.Success<Unit>>(controller.setParameter(presetParam.id, presetParam.max))
    }

    // ---- capability gate --------------------------------------------------------------------

    @Test
    fun `setParameter on a preset parameter with NONE_CONFIRMED fails UnsupportedByFirmware single-parameter-write`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(scope = backgroundScope, capabilities = FirmwareCapabilities.NONE_CONFIRMED)
        connectToReady(controller, fake)

        val result = controller.setParameter(presetParam.id, presetParam.min)

        val error = (result as TonexResult.Failure).error
        assertIs<TonexError.UnsupportedByFirmware>(error)
        assertEquals("single-parameter-write", (error as TonexError.UnsupportedByFirmware).operation)
    }

    @Test
    fun `setParameter on master volume with NONE_CONFIRMED fails UnsupportedByFirmware master-volume-write`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(scope = backgroundScope, capabilities = FirmwareCapabilities.NONE_CONFIRMED)
        connectToReady(controller, fake)

        val result = controller.setParameter(masterVolumeSpec.id, 0f)

        val error = (result as TonexResult.Failure).error
        assertIs<TonexError.UnsupportedByFirmware>(error)
        assertEquals("master-volume-write", (error as TonexError.UnsupportedByFirmware).operation)
    }

    // ---- routing: PRESET vs MASTER_VOLUME vs other GLOBAL -------------------------------------

    @Test
    fun `a PRESET-scoped id produces a KIND_PARAMETER payload`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(
            scope = backgroundScope,
            capabilities = FirmwareCapabilities(supportsSingleParameterWrite = true),
        )
        connectToReady(controller, fake)
        val writesBefore = fake.writtenMessages().size

        val result = controller.setParameter(presetParam.id, -50f)

        assertIs<TonexResult.Success<Unit>>(result)
        val written = fake.writtenMessages().drop(writesBefore).single()
        val payload = SingleParameterPayloadCodec.decode(written.payload)
        assertIs<TonexResult.Success<SingleParameterPayload>>(payload)
        assertEquals(SingleParameterPayloadCodec.KIND_PARAMETER, payload.value.kind)
    }

    @Test
    fun `MASTER_VOLUME produces a KIND_MASTER_VOLUME payload with index 0 and a native 0 to 10 value`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(
            scope = backgroundScope,
            capabilities = FirmwareCapabilities(supportsSingleParameterWrite = true),
        )
        connectToReady(controller, fake)
        val writesBefore = fake.writtenMessages().size

        val result = controller.setParameter(masterVolumeSpec.id, 0f) // 0 dB -> native ~9.3

        assertIs<TonexResult.Success<Unit>>(result)
        val written = fake.writtenMessages().drop(writesBefore).single()
        val payload = SingleParameterPayloadCodec.decode(written.payload)
        assertIs<TonexResult.Success<SingleParameterPayload>>(payload)
        assertEquals(SingleParameterPayloadCodec.KIND_MASTER_VOLUME, payload.value.kind)
        assertEquals(0, payload.value.index)
        val expectedNative = MasterVolumeMessage.decibelsToNative(0f)
        assertTrue(abs(payload.value.value - expectedNative) < 1e-3f)
        assertTrue(payload.value.value in 0f..10f)
    }

    @Test
    fun `another global parameter (BPM) is rejected with ProtocolStateViolation - no write path exists`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(
            scope = backgroundScope,
            capabilities = FirmwareCapabilities(supportsSingleParameterWrite = true),
        )
        connectToReady(controller, fake)
        val writesBefore = fake.writtenMessages().size

        val result = controller.setParameter(bpmParam.id, bpmParam.min)

        val error = (result as TonexResult.Failure).error
        assertIs<TonexError.ProtocolStateViolation>(error)
        assertEquals(fake.writtenMessages().size, writesBefore, "no write path exists for a non-master-volume global")
    }

    // ---- parameterValues updated only after a successful write --------------------------------

    @Test
    fun `parameterValues is updated only after writeFramed succeeds, with the exact value`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(
            scope = backgroundScope,
            capabilities = FirmwareCapabilities(supportsSingleParameterWrite = true),
        )
        connectToReady(controller, fake)
        assertTrue(!controller.parameterValues.value.containsKey(presetParam.id))

        controller.setParameter(presetParam.id, -25f)

        assertEquals(-25f, controller.parameterValues.value[presetParam.id])
    }

    @Test
    fun `parameterValues is unchanged when the write fails`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(
            scope = backgroundScope,
            capabilities = FirmwareCapabilities(supportsSingleParameterWrite = true),
        )
        connectToReady(controller, fake)
        fake.writeThrows = java.io.IOException("wedged")

        val result = controller.setParameter(presetParam.id, -25f)

        assertIs<TonexResult.Failure>(result)
        assertTrue(!controller.parameterValues.value.containsKey(presetParam.id))
    }

    // ---- revertActivePreset: both non-snapshot failure modes, no throw, no write --------------

    @Test
    fun `revertActivePreset with capability unconfirmed fails UnsupportedByFirmware, no write`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(scope = backgroundScope, capabilities = FirmwareCapabilities.NONE_CONFIRMED)
        connectToReady(controller, fake)
        val writesBefore = fake.writtenMessages().size

        val result = controller.revertActivePreset()

        val error = (result as TonexResult.Failure).error
        assertIs<TonexError.UnsupportedByFirmware>(error)
        assertEquals("revert-active-preset", (error as TonexError.UnsupportedByFirmware).operation)
        assertEquals(fake.writtenMessages().size, writesBefore)
    }

    @Test
    fun `revertActivePreset with capability confirmed fails NoSnapshotAvailable - unreachable success in S9`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(
            scope = backgroundScope,
            capabilities = FirmwareCapabilities(supportsSingleParameterWrite = true),
        )
        connectToReady(controller, fake, activeSlot = PresetSlot.A, a = 4, b = 1, c = 2)
        val writesBefore = fake.writtenMessages().size

        val result = controller.revertActivePreset()

        val error = (result as TonexResult.Failure).error
        assertIs<TonexError.NoSnapshotAvailable>(error)
        assertEquals(PresetIndex(4), (error as TonexError.NoSnapshotAvailable).presetIndex)
        assertEquals(fake.writtenMessages().size, writesBefore, "revertActivePreset must never write in S9")
    }

    // ---- shared setup -------------------------------------------------------------------------

    private suspend fun kotlinx.coroutines.test.TestScope.connectToReady(
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
