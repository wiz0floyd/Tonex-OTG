package dev.tonexotg.protocol.state

import dev.tonexotg.protocol.PedalState
import dev.tonexotg.protocol.PresetIndex
import dev.tonexotg.protocol.PresetSlot
import dev.tonexotg.protocol.SessionId
import dev.tonexotg.protocol.TonexError
import dev.tonexotg.protocol.TonexResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Tests for [StateBlobReader] — the read-only counterpart to [StateBlobPatcher]. Reuses the
 * fixture shape and helpers already established by `StateBlobPatcherTest`.
 */
class StateBlobReaderTest {

    private fun distinctBytes(size: Int): ByteArray = ByteArray(size) { i -> ((i * 7 + 13) % 256).toByte() }

    private fun plausibleBlob(
        size: Int = StateBlobOffsets.MIN_PLAUSIBLE_BLOB_SIZE,
        slotA: Int = 2,
        slotB: Int = 5,
        slotC: Int = 9,
        currentSlot: Int = 0,
    ): ByteArray {
        val bytes = distinctBytes(size)
        bytes[size - StateBlobOffsets.END_SLOT_A_PRESET] = slotA.toByte()
        bytes[size - StateBlobOffsets.END_SLOT_B_PRESET] = slotB.toByte()
        bytes[size - StateBlobOffsets.END_SLOT_C_PRESET] = slotC.toByte()
        bytes[size - StateBlobOffsets.END_CURRENT_SLOT] = currentSlot.toByte()
        return bytes
    }

    private fun <T> TonexResult<T>.assertSuccess(): T = when (this) {
        is TonexResult.Success -> value
        is TonexResult.Failure -> fail("expected Success, got Failure(${error.message})")
    }

    private fun <T> TonexResult<T>.assertFailure(): TonexError = when (this) {
        is TonexResult.Success -> fail("expected Failure, got Success($value)")
        is TonexResult.Failure -> error
    }

    // ---- activeSlot ----------------------------------------------------------------------------

    @Test
    fun `activeSlot decodes the active-slot byte for each PresetSlot`() {
        for (slot in PresetSlot.entries) {
            val blob = plausibleBlob(currentSlot = slot.ordinal)
            assertEquals(slot, StateBlobReader.activeSlot(blob).assertSuccess())
        }
    }

    // ---- presetInSlot ----------------------------------------------------------------------------

    @Test
    fun `presetInSlot decodes each slot's assigned preset independently`() {
        val blob = plausibleBlob(slotA = 2, slotB = 5, slotC = 9)
        assertEquals(PresetIndex(2), StateBlobReader.presetInSlot(blob, PresetSlot.A).assertSuccess())
        assertEquals(PresetIndex(5), StateBlobReader.presetInSlot(blob, PresetSlot.B).assertSuccess())
        assertEquals(PresetIndex(9), StateBlobReader.presetInSlot(blob, PresetSlot.C).assertSuccess())
    }

    // ---- slotAssignments ------------------------------------------------------------------------

    @Test
    fun `slotAssignments returns all three slots in one map`() {
        val blob = plausibleBlob(slotA = 3, slotB = 7, slotC = 11)
        val assignments = StateBlobReader.slotAssignments(blob).assertSuccess()

        assertEquals(
            mapOf(PresetSlot.A to PresetIndex(3), PresetSlot.B to PresetIndex(7), PresetSlot.C to PresetIndex(11)),
            assignments,
        )
    }

    @Test
    fun `slotAssignments overload accepting a PedalState delegates to the ByteArray form`() {
        val session = SessionId.create()
        val blob = plausibleBlob(slotA = 4, slotB = 8, slotC = 12)
        val state = PedalState.create(session, blob).assertSuccess()

        val assignments = StateBlobReader.slotAssignments(state).assertSuccess()

        assertEquals(
            mapOf(PresetSlot.A to PresetIndex(4), PresetSlot.B to PresetIndex(8), PresetSlot.C to PresetIndex(12)),
            assignments,
        )
    }

    // ---- activePreset ----------------------------------------------------------------------------

    @Test
    fun `activePreset resolves presetInSlot of activeSlot`() {
        val blob = plausibleBlob(slotA = 2, slotB = 5, slotC = 9, currentSlot = PresetSlot.B.ordinal)
        assertEquals(PresetIndex(5), StateBlobReader.activePreset(blob).assertSuccess())
    }

