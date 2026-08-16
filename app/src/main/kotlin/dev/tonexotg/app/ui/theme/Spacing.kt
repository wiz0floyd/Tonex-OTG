package dev.tonexotg.app.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The D1 §4.1 spacing scale (8dp base grid, one half-step for tight/inline spacing), and the
 * D1 §4.2 touch-target sizes. Every spacing/size value in this module traces back to one of
 * these vals — no call site should write a raw `.dp` literal for a value that has a token here.
 *
 * Access via [TonexTheme.spacing] / [TonexTheme.touchTargets] rather than these objects
 * directly, to keep call sites going through one theme entry point (mirrors
 * `MaterialTheme.colorScheme` / `MaterialTheme.typography`).
 */
object TonexSpacing {
    /** `space.0-5` — icon-to-label gap, tightest inline spacing. */
    val space0_5: Dp = 4.dp

    /** `space.1` — base unit; padding inside small chips. */
    val space1: Dp = 8.dp

    /** `space.2` — padding inside compact controls. */
    val space2: Dp = 12.dp

    /** `space.3` — standard content padding, gap between related controls. */
    val space3: Dp = 16.dp

    /** `space.4` — gap between unrelated control groups. */
    val space4: Dp = 24.dp

    /** `space.5` — section separation. */
    val space5: Dp = 32.dp

    /** `space.6` — major layout margins, top-level screen padding. */
    val space6: Dp = 48.dp

    /** `space.7` — reserved, matches the touch-target minimum footprint. */
    val space7: Dp = 64.dp
}

/**
 * The D1 §4.2 minimum touch-target sizes. [min] (64dp) is mandatory for every control operated
 * *during* a performance (preset tiles/rows, effect toggles, sliders, transport-style actions);
 * [secondary] (48dp, Material's own baseline) is for low-frequency chrome never touched
 * mid-song. See [dev.tonexotg.app.ui.theme.minTouchTarget] for the reusable [androidx.compose.ui.Modifier]
 * that enforces these — do not repeat a raw `.sizeIn(...)` per call site.
 */
object TonexTouchTargets {
    /** `touch.target.min` — preset tiles, preset list rows, effect toggles, any mid-song control. */
    val min: Dp = 64.dp

    /** `touch.target.secondary` — settings/overflow/navigation chrome, never touched mid-song. */
    val secondary: Dp = 48.dp

    /** `touch.target.spacing` — minimum gap between two adjacent 64dp targets. */
    val spacing: Dp = 8.dp
}
