package dev.tonexotg.protocol.framing

import dev.tonexotg.protocol.TonexError
import dev.tonexotg.protocol.TonexResult
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Incrementally reassembles HDLC-style frames out of a live byte stream that arrives in
 * arbitrarily-sized chunks — exactly the shape [dev.tonexotg.protocol.TonexTransport.incoming]
 * promises: "a frame may be split across multiple emissions, and one emission may contain
 * multiple frames back to back."
 *
 * Call [feed] with each chunk as it arrives, in order, on a single instance. Each call returns
 * the [TonexResult]s for every frame *completed* by that chunk (zero, one, or many); bytes
 * belonging to a still-incomplete frame are buffered internally and carried forward to the next
 * [feed] call. An instance is not thread-safe — feed it from a single reader, matching the
 * ordering guarantee [dev.tonexotg.protocol.TonexTransport.incoming] already provides.
 *
 * ### Escape-aware scanning
 *
 * Upstream's frame-end scanner looks for the next raw `0x7E` byte without tracking whether it is
 * preceded by an unconsumed `0x7D` escape marker. That happens to be safe *for upstream's own
 * encoder*, because a byte that is itself `0x7E` is never emitted unescaped once stuffed — but it
 * is fragile: a scanner that doesn't track escape state cannot tell a real delimiter from an
 * escaped payload byte that happens to decode to `0x7E`, and would misframe if it ever received
 * one from a source that isn't equally careful. This implementation is escape-aware instead: a
 * `0x7D` puts the scanner into an "next byte is escaped content, not a delimiter" state for
 * exactly one byte, and that state is preserved across a [feed] call boundary if the chunk ends
 * mid-escape-sequence.
 *
 * ### Resynchronization
 *
 * Two delimiters with no content between them (`0x7E 0x7E`, which is what you get whenever two
 * frames are transmitted back to back and each carries both of its own delimiters) are treated
 * as an idle/resync marker, not as a malformed zero-length frame — no [TonexResult] is emitted
 * for them. Bytes seen before the very first `0x7E` this instance has ever observed are treated
 * as pre-frame noise and discarded; once a frame has started, all bytes up to its closing
 * delimiter are buffered as that frame's content.
 */
class FrameReassembler {

    private var sawOpeningFlag = false
    private var buffer = ByteArrayOutputStream()
    private var escapePending = false

    /**
     * Feeds the next chunk of bytes read off the wire into the reassembler.
     *
     * @param chunk raw bytes as delivered by the transport. May be empty, may split a frame at
     *   any byte offset (including mid-escape-sequence), and may contain several frames.
     * @return the decoded result for each frame this chunk completed, in the order their closing
     *   delimiters appeared. Empty if [chunk] did not complete any frame.
     */
    fun feed(chunk: ByteArray): List<TonexResult<ByteArray>> {
        if (chunk.isEmpty()) return emptyList()

        val results = mutableListOf<TonexResult<ByteArray>>()
        for (b in chunk) {
            if (!sawOpeningFlag) {
                if (b == FRAME_FLAG) {
                    sawOpeningFlag = true
                }
                continue
            }

            when {
                escapePending -> {
                    buffer.write((b.toInt() xor FRAME_ESCAPE_XOR.toInt()) and 0xFF)
                    escapePending = false
                }
                b == FRAME_ESCAPE -> {
                    escapePending = true
                }
                b == FRAME_FLAG -> {
                    val content = buffer.toByteArray()
                    buffer = ByteArrayOutputStream()
                    if (content.isNotEmpty()) {
                        results.add(finishDecoding(content))
                    }
                    // This same flag also serves as the opening delimiter of whatever follows,
                    // so we stay "in frame" (sawOpeningFlag remains true) rather than going idle.
                }
                else -> {
                    buffer.write(b.toInt() and 0xFF)
                }
            }
        }
        return results
    }
}

/**
 * Adapts a [FrameReassembler] to the `Flow<ByteArray>` shape of
 * [dev.tonexotg.protocol.TonexTransport.incoming]: collects the upstream flow of raw chunks and
 * emits one [TonexResult] per complete frame found, in order.
 *
 * A dangling partial frame at the end of the underlying flow (e.g. the transport disconnects
 * mid-frame) is silently dropped rather than emitted as an error — there is no further data
 * coming that could complete it, and [dev.tonexotg.protocol.TonexError.TransportFailure] /
 * disconnect handling above this layer is the more meaningful signal for that situation.
 */
fun Flow<ByteArray>.decodeFrames(): Flow<TonexResult<ByteArray>> {
    val reassembler = FrameReassembler()
    return flow {
        collect { chunk ->
            for (result in reassembler.feed(chunk)) {
                emit(result)
            }
        }
    }
}