    @Test
    fun `activePreset overload accepting a PedalState delegates to the ByteArray form`() {
        val session = SessionId.create()
        val blob = plausibleBlob(slotA = 2, slotB = 5, slotC = 9, currentSlot = PresetSlot.C.ordinal)
        val state = PedalState.create(session, blob).assertSuccess()

        assertEquals(PresetIndex(9), StateBlobReader.activePreset(state).assertSuccess())
    }

    // ---- validation: too short ---------------------------------------------------------------

    @Test
    fun `a blob shorter than MIN_PLAUSIBLE_BLOB_SIZE is rejected with BlobTooShortToPatch`() {
        val blob = plausibleBlob(size = StateBlobOffsets.MIN_PLAUSIBLE_BLOB_SIZE - 1)

        val error = StateBlobReader.activePreset(blob).assertFailure()

        assertTrue(error is TonexError.BlobTooShortToPatch, "expected BlobTooShortToPatch, got $error")
        assertEquals(StateBlobOffsets.MIN_PLAUSIBLE_BLOB_SIZE, (error as TonexError.BlobTooShortToPatch).minimumSize)
        assertEquals(StateBlobOffsets.MIN_PLAUSIBLE_BLOB_SIZE - 1, error.actualSize)
    }

    @Test
    fun `an empty blob is rejected with a typed error, not an exception`() {
        val error = StateBlobReader.activeSlot(ByteArray(0)).assertFailure()
        assertTrue(error is TonexError.BlobTooShortToPatch)
    }

    @Test
    fun `boundary - exactly MIN_PLAUSIBLE_BLOB_SIZE is accepted`() {
        val blob = plausibleBlob(size = StateBlobOffsets.MIN_PLAUSIBLE_BLOB_SIZE)
        StateBlobReader.activePreset(blob).assertSuccess()
    }

    // ---- validation: implausible shape ---------------------------------------------------------

    @Test
    fun `an implausible slot-A byte is rejected with ImplausibleStateBlobShape`() {
        val blob = plausibleBlob(slotA = 250)
        val error = StateBlobReader.slotAssignments(blob).assertFailure()
        assertTrue(error is TonexError.ImplausibleStateBlobShape, "expected ImplausibleStateBlobShape, got $error")
    }

    @Test
    fun `an implausible slot-B byte is rejected with ImplausibleStateBlobShape`() {
        val blob = plausibleBlob(slotB = 200)
        assertTrue(StateBlobReader.slotAssignments(blob).assertFailure() is TonexError.ImplausibleStateBlobShape)
    }

    @Test
    fun `an implausible slot-C byte is rejected with ImplausibleStateBlobShape`() {
        val blob = plausibleBlob(slotC = 199)
        assertTrue(StateBlobReader.slotAssignments(blob).assertFailure() is TonexError.ImplausibleStateBlobShape)
    }

    @Test
    fun `an implausible active-slot byte is rejected with ImplausibleStateBlobShape`() {
        val blob = plausibleBlob(currentSlot = 9)
        assertTrue(StateBlobReader.activeSlot(blob).assertFailure() is TonexError.ImplausibleStateBlobShape)
    }

    // ---- read authorizes nothing: no SessionId / PedalState provenance checks -----------------

    @Test
    fun `a plausible blob reads successfully with no SessionId or generation checks at all - a read authorizes nothing`() {
        // Deliberately NOT going through PedalState.create/SessionId at all here - the ByteArray
        // overloads take raw bytes directly, proving no write-authorization machinery gates a read.
        val blob = plausibleBlob(slotA = 6)
        assertEquals(PresetIndex(6), StateBlobReader.presetInSlot(blob, PresetSlot.A).assertSuccess())
    }

    // ---- looksLikeSlotRegion is shared with StateBlobPatcher, not duplicated ------------------

    @Test
    fun `looksLikeSlotRegion agrees with StateBlobPatcher's own shape rejection for the same blob`() {
        // Regression guard for the refactor extracting this into StateBlobReader: a blob that
        // StateBlobReader considers implausible must also be rejected by StateBlobPatcher via the
        // exact same shared helper, not a second, independently-drifting copy.
        val session = SessionId.create()
        val implausible = plausibleBlob(slotA = 250)
        val state = PedalState.create(session, implausible).assertSuccess()

        val patcherResult = StateBlobPatcher.patchActiveSlot(state, session, PresetSlot.B)
        val readerResult = StateBlobReader.activeSlot(implausible)

        assertTrue(patcherResult.assertFailure() is TonexError.ImplausibleStateBlobShape)
        assertTrue(readerResult.assertFailure() is TonexError.ImplausibleStateBlobShape)
    }
}
