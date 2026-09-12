package com.zenith.thermal.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zenith.thermal.ui.theme.ZenithBorder2
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
    fg: Color = ZenithPurple
) {
    Box(
        modifier = modifier
            .background(bg, RoundedCornerShape(100.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(text, color = fg, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.3.sp)
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
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (selected) ZenithPurple.copy(alpha = 0.12f) else Color(0x0FFFFFFF),
                RoundedCornerShape(8.dp)
            )
            .then(
                if (selected) Modifier.border(1.dp, ZenithPurple.copy(alpha = 0.25f), RoundedCornerShape(8.dp))
                else Modifier.border(1.dp, ZenithBorder2, RoundedCornerShape(8.dp))
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp)
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
            .clip(RoundedCornerShape(10.dp))
            .background(
                androidx.compose.ui.graphics.Brush.linearGradient(bg),
                RoundedCornerShape(10.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(initials, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}