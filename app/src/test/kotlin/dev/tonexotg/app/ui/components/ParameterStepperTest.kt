package dev.tonexotg.app.ui.components

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dev.tonexotg.app.ui.theme.TonexTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * D3 §3.2: the stepper for unlabeled `SELECT` options increments/decrements by exactly 1 and
 * clamps at the range's bounds — no wraparound, no jump-by-more-than-one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ParameterStepperTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun incrementButton_increasesByExactlyOne() {
        var value = 4

        composeTestRule.setContent {
            TonexTheme {
                ParameterStepper(label = "Sync Division", value = value, range = 0..17, onValueChange = { value = it })
            }
        }

        composeTestRule.onNodeWithTag("paramStepper.increment").performClick()

        assertEquals(5, value)
    }

    @Test
    fun decrementButton_decreasesByExactlyOne() {
        var value = 4

        composeTestRule.setContent {
            TonexTheme {
                ParameterStepper(label = "Sync Division", value = value, range = 0..17, onValueChange = { value = it })
            }
        }

        composeTestRule.onNodeWithTag("paramStepper.decrement").performClick()

        assertEquals(3, value)
    }

    @Test
    fun atMinimum_decrementButtonIsDisabled_noWraparound() {
        composeTestRule.setContent {
            TonexTheme {
                ParameterStepper(label = "Mic 1", value = 0, range = 0..2, onValueChange = {})
            }
        }

        composeTestRule.onNodeWithTag("paramStepper.decrement").assertIsNotEnabled()
    }

    @Test
    fun atMaximum_incrementButtonIsDisabled_noWraparound() {
        composeTestRule.setContent {
            TonexTheme {
                ParameterStepper(label = "Mic 1", value = 2, range = 0..2, onValueChange = {})
            }
        }

        composeTestRule.onNodeWithTag("paramStepper.increment").assertIsNotEnabled()
    }

    @Test
    fun currentValue_isDisplayed() {
        composeTestRule.setContent {
            TonexTheme {
                ParameterStepper(label = "Sync Division", value = 9, range = 0..17, onValueChange = {})
            }
        }

        composeTestRule.onNodeWithTag("paramStepper.value").assertExists()
    }
}
