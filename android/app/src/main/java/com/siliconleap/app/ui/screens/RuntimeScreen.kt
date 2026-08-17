package com.siliconleap.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import com.siliconleap.app.runtime.AppSettings
import com.siliconleap.app.runtime.HarnessService
import com.siliconleap.app.runtime.RuntimeDiagnostics
import com.siliconleap.app.runtime.RuntimeManager
import com.siliconleap.app.runtime.RuntimeMeta
import com.siliconleap.app.runtime.RuntimeState
import com.siliconleap.app.runtime.ServerPhase
import com.siliconleap.app.runtime.StorageStats
import com.siliconleap.app.runtime.SubsystemManager
import com.siliconleap.app.runtime.SubsystemPhase
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
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowDialog

/** 环境页：复刻 KernelSU 设置页分组卡布局。 */
@Composable
fun RuntimeScreen(state: RuntimeState, bottomInnerPadding: Dp, isActive: Boolean = true) {
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop(enableBlur = true)
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else colorScheme.surface
    var showUninstall by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }

    // rememberUpdatedState：页面被 Pager 预组合（beyondViewportPageCount=1）但不可见时，
    // 轮询循环只空转 delay 不更新 state——不触发重组、不触发 backdrop 层重录，切页瞬时无卡顿
    val currentActive by rememberUpdatedState(isActive)

    // 存储明细（15s 全树遍历开销大）、进程/会话/日志/网络（1s）仅激活时更新；
    // meta 为低频一次性请求，预组合时预取（切到页面即时显示），不随激活状态变化
    val storage by produceState(StorageStats()) {
        while (true) {
            if (currentActive) value = withContext(Dispatchers.IO) { RuntimeManager.storageStats() }
            delay(15000)
        }
    }
    val diag by produceState(RuntimeDiagnostics()) {
        while (true) {
            if (currentActive) value = RuntimeManager.diagnostics()
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
                item { RuntimeSectionTitle("运行时") }
                item {
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
                }
                item { ActionsCard(state, onUninstall = { showUninstall = true }) }
                item { RuntimeSectionTitle("Debian 子系统") }
                item { SubsystemCard(currentActive) }
                item { RuntimeSectionTitle("监控与诊断") }
                item { StorageCard(storage) }
                item { ProcessCard(diag) }
                item { EnvInfoCard(state, meta, diag) }
                item { DiagnosticCard(diag, meta) { showLog = true } }
                item {
                    Spacer(Modifier.height(bottomInnerPadding))
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

// ------------------------------------------------------------------ 子系统

@Composable
private fun SubsystemCard(isActive: Boolean) {
    val context = LocalContext.current
    val subState by SubsystemManager.state.collectAsState()
    val installed = SubsystemManager.isInstalled(context)
    var showUninstall by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }
    var showFlavor by remember { mutableStateOf(false) }
    var showCompare by remember { mutableStateOf(false) }
    var shellEnabled by remember { mutableStateOf(AppSettings.subsystemShellEnabled(context)) }
    val flavor by remember { mutableStateOf(AppSettings.subsystemFlavor(context)) }
    // 非激活页只空转 delay，不更新 subSize（目录全树遍历开销大），避免触发重组与 backdrop 重录
    val currentActive by rememberUpdatedState(isActive)
    val subSize by produceState(0L) {
        while (true) {
            if (currentActive) value = SubsystemManager.subsystemSize(context)
            delay(15000)
        }
    }
    val busy = subState.phase == SubsystemPhase.DOWNLOADING || subState.phase == SubsystemPhase.EXTRACTING
    Card(
        modifier = Modifier
            .padding(top = 12.dp)
            .fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Storage,
                    contentDescription = "子系统",
                    modifier = Modifier.padding(end = 6.dp),
                    tint = colorScheme.onBackground,
                )
                Text(
                    text = "子系统（${flavorLabel(flavor)}）",
                    fontWeight = FontWeight.Medium,
                    color = colorScheme.onSurface,
                )
            }
            if (busy) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .padding(horizontal = 18.dp, vertical = 8.dp)
                        .fillMaxWidth(),
                    progress = if (subState.progress > 0f) subState.progress else null,
                )
                Text(
                    text = subState.message,
                    fontSize = 12.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                )
            } else if (installed) {
                SwitchPreference(
                    title = "agent Shell 使用子系统",
                    summary = "DSH 命令在 ${flavorLabel(flavor)} 中执行（proot），重启服务生效",
                    checked = shellEnabled,
                    onCheckedChange = { enabled ->
                        shellEnabled = enabled
                        AppSettings.setSubsystemShellEnabled(context, enabled)
                        Toast.makeText(context, "已更新，重启服务后生效", Toast.LENGTH_SHORT).show()
                    },
                )
                ArrowPreference(
                    title = "切换发行版",
                    summary = "当前 ${flavorLabel(flavor)} · 点击选择 Debian / Ubuntu",
                    onClick = { showFlavor = true },
                )
                ArrowPreference(
                    title = "查看子系统日志",
                    summary = "安装/运行日志 · 占用 ${UpdateManager.formatBytes(subSize)}",
                    onClick = { showLog = true },
                )
                ArrowPreference(
                    title = "卸载子系统",
                    summary = "删除 ${flavorLabel(flavor)} 环境，保留 Termux 运行时",
                    onClick = { showUninstall = true },
                )
            } else {
                ArrowPreference(
                    title = "拉取并安装子系统",
                    summary = "${flavorLabel(flavor)} · proot 免 root · 点击选择发行版",
                    onClick = { showFlavor = true },
                )
            }
            ArrowPreference(
                title = "Debian 与 Ubuntu 对比",
                summary = "不同发行版的优势与劣势",
                onClick = { showCompare = true },
            )
        }
    }
    if (showUninstall) {
        ConfirmDialog(
            show = true,
            title = "卸载子系统",
            message = "将删除 ${flavorLabel(flavor)} 子系统（rootfs），Termux 运行时与数据保留。",
            confirmText = "卸载",
            onConfirm = {
                showUninstall = false
                SubsystemManager.uninstallSubsystem()
            },
            onDismiss = { showUninstall = false },
        )
    }
    if (showLog) {
        SubsystemLogDialog(onDismiss = { showLog = false })
    }
    if (showFlavor) {
        SubsystemFlavorDialog(
            current = flavor,
            onConfirm = { newFlavor ->
                showFlavor = false
                if (newFlavor != flavor) {
                    SubsystemManager.installSubsystem(context, newFlavor)
                } else if (!installed) {
                    SubsystemManager.installSubsystem(context, newFlavor)
                }
            },
            onDismiss = { showFlavor = false },
        )
    }
    if (showCompare) {
        SubsystemCompareDialog(onDismiss = { showCompare = false })
    }
}

