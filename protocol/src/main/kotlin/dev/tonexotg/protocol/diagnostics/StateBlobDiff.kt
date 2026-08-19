package dev.tonexotg.protocol.diagnostics

import dev.tonexotg.protocol.state.StateBlobOffsets

/**
 * ⚠️ DIAGNOSTIC-ONLY (issue #27, S22). A full, byte-by-byte comparison between two captures of
 * the pedal's whole-device state blob (`GetState`, wire type `0x0306`).
 *
 * Deliberately compares **every** byte, not a spot-check of a few named fields: a spot-check can
 * never catch drift at a byte nobody thought to name (e.g. `DIRECT_MONITOR`, stomp/AB mode, or
 * bypass mode — the three confirmed upstream `set_preset_in_slot()` side effects
 * [dev.tonexotg.protocol.state.StateBlobPatcher] is documented to never write, but never
 * independently confirmed against a real captured blob until this drill; see
 * [dev.tonexotg.protocol.state.StateBlobOffsets]'s "Fields intentionally NOT modelled here").
 * [differingIndices] is a concrete list of every start-relative index that changed, not a boolean
 * — so a genuinely unexpected drift is *nameable*, not merely detectable. See [PresetChangeAudit]
 * for the higher-level check that actually classifies each differing index as sanctioned or not.
 */
data class StateBlobDiff(
    val beforeSize: Int,
    val afterSize: Int,
    /**
     * Every start-relative array index where the two blobs disagree. Only meaningful when
     * [beforeSize] == [afterSize] — always empty when the sizes differ (see [sizeChanged]); a
     * length change is itself the finding in that case, and per-index comparison of
     * differently-shaped arrays would not mean anything.
     */
    val differingIndices: List<Int>,
) {
    val sizeChanged: Boolean get() = beforeSize != afterSize
    val identical: Boolean get() = !sizeChanged && differingIndices.isEmpty()

    /**
     * Renders every differing index as a human-readable, `old -> new` line — issue #27's own
     * pre-implementation comment, verbatim: "old → new byte values at each differing index,"
     * "map it to a name where one exists ... Unnamed differing indices are still failures and
     * must be reported as raw indices, not filtered out."
     *
     * [before]/[after] must be the same arrays this diff was computed from (same length as
     * [beforeSize]/[afterSize]) — this method does not re-validate that, it only indexes them.
     *
     * The full sorted index list is always included in full, never truncated. Only the per-index
     * `old -> new` detail lines are capped at [maxDetailed] (default 64), because a genuine
     * layout shift can make nearly every byte differ and a wall of thousands of lines would bury
     * the finding rather than explain it — the reviewer's own caution on this point.
     */
    fun formatDifferences(before: ByteArray, after: ByteArray, maxDetailed: Int = 64): String {
        if (sizeChanged) {
            return "blob length changed ($beforeSize -> $afterSize bytes) — not diagnosable byte-by-byte"
        }
        if (differingIndices.isEmpty()) return "0 bytes differ"
        val sorted = differingIndices.sorted()
        val out = StringBuilder("${sorted.size} byte(s) differ, indices $sorted:\n")
        for (index in sorted.take(maxDetailed)) {
            val name = NamedStateBlobFields.nameFor(index, beforeSize)
            val label = name ?: "unnamed"
            out.append(
                "  index %d (%s): 0x%02X -> 0x%02X\n".format(
                    index,
                    label,
                    before[index],
                    after[index],
                ),
            )
        }
        if (sorted.size > maxDetailed) {
            out.append("  ... and ${sorted.size - maxDetailed} more (see the full index list above)\n")
        }
        return out.toString().trimEnd()
    }

    companion object {
        /** Computes the full diff between [before] and [after]. Never throws. */
        fun of(before: ByteArray, after: ByteArray): StateBlobDiff {
            if (before.size != after.size) {
                return StateBlobDiff(before.size, after.size, emptyList())
            }
            val differing = before.indices.filter { before[it] != after[it] }
            return StateBlobDiff(before.size, after.size, differing)
        }
    }
}

/**
 * Human-readable names for the small set of globally-known offsets from issue #27's
 * pre-implementation ground-truth comment (the "Complete offset map for the §2/§3 assertions"
 * table) — **diagnostic-only, for log readability**, never a source of truth for which offsets
 * [dev.tonexotg.protocol.state.StateBlobPatcher] is allowed to touch (that remains
 * [StateBlobOffsets] alone). Only single-byte fields are named exactly; the multi-byte fields in
 * that table (BPM and tuning reference — both noted `(float)`/`(uint16)` — and input trim, also a
 * float) are deliberately left unnamed here rather than guessed at a possibly-wrong per-byte
 * offset, and the 20-entry colour table's per-entry width is a variable-length varint (see
 * [StateBlobOffsets.COLOUR_TABLE_MIN_BYTES_PER_ENTRY]'s KDoc), so no single index within it maps
 * to one entry reliably either. An unnamed index is still reported — just without a label, never
 * filtered out.
 */
