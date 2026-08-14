package com.siliconleap.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.siliconleap.app.web.ServerWebView

@Composable
fun HomeScreen(port: Int) {
    Box(Modifier.fillMaxSize()) {
        ServerWebView(port = port)
    }
}
