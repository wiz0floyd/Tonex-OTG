package dev.tonexotg.app.ui.screens.parameters

import dev.tonexotg.protocol.ParameterId
import dev.tonexotg.protocol.params.ParameterRegistry

/**
 * The UI-only tiering/grouping decisions D3 makes on top of the S6 registry.
 *
 * Per D3 §1 ("Protocol parity: full. UI exposure: tiered."), this is deliberately a `:app`-only
 * concern: nothing here removes a parameter from what `:protocol` can read or write, it only
 * decides what the Parameter Editor screen shows and in what grouping. Every id below is read
 * directly against [ParameterRegistry] (S6) — see this file's own tests for a check that every
 * registry entry lands in exactly one of: the quick tier's fixed cards, a model bank's per-model
 * rows, a category's flat/always-on rows, so nothing is silently dropped or duplicated.
 */
object ParameterCatalog {

    private fun ids(range: IntRange): List<ParameterId> = range.map(::ParameterId)
    private fun ids(vararg indices: Int): List<ParameterId> = indices.map(::ParameterId).toList()

    // ---- Model banks (D3 §2) --------------------------------------------------------------

    /** One mutually-exclusive model's own parameters, in the order D3 §2's table lists them. */
    data class ModelOption(
        val index: Int,
        val label: String,
        val parameterIds: List<ParameterId>,
    )

    /**
     * A `*_MODEL`/`CABINET_TYPE`-style selector plus the mutually-exclusive parameter blocks it
     * chooses between. D3 §2.1: only [selectedModelIndex]'s [ModelOption.parameterIds] are ever
     * rendered — the others are not present in the composition at all, not grayed, not collapsed.
     */
    data class ModelBank(
        val selectorId: ParameterId,
        val models: List<ModelOption>,
    ) {
        fun modelFor(index: Int): ModelOption =
            models.firstOrNull { it.index == index } ?: models.first()

        /**
         * The parameter within [modelFor]'s block whose registry `enumName` ends `_MIX` — the
         * resolution D3 §1.1 describes for the quick tier's Reverb Mix / Delay Mix cards ("the
         * quick-tier card always shows and edits whichever [mix] is currently live").
         */
        fun mixParameterFor(index: Int): ParameterId =
            modelFor(index).parameterIds.first { id ->
                ParameterRegistry.byIndex(id.index)?.enumName?.endsWith("_MIX") == true
            }
    }

    val reverbBank = ModelBank(
        selectorId = ParameterId(38), // RVB MODEL
        models = listOf(
            ModelOption(0, "Spring 1", ids(39, 40, 41, 42)),
            ModelOption(1, "Spring 2", ids(43, 44, 45, 46)),
            ModelOption(2, "Spring 3", ids(47, 48, 49, 50)),
            ModelOption(3, "Spring 4", ids(51, 52, 53, 54)),
            ModelOption(4, "Room", ids(55, 56, 57, 58)),
            ModelOption(5, "Plate", ids(59, 60, 61, 62)),
        ),
    )

    val modulationBank = ModelBank(
        selectorId = ParameterId(65), // MOD MODEL
        models = listOf(
            ModelOption(0, "Chorus", ids(66, 67, 68, 69, 70)),
            ModelOption(1, "Tremolo", ids(71, 72, 73, 74, 75, 76)),
            ModelOption(2, "Phaser", ids(77, 78, 79, 80, 81)),
            ModelOption(3, "Flanger", ids(82, 83, 84, 85, 86, 87)),
            ModelOption(4, "Rotary", ids(88, 89, 90, 91, 92, 93)),
        ),
    )

    val delayBank = ModelBank(
        selectorId = ParameterId(96), // DLY MODEL
        models = listOf(
            ModelOption(0, "Digital", ids(97, 98, 99, 100, 101, 102)),
            ModelOption(1, "Tape", ids(103, 104, 105, 106, 107, 108)),
        ),
    )

