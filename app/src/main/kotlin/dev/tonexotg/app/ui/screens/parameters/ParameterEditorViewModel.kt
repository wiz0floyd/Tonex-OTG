package dev.tonexotg.app.ui.screens.parameters

import dev.tonexotg.protocol.ConnectionState
import dev.tonexotg.protocol.ParameterId
import dev.tonexotg.protocol.ParameterScope
import dev.tonexotg.protocol.ParameterSpec
import dev.tonexotg.protocol.ParameterType
import dev.tonexotg.protocol.PresetIndex
import dev.tonexotg.protocol.PresetInfo
import dev.tonexotg.protocol.TonexController
import dev.tonexotg.protocol.TonexEvent
import dev.tonexotg.protocol.TonexResult
import dev.tonexotg.protocol.params.EffectiveParameterBounds
import dev.tonexotg.protocol.params.ParameterRegistry
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Drives [ParameterEditorUiState] from a live [TonexController] plus this screen's own local UI
 * state (accordion expansion, an open numeric-entry sheet, dialog visibility) — the `:app`-side
 * half of S17 (issue #22). Deliberately **not** an `androidx.lifecycle.ViewModel` subclass: `:app`
 * does not yet depend on `lifecycle-viewmodel`/`-compose` (see `app/build.gradle.kts`), and this
 * class needs nothing from that base class beyond a `CoroutineScope` — [scope] is supplied by the
 * caller (a screen-level `rememberCoroutineScope()`, or a test's own `TestScope`) instead, and
 * [onCleared] is a plain method the caller invokes on teardown (e.g. from
 * `DisposableEffect { onDispose { viewModel.onCleared() } }`) rather than an inherited lifecycle
 * hook. This keeps the class a plain, directly-constructible, directly-testable Kotlin object —
 * no Robolectric, no Android framework, needed to exercise any of its logic.
 *
 * ## Continuous vs. discrete writes
 * [onRangeDrag] (slider drags) goes through [ParameterWriteThrottler] — NFR2's conflate-in-flight
 * requirement. Every other write ([onSwitchToggle], [onStepperChange], [onModelSelect],
 * [onNumericEntrySet]) is a single, immediate `suspend` call directly against [controller] — each
 * is one discrete tap, not a stream of values, so there is nothing to conflate (D3 §2.3: model
 * selection "is simply the next ordinary action," not something needing reconciliation with a
 * throttled stream).
 *
 * ## Optimistic overrides
 * A value just submitted (by either path) is held in [overrides] until [controller]'s own
 * [TonexController.parameterValues] catches up (write succeeded) or the attempt is abandoned
 * (write failed) — see [handleWriteResult]. Without this, a slider would visually snap back to
 * the pre-drag value on every conflated write's round trip, since [TonexController.parameterValues]
 * only updates *after* a write actually completes.
 *
 * ## Revert and the first-destructive-write notice
 * D3 §5.1 places the revert entry point in **Settings**, a screen S18 has not built yet in this
 * codebase. Issue #22 nonetheless calls out "surface the revert action" as in-scope for S17, so
 * [ParameterEditorScreen] exposes it directly on this screen for now (placed at the very bottom,
 * past the full-tier accordion — the furthest a player can be from it without a second screen —
 * to keep as much of D3's "physical separation from anything touched mid-song" intent as a
 * single-screen scope allows). Move this to Settings once S18 exists; nothing here assumes it
 * stays on this screen permanently. The first-destructive-write dialog needs no bookkeeping of
 * its own here at all: [TonexController] already gates it end-to-end via `SnapshotStore` (S9b) —
 * this class only listens for [TonexEvent.FirstDestructiveWrite] and flips a visibility flag.
 *
 * ## Effective bounds, not just the static registry (issue #80)
 * [effectiveBounds] defaults to [EffectiveParameterBounds.STATIC] (the registry's own static
 * `max`, unchanged behaviour) but the production caller (`TonexSessionHolder`) supplies its
 * session-scoped `SelfWideningParameterBounds` instance so the three self-widening allowlisted
 * parameters ([dev.tonexotg.protocol.params.ParameterRegistry.SELF_WIDENING_PARAMETER_IDS]) get
 * a widened write-clamp ([onDiscreteChange]) and a widened stepper `range` ([buildRow]) that
 * track `DefaultTonexController`'s own effective bounds exactly — see that class's KDoc for why
 * this must be one shared source, not a UI-only cosmetic widening.
 */
class ParameterEditorViewModel(
    private val controller: TonexController,
    private val scope: CoroutineScope,
    private val effectiveBounds: EffectiveParameterBounds = EffectiveParameterBounds.STATIC,
) {
    private val throttler = ParameterWriteThrottler(
        scope = scope,
        write = controller::setParameter,
        onResult = ::handleWriteResult,
    )

    private val _overrides = MutableStateFlow<Map<ParameterId, Float>>(emptyMap())
    private val _expandedCategories = MutableStateFlow<Set<String>>(emptySet())
    private val _numericEntryTargetId = MutableStateFlow<ParameterId?>(null)
    private val _firstWriteWarningVisible = MutableStateFlow(false)
    private val _revertConfirmVisible = MutableStateFlow(false)
    private val _revertError = MutableStateFlow<String?>(null)

    /** A write failure, tagged with which [ParameterId] it was for — see [handleWriteResult]. */
    private data class WriteError(val id: ParameterId, val message: String)
    private val _writeError = MutableStateFlow<WriteError?>(null)
    private val _externalPresetChangeMessage = MutableStateFlow<String?>(null)

    private val controllerSnapshot = combine(
        controller.connectionState,
        controller.activePreset,
        controller.presets,
        controller.parameterValues,
    ) { connectionState, activePreset, presets, parameterValues ->
        ControllerSnapshot(connectionState, activePreset, presets, parameterValues)
    }

    private val localEditState = combine(
        _overrides,
        _expandedCategories,
        _numericEntryTargetId,
    ) { overrides, expanded, numericEntryTargetId ->
        LocalEditState(overrides, expanded, numericEntryTargetId)
    }

    private val dialogState = combine(
        _firstWriteWarningVisible,
        _revertConfirmVisible,
        _revertError,
        _writeError,
        _externalPresetChangeMessage,
    ) { firstWrite, revertConfirm, revertError, writeError, externalMessage ->
        DialogState(firstWrite, revertConfirm, revertError, writeError?.message, externalMessage)
    }

    val uiState: StateFlow<ParameterEditorUiState> =
        combine(controllerSnapshot, localEditState, dialogState) { snapshot, editState, dialog ->
            buildUiState(snapshot, editState, dialog)
        }.stateIn(scope, SharingStarted.Eagerly, ParameterEditorUiState.Empty)

    init {
        scope.launch {
            controller.events.collect { event ->
                when (event) {
                    is TonexEvent.FirstDestructiveWrite -> _firstWriteWarningVisible.value = true

                    is TonexEvent.ExternalPresetChange -> {
                        // D3 §6.3: an in-flight drag or open sheet belongs to the *stale* preset
                        // and must not land a write against whatever just became active.
                        throttler.cancelAll()
                        _numericEntryTargetId.value = null
                        _overrides.value = emptyMap()
                        val name = controller.presets.value.firstOrNull { it.index == event.newIndex }?.pedalName
                            ?: "preset ${event.newIndex.value + 1}"
                        _externalPresetChangeMessage.value = "Pedal switched to $name (footswitch)"
                    }
                }
            }
        }
    }

    /**
     * Continuous slider input (D3 §3) — clamped, held as an optimistic override, and submitted
     * through [throttler].
     *
     * Clamps against [effectiveBounds], not `spec.max` directly (Opus review, PR #81) — the same
     * class of gap [onDiscreteChange] was fixed for issue #80. Currently a no-op distinction in
     * practice: all three self-widening allowlisted ids ([dev.tonexotg.protocol.params.ParameterRegistry.SELF_WIDENING_PARAMETER_IDS])
     * are `SELECT` type, which only ever reaches a `Stepper`/[onDiscreteChange], never a
     * `Slider`/this function — but fixing it here now avoids leaving the exact same
     * static-vs-effective-bounds mismatch latent for a future RANGE-type addition to the allowlist
     * to rediscover.
     */
    fun onRangeDrag(id: ParameterId, rawValue: Float) {
        val spec = ParameterRegistry.byIndex(id.index) ?: return
        if (isWriteUnsupported(spec)) {
            _writeError.value = WriteError(id, UNSUPPORTED_WRITE_MESSAGE)
            return
        }
        val clamped = rawValue.coerceIn(spec.min, effectiveBounds.effectiveMax(id))
        _overrides.update { it + (id to clamped) }
        throttler.submit(id, clamped)
    }

    /** One `SWITCH` toggle tap (D3 §3.2). */
    fun onSwitchToggle(id: ParameterId, checked: Boolean) = onDiscreteChange(id, if (checked) 1f else 0f)

    /** One stepper ±1 tap, for a `SELECT` with no known option labels (D3 §3.2). */
    fun onStepperChange(id: ParameterId, value: Int) = onDiscreteChange(id, value.toFloat())

    /** One model-selector chip tap (D3 §2.3) — an ordinary immediate write, same as any other discrete change. */
    fun onModelSelect(id: ParameterId, modelIndex: Int) = onDiscreteChange(id, modelIndex.toFloat())

    /** Opens the numeric-entry sheet (D3 §3.1) for [id] — must be a `RANGE` parameter. */
    fun onNumericEntryOpen(id: ParameterId) {
        _numericEntryTargetId.value = id
    }

    /** Cancel, scrim-tap, or swipe-down on the sheet — discards with no write (D3 §3.1). */
    fun onNumericEntryDismiss() {
        _numericEntryTargetId.value = null
    }

    /**
     * `Set` on the numeric-entry sheet. [value] is already clamped by the sheet itself (D3 §3.1),
     * but this re-clamps unconditionally via [onDiscreteChange] regardless — this path, like
     * every other write path in this class, can never emit an out-of-range value no matter what
     * a caller passes in.
     */
    fun onNumericEntrySet(value: Float) {
        val id = _numericEntryTargetId.value ?: return
        onDiscreteChange(id, value)
        _numericEntryTargetId.value = null
    }

    /** Expands/collapses one full-tier accordion category (D3 §1.3: starts collapsed). */
    fun onCategoryToggle(title: String) {
        _expandedCategories.update { if (title in it) it - title else it + title }
    }

    /** `Got it` on the first-destructive-write notice (D3 §5.2) — presentation only; the underlying gate already fired. */
    fun onFirstWriteWarningDismiss() {
        _firstWriteWarningVisible.value = false
    }

    /** The external-preset-change snackbar (D3 §6.2) has finished displaying. */
    fun onExternalPresetChangeMessageShown() {
        _externalPresetChangeMessage.value = null
    }

    /** Opens the revert confirmation dialog (D3 §5.1). */
    fun onRevertRequested() {
        _revertConfirmVisible.value = true
    }

    /** `Cancel` (the visually-primary button — D3 §5.1's inverted hierarchy) on the revert dialog. */
    fun onRevertCancelled() {
        _revertConfirmVisible.value = false
    }

    /** `Revert Anyway` — issues [TonexController.revertActivePreset] and surfaces any failure per FR11. */
    fun onRevertConfirmed() {
        _revertConfirmVisible.value = false
        scope.launch {
            when (val result = controller.revertActivePreset()) {
                is TonexResult.Success -> {
                    _overrides.value = emptyMap()
                    _revertError.value = null
                }

                is TonexResult.Failure -> _revertError.value = result.error.message
            }
        }
    }

    /** Dismisses a surfaced revert failure. */
    fun onRevertErrorDismissed() {
        _revertError.value = null
    }

    /** Dismisses a surfaced parameter-write failure. */
    fun onWriteErrorDismissed() {
        _writeError.value = null
    }

    /** Stops every in-flight/buffered throttled write. Call from the caller's own teardown (e.g. `onDispose`). */
    fun onCleared() {
        throttler.cancelAll()
    }

    private fun onDiscreteChange(id: ParameterId, rawValue: Float) {
        val spec = ParameterRegistry.byIndex(id.index) ?: return
        if (isWriteUnsupported(spec)) {
            _writeError.value = WriteError(id, UNSUPPORTED_WRITE_MESSAGE)
            return
        }
        // Effective max (issue #80): the write-clamp must track the same widened ceiling
        // DefaultTonexController's own setParameter validation uses, or a value the UI's widened
        // stepper range permits (buildRow) could never actually reach the pedal — clamped back
        // down to the static max right here, one step before the write.
        val clamped = rawValue.coerceIn(spec.min, effectiveBounds.effectiveMax(id))
        _overrides.update { it + (id to clamped) }
        scope.launch {
            val result = controller.setParameter(id, clamped)
            handleWriteResult(id, clamped, result)
        }
    }

    /**
     * True for the 6 `GLOBAL`-scope parameters (everything except `MASTER_VOLUME`, wire indices
     * 110-115: `BPM`, `INPUT_TRIM`, `CABSIM_BYPASS`, `TEMPO_SOURCE`, `TUNING_REFERENCE`, `BYPASS`)
     * that [dev.tonexotg.protocol.connection.DefaultTonexController.writeParameterLocked]
     * *deterministically* rejects — `:protocol` has no write path for them yet (upstream patches
     * the full state blob at offsets `StateBlobOffsets` deliberately does not model). Mirrors that
     * function's own routing condition (`scope == PRESET`, else `enumName == "MASTER_VOLUME"`,
     * else reject) so this never drifts out of sync with it.
     *
     * Checked before ever calling [TonexController.setParameter] for these ids: since the
     * rejection is deterministic, there is nothing to gain from a doomed round trip, and skipping
     * it also means the user never sees `:protocol`'s raw internal `ProtocolStateViolation` prose
     * (`"$enumName is a global parameter other than master volume; :protocol has no write path
     * for it..."`) — [UNSUPPORTED_WRITE_MESSAGE] is shown instead.
     */
    private fun isWriteUnsupported(spec: ParameterSpec): Boolean =
        spec.scope == ParameterScope.GLOBAL && spec.enumName != "MASTER_VOLUME"

    /**
     * Shared by both write paths ([throttler]'s `onResult` and [onDiscreteChange]'s own call):
     * whether [id]'s write just succeeded or failed, its optimistic override is no longer needed.
     * On success [TonexController.parameterValues] now carries this exact value, so the override
     * would be redundant; on failure, falling back to the controller's own last-known value is
     * this project's "fail fast and loud" philosophy applied here — a local override must never
     * keep *pretending* a rejected write actually landed on the pedal. A failure is also surfaced
     * to the user via [_writeError], the same pattern [onRevertConfirmed] uses for
     * [TonexController.revertActivePreset] failures — silently reverting the control with no
     * explanation is exactly the "responds visually but doesn't stick, with zero feedback" bug
     * this exists to prevent.
     *
     * [_writeError] is tagged with [id] so a later *success* only clears an error that was for
     * this same parameter — e.g. a throttler-conflated write to Gain that fails, followed by
     * Gain's own next conflated write succeeding, must clear the dialog; a concurrent, unrelated
     * failure on Bass must not be wiped out by Gain's success just because they share one error
     * slot. (Two different parameters failing at the same time still only surfaces the more recent
     * one's message — an accepted single-dialog-at-a-time limitation, not a per-id queue.)
     */
    private fun handleWriteResult(id: ParameterId, @Suppress("UNUSED_PARAMETER") value: Float, result: TonexResult<Unit>) {
        _overrides.update { it - id }
        when (result) {
            is TonexResult.Success -> _writeError.update { current -> if (current?.id == id) null else current }
            is TonexResult.Failure -> _writeError.value = WriteError(id, result.error.message)
        }
    }

    private companion object {
        const val UNSUPPORTED_WRITE_MESSAGE = "This parameter isn't supported yet."
    }

    private fun buildUiState(controllerSnapshot: ControllerSnapshot, localEditState: LocalEditState, dialogState: DialogState): ParameterEditorUiState {
        fun effectiveValue(id: ParameterId): Float =
            localEditState.overrides[id]
                ?: controllerSnapshot.parameterValues[id]
                ?: (ParameterRegistry.byIndex(id.index)?.default ?: 0f)

        val quickTier = ParameterCatalog.quickTier.map { item -> buildQuickTierCard(item, ::effectiveValue) }
        val masterVolume = buildRangeRow(ParameterCatalog.masterVolumeId, effectiveValue(ParameterCatalog.masterVolumeId))
        val categories = ParameterCatalog.categories.map { buildCategory(it, ::effectiveValue, effectiveBounds) }
        val presetName = controllerSnapshot.activePreset
            ?.let { idx -> controllerSnapshot.presets.firstOrNull { it.index == idx }?.pedalName }
            .orEmpty()
        val numericEntryTarget = localEditState.numericEntryTargetId?.let { id -> buildRangeRow(id, effectiveValue(id)) }

        return ParameterEditorUiState(
            connectionState = controllerSnapshot.connectionState,
            presetName = presetName,
            presetIndex = controllerSnapshot.activePreset,
            quickTier = quickTier,
            masterVolume = masterVolume,
            categories = categories,
            expandedCategories = localEditState.expandedCategories,
            numericEntryTarget = numericEntryTarget,
            firstWriteWarningVisible = dialogState.firstWriteWarningVisible,
            revertConfirmVisible = dialogState.revertConfirmVisible,
            revertError = dialogState.revertError,
            writeError = dialogState.writeError,
            externalPresetChangeMessage = dialogState.externalPresetChangeMessage,
        )
    }

    private fun buildQuickTierCard(item: ParameterCatalog.QuickTierItem, effectiveValue: (ParameterId) -> Float): QuickTierCardUiState =
        when (item) {
            is ParameterCatalog.QuickTierItem.Fixed ->
                QuickTierCardUiState(row = buildRangeRow(item.id, effectiveValue(item.id), labelOverride = item.label))

            is ParameterCatalog.QuickTierItem.Resolved -> {
                val selectorValue = effectiveValue(item.bank.selectorId)
                val selectedIndex = selectorValue.toInt().coerceIn(0, item.bank.models.size - 1)
                val model = item.bank.modelFor(selectedIndex)
                val resolvedId = item.resolve(selectorValue)
                val resolvedSpec = ParameterRegistry.byIndex(resolvedId.index) ?: error("No ParameterSpec for $resolvedId")
                val caption = "→ resolves to ${resolvedSpec.name} · ${model.label} model is active"
                QuickTierCardUiState(
                    row = buildRangeRow(resolvedId, effectiveValue(resolvedId), labelOverride = item.label),
                    resolvedCaption = caption,
                )
            }
        }

    private fun buildCategory(category: ParameterCatalog.Category, effectiveValue: (ParameterId) -> Float, effectiveBounds: EffectiveParameterBounds): CategoryUiState {
        val bank = category.modelBank
        if (bank == null) {
            return CategoryUiState.Flat(
                title = category.title,
                rows = category.flatIds.map { buildRow(it, effectiveValue(it), effectiveBounds) },
            )
        }

        val selectorSpec = ParameterRegistry.byIndex(bank.selectorId.index) ?: error("No ParameterSpec for ${bank.selectorId}")
        val selectorValue = effectiveValue(bank.selectorId)
        val selectedIndex = selectorValue.toInt().coerceIn(0, bank.models.size - 1)

        val alwaysOnRows = category.alwaysOnIds
            .filter { it != bank.selectorId } // the selector renders as its own chip row below, not a generic row
            .map { buildRow(it, effectiveValue(it), effectiveBounds) }

        val selector = ModelSelectorUiState(
            id = bank.selectorId,
            label = friendlyLabel(selectorSpec.enumName),
            options = bank.models.map { it.index },
            selectedIndex = selectedIndex,
            optionLabel = { idx -> bank.modelFor(idx).label },
        )

        // D3 §2.1: only the selected model's rows exist here at all — nothing else is grayed,
        // collapsed, or otherwise present-but-hidden for the other models.
        val modelRows = bank.modelFor(selectedIndex).parameterIds.map { buildRow(it, effectiveValue(it), effectiveBounds) }

        return CategoryUiState.Banked(category.title, alwaysOnRows, selector, modelRows)
    }

    private data class ControllerSnapshot(
        val connectionState: ConnectionState,
        val activePreset: PresetIndex?,
        val presets: List<PresetInfo>,
        val parameterValues: Map<ParameterId, Float>,
    )

    private data class LocalEditState(
        val overrides: Map<ParameterId, Float>,
        val expandedCategories: Set<String>,
        val numericEntryTargetId: ParameterId?,
    )

    private data class DialogState(
        val firstWriteWarningVisible: Boolean,
        val revertConfirmVisible: Boolean,
        val revertError: String?,
        val writeError: String?,
        val externalPresetChangeMessage: String?,
    )
}

private fun buildRow(id: ParameterId, rawValue: Float, effectiveBounds: EffectiveParameterBounds): ParameterRow {
    val spec = ParameterRegistry.byIndex(id.index) ?: error("No ParameterSpec for $id")
    return when (spec.type) {
        ParameterType.RANGE -> buildRangeRow(id, rawValue)

        ParameterType.SWITCH -> ParameterRow.Switch(
            id = id,
            label = friendlyLabel(spec.enumName),
            abbreviation = abbreviationFor(spec, id),
            checked = rawValue >= 0.5f,
        )

        // Every SELECT reaching this function is one of D3 §3.2's *unlabeled* selectors — the 4
        // known-label model selectors are always filtered out before this is called and rendered
        // as a ModelSelectorUiState chip row instead (see buildCategory).
        //
        // ## No coercion of the displayed value (issue #80 bug fix)
        // Previously `value = rawValue.toInt().coerceIn(range.first, range.last)` silently
        // falsified the displayed value for any genuinely-read out-of-range value (e.g. a real
        // VIR_CABINET_MODEL read of 42 displayed as a clamped 38) -- the UI misreported real pedal
        // state instead of surfacing the discrepancy. `value` below is always the raw read,
        // unmodified; `range` widens (in either direction, never narrows below the static min/max)
        // just enough to keep the Stepper widget internally consistent with whatever value it is
        // asked to display -- for the three self-widening allowlisted parameters this is normally
        // a no-op (their `effectiveBounds.effectiveMax` already covers the observed value, since
        // the read that produced [rawValue] is what widened it in the first place -- see
        // DefaultTonexController.applyCapturedValues); for the other ~106 preset parameters, an
        // out-of-range read now visibly shows as an anomalous stepper value instead of a falsely
        // clamped "normal-looking" one -- the "fail loud" philosophy applied to display code, which
        // has no typed-error channel of its own to surface through.
        ParameterType.SELECT -> {
            val rawInt = rawValue.toInt()
            val effectiveMax = effectiveBounds.effectiveMax(id)
            val range = minOf(spec.min.toInt(), rawInt)..maxOf(effectiveMax.toInt(), rawInt)
            ParameterRow.Stepper(
                id = id,
                label = friendlyLabel(spec.enumName),
                abbreviation = abbreviationFor(spec, id),
                value = rawInt,
                range = range,
            )
        }
    }
}

private fun buildRangeRow(id: ParameterId, rawValue: Float, labelOverride: String? = null): ParameterRow.Range {
    val spec = ParameterRegistry.byIndex(id.index) ?: error("No ParameterSpec for $id")
    val value = rawValue.coerceIn(spec.min, spec.max)
    return ParameterRow.Range(
        id = id,
        label = labelOverride ?: friendlyLabel(spec.enumName),
        abbreviation = abbreviationFor(spec, id),
        value = value,
        range = spec.min..spec.max,
        valueText = formatValueText(value, spec),
        decimalPlaces = decimalPlacesFor(spec),
        unit = spec.unit,
    )
}

private fun abbreviationFor(spec: ParameterSpec, id: ParameterId): String = "${spec.name} · index ${id.index}"

/** `"NOISE_GATE_THRESHOLD"` → `"Noise Gate Threshold"` — a generic, no-curation-needed friendly label for full-tier rows. */
private fun friendlyLabel(enumName: String): String =
    enumName.lowercase(Locale.US).split('_').joinToString(" ") { word ->
        word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
    }
