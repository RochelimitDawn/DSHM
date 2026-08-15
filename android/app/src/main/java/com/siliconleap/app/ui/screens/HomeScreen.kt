package com.siliconleap.app.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.siliconleap.app.runtime.RuntimeState
import com.siliconleap.app.runtime.ServerPhase
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun HomeScreen(state: RuntimeState) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusCard(state = state, onOpen = { openHarness(context, state.port) })
        InfoCard(state = state)
        DeviceCard()
    }
}

@Composable
private fun StatusCard(state: RuntimeState, onOpen: () -> Unit) {
    Card {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Text("Harness 状态", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val (color, text) = statusOf(state.phase)
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(color)
                )
                Spacer(Modifier.size(8.dp))
                Text(text, fontSize = 14.sp, color = MiuixTheme.colorScheme.secondary)
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
                Text("打开 Harness", fontSize = 16.sp)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "服务地址：http://127.0.0.1:${state.port}",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.secondary,
            )
        }
    }
}

@Composable
private fun InfoCard(state: RuntimeState) {
    Card {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Text("运行时", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("版本：${state.runtimeVersion ?: "未安装"}", fontSize = 14.sp)
            Text("状态：${statusOf(state.phase).second}", fontSize = 14.sp)
            Text("端口：${state.port}", fontSize = 14.sp)
        }
    }
}

@Composable
private fun DeviceCard() {
    val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
    Card {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Text("设备信息", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("Android ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}", fontSize = 14.sp)
            Text("架构：$abi", fontSize = 14.sp)
        }
    }
}

private fun statusOf(phase: ServerPhase): Pair<Color, String> = when (phase) {
    ServerPhase.RUNNING -> Color(0xFF4CAF50) to "运行中"
    ServerPhase.STARTING -> Color(0xFFFFA000) to "正在启动"
    ServerPhase.DOWNLOADING, ServerPhase.EXTRACTING -> Color(0xFFFFA000) to "正在安装"
    ServerPhase.ERROR -> Color(0xFFE5484D) to "异常"
    ServerPhase.NOT_READY -> Color(0xFF9E9E9E) to "未启动"
}

private fun openHarness(context: Context, port: Int) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:$port/"))
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
