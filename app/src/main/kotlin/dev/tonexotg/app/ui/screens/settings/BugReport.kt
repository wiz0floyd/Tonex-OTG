package dev.tonexotg.app.ui.screens.settings

import android.net.Uri
import android.os.Build
import dev.tonexotg.app.ui.screens.about.AppVersionInfo

/**
 * Issue #126: the regular-user "Report a bug" entry point, reachable from Settings directly (not
 * the debug/diagnostics submenu). Builds a `github.com/.../issues/new` deep link pre-filled with
 * app version, Android version, and device model -- the context a maintainer needs to triage a
 * report without asking the user to dig it up themselves -- for [android.content.Intent.ACTION_VIEW]
 * to hand off to the browser/GitHub app.
 *
 * Deliberately does not attach or tail the diagnostic log file the way the debug menu's
 * `buildDebugDumpIssueUri` (issue #96, `ProbeActivity.kt`) does -- that flow stays diagnostic-only
 * per issue #126's "out of scope". A regular user's log rarely has anything a probe session would,
 * and skipping it keeps this entry point simple to reason about (no [ProbeLog] dependency, no
 * `ProbeActivity` coupling from a screen normal users see).
 */
internal fun buildBugReportIssueUri(appVersionInfo: AppVersionInfo): Uri {
    val body = buildString {
        appendLine("**What happened:** _(describe here)_")
        appendLine()
        appendLine("**Steps to reproduce:** _(describe here)_")
        appendLine()
        appendLine("---")
        appendLine("App version: ${appVersionInfo.versionName} (${appVersionInfo.versionCode})")
        appendLine("Android version: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
    }
    return Uri.parse("https://github.com/$GITHUB_REPO/issues/new")
        .buildUpon()
        .appendQueryParameter("title", "Bug: ")
        .appendQueryParameter("body", body)
        .build()
}

private const val GITHUB_REPO = "wiz0floyd/Tonex-OTG"
