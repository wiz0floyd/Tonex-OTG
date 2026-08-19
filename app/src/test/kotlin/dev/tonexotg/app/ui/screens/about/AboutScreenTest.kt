package dev.tonexotg.app.ui.screens.about

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.tonexotg.app.ui.theme.TonexTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Non-visual, semantics-tree assertions for the About/credits screen (S19, issue #24). Verifies
 * the content-driven overload of [AboutScreen] renders what #24's acceptance criteria require:
 * both upstream projects with their correct licence/copyright, both full licence texts reachable
 * (via a button tap, no network involved), app version, and the tested-firmware-version line
 * rendered as "pending hardware verification" rather than a fabricated value while #25/S20 is
 * still open.
 *
 * [AboutScreen]'s content lives in a `verticalScroll` column taller than Robolectric's default
 * window, so tests that tap a button below the fold call `performScrollTo()` first — a synthetic
 * tap issued for coordinates outside the window's actual bounds is silently swallowed (no
 * exception, no state change), which is what a bare `performClick()` on those nodes ran into
 * before this was added.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AboutScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val sampleContent = AboutScreenContent(
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
            "TonexOneController" to "APACHE LICENSE FULL TEXT MARKER",
            "tonex_controller" to "MIT LICENSE FULL TEXT MARKER",
        ),
        fullCreditsMarkdown = "# Credits and Third-Party Attribution\n\n(full CREDITS.md content)",
    )

    private val sampleAppVersionInfo = AppVersionInfo(versionName = "0.1.0", versionCode = 1)

    @Test
    fun bothUpstreamProjects_areCreditedWithLicenceAndCopyright() {
        composeTestRule.setContent {
            TonexTheme {
                AboutScreen(content = sampleContent, appVersionInfo = sampleAppVersionInfo)
            }
        }

        composeTestRule.onNodeWithText("TonexOneController").assertExists()
        composeTestRule.onNodeWithText("Apache License 2.0 (SPDX: `Apache-2.0`)").assertExists()
        composeTestRule.onNodeWithText("Copyright 2025 Greg Smith").assertExists()

        composeTestRule.onNodeWithText("tonex_controller").assertExists()
        composeTestRule.onNodeWithText("MIT License (SPDX: `MIT`)").assertExists()
        composeTestRule.onNodeWithText("Copyright (c) 2024 vit3k").assertExists()
    }

    @Test
    fun apacheFullLicenceText_isReachableViaButtonTap_noNetworkInvolved() {
        composeTestRule.setContent {
            TonexTheme {
                AboutScreen(content = sampleContent, appVersionInfo = sampleAppVersionInfo)
            }
        }

        // Full licence text is not on screen until the user asks for it...
        composeTestRule.onNodeWithText("APACHE LICENSE FULL TEXT MARKER").assertDoesNotExist()

        // ...but a single in-app tap (no network call anywhere in this path) reveals it.
        composeTestRule.onAllNodesWithText("View full licence text").assertCountEquals(2)
        composeTestRule.onAllNodesWithText("View full licence text")[0].performScrollTo().performClick()
        composeTestRule.onNodeWithText("APACHE LICENSE FULL TEXT MARKER").assertExists()
    }

    @Test
    fun mitFullLicenceText_isAlsoReachableViaButtonTap() {
        composeTestRule.setContent {
            TonexTheme {
                AboutScreen(content = sampleContent, appVersionInfo = sampleAppVersionInfo)
            }
        }

        composeTestRule.onNodeWithText("MIT LICENSE FULL TEXT MARKER").assertDoesNotExist()

        composeTestRule.onAllNodesWithText("View full licence text")[1].performScrollTo().performClick()
        composeTestRule.onNodeWithText("MIT LICENSE FULL TEXT MARKER").assertExists()
    }

    @Test
    fun appVersion_isDisplayed() {
        composeTestRule.setContent {
            TonexTheme {
                AboutScreen(content = sampleContent, appVersionInfo = sampleAppVersionInfo)
            }
        }

        composeTestRule.onNodeWithText("Version 0.1.0 (1)").assertExists()
    }

    @Test
    fun testedFirmware_defaultsToPendingVerification_neverFabricatesAVersion() {
        composeTestRule.setContent {
            TonexTheme {
                AboutScreen(content = sampleContent, appVersionInfo = sampleAppVersionInfo)
            }
        }

        composeTestRule
            .onNodeWithText("Tested pedal firmware: pending hardware verification (see issue #25)")
            .assertExists()
    }

    @Test
    fun testedFirmware_rendersARealVersionOnceVerified() {
        composeTestRule.setContent {
            TonexTheme {
                AboutScreen(
                    content = sampleContent,
                    appVersionInfo = sampleAppVersionInfo,
                    firmwareInfo = TestedFirmwareInfo.Verified("1.2.3"),
                )
            }
        }

        composeTestRule.onNodeWithText("Tested pedal firmware: 1.2.3").assertExists()
    }

    @Test
    fun fullCreditsMd_isReachableViaButtonTap() {
        composeTestRule.setContent {
            TonexTheme {
                AboutScreen(content = sampleContent, appVersionInfo = sampleAppVersionInfo)
            }
        }

        composeTestRule.onNodeWithText(sampleContent.fullCreditsMarkdown).assertDoesNotExist()
        composeTestRule.onNodeWithText("View full CREDITS.md").performScrollTo().performClick()
        composeTestRule.onNodeWithText(sampleContent.fullCreditsMarkdown).assertExists()
    }
}
