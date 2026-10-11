package com.example.qidk_hack2026.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.qidk_hack2026.ui.theme.Glass
import kotlin.math.cos
import kotlin.math.sin

val CardShape = RoundedCornerShape(28.dp)
val ChipShape = RoundedCornerShape(100.dp)

/**
 * The backdrop glass is laid over: a soft gradient with three slow, blurred colour fields.
 * Glass only reads as glass when there is something colourful behind it.
 */
@Composable
fun GlassBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val g = Glass.colors
    val t = rememberInfiniteTransition(label = "drift")
    val phase by t.animateFloat(
        0f, 6.2831855f,
        infiniteRepeatable(tween(46_000, easing = androidx.compose.animation.core.LinearEasing), RepeatMode.Restart),
        label = "phase",
    )
    Box(
        modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(g.bgTop, g.bgBottom)))
            .drawBehind {
                val w = size.width
                val h = size.height
                fun blob(color: Color, cx: Float, cy: Float, r: Float) = drawCircle(
                    Brush.radialGradient(listOf(color.copy(alpha = if (g.dark) 0.55f else 0.62f), Color.Transparent), Offset(cx, cy), r),
                    r, Offset(cx, cy),
                )
                blob(g.blobA, w * (0.2f + 0.08f * cos(phase)), h * (0.14f + 0.04f * sin(phase)), w * 0.85f)
                blob(g.blobB, w * (0.9f + 0.07f * sin(phase + 1f)), h * (0.42f + 0.05f * cos(phase + 2f)), w * 0.8f)
                blob(g.blobC, w * (0.3f + 0.1f * sin(phase + 3f)), h * (0.88f + 0.03f * cos(phase)), w * 0.9f)
            },
        content = content,
    )
}

/** A pane of glass: translucent fill, a bright upper edge fading to a faint lower one, and a soft shadow. */
@Composable
fun Modifier.glass(shape: Shape = CardShape, strong: Boolean = false, @Suppress("UNUSED_PARAMETER") elevation: Dp = 0.dp): Modifier {
    val g = Glass.colors
    return this
        .clip(shape)
        .background(if (strong) g.glassFillStrong else g.glassFill)
        .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = if (g.dark) 0.07f else 0.32f), Color.Transparent)))
        .border(BorderStroke(1.dp, Brush.linearGradient(listOf(g.edgeLight, g.edgeDim, g.edgeLight.copy(alpha = g.edgeLight.alpha * 0.5f)))), shape)
}

/** Scales slightly while pressed, like a touch on glass. */
@Composable
fun Modifier.pressable(onClick: () -> Unit, enabled: Boolean = true, source: MutableInteractionSource = remember { MutableInteractionSource() }): Modifier {
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.96f else 1f, spring(dampingRatio = 0.6f, stiffness = 500f), label = "press")
    return this.scale(s).clickable(source, null, enabled = enabled, onClick = onClick)
}

@Composable
fun GlassCard(modifier: Modifier = Modifier, strong: Boolean = false, padding: PaddingValues = PaddingValues(20.dp), content: @Composable () -> Unit) {
    Box(modifier.fillMaxWidth().glass(CardShape, strong).padding(padding)) { content() }
}

/** The one filled, coloured control per screen. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val g = Glass.colors
    Box(
        modifier
            .heightIn(min = 54.dp)
            .shadow(if (enabled) 14.dp else 0.dp, ChipShape, ambientColor = g.accent.copy(alpha = 0.4f), spotColor = g.accent.copy(alpha = 0.5f))
            .clip(ChipShape)
            .background(Brush.linearGradient(listOf(g.accent, g.accentEnd)), alpha = if (enabled) 1f else 0.35f)
            .border(1.dp, Color.White.copy(alpha = 0.35f), ChipShape)
            .pressable(onClick, enabled)
            .padding(horizontal = 28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = g.onAccent, style = androidx.compose.material3.MaterialTheme.typography.labelLarge)
    }
}

/** A glass pill with an optional icon, for secondary actions. */
@Composable
fun GlassButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: Int? = null, tint: Color = Glass.colors.text) {
    Row(
        modifier
            .heightIn(min = 44.dp)
            .glass(ChipShape, elevation = 6.dp)
            .pressable(onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) Icon(painterResource(icon), null, Modifier.size(18.dp), tint = tint)
        Text(text, color = tint, style = androidx.compose.material3.MaterialTheme.typography.labelLarge)
    }
}

/** Round glass button holding a single icon. */
@Composable
fun GlassIconButton(icon: Int, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = Glass.colors.text, size: Dp = 44.dp) {
    Box(
        modifier.size(size).glass(CircleShape, elevation = 6.dp).pressable(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), description, Modifier.size(size * 0.45f), tint = tint)
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier,
        color = Glass.colors.textMuted,
        style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
    )
}

/** A small coloured dot that breathes while [live]. */
@Composable
fun StatusDot(color: Color, live: Boolean = false, size: Dp = 10.dp) {
    val t = rememberInfiniteTransition(label = "dot")
    val a by t.animateFloat(1f, 0.35f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "a")
    Box(Modifier.size(size).clip(CircleShape).background(color.copy(alpha = if (live) a else 1f)))
}

@Composable
fun Divider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Glass.colors.textMuted.copy(alpha = 0.16f)))
}
