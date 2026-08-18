package com.siliconleap.app.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.siliconleap.app.BuildConfig
import com.siliconleap.app.R
import com.siliconleap.app.runtime.AddonManager
import com.siliconleap.app.runtime.AppSettings
import com.siliconleap.app.runtime.BackgroundGuard
import com.siliconleap.app.runtime.RootManager
import com.siliconleap.app.runtime.SourceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.siliconleap.app.runtime.RuntimeManager
import com.siliconleap.app.runtime.RuntimeState
import com.siliconleap.app.runtime.ThemeStore
import com.siliconleap.app.runtime.UpdateManager
import com.siliconleap.app.ui.component.BlurredBar
import com.siliconleap.app.ui.component.ConfirmDialog
import com.siliconleap.app.ui.component.UpdateDialog
import com.siliconleap.app.ui.component.rememberBlurBackdrop
import java.io.File
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
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

/** 设置页：复刻 KernelSU 设置页分组卡布局（响应式网格 + 分区标题）。 */
@Composable
private fun SettingsSectionTitle(title: String) {
    Text(
        text = title,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 2.dp),
    )
}

@Composable
fun SettingsScreen(state: RuntimeState, bottomInnerPadding: Dp, isActive: Boolean = true) {
    val scrollBehavior = MiuixScrollBehavior()
    val context = LocalContext.current
    val backdrop = rememberBlurBackdrop(enableBlur = true)
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else colorScheme.surface
    var showUninstall by remember { mutableStateOf(false) }
    var showClearData by remember { mutableStateOf(false) }
    val updateState by UpdateManager.state.collectAsState()
    var showUpdate by remember { mutableStateOf(false) }

    LaunchedEffect(updateState.available) {
        if (updateState.available != null) showUpdate = true
    }

    Scaffold(
        topBar = {
            BlurredBar(backdrop) {
                TopAppBar(
                    color = barColor,
                    title = "设置",
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
                item { SettingsSectionTitle("外观") }
                item { ThemeCard() }
                item { SettingsSectionTitle("服务与更新") }
                item { ServiceCard(state) }
                item { BackgroundGuardCard() }
                item { UpdateCard() }
                item { SettingsSectionTitle("下载源") }
                item { SourceCard() }
                item { SettingsSectionTitle("工作区与权限") }
                item { WorkspaceCard() }
                item { RunModeCard() }
                item { RootShellCard() }
                item { SettingsSectionTitle("数据管理") }
                item { DataCard(state, onUninstall = { showUninstall = true }, onClearData = { showClearData = true }) }
                item { SettingsSectionTitle("全能优化") }
                item { MobileUiCard() }
                item { SettingsSectionTitle("关于") }
                item { AboutCard() }
                item { AboutLinkCard() }
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

    ConfirmDialog(
        show = showClearData,
        title = "清空会话与设置数据",
        message = "将删除 dsh-home 下的全部会话与设置数据（含 WebUI 配置），运行时与工作区保留。此操作不可撤销。",
        confirmText = "清空",
        onConfirm = {
            showClearData = false
            RuntimeManager.clearData()
        },
        onDismiss = { showClearData = false },
    )

    updateState.available?.let { info ->
        if (showUpdate) {
            UpdateDialog(
                info = info,
                downloading = updateState.downloading,
                progress = updateState.progress,
                hasPendingDownload = UpdateManager.hasPendingDownload(info),
                onDownload = { UpdateManager.downloadAndInstall(context, info) },
                onDismiss = { showUpdate = false },
            )
        }
    }
}

@Composable
private fun UpdateCard() {
    val context = LocalContext.current
    val updateState by UpdateManager.state.collectAsState()
    var autoUpdate by remember { mutableStateOf(AppSettings.autoUpdate(context)) }
    Card(
        modifier = Modifier
            .padding(top = 12.dp)
            .fillMaxWidth(),
    ) {
        SwitchPreference(
            title = "自动检测更新",
            summary = "启动时自动检查新版 DSHM",
            startAction = {
                Icon(
                    imageVector = Icons.Rounded.RestartAlt,
                    contentDescription = "自动检测更新",
                    modifier = Modifier.padding(end = 6.dp),
                    tint = colorScheme.onBackground,
                )
            },
            checked = autoUpdate,
            onCheckedChange = { enabled ->
                autoUpdate = enabled
                AppSettings.setAutoUpdate(context, enabled)
            },
        )
        ArrowPreference(
            title = "检查更新",
            summary = when {
                updateState.downloading -> updateState.message
                updateState.checking -> "正在检查更新…"
                updateState.available != null -> "发现新版本 ${updateState.available?.versionName}"
                updateState.message.isNotBlank() -> updateState.message
                else -> "当前版本 ${BuildConfig.VERSION_NAME}"
            },
            startAction = {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = "检查更新",
                    modifier = Modifier.padding(end = 6.dp),
                    tint = colorScheme.onBackground,
                )
            },
            onClick = { UpdateManager.checkForUpdate(context, force = true) },
        )
    }
}

@Composable
private fun ThemeCard() {
    val context = LocalContext.current
    val mode by ThemeStore.modeFlow.collectAsState()
    var lightCenter by remember { mutableStateOf(Offset(0f, 0f)) }
    var darkCenter by remember { mutableStateOf(Offset(0f, 0f)) }

    Card(
        modifier = Modifier
            .padding(top = 12.dp)
            .fillMaxWidth(),
    ) {
        BasicComponent(
            title = "主题",
            summary = when (mode) {
                ThemeStore.MODE_LIGHT -> "白天"
                ThemeStore.MODE_DARK -> "黑夜"
                else -> "跟随系统"
            },
            startAction = {
                Icon(
                    imageVector = if (mode == ThemeStore.MODE_DARK) Icons.Rounded.DarkMode else Icons.Rounded.LightMode,
                    contentDescription = "主题",
                    modifier = Modifier.padding(end = 6.dp),
                    tint = colorScheme.onBackground,
                )
            },
            endActions = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
                ) {
                    ThemeButton(
                        icon = Icons.Rounded.LightMode,
                        active = mode != ThemeStore.MODE_DARK,
                        modifier = Modifier.onGloballyPositioned { coords ->
                            lightCenter = coords.positionInWindow() +
                                Offset(coords.size.width / 2f, coords.size.height / 2f)
                        },
                        onClick = {
                            ThemeStore.setMode(context, ThemeStore.MODE_LIGHT, lightCenter)
                        },
                    )
                    ThemeButton(
                        icon = Icons.Rounded.DarkMode,
                        active = mode == ThemeStore.MODE_DARK,
                        modifier = Modifier.onGloballyPositioned { coords ->
                            darkCenter = coords.positionInWindow() +
                                Offset(coords.size.width / 2f, coords.size.height / 2f)
                        },
                        onClick = {
                            ThemeStore.setMode(context, ThemeStore.MODE_DARK, darkCenter)
                        },
                    )
                }
            },
        )
    }
}

@Composable
private fun ThemeButton(
    icon: ImageVector,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(if (active) colorScheme.primary else colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (active) colorScheme.onPrimary else colorScheme.onSurfaceVariantSummary,
        )
    }
}

