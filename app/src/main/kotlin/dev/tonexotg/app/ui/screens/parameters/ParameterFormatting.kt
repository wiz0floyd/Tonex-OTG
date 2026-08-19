package dev.tonexotg.app.ui.screens.parameters

import dev.tonexotg.protocol.ParameterSpec
import java.util.Locale

/**
 * Display-formatting helpers shared by [ParameterEditorViewModel] (building `valueText` for
 * [dev.tonexotg.app.ui.components.ParameterControl]) and the numeric-entry sheet's
 * `decimalPlaces` (D3 §3.1: "exact decimal-place defaults... are an S17 implementation detail
 * within this pattern, not a product decision this document needs to pin down further").
 *
 * Kept in its own file (rather than folded into the view model) so the formatting rule is a
 * single, directly-unit-testable function independent of any Compose/coroutine machinery.
 */

/**
 * D3 §3.1's default: "one decimal for 0-10 float knobs, whole numbers for ms/Hz/RPM/BPM." Applied
 * generically from [ParameterSpec.unit] rather than special-casing individual parameters: a
 * dimensionless `RANGE` (empty unit — the 0-10 knobs, plus fractional ones like `EQ_MIDQ`)
 * gets 1 decimal place; every unit-bearing engineering quantity (dB, ms, Hz, RPM, BPM, %) gets 0,
 * matching D2's own rendered examples ("62%", not "62.0%").
 */
internal fun decimalPlacesFor(spec: ParameterSpec): Int = if (spec.unit.isEmpty()) 1 else 0

/** The value chip / numeric-entry field's display text: [value] formatted to [spec]'s own precision, plus its unit suffix (if any). */
internal fun formatValueText(value: Float, spec: ParameterSpec): String {
    val decimalPlaces = decimalPlacesFor(spec)
    val number = String.format(Locale.US, "%.${decimalPlaces}f", value)
    // "%" reads naturally with no space ("28%"); every other unit gets a separating space ("-64 dB").
    return when {
        spec.unit.isEmpty() -> number
        spec.unit == "%" -> "$number%"
        else -> "$number ${spec.unit}"
    }
}
