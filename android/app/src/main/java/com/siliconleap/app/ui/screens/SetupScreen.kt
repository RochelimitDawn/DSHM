package com.siliconleap.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.siliconleap.app.runtime.AppSettings
import com.siliconleap.app.runtime.RootManager
import com.siliconleap.app.runtime.RuntimeManager
import com.siliconleap.app.runtime.ThemeStore
import com.siliconleap.app.ui.component.BlurredBar
import com.siliconleap.app.ui.component.rememberBlurBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Badge
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/**
 * 首次启动引导：选择运行分区（miuix 原生风格，响应式布局）。
 * - Root 分区：检测设备 root 并请求授权（Magisk/KernelSU 弹窗），授权后以真 root 宿主执行。
 * - 容器分区（非 root）：proot 容器（Termux 运行时 + Debian/Ubuntu 子系统），自动安装。
 */
@Composable
fun SetupScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val backdrop = rememberBlurBackdrop(enableBlur = true)
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else colorScheme.surface

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    fun chooseRoot() {
        if (busy) return
        busy = true
        scope.launch {
            val hasSu = withContext(Dispatchers.IO) { RootManager.rootAvailable() }
            if (!hasSu) {
                AppSettings.setRootShellEnabled(context, false)
                RuntimeManager.setRunMode(context, AppSettings.RUN_MODE_CONTAINER)
                toast("未检测到 root，将使用容器分区")
                busy = false
                return@launch
            }
            val ok = withContext(Dispatchers.IO) { RootManager.requestRoot() }
            if (ok) {
                AppSettings.setRootShellEnabled(context, true)
                RuntimeManager.setRunMode(context, AppSettings.RUN_MODE_ROOT)
                toast("已获得 root 权限，将使用 Root 分区")
            } else {
                RootManager.clearGrant()
                AppSettings.setRootShellEnabled(context, false)
                RuntimeManager.setRunMode(context, AppSettings.RUN_MODE_CONTAINER)
                toast("未获得 root 授权，将使用容器分区")
            }
            busy = false
        }
    }

    fun chooseContainer() {
        if (busy) return
        AppSettings.setRootShellEnabled(context, false)
        RuntimeManager.setRunMode(context, AppSettings.RUN_MODE_CONTAINER)
        toast("将使用容器分区（proot）")
    }

    Scaffold(
        topBar = {
            BlurredBar(backdrop) {
                TopAppBar(
                    color = barColor,
                    title = "选择运行分区",
                )
            }
        },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        Box(modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(horizontal = 12.dp)
                    .padding(innerPadding),
            ) {
                val isWide = maxWidth >= 600.dp
                if (isWide) {
                    // 平板/横屏：两卡并排，整体限宽居中，避免拉伸过宽
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Spacer(Modifier.height(16.dp))
                        IntroText()
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .widthIn(max = 720.dp)
                                .padding(top = 12.dp),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            PartitionCard(
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 232.dp),
                                title = "Root 分区",
                                summary = "以真 root 在宿主 Android 执行，可访问系统文件与设备。",
                                icon = Icons.Rounded.Security,
                                badgeText = "需设备已 root",
                                badgeContainer = rootBadgeColor(),
                                badgeContent = rootBadgeContentColor(),
                                onClick = { chooseRoot() },
                                enabled = !busy,
                            )
                            Spacer(Modifier.width(12.dp))
                            PartitionCard(
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 232.dp),
                                title = "容器分区（非 root）",
                                summary = "自动安装 Termux 运行时与 Debian/Ubuntu 子系统（proot 免 root），兼容所有设备。",
                                icon = Icons.Rounded.Storage,
                                badgeText = "推荐 · 免 root",
                                badgeContainer = containerBadgeColor(),
                                badgeContent = containerBadgeContentColor(),
                                onClick = { chooseContainer() },
                                enabled = !busy,
                            )
                        }
                        RootSelinuxHint()
                        BusyState(busy)
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        IntroText()
                        PartitionCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp),
                            title = "Root 分区",
                            summary = "以真 root 在宿主 Android 执行，可访问系统文件与设备。",
                            icon = Icons.Rounded.Security,
                            badgeText = "需设备已 root",
                            badgeContainer = rootBadgeColor(),
                            badgeContent = rootBadgeContentColor(),
                            onClick = { chooseRoot() },
                            enabled = !busy,
                        )
                        PartitionCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp),
                            title = "容器分区（非 root）",
                            summary = "自动安装 Termux 运行时与 Debian/Ubuntu 子系统（proot 免 root），兼容所有设备。",
                            icon = Icons.Rounded.Storage,
                            badgeText = "推荐 · 免 root",
                            badgeContainer = containerBadgeColor(),
                            badgeContent = containerBadgeContentColor(),
                            onClick = { chooseContainer() },
                            enabled = !busy,
                        )
                        RootSelinuxHint()
                        BusyState(busy)
                    }
                }
            }
        }
    }
}

