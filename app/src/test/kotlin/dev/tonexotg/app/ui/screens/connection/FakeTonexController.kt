package dev.tonexotg.app.ui.screens.connection

import dev.tonexotg.protocol.ConnectionState
import dev.tonexotg.protocol.ParameterId
import dev.tonexotg.protocol.PresetIndex
import dev.tonexotg.protocol.PresetInfo
import dev.tonexotg.protocol.TonexController
import dev.tonexotg.protocol.TonexError
import dev.tonexotg.protocol.TonexEvent
import dev.tonexotg.protocol.TonexResult
import dev.tonexotg.protocol.TonexTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * A minimal [TonexController] test double for `:app`-side connection-status tests only.
 *
 * Deliberately written from scratch against the public [TonexController] interface, per this
 * story's own scoping note — it does not depend on `:protocol`'s test-only `FakeTonexTransport`,
 * which is not a production artifact and isn't visible from `:app` test sources anyway. This
 * fake never touches a real [TonexTransport]; [connect] just records the call and lets the test
 * push whatever [ConnectionState] it wants directly via [setConnectionState].
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

    private val _parameterValues = MutableStateFlow<Map<ParameterId, Float>>(emptyMap())
    override val parameterValues: StateFlow<Map<ParameterId, Float>> = _parameterValues

    private val _events = MutableSharedFlow<TonexEvent>()
    override val events: SharedFlow<TonexEvent> = _events

    /** How many times [connect] was called, and with what — for reconnect-wiring assertions. */
    var connectCallCount: Int = 0
        private set
    var lastConnectTransport: TonexTransport? = null
        private set

    var disconnectCallCount: Int = 0
        private set

    /** What [connect] returns the next time it's called; defaults to success. */
    var nextConnectResult: TonexResult<Unit> = TonexResult.Success(Unit)

    /** Directly pushes a new [ConnectionState], simulating what the real state machine would do. */
    fun setConnectionState(state: ConnectionState) {
        _connectionState.value = state
    }

    override suspend fun connect(transport: TonexTransport): TonexResult<Unit> {
        connectCallCount++
        lastConnectTransport = transport
        return nextConnectResult
    }

    override suspend fun disconnect() {
        disconnectCallCount++
        _connectionState.value = ConnectionState.Idle
    }

    override suspend fun selectPreset(index: PresetIndex): TonexResult<Unit> = TonexResult.Success(Unit)

    override suspend fun setParameter(id: ParameterId, value: Float): TonexResult<Unit> = TonexResult.Success(Unit)

    override suspend fun revertActivePreset(): TonexResult<Unit> = TonexResult.Success(Unit)

    override suspend fun restoreFootswitches(): TonexResult<Unit> = TonexResult.Success(Unit)
}

/** A [TonexTransport] test double that does nothing — only its identity is ever inspected. */
class NoOpTonexTransport : TonexTransport {
    override suspend fun write(bytes: ByteArray): Int = bytes.size
    override fun incoming(): Flow<ByteArray> = emptyFlow()
    override fun close() = Unit
}
