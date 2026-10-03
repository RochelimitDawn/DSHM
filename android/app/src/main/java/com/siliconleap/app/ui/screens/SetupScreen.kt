package com.siliconleap.app.ui.screens

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Memory

import androidx.compose.material.icons.rounded.Storage
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.siliconleap.app.R
import com.siliconleap.app.runtime.AppSettings
import com.siliconleap.app.runtime.RuntimeManager
import com.siliconleap.app.runtime.SubsystemManager

import top.yukonga.miuix.kmp.basic.Badge
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/**
 * 渐进式首次引导（参考 DEEIX-Chat 引导流程，miuix 风格，手机/平板响应式）：
 * 1. 欢迎（DSHM 是什么、将安装什么、空间预估）
 * 2. 子系统引擎（自动 = UML 优先 + proot 回退；保留步骤使引导结构与确认页稳定）
 * 3. 子系统发行版（Debian / Ubuntu）
 * 4. 预装插件选择（兼容插件可勾选，主插件固定必装）
 * 5. 确认并开始安装
 */
@Composable
fun SetupScreen() {
    val context = LocalContext.current
    val totalSteps = 5
    var step by remember { mutableIntStateOf(0) }
    var engine by remember { mutableStateOf(AppSettings.SUBSYSTEM_ENGINE_AUTO) }
    var flavor by remember { mutableStateOf(AppSettings.subsystemFlavor(context)) }
    var preinstall by remember {
        mutableStateOf(
            AppSettings.preinstallPlugins(context).ifEmpty {
                AddonManagerCompat.pluginDescriptions
                    .filter { it.id != "dsh-web-mobile" }
                    .map { it.id }
                    .toSet()
            },
        )
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val isWide = maxWidth >= 600.dp
        val contentModifier = if (isWide) {
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .widthIn(max = 720.dp).align(Alignment.TopCenter)
        } else {
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        }
        Box(Modifier.fillMaxSize()) {
            Column(modifier = contentModifier) {
                TopAppBar(
                    title = "引导（${step + 1}/$totalSteps）",
                )
                StepIndicator(step, totalSteps)
                AnimatedContent(
                    targetState = step,
                    transitionSpec = {
                        // 方向感知转场：前进新页从右滑入、后退从左滑入（部分位移比全宽更轻盈），
                        // 淡入淡出错峰（退出更快），配合 FastOutSlowIn 缓动形成连贯动线
                        val forward = targetState > initialState
                        val enter = slideInHorizontally(
                            animationSpec = tween(320, easing = FastOutSlowInEasing),
                        ) { full -> if (forward) full / 4 else -full / 4 } +
                            fadeIn(tween(240, delayMillis = 60, easing = FastOutSlowInEasing))
                        val exit = slideOutHorizontally(
                            animationSpec = tween(280, easing = FastOutSlowInEasing),
                        ) { full -> if (forward) -full / 4 else full / 4 } +
                            fadeOut(tween(150))
                        enter.togetherWith(exit)
                    },
                    label = "setupStep",
                ) { s ->
                    when (s) {
                        0 -> WelcomeStep()
                        1 -> EngineStep(engine) { engine = it }
                        2 -> FlavorStep(flavor) { flavor = it }
                        3 -> PluginsStep(preinstall) { preinstall = it }
                        4 -> ConfirmStep(engine, flavor, preinstall)
                    }
                }
            }
            // 底部导航（下一步/上一步）：悬浮于内容之上
            StepNav(
                step = step,
                totalSteps = totalSteps,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp)
                    .widthIn(max = 720.dp),
                onBack = { if (step > 0) step-- },
                onNext = { if (step < totalSteps - 1) step++ },
                onFinish = {
                    AppSettings.setSubsystemEngine(context, engine)
                    AppSettings.setSubsystemFlavor(context, flavor)
                    AppSettings.setPreinstallPlugins(context, preinstall)
                    AppSettings.setOnboardingDone(context, true)
                    RuntimeManager.setRunMode(context, AppSettings.RUN_MODE_CONTAINER)
                    Toast.makeText(context, "配置已保存，开始安装", Toast.LENGTH_SHORT).show()
                    RuntimeManager.bootstrap()
                },
            )
        }
    }
}

