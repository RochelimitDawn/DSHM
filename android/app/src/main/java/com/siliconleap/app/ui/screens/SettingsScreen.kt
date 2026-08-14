package com.siliconleap.app.ui.screens

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.siliconleap.app.runtime.RuntimeManager
import com.siliconleap.app.runtime.TermuxEnv
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val dataDir = TermuxEnv.dshHome(context).absolutePath

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        SectionTitle("产品")
        InfoRow("名称", "SiliconLeap")
        InfoRow("中文名", "硅基跃迁")
        InfoRow("版本", "v2.0.0-preview")
        InfoRow("阶段", "Preview 预览版")

        Spacer(Modifier.height(20.dp))
        SectionTitle("运行环境")
        InfoRow("服务端口", "3080")
        InfoRow("运行时", "Termux / Node.js")
        InfoRow("数据目录", dataDir)

        Spacer(Modifier.height(20.dp))
        SectionTitle("数据管理")
        Button(onClick = {
            RuntimeManager.clearData()
            RuntimeManager.bootstrap()
        }) { Text("清空会话与设置") }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "清除后所有会话、凭据与设置将被删除，服务将重新初始化。",
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.secondary,
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        color = MiuixTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(4.dp))
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
