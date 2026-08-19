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

    // ---- StateBlobDiff.formatDifferences (issue #27: "old -> new byte values ... map it to a
    // name where one exists") -----------------------------------------------------------------

    @Test
    fun `formatDifferences names a known offset and shows old to new byte values`() {
        val before = plausibleBlob(activeSlot = PresetSlot.A)
        val after = before.copyOf()
        after[before.size - StateBlobOffsets.END_CURRENT_SLOT] = PresetSlot.B.ordinal.toByte()

        val report = StateBlobDiff.of(before, after).formatDifferences(before, after)
        assertTrue(report.contains("current slot"), "expected the offset to be named, got: $report")
        assertTrue(report.contains("0x00 -> 0x01"), "expected old->new byte values, got: $report")
    }

    @Test
    fun `formatDifferences still reports an unnamed index as a raw number, not filtered out`() {
        val before = plausibleBlob()
        val after = before.copyOf()
        after[3] = (after[3] + 1).toByte()

        val report = StateBlobDiff.of(before, after).formatDifferences(before, after)
        assertTrue(report.contains("index 3"), "expected the raw index to still be reported, got: $report")
        assertTrue(report.contains("unnamed"), "expected an unnamed index to say so, not be silently dropped: $report")
    }

    @Test
    fun `formatDifferences never truncates the index list even when detail lines are capped`() {
        val before = plausibleBlob(size = 300)
        val after = before.copyOf()
        // More differing bytes than the default detail cap (64).
        val changed = (0 until 100).toList()
        for (i in changed) after[i] = (after[i] + 1).toByte()

        val report = StateBlobDiff.of(before, after).formatDifferences(before, after)
        assertTrue(report.contains(changed.toString()), "full index list must never be truncated: $report")
        assertTrue(report.contains("36 more"), "expected the overflow beyond the cap to be called out: $report")
    }

    // ---- NamedStateBlobFields -----------------------------------------------------------------

    @Test
    fun `NamedStateBlobFields names all three real set_preset_in_slot side-effect offsets`() {
        val blobSize = 200
        // Confirmed against upstream in issue #27's pre-implementation comment: STOMP_MODE (S19),
        // DIRECT_MONITOR (E7), and the third, easy-to-miss one, BYPASS_MODE (E12).
        assertTrue(NamedStateBlobFields.nameFor(blobSize - 12, blobSize)!!.contains("bypass"))
        assertTrue(NamedStateBlobFields.nameFor(blobSize - 7, blobSize)!!.contains("direct monitor"))
        assertTrue(NamedStateBlobFields.nameFor(19, blobSize)!!.contains("stomp"))
    }

    @Test
    fun `NamedStateBlobFields returns null for a genuinely unnamed index`() {
        assertEquals(null, NamedStateBlobFields.nameFor(3, 200))
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
    fun `a change to only the active-slot byte passes when that offset is the one expected`() {
        // Mirrors DefaultTonexController#selectPreset's "already assigned to another slot" branch
        // (StateBlobPatcher#patchActiveSlot) -- exactly one byte touched.
        val before = plausibleBlob(activeSlot = PresetSlot.A, a = 0, b = 1, c = 2)
        val after = before.copyOf()
        after[before.size - StateBlobOffsets.END_CURRENT_SLOT] = PresetSlot.B.ordinal.toByte()

        val audit = PresetChangeAudit.audit(before, after, expectedOffsets = setOf(StateBlobOffsets.END_CURRENT_SLOT))
        assertTrue(audit.passed)
        assertEquals(setOf(StateBlobOffsets.END_CURRENT_SLOT), audit.sanctionedOffsetsChanged)
        assertEquals(emptyList(), audit.unexpectedIndices)
        assertEquals(emptySet(), audit.missingExpectedOffsets)
        assertEquals(emptySet(), audit.extraSanctionedOffsets)
    }

    @Test
    fun `a change to the slot-assignment byte passes when that offset is the one expected, and the untouched active-slot byte is not falsely flagged`() {
        // Mirrors selectPreset's "not assigned anywhere" branch (StateBlobPatcher#selectPreset).
        // The active-slot byte is written with the SAME value it already held (selecting a preset
        // into the already-active slot A), so it is byte-identical and never shows up as
        // "changed" even though selectPreset issued a write to it -- this is the diff seeing
        // actual bytes, not intent, which is exactly the point.
        val before = plausibleBlob(activeSlot = PresetSlot.A, a = 0, b = 1, c = 2)
        val after = before.copyOf()
        after[before.size - StateBlobOffsets.END_SLOT_A_PRESET] = 9
        after[before.size - StateBlobOffsets.END_CURRENT_SLOT] = PresetSlot.A.ordinal.toByte() // unchanged value

        val audit = PresetChangeAudit.audit(before, after, expectedOffsets = setOf(StateBlobOffsets.END_SLOT_A_PRESET))
        assertTrue(audit.passed)
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

        val audit = PresetChangeAudit.audit(before, after, expectedOffsets = setOf(StateBlobOffsets.END_CURRENT_SLOT))
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

    // ---- expectedOffsets: closing the vacuous-pass hole (Opus review of issue #27, blocking

    // finding B1) ---------------------------------------------------------------------------

    @Test
    fun `an empty diff FAILS the audit when an offset was expected to change - the vacuous-pass hole`() {
        // This is the exact scenario the Opus review flagged as reachable and blocking: a dropped
        // or silently-not-applied write, or two captures racing a stale read, produces two
        // IDENTICAL blobs. The old "any subset of the four is fine" semantics treated that as a
        // clean PASS with zero evidence anything happened at all -- precisely the "no side effects
        // detected: true" report issue #27's own ground-truth comment pre-declared unacceptable.
        val blob = plausibleBlob(activeSlot = PresetSlot.A)

        val audit = PresetChangeAudit.audit(blob, blob.copyOf(), expectedOffsets = setOf(StateBlobOffsets.END_CURRENT_SLOT))
        assertFalse(audit.passed, "an empty diff must not pass when the operation was expected to move a byte")
        assertEquals(setOf(StateBlobOffsets.END_CURRENT_SLOT), audit.missingExpectedOffsets)
    }

    @Test
    fun `a sanctioned offset changing that was NOT the expected one fails the audit even though it is not 'unexpectedIndices'`() {
        // A stray write to a DIFFERENT footswitch slot than the one actually being changed is
        // still one of the four named offsets, so unexpectedIndices alone would never catch it.
        // expectedOffsets exists specifically to catch this case too.
        val before = plausibleBlob(activeSlot = PresetSlot.A, a = 0, b = 1, c = 2)
        val after = before.copyOf()
        after[before.size - StateBlobOffsets.END_SLOT_B_PRESET] = 9 // wrong slot touched

        val audit = PresetChangeAudit.audit(before, after, expectedOffsets = setOf(StateBlobOffsets.END_CURRENT_SLOT))
        assertFalse(audit.passed)
        assertEquals(emptyList(), audit.unexpectedIndices) // NOT caught by this check...
        assertEquals(setOf(StateBlobOffsets.END_SLOT_B_PRESET), audit.extraSanctionedOffsets) // ...but caught by this one.
    }

    @Test
    fun `the default expectedOffsets is empty - suited to an operation that should touch nothing at all`() {
        // dev.tonexotg.protocol.diagnostics.runRevertDrill's use case: the state blob should not
        // move a single byte, not even one of the four sanctioned ones.
        val before = plausibleBlob(activeSlot = PresetSlot.A)
        val after = before.copyOf()
        after[before.size - StateBlobOffsets.END_CURRENT_SLOT] = PresetSlot.B.ordinal.toByte()

        val audit = PresetChangeAudit.audit(before, after) // expectedOffsets defaults to emptySet()
        assertFalse(audit.passed, "a sanctioned offset moving must fail an audit that expected nothing to move")
        assertEquals(setOf(StateBlobOffsets.END_CURRENT_SLOT), audit.extraSanctionedOffsets)
    }

    // ---- H3: the third real side-effect offset (END_BYPASS_MODE) is genuinely tested, not just

    // documented ------------------------------------------------------------------------------

    @Test
    fun `END_BYPASS_MODE drift is classified as unexpected and is NOT swallowed by the adjacent sanctioned END_CURRENT_SLOT byte`() {
        // Issue #27's pre-implementation ground-truth comment: TONEX_STATE_OFFSET_END_BYPASS_MODE
        // (end-relative 12) sits immediately next to END_CURRENT_SLOT (end-relative 11) -- adjacent
        // indices in a size-200 blob (188 and 189). This is the literal-pinned negative case
        // CLAUDE.md calls gold: a real byte, at a real confirmed-against-upstream offset, that this
        // codebase has no write path for and must never silently swallow into "sanctioned."
        val before = plausibleBlob(size = 200, activeSlot = PresetSlot.A, a = 0, b = 1, c = 2)
        val after = before.copyOf()
        val bypassModeIndex = before.size - 12 // TONEX_STATE_OFFSET_END_BYPASS_MODE
        val currentSlotIndex = before.size - StateBlobOffsets.END_CURRENT_SLOT
        assertEquals(188, bypassModeIndex)
        assertEquals(189, currentSlotIndex)

        after[bypassModeIndex] = (after[bypassModeIndex] + 1).toByte() // the un-modelled side effect
        after[currentSlotIndex] = PresetSlot.B.ordinal.toByte() // the genuine, expected write

        val audit = PresetChangeAudit.audit(before, after, expectedOffsets = setOf(StateBlobOffsets.END_CURRENT_SLOT))
        assertFalse(audit.passed)
        assertEquals(listOf(bypassModeIndex), audit.unexpectedIndices)
        assertEquals(setOf(StateBlobOffsets.END_CURRENT_SLOT), audit.sanctionedOffsetsChanged)
        // formatDifferences must name it, per issue #27's explicit reporting requirement.
        val report = audit.describe(before, after)
        assertTrue(report.contains("bypass mode"), "expected the third side-effect offset to be named, got: $report")
    }

    // ---- PresetChangeAudit.describe (issue #27: full report, not a boolean) -----------------

    @Test
    fun `describe reports PASSED with the expected and actual offsets named, on a clean audit`() {
        val before = plausibleBlob(activeSlot = PresetSlot.A)
        val after = before.copyOf()
        after[before.size - StateBlobOffsets.END_CURRENT_SLOT] = PresetSlot.B.ordinal.toByte()

        val audit = PresetChangeAudit.audit(before, after, expectedOffsets = setOf(StateBlobOffsets.END_CURRENT_SLOT))
        val report = audit.describe(before, after)
        assertTrue(report.startsWith("PASSED"), report)
        assertTrue(report.contains("current slot"), report)
    }

    @Test
    fun `describe reports FAILED with MISSING called out when an expected offset never moved`() {
        val blob = plausibleBlob(activeSlot = PresetSlot.A)
        val audit = PresetChangeAudit.audit(blob, blob.copyOf(), expectedOffsets = setOf(StateBlobOffsets.END_CURRENT_SLOT))
        val report = audit.describe(blob, blob.copyOf())
        assertTrue(report.startsWith("FAILED"), report)
        assertTrue(report.contains("MISSING"), report)
    }
}
