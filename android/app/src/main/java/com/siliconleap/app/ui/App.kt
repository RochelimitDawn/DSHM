package com.siliconleap.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import com.siliconleap.app.runtime.HarnessService
import com.siliconleap.app.runtime.RuntimeManager
import com.siliconleap.app.runtime.ServerPhase
import com.siliconleap.app.ui.screens.BootScreen
import com.siliconleap.app.ui.screens.MainScreen

@Composable
fun SiliconLeapApp() {
    val context = LocalContext.current
    val state by RuntimeManager.state.collectAsState()

    LaunchedEffect(Unit) {
        if (state.phase == ServerPhase.NOT_READY) {
            RuntimeManager.bootstrap()
        }
    }

    LaunchedEffect(state.phase) {
        if (state.phase == ServerPhase.RUNNING) {
            HarnessService.start(context)
        }
    }

    when (state.phase) {
        ServerPhase.RUNNING -> MainScreen(state)
        else -> BootScreen(state)
    }
}