@Composable
private fun BackgroundGuardCard() {
    val context = LocalContext.current
    var exempt by remember { mutableStateOf(BackgroundGuard.isIgnoringBatteryOptimizations(context)) }
    val canRequest = remember { BackgroundGuard.canRequestBatteryOptimization(context) }
    Card(
        modifier = Modifier
            .padding(top = 12.dp)
            .fillMaxWidth(),
    ) {
        ArrowPreference(
            title = "后台保护",
            summary = when {
                exempt -> "已加入「忽略电池优化」白名单，后台服务不易被杀"
                canRequest -> "电量优化可能杀掉后台服务，点击请求豁免"
                else -> "系统限制下无法自动豁免，可点击进入应用详情手动设置"
            },
            startAction = {
                Icon(
                    imageVector = if (exempt) Icons.Rounded.Shield else Icons.Rounded.BatteryAlert,
                    contentDescription = "后台保护",
                    modifier = Modifier.padding(end = 6.dp),
                    tint = if (exempt) colorScheme.onBackground else colorScheme.error,
                )
            },
            onClick = {
                if (exempt) {
                    BackgroundGuard.openAppDetails(context)
                } else if (canRequest) {
                    BackgroundGuard.requestIgnoreBatteryOptimizations(context)
                } else {
                    BackgroundGuard.openAppDetails(context)
                }
            },
        )
        ArrowPreference(
            title = "应用详情（电池/自启动）",
            summary = "打开系统应用详情，可设置电池无限制、自启动、锁定后台",
            startAction = {
                Icon(
                    imageVector = Icons.Rounded.Settings,
                    contentDescription = "应用详情",
                    modifier = Modifier.padding(end = 6.dp),
                    tint = colorScheme.onBackground,
                )
            },
            onClick = { BackgroundGuard.openAppDetails(context) },
        )
    }
}

