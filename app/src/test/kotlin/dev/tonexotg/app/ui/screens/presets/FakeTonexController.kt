package dev.tonexotg.app.ui.screens.presets

import dev.tonexotg.protocol.ConnectionState
import dev.tonexotg.protocol.ParameterId
import dev.tonexotg.protocol.PresetIndex
import dev.tonexotg.protocol.PresetInfo
import dev.tonexotg.protocol.PresetSlot
import dev.tonexotg.protocol.TonexController
import dev.tonexotg.protocol.TonexError
import dev.tonexotg.protocol.TonexEvent
import dev.tonexotg.protocol.TonexResult
import dev.tonexotg.protocol.TonexTransport
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A minimal [TonexController] test double for S16's [PresetListViewModel]/[PresetListScreen]
 * tests only.
 *
 * Deliberately written from scratch against the public [TonexController] interface, per this
 * story's own scoping note — a near-identical fake already exists at
 * `dev.tonexotg.app.ui.screens.connection.FakeTonexController` (S18), but that one is explicitly
 * scoped to connection-status tests and doesn't expose the preset/active-preset mutation hooks
 * this story's tests need (in particular, [emitExternalPresetChange], which is what stands in for
 * a real footswitch press for the FR6 assertion). Kept in this package rather than shared, same
 * as that one is kept in its own.
 */
class FakeTonexController(
    initialState: ConnectionState = ConnectionState.Idle,
) : TonexController {

    private val _connectionState = MutableStateFlow(initialState)
    override val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _activePreset = MutableStateFlow<PresetIndex?>(null)
    override val activePreset: StateFlow<PresetIndex?> = _activePreset

    private val _presets = MutableStateFlow<List<PresetInfo>>(emptyList())
    override val presets: StateFlow<List<PresetInfo>> = _presets

    private val _slotAssignments = MutableStateFlow<Map<PresetSlot, PresetIndex>>(emptyMap())
    override val slotAssignments: StateFlow<Map<PresetSlot, PresetIndex>> = _slotAssignments

    private val _parameterValues = MutableStateFlow<Map<ParameterId, Float>>(emptyMap())
    override val parameterValues: StateFlow<Map<ParameterId, Float>> = _parameterValues

    private val _events = MutableSharedFlow<TonexEvent>()
    override val events: SharedFlow<TonexEvent> = _events

    /** Every index [selectPreset] was called with, in order — for tap-to-load assertions. */
    val selectPresetCalls: MutableList<PresetIndex> = mutableListOf()

    /** What [selectPreset] returns the next time it's called; defaults to success. */
    var nextSelectPresetResult: TonexResult<Unit> = TonexResult.Success(Unit)

    /** Directly pushes a new [ConnectionState], simulating what the real state machine would do. */
    fun setConnectionState(state: ConnectionState) {
        _connectionState.value = state
    }

    /** Directly pushes the harvested preset list, simulating S9's post-handshake harvest. */
    fun setPresets(presets: List<PresetInfo>) {
        _presets.value = presets
    }

    /**
     * Directly pushes a new active preset **without** going through [selectPreset] — simulating
     * an app-initiated selection completing.
     */
    fun setActivePreset(index: PresetIndex?) {
        _activePreset.value = index
    }

    /**
     * Simulates a footswitch-initiated preset change (FR6): pushes [newIndex] onto
     * [activePreset] exactly as the real controller would when it observes an external change,
     * and emits [TonexEvent.ExternalPresetChange] on [events] to match. This is the fake's stand-in
     * for a real footswitch press — tests assert that [PresetListViewModel.uiState] picks this up
     * with no call to [selectPreset] in between.
     */
    suspend fun emitExternalPresetChange(newIndex: PresetIndex) {
        _activePreset.value = newIndex
        _events.emit(TonexEvent.ExternalPresetChange(newIndex))
    }

    override suspend fun connect(transport: TonexTransport): TonexResult<Unit> = TonexResult.Success(Unit)

    override suspend fun disconnect() {
        _connectionState.value = ConnectionState.Idle
        _presets.value = emptyList()
        _activePreset.value = null
    }

    override suspend fun selectPreset(index: PresetIndex): TonexResult<Unit> {
        selectPresetCalls.add(index)
        val result = nextSelectPresetResult
        if (result is TonexResult.Success) {
            _activePreset.value = index
        }
        return result
    }

    override suspend fun setParameter(id: ParameterId, value: Float): TonexResult<Unit> = TonexResult.Success(Unit)

    override suspend fun revertActivePreset(): TonexResult<Unit> = TonexResult.Success(Unit)

    override suspend fun restoreFootswitches(): TonexResult<Unit> = TonexResult.Success(Unit)

    override suspend fun assignPresetToSlot(slot: PresetSlot, preset: PresetIndex): TonexResult<Unit> =
        TonexResult.Success(Unit)
}

/**
 * Builds 20 [PresetInfo]s named `"PRESET 01"..."PRESET 20"` — a realistic-shaped stand-in for
 * what S9's post-handshake harvest actually produces, for tests/previews that don't care about
 * specific names.
 */
fun fakePresetInfoList(): List<PresetInfo> = (0..19).map { i ->
    PresetInfo(index = PresetIndex(i), pedalName = "PRESET %02d".format(i + 1))
}
