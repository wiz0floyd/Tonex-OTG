package dev.tonexotg.app.session

import dev.tonexotg.app.data.alias.PresetAliasStore
import dev.tonexotg.app.usb.FakeUsbIoPort
import dev.tonexotg.app.usb.TonexUsbTransport
import dev.tonexotg.app.usb.connection.UsbConnectionState
import dev.tonexotg.protocol.ConnectionState
import dev.tonexotg.protocol.ParameterId
import dev.tonexotg.protocol.PresetIndex
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-JVM coverage for [TonexSessionHolder]'s decision table (doc
 * `docs/architecture/s23-ui-wiring.md` §3.3/§4). Drives fake [StateFlow]s for the USB connection
 * state and [dev.tonexotg.app.usb.connection.UsbConnectionService.foregroundActive] against a
 * fake [TonexController] -- no Robolectric, no real Android service, no DataStore -- via
 * [TonexSessionHolder]'s `internal` test constructor.
 */
class TonexSessionHolderTest {

    private fun buildHolder(
        usbState: MutableStateFlow<UsbConnectionState>,
        foregroundActive: MutableStateFlow<Boolean>,
        controller: FakeTonexController,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
        scope: kotlinx.coroutines.CoroutineScope,
    ): TonexSessionHolder = TonexSessionHolder(
        usbState = usbState,
        foregroundActive = foregroundActive,
        controller = controller,
        aliasStore = FakePresetAliasStore(),
        scope = scope,
    )

    @Test
    fun connectedAndForegroundActive_connectsTheController() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val usbState = MutableStateFlow<UsbConnectionState>(UsbConnectionState.Disconnected)
        val foregroundActive = MutableStateFlow(false)
        val controller = FakeTonexController()
        val scope = kotlinx.coroutines.CoroutineScope(dispatcher)
        buildHolder(usbState, foregroundActive, controller, dispatcher, scope)
        advanceUntilIdle()

        foregroundActive.value = true
        usbState.value = UsbConnectionState.Connected(1, "ToneX One", fakeTransport())
        advanceUntilIdle()

        assertEquals(1, controller.connectCallCount)
        scope.cancel()
    }

    @Test
    fun connectedButForegroundInactive_setsBlockedReasonAndDoesNotConnect() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val usbState = MutableStateFlow<UsbConnectionState>(UsbConnectionState.Disconnected)
        val foregroundActive = MutableStateFlow(false)
        val controller = FakeTonexController()
        val scope = kotlinx.coroutines.CoroutineScope(dispatcher)
        val holder = buildHolder(usbState, foregroundActive, controller, dispatcher, scope)
        advanceUntilIdle()

        usbState.value = UsbConnectionState.Connected(1, "ToneX One", fakeTransport())
        advanceUntilIdle()

        assertEquals(0, controller.connectCallCount)
        assertEquals(true, holder.blockedReason.value != null)
        scope.cancel()
    }

    @Test
    fun disconnected_disconnectsTheController() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val usbState = MutableStateFlow<UsbConnectionState>(UsbConnectionState.Disconnected)
        val foregroundActive = MutableStateFlow(true)
        val controller = FakeTonexController()
        val scope = kotlinx.coroutines.CoroutineScope(dispatcher)
        val holder = buildHolder(usbState, foregroundActive, controller, dispatcher, scope)
        advanceUntilIdle()

        usbState.value = UsbConnectionState.Connected(1, "ToneX One", fakeTransport())
        advanceUntilIdle()
        assertEquals(1, controller.connectCallCount)

        val disconnectCallsBeforeDetach = controller.disconnectCallCount
        usbState.value = UsbConnectionState.Disconnected
        advanceUntilIdle()

        assertEquals(disconnectCallsBeforeDetach + 1, controller.disconnectCallCount)
        assertNull(holder.blockedReason.value)
        scope.cancel()
    }

    @Test
    fun alreadyReady_doesNotReconnectOnASecondConnectedEmission() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val usbState = MutableStateFlow<UsbConnectionState>(UsbConnectionState.Disconnected)
        val foregroundActive = MutableStateFlow(true)
        val controller = FakeTonexController()
        val scope = kotlinx.coroutines.CoroutineScope(dispatcher)
        buildHolder(usbState, foregroundActive, controller, dispatcher, scope)
        advanceUntilIdle()

        usbState.value = UsbConnectionState.Connected(1, "ToneX One", fakeTransport())
        advanceUntilIdle()
        assertEquals(1, controller.connectCallCount)
        controller.setConnectionState(ConnectionState.Ready)

        // A second Connected emission for the same attachment (e.g. foregroundActive toggling
        // false-then-true again with the USB state unchanged) must not re-enter connect() while
        // already Ready.
        foregroundActive.value = false
        advanceUntilIdle()
        foregroundActive.value = true
        advanceUntilIdle()

        assertEquals(1, controller.connectCallCount)
        scope.cancel()
    }
}

/**
 * No mocking library in this project's `:app` test deps -- same approach as
 * `UsbConnectionDecisionsTest`: [TonexUsbTransport]'s `internal constructor` over a fake
 * [dev.tonexotg.app.usb.UsbIoPort] is the seam this codebase already uses instead.
 */
private fun fakeTransport(): TonexUsbTransport =
    TonexUsbTransport(FakeUsbIoPort(), watchdogTimeoutMillis = 30_000L, watchdogPollIntervalMillis = 5_000L)

private class FakePresetAliasStore : PresetAliasStore {
    override fun alias(preset: PresetIndex): Flow<String?> = emptyFlow()
    override suspend fun setAlias(preset: PresetIndex, alias: String) = Unit
    override suspend fun clearAlias(preset: PresetIndex) = Unit
}

private class FakeTonexController(
    initialState: ConnectionState = ConnectionState.Idle,
) : TonexController {

    private val _connectionState = MutableStateFlow(initialState)
    override val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _activePreset = MutableStateFlow<PresetIndex?>(null)
    override val activePreset: StateFlow<PresetIndex?> = _activePreset

    private val _presets = MutableStateFlow<List<dev.tonexotg.protocol.PresetInfo>>(emptyList())
    override val presets: StateFlow<List<dev.tonexotg.protocol.PresetInfo>> = _presets

    private val _parameterValues = MutableStateFlow<Map<ParameterId, Float>>(emptyMap())
    override val parameterValues: StateFlow<Map<ParameterId, Float>> = _parameterValues

    private val _events = MutableSharedFlow<TonexEvent>()
    override val events: SharedFlow<TonexEvent> = _events

    var connectCallCount: Int = 0
        private set
    var disconnectCallCount: Int = 0
        private set

    fun setConnectionState(state: ConnectionState) {
        _connectionState.value = state
    }

    override suspend fun connect(transport: TonexTransport): TonexResult<Unit> {
        connectCallCount++
        _connectionState.value = ConnectionState.Ready
        return TonexResult.Success(Unit)
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
