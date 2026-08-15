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
import com.siliconleap.app.BuildConfig
import com.siliconleap.app.runtime.RuntimeManager
import com.siliconleap.app.runtime.RuntimeState
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun SettingsScreen(state: RuntimeState = RuntimeManager.state.value) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ServiceCard(state)
        DataCard()
        AboutCard()
    }
}

@Composable
private fun ServiceCard(state: RuntimeState) {
    Card {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Text("服务", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text("端口：${state.port}", fontSize = 14.sp)
            Text("下载源：${RuntimeManager.metaUrl}", fontSize = 12.sp, color = MiuixTheme.colorScheme.secondary)
            Spacer(Modifier.height(12.dp))
            Button(onClick = { RuntimeManager.restart() }, modifier = Modifier.fillMaxWidth()) {
                Text("重启服务")
            }
        }
    }
}

@Composable
private fun DataCard() {
    Card {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Text("数据", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            TextButton(text = "清空会话与设置数据", onClick = { RuntimeManager.clearData() })
            TextButton(text = "卸载运行时", onClick = { RuntimeManager.uninstallRuntime() })
            Text(
                "清空数据保留运行时与工作区；卸载运行时将删除 usr，需重新下载。",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.secondary,
            )
        }
    }
}

@Composable
private fun AboutCard() {
    Card {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Text("关于", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text("SiliconLeap 硅基跃迁", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text("版本：${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", fontSize = 14.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                "基于 DeepSeek Harness 的移动端封装。运行时在线下载，服务经系统浏览器使用。",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.secondary,
            )
        }
    }
}
