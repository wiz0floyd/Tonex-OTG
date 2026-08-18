package dev.tonexotg.app.ui.screens.parameters

import dev.tonexotg.protocol.ConnectionState
import dev.tonexotg.protocol.ParameterId
import dev.tonexotg.protocol.PresetIndex
import dev.tonexotg.protocol.PresetInfo
import dev.tonexotg.protocol.TonexController
import dev.tonexotg.protocol.TonexError
import dev.tonexotg.protocol.TonexEvent
import dev.tonexotg.protocol.TonexResult
import dev.tonexotg.protocol.TonexTransport
import dev.tonexotg.protocol.params.ParameterRegistry
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A minimal, hand-written [TonexController] test double for `:app`'s own tests — deliberately
 * not a dependency on `:protocol`'s test-only `FakeTonexTransport` (per issue #22's scope note:
 * `:app` tests exercise the [TonexController] interface abstractly, since there is no real
 * Android USB transport yet, S11 being hardware-blocked).
 *
 * Every [TonexController] flow is driven directly by test code via the public `Mutable*Flow`
 * properties/setters below, rather than by actually running a connection handshake — this is a
 * view-model-level test double, not a protocol-level one.
 */
class FakeTonexController : TonexController {

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Ready)
    override val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _activePreset = MutableStateFlow<PresetIndex?>(PresetIndex(0))
    override val activePreset: StateFlow<PresetIndex?> = _activePreset

    private val _presets = MutableStateFlow<List<PresetInfo>>(emptyList())
    override val presets: StateFlow<List<PresetInfo>> = _presets

    private val _parameterValues = MutableStateFlow<Map<ParameterId, Float>>(emptyMap())
    override val parameterValues: StateFlow<Map<ParameterId, Float>> = _parameterValues

    private val _events = MutableSharedFlow<TonexEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<TonexEvent> = _events

    /** Every (id, value) pair a caller asked to write, in call order — the throttling tests' oracle. */
    val setParameterCalls = mutableListOf<Pair<ParameterId, Float>>()

    /** If non-null, the next [setParameter] call(s) return this instead of the default success. */
    var nextSetParameterResult: TonexResult<Unit>? = null

    /** How many times [revertActivePreset] was called. */
    var revertCallCount = 0
        private set

    /** What [revertActivePreset] returns; defaults to success. */
    var revertResult: TonexResult<Unit> = TonexResult.Success(Unit)

    override suspend fun connect(transport: TonexTransport): TonexResult<Unit> = TonexResult.Success(Unit)

    override suspend fun disconnect() {
        _connectionState.value = ConnectionState.Idle
    }

    override suspend fun selectPreset(index: PresetIndex): TonexResult<Unit> {
        _activePreset.value = index
        return TonexResult.Success(Unit)
    }

    override suspend fun setParameter(id: ParameterId, value: Float): TonexResult<Unit> {
        setParameterCalls.add(id to value)
        val spec = ParameterRegistry.byIndex(id.index)
        if (spec != null && (value < spec.min || value > spec.max)) {
            return TonexResult.Failure(TonexError.ParameterValueOutOfRange(id, value, spec.min, spec.max))
        }
        val result = nextSetParameterResult ?: TonexResult.Success(Unit)
        nextSetParameterResult = null
        if (result is TonexResult.Success) {
            _parameterValues.value = _parameterValues.value + (id to value)
        }
        return result
    }

    override suspend fun revertActivePreset(): TonexResult<Unit> {
        revertCallCount++
        return revertResult
    }

    /** Test helper: sets [id]'s live value directly, as if it had just been read from the pedal. */
    fun seedParameterValue(id: ParameterId, value: Float) {
        _parameterValues.value = _parameterValues.value + (id to value)
    }

    /** Test helper: emits [event] on [events]. */
    suspend fun emitEvent(event: TonexEvent) {
        _events.emit(event)
    }

    /** Test helper: moves the active preset without going through [selectPreset] (simulates FR6). */
    fun setActivePresetExternally(index: PresetIndex) {
        _activePreset.value = index
    }
}
