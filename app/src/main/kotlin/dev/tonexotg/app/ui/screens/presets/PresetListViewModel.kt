package dev.tonexotg.app.ui.screens.presets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import dev.tonexotg.app.data.alias.PresetAliasStore
import dev.tonexotg.protocol.ConnectionState
import dev.tonexotg.protocol.PresetIndex
import dev.tonexotg.protocol.PresetInfo
import dev.tonexotg.protocol.TonexController
import dev.tonexotg.protocol.TonexError
import dev.tonexotg.protocol.TonexResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * S16's screen state holder: the primary "all 20 presets, tap to load" screen (FR4/FR5/FR6,
 * O1/O2).
 *
 * Built against [TonexController]'s interface only, same as [dev.tonexotg.app.ui.screens.connection.ConnectionStatusViewModel]
 * — S11's real Android USB transport doesn't exist yet, and this class never needs one directly
 * anyway (it never calls [TonexController.connect]; that's the connection screen's job). A plain
 * class rather than an `androidx.lifecycle.ViewModel` subclass, for the same reason documented on
 * [dev.tonexotg.app.ui.screens.connection.ConnectionStatusViewModel]: `:app` has no
 * `lifecycle-viewmodel-compose` dependency yet.
 *
 * ## FR6: live view, not a local selection cache
 * [uiState]'s items are projected straight from [TonexController.activePreset] and
 * [TonexController.presets] — both of which the controller itself keeps current across
 * externally-initiated (footswitch) changes, per [TonexController.activePreset]'s own kdoc. This
 * class adds no debouncing, caching, or "last known selection" state of its own that could go
 * stale relative to the pedal; every recomposition of [uiState] is a fresh read of whatever the
 * controller currently reports.
 *
 * ## Disconnected behaviour
 * [PresetListUiState.isLive] tracks [TonexController.connectionState] directly — `true` only in
 * [ConnectionState.Ready]. Local aliases (from [aliasStore]) resolve regardless of connection
 * state (S14 never depended on a connection), so a disconnected list still shows every alias the
 * user has set; it just can't show pedal names for un-aliased slots, can't highlight an active
 * preset, and [selectPreset] is a no-op while not live (see that function's kdoc).
 *
 * @property controller the connection this instance reads preset state from.
 * @property aliasStore local alias overrides (S14), resolved into every row regardless of
 *   connection state.
 * @property scope the coroutine scope [uiState], [selectPreset], and [setAlias] run on. Callers
 *   supply one whose lifetime covers this instance's use (see [rememberPresetListViewModel]).
 */
class PresetListViewModel(
    private val controller: TonexController,
    private val aliasStore: PresetAliasStore,
    private val scope: CoroutineScope,
) {

    private val aliasesFlow = combine(
        (0..19).map { aliasStore.alias(PresetIndex(it)) },
    ) { aliases -> aliases.toList() }

    /** The most recent [selectPreset] failure, or `null` — folded into [uiState] below. */
    private val lastSelectError = MutableStateFlow<TonexError?>(null)

    /**
     * The current [PresetListUiState], recombined whenever the controller's connection state,
     * preset list, or active preset changes; a local alias changes; or a [selectPreset] call
     * fails. [PresetListUiState.initial] covers the one composition frame before this flow's
     * first emission; every value after that is a full recomputation, never a patch, so there's
     * no way for a stale item to survive an update to any one of its inputs.
     */
    val uiState: StateFlow<PresetListUiState> = combine(
        controller.connectionState,
        controller.presets,
        controller.activePreset,
        aliasesFlow,
        lastSelectError,
    ) { connectionState, presets, activePreset, aliases, selectError ->
        buildUiState(connectionState, presets, activePreset, aliases, selectError)
    }.stateIn(
        scope = scope,
        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
        initialValue = PresetListUiState.initial(),
    )

    private fun buildUiState(
        connectionState: ConnectionState,
        presets: List<PresetInfo>,
        activePreset: PresetIndex?,
        aliases: List<String?>,
        selectError: TonexError?,
    ): PresetListUiState {
        val isLive = connectionState is ConnectionState.Ready
        val presetsByIndex: Map<Int, PresetInfo> = presets.associateBy { it.index.value }
        val items = (0..19).map { i ->
            val index = PresetIndex(i)
            buildPresetListItem(
                index = index,
                pedalInfo = presetsByIndex[i],
                localAlias = aliases.getOrNull(i),
                activePreset = activePreset,
                isLive = isLive,
            )
        }
        return PresetListUiState(items = items, isLive = isLive, selectPresetError = selectError)
    }

    /**
     * Loads [index] as the pedal's active preset — the whole point of this screen (O1).
     *
     * A no-op while [PresetListUiState.isLive] is `false`: there is no live pedal to select a
     * preset on, and calling [TonexController.selectPreset] outside [ConnectionState.Ready]
     * would just fail with [TonexError.ProtocolStateViolation] anyway — checking here avoids a
     * round trip through the controller for a failure the UI already knows is coming.
     * Fire-and-forget by design, matching [dev.tonexotg.app.ui.screens.connection.ConnectionStatusViewModel.reconnect]:
     * progress is observable via [uiState] ([TonexController.activePreset] updates the row's
     * highlight), and a failure lands in [PresetListUiState.selectPresetError] rather than being
     * silently dropped (fail fast and loud).
     */
    fun selectPreset(index: PresetIndex) {
        if (!uiState.value.isLive) return
        scope.launch {
            when (val result = controller.selectPreset(index)) {
                is TonexResult.Success -> lastSelectError.value = null
                is TonexResult.Failure -> lastSelectError.value = result.error
            }
        }
    }

    /**
     * Sets or clears [index]'s local alias to [newAlias] (S14 inline editing). Blank/whitespace-only
     * input clears the alias instead of calling [PresetAliasStore.setAlias] with it — that method
     * throws on a blank string (see its kdoc); trimming and re-routing here means this screen's
     * edit UI never has to special-case "the user cleared the text field" separately from "the
     * user tapped clear."
     */
    fun setAlias(index: PresetIndex, newAlias: String) {
        val trimmed = newAlias.trim()
        scope.launch {
            if (trimmed.isEmpty()) {
                aliasStore.clearAlias(index)
            } else {
                aliasStore.setAlias(index, trimmed)
            }
        }
    }
}

/**
 * Creates and remembers a [PresetListViewModel] scoped to the current composition, using
 * [rememberCoroutineScope] in place of a real `ViewModel`'s `viewModelScope` — same pattern as
 * [dev.tonexotg.app.ui.screens.connection.rememberConnectionStatusViewModel]. Re-created only if
 * [controller] or [aliasStore] identity changes.
 */
@Composable
fun rememberPresetListViewModel(
    controller: TonexController,
    aliasStore: PresetAliasStore,
): PresetListViewModel {
    val scope = rememberCoroutineScope()
    return remember(controller, aliasStore) {
        PresetListViewModel(controller, aliasStore, scope)
    }
}
