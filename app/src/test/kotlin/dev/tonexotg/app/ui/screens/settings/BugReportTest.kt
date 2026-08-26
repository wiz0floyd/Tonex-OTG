package dev.tonexotg.app.ui.screens.settings

import dev.tonexotg.app.ui.screens.about.AppVersionInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exercises [buildBugReportIssueUri] (issue #126) -- the regular-user "Report a bug" deep link
 * built from Settings, distinct from the debug menu's diagnostic dump shortcut (issue #96).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BugReportTest {

    @Test
    fun uri_pointsAtNewIssuePageForThisRepo() {
        val uri = buildBugReportIssueUri(AppVersionInfo(versionName = "0.1.0", versionCode = 42))

        assertEquals("https", uri.scheme)
        assertEquals("github.com", uri.host)
        assertEquals("/wiz0floyd/Tonex-OTG/issues/new", uri.path)
    }

    @Test
    fun body_includesAppVersionAndroidVersionAndDeviceModel() {
        val uri = buildBugReportIssueUri(AppVersionInfo(versionName = "0.1.0", versionCode = 42))

        val body = uri.getQueryParameter("body").orEmpty()
        assertTrue(body.contains("App version: 0.1.0 (42)"))
        assertTrue(body.contains("Android version:"))
        assertTrue(body.contains("Device:"))
    }

    @Test
    fun body_doesNotReferenceOrAttachTheDiagnosticLogFile() {
        // Issue #126's "out of scope": the log-tail-and-attach behavior stays debug-menu-only.
        val uri = buildBugReportIssueUri(AppVersionInfo(versionName = "0.1.0", versionCode = 42))

        val body = uri.getQueryParameter("body").orEmpty()
        assertTrue(!body.contains("log", ignoreCase = true))
    }

    @Test
    fun title_isPrefilledForABugReport() {
        val uri = buildBugReportIssueUri(AppVersionInfo(versionName = "0.1.0", versionCode = 42))

        assertEquals("Bug: ", uri.getQueryParameter("title"))
    }
}
