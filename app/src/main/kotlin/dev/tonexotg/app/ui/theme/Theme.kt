package dev.tonexotg.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The M3 [androidx.compose.material3.ColorScheme], wired from the D1 tokens exactly per D1 §2.1's
 * and §2.2's stated "M3 mapping" columns. Dark is not one of two branches here — D1 §6 is
 * explicit that v1 has no light theme, no toggle, and no "follow system" branch, so there is
 * exactly one [androidx.compose.material3.ColorScheme] in this module, not a light/dark pair.
 *
 * `onSurfaceVariant` is wired to [TonexOnSurfaceSecondary] (D1's "onSurfaceVariant (strong)"
 * row) — the tertiary/muted variant and the disabled tone have no stock M3
 * [androidx.compose.material3.ColorScheme] slot of their own and live on [TonexExtendedColors]
 * instead, alongside the status colors (which D1 §7 explicitly calls out as needing a custom
 * extension since M3 has no "success"/neutral-status role).
 */
private val TonexColorScheme = darkColorScheme(
    primary = TonexAccentPrimary,
    onPrimary = TonexOnAccent,
    surface = TonexSurfaceBase,
    onSurface = TonexOnSurfacePrimary,
    onSurfaceVariant = TonexOnSurfaceSecondary,
    surfaceContainer = TonexSurfaceRaised1,
    surfaceContainerHigh = TonexSurfaceRaised2,
    surfaceContainerHighest = TonexSurfaceRaised3,
    background = TonexSurfaceBase,
    onBackground = TonexOnSurfacePrimary,
    error = TonexStatusError,
    onError = TonexOnStatus,
)

/**
 * D1 tokens with no stock M3 [androidx.compose.material3.ColorScheme] role: the tertiary/muted
 * and disabled on-surface tones (§2.1), and the four connection-status colors plus their
 * on-status content color (§2.3). Wired as a custom [androidx.compose.runtime.CompositionLocal]
 * extension, per D1 §7's explicit instruction, not by repurposing `tertiary`/`secondary` roles
 * that mean something else in M3.
 */
data class TonexExtendedColors(
    val onSurfaceTertiary: Color,
    val onSurfaceDisabled: Color,
    val statusConnected: Color,
    val statusConnecting: Color,
    val statusDisconnected: Color,
    val statusError: Color,
    val onStatus: Color,
)

private val TonexExtendedColorsDefault = TonexExtendedColors(
    onSurfaceTertiary = TonexOnSurfaceTertiary,
    onSurfaceDisabled = TonexOnSurfaceDisabled,
    statusConnected = TonexStatusConnected,
    statusConnecting = TonexStatusConnecting,
    statusDisconnected = TonexStatusDisconnected,
    statusError = TonexStatusError,
    onStatus = TonexOnStatus,
)

private val LocalTonexExtendedColors = staticCompositionLocalOf<TonexExtendedColors> {
    error("TonexExtendedColors requested outside of TonexTheme { }")
}

/**
 * The app's single Material 3 theme. Dark-first by construction (D1 §1, §6): there is no
 * parameter to select a light scheme or follow the system setting, because D1 defines none.
 *
 * Wrap the app's content (or any `@Preview` composable exercising a themed component) in this
 * once, at the root:
 * ```
 * TonexTheme {
 *     // content reads colors via MaterialTheme.colorScheme / TonexTheme.extendedColors,
 *     // type via MaterialTheme.typography, spacing via TonexTheme.spacing, etc.
 * }
 * ```
 */
@Composable
fun TonexTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalTonexExtendedColors provides TonexExtendedColorsDefault) {
        MaterialTheme(
            colorScheme = TonexColorScheme,
            typography = TonexTypography,
            content = content,
        )
    }
}

/**
 * Theme accessors, mirroring the `MaterialTheme.colorScheme` / `MaterialTheme.typography`
 * pattern for the tokens D1 defines that M3's own [MaterialTheme] object has no slot for:
 * the extended status/tertiary/disabled colors, the spacing scale, and touch-target sizes.
 */
object TonexTheme {
    val extendedColors: TonexExtendedColors
        @Composable get() = LocalTonexExtendedColors.current

    val spacing: TonexSpacing
        get() = TonexSpacing

    val touchTargets: TonexTouchTargets
        get() = TonexTouchTargets
}
