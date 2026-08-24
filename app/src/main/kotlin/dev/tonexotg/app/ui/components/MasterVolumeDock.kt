package dev.tonexotg.app.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.tonexotg.app.ui.screens.parameters.ParameterRow
import dev.tonexotg.app.ui.theme.TonexTheme

/**
 * `MASTER_VOLUME` (idx 116) — the one global reached for constantly, so it gets its own dock
 * rather than sitting inside the full-tier accordion or (as of #107) the collapsible globals
 * tray. Originally private to `ParameterEditorScreen.kt` (S17/S19); extracted here by #107 so the
 * preset-list home screen can mount a second mount point of the *same* composable rather than a
 * second implementation — Parameter Editor keeps its own bottom-bar mount unchanged.
 *
 * D1 §2.1 `surface.raised-1` (top app bar / bottom bar token) — global scope reads as chrome, not
 * as belonging to whichever preset is open (D3 §1.1).
 */
@Composable
fun MasterVolumeDock(
    row: ParameterRow.Range,
    onValueChange: (Float) -> Unit,
    onValueTextClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier) {
        ParameterControl(
            label = "Master Volume",
            value = row.value,
            valueRange = row.range,
            valueText = row.valueText,
            onValueChange = onValueChange,
            abbreviation = row.abbreviation,
            onValueTextClick = onValueTextClick,
            modifier = Modifier
                .padding(TonexTheme.spacing.space3)
                .testTag("masterVolume.dock"),
        )
    }
}
