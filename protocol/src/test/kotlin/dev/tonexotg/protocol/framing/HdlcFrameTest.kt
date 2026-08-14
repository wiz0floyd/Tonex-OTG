package dev.tonexotg.protocol.framing

import dev.tonexotg.protocol.TonexError
import dev.tonexotg.protocol.TonexResult
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HdlcFrameTest {

    // ---------------------------------------------------------------------
    // Round-trip properties
    // ---------------------------------------------------------------------

    @Test
    fun `round-trip - random payloads survive encode then decode intact`() {
        val random = Random(seed = 20260814)
        repeat(500) { iteration ->
            val length = random.nextInt(0, 300)
            val payload = ByteArray(length) { random.nextInt(0, 256).toByte() }

            val framed = HdlcFrame.encode(payload)
            val result = HdlcFrame.decode(framed)

            assertIs<TonexResult.Success<ByteArray>>(result, "iteration $iteration failed to decode")
            assertContentEquals(payload, result.value, "iteration $iteration payload mismatch")
        }
    }

    @Test
    fun `round-trip - empty payload`() {
        val framed = HdlcFrame.encode(byteArrayOf())
        val result = HdlcFrame.decode(framed)
        assertIs<TonexResult.Success<ByteArray>>(result)
        assertContentEquals(byteArrayOf(), result.value)
    }

    @Test
    fun `round-trip - every single byte value 0 to 255`() {
        for (value in 0..255) {
            val payload = byteArrayOf(value.toByte())
            val result = HdlcFrame.decode(HdlcFrame.encode(payload))
            assertIs<TonexResult.Success<ByteArray>>(result, "value 0x%02X failed".format(value))
            assertContentEquals(payload, result.value)
        }
    }

    @Test
    fun `encoded frame always starts and ends with the flag byte`() {
        val random = Random(seed = 7)
        repeat(50) {
            val payload = ByteArray(random.nextInt(0, 64)) { random.nextInt(0, 256).toByte() }
            val framed = HdlcFrame.encode(payload)
            assertEquals(0x7E.toByte(), framed.first())
            assertEquals(0x7E.toByte(), framed.last())
        }
    }

    // ---------------------------------------------------------------------
    // Bytes requiring escaping
    // ---------------------------------------------------------------------

    @Test
    fun `payload containing 0x7E and 0x7D round-trips correctly`() {
        val payload = byteArrayOf(0x01, 0x7E, 0x02, 0x7D, 0x03)
        val framed = HdlcFrame.encode(payload)

        // Confirm stuffing actually happened: the flag only appears as the two frame
        // delimiters, nowhere else unescaped.
        assertEquals(0x7E.toByte(), framed.first())
        assertEquals(0x7E.toByte(), framed.last())
        val interior = framed.copyOfRange(1, framed.size - 1)
        assertTrue(interior.none { it == 0x7E.toByte() }, "an unescaped 0x7E leaked into the frame body")

        val result = HdlcFrame.decode(framed)
        assertIs<TonexResult.Success<ByteArray>>(result)
        assertContentEquals(payload, result.value)
    }

    @Test
    fun `payload that is a run of 0x7E and 0x7D bytes round-trips correctly`() {
        val payload = byteArrayOf(0x7E, 0x7E, 0x7D, 0x7D, 0x7E, 0x7D, 0x7E, 0x7E, 0x7D)
        val result = HdlcFrame.decode(HdlcFrame.encode(payload))
        assertIs<TonexResult.Success<ByteArray>>(result)
        assertContentEquals(payload, result.value)
    }

    @Test
    fun `payload entirely of flag bytes round-trips`() {
        val payload = ByteArray(20) { 0x7E }
        val result = HdlcFrame.decode(HdlcFrame.encode(payload))
        assertIs<TonexResult.Success<ByteArray>>(result)
        assertContentEquals(payload, result.value)
    }

    @Test
    fun `payload entirely of escape bytes round-trips`() {
        val payload = ByteArray(20) { 0x7D }
        val result = HdlcFrame.decode(HdlcFrame.encode(payload))
        assertIs<TonexResult.Success<ByteArray>>(result)
        assertContentEquals(payload, result.value)
    }

    @Test
    fun `a CRC that itself contains a byte needing escaping still round-trips`() {
        // Search for a payload whose CRC bytes include 0x7E or 0x7D, to exercise CRC-stuffing
        // specifically (not just payload-stuffing).
        var found = false
        for (seed in 0 until 2000) {
            val payload = byteArrayOf(seed.toByte(), (seed / 7).toByte())
            val crc = Crc16X25.compute(payload)
            val lo = crc and 0xFF
            val hi = (crc ushr 8) and 0xFF
            if (lo == 0x7E || lo == 0x7D || hi == 0x7E || hi == 0x7D) {
                found = true
                val result = HdlcFrame.decode(HdlcFrame.encode(payload))
                assertIs<TonexResult.Success<ByteArray>>(result)
                assertContentEquals(payload, result.value)
            }
        }
        assertTrue(found, "test setup failure: no payload in the search space produced a CRC needing escaping")
    }

    // ---------------------------------------------------------------------
    // Malformed / truncated frames
    // ---------------------------------------------------------------------

    @Test
    fun `decode fails on empty input`() {
        val result = HdlcFrame.decode(byteArrayOf())
        assertIs<TonexResult.Failure>(result)
        assertIs<TonexError.MalformedFrame>(result.error)
    }

    @Test
    fun `decode fails when missing the opening delimiter`() {
        val framed = HdlcFrame.encode(byteArrayOf(1, 2, 3))
        val corrupted = framed.copyOfRange(1, framed.size) // drop leading 0x7E
        val result = HdlcFrame.decode(corrupted)
        assertIs<TonexResult.Failure>(result)
        assertIs<TonexError.MalformedFrame>(result.error)
    }

    @Test
    fun `decode fails when missing the closing delimiter (truncated mid-transmission)`() {
        val framed = HdlcFrame.encode(byteArrayOf(1, 2, 3, 4, 5))
        val truncated = framed.copyOfRange(0, framed.size - 3) // cut off before the flag arrives
        val result = HdlcFrame.decode(truncated)
        assertIs<TonexResult.Failure>(result)
        assertIs<TonexError.MalformedFrame>(result.error)
    }

    @Test
    fun `decode fails on a frame too short to contain a CRC`() {
        // 0x7E, one content byte, 0x7E -- one byte cannot hold a two-byte CRC.
        val result = HdlcFrame.decode(byteArrayOf(0x7E, 0x01, 0x7E))
        assertIs<TonexResult.Failure>(result)
        assertIs<TonexError.MalformedFrame>(result.error)
    }

    @Test
    fun `decode fails on a dangling escape byte at the end of the frame`() {
        // 0x7E, 0x7D with nothing after it before the closing flag.
        val result = HdlcFrame.decode(byteArrayOf(0x7E, 0x01, 0x7D, 0x7E))
        assertIs<TonexResult.Failure>(result)
        assertIs<TonexError.MalformedFrame>(result.error)
    }

    @Test
    fun `decode fails on an unescaped flag byte inside the frame body`() {
        val result = HdlcFrame.decode(byteArrayOf(0x7E, 0x01, 0x7E, 0x02, 0x7E))
        assertIs<TonexResult.Failure>(result)
        assertIs<TonexError.MalformedFrame>(result.error)
    }

    // ---------------------------------------------------------------------
    // Corrupted CRC
    // ---------------------------------------------------------------------

    @Test
    fun `decode fails with CrcMismatch when the CRC bytes are corrupted`() {
        val payload = byteArrayOf(0x10, 0x20, 0x30, 0x40)
        val framed = HdlcFrame.encode(payload)

        // Flip a bit in the byte right before the closing flag (the last CRC byte).
        val corrupted = framed.copyOf()
        corrupted[corrupted.size - 2] = (corrupted[corrupted.size - 2].toInt() xor 0x01).toByte()

        val result = HdlcFrame.decode(corrupted)
        assertIs<TonexResult.Failure>(result)
        assertIs<TonexError.CrcMismatch>(result.error)
    }

    @Test
    fun `decode fails with CrcMismatch when a payload byte is corrupted in transit`() {
        val payload = byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55)
        val framed = HdlcFrame.encode(payload)

        // Corrupt the first payload byte, right after the opening flag; this changes the
        // effective payload without changing the trailing CRC, so it must be caught.
        val corrupted = framed.copyOf()
        corrupted[1] = (corrupted[1].toInt() xor 0xFF).toByte()

        val result = HdlcFrame.decode(corrupted)
        assertIs<TonexResult.Failure>(result)
        val error = result.error
        assertIs<TonexError.CrcMismatch>(error)
        assertTrue(error.expected != error.actual)
    }

    @Test
    fun `CrcMismatch never smuggles a payload through`() {
        // Belt-and-suspenders: a corrupted frame's failure must be a Failure, never a Success
        // with a wrong-but-present payload.
        val payload = "hello tonex".toByteArray(Charsets.UTF_8)
        val framed = HdlcFrame.encode(payload)
        val corrupted = framed.copyOf()
        corrupted[corrupted.size - 3] = (corrupted[corrupted.size - 3].toInt() xor 0x40).toByte()

        val result = HdlcFrame.decode(corrupted)
        assertTrue(result is TonexResult.Failure, "corrupted frame must not decode successfully")
    }
}
