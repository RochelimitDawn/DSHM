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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.ui.graphics.ColorFilter
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
import androidx.compose.material.icons.rounded.Mouse
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import androidx.compose.ui.text.style.TextOverflow
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
import com.siliconleap.app.runtime.SubsystemManager
import com.siliconleap.app.runtime.TermuxEnv
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

/**
 * 设置分区标题：彩色分区 logo（左端，矢量多层配色）+ 标题 + 一句分区说明。
 * 布局风格与 KernelSU 设置页分区卡一致：小号半粗标题，说明行更浅色；
 * logo 为各分区专属彩色矢量资源（res/drawable/sec_*.xml），彼此配色与造型不同。
 */
@Composable
private fun SettingsSectionTitle(title: String, iconRes: Int, description: String? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = 12.dp, top = 14.dp, bottom = 2.dp),
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = title,
            modifier = Modifier
                .padding(end = 7.dp)
                .size(22.dp),
        )
        Column {
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = colorScheme.onSurfaceVariantSummary,
            )
            if (description != null) {
                Text(
                    text = description,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = colorScheme.onSurfaceVariantSummary.copy(alpha = 0.65f),
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
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
                // 分区编排（结合 Eta 的分区思路：按用户心智模型分组，每区一句说明 + 专属彩色 logo）：
                // 外观（主题）→ 运行（服务/后台/更新）→ 子系统（下载源/root/工作区）→
                // 体验（WebUI 优化）→ 数据（清空/卸载）→ 关于（项目信息）
                item { SettingsSectionTitle("外观", R.drawable.sec_appearance, "主题模式与黑白切换") }
                item { ThemeCard() }
                item { SettingsSectionTitle("运行", R.drawable.sec_runtime, "前台服务、后台保护与在线更新") }
                item { ServiceCard(state) }
                item { BackgroundGuardCard() }
                item { UpdateCard() }
                item { SettingsSectionTitle("子系统", R.drawable.sec_subsystem, "下载源、Root Shell、代理与工作区（引擎设置在环境页）") }
                item { SourceCard() }
                item { ProxyCard() }
                item { RootShellCard() }
                item { WorkspaceCard() }
                item { SettingsSectionTitle("体验", R.drawable.sec_experience, "WebUI 移动端优化与插件") }
                item { MobileUiCard() }
                item { SettingsSectionTitle("数据管理", R.drawable.sec_data, "会话清空与运行时卸载") }
                item { DataCard(state, onUninstall = { showUninstall = true }, onClearData = { showClearData = true }) }
                item { SettingsSectionTitle("关于", R.drawable.sec_about, "版本信息与项目链接") }
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
/** 运行 · 更新卡：自动检测更新开关 + 手动检查（状态行显示当前版本/检查进度）。 */
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

/** 外观 · 主题卡：白天/黑夜切换（圆形扩散动画，中心点取自按钮位置，与 Harness 配置双向同步）。 */
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

/** 运行 · 后台保护卡：忽略电池优化豁免 + 系统应用详情入口（前台服务防杀）。 */
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

/** 运行 · 服务卡：自动启动开关、服务端口与重启。 */
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

/**
 * 下载源 logo（品牌矢量资源，drawable-nodpi）：
 * - AxisNow → gh-proxy 官方 logo（GitHub 猫 + 闪电，深色圆角底）
 * - Cloudflare → Cloudflare 官方双色 logo（LobeHub Icons）
 * - GitHub → GitHub 官方 Octocat mark（矢量，单色，按主题着色适配黑白模式）
 * - 自动/自定义 → 主题色图标
 */
@Composable
private fun SourceLogo(source: String, size: Dp = 22.dp) {
    when (source) {
        AppSettings.SOURCE_GHPROXY_AXISNOW -> Image(
            painter = painterResource(R.drawable.res_source_ghproxy),
            contentDescription = "gh-proxy",
            modifier = Modifier.padding(end = 6.dp).size(size),
        )
        AppSettings.SOURCE_GHPROXY_CF -> Image(
            painter = painterResource(R.drawable.res_source_cloudflare),
            contentDescription = "Cloudflare",
            modifier = Modifier.padding(end = 6.dp).size(size),
        )
        AppSettings.SOURCE_GITHUB -> Image(
            painter = painterResource(R.drawable.res_source_github),
            contentDescription = "GitHub",
            modifier = Modifier.padding(end = 6.dp).size(size),
            colorFilter = ColorFilter.tint(colorScheme.onBackground),
        )
        else -> Icon(
            imageVector = Icons.Rounded.Speed,
            contentDescription = sourceLabel(source),
            modifier = Modifier.padding(end = 6.dp).size(size),
            tint = colorScheme.onBackground,
        )
    }
}

/** 子系统 · 下载源卡：运行时/子系统资产的 GitHub 下载源选择（自动测速/镜像/自定义），点击进入源选择对话框。 */
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
                SourceLogo(source, size = 24.dp)
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
        // 5 个选项 + 测速结果可能超出对话框高度：verticalScroll 保证测速结果可达
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
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
                startAction = {
                    Icon(
                        imageVector = Icons.Rounded.Speed,
                        contentDescription = "自动选择",
                        modifier = Modifier.padding(end = 6.dp),
                        tint = colorScheme.onBackground,
                    )
                },
            )
            RadioButtonPreference(
                title = "GHProxy AxisNow",
                summary = "axisnow.gh-proxy.org · 三网优选节点加速",
                selected = selected == AppSettings.SOURCE_GHPROXY_AXISNOW,
                onClick = { selected = AppSettings.SOURCE_GHPROXY_AXISNOW },
                startAction = { SourceLogo(AppSettings.SOURCE_GHPROXY_AXISNOW) },
            )
            RadioButtonPreference(
                title = "GHProxy Cloudflare",
                summary = "v6.gh-proxy.org · Cloudflare V4/V6 优选加速",
                selected = selected == AppSettings.SOURCE_GHPROXY_CF,
                onClick = { selected = AppSettings.SOURCE_GHPROXY_CF },
                startAction = { SourceLogo(AppSettings.SOURCE_GHPROXY_CF) },
            )
            RadioButtonPreference(
                title = "GitHub",
                summary = "github.com/RochelimitDawn/DSHM 直连下载",
                selected = selected == AppSettings.SOURCE_GITHUB,
                onClick = { selected = AppSettings.SOURCE_GITHUB },
                startAction = { SourceLogo(AppSettings.SOURCE_GITHUB) },
            )
            RadioButtonPreference(
                title = "自定义",
                summary = "自建镜像的 metadata.json 地址",
                selected = selected == AppSettings.SOURCE_CUSTOM,
                onClick = { selected = AppSettings.SOURCE_CUSTOM },
                startAction = {
                    Icon(
                        imageVector = Icons.Rounded.Dns,
                        contentDescription = "自定义镜像",
                        modifier = Modifier.padding(end = 6.dp),
                        tint = colorScheme.onBackground,
                    )
                },
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
                    testing -> Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "正在测速各节点…",
                            fontSize = 12.sp,
                            color = colorScheme.onSurfaceVariantSummary,
                        )
                        Spacer(Modifier.weight(1f))
                        TextButton(
                            text = "测速中…",
                            onClick = { },
                            enabled = false,
                        )
                    }
                    results != null && results!!.isEmpty() -> Text(
                        text = "所有节点测速失败，将回退 AxisNow",
                        fontSize = 12.sp,
                        color = colorScheme.error,
                        modifier = Modifier.padding(start = 16.dp),
                    )
                    results != null -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "测速结果（由快到慢）：",
                                fontSize = 12.sp,
                                color = colorScheme.onSurfaceVariantSummary,
                            )
                            Spacer(Modifier.weight(1f))
                            TextButton(
                                text = if (testing) "测速中…" else "重新测速",
                                onClick = { if (!testing) runSpeedTest() },
                                enabled = !testing,
                                colors = ButtonDefaults.textButtonColorsPrimary(),
                            )
                        }
                        results!!.sortedBy { it.estimatedMs }.forEach { r ->
                            val speed = if (r.speedKBps > 0.0) {
                                String.format("%.1f MB/s", r.speedKBps / 1024.0)
                            } else {
                                "未测速"
                            }
                            Row(
                                modifier = Modifier.padding(start = 16.dp, bottom = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                SourceLogo(r.source, size = 16.dp)
                                Text(
                                    text = "${sourceLabel(r.source)} · 延迟 ${r.latencyMs}ms · $speed",
                                    fontSize = 13.sp,
                                    color = colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
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

/** 子系统 · 工作区卡：DSH 工作目录选择（SAF 目录选择器/手动路径）+ 全部文件访问权限申请（ON_RESUME 刷新状态）。 */
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

/** 子系统 · Root Shell 卡：可选真 root 执行（Magisk/KernelSU 授权，替换子系统），未授权自动回退。 */
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

/**
 * 子系统 · 代理卡（Clash/mihomo）：TUN 透明分流，规则智能切换（国外走节点、
 * 下载慢/不通 fallback 自动切换、国内直连）。开关 + 订阅地址 + 分流模式 + 状态。
 * mihomo 随 guest 启动收口；开关/订阅在下次子系统启动时生效。
 */
@Composable
private fun ProxyCard() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(AppSettings.proxyEnabled(context)) }
    var subUrl by remember { mutableStateOf(AppSettings.proxySubUrl(context)) }
    var mode by remember { mutableStateOf(AppSettings.proxyMode(context)) }
    var updatedAt by remember { mutableStateOf(AppSettings.proxyUpdatedAt(context)) }
    var showSub by remember { mutableStateOf(false) }
    var showMode by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var toastMsg by remember { mutableStateOf<String?>(null) }
    val installed = SubsystemManager.isUmlInstalled(context)

    toastMsg?.let { msg ->
        LaunchedEffect(msg) {
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            toastMsg = null
        }
    }
    Card(
        modifier = Modifier
            .padding(top = 12.dp)
            .fillMaxWidth(),
    ) {
        if (!installed) {
            ArrowPreference(
                title = "Clash 代理",
                summary = "需先安装子系统（环境页「拉取并安装」），安装后可配置代理分流",
                startAction = {
                    Icon(
                        imageVector = Icons.Rounded.Shield,
                        contentDescription = "代理",
                        modifier = Modifier.padding(end = 6.dp),
                        tint = colorScheme.onSurfaceVariantSummary,
                    )
                },
                onClick = {},
            )
        } else {
            SwitchPreference(
                title = "Clash 代理（TUN 分流）",
                summary = when {
                    enabled && updatedAt.isNotBlank() -> "已开启（$mode）· 订阅 $updatedAt · 重启子系统生效"
                    enabled -> "已开启（$mode）· 重启子系统生效"
                    else -> "关闭时 guest 直连（国内 apt 源仍加速）"
                },
                startAction = {
                    Icon(
                        imageVector = Icons.Rounded.Shield,
                        contentDescription = "代理",
                        modifier = Modifier.padding(end = 6.dp),
                        tint = colorScheme.onBackground,
                    )
                },
                checked = enabled,
                onCheckedChange = { on ->
                    enabled = on
                    SubsystemManager.setProxyEnabled(context, on)
                    Toast.makeText(context, if (on) "代理已开启，重启子系统后生效" else "代理已关闭，重启子系统后生效", Toast.LENGTH_SHORT).show()
                },
            )
            ArrowPreference(
                title = "订阅地址",
                summary = if (subUrl.isBlank()) "未配置 · 点击输入机场订阅 URL" else "已配置 · 点击更新订阅",
                startAction = {
                    Icon(
                        imageVector = Icons.Rounded.Edit,
                        contentDescription = "订阅地址",
                        modifier = Modifier.padding(end = 6.dp),
                        tint = colorScheme.onBackground,
                    )
                },
                onClick = { showSub = true },
            )
            ArrowPreference(
                title = "分流模式",
                summary = when (mode) {
                    AppSettings.PROXY_MODE_GLOBAL -> "全局（全部流量走节点）"
                    AppSettings.PROXY_MODE_DIRECT -> "直连（仅查询，不分流）"
                    else -> "规则（默认 · 国外走节点，国内直连）"
                },
                startAction = {
                    Icon(
                        imageVector = Icons.Rounded.SwapHoriz,
                        contentDescription = "分流模式",
                        modifier = Modifier.padding(end = 6.dp),
                        tint = colorScheme.onBackground,
                    )
                },
                onClick = { showMode = true },
            )
        }
    }
    if (showSub) {
        ProxySubDialog(
            initial = subUrl,
            busy = busy,
            onConfirm = { url ->
                showSub = false
                if (url.isNotBlank()) {
                    busy = true
                    AppSettings.setProxySubUrl(context, url)
                    SubsystemManager.updateClashProfile(context) { ok, msg ->
                        busy = false
                        if (ok) updatedAt = AppSettings.proxyUpdatedAt(context)
                        toastMsg = msg
                    }
                }
            },
            onDismiss = { showSub = false },
        )
    }
    if (showMode) {
        ProxyModeDialog(
            current = mode,
            onConfirm = { newMode ->
                showMode = false
                if (newMode != mode) {
                    mode = newMode
                    AppSettings.setProxyMode(context, newMode)
                    Toast.makeText(context, "分流模式已更新，重启子系统后生效", Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { showMode = false },
        )
    }
}

/** 代理订阅输入对话框：机场订阅 URL，确认后下载写入 hostfs share。 */
@Composable
private fun ProxySubDialog(initial: String, busy: Boolean, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var url by remember { mutableStateOf(initial) }
    WindowDialog(
        show = true,
        title = "Clash 订阅地址",
        onDismissRequest = onDismiss,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                text = "粘贴机场订阅 URL，确认后自动下载并写入子系统（本地保存，不出设备）。",
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(8.dp))
            PathInput(
                value = url,
                placeholder = "https://example.com/subscribe",
                onValueChange = { url = it },
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
                    text = if (busy) "下载中…" else "确定",
                    onClick = { if (!busy) onConfirm(url) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

/** 分流模式选择对话框：rule（默认）/ global / direct。 */
@Composable
private fun ProxyModeDialog(current: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var selected by remember { mutableStateOf(current) }
    WindowDialog(
        show = true,
        title = "选择分流模式",
        onDismissRequest = onDismiss,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                text = "规则模式按 mihomo 自带 GEOSITE/GEOIP 规则智能分流；全局模式全部流量走节点；直连模式仅查询。",
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(6.dp))
            RadioButtonPreference(
                title = "规则（推荐）",
                summary = "国外走节点（GitHub 等），国内直连，自动测速选优",
                selected = selected == AppSettings.PROXY_MODE_RULE,
                onClick = { selected = AppSettings.PROXY_MODE_RULE },
            )
            RadioButtonPreference(
                title = "全局",
                summary = "全部流量走代理节点",
                selected = selected == AppSettings.PROXY_MODE_GLOBAL,
                onClick = { selected = AppSettings.PROXY_MODE_GLOBAL },
            )
            RadioButtonPreference(
                title = "直连",
                summary = "不分流，保持直连",
                selected = selected == AppSettings.PROXY_MODE_DIRECT,
                onClick = { selected = AppSettings.PROXY_MODE_DIRECT },
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

/** 体验 · WebUI 优化卡：dsh-web-mobile 装配状态 + 兼容插件计数 + 已装插件列表（profile manifest 读取）。 */
@Composable
private fun MobileUiCard() {
    val context = LocalContext.current
    val installed = AddonManager.isInstalled()
    val compatTotal = AddonManager.compatPluginIds.size
    val compatDone = AddonManager.compatPluginIds.count { AddonManager.isCompatInstalled(it) }
    // 已装插件列表（profile manifest 的 dependencies，纯文件读取，随装配状态变化）
    val plugins by produceState<List<Pair<String, String>>>(emptyList()) {
        value = loadInstalledPlugins(context)
    }
    Card(
        modifier = Modifier
            .padding(top = 12.dp)
            .fillMaxWidth(),
    ) {
        ArrowPreference(
            title = "WebUI 全能优化",
            summary = if (installed) {
                "dsh-web-mobile 移动端适配已装配（Pi UI 翻页器）· 兼容插件 $compatDone/$compatTotal"
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
        if (plugins.isNotEmpty()) {
            Text(
                text = "已装插件（${plugins.size}）",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 2.dp),
            )
            for ((name, version) in plugins) {
                Text(
                    text = "$name  $version",
                    fontSize = 12.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 2.dp),
                )
            }
        }
    }
}

/** 读取已装插件列表（profiles/web/package.json 的 dependencies，随装配状态变化）。 */
private fun loadInstalledPlugins(context: Context): List<Pair<String, String>> = runCatching {
    val manifest = File(TermuxEnv.dshHome(context), "profiles/web/package.json")
    if (!manifest.exists()) return emptyList()
    val deps = org.json.JSONObject(manifest.readText()).optJSONObject("dependencies")
        ?: return emptyList()
    val out = mutableListOf<Pair<String, String>>()
    val it = deps.keys()
    while (it.hasNext()) {
        val name = it.next()
        out.add(name to deps.optString(name))
    }
    out.sortedBy { it.first }
}.getOrDefault(emptyList())

/** 数据 · 数据管理卡：清空会话与设置数据 + 卸载运行时（均为危险操作，经主层 ConfirmDialog 确认后执行）。 */
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

/** 关于 · 品牌卡：DSHM logo、产品名、一句话定位与当前版本号（版本由 BuildConfig 提供）。 */
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

/** 关于 · 链接卡：GitHub 项目主页与使用说明跳转（从首页关于迁移至此）。 */
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
