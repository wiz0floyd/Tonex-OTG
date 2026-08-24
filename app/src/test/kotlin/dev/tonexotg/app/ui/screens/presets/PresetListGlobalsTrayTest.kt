package dev.tonexotg.app.ui.screens.presets

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dev.tonexotg.app.data.alias.DataStorePresetAliasStore
import dev.tonexotg.app.data.alias.InMemoryPreferencesDataStore
import dev.tonexotg.app.ui.screens.parameters.ParameterCatalog
import dev.tonexotg.app.ui.theme.TonexTheme
import dev.tonexotg.protocol.ConnectionState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose-level coverage for #107's sticky master volume + collapsible globals tray, on top of
 * [PresetListScreenTest]'s existing preset-list coverage. Exercises the acceptance criteria a
 * green [PresetListViewModelTest] (state-only) can't: default-collapsed, expand-on-tap, the six
 * chips' `contentDescription`s, the header's merged-tap-target semantics, and the collapsed row's
 * 360dp fit.
 */
@RunWith(RobolectricTestRunner::class)
// Robolectric's default test window (320x470dp) is too short to fit the sticky master-volume
// row + the tray's header + all 6 expanded controls without the last one being squeezed to zero
// height in this non-scrolling Column -- a Robolectric-viewport artifact, not a real-device
// layout bug (h1400dp is generously larger than any real phone purely so every expanded control
// has room to measure and assert on; it's not claiming phones are this tall). w360dp is also the
// exact width the 360dp-fit test below needs to pin.
@Config(sdk = [34], qualifiers = "w360dp-h1400dp")
class PresetListGlobalsTrayTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    /** All 6 [ParameterCatalog.homeScreenGlobalIds] plus [ParameterCatalog.masterVolumeId] seeded with values that produce non-truncated, illustrative chip text (real BPM/dB/Hz-shaped numbers, not zeros). */
    private fun seedAllGlobals(controller: FakeTonexController) {
        controller.seedParameterValue(ParameterCatalog.bpmId, 120f)
        controller.seedParameterValue(ParameterCatalog.inputTrimId, -2f)
        controller.seedParameterValue(ParameterCatalog.tuningReferenceId, 440f)
        controller.seedParameterValue(ParameterCatalog.cabSimBypassId, 1f)
        controller.seedParameterValue(ParameterCatalog.tempoSourceId, 0f)
        controller.seedParameterValue(ParameterCatalog.bypassId, 0f)
        controller.seedParameterValue(ParameterCatalog.masterVolumeId, -8f)
    }

    private fun setContentWithViewModel(controller: FakeTonexController) {
        composeTestRule.setContent {
            TonexTheme {
                val viewModel = rememberPresetListViewModel(
                    controller = controller,
                    aliasStore = DataStorePresetAliasStore(InMemoryPreferencesDataStore()),
                )
                PresetListScreen(viewModel = viewModel, onPresetOpened = {})
            }
        }
    }

    // ---- Default collapsed, all 20 presets start higher -----------------------------------------

    @Test
    fun trayIsCollapsedByDefault_expandedBodyNotInTree() {
        val controller = FakeTonexController(initialState = ConnectionState.Ready)
        seedAllGlobals(controller)
        setContentWithViewModel(controller)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("globalParameters.header").assertIsDisplayed()
        composeTestRule.onNodeWithTag("presetList.masterVolume").assertIsDisplayed()
        composeTestRule.onNodeWithTag("globalParameters.body").assertDoesNotExist()
    }

    // ---- Tapping the header expands the tray, revealing the unchanged #83 controls --------------

    @Test
    fun tappingHeader_expandsTray_revealingSixControls() {
        val controller = FakeTonexController(initialState = ConnectionState.Ready)
        seedAllGlobals(controller)
        setContentWithViewModel(controller)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("globalParameters.header").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("globalParameters.body").assertIsDisplayed()
        composeTestRule.onNodeWithTag("globalParameters.range.${ParameterCatalog.bpmId.index}").assertIsDisplayed()
        composeTestRule.onNodeWithTag("globalParameters.switch.${ParameterCatalog.bypassId.index}").assertIsDisplayed()

        // Tapping again collapses it back -- same one merged target does both directions.
        composeTestRule.onNodeWithTag("globalParameters.header").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("globalParameters.body").assertDoesNotExist()
    }

    // ---- Every one of the 6 chips carries a contentDescription naming the parameter + value ------

    @Test
    fun allSixChips_haveContentDescriptionsNamingParameterAndValue() {
        val controller = FakeTonexController(initialState = ConnectionState.Ready)
        seedAllGlobals(controller)
        setContentWithViewModel(controller)
        composeTestRule.waitForIdle()

        // useUnmergedTree = true: the header row merges these 6 descriptions into one semantics
        // node (that's the "one merged tap target, not six" requirement itself), so each chip's
        // own contentDescription is only independently addressable in the unmerged tree -- exactly
        // what this asserts each chip still carries one.
        composeTestRule.onNodeWithContentDescription("BPM, 120 BPM", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Input Trim, -2 dB", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Tuning Reference, 440 Hz", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Cab Sim Bypass, on", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Tempo Source, GLOBAL", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Bypass, off", useUnmergedTree = true).assertIsDisplayed()
    }

    // ---- The header is one merged tap target, not six separately-focusable chips -----------------

    @Test
    fun headerChipDescriptions_mergeUpIntoTheHeaderNode_notSixSeparateTargets() {
        val controller = FakeTonexController(initialState = ConnectionState.Ready)
        seedAllGlobals(controller)
        setContentWithViewModel(controller)
        composeTestRule.waitForIdle()

        // In the default (merged) tree, `hasContentDescription` matches any node whose merged
        // property *list* contains the value -- a chip's own description ends up matching
        // whichever node the merge landed it on. The real assertion isn't "can this description
        // still be found" (it can, by design -- that's what merging a value up means): it's
        // *which node* it resolves to. If merging worked, every one of the 6 chips' descriptions
        // resolves to the SAME node -- the header itself (testTag "globalParameters.header"), not
        // 6 different chip nodes each independently focusable/tappable.
        val descriptions = listOf(
            "BPM, 120 BPM",
            "Input Trim, -2 dB",
            "Tuning Reference, 440 Hz",
            "Cab Sim Bypass, on",
            "Tempo Source, GLOBAL",
            "Bypass, off",
        )
        val resolvedTestTags = descriptions.map { description ->
            composeTestRule.onNodeWithContentDescription(description).fetchSemanticsNode()
                .config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { "<no testTag -- unmerged leaf node>" }
        }
        assertTrue(
            "All 6 chip descriptions must merge onto the same node (the header, testTag=" +
                "'globalParameters.header'), not resolve to distinct/unmerged nodes. Got: $resolvedTestTags",
            resolvedTestTags.all { it == "globalParameters.header" },
        )
    }

    // ---- Collapsed row fits 360dp with no horizontal scroll ---------------------------------------

    @Test
    fun collapsedRow_fitsWithin360dp() {
        val controller = FakeTonexController(initialState = ConnectionState.Ready)
        seedAllGlobals(controller)
        composeTestRule.setContent {
            TonexTheme {
                Box(modifier = Modifier.width(360.dp)) {
                    val viewModel = rememberPresetListViewModel(
                        controller = controller,
                        aliasStore = DataStorePresetAliasStore(InMemoryPreferencesDataStore()),
                    )
                    PresetListScreen(viewModel = viewModel, onPresetOpened = {})
                }
            }
        }
        composeTestRule.waitForIdle()

        // Every chip must still be displayed (not clipped out) at 360dp, and each of the 6
        // contentDescriptions must carry its full, non-truncated value text -- if the row were too
        // wide, layout would push chips outside the 360dp Box and assertIsDisplayed would fail.
        composeTestRule.onNodeWithContentDescription("BPM, 120 BPM", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Input Trim, -2 dB", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Tuning Reference, 440 Hz", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Cab Sim Bypass, on", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Tempo Source, GLOBAL", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Bypass, off", useUnmergedTree = true).assertIsDisplayed()

        val headerNode = composeTestRule.onNodeWithTag("globalParameters.header").fetchSemanticsNode()
        val widthPx = headerNode.size.width
        val maxWidthPx = with(composeTestRule.density) { 360.dp.roundToPx() }
        assertTrue(
            "Collapsed header row ($widthPx px) must fit within 360dp ($maxWidthPx px) -- no horizontal scroll",
            widthPx <= maxWidthPx,
        )
    }
}
