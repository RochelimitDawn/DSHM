package com.siliconleap.app.web

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

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
            webViewClient = object : WebViewClient() {
                @Deprecated("Deprecated in API 24")
                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                    return handleUrl(url)
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    return handleUrl(request.url.toString())
                }

                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) = Unit
            }
        }
    }

    AndroidView(factory = { webView }) { view ->
        if (view.url != url) view.loadUrl(url)
    }
}
