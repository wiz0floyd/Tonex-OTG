package dev.tonexotg.app.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dev.tonexotg.app.ui.theme.TonexTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * D3 §3's whole architectural point is that the slider band and the numeric-entry chip are two
 * physically separate, non-overlapping tap targets on [ParameterControl]'s card — "no touch ever
 * has to be disambiguated between drag and open-the-keypad" only holds if their hit regions
 * genuinely never overlap. These tests pin that geometrically (not just "they're in different
 * `Row`s in the source", which a future edit could quietly break) and pin that a tap on the chip
 * only ever fires [ParameterControl]'s numeric-entry callback, never the slider's value callback.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ParameterControlTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun sliderBandAndValueChip_boundsDoNotOverlap() {
        composeTestRule.setContent {
            TonexTheme {
                ParameterControl(
                    label = "Gain",
                    value = 6.4f,
                    valueRange = 0f..10f,
                    valueText = "6.4",
                    onValueChange = {},
                    onValueTextClick = {},
                    modifier = Modifier.padding(16.dp),
                )
            }
        }

        val sliderBounds = composeTestRule.onNodeWithTag("paramControl.sliderBand").fetchSemanticsNode().boundsInRoot
        val chipBounds = composeTestRule.onNodeWithTag("paramControl.valueChip").fetchSemanticsNode().boundsInRoot

        // Two axis-aligned rectangles do NOT overlap iff one is entirely to the left/right, or
        // entirely above/below, the other.
        val nonOverlapping = sliderBounds.right <= chipBounds.left ||
            chipBounds.right <= sliderBounds.left ||
            sliderBounds.bottom <= chipBounds.top ||
            chipBounds.bottom <= sliderBounds.top

        assertTrue(
            "slider band $sliderBounds must not overlap value chip $chipBounds",
            nonOverlapping,
        )
    }

    @Test
    fun tappingValueChip_firesOnlyTheNumericEntryCallback_neverTheSliderCallback() {
        var numericEntryTaps = 0
        var sliderValueChanges = 0

        composeTestRule.setContent {
            TonexTheme {
                ParameterControl(
                    label = "Gain",
                    value = 6.4f,
                    valueRange = 0f..10f,
                    valueText = "6.4",
                    onValueChange = { sliderValueChanges++ },
                    onValueTextClick = { numericEntryTaps++ },
                    modifier = Modifier.padding(16.dp),
                )
            }
        }

        composeTestRule.onNodeWithTag("paramControl.valueChip").performClick()

        assertEquals(1, numericEntryTaps)
        assertEquals(0, sliderValueChanges)
    }

    @Test
    fun valueChipWithNoCallback_isPresentButInert() {
        // D2/D3: the readout must still render even before a caller wires up exact entry (e.g. in
        // a not-yet-interactive preview) — it just doesn't crash or do anything on tap.
        composeTestRule.setContent {
            TonexTheme {
                ParameterControl(
                    label = "Reverb Mix",
                    value = 28f,
                    valueRange = 0f..100f,
                    valueText = "28%",
                    onValueChange = {},
                    modifier = Modifier.padding(16.dp),
                )
            }
        }

        composeTestRule.onNodeWithTag("paramControl.valueChip").assertExists()
    }
}
