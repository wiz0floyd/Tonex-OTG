package dev.tonexotg.protocol.message

import dev.tonexotg.protocol.PresetSnapshot
import dev.tonexotg.protocol.TonexError
import dev.tonexotg.protocol.TonexResult
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * §H.1 of the S9b architecture plan. Per CLAUDE.md, prefers literal assertions (a wire literal
 * transcribed by hand, or built via [ByteBuffer] rather than [PresetParameterExtractor]'s own
 * production encoder) over round-trip self-consistency, so a real wire-format regression cannot
 * hide behind an internally-consistent test.
 */
class PresetParameterExtractorTest {

    private val marker = PresetParameterExtractor.marker()

    /** Encodes [value] as the wire literally does: `0x88` + little-endian float32 — hand-built, not via TonexVarint. */
    private fun floatEntry(value: Float): ByteArray {
        val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(value)
        return byteArrayOf(0x88.toByte()) + buf.array()
    }

    /** Builds a valid 545-byte parameter block (no marker) from [values] (must be exactly 109). */
    private fun block(values: FloatArray): ByteArray {
        require(values.size == PresetSnapshot.PARAMETER_COUNT)
        return values.fold(ByteArray(0)) { acc, v -> acc + floatEntry(v) }
    }

    /** 109 distinct values, so a cross-index copy or off-by-one shows up as a mismatch. */
    private fun distinctValues(): FloatArray = FloatArray(PresetSnapshot.PARAMETER_COUNT) { it * 1.5f - 10f }

    // ---- marker() ------------------------------------------------------------------------------

    @Test
    fun `marker() returns the exact upstream literal BA 03 BA 6D`() {
        assertTrue(marker.contentEquals(byteArrayOf(0xBA.toByte(), 0x03, 0xBA.toByte(), 0x6D)))
    }

    @Test
    fun `marker() returns a fresh copy each call`() {
        val first = PresetParameterExtractor.marker()
        first[0] = 0x00
        val second = PresetParameterExtractor.marker()
        assertTrue(second.contentEquals(byteArrayOf(0xBA.toByte(), 0x03, 0xBA.toByte(), 0x6D)))
    }

    // ---- happy path ------------------------------------------------------------------------------

    @Test
    fun `extracts 109 floats from a hand-built literal block`() {
        val values = distinctValues()
        val payload = marker + block(values)

        val result = PresetParameterExtractor.extract(payload)

        val extracted = assertSuccessValue(result)
        assertEquals(109, extracted.size)
        for (i in values.indices) {
            assertEquals(values[i], extracted[i], "parameter $i")
        }
    }

    @Test
    fun `finds the block at a non-zero offset`() {
        val values = distinctValues()
        val prefix = ByteArray(37) { (it % 251).toByte() }
        val payload = prefix + marker + block(values)

        val extracted = assertSuccessValue(PresetParameterExtractor.extract(payload))

        for (i in values.indices) {
            assertEquals(values[i], extracted[i], "parameter $i")
        }
    }

    @Test
    fun `values containing 0x7E and 0x7D bytes decode correctly - the section 0-2 divergence, made executable`() {
        // Float bits chosen so the little-endian byte stream contains HDLC delimiter (0x7E) and
        // escape (0x7D) bytes - upstream's raw-buffer walk would misparse these; our decoded-
        // payload walk must not.
        val values = FloatArray(PresetSnapshot.PARAMETER_COUNT)
        values[0] = Float.fromBits(0x7E7D7E7D)
        values[1] = Float.fromBits(0x7D7E7D7E)
        for (i in 2 until values.size) values[i] = i.toFloat()
        val payload = marker + block(values)

        val extracted = assertSuccessValue(PresetParameterExtractor.extract(payload))

        for (i in values.indices) {
            assertEquals(values[i], extracted[i], "parameter $i")
        }
    }

    // ---- missing marker --------------------------------------------------------------------------

    @Test
    fun `a missing marker fails with MalformedFrame and nothing is returned`() {
        val payload = ByteArray(64) { it.toByte() } // no BA 03 BA 6D anywhere
        val result = PresetParameterExtractor.extract(payload)
        assertIs<TonexResult.Failure>(result)
        assertIs<TonexError.MalformedFrame>(result.error)
    }

    // ---- truncated block -------------------------------------------------------------------------

    @Test
    fun `a truncated block fails with UnexpectedBlobShape`() {
        val values = distinctValues()
        val fullBlock = block(values)
        // Only 108 parameters' worth of bytes after the marker.
        val truncated = marker + fullBlock.copyOfRange(0, 108 * PresetParameterExtractor.BYTES_PER_PARAMETER)

        val result = PresetParameterExtractor.extract(truncated)

        assertIs<TonexResult.Failure>(result)
        val error = assertIs<TonexError.UnexpectedBlobShape>(result.error)
        assertEquals(545, error.expectedSize)
    }

    // ---- corrupt marker byte at a parameter position - the upstream bug we do NOT reproduce ------

    @Test
    fun `a non-0x88 marker at parameter 60 fails the whole extraction - unlike upstream's silent break`() {
        val values = distinctValues()
        val bytes = block(values)
        val corruptOffset = 60 * PresetParameterExtractor.BYTES_PER_PARAMETER
        bytes[corruptOffset] = 0x00 // was 0x88
        val payload = marker + bytes

        val result = PresetParameterExtractor.extract(payload)

        assertIs<TonexResult.Failure>(result)
        val error = assertIs<TonexError.MalformedFrame>(result.error)
        assertTrue(error.reason.contains("60"), "message should name the offending parameter index: ${error.reason}")
    }

    // ---- int-marker at a parameter position must fail, not desync ---------------------------------

    @Test
    fun `an int-marker (0x80) at a parameter position fails rather than desyncing`() {
        val values = distinctValues()
        val bytes = block(values)
        val corruptOffset = 10 * PresetParameterExtractor.BYTES_PER_PARAMETER
        // 0x80 marker means "next 1 byte is the value" - only 2 bytes total, not 5, so this must
        // be rejected by the strict bytesConsumed == 5 check rather than silently shifting every
        // subsequent parameter's offset.
        bytes[corruptOffset] = 0x80.toByte()
        val payload = marker + bytes

        val result = PresetParameterExtractor.extract(payload)

        assertIs<TonexResult.Failure>(result)
        assertIs<TonexError.MalformedFrame>(result.error)
    }

    // ---- real response shape: both extractors over one payload -----------------------------------

    @Test
    fun `a full real-shaped payload carrying both the name marker and the parameter block yields both`() {
        val name = "Lead Tone"
        val nameField = ByteArray(PresetNameExtractor.NAME_FIELD_LENGTH)
        name.toByteArray(Charsets.UTF_8).copyInto(nameField)
        val values = distinctValues()

        // Real response shape (§0): name marker + 32-byte field, then the parameter block.
        val payload = PresetNameExtractor.marker() + nameField + marker + block(values)

        val nameResult = PresetNameExtractor.extract(payload)
        val paramResult = PresetParameterExtractor.extract(payload)

        assertEquals(name, assertSuccessValue(nameResult))
        val extractedValues = assertSuccessValue(paramResult)
        for (i in values.indices) {
            assertEquals(values[i], extractedValues[i], "parameter $i")
        }
    }

    private fun <T> assertSuccessValue(result: TonexResult<T>): T {
        assertIs<TonexResult.Success<T>>(result)
        return result.value
    }
}
