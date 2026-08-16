package com.siliconleap.app.runtime

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
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

enum class SubsystemPhase {
    NOT_INSTALLED,
    DOWNLOADING,
    EXTRACTING,
    READY,
    ERROR,
}

data class SubsystemState(
    val phase: SubsystemPhase = SubsystemPhase.NOT_INSTALLED,
    val progress: Float = 0f,
    val speedBytesPerSec: Long = 0L,
    val message: String = "",
    val version: String? = null,
    val installedBytes: Long = 0L,
)

/** Debian 子系统元数据（debian-subsystem release 提供）。 */
data class SubsystemMeta(
    val version: String,
    val rootfsUrl: String,
    val rootfsSha256: String,
    val rootfsSizeBytes: Long,
)

/**
 * Debian 子系统管理：在线下载 rootfs、解压安装、卸载。
 * proot 二进制由 APK 内置（nativeLibraryDir，SELinux 允许执行）；
 * 实际 shell 命令由 DSH 服务经 proot 包裹执行（每次调用冷启动，无常驻进程）。
 */
object SubsystemManager {
    private const val DEFAULT_META_URL =
        "https://github.com/RochelimitDawn/DSHM/releases/download/debian-subsystem/metadata.json"

    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(SubsystemState())
    val state: StateFlow<SubsystemState> = _state.asStateFlow()

    fun attach(context: Context) {
        if (!::appContext.isInitialized) appContext = context.applicationContext
    }

    // ------------------------------------------------------------- 路径

    fun subsystemDir(context: Context): File = File(TermuxEnv.filesDir(context), "subsystem")

    fun rootfsDir(context: Context): File = File(subsystemDir(context), "rootfs")

    /** proot 二进制（APK 内置，nativeLibraryDir 为 SELinux 允许执行区）。 */
    fun prootBin(context: Context): File = File(TermuxEnv.nativeLibDir(context), "libproot.so")

    fun resolvConf(context: Context): File = File(subsystemDir(context), "resolv.conf")

    private fun subsystemLog(context: Context): File = File(TermuxEnv.logs(context), "subsystem.log")

    fun isInstalled(context: Context): Boolean =
        File(rootfsDir(context), "etc").isDirectory && File(rootfsDir(context), "bin/bash").exists()

    fun subsystemSize(context: Context): Long = runCatching {
        subsystemDir(context).walkTopDown().filter { it.isFile }.map { it.length() }.sum()
    }.getOrDefault(0L)

    fun metaUrl(context: Context): String = when (AppSettings.downloadSource(context)) {
        AppSettings.SOURCE_GHPROXY_CF -> "https://v6.gh-proxy.org/$DEFAULT_META_URL"

        AppSettings.SOURCE_GHPROXY_AXISNOW -> "https://axisnow.gh-proxy.org/$DEFAULT_META_URL"

        AppSettings.SOURCE_CUSTOM -> AppSettings.customMetaUrl(context).ifBlank { DEFAULT_META_URL }

        else -> DEFAULT_META_URL
    }

    fun tailLog(context: Context, lines: Int = 80): String =
        runCatching { subsystemLog(context).readLines().takeLast(lines).joinToString("\n") }
            .getOrDefault("(暂无日志)")

    // ------------------------------------------------------------- 安装/卸载

    fun installSubsystem() {
        val p = _state.value.phase
        if (p == SubsystemPhase.DOWNLOADING || p == SubsystemPhase.EXTRACTING) return
        scope.launch { downloadAndInstall() }
    }

    fun uninstallSubsystem() {
        val p = _state.value.phase
        if (p == SubsystemPhase.DOWNLOADING || p == SubsystemPhase.EXTRACTING) return
        scope.launch {
            runCatching { subsystemDir(appContext).deleteRecursively() }
            _state.update {
                it.copy(
                    phase = SubsystemPhase.NOT_INSTALLED,
                    version = null,
                    progress = 0f,
                    speedBytesPerSec = 0L,
                    message = "子系统已卸载",
                )
            }
            appendLog("> 子系统已卸载")
        }
    }

