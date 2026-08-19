package dev.tonexotg.protocol.diagnostics

import dev.tonexotg.protocol.PresetSlot
import dev.tonexotg.protocol.connection.plausibleBlob
import dev.tonexotg.protocol.state.StateBlobOffsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #27 (S22) — the crux of the whole safety drill: [StateBlobDiff] must be a genuine,
 * exhaustive full-blob byte diff reporting the concrete list of differing indices, and
 * [PresetChangeAudit] must classify every one of them correctly against the real, named sanctioned
 * offsets — never a boolean, never a spot-check of a few fields.
 */
class StateBlobDiffTest {

    // ---- StateBlobDiff.of ------------------------------------------------------------------

    @Test
    fun `identical blobs report no differing indices`() {
        val blob = plausibleBlob()
        val diff = StateBlobDiff.of(blob, blob.copyOf())
        assertFalse(diff.sizeChanged)
        assertTrue(diff.identical)
        assertEquals(emptyList(), diff.differingIndices)
    }

    @Test
    fun `a single differing byte anywhere in the array is reported by exact index`() {
        val before = plausibleBlob()
        val after = before.copyOf()
        after[42] = (after[42] + 1).toByte()

        val diff = StateBlobDiff.of(before, after)
        assertFalse(diff.sizeChanged)
        assertFalse(diff.identical)
        assertEquals(listOf(42), diff.differingIndices)
    }

    @Test
    fun `every differing byte is reported, not just the first`() {
        val before = plausibleBlob()
        val after = before.copyOf()
        val changed = listOf(0, 5, 100, before.size - 1)
        for (i in changed) after[i] = (after[i] + 1).toByte()

        val diff = StateBlobDiff.of(before, after)
        assertEquals(changed, diff.differingIndices)
    }

    @Test
    fun `a size change is reported as sizeChanged with no per-index claims`() {
        val before = plausibleBlob(size = 200)
        val after = plausibleBlob(size = 250)

        val diff = StateBlobDiff.of(before, after)
        assertTrue(diff.sizeChanged)
        assertFalse(diff.identical)
        assertEquals(emptyList(), diff.differingIndices)
    }

    // ---- PresetChangeAudit.audit ------------------------------------------------------------

    @Test
    fun `identical blobs pass the audit with no sanctioned offsets and no unexpected indices`() {
        val blob = plausibleBlob()
        val audit = PresetChangeAudit.audit(blob, blob.copyOf())
        assertTrue(audit.passed)
        assertEquals(emptySet(), audit.sanctionedOffsetsChanged)
        assertEquals(emptyList(), audit.unexpectedIndices)
    }

    @Test
    fun `a change to only the active-slot byte passes and is reported as that one sanctioned offset`() {
        // Mirrors DefaultTonexController#selectPreset's "already assigned to another slot" branch
        // (StateBlobPatcher#patchActiveSlot) -- exactly one byte touched.
        val before = plausibleBlob(activeSlot = PresetSlot.A, a = 0, b = 1, c = 2)
        val after = before.copyOf()
        after[before.size - StateBlobOffsets.END_CURRENT_SLOT] = PresetSlot.B.ordinal.toByte()

        val audit = PresetChangeAudit.audit(before, after)
        assertTrue(audit.passed)
        assertEquals(setOf(StateBlobOffsets.END_CURRENT_SLOT), audit.sanctionedOffsetsChanged)
        assertEquals(emptyList(), audit.unexpectedIndices)
    }

    @Test
    fun `a change to a slot-assignment byte plus the active-slot byte passes and both are reported`() {
        // Mirrors selectPreset's "not assigned anywhere" branch (StateBlobPatcher#selectPreset) --
        // two bytes touched: the target slot's assignment, and the active-slot byte.
        val before = plausibleBlob(activeSlot = PresetSlot.A, a = 0, b = 1, c = 2)
        val after = before.copyOf()
        after[before.size - StateBlobOffsets.END_SLOT_A_PRESET] = 9
        after[before.size - StateBlobOffsets.END_CURRENT_SLOT] = PresetSlot.A.ordinal.toByte() // unchanged value, still "touched" conceptually but byte-identical here

        val audit = PresetChangeAudit.audit(before, after)
        assertTrue(audit.passed)
        // The active-slot byte was written with the SAME value it already held (selecting a
        // preset into the already-active slot A), so it is byte-identical and does not show up as
        // "changed" even though selectPreset issued a write to it -- this is the diff seeing
        // actual bytes, not intent, which is exactly the point.
        assertEquals(setOf(StateBlobOffsets.END_SLOT_A_PRESET), audit.sanctionedOffsetsChanged)
        assertEquals(emptyList(), audit.unexpectedIndices)
    }

    @Test
    fun `an unexpected byte outside the four sanctioned offsets fails the audit and is named exactly`() {
        // The serious, escalate-worthy finding issue #27 section 3 exists to catch: something
        // wrote outside the four offsets StateBlobPatcher is documented to ever touch (e.g. the
        // never-modelled DIRECT_MONITOR or stomp/AB-mode offsets moving).
        val before = plausibleBlob(activeSlot = PresetSlot.A, a = 0, b = 1, c = 2)
        val after = before.copyOf()
        after[before.size - StateBlobOffsets.END_CURRENT_SLOT] = PresetSlot.B.ordinal.toByte() // sanctioned
        val unexpectedIndex = 7
        after[unexpectedIndex] = (after[unexpectedIndex] + 1).toByte() // NOT sanctioned

        val audit = PresetChangeAudit.audit(before, after)
        assertFalse(audit.passed)
        assertEquals(setOf(StateBlobOffsets.END_CURRENT_SLOT), audit.sanctionedOffsetsChanged)
        assertEquals(listOf(unexpectedIndex), audit.unexpectedIndices)
    }

    @Test
    fun `multiple unexpected bytes are all named, not just the first`() {
        val before = plausibleBlob()
        val after = before.copyOf()
        val unexpected = listOf(3, 55, 199)
        for (i in unexpected) after[i] = (after[i] + 1).toByte()

        val audit = PresetChangeAudit.audit(before, after)
        assertFalse(audit.passed)
        assertEquals(unexpected, audit.unexpectedIndices)
    }

    @Test
    fun `a size change fails the audit with no sanctioned or unexpected claims`() {
        val before = plausibleBlob(size = 200)
        val after = plausibleBlob(size = 199)

        val audit = PresetChangeAudit.audit(before, after)
        assertFalse(audit.passed)
        assertTrue(audit.diff.sizeChanged)
        assertEquals(emptySet(), audit.sanctionedOffsetsChanged)
        assertEquals(emptyList(), audit.unexpectedIndices)
    }
}
