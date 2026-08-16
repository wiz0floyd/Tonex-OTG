package dev.tonexotg.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.tonexotg.app.ui.theme.TonexTheme
import dev.tonexotg.app.ui.theme.minTouchTarget

/**
 * The slider + numeric-entry pairing used everywhere a preset parameter is edited (S17's quick
 * tier and S19's full editor — D2 screens 2/2b/2c). This is deliberately a translation of what
 * D1/D2 show, not a commitment to one specific interaction mechanic: D3 (S17's interaction spec)
 * may not exist yet when this is built, so the component exposes a plain
 * value/range/callback shape general enough for either interaction to be layered on top of it
 * without changing this component's signature:
 *
 * - **Drag**: [Slider] is fully wired here — dragging updates [value] via [onValueChange] on
 *   every step and [onValueChangeFinished] once the drag ends, standard M3 slider semantics.
 * - **Direct numeric entry**: this component does not assume *how* exact entry happens (a
 *   keypad bottom sheet per D2 screen 2b, an inline text field, a stepper — D3's call). It only
 *   guarantees the numeric readout ([valueText]) is present and, if [onValueTextClick] is
 *   supplied, tappable — the caller wires whatever exact-entry surface D3 ultimately specifies
 *   behind that single callback. If [onValueTextClick] is null the readout is still shown, just
 *   inert, so this composable is equally usable for a not-yet-interactive preview.
 *
 * [value]/[valueRange] are plain `Float`/`ClosedFloatingPointRange<Float>` — this module never
 * imports `dev.tonexotg.protocol.*`, so the view model that eventually drives this from a real
 * `ParameterSpec` is what does that translation.
 */
@Composable
fun ParameterControl(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    valueText: String,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    minLabel: String? = null,
    maxLabel: String? = null,
    abbreviation: String? = null,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
    onValueTextClick: (() -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ParameterValueReadout(
                text = valueText,
                onClick = onValueTextClick,
            )
        }

        if (abbreviation != null) {
            Text(
                text = abbreviation,
                style = MaterialTheme.typography.labelSmall,
                color = TonexTheme.extendedColors.onSurfaceTertiary,
            )
        }

        // Full 64dp tap/drag band around the thumb (D1 §4.2: any control used mid-performance),
        // even though the visual track inside it is thin — matches D2 screen 2's slider-wrap.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .minTouchTarget(),
            contentAlignment = Alignment.Center,
        ) {
            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = valueRange,
                enabled = enabled,
                onValueChangeFinished = onValueChangeFinished,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                ),
            )
        }

        if (minLabel != null && maxLabel != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(text = minLabel, style = MaterialTheme.typography.labelSmall, color = TonexTheme.extendedColors.onSurfaceDisabled)
                Text(text = maxLabel, style = MaterialTheme.typography.labelSmall, color = TonexTheme.extendedColors.onSurfaceDisabled)
            }
        }
    }
}

/**
 * The numeric readout chip (D2's `.param-value`) — the entry point into direct numeric entry,
 * whatever form D3 eventually gives that flow. Sized to
 * [dev.tonexotg.app.ui.theme.TonexTouchTargets.secondary] (48dp): D2 screen 2's own annotation
 * treats "tap to open exact-entry" as a between-songs action, not a mid-song slider drag.
 */
@Composable
private fun ParameterValueReadout(text: String, onClick: (() -> Unit)?) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .widthIn(min = 48.dp)
            .minTouchTarget(TonexTheme.touchTargets.secondary)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = TonexTheme.spacing.space2),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Preview(name = "Parameter control — draggable + tappable readout", showBackground = true, backgroundColor = 0xFF262626)
@Composable
private fun ParameterControlPreview() {
    TonexTheme {
        ParameterControl(
            label = "Gain",
            value = 6.4f,
            valueRange = 0f..10f,
            valueText = "6.4",
            onValueChange = {},
            onValueChangeFinished = {},
            onValueTextClick = {},
            abbreviation = "MDL GAIN · index 20",
            minLabel = "0.0",
            maxLabel = "10.0",
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview(name = "Parameter control — no numeric-entry callback wired yet", showBackground = true, backgroundColor = 0xFF262626)
@Composable
private fun ParameterControlNoEntryPreview() {
    TonexTheme {
        ParameterControl(
            label = "Reverb Mix",
            value = 28f,
            valueRange = 0f..100f,
            valueText = "28%",
            onValueChange = {},
            minLabel = "0%",
            maxLabel = "100%",
            modifier = Modifier.padding(16.dp),
        )
    }
}
