package dev.tonexotg.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.tonexotg.app.ui.theme.TonexOnStatus
import dev.tonexotg.app.ui.theme.TonexTheme

/**
 * The four connection states the UI ever needs to show (D2 screen 3's collapse of the protocol's
 * 5-value `ConnectionState` sealed interface — `Connecting`/`Hello`/`GetState` all read as
 * [Connecting] here). This is a local, Compose-native enum: this module never imports
 * `dev.tonexotg.protocol.*` directly, so the eventual view model layer maps the real
 * `ConnectionState` (and its `Error(cause)` payload) onto this enum and a display string.
 */
enum class ConnectionStatus {
    Connected,
    Connecting,
    Disconnected,
    Error,
}

/**
 * Renders connection state as D1 §2.3 requires: a fill color, a distinct silhouette, AND a text
 * label — never color alone. Each of the three permanent silhouettes is unique (filled circle /
 * hollow ring / filled triangle) so the states remain distinguishable to a grayscale or
 * protanopic/deuteranopic render, and [label] is also exposed as the accessibility
 * `contentDescription` so the meaning survives even if the glyph itself isn't legible.
 *
 * [label] is caller-supplied (not hardcoded per state) so it can be localized or reworded without
 * touching this component; D1 §2.3's own copy is "Connected" / "Connecting…" / "Not Connected" /
 * "Error".
 */
@Composable
fun ConnectionStatusIndicator(
    status: ConnectionStatus,
    label: String,
    modifier: Modifier = Modifier,
) {
    val color = when (status) {
        ConnectionStatus.Connected -> TonexTheme.extendedColors.statusConnected
        ConnectionStatus.Connecting -> TonexTheme.extendedColors.statusConnecting
        ConnectionStatus.Disconnected -> TonexTheme.extendedColors.statusDisconnected
        ConnectionStatus.Error -> TonexTheme.extendedColors.statusError
    }

    Row(
        modifier = modifier.semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TonexTheme.spacing.space0_5),
    ) {
        ConnectionStatusGlyph(status = status, color = color, modifier = Modifier.size(14.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = color,
        )
    }
}

/**
 * The silhouette half of D1 §2.3's "shape, text, or icon, never hue alone" requirement:
 * - [ConnectionStatus.Connected] → filled circle ("good, live")
 * - [ConnectionStatus.Connecting] and [ConnectionStatus.Disconnected] → hollow ring ("nothing
 *   there yet") — D1 deliberately shares this silhouette between the two transient/resting
 *   "not connected" states, distinguished from each other by color and by [label] text only.
 * - [ConnectionStatus.Error] → filled triangle ("something is wrong")
 *
 * No two states share a silhouette+color combination, and per D1 §5 nothing here animates —
 * the connecting glyph is a static hollow ring, not a spinner.
 */
@Composable
private fun ConnectionStatusGlyph(status: ConnectionStatus, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val strokeWidth = size.minDimension * 0.16f
        when (status) {
            ConnectionStatus.Connected -> {
                drawCircle(color = color)
            }

            ConnectionStatus.Connecting, ConnectionStatus.Disconnected -> {
                drawCircle(
                    color = color,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                    radius = (size.minDimension - strokeWidth) / 2f,
                )
            }

            ConnectionStatus.Error -> {
                val path = Path().apply {
                    moveTo(size.width / 2f, 0f)
                    lineTo(size.width, size.height)
                    lineTo(0f, size.height)
                    close()
                }
                drawPath(path = path, color = color)
                // The "!" glyph inside the triangle (D1: "filled triangle with glyph (▲!)").
                val glyphColor = TonexOnStatus
                val cx = size.width / 2f
                drawLine(
                    color = glyphColor,
                    start = Offset(cx, size.height * 0.38f),
                    end = Offset(cx, size.height * 0.68f),
                    strokeWidth = strokeWidth * 0.7f,
                    cap = StrokeCap.Round,
                )
                drawCircle(
                    color = glyphColor,
                    radius = strokeWidth * 0.4f,
                    center = Offset(cx, size.height * 0.82f),
                )
            }
        }
    }
}

@Preview(name = "Connection status — all four states", showBackground = true, backgroundColor = 0xFF121212)
@Composable
private fun ConnectionStatusIndicatorPreview() {
    TonexTheme {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(TonexTheme.spacing.space4),
        ) {
            ConnectionStatusIndicator(status = ConnectionStatus.Connected, label = "Connected")
            ConnectionStatusIndicator(status = ConnectionStatus.Connecting, label = "Connecting…")
            ConnectionStatusIndicator(status = ConnectionStatus.Disconnected, label = "Not Connected")
            ConnectionStatusIndicator(status = ConnectionStatus.Error, label = "Error")
        }
    }
}