    private suspend fun downloadAndInstall() {
        clearLog()
        _state.update {
            it.copy(phase = SubsystemPhase.DOWNLOADING, progress = 0f, speedBytesPerSec = 0L, message = "正在获取子系统信息…")
        }
        appendLog("> 获取子系统信息…")
        val meta = runCatching { fetchMeta() }.getOrNull()
        if (meta == null) {
            appendLog("! 获取子系统信息失败，请检查网络或下载源")
            _state.update { it.copy(phase = SubsystemPhase.ERROR, message = "获取子系统信息失败，请检查网络或下载源") }
            return
        }
        if (!hasEnoughSpace(meta.rootfsSizeBytes)) {
            appendLog("! 存储空间不足，安装已阻止")
            _state.update { it.copy(phase = SubsystemPhase.ERROR, message = "存储空间不足，无法安装子系统") }
            return
        }
        val tar = File(TermuxEnv.filesDir(appContext), "subsystem-rootfs.tar.gz")
        appendLog("> 开始下载 Debian 子系统（${meta.version}）…")
        val ok = downloadWithFallback(meta.rootfsUrl, tar, meta.rootfsSizeBytes)
        if (!ok) {
            appendLog("! 子系统下载失败，请检查网络或切换下载源")
            _state.update { it.copy(phase = SubsystemPhase.ERROR, message = "子系统下载失败，请检查网络或切换下载源") }
            return
        }
        appendLog("> 下载完成（${tar.length() / 1024 / 1024} MB），校验 sha256…")
        if (!verifySha256(tar, meta.rootfsSha256)) {
            appendLog("! 子系统校验失败（sha256 不匹配）")
            _state.update { it.copy(phase = SubsystemPhase.ERROR, message = "子系统校验失败（sha256 不匹配）") }
            return
        }
        appendLog("> sha256 校验通过，开始安装…")
        _state.update { it.copy(phase = SubsystemPhase.EXTRACTING, progress = 0f, message = "正在安装子系统…") }
        val installed = extractTarGz(tar, rootfsDir(appContext))
        tar.delete()
        if (!installed) {
            appendLog("! 子系统安装失败")
            _state.update { it.copy(phase = SubsystemPhase.ERROR, message = "子系统安装失败") }
            return
        }
        writeResolvConf()
        appendLog("> 子系统安装完成（Debian ${readDebianVersion()}）")
        _state.update {
            it.copy(
                phase = SubsystemPhase.READY,
                version = meta.version,
                progress = 1f,
                speedBytesPerSec = 0L,
                message = "子系统已就绪",
            )
        }
    }

    // ------------------------------------------------------------- 下载

    private suspend fun fetchMeta(): SubsystemMeta? = withContext(Dispatchers.IO) {
        try {
            val conn = URL(metaUrl(appContext)).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            SubsystemMeta(
                version = json.optString("version", "debian"),
                rootfsUrl = json.getString("rootfsUrl"),
                rootfsSha256 = json.optString("rootfsSha256", ""),
                rootfsSizeBytes = json.optLong("rootfsSizeBytes", 0L),
            )
        } catch (_: Exception) {
            null
        }
    }

    /** 按下载源给 GitHub 地址加 GHProxy 前缀，直连兜底。 */
    private suspend fun downloadWithFallback(url: String, target: File, sizeBytes: Long): Boolean {
        val prefix = when (AppSettings.downloadSource(appContext)) {
            AppSettings.SOURCE_GHPROXY_CF -> "https://v6.gh-proxy.org/"

            AppSettings.SOURCE_GHPROXY_AXISNOW -> "https://axisnow.gh-proxy.org/"

            else -> ""
        }
        val primary = if (prefix.isNotEmpty() && url.startsWith("https://github.com/")) prefix + url else url
        val candidates = if (primary == url) listOf(url) else listOf(primary, url)
        for (c in candidates) {
            if (downloadSingle(c, target, sizeBytes)) return true
        }
        return false
    }

