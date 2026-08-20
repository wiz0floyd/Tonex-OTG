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
        scope: kotlinx.coroutines.CoroutineScope,
        disconnectUsb: FakeDisconnectUsb = FakeDisconnectUsb(usbState),
    ): TonexSessionHolder = TonexSessionHolder(
        usbState = usbState,
        foregroundActive = foregroundActive,
        controller = controller,
        aliasStore = FakePresetAliasStore(),
        scope = scope,
        disconnectUsb = disconnectUsb,
    )

    @Test
    fun connectedAndForegroundActive_connectsTheController() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val usbState = MutableStateFlow<UsbConnectionState>(UsbConnectionState.Disconnected)
        val foregroundActive = MutableStateFlow(false)
        val controller = FakeTonexController()
        val scope = kotlinx.coroutines.CoroutineScope(dispatcher)
        buildHolder(usbState, foregroundActive, controller, scope)
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
        val holder = buildHolder(usbState, foregroundActive, controller, scope)
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
        val holder = buildHolder(usbState, foregroundActive, controller, scope)
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
        val disconnectUsb = FakeDisconnectUsb(usbState)
        buildHolder(usbState, foregroundActive, controller, scope, disconnectUsb)
        advanceUntilIdle()

        usbState.value = UsbConnectionState.Connected(1, "ToneX One", fakeTransport())
        advanceUntilIdle()
        assertEquals(1, controller.connectCallCount)
        controller.setConnectionState(ConnectionState.Ready)

        // A second Connected emission for the same attachment while the controller is already
        // Ready and the FGS gate stays up (e.g. some *other* unrelated flow value briefly
        // recomputing) must not re-enter connect() while already Ready, and must not tear
        // anything down -- there was no gate loss and no controller failure here.
        foregroundActive.value = true // no-op re-assignment; StateFlow conflates, asserting intent
        advanceUntilIdle()

        assertEquals(1, controller.connectCallCount)
        assertEquals(0, disconnectUsb.callCount)
        scope.cancel()
    }

    @Test
    fun reconnectAfterControllerError_reopensOnceUsbStateReflectsDisconnected() = runTest {
        // PR #75 review, blocker B: a controller-side connect failure (handshake timeout, decode
        // error) used to leave UsbConnectionManager's own state at Connected forever, so
        // UsbConnectionDecisions.shouldConnect() refused every later attach/reconnect for the same
        // deviceId and the Reconnect button was a permanent dead end. The fix: TonexSessionHolder
        // must react to ConnectionState.Error by tearing down through disconnectUsb() (the
        // production UsbConnectionManager.disconnect()), not just leave a note in blockedReason.
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val usbState = MutableStateFlow<UsbConnectionState>(UsbConnectionState.Disconnected)
        val foregroundActive = MutableStateFlow(true)
        val controller = FakeTonexController()
        val scope = kotlinx.coroutines.CoroutineScope(dispatcher)
        val disconnectUsb = FakeDisconnectUsb(usbState)
        buildHolder(usbState, foregroundActive, controller, scope, disconnectUsb)
        advanceUntilIdle()

        controller.failNextConnect = true
        usbState.value = UsbConnectionState.Connected(1, "ToneX One", fakeTransport())
        advanceUntilIdle()

        assertEquals(1, controller.connectCallCount)
        // Note: we can't observe the transient ConnectionState.Error itself here -- under
        // UnconfinedTestDispatcher, advanceUntilIdle() runs the whole cascading reaction
        // (Error -> disconnectUsb() -> usbState=Disconnected -> controller.disconnect() -> Idle)
        // to completion before returning control to this test. The assertions below confirm the
        // cascade actually happened, which is the thing that matters for recoverability.
        // The holder must have torn the manager's state down itself -- this is what actually
        // unblocks a future shouldConnect() for the same deviceId.
        assertEquals(1, disconnectUsb.callCount)
        assertEquals(UsbConnectionState.Disconnected, usbState.value)
        // ...and the controller must have followed the manager back down to Idle (the existing
        // Disconnected branch's controller.disconnect() call), not stayed wedged in Error.
        assertEquals(ConnectionState.Idle, controller.connectionState.value)

        // The equivalent of the user tapping Reconnect and the service reopening the still-
        // attached pedal: a fresh Connected emission for the same deviceId.
        usbState.value = UsbConnectionState.Connected(1, "ToneX One", fakeTransport())
        advanceUntilIdle()

        assertEquals(2, controller.connectCallCount)
        assertEquals(ConnectionState.Ready, controller.connectionState.value)
        scope.cancel()
    }

    @Test
    fun foregroundServiceLostWhileReady_tearsDownThroughTheManagerNotJustTheController() = runTest {
        // PR #75 review, open question A: a service death (degradeAfterFailedStartForeground's
        // stopSelf(), or the OS reclaiming the process) mid-session must not leave the manager
        // reporting Connected over a transport the controller is about to close out from under it
        // -- that's exactly the naive fix the review warned against (controller.disconnect() alone
        // would close the transport while UsbConnectionManager._state still says Connected,
        // wedging the *next* reconnect the same way as blocker B).
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val usbState = MutableStateFlow<UsbConnectionState>(UsbConnectionState.Disconnected)
        val foregroundActive = MutableStateFlow(true)
        val controller = FakeTonexController()
        val scope = kotlinx.coroutines.CoroutineScope(dispatcher)
        val disconnectUsb = FakeDisconnectUsb(usbState)
        buildHolder(usbState, foregroundActive, controller, scope, disconnectUsb)
        advanceUntilIdle()

        usbState.value = UsbConnectionState.Connected(1, "ToneX One", fakeTransport())
        advanceUntilIdle()
        assertEquals(1, controller.connectCallCount)
        assertEquals(ConnectionState.Ready, controller.connectionState.value)

        // The service dies mid-session: foregroundActive flips false with the pedal still
        // physically attached (usbState untouched by this -- UsbConnectionState knows nothing
        // about the service's lifecycle).
        foregroundActive.value = false
        advanceUntilIdle()

        assertEquals(1, disconnectUsb.callCount)
        assertEquals(UsbConnectionState.Disconnected, usbState.value)
        assertEquals(1, controller.disconnectCallCount)
        assertEquals(ConnectionState.Idle, controller.connectionState.value)
    }
}

