package dev.tonexotg.app.ui.screens.settings

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import dev.tonexotg.app.probe.ProbeActivity
import dev.tonexotg.app.ui.screens.about.loadAppVersionInfo
import dev.tonexotg.app.ui.theme.TonexTheme

/**
 * Issue #96: the app's Settings screen. Its one real job today is to be the in-app entry point
 * for [ProbeActivity] (the S20 hardware probe / diagnostics, issue #25), which used to have its
 * own separate launcher icon -- see that Activity's own KDoc and the manifest for why it no
 * longer does. Launching it via an explicit [Intent] here (rather than folding its Compose UI
 * into this NavHost) keeps that Activity's own lifecycle/CoroutineScope contract (see its KDoc,
 * "Deliberately NOT scope.cancel()...") unchanged.
 *
 * Issue #126: also the discoverable "Report a bug" entry point for regular users, as its own
 * "Support" section above "Diagnostics" -- the debug menu's own GitHub-issue shortcut
 * (`buildDebugDumpIssueUri`, issue #96) stays diagnostic-only per issue #126's "out of scope".
 */
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(TonexTheme.spacing.space3),
            verticalArrangement = Arrangement.spacedBy(TonexTheme.spacing.space3),
        ) {
            Text(text = "Settings", style = MaterialTheme.typography.headlineSmall)

            HorizontalDivider()

            Text(text = "Support", style = MaterialTheme.typography.titleLarge)
            Text(
                text = "Found a problem? Report it and we'll take a look.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = {
                    val appVersionInfo = loadAppVersionInfo(context)
                    val uri = buildBugReportIssueUri(appVersionInfo)
                    context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Report a bug")
            }

            HorizontalDivider()

            Text(text = "Diagnostics", style = MaterialTheme.typography.titleLarge)
            Text(
                text = "Diagnostic tools for troubleshooting a pedal connection. Not needed for " +
                    "normal use.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = { context.startActivity(Intent(context, ProbeActivity::class.java)) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Open hardware probe / debug menu")
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsScreenPreview() {
    TonexTheme {
        SettingsScreen()
    }
}
