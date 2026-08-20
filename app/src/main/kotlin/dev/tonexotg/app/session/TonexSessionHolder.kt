package dev.tonexotg.app.session

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dev.tonexotg.app.data.alias.DataStorePresetAliasStore
import dev.tonexotg.app.data.alias.PresetAliasStore
import dev.tonexotg.app.data.alias.presetAliasDataStore
import dev.tonexotg.app.usb.connection.UsbConnectionManager
import dev.tonexotg.app.usb.connection.UsbConnectionService
import dev.tonexotg.app.usb.connection.UsbConnectionState
import dev.tonexotg.protocol.ConnectionState
import dev.tonexotg.protocol.TonexController
import dev.tonexotg.protocol.connection.DefaultTonexController
import dev.tonexotg.protocol.message.FirmwareCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * App-scoped singleton owning the one [TonexController] instance for the life of the process
 * (S23, issue #74; `docs/architecture/s23-ui-wiring.md` §3). Same shape as
 * [UsbConnectionManager.getInstance]: a `@Volatile` instance behind [getInstance], backed by its
 * own long-lived [CoroutineScope].
 *
 * ## Why one controller, never recreated
 * See the architecture doc §3.1 in full. In short: every screen and state holder already models
 * "not connected" through [TonexController.connectionState] rather than a nullable controller,
 * `remember(controller)` call sites key on controller identity (a fresh instance per attachment
 * would silently discard UI state on every replug), and [DefaultTonexController] is documented as
 * safe to reuse across connect/disconnect cycles -- `teardown()` resets everything that needs
 * resetting, including the snapshot store D3 §5.2's first-write-warning gate depends on.
 *
 * ## The foreground-service gate (doc §3.4) -- the load-bearing part of this class
 * This holder never connects the controller off [UsbConnectionManager.state] alone. It requires
 * *both* [UsbConnectionState.Connected] *and* [UsbConnectionService.foregroundActive] before
 * calling [TonexController.connect] -- see [observe]. A [UsbConnectionState.Connected] with no
 * foreground service running surfaces as [blockedReason] instead of opening a protocol session:
 * "fail loud," per the house philosophy, rather than silently bypassing issue #18's product
 * decision that a pedal session never runs unprotected.
 *
 * ## Recovering from a controller-side failure, and FGS loss mid-session (PR #75 review, blocker
 * B + open question A)
 * [observe] also folds [TonexController.connectionState] into its `combine` -- not just
 * [usbState]/[foregroundActive]. Two cases that live *entirely* on the controller side (or on the
 * service side, invisible to [UsbConnectionState]) used to be unrecoverable without a physical
 * unplug/replug or an app kill:
 *
 * 1. **[ConnectionState.Error] while [UsbConnectionState.Connected].** [DefaultTonexController]
 *    already closes its transport on the way into `Error` (see that class's `teardown`/`orFinish`),
 *    but [UsbConnectionManager]'s own `_state` is untouched by that -- it only ever moves off
 *    [UsbConnectionState.Connected] via attach/detach/[UsbConnectionManager.disconnect]. Left
 *    alone, [UsbConnectionDecisions.shouldConnect] sees the *same* `deviceId` still "connected" and
 *    refuses every future attach/reconnect, so tapping Reconnect was a permanent no-op.
 * 2. **[UsbConnectionService.foregroundActive] dropping to `false` while the controller is
 *    already `Ready`.** The old code only set [blockedReason] here and left the controller running
 *    unprotected -- issue #18's FGS/wakelock guarantee silently gone mid-session, with the banner
 *    contradicting the still-live parameter editor.
 *
 * Both are handled the same way: [observe] calls [disconnectUsb] (production:
 * [UsbConnectionManager.disconnect]), not just [TonexController.disconnect]. This is deliberate,
 * not a shortcut -- calling only `controller.disconnect()` would leave [UsbConnectionManager]
 * still reporting [UsbConnectionState.Connected] over an already-closed transport, so the *next*
 * reconnect would call [TonexController.connect] against dead I/O and land right back in `Error`
 * (see PR #75 review for the exact trace). Tearing down through the manager instead makes its
 * `_state` genuinely flip to [UsbConnectionState.Disconnected]; the existing `Disconnected` branch
 * below then calls [TonexController.disconnect] (safe from any state, including `Error` --
 * see that method's own KDoc), resetting the controller to `Idle`. From there,
 * [UsbConnectionDecisions.shouldConnect] correctly returns `true` for the same still-attached
 * `deviceId`, so [requestReconnect]'s existing `connectToAttachedDeviceIfPresent()` call reopens a
 * *fresh* transport -- the normal reattach path, not a bespoke retry.
 *
 * ## `FirmwareCapabilities` (doc §5, escalated and resolved)
 * [CAPABILITIES] ships `supportsSingleParameterWrite = true`. This reflects an already-observed
 * fact, not a new hardware assumption: issue #25's hardware probe log reports "Per-parameter
 * write support: CONFIRMED YES" on the real pedal (wrote `EQ_MID = 10.0`, reconnected, read back
 * `10.0`). `ProbeSession`'s own write tests use the same value (lines ~198, ~289, ~413). See
 * issue #26 for the broader capability-probe discussion. If firmware ever turns out to be too old
 * for this on a *different* unit, the failure mode is a write the pedal ignores -- not a
 * corrupting write; the single-parameter path is the safe path, and the dangerous whole-state
 * path is unaffected either way.
 *
 * ## Test seam
 * The primary constructor is `internal` and takes the four dependencies directly (fake
 * [StateFlow]s, a fake [TonexController], an in-memory [PresetAliasStore]) so
 * `TonexSessionHolderTest` can drive [observe]'s decision table on the plain JVM, no Robolectric
 * or DataStore needed -- the same seam [UsbConnectionManager]'s own `internal constructor` and
 * [UsbConnectionService.connectionManager] already establish in this codebase. [getInstance] is
 * the thin production factory that wires the real four singletons together. This isn't named in
 * the architecture doc's file list; noted as a deliberate, minimal deviation required to satisfy
 * that same doc's own test requirements (§4), not a design change.
 */
class TonexSessionHolder internal constructor(
    private val usbState: StateFlow<UsbConnectionState>,
    private val foregroundActive: StateFlow<Boolean>,
    val controller: TonexController,
    val aliasStore: PresetAliasStore,
    private val scope: CoroutineScope,
    /**
     * Tears down [UsbConnectionManager]'s own state (production:
     * [UsbConnectionManager.disconnect]) -- see class KDoc, "Recovering from a controller-side
     * failure, and FGS loss mid-session." A plain fire-and-forget callback, not the manager type
     * itself, so `TonexSessionHolderTest` can assert on it without constructing a real
     * [UsbConnectionManager] (which needs a `Context`).
     */
    private val disconnectUsb: () -> Unit,
) {

    private val mutex = Mutex()

    private val _blockedReason = MutableStateFlow<String?>(null)
    val blockedReason: StateFlow<String?> = _blockedReason.asStateFlow()

    init {
        scope.launch { observe() }
    }

    private suspend fun observe() {
        combine(
            usbState,
            foregroundActive,
            controller.connectionState,
        ) { usb, fgs, controllerState -> Triple(usb, fgs, controllerState) }.collect { (usb, fgs, controllerState) ->
            mutex.withLock {
                when (usb) {
                    is UsbConnectionState.Connected -> when {
                        controllerState is ConnectionState.Error -> {
                            // See class KDoc, "Recovering from a controller-side failure": the
                            // controller already closed its own transport on the way into Error;
                            // tear down the *manager's* state too so a future attach/reconnect for
                            // this same deviceId is not permanently refused by
                            // UsbConnectionDecisions.shouldConnect.
                            _blockedReason.value = null
                            disconnectUsb()
                        }

                        !fgs && controllerState !is ConnectionState.Idle -> {
                            // See class KDoc, "FGS loss mid-session": the controller has actually
                            // touched this transport (it's Ready, or mid-handshake) -- do not
                            // merely note the reason and leave it running unprotected. Tear down
                            // through the manager here too, for the same reason as the Error case
                            // above: leaving _state at Connected over a transport the controller
                            // may still be using/about to close would wedge the next reconnect the
                            // same way.
                            _blockedReason.value = BLOCKED_NO_FOREGROUND_SERVICE
                            disconnectUsb()
                        }

                        !fgs -> {
                            // The controller is still Idle -- it never touched this transport, so
                            // there is nothing to unwind. Just block; once the service (re)starts
                            // and this flips fgs back to true, the `else` branch below connects
                            // normally.
                            _blockedReason.value = BLOCKED_NO_FOREGROUND_SERVICE
                        }

                        else -> {
                            _blockedReason.value = null
                            if (controllerState is ConnectionState.Idle) {
                                controller.connect(usb.transport)
                            }
                        }
                    }

                    is UsbConnectionState.Connecting -> {
                        _blockedReason.value = null
                    }

                    is UsbConnectionState.Disconnected -> {
                        _blockedReason.value = null
                        controller.disconnect()
                    }

                    is UsbConnectionState.Failed -> {
                        _blockedReason.value = usb.reason
                        controller.disconnect()
                    }
                }
            }
        }
    }

    /**
     * Doc §3.5: "reconnect" never calls [TonexController.connect] directly -- that would bypass
     * the foreground-service gate this class exists to enforce. It restarts the foreground
     * service; the service's own `onStartCommand` re-attempts the connection (or, once it's
     * running, this holder's [observe] collector picks the now-`Connected` state up itself once
     * [UsbConnectionService.foregroundActive] flips back to `true`).
     */
    fun requestReconnect(context: Context) {
        ContextCompat.startForegroundService(
            context.applicationContext,
            Intent(context.applicationContext, UsbConnectionService::class.java),
        )
    }

    companion object {
        private const val BLOCKED_NO_FOREGROUND_SERVICE =
            "Pedal attached, but the background service isn't running — reconnect to continue."

        /**
         * See class KDoc, "FirmwareCapabilities" -- ship `true`, per issue #25's confirmed
         * single-parameter-write probe result and issue #26's discussion. Named constant, not an
         * inline literal, precisely so this decision stays greppable and citable from a review.
         */
        val CAPABILITIES: FirmwareCapabilities = FirmwareCapabilities(supportsSingleParameterWrite = true)

        @Volatile
        private var instance: TonexSessionHolder? = null

        fun getInstance(context: Context): TonexSessionHolder =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        private fun build(appContext: Context): TonexSessionHolder {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val usbConnectionManager = UsbConnectionManager.getInstance(appContext)
            return TonexSessionHolder(
                usbState = usbConnectionManager.state,
                foregroundActive = UsbConnectionService.foregroundActive,
                controller = DefaultTonexController(scope = scope, capabilities = CAPABILITIES),
                aliasStore = DataStorePresetAliasStore(appContext.presetAliasDataStore),
                scope = scope,
                disconnectUsb = usbConnectionManager::disconnect,
            )
        }
    }
}
