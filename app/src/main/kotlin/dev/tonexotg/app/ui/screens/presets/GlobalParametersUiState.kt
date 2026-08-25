package dev.tonexotg.app.ui.screens.presets

import dev.tonexotg.app.ui.screens.parameters.ParameterCatalog
import dev.tonexotg.app.ui.screens.parameters.ParameterRow
import dev.tonexotg.app.ui.screens.parameters.abbreviationFor
import dev.tonexotg.app.ui.screens.parameters.buildRangeRow
import dev.tonexotg.protocol.ParameterId
import dev.tonexotg.protocol.params.ParameterRegistry

/**
 * The always-visible home-screen section (issue #83) for the 6 GLOBAL-scope parameters
 * [ParameterCatalog.homeScreenGlobalIds] relocated out of the Parameter Editor's full-tier
 * accordion — [tempoSource]'s [ParameterRow.Switch.abbreviation] carries `"GLOBAL"`/`"PRESET"`
 * rather than a generic on/off label, per [dev.tonexotg.protocol.state.StateBlobOffsets.END_TEMPO_SOURCE]'s
 * documented `0 = GLOBAL, 1 = PRESET` polarity.
 */
data class GlobalParametersUiState(
    val bpm: ParameterRow.Range,
    val inputTrim: ParameterRow.Range,
    val tuningReference: ParameterRow.Range,
    val cabSimBypass: ParameterRow.Switch,
    val tempoSource: ParameterRow.Switch,
    val bypass: ParameterRow.Switch,
)

/**
 * Builds [GlobalParametersUiState] from a raw value lookup — `null` for any of
 * [ParameterCatalog.homeScreenGlobalIds] not yet present in [value] (see [effectiveValue]'s
 * caller for why absence, not a [dev.tonexotg.protocol.ParameterSpec.default] placeholder, must
 * gate this section's visibility: issue #83's whole point is never rendering a stale default the
 * user's first touch could silently write back to the pedal).
 */
internal fun buildGlobalParametersUiState(value: (ParameterId) -> Float?): GlobalParametersUiState? {
    val ids = ParameterCatalog.homeScreenGlobalIds
    val values = ids.associateWith { value(it) ?: return null }

    return GlobalParametersUiState(
        bpm = buildRangeRow(ParameterCatalog.bpmId, values.getValue(ParameterCatalog.bpmId), labelOverride = "BPM"),
        inputTrim = buildRangeRow(ParameterCatalog.inputTrimId, values.getValue(ParameterCatalog.inputTrimId), labelOverride = "Input Trim"),
        tuningReference = buildRangeRow(
            ParameterCatalog.tuningReferenceId,
            values.getValue(ParameterCatalog.tuningReferenceId),
            labelOverride = "Tuning Reference",
        ),
        // Labels read "active" now, not "bypassed" -- see ParameterCatalog.switchLabelOverride's kdoc.
        cabSimBypass = switchRow(ParameterCatalog.cabSimBypassId, values.getValue(ParameterCatalog.cabSimBypassId), label = "Cab Sim"),
        tempoSource = tempoSourceRow(values.getValue(ParameterCatalog.tempoSourceId)),
        bypass = switchRow(ParameterCatalog.bypassId, values.getValue(ParameterCatalog.bypassId), label = "Engaged"),
    )
}

private fun switchRow(id: ParameterId, rawValue: Float, label: String): ParameterRow.Switch {
    val spec = ParameterRegistry.byIndex(id.index) ?: error("No ParameterSpec for $id")
    return ParameterRow.Switch(
        id = id,
        label = label,
        abbreviation = abbreviationFor(spec, id),
        checked = (rawValue >= 0.5f) != ParameterCatalog.isBypassSemantic(id),
    )
}

/**
 * `TEMPO_SOURCE` gets real `GLOBAL`/`PRESET` labels (issue #83) instead of a generic on/off
 * abbreviation — `0 = GLOBAL, 1 = PRESET` per
 * [dev.tonexotg.protocol.state.StateBlobOffsets.END_TEMPO_SOURCE]'s KDoc.
 */
private fun tempoSourceRow(rawValue: Float): ParameterRow.Switch {
    val id = ParameterCatalog.tempoSourceId
    val checked = rawValue >= 0.5f
    return ParameterRow.Switch(
        id = id,
        label = "Tempo Source",
        abbreviation = if (checked) "PRESET" else "GLOBAL",
        checked = checked,
    )
}
