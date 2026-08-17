package dev.tonexotg.app.ui.components

import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
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
 * D3 §3.2: `SWITCH` parameters are "a one-tap toggle, no sheet at all." These tests pin that a
 * tap anywhere on the row (not just the switch glyph) toggles, and that the toggle glyph itself
 * reflects on/off state via M3's own semantics (not just visually).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ParameterSwitchTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun tappingAnywhereOnRow_togglesState() {
        var checked = false

        composeTestRule.setContent {
            TonexTheme {
                ParameterSwitch(
                    label = "Enable",
                    checked = checked,
                    onCheckedChange = { checked = it },
                )
            }
        }

        composeTestRule.onNodeWithTag("paramSwitch.row").performClick()

        assertEquals(true, checked)
    }

    @Test
    fun toggleGlyph_reflectsCheckedState() {
        composeTestRule.setContent {
            TonexTheme {
                ParameterSwitch(label = "Enable", checked = true, onCheckedChange = {})
            }
        }
        composeTestRule.onNodeWithTag("paramSwitch.toggle").assertIsOn()
    }

    @Test
    fun toggleGlyph_reflectsUncheckedState() {
        composeTestRule.setContent {
            TonexTheme {
                ParameterSwitch(label = "Enable", checked = false, onCheckedChange = {})
            }
        }
        composeTestRule.onNodeWithTag("paramSwitch.toggle").assertIsOff()
    }
}
