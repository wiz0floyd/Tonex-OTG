package dev.tonexotg.protocol.message

import dev.tonexotg.protocol.TonexError
import dev.tonexotg.protocol.TonexResult
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

private fun hex(spec: String): ByteArray =
    spec.trim().split(Regex("\\s+")).map { it.toInt(16).toByte() }.toByteArray()

private fun ByteArray.hex(): String = joinToString(" ") { "%02x".format(it) }

class SingleParameterPayloadCodecTest {

    // ---- encode: write-side byte layout, byte-exact against payload[4] = index -------------------

    @Test
    fun `encode places the kind byte at offset 2`() {
        val encoded = SingleParameterPayloadCodec.encode(kind = 0x02, index = 5, value = 0f)
        assertEquals(0x02.toByte(), encoded[2])
    }

    @Test
    fun `encode leaves offset 3 at 0x00 - the write side never touches it`() {
        for (index in listOf(0, 1, 5, 108, 116, 255)) {
            val encoded = SingleParameterPayloadCodec.encode(kind = 0x02, index = index, value = 0f)
            assertEquals(0x00.toByte(), encoded[3], "index=$index: offset 3 must stay 0x00")
        }
    }

    @Test
    fun `encode places the index at offset 4 - payload offset 4 equals index, byte-exact against upstream`() {
        for (index in listOf(0, 1, 5, 42, 108, 116, 255)) {
            val encoded = SingleParameterPayloadCodec.encode(kind = 0x02, index = index, value = 0f)
            assertEquals(index.toByte(), encoded[4], "index=$index: offset 4 must equal index")
        }
    }

    @Test
    fun `encode places the 0x88 float marker at offset 5`() {
        val encoded = SingleParameterPayloadCodec.encode(kind = 0x02, index = 0, value = 5.5f)
        assertEquals(0x88.toByte(), encoded[5])
    }

    @Test
    fun `encode is exactly 10 bytes`() {
        val encoded = SingleParameterPayloadCodec.encode(kind = 0x02, index = 5, value = 5.5f)
        assertEquals(10, encoded.size)
    }

    @Test
    fun `encode reproduces the full byte-exact template for a known kind, index and value`() {
        // kind=0x02 (KIND_PARAMETER), index=2, value=5.5f: 5.5f LE = 00 00 b0 40
        val encoded = SingleParameterPayloadCodec.encode(
            kind = SingleParameterPayloadCodec.KIND_PARAMETER,
            index = 2,
            value = 5.5f,
        )
        val expected = hex("b9 04 02 00 02 88 00 00 b0 40")
        assertTrue(encoded.contentEquals(expected), "expected ${expected.hex()}, got ${encoded.hex()}")
    }

    @Test
    fun `encode with index 0 produces the zeroed base template from upstream`() {
        // uint8_t payload[] = {0xB9,0x04,0x02,0x00,0x00,0x88,0x00,0x00,0x00,0x00}; (value 0.0f, index 0)
        val encoded = SingleParameterPayloadCodec.encode(
            kind = SingleParameterPayloadCodec.KIND_PARAMETER,
            index = 0,
            value = 0f,
        )
        val expected = hex("b9 04 02 00 00 88 00 00 00 00")
        assertTrue(encoded.contentEquals(expected), "expected ${expected.hex()}, got ${encoded.hex()}")
    }

    @Test
    fun `encode rejects an index that does not fit the write side's single-byte encoding`() {
        assertFailsWith<IllegalArgumentException> {
            SingleParameterPayloadCodec.encode(kind = 0x02, index = 256, value = 0f)
        }
    }

    @Test
    fun `encode rejects a negative index`() {
        assertFailsWith<IllegalArgumentException> {
            SingleParameterPayloadCodec.encode(kind = 0x02, index = -1, value = 0f)
        }
    }

