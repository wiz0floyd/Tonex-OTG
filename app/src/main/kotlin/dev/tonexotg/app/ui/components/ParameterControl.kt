package dev.tonexotg.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.tonexotg.app.ui.theme.TonexTheme
import dev.tonexotg.app.ui.theme.minTouchTarget

/**
 * The slider + numeric-entry pairing used everywhere a preset `RANGE` parameter is edited (S17's
 * quick tier and S19's full editor — D2 screens 2/2b/2c, and now D3 §3/§3.1's binding interaction
 * contract). D3 resolves the drag-vs-exact-entry ambiguity **architecturally**, not with a
 * tap/long-press timing heuristic, and this component's layout is a direct translation of that:
 *
 * - **The slider band** ([ParameterSliderBand], tagged `"paramControl.sliderBand"`) spans the full
 *   card width and is sized to [dev.tonexotg.app.ui.theme.TonexTouchTargets.min] (64dp) — D1
 *   §4.2's mid-performance minimum — even though the visual track drawn inside it is a slim 8dp
 *   (D3 §3: "the visual track stays slim, but the tappable/draggable region around it is
 *   full-size"). Any touch-and-move here is a drag; it can never resolve to "open exact entry."
 * - **The numeric value chip** ([ParameterValueReadout], tagged `"paramControl.valueChip"`) is a
 *   separate, non-overlapping element in its own [Row] above the slider band, top-right of the
 *   card, sized to [dev.tonexotg.app.ui.theme.TonexTouchTargets.secondary] (48dp). A tap here —
 *   anywhere on the chip, no drag — is the *only* way to open exact numeric entry (D3's bottom
 *   sheet, [ParameterNumericEntrySheet]); it is not inside the slider band's layout subtree, so it
 *   can never participate in the slider's drag gesture.
 *
 * D3 §3 is explicit about the failure mode this avoids: **do not** build this as an editable text
 * field overlaid on or adjacent to the slider thumb, where a drag could be misread as text
 * selection. Keeping the two controls in physically separate [Column] rows — not overlapping
 * `Box`es — is what makes that failure mode structurally impossible rather than merely unlikely;
 * [dev.tonexotg.app.ui.components.ParameterControlTest] pins the two regions' bounds as
 * non-overlapping so a future layout change can't reintroduce it silently.
 *
 * [value]/[valueRange] are plain `Float`/`ClosedFloatingPointRange<Float>` — this module never
 * imports `dev.tonexotg.protocol.*`, so the view model that eventually drives this from a real
 * `ParameterSpec` is what does that translation. [onValueTextClick] is where a caller wires up
 * [ParameterNumericEntrySheet]; it stays a plain callback here (rather than this component owning
 * the sheet) so a not-yet-interactive preview or a caller with different exact-entry needs can
 * still use this component with the callback left null.
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

        ParameterSliderBand(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            enabled = enabled,
            onValueChangeFinished = onValueChangeFinished,
        )

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
 * D3 §3's slider band: a [dev.tonexotg.app.ui.theme.TonexTouchTargets.min] (64dp) tall drag/hit
 * region spanning the full card width, with an 8dp visual track centered inside it. The 64dp
 * height is applied to the [Slider] itself (not a wrapping `Box`) so the actual pointer-input
 * region — not just the visually reserved space — is the full 64dp; a `Box` with a min-height
 * around a default-sized `Slider` would reserve the layout space without enlarging the real
 * touch/drag target, which is exactly the gap D3 §3 exists to close.
 *
 * Uses the `track =` slot overload of M3's [Slider] (`@ExperimentalMaterial3Api`) to draw the
 * slim 8dp track — the stable slot API, not a hand-rolled gesture implementation, so drag/tap
 * semantics and accessibility stay M3's own well-tested behavior.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ParameterSliderBand(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    onValueChangeFinished: (() -> Unit)?,
) {
    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .height(TonexTheme.touchTargets.min)
            .testTag("paramControl.sliderBand"),
        valueRange = valueRange,
        enabled = enabled,
        onValueChangeFinished = onValueChangeFinished,
        colors = SliderDefaults.colors(
            thumbColor = MaterialTheme.colorScheme.primary,
            activeTrackColor = MaterialTheme.colorScheme.primary,
            inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
        track = { sliderState: SliderState ->
            // D1 §4.2 / D3 §3: the visual track stays a slim 8dp even though the tappable region
            // around it (this whole Slider's 64dp-tall bounding box, set above) is full-size.
            SliderDefaults.Track(
                sliderState = sliderState,
                modifier = Modifier.height(8.dp),
                colors = SliderDefaults.colors(
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                ),
                enabled = enabled,
            )
        },
    )
}

/**
 * The numeric readout chip (D2's `.param-value`) — per D3 §3, the *only* entry point into
 * [ParameterNumericEntrySheet]. Sized to
 * [dev.tonexotg.app.ui.theme.TonexTouchTargets.secondary] (48dp) and tagged
 * `"paramControl.valueChip"` so [dev.tonexotg.app.ui.components.ParameterControlTest] can assert
 * its bounds never overlap [ParameterSliderBand]'s.
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
            .padding(horizontal = TonexTheme.spacing.space2)
            .testTag("paramControl.valueChip"),
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
