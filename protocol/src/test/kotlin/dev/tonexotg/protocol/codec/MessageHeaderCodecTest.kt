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

/**
 * The four-varint header field values for every complete message literal in upstream
 * `usb_tonex_one.c`, confirmed by testing this exact model against all of them (see
 * [MessageHeaderCodec] KDoc). Used below for the round-trip test (a synthetic, size-exact body is
 * attached and the whole message is decoded back). See [REFERENCE_HEADER_BYTES] for the raw
 * header bytes of all five, used for the byte-exact encode test.
 */
private data class UpstreamLiteral(
    val label: String,
    val type: Long,
    val size: Long,
    val unknownA: Long,
    val unknownB: Long,
)

private val UPSTREAM_LITERALS = listOf(
    UpstreamLiteral("hello (usb_tonex_one.c:204)", type = 0x0000L, size = 4L, unknownA = 11L, unknownB = 1L),
    UpstreamLiteral("request state (usb_tonex_one.c:224)", type = 0x0000L, size = 6L, unknownA = 11L, unknownB = 3L),
    UpstreamLiteral("preset details (usb_tonex_one.c:246)", type = 0x0300L, size = 6L, unknownA = 11L, unknownB = 3L),
    UpstreamLiteral("usb_tonex_one.c:347", type = 0x030DL, size = 5L, unknownA = 11L, unknownB = 3L),
)

/**
 * The raw header bytes (prefix + all four varints, no body) for all five reference literals,
 * extracted directly from upstream `usb_tonex_one.c` — this is what pins down each field's own
 * encoding convention (see [MessageHeaderCodec.encode] KDoc), most importantly that `type` uses
 * `0x81` for its 2-byte form and never `0x82`, which nothing else in this test file would catch a
 * regression in.
 */
private data class ReferenceHeader(
    val label: String,
    val type: Long,
    val size: Long,
    val unknownA: Long,
    val unknownB: Long,
    val headerBytesHex: String,
)

private val REFERENCE_HEADER_BYTES = listOf(
    ReferenceHeader("hello (usb_tonex_one.c:204)", 0x0000L, 4L, 11L, 1L, "b9 03 00 82 04 00 80 0b 01"),
    ReferenceHeader("request state (usb_tonex_one.c:224)", 0x0000L, 6L, 11L, 3L, "b9 03 00 82 06 00 80 0b 03"),
    ReferenceHeader("preset details (usb_tonex_one.c:246)", 0x0300L, 6L, 11L, 3L, "b9 03 81 00 03 82 06 00 80 0b 03"),
    ReferenceHeader("usb_tonex_one.c:347", 0x030DL, 5L, 11L, 3L, "b9 03 81 0d 03 82 05 00 80 0b 03"),
    ReferenceHeader(
        "usb_tonex_one.c:272 (header-only template, body appended at runtime)",
        0x0309L,
        10L,
        11L,
        3L,
        "b9 03 81 09 03 82 0a 00 80 0b 03",
    ),
)

class MessageHeaderCodecTest {

    // ---- byte-exact ground-truth fixtures -----------------------------------------------------

