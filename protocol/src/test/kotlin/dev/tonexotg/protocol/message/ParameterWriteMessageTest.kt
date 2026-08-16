package dev.tonexotg.protocol.message

import dev.tonexotg.protocol.ParameterId
import dev.tonexotg.protocol.TonexError
import dev.tonexotg.protocol.TonexResult
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

private fun hex(spec: String): ByteArray =
    spec.trim().split(Regex("\\s+")).map { it.toInt(16).toByte() }.toByteArray()

private fun ByteArray.hex(): String = joinToString(" ") { "%02x".format(it) }

class ParameterWriteMessageTest {

    // ---- byte-exact against usb_tonex_one_send_single_parameter -----------------------------------

    /** Header is fixed regardless of index/value: `usb_tonex_one.c:265-295`. */
    private val EXPECTED_HEADER = hex("b9 03 81 09 03 82 0a 00 80 0b 03")

    @Test
    fun `encodes byte-exact header for a known index-value pair - NG THRESH index 2 at -50 dB`() {
        // ParameterId(2) = NOISE_GATE_THRESHOLD (preset-scoped, range -100..0), value -50.0f
        // (LE bytes: 00 00 48 c2). payload[4] = index = 2.
        val encoded = ParameterWriteMessage.encode(ParameterId(2), -50.0f)
        val actualHeader = encoded.copyOfRange(0, EXPECTED_HEADER.size)
        assertTrue(
            actualHeader.contentEquals(EXPECTED_HEADER),
            "expected header ${EXPECTED_HEADER.hex()}, got ${actualHeader.hex()}",
        )
    }

    @Test
    fun `encodes byte-exact full message for a known index-value pair`() {
        val encoded = ParameterWriteMessage.encode(ParameterId(2), -50.0f)
        val expected = hex("b9 03 81 09 03 82 0a 00 80 0b 03 b9 04 02 00 02 88 00 00 48 c2")
        assertTrue(encoded.contentEquals(expected), "expected ${expected.hex()}, got ${encoded.hex()}")
    }

    @Test
    fun `payload offset 4 (payload byte index 15 overall) carries the parameter index`() {
        val encoded = ParameterWriteMessage.encode(ParameterId(2), 0f)
        // header is 11 bytes (EXPECTED_HEADER.size); payload starts at 11, so payload[4] is at 15.
        assertEquals(2.toByte(), encoded[11 + 4])
    }

    @Test
    fun `payload float32 occupies the last 4 bytes, little-endian`() {
        val encoded = ParameterWriteMessage.encode(ParameterId(2), -50.0f)
        val floatBytes = encoded.copyOfRange(encoded.size - 4, encoded.size)
        assertTrue(floatBytes.contentEquals(hex("00 00 48 c2")), "expected 00 00 48 c2, got ${floatBytes.hex()}")
    }

    @Test
    fun `message is 21 bytes total - 11-byte header plus 10-byte payload`() {
        val encoded = ParameterWriteMessage.encode(ParameterId(2), 0f)
        assertEquals(21, encoded.size)
    }

    // ---- scope validation --------------------------------------------------------------------------

    @Test
    fun `rejects a global parameter id`() {
        // BPM (index 110) is global-scoped.
        assertFailsWith<IllegalArgumentException> {
            ParameterWriteMessage.encode(ParameterId(110), 100f)
        }
    }

    @Test
    fun `rejects master volume specifically - it must go through MasterVolumeMessage`() {
        // MASTER_VOLUME is index 116, global-scoped.
        assertFailsWith<IllegalArgumentException> {
            ParameterWriteMessage.encode(ParameterId(116), 0f)
        }
    }

    @Test
    fun `accepts every preset-scoped index 0 through 108 without throwing`() {
        for (i in 0..108) {
            ParameterWriteMessage.encode(ParameterId(i), 0f)
        }
    }

    // ---- value range clamping -----------------------------------------------------------------------
    // ParameterId(2) = NOISE_GATE_THRESHOLD, registry range -100..0 (see ParameterRegistryTest
    // "spot check noise gate threshold parameter").

