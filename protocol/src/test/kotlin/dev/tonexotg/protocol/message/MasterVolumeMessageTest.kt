package dev.tonexotg.protocol.message

import dev.tonexotg.protocol.TonexError
import dev.tonexotg.protocol.TonexResult
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.math.abs

private fun hex(spec: String): ByteArray =
    spec.trim().split(Regex("\\s+")).map { it.toInt(16).toByte() }.toByteArray()

private fun ByteArray.hex(): String = joinToString(" ") { "%02x".format(it) }

class MasterVolumeMessageTest {

    // ---- byte-exact against usb_tonex_one_send_master_volume --------------------------------------

    /** Same header shape as [ParameterWriteMessage] - see class KDoc. */
    private val EXPECTED_HEADER = hex("b9 03 81 09 03 82 0a 00 80 0b 03")

    @Test
    fun `encodes byte-exact header - identical shape to ParameterWriteMessage`() {
        val encoded = MasterVolumeMessage.encode(0f)
        val actualHeader = encoded.copyOfRange(0, EXPECTED_HEADER.size)
        assertTrue(
            actualHeader.contentEquals(EXPECTED_HEADER),
            "expected header ${EXPECTED_HEADER.hex()}, got ${actualHeader.hex()}",
        )
    }

    @Test
    fun `payload kind byte (offset 2) is 0x03 - distinct from ParameterWriteMessage's 0x02`() {
        val encoded = MasterVolumeMessage.encode(0f)
        // header is 11 bytes; payload offset 2 is at overall index 11 + 2.
        assertEquals(0x03.toByte(), encoded[11 + 2])
    }

    @Test
    fun `encodes byte-exact full message at the engineering minimum (-40 dB - native 0)`() {
        val encoded = MasterVolumeMessage.encode(-40.0f)
        val expected = hex("b9 03 81 09 03 82 0a 00 80 0b 03 b9 04 03 00 00 88 00 00 00 00")
        assertTrue(encoded.contentEquals(expected), "expected ${expected.hex()}, got ${encoded.hex()}")
    }

    @Test
    fun `encodes byte-exact full message at the engineering maximum (3 dB - native 10)`() {
        val encoded = MasterVolumeMessage.encode(3.0f)
        val expected = hex("b9 03 81 09 03 82 0a 00 80 0b 03 b9 04 03 00 00 88 00 00 20 41")
        assertTrue(encoded.contentEquals(expected), "expected ${expected.hex()}, got ${encoded.hex()}")
    }

    @Test
    fun `always writes index 0 - payload offset 4 is 0x00 regardless of value`() {
        for (db in listOf(-40f, -20f, 0f, 3f)) {
            val encoded = MasterVolumeMessage.encode(db)
            assertEquals(0x00.toByte(), encoded[11 + 4], "db=$db: master volume must always write index 0")
        }
    }

    @Test
    fun `message is 21 bytes total`() {
        assertEquals(21, MasterVolumeMessage.encode(0f).size)
    }

    // ---- dB <-> native conversion: formula, both directions ----------------------------------------

    private val TOLERANCE = 1e-3f

    @Test
    fun `decibelsToNative matches the upstream send formula - ((v+40)divided by 43)times10`() {
        // usb_tonex_one.c:711-713: scaled_value = ((value + 40.0f) / 43.0f) * 10.0f;
        val cases = mapOf(
            -40.0f to 0.0f,
            3.0f to 10.0f,
            0.0f to 9.302326f,
            -20.0f to 4.6511627f,
            -14.0f to 6.0465116f,
        )
        for ((decibels, expectedNative) in cases) {
            val actual = MasterVolumeMessage.decibelsToNative(decibels)
            assertTrue(
                abs(actual - expectedNative) < TOLERANCE,
                "decibelsToNative($decibels): expected ~$expectedNative, got $actual",
            )
        }
    }

    @Test
    fun `nativeToDecibels matches the upstream receive formula - ((v divided by 10)times43)minus40`() {
        // usb_tonex_one.c:859-861: scaled_value = ((value / 10.0f) * 43.0f) - 40.0f;
        val cases = mapOf(
            0.0f to -40.0f,
            10.0f to 3.0f,
            5.0f to -18.5f,
            9.302326f to 0.0f,
        )
        for ((native, expectedDecibels) in cases) {
            val actual = MasterVolumeMessage.nativeToDecibels(native)
            assertTrue(
                abs(actual - expectedDecibels) < TOLERANCE,
                "nativeToDecibels($native): expected ~$expectedDecibels, got $actual",
            )
        }
    }

    @Test
    fun `dB to native to dB round trips within float32-appropriate tolerance`() {
        for (decibels in listOf(-40.0f, -30.5f, -14.0f, 0.0f, 1.5f, 3.0f)) {
            val native = MasterVolumeMessage.decibelsToNative(decibels)
            val roundTripped = MasterVolumeMessage.nativeToDecibels(native)
            assertTrue(
                abs(roundTripped - decibels) < TOLERANCE,
                "round trip for $decibels dB: got $roundTripped dB (native intermediate was $native)",
            )
        }
    }

