package dev.tonexotg.app.ui.screens.connection

import dev.tonexotg.app.ui.components.ConnectionStatus
import dev.tonexotg.protocol.ConnectionState
import dev.tonexotg.protocol.TonexError

/**
 * The screen-level view of [ConnectionState] — everything a host screen needs to drive the S15
 * [dev.tonexotg.app.ui.components.ConnectionStatusIndicator] plus (on failure) an
 * [ErrorPresentation], without depending on `dev.tonexotg.protocol.*` itself. Mirrors the
 * [ConnectionStatusIndicator]'s own "this module never imports `dev.tonexotg.protocol.*`
 * directly" boundary at the composable layer; [toConnectionUiState] is the one place that
 * boundary is deliberately crossed.
 *
 * Every variant carries its own [status] and [label] (D1 §2.3's copy) so a caller never has to
 * re-derive them; [ErrorState] additionally carries the [ErrorPresentation] FR11 requires.
 */
sealed interface ConnectionUiState {
    val status: ConnectionStatus
    val label: String

    /** No transport attached, or a previous session ended cleanly. Matches [ConnectionState.Idle]. */
    data object NotConnected : ConnectionUiState {
        override val status = ConnectionStatus.Disconnected
        override val label = "Not Connected"
    }

    /**
     * A connection attempt is in progress. Collapses [ConnectionState.Connecting],
     * [ConnectionState.Hello], and [ConnectionState.GetState] — all three read identically to a
     * player waiting for the link to come up; the distinction only matters for diagnostics/tests,
     * not for what's shown on screen (matches [ConnectionStatus]'s own documented collapse).
     */
    data object Connecting : ConnectionUiState {
        override val status = ConnectionStatus.Connecting
        override val label = "Connecting…"
    }

    /** The handshake is complete and the pedal is ready to read/write. Matches [ConnectionState.Ready]. */
    data object Connected : ConnectionUiState {
        override val status = ConnectionStatus.Connected
        override val label = "Connected"
    }

    /**
     * The connection failed and is not usable. Matches [ConnectionState.Error]. [presentation] is
     * the FR11-mandated distinct, actionable explanation for [cause] — never a generic failure
     * with no explanation.
     */
    data class ErrorState(val cause: TonexError) : ConnectionUiState {
        override val status = ConnectionStatus.Error
        override val label = "Error"
        val presentation: ErrorPresentation = cause.toPresentation()
    }
}

/**
 * Maps the protocol-level [ConnectionState] to the screen-level [ConnectionUiState]. The one
 * place `dev.tonexotg.app.ui.screens.connection` depends on `dev.tonexotg.protocol.ConnectionState`
 * directly.
 */
fun ConnectionState.toConnectionUiState(): ConnectionUiState = when (this) {
    is ConnectionState.Idle -> ConnectionUiState.NotConnected
    is ConnectionState.Connecting,
    is ConnectionState.Hello,
    is ConnectionState.GetState,
    -> ConnectionUiState.Connecting

    is ConnectionState.Ready -> ConnectionUiState.Connected
    is ConnectionState.Error -> ConnectionUiState.ErrorState(cause)
}
