package dev.tonexotg.app.ui.screens.connection

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import dev.tonexotg.protocol.TonexController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Screen-agnostic connection-status state holder, built against [TonexController]'s interface
 * only — per this story's scope, S11's real Android USB transport doesn't exist yet, so this
 * class never constructs a [TonexTransport] itself; it is handed a [transportFactory] that
 * knows how to produce one when a real transport implementation lands.
 *
 * Deliberately a plain class, not an `androidx.lifecycle.ViewModel` subclass: `:app` has no
 * `lifecycle-viewmodel-compose` dependency yet (only `lifecycle-runtime-ktx`), and adding one
 * is out of scope for this story — see [rememberConnectionStatusViewModel] for how a host screen
 * wires this up today via [rememberCoroutineScope], and swaps to a real `ViewModel` later
 * without this class's own API changing.
 *
 * @property controller the connection this instance surfaces status for.
 * @property onReconnectRequested invoked by [reconnect]. S23 (issue #74;
 *   `docs/architecture/s23-ui-wiring.md` section 3.6) replaced the earlier
 *   `transportFactory: () -> TonexTransport` parameter with this callback: opening a protocol
 *   session is no longer this class's job. Calling `controller.connect(transportFactory())`
 *   directly from the UI layer bypassed the app's foreground-service gate entirely (issue #18),
 *   and the old `transportFactory` signature had no way to express "no transport is available
 *   right now" without throwing inside a `launch`. Production wiring passes
 *   `{ sessionHolder.requestReconnect(context) }` — restarting the foreground service, the only
 *   path allowed to hand a transport to the controller.
 * @property scope the coroutine scope [reconnect] and the [uiState] projection run on. Callers
 *   are responsible for supplying one whose lifetime covers this instance's use (see
 *   [rememberConnectionStatusViewModel]).
 */
class ConnectionStatusViewModel(
    private val controller: TonexController,
    private val onReconnectRequested: () -> Unit,
    private val scope: CoroutineScope,
) {

    /**
     * The current [ConnectionUiState], projected from [TonexController.connectionState]. A
     * [StateFlow] (not a one-shot read) so every screen embedding the persistent status
     * indicator (this story's scope) observes the same live value.
     */
    val uiState: StateFlow<ConnectionUiState> = controller.connectionState
        .map { it.toConnectionUiState() }
        .stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
            initialValue = controller.connectionState.value.toConnectionUiState(),
        )

    /**
     * The reconnect affordance this story requires. Simply invokes [onReconnectRequested] — see
     * that property's KDoc for why this class no longer opens a protocol session itself.
     * Fire-and-forget by design — progress is observable via [uiState], not this function's
     * return value — matching how a Compose button's `onClick` is expected to launch work rather
     * than suspend the caller.
     */
    fun reconnect() {
        onReconnectRequested()
    }
}

/**
 * Creates and remembers a [ConnectionStatusViewModel] scoped to the current composition, using
 * [rememberCoroutineScope] in place of a real `ViewModel`'s `viewModelScope` (see the class KDoc
 * for why). Re-created only if [controller] or [onReconnectRequested] identity changes.
 */
@Composable
fun rememberConnectionStatusViewModel(
    controller: TonexController,
    onReconnectRequested: () -> Unit,
): ConnectionStatusViewModel {
    val scope = rememberCoroutineScope()
    return remember(controller, onReconnectRequested) {
        ConnectionStatusViewModel(controller, onReconnectRequested, scope)
    }
}
