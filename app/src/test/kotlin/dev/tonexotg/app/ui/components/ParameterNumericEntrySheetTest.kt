package dev.tonexotg.app.ui.components

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.tonexotg.app.ui.theme.TonexTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * D3 §3.1's binding rule under test: typing is never blocked for being out of range, but `Set`
 * clamps into the parameter's registered range before firing — the same clamp-not-reject policy
 * `ParameterWriteMessage.kt` documents at the wire level. These tests drive
 * [ParameterNumericEntrySheetContent] directly (not the [ParameterNumericEntrySheet] wrapper) —
 * see that composable's KDoc for why: it isolates the clamp/keypad logic under test from
 * [androidx.compose.material3.ModalBottomSheet]'s own dialog/window machinery.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ParameterNumericEntrySheetTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun typingAboveMax_thenSet_clampsToMax() {
        var setValue: Float? = null

        composeTestRule.setContent {
            TonexTheme {
                ParameterNumericEntrySheetContent(
                    parameterName = "Gain",
                    initialValue = 6.4f,
                    valueRange = 0f..10f,
                    onDismiss = {},
                    onSet = { setValue = it },
                )
            }
        }

        // Backspace the pre-filled "6.4" down to nothing, then type an out-of-range "99".
        repeat(5) { composeTestRule.onNodeWithTag("numericEntry.key.⌫").performClick() }
        composeTestRule.onNodeWithTag("numericEntry.key.9").performClick()
        composeTestRule.onNodeWithTag("numericEntry.key.9").performClick()
        composeTestRule.onNodeWithText("99", substring = true).assertExists()

        composeTestRule.onNodeWithTag("numericEntry.setButton").performClick()

        assertEquals(10f, setValue)
    }

    @Test
    fun typingBelowMin_thenSet_clampsToMin() {
        var setValue: Float? = null

        composeTestRule.setContent {
            TonexTheme {
                ParameterNumericEntrySheetContent(
                    parameterName = "Noise Gate Threshold",
                    initialValue = -40f,
                    valueRange = -100f..0f,
                    unit = "dB",
                    decimalPlaces = 0,
                    onDismiss = {},
                    onSet = { setValue = it },
                )
            }
        }

        // The keypad has no minus key (D3 §3.1 — see class KDoc): backspacing the pre-filled
        // "-40" down and typing an in-range-looking "5" still lands at a non-negative value,
        // which this -100..0 range then clamps to its own max, 0 — the documented, faithful-to-
        // spec limitation of a signless keypad against a negative-only range.
        repeat(5) { composeTestRule.onNodeWithTag("numericEntry.key.⌫").performClick() }
        composeTestRule.onNodeWithTag("numericEntry.key.5").performClick()

        composeTestRule.onNodeWithTag("numericEntry.setButton").performClick()

        assertEquals(0f, setValue)
    }

    @Test
    fun typingInRangeValue_thenSet_passesThroughUnclamped() {
        var setValue: Float? = null

        composeTestRule.setContent {
            TonexTheme {
                ParameterNumericEntrySheetContent(
                    parameterName = "Gain",
                    initialValue = 6.4f,
                    valueRange = 0f..10f,
                    onDismiss = {},
                    onSet = { setValue = it },
                )
            }
        }

        repeat(5) { composeTestRule.onNodeWithTag("numericEntry.key.⌫").performClick() }
        composeTestRule.onNodeWithTag("numericEntry.key.5").performClick()
        composeTestRule.onNodeWithTag("numericEntry.key..").performClick()
        composeTestRule.onNodeWithTag("numericEntry.key.5").performClick()

        composeTestRule.onNodeWithTag("numericEntry.setButton").performClick()

        assertEquals(5.5f, setValue)
    }

    @Test
    fun cancel_firesNoWrite() {
        var setValue: Float? = null
        var dismissed = false

        composeTestRule.setContent {
            TonexTheme {
                ParameterNumericEntrySheetContent(
                    parameterName = "Gain",
                    initialValue = 6.4f,
                    valueRange = 0f..10f,
                    onDismiss = { dismissed = true },
                    onSet = { setValue = it },
                )
            }
        }

        // Type something first, to prove Cancel discards even a would-be-valid in-progress edit.
        composeTestRule.onNodeWithTag("numericEntry.key.9").performClick()
        composeTestRule.onNodeWithTag("numericEntry.cancelButton").performClick()

        assertNull(setValue)
        assertEquals(true, dismissed)
    }

    @Test
    fun emptyEntry_disablesSetButton() {
        composeTestRule.setContent {
            TonexTheme {
                ParameterNumericEntrySheetContent(
                    parameterName = "Gain",
                    initialValue = 6.4f,
                    valueRange = 0f..10f,
                    onDismiss = {},
                    onSet = {},
                )
            }
        }

        repeat(6) { composeTestRule.onNodeWithTag("numericEntry.key.⌫").performClick() }

        composeTestRule.onNodeWithTag("numericEntry.setButton").assertIsNotEnabled()
    }

    @Test
    fun sheetPrefillsTheCurrentValue_perD2Screen2b() {
        composeTestRule.setContent {
            TonexTheme {
                ParameterNumericEntrySheetContent(
                    parameterName = "Gain",
                    initialValue = 6.4f,
                    valueRange = 0f..10f,
                    onDismiss = {},
                    onSet = {},
                )
            }
        }

        composeTestRule.onNodeWithText("6.4", substring = true).assertExists()
    }
}
