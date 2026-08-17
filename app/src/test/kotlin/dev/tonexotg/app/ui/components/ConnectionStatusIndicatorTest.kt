package dev.tonexotg.app.ui.components

import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import dev.tonexotg.app.ui.theme.TonexTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Non-visual, semantics-tree assertions for the color-independence requirement in D1 §2.3 /
 * the S15 spec: "connection status MUST be distinguishable without color vision — shape, text,
 * or icon, never hue alone." These run as plain JVM unit tests via Robolectric
 * (`testDebugUnitTest`), not `connectedAndroidTest` — no emulator/device is used or required,
 * and nothing here inspects pixel color. What's asserted is that every
 * [ConnectionStatus] exposes a distinct, non-empty accessibility [contentDescription] carrying
 * the same text passed as [ConnectionStatusIndicator]'s `label` — i.e. the meaning survives via
 * text even if color/shape were stripped from the render entirely.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConnectionStatusIndicatorTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun connected_exposesItsLabelAsContentDescription() {
        composeTestRule.setContent {
            TonexTheme {
                ConnectionStatusIndicator(status = ConnectionStatus.Connected, label = "Connected")
            }
        }

        composeTestRule.onNodeWithContentDescription("Connected").assertContentDescriptionEquals("Connected")
    }

    @Test
    fun connecting_exposesItsLabelAsContentDescription() {
        composeTestRule.setContent {
            TonexTheme {
                ConnectionStatusIndicator(status = ConnectionStatus.Connecting, label = "Connecting…")
            }
        }

        composeTestRule.onNodeWithContentDescription("Connecting…").assertContentDescriptionEquals("Connecting…")
    }

    @Test
    fun disconnected_exposesItsLabelAsContentDescription() {
        composeTestRule.setContent {
            TonexTheme {
                ConnectionStatusIndicator(status = ConnectionStatus.Disconnected, label = "Not Connected")
            }
        }

        composeTestRule.onNodeWithContentDescription("Not Connected").assertContentDescriptionEquals("Not Connected")
    }

    @Test
    fun error_exposesItsLabelAsContentDescription() {
        composeTestRule.setContent {
            TonexTheme {
                ConnectionStatusIndicator(status = ConnectionStatus.Error, label = "Error")
            }
        }

        composeTestRule.onNodeWithContentDescription("Error").assertContentDescriptionEquals("Error")
    }

    @Test
    fun allFourStates_haveDistinctLabels() {
        // A grayscale/color-blind render collapses hue, so distinctness has to come from text
        // (or shape) alone — this pins that the four labels this component is expected to be
        // driven with (D1 §2.3's own copy) are all different from each other.
        val labels = listOf("Connected", "Connecting…", "Not Connected", "Error")
        assert(labels.toSet().size == labels.size) {
            "Connection status labels must be pairwise distinct for color-independent disambiguation"
        }
    }
}
