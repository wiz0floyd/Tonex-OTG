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

    // ---- decode: byte-exact against the #106 hardware capture ------------------------------------

    @Test
    fun `decode reads the literal MODEL_GAIN notification frame captured from real hardware`() {
        // THE acceptance-criterion test for issue #104. This is a real inbound frame's payload,
        // hand-decoded from the #106 debug dump of a physical MODEL_GAIN knob turn - a literal,
        // NOT a round trip. A round-trip test passed against the old (wrong) 2-byte index read and
        // is exactly what hid the bug; see CLAUDE.md, "Byte-exact / literal-exact acceptance
        // criteria are gold".
        //
        //   b9 04 | 02       | 00      | 14        | 88 9a 99 59 40
        //   marker  kind=0x02  padding   index=20    float32 LE = 3.4f
        //                                (MODEL_GAIN)
        val fixture = hex("b9 04 02 00 14 88 9a 99 59 40")
        val decoded = assertSuccess(SingleParameterPayloadCodec.decode(fixture))
        assertEquals(0x02, decoded.kind, "inbound kind is 0x02, not upstream's assumed 0x03")
        assertEquals(20, decoded.index, "index is payload[4] alone; the old 2-byte read gave 5120")
        assertEquals(3.4f, decoded.value, 1e-4f)
    }

    @Test
    fun `decode reads the index from payload 4 alone`() {
        val fixture = hex("b9 04 02 00 01 88 00 00 00 00")
        val decoded = assertSuccess(SingleParameterPayloadCodec.decode(fixture))
        assertEquals(1, decoded.index)
    }

    @Test
    fun `decode ignores payload 3 - it is padding, not an index byte`() {
        // Upstream's own reader would have decoded this as 0x0100 = 256. #106 confirms payload[3]
        // is always 0x00 padding on a real frame; a stray nonzero there must not shift the index.
        val fixture = hex("b9 04 02 01 00 88 00 00 00 00")
        val decoded = assertSuccess(SingleParameterPayloadCodec.decode(fixture))
        assertEquals(0, decoded.index)
    }

    @Test
    fun `decode finds the marker even when preceded by leading bytes`() {
        val fixture = hex("aa bb cc b9 04 02 00 05 88 00 00 00 00")
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
        val fixture = hex("b9 04 ff ff ff b9 04 02 00 05 88 00 00 b0 40")
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

    // ---- encode and decode ARE inverses of each other (issue #104) --------------------------------

    @Test
    fun `decoding a payload this app just encoded recovers the same nonzero index`() {
        val encoded = SingleParameterPayloadCodec.encode(kind = 0x02, index = 20, value = 3.4f)
        val decoded = assertSuccess(SingleParameterPayloadCodec.decode(encoded))
        assertEquals(0x02, decoded.kind)
        assertEquals(20, decoded.index)
        assertEquals(3.4f, decoded.value, 1e-4f)
    }

    @Test
    fun `encode and decode round-trip every valid parameter index`() {
        for (index in ParameterId.PRESET_RANGE + ParameterId.GLOBAL_RANGE) {
            val encoded = SingleParameterPayloadCodec.encode(kind = 0x02, index = index, value = 1.0f)
            val decoded = assertSuccess(SingleParameterPayloadCodec.decode(encoded))
            assertEquals(index, decoded.index, "index=$index must round-trip")
        }
    }

    @Test
    fun `encode and decode agree for index 0 - master volume`() {
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
