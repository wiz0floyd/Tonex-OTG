package dev.tonexotg.protocol.framing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Known-answer and regression tests for [Crc16X25].
 *
 * The pinned hex values below were computed by independently running the exact bitwise
 * algorithm quoted in issue #8 (reflected poly `0x8408`, init `0xFFFF`, final xor `0xFFFF`)
 * against each input, not copied from [Crc16X25]'s own implementation — they also match the
 * published "check" values for CRC-16/X-25 and CRC-16/CCITT-FALSE in the standard CRC
 * catalogue (`check("123456789") = 0x906E` / `0x29B1` respectively), which is independent
 * confirmation that both algorithms below are implemented correctly.
 */
class Crc16X25Test {

    @Test
    fun `standard check value for ASCII 123456789`() {
        // The canonical CRC catalogue "check" input/output pair for CRC-16/X-25.
        val input = "123456789".toByteArray(Charsets.US_ASCII)
        assertEquals(0x906E, Crc16X25.compute(input))
    }

    @Test
    fun `known-answer vectors`() {
        val cases = listOf(
            byteArrayOf() to 0x0000,
            byteArrayOf(0x00) to 0xF078,
            byteArrayOf(0xFF.toByte()) to 0xFF00,
            byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05) to 0x22EC,
            byteArrayOf(0x7E) to 0x6A81,
            byteArrayOf(0x7D) to 0x581A,
        )
        for ((input, expected) in cases) {
            assertEquals(
                expected,
                Crc16X25.compute(input),
                "CRC mismatch for input ${input.joinToString(",") { "0x%02X".format(it) }}",
            )
        }
    }

    @Test
    fun `result is stable across repeated calls`() {
        val input = byteArrayOf(0x10, 0x20, 0x30, 0x7E, 0x7D, 0x40)
        val first = Crc16X25.compute(input)
        val second = Crc16X25.compute(input)
        assertEquals(first, second)
    }

    /**
     * The regression this whole test class exists to pin: implementing CCITT-FALSE (non-reflected
     * poly `0x1021`, init `0xFFFF`, no final xor — the "textbook" CCITT algorithm, and what
     * upstream's imprecise "CRC-CCITT" naming would lead someone to implement) must NOT produce
     * the same result as CRC-16/X-25 for the same input. If this test ever passes with the two
     * values equal, the X-25 implementation has silently drifted into CCITT-FALSE.
     */
    @Test
    fun `X-25 differs from CCITT-FALSE for the same input`() {
        val inputs = listOf(
            "123456789".toByteArray(Charsets.US_ASCII),
            byteArrayOf(0x01, 0x02, 0x03),
            byteArrayOf(0x7E, 0x7D, 0x00, 0xFF.toByte()),
        )
        for (input in inputs) {
            assertNotEquals(
                crc16CcittFalseReference(input),
                Crc16X25.compute(input),
                "X-25 and CCITT-FALSE unexpectedly agree for ${input.joinToString(",") { "0x%02X".format(it) }}",
            )
        }
    }

    @Test
    fun `CCITT-FALSE reference implementation matches its own standard check value`() {
        // Sanity check on the reference implementation used above, so a bug in *it* can't
        // accidentally make the divergence test pass for the wrong reason.
        val input = "123456789".toByteArray(Charsets.US_ASCII)
        assertEquals(0x29B1, crc16CcittFalseReference(input))
    }

    /**
     * A from-scratch, deliberately independent implementation of CRC-16/CCITT-FALSE (non-reflected
     * poly `0x1021`, init `0xFFFF`, xorout `0x0000`) used only as a regression fixture for
     * [X-25 differs from CCITT-FALSE for the same input]. This is intentionally NOT how
     * [Crc16X25] is implemented — that is the entire point of the test.
     */
    private fun crc16CcittFalseReference(bytes: ByteArray): Int {
        var crc = 0xFFFF
        for (byte in bytes) {
            crc = crc xor ((byte.toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) {
                    ((crc shl 1) xor 0x1021) and 0xFFFF
                } else {
                    (crc shl 1) and 0xFFFF
                }
            }
        }
        return crc and 0xFFFF
    }
}
