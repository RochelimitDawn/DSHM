package com.siliconleap.app.web

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.siliconleap.app.runtime.TermuxEnv
import com.siliconleap.app.runtime.ThemeStore
import java.io.File

private const val TAG = "SiliconLeapWeb"

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ServerWebView(port: Int) {
    val context = LocalContext.current
    val url = "http://127.0.0.1:$port/"

    fun handleUrl(raw: String): Boolean {
        val uri = Uri.parse(raw)
        val host = uri.host ?: return false
        if (host == "127.0.0.1" || host == "localhost") return false
        val intent = Intent(Intent.ACTION_VIEW, uri)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
        return true
    }

    val webView = remember {
        WebView(context.applicationContext).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.loadsImagesAutomatically = true
            settings.useWideViewPort = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            addJavascriptInterface(object {
                @android.webkit.JavascriptInterface
                fun setDark(dark: Boolean) {
                    ThemeStore.saveDark(context, dark)
                }
            }, "AndroidTheme")
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    val level = when (message.messageLevel()) {
                        ConsoleMessage.MessageLevel.ERROR -> "E"
                        ConsoleMessage.MessageLevel.WARNING -> "W"
                        else -> "I"
                    }
                    val line = "[$level] ${message.message()} @ ${message.sourceId()}:${message.lineNumber()}"
                    if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                        Log.e(TAG, line)
                        appendWebLog(context, line)
                    }
                    return super.onConsoleMessage(message)
                }
            }
            webViewClient = object : WebViewClient() {
                @Deprecated("Deprecated in API 24")
                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                    return handleUrl(url)
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    return handleUrl(request.url.toString())
                }

                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) = Unit

                override fun onPageFinished(view: WebView, url: String?) {
                    Log.i(TAG, "onPageFinished: $url")
                    injectThemeObserver(view)
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    val line = "onReceivedError: ${error.description} (code=${error.errorCode}) url=${request.url}"
                    Log.e(TAG, line)
                    appendWebLog(context, line)
                }

                override fun onReceivedHttpError(
                    view: WebView,
                    request: WebResourceRequest,
                    errorResponse: WebResourceResponse,
                ) {
                    val line = "onReceivedHttpError: ${errorResponse.statusCode} ${request.url}"
                    Log.w(TAG, line)
                    appendWebLog(context, line)
                }
            }
            WebView.setWebContentsDebuggingEnabled(true)
        }
    }

    AndroidView(factory = { webView }) { view ->
        if (view.url != url) view.loadUrl(url)
    }
}

private fun appendWebLog(context: Context, line: String) {
    runCatching {
        val file = File(TermuxEnv.logs(context), "webview.log")
        file.parentFile?.mkdirs()
        file.appendText(line + "\n")
    }
}

/** 注入主题监听：Web UI 通过 body[data-ds-dark-theme] 切换明暗，同步到壳侧 ThemeStore。 */
private fun injectThemeObserver(view: WebView) {
    val js = """
        (function () {
          if (window.__slThemeObs) return;
          window.__slThemeObs = true;
          function sync() {
            var dark = !!(document.body && document.body.hasAttribute('data-ds-dark-theme'));
            try { window.AndroidTheme.setDark(dark); } catch (e) {}
          }
          function onDoc() {
            sync();
            if (document.body) {
              new MutationObserver(sync).observe(document.body, { attributes: true, attributeFilter: ['data-ds-dark-theme'] });
            }
          }
          if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', onDoc);
          else onDoc();
        })();
    """.trimIndent()
    view.evaluateJavascript(js, null)
}
