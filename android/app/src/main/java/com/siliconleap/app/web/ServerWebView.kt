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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.siliconleap.app.runtime.TermuxEnv
import com.siliconleap.app.runtime.ThemeStore
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

private const val TAG = "SiliconLeapWeb"

/**
 * 旧版 Android System WebView 缺少的部分现代 API polyfill。
 * dsh 前端（Vite 产物）直接调用 Object.hasOwn（Chrome 93+）等 API，
 * 设备 WebView 过旧时前端初始化抛错导致白屏。必须在页面任何脚本执行前注入。
 */
private const val POLYFILL_SCRIPT = """<script>
if (typeof Object.hasOwn !== 'function') {
  Object.hasOwn = function hasOwn(obj, prop) {
    if (obj === null || obj === undefined) throw new TypeError('Cannot convert undefined or null to object');
    return Object.prototype.hasOwnProperty.call(obj, prop);
  };
}
if (typeof WeakRef === 'undefined') {
  globalThis.WeakRef = class WeakRef { constructor(v) { this.v = v; } deref() { return this.v; } };
}
if (typeof queueMicrotask !== 'function') {
  globalThis.queueMicrotask = function (cb) { Promise.resolve().then(cb); };
}
</script>"""

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ServerWebView(port: Int) {
    val url = "http://127.0.0.1:$port/"

    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                settings.loadsImagesAutomatically = true
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                addJavascriptInterface(object {
                    @android.webkit.JavascriptInterface
                    fun setDark(dark: Boolean) {
                        ThemeStore.saveDark(ctx, dark)
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
                        }
                        appendWebLog(ctx, line)
                        return super.onConsoleMessage(message)
                    }
                }
                webViewClient = object : WebViewClient() {
                    private fun handleUrl(raw: String): Boolean {
                        val uri = Uri.parse(raw)
                        val host = uri.host ?: return false
                        if (host == "127.0.0.1" || host == "localhost") return false
                        val intent = Intent(Intent.ACTION_VIEW, uri)
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { ctx.startActivity(intent) }
                        return true
                    }

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
                        logViewport(view)
                    }

                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        val uri = request.url
                        val host = uri.host
                        val path = uri.path
                        if (request.isForMainFrame &&
                            (host == "127.0.0.1" || host == "localhost") &&
                            (path == "/" || path == "/index.html")
                        ) {
                            return fetchIndexWithPolyfill(uri.toString())
                        }
                        return null
                    }

                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        val line = "onReceivedError: ${error.description} (code=${error.errorCode}) url=${request.url}"
                        Log.e(TAG, line)
                        appendWebLog(ctx, line)
                    }

                    override fun onReceivedHttpError(
                        view: WebView,
                        request: WebResourceRequest,
                        errorResponse: WebResourceResponse,
                    ) {
                        val line = "onReceivedHttpError: ${errorResponse.statusCode} ${request.url}"
                        Log.w(TAG, line)
                        appendWebLog(ctx, line)
                    }
                }
                WebView.setWebContentsDebuggingEnabled(true)
            }
        },
        modifier = Modifier.fillMaxSize(),
        update = { view ->
            if (view.url != url) view.loadUrl(url)
        },
    )
}

private fun appendWebLog(context: Context, line: String) {
    runCatching {
        val file = File(TermuxEnv.logs(context), "webview.log")
        file.parentFile?.mkdirs()
        file.appendText(line + "\n")
    }
}

/** 拦截 dsh 首页 HTML，在文档解析前注入 polyfill，返回自定义响应。 */
private fun fetchIndexWithPolyfill(url: String): WebResourceResponse? {
    return try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.instanceFollowRedirects = true
        val charset = StandardCharsets.UTF_8
        val body = conn.inputStream.bufferedReader(charset).readText()
        val injected = body.replace("<head>", "<head>\n$POLYFILL_SCRIPT")
        WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(injected.toByteArray(charset)))
    } catch (e: Exception) {
        Log.w(TAG, "注入 polyfill 失败: ${e.message}")
        null
    }
}

/** 记录页面视口信息（innerWidth/height、dpr、布局视口），用于定位真机视口异常。 */
private fun logViewport(view: WebView) {
    val js = "(function(){return JSON.stringify({innerW:window.innerWidth,innerH:window.innerHeight,dpr:window.devicePixelRatio,clientW:document.documentElement.clientWidth,scrollW:document.body.scrollWidth})})()"
    view.evaluateJavascript(js) { value ->
        runCatching { appendWebLog(view.context, "[viewport] $value") }
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
