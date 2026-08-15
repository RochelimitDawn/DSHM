package com.siliconleap.app.ui.screens

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.siliconleap.app.runtime.RuntimeManager
import com.siliconleap.app.runtime.RuntimeState
import com.siliconleap.app.runtime.ServerPhase
import com.siliconleap.app.runtime.ThemeStore
import org.json.JSONObject

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BootScreen(state: RuntimeState) {
    val context = LocalContext.current
    val dark = remember { ThemeStore.readDark(context) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = true
                settings.allowContentAccess = true
                addJavascriptInterface(object {
                    @JavascriptInterface
                    fun notifyReady() {
                        val d = ThemeStore.readDark(ctx)
                        evaluateJavascript("window.BootUI && window.BootUI.setTheme($d)", null)
                        pushBootState(this@apply)
                    }

                    @JavascriptInterface
                    fun onAction(action: String) {
                        when (action) {
                            "copy" -> copyLog(ctx)
                            "retry" -> RuntimeManager.bootstrap()
                            "rebuild" -> RuntimeManager.rebuildRuntime()
                        }
                    }
                }, "AndroidBridge")
                loadUrl("file:///android_asset/boot/index.html")
                webView = this
            }
        },
        modifier = Modifier.fillMaxSize(),
    )

    LaunchedEffect(Unit) {
        webView?.evaluateJavascript("window.BootUI && window.BootUI.setTheme($dark)", null)
    }

    LaunchedEffect(state.phase, state.message, state.progress) {
        webView?.let { pushBootState(it) }
    }
}

private fun pushBootState(webView: WebView) {
    val s = RuntimeManager.state.value
    val (phase, message) = when (s.phase) {
        ServerPhase.EXTRACTING -> {
            val pct = (s.progress * 100).toInt().coerceIn(0, 100)
            "extracting" to "解压运行时 · Extracting runtime $pct%"
        }
        ServerPhase.STARTING -> "starting" to "启动服务 · Starting service"
        ServerPhase.RUNNING -> "ready" to "服务就绪 · Ready"
        ServerPhase.ERROR -> "error" to s.message.ifBlank { "启动失败 · Startup failed" }
        else -> "extracting" to "初始化 · Initializing"
    }
    val log = if (s.phase == ServerPhase.ERROR) RuntimeManager.tailLog(120) else ""
    val jsonMessage = JSONObject.quote(message)
    val jsonLog = JSONObject.quote(log)
    webView.evaluateJavascript(
        "window.BootUI && window.BootUI.update('$phase', $jsonMessage, $jsonLog)",
        null,
    )
}

private fun copyLog(context: Context) {
    val text = RuntimeManager.tailLog(200)
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("SiliconLeap 日志", text))
}
