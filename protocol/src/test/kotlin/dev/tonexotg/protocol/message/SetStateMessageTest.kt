package dev.tonexotg.protocol.message

import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

private fun ByteArray.hex(): String = joinToString(" ") { "%02x".format(it) }

/**
 * Byte-exact tests against `usb_tonex_one_set_active_slot`/`_set_preset_in_slot`/`_set_ab_slots`
 * (`usb_tonex_one.c:412`/`450`/`565` — identical envelope in all three).
 *
 * [SetStateMessage.encode] is `internal`, not `public` — reachable here because this test file is
 * in the same Gradle module (`:protocol`) as `src/main`, which Kotlin treats as a friend source
 * set (same pattern already used by `StateBlobPatcherTest`).
 */
class SetStateMessageTest {

    @Test
    fun `header is byte-exact for a 512-byte blob - usb_tonex_one_c 412`() {
        val blob = ByteArray(512) { it.toByte() }
        val encoded = SetStateMessage.encode(blob)

        val expectedHeader = byteArrayOf(
            0xB9.toByte(), 0x03, 0x81.toByte(), 0x06, 0x03, 0x82.toByte(), 0x00, 0x02, 0x80.toByte(), 0x0B, 0x03,
        )
        val actualHeader = encoded.copyOfRange(0, 11)
        assertTrue(
            actualHeader.contentEquals(expectedHeader),
            "expected ${expectedHeader.hex()}, got ${actualHeader.hex()}",
        )
    }

    @Test
    fun `header is byte-exact for a 200-byte blob`() {
        val blob = ByteArray(200) { it.toByte() }
        val encoded = SetStateMessage.encode(blob)

        val expectedHeader = byteArrayOf(
            0xB9.toByte(), 0x03, 0x81.toByte(), 0x06, 0x03, 0x82.toByte(), 0xC8.toByte(), 0x00, 0x80.toByte(), 0x0B, 0x03,
        )
        val actualHeader = encoded.copyOfRange(0, 11)
        assertTrue(
            actualHeader.contentEquals(expectedHeader),
            "expected ${expectedHeader.hex()}, got ${actualHeader.hex()}",
        )
    }

    @Test
    fun `payload is the patched blob verbatim, with no prefix - 512 byte case`() {
        val blob = ByteArray(512) { (it * 3 + 1).toByte() }
        val encoded = SetStateMessage.encode(blob)

        assertTrue(encoded.copyOfRange(11, encoded.size).contentEquals(blob))
        assertTrue(encoded.size == 11 + blob.size)
    }

    @Test
    fun `payload is the patched blob verbatim, with no prefix - 200 byte case`() {
        val blob = ByteArray(200) { (it * 5 + 7).toByte() }
        val encoded = SetStateMessage.encode(blob)

        assertTrue(encoded.copyOfRange(11, encoded.size).contentEquals(blob))
        assertTrue(encoded.size == 11 + blob.size)
    }

    @Test
    fun `encode is compiled as an internal (name-mangled) method, not an ordinary public one`() {
        // Kotlin compiles `internal` to a public, name-mangled JVM method (e.g. "encode$protocol")
        // rather than a genuinely private one - see SessionId.create's KDoc for the same pattern.
        // What this test actually pins down: there is exactly one `encode` overload, and its
        // compiled name is mangled, distinguishing it from HelloMessage/RequestStateMessage's
        // ordinary public `fun encode()` (unmangled name, no capability gate needed).
        val encodeMethods = SetStateMessage::class.java.declaredMethods.filter { it.name.startsWith("encode") }
        assertTrue(encodeMethods.size == 1, "expected exactly one encode method, found: $encodeMethods")
        assertTrue(
            encodeMethods.single().name.contains("$"),
            "SetStateMessage.encode must be `internal` (mangled name) - found unmangled ${encodeMethods.single().name}",
        )
    }
}
