package dev.tonexotg.protocol.codec

import dev.tonexotg.protocol.TonexError
import dev.tonexotg.protocol.TonexResult
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun hex(spec: String): ByteArray =
    spec.trim().split(Regex("\\s+")).map { it.toInt(16).toByte() }.toByteArray()

/**
 * Encodes a synthetic **inbound**-shaped (3-field: `type`, `size`, `unknown`) frame for the
 * decode()-focused tests below. Mirrors `ConnectionTestFixtures.encodeInboundFrame` (a different
 * package, connection-test-suite-focused) but kept local here since it's a handful of lines and
 * this file has no other reason to depend on that one.
 *
 * @param declaredSize normally left as [payload]'s real length; overridden explicitly by the
 *   size-mismatch tests below to build a frame whose declared size lies about its actual body.
 */
private fun encodeInboundFrame(
    type: Long,
    unknown: Long,
    payload: ByteArray,
    declaredSize: Long = payload.size.toLong(),
): ByteArray {
    val out = ArrayList<Byte>()
    out.add(0xB9.toByte())
    out.add(0x03.toByte())
    out.addAll(TonexVarint.encodeInt(type).asIterable())
    out.addAll(TonexVarint.encodeInt(declaredSize).asIterable())
    out.addAll(TonexVarint.encodeInt(unknown).asIterable())
    out.addAll(payload.asIterable())
    return out.toByteArray()
}

/**
 * Real hardware capture, issue #25: the ToneX One's Hello-ack response, byte-identical across two
 * independent connections (with HDLC framing/CRC already stripped by `FrameReassembler` — this
 * codec's concern starts after that). This is the literal that exposed `decode()`'s field-count
 * bug: it was reading 4 header fields (matching [MessageHeaderCodec.encode]'s outbound shape) when
 * a real inbound frame only carries 3 — see [MessageHeaderCodec]'s class KDoc for the full
 * evidence trail (this literal, upstream's `usb_tonex_one_parse`, and issue #9's history).
 */
private val REAL_HELLO_ACK_FIXTURE = hex(
    "b9 03 02 2b 0b b9 07 00 80 c7 b9 03 02 01 00 " +
        "b9 03 01 03 0f bc 14 6b 8d c2 72 d6 fa 9b 73 06 " +
        "a3 3d d9 08 13 cd 0e fd 09 78 36 82 1b 45 0f 00 00",
)

// ---- outbound request literals (from usb_tonex_one.c) — confirm encode()'s 4-field shape -------

private val HELLO_REQUEST_FIXTURE = hex("b9 03 00 82 04 00 80 0b 01 b9 02 02 0b")
private val REQUEST_STATE_REQUEST_FIXTURE = hex("b9 03 00 82 06 00 80 0b 03 b9 02 81 06 03 0b")

