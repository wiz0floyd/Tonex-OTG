package dev.tonexotg.protocol.message

import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

private fun hex(spec: String): ByteArray =
    spec.trim().split(Regex("\\s+")).map { it.toInt(16).toByte() }.toByteArray()

private fun ByteArray.hex(): String = joinToString(" ") { "%02x".format(it) }

class RequestStateMessageTest {

    /** `usb_tonex_one.c:224` — verified byte-exact literal from issue #11. */
    private val EXPECTED = hex("b9 03 00 82 06 00 80 0b 03 b9 02 81 06 03 0b")

    @Test
    fun `encodes byte-exact against the usb_tonex_one_c request-state literal`() {
        val actual = RequestStateMessage.encode()
        assertTrue(actual.contentEquals(EXPECTED), "expected ${EXPECTED.hex()}, got ${actual.hex()}")
    }

    @Test
    fun `encode is deterministic across calls`() {
        assertTrue(RequestStateMessage.encode().contentEquals(RequestStateMessage.encode()))
    }
}
