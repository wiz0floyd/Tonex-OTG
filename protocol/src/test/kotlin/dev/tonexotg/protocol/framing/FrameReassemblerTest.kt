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
}
