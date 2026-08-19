package dev.tonexotg.protocol.diagnostics

import dev.tonexotg.protocol.state.StateBlobOffsets

/**
 * ⚠️ DIAGNOSTIC-ONLY (issue #27, S22). A full, byte-by-byte comparison between two captures of
 * the pedal's whole-device state blob (`GetState`, wire type `0x0306`).
 *
 * Deliberately compares **every** byte, not a spot-check of a few named fields: a spot-check can
 * never catch drift at a byte nobody thought to name (e.g. `DIRECT_MONITOR` or stomp/AB mode —
 * upstream side effects [dev.tonexotg.protocol.state.StateBlobPatcher] is documented to never
 * write, but never independently confirmed against a real captured blob until this drill).
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
 * bytes changed," "no global drifted," and "`DIRECT_MONITOR`/stomp-AB mode unchanged" are all the
 * *same* fact here — a clean [passed] result proves all three simultaneously, because the blob's
 * globals, colour table, and every other named-or-unnamed field are covered by the same exhaustive
 * per-byte comparison as the four sanctioned offsets, not verified separately.
 *
 * @property sanctionedOffsetsChanged which of the four named end-relative offsets actually
 *   differed. A **subset** of all four is expected and fine — e.g.
 *   [dev.tonexotg.protocol.connection.DefaultTonexController.selectPreset] touches only the
 *   active-slot byte when the target preset is already assigned to a different slot, and is a
 *   complete no-write no-op when the target is already active. An empty set here is not itself a
 *   failure.
 * @property unexpectedIndices every start-relative index that changed and is **not** one of the
 *   four sanctioned offsets. Must be empty for [passed] to be true — a non-empty list is exactly
 *   the serious, escalate-worthy finding issue #27 §3 exists to catch (something writing outside
 *   the four sanctioned offsets).
 */
data class PresetChangeAudit(
    val diff: StateBlobDiff,
    val sanctionedOffsetsChanged: Set<Int>,
    val unexpectedIndices: List<Int>,
) {
    /**
     * `true` iff the blob's length did not change AND every differing byte is accounted for by
     * one of the four sanctioned offsets. This is the drill's pass/fail verdict.
     */
    val passed: Boolean get() = !diff.sizeChanged && unexpectedIndices.isEmpty()

    companion object {
        /** Audits [before]/[after] — see class KDoc. Never throws. */
        fun audit(before: ByteArray, after: ByteArray): PresetChangeAudit {
            val diff = StateBlobDiff.of(before, after)
            if (diff.sizeChanged) {
                // A length change is not diagnosable byte-by-byte at all (see StateBlobDiff's
                // KDoc) - report it as the finding it is, with no per-index claims either way.
                return PresetChangeAudit(diff, sanctionedOffsetsChanged = emptySet(), unexpectedIndices = emptyList())
            }
            // start-relative index -> the end-relative offset it corresponds to, for exactly the
            // four sanctioned offsets.
            val sanctionedIndexToOffset = sanctionedEndRelativeOffsets().associateBy { before.size - it }
            val sanctionedChanged = diff.differingIndices.mapNotNull { sanctionedIndexToOffset[it] }.toSet()
            val unexpected = diff.differingIndices.filterNot { it in sanctionedIndexToOffset.keys }
            return PresetChangeAudit(diff, sanctionedChanged, unexpected)
        }
    }
}
