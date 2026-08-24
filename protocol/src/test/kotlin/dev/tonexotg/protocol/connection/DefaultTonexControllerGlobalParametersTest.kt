package dev.tonexotg.protocol.connection

import dev.tonexotg.protocol.ParameterId
import dev.tonexotg.protocol.PresetSlot
import dev.tonexotg.protocol.TonexResult
import dev.tonexotg.protocol.message.FirmwareCapabilities
import dev.tonexotg.protocol.state.StateBlobOffsets
import dev.tonexotg.protocol.state.StateBlobReader
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Wiring coverage for issue #83's read path: [StateBlobReader.globalParameterValues]'s decoder is
 * covered byte-exactly by `StateBlobReaderTest`, but that alone doesn't prove
 * [DefaultTonexController] actually threads it into [DefaultTonexController.parameterValues] — the
 * two call sites this asserts against ([DefaultTonexController.connect]'s handshake seed, and
 * [DefaultTonexController]'s unsolicited-state-push handler) are what make the six GLOBAL values
 * reachable by a UI observing [DefaultTonexController.parameterValues] at all.
 */
class DefaultTonexControllerGlobalParametersTest {

    /** [plausibleBlob] with [StateBlobOffsets]'s six global-parameter offsets overwritten to distinct, decodable values. */
    private fun blobWithGlobals(
        bpm: Float,
        inputTrim: Float,
        cabSimBypass: Boolean,
        tempoSource: Boolean,
        tuningReferenceHz: Int,
        bypass: Boolean,
        activeSlot: PresetSlot = PresetSlot.A,
    ): ByteArray {
        val bytes = plausibleBlob(activeSlot = activeSlot)
        writeFloatLe(bytes, bytes.size - StateBlobOffsets.END_BPM, bpm)
        writeFloatLe(bytes, StateBlobOffsets.START_INPUT_TRIM, inputTrim)
        bytes[StateBlobOffsets.START_CAB_BYPASS] = if (cabSimBypass) 1 else 0
        bytes[bytes.size - StateBlobOffsets.END_TEMPO_SOURCE] = if (tempoSource) 1 else 0
        val tuningIndex = bytes.size - StateBlobOffsets.END_TUNING_REF
        bytes[tuningIndex] = (tuningReferenceHz and 0xFF).toByte()
        bytes[tuningIndex + 1] = ((tuningReferenceHz ushr 8) and 0xFF).toByte()
        bytes[bytes.size - StateBlobOffsets.END_BYPASS_MODE] = if (bypass) 1 else 0
        return bytes
    }

    private fun writeFloatLe(bytes: ByteArray, index: Int, value: Float) {
        val bits = value.toRawBits()
        bytes[index] = (bits and 0xFF).toByte()
        bytes[index + 1] = ((bits ushr 8) and 0xFF).toByte()
        bytes[index + 2] = ((bits ushr 16) and 0xFF).toByte()
        bytes[index + 3] = ((bits ushr 24) and 0xFF).toByte()
    }

    @Test
    fun `connect seeds the six GLOBAL parameter values from the handshake blob`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(
            scope = backgroundScope,
            capabilities = FirmwareCapabilities.NONE_CONFIRMED,
        )

        val handshakeBlob = blobWithGlobals(
            bpm = 120f,
            inputTrim = -6.5f,
            cabSimBypass = true,
            tempoSource = false,
            tuningReferenceHz = 442,
            bypass = false,
        )

        val connectDeferred = async { controller.connect(fake) }
        testScheduler.runCurrent()
        fake.emitMessage(helloResponse())
        testScheduler.runCurrent()
        fake.emitMessage(stateUpdateMessage(handshakeBlob))
        for (i in dev.tonexotg.protocol.PresetIndex.VALID_RANGE) {
            testScheduler.runCurrent()
            fake.emitMessage(presetDetailsSummary("Preset $i"))
        }
        testScheduler.runCurrent()

        val result = connectDeferred.await()
        assertIs<TonexResult.Success<Unit>>(result)

        val values = controller.parameterValues.value
        assertEquals(120f, values[ParameterId(110)]) // BPM
        assertEquals(-6.5f, values[ParameterId(111)]) // INPUT_TRIM
        assertEquals(1f, values[ParameterId(112)]) // CABSIM_BYPASS
        assertEquals(0f, values[ParameterId(113)]) // TEMPO_SOURCE
        assertEquals(442f, values[ParameterId(114)]) // TUNING_REFERENCE
        assertEquals(0f, values[ParameterId(115)]) // BYPASS
    }

    @Test
    fun `an unsolicited state push updates the six GLOBAL parameter values`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(
            scope = backgroundScope,
            capabilities = FirmwareCapabilities.NONE_CONFIRMED,
        )

        val connectDeferred = async { controller.connect(fake) }
        driveToReady(fake)
        connectDeferred.await()

        val pushedBlob = blobWithGlobals(
            bpm = 200f,
            inputTrim = 2.5f,
            cabSimBypass = false,
            tempoSource = true,
            tuningReferenceHz = 440,
            bypass = true,
        )
        fake.emitMessage(stateUpdateMessage(pushedBlob))
        testScheduler.runCurrent()

        val values = controller.parameterValues.value
        assertEquals(200f, values[ParameterId(110)]) // BPM
        assertEquals(2.5f, values[ParameterId(111)]) // INPUT_TRIM
        assertEquals(0f, values[ParameterId(112)]) // CABSIM_BYPASS
        assertEquals(1f, values[ParameterId(113)]) // TEMPO_SOURCE
        assertEquals(440f, values[ParameterId(114)]) // TUNING_REFERENCE
        assertEquals(1f, values[ParameterId(115)]) // BYPASS
    }
}
