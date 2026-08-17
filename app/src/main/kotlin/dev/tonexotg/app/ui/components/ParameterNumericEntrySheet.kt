package dev.tonexotg.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.tonexotg.app.ui.theme.TonexTheme
import dev.tonexotg.app.ui.theme.minTouchTarget
import java.util.Locale

/**
 * The exact numeric-entry bottom sheet for `RANGE`-type parameters (D3 §3.1, D2 screen 2b) —
 * reached only via [ParameterControl]'s numeric value chip, never via the slider band (D3 §3).
 *
 * Structure, per D3 §3.1 / D2 screen 2b: a grabber (M3's [ModalBottomSheet] default drag handle —
 * not suppressed here, so it's present for free), [parameterName], a large numeric field
 * pre-filled with [initialValue] and a caller-supplied [unit] suffix, a 3-column keypad (`1`-`9`,
 * `.`, `0`, `⌫`), and two [dev.tonexotg.app.ui.theme.TonexTouchTargets.min] (64dp) actions: outline
 * `Cancel` and accent-filled `Set`. **Every key — the twelve keypad keys and the two action
 * buttons — is 64dp**, not the 48dp chrome floor: D2 screen 2b's own annotation is explicit that
 * "dialing in an exact value fast between songs is a mid-performance-adjacent action," so this is
 * the one keypad in the app sized like a performance control, not like settings chrome.
 *
 * ## Clamp-not-reject (D3 §3.1)
 *
 * Typing is never blocked for being out of range — every digit/`.`/backspace key always applies.
 * Validation happens only at `Set`, where the typed value is clamped to [valueRange] before
 * [onSet] fires. This deliberately mirrors the wire-level policy `ParameterWriteMessage.kt`
 * documents ("an out-of-range value has one unambiguous, safe interpretation — the nearest
 * in-range value") — this sheet does not invent a stricter or different policy than the write
 * path it feeds. `Set` is disabled only while the typed text cannot parse to a number at all
 * (the player backspaced everything, or down to a lone `.`) — that's "not yet a value," not "an
 * out-of-range value," so it's not something clamping has an answer for.
 *
 * `Cancel` — and dismissing the sheet any other way (scrim tap, swipe-down) — discards with no
 * write: [onSet] is never called, only [onDismiss].
 *
 * ## Keypad has no minus key — a faithful-to-spec limitation, not silently patched
 *
 * D3 §3.1 specifies the keypad literally as "`1`-`9`, `.`, `0`, `⌫`" — eleven keys, no sign key —
 * even though some `RANGE` parameters have negative-only or negative-spanning ranges (e.g.
 * Noise Gate Threshold −100..0dB, Master Volume −40..+3dB). Typing on this sheet for such a
 * parameter can therefore only reach the non-negative portion of its range (or clamp to 0 if the
 * range doesn't include 0). This component implements D3's keypad exactly as specified rather
 * than inventing an unspec'd sign key; flagged here per this project's "don't silently invent a
 * different policy" convention, for the product owner to revisit if it turns out to matter for
 * the specific negative-range parameters once S17 wires real parameters through this sheet.
 *
 * [unit] is caller-supplied and may be `null`/blank for the dimensionless 0-10 knobs — this
 * component never hardcodes a unit string. [decimalPlaces] controls how [initialValue] is
 * formatted into the field's starting text (D3 §3.1: "exact decimal-place defaults... are an S17
 * implementation detail") — default 1, matching D2 screen 2b's own "6.4" example for a 0-10 knob.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParameterNumericEntrySheet(
    parameterName: String,
    initialValue: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onDismiss: () -> Unit,
    onSet: (Float) -> Unit,
    modifier: Modifier = Modifier,
    unit: String? = null,
    decimalPlaces: Int = 1,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        ParameterNumericEntrySheetContent(
            parameterName = parameterName,
            initialValue = initialValue,
            valueRange = valueRange,
            unit = unit,
            decimalPlaces = decimalPlaces,
            onDismiss = onDismiss,
            onSet = onSet,
        )
    }
}

/**
 * The sheet's actual content, factored out from [ParameterNumericEntrySheet] so it can be tested
 * ([dev.tonexotg.app.ui.components.ParameterNumericEntrySheetTest]) without instantiating
 * [ModalBottomSheet]'s own window/host machinery under Robolectric — the keypad/clamp/Set-Cancel
 * logic under test lives entirely here, and [ParameterNumericEntrySheet] only adds the bottom-sheet
 * chrome around it.
 */
