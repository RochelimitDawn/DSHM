package com.siliconleap.app.runtime

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.siliconleap.app.BuildConfig
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
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
import org.json.JSONArray

/**
 * 在线更新：从 GitHub Release 检查最新版本并下载安装。
 * 安装仅替换 APK，filesDir 数据（运行时/工作区/会话）保留，实现无缝切换。
 */
object UpdateManager {
    private const val REPO = "RochelimitDawn/DSHM"
    // 使用完整 release 列表而非 releases/latest：后者排除 prerelease，且在版本
    // release 不存在时会回落到资产类 release（如 uml-debian-subsystem），导致更新匹配错乱
    private const val RELEASES_API = "https://api.github.com/repos/$REPO/releases?per_page=100"
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

    /** 「本次关闭」标记（进程内存）：重启应用后恢复。与永久开关（AppSettings.updaterEnabled）取或。 */
    @Volatile
    private var sessionDisabled: Boolean = false

    /** 更新功能是否开启（永久开关 + 本次关闭取与）。 */
    fun updatesEnabled(context: Context): Boolean =
        AppSettings.updaterEnabled(context) && !sessionDisabled

    /** 关闭更新：permanent=true 永久关闭（设置持久化），false 仅本次（重启恢复）。 */
    fun setUpdatesDisabled(context: Context, permanent: Boolean) {
        sessionDisabled = true
        if (permanent) AppSettings.setUpdaterEnabled(context.applicationContext, false)
        _state.update { it.copy(available = null, message = "更新已关闭") }
    }

    /** 开启更新：清除本次关闭标记与永久开关，立即检查。 */
    fun setUpdatesEnabled(context: Context) {
        sessionDisabled = false
        AppSettings.setUpdaterEnabled(context.applicationContext, true)
        checkForUpdate(context, force = true)
    }

    /** 检查最新版本；有更新则标记 available。 */
    fun checkForUpdate(context: Context, force: Boolean = false) {
        if (!updatesEnabled(context)) {
            _state.update { it.copy(available = null) }
            return
        }
        val s = _state.value
        if (s.checking || s.downloading) return
        if (!force && s.available != null) return
        _state.update { it.copy(checking = true, message = "正在检查更新…") }
        scope.launch {
            // 渠道：安装版本为 beta，或用户开启「接收 Beta 版更新」
            val allowPre = allowPrereleaseBase || AppSettings.acceptAppBeta(context.applicationContext)
            // fetchOk=false 为网络/API 失败；fetchOk=true 且 info=null 表示
            // 渠道内暂无可升级的 release（如稳定版用户当前无正式 release）
            val result = runCatching { fetchLatest(allowPre) }.getOrNull()
            if (result == null) {
                _state.update { it.copy(checking = false, message = "检查更新失败，请稍后重试") }
                return@launch
            }
            val (fetchOk, info) = result
            if (!fetchOk) {
                _state.update { it.copy(checking = false, message = "当前渠道暂无可用更新") }
                return@launch
            }
            _state.update {
                if (info != null && info.versionCode > BuildConfig.VERSION_CODE) {
                    it.copy(checking = false, available = info, message = "发现新版本 ${info.versionName}")
                } else {
                    it.copy(checking = false, available = null, message = "当前已是最新版本")
                }
            }
        }
    }

    /** 下载并安装新版 APK。 */
    private var pendingApk: File? = null
    private var pendingVersionCode: Int = 0

    /** 是否已有匹配版本的已下载安装包（权限授权后可直接安装，无需重下）。 */
    fun hasPendingDownload(info: UpdateInfo): Boolean =
        pendingApk != null && pendingApk?.exists() == true && pendingVersionCode == info.versionCode

