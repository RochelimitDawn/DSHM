package com.siliconleap.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.siliconleap.app.runtime.HarnessService
import com.siliconleap.app.runtime.RuntimeDiagnostics
import com.siliconleap.app.runtime.RuntimeManager
import com.siliconleap.app.runtime.RuntimeMeta
import com.siliconleap.app.runtime.RuntimeState
import com.siliconleap.app.runtime.ServerPhase
import com.siliconleap.app.runtime.StorageStats
import com.siliconleap.app.runtime.TermuxEnv
import com.siliconleap.app.runtime.UpdateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.siliconleap.app.ui.component.BlurredBar
import com.siliconleap.app.ui.component.ConfirmDialog
import com.siliconleap.app.ui.component.rememberBlurBackdrop
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowDialog

/** 环境页：复刻 KernelSU 设置页分组卡布局。 */
@Composable
fun RuntimeScreen(state: RuntimeState, bottomInnerPadding: Dp) {
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop(enableBlur = true)
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else colorScheme.surface
    var showUninstall by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }

    // 存储明细（3s）、进程/会话/日志/网络（1s）、运行时元数据（一次性）
    val storage by produceState(StorageStats()) {
        while (true) {
            value = withContext(Dispatchers.IO) { RuntimeManager.storageStats() }
            delay(3000)
        }
    }
    val diag by produceState(RuntimeDiagnostics()) {
        while (true) {
            value = RuntimeManager.diagnostics()
            delay(1000)
        }
    }
    val meta by produceState<RuntimeMeta?>(null) {
        value = RuntimeManager.fetchRuntimeMeta()
    }

    Scaffold(
        topBar = {
            BlurredBar(backdrop) {
                TopAppBar(
                    color = barColor,
                    title = "运行时",
                    scrollBehavior = scrollBehavior,
                )
            }
        },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        Box(modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxHeight()
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .padding(horizontal = 12.dp),
                contentPadding = innerPadding,
                overscrollEffect = null,
            ) {
                item {
                    Column {
                        Card(
                            modifier = Modifier
                                .padding(top = 12.dp)
                                .fillMaxWidth(),
                        ) {
                            RuntimeStatusRow(state)
                            if (state.phase == ServerPhase.DOWNLOADING || state.phase == ServerPhase.EXTRACTING) {
                                LinearProgressIndicator(
                                    modifier = Modifier
                                        .padding(horizontal = 18.dp, vertical = 8.dp)
                                        .fillMaxWidth(),
                                    progress = if (state.progress > 0f) state.progress else null,
                                )
                                Text(
                                    text = state.message,
                                    fontSize = 12.sp,
                                    color = colorScheme.onSurfaceVariantSummary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                                )
                            }
                            if (state.phase == ServerPhase.ERROR) {
                                Text(
                                    text = state.message.substringBefore("\n\n"),
                                    fontSize = 12.sp,
                                    color = Color(0xFFF72727),
                                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                                )
                            }
                        }
                        ActionsCard(state, onUninstall = { showUninstall = true })
                        StorageCard(storage)
                        ProcessCard(diag)
                        EnvInfoCard(state, meta, diag)
                        DiagnosticCard(diag, meta) { showLog = true }
                        Spacer(Modifier.height(bottomInnerPadding))
                    }
                }
            }
        }
    }

    ConfirmDialog(
        show = showUninstall,
        title = "卸载运行时",
        message = "将删除运行时环境（usr），需要重新下载安装。会话与工作区数据保留。",
        confirmText = "卸载",
        onConfirm = {
            showUninstall = false
            RuntimeManager.uninstallRuntime()
        },
        onDismiss = { showUninstall = false },
    )

    if (showLog) {
        LogDialog(onDismiss = { showLog = false })
    }
}

@Composable
private fun RuntimeStatusRow(state: RuntimeState) {
    val summary = if (state.installed) {
        "v${state.runtimeVersion ?: "unknown"} · 已安装"
    } else {
        "尚未安装"
    }
    BasicComponent(
        title = "运行时环境",
        summary = summary,
        startAction = {
            Icon(
                imageVector = Icons.Rounded.Storage,
                contentDescription = "运行时环境",
                modifier = Modifier.padding(end = 6.dp),
                tint = colorScheme.onBackground,
            )
        },
    )
}

@Composable
private fun ActionsCard(state: RuntimeState, onUninstall: () -> Unit) {
    val context = LocalContext.current
    // 从 GitHub Release 获取运行时实际大小；网络不通时回退显示约 500 MB
    val runtimeSize by produceState<Long?>(null) {
        value = RuntimeManager.fetchRuntimeSize()
    }
    val sizeText = runtimeSize?.let { UpdateManager.formatBytes(it) } ?: "约 500 MB"
    Card(
        modifier = Modifier
            .padding(top = 12.dp)
            .fillMaxWidth(),
    ) {
        when {
            !state.installed -> ArrowPreference(
                title = "拉取并安装运行时",
                summary = "从下载源获取，$sizeText",
                startAction = {
                    Icon(
                        imageVector = Icons.Rounded.FileDownload,
                        contentDescription = "拉取并安装运行时",
                        modifier = Modifier.padding(end = 6.dp),
                        tint = colorScheme.onBackground,
                    )
                },
                onClick = { RuntimeManager.installRuntime() },
            )

            state.phase == ServerPhase.RUNNING -> ArrowPreference(
                title = "卸载运行时",
                summary = "删除运行时文件，需重新下载",
                startAction = {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = "卸载运行时",
                        modifier = Modifier.padding(end = 6.dp),
                        tint = colorScheme.onBackground,
                    )
                },
                onClick = onUninstall,
            )

            else -> {
                ArrowPreference(
                    title = "启动服务",
                    summary = "后台启动 Harness 服务",
                    startAction = {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = "启动服务",
                            modifier = Modifier.padding(end = 6.dp),
                            tint = colorScheme.onBackground,
                        )
                    },
                    onClick = {
                        HarnessService.start(context)
                        RuntimeManager.bootstrap()
                    },
                )
                ArrowPreference(
                    title = "卸载运行时",
                    summary = "删除运行时文件，需重新下载",
                    startAction = {
                        Icon(
                            imageVector = Icons.Rounded.Delete,
                            contentDescription = "卸载运行时",
                            modifier = Modifier.padding(end = 6.dp),
                            tint = colorScheme.onBackground,
                        )
                    },
                    onClick = onUninstall,
                )
            }
        }
    }
}

