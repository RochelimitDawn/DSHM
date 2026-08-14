package com.siliconleap.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
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
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
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
                val summary = state.message.substringBefore("\n\n").ifBlank { state.message }
                val log = state.message.substringAfter("\n\n", "").ifBlank { RuntimeManager.tailLog(60) }
                Text(
                    text = summary,
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.error,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .background(MiuixTheme.colorScheme.secondary.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                        .padding(10.dp),
                ) {
                    Text(
                        text = log,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MiuixTheme.colorScheme.onBackground,
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                    )
                }
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(text = "复制日志", onClick = { copyText(context, log) })
                    Spacer(Modifier.width(8.dp))
                    TextButton(text = "重试", onClick = { RuntimeManager.bootstrap() })
                }
            }
            else -> Unit
        }
    }
}

private fun copyText(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("SiliconLeap 日志", text))
}