    private suspend fun downloadSingle(url: String, target: File, sizeBytes: Long): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.instanceFollowRedirects = true
                if (conn.responseCode !in 200..299) return@withContext false
                val contentLength = if (sizeBytes > 0) sizeBytes else conn.contentLengthLong
                target.parentFile?.mkdirs()
                val out = FileOutputStream(target)
                val input: InputStream = conn.inputStream
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
                    if (total - lastUpdate > 256 * 1024 || (contentLength > 0 && total >= contentLength)) {
                        lastUpdate = total
                        if (contentLength > 0) {
                            val pct = (total.toDouble() / contentLength).coerceIn(0.0, 1.0)
                            val pctInt = (pct * 100).toInt()
                            _state.update {
                                it.copy(
                                    progress = pct.toFloat(),
                                    speedBytesPerSec = speedBps,
                                    message = "正在下载子系统（${pctInt}%）· ${RuntimeManager.formatSpeed(speedBps)}",
                                )
                            }
                        }
                    }
                    if (contentLength > 0 && total > contentLength) {
                        input.close()
                        out.close()
                        return@withContext false
                    }
                }
                input.close()
                out.close()
                if (contentLength > 0 && total != contentLength) {
                    target.delete()
                    return@withContext false
                }
                true
            } catch (_: Exception) {
                target.delete()
                false
            }
        }

    // ------------------------------------------------------------- 校验/空间

    private fun verifySha256(file: File, expected: String): Boolean {
        if (expected.isBlank()) return true
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }.equals(expected, ignoreCase = true)
        }.getOrDefault(false)
    }

    private fun hasEnoughSpace(requiredBytes: Long): Boolean {
        if (requiredBytes <= 0L) return true
        return runCatching {
            val stat = android.os.StatFs(appContext.filesDir.absolutePath)
            val free = stat.availableBlocksLong * stat.blockSizeLong
            free > requiredBytes * 15 / 10
        }.getOrDefault(true)
    }

    private fun writeResolvConf() {
        runCatching {
            val f = resolvConf(appContext)
            f.parentFile?.mkdirs()
            f.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
        }
    }

    private fun readDebianVersion(): String = runCatching {
        File(rootfsDir(appContext), "etc/debian_version").readText().trim()
    }.getOrDefault("")

    // ------------------------------------------------------------- tar.gz 解压

    private fun extractTarGz(file: File, dest: File): Boolean = runCatching {
        val tmp = File(dest.parentFile, "subsys.tmp")
        tmp.deleteRecursively()
        tmp.mkdirs()
        GZIPInputStream(file.inputStream()).use { input ->
            val header = ByteArray(512)
            var pendingName: String? = null
            var pendingLink: String? = null
            while (true) {
                val n = readFully(input, header, 0, 512)
                if (n <= 0) break
                if (header.all { it == 0.toByte() }) break
                val type = header[156].toInt().toChar()
                val size = parseOctal(header, 124, 12)
                when (type) {
                    'L' -> { pendingName = readTarBlock(input, size); continue }
                    'K' -> { pendingLink = readTarBlock(input, size); continue }
                    'x', 'g' -> { skipTarData(input, size); continue }
                }
                var name = parseTarName(header)
                if (pendingName != null) { name = pendingName!!; pendingName = null }
                var link = parseTarLink(header)
                if (pendingLink != null) { link = pendingLink!!; pendingLink = null }
                val target = safeResolve(tmp, name)
                if (target == null) { skipTarData(input, size); skipPadding(input, size); continue }
                when (type) {
                    '5' -> target.mkdirs()
                    '0', '\u0000' -> {
                        target.parentFile?.mkdirs()
                        copyTarData(input, target, size)
                    }
                    '2' -> {
                        target.parentFile?.mkdirs()
                        if (link != null) {
                            runCatching {
                                java.nio.file.Files.createSymbolicLink(target.toPath(), java.nio.file.Paths.get(link))
                            }
                        }
                    }
                    '1' -> {
                        target.parentFile?.mkdirs()
                        if (link != null) {
                            val src = safeResolve(tmp, link.removePrefix("./"))
                            if (src != null && src.isFile) src.copyTo(target, overwrite = true)
                        }
                    }
                    else -> skipTarData(input, size)
                }
                skipPadding(input, size)
            }
        }
        // 常见 bin 目录恢复可执行位
        for (d in listOf("bin", "sbin", "usr/bin", "usr/sbin", "usr/local/bin")) {
            makeExecutable(File(tmp, d))
        }
        if (dest.exists()) dest.deleteRecursively()
        if (!tmp.renameTo(dest)) {
            copyRecursively(tmp, dest)
            tmp.deleteRecursively()
        }
        true
    }.getOrDefault(false)

    private fun readFully(input: InputStream, buf: ByteArray, off: Int, len: Int): Int {
        var total = 0
        while (total < len) {
            val n = input.read(buf, off + total, len - total)
            if (n < 0) return if (total == 0) -1 else total
            total += n
        }
        return total
    }

    private fun parseOctal(header: ByteArray, offset: Int, len: Int): Long {
        var v = 0L
        for (i in offset until offset + len) {
            val c = header[i].toInt().toChar()
            if (c == ' ' || c == '\u0000') continue
            if (c < '0' || c > '7') break
            v = v * 8 + (c - '0')
        }
        return v
    }

    private fun parseTarName(header: ByteArray): String {
        val name = header.copyOfRange(0, 100).toString(Charsets.UTF_8).trim('\u0000', ' ')
        val prefix = header.copyOfRange(345, 500).toString(Charsets.UTF_8).trim('\u0000', ' ')
        return if (prefix.isNotEmpty()) "$prefix/$name" else name
    }

    private fun parseTarLink(header: ByteArray): String? {
        val link = header.copyOfRange(157, 257).toString(Charsets.UTF_8).trim('\u0000', ' ')
        return link.ifEmpty { null }
    }

    private fun readTarBlock(input: InputStream, size: Long): String {
        val data = ByteArray(size.toInt().coerceAtLeast(0))
        if (data.isNotEmpty()) readFully(input, data, 0, data.size)
        return data.toString(Charsets.UTF_8).trim('\u0000')
    }

    private fun copyTarData(input: InputStream, target: File, size: Long) {
        FileOutputStream(target).use { out ->
            var remaining = size
            val buf = ByteArray(64 * 1024)
            while (remaining > 0) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                if (n < 0) break
                out.write(buf, 0, n)
                remaining -= n
            }
        }
    }

    private fun skipTarData(input: InputStream, size: Long) {
        var remaining = size
        val buf = ByteArray(64 * 1024)
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n < 0) break
            remaining -= n
        }
    }

    private fun skipPadding(input: InputStream, size: Long) {
        val pad = ((512 - size % 512) % 512).toInt()
        if (pad > 0) {
            val buf = ByteArray(pad)
            readFully(input, buf, 0, pad)
        }
    }

    private fun safeResolve(base: File, name: String): File? {
        val cleaned = name.removePrefix("./").removePrefix("/")
        if (cleaned == "" || cleaned.contains("..") || cleaned.contains("\u0000")) return null
        return File(base, cleaned)
    }

    private fun makeExecutable(dir: File) {
        if (!dir.isDirectory) return
        dir.listFiles()?.forEach { it.setExecutable(true, false) }
    }

    private fun copyRecursively(src: File, dst: File) {
        if (src.isDirectory) {
            dst.mkdirs()
            src.listFiles()?.forEach { copyRecursively(it, File(dst, it.name)) }
        } else if (src.isFile) {
            src.copyTo(dst, overwrite = true)
        }
    }

    // ------------------------------------------------------------- 日志

    private fun appendLog(line: String) {
        runCatching {
            val f = subsystemLog(appContext)
            f.parentFile?.mkdirs()
            f.appendText(line + "\n")
        }
    }

    private fun clearLog() {
        runCatching {
            val f = subsystemLog(appContext)
            f.parentFile?.mkdirs()
            f.writeText("")
        }
    }
}
