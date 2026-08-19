package dev.tonexotg.app.ui.screens.about

import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exercises [LicensingAssets.load] against the real bundled Android assets — i.e. the exact same
 * `syncLicensingAssets` Gradle output (`app/build.gradle.kts`) that a running app reads from —
 * rather than a hand-built fixture. This is what actually pins issue #24's "Content matches
 * CREDITS.md from S3; no divergent second copy to drift" acceptance criterion: the assertions
 * below compare the loaded asset content byte-for-byte against the repo-root files read directly
 * from disk, so a future edit to CREDITS.md/LICENSE/LICENSES/MIT-vit3k.txt that isn't reflected
 * in what ships in the app would fail this test rather than silently drift.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LicensingAssetsTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun fullCreditsMarkdown_matchesRepoRootCreditsMdByteForByte() {
        val content = LicensingAssets.load(context)
        val realCreditsMd = File("../CREDITS.md").readText()

        assertEquals(realCreditsMd, content.fullCreditsMarkdown)
    }

    @Test
    fun apacheLicenceText_matchesRepoRootLicenceByteForByte() {
        val content = LicensingAssets.load(context)
        val realLicenseText = File("../LICENSE").readText()

        val apacheText = content.licenceTexts.getValue("TonexOneController")
        assertEquals(realLicenseText, apacheText)
    }

    @Test
    fun mitLicenceText_matchesRepoRootLicenceByteForByte() {
        val content = LicensingAssets.load(context)
        val realMitText = File("../LICENSES/MIT-vit3k.txt").readText()

        val mitText = content.licenceTexts.getValue("tonex_controller")
        assertEquals(realMitText, mitText)
    }

    @Test
    fun bothUpstreamProjects_arePresentWithCorrectLicenceAndCopyright() {
        val content = LicensingAssets.load(context)
        val byName = content.upstreamProjects.associateBy { it.name }

        assertEquals(setOf("TonexOneController", "tonex_controller"), byName.keys)

        val tonexOneController = byName.getValue("TonexOneController")
        assertTrue(tonexOneController.licenceSummary.contains("Apache", ignoreCase = true))
        assertEquals("Copyright 2025 Greg Smith", tonexOneController.copyright)

        val tonexController = byName.getValue("tonex_controller")
        assertTrue(tonexController.licenceSummary.contains("MIT", ignoreCase = true))
        assertEquals("Copyright (c) 2024 vit3k", tonexController.copyright)
    }

    @Test
    fun licenceTexts_areReachableFullyOffline() {
        // "Reachable in-app, offline" (NFR3): both full licence texts must come from bundled
        // assets only, never a network fetch. There's no HTTP client anywhere in this call path,
        // but we can at least confirm both texts are real, non-trivial, self-contained license
        // bodies rather than e.g. a URL-only stub.
        val content = LicensingAssets.load(context)

        val apacheText = content.licenceTexts.getValue("TonexOneController")
        assertTrue(apacheText.contains("Apache License"))
        assertTrue(apacheText.length > 500)

        val mitText = content.licenceTexts.getValue("tonex_controller")
        assertTrue(mitText.contains("MIT License") || mitText.contains("Permission is hereby granted"))
        assertTrue(mitText.length > 100)
    }
}
