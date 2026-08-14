package dev.tonexotg.protocol.codec

import dev.tonexotg.protocol.TonexError
import dev.tonexotg.protocol.TonexResult
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Byte-exact fixtures from issue #9 ("recovered from the reference implementation and are ground
 * truth"). These are the framed message payloads *before* HDLC byte-stuffing (S4's concern, not
 * this codec's).
 */
private fun hex(spec: String): ByteArray =
    spec.trim().split(Regex("\\s+")).map { it.toInt(16).toByte() }.toByteArray()

private val HELLO_FIXTURE = hex("b9 03 00 82 04 00 80 0b 01 b9 02 02 0b")
private val REQUEST_STATE_FIXTURE = hex("b9 03 00 82 06 00 80 0b 03 b9 02 81 06 03 0b")

class MessageHeaderCodecTest {

    // ---- byte-exact ground-truth fixtures -----------------------------------------------------

    @Test
    fun `decodes the Hello fixture`() {
        val decoded = assertSuccess(MessageHeaderCodec.decode(HELLO_FIXTURE))
        // type=0 here is the outer envelope's "type" varint as literally positioned on the wire
        // (first of the three header varints) - it does not match any wire ID in the known
        // MessageType table below, which upstream describes as *response* IDs. This fixture is
        // plausibly an outgoing request, so an unrecognised/Unknown type here is expected, not a
        // bug - see MessageHeaderCodec KDoc.
        assertEquals(MessageType.Unknown(0), decoded.header.type)
        assertEquals(4L, decoded.header.declaredSize)
        assertEquals(11L, decoded.header.opaqueField)
        assertEquals(5, decoded.payload.size)
    }

    @Test
    fun `re-encoding the decoded Hello fixture reproduces the original bytes exactly`() {
        val decoded = assertSuccess(MessageHeaderCodec.decode(HELLO_FIXTURE))
        val reEncoded = MessageHeaderCodec.encode(decoded.header, decoded.payload)
        assertTrue(reEncoded.contentEquals(HELLO_FIXTURE), "expected ${HELLO_FIXTURE.hex()}, got ${reEncoded.hex()}")
    }

    @Test
    fun `decodes the Request State fixture`() {
        val decoded = assertSuccess(MessageHeaderCodec.decode(REQUEST_STATE_FIXTURE))
        assertEquals(MessageType.Unknown(0), decoded.header.type)
        assertEquals(6L, decoded.header.declaredSize)
        assertEquals(11L, decoded.header.opaqueField)
        assertEquals(7, decoded.payload.size)
    }

    @Test
    fun `re-encoding the decoded Request State fixture reproduces the original bytes exactly`() {
        val decoded = assertSuccess(MessageHeaderCodec.decode(REQUEST_STATE_FIXTURE))
        val reEncoded = MessageHeaderCodec.encode(decoded.header, decoded.payload)
        assertTrue(
            reEncoded.contentEquals(REQUEST_STATE_FIXTURE),
            "expected ${REQUEST_STATE_FIXTURE.hex()}, got ${reEncoded.hex()}",
        )
    }

    // ---- known message types ------------------------------------------------------------------

    @Test
    fun `each catalogued wire ID decodes to its known MessageType`() {
        val cases = listOf(
            0x02L to MessageType.Hello,
            0x0303L to MessageType.FullPresetDetails,
            0x0304L to MessageType.PresetDetailsSummary,
            0x0306L to MessageType.StateUpdate,
            0x0309L to MessageType.ParameterChanged,
        )
        for ((wireId, expected) in cases) {
            val header = MessageHeader(type = MessageType.fromWireId(wireId), declaredSize = 0, opaqueField = 0)
            val frame = MessageHeaderCodec.encode(header, ByteArray(0))
            val decoded = assertSuccess(MessageHeaderCodec.decode(frame))
            assertEquals(expected, decoded.header.type, "wire id 0x${wireId.toString(16)}")
        }
    }

    @Test
    fun `an uncatalogued wire ID decodes successfully as Unknown, not a failure`() {
        val header = MessageHeader(type = MessageType.Unknown(0x1234), declaredSize = 0, opaqueField = 0)
        val frame = MessageHeaderCodec.encode(header, ByteArray(0))
        val decoded = assertSuccess(MessageHeaderCodec.decode(frame))
        assertEquals(MessageType.Unknown(0x1234), decoded.header.type)
    }

    // ---- round trip for synthetic headers --------------------------------------------------------

