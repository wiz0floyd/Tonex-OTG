package dev.tonexotg.app.ui.screens.presets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import dev.tonexotg.app.data.alias.PresetAliasStore
import dev.tonexotg.app.ui.screens.parameters.ParameterCatalog
import dev.tonexotg.app.ui.screens.parameters.ParameterWriteThrottler
import dev.tonexotg.app.ui.screens.parameters.buildRangeRow
import dev.tonexotg.protocol.ConnectionState
import dev.tonexotg.protocol.ParameterId
import dev.tonexotg.protocol.PresetIndex
import dev.tonexotg.protocol.PresetInfo
import dev.tonexotg.protocol.PresetSlot
import dev.tonexotg.protocol.TonexController
import dev.tonexotg.protocol.TonexError
import dev.tonexotg.protocol.TonexResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
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

    /**
     * [controller.presets] paired with [controller.slotAssignments] into one flow, the same
     * pattern [aliasesFlow] already uses to pre-combine several sources into one arm —
     * [kotlinx.coroutines.flow.combine] only has typed overloads up to 5 flows, and [uiState]'s
     * own `combine` below is already at that ceiling without this.
     */
    private val pedalPresetState = combine(
        controller.presets,
        controller.slotAssignments,
    ) { presets, slotAssignments -> presets to slotAssignments }

    /** The most recent [selectPreset] failure, or `null` — folded into [uiState] below. */
    private val lastSelectError = MutableStateFlow<TonexError?>(null)

    /** The most recent [assignToSlot] failure, or `null` — folded into [uiState] below. */
    private val lastAssignError = MutableStateFlow<TonexError?>(null)

    /**
     * [lastSelectError] and [lastAssignError] pre-combined into one arm — the same reason
     * [pedalPresetState] exists: [uiState]'s own `combine` below is already at the 5-flow ceiling
     * of [kotlinx.coroutines.flow.combine]'s typed overloads without this.
     */
    private val writeErrors = combine(lastSelectError, lastAssignError) { select, assign -> select to assign }

    // ---- home-screen global-parameters section (issue #83) --------------------------------------

    /** A value just submitted for one of [ParameterCatalog.homeScreenGlobalIds], held until [controller.parameterValues] catches up or the write fails — same "optimistic override" pattern as [dev.tonexotg.app.ui.screens.parameters.ParameterEditorViewModel]. */
    private val _globalOverrides = MutableStateFlow<Map<ParameterId, Float>>(emptyMap())

    private val globalWriteThrottler = ParameterWriteThrottler(
        scope = scope,
        write = controller::setParameter,
        onResult = { id, _, result -> handleGlobalWriteResult(id, result) },
    )

    /**
     * The most recent global-parameter write failure, or `null` — folded into [uiState] below.
     * Review finding 2 on PR #101: a swallowed failure here would depart from the same
     * fail-fast-and-loud rule [lastSelectError]/[lastAssignError] already follow on this screen,
     * leaving a control that silently slides back to its old value as the only "error" signal.
     */
    private val lastGlobalWriteError = MutableStateFlow<TonexError?>(null)

    /** [controller.parameterValues], [_globalOverrides] and [lastGlobalWriteError] pre-combined into one arm — same 5-flow-ceiling reason as [pedalPresetState]/[writeErrors]. */
    private val globalParameterState = combine(
        controller.parameterValues,
        _globalOverrides,
        lastGlobalWriteError,
    ) { values, overrides, writeError -> Triple(values, overrides, writeError) }

    /**
     * The current [PresetListUiState], recombined whenever the controller's connection state,
     * preset list, slot assignments, or active preset changes; a local alias changes; a
     * [selectPreset]/[assignToSlot] call fails; or the six home-screen global parameter values
     * change. [PresetListUiState.initial] covers the one composition frame before this flow's
     * first emission; every value after that is a full recomputation, never a patch, so there's no
     * way for a stale item to survive an update to any one of its inputs.
     *
     * Two-level `combine` (an inner 5-arg arm, then this outer 2-arg one) because
     * [kotlinx.coroutines.flow.combine] only has typed overloads up to 5 flows and adding
     * [globalParameterState] as a 6th arm here would exceed that ceiling.
     */
    private val corePresetState = combine(
        controller.connectionState,
        pedalPresetState,
        controller.activePreset,
        aliasesFlow,
        writeErrors,
    ) { connectionState, pedalState, activePreset, aliases, errors ->
        val (presets, slotAssignments) = pedalState
        val (selectError, assignError) = errors
        CorePresetState(connectionState, presets, activePreset, aliases, selectError, assignError, slotAssignments)
    }

    val uiState: StateFlow<PresetListUiState> = combine(
        corePresetState,
        globalParameterState,
    ) { core, globalState ->
        val (parameterValues, overrides, globalWriteError) = globalState
        buildUiState(core, parameterValues, overrides, globalWriteError)
    }.stateIn(
        scope = scope,
        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
        initialValue = PresetListUiState.initial(),
    )

    private data class CorePresetState(
        val connectionState: ConnectionState,
        val presets: List<PresetInfo>,
        val activePreset: PresetIndex?,
        val aliases: List<String?>,
        val selectError: TonexError?,
        val assignError: TonexError?,
        val slotAssignments: Map<PresetSlot, PresetIndex>,
    )

    private fun buildUiState(
        core: CorePresetState,
        parameterValues: Map<ParameterId, Float>,
        globalOverrides: Map<ParameterId, Float>,
        globalWriteError: TonexError?,
    ): PresetListUiState {
        val isLive = core.connectionState is ConnectionState.Ready
        val presetsByIndex: Map<Int, PresetInfo> = core.presets.associateBy { it.index.value }
        val items = (0..19).map { i ->
            val index = PresetIndex(i)
            buildPresetListItem(
                index = index,
                pedalInfo = presetsByIndex[i],
                localAlias = core.aliases.getOrNull(i),
                activePreset = core.activePreset,
                isLive = isLive,
                slotAssignments = core.slotAssignments,
            )
        }
        // issue #83: absence, not a ParameterRegistry default, is what gates this section's
        // visibility - buildGlobalParametersUiState returns null the moment any of the six ids is
        // not yet known, so the section simply doesn't render rather than showing a stale default
        // the first touch could silently write back to the pedal.
        val globalParameters = if (isLive) {
            buildGlobalParametersUiState { id -> globalOverrides[id] ?: parameterValues[id] }
        } else {
            null
        }
        // issue #107: master volume follows the exact same "absence gates visibility, never a
        // ParameterRegistry default" rule as globalParameters above — not connected, or connected
        // but the pedal hasn't reported MASTER_VOLUME yet, both render nothing on this screen.
        val masterVolumeId = ParameterCatalog.masterVolumeId
        val masterVolume = if (isLive) {
            (globalOverrides[masterVolumeId] ?: parameterValues[masterVolumeId])?.let { raw ->
                buildRangeRow(masterVolumeId, raw)
            }
        } else {
            null
        }
        return PresetListUiState(
            items = items,
            isLive = isLive,
            selectPresetError = core.selectError,
            assignSlotError = core.assignError,
            globalParameters = globalParameters,
            globalWriteError = globalWriteError,
            masterVolume = masterVolume,
        )
    }

    /**
     * Loads [index] as the pedal's active preset — the whole point of this screen (O1).
     *
     * A no-op while the controller isn't [ConnectionState.Ready]: there is no live pedal to
     * select a preset on, and calling [TonexController.selectPreset] outside [ConnectionState.Ready]
     * would just fail with [TonexError.ProtocolStateViolation] anyway — checking here avoids a
     * round trip through the controller for a failure the UI already knows is coming. Reads
     * [TonexController.connectionState] directly rather than [uiState]'s own `isLive` — unlike
     * the controller's flows, [uiState] is a [kotlinx.coroutines.flow.SharingStarted.WhileSubscribed]
     * projection that only reflects live values while something is actively collecting it, so
     * gating on it here would make this check's correctness depend on collector presence.
     * Fire-and-forget by design, matching [dev.tonexotg.app.ui.screens.connection.ConnectionStatusViewModel.reconnect]:
     * progress is observable via [uiState] ([TonexController.activePreset] updates the row's
     * highlight), and a failure lands in [PresetListUiState.selectPresetError] rather than being
     * silently dropped (fail fast and loud).
     */
    fun selectPreset(index: PresetIndex) {
        if (controller.connectionState.value !is ConnectionState.Ready) return
        scope.launch {
            when (val result = controller.selectPreset(index)) {
                is TonexResult.Success -> lastSelectError.value = null
                is TonexResult.Failure -> lastSelectError.value = result.error
            }
        }
    }

    /**
     * Assigns [index] to [slot] on the pedal (S85 part 2b) — the direct "edit what footswitch
     * slot A/B/C plays" entry point, distinct from [selectPreset]'s "load this preset now."
     *
     * Same fire-and-forget/gate-on-[ConnectionState.Ready] shape as [selectPreset], and
     * deliberately makes **no** local guess about the result: [TonexController.assignPresetToSlot]'s
     * own kdoc documents that a single call can move a *different* slot's assignment (the swap's
     * source) and even [TonexController.activePreset] itself, and both [TonexController.slotAssignments]
     * and [activePreset] are push-driven only, with no optimistic update from this controller. This
     * function does not attempt to render its own before-the-pedal-confirms guess of any of
     * that — [uiState] simply re-renders off the controller's real flows once the confirming push
     * arrives, exactly as [selectPreset]'s tap-to-load already does for `activePreset`.
     */
    fun assignToSlot(index: PresetIndex, slot: PresetSlot) {
        if (controller.connectionState.value !is ConnectionState.Ready) return
        scope.launch {
            when (val result = controller.assignPresetToSlot(slot, index)) {
                is TonexResult.Success -> lastAssignError.value = null
                is TonexResult.Failure -> lastAssignError.value = result.error
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

    // ---- home-screen global-parameters section writes (issue #83) -------------------------------

    /**
     * Continuous slider drag for one of [ParameterCatalog.homeScreenGlobalIds]'s 3 RANGE controls
     * — **updates the optimistic override only; it deliberately does not write.**
     *
     * Review finding 1 on PR #101: unlike a PRESET parameter (`ParameterWriteMessage`) or
     * `MASTER_VOLUME` ([dev.tonexotg.protocol.message.MasterVolumeMessage]), each of these six
     * writes costs a full state-blob read-modify-write inside
     * `DefaultTonexController.writeParameterLocked` — a `RequestState` round trip, a
     * `StateBlobPatcher` patch, then a `SetState` of the *entire* blob (slots, active preset,
     * colour table and all). `selectPreset`/`restoreFootswitches` set the precedent of **one**
     * such blob rewrite per discrete user action; writing per drag tick would instead issue tens
     * of them per gesture, each with its own clobber window against anything that changed in
     * between. The rate, not the mechanism, is the problem — so the write moves to
     * [onGlobalRangeChangeFinished] (the pointer release). The displayed value stays responsive
     * throughout because the override alone already drives it.
     */
    fun onGlobalRangeDrag(id: ParameterId, value: Float) {
        _globalOverrides.update { it + (id to value) }
    }

    /**
     * Pointer release on one of the 3 RANGE controls — the single write for the whole gesture,
     * carrying whatever value [onGlobalRangeDrag] last recorded. Still routed through
     * [globalWriteThrottler] so a flurry of quick successive releases conflates rather than
     * queueing an unbounded backlog of full-blob rewrites.
     */
    fun onGlobalRangeChangeFinished(id: ParameterId) {
        val value = _globalOverrides.value[id] ?: return
        globalWriteThrottler.submit(id, value)
    }

    /**
     * One switch toggle for one of [ParameterCatalog.homeScreenGlobalIds]'s 3 SWITCH controls.
     * Routed through [globalWriteThrottler] rather than a bare [scope].launch (review finding 1 on
     * PR #101) so a rapid double-tap conflates to a single blob rewrite instead of queueing two
     * uncontrolled ones.
     */
    fun onGlobalSwitchToggle(id: ParameterId, checked: Boolean) {
        val raw = checked != ParameterCatalog.isBypassSemantic(id)
        val value = if (raw) 1f else 0f
        _globalOverrides.update { it + (id to value) }
        globalWriteThrottler.submit(id, value)
    }

    /**
     * Continuous slider drag on the sticky home-screen master-volume row (issue #107,
     * `MASTER_VOLUME` idx 116). Unlike [onGlobalRangeDrag]'s deliberate release-only write (this
     * class's own kdoc explains why: each of *those* six writes costs a full state-blob
     * read-modify-write), `MASTER_VOLUME` writes through [dev.tonexotg.protocol.message.MasterVolumeMessage]
     * — a cheap, dedicated wire message, not a blob rewrite — so this mirrors
     * [dev.tonexotg.app.ui.screens.parameters.ParameterEditorViewModel.onRangeDrag]'s per-tick cadence
     * instead: every drag tick both updates the optimistic override and submits through
     * [globalWriteThrottler] (last-value-wins conflation still applies; see that class's kdoc).
     * Reuses [globalWriteThrottler]/[_globalOverrides]/[lastGlobalWriteError] rather than a second
     * set of state — [ParameterId] already keys the throttler's per-parameter workers, so sharing
     * them with the six home-screen globals costs nothing and keeps one error slot for this whole
     * screen's global-parameter writes.
     */
    fun onMasterVolumeDrag(value: Float) {
        val id = ParameterCatalog.masterVolumeId
        _globalOverrides.update { it + (id to value) }
        globalWriteThrottler.submit(id, value)
    }

    /** Once [id]'s write completes (success or failure), its optimistic override is no longer needed — same rationale as [dev.tonexotg.app.ui.screens.parameters.ParameterEditorViewModel.handleWriteResult]. */
    private fun handleGlobalWriteResult(id: ParameterId, result: TonexResult<Unit>) {
        // Dropped on success AND on failure: a local override must never keep pretending a
        // rejected write actually landed, so the control falls back to the controller's own last
        // known value either way.
        _globalOverrides.update { it - id }
        // ...and the failure itself is surfaced rather than swallowed (review finding 2 on PR
        // #101), the same way selectPreset/assignToSlot failures already are on this screen — a
        // control quietly sliding back to its old value is not a legible failure signal.
        lastGlobalWriteError.value = (result as? TonexResult.Failure)?.error
    }

    /** Stops every in-flight/buffered throttled global-parameter write. Call from the caller's own teardown (e.g. `onDispose`). */
    fun onGlobalParametersCleared() {
        globalWriteThrottler.cancelAll()
    }

    // ---- error dismissal (issue #93) -------------------------------------------------------------
    // Each of the three write-error slots above only clears itself on a subsequent *successful*
    // call of the same kind, which left the panel permanently stuck after a single failure with no
    // way to get it off the screen. These give the panel's "Dismiss" button somewhere real to go,
    // independent of ever retrying the action that failed.

    /** Clears [lastSelectError], dismissing [PresetListUiState.selectPresetError]'s panel. */
    fun dismissSelectError() {
        lastSelectError.value = null
    }

    /** Clears [lastAssignError], dismissing [PresetListUiState.assignSlotError]'s panel. */
    fun dismissAssignError() {
        lastAssignError.value = null
    }

    /** Clears [lastGlobalWriteError], dismissing [PresetListUiState.globalWriteError]'s panel. */
    fun dismissGlobalWriteError() {
        lastGlobalWriteError.value = null
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
