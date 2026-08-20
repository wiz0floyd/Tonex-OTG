package dev.tonexotg.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.tonexotg.app.ui.theme.TonexTheme
import dev.tonexotg.app.ui.theme.minTouchTarget

/**
 * The stepper for a `SELECT` parameter with **no known option labels** (D3 §3.2) —
 * `VIR_CABINET_MODEL` (0-max, max taken live from `ParameterSpec` in the registry, not hardcoded
 * here), `VIR_MIC_1`/`VIR_MIC_2` (0-2 each), and the six `*_TS` "Sync
 * Division" selectors banked under every reverb/mod/delay model (0-17 each).
 *
 * D3 §3.2 is explicit about why this is a *third*, distinct pattern rather than reusing either of
 * the other two `SELECT` treatments:
 * - **Not [ParameterNumericEntrySheet]'s keypad**: typing e.g. `7` for an 18-option selector with
 *   no option labels would let a player enter a value with no way to know what option 7 *is*, and
 *   wrongly implies a continuous range rather than discrete integer steps.
 * - **Not [ParameterSelectChipRow]**: a chip row is unusable at 11-18 unlabeled options, and this
 *   component deliberately doesn't invent option names the registry doesn't provide (D2's own
 *   refusal to invent mic-brand names for these same rows).
 *
 * The raw numeric index is shown large in the middle, flanked by `−`/`+` buttons — each
 * [dev.tonexotg.app.ui.theme.TonexTouchTargets.secondary] (48dp) per D3 §3.2's exact spec —
 * incrementing/decrementing [value] by exactly 1 and clamping at [range]'s bounds (the `−` button
 * disables at [IntRange.first], the `+` button disables at [IntRange.last] — there is no wraparound).
 */
@Composable
fun ParameterStepper(
    label: String,
    value: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    abbreviation: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .minTouchTarget(TonexTheme.touchTargets.secondary),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else TonexTheme.extendedColors.onSurfaceDisabled,
            )
            if (abbreviation != null) {
                Text(
                    text = abbreviation,
                    style = MaterialTheme.typography.labelSmall,
                    color = TonexTheme.extendedColors.onSurfaceTertiary,
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            StepperButton(
                symbol = "−",
                onClick = { onValueChange((value - 1).coerceAtLeast(range.first)) },
                enabled = enabled && value > range.first,
                modifier = Modifier.testTag("paramStepper.decrement"),
            )
            Text(
                text = value.toString(),
                style = MaterialTheme.typography.headlineSmall,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else TonexTheme.extendedColors.onSurfaceDisabled,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .width(48.dp)
                    .testTag("paramStepper.value"),
            )
            StepperButton(
                symbol = "+",
                onClick = { onValueChange((value + 1).coerceAtMost(range.last)) },
                enabled = enabled && value < range.last,
                modifier = Modifier.testTag("paramStepper.increment"),
            )
        }
    }
}

@Composable
private fun StepperButton(symbol: String, onClick: () -> Unit, enabled: Boolean, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = modifier
            .minTouchTarget(TonexTheme.touchTargets.secondary)
            .background(if (enabled) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent, shape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = symbol,
            style = MaterialTheme.typography.titleLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else TonexTheme.extendedColors.onSurfaceDisabled,
        )
    }
}

@Preview(name = "Parameter stepper — mid-range (Sync Division, 0-17)", showBackground = true, backgroundColor = 0xFF262626)
@Composable
private fun ParameterStepperMidRangePreview() {
    TonexTheme {
        ParameterStepper(
            label = "Sync Division",
            abbreviation = "MOD RO T · idx 89",
            value = 4,
            range = 0..17,
            onValueChange = {},
        )
    }
}

@Preview(name = "Parameter stepper — at min (Mic 1, 0-2)", showBackground = true, backgroundColor = 0xFF262626)
@Composable
private fun ParameterStepperAtMinPreview() {
    TonexTheme {
        ParameterStepper(
            label = "Mic 1",
            abbreviation = "VIR M1",
            value = 0,
            range = 0..2,
            onValueChange = {},
        )
    }
}