    @Test
    fun `header and payload round trip through encode then decode`() {
        val header = MessageHeader(type = MessageType.StateUpdate, declaredSize = 3, opaqueField = 11)
        val payload = byteArrayOf(0x01, 0x02, 0x03)
        val frame = MessageHeaderCodec.encode(header, payload)
        val decoded = assertSuccess(MessageHeaderCodec.decode(frame))
        assertEquals(header, decoded.header)
        assertTrue(payload.contentEquals(decoded.payload))
    }

    @Test
    fun `opaque field beyond one byte round trips via the 2-byte fallback form`() {
        val header = MessageHeader(type = MessageType.Hello, declaredSize = 1, opaqueField = 300)
        val payload = byteArrayOf(0x00)
        val frame = MessageHeaderCodec.encode(header, payload)
        val decoded = assertSuccess(MessageHeaderCodec.decode(frame))
        assertEquals(300L, decoded.header.opaqueField)
    }

    // ---- malformed header: 3 distinct rejection cases --------------------------------------------

    @Test
    fun `rejects a frame shorter than the minimum possible header`() {
        val result = MessageHeaderCodec.decode(byteArrayOf(0xB9.toByte(), 0x03, 0x00, 0x00))
        assertIs<TonexResult.Failure>(result)
        assertIs<TonexError.MalformedFrame>(result.error)
    }

    @Test
    fun `rejects a frame with the wrong prefix`() {
        val result = MessageHeaderCodec.decode(hex("b9 04 00 00 00 00 00"))
        assertIs<TonexResult.Failure>(result)
        assertIs<TonexError.MalformedFrame>(result.error)
    }

    @Test
    fun `the too-short and wrong-prefix failures are distinct TonexError values`() {
        val tooShort = MessageHeaderCodec.decode(byteArrayOf(0xB9.toByte(), 0x03, 0x00, 0x00))
        val wrongPrefix = MessageHeaderCodec.decode(hex("b9 04 00 00 00 00 00"))
        assertIs<TonexResult.Failure>(tooShort)
        assertIs<TonexResult.Failure>(wrongPrefix)
        assertNotEquals(tooShort.error, wrongPrefix.error)
    }

    @Test
    fun `rejects a declared size larger than the actual remaining bytes`() {
        // type=0 (literal), size=100 (0x82 64 00), opaque=0 (0x80 00), then only 1 payload byte.
        val frame = hex("b9 03 00 82 64 00 80 00 aa")
        val result = MessageHeaderCodec.decode(frame)
        assertIs<TonexResult.Failure>(result)
        val error = assertIs<TonexError.UnexpectedBlobShape>(result.error)
        assertEquals(100, error.expectedSize)
        assertEquals(1, error.actualSize)
    }

    @Test
    fun `all three malformed cases are surfaced as distinct TonexError values`() {
        val tooShort = MessageHeaderCodec.decode(byteArrayOf(0xB9.toByte(), 0x03, 0x00, 0x00))
        val wrongPrefix = MessageHeaderCodec.decode(hex("b9 04 00 00 00 00 00"))
        val sizeMismatch = MessageHeaderCodec.decode(hex("b9 03 00 82 64 00 80 00 aa"))
        val errors = listOf(
            (tooShort as TonexResult.Failure).error,
            (wrongPrefix as TonexResult.Failure).error,
            (sizeMismatch as TonexResult.Failure).error,
        )
        assertEquals(3, errors.toSet().size, "expected 3 distinct TonexError values, got $errors")
    }

    // ---- truncated input never throws -------------------------------------------------------------

    @Test
    fun `a header truncated mid-varint is a typed failure, not a thrown exception`() {
        // B9 03 then a lone 0x82 marker byte with no data bytes following at all.
        val result = MessageHeaderCodec.decode(byteArrayOf(0xB9.toByte(), 0x03, 0x00, 0x82.toByte()))
        assertIs<TonexResult.Failure>(result)
        assertIs<TonexError.MalformedFrame>(result.error)
    }

    @Test
    fun `an empty buffer is a typed failure, not a thrown exception`() {
        val result = MessageHeaderCodec.decode(ByteArray(0))
        assertIs<TonexResult.Failure>(result)
        assertIs<TonexError.MalformedFrame>(result.error)
    }

    private fun assertSuccess(result: TonexResult<DecodedMessage>): DecodedMessage {
        assertIs<TonexResult.Success<DecodedMessage>>(result)
        return result.value
    }

    private fun ByteArray.hex(): String = joinToString(" ") { "%02x".format(it) }
}