    @Test
    fun `encode rejects a kind byte outside 0 to 0xFF`() {
        assertFailsWith<IllegalArgumentException> {
            SingleParameterPayloadCodec.encode(kind = 256, index = 0, value = 0f)
        }
        assertFailsWith<IllegalArgumentException> {
            SingleParameterPayloadCodec.encode(kind = -1, index = 0, value = 0f)
        }
    }

    // ---- kind constants ---------------------------------------------------------------------------

    @Test
    fun `KIND_PARAMETER is 0x02 and KIND_MASTER_VOLUME is 0x03`() {
        assertEquals(0x02, SingleParameterPayloadCodec.KIND_PARAMETER)
        assertEquals(0x03, SingleParameterPayloadCodec.KIND_MASTER_VOLUME)
    }

    // ---- decode: read-side byte layout, byte-exact against usb_tonex_one_parse_param_changed ------

    @Test
    fun `decode reads kind index and value from a synthetic read-side fixture`() {
        // Read-side layout per usb_tonex_one_parse_param_changed: marker B9 04 <kind=03>, then
        // index low byte, index high byte, then the 0x88-prefixed float.
        // index = 0x1234 (indexLo=0x34, indexHi=0x12), value = 1.0f (LE: 00 00 80 3F)
        val fixture = hex("b9 04 03 34 12 88 00 00 80 3f")
        val decoded = assertSuccess(SingleParameterPayloadCodec.decode(fixture))
        assertEquals(0x03, decoded.kind)
        assertEquals(0x1234, decoded.index)
        assertEquals(1.0f, decoded.value)
    }

    @Test
    fun `decode treats the first index byte after kind as the low byte`() {
        val fixture = hex("b9 04 03 01 00 88 00 00 00 00")
        val decoded = assertSuccess(SingleParameterPayloadCodec.decode(fixture))
        assertEquals(1, decoded.index)
    }

    @Test
    fun `decode treats the second index byte after kind as the high byte`() {
        val fixture = hex("b9 04 03 00 01 88 00 00 00 00")
        val decoded = assertSuccess(SingleParameterPayloadCodec.decode(fixture))
        assertEquals(256, decoded.index)
    }

    @Test
    fun `decode finds the marker even when preceded by leading bytes`() {
        val fixture = hex("aa bb cc b9 04 02 05 00 88 00 00 00 00")
        val decoded = assertSuccess(SingleParameterPayloadCodec.decode(fixture))
        assertEquals(0x02, decoded.kind)
        assertEquals(5, decoded.index)
    }

    // ---- decode retries past a spurious earlier B9 04 that doesn't validate as this shape ----------

    @Test
    fun `decode skips an earlier incidental B9 04 with no 0x88 float marker and finds the real one`() {
        // The first "b9 04" at offset 0 is not followed by a 0x88 marker at the shape's predicted
        // offset (it has "ff ff ff" instead) - a naive first-match scan would report MalformedFrame
        // even though a genuine, fully-valid occurrence follows starting at offset 5.
        val fixture = hex("b9 04 ff ff ff b9 04 02 05 00 88 00 00 b0 40")
        val decoded = assertSuccess(SingleParameterPayloadCodec.decode(fixture))
        assertEquals(0x02, decoded.kind)
        assertEquals(5, decoded.index)
        assertEquals(5.5f, decoded.value)
    }

    @Test
    fun `decode is not fooled by the preset-name marker's own leading B9 04 bytes`() {
        // PresetNameExtractor's 6-byte marker (b9 04 b9 02 bc 21) itself begins with "b9 04" -
        // a plain first-match scan would treat it as this codec's marker and misparse or fail.
        // Here it appears (harmlessly, as unrelated leading bytes) before a genuine occurrence.
        val presetNameMarker = hex("b9 04 b9 02 bc 21")
        val genuine = hex("b9 04 03 00 00 88 00 00 80 3f")
        val fixture = presetNameMarker + genuine
        val decoded = assertSuccess(SingleParameterPayloadCodec.decode(fixture))
        assertEquals(0x03, decoded.kind)
        assertEquals(0, decoded.index)
        assertEquals(1.0f, decoded.value)
    }

