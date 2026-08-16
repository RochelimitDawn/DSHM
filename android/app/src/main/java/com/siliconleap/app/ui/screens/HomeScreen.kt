package com.siliconleap.app.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.siliconleap.app.BuildConfig
import com.siliconleap.app.R
import com.siliconleap.app.runtime.RuntimeManager
import com.siliconleap.app.runtime.RuntimeState
import com.siliconleap.app.runtime.ServerPhase
import com.siliconleap.app.runtime.ThemeStore
import com.siliconleap.app.ui.component.BlurredBar
import com.siliconleap.app.ui.component.WarningCard
import com.siliconleap.app.ui.component.rememberBlurBackdrop
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.isDynamicColor
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/** 首页：复刻 KernelSU HomePagerMiuix 布局（状态卡 / 信息卡 / 操作卡）。 */
@Composable
fun HomeScreen(
    state: RuntimeState,
    bottomInnerPadding: Dp,
    onOpenRuntimeTab: () -> Unit = {},
) {
    val context = LocalContext.current
    val backdrop = rememberBlurBackdrop(enableBlur = true)
    val blurActive = backdrop != null
    val listState = rememberLazyListState()
    val scrolled by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 20
        }
    }
    val bannerCollapsed by animateFloatAsState(
        targetValue = if (scrolled) 1f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "bannerCollapse",
    )

    Scaffold(
        topBar = {
            BlurredBar(backdrop) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_deepseek_banner),
                        contentDescription = "DSHM",
                        modifier = Modifier
                            .padding(start = 16.dp)
                            .height(22.dp)
                            .graphicsLayer {
                                scaleX = 1f - 0.18f * bannerCollapsed
                                scaleY = 1f - 0.18f * bannerCollapsed
                                alpha = 1f - 0.45f * bannerCollapsed
                            },
                        colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onBackground),
                    )
                }
            }
        },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        Box(modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxHeight()
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .padding(horizontal = 12.dp),
                contentPadding = innerPadding,
                overscrollEffect = null,
            ) {
                item {
                    Column(
                        modifier = Modifier.padding(top = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (state.phase == ServerPhase.ERROR) {
                            WarningCard(message = state.message.substringBefore("\n\n"))
                        }
                        StatusCard(state, onOpen = { onStatusCardClick(context, state) }, onGoInstall = onOpenRuntimeTab)
                        InfoCard(state)
                        OpenHarnessCard(state)
                        LearnMoreCard()
                        Spacer(Modifier.height(bottomInnerPadding))
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusCard(
    state: RuntimeState,
    onOpen: () -> Unit,
    onGoInstall: () -> Unit,
) {
    if (!state.installed) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            onClick = onGoInstall,
            showIndication = true,
            pressFeedbackType = PressFeedbackType.Sink,
        ) {
            BasicComponent(
                title = "未安装运行时",
                summary = "点击拉取并安装运行时",
                startAction = {
                    Icon(
                        imageVector = Icons.Rounded.ErrorOutline,
                        contentDescription = "未安装运行时",
                        modifier = Modifier.padding(end = 6.dp),
                        tint = colorScheme.onBackground,
                    )
                },
            )
        }
        return
    }

    val isRunning = state.phase == ServerPhase.RUNNING
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.defaultColors(
                color = when {
                    isDynamicColor -> colorScheme.secondaryContainer
                    ThemeStore.isDark() -> Color(0xFF1A3825)
                    else -> Color(0xFFDFFAE4)
                },
                contentColor = colorScheme.onBackground,
            ),
            onClick = onOpen,
            showIndication = true,
            pressFeedbackType = PressFeedbackType.Tilt,
        ) {
            Box {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .offset(27.dp, 31.dp),
                    contentAlignment = Alignment.BottomEnd,
                ) {
                    Icon(
                        modifier = Modifier.size(110.dp),
                        imageVector = Icons.Rounded.CheckCircleOutline,
                        tint = if (isDynamicColor) {
                            colorScheme.primary.copy(alpha = 0.8f)
                        } else {
                            Color(0xFF36D167)
                        },
                        contentDescription = null,
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp, 14.dp),
                    contentAlignment = Alignment.TopStart,
                ) {
                    Column {
                        Text(
                            text = if (isRunning) "运行中" else "未启动",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(1.dp))
                        Text(
                            text = "v${state.runtimeVersion ?: "unknown"}",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OpenHarnessCard(state: RuntimeState) {
    val context = LocalContext.current
    val isRunning = state.phase == ServerPhase.RUNNING
    Card(modifier = Modifier.fillMaxWidth()) {
        BasicComponent(
            title = if (isRunning) "打开 Harness" else "启动服务",
            summary = "在系统浏览器中访问 ${if (isRunning) "http://127.0.0.1:${state.port}" else "后台启动服务"}",
            endActions = {
                Icon(
                    imageVector = MiuixIcons.Link,
                    tint = colorScheme.onSurface,
                    contentDescription = null,
                )
            },
            onClick = {
                if (isRunning) openHarness(context, state.port) else RuntimeManager.bootstrap()
            },
        )
    }
}

@Composable
private fun LearnMoreCard() {
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth()) {
        BasicComponent(
            title = "了解 DeepSeek Harness",
            summary = "查看项目与使用说明",
            endActions = {
                Icon(
                    imageVector = MiuixIcons.Link,
                    tint = colorScheme.onSurface,
                    contentDescription = null,
                )
            },
            onClick = { openUrl(context, "https://github.com/RochelimitDawn/DSHM") },
        )
    }
}

@Composable
private fun InfoCard(state: RuntimeState) {
    val uptimeText by produceState("0 秒") {
        while (true) {
            value = if (state.phase == ServerPhase.RUNNING) {
                formatUptime(RuntimeManager.uptimeMillis())
            } else {
                "-"
            }
            delay(1000)
        }
    }

    @Composable
    fun InfoText(
        title: String,
        content: String,
        bottomPadding: Dp = 24.dp,
    ) {
        Text(
            text = title,
            fontSize = MiuixTheme.textStyles.headline1.fontSize,
            fontWeight = FontWeight.Medium,
            color = colorScheme.onSurface,
        )
        Text(
            text = content,
            fontSize = MiuixTheme.textStyles.body2.fontSize,
            color = colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(top = 2.dp, bottom = bottomPadding),
        )
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            InfoText(title = "应用版本", content = "v${BuildConfig.VERSION_NAME}")
            InfoText(title = "运行时版本", content = state.runtimeVersion ?: "未安装")
            InfoText(title = "服务状态", content = statusTextOf(state.phase))
            InfoText(title = "监听地址", content = "http://127.0.0.1:${state.port}")
            InfoText(title = "进程 PID", content = state.pid?.toString() ?: "-")
            InfoText(
                title = "运行时长",
                content = uptimeText,
                bottomPadding = 0.dp,
            )
        }
    }
}

private fun onStatusCardClick(context: Context, state: RuntimeState) {
    when {
        state.phase == ServerPhase.RUNNING -> openHarness(context, state.port)
        state.installed -> RuntimeManager.bootstrap()
    }
}

private fun statusTextOf(phase: ServerPhase): String = when (phase) {
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

private fun openHarness(context: Context, port: Int) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:$port/"))
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

private fun openUrl(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
