package dev.tonexotg.app.ui.screens.presets

import dev.tonexotg.app.ui.screens.connection.ErrorPresentation
import dev.tonexotg.app.ui.screens.connection.toPresentation
import dev.tonexotg.protocol.ConnectionState
import dev.tonexotg.protocol.PresetIndex
import dev.tonexotg.protocol.PresetInfo
import dev.tonexotg.protocol.PresetSlot
import dev.tonexotg.protocol.TonexError

/**
 * One row's worth of display data for S16's preset list — always exactly 20 of these, one per
 * [PresetIndex] `0..19`, regardless of connection state.
 *
 * @property index the preset's onboard slot.
 * @property pedalName the pedal's own name for this slot, or `null` when it isn't known yet
 *   (not connected, or connected but not yet [ConnectionState.Ready]). Distinct from `null`
 *   meaning "the pedal reported an empty name" — this pedal always reports *something* once
 *   [ConnectionState.Ready] (S7); `null` here means "we haven't asked."
 * @property localAlias the raw local override from [dev.tonexotg.app.data.alias.PresetAliasStore],
 *   or `null` if none is set. Exposed separately from [displayName] so the edit affordance can
 *   prefill with exactly what the user typed, not a fallback.
 * @property displayName the resolved `localAlias ?: pedalName ?: "Preset N"` name to actually
 *   show — the same override rule S14 defines, with one addition: unlike
 *   [dev.tonexotg.app.data.alias.PresetAliasStore.displayName] (which requires a caller-supplied
 *   pedal name), this falls back to a slot-numbered placeholder when neither an alias nor a
 *   pedal name is available yet, so a disconnected list never renders a blank row.
 * @property isActive whether this is the pedal's current active preset. Always `false` when
 *   [PresetListUiState.isLive] is `false` — "active" is a live-pedal fact, never inferred or
 *   remembered across a disconnect (see that property's kdoc for why).
 * @property assignedSlots the footswitch slots ([PresetSlot]) currently pointed at this preset,
 *   per [dev.tonexotg.protocol.TonexController.slotAssignments] — zero, one, two, or all three at
 *   once (nothing stops two slots pointing at the same preset). Always empty when
 *   [PresetListUiState.isLive] is `false`, the same "live pedal fact, never inferred or
 *   remembered across a disconnect" rule [isActive] already follows.
 */
data class PresetListItem(
    val index: PresetIndex,
    val pedalName: String?,
    val localAlias: String?,
    val displayName: String,
    val isActive: Boolean,
    val assignedSlots: Set<PresetSlot>,
)

/**
 * The full S16 screen state: all 20 [PresetListItem]s plus whether they currently reflect live
 * pedal state.
 *
 * @property items always exactly 20 entries, ordered by [PresetIndex.value] `0..19`. Local
 *   aliases are always resolved here (they don't require a connection, per S14) — only
 *   [PresetListItem.pedalName] and [PresetListItem.isActive] depend on [isLive].
 * @property isLive `true` only while the controller is [ConnectionState.Ready]. This is the
 *   AC's "does not pretend to be live" signal: when `false`, the screen still shows every
 *   alias (and any previously-known pedal names, per [PresetListItem.pedalName]'s own kdoc —
 *   in practice the controller's own [dev.tonexotg.protocol.TonexController.presets] resets to
 *   empty on disconnect, so `pedalName` reads `null` here too, but this flag is the thing the UI
 *   actually keys "live vs. stale" off of, not an inference from whether names are present) but
 *   tap-to-load and the active-preset highlight are both disabled — selecting a preset with no
 *   live pedal to select it on would be a silent no-op at best.
 * @property selectPresetError the most recent [TonexError] from a failed tap-to-load, or `null`.
 *   Per FR11 / this project's fail-fast-and-loud house style, a failed [dev.tonexotg.protocol.TonexController.selectPreset]
 *   call is surfaced here rather than swallowed — the row simply not turning active is not
 *   itself a legible failure signal.
 * @property assignSlotError the most recent [TonexError] from a failed [dev.tonexotg.protocol.TonexController.assignPresetToSlot]
 *   call (S85 part 2b), or `null`. Same fail-fast-and-loud rationale as [selectPresetError].
 * @property globalParameters the home-screen section for the 6 relocated GLOBAL parameters
 *   (issue #83), or `null` when their live values aren't fully known yet (not connected, or
 *   connected but the pedal hasn't reported all six yet). `null` means "don't render the section
 *   at all" — never rendered with a [dev.tonexotg.protocol.ParameterSpec.default] placeholder,
 *   which the very first touch on a control could otherwise silently write back to the pedal.
 */
data class PresetListUiState(
    val items: List<PresetListItem>,
    val isLive: Boolean,
    val selectPresetError: TonexError? = null,
    val assignSlotError: TonexError? = null,
    val globalParameters: GlobalParametersUiState? = null,
) {
    val selectPresetErrorPresentation: ErrorPresentation?
        get() = selectPresetError?.toPresentation()

    val assignSlotErrorPresentation: ErrorPresentation?
        get() = assignSlotError?.toPresentation()

    companion object {
        /** Every screen starts here for one composition frame, before the first flow emission lands. */
        fun initial(): PresetListUiState = PresetListUiState(
            items = (0..19).map { i ->
                val index = PresetIndex(i)
                PresetListItem(
                    index = index,
                    pedalName = null,
                    localAlias = null,
                    displayName = placeholderName(index),
                    isActive = false,
                    assignedSlots = emptySet(),
                )
            },
            isLive = false,
        )
    }
}

/** The `"Preset N"` fallback shown for a slot with neither a local alias nor a known pedal name. */
internal fun placeholderName(index: PresetIndex): String = "Preset ${index.value + 1}"

/** Resolves one slot's display data from the raw pieces the view model combines. */
internal fun buildPresetListItem(
    index: PresetIndex,
    pedalInfo: PresetInfo?,
    localAlias: String?,
    activePreset: PresetIndex?,
    isLive: Boolean,
    slotAssignments: Map<PresetSlot, PresetIndex>,
): PresetListItem {
    val pedalName = pedalInfo?.pedalName
    return PresetListItem(
        index = index,
        pedalName = pedalName,
        localAlias = localAlias,
        displayName = localAlias ?: pedalName ?: placeholderName(index),
        isActive = isLive && activePreset == index,
        assignedSlots = if (isLive) {
            PresetSlot.entries.filter { slotAssignments[it] == index }.toSet()
        } else {
            emptySet()
        },
    )
}
