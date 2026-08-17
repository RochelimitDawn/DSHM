package com.siliconleap.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.siliconleap.app.runtime.RuntimeManager
import com.siliconleap.app.runtime.RuntimeState
import com.siliconleap.app.runtime.ServerPhase
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/** 安装/启动进度卡片：miuix 对话框 + 进度条 + Shell 终端日志框。 */
@Composable
fun BootScreen(state: RuntimeState) {
    val context = LocalContext.current
    var log by remember { mutableStateOf("") }
    var dismissed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (!dismissed) {
            val next = RuntimeManager.tailLog(200)
            // 内容未变化时不更新 state，避免高频轮询导致整屏无谓重组
            if (next != log) log = next
            delay(400)
        }
    }

    if (dismissed) return

    val phase = state.phase
    val title = when (phase) {
        ServerPhase.DOWNLOADING -> "下载运行时"
        ServerPhase.EXTRACTING -> "解压运行时"
        ServerPhase.STARTING -> "启动服务"
        ServerPhase.ERROR -> "出错了"
        else -> "安装运行时"
    }
    val installing = phase == ServerPhase.DOWNLOADING || phase == ServerPhase.EXTRACTING
    val pct = (state.progress * 100).toInt().coerceIn(0, 100)

    WindowDialog(
        show = true,
        title = title,
        onDismissRequest = { dismissed = true },
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                text = state.message,
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                progress = if (installing && state.progress > 0f) state.progress else null,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (installing) "$pct%" else "…",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(16.dp))
            TerminalBox(log)
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(
                    text = "复制日志",
                    onClick = { copyLog(context) },
                    modifier = Modifier.weight(1f),
                )
                if (phase == ServerPhase.ERROR) {
                    TextButton(
                        text = "重试",
                        onClick = { RuntimeManager.bootstrap() },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
                TextButton(
                    text = "关闭",
                    onClick = { dismissed = true },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun TerminalBox(log: String) {
    val dark = isSystemInDarkTheme()
    val background = if (dark) Color(0xFF0C0C0E) else Color(0xFF1B1C1F)
    val listState = rememberLazyListState()
    val lines = log.lines()

    LaunchedEffect(log) {
        if (lines.isNotEmpty()) {
            listState.scrollToItem(lines.size - 1)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(background),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Dot(Color(0xFFFF5F57))
                Spacer(Modifier.width(6.dp))
                Dot(Color(0xFFFEBC2E))
                Spacer(Modifier.width(6.dp))
                Dot(Color(0xFF28C840))
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "server.log · bash",
                    fontSize = 11.sp,
                    color = Color(0xFF9E9E9E),
                    fontFamily = FontFamily.Monospace,
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color(0x1FFFFFFF)),
            )
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                items(lines) { line ->
                    Text(
                        text = line.ifBlank { " " },
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFFD4D4D4),
                    )
                }
            }
        }
    }
}

@Composable
private fun Dot(color: Color) {
    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(color),
    )
}

private fun copyLog(context: Context) {
    val text = RuntimeManager.tailLog(200)
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("SiliconLeap 日志", text))
}