/**
 * Fakes [UsbConnectionManager.disconnect]'s one externally-visible effect (flip [usbState] to
 * [UsbConnectionState.Disconnected]) without constructing a real [UsbConnectionManager], which
 * needs a `Context`. Counts invocations so tests can assert the holder actually called it, per
 * the PR #75 review's "tear down through the manager, not just the controller" requirement.
 */
private class FakeDisconnectUsb(
    private val usbState: MutableStateFlow<UsbConnectionState>,
) : () -> Unit {
    var callCount: Int = 0
        private set

    override fun invoke() {
        callCount++
        usbState.value = UsbConnectionState.Disconnected
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

    /**
     * When `true`, the next [connect] call lands in [ConnectionState.Error] instead of
     * [ConnectionState.Ready] and resets itself back to `false` -- a minimal "fail on next
     * connect" seam (PR #75 review nit) for exercising [TonexSessionHolder]'s controller-side
     * failure recovery without a full suspending/coroutine-controlled fake.
     */
    var failNextConnect: Boolean = false

    fun setConnectionState(state: ConnectionState) {
        _connectionState.value = state
    }

    override suspend fun connect(transport: TonexTransport): TonexResult<Unit> {
        connectCallCount++
        return if (failNextConnect) {
            failNextConnect = false
            val error = TonexError.Timeout(operation = "hello", timeoutMillis = 1_000L)
            _connectionState.value = ConnectionState.Error(error)
            TonexResult.Failure(error)
        } else {
            _connectionState.value = ConnectionState.Ready
            TonexResult.Success(Unit)
        }
    }

    override suspend fun disconnect() {
        // Mirrors DefaultTonexController.disconnect()'s own early-return: "safe to call from any
        // state" is documented as a no-op once already Idle, not an unconditional re-publish. The
        // holder's combine now includes connectionState itself, so without this guard a fake that
        // recounts/republishes on every call would make TonexSessionHolder's Disconnected/Failed
        // branches (which call this unconditionally) loop or over-count.
        if (_connectionState.value is ConnectionState.Idle) return
        disconnectCallCount++
        _connectionState.value = ConnectionState.Idle
    }

    override suspend fun selectPreset(index: PresetIndex): TonexResult<Unit> = TonexResult.Success(Unit)

    override suspend fun setParameter(id: ParameterId, value: Float): TonexResult<Unit> = TonexResult.Success(Unit)

    override suspend fun revertActivePreset(): TonexResult<Unit> = TonexResult.Success(Unit)

    override suspend fun restoreFootswitches(): TonexResult<Unit> = TonexResult.Success(Unit)
}
