package dev.tonexotg.app.ui.screens.connection

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.tonexotg.app.ui.components.ConnectionStatusIndicator
import dev.tonexotg.app.ui.theme.TonexTheme
import dev.tonexotg.app.ui.theme.minTouchTarget

/**
 * The persistent connection indicator this story requires "visible from every screen" — a thin
 * chrome bar meant to sit at the top of whatever host screen embeds it (Preset List, Parameter
 * Editor, Settings, ...), per D1's "legible peripherally" requirement: a player glancing up
 * mid-song should be able to read [uiState] without focusing on it, from the same fixed spot
 * regardless of which screen they're on.
 *
 * Reuses the S15 [ConnectionStatusIndicator] as-is for the glyph+color+text triplet (D1 §2.3:
 * never hue alone) and adds only what S15 didn't need to cover: a reconnect affordance, shown
 * whenever the link isn't already up or coming up. No reconnect action is offered while
 * [ConnectionUiState.Connecting] — a second connection attempt started mid-attempt would race
 * the one already in flight, not help it along.
 */
@Composable
fun ConnectionStatusBar(
    uiState: ConnectionUiState,
    onReconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = TonexTheme.spacing.space3, vertical = TonexTheme.spacing.space1),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        ConnectionStatusIndicator(status = uiState.status, label = uiState.label)

        val showReconnect = uiState is ConnectionUiState.NotConnected || uiState is ConnectionUiState.ErrorState
        if (showReconnect) {
            TextButton(
                onClick = onReconnect,
                modifier = Modifier
                    .minTouchTarget(TonexTheme.touchTargets.secondary)
                    .semantics { contentDescription = "Reconnect to pedal" },
            ) {
                Text("Reconnect")
            }
        }
    }
}

/**
 * The FR11 error detail surface: [presentation]'s [ErrorPresentation.title],
 * [ErrorPresentation.detail], and [ErrorPresentation.advice], plus a reconnect action when
 * [ErrorPresentation.recommendReconnect] is true. Rendered as an ordinary in-flow card, never a
 * blocking dialog — per this story's "disconnect mid-edit without losing the user's place"
 * requirement, the screen behind this panel is never removed from composition to show it (see
 * [ConnectionAwareHost]).
 *
 * Every field here comes from [presentation], never re-derived or paraphrased — this is the one
 * place [ErrorPresentation.toPresentation] is actually consumed, so the exhaustive `when` in
 * `ErrorPresentation.kt` is what guarantees no [dev.tonexotg.protocol.TonexError] variant reaches
 * a player without a considered, distinct message here.
 */
@Composable
fun ConnectionErrorPanel(
    presentation: ErrorPresentation,
    onReconnect: () -> Unit,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(TonexTheme.spacing.space3)
            .semantics {
                contentDescription = "${presentation.title}. ${presentation.detail} ${presentation.advice}"
            },
        verticalArrangement = Arrangement.spacedBy(TonexTheme.spacing.space1),
    ) {
        Text(
            text = presentation.title,
            style = MaterialTheme.typography.titleMedium,
            color = TonexTheme.extendedColors.statusError,
        )
        Text(
            text = presentation.detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = presentation.advice,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (presentation.recommendReconnect || onDismiss != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(TonexTheme.spacing.space2)) {
                if (presentation.recommendReconnect) {
                    TextButton(
                        onClick = onReconnect,
                        modifier = Modifier.minTouchTarget(TonexTheme.touchTargets.secondary),
                    ) {
                        Text("Reconnect")
                    }
                }
                // issue #93: without this, a failed write leaves this panel permanently stuck —
                // it previously only cleared on a subsequent *successful* call of the same kind,
                // and recommendReconnect is false for most write failures (they aren't connection
                // errors), so there was often no button here at all.
                if (onDismiss != null) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.minTouchTarget(TonexTheme.touchTargets.secondary),
                    ) {
                        Text("Dismiss")
                    }
                }
            }
        }
    }
}

/**
 * Wraps [content] with the persistent [ConnectionStatusBar] and, on [ConnectionUiState.ErrorState],
 * an in-flow [ConnectionErrorPanel] — **without ever removing [content] from composition**.
 *
 * This is the direct mechanism behind this story's "handle disconnect mid-edit without losing the
 * user's place" acceptance criterion: [content] is composed unconditionally on every branch below,
 * so any state a caller has `remember`ed inside it (scroll position, an open numeric-entry sheet,
 * an in-progress drag) survives a connection drop intact — the error panel appears *alongside* the
 * screen, not *instead of* it. A caller that wants to react further (e.g. cancel an in-flight slider
 * drag per D3 §6.3's external-preset-change handling) still can, by observing [uiState] itself;
 * this wrapper only guarantees composition survives, it does not itself cancel anything inside
 * [content].
 *
 * The error panel's expanded/collapsed state is purely local presentation — collapsing it never
 * changes [uiState] or implies the error resolved; the persistent status bar above keeps showing
 * [ConnectionUiState.ErrorState] regardless, so there is no path here that can end up looking like
 * success while [uiState] says otherwise.
 */
@Composable
fun ConnectionAwareHost(
    uiState: ConnectionUiState,
    onReconnect: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        ConnectionStatusBar(uiState = uiState, onReconnect = onReconnect)

        if (uiState is ConnectionUiState.ErrorState) {
            var expanded by remember(uiState.cause) { mutableStateOf(true) }
            if (expanded) {
                ConnectionErrorPanel(
                    presentation = uiState.presentation,
                    onReconnect = onReconnect,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                TextButton(onClick = { expanded = true }) {
                    Text("Show error details")
                }
            }
        }

        Box(modifier = Modifier.fillMaxWidth()) {
            content()
        }
    }
}

@Preview(name = "Connection status bar — all four states", showBackground = true, backgroundColor = 0xFF121212)
@Composable
private fun ConnectionStatusBarPreview() {
    TonexTheme {
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            ConnectionStatusBar(uiState = ConnectionUiState.Connected, onReconnect = {})
            ConnectionStatusBar(uiState = ConnectionUiState.Connecting, onReconnect = {})
            ConnectionStatusBar(uiState = ConnectionUiState.NotConnected, onReconnect = {})
            ConnectionStatusBar(
                uiState = ConnectionUiState.ErrorState(
                    dev.tonexotg.protocol.TonexError.Timeout("hello", 3_000),
                ),
                onReconnect = {},
            )
        }
    }
}

@Preview(name = "Connection error panel — transport failure", showBackground = true, backgroundColor = 0xFF121212)
@Composable
private fun ConnectionErrorPanelPreview() {
    TonexTheme {
        ConnectionErrorPanel(
            presentation = dev.tonexotg.protocol.TonexError.TransportFailure(
                cause = RuntimeException("device disconnected"),
            ).toPresentation(),
            onReconnect = {},
        )
    }
}

@Preview(name = "Connection-aware host — content survives an error", showBackground = true, backgroundColor = 0xFF121212)
@Composable
private fun ConnectionAwareHostPreview() {
    TonexTheme {
        ConnectionAwareHost(
            uiState = ConnectionUiState.ErrorState(
                dev.tonexotg.protocol.TonexError.CrcMismatch(expected = 0x1234, actual = 0xABCD),
            ),
            onReconnect = {},
        ) {
            Box(modifier = Modifier.padding(TonexTheme.spacing.space4)) {
                Text("Parameter editor content stays mounted here", color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}