@Composable
private fun IntroText() {
    Text(
        text = "DSHM 通过分区执行 agent 命令。首次使用请选择一种方式，之后可在设置中切换。",
        fontSize = 13.sp,
        color = colorScheme.onSurfaceVariantSummary,
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 720.dp)
            .padding(start = 12.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun RootSelinuxHint() {
    val dark = ThemeStore.isDark()
    val bg = if (MiuixTheme.isDynamicColor) {
        colorScheme.tertiaryContainer
    } else if (dark) {
        Color(0xFF3E2F1B)
    } else {
        Color(0xFFFFF0DB)
    }
    val fg = if (MiuixTheme.isDynamicColor) {
        colorScheme.onTertiaryContainer
    } else {
        Color(0xFFF5A623)
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 720.dp)
            .padding(top = 12.dp),
        colors = CardDefaults.defaultColors(color = bg, contentColor = fg),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.Warning,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = fg,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "选择 Root 分区后，若需直接执行 usr/bin 外部命令，需要全局 SELinux 宽容模式（setenforce 0），或选择其他方案。",
                fontSize = 13.sp,
                lineHeight = 18.sp,
            )
        }
    }
}

@Composable
private fun BusyState(busy: Boolean) {
    if (busy) {
        Text(
            text = "正在检测并请求授权…",
            fontSize = 12.sp,
            color = colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(start = 12.dp, top = 16.dp),
        )
    } else {
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun PartitionCard(
    modifier: Modifier = Modifier,
    title: String,
    summary: String,
    icon: ImageVector,
    badgeText: String,
    badgeContainer: Color,
    badgeContent: Color,
    onClick: () -> Unit,
    enabled: Boolean,
) {
    Card(
        modifier = modifier,
        onClick = onClick,
        showIndication = enabled,
        pressFeedbackType = PressFeedbackType.Sink,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = title,
                        modifier = Modifier.size(24.dp),
                        tint = colorScheme.onPrimaryContainer,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = title,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(2.dp))
                    Badge(
                        containerColor = badgeContainer,
                        contentColor = badgeContent,
                    ) {
                        Text(text = badgeText, fontSize = 10.sp)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(
                text = summary,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun rootBadgeColor(): Color = when {
    MiuixTheme.isDynamicColor -> colorScheme.tertiaryContainer
    ThemeStore.isDark() -> Color(0xFF3E2F1B)
    else -> Color(0xFFFFF0DB)
}

@Composable
private fun rootBadgeContentColor(): Color = when {
    MiuixTheme.isDynamicColor -> colorScheme.onTertiaryContainer
    ThemeStore.isDark() -> Color(0xFFF5A623)
    else -> Color(0xFFB45F00)
}

@Composable
private fun containerBadgeColor(): Color = when {
    MiuixTheme.isDynamicColor -> colorScheme.primaryContainer
    ThemeStore.isDark() -> Color(0xFF1A3825)
    else -> Color(0xFFDFFAE4)
}

@Composable
private fun containerBadgeContentColor(): Color = when {
    MiuixTheme.isDynamicColor -> colorScheme.onPrimaryContainer
    ThemeStore.isDark() -> Color(0xFF36D167)
    else -> Color(0xFF2E7D32)
}
