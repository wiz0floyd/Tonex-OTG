package dev.tonexotg.protocol.state

import dev.tonexotg.protocol.PresetSlot
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