internal object NamedStateBlobFields {
    /** End-relative offset -> name, for offsets whose upstream `#define` names a single byte. */
    private val endRelative: Map<Int, String> = mapOf(
        StateBlobOffsets.END_SLOT_A_PRESET to "slot A preset",
        StateBlobOffsets.END_SLOT_B_PRESET to "slot B preset",
        StateBlobOffsets.END_SLOT_C_PRESET to "slot C preset",
        StateBlobOffsets.END_CURRENT_SLOT to "current slot",
        12 to "bypass mode", // TONEX_STATE_OFFSET_END_BYPASS_MODE — see StateBlobOffsets KDoc.
        7 to "direct monitor", // TONEX_STATE_OFFSET_END_DIRECT_MONITOR
        6 to "tempo source", // TONEX_STATE_OFFSET_END_TEMPO_SOURCE
    )

    /** Start-relative offset -> name, for offsets whose upstream `#define` names a single byte. */
    private val startRelative: Map<Int, String> = mapOf(
        19 to "stomp/AB mode", // TONEX_STATE_OFFSET_START_STOMP_MODE
        20 to "cab-sim bypass", // TONEX_STATE_OFFSET_START_CAB_BYPASS
        21 to "tuning mode", // TONEX_STATE_OFFSET_START_TUNING_MODE
    )

    /**
     * The name for [startRelativeIndex] within a blob of [blobSize] bytes, if it exactly matches
     * a known single-byte offset (checked both as an end-relative and a start-relative offset).
     * `null` for a genuinely unnamed byte — callers must still report the raw index in that case.
     */
    fun nameFor(startRelativeIndex: Int, blobSize: Int): String? {
        val endRelativeOffset = blobSize - startRelativeIndex
        endRelative[endRelativeOffset]?.let { return "E $endRelativeOffset, $it" }
        startRelative[startRelativeIndex]?.let { return "S $startRelativeIndex, $it" }
        return null
    }
}

/**
 * The four end-relative offsets [dev.tonexotg.protocol.state.StateBlobPatcher] is documented to
 * ever write when changing a preset slot or the active slot — read directly from
 * [StateBlobOffsets] (the real, single source of truth for these values) rather than duplicated as
 * bare literals here, so this audit cannot silently drift from what the patcher actually touches.
 */
private fun sanctionedEndRelativeOffsets(): List<Int> = listOf(
    StateBlobOffsets.END_SLOT_A_PRESET,
    StateBlobOffsets.END_SLOT_B_PRESET,
    StateBlobOffsets.END_SLOT_C_PRESET,
    StateBlobOffsets.END_CURRENT_SLOT,
)

/**
 * The result of auditing a preset-change (or any other operation expected to touch only the
 * sanctioned slot-region offsets — e.g. [dev.tonexotg.protocol.TonexController.restoreFootswitches])
 * against a real before/after state-blob pair. This is issue #27 §2/§3's actual audit: "only slot
 * bytes changed," "no global drifted," and "`DIRECT_MONITOR`/stomp-AB mode/bypass mode unchanged"
 * are all the *same* fact here — a clean [passed] result proves all three simultaneously, because
 * the blob's globals, colour table, and every other named-or-unnamed field are covered by the same
 * exhaustive per-byte comparison as the four sanctioned offsets, not verified separately.
 *
 * ## Why [expectedOffsets] exists — closing the vacuous-pass hole
 * An earlier version of this class treated *any* subset of the four sanctioned offsets — including
 * the **empty** subset — as a passing result, on the reasoning that "a subset is expected and
 * fine." An Opus review of issue #27 (pre-merge) pointed out that reasoning admits a genuinely
 * empty diff (e.g. a dropped/deferred write, or two captures that raced a stale read) as a clean
 * PASS with zero evidence anything actually happened — exactly the "no side effects detected:
 * true" report the issue's own ground-truth comment pre-declared unacceptable. [expectedOffsets]
 * closes that hole: the caller states up front which offset(s) *this specific operation* should
 * touch (derived from the real before-state, not guessed), and [passed] now requires an **exact**
 * match against what actually changed — not merely "a subset of the four," and not merely
 * "nothing unexpected." A caller with nothing to assert about which offsets should move (e.g.
 * [dev.tonexotg.protocol.diagnostics.runRevertDrill], which expects the state blob to move *zero*
 * bytes) passes `expectedOffsets = emptySet()`, the default.
 *
 * @property sanctionedOffsetsChanged which of the four named end-relative offsets actually
 *   differed.
 * @property unexpectedIndices every start-relative index that changed and is **not** one of the
 *   four sanctioned offsets. Must be empty for [passed] to be true — a non-empty list is exactly
 *   the serious, escalate-worthy finding issue #27 §3 exists to catch (something writing outside
 *   the four sanctioned offsets).
 * @property expectedOffsets which of the four sanctioned offsets the caller asserts *this*
 *   operation should have touched, computed from the real pre-write state — not a guess and not
 *   duplicated as a literal at the call site (see [dev.tonexotg.protocol.diagnostics.runPresetChangeByteDiffDrill]).
 */
