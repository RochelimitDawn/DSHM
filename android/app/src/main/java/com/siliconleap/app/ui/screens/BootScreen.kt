package com.siliconleap.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.siliconleap.app.runtime.RuntimeManager
import com.siliconleap.app.runtime.RuntimeState
import com.siliconleap.app.runtime.ServerPhase
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun BootScreen(state: RuntimeState) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .background(MiuixTheme.colorScheme.primary, RoundedCornerShape(24.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text("S", color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = "SiliconLeap",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = MiuixTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(6.dp))
        Text("硅基跃迁", fontSize = 15.sp, color = MiuixTheme.colorScheme.secondary)
        Spacer(Modifier.height(4.dp))
        Text("v2.0.0-preview", fontSize = 13.sp, color = MiuixTheme.colorScheme.secondary)
        Spacer(Modifier.height(40.dp))
        when (state.phase) {
            ServerPhase.EXTRACTING, ServerPhase.STARTING -> {
                Text(state.message, fontSize = 14.sp, color = MiuixTheme.colorScheme.secondary)
                Spacer(Modifier.height(16.dp))
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(0.6f),
                    progress = if (state.progress > 0f) state.progress else null,
                )
            }
            ServerPhase.ERROR -> {
                Text(state.message, fontSize = 14.sp, color = MiuixTheme.colorScheme.error)
                Spacer(Modifier.height(16.dp))
                TextButton(text = "重试", onClick = { RuntimeManager.bootstrap() })
            }
            else -> Unit
        }
    }
}
