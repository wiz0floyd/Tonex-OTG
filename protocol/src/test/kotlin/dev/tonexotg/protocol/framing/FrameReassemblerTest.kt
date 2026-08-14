package dev.tonexotg.protocol.framing

import dev.tonexotg.protocol.TonexError
import dev.tonexotg.protocol.TonexResult
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FrameReassemblerTest {

    private fun payloadOf(result: TonexResult<ByteArray>): ByteArray {
        assertIs<TonexResult.Success<ByteArray>>(result)
        return result.value
    }

    // ---------------------------------------------------------------------
    // Single frame, single chunk
    // ---------------------------------------------------------------------

    @Test
    fun `a single frame delivered in one chunk decodes to one result`() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val reassembler = FrameReassembler()

        val results = reassembler.feed(HdlcFrame.encode(payload))

        assertEquals(1, results.size)
        assertContentEquals(payload, payloadOf(results[0]))
    }

    @Test
    fun `bytes before the first flag are discarded as noise`() {
        val payload = byteArrayOf(9, 8, 7)
        val noise = byteArrayOf(0x00, 0x01, 0xFF.toByte())
        val reassembler = FrameReassembler()

        val results = reassembler.feed(noise + HdlcFrame.encode(payload))

        assertEquals(1, results.size)
        assertContentEquals(payload, payloadOf(results[0]))
    }

    // ---------------------------------------------------------------------
    // Multiple frames per chunk
    // ---------------------------------------------------------------------

    @Test
    fun `multiple concatenated frames in one chunk all decode, in order`() {
        val payloads = listOf(
            byteArrayOf(1),
            byteArrayOf(2, 2),
            byteArrayOf(),
            byteArrayOf(4, 4, 4, 4),
        )
        val concatenated = payloads.fold(byteArrayOf()) { acc, p -> acc + HdlcFrame.encode(p) }

        val reassembler = FrameReassembler()
        val results = reassembler.feed(concatenated)

        assertEquals(payloads.size, results.size)
        payloads.zip(results).forEach { (expected, actual) ->
            assertContentEquals(expected, payloadOf(actual))
        }
    }

    @Test
    fun `frames sharing a single delimiter (no double flag) still separate correctly`() {
        // Manually build "0x7E payload1 CRC1 0x7E payload2 CRC2 0x7E" -- a single shared flag
        // between the two frames, rather than two adjacent encode() outputs.
        val p1 = byteArrayOf(0x11, 0x22)
        val p2 = byteArrayOf(0x33, 0x44, 0x55)
        val f1 = HdlcFrame.encode(p1)
        val f2 = HdlcFrame.encode(p2)
        // f1 ends with 0x7E and f2 starts with 0x7E; merge those into one shared byte.
        val merged = f1.copyOfRange(0, f1.size - 1) + f2

        val reassembler = FrameReassembler()
        val results = reassembler.feed(merged)

        assertEquals(2, results.size)
        assertContentEquals(p1, payloadOf(results[0]))
        assertContentEquals(p2, payloadOf(results[1]))
    }

    // ---------------------------------------------------------------------
    // A frame split across reads, at arbitrary byte offsets
    // ---------------------------------------------------------------------

    @Test
    fun `a frame split byte-by-byte across many feed calls still decodes`() {
        val payload = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0x7E, 0x7D, 0x01)
        val framed = HdlcFrame.encode(payload)

        val reassembler = FrameReassembler()
        val allResults = mutableListOf<TonexResult<ByteArray>>()
        for (b in framed) {
            allResults += reassembler.feed(byteArrayOf(b))
        }

        assertEquals(1, allResults.size)
        assertContentEquals(payload, payloadOf(allResults[0]))
    }

    @Test
    fun `a chunk split exactly in the middle of an escape sequence still decodes`() {
        // Payload chosen so the stuffed frame definitely contains a 0x7D,escaped-byte pair,
        // and we split the chunk stream so the escape marker and its escaped byte land in two
        // different feed() calls.
        val payload = byteArrayOf(0x01, 0x7E, 0x02)
        val framed = HdlcFrame.encode(payload)
        val escapeIndex = framed.indexOfFirst { it == 0x7D.toByte() }
        assertTrue(escapeIndex in 0 until framed.size - 1, "test setup failure: no escape byte found")

        val splitPoint = escapeIndex + 1 // right after the 0x7D, before its escaped byte
        val reassembler = FrameReassembler()
        val results = reassembler.feed(framed.copyOfRange(0, splitPoint)) +
            reassembler.feed(framed.copyOfRange(splitPoint, framed.size))

        assertEquals(1, results.size)
        assertContentEquals(payload, payloadOf(results[0]))
    }

    @Test
    fun `random split points across many frames all reassemble correctly`() {
        val random = Random(seed = 424242)
        repeat(200) { iteration ->
            val payload = ByteArray(random.nextInt(0, 40)) { random.nextInt(0, 256).toByte() }
            val framed = HdlcFrame.encode(payload)

            // Break the framed bytes into 1..framed.size random-sized chunks.
            val chunks = mutableListOf<ByteArray>()
            var offset = 0
            while (offset < framed.size) {
                val remaining = framed.size - offset
                val take = if (remaining == 1) 1 else random.nextInt(1, remaining + 1)
                chunks += framed.copyOfRange(offset, offset + take)
                offset += take
            }

            val reassembler = FrameReassembler()
            val results = chunks.flatMap { reassembler.feed(it) }

            assertEquals(1, results.size, "iteration $iteration produced ${results.size} results")
            assertContentEquals(payload, payloadOf(results[0]), "iteration $iteration payload mismatch")
        }
    }

    @Test
    fun `empty chunks are a no-op and do not disturb reassembly`() {
        val payload = byteArrayOf(1, 2, 3)
        val framed = HdlcFrame.encode(payload)
        val mid = framed.size / 2

        val reassembler = FrameReassembler()
        val results = reassembler.feed(byteArrayOf()) +
            reassembler.feed(framed.copyOfRange(0, mid)) +
            reassembler.feed(byteArrayOf()) +
            reassembler.feed(framed.copyOfRange(mid, framed.size)) +
            reassembler.feed(byteArrayOf())

        assertEquals(1, results.size)
        assertContentEquals(payload, payloadOf(results[0]))
    }

    // ---------------------------------------------------------------------
    // Errors surface without derailing subsequent frames
    // ---------------------------------------------------------------------

    @Test
    fun `a corrupted frame surfaces CrcMismatch and does not prevent the next frame decoding`() {
        val goodPayload1 = byteArrayOf(1, 2, 3)
        val badPayload = byteArrayOf(9, 9, 9)
        val goodPayload2 = byteArrayOf(4, 5, 6)

        val framed1 = HdlcFrame.encode(goodPayload1)
        val framedBad = HdlcFrame.encode(badPayload).also { it[it.size - 2] = (it[it.size - 2].toInt() xor 0x01).toByte() }
        val framed2 = HdlcFrame.encode(goodPayload2)

        val reassembler = FrameReassembler()
        val results = reassembler.feed(framed1 + framedBad + framed2)

        assertEquals(3, results.size)
        assertContentEquals(goodPayload1, payloadOf(results[0]))
        val badResult = results[1]
        assertIs<TonexResult.Failure>(badResult)
        assertIs<TonexError.CrcMismatch>(badResult.error)
        assertContentEquals(goodPayload2, payloadOf(results[2]))
    }

    @Test
    fun `an incomplete final frame at end of stream produces no result yet`() {
        val payload = byteArrayOf(1, 2, 3, 4)
        val framed = HdlcFrame.encode(payload)
        val incomplete = framed.copyOfRange(0, framed.size - 2) // never see the closing flag

        val reassembler = FrameReassembler()
        val results = reassembler.feed(incomplete)

        assertEquals(0, results.size)
    }

    @Test
    fun `a dangling escape byte at the very end of a chunk is resolved once the next chunk arrives`() {
        val payload = byteArrayOf(0x7D, 0x01) // guarantees an escape marker in the stuffed frame
        val framed = HdlcFrame.encode(payload)
        val escapeIndex = framed.indexOfFirst { it == 0x7D.toByte() }
        assertTrue(escapeIndex >= 0)

        val reassembler = FrameReassembler()
        val firstResults = reassembler.feed(framed.copyOfRange(0, escapeIndex + 1))
        assertEquals(0, firstResults.size, "must not emit or misinterpret anything mid-escape")

        val secondResults = reassembler.feed(framed.copyOfRange(escapeIndex + 1, framed.size))
        assertEquals(1, secondResults.size)
        assertContentEquals(payload, payloadOf(secondResults[0]))
    }

    // ---------------------------------------------------------------------
    // Runaway-stream protection (unbounded-buffer cap)
    // ---------------------------------------------------------------------

    @Test
    fun `a stream with no closing flag past the cap emits exactly one MalformedFrame, not one per byte`() {
        // Well past the cap, and well past it again, with no delimiter anywhere -- a stuck OTG
        // link. Avoid 0x7E/0x7D so this is pure unescaped content and isn't accidentally
        // resynchronizing partway through.
        val junk = ByteArray(FrameReassembler.MAX_FRAME_CONTENT_BYTES * 10) { 0x41 }

        val reassembler = FrameReassembler()
        val results = reassembler.feed(byteArrayOf(0x7E) + junk)

        assertEquals(1, results.size, "expected exactly one overflow error, got ${results.size}")
        val failure = results[0]
        assertIs<TonexResult.Failure>(failure)
        assertIs<TonexError.MalformedFrame>(failure.error)
    }

    @Test
    fun `overflow is also silent across multiple feed calls, not just within one chunk`() {
        val reassembler = FrameReassembler()
        val firstResults = reassembler.feed(byteArrayOf(0x7E) + ByteArray(FrameReassembler.MAX_FRAME_CONTENT_BYTES + 1) { 0x41 })
        assertEquals(1, firstResults.size)
        assertIs<TonexError.MalformedFrame>((firstResults[0] as TonexResult.Failure).error)

        // Keep feeding junk, still no closing flag: must not emit a second error.
        val secondResults = reassembler.feed(ByteArray(FrameReassembler.MAX_FRAME_CONTENT_BYTES * 5) { 0x42 })
        assertEquals(0, secondResults.size, "overflow must not re-fire per byte on a still-stuck stream")
    }

    @Test
    fun `a valid frame arriving after an overflow decodes correctly (resync recovery)`() {
        val junk = ByteArray(FrameReassembler.MAX_FRAME_CONTENT_BYTES * 3) { 0x41 }
        val recoveryPayload = byteArrayOf(0x10, 0x20, 0x30, 0x40, 0x50)
        val recoveryFrame = HdlcFrame.encode(recoveryPayload)

        val reassembler = FrameReassembler()
        val results = reassembler.feed(byteArrayOf(0x7E) + junk + recoveryFrame)

        assertEquals(2, results.size, "expected one overflow error followed by one successful frame")

        val overflow = results[0]
        assertIs<TonexResult.Failure>(overflow)
        assertIs<TonexError.MalformedFrame>(overflow.error)

        assertContentEquals(recoveryPayload, payloadOf(results[1]))
    }

    @Test
    fun `a valid frame arriving after an overflow still decodes correctly when split across feed calls`() {
        // Same recovery scenario as above, but the overflow and the recovery frame arrive in
        // separate reads, matching how a real reconnect/resync would actually show up.
        val junk = ByteArray(FrameReassembler.MAX_FRAME_CONTENT_BYTES * 2) { 0x41 }
        val recoveryPayload = byteArrayOf(0x9, 0x8, 0x7)
        val recoveryFrame = HdlcFrame.encode(recoveryPayload)

        val reassembler = FrameReassembler()
        val overflowResults = reassembler.feed(byteArrayOf(0x7E) + junk)
        assertEquals(1, overflowResults.size)
        assertIs<TonexError.MalformedFrame>((overflowResults[0] as TonexResult.Failure).error)

        val recoveryResults = reassembler.feed(recoveryFrame)
        assertEquals(1, recoveryResults.size)
        assertContentEquals(recoveryPayload, payloadOf(recoveryResults[0]))
    }

    @Test
    fun `a legitimate large frame just under the cap still decodes fine`() {
        // Content (payload + 2 CRC bytes) lands one byte under the cap, so this must NOT trip
        // the overflow path -- the cap must not be so tight it rejects real large traffic (e.g.
        // a preset-details response).
        val random = Random(seed = 99)
        val payloadSize = FrameReassembler.MAX_FRAME_CONTENT_BYTES - 2 - 1
        val payload = ByteArray(payloadSize) { random.nextInt(0, 256).toByte() }

        val reassembler = FrameReassembler()
        val results = reassembler.feed(HdlcFrame.encode(payload))

        assertEquals(1, results.size)
        assertContentEquals(payload, payloadOf(results[0]))
    }

    @Test
    fun `content of exactly the cap size decodes fine, one byte more overflows`() {
        val random = Random(seed = 100)

        val exactPayloadSize = FrameReassembler.MAX_FRAME_CONTENT_BYTES - 2
        val exactPayload = ByteArray(exactPayloadSize) { random.nextInt(0, 256).toByte() }
        val exactResults = FrameReassembler().feed(HdlcFrame.encode(exactPayload))
        assertEquals(1, exactResults.size, "content exactly at the cap must decode, not overflow")
        assertContentEquals(exactPayload, payloadOf(exactResults[0]))

        val overSize = exactPayloadSize + 1
        val overPayload = ByteArray(overSize) { random.nextInt(0, 256).toByte() }
        val overResults = FrameReassembler().feed(HdlcFrame.encode(overPayload))
        assertEquals(1, overResults.size)
        assertIs<TonexError.MalformedFrame>((overResults[0] as TonexResult.Failure).error)
    }

    // ---------------------------------------------------------------------
    // A raw flag is always a delimiter, even with a dangling escape pending
    //
    // Regression coverage: a 0x7D immediately followed by 0x7E can never be produced by a
    // correct encoder (see the class KDoc), so the flag must win -- an earlier version of this
    // reassembler let escapePending swallow the next frame's opening flag as content instead,
    // silently losing the frame that followed. These tests pin the fix.
    // ---------------------------------------------------------------------

    @Test
    fun `a dangling escape immediately before a delimiter emits a MalformedFrame, not a silent swallow`() {
        // 0x7E (open), 0x01 0x02 (ordinary content), 0x7D (dangling escape marker), 0x7E (close).
        val malformed = byteArrayOf(0x7E, 0x01, 0x02, 0x7D, 0x7E)

        val results = FrameReassembler().feed(malformed)

        assertEquals(1, results.size)
        val failure = results[0]
        assertIs<TonexResult.Failure>(failure)
        assertIs<TonexError.MalformedFrame>(failure.error)
    }

    @Test
    fun `a dangling escape immediately before a delimiter resyncs and the following valid frame still decodes`() {
        val malformed = byteArrayOf(0x7E, 0x01, 0x02, 0x7D, 0x7E)
        val recoveryPayload = byteArrayOf(0x11, 0x22, 0x33)
        val recoveryFrame = HdlcFrame.encode(recoveryPayload)

        val results = FrameReassembler().feed(malformed + recoveryFrame)

        assertEquals(2, results.size, "expected one dangling-escape error followed by one successful frame")
        val failure = results[0]
        assertIs<TonexResult.Failure>(failure)
        assertIs<TonexError.MalformedFrame>(failure.error)
        assertContentEquals(recoveryPayload, payloadOf(results[1]))
    }

    @Test
    fun `a dangling escape split across a feed call boundary still resyncs and the next frame decodes`() {
        // The 0x7D lands in one chunk, the delimiter that follows it lands in the next -- exercises
        // that escapePending correctly carries across feed() calls into this path too.
        val recoveryPayload = byteArrayOf(0x44, 0x55)
        val recoveryFrame = HdlcFrame.encode(recoveryPayload)

        val reassembler = FrameReassembler()
        val firstResults = reassembler.feed(byteArrayOf(0x7E, 0x01, 0x02, 0x7D))
        assertEquals(0, firstResults.size, "no delimiter seen yet -- nothing should be emitted")

        val secondResults = reassembler.feed(byteArrayOf(0x7E) + recoveryFrame)

        assertEquals(2, secondResults.size)
        val failure = secondResults[0]
        assertIs<TonexResult.Failure>(failure)
        assertIs<TonexError.MalformedFrame>(failure.error)
        assertContentEquals(recoveryPayload, payloadOf(secondResults[1]))
    }

    @Test
    fun `a dangling escape left over from an overflow episode does not swallow the next valid frame`() {
        // Exact reported repro: strand the parser on a dangling 0x7D right after an overflow,
        // then send a perfectly valid frame. The valid frame must not be lost.
        val payload = byteArrayOf(0xAA.toByte(), 0xBB.toByte())
        val reassembler = FrameReassembler()

        val openResults = reassembler.feed(byteArrayOf(0x7E))
        assertEquals(0, openResults.size)

        val overflowResults = (1..20).flatMap { reassembler.feed(ByteArray(1024) { 0x41 }) }
        assertEquals(1, overflowResults.size, "expected exactly one overflow error")
        assertIs<TonexError.MalformedFrame>((overflowResults[0] as TonexResult.Failure).error)

        val danglingEscapeResults = reassembler.feed(byteArrayOf(0x7D))
        assertEquals(0, danglingEscapeResults.size, "no delimiter seen yet -- nothing should be emitted")

        val recoveryResults = reassembler.feed(HdlcFrame.encode(payload))

        // The dangling escape is itself a genuine, distinct anomaly and is reported as such
        // (never silently absorbed, per FR11) -- but the important part is that the good frame
        // that follows is NOT lost.
        assertEquals(2, recoveryResults.size)
        val danglingEscapeFailure = recoveryResults[0]
        assertIs<TonexResult.Failure>(danglingEscapeFailure)
        assertIs<TonexError.MalformedFrame>(danglingEscapeFailure.error)
        assertContentEquals(payload, payloadOf(recoveryResults[1]))
    }
}
