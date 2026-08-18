package dev.tonexotg.app.ui.screens.about

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.tonexotg.app.ui.theme.TonexTheme

/**
 * The tested pedal firmware version (S8's state-blob offsets are firmware-dependent — see
 * issue #24). No firmware has been recorded against real hardware yet (issue #25 / S20's
 * hardware-verification pass is still open), so [PendingVerification] is the only case this
 * screen ever renders today. [Verified] exists so that a future session closing #25 has a real
 * slot to fill in, rather than inventing a version string now.
 */
sealed interface TestedFirmwareInfo {
    data object PendingVerification : TestedFirmwareInfo
    data class Verified(val version: String) : TestedFirmwareInfo
}

/**
 * S19 / issue #24: the About/credits screen. Standalone and self-contained — it takes its content
 * as parameters rather than reading `LocalContext` or navigation state itself, so it can be
 * dropped into a `NavHost` destination later without this file needing to change. Use the
 * zero-arg-content overload below for real app usage (it loads [AboutScreenContent] and
 * [AppVersionInfo] from the running app); this overload is what both that convenience wrapper and
 * tests/[Preview]s call into directly.
 */
@Composable
fun AboutScreen(
    content: AboutScreenContent,
    appVersionInfo: AppVersionInfo,
    modifier: Modifier = Modifier,
    firmwareInfo: TestedFirmwareInfo = TestedFirmwareInfo.PendingVerification,
) {
    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(TonexTheme.spacing.space3),
            verticalArrangement = Arrangement.spacedBy(TonexTheme.spacing.space4),
        ) {
            Text(text = "About Tonex-OTG", style = MaterialTheme.typography.headlineSmall)

            AppInfoSection(appVersionInfo = appVersionInfo, firmwareInfo = firmwareInfo)

            HorizontalDivider()

            Text(text = "Credits & Licences", style = MaterialTheme.typography.titleLarge)
            Text(
                text = "Tonex-OTG ports USB protocol logic from the two open-source projects " +
                    "below. Full attribution details: /CREDITS.md.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            content.upstreamProjects.forEach { project ->
                UpstreamProjectCard(
                    project = project,
                    licenceText = content.licenceTexts[project.name].orEmpty(),
                )
            }

            FullCreditsSection(fullCreditsMarkdown = content.fullCreditsMarkdown)
        }
    }
}

/** Real-usage convenience overload: loads content and version info from the running app. */
@Composable
fun AboutScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val content = remember { LicensingAssets.load(context) }
    val appVersionInfo = remember { loadAppVersionInfo(context) }
    AboutScreen(content = content, appVersionInfo = appVersionInfo, modifier = modifier)
}

@Composable
private fun AppInfoSection(appVersionInfo: AppVersionInfo, firmwareInfo: TestedFirmwareInfo) {
    Column(verticalArrangement = Arrangement.spacedBy(TonexTheme.spacing.space0_5)) {
        Text(
            text = "Version ${appVersionInfo.versionName} (${appVersionInfo.versionCode})",
            style = MaterialTheme.typography.bodyLarge,
        )

        val firmwareText = when (firmwareInfo) {
            is TestedFirmwareInfo.PendingVerification ->
                "Tested pedal firmware: pending hardware verification (see issue #25)"
            is TestedFirmwareInfo.Verified ->
                "Tested pedal firmware: ${firmwareInfo.version}"
        }
        Text(
            text = firmwareText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun UpstreamProjectCard(project: UpstreamProjectCredit, licenceText: String) {
    var licenceExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(TonexTheme.spacing.space1),
            )
            .padding(TonexTheme.spacing.space3),
        verticalArrangement = Arrangement.spacedBy(TonexTheme.spacing.space0_5),
    ) {
        Text(text = project.name, style = MaterialTheme.typography.titleLarge)
        Text(text = project.licenceSummary, style = MaterialTheme.typography.bodyMedium)
        Text(text = project.copyright, style = MaterialTheme.typography.bodyMedium)
        if (project.repositoryUrl.isNotBlank()) {
            Text(
                text = project.repositoryUrl,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        TextButton(onClick = { licenceExpanded = !licenceExpanded }) {
            Text(if (licenceExpanded) "Hide full licence text" else "View full licence text")
        }

        if (licenceExpanded) {
            Text(
                text = licenceText,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .padding(TonexTheme.spacing.space2),
            )
        }
    }
}

@Composable
private fun FullCreditsSection(fullCreditsMarkdown: String) {
    var expanded by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(TonexTheme.spacing.space0_5)) {
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "Hide full CREDITS.md" else "View full CREDITS.md")
        }
        if (expanded) {
            Text(
                text = fullCreditsMarkdown,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .padding(TonexTheme.spacing.space2),
            )
        }
    }
}

// PREVIEW-ONLY sample data. This mirrors the real CREDITS.md content at the time this screen was
// written, purely so the @Preview renders something realistic without touching Android assets
// (which the Compose tooling preview environment cannot reliably load). It is not read at
// runtime and is not a second source of truth — [AboutScreen]'s real overload above always loads
// from [LicensingAssets], which reads the bundled `CREDITS.md` asset.
private val PreviewContent = AboutScreenContent(
    upstreamProjects = listOf(
        UpstreamProjectCredit(
            name = "TonexOneController",
            repositoryUrl = "https://github.com/Builty/TonexOneController",
            licenceSummary = "Apache License 2.0 (SPDX: `Apache-2.0`)",
            copyright = "Copyright 2025 Greg Smith",
        ),
        UpstreamProjectCredit(
            name = "tonex_controller",
            repositoryUrl = "https://github.com/vit3k/tonex_controller",
            licenceSummary = "MIT License (SPDX: `MIT`)",
            copyright = "Copyright (c) 2024 vit3k",
        ),
    ),
    licenceTexts = mapOf(
        "TonexOneController" to "Apache License 2.0 full text…",
        "tonex_controller" to "MIT License full text…",
    ),
    fullCreditsMarkdown = "# Credits and Third-Party Attribution\n\n(full CREDITS.md content)",
)

@Preview(showBackground = true)
@Composable
private fun AboutScreenPreview() {
    TonexTheme {
        AboutScreen(
            content = PreviewContent,
            appVersionInfo = AppVersionInfo(versionName = "0.1.0", versionCode = 1),
        )
    }
}