// ------------------------------------------------------------------ 环境页数据卡

@Composable
private fun StorageCard(storage: StorageStats) {
    Card(
        modifier = Modifier
            .padding(top = 12.dp)
            .fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            SectionTitle("存储空间")
            StorageRow("运行时", storage.runtimeBytes)
            StorageRow("工作区", storage.workspaceBytes)
            StorageRow("会话与设置", storage.dshHomeBytes)
            StorageRow("日志", storage.logsBytes)
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "总占用",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = colorScheme.onSurface,
                )
                Text(
                    text = UpdateManager.formatBytes(storage.totalBytes),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun StorageRow(label: String, bytes: Long) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            color = colorScheme.onSurfaceVariantSummary,
        )
        Text(
            text = UpdateManager.formatBytes(bytes),
            fontSize = 14.sp,
            color = colorScheme.onSurface,
        )
    }
}

@Composable
private fun ProcessCard(diag: RuntimeDiagnostics) {
    Card(
        modifier = Modifier
            .padding(top = 12.dp)
            .fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            SectionTitle("进程监控")
            if (diag.pid == null) {
                Text(
                    text = "服务未运行",
                    fontSize = 12.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                )
            } else {
                DiagRow("进程 PID", diag.pid.toString())
                DiagRow("CPU 占用", String.format("%.1f %%", diag.cpuPercent))
                DiagRow("内存 RSS", "${diag.memRssKb / 1024} MB")
                DiagRow("线程数", diag.threads.toString())
                DiagRow("文件描述符", diag.fds.toString())
            }
        }
    }
}

@Composable
private fun EnvInfoCard(state: RuntimeState, meta: RuntimeMeta?, diag: RuntimeDiagnostics) {
    val ctx = LocalContext.current
    Card(
        modifier = Modifier
            .padding(top = 12.dp)
            .fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            SectionTitle("运行时与环境")
            DiagRow("架构", Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
            DiagRow("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            DiagRow("DSH", meta?.dsh?.ifBlank { "-" } ?: state.runtimeVersion ?: "-")
            DiagRow("Node", meta?.nodeVersion ?: "-")
            DiagRow("Termux", meta?.termuxApp ?: "-")
            DiagRow("构建时间", meta?.builtAt?.substringBefore("T") ?: "-")
            Spacer(Modifier.height(6.dp))
            DiagRow("PREFIX", TermuxEnv.prefix(ctx).absolutePath)
            DiagRow("DSH_HOME", TermuxEnv.dshHome(ctx).absolutePath)
            DiagRow("工作区", TermuxEnv.workspace(ctx).absolutePath)
            Spacer(Modifier.height(6.dp))
            DiagRow("监听地址", "127.0.0.1:${state.port}")
            DiagRow("局域网 IP", diag.lanIps.joinToString(", ").ifBlank { "-" })
        }
    }
}

@Composable
private fun DiagnosticCard(diag: RuntimeDiagnostics, meta: RuntimeMeta?, onShowLog: () -> Unit) {
    Card(
        modifier = Modifier
            .padding(top = 12.dp)
            .fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            SectionTitle("诊断")
            DiagRow("会话数", diag.sessions.toString())
            DiagRow("日志行数", diag.logLines.toString())
            DiagRow("日志大小", UpdateManager.formatBytes(diag.logBytes))
            val sha = meta?.sha256 ?: "-"
            DiagRow("sha256", if (sha.length > 16) "${sha.take(16)}…" else sha)
            DiagRow(
                "设备空间",
                "${UpdateManager.formatBytes(diag.freeBytes)} / ${UpdateManager.formatBytes(diag.totalBytes)}",
            )
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onShowLog)
                    .padding(vertical = 8.dp, horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "查看运行日志",
                    fontSize = 14.sp,
                    color = colorScheme.primary,
                )
                Icon(
                    imageVector = Icons.Rounded.Info,
                    contentDescription = "查看运行日志",
                    tint = colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        fontSize = MiuixTheme.textStyles.headline1.fontSize,
        fontWeight = FontWeight.Medium,
        color = colorScheme.onSurface,
    )
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun DiagRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = colorScheme.onSurfaceVariantSummary,
        )
        Text(
            text = value,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = colorScheme.onBackground,
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun LogDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var refreshKey by remember { mutableStateOf(0) }
    val log by produceState("", refreshKey) {
        value = RuntimeManager.tailLog(400)
    }
    WindowDialog(
        show = true,
        title = "运行日志",
        onDismissRequest = onDismiss,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isSystemInDarkTheme()) Color(0xFF0C0C0E) else Color(0xFF1B1C1F))
                    .padding(12.dp),
            ) {
                Text(
                    text = log.ifBlank { "(暂无日志)" },
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFFD4D4D4),
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(
                    text = "复制",
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        clipboard?.setPrimaryClip(ClipData.newPlainText("运行日志", RuntimeManager.tailLog(400)))
                    },
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = "刷新",
                    onClick = { refreshKey++ },
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = "关闭",
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
