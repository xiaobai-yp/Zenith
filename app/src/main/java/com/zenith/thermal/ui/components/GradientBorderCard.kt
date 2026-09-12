package com.zenith.thermal.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zenith.thermal.ui.theme.Radius
import com.zenith.thermal.ui.theme.Space
import com.zenith.thermal.ui.theme.ZenithPurple
import com.zenith.thermal.ui.theme.ZenithPink

/**
 * Gradient-border card: outer box painted with purple→pink gradient,
 * inner box with card bg. Mirrors `.card-group` / `.app-card` from UI preview.
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
    val innerBg = Modifier
        .background(innerColor, RoundedCornerShape(radius - borderPadding))
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(innerPadding)

    Box(
        modifier = modifier
            .background(gradient, RoundedCornerShape(radius))
            .padding(borderPadding)
    ) {
        Column(modifier = innerBg) {
            content()
        }
    }
}