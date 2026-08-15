package dev.tonexotg.protocol.message

import kotlin.test.Test
import kotlin.test.assertTrue

private fun hex(spec: String): ByteArray =
    spec.trim().split(Regex("\\s+")).map { it.toInt(16).toByte() }.toByteArray()

private fun ByteArray.hex(): String = joinToString(" ") { "%02x".format(it) }

class HelloMessageTest {

    /** `usb_tonex_one.c:204` — verified byte-exact literal from issue #11. */
    private val EXPECTED = hex("b9 03 00 82 04 00 80 0b 01 b9 02 02 0b")

    @Test
    fun `encodes byte-exact against the usb_tonex_one_c hello literal`() {
        val actual = HelloMessage.encode()
        assertTrue(actual.contentEquals(EXPECTED), "expected ${EXPECTED.hex()}, got ${actual.hex()}")
    }

    @Test
    fun `encode is deterministic across calls`() {
        assertTrue(HelloMessage.encode().contentEquals(HelloMessage.encode()))
    }
}