/**
 * The raw header bytes (prefix + all four varints, no body) for five outbound reference literals,
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

    // ---- decode(): the real inbound 3-field shape -----------------------------------------------

    @Test
    fun `decodes the real hardware Hello-ack fixture (issue 25) with the 3-field inbound header`() {
        val decoded = assertSuccess(MessageHeaderCodec.decode(REAL_HELLO_ACK_FIXTURE))
        assertEquals(MessageType.Hello, decoded.header.type)
        assertEquals(43L, decoded.header.declaredSize)
        assertEquals(11L, decoded.header.unknownA)
        assertNull(decoded.header.unknownB, "a decoded inbound header never has a 4th field")
        // 5-byte header (B9 03 <type=02> <size=2B> <unknown=0B>), so 48 - 5 = 43 payload bytes.
        assertEquals(43, decoded.payload.size)
    }

    @Test
    fun `re-encoding a decoded inbound header fails loud, not silently -- unknownB is null`() {
        val decoded = assertSuccess(MessageHeaderCodec.decode(REAL_HELLO_ACK_FIXTURE))
        // encode() always produces the 4-field outbound shape and requires unknownB; feeding it a
        // decoded (3-field, unknownB == null) header is a programmer error, not a valid use.
        assertFailsWith<IllegalStateException> { MessageHeaderCodec.encode(decoded.header, decoded.payload) }
    }

    @Test
    fun `each catalogued wire ID decodes to its known MessageType via a realistic inbound frame`() {
        val cases = listOf(
            0x02L to MessageType.Hello,
            0x0303L to MessageType.FullPresetDetails,
            0x0304L to MessageType.PresetDetailsSummary,
            0x0306L to MessageType.StateUpdate,
            0x0309L to MessageType.ParameterChanged,
        )
        for ((wireId, expected) in cases) {
            val frame = encodeInboundFrame(type = wireId, unknown = 11L, payload = ByteArray(0))
            val decoded = assertSuccess(MessageHeaderCodec.decode(frame))
            assertEquals(expected, decoded.header.type, "wire id 0x${wireId.toString(16)}")
        }
    }

    @Test
    fun `an uncatalogued wire ID decodes successfully as Unknown, not a failure`() {
        val frame = encodeInboundFrame(type = 0x1234L, unknown = 11L, payload = ByteArray(0))
        val decoded = assertSuccess(MessageHeaderCodec.decode(frame))
        assertEquals(MessageType.Unknown(0x1234), decoded.header.type)
    }

    @Test
    fun `inbound header and payload round trip through encodeInboundFrame then decode`() {
        val payload = byteArrayOf(0x01, 0x02, 0x03)
        val frame = encodeInboundFrame(type = MessageType.StateUpdate.wireId, unknown = 11L, payload = payload)
        val decoded = assertSuccess(MessageHeaderCodec.decode(frame))
        assertEquals(MessageType.StateUpdate, decoded.header.type)
        assertEquals(11L, decoded.header.unknownA)
        assertNull(decoded.header.unknownB)
        assertTrue(payload.contentEquals(decoded.payload))
    }

    @Test
    fun `the inbound unknown field beyond one byte round trips via the 2-byte fallback form`() {
        val payload = byteArrayOf(0x00)
        val frame = encodeInboundFrame(type = MessageType.Hello.wireId, unknown = 300L, payload = payload)
        val decoded = assertSuccess(MessageHeaderCodec.decode(frame))
        assertEquals(300L, decoded.header.unknownA)
    }

    // ---- encode(): the real outbound 4-field shape, byte-exact against upstream literals ---------

    @Test
    fun `encode reproduces the Hello request literal byte-exact`() {
        val header = MessageHeader(type = MessageType.fromWireId(0), declaredSize = 4, unknownA = 11, unknownB = 1)
        val payload = byteArrayOf(0xB9.toByte(), 0x02, 0x02, 0x0B)
        val encoded = MessageHeaderCodec.encode(header, payload)
        assertTrue(
            encoded.contentEquals(HELLO_REQUEST_FIXTURE),
            "expected ${HELLO_REQUEST_FIXTURE.hex()}, got ${encoded.hex()}",
        )
    }

    @Test
    fun `encode reproduces the Request State literal byte-exact`() {
        val header = MessageHeader(type = MessageType.fromWireId(0), declaredSize = 6, unknownA = 11, unknownB = 3)
        val payload = hex("b9 02 81 06 03 0b")
        val encoded = MessageHeaderCodec.encode(header, payload)
        assertTrue(
            encoded.contentEquals(REQUEST_STATE_REQUEST_FIXTURE),
            "expected ${REQUEST_STATE_REQUEST_FIXTURE.hex()}, got ${encoded.hex()}",
        )
    }

    @Test
    fun `encodes each reference literal's header byte-exact, including per-field marker choice`() {
        // Pins down type's marker choice (0x81, never 0x82, for wide values), which is only
        // visible by comparing against real upstream bytes - decode() cannot catch a regression
        // here, since it accepts 0x81 and 0x82 interchangeably by design.
        for (reference in REFERENCE_HEADER_BYTES) {
            val header = MessageHeader(
                type = MessageType.fromWireId(reference.type),
                declaredSize = reference.size,
                unknownA = reference.unknownA,
                unknownB = reference.unknownB,
            )
            val expectedHeaderBytes = hex(reference.headerBytesHex)
            // Body is irrelevant here - only the header bytes are asserted, so an empty payload
            // is used even for the literals that have a real body (encode() does not validate
            // declaredSize against payload.size; that is decode()'s job).
            val actualHeaderBytes = MessageHeaderCodec.encode(header, ByteArray(0))
            assertTrue(
                actualHeaderBytes.contentEquals(expectedHeaderBytes),
                "${reference.label}: expected ${expectedHeaderBytes.hex()}, got ${actualHeaderBytes.hex()}",
            )
        }
    }

    @Test
    fun `encode fails loud when header unknownB is null -- it always produces the 4-field outbound shape`() {
        val header = MessageHeader(type = MessageType.Hello, declaredSize = 0, unknownA = 11, unknownB = null)
        assertFailsWith<IllegalStateException> { MessageHeaderCodec.encode(header, ByteArray(0)) }
    }

    // ---- malformed header: 3 distinct rejection cases ---------------------------------------------

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
        val frame = encodeInboundFrame(
            type = 0L,
            unknown = 11L,
            payload = byteArrayOf(0xAA.toByte(), 0xBB.toByte()),
            declaredSize = 100L,
        )
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
        // too few, since `size` is confirmed to be an exact body length.
        val frame = encodeInboundFrame(
            type = 0L,
            unknown = 11L,
            payload = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte()),
            declaredSize = 2L,
        )
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
        val sizeMismatch = MessageHeaderCodec.decode(
            encodeInboundFrame(type = 0L, unknown = 11L, payload = byteArrayOf(0xAA.toByte()), declaredSize = 100L),
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
