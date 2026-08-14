package dev.tonexotg.protocol.framing

import app.cash.turbine.test
import dev.tonexotg.protocol.TonexError
import dev.tonexotg.protocol.TonexResult
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertIs

/**
 * Exercises [decodeFrames] against the exact shape [dev.tonexotg.protocol.TonexTransport.incoming]
 * promises: a `Flow<ByteArray>` whose emission boundaries carry no protocol meaning.
 */
class FlowFramingTest {

    @Test
    fun `decodeFrames emits one result per frame across multiple chunk emissions`() = runTest {
        val payload1 = byteArrayOf(1, 2, 3)
        val payload2 = byteArrayOf(4, 5, 6, 7)
        val framed1 = HdlcFrame.encode(payload1)
        val framed2 = HdlcFrame.encode(payload2)

        // Split framed1 across two emissions to simulate a frame split across transport reads,
        // then deliver framed2 whole, then split further mid-stream.
        val chunks = listOf(
            framed1.copyOfRange(0, framed1.size / 2),
            framed1.copyOfRange(framed1.size / 2, framed1.size),
            framed2,
        )

        flowOf(*chunks.toTypedArray()).decodeFrames().test {
            val first = awaitItem()
            assertIs<TonexResult.Success<ByteArray>>(first)
            assertContentEquals(payload1, first.value)

            val second = awaitItem()
            assertIs<TonexResult.Success<ByteArray>>(second)
            assertContentEquals(payload2, second.value)

            awaitComplete()
        }
    }

    @Test
    fun `decodeFrames emits multiple frames found within a single chunk emission`() = runTest {
        val payload1 = byteArrayOf(10)
        val payload2 = byteArrayOf(20, 21)
        val combined = HdlcFrame.encode(payload1) + HdlcFrame.encode(payload2)

        flowOf(combined).decodeFrames().test {
            assertContentEquals(payload1, (awaitItem() as TonexResult.Success<ByteArray>).value)
            assertContentEquals(payload2, (awaitItem() as TonexResult.Success<ByteArray>).value)
            awaitComplete()
        }
    }

    @Test
    fun `decodeFrames surfaces a CrcMismatch as a Failure item without terminating the flow`() = runTest {
        val payload = byteArrayOf(1, 1, 1)
        val framed = HdlcFrame.encode(payload).also {
            it[it.size - 2] = (it[it.size - 2].toInt() xor 0x01).toByte()
        }
        val goodPayload = byteArrayOf(9)
        val goodFramed = HdlcFrame.encode(goodPayload)

        flowOf(framed, goodFramed).decodeFrames().test {
            val bad = awaitItem()
            assertIs<TonexResult.Failure>(bad)
            assertIs<TonexError.CrcMismatch>(bad.error)

            val good = awaitItem()
            assertContentEquals(goodPayload, (good as TonexResult.Success<ByteArray>).value)

            awaitComplete()
        }
    }
}
