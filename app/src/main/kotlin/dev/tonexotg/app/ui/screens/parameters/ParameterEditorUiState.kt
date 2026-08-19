package dev.tonexotg.app.ui.screens.parameters

import dev.tonexotg.protocol.ConnectionState
import dev.tonexotg.protocol.ParameterId
import dev.tonexotg.protocol.PresetIndex

/**
 * One rendered row in the full-tier accordion (D3 §1.2/§3.2), already resolved to a concrete
 * widget shape from the registry's [dev.tonexotg.protocol.ParameterType] — the view model does
 * this mapping once, so [ParameterEditorScreen] never has to `when` on a raw `ParameterSpec`
 * itself.
 */
sealed interface ParameterRow {
    val id: ParameterId
    val label: String
    val abbreviation: String

    /** A `RANGE` parameter (D3 §3/§3.1) — slider + numeric-entry-sheet chip. */
    data class Range(
        override val id: ParameterId,
        override val label: String,
        override val abbreviation: String,
        val value: Float,
        val range: ClosedFloatingPointRange<Float>,
        val valueText: String,
        val decimalPlaces: Int,
        val unit: String,
    ) : ParameterRow

    /** A `SWITCH` parameter (D3 §3.2) — one-tap toggle, no sheet. */
    data class Switch(
        override val id: ParameterId,
        override val label: String,
        override val abbreviation: String,
        val checked: Boolean,
    ) : ParameterRow

    /** A `SELECT` parameter with **no** known option labels (D3 §3.2) — the −/+ stepper. */
    data class Stepper(
        override val id: ParameterId,
        override val label: String,
        override val abbreviation: String,
        val value: Int,
        val range: IntRange,
    ) : ParameterRow
}

/**
 * A `*_MODEL`/`CABINET_TYPE` selector row (D3 §2, §3.2's known-label chip row) — rendered via
 * [dev.tonexotg.app.ui.components.ParameterSelectChipRow], never folded into [ParameterRow]
 * because choosing an option here changes which *other* rows exist in the composition
 * ([CategoryUiState.Banked.modelRows]), which a single row's own state can't express.
 */
data class ModelSelectorUiState(
    val id: ParameterId,
    val label: String,
    val options: List<Int>,
    val selectedIndex: Int,
    val optionLabel: (Int) -> String,
)

/** One full-tier accordion category (D3 §1.2). */
sealed interface CategoryUiState {
    val title: String

    /** A category with no model bank — every row always renders (e.g. Noise Gate, EQ, Global). */
    data class Flat(override val title: String, val rows: List<ParameterRow>) : CategoryUiState

    /**
     * A category built around a [ModelBank][dev.tonexotg.app.ui.screens.parameters.ParameterCatalog.ModelBank]
     * (Cabinet, Modulation, Delay, Reverb). [alwaysOnRows] render unconditionally;
     * [selector]'s currently-chosen option decides [modelRows] — D3 §2.1's "hidden entirely, not
     * grayed" rule is enforced simply by [modelRows] never containing another model's parameters
     * at all, not by any visibility flag on them.
     */
    data class Banked(
        override val title: String,
        val alwaysOnRows: List<ParameterRow>,
        val selector: ModelSelectorUiState,
        val modelRows: List<ParameterRow>,
    ) : CategoryUiState
}

/** One quick-tier card (D3 §1.1) — always a `RANGE` row; [resolvedCaption] is set only for the two `Resolved` (mix) cards. */
data class QuickTierCardUiState(
    val row: ParameterRow.Range,
    val resolvedCaption: String? = null,
)

/**
 * Everything [ParameterEditorScreen] needs to render one frame, assembled by
 * [ParameterEditorViewModel] from live [dev.tonexotg.protocol.TonexController] state plus this
 * screen's own local UI state (accordion expansion, open sheets, dialog visibility).
 */
data class ParameterEditorUiState(
    val connectionState: ConnectionState,
    val presetName: String,
    val presetIndex: PresetIndex?,
    val quickTier: List<QuickTierCardUiState>,
    val masterVolume: ParameterRow.Range?,
    val categories: List<CategoryUiState>,
    val expandedCategories: Set<String>,
    val numericEntryTarget: ParameterRow.Range?,
    val firstWriteWarningVisible: Boolean,
    val revertConfirmVisible: Boolean,
    val revertError: String?,
    val externalPresetChangeMessage: String?,
) {
    companion object {
        val Empty = ParameterEditorUiState(
            connectionState = ConnectionState.Idle,
            presetName = "",
            presetIndex = null,
            quickTier = emptyList(),
            masterVolume = null,
            categories = emptyList(),
            expandedCategories = emptySet(),
            numericEntryTarget = null,
            firstWriteWarningVisible = false,
            revertConfirmVisible = false,
            revertError = null,
            externalPresetChangeMessage = null,
        )
    }
}
