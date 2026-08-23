package dev.tonexotg.app.ui.screens.presets

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import dev.tonexotg.app.data.alias.DataStorePresetAliasStore
import dev.tonexotg.app.data.alias.InMemoryPreferencesDataStore
import dev.tonexotg.app.ui.theme.TonexTheme
import dev.tonexotg.protocol.ConnectionState
import dev.tonexotg.protocol.PresetIndex
import dev.tonexotg.protocol.PresetSlot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Screen-level integration tests for S16 (issue #21): all 20 presets rendered with real pedal
 * names, the active preset unambiguous in the composed tree, tap-to-load, FR6's live-updates-on-
 * external-change requirement, inline alias editing, and the disconnected-but-not-pretending-to-
 * be-live behaviour. Runs as a plain JVM Robolectric unit test, same pattern as
 * [dev.tonexotg.app.ui.screens.connection.ConnectionStatusScreenTest].
 *
 * The "active preset visually unambiguous... verified on-device at arm's length" half of this
 * story's AC is explicitly a hardware/visual-verification claim this suite cannot make — see the
 * class-level note on that in the issue/PR status comments. What's asserted here is the
 * unambiguous *semantic* signal: exactly one row's checkmark exists, and it's on the row matching
 * [dev.tonexotg.protocol.TonexController.activePreset].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PresetListScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun setContentWithViewModel(controller: FakeTonexController): PresetListViewModel {
        lateinit var viewModel: PresetListViewModel
        composeTestRule.setContent {
            TonexTheme {
                viewModel = rememberPresetListViewModel(
                    controller = controller,
                    aliasStore = DataStorePresetAliasStore(InMemoryPreferencesDataStore()),
                )
                PresetListScreen(viewModel = viewModel, onPresetOpened = {})
            }
        }
        return viewModel
    }

    // ---- All 20 presets load and display real pedal names -------------------------------------

    @Test
    fun allTwentyPresets_renderWithTheirRealPedalNames() {
        val controller = FakeTonexController(initialState = ConnectionState.Ready)
        controller.setPresets(fakePresetInfoList())
        setContentWithViewModel(controller)
        composeTestRule.waitForIdle()

        for (i in 0..19) {
            composeTestRule.onNodeWithTag("presetList").performScrollToIndex(i)
            composeTestRule.onNodeWithText("PRESET %02d".format(i + 1)).assertIsDisplayed()
        }
    }

    // ---- Active preset is unambiguous ----------------------------------------------------------

    @Test
    fun exactlyOneCheckmark_isShown_onTheActivePresetRow() {
        val controller = FakeTonexController(initialState = ConnectionState.Ready)
        controller.setPresets(fakePresetInfoList())
        controller.setActivePreset(PresetIndex(4))
        setContentWithViewModel(controller)
        composeTestRule.waitForIdle()

        composeTestRule.onAllNodesWithText("✓").assertCountEquals(1)
        composeTestRule.onNodeWithTag("presetRow.4").assertIsDisplayed()
    }

    // ---- Tap-to-load --------------------------------------------------------------------------

    @Test
    fun tappingAPresetRow_callsSelectPresetOnTheController() {
        val controller = FakeTonexController(initialState = ConnectionState.Ready)
        controller.setPresets(fakePresetInfoList())
        setContentWithViewModel(controller)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("presetList").performScrollToIndex(9)
        composeTestRule.onNodeWithText("PRESET 10").performClick()
        composeTestRule.waitForIdle()

        assertEquals(listOf(PresetIndex(9)), controller.selectPresetCalls)
    }

    // ---- FR6: footswitch-initiated external changes update the list with no user interaction --

    @Test
    fun anExternalPresetChange_movesTheCheckmark_withNoUserInteraction() {
        val controller = FakeTonexController(initialState = ConnectionState.Ready)
        controller.setPresets(fakePresetInfoList())
        controller.setActivePreset(PresetIndex(0))
        setContentWithViewModel(controller)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("presetRow.0").assertIsDisplayed()

        // Simulate a footswitch press: nothing in this test taps the screen.
        composeTestRule.runOnUiThread {
            runBlocking {
                controller.emitExternalPresetChange(PresetIndex(17))
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("presetList").performScrollToIndex(17)
        composeTestRule.onAllNodesWithText("✓").assertCountEquals(1)
        // No selectPreset call was made by this (or any) view-model action.
        assertEquals(emptyList<PresetIndex>(), controller.selectPresetCalls)
    }

    // ---- Disconnected: shows aliases, does not pretend to be live -----------------------------

    @Test
    fun disconnected_showsANotConnectedBanner_andNoCheckmark() {
        val controller = FakeTonexController(initialState = ConnectionState.Idle)
        setContentWithViewModel(controller)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithContentDescription(
            "Not connected to pedal. This list shows saved local names only and is not live.",
        ).assertIsDisplayed()
        composeTestRule.onAllNodesWithText("✓").assertCountEquals(0)
    }

    @Test
    fun disconnected_stillShowsAPreviouslySetLocalAlias() {
        val controller = FakeTonexController(initialState = ConnectionState.Ready)
        controller.setPresets(fakePresetInfoList())
        val viewModel = setContentWithViewModel(controller)
        composeTestRule.waitForIdle()

        viewModel.setAlias(PresetIndex(2), "My Lead Tone")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("My Lead Tone").assertIsDisplayed()

        // Now disconnect entirely: the pedal name disappears from the controller's own state,
        // but the alias -- which never depended on a connection -- must still show.
        composeTestRule.runOnUiThread {
            runBlocking { controller.disconnect() }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("My Lead Tone").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("✓").assertCountEquals(0)
    }

    // ---- Slot badges (S85 part 2a) --------------------------------------------------------------

    @Test
    fun aPresetAssignedToTwoFootswitchSlots_showsBothBadges() {
        val controller = FakeTonexController(initialState = ConnectionState.Ready)
        controller.setPresets(fakePresetInfoList())
        controller.setSlotAssignments(
            mapOf(PresetSlot.A to PresetIndex(2), PresetSlot.B to PresetIndex(2)),
        )
        setContentWithViewModel(controller)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("presetList").performScrollToIndex(2)
        composeTestRule.onNodeWithContentDescription("Assigned to footswitch A, B").assertIsDisplayed()
        // The description alone doesn't prove the chips themselves rendered -- it's authored on
        // the wrapper Row, a sibling of the forEach that actually emits the badge Text nodes.
        // Scope to this row's subtree (unmerged, since the row's own clickable merges
        // descendants) and assert each chip's own text exists.
        composeTestRule.onNode(
            hasTestTag("presetRow.2").and(hasAnyDescendant(hasText("A"))),
            useUnmergedTree = true,
        ).assertExists()
        composeTestRule.onNode(
            hasTestTag("presetRow.2").and(hasAnyDescendant(hasText("B"))),
            useUnmergedTree = true,
        ).assertExists()
    }

    @Test
    fun noSlotBadge_isShown_whenDisconnectedEvenWithStaleSlotData() {
        val controller = FakeTonexController(initialState = ConnectionState.Idle)
        controller.setSlotAssignments(mapOf(PresetSlot.A to PresetIndex(0)))
        setContentWithViewModel(controller)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithContentDescription("Assigned to footswitch A").assertDoesNotExist()
        composeTestRule.onNode(
            hasTestTag("presetRow.0").and(hasAnyDescendant(hasText("A"))),
            useUnmergedTree = true,
        ).assertDoesNotExist()
    }

    // ---- Assign-to-slot (S85 part 2b) ------------------------------------------------------------

    @Test
    fun tappingTheAssignSlotIcon_opensTheDialog() {
        val controller = FakeTonexController(initialState = ConnectionState.Ready)
        controller.setPresets(fakePresetInfoList())
        setContentWithViewModel(controller)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithContentDescription("Assign preset 01 to a footswitch slot").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Assign preset 1 to a footswitch slot").assertIsDisplayed()
    }

    @Test
    fun tappingSlotB_inTheDialog_callsAssignPresetToSlotWithTheRightSlotAndIndex() {
        val controller = FakeTonexController(initialState = ConnectionState.Ready)
        controller.setPresets(fakePresetInfoList())
        setContentWithViewModel(controller)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("presetList").performScrollToIndex(9)
        composeTestRule.onNodeWithContentDescription("Assign preset 10 to a footswitch slot").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("assignSlotDialog.slot.B").performClick()
        composeTestRule.waitForIdle()

        assertEquals(
            listOf(PresetSlot.B to PresetIndex(9)),
            controller.assignPresetToSlotCalls,
        )
        // The dialog closes immediately on tap (fire-and-forget, no await) -- it must not still
        // be showing after the click.
        composeTestRule.onNodeWithText("Assign preset 10 to a footswitch slot").assertDoesNotExist()
    }

    @Test
    fun theDialog_showsWhichSlotThisPresetAlreadyOccupies() {
        val controller = FakeTonexController(initialState = ConnectionState.Ready)
        controller.setPresets(fakePresetInfoList())
        controller.setSlotAssignments(mapOf(PresetSlot.A to PresetIndex(2)))
        setContentWithViewModel(controller)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("presetList").performScrollToIndex(2)
        composeTestRule.onNodeWithContentDescription("Assign preset 03 to a footswitch slot").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("assignSlotDialog.slot.A")
            .assertIsDisplayed()
        composeTestRule.onNode(
            hasTestTag("assignSlotDialog.slot.A").and(hasAnyDescendant(hasText("Already here"))),
            useUnmergedTree = true,
        ).assertExists()
    }

    @Test
    fun theDialog_showsWhatASwapWouldDisplaceOnAnotherSlot() {
        val controller = FakeTonexController(initialState = ConnectionState.Ready)
        controller.setPresets(fakePresetInfoList())
        controller.setSlotAssignments(mapOf(PresetSlot.C to PresetIndex(5)))
        setContentWithViewModel(controller)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("presetList").performScrollToIndex(0)
        composeTestRule.onNodeWithContentDescription("Assign preset 01 to a footswitch slot").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNode(
            hasTestTag("assignSlotDialog.slot.C").and(hasAnyDescendant(hasText("Will swap with PRESET 06"))),
            useUnmergedTree = true,
        ).assertExists()
    }

    // ---- Inline alias editing -------------------------------------------------------------------

    @Test
    fun editingAnAlias_throughTheDialog_updatesTheRowsDisplayName() {
        val controller = FakeTonexController(initialState = ConnectionState.Ready)
        controller.setPresets(fakePresetInfoList())
        setContentWithViewModel(controller)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithContentDescription("Edit name for preset 01").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Local name").performTextInput("Set Opener")
        composeTestRule.onNodeWithText("Save").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Set Opener").assertIsDisplayed()
    }

    @Test
    fun clearingAnAliasField_toBlank_restoresThePedalName() {
        val controller = FakeTonexController(initialState = ConnectionState.Ready)
        controller.setPresets(fakePresetInfoList())
        val viewModel = setContentWithViewModel(controller)
        viewModel.setAlias(PresetIndex(0), "Custom Name")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Custom Name").assertIsDisplayed()

        composeTestRule.onNodeWithContentDescription("Edit name for preset 01").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Local name").performTextClearance()
        composeTestRule.onNodeWithText("Save").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("PRESET 01").assertIsDisplayed()
        composeTestRule.onNodeWithText("Custom Name").assertDoesNotExist()
    }
}
