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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import com.siliconleap.app.runtime.TermuxEnv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.concurrent.TimeUnit

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
                // 0.2.0 起 WebUI 需要 ?token= 认证；token 不可用时裸地址
                val url = RuntimeManager.webUrl(state.port, RuntimeManager.state.value.authToken)
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
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
        TerminalCard()
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * 终端：在运行时环境内执行 bash 命令（nativeLibraryDir 的 bash + serverEnv），
 * 输出流式展示。一次执行模型（无交互 TTY），排查与轻量操作用。
 */
@Composable
private fun TerminalCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var output by remember { mutableStateOf("(暂无输出)") }
    Column {
        Text("终端", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onBackground)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                label = "bash 命令",
                enabled = !running,
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    val cmd = input.trim()
                    if (cmd.isEmpty() || running) return@Button
                    running = true
                    scope.launch(Dispatchers.IO) {
                        val result = runShellCommand(context, cmd)
                        withContext(Dispatchers.Main) {
                            output = result
                            running = false
                        }
                    }
                },
                enabled = !running,
            ) { Text(if (running) "执行中" else "执行") }
        }
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 320.dp)
                .background(MiuixTheme.colorScheme.secondary.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                .padding(12.dp),
        ) {
            Text(
                text = output,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = MiuixTheme.colorScheme.onBackground,
            )
        }
    }
}

/** 在运行时环境内执行一条 bash 命令（serverEnv + workspace cwd），返回带退出码的输出。 */
private fun runShellCommand(context: android.content.Context, command: String): String {
    val bash = java.io.File(TermuxEnv.nativeLibDir(context), "libbash.so")
    if (!bash.exists()) return "! 运行时未安装，请先拉取运行时"
    val pb = ProcessBuilder(listOf(bash.absolutePath, "-c", command))
    pb.environment().putAll(TermuxEnv.serverEnv(context))
    pb.directory(TermuxEnv.workspace(context))
    pb.redirectErrorStream(true)
    return try {
        val p = pb.start()
        val out = StringBuilder()
        // 边读边等：不消费 stdout，长输出超过管道缓冲会阻塞写而永不退出
        val pump = Thread {
            runCatching {
                p.inputStream.bufferedReader().use { r ->
                    var line = r.readLine()
                    while (line != null) {
                        out.appendLine(line)
                        line = r.readLine()
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        val done = p.waitFor(60, TimeUnit.SECONDS)
        pump.join(3_000)
        if (!done) {
            p.destroyForcibly()
            out.appendLine("! 命令超时（60s），已终止")
        } else {
            out.appendLine("[exit ${p.exitValue()}]")
        }
        out.toString().trim().ifBlank { "(无输出)" }
    } catch (e: Exception) {
        "! 执行失败: ${e.javaClass.simpleName}: ${e.message}"
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
        Text(label, fontSize = 15.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
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
