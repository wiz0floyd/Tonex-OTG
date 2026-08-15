package dev.tonexotg.protocol.message

import dev.tonexotg.protocol.TonexError
import dev.tonexotg.protocol.TonexResult
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private fun hex(spec: String): ByteArray =
    spec.trim().split(Regex("\\s+")).map { it.toInt(16).toByte() }.toByteArray()

class PresetNameExtractorTest {

    private val marker = PresetNameExtractor.MARKER

    /** Builds a payload: [prefix] bytes + [marker] + [nameField] (must be exactly 32 bytes). */
    private fun payload(nameField: ByteArray, prefix: ByteArray = ByteArray(0)): ByteArray {
        require(nameField.size == PresetNameExtractor.NAME_FIELD_LENGTH)
        return prefix + marker + nameField
    }

    // ---- full 32-byte name, no NUL terminator anywhere ---------------------------------------------

    @Test
    fun `extracts a name that fills the full 32 bytes with no NUL terminator`() {
        // Exactly 32 printable characters - upstream copies these 32 bytes verbatim with no
        // guarantee byte 32 is 0x00, so this must not be truncated or fail.
        val fullName = "My Favourite Lead Tone Preset!!!"
        assertEquals(32, fullName.length, "test fixture itself must be exactly 32 chars")
        val nameField = fullName.toByteArray(Charsets.UTF_8)
        val result = PresetNameExtractor.extract(payload(nameField))
        val extracted = assertSuccessValue(result)
        assertEquals(fullName, extracted)
    }

    // ---- short name, trailing 0x00 padding trimmed, NOT NUL-termination-assumed --------------------

    @Test
    fun `extracts a short name and trims trailing 0x00 padding`() {
        val shortName = "Crunch"
        val nameField = shortName.toByteArray(Charsets.UTF_8) + ByteArray(32 - shortName.length)
        assertEquals(32, nameField.size)
        val result = PresetNameExtractor.extract(payload(nameField))
        val extracted = assertSuccessValue(result)
        assertEquals(shortName, extracted)
    }

    @Test
    fun `extracts an empty name when the entire 32-byte field is padding`() {
        val nameField = ByteArray(32)
        val result = PresetNameExtractor.extract(payload(nameField))
        assertEquals("", assertSuccessValue(result))
    }

    @Test
    fun `does not assume NUL-termination - a full-length name with no 0x00 anywhere is kept whole`() {
        val nameField = ByteArray(32) { 'X'.code.toByte() }
        val result = PresetNameExtractor.extract(payload(nameField))
        assertEquals("X".repeat(32), assertSuccessValue(result))
    }

    @Test
    fun `trims only bytes after the first 0x00, matching upstream's memcpy-with-no-length-check semantics`() {
        val nameField = "AB".toByteArray() + byteArrayOf(0x00) + "CD".toByteArray() + ByteArray(27)
        val result = PresetNameExtractor.extract(payload(nameField))
        // Content after the first 0x00 ("CD") must not resurface - it is trailing padding territory.
        assertEquals("AB", assertSuccessValue(result))
    }

    // ---- marker located via scan, not a fixed offset -----------------------------------------------

    @Test
    fun `locates the marker wherever it occurs in the payload, not just at offset 0`() {
        val shortName = "Lead"
        val nameField = shortName.toByteArray(Charsets.UTF_8) + ByteArray(32 - shortName.length)
        val prefix = ByteArray(50) { (it % 251).toByte() }
        val result = PresetNameExtractor.extract(payload(nameField, prefix = prefix))
        assertEquals(shortName, assertSuccessValue(result))
    }

    // ---- missing marker -> typed error, not a crash -------------------------------------------------

    @Test
    fun `fails with MalformedFrame when the marker is not present anywhere`() {
        val payload = ByteArray(64) { it.toByte() }
        val result = PresetNameExtractor.extract(payload)
        assertIs<TonexResult.Failure>(result)
        assertIs<TonexError.MalformedFrame>(result.error)
    }

    @Test
    fun `fails with MalformedFrame on an empty payload`() {
        val result = PresetNameExtractor.extract(ByteArray(0))
        assertIs<TonexResult.Failure>(result)
        assertIs<TonexError.MalformedFrame>(result.error)
    }

    @Test
    fun `fails with MalformedFrame when only part of the marker is present`() {
        val result = PresetNameExtractor.extract(hex("b9 04 b9 02"))
        assertIs<TonexResult.Failure>(result)
        assertIs<TonexError.MalformedFrame>(result.error)
    }

    // ---- marker found but not enough trailing bytes -> typed error, not a crash --------------------

    @Test
    fun `fails with UnexpectedBlobShape when the marker is found but fewer than 32 bytes follow`() {
        val truncated = marker + ByteArray(10)
        val result = PresetNameExtractor.extract(truncated)
        assertIs<TonexResult.Failure>(result)
        val error = assertIs<TonexError.UnexpectedBlobShape>(result.error)
        assertEquals(32, error.expectedSize)
        assertEquals(10, error.actualSize)
    }

    @Test
    fun `fails with UnexpectedBlobShape when the marker is the very last bytes with nothing following`() {
        val result = PresetNameExtractor.extract(marker)
        assertIs<TonexResult.Failure>(result)
        val error = assertIs<TonexError.UnexpectedBlobShape>(result.error)
        assertEquals(32, error.expectedSize)
        assertEquals(0, error.actualSize)
    }

    @Test
    fun `succeeds when exactly 32 bytes follow the marker - the minimum sufficient case`() {
        val nameField = ByteArray(32) { 'Z'.code.toByte() }
        val exact = marker + nameField
        val result = PresetNameExtractor.extract(exact)
        assertEquals("Z".repeat(32), assertSuccessValue(result))
    }

    // ---- constants ------------------------------------------------------------------------------------

    @Test
    fun `MARKER is the 6-byte ToneOnePresetByteMarker literal`() {
        assertTrue(marker.contentEquals(hex("b9 04 b9 02 bc 21")))
    }

    @Test
    fun `NAME_FIELD_LENGTH is 32`() {
        assertEquals(32, PresetNameExtractor.NAME_FIELD_LENGTH)
    }

    private fun assertSuccessValue(result: TonexResult<String>): String {
        assertIs<TonexResult.Success<String>>(result)
        return result.value
    }
}
