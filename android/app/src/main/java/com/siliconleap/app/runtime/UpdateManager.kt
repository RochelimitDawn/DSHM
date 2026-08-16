package com.siliconleap.app.runtime

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.siliconleap.app.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 在线更新：从 GitHub Release 检查最新版本并下载安装。
 * 安装仅替换 APK，filesDir 数据（运行时/工作区/会话）保留，实现无缝切换。
 */
object UpdateManager {
    private const val REPO = "RochelimitDawn/DSHM"
    private const val LATEST_API = "https://api.github.com/repos/$REPO/releases/latest"
    private const val APK_ASSET = "app-release.apk"

    data class UpdateInfo(
        val tag: String,
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val sizeBytes: Long,
        val body: String = "",
    )

    data class UpdateState(
        val checking: Boolean = false,
        val available: UpdateInfo? = null,
        val downloading: Boolean = false,
        val progress: Float = 0f,
        val message: String = "",
    )

    private val _state = MutableStateFlow(UpdateState())
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 检查最新版本；有更新则标记 available。 */
    fun checkForUpdate(context: Context, force: Boolean = false) {
        val s = _state.value
        if (s.checking || s.downloading) return
        if (!force && s.available != null) return
        _state.update { it.copy(checking = true, message = "正在检查更新…") }
        scope.launch {
            val info = runCatching { fetchLatest() }.getOrNull()
            if (info == null) {
                _state.update { it.copy(checking = false, message = "检查更新失败，请稍后重试") }
                return@launch
            }
            _state.update {
                if (info.versionCode > BuildConfig.VERSION_CODE) {
                    it.copy(checking = false, available = info, message = "发现新版本 ${info.versionName}")
                } else {
                    it.copy(checking = false, available = null, message = "当前已是最新版本")
                }
            }
        }
    }

    /** 下载并安装新版 APK。 */
    fun downloadAndInstall(context: Context, info: UpdateInfo) {
        if (_state.value.downloading) return
        _state.update { it.copy(downloading = true, progress = 0f, message = "正在下载…") }
        scope.launch {
            val dir = File(TermuxEnv.filesDir(context), "downloads").apply { mkdirs() }
            val apk = File(dir, "dshm-update.apk")
            // fastgit 源：给 GitHub 下载地址加加速域名前缀
            val downloadUrl = if (AppSettings.downloadSource(context) == AppSettings.SOURCE_FASTGIT) {
                "https://fastgit.cc/${info.apkUrl}"
            } else {
                info.apkUrl
            }
            val ok = downloadFile(downloadUrl, apk, info.sizeBytes) { pct ->
                _state.update { it.copy(progress = pct, message = "正在下载 ${(pct * 100).toInt()}%…") }
            }
            if (!ok) {
                _state.update { it.copy(downloading = false, message = "下载失败，请重试") }
                return@launch
            }
            _state.update { it.copy(downloading = false, progress = 1f, message = "下载完成，正在安装…") }
            if (!context.packageManager.canRequestPackageInstalls()) {
                _state.update {
                    it.copy(downloading = false, message = "需要在系统设置中允许 DSHM 安装应用后再更新")
                }
                openInstallSettings(context)
                return@launch
            }
            installApk(context, apk)
        }
    }

    /** 「最新版本」检查 API（fastgit 只代理下载，检查仍走 GitHub）。 */
    private fun latestApi(): String = LATEST_API

    private fun fetchLatest(): UpdateInfo? = try {
        val conn = URL(latestApi()).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("Accept", "application/json")
        if (conn.responseCode !in 200..299) return null
        val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        val tag = json.optString("tag_name", "")
        val assets = json.optJSONArray("assets") ?: return null
        var apkUrl: String? = null
        var size = 0L
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name") == APK_ASSET) {
                apkUrl = a.optString("browser_download_url")
                size = a.optLong("size", 0L)
                break
            }
        }
        val url = apkUrl ?: return null
        val versionCode = parseVersionCode(tag) ?: return null
        val body = json.optString("body", "")
        UpdateInfo(tag, versionCode, tag.removePrefix("v"), url, size, body)
    } catch (_: Exception) {
        null
    }

    private fun parseVersionCode(tag: String): Int? {
        // 版本格式 v2.{N}.{E}，versionCode = 2000000 + N*10000 + E*100
        val m = Regex("""v\d+\.(\d+)\.(\d+)""").find(tag) ?: return null
        val n = m.groupValues[1].toIntOrNull() ?: return null
        val e = m.groupValues[2].toIntOrNull() ?: return null
        return 2000000 + n * 10000 + e * 100
    }

    private suspend fun downloadFile(
        url: String,
        target: File,
        sizeBytes: Long,
        onProgress: (Float) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            if (conn.responseCode !in 200..299) return@withContext false
            val contentLength = if (sizeBytes > 0) sizeBytes else conn.contentLengthLong
            val input = conn.inputStream
            val out = FileOutputStream(target)
            val buf = ByteArray(64 * 1024)
            var total = 0L
            var lastUpdate = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                total += n
                if (contentLength > 0 && total - lastUpdate > 256 * 1024) {
                    lastUpdate = total
                    onProgress((total.toDouble() / contentLength).toFloat().coerceIn(0f, 1f))
                }
            }
            input.close()
            out.close()
            contentLength <= 0 || total == contentLength
        } catch (_: Exception) {
            target.delete()
            false
        }
    }

    private fun installApk(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        runCatching { context.startActivity(intent) }
    }

    /** 引导用户到系统设置授予"安装未知应用"权限（Android 12+ 必需）。 */
    private fun openInstallSettings(context: Context) {
        val ctx = context.applicationContext
        runCatching {
            ctx.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${ctx.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.getOrElse {
            runCatching {
                ctx.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }

    /** 字节数人类可读格式化。 */
    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val kb = 1024.0
        val mb = kb * 1024
        val gb = mb * 1024
        return when {
            bytes >= gb -> String.format("%.2f GB", bytes / gb)
            bytes >= mb -> String.format("%.1f MB", bytes / mb)
            else -> String.format("%.0f KB", bytes / kb)
        }
    }
}