@Composable
private fun ServiceCard(state: RuntimeState) {
    val context = LocalContext.current
    var autoStart by remember { mutableStateOf(AppSettings.autoStartService(context)) }
    Card(
        modifier = Modifier
            .padding(top = 12.dp)
            .fillMaxWidth(),
    ) {
        SwitchPreference(
            title = "打开应用时自动启动服务",
            summary = "运行时已安装时自动后台启动 Harness",
            startAction = {
                Icon(
                    imageVector = Icons.Rounded.PowerSettingsNew,
                    contentDescription = "自动启动服务",
                    modifier = Modifier.padding(end = 6.dp),
                    tint = colorScheme.onBackground,
                )
            },
            checked = autoStart,
            onCheckedChange = { enabled ->
                autoStart = enabled
                AppSettings.setAutoStartService(context, enabled)
            },
        )
        ArrowPreference(
            title = "服务端口",
            summary = "http://127.0.0.1:${state.port}",
            startAction = {
                Icon(
                    imageVector = Icons.Rounded.Info,
                    contentDescription = "服务端口",
                    modifier = Modifier.padding(end = 6.dp),
                    tint = colorScheme.onBackground,
                )
            },
            onClick = {},
        )
        ArrowPreference(
            title = "重启服务",
            summary = "停止并重新启动 Harness 服务",
            startAction = {
                Icon(
                    imageVector = Icons.Rounded.RestartAlt,
                    contentDescription = "重启服务",
                    modifier = Modifier.padding(end = 6.dp),
                    tint = colorScheme.onBackground,
                )
            },
            onClick = { RuntimeManager.restart() },
        )
    }
}

@Composable
private fun SourceCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf(AppSettings.downloadSource(context)) }
    var showDialog by remember { mutableStateOf(false) }
    val summary = when (source) {
        AppSettings.SOURCE_AUTO -> "自动测速选择（推荐）"
        AppSettings.SOURCE_GHPROXY_AXISNOW -> "GHProxy AxisNow"
        AppSettings.SOURCE_GHPROXY_CF -> "GHProxy Cloudflare"
        AppSettings.SOURCE_CUSTOM -> "自定义镜像"
        else -> "GitHub"
    }
    Card(
        modifier = Modifier
            .padding(top = 12.dp)
            .fillMaxWidth(),
    ) {
        BasicComponent(
            title = "下载源",
            summary = summary,
            startAction = {
                Icon(
                    imageVector = Icons.Rounded.CloudDownload,
                    contentDescription = "下载源",
                    modifier = Modifier.padding(end = 6.dp),
                    tint = colorScheme.onBackground,
                )
            },
            endActions = {
                Icon(
                    imageVector = Icons.Rounded.Edit,
                    tint = colorScheme.onSurface,
                    contentDescription = "修改",
                )
            },
            onClick = { showDialog = true },
        )
    }
    if (showDialog) {
        SourceDialog(
            onConfirm = { newSource ->
                source = newSource
                if (newSource != AppSettings.SOURCE_AUTO) {
                    RuntimeManager.refreshSource(context)
                }
                Toast.makeText(context, "下载源已更新，重新拉取时生效", Toast.LENGTH_SHORT).show()
                showDialog = false
            },
            onDismiss = { showDialog = false },
        )
    }
}