/** 发行版显示名。 */
private fun flavorLabel(flavor: String): String = when (flavor) {
    AppSettings.SUBSYSTEM_UBUNTU -> "Ubuntu 24.04"
    else -> "Debian 12"
}

/** 发行版选择对话框：Debian / Ubuntu，附带一句定位提示。 */
@Composable
private fun SubsystemFlavorDialog(current: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var selected by remember { mutableStateOf(current) }
    WindowDialog(
        show = true,
        title = "选择子系统发行版",
        onDismissRequest = onDismiss,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                text = "选择后自动下载安装对应 rootfs；切换发行版会先卸载当前子系统。",
                fontSize = 13.sp,
                color = colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(6.dp))
            RadioButtonPreference(
                title = "Debian 12（Bookworm）",
                summary = "稳定、体积小、兼容验证充分 · 默认",
                selected = selected == AppSettings.SUBSYSTEM_DEBIAN,
                onClick = { selected = AppSettings.SUBSYSTEM_DEBIAN },
            )
            RadioButtonPreference(
                title = "Ubuntu 24.04（Noble）",
                summary = "更新、工具链新、LTS 支持周期长",
                selected = selected == AppSettings.SUBSYSTEM_UBUNTU,
                onClick = { selected = AppSettings.SUBSYSTEM_UBUNTU },
            )
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(
                    text = "取消",
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = "确定",
                    onClick = { onConfirm(selected) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

/** 发行版对比对话框：优势 / 劣势一览。 */
@Composable
private fun SubsystemCompareDialog(onDismiss: () -> Unit) {
    WindowDialog(
        show = true,
        title = "子系统发行版对比",
        onDismissRequest = onDismiss,
    ) {
        Column(Modifier.fillMaxWidth()) {
            CompareBlock(
                title = "Debian 12（Bookworm）",
                pros = listOf("系统稳定，适合长期运行", "体积小，下载与占用更省", "与 DSHM/proot 兼容验证充分，默认选择"),
                cons = listOf("软件包版本相对较旧", "非滚动更新，新特性到得慢"),
            )
            Spacer(Modifier.height(10.dp))
            CompareBlock(
                title = "Ubuntu 24.04（Noble）",
                pros = listOf("软件与工具链更新（Python/Node 等）", "LTS 支持周期长（至 2029）", "生态与文档更丰富"),
                cons = listOf("体积稍大，占用略高", "与 DSHM 官方适配验证相对较少"),
            )
            Spacer(Modifier.height(14.dp))
            TextButton(
                text = "关闭",
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun CompareBlock(title: String, pros: List<String>, cons: List<String>) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = title,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = colorScheme.onBackground,
        )
        Spacer(Modifier.height(4.dp))
        pros.forEach { item ->
            Text(
                text = "· 优势：$item",
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = colorScheme.onSurfaceVariantSummary,
            )
        }
        cons.forEach { item ->
            Text(
                text = "· 劣势：$item",
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = Color(0xFFB0643A),
            )
        }
    }
}

@Composable
private fun SubsystemLogDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var refreshKey by remember { mutableStateOf(0) }
    val log by produceState("", refreshKey) {
        value = SubsystemManager.tailLog(context, 200)
    }
    WindowDialog(
        show = true,
        title = "子系统日志",
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

/** 响应式网格的分区标题（占满整行，带外边距）。 */
@Composable
private fun RuntimeSectionTitle(title: String) {
    Text(
        text = title,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 2.dp),
    )
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