    @Test
    fun `native to dB to native round trips within float32-appropriate tolerance`() {
        for (native in listOf(0.0f, 2.5f, 4.6511627f, 7.0f, 10.0f)) {
            val decibels = MasterVolumeMessage.nativeToDecibels(native)
            val roundTripped = MasterVolumeMessage.decibelsToNative(decibels)
            assertTrue(
                abs(roundTripped - native) < TOLERANCE,
                "round trip for $native native: got $roundTripped native (dB intermediate was $decibels)",
            )
        }
    }

    // ---- value range clamping -----------------------------------------------------------------------
    // MasterVolumeMessage.encode clamps the *dB* argument to ParameterRegistry's MASTER_VOLUME range
    // (-40..3) before converting to native units, so a clamped write's native payload value is
    // decibelsToNative applied to the clamped dB value, not to the raw argument.

    /** Decodes the native value this app itself just [MasterVolumeMessage.encode]d. */
    private fun decodedNativeValue(encoded: ByteArray): Float {
        val payload = encoded.copyOfRange(EXPECTED_HEADER.size, encoded.size)
        val decoded = SingleParameterPayloadCodec.decode(payload)
        assertIs<TonexResult.Success<SingleParameterPayload>>(decoded)
        return decoded.value.value
    }

    @Test
    fun `clamps a dB value above the engineering maximum down to that maximum before converting`() {
        // 50.0 dB is well above MASTER_VOLUME's 3 dB max - measured by the adversarial review to
        // reach the wire as native 20.930233 (2x the pedal's 0..10 max) before this fix.
        val encoded = MasterVolumeMessage.encode(50.0f)
        val native = decodedNativeValue(encoded)
        assertTrue(abs(native - MasterVolumeMessage.decibelsToNative(3.0f)) < TOLERANCE, "got native $native")
        assertTrue(native <= 10.0f, "clamped native value must not exceed the pedal's 0..10 range")
    }

    @Test
    fun `clamps a dB value below the engineering minimum up to that minimum before converting`() {
        // -100.0 dB is well below MASTER_VOLUME's -40 dB min - measured by the adversarial review
        // to reach the wire as a negative native volume before this fix.
        val encoded = MasterVolumeMessage.encode(-100.0f)
        val native = decodedNativeValue(encoded)
        assertTrue(abs(native - MasterVolumeMessage.decibelsToNative(-40.0f)) < TOLERANCE, "got native $native")
        assertTrue(native >= 0.0f, "clamped native value must not be negative")
    }

    @Test
    fun `clamps an extreme out-of-range dB value rather than writing it through unmodified`() {
        // Regression fixture for the review's most extreme measured case: 1e30 dB previously
        // produced a native value around 2.3e29.
        val encoded = MasterVolumeMessage.encode(1e30f)
        val native = decodedNativeValue(encoded)
        assertTrue(native in 0.0f..10.0f, "clamped native value must stay within the pedal's 0..10 range, got $native")
    }

    @Test
    fun `does not clamp the exact engineering minimum boundary value`() {
        val encoded = MasterVolumeMessage.encode(-40.0f)
        assertTrue(abs(decodedNativeValue(encoded) - 0.0f) < TOLERANCE)
    }

    @Test
    fun `does not clamp the exact engineering maximum boundary value`() {
        val encoded = MasterVolumeMessage.encode(3.0f)
        assertTrue(abs(decodedNativeValue(encoded) - 10.0f) < TOLERANCE)
    }

    // ---- firmware capability gating -----------------------------------------------------------------

    @Test
    fun `encode (capability-gated) succeeds and matches encode when the capability is confirmed`() {
        val capabilities = FirmwareCapabilities(supportsSingleParameterWrite = true)
        val result = MasterVolumeMessage.encode(0f, capabilities)
        assertIs<TonexResult.Success<ByteArray>>(result)
        assertTrue(result.value.contentEquals(MasterVolumeMessage.encode(0f)))
    }

    @Test
    fun `encode (capability-gated) fails with UnsupportedByFirmware when the capability is not confirmed`() {
        val capabilities = FirmwareCapabilities(supportsSingleParameterWrite = false)
        val result = MasterVolumeMessage.encode(0f, capabilities)
        assertIs<TonexResult.Failure>(result)
        val error = assertIs<TonexError.UnsupportedByFirmware>(result.error)
        assertEquals("master-volume-write", error.operation)
    }

    @Test
    fun `master volume and single-parameter-write use distinct UnsupportedByFirmware operation names`() {
        val notSupported = FirmwareCapabilities(supportsSingleParameterWrite = false)
        val masterVolumeError =
            (MasterVolumeMessage.encode(0f, notSupported) as TonexResult.Failure).error
                as TonexError.UnsupportedByFirmware
        val parameterError =
            (
                ParameterWriteMessage.encode(
                    dev.tonexotg.protocol.ParameterId(2),
                    0f,
                    notSupported,
                ) as TonexResult.Failure
                ).error as TonexError.UnsupportedByFirmware
        assertTrue(masterVolumeError.operation != parameterError.operation)
    }
}