@Composable
private fun SourceDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf(AppSettings.downloadSource(context)) }
    var customUrl by remember { mutableStateOf(AppSettings.customMetaUrl(context)) }
    var testing by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<SourceManager.SpeedResult>?>(null) }

    fun runSpeedTest() {
        testing = true
        results = null
        scope.launch {
            results = withContext(Dispatchers.IO) { SourceManager.speedTest() }
            testing = false
        }
    }

    LaunchedEffect(Unit) {
        if (AppSettings.downloadSource(context) == AppSettings.SOURCE_AUTO) runSpeedTest()
    }

    WindowDialog(
        show = true,
        title = "下载源",
        onDismissRequest = onDismiss,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                text = "选择运行时与更新包的下载地址。自动模式会对各源测速，选最快节点。",
                fontSize = 13.sp,
                color = colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(6.dp))
            RadioButtonPreference(
                title = "自动选择（测速）",
                summary = "下载前对各节点测速，自动选最快源 · 推荐",
                selected = selected == AppSettings.SOURCE_AUTO,
                onClick = { selected = AppSettings.SOURCE_AUTO },
            )
            RadioButtonPreference(
                title = "GHProxy AxisNow",
                summary = "axisnow.gh-proxy.org · 三网优选节点加速",
                selected = selected == AppSettings.SOURCE_GHPROXY_AXISNOW,
                onClick = { selected = AppSettings.SOURCE_GHPROXY_AXISNOW },
            )
            RadioButtonPreference(
                title = "GHProxy Cloudflare",
                summary = "v6.gh-proxy.org · Cloudflare V4/V6 优选加速",
                selected = selected == AppSettings.SOURCE_GHPROXY_CF,
                onClick = { selected = AppSettings.SOURCE_GHPROXY_CF },
            )
            RadioButtonPreference(
                title = "GitHub",
                summary = "github.com/RochelimitDawn/DSHM 直连下载",
                selected = selected == AppSettings.SOURCE_GITHUB,
                onClick = { selected = AppSettings.SOURCE_GITHUB },
            )
            RadioButtonPreference(
                title = "自定义",
                summary = "自建镜像的 metadata.json 地址",
                selected = selected == AppSettings.SOURCE_CUSTOM,
                onClick = { selected = AppSettings.SOURCE_CUSTOM },
            )
            if (selected == AppSettings.SOURCE_CUSTOM) {
                PathInput(
                    value = customUrl,
                    placeholder = "https://…/metadata.json",
                    onValueChange = { customUrl = it },
                )
            }
            if (selected == AppSettings.SOURCE_AUTO) {
                Spacer(Modifier.height(8.dp))
                when {
                    testing -> Text(
                        text = "正在测速各节点…",
                        fontSize = 12.sp,
                        color = colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(start = 16.dp),
                    )
                    results != null && results!!.isEmpty() -> Text(
                        text = "所有节点测速失败，将回退 AxisNow",
                        fontSize = 12.sp,
                        color = colorScheme.error,
                        modifier = Modifier.padding(start = 16.dp),
                    )
                    results != null -> {
                        Text(
                            text = "测速结果（由快到慢）：",
                            fontSize = 12.sp,
                            color = colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(start = 16.dp, bottom = 4.dp),
                        )
                        results!!.sortedBy { it.estimatedMs }.forEach { r ->
                            val speed = if (r.speedKBps > 0.0) {
                                String.format("%.1f MB/s", r.speedKBps / 1024.0)
                            } else {
                                "未测速"
                            }
                            Text(
                                text = "  ${sourceLabel(r.source)} · 延迟 ${r.latencyMs}ms · $speed",
                                fontSize = 13.sp,
                                color = colorScheme.onSurface,
                                modifier = Modifier.padding(start = 16.dp, bottom = 2.dp),
                            )
                        }
                    }
                }
                TextButton(
                    text = if (testing) "测速中…" else "重新测速",
                    onClick = { if (!testing) runSpeedTest() },
                    modifier = Modifier.padding(start = 12.dp, top = 4.dp),
                    enabled = !testing,
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(
                    text = "取消",
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = "确定",
                    onClick = {
                        AppSettings.setDownloadSource(context, selected)
                        if (selected == AppSettings.SOURCE_CUSTOM) {
                            AppSettings.setCustomMetaUrl(context, customUrl.trim())
                        }
                        onConfirm(selected)
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

@Composable
private fun sourceLabel(source: String): String = when (source) {
    AppSettings.SOURCE_GHPROXY_AXISNOW -> "AxisNow"
    AppSettings.SOURCE_GHPROXY_CF -> "Cloudflare"
    AppSettings.SOURCE_GITHUB -> "GitHub"
    else -> source
}

@Composable
private fun PathInput(
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colorScheme.surfaceContainer)
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            textStyle = MiuixTheme.textStyles.main.copy(color = colorScheme.onBackground),
            singleLine = true,
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        fontSize = 14.sp,
                        color = colorScheme.onSurfaceVariantSummary,
                    )
                }
                inner()
            },
        )
    }
}

