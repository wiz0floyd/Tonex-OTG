package dev.tonexotg.app.ui.theme

import androidx.compose.foundation.layout.sizeIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

/**
 * Enforces a minimum touch target footprint (D1 §4.2). This is the single reusable definition
 * of "minimum touch target" for the module — call sites use this modifier instead of writing
 * their own `Modifier.sizeIn(minWidth = ..., minHeight = ...)`.
 *
 * Defaults to [TonexTouchTargets.min] (64dp), the D1-mandated size for any control operated
 * *during* a performance. Pass [TonexTouchTargets.secondary] (48dp) explicitly for low-frequency
 * chrome (overflow menus, settings, back buttons) that D1 §4.2 exempts from the larger minimum.
 *
 * This only guarantees a minimum layout footprint (so touch/click handling has enough room) —
 * it does not draw anything and does not constrain the maximum size of visual content inside it.
 */
fun Modifier.minTouchTarget(minSize: Dp = TonexTouchTargets.min): Modifier =
    this.then(Modifier.sizeIn(minWidth = minSize, minHeight = minSize))