    /** Decodes the value this app itself just [ParameterWriteMessage.encode]d, via the shared codec. */
    private fun decodedValue(encoded: ByteArray): Float {
        val payload = encoded.copyOfRange(EXPECTED_HEADER.size, encoded.size)
        val decoded = SingleParameterPayloadCodec.decode(payload)
        assertIs<TonexResult.Success<SingleParameterPayload>>(decoded)
        return decoded.value.value
    }

    @Test
    fun `clamps a value above the registered maximum down to that maximum`() {
        // 50.0 dB is 2x NOISE_GATE_THRESHOLD's 0 dB max - must not reach the wire unclamped.
        val encoded = ParameterWriteMessage.encode(ParameterId(2), 50.0f)
        assertEquals(0.0f, decodedValue(encoded))
    }

    @Test
    fun `clamps a value below the registered minimum up to that minimum`() {
        // -1000.0 dB is far below NOISE_GATE_THRESHOLD's -100 dB min.
        val encoded = ParameterWriteMessage.encode(ParameterId(2), -1000.0f)
        assertEquals(-100.0f, decodedValue(encoded))
    }

    @Test
    fun `clamps an extreme out-of-range value rather than writing it through unmodified`() {
        // Regression fixture for the specific value the adversarial review measured reaching the
        // wire unclamped: ParameterWriteMessage.encode(ParameterId(2), 1e30f) previously wrote an
        // arbitrary float to a parameter whose registry range is -100..0.
        val encoded = ParameterWriteMessage.encode(ParameterId(2), 1e30f)
        assertEquals(0.0f, decodedValue(encoded))
    }

    @Test
    fun `does not clamp a value already within range`() {
        val encoded = ParameterWriteMessage.encode(ParameterId(2), -50.0f)
        assertEquals(-50.0f, decodedValue(encoded))
    }

    @Test
    fun `does not clamp the exact minimum boundary value`() {
        val encoded = ParameterWriteMessage.encode(ParameterId(2), -100.0f)
        assertEquals(-100.0f, decodedValue(encoded))
    }

    @Test
    fun `does not clamp the exact maximum boundary value`() {
        val encoded = ParameterWriteMessage.encode(ParameterId(2), 0.0f)
        assertEquals(0.0f, decodedValue(encoded))
    }

    // ---- firmware capability gating -----------------------------------------------------------------

    @Test
    fun `encode (capability-gated) succeeds and matches encode when the capability is confirmed`() {
        val capabilities = FirmwareCapabilities(supportsSingleParameterWrite = true)
        val result = ParameterWriteMessage.encode(ParameterId(2), -50.0f, capabilities)
        assertIs<TonexResult.Success<ByteArray>>(result)
        assertTrue(result.value.contentEquals(ParameterWriteMessage.encode(ParameterId(2), -50.0f)))
    }

    @Test
    fun `encode (capability-gated) fails with UnsupportedByFirmware when the capability is not confirmed`() {
        val capabilities = FirmwareCapabilities(supportsSingleParameterWrite = false)
        val result = ParameterWriteMessage.encode(ParameterId(2), -50.0f, capabilities)
        assertIs<TonexResult.Failure>(result)
        val error = assertIs<TonexError.UnsupportedByFirmware>(result.error)
        assertEquals("single-parameter-write", error.operation)
    }

    @Test
    fun `encode (capability-gated) never falls back to encoding anyway when unsupported`() {
        // A regression here would be a silent, dangerous fallback: producing bytes for a write the
        // pedal is known not to honour, rather than surfacing the typed error.
        val capabilities = FirmwareCapabilities.NONE_CONFIRMED
        val result = ParameterWriteMessage.encode(ParameterId(2), -50.0f, capabilities)
        assertIs<TonexResult.Failure>(result)
    }

    @Test
    fun `FirmwareCapabilities NONE_CONFIRMED does not support single-parameter write`() {
        assertEquals(false, FirmwareCapabilities.NONE_CONFIRMED.supportsSingleParameterWrite)
    }
}
