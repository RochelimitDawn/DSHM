package com.siliconleap.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.siliconleap.app.runtime.RuntimeManager
import com.siliconleap.app.runtime.RuntimeState
import com.siliconleap.app.runtime.ServerPhase
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun RuntimeScreen(state: RuntimeState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusCard(state)
        ActionsCard(state)
        SourceCard()
    }
}

@Composable
private fun StatusCard(state: RuntimeState) {
    Card {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Text("运行时环境", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text(
                if (state.installed) "已安装 · ${state.runtimeVersion ?: "unknown"}" else "未安装",
                fontSize = 14.sp,
            )
            if (state.phase == ServerPhase.DOWNLOADING || state.phase == ServerPhase.EXTRACTING) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    progress = if (state.progress > 0f) state.progress else null,
                )
                Spacer(Modifier.height(8.dp))
                Text(state.message, fontSize = 12.sp, color = MiuixTheme.colorScheme.secondary)
            }
            if (state.phase == ServerPhase.ERROR) {
                Spacer(Modifier.height(8.dp))
                Text(
                    state.message.substringBefore("\n\n"),
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun ActionsCard(state: RuntimeState) {
    Card {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Text("操作", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            if (!state.installed) {
                Button(onClick = { RuntimeManager.bootstrap() }, modifier = Modifier.fillMaxWidth()) {
                    Text("下载并安装运行时")
                }
            } else {
                Button(onClick = { RuntimeManager.uninstallRuntime() }, modifier = Modifier.fillMaxWidth()) {
                    Text("卸载运行时")
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "卸载仅移除运行时（usr），保留会话与工作区数据。",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.secondary,
                )
            }
        }
    }
}

@Composable
private fun SourceCard() {
    Card {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Text("下载源", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "元数据：${RuntimeManager.metaUrl}",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.secondary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "可在设置页修改镜像源。",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.secondary,
            )
        }
    }
}
