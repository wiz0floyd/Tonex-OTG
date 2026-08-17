package dev.tonexotg.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The full D1 §3 type scale, mapped one-for-one onto M3 [Typography] slots exactly as D1's
 * "M3 mapping" column specifies. Three tiers (`bodyLarge`, `bodyMedium`, `labelLarge`) are
 * intentionally bumped above stock M3 defaults per D1 — that is not a bug to "fix" back down
 * (see D1 §7).
 *
 * `displayLarge` additionally overrides weight to Bold — M3's stock `displayLarge` is Regular by
 * default, but D1 §3's arm's-length rationale requires Bold for `type.display.preset`, the one
 * non-negotiable tier in the app.
 *
 * Every other M3 typography slot (bodySmall, labelMedium, displaySmall, headlineMedium/Large,
 * titleMedium/Small) is left at M3 default: D1 does not define a token for them, and this story
 * is a translation of D1, not an invitation to invent additional scale steps.
 */
val TonexTypography = Typography(
    // type.display.preset — 57sp/64sp, 700 Bold. The arm's-length tier; active preset name only.
    displayLarge = TextStyle(
        fontSize = 57.sp,
        lineHeight = 64.sp,
        fontWeight = FontWeight.Bold,
    ),
    // type.display.index — 45sp/52sp, 700 Bold. Companion numeral badge next to the preset name.
    displayMedium = TextStyle(
        fontSize = 45.sp,
        lineHeight = 52.sp,
        fontWeight = FontWeight.Bold,
    ),
    // type.headline — 24sp/32sp, 600 SemiBold. Screen/section titles.
    headlineSmall = TextStyle(
        fontSize = 24.sp,
        lineHeight = 32.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    // type.title — 22sp/28sp, 500 Medium. Preset list row primary text, dialog titles.
    titleLarge = TextStyle(
        fontSize = 22.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.Medium,
    ),
    // type.body-large — 18sp/26sp (M3 default 16sp), 400 Regular. Parameter names, primary body.
    bodyLarge = TextStyle(
        fontSize = 18.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.Normal,
    ),
    // type.body — 16sp/22sp (M3 default 14sp), 400 Regular. Secondary body copy, helper text.
    bodyMedium = TextStyle(
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.Normal,
    ),
    // type.label — 16sp/20sp (M3 default 14sp), 600 SemiBold. Button labels, chip text, tabs.
    labelLarge = TextStyle(
        fontSize = 16.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    // type.caption — 13sp/18sp (M3 default 11sp), 400 Regular. Lowest-priority metadata only.
    labelSmall = TextStyle(
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Normal,
    ),
)
