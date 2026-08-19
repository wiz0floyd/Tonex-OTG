package dev.tonexotg.app.ui.screens.parameters

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import dev.tonexotg.app.ui.theme.TonexTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A render smoke test for [ParameterEditorScreen] — this is the first real screen assembled from
 * the S15 component library, so the thing most worth pinning here is simply "the whole tree
 * composes against a real [ParameterEditorViewModel] without crashing," plus that its quick tier,
 * "All Parameters" section, and full-tier accordion toggle are actually present and interactive.
 * Individual control behavior (clamping, throttling, model-switch disclosure, etc.) is already
 * covered by [ParameterEditorViewModelTest] and each S15 component's own test; this file
 * deliberately does not re-duplicate that at the Compose layer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ParameterEditorScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun newViewModel(controller: FakeTonexController = FakeTonexController()): ParameterEditorViewModel {
        // Dispatchers.Unconfined so the view model's combine/stateIn machinery (and the
        // throttler's per-parameter workers) run synchronously inline with each call, matching
        // what Robolectric's own synchronous composeTestRule expects - no virtual-time scheduler
        // pumping needed here, unlike ParameterEditorViewModelTest's runTest-based tests.
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        return ParameterEditorViewModel(controller, scope)
    }

    /**
     * The screen's content lives in a [androidx.compose.foundation.lazy.LazyColumn]
     * (`"parameterEditor.scrollList"`), which only composes what's actually visible in
     * Robolectric's small default viewport — every assertion/interaction on content past the
     * first couple of quick-tier cards needs to scroll that node into view first, via this
     * helper, rather than asserting on it directly.
     */
    private fun scrollTo(matcher: androidx.compose.ui.test.SemanticsMatcher) {
        composeTestRule.onNodeWithTag("parameterEditor.scrollList").performScrollToNode(matcher)
    }

    @Test
    fun rendersQuickTierAndAllParametersSectionWithoutCrashing() {
        val viewModel = newViewModel()

        composeTestRule.setContent {
            TonexTheme {
                ParameterEditorScreen(viewModel)
            }
        }

        // The bottom dock lives outside the scrolling list (Scaffold's bottomBar) and is always visible.
        composeTestRule.onNodeWithTag("masterVolume.dock").assertExists()

        composeTestRule.onNodeWithText("Gain").assertExists()

        scrollTo(hasText("Delay Mix"))
        composeTestRule.onNodeWithText("Delay Mix").assertExists()

        scrollTo(hasText("All Parameters"))
        composeTestRule.onNodeWithText("All Parameters").assertExists()

        scrollTo(hasTestTag("parameterEditor.revertButton"))
        composeTestRule.onNodeWithTag("parameterEditor.revertButton").assertExists()

        viewModel.onCleared()
    }

    @Test
    fun expandingACategory_revealsItsRows() {
        val viewModel = newViewModel()

        composeTestRule.setContent {
            TonexTheme {
                ParameterEditorScreen(viewModel)
            }
        }

        scrollTo(hasTestTag("category.header.Noise Gate"))
        // Collapsed by default (D3 §1.3) - the row shouldn't exist yet.
        composeTestRule.onNodeWithTag("paramRow.switch.0").assertDoesNotExist() // NOISE_GATE_POST, first row of "Noise Gate"

        composeTestRule.onNodeWithTag("category.header.Noise Gate").performClick()

        composeTestRule.onNodeWithTag("paramRow.switch.0").assertExists()

        viewModel.onCleared()
    }

    @Test
    fun tappingRevertButton_opensTheConfirmationDialog() {
        val viewModel = newViewModel()

        composeTestRule.setContent {
            TonexTheme {
                ParameterEditorScreen(viewModel)
            }
        }

        scrollTo(hasTestTag("parameterEditor.revertButton"))
        composeTestRule.onNodeWithTag("parameterEditor.revertButton").performClick()
        composeTestRule.onNodeWithText("Revert to snapshot?").assertExists()
        composeTestRule.onNodeWithText("Revert Anyway").assertExists()

        viewModel.onCleared()
    }
}
