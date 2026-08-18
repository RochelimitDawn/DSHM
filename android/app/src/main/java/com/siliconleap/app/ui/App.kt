package com.siliconleap.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.siliconleap.app.runtime.AppSettings
import com.siliconleap.app.runtime.HarnessService
import com.siliconleap.app.runtime.RuntimeManager
import com.siliconleap.app.runtime.ServerPhase
import com.siliconleap.app.runtime.ThemeStore
import com.siliconleap.app.runtime.UpdateManager
import com.siliconleap.app.ui.component.ThemeTransitionOverlay
import com.siliconleap.app.ui.screens.BootScreen
import com.siliconleap.app.ui.screens.MainScreen
import com.siliconleap.app.ui.screens.SetupScreen

@Composable
fun SiliconLeapApp() {
    val context = LocalContext.current
    val state by RuntimeManager.state.collectAsState()
    // 运行分区选择：首次启动引导；选择后持久化并自动启动
    val runMode by RuntimeManager.runMode.collectAsState()

    // 从 Harness settings.yaml 加载主题（跟随 Harness 黑白模式）
    LaunchedEffect(Unit) {
        ThemeStore.load(context)
        if (AppSettings.autoUpdate(context)) {
            UpdateManager.checkForUpdate(context)
        }
    }

    // 已选择分区后自动启动服务（首次引导完成由分区选择驱动 runMode 变化触发）
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

    val transition by ThemeStore.transition.collectAsState()

    Box(Modifier.fillMaxSize()) {
        if (runMode.isEmpty()) {
            // 首次启动：分区引导（Root / 容器）
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