    /**
     * Modeled as a bank per D3 §2's own table (`CABINET_TYPE` alongside the other three
     * selectors) even though two of its three "models" carry zero extra parameters — Tone Model's
     * own knobs live in the "Tone Model / Amp" category, and "Disabled" has none by definition.
     * Only VIR mode discloses its 9 mic parameters.
     */
    val cabinetBank = ModelBank(
        selectorId = ParameterId(24), // MDL CAB
        models = listOf(
            ModelOption(0, "Tone Model", emptyList()),
            ModelOption(1, "VIR", ids(25, 26, 27, 28, 29, 30, 31, 32, 33)),
            ModelOption(2, "Disabled", emptyList()),
        ),
    )

    // ---- Quick tier (D3 §1.1) --------------------------------------------------------------

    sealed interface QuickTierItem {
        val label: String

        /** A fixed, single-wire-index quick-tier card (Gain, Bass, Mid, Treble). */
        data class Fixed(override val label: String, val id: ParameterId) : QuickTierItem

        /**
         * A card whose backing parameter id depends on the current value of [bank]'s selector —
         * Reverb Mix / Delay Mix (D3 §1.1's "resolves to the active [reverb/delay] model's
         * `*_MIX`"). [resolve] needs the *current* value of [ModelBank.selectorId], supplied by
         * the caller from live parameter state.
         */
        data class Resolved(override val label: String, val bank: ModelBank) : QuickTierItem {
            fun resolve(selectorValue: Float): ParameterId = bank.mixParameterFor(selectorValue.toInt())
        }
    }

    val quickTier: List<QuickTierItem> = listOf(
        QuickTierItem.Fixed("Gain", ParameterId(20)),
        QuickTierItem.Fixed("Bass", ParameterId(11)),
        QuickTierItem.Fixed("Mid", ParameterId(13)),
        QuickTierItem.Fixed("Treble", ParameterId(16)),
        QuickTierItem.Resolved("Reverb Mix", reverbBank),
        QuickTierItem.Resolved("Delay Mix", delayBank),
    )

    /** `MASTER_VOLUME` (idx 116) — deliberately excluded from [quickTier]; see D3 §1.1. */
    val masterVolumeId = ParameterId(116)

    // ---- Full tier categories (D3 §1.2) ----------------------------------------------------

    /**
     * One full-tier accordion category. Either a flat list of rows ([flatIds], e.g. Noise Gate),
     * or a set of rows always rendered ([alwaysOnIds], e.g. Reverb's Position/Enable/Model) plus
     * a [modelBank] whose currently-selected model's rows are appended after them.
     */
    data class Category(
        val title: String,
        val flatIds: List<ParameterId> = emptyList(),
        val alwaysOnIds: List<ParameterId> = emptyList(),
        val modelBank: ModelBank? = null,
    )

    val categories: List<Category> = listOf(
        Category("Noise Gate", flatIds = ids(0..4)),
        Category("Compressor", flatIds = ids(5..9)),
        Category("EQ", flatIds = ids(10..17)),
        Category("Tone Model / Amp", flatIds = ids(18, 19, 20, 21, 22, 23, 34, 35)),
        Category("Cabinet", alwaysOnIds = listOf(cabinetBank.selectorId), modelBank = cabinetBank),
        Category("Modulation", alwaysOnIds = ids(63, 64, 65), modelBank = modulationBank),
        Category("Delay", alwaysOnIds = ids(94, 95, 96), modelBank = delayBank),
        Category("Reverb", alwaysOnIds = ids(36, 37, 38), modelBank = reverbBank),
        Category("Global", flatIds = ids(110..116)),
    )

    /** The 4 `SELECT` parameters with real option labels — every other `SELECT` gets a stepper (D3 §3.2). */
    val knownLabelSelectIds: Set<ParameterId> =
        setOf(reverbBank.selectorId, modulationBank.selectorId, delayBank.selectorId, cabinetBank.selectorId)
}
