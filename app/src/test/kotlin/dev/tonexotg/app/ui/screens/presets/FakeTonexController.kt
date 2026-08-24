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

    /**
     * Every (slot, preset) pair [assignPresetToSlot] was called with, in order — for S85 part 2b
     * assign-to-slot assertions.
     */
    val assignPresetToSlotCalls: MutableList<Pair<PresetSlot, PresetIndex>> = mutableListOf()

    /** What [assignPresetToSlot] returns the next time it's called; defaults to success. */
    var nextAssignPresetToSlotResult: TonexResult<Unit> = TonexResult.Success(Unit)

    /** Every (id, value) pair [setParameter] was called with, in order — issue #83's home-screen global-parameter write tests' oracle. */
    val setParameterCalls: MutableList<Pair<ParameterId, Float>> = mutableListOf()

    /** What [setParameter] returns the next time it's called; defaults to success. */
    var nextSetParameterResult: TonexResult<Unit> = TonexResult.Success(Unit)

    /** Test helper: sets [id]'s live value directly, as if it had just been read from the pedal (issue #83). */
    fun seedParameterValue(id: ParameterId, value: Float) {
        _parameterValues.value = _parameterValues.value + (id to value)
    }

    /** Directly pushes a new [ConnectionState], simulating what the real state machine would do. */
    fun setConnectionState(state: ConnectionState) {
        _connectionState.value = state
    }

    /** Directly pushes the harvested preset list, simulating S9's post-handshake harvest. */
    fun setPresets(presets: List<PresetInfo>) {
        _presets.value = presets
    }

    /**
     * Directly pushes new footswitch slot assignments, simulating a pedal-confirmed state push
     * (S85 part 2a) -- [disconnect] deliberately does *not* clear this, unlike [_presets] and
     * [_activePreset], so tests can exercise "controller holds stale slot data while not live."
     */
    fun setSlotAssignments(assignments: Map<PresetSlot, PresetIndex>) {
        _slotAssignments.value = assignments
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

    override suspend fun setParameter(id: ParameterId, value: Float): TonexResult<Unit> {
        setParameterCalls.add(id to value)
        val result = nextSetParameterResult
        nextSetParameterResult = TonexResult.Success(Unit)
        if (result is TonexResult.Success) {
            _parameterValues.value = _parameterValues.value + (id to value)
        }
        return result
    }

    override suspend fun revertActivePreset(): TonexResult<Unit> = TonexResult.Success(Unit)

    override suspend fun restoreFootswitches(): TonexResult<Unit> = TonexResult.Success(Unit)

    /**
     * Deliberately does **not** touch [_slotAssignments] or [_activePreset] on success — the real
     * [TonexController.assignPresetToSlot] is push-driven only (see its kdoc), and a fake that
     * "optimistically" updated state here would make S85 part 2b's no-optimistic-update view-model
     * test vacuous: it couldn't tell "the view model updated state itself" apart from "the fake
     * pushed it." Tests that need the post-assignment state must call [setSlotAssignments]/
     * [setActivePreset] explicitly, exactly as they would to simulate any other pedal-confirmed
     * push.
     */
    override suspend fun assignPresetToSlot(slot: PresetSlot, preset: PresetIndex): TonexResult<Unit> {
        assignPresetToSlotCalls.add(slot to preset)
        return nextAssignPresetToSlotResult
    }
}

/**
 * Builds 20 [PresetInfo]s named `"PRESET 01"..."PRESET 20"` — a realistic-shaped stand-in for
 * what S9's post-handshake harvest actually produces, for tests/previews that don't care about
 * specific names.
 */
fun fakePresetInfoList(): List<PresetInfo> = (0..19).map { i ->
    PresetInfo(index = PresetIndex(i), pedalName = "PRESET %02d".format(i + 1))
}
