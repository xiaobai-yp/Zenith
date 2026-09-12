package com.zenith.thermal.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zenith.thermal.ui.theme.Radius
import com.zenith.thermal.ui.theme.Space
import com.zenith.thermal.ui.theme.ZenithPurple
import com.zenith.thermal.ui.theme.ZenithPink

/**
 * Gradient-border card using drawWithCache.
 * Two cached drawRoundRect calls: outer = gradient brush, inner = solid fill.
 * No Path/clip/layer — shader compiled once per size, not per frame.
 */
@Composable
fun GradientBorderCard(
    modifier: Modifier = Modifier,
    radius: Dp = Radius.xl,
    borderPadding: Dp = 1.5.dp,
    gradient: Brush = Brush.linearGradient(
        listOf(
            ZenithPurple.copy(alpha = 0.55f),
            ZenithPink.copy(alpha = 0.50f),
            ZenithPurple.copy(alpha = 0.40f)
        )
    ),
    innerPadding: Dp = Space.lg,
    innerColor: Color = Color(0xF00C0C18),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val r = radius.value
    val bp = borderPadding.value

    Box(
        modifier = modifier
            .drawWithCache {
                val w = size.width
                val h = size.height
                val innerR = (r - bp).coerceAtLeast(0f)

                onDrawBehind {
                    // Outer: gradient brush filled rounded rect (the border ring)
                    drawRoundRect(
                        brush = gradient,
                        topLeft = Offset.Zero,
                        size = Size(w, h),
                        cornerRadius = CornerRadius(r, r)
                    )
                    // Inner: solid card fill (cheap, no shader)
                    drawRoundRect(
                        color = innerColor,
                        topLeft = Offset(bp, bp),
                        size = Size(w - 2 * bp, h - 2 * bp),
                        cornerRadius = CornerRadius(innerR, innerR)
                    )
                }
            }
            .padding(borderPadding)
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