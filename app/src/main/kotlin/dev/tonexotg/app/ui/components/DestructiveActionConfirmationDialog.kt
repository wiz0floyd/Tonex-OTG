package dev.tonexotg.app.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import dev.tonexotg.app.ui.theme.TonexTheme
import dev.tonexotg.app.ui.theme.minTouchTarget

/**
 * Confirmation dialog for an irreversible action (the revert-to-snapshot flow, D2 screen 4b).
 *
 * The button hierarchy is intentionally inverted from the usual "primary action = filled,
 * accent-colored" pattern: **[onCancel] is the visually primary, accent-filled button; the
 * destructive action is the plain outline button.** This is D2's own explicit safety mechanism
 * (screen 4b's "Design call" annotation) — the safe path looks like the default path, so a
 * hurried or careless tap lands on Cancel, not on the irreversible action. Do not "fix" this by
 * making [confirmLabel] the filled button; that would undo the one thing this component exists
 * to do.
 *
 * D1 has no generic "destructive" color token (`status.error` is scoped to connection state,
 * D1 §2.3's own section title) — per D2 screen 4b's open question to the lead, this component
 * does not invent one. Safety is carried by placement/copy/hierarchy, not by coloring the
 * destructive button red.
 */
@Composable
fun DestructiveActionConfirmationDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    cancelLabel: String = "Cancel",
) {
    AlertDialog(
        onDismissRequest = onCancel,
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        confirmButton = {
            // Deliberately the SAFE action, styled as primary — see kdoc above.
            TextButton(
                onClick = onCancel,
                modifier = Modifier.minTouchTarget(TonexTheme.touchTargets.secondary),
                colors = ButtonDefaults.textButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Text(cancelLabel)
            }
        },
        dismissButton = {
            // Deliberately the DESTRUCTIVE action, styled as the plain outline button.
            OutlinedButton(
                onClick = onConfirm,
                modifier = Modifier.minTouchTarget(TonexTheme.touchTargets.secondary),
            ) {
                Text(confirmLabel)
            }
        },
    )
}

@Preview(name = "Destructive-action confirmation — revert to snapshot", showBackground = true, backgroundColor = 0xFF121212)
@Composable
private fun DestructiveActionConfirmationDialogPreview() {
    TonexTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            DestructiveActionConfirmationDialog(
                title = "Revert to snapshot?",
                message = "This immediately rewrites all 109 parameters of Set Opener back to " +
                    "their values from when it became active. The pedal auto-saves — this " +
                    "can't be undone either.",
                confirmLabel = "Revert Anyway",
                onConfirm = {},
                onCancel = {},
            )
        }
    }
}
