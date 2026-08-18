package dev.tonexotg.app.ui.screens.about

import android.content.Context
import android.os.Build

/**
 * Everything the About/credits screen (S19, issue #24) needs, sourced at runtime from the
 * repo-root `CREDITS.md` and licence files that `app/build.gradle.kts`'s `syncLicensingAssets`
 * task bundles as Android assets — never a second, hand-maintained copy of that content baked
 * into Kotlin source.
 *
 * @property upstreamProjects Parsed from `CREDITS.md`'s "Upstream projects" section.
 * @property licenceTexts Full licence text per [UpstreamProjectCredit.name], for the "full
 *   licence text reachable in-app, offline" acceptance criterion (NFR3 — no network calls).
 * @property fullCreditsMarkdown The complete, unmodified `CREDITS.md` contents, so the screen can
 *   also offer the full attribution document verbatim rather than only the parsed summary.
 */
data class AboutScreenContent(
    val upstreamProjects: List<UpstreamProjectCredit>,
    val licenceTexts: Map<String, String>,
    val fullCreditsMarkdown: String,
)

/** App version, read from the installed package rather than a separately maintained constant. */
data class AppVersionInfo(
    val versionName: String,
    val versionCode: Long,
)

/**
 * Reads the bundled `CREDITS.md` and upstream licence texts from Android assets and assembles
 * [AboutScreenContent]. Per the house "fail fast and loud" philosophy (see `CLAUDE.md`), this
 * throws rather than silently falling back if a licence can't be matched to a known licence
 * family — a screen that silently drops an upstream project's licence text is worse than one that
 * crashes loudly during development.
 */
object LicensingAssets {

    private const val CREDITS_ASSET_PATH = "CREDITS.md"
    private const val APACHE_LICENCE_ASSET_PATH = "LICENSE-APACHE-2.0.txt"
    private const val MIT_LICENCE_ASSET_PATH = "MIT-vit3k.txt"

    fun load(context: Context): AboutScreenContent {
        val creditsMarkdown = readAsset(context, CREDITS_ASSET_PATH)
        val apacheLicenceText = readAsset(context, APACHE_LICENCE_ASSET_PATH)
        val mitLicenceText = readAsset(context, MIT_LICENCE_ASSET_PATH)

        val upstreamProjects = CreditsParser.parseUpstreamProjects(creditsMarkdown)
        check(upstreamProjects.isNotEmpty()) {
            "Parsed zero upstream projects out of $CREDITS_ASSET_PATH — CreditsParser is out of " +
                "sync with the file's structure, or the bundled asset is empty/stale."
        }

        val licenceTexts = upstreamProjects.associate { project ->
            val licenceText = when {
                project.licenceSummary.contains("Apache", ignoreCase = true) -> apacheLicenceText
                project.licenceSummary.contains("MIT", ignoreCase = true) -> mitLicenceText
                else -> error(
                    "Unrecognized licence \"${project.licenceSummary}\" for upstream project " +
                        "\"${project.name}\" — add a matching branch here and bundle its full " +
                        "licence text as an asset before displaying it.",
                )
            }
            project.name to licenceText
        }

        return AboutScreenContent(
            upstreamProjects = upstreamProjects,
            licenceTexts = licenceTexts,
            fullCreditsMarkdown = creditsMarkdown,
        )
    }

    private fun readAsset(context: Context, path: String): String =
        context.assets.open(path).bufferedReader().use { it.readText() }
}

/** Reads the running app's own version name/code via [android.content.pm.PackageManager]. */
fun loadAppVersionInfo(context: Context): AppVersionInfo {
    @Suppress("DEPRECATION")
    val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
    val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        packageInfo.longVersionCode
    } else {
        @Suppress("DEPRECATION")
        packageInfo.versionCode.toLong()
    }
    return AppVersionInfo(
        versionName = packageInfo.versionName.orEmpty(),
        versionCode = versionCode,
    )
}