@Composable
private fun WorkspaceCard() {
    val context = LocalContext.current
    var workspacePath by remember { mutableStateOf(AppSettings.workspacePath(context)) }
    var hasAccess by remember { mutableStateOf(hasAllFilesAccess()) }
    var writable by remember { mutableStateOf(RuntimeManager.workspaceWritable(context)) }
    var showEdit by remember { mutableStateOf(false) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val granted = hasAllFilesAccess()
                if (granted && !hasAccess) {
                    // 授权刚生效：MANAGE_EXTERNAL_STORAGE 在部分设备需重启进程才生效
                    Toast.makeText(context, "文件访问权限已授予；如仍无法写入请完全关闭应用后重开", Toast.LENGTH_LONG).show()
                }
                hasAccess = granted
                writable = RuntimeManager.workspaceWritable(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val pickDirectory = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val resolved = resolveDocumentTreePath(uri)
            if (resolved != null) {
                AppSettings.setWorkspacePath(context, resolved)
                workspacePath = resolved
                showEdit = false
                if (hasAllFilesAccess()) {
                    Toast.makeText(context, "工作区已设为：$resolved", Toast.LENGTH_LONG).show()
                    RuntimeManager.restart()
                } else {
                    Toast.makeText(context, "已保存。外部目录需先授予「文件访问权限」再重启服务生效", Toast.LENGTH_LONG).show()
                }
            } else {
                Toast.makeText(context, "无法解析所选目录，请手动输入路径", Toast.LENGTH_LONG).show()
            }
        }
    }

    Card(
        modifier = Modifier
            .padding(top = 12.dp)
            .fillMaxWidth(),
    ) {
        BasicComponent(
            title = "工作区目录",
            summary = workspacePath,
            startAction = {
                Icon(
                    imageVector = Icons.Rounded.FolderOpen,
                    contentDescription = "工作区目录",
                    modifier = Modifier.padding(end = 6.dp),
                    tint = colorScheme.onBackground,
                )
            },
            endActions = {
                Icon(
                    imageVector = Icons.Rounded.Edit,
                    tint = colorScheme.onSurface,
                    contentDescription = "修改",
                )
            },
            onClick = { showEdit = true },
        )
        ArrowPreference(
            title = "文件访问权限",
            summary = if (hasAccess) "已授予「所有文件访问」，可读写公共存储" else "未授予：工作区仅限应用私有目录",
            startAction = {
                Icon(
                    imageVector = if (hasAccess) Icons.Rounded.Shield else Icons.Rounded.Lock,
                    contentDescription = "文件访问权限",
                    modifier = Modifier.padding(end = 6.dp),
                    tint = colorScheme.onBackground,
                )
            },
            onClick = {
                if (!hasAccess) openManageAllFilesSettings(context)
            },
        )
        ArrowPreference(
            title = "工作区可写性",
            summary = if (writable) {
                "可写，正常工作"
            } else {
                "不可写：请授予文件访问权限并重启应用，或将工作区恢复到应用私有目录"
            },
            startAction = {
                Icon(
                    imageVector = if (writable) Icons.Rounded.CheckCircle else Icons.Rounded.Warning,
                    contentDescription = "工作区可写性",
                    modifier = Modifier.padding(end = 6.dp),
                    tint = colorScheme.onBackground,
                )
            },
            onClick = {
                writable = RuntimeManager.workspaceWritable(context)
                Toast.makeText(
                    context,
                    if (writable) "工作区可写" else "工作区不可写：请检查权限或更换目录",
                    Toast.LENGTH_LONG,
                ).show()
            },
        )
        ArrowPreference(
            title = "恢复默认工作区",
            summary = "切换到应用私有目录 workspace",
            startAction = {
                Icon(
                    imageVector = Icons.Rounded.RestartAlt,
                    contentDescription = "恢复默认工作区",
                    modifier = Modifier.padding(end = 6.dp),
                    tint = colorScheme.onBackground,
                )
            },
            onClick = {
                AppSettings.resetWorkspacePath(context)
                workspacePath = AppSettings.workspacePath(context)
                Toast.makeText(context, "已恢复默认工作区", Toast.LENGTH_SHORT).show()
                RuntimeManager.restart()
            },
        )
    }

    if (showEdit) {
        WorkspaceDialog(
            currentPath = workspacePath,
            onPickDirectory = { pickDirectory.launch(null) },
            onConfirm = { newPath ->
                val trimmed = newPath.trim()
                if (trimmed.isNotEmpty()) {
                    val isPrivate = trimmed.startsWith(context.filesDir.absolutePath)
                    val externalNeedsGrant = !isPrivate && !hasAllFilesAccess()
                    if (externalNeedsGrant) {
                        Toast.makeText(context, "外部目录需先授予「文件访问权限」", Toast.LENGTH_LONG).show()
                    }
                    val dir = File(trimmed)
                    val created = runCatching { dir.mkdirs() }.getOrDefault(false)
                    AppSettings.setWorkspacePath(context, trimmed)
                    workspacePath = trimmed
                    showEdit = false
                    Toast.makeText(
                        context,
                        if (created) "工作区已设为：$trimmed" else "目录无法创建，请检查路径与权限",
                        Toast.LENGTH_LONG,
                    ).show()
                    if (created && !externalNeedsGrant) RuntimeManager.restart()
                } else {
                    Toast.makeText(context, "路径不能为空", Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { showEdit = false },
        )
    }
}

@Composable
private fun WorkspaceDialog(
    currentPath: String,
    onPickDirectory: () -> Unit,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var path by remember { mutableStateOf(currentPath) }
    WindowDialog(
        show = true,
        title = "工作区目录",
        onDismissRequest = onDismiss,
    ) {
        Text(
            text = "工作区是 dsh 的项目与文件操作根目录。可设在公共存储（如 /storage/emulated/0/DSHM），需先授予「文件访问权限」；应用升级与既有数据不受影响。",
            fontSize = 13.sp,
            color = colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.height(10.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(colorScheme.surfaceContainer)
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            BasicTextField(
                value = path,
                onValueChange = { path = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                textStyle = MiuixTheme.textStyles.main.copy(color = colorScheme.onBackground),
                singleLine = true,
            )
        }
        Spacer(Modifier.height(14.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(
                text = "目录",
                onClick = onPickDirectory,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            TextButton(
                text = "取消",
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            TextButton(
                text = "确定",
                onClick = { onConfirm(path) },
                 modifier = Modifier.weight(1f),
                 colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

@Composable
private fun RunModeCard() {
    val context = LocalContext.current
    val mode by RuntimeManager.runMode.collectAsState()
    val summary = when (mode) {
        AppSettings.RUN_MODE_ROOT -> "Root 分区 · 真 root 宿主执行"
        AppSettings.RUN_MODE_CONTAINER -> "容器分区 · proot（Termux + Debian）"
        else -> "未选择"
    }
    Card(
        modifier = Modifier
            .padding(top = 12.dp)
            .fillMaxWidth(),
    ) {
        ArrowPreference(
            title = "运行分区",
            summary = "$summary · 点击重新选择",
            startAction = {
                Icon(
                    imageVector = Icons.Rounded.Security,
                    contentDescription = "运行分区",
                    modifier = Modifier.padding(end = 6.dp),
                    tint = colorScheme.onBackground,
                )
            },
            onClick = {
                RuntimeManager.stopServer()
                RuntimeManager.setRunMode(context, "")
                Toast.makeText(context, "请重新选择运行分区", Toast.LENGTH_SHORT).show()
            },
        )
    }
}

@Composable
private fun RootShellCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(AppSettings.rootShellEnabled(context)) }
    var granted by remember { mutableStateOf(RootManager.isGranted()) }
    var rootAvailable = RootManager.rootAvailable()

    Card(
        modifier = Modifier
            .padding(top = 12.dp)
            .fillMaxWidth(),
    ) {
        SwitchPreference(
            title = "Root Shell（可选）",
            summary = when {
                !rootAvailable -> "设备未检测到 root（su 不存在）"
                granted -> "已获得 root 授权，agent 命令以真 root 执行"
                enabled -> "已开启，需授权后生效（Magisk/KernelSU）"
                else -> "开启将请求 root 管理器授权（Magisk/KernelSU）"
            },
            startAction = {
                Icon(
                    imageVector = Icons.Rounded.Lock,
                    contentDescription = "Root Shell",
                    modifier = Modifier.padding(end = 6.dp),
                    tint = colorScheme.onBackground,
                )
            },
            checked = enabled,
            enabled = rootAvailable,
            onCheckedChange = { on ->
                if (on) {
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { RootManager.requestRoot() }
                        if (ok) {
                            enabled = true
                            granted = true
                            AppSettings.setRootShellEnabled(context, true)
                            Toast.makeText(context, "已获得 root 权限，重启服务后生效", Toast.LENGTH_LONG).show()
                        } else {
                            RootManager.clearGrant()
                            Toast.makeText(context, "未获得 root 授权，请检查 root 管理器", Toast.LENGTH_LONG).show()
                        }
                    }
                } else {
                    enabled = false
                    granted = false
                    RootManager.clearGrant()
                    AppSettings.setRootShellEnabled(context, false)
                    Toast.makeText(context, "已关闭 Root Shell，重启服务后生效", Toast.LENGTH_SHORT).show()
                }
            },
        )
        ArrowPreference(
            title = "关于 Root Shell",
            summary = "开启后 agent 命令以真 root 在宿主 Android 执行（替换 proot 子系统）。可访问系统文件与设备，需设备已 root 并在 Magisk/KernelSU 中授权；未授权自动回退。",
        )
    }
}

@Composable
private fun MobileUiCard() {
    val context = LocalContext.current
    val installed = AddonManager.isInstalled()
    val compatTotal = AddonManager.compatPluginIds.size
    val compatDone = AddonManager.compatPluginIds.count { AddonManager.isCompatInstalled(it) }
    Card(
        modifier = Modifier
            .padding(top = 12.dp)
            .fillMaxWidth(),
    ) {
        ArrowPreference(
            title = "WebUI 全能优化",
            summary = if (installed) {
                "dsh-mobile-nav 移动端适配已装配（PiUI 翻页器）· 兼容插件 $compatDone/$compatTotal"
            } else {
                "整合移动端适配与推荐插件，首次启动服务时自动装配"
            },
            startAction = {
                Icon(
                    imageVector = Icons.Rounded.Info,
                    contentDescription = "WebUI 全能优化",
                    modifier = Modifier.padding(end = 6.dp),
                    tint = colorScheme.onBackground,
                )
            },
            onClick = {
                if (!installed) {
                    Toast.makeText(context, "重启服务后将自动装配 WebUI 全能优化与推荐插件", Toast.LENGTH_SHORT).show()
                    RuntimeManager.restart()
                }
            },
        )
    }
}

@Composable
private fun DataCard(state: RuntimeState, onUninstall: () -> Unit, onClearData: () -> Unit) {
    Card(
        modifier = Modifier
            .padding(top = 12.dp)
            .fillMaxWidth(),
    ) {
        ArrowPreference(
            title = "清空会话与设置数据",
            summary = "保留运行时与工作区",
            startAction = {
                Icon(
                    imageVector = Icons.Rounded.DeleteSweep,
                    contentDescription = "清空会话与设置数据",
                    modifier = Modifier.padding(end = 6.dp),
                    tint = colorScheme.onBackground,
                )
            },
            onClick = onClearData,
        )
        if (state.installed) {
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

@Composable
private fun AboutCard() {
    val mode by ThemeStore.modeFlow.collectAsState()
    val systemDark = isSystemInDarkTheme()
    val dark = when (mode) {
        ThemeStore.MODE_DARK -> true
        ThemeStore.MODE_LIGHT -> false
        else -> systemDark
    }
    Card(
        modifier = Modifier
            .padding(vertical = 12.dp)
            .fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (dark) Color(0xFFF2F2F3) else Color.Transparent),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(
                        if (dark) R.drawable.ic_deepseek_logo_black else R.drawable.ic_deepseek_logo_blue,
                    ),
                    contentDescription = "DSHM",
                    modifier = Modifier.size(30.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = "DSHM",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = colorScheme.onBackground,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "Deepseek Harness Mobile",
                    fontSize = 12.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        Text(
            text = "基于 DeepSeek Harness 的移动端封装。运行时在线下载，服务经系统浏览器使用。",
            fontSize = 12.sp,
            color = colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 4.dp),
        )
        Text(
            text = "版本：${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            fontSize = 12.sp,
            color = colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 12.dp),
        )
    }
}

/** 了解 DSHM：项目与使用说明跳转链接（从首页关于迁移至此）。 */
@Composable
private fun AboutLinkCard() {
    val context = LocalContext.current
    Card(
        modifier = Modifier
            .padding(top = 12.dp)
            .fillMaxWidth(),
    ) {
        ArrowPreference(
            title = "了解 DSHM",
            summary = "GitHub 项目主页与使用说明",
            startAction = {
                Icon(
                    imageVector = Icons.Rounded.Link,
                    contentDescription = "了解 DSHM",
                    modifier = Modifier.padding(end = 6.dp),
                    tint = colorScheme.onBackground,
                )
            },
            onClick = { openUrl(context, "https://github.com/RochelimitDawn/DSHM") },
        )
    }
}

private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}

private fun hasAllFilesAccess(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()

private fun openManageAllFilesSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            ),
        )
    }.getOrElse {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }
}

/** 将 SAF 目录选择器返回的 content:// URI 解析为真实文件系统路径（主存储可靠，SD 卡尽力）。 */
private fun resolveDocumentTreePath(uri: Uri): String? = runCatching {
    val docId = DocumentsContract.getTreeDocumentId(uri) ?: return@runCatching null
    when {
        docId.startsWith("primary:") -> "/storage/emulated/0/" + docId.removePrefix("primary:")
        docId.contains(":") -> {
            val volume = docId.substringBefore(":")
            "/storage/$volume/" + docId.substringAfter(":")
        }
        else -> null
    }
}.getOrNull()
