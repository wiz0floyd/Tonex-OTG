package dev.tonexotg.protocol.state

import dev.tonexotg.protocol.PresetSlot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins [StateBlobOffsets]' constants against the exact values documented in its KDoc, so a typo
 * or an accidental edit shows up as a test failure rather than a silent, wrong-firmware patch.
 *
 * Reference: `TonexOneController` @ `7079f157107a7bc91f171e51e3da0d799d31fcfb`,
 * `source/main/usb_tonex_one.c`, lines 105-119.
 */
class StateBlobOffsetsTest {

    @Test
    fun `slot and current-slot offsets match the pinned upstream revision`() {
        assertEquals(18, StateBlobOffsets.END_SLOT_A_PRESET)
        assertEquals(16, StateBlobOffsets.END_SLOT_B_PRESET)
        assertEquals(14, StateBlobOffsets.END_SLOT_C_PRESET)
        assertEquals(11, StateBlobOffsets.END_CURRENT_SLOT)
    }

    @Test
    fun `MAX_END_OFFSET is the largest of the four offsets`() {
        val all = listOf(
            StateBlobOffsets.END_SLOT_A_PRESET,
            StateBlobOffsets.END_SLOT_B_PRESET,
            StateBlobOffsets.END_SLOT_C_PRESET,
            StateBlobOffsets.END_CURRENT_SLOT,
        )
        assertEquals(all.max(), StateBlobOffsets.MAX_END_OFFSET)
    }

    @Test
    fun `endOffsetForSlotPreset maps each PresetSlot to its own distinct offset`() {
        val offsets = PresetSlot.entries.map { StateBlobOffsets.endOffsetForSlotPreset(it) }
        assertEquals(offsets.toSet().size, offsets.size, "each slot must map to a distinct offset")
        assertEquals(StateBlobOffsets.END_SLOT_A_PRESET, StateBlobOffsets.endOffsetForSlotPreset(PresetSlot.A))
        assertEquals(StateBlobOffsets.END_SLOT_B_PRESET, StateBlobOffsets.endOffsetForSlotPreset(PresetSlot.B))
        assertEquals(StateBlobOffsets.END_SLOT_C_PRESET, StateBlobOffsets.endOffsetForSlotPreset(PresetSlot.C))
    }

    @Test
    fun `MIN_PLAUSIBLE_BLOB_SIZE is derived from the colour table and the indexed tail, not an arbitrary guess`() {
        // 22 bytes before the colour table + 20 preset entries * 3 bytes/entry (minimum varint
        // encoding) + the 18-byte tail this module indexes into = 100. Pinned here so a future
        // edit to any of the inputs shows up as an explicit test failure rather than a silent
        // change to how permissive patching is (issue #12 review: the old floor, MAX_END_OFFSET
        // alone, was ~5x too permissive - it accepted an 18-byte blob for patching).
        val expected = 22 + (20 * 3) + StateBlobOffsets.MAX_END_OFFSET
        assertEquals(100, expected, "sanity: the derivation itself should land on 100")
        assertEquals(expected, StateBlobOffsets.MIN_PLAUSIBLE_BLOB_SIZE)
    }

    @Test
    fun `MIN_PLAUSIBLE_BLOB_SIZE is comfortably larger than MAX_END_OFFSET`() {
        // The two floors serve different purposes (see StateBlobOffsets' KDoc): MAX_END_OFFSET is
        // only an indexing-safety bound, MIN_PLAUSIBLE_BLOB_SIZE is what StateBlobPatcher actually
        // enforces. This pins the relationship so the two can never accidentally invert.
        assertTrue(StateBlobOffsets.MIN_PLAUSIBLE_BLOB_SIZE > StateBlobOffsets.MAX_END_OFFSET)
    }
}
