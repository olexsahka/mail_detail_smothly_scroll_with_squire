package com.alex.mailstubdetails.ui.conversation

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

/**
 * Native shimmer placeholder rendered over the DOM's `.msg-body-spacer`
 * while the message body is "loading". Chosen over a CSS keyframe
 * shimmer for two reasons:
 *
 *   • Consistency with the rest of the conversation surface — every
 *     other overlay (app bar, header, footer) is Compose-native and
 *     stays at density size during pinch. A CSS shimmer would zoom
 *     with the compositor and read as a second class visual layer.
 *   • Smoother animation — Compose's [rememberInfiniteTransition]
 *     runs on the Choreographer and stays in sync with the app's
 *     other animations (compact bar swap, etc.). CSS keyframes inside
 *     the WebView compete with its raster pipeline during pinch.
 *
 * Height is fixed at ~220 dp — a reasonable "before we know real body
 * height" placeholder. Native measurement of this composable feeds
 * `pushSpacerHeights` which sets the DOM spacer's height, keeping the
 * loader flush with adjacent header/footer overlays.
 */
@Composable
fun MessageBodyLoaderOverlay(
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    // 0 → 1 progress across a shimmer cycle. Multiplied by 3 inside the
    // gradient math so the highlight enters from off-screen left and
    // exits off-screen right, giving a continuous sweep with no visible
    // reset.
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerProgress"
    )

    val surface = MaterialTheme.colorScheme.surface
    val onSurface = MaterialTheme.colorScheme.onSurface
    val isDark = surface.luminance() < 0.5f
    // Darker for dark theme, lighter for light theme. The middle stop is
    // ~2 shades brighter than the base — a subtle highlight, not a
    // "moving stripe".
    val baseColor = if (isDark) {
        onSurface.copy(alpha = 0.08f)
    } else {
        onSurface.copy(alpha = 0.06f)
    }
    val highlightColor = if (isDark) {
        onSurface.copy(alpha = 0.16f)
    } else {
        onSurface.copy(alpha = 0.12f)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ShimmerLine(progress, baseColor, highlightColor, widthFraction = 0.94f)
        ShimmerLine(progress, baseColor, highlightColor, widthFraction = 0.90f)
        ShimmerLine(progress, baseColor, highlightColor, widthFraction = 0.72f)
        Spacer(Modifier.height(6.dp))
        ShimmerBlock(progress, baseColor, highlightColor, height = 80.dp)
        Spacer(Modifier.height(2.dp))
        ShimmerLine(progress, baseColor, highlightColor, widthFraction = 0.88f)
        ShimmerLine(progress, baseColor, highlightColor, widthFraction = 0.52f)
    }
}

@Composable
private fun ShimmerLine(
    progress: Float,
    base: Color,
    highlight: Color,
    widthFraction: Float
) {
    Box(
        modifier = Modifier
            .fillMaxWidth(widthFraction)
            .height(14.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(base)
            .shimmerHighlight(progress, highlight)
    )
}

@Composable
private fun ShimmerBlock(
    progress: Float,
    base: Color,
    highlight: Color,
    height: androidx.compose.ui.unit.Dp
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(8.dp))
            .background(base)
            .shimmerHighlight(progress, highlight)
    )
}

/**
 * Draws a linear-gradient highlight that sweeps from left to right as
 * `progress` moves 0 → 1. The gradient spans 3× the box width so the
 * transparent-highlight-transparent band enters from far-left and exits
 * off far-right in one full progress cycle without a jump.
 */
private fun Modifier.shimmerHighlight(progress: Float, highlight: Color): Modifier =
    drawBehind {
        val w = size.width
        val bandWidth = w * 0.6f
        // Center of the highlight band travels from -bandWidth to w + bandWidth.
        val center = -bandWidth + (w + 2 * bandWidth) * progress
        val start = Offset(center - bandWidth / 2f, 0f)
        val end = Offset(center + bandWidth / 2f, 0f)
        val brush = Brush.linearGradient(
            colors = listOf(
                Color.Transparent,
                highlight,
                Color.Transparent
            ),
            start = start,
            end = end
        )
        drawRect(brush = brush)
    }