    @Test
    fun `decodes the Hello fixture`() {
        val decoded = assertSuccess(MessageHeaderCodec.decode(HELLO_FIXTURE))
        // type=0 here is the outer envelope's "type" varint as literally positioned on the wire
        // (first of the four header varints) - it does not match any wire ID in the known
        // MessageType table below, which upstream describes as *response* IDs. This fixture is
        // plausibly an outgoing request, so an unrecognised/Unknown type here is expected, not a
        // bug - see MessageHeaderCodec KDoc.
        assertEquals(MessageType.Unknown(0), decoded.header.type)
        assertEquals(4L, decoded.header.declaredSize)
        assertEquals(11L, decoded.header.unknownA)
        assertEquals(1L, decoded.header.unknownB)
        // size is now an exact body length, not a lower bound: the header consumes 9 of the 13
        // fixture bytes (B9 03 00 [82 04 00] [80 0B] 01), leaving exactly 4 payload bytes.
        assertEquals(4, decoded.payload.size)
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
        assertEquals(11L, decoded.header.unknownA)
        assertEquals(3L, decoded.header.unknownB)
        assertEquals(6, decoded.payload.size)
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

    @Test
    fun `encodes each reference literal's header byte-exact, including per-field marker choice`() {
        // This is what closes the gap flagged in the previous pass: type's marker choice (0x81,
        // never 0x82, for wide values) is only pinned down by comparing against real upstream
        // bytes - a round trip through this codec's own decode() cannot catch a regression here,
        // since decode() accepts 0x81 and 0x82 interchangeably by design.
        for (reference in REFERENCE_HEADER_BYTES) {
            val header = MessageHeader(
                type = MessageType.fromWireId(reference.type),
                declaredSize = reference.size,
                unknownA = reference.unknownA,
                unknownB = reference.unknownB,
            )
            val expectedHeaderBytes = hex(reference.headerBytesHex)
            // Body is irrelevant here - only the header bytes are asserted, so an empty payload
            // is used even for the four literals that have a real body (encode() does not
            // validate declaredSize against payload.size; that is decode()'s job).
            val actualHeaderBytes = MessageHeaderCodec.encode(header, ByteArray(0))
            assertTrue(
                actualHeaderBytes.contentEquals(expectedHeaderBytes),
                "${reference.label}: expected ${expectedHeaderBytes.hex()}, got ${actualHeaderBytes.hex()}",
            )
        }
    }

    // ---- all five upstream usb_tonex_one.c literals -------------------------------------------

    @Test
    fun `every upstream usb_tonex_one_c message literal decodes to its exact field values with a size-exact body`() {
        for (literal in UPSTREAM_LITERALS) {
            val header = MessageHeader(
                type = MessageType.fromWireId(literal.type),
                declaredSize = literal.size,
                unknownA = literal.unknownA,
                unknownB = literal.unknownB,
            )
            // Body content is arbitrary (upstream's actual bytes aren't part of the header
            // fixture) - what this test pins down is the header field values and that the body
            // length exactly equals `size`, per the confirmed four-varint model.
            val body = ByteArray(literal.size.toInt()) { (it + 1).toByte() }
            val frame = MessageHeaderCodec.encode(header, body)
            val decoded = assertSuccess(MessageHeaderCodec.decode(frame))

            assertEquals(header, decoded.header, literal.label)
            assertEquals(literal.size.toInt(), decoded.payload.size, "${literal.label}: body length must equal size")
            assertTrue(body.contentEquals(decoded.payload), literal.label)
        }
    }

    @Test
    fun `the header-only template literal (usb_tonex_one_c 272) fails size validation with no body appended`() {
        // upstream builds this header (type=0x0309 ParameterChanged, size=10, unknownA=11,
        // unknownB=3) and appends a 10-byte body to it at runtime - it is not a complete message
        // on its own. Decoding it with zero bytes appended is *expected* to fail size validation;
        // that failure is the same rule that catches genuine truncation, not a special case.
        val header = MessageHeader(
            type = MessageType.fromWireId(0x0309L),
            declaredSize = 10L,
            unknownA = 11L,
            unknownB = 3L,
        )
        assertEquals(MessageType.ParameterChanged, header.type)

        val frame = MessageHeaderCodec.encode(header, ByteArray(0))
        val result = MessageHeaderCodec.decode(frame)
        assertIs<TonexResult.Failure>(result)
        val error = assertIs<TonexError.UnexpectedBlobShape>(result.error)
        assertEquals(10, error.expectedSize)
        assertEquals(0, error.actualSize)
        assertEquals("message header body (MessageHeaderCodec.decode)", error.context)
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
            val header = MessageHeader(
                type = MessageType.fromWireId(wireId),
                declaredSize = 0,
                unknownA = 0,
                unknownB = 0,
            )
            val frame = MessageHeaderCodec.encode(header, ByteArray(0))
            val decoded = assertSuccess(MessageHeaderCodec.decode(frame))
            assertEquals(expected, decoded.header.type, "wire id 0x${wireId.toString(16)}")
        }
    }

    @Test
    fun `an uncatalogued wire ID decodes successfully as Unknown, not a failure`() {
        val header = MessageHeader(type = MessageType.Unknown(0x1234), declaredSize = 0, unknownA = 0, unknownB = 0)
        val frame = MessageHeaderCodec.encode(header, ByteArray(0))
        val decoded = assertSuccess(MessageHeaderCodec.decode(frame))
        assertEquals(MessageType.Unknown(0x1234), decoded.header.type)
    }

