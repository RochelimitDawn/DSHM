package com.siliconleap.app.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.siliconleap.app.runtime.ThemeStore
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 白天/黑夜切换动画：从触发点圆形扩散的遮罩过渡。 */
@Composable
fun ThemeTransitionOverlay(
    center: Offset,
    mode: String,
    onFinished: () -> Unit,
) {
    val progress = remember { Animatable(0f) }
    val targetColor = if (mode == ThemeStore.MODE_DARK) {
        MiuixTheme.colorScheme.surface.copy(alpha = 0.85f)
    } else {
        Color.White.copy(alpha = 0.92f)
    }

    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(durationMillis = 700, easing = FastOutSlowInEasing))
        onFinished()
    }

    Canvas(Modifier.fillMaxSize()) {
        val maxRadius = size.maxDimension * 1.2f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(targetColor, Color.Transparent),
                center = center,
                radius = maxRadius,
            ),
            radius = maxRadius * progress.value,
            center = center,
        )
    }
}
