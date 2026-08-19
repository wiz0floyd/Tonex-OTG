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
import kotlinx.coroutines.CompletableDeferred
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

    /**
     * Test helper (opt-in, one-shot): arms the *next* [setParameter] call to signal [started] the
     * moment it begins blocking, then suspend until [release] completes — the same
     * in-flight-write pattern [ParameterWriteThrottlerTest] uses directly against the throttler,
     * lifted to the controller so a view-model-level test can deterministically hold one write
     * "in flight" while it exercises what happens to a second, still-buffered value (e.g. D3
     * §6.3's cancellation on an external preset change). Consumed after one use; set
     * [FakeTonexController.nextSetParameterGate] again for another gated call.
     */
    class SetParameterGate {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
    }

    var nextSetParameterGate: SetParameterGate? = null

    override suspend fun connect(transport: TonexTransport): TonexResult<Unit> = TonexResult.Success(Unit)

    override suspend fun disconnect() {
        _connectionState.value = ConnectionState.Idle
    }

    override suspend fun selectPreset(index: PresetIndex): TonexResult<Unit> {
        _activePreset.value = index
        return TonexResult.Success(Unit)
    }

    override suspend fun setParameter(id: ParameterId, value: Float): TonexResult<Unit> {
        // Record the call BEFORE gating: an "in-flight" write, per ParameterWriteThrottler's own
        // KDoc, is one that "already left this process" - the call is already recorded/sent, and
        // only completion (the return) is what's still pending. Gating before recording would
        // instead model a write that hasn't been sent yet, which is a different scenario (and one
        // cancelAll() legitimately IS allowed to drop).
        setParameterCalls.add(id to value)
        nextSetParameterGate?.let { gate ->
            nextSetParameterGate = null
            gate.started.complete(Unit)
            gate.release.await()
        }
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

    override suspend fun restoreFootswitches(): TonexResult<Unit> = TonexResult.Success(Unit)

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

    /** Test helper: seeds [presets] directly, as if just harvested after reaching [ConnectionState.Ready]. */
    fun seedPresets(presets: List<PresetInfo>) {
        _presets.value = presets
    }
}
