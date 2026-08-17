package dev.tonexotg.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Plain JVM tests for [ParameterNumericEntrySheet]'s pure text/clamp helpers — no Robolectric or
 * Compose rendering needed, since none of these functions touch the UI tree. The Compose-level
 * behavior (keypad taps, Set/Cancel wiring) is covered separately by
 * [ParameterNumericEntrySheetTest]; this file is the fast, no-Android-dependency layer underneath
 * it.
 */
class NumericEntryLogicTest {

    @Test
    fun appendKeypadChar_digitsAppendInOrder() {
        var text = ""
        text = appendKeypadChar(text, '6')
        text = appendKeypadChar(text, '4')
        assertEquals("64", text)
    }

    @Test
    fun appendKeypadChar_decimalPointOnEmptyText_seedsLeadingZero() {
        assertEquals("0.", appendKeypadChar("", '.'))
    }

    @Test
    fun appendKeypadChar_secondDecimalPoint_isNoOp() {
        assertEquals("6.4", appendKeypadChar("6.4", '.'))
    }

    @Test
    fun backspaceKeypad_removesLastCharacter() {
        assertEquals("6.", backspaceKeypad("6.4"))
    }

    @Test
    fun backspaceKeypad_onEmptyText_isNoOp() {
        assertEquals("", backspaceKeypad(""))
    }

    @Test
    fun clampEnteredValue_inRange_passesThroughUnchanged() {
        assertEquals(5.5f, clampEnteredValue("5.5", 0f..10f))
    }

    @Test
    fun clampEnteredValue_aboveMax_clampsToMax() {
        assertEquals(10f, clampEnteredValue("99", 0f..10f))
    }

    @Test
    fun clampEnteredValue_belowMin_clampsToMin() {
        assertEquals(-100f, clampEnteredValue("-999", -100f..0f))
    }

    @Test
    fun clampEnteredValue_unparsableText_returnsNull() {
        assertNull(clampEnteredValue("", 0f..10f))
        assertNull(clampEnteredValue(".", 0f..10f))
    }

    @Test
    fun formatInitialValue_respectsDecimalPlaces() {
        assertEquals("6.4", formatInitialValue(6.4f, decimalPlaces = 1))
        assertEquals("-40", formatInitialValue(-40f, decimalPlaces = 0))
    }
}