    // ---- round trip for synthetic headers --------------------------------------------------------

    @Test
    fun `header and payload round trip through encode then decode`() {
        val header = MessageHeader(type = MessageType.StateUpdate, declaredSize = 3, unknownA = 11, unknownB = 3)
        val payload = byteArrayOf(0x01, 0x02, 0x03)
        val frame = MessageHeaderCodec.encode(header, payload)
        val decoded = assertSuccess(MessageHeaderCodec.decode(frame))
        assertEquals(header, decoded.header)
        assertTrue(payload.contentEquals(decoded.payload))
    }

    @Test
    fun `unknownA beyond one byte round trips via the 2-byte fallback form`() {
        val header = MessageHeader(type = MessageType.Hello, declaredSize = 1, unknownA = 300, unknownB = 1)
        val payload = byteArrayOf(0x00)
        val frame = MessageHeaderCodec.encode(header, payload)
        val decoded = assertSuccess(MessageHeaderCodec.decode(frame))
        assertEquals(300L, decoded.header.unknownA)
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
        val result = MessageHeaderCodec.decode(hex("b9 04 00 00 00 00 00 00"))
        assertIs<TonexResult.Failure>(result)
        assertIs<TonexError.MalformedFrame>(result.error)
    }

    @Test
    fun `the too-short and wrong-prefix failures are distinct TonexError values`() {
        val tooShort = MessageHeaderCodec.decode(byteArrayOf(0xB9.toByte(), 0x03, 0x00, 0x00))
        val wrongPrefix = MessageHeaderCodec.decode(hex("b9 04 00 00 00 00 00 00"))
        assertIs<TonexResult.Failure>(tooShort)
        assertIs<TonexResult.Failure>(wrongPrefix)
        assertNotEquals(tooShort.error, wrongPrefix.error)
    }

    @Test
    fun `rejects a declared size that does not match the actual remaining bytes`() {
        val header = MessageHeader(type = MessageType.Unknown(0), declaredSize = 100, unknownA = 11, unknownB = 1)
        val frame = MessageHeaderCodec.encode(header, byteArrayOf(0xAA.toByte(), 0xBB.toByte()))
        val result = MessageHeaderCodec.decode(frame)
        assertIs<TonexResult.Failure>(result)
        val error = assertIs<TonexError.UnexpectedBlobShape>(result.error)
        assertEquals(100, error.expectedSize)
        assertEquals(2, error.actualSize)
        assertEquals("message header body (MessageHeaderCodec.decode)", error.context)
    }

    @Test
    fun `rejects a declared size smaller than the actual remaining bytes too, not just truncation`() {
        // Strict equality, not a lower bound: too many trailing bytes is rejected exactly like
        // too few, since `size` is confirmed to be an exact body length (see UPSTREAM_LITERALS).
        val header = MessageHeader(type = MessageType.Unknown(0), declaredSize = 2, unknownA = 11, unknownB = 1)
        val frame = MessageHeaderCodec.encode(header, byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte()))
        val result = MessageHeaderCodec.decode(frame)
        assertIs<TonexResult.Failure>(result)
        val error = assertIs<TonexError.UnexpectedBlobShape>(result.error)
        assertEquals(2, error.expectedSize)
        assertEquals(3, error.actualSize)
        assertEquals("message header body (MessageHeaderCodec.decode)", error.context)
    }

    @Test
    fun `all three malformed cases are surfaced as distinct TonexError values`() {
        val tooShort = MessageHeaderCodec.decode(byteArrayOf(0xB9.toByte(), 0x03, 0x00, 0x00))
        val wrongPrefix = MessageHeaderCodec.decode(hex("b9 04 00 00 00 00 00 00"))
        val mismatchHeader = MessageHeader(type = MessageType.Unknown(0), declaredSize = 100, unknownA = 11, unknownB = 1)
        val sizeMismatch = MessageHeaderCodec.decode(
            MessageHeaderCodec.encode(mismatchHeader, byteArrayOf(0xAA.toByte())),
        )
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
