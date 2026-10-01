package com.siliconleap.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.siliconleap.app.BuildConfig
import com.siliconleap.app.R
import com.siliconleap.app.runtime.AppSettings
import com.siliconleap.app.runtime.HarnessService
import com.siliconleap.app.runtime.RuntimeManager
import com.siliconleap.app.runtime.ServerPhase
import com.siliconleap.app.runtime.ThemeStore
import com.siliconleap.app.runtime.UpdateManager
import com.siliconleap.app.ui.component.ConfirmDialog
import com.siliconleap.app.ui.component.ThemeTransitionOverlay
import com.siliconleap.app.ui.screens.BootScreen
import com.siliconleap.app.ui.screens.MainScreen
import com.siliconleap.app.ui.screens.SetupScreen

@Composable
fun SiliconLeapApp() {
    val context = LocalContext.current
    val state by RuntimeManager.state.collectAsState()

    // 从 Harness settings.yaml 加载主题（跟随 Harness 黑白模式）
    LaunchedEffect(Unit) {
        ThemeStore.load(context)
        if (AppSettings.autoUpdate(context)) {
            UpdateManager.checkForUpdate(context)
        }
        // 存量用户迁移：引导上线前已选分区/已装运行时的老用户直接跳过向导
        if (!AppSettings.onboardingDone(context) && AppSettings.runMode(context).isNotEmpty()) {
            AppSettings.setOnboardingDone(context, true)
        }
    }

    // 首次启动进入渐进式引导（runMode 为空=未完成引导）；引导完成由向导的
    // 「开始安装」设置 runMode 触发：持久化配置 + 默认容器分区 + 拉起装配
    val runMode by RuntimeManager.runMode.collectAsState()
    LaunchedEffect(runMode) {
        if (runMode.isNotEmpty() && state.phase == ServerPhase.NOT_READY && AppSettings.autoStartService(context)) {
            RuntimeManager.bootstrap()
        }
    }

    LaunchedEffect(state.phase) {
        if (state.phase == ServerPhase.RUNNING) {
            HarnessService.start(context)
        }
    }

    // 升级后首次打开弹一次「本次更新」说明（本地资源，离线可用）。
    // 已安装运行时（存量用户）才弹；新装用户首次引导更重要，静默记为已弹。
    var showChangelog by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val shown = AppSettings.changelogShownVersion(context)
        if (shown != BuildConfig.VERSION_NAME) {
            if (AppSettings.runtimeInstalled(context)) {
                showChangelog = true
            } else {
                AppSettings.setChangelogShownVersion(context, BuildConfig.VERSION_NAME)
            }
        }
    }
    ConfirmDialog(
        show = showChangelog,
        title = "本次更新（${BuildConfig.VERSION_NAME}）",
        message = changelogText(context),
        confirmText = "知道了",
        onConfirm = {
            showChangelog = false
            AppSettings.setChangelogShownVersion(context, BuildConfig.VERSION_NAME)
        },
        onDismiss = {
            showChangelog = false
            AppSettings.setChangelogShownVersion(context, BuildConfig.VERSION_NAME)
        },
    )

    val transition by ThemeStore.transition.collectAsState()

    Box(Modifier.fillMaxSize()) {
        if (runMode.isEmpty()) {
            // 首次启动：渐进式引导（欢迎 → 引擎 → 发行版 → 插件 → 确认安装）
            SetupScreen()
        } else {
            MainScreen(state)
            // 安装/启动/出错时以卡片形式叠加进度与 Shell 日志
            when (state.phase) {
                ServerPhase.DOWNLOADING,
                ServerPhase.EXTRACTING,
                ServerPhase.STARTING,
                ServerPhase.ERROR,
                -> BootScreen(state)

                else -> Unit
            }
            // 白天/黑夜切换动画（最顶层）
            transition?.let {
                ThemeTransitionOverlay(center = it.center, mode = it.mode) {
                    ThemeStore.consumeTransition()
                }
            }
        }
    }
}

/** 本地更新说明（R.array.changelog_items），逐条以「• 」列出。 */
private fun changelogText(context: android.content.Context): String {
    val items = runCatching {
        context.resources.getStringArray(R.array.changelog_items)
    }.getOrDefault(emptyArray())
    if (items.isEmpty()) return "本次更新：内置运行时与稳定性改进。"
    return items.joinToString("\n") { "• $it" }
}