    fun downloadAndInstall(context: Context, info: UpdateInfo) {
        if (_state.value.downloading) return
        // 已下载且版本匹配：授权后直接安装，不重复下载（权限页返回后场景）
        val cached = pendingApk
        if (cached != null && cached.exists() && pendingVersionCode == info.versionCode) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                _state.update {
                    it.copy(downloading = false, message = "请先在系统设置中允许 DSHM 安装应用，再重新点击更新")
                }
                openInstallSettings(context)
                return
            }
            _state.update { it.copy(downloading = false, message = "正在安装已下载的更新包…") }
            installApk(context, cached)
            return
        }
        // 下载前检查安装权限，避免下载完成后无法安装导致重新下载
        if (!context.packageManager.canRequestPackageInstalls()) {
            _state.update {
                it.copy(downloading = false, message = "请先在系统设置中允许 DSHM 安装应用，再重新点击更新")
            }
            openInstallSettings(context)
            return
        }
        _state.update { it.copy(downloading = true, progress = 0f, message = "正在下载…") }
        scope.launch {
            val dir = File(TermuxEnv.filesDir(context), "downloads").apply { mkdirs() }
            val apk = File(dir, "dshm-update.apk")
            // 实际生效的源（auto 已解析）；GHProxy 源：给 GitHub 下载地址加加速域名前缀
            val downloadUrl = when (SourceManager.resolve(context)) {
                AppSettings.SOURCE_GHPROXY_CF -> "https://v6.gh-proxy.org/${info.apkUrl}"

                AppSettings.SOURCE_GHPROXY_AXISNOW -> "https://axisnow.gh-proxy.org/${info.apkUrl}"

                else -> info.apkUrl
            }
            val ok = downloadFile(downloadUrl, apk, info.sizeBytes) { pct, speed ->
                _state.update {
                    it.copy(progress = pct, message = "正在下载 ${(pct * 100).toInt()}% · $speed")
                }
            }
            if (!ok) {
                _state.update { it.copy(downloading = false, message = "下载失败，请重试") }
                return@launch
            }
            // 记录已下载包，供权限授权后直接安装
            pendingApk = apk
            pendingVersionCode = info.versionCode
            _state.update { it.copy(downloading = false, progress = 1f, message = "下载完成，正在安装…") }
            installApk(context, apk)
        }
    }

    /** 「最新版本」检查 API（GHProxy 只代理下载，检查仍走 GitHub）。 */
    private fun latestApi(): String = RELEASES_API

    /** 安装版本自带渠道：版本名含 beta 即收 prerelease（配合「接收 Beta 版更新」开关取或）。 */
    private val allowPrereleaseBase: Boolean
        get() = BuildConfig.VERSION_NAME.contains("beta", ignoreCase = true)

    private fun fetchLatest(allowPre: Boolean): Pair<Boolean, UpdateInfo?> = try {
        val conn = URL(latestApi()).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("Accept", "application/json")
        if (conn.responseCode !in 200..299) return Pair(false, null)
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        val releases = JSONArray(text)
        // 在合法 release 中取 versionCode 最高者：跳过 draft、按渠道过滤 prerelease、
        // 跳过无 app-release.apk 或 tag 无法解析版本号的资产类 release
        var best: UpdateInfo? = null
        var bestCode = Int.MIN_VALUE
        for (i in 0 until releases.length()) {
            val r = releases.getJSONObject(i)
            if (r.optBoolean("draft", false)) continue
            if (r.optBoolean("prerelease", false) && !allowPre) continue
            val tag = r.optString("tag_name", "")
            val code = parseVersionCode(tag) ?: continue
            if (code <= bestCode) continue
            val assets = r.optJSONArray("assets") ?: continue
            var apkUrl: String? = null
            var size = 0L
            for (j in 0 until assets.length()) {
                val a = assets.getJSONObject(j)
                if (a.optString("name") == APK_ASSET) {
                    apkUrl = a.optString("browser_download_url")
                    size = a.optLong("size", 0L)
                    break
                }
            }
            val url = apkUrl ?: continue
            bestCode = code
            best = UpdateInfo(tag, code, tag.removePrefix("v"), url, size, r.optString("body", ""))
        }
        Pair(true, best)
    } catch (_: Exception) {
        Pair(false, null)
    }

    private fun parseVersionCode(tag: String): Int? {
        // 版本格式 v{MAJ}.{MIN}.{PATCH}[-后缀]；与 build.gradle.kts 的 versionCode 推导保持一致：
        //   MAJ*10^7 + MIN*10^5 + PATCH*10^3，稳定版 +5（同版本号 稳定版 > 预发布版）
        // 例：v2.2.12-beta = 20212000，v2.2.12 = 20212005
        val m = Regex("""v(\d+)\.(\d+)\.(\d+)""").find(tag) ?: return null
        val maj = m.groupValues[1].toIntOrNull() ?: return null
        val min = m.groupValues[2].toIntOrNull() ?: return null
        val pat = m.groupValues[3].toIntOrNull() ?: return null
        val suffix = tag.substringAfter(m.value, "")
        val beta = suffix.startsWith("-")
        return maj * 10_000_000 + min * 100_000 + pat * 1_000 + (if (beta) 0 else 5)
    }

    private suspend fun downloadFile(
        url: String,
        target: File,
        sizeBytes: Long,
        onProgress: (Float, String) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        var input: InputStream? = null
        var out: OutputStream? = null
        var ok = false
        try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            if (conn.responseCode !in 200..299) return@withContext false
            val contentLength = if (sizeBytes > 0) sizeBytes else conn.contentLengthLong
            input = conn.inputStream
            out = BufferedOutputStream(FileOutputStream(target))
            val buf = ByteArray(64 * 1024)
            var total = 0L
            var lastUpdate = 0L
            var speedBps = 0L
            var lastSpeedAt = System.currentTimeMillis()
            var lastSpeedTotal = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                total += n
                val now = System.currentTimeMillis()
                if (now - lastSpeedAt >= 500) {
                    val dtSec = (now - lastSpeedAt) / 1000.0
                    if (dtSec > 0.0) speedBps = ((total - lastSpeedTotal) / dtSec).toLong()
                    lastSpeedAt = now
                    lastSpeedTotal = total
                }
                if (contentLength > 0 && total - lastUpdate > 512 * 1024) {
                    lastUpdate = total
                    onProgress(
                        (total.toDouble() / contentLength).toFloat().coerceIn(0f, 1f),
                        RuntimeManager.formatSpeed(speedBps),
                    )
                }
            }
            if (contentLength > 0 && total != contentLength) return@withContext false
            ok = true
            true
        } catch (_: Exception) {
            false
        } finally {
            runCatching { input?.close() }
            runCatching { out?.close() }
            runCatching { conn?.disconnect() }
            if (!ok) runCatching { target.delete() }
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
