package dev.tonexotg.protocol.state

import dev.tonexotg.protocol.PresetSlot
import dev.tonexotg.protocol.SessionId
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
        // Computed from the *named* layout constants, not bare duplicated literals - a change to
        // any of these four inputs must flow through this expression and fail the assertion below
        // if StateBlobOffsets.MIN_PLAUSIBLE_BLOB_SIZE (itself now a real computed expression, not a
        // bare literal - see its KDoc) was not updated to match. Pinning `expected` to a bare `22 +
        // 20 * 3 + 18` here would silently stop catching drift in three of those four inputs, which
        // is exactly the bug this test previously had (issue #12 round-5 review, LOW finding):
        // moving MIN_PLAUSIBLE_BLOB_SIZE to a bare literal on SessionId left this test reconstructing
        // `expected` from bare literals too, so changing START_COLOUR_TABLE or
        // COLOUR_TABLE_ENTRY_COUNT changed neither side and this test kept passing regardless.
        val expected = StateBlobOffsets.START_COLOUR_TABLE +
            (StateBlobOffsets.COLOUR_TABLE_ENTRY_COUNT * StateBlobOffsets.COLOUR_TABLE_MIN_BYTES_PER_ENTRY) +
            StateBlobOffsets.MAX_END_OFFSET
        assertEquals(100, expected, "sanity: the derivation itself should land on 100")
        assertEquals(expected, StateBlobOffsets.MIN_PLAUSIBLE_BLOB_SIZE)
    }

    @Test
    fun `SessionId's mirrored floor has not drifted from StateBlobOffsets' derived floor`() {
        // SessionId.MIN_PLAUSIBLE_BLOB_SIZE cannot simply reference StateBlobOffsets'
        // MIN_PLAUSIBLE_BLOB_SIZE (see SessionId's KDoc for the circular-package-dependency reason
        // why not), so it is a manually-maintained literal mirror of the same value. This test is
        // what actually catches the two drifting apart: it asserts SessionId's mirror against a
        // fresh computation over StateBlobOffsets' own named layout constants, so an edit to any of
        // START_COLOUR_TABLE, COLOUR_TABLE_ENTRY_COUNT, COLOUR_TABLE_MIN_BYTES_PER_ENTRY, or
        // MAX_END_OFFSET that isn't mirrored into SessionId's literal fails here (issue #12 round-5
        // review, LOW finding).
        val expected = StateBlobOffsets.START_COLOUR_TABLE +
            (StateBlobOffsets.COLOUR_TABLE_ENTRY_COUNT * StateBlobOffsets.COLOUR_TABLE_MIN_BYTES_PER_ENTRY) +
            StateBlobOffsets.MAX_END_OFFSET
        assertEquals(
            expected,
            SessionId.MIN_PLAUSIBLE_BLOB_SIZE,
            "SessionId.MIN_PLAUSIBLE_BLOB_SIZE has drifted from StateBlobOffsets' derivation - " +
                "update SessionId's mirrored literal to match",
        )
        assertEquals(
            StateBlobOffsets.MIN_PLAUSIBLE_BLOB_SIZE,
            SessionId.MIN_PLAUSIBLE_BLOB_SIZE,
            "the two constants must always agree - StateBlobPatcher enforces one, PedalState/SessionId enforce the other",
        )
    }

    @Test
    fun `MIN_PLAUSIBLE_BLOB_SIZE is comfortably larger than MAX_END_OFFSET`() {
        // The two floors serve different purposes (see StateBlobOffsets' KDoc): MAX_END_OFFSET is
        // only an indexing-safety bound, MIN_PLAUSIBLE_BLOB_SIZE is what StateBlobPatcher actually
        // enforces. This pins the relationship so the two can never accidentally invert.
        assertTrue(StateBlobOffsets.MIN_PLAUSIBLE_BLOB_SIZE > StateBlobOffsets.MAX_END_OFFSET)
    }
}
