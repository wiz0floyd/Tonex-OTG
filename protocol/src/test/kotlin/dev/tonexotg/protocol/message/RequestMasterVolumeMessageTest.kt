package dev.tonexotg.protocol.message

import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

private fun hex(spec: String): ByteArray =
    spec.trim().split(Regex("\\s+")).map { it.toInt(16).toByte() }.toByteArray()

private fun ByteArray.hex(): String = joinToString(" ") { "%02x".format(it) }

class RequestMasterVolumeMessageTest {

    /**
     * `usb_tonex_one.c:347` (`usb_tonex_one_request_master_volume`) — doubly confirmed: also row 4
     * of `MessageHeaderCodec`'s own reference table.
     */
    private val EXPECTED = hex("b9 03 81 0d 03 82 05 00 80 0b 03 b9 03 03 00 00")

    @Test
    fun `encodes byte-exact against the usb_tonex_one_c request-master-volume literal`() {
        val actual = RequestMasterVolumeMessage.encode()
        assertTrue(actual.contentEquals(EXPECTED), "expected ${EXPECTED.hex()}, got ${actual.hex()}")
    }

    @Test
    fun `encode is deterministic across calls`() {
        assertTrue(RequestMasterVolumeMessage.encode().contentEquals(RequestMasterVolumeMessage.encode()))
    }

    @Test
    fun `message is 16 bytes total`() {
        assertTrue(RequestMasterVolumeMessage.encode().size == 16)
    }
}
