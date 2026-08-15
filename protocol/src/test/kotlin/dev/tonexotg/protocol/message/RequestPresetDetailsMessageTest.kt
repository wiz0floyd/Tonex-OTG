package dev.tonexotg.protocol.message

import dev.tonexotg.protocol.PresetIndex
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

private fun hex(spec: String): ByteArray =
    spec.trim().split(Regex("\\s+")).map { it.toInt(16).toByte() }.toByteArray()

private fun ByteArray.hex(): String = joinToString(" ") { "%02x".format(it) }

class RequestPresetDetailsMessageTest {

    /**
     * `usb_tonex_one.c:246` — the header + payload prefix, byte-exact for every preset index and
     * kind: only the trailing 2 bytes (`request[15]` = index, `request[16]` = full_details) vary.
     */
    private val FIXED_PREFIX = hex("b9 03 81 00 03 82 06 00 80 0b 03 b9 04 0b 01")

    private fun expected(index: Int, kindWireValue: Int): ByteArray =
        FIXED_PREFIX + byteArrayOf(index.toByte(), kindWireValue.toByte())

    @Test
    fun `every preset index 0 through 19 encodes byte-exact for a SUMMARY request`() {
        for (i in 0..19) {
            val actual = RequestPresetDetailsMessage.encode(PresetIndex(i), PresetDetailsKind.SUMMARY)
            val exp = expected(i, 0x00)
            assertTrue(actual.contentEquals(exp), "index=$i SUMMARY: expected ${exp.hex()}, got ${actual.hex()}")
        }
    }

    @Test
    fun `every preset index 0 through 19 encodes byte-exact for a FULL request`() {
        for (i in 0..19) {
            val actual = RequestPresetDetailsMessage.encode(PresetIndex(i), PresetDetailsKind.FULL)
            val exp = expected(i, 0x01)
            assertTrue(actual.contentEquals(exp), "index=$i FULL: expected ${exp.hex()}, got ${actual.hex()}")
        }
    }

    @Test
    fun `byte 15 (0-indexed) carries the preset index`() {
        for (i in 0..19) {
            val actual = RequestPresetDetailsMessage.encode(PresetIndex(i), PresetDetailsKind.SUMMARY)
            assertTrue(
                (actual[15].toInt() and 0xFF) == i,
                "expected byte[15] == $i, got ${actual[15].toInt() and 0xFF}",
            )
        }
    }

    @Test
    fun `byte 16 (0-indexed) carries the detail-level kind`() {
        val summary = RequestPresetDetailsMessage.encode(PresetIndex(0), PresetDetailsKind.SUMMARY)
        val full = RequestPresetDetailsMessage.encode(PresetIndex(0), PresetDetailsKind.FULL)
        assertTrue((summary[16].toInt() and 0xFF) == 0x00, "SUMMARY should encode byte[16] = 0x00")
        assertTrue((full[16].toInt() and 0xFF) == 0x01, "FULL should encode byte[16] = 0x01")
    }

    @Test
    fun `SUMMARY and FULL requests for the same index differ only in the trailing byte`() {
        for (i in 0..19) {
            val summary = RequestPresetDetailsMessage.encode(PresetIndex(i), PresetDetailsKind.SUMMARY)
            val full = RequestPresetDetailsMessage.encode(PresetIndex(i), PresetDetailsKind.FULL)
            assertTrue(summary.size == full.size)
            assertTrue(summary.copyOfRange(0, 16).contentEquals(full.copyOfRange(0, 16)))
            assertTrue(summary.last() != full.last())
        }
    }

    @Test
    fun `PresetDetailsKind wire values are 0x00 for SUMMARY and 0x01 for FULL`() {
        assertTrue(PresetDetailsKind.SUMMARY.wireValue == 0x00)
        assertTrue(PresetDetailsKind.FULL.wireValue == 0x01)
    }
}
