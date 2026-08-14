package dev.tonexotg.protocol.codec

import dev.tonexotg.protocol.TonexError
import dev.tonexotg.protocol.TonexResult
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class VarintTest {

    // ---- literal form (anything else IS the value) ----------------------------------------

    @Test
    fun `literal 0x00 decodes to 0`() {
        val result = TonexVarint.decode(byteArrayOf(0x00))
        val decoded = assertSuccess(result)
        assertEquals(VarintValue.IntValue(0), decoded.value)
        assertEquals(1, decoded.bytesConsumed)
    }

    @Test
    fun `literal 0x7F is the last literal value before the marker range`() {
        val decoded = assertSuccess(TonexVarint.decode(byteArrayOf(0x7F)))
        assertEquals(VarintValue.IntValue(127), decoded.value)
        assertEquals(1, decoded.bytesConsumed)
    }

    // ---- 0x80: next 1 byte -------------------------------------------------------------------

    @Test
    fun `0x80 marker reads the following byte as the value`() {
        val decoded = assertSuccess(TonexVarint.decode(byteArrayOf(0x80.toByte(), 0x0B)))
        assertEquals(VarintValue.IntValue(11), decoded.value)
        assertEquals(2, decoded.bytesConsumed)
    }

    @Test
    fun `0x80 marker with data byte 0xFF decodes to 255`() {
        val decoded = assertSuccess(TonexVarint.decode(byteArrayOf(0x80.toByte(), 0xFF.toByte())))
        assertEquals(VarintValue.IntValue(255), decoded.value)
    }

    @Test
    fun `0x7F-to-0x80 boundary is the literal-vs-marker split`() {
        // 0x7F alone is a complete literal varint (value 127).
        val literal = assertSuccess(TonexVarint.decode(byteArrayOf(0x7F)))
        assertEquals(VarintValue.IntValue(127), literal.value)

        // 0x80 alone is an incomplete marker (needs 1 more byte) - never a literal 128.
        val truncated = TonexVarint.decode(byteArrayOf(0x80.toByte()))
        assertIs<TonexResult.Failure>(truncated)
        assertIs<TonexError.MalformedFrame>(truncated.error)
    }

    // ---- 0x81 / 0x82: next 2 bytes, little-endian --------------------------------------------

    @Test
    fun `0x81 marker reads 2 following bytes little-endian`() {
        // bytes 0x34, 0x12 little-endian -> 0x1234, NOT 0x3412 (big-endian would give 13330).
        val decoded = assertSuccess(TonexVarint.decode(byteArrayOf(0x81.toByte(), 0x34, 0x12)))
        assertEquals(VarintValue.IntValue(0x1234), decoded.value)
        assertEquals(3, decoded.bytesConsumed)
        assertTrue((decoded.value as VarintValue.IntValue).value != 0x3412L, "must not be byte-swapped")
    }

    @Test
    fun `0x82 marker reads 2 following bytes little-endian`() {
        val decoded = assertSuccess(TonexVarint.decode(byteArrayOf(0x82.toByte(), 0x34, 0x12)))
        assertEquals(VarintValue.IntValue(0x1234), decoded.value)
        assertEquals(3, decoded.bytesConsumed)
    }

    @Test
    fun `0x81 and 0x82 decode identically for the same data bytes`() {
        val a = assertSuccess(TonexVarint.decode(byteArrayOf(0x81.toByte(), 0x06, 0x00)))
        val b = assertSuccess(TonexVarint.decode(byteArrayOf(0x82.toByte(), 0x06, 0x00)))
        assertEquals(a.value, b.value)
    }

    @Test
    fun `2-byte marker of 256 decodes correctly, not swapped with the 1-byte value 1`() {
        val decoded = assertSuccess(TonexVarint.decode(byteArrayOf(0x82.toByte(), 0x00, 0x01)))
        assertEquals(VarintValue.IntValue(256), decoded.value)
    }

    @Test
    fun `2-byte marker of 65535 is the largest representable value`() {
        val decoded = assertSuccess(TonexVarint.decode(byteArrayOf(0x82.toByte(), 0xFF.toByte(), 0xFF.toByte())))
        assertEquals(VarintValue.IntValue(65535), decoded.value)
    }

    // ---- 0x88: float32, little-endian ----------------------------------------------------------

    @Test
    fun `0x88 decodes 1_0f from its known IEEE-754 bit pattern`() {
        // 1.0f = 0x3F800000, little-endian bytes: 00 00 80 3F
        val decoded = assertSuccess(
            TonexVarint.decode(byteArrayOf(0x88.toByte(), 0x00, 0x00, 0x80.toByte(), 0x3F)),
        )
        assertEquals(VarintValue.FloatValue(1.0f), decoded.value)
        assertEquals(5, decoded.bytesConsumed)
    }

    @Test
    fun `0x88 decodes negative and fractional values correctly`() {
        for (value in listOf(-1.0f, -0.5f, 3.14f, -123.456f, 0.0f, -0.0f)) {
            val encoded = TonexVarint.encodeFloat(value)
            val decoded = assertSuccess(TonexVarint.decode(encoded))
            assertEquals(VarintValue.FloatValue(value), decoded.value, "round trip for $value")
        }
    }

    @Test
    fun `float32 is little-endian, not byte-swapped`() {
        // -123.456f = 0xC2F6E979 big-endian bit pattern; little-endian bytes on the wire are the
        // reverse. Decoding the correctly-ordered bytes must NOT equal decoding the swapped ones.
        val bits = (-123.456f).toRawBits()
        val leBytes = byteArrayOf(
            0x88.toByte(),
            (bits and 0xFF).toByte(),
            ((bits shr 8) and 0xFF).toByte(),
            ((bits shr 16) and 0xFF).toByte(),
            ((bits shr 24) and 0xFF).toByte(),
        )
        val beBytes = byteArrayOf(0x88.toByte(), leBytes[4], leBytes[3], leBytes[2], leBytes[1])

        val correct = assertSuccess(TonexVarint.decode(leBytes))
        val swapped = assertSuccess(TonexVarint.decode(beBytes))
        assertEquals(VarintValue.FloatValue(-123.456f), correct.value)
        assertTrue(correct.value != swapped.value)
    }

    // ---- encode: minimal marker choice ---------------------------------------------------------

    @Test
    fun `encodeInt picks the literal form for 0 through 127`() {
        assertEquals(listOf(0x00.toByte()), TonexVarint.encodeInt(0).toList())
        assertEquals(listOf(0x01.toByte()), TonexVarint.encodeInt(1).toList())
        assertEquals(listOf(0x7F.toByte()), TonexVarint.encodeInt(127).toList())
    }

    @Test
    fun `encodeInt picks the 0x80 form for 128 through 255`() {
        assertEquals(listOf(0x80.toByte(), 0x80.toByte()), TonexVarint.encodeInt(128).toList())
        assertEquals(listOf(0x80.toByte(), 0xFF.toByte()), TonexVarint.encodeInt(255).toList())
    }

    @Test
    fun `encodeInt picks the 2-byte marker form above 255, and emits 0x82 not 0x81`() {
        // Judgement call: the spec documents both 0x81 and 0x82 as valid 2-byte markers without
        // saying which an encoder should prefer. Both confirmed handshake fixtures (issue #9)
        // use 0x82 exclusively, so this codec always emits 0x82. See TonexVarint.encodeInt KDoc.
        val encoded = TonexVarint.encodeInt(256)
        assertEquals(0x82.toByte(), encoded[0], "expected 0x82, not 0x81 - see the judgement call in KDoc")
        assertEquals(listOf(0x82.toByte(), 0x00.toByte(), 0x01.toByte()), encoded.toList())
    }

    @Test
    fun `encodeInt of 65535 uses the 2-byte marker form`() {
        assertEquals(
            listOf(0x82.toByte(), 0xFF.toByte(), 0xFF.toByte()),
            TonexVarint.encodeInt(65535).toList(),
        )
    }

    @Test
    fun `encodeInt rejects negative values`() {
        assertFailsWith<IllegalArgumentException> { TonexVarint.encodeInt(-1) }
    }

    @Test
    fun `encodeInt rejects values beyond the 2-byte range`() {
        assertFailsWith<IllegalArgumentException> { TonexVarint.encodeInt(65536) }
    }

    // ---- round trip -----------------------------------------------------------------------------

    @Test
    fun `every marker form round trips through encode then decode`() {
        for (value in listOf(0L, 1L, 5L, 127L, 128L, 200L, 255L, 256L, 1000L, 65535L)) {
            val encoded = TonexVarint.encodeInt(value)
            val decoded = assertSuccess(TonexVarint.decode(encoded))
            assertEquals(VarintValue.IntValue(value), decoded.value, "round trip for $value")
            assertEquals(encoded.size, decoded.bytesConsumed)
        }
    }

    @Test
    fun `float round trips including negative and fractional values`() {
        for (value in listOf(0.0f, 1.0f, -1.0f, 3.14f, -0.5f, 12345.6789f, -98765.4321f)) {
            val encoded = TonexVarint.encodeFloat(value)
            assertEquals(0x88.toByte(), encoded[0])
            assertEquals(5, encoded.size)
            val decoded = assertSuccess(TonexVarint.decode(encoded))
            assertEquals(VarintValue.FloatValue(value), decoded.value, "round trip for $value")
        }
    }

    // ---- truncated / malformed input never throws ------------------------------------------------

    @Test
    fun `empty buffer is a typed failure, not a thrown exception`() {
        val result = TonexVarint.decode(ByteArray(0))
        assertIs<TonexResult.Failure>(result)
        assertIs<TonexError.MalformedFrame>(result.error)
    }

    @Test
    fun `offset past the end of the buffer is a typed failure`() {
        val result = TonexVarint.decode(byteArrayOf(0x01), offset = 5)
        assertIs<TonexResult.Failure>(result)
        assertIs<TonexError.MalformedFrame>(result.error)
    }

    @Test
    fun `0x82 marker with only 1 of 2 data bytes present is a typed failure`() {
        val result = TonexVarint.decode(byteArrayOf(0x82.toByte(), 0x01))
        assertIs<TonexResult.Failure>(result)
        assertIs<TonexError.MalformedFrame>(result.error)
    }

    @Test
    fun `0x88 marker with only 2 of 4 data bytes present is a typed failure`() {
        val result = TonexVarint.decode(byteArrayOf(0x88.toByte(), 0x01, 0x02))
        assertIs<TonexResult.Failure>(result)
        assertIs<TonexError.MalformedFrame>(result.error)
    }

    private fun assertSuccess(result: TonexResult<DecodedVarint>): DecodedVarint {
        assertIs<TonexResult.Success<DecodedVarint>>(result)
        return result.value
    }
}