    @Test
    fun `decode fails typed when every B9 04 candidate fails to validate as this shape`() {
        // Two "b9 04" occurrences, neither followed by a genuine 0x88 float32 marker.
        val fixture = hex("b9 04 00 00 00 00 00 b9 04 00 00 00 00 00")
        val result = SingleParameterPayloadCodec.decode(fixture)
        assertIs<TonexResult.Failure>(result)
        assertIs<TonexError.MalformedFrame>(result.error)
    }

    // ---- encode and decode are NOT inverses of each other for a nonzero index ---------------------

    @Disabled(
        "Pins the CURRENT decode() behaviour for a nonzero index (index * 256), which is most " +
            "likely reproducing a latent, never-actually-exercised upstream bug rather than " +
            "confirmed intended behaviour - see SingleParameterPayloadCodec KDoc's 'Read side' and " +
            "'S20 hardware probe' sections. Left active, this assertion would fail CI the moment " +
            "the S20 hardware probe resolves the ambiguity and decode() is corrected to read the " +
            "index from payload[4] alone. Do not re-enable without first re-deriving the expected " +
            "value from the S20 probe's outcome.",
    )
    @Test
    fun `decoding a payload this app just encoded does not recover the same nonzero index`() {
        // This pins down a real, independently-confirmed asymmetry between the write-side
        // (payload[4] = index) and read-side (index = lowByte | highByte << 8) upstream sources -
        // see SingleParameterPayloadCodec class KDoc. Decoding what encode() just wrote for a
        // nonzero index recovers index * 256, not index - that is expected, not a bug.
        val encoded = SingleParameterPayloadCodec.encode(kind = 0x02, index = 5, value = 1.0f)
        val decoded = assertSuccess(SingleParameterPayloadCodec.decode(encoded))
        assertEquals(5 * 256, decoded.index)
        assertTrue(decoded.index != 5)
    }

    @Test
    fun `encode and decode agree for index 0 - the only value where the asymmetry is invisible`() {
        val encoded = SingleParameterPayloadCodec.encode(kind = 0x03, index = 0, value = 2.5f)
        val decoded = assertSuccess(SingleParameterPayloadCodec.decode(encoded))
        assertEquals(0, decoded.index)
        assertEquals(2.5f, decoded.value)
    }

    // ---- decode never throws ------------------------------------------------------------------------

    @Test
    fun `decode fails typed when the B9 04 marker is absent`() {
        val result = SingleParameterPayloadCodec.decode(hex("00 01 02 03"))
        assertIs<TonexResult.Failure>(result)
        assertIs<TonexError.MalformedFrame>(result.error)
    }

    @Test
    fun `decode fails typed on an empty payload`() {
        val result = SingleParameterPayloadCodec.decode(ByteArray(0))
        assertIs<TonexResult.Failure>(result)
        assertIs<TonexError.MalformedFrame>(result.error)
    }

    @Test
    fun `decode fails typed when the marker is found but too few trailing bytes remain`() {
        val result = SingleParameterPayloadCodec.decode(hex("b9 04 02 00"))
        assertIs<TonexResult.Failure>(result)
        val error = assertIs<TonexError.UnexpectedBlobShape>(result.error)
        assertEquals("single-parameter payload float marker (SingleParameterPayloadCodec)", error.context)
    }

    @Test
    fun `decode fails typed when the value slot is an integer varint instead of a float32`() {
        // offset 5 (where 0x88 should be) is 0x00, a literal integer varint instead.
        val result = SingleParameterPayloadCodec.decode(hex("b9 04 02 00 00 00 00 00 00 00"))
        assertIs<TonexResult.Failure>(result)
        assertIs<TonexError.MalformedFrame>(result.error)
    }

    private fun assertSuccess(result: TonexResult<SingleParameterPayload>): SingleParameterPayload {
        assertIs<TonexResult.Success<SingleParameterPayload>>(result)
        return result.value
    }
}
