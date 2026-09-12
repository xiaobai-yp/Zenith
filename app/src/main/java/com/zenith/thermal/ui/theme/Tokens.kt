package com.zenith.thermal.ui.theme

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Design tokens — spacing, radius, typography scaled from base 4px grid.
 * All values must come from this file. No arbitrary values.
 */

// ── Spacing tokens (multiples of 4) ──
object Space {
    val xs  = 4.dp    // micro spacing
    val sm  = 8.dp    // small element gap
    val md  = 12.dp   // icon ↔ text
    val lg  = 16.dp   // padding / component spacing
    val xl  = 24.dp   // between sections
    val xxl = 32.dp   // section separator
    val xxxl = 40.dp  // large spacing
    val huge = 48.dp  // major layout spacing
}

// ── Radius tokens ──
object Radius {
    val sm   = 4.dp   // sharp / technical
    val md   = 8.dp   // default card inner
    val lg   = 12.dp  // app card
    val xl   = 16.dp  // menu card, section card
    val xxl  = 24.dp  // large containers

    /** inner radius = outer - padding (nested radius rule) */
    fun nested(outer: androidx.compose.ui.unit.Dp, padding: androidx.compose.ui.unit.Dp): androidx.compose.ui.unit.Dp =
        (outer - padding).coerceAtLeast(sm)
}

// ── Typography tokens ──
// body: 16sp / 24sp line-height
// heading: 26sp / 32sp line-height
// body-sm: 14sp
// All line-heights aligned to spacing grid (multiples of 4)
val TextBody     = sp(16)    // default body
val TextBodyLH   = sp(24)    // 16 × 1.5 = 24
val TextBodySm   = sp(14)    // small body
val TextBodySmLH = sp(20)    // 14 × 1.4 ≈ 20
val TextHeading  = sp(26)    // section heading
val TextHeadingLH= sp(32)    // 26 × 1.25 ≈ 32
val TextCaption  = sp(12)    // captions / badges
val TextMini     = sp(10)    // section labels
val TextMicro    = sp(8)     // micro labels