/** 步骤指示器：进度条动线 + 指示点（当前步平滑放大高亮）。 */
@Composable
private fun StepIndicator(step: Int, total: Int) {
    val progress by animateFloatAsState(
        targetValue = (step + 1) / total.toFloat(),
        animationSpec = tween(350, easing = FastOutSlowInEasing),
        label = "stepProgress",
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(50))
                .background(colorScheme.onSurfaceVariantSummary.copy(alpha = 0.15f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .height(4.dp)
                    .clip(RoundedCornerShape(50))
                    .background(colorScheme.primary),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        ) {
            repeat(total) { i ->
                val active = i <= step
                val current = i == step
                val dotSize by animateDpAsState(
                    targetValue = if (current) 10.dp else 8.dp,
                    animationSpec = tween(250, easing = FastOutSlowInEasing),
                    label = "dotSize",
                )
                val dotColor by animateColorAsState(
                    targetValue = if (active) colorScheme.primary
                    else colorScheme.onSurfaceVariantSummary.copy(alpha = 0.3f),
                    animationSpec = tween(250, easing = FastOutSlowInEasing),
                    label = "dotColor",
                )
                Box(
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .size(dotSize)
                        .clip(RoundedCornerShape(50))
                        .background(dotColor),
                )
            }
        }
    }
}

/** 步骤内容卡（标题 + 说明 + 内容），风格与主 UI 一致。 */
@Composable
private fun StepCard(title: String, body: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Text(
            text = title,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            color = colorScheme.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = body,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            color = colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.height(14.dp))
        content()
        Spacer(Modifier.height(88.dp))
    }
}

/** 步骤 1：欢迎。 */
@Composable
private fun WelcomeStep() {
    StepCard(
        title = stringResource(R.string.onboarding_welcome_title),
        body = stringResource(R.string.onboarding_welcome_body),
    ) {
        OptionCard(
            title = "Linux 容器运行时",
            summary = "约 580 MB（解压后约 600 MB） · Node.js 与 dsh",
            icon = Icons.Rounded.Memory,
            badgeText = "必装",
        )
        OptionCard(
            title = "Linux 子系统",
            summary = "约 46 MB · agent 命令执行环境（免 root）",
            icon = Icons.Rounded.Storage,
            badgeText = "必装",
        )
        OptionCard(
            title = "WebUI 插件",
            summary = "移动端适配、插件市场、用量统计等 · 可选",
            icon = Icons.Rounded.Extension,
            badgeText = "可选",
        )
    }
}

/** 步骤 2：子系统引擎（自动 = UML 优先 + proot 回退；UML 需 APK 内置内核）。 */
@Composable
private fun EngineStep(selected: String, onSelect: (String) -> Unit) {
    StepCard(
        title = stringResource(R.string.onboarding_engine_title),
        body = stringResource(R.string.onboarding_engine_body),
    ) {
        SelectCard(
            title = "自动（推荐）",
            summary = "UML 真内核优先（零拦截开销、guest 真 root），不可用自动回退 proot",
            icon = Icons.Rounded.Memory,
            badgeText = "推荐",
            selected = selected == AppSettings.SUBSYSTEM_ENGINE_AUTO,
            onClick = { onSelect(AppSettings.SUBSYSTEM_ENGINE_AUTO) },
        )
        SelectCard(
            title = "proot",
            summary = stringResource(R.string.onboarding_engine_proot_summary),
            icon = Icons.Rounded.Memory,
            badgeText = "内置引擎 · 兼容性最好",
            selected = selected == AppSettings.SUBSYSTEM_ENGINE_PROOT,
            onClick = { onSelect(AppSettings.SUBSYSTEM_ENGINE_PROOT) },
        )
    }
}

/** 步骤 3：子系统发行版（Debian / Ubuntu）。 */
@Composable
private fun FlavorStep(selected: String, onSelect: (String) -> Unit) {
    StepCard(
        title = stringResource(R.string.onboarding_flavor_title),
        body = stringResource(R.string.onboarding_flavor_body),
    ) {
        SelectCard(
            title = "Debian 12（Bookworm）",
            summary = "稳定、体积小、兼容验证充分 · 默认推荐",
            icon = Icons.Rounded.Storage,
            badgeText = "推荐",
            selected = selected == AppSettings.SUBSYSTEM_DEBIAN,
            onClick = { onSelect(AppSettings.SUBSYSTEM_DEBIAN) },
        )
        SelectCard(
            title = "Ubuntu 24.04（Noble）",
            summary = "软件与工具链更新（Python/Node 等），LTS 支持周期长（至 2029）",
            icon = Icons.Rounded.Storage,
            badgeText = "更新",
            selected = selected == AppSettings.SUBSYSTEM_UBUNTU,
            onClick = { onSelect(AppSettings.SUBSYSTEM_UBUNTU) },
        )
    }
}