@Composable
internal fun ParameterNumericEntrySheetContent(
    parameterName: String,
    initialValue: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onDismiss: () -> Unit,
    onSet: (Float) -> Unit,
    modifier: Modifier = Modifier,
    unit: String? = null,
    decimalPlaces: Int = 1,
) {
    var text by remember(parameterName) { mutableStateOf(formatInitialValue(initialValue, decimalPlaces)) }
    val isSetEnabled = remember(text) { text.toFloatOrNull() != null }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = TonexTheme.spacing.space3, vertical = TonexTheme.spacing.space2),
        verticalArrangement = Arrangement.spacedBy(TonexTheme.spacing.space3),
    ) {
        Text(
            text = parameterName,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )

        // The large numeric field (D3 §3.1 / D2 screen 2b's ".field .n"). Shows the raw typed
        // digits plus the caller-supplied unit suffix; an empty entry (fully backspaced) shows a
        // placeholder zero so the field is never blank pixels, even though Set is disabled then.
        Text(
            text = if (text.isEmpty()) "0${unitSuffixOrEmpty(unit)}" else "$text${unitSuffixOrEmpty(unit)}",
            style = MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("numericEntry.display"),
        )

        NumericKeypad(
            onDigit = { digit -> text = appendKeypadChar(text, digit) },
            onDecimalPoint = { text = appendKeypadChar(text, '.') },
            onBackspace = { text = backspaceKeypad(text) },
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(TonexTheme.spacing.space3),
        ) {
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier
                    .weight(1f)
                    .minTouchTarget()
                    .testTag("numericEntry.cancelButton"),
            ) {
                Text("Cancel", style = MaterialTheme.typography.labelLarge)
            }
            Button(
                onClick = {
                    val clamped = clampEnteredValue(text, valueRange)
                    if (clamped != null) {
                        onSet(clamped)
                    }
                },
                enabled = isSetEnabled,
                modifier = Modifier
                    .weight(1f)
                    .minTouchTarget()
                    .testTag("numericEntry.setButton"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Text("Set", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

private fun unitSuffixOrEmpty(unit: String?): String =
    if (unit.isNullOrBlank()) "" else " $unit"

/**
 * Formats [value] to [decimalPlaces] decimal places using [Locale.US] regardless of device
 * locale, so the field's decimal separator always matches the `.` key the keypad itself types —
 * a comma-decimal locale (e.g. `de`) would otherwise pre-fill text the keypad's own `.` key
 * couldn't produce, silently breaking [appendKeypadChar]'s "only one decimal point" rule.
 */
internal fun formatInitialValue(value: Float, decimalPlaces: Int): String =
    String.format(Locale.US, "%.${decimalPlaces}f", value)

/**
 * The 3-column, 4-row keypad: `1-9`, then `.`, `0`, `⌫` (D3 §3.1 / D2 screen 2b) — exactly these
 * eleven keys, each sized to [dev.tonexotg.app.ui.theme.TonexTouchTargets.min] (64dp) per D2
 * screen 2b's explicit annotation.
 */
@Composable
private fun NumericKeypad(
    onDigit: (Char) -> Unit,
    onDecimalPoint: () -> Unit,
    onBackspace: () -> Unit,
) {
    val rows = listOf(
        listOf('1', '2', '3'),
        listOf('4', '5', '6'),
        listOf('7', '8', '9'),
        listOf('.', '0', '⌫'),
    )
    Column(verticalArrangement = Arrangement.spacedBy(TonexTheme.spacing.space2)) {
        for (row in rows) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(TonexTheme.spacing.space2),
            ) {
                for (key in row) {
                    NumericKeypadKey(
                        label = key.toString(),
                        onClick = {
                            when (key) {
                                '.' -> onDecimalPoint()
                                '⌫' -> onBackspace()
                                else -> onDigit(key)
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("numericEntry.key.$key"),
                    )
                }
            }
        }
    }
}

@Composable
private fun NumericKeypadKey(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        // touch.target.min (64dp), not .secondary — see class KDoc: this keypad is the one
        // exception to the 48dp chrome floor, per D2 screen 2b's explicit annotation.
        modifier = modifier.minTouchTarget(TonexTheme.touchTargets.min),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Appends [char] to [current] per the keypad's rules: a second `.` is a no-op (only one decimal
 * point is ever meaningful), and typing `.` as the very first character seeds a leading `0` so
 * the field never reads as a bare, ambiguous `.`. `internal` so
 * [dev.tonexotg.app.ui.components.ParameterNumericEntrySheetTest] (or a lighter, non-Compose unit
 * test) can exercise the text-building rules directly, without driving a full keypad tap sequence
 * for every case.
 */
internal fun appendKeypadChar(current: String, char: Char): String = when {
    char == '.' && current.contains('.') -> current
    char == '.' && current.isEmpty() -> "0."
    else -> current + char
}

/** Removes the last typed character, or is a no-op on an already-empty entry. */
internal fun backspaceKeypad(current: String): String =
    if (current.isEmpty()) current else current.dropLast(1)

/**
 * D3 §3.1's clamp-not-reject validation: parses [text] to a [Float] and, only if that succeeds,
 * clamps it into [valueRange]. Returns `null` for text that isn't a number at all (empty, or a
 * lone `.`) — the one case this sheet treats as "nothing to commit yet" rather than "an
 * out-of-range value," since clamping has no defined answer for "not a number."
 */
internal fun clampEnteredValue(text: String, valueRange: ClosedFloatingPointRange<Float>): Float? =
    text.toFloatOrNull()?.coerceIn(valueRange)

@Preview(name = "Numeric-entry sheet — Gain (0-10, no unit)", showBackground = true, backgroundColor = 0xFF262626)
@Composable
private fun ParameterNumericEntrySheetContentPreview() {
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

@Preview(name = "Numeric-entry sheet — Noise Gate Threshold (dB unit)", showBackground = true, backgroundColor = 0xFF262626)
@Composable
private fun ParameterNumericEntrySheetContentDbPreview() {
    TonexTheme {
        ParameterNumericEntrySheetContent(
            parameterName = "Noise Gate Threshold",
            initialValue = -40f,
            valueRange = -100f..0f,
            unit = "dB",
            decimalPlaces = 0,
            onDismiss = {},
            onSet = {},
        )
    }
}
