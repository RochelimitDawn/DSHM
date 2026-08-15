package com.siliconleap.app.ui.screens

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.siliconleap.app.runtime.RuntimeManager
import com.siliconleap.app.runtime.RuntimeState
import com.siliconleap.app.runtime.ServerPhase
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun StatusScreen(state: RuntimeState) {
    val context = LocalContext.current
    val uptime = formatUptime(RuntimeManager.uptimeMillis())
    val log = RuntimeManager.tailLog()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        InfoRow("服务状态", phaseLabel(state.phase))
        InfoRow("监听地址", "http://127.0.0.1:${state.port}")
        InfoRow("进程 PID", state.pid?.toString() ?: "-")
        InfoRow("运行时长", uptime)

        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { RuntimeManager.restart() }) { Text("重启服务") }
            Spacer(Modifier.width(12.dp))
            TextButton(text = "浏览器打开", onClick = {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:${state.port}/"))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(intent) }
            })
        }

        Spacer(Modifier.height(24.dp))
        Text("运行日志", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onBackground)
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MiuixTheme.colorScheme.secondary.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                .padding(12.dp),
        ) {
            Text(
                text = log,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = MiuixTheme.colorScheme.onBackground,
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 15.sp, color = MiuixTheme.colorScheme.secondary)
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.onBackground)
    }
}

private fun phaseLabel(phase: ServerPhase): String = when (phase) {
    ServerPhase.RUNNING -> "运行中"
    ServerPhase.STARTING -> "启动中"
    ServerPhase.DOWNLOADING, ServerPhase.EXTRACTING -> "安装中"
    ServerPhase.ERROR -> "异常"
    ServerPhase.NOT_READY -> "未启动"
}

private fun formatUptime(ms: Long): String {
    if (ms <= 0L) return "0 秒"
    val totalSeconds = ms / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return buildString {
        if (h > 0) append("${h} 小时 ")
        if (m > 0) append("${m} 分 ")
        append("${s} 秒")
    }
}