/** 步骤 4：预装插件选择（主插件固定必装，兼容插件可勾选）。 */
@Composable
private fun PluginsStep(selected: Set<String>, onChange: (Set<String>) -> Unit) {
    StepCard(
        title = stringResource(R.string.onboarding_plugins_title),
        body = stringResource(R.string.onboarding_plugins_body),
    ) {
        OptionCard(
            title = "dsh-web-mobile（主插件）",
            summary = "WebUI 移动端适配 · 固定必装",
            icon = Icons.Rounded.Extension,
            badgeText = "必装",
        )
        AddonManagerCompat.pluginDescriptions
            .filter { it.id != "dsh-web-mobile" }
            .forEach { (id, title, desc) ->
                SwitchPreference(
                    title = title,
                    summary = desc,
                    startAction = {
                        Icon(
                            imageVector = Icons.Rounded.Extension,
                            contentDescription = title,
                            modifier = Modifier.padding(end = 6.dp),
                            tint = colorScheme.onBackground,
                        )
                    },
                    checked = id in selected,
                    onCheckedChange = { on ->
                        onChange(if (on) selected + id else selected - id)
                    },
                )
            }
    }
}

/** 步骤 5：确认并开始安装。 */
@Composable
private fun ConfirmStep(engine: String, flavor: String, preinstall: Set<String>) {
    StepCard(
        title = stringResource(R.string.onboarding_confirm_title),
        body = stringResource(R.string.onboarding_confirm_body),
    ) {
        SummaryRow("子系统引擎", if (engine == AppSettings.SUBSYSTEM_ENGINE_UML) "UML" else if (engine == AppSettings.SUBSYSTEM_ENGINE_PROOT) "proot" else "自动（UML 优先）")
        SummaryRow("子系统发行版", if (flavor == AppSettings.SUBSYSTEM_UBUNTU) "Ubuntu 24.04" else "Debian 12")
        SummaryRow("预装插件", "主插件 + ${preinstall.size} 个兼容插件")
        SummaryRow("下载源", "自动测速选优（可在设置里改）")
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth(),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = label, fontSize = 14.sp, color = colorScheme.onSurfaceVariantSummary)
            Text(text = value, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = colorScheme.onSurface)
        }
    }
}

/** 只读说明卡（引导第 1 步的「将安装什么」一览）。 */
@Composable
private fun OptionCard(title: String, summary: String, icon: ImageVector, badgeText: String) {
    Card(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(imageVector = icon, contentDescription = title, tint = colorScheme.onPrimaryContainer)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.padding(start = 0.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = colorScheme.onSurface)
                    Spacer(Modifier.width(8.dp))
                    Badge { Text(text = badgeText, fontSize = 10.sp) }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = summary,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

/** 可选中选项卡（引导第 2/3 步的引擎与发行版选择）。 */
@Composable
private fun SelectCard(
    title: String,
    summary: String,
    icon: ImageVector,
    badgeText: String,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Card(
        onClick = if (enabled) onClick else ({ }),
        showIndication = true,
        pressFeedbackType = PressFeedbackType.Sink,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .then(if (enabled) Modifier else Modifier.alpha(0.45f)),
        colors = if (selected) {
            CardDefaults.defaultColors(
                color = colorScheme.primaryContainer.copy(alpha = 0.35f),
                contentColor = colorScheme.onPrimaryContainer,
            )
        } else {
            CardDefaults.defaultColors()
        },
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(imageVector = icon, contentDescription = title, tint = colorScheme.onPrimaryContainer)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = colorScheme.onSurface)
                    Spacer(Modifier.width(8.dp))
                    Badge { Text(text = badgeText, fontSize = 10.sp) }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = summary,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

/** 底部导航（上一步 / 下一步 / 开始安装）。 */
@Composable
private fun StepNav(
    step: Int,
    totalSteps: Int,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onFinish: () -> Unit,
) {
    Row(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        if (step > 0) {
            TextButton(
                text = stringResource(R.string.onboarding_back),
                onClick = onBack,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
        }
        TextButton(
            text = if (step == totalSteps - 1) {
                stringResource(R.string.onboarding_start_install)
            } else {
                stringResource(R.string.onboarding_next)
            },
            onClick = { if (step == totalSteps - 1) onFinish() else onNext() },
            modifier = Modifier.weight(1f),
        )
    }
}
