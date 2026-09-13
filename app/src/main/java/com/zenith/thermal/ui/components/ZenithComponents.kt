package com.zenith.thermal.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zenith.thermal.ui.theme.ButtonPad
import com.zenith.thermal.ui.theme.ButtonSize
import com.zenith.thermal.ui.theme.ButtonType
import com.zenith.thermal.ui.theme.Radius
import com.zenith.thermal.ui.theme.Space
import com.zenith.thermal.ui.theme.ZenithBorder2
import com.zenith.thermal.ui.theme.ZenithRed
import com.zenith.thermal.ui.theme.ZenithMuted
import com.zenith.thermal.ui.theme.ZenithMuted2
import com.zenith.thermal.ui.theme.ZenithMuted3
import com.zenith.thermal.ui.theme.ZenithPink
import com.zenith.thermal.ui.theme.ZenithPurple
import com.zenith.thermal.ui.theme.ZenithText

/** Badge pill — colors mirrored from preview (purple/pink/green/amber/muted/red) */
@Composable
fun Badge(
   text: String,
   modifier: Modifier = Modifier,
   bg: Color = ZenithPurple.copy(alpha = 0.12f),
    fg: Color = ZenithPurple,
    round: Boolean = false,
    dot: Boolean? = null
) {
    Row(
        modifier = modifier
            .background(
                bg,
                if (round) RoundedCornerShape(999.dp) else RoundedCornerShape(Radius.sm)
            )
            .padding(horizontal = if (round) 14.dp else 8.dp, vertical = if (round) 6.dp else 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (dot != null) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(
                        if (dot) Color(0xFF34D399) else Color(0xFFF87171),
                        CircleShape
                    )
            )
            Spacer(Modifier.width(5.dp))
        }
        Text(
            text,
            color = fg,
            fontSize = if (round) 11.sp else 8.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = if (round) 0.3.sp else 0.3.sp
        )
    }
}

/** Section header — uppercase mini label like `.sec` */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, count: String? = null) {
    Row(modifier = modifier.padding(start = 4.dp, top = 12.dp, bottom = 4.dp)) {
        Text(
            text.uppercase(),
            color = ZenithMuted2,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp
        )
        if (count != null) {
            Text(
                "  $count",
                color = ZenithMuted,
                fontSize = 9.sp,
                fontWeight = FontWeight.Normal,
                letterSpacing = 0.sp
            )
        }
    }
}

/** Selectable pill — `.select-pill` */
@Composable
fun SelectPill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(Radius.sm))
            .background(
                if (selected) ZenithPurple.copy(alpha = 0.12f) else Color(0x0FFFFFFF),
                RoundedCornerShape(Radius.sm)
            )
            .then(
                if (selected) Modifier.border(1.dp, ZenithPurple.copy(alpha = 0.25f), RoundedCornerShape(Radius.sm))
                else Modifier.border(1.dp, ZenithBorder2, RoundedCornerShape(Radius.sm))
            )
            .clickable(onClick = onClick)
            .padding(horizontal = Space.sm + Space.xs, vertical = Space.xs)
    ) {
        Text(
            text,
            color = if (selected) ZenithPurple else ZenithMuted,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/** Preview-style toggle switch (purple when on) */
@Composable
fun ZenithSwitch(checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(width = 42.dp, height = 24.dp)
            .clip(CircleShape)
            .background(if (checked) ZenithPurple else Color(0x1AFFFFFF))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { onChange(!checked) }
            )
            .padding(3.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Box(
            Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(Color.White)
        )
    }
}

/** Preview-style radio (purple dot when selected) */
@Composable
fun ZenithRadio(checked: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(20.dp)
            .clip(CircleShape)
            .then(
                if (checked) Modifier.border(1.5.dp, ZenithPurple, CircleShape)
                else Modifier.border(1.5.dp, Color(0x33FFFFFF), CircleShape)
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (checked) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(ZenithPurple)
            )
        }
    }
}

/** App icon placeholder with brand-ish gradient + initials (preview `.app-card-icon`) */
@Composable
fun AppIcon(initials: String, modifier: Modifier = Modifier, gradient: List<Color>? = null) {
    val bg = gradient ?: listOf(ZenithPurple, ZenithPink)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(Radius.md))
            .background(
                androidx.compose.ui.graphics.Brush.linearGradient(bg),
                RoundedCornerShape(Radius.md)
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(initials, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

// ── Button variants ──
enum class ZenithButtonVariant { Primary, Outline, Text, Danger }

// ── Button sizes (32/40/48 system) ──
enum class ZenithButtonSize(val h: Dp, val vPad: Dp, val hPad: Dp, val fs: TextUnit) {
    Small(ButtonSize.sm, ButtonPad.smV, ButtonPad.smH, ButtonType.sm),
    Medium(ButtonSize.md, ButtonPad.mdV, ButtonPad.mdH, ButtonType.md),
    Large(ButtonSize.lg, ButtonPad.lgV, ButtonPad.lgH, ButtonType.lg)
}

/** Design-system button: 4 variants × states, token-sized. */
@Composable
fun ZenithButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: ZenithButtonVariant = ZenithButtonVariant.Primary,
    size: ZenithButtonSize = ZenithButtonSize.Medium,
    enabled: Boolean = true
) {
    // State-driven colors — pressed/dim via alpha + scale, disabled via muted
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val fg = when (variant) {
        ZenithButtonVariant.Primary -> Color.White
        ZenithButtonVariant.Outline -> ZenithPurple
        ZenithButtonVariant.Text -> ZenithPurple
        ZenithButtonVariant.Danger -> Color.White
    }
    val bg = when (variant) {
        ZenithButtonVariant.Primary ->
            if (pressed) ZenithPurple.copy(alpha = 0.65f) else ZenithPurple.copy(alpha = 0.25f)
        ZenithButtonVariant.Outline -> Color.Transparent
        ZenithButtonVariant.Text -> Color.Transparent
        ZenithButtonVariant.Danger ->
            if (pressed) ZenithRed.copy(alpha = 0.65f) else ZenithRed.copy(alpha = 0.25f)
    }
    val borderColor = when (variant) {
        ZenithButtonVariant.Primary -> if (pressed) ZenithPurple.copy(alpha = 0.8f) else ZenithPurple.copy(alpha = 0.5f)
        ZenithButtonVariant.Outline -> if (pressed) ZenithPurple.copy(alpha = 0.8f) else ZenithPurple.copy(alpha = 0.4f)
        ZenithButtonVariant.Text -> Color.Transparent
        ZenithButtonVariant.Danger -> if (pressed) ZenithRed.copy(alpha = 0.8f) else ZenithRed.copy(alpha = 0.5f)
    }
    val effFg = if (!enabled) ZenithMuted3 else fg

    Box(
        modifier = modifier
            .height(size.h)
            .clip(RoundedCornerShape(Radius.md))
            .background(
                if (!enabled) ZenithMuted3.copy(alpha = 0.08f) else bg,
                RoundedCornerShape(Radius.md)
            )
            .then(
                if (variant != ZenithButtonVariant.Text)
                    Modifier.border(1.dp, if (!enabled) ZenithMuted3.copy(alpha = 0.15f) else borderColor, RoundedCornerShape(Radius.md))
                else Modifier
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick
            )
            .padding(horizontal = size.hPad, vertical = size.vPad),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = effFg, fontSize = size.fs, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp)
    }
}