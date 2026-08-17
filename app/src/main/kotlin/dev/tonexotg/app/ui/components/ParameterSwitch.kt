package dev.tonexotg.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import dev.tonexotg.app.ui.theme.TonexTheme
import dev.tonexotg.app.ui.theme.minTouchTarget

/**
 * The one-tap toggle for `SWITCH`-type parameters (D3 §3.2, e.g. `NOISE_GATE_ENABLE`, `EQ_POST`,
 * `DELAY_DIGITAL_MODE`) — "no sheet at all," matching D2's `.mini-row.switch` treatment (screen
 * 2c's full-tier accordion rows) exactly: the whole row is one tap target, not just the switch
 * glyph itself, so a mis-aimed tap anywhere on the label still lands the toggle.
 *
 * Sized to [dev.tonexotg.app.ui.theme.TonexTouchTargets.secondary] (48dp), matching D2's own
 * `.mini-row { min-height: var(--touch-secondary) }` CSS for every full-tier parameter row,
 * switches included — browsing/toggling a full-tier row is D3 §1.2's "between-songs action," not
 * a quick-tier, mid-drag control, so it does not get the 64dp mid-performance minimum.
 *
 * The switch glyph itself uses [Switch] (M3's own accessible toggle — checked/unchecked semantics,
 * state description, etc. come for free) rather than a hand-drawn track+thumb. [onCheckedChange]
 * is wired to both the row's own `clickable` (so a tap on the label or surrounding whitespace
 * toggles too) and the [Switch] itself — Compose's nested-`clickable` gesture handling means a tap
 * that lands on the [Switch] is consumed there and never reaches the row's `clickable`, so this
 * does not double-fire.
 */
@Composable
fun ParameterSwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    abbreviation: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .minTouchTarget(TonexTheme.touchTargets.secondary)
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .semantics { role = Role.Switch }
            .padding(vertical = TonexTheme.spacing.space1)
            .testTag("paramSwitch.row"),
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
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            modifier = Modifier.testTag("paramSwitch.toggle"),
            colors = SwitchDefaults.colors(
                // checked thumb = on-accent (D1 §2.2/D2 CSS: `.mval::after` checked → on-accent).
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                checkedBorderColor = MaterialTheme.colorScheme.primary,
                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                uncheckedBorderColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            ),
        )
    }
}

@Preview(name = "Parameter switch — on and off", showBackground = true, backgroundColor = 0xFF262626)
@Composable
private fun ParameterSwitchPreview() {
    TonexTheme {
        Column {
            ParameterSwitch(
                label = "Enable",
                abbreviation = "NG POWER",
                checked = true,
                onCheckedChange = {},
            )
            ParameterSwitch(
                label = "Post",
                abbreviation = "NG POST",
                checked = false,
                onCheckedChange = {},
            )
        }
    }
}
