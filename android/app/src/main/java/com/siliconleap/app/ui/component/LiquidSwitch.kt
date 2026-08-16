package com.siliconleap.app.ui.component

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 液态玻璃风格开关：渐变轨道 + 高光拇指 + 弹簧动画 + 按压缩放。 */
@Composable
fun LiquidSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 23.dp else 3.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMedium, dampingRatio = Spring.DampingRatioNoBouncy),
        label = "liquidSwitchThumb",
    )
    val thumbScale by animateFloatAsState(
        targetValue = if (pressed) 1.18f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "liquidSwitchThumbScale",
    )

    Box(
        modifier = modifier
            .size(width = 52.dp, height = 32.dp)
            .clip(CircleShape)
            .background(
                Brush.verticalGradient(
                    colors = if (checked) {
                        listOf(
                            MiuixTheme.colorScheme.primary.copy(alpha = 0.85f),
                            MiuixTheme.colorScheme.primary,
                        )
                    } else {
                        listOf(
                            MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f),
                            MiuixTheme.colorScheme.surfaceVariant,
                        )
                    },
                ),
            )
            .then(
                if (checked) {
                    Modifier.border(1.5.dp, MiuixTheme.colorScheme.primary.copy(alpha = 0.55f), CircleShape)
                } else {
                    Modifier.border(1.dp, MiuixTheme.colorScheme.outline.copy(alpha = 0.35f), CircleShape)
                },
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = { onCheckedChange(!checked) },
            ),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = thumbOffset)
                .size(26.dp)
                .graphicsLayer {
                    scaleX = thumbScale
                    scaleY = thumbScale
                }
                .shadow(
                    elevation = if (checked) 3.dp else 2.dp,
                    shape = CircleShape,
                    ambientColor = Color.Black.copy(alpha = 0.4f),
                    spotColor = Color.Black.copy(alpha = 0.4f),
                )
                .clip(CircleShape)
                .background(Color.White),
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .size(22.dp, 11.dp)
                    .clip(RoundedCornerShape(topStart = 11.dp, topEnd = 11.dp))
                    .background(Color.White.copy(alpha = 0.4f)),
            )
        }
    }
}
