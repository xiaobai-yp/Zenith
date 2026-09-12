package com.zenith.thermal.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zenith.thermal.ui.theme.Radius
import com.zenith.thermal.ui.theme.Space
import com.zenith.thermal.ui.theme.ZenithBorder2
import com.zenith.thermal.ui.theme.ZenithPurple
import com.zenith.thermal.ui.theme.ZenithPink

/**
 * Gradient-border card. Fast path: solid 1px border (zero shader).
 * Optional gradient = outer glow only, not full gradient ring.
 * For 84-card lists: use border (solid) — gradient kills FPS on midrange GPUs.
 */
@Composable
fun GradientBorderCard(
    modifier: Modifier = Modifier,
    radius: Dp = Radius.xl,
    borderPadding: Dp = 1.dp,
    gradient: Brush? = null,          // null = solid border (fast), non-null = gradient glow (slow)
    innerPadding: Dp = Space.lg,
    innerColor: Color = Color(0xF00C0C18),
    borderSolid: Color = ZenithBorder2, // solid fallback (used when gradient = null)
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(radius)
    val effectiveBorder = if (gradient != null) {
        // Gradient: drawWithCache, shader compiled once per size
        Modifier.drawWithCache {
            val w = size.width; val h = size.height; val r = radius.value
            val innerR = (r - borderPadding.value).coerceAtLeast(0f)
            onDrawBehind {
                drawRoundRect(brush = gradient, topLeft = Offset.Zero, size = Size(w, h),
                    cornerRadius = CornerRadius(r, r))
                drawRoundRect(color = innerColor, topLeft = Offset(borderPadding.value, borderPadding.value),
                    size = Size(w - 2 * borderPadding.value, h - 2 * borderPadding.value),
                    cornerRadius = CornerRadius(innerR, innerR))
            }
        }.padding(borderPadding)
    } else {
        // Solid: 1px border — zero shader, zero drawWithCache overhead
        Modifier.border(borderPadding, borderSolid, shape)
    }

    Box(
        modifier = modifier
            .then(effectiveBorder)
            .background(innerColor, shape)
    ) {
        Column(
            modifier = Modifier
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(innerPadding)
        ) {
            content()
        }
    }
}