package dev.tonexotg.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.tonexotg.app.ui.theme.TonexTheme
import dev.tonexotg.app.ui.theme.minTouchTarget

/**
 * The horizontal chip row for a `SELECT` parameter with known option labels (D3 §2.1, §3.2) —
 * the four model selectors (`REVERB_MODEL`, `MODULATION_MODEL`, `DELAY_MODEL`) and `CABINET_TYPE`.
 * **Tapping a chip is the entire interaction** — no separate numeric affordance, no confirmation
 * step, matching D2's `.model-selector`/`.model-chip` markup exactly.
 *
 * Selection is visually and functionally **instant** — [onSelect] fires directly from the tap,
 * with no transition or animation gating it, per D1 §5's zero-motion-gating rule (D3 §2.1: "instant
 * ... because D1 forbids any animation sitting between a tap and its effect"). Swapping which
 * model's parameter block renders beneath this row is the caller's job (D3 §2.1's "hidden
 * entirely, not grayed" rule) — this component only owns the chip row itself.
 *
 * Chips wrap onto multiple lines ([FlowRow], matching D2's `flex-wrap: wrap` `.model-selector`)
 * rather than horizontally scrolling — Reverb's 6 model chips would not all fit on one line on a
 * phone-width card, and a wrapped row keeps every option visible at once rather than hiding some
 * behind a scroll a player would have to discover mid-song.
 *
 * Each chip is sized to [dev.tonexotg.app.ui.theme.TonexTouchTargets.secondary] (48dp), matching
 * D2's `.model-chip { min-height: var(--touch-secondary) }` exactly — not the 64dp mid-performance
 * minimum, since D2 treats model browsing/selection the same as the rest of the full-tier rows.
 *
 * [options] and [label] are generic (`T`, `(T) -> String`) rather than a fixed enum, so this one
 * component serves all of D3's known-label `SELECT` parameters (reverb/mod/delay/cabinet models)
 * without this module needing to import `dev.tonexotg.protocol.*` to know their real option types.
 */
@Composable
fun <T> ParameterSelectChipRow(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(TonexTheme.spacing.space1),
        verticalArrangement = Arrangement.spacedBy(TonexTheme.spacing.space1),
    ) {
        for (option in options) {
            val isActive = option == selected
            SelectChip(
                text = label(option),
                active = isActive,
                enabled = enabled,
                onClick = { onSelect(option) },
                modifier = Modifier.testTag("selectChipRow.chip.${label(option)}"),
            )
        }
    }
}

@Composable
private fun SelectChip(text: String, active: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(percent = 50)
    val background = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh
    val contentColor = when {
        !enabled -> TonexTheme.extendedColors.onSurfaceDisabled
        active -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(
        modifier = modifier
            .minTouchTarget(TonexTheme.touchTargets.secondary)
            .background(background, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics {
                role = Role.RadioButton
                selected = active
            }
            .padding(horizontal = TonexTheme.spacing.space2),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = contentColor,
        )
    }
}

@Preview(name = "Select chip row — Reverb model (6 options)", showBackground = true, backgroundColor = 0xFF262626)
@Composable
private fun ParameterSelectChipRowReverbPreview() {
    TonexTheme {
        ParameterSelectChipRow(
            options = listOf("Spring 1", "Spring 2", "Spring 3", "Spring 4", "Room", "Plate"),
            selected = "Plate",
            onSelect = {},
            label = { it },
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview(name = "Select chip row — Cabinet mode (3 options)", showBackground = true, backgroundColor = 0xFF262626)
@Composable
private fun ParameterSelectChipRowCabinetPreview() {
    TonexTheme {
        ParameterSelectChipRow(
            options = listOf("Tone Model", "VIR", "Disabled"),
            selected = "VIR",
            onSelect = {},
            label = { it },
            modifier = Modifier.padding(16.dp),
        )
    }
}
