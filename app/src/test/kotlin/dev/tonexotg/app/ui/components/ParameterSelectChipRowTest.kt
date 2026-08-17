package dev.tonexotg.app.ui.components

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
 * D3 §2.1/§3.2: "tapping a chip *is* the entire interaction" for a known-label `SELECT`
 * parameter — no confirmation, no separate affordance. This pins that a tap on any chip fires
 * [ParameterSelectChipRow]'s [onSelect] with exactly that option, for every option in the row
 * (not just the currently-active one).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ParameterSelectChipRowTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val reverbModels = listOf("Spring 1", "Spring 2", "Spring 3", "Spring 4", "Room", "Plate")

    @Test
    fun tappingAnInactiveChip_selectsIt() {
        var selected = "Plate"

        composeTestRule.setContent {
            TonexTheme {
                ParameterSelectChipRow(
                    options = reverbModels,
                    selected = selected,
                    onSelect = { selected = it },
                    label = { it },
                )
            }
        }

        composeTestRule.onNodeWithTag("selectChipRow.chip.Room").performClick()

        assertEquals("Room", selected)
    }

    @Test
    fun tappingEveryChip_selectsExactlyThatOption() {
        val selections = mutableListOf<String>()

        composeTestRule.setContent {
            TonexTheme {
                ParameterSelectChipRow(
                    options = reverbModels,
                    selected = "Plate",
                    onSelect = { selections.add(it) },
                    label = { it },
                )
            }
        }

        for (model in reverbModels) {
            composeTestRule.onNodeWithTag("selectChipRow.chip.$model").performClick()
        }

        assertEquals(reverbModels, selections)
    }
}
