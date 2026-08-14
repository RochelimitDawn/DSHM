package com.siliconleap.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.siliconleap.app.runtime.RuntimeManager
import com.siliconleap.app.runtime.ServerPhase
import com.siliconleap.app.ui.screens.BootScreen
import com.siliconleap.app.ui.screens.HomeScreen

@Composable
fun SiliconLeapApp() {
    val state by RuntimeManager.state.collectAsState()

    LaunchedEffect(Unit) {
        if (state.phase == ServerPhase.NOT_READY) {
            RuntimeManager.bootstrap()
        }
    }

    when (state.phase) {
        ServerPhase.RUNNING -> HomeScreen(state.port)
        else -> BootScreen(state)
    }
}