data class PresetChangeAudit(
    val diff: StateBlobDiff,
    val sanctionedOffsetsChanged: Set<Int>,
    val unexpectedIndices: List<Int>,
    val expectedOffsets: Set<Int> = emptySet(),
) {
    /** Expected offsets that did NOT change, even though this operation should have touched them
     * — e.g. a write the transport reported as accepted but the pedal silently dropped. */
    val missingExpectedOffsets: Set<Int> get() = expectedOffsets - sanctionedOffsetsChanged

    /** Sanctioned offsets that changed but were **not** expected to for this specific operation
     * — e.g. a stray write to a different footswitch slot than the one being changed. Still one
     * of the four named offsets, so [unexpectedIndices] would not have caught it; this is what
     * [expectedOffsets] exists to catch instead. */
    val extraSanctionedOffsets: Set<Int> get() = sanctionedOffsetsChanged - expectedOffsets

    /**
     * `true` iff the blob's length did not change, every differing byte is accounted for by one
     * of the four sanctioned offsets, AND the sanctioned offsets that changed are **exactly**
     * [expectedOffsets] — no more, no less. This is the drill's pass/fail verdict.
     */
    val passed: Boolean get() =
        !diff.sizeChanged && unexpectedIndices.isEmpty() && missingExpectedOffsets.isEmpty() && extraSanctionedOffsets.isEmpty()

    /**
     * Full human-readable report, per issue #27's explicit reporting requirements: the full
     * sorted differing-index list with `old -> new` byte values named where possible, the
     * expected-vs-actual offset comparison, and an explicit pass/fail — never a bare boolean.
     * Deliberately safe (and intended) to log on a PASS as well as a FAIL: a clean pass with
     * concrete numbers is what turns "the tool says it's fine" into "here is the byte that moved,
     * and it was the right one," per the Opus review's own framing of why a silent pass is the
     * more dangerous half of this drill to leave unevidenced.
     */
    fun describe(before: ByteArray, after: ByteArray): String {
        fun nameOffsets(offsets: Set<Int>) = if (offsets.isEmpty()) {
            "none"
        } else {
            offsets.sorted().joinToString { "E $it (${NamedStateBlobFields.nameFor(before.size - it, before.size) ?: "?"})" }
        }
        val out = StringBuilder()
        out.append(if (passed) "PASSED" else "FAILED").append(" — ")
        out.append("expected offset(s): ${nameOffsets(expectedOffsets)}; ")
        out.append("actually changed sanctioned offset(s): ${nameOffsets(sanctionedOffsetsChanged)}")
        if (missingExpectedOffsets.isNotEmpty()) {
            out.append("\n  MISSING: expected offset(s) that did NOT change: ${nameOffsets(missingExpectedOffsets)}")
        }
        if (extraSanctionedOffsets.isNotEmpty()) {
            out.append("\n  UNEXPECTED: sanctioned offset(s) changed that were not expected: ${nameOffsets(extraSanctionedOffsets)}")
        }
        if (unexpectedIndices.isNotEmpty()) {
            out.append("\n  UNEXPECTED: byte(s) changed OUTSIDE all four sanctioned offsets — STOP AND ESCALATE")
        }
        out.append("\n").append(diff.formatDifferences(before, after))
        return out.toString()
    }

    companion object {
        /**
         * Audits [before]/[after] against [expectedOffsets] (see class KDoc — defaults to
         * "nothing should have moved"). Never throws.
         */
        fun audit(before: ByteArray, after: ByteArray, expectedOffsets: Set<Int> = emptySet()): PresetChangeAudit {
            val diff = StateBlobDiff.of(before, after)
            if (diff.sizeChanged) {
                // A length change is not diagnosable byte-by-byte at all (see StateBlobDiff's
                // KDoc) - report it as the finding it is, with no per-index claims either way.
                return PresetChangeAudit(
                    diff,
                    sanctionedOffsetsChanged = emptySet(),
                    unexpectedIndices = emptyList(),
                    expectedOffsets = expectedOffsets,
                )
            }
            // start-relative index -> the end-relative offset it corresponds to, for exactly the
            // four sanctioned offsets.
            val sanctionedIndexToOffset = sanctionedEndRelativeOffsets().associateBy { before.size - it }
            val sanctionedChanged = diff.differingIndices.mapNotNull { sanctionedIndexToOffset[it] }.toSet()
            val unexpected = diff.differingIndices.filterNot { it in sanctionedIndexToOffset.keys }
            return PresetChangeAudit(diff, sanctionedChanged, unexpected, expectedOffsets)
        }
    }
}
