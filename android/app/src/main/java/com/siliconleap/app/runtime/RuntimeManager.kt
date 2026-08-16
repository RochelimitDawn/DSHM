package com.siliconleap.app.runtime

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

enum class ServerPhase {
    NOT_READY,
    DOWNLOADING,
    EXTRACTING,
    STARTING,
    RUNNING,
    ERROR,
}

data class RuntimeState(
    val phase: ServerPhase = ServerPhase.NOT_READY,
    val progress: Float = 0f,
    val speedBytesPerSec: Long = 0L,
    val message: String = "",
    val port: Int = 3080,
    val pid: Long? = null,
    val runtimeVersion: String? = null,
    val installed: Boolean = false,
)

/** 存储占用统计。 */
data class StorageStats(
    val runtimeBytes: Long = 0L,
    val workspaceBytes: Long = 0L,
    val dshHomeBytes: Long = 0L,
    val logsBytes: Long = 0L,
) {
    val totalBytes: Long get() = runtimeBytes + workspaceBytes + dshHomeBytes + logsBytes
}

/** 运行时下载元数据（发布侧提供）。 */
data class RuntimeMeta(
    val version: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
    val mirrors: List<String> = emptyList(),
    val arch: String = "",
    val termuxApp: String = "",
    val dsh: String = "",
    val nodeVersion: String = "",
    val builtAt: String = "",
)

/** 环境页诊断快照（进程监控 / 会话 / 日志 / 存储 / 网络）。 */
data class RuntimeDiagnostics(
    val pid: Long? = null,
    val cpuPercent: Double = 0.0,
    val memRssKb: Long = 0L,
    val threads: Int = 0,
    val fds: Int = 0,
    val sessions: Int = 0,
    val logLines: Int = 0,
    val logBytes: Long = 0L,
    val freeBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val lanIps: List<String> = emptyList(),
)

object RuntimeManager {
    private const val READY_TIMEOUT_MS = 120_000L
    private const val DOWNLOAD_TIMEOUT_MS = 20 * 60_000L

    /** 默认元数据地址（GitHub Releases，runtime-latest 资产自动更新）。 */
    const val DEFAULT_META_URL =
        "https://github.com/RochelimitDawn/DSHM/releases/download/runtime-latest/metadata.json"

    /** 按下载源解析 metadata URL（github / ghproxy_cf / ghproxy_axisnow / custom）。 */
    fun effectiveMetaUrl(context: Context): String = when (AppSettings.downloadSource(context)) {
        AppSettings.SOURCE_GHPROXY_CF -> "https://v6.gh-proxy.org/$DEFAULT_META_URL"

        AppSettings.SOURCE_GHPROXY_AXISNOW -> "https://axisnow.gh-proxy.org/$DEFAULT_META_URL"

        AppSettings.SOURCE_CUSTOM ->
            AppSettings.customMetaUrl(context).ifBlank { DEFAULT_META_URL }

        else -> DEFAULT_META_URL
    }

    /** 按当前下载源刷新 metaUrl（切换源后调用）。 */
    fun refreshSource(context: Context) {
        metaUrl = effectiveMetaUrl(context)
    }

    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(RuntimeState())
    val state: StateFlow<RuntimeState> = _state.asStateFlow()

    private var serverProcess: Process? = null
    private var startedAt: Long = 0L

    /** 下载源列表（可由设置页调整）。 */
    var metaUrl: String = DEFAULT_META_URL
        private set

    fun setMetaUrl(url: String) {
        metaUrl = url
    }

    fun attach(context: Context) {
        if (!::appContext.isInitialized) {
            appContext = context.applicationContext
            metaUrl = effectiveMetaUrl(appContext)
        }
    }

    fun uptimeMillis(): Long =
        if (serverProcess?.isAlive == true) System.currentTimeMillis() - startedAt else 0L

    /** 各数据目录占用空间（调用方应在 IO 线程执行）。 */
    fun storageStats(): StorageStats = StorageStats(
        runtimeBytes = dirSize(TermuxEnv.prefix(appContext)),
        workspaceBytes = dirSize(TermuxEnv.workspace(appContext)),
        dshHomeBytes = dirSize(TermuxEnv.dshHome(appContext)),
        logsBytes = dirSize(TermuxEnv.logs(appContext)),
    )

    private fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        return runCatching {
            dir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
        }.getOrDefault(0L)
    }

    fun tailLog(lines: Int = 80): String {
        val log = TermuxEnv.serverLog(appContext)
        return if (log.exists()) log.readLines().takeLast(lines).joinToString("\n") else "(暂无日志)"
    }

    fun isRuntimeInstalled(): Boolean = TermuxEnv.dshEntry(appContext).exists()

    /** 仅下载并安装运行时（不自动启动），供环境页点击拉取。 */
    fun installRuntime() {
        if (_state.value.phase == ServerPhase.DOWNLOADING ||
            _state.value.phase == ServerPhase.EXTRACTING
        ) {
            return
        }
        scope.launch {
            downloadAndInstall()
        }
    }

    /** 启动/检查流程：未安装 → 在线下载安装；已安装 → 启动服务。 */
    fun bootstrap() {
        if (_state.value.phase == ServerPhase.RUNNING) return
        val installed = isRuntimeInstalled()
        _state.update {
            it.copy(
                installed = installed,
                runtimeVersion = if (installed) readRuntimeVersion() ?: it.runtimeVersion else null,
            )
        }
        scope.launch {
            if (ping(_state.value.port)) {
                _state.update {
                    it.copy(
                        phase = ServerPhase.RUNNING,
                        message = "服务运行中",
                        pid = processPid(serverProcess),
                    )
                }
                return@launch
            }
            if (!installed) {
                downloadAndInstall()
            }
            startServerIfNeeded()
        }
    }

    fun restart() {
        stopServer()
        _state.update { it.copy(phase = ServerPhase.NOT_READY) }
        bootstrap()
    }

    /** 删除运行时并重新下载安装（用于修复损坏/权限异常）。 */
    fun rebuildRuntime() {
        stopServer()
        TermuxEnv.prefix(appContext).deleteRecursively()
        _state.update { it.copy(phase = ServerPhase.NOT_READY, installed = false, runtimeVersion = null) }
        bootstrap()
    }

    /** 卸载运行时（保留 dsh-home/workspace）。 */
    fun uninstallRuntime() {
        stopServer()
        TermuxEnv.prefix(appContext).deleteRecursively()
        _state.update {
            it.copy(phase = ServerPhase.NOT_READY, installed = false, runtimeVersion = null)
        }
    }

    fun clearData() {
        stopServer()
        TermuxEnv.dshHome(appContext).deleteRecursively()
        _state.update { it.copy(phase = ServerPhase.NOT_READY) }
    }

    fun stopServer() {
        serverProcess?.destroy()
        runCatching { serverProcess?.waitFor(3, TimeUnit.SECONDS) }
        serverProcess?.destroyForcibly()
        serverProcess = null
        startedAt = 0L
        _state.update { it.copy(phase = ServerPhase.NOT_READY, pid = null) }
    }

    // ------------------------------------------------------------------ 下载安装

    private suspend fun downloadAndInstall() {
        clearLog()
        _state.update {
            it.copy(phase = ServerPhase.DOWNLOADING, progress = 0f, speedBytesPerSec = 0L, message = "正在获取运行时信息…")
        }
        appendLog("> 获取运行时信息…")
        val meta = runCatching { fetchMeta() }.getOrNull()
        if (meta == null) {
            appendLog("! 获取运行时信息失败，请检查网络或镜像源")
            _state.update {
                it.copy(phase = ServerPhase.ERROR, message = "获取运行时信息失败，请检查网络或镜像源")
            }
            return
        }
        val zip = File(TermuxEnv.filesDir(appContext), "runtime-download.zip")
        appendLog("> 开始下载运行时 v${meta.version}")
        val ok = downloadWithFallback(meta, zip)
        if (!ok) {
            appendLog("! 运行时下载失败，请检查网络或切换镜像源")
            _state.update {
                it.copy(phase = ServerPhase.ERROR, message = "运行时下载失败，请检查网络或切换镜像源")
            }
            return
        }
        appendLog("> 下载完成（${zip.length() / 1024 / 1024} MB），校验 sha256…")
        if (!verifySha256(zip, meta.sha256)) {
            appendLog("! 运行时校验失败（sha256 不匹配）")
            _state.update { it.copy(phase = ServerPhase.ERROR, message = "运行时校验失败（sha256 不匹配）") }
            return
        }
        appendLog("> sha256 校验通过，开始解压安装…")
        _state.update { it.copy(phase = ServerPhase.EXTRACTING, progress = 0f, message = "正在安装运行时…") }
        val installed = extractZip(zip, TermuxEnv.prefix(appContext))
        zip.delete()
        if (!installed) {
            appendLog("! 运行时安装失败")
            _state.update { it.copy(phase = ServerPhase.ERROR, message = "运行时安装失败") }
            return
        }
        appendLog("> 运行时安装完成")
        _state.update {
            it.copy(
                phase = ServerPhase.NOT_READY,
                installed = true,
                runtimeVersion = meta.version,
                progress = 1f,
                speedBytesPerSec = 0L,
            )
        }
    }

    /** 获取运行时实际大小（字节）；网络不通时返回 null（UI 回退显示约 500 MB）。 */
    suspend fun fetchRuntimeSize(): Long? = withContext(Dispatchers.IO) { fetchMeta()?.sizeBytes }

    /** 获取运行时完整元数据（版本/架构/构建时间）；网络不通时返回 null。 */
    suspend fun fetchRuntimeMeta(): RuntimeMeta? = withContext(Dispatchers.IO) { fetchMeta() }

    // ------------------------------------------------------------- 诊断数据

    private var lastCpuJiffies: Long = 0L
    private var lastCpuSampleAt: Long = 0L

    /** 环境页诊断快照（IO 线程执行，每秒轮询）。 */
    suspend fun diagnostics(): RuntimeDiagnostics = withContext(Dispatchers.IO) {
        val pid = processPid(serverProcess)
        var cpu = 0.0
        var memKb = 0L
        var threads = 0
        var fds = 0
        if (pid != null) {
            val jiffies = procJiffies(pid)
            val now = System.currentTimeMillis()
            if (lastCpuJiffies > 0L && lastCpuSampleAt > 0L && now > lastCpuSampleAt) {
                val dtSec = (now - lastCpuSampleAt) / 1000.0
                if (dtSec > 0.0) {
                    val dj = (jiffies - lastCpuJiffies).coerceAtLeast(0L)
                    cpu = (dj / 100.0 / dtSec * 100.0).coerceIn(0.0, 100.0)
                }
            }
            lastCpuJiffies = jiffies
            lastCpuSampleAt = now
            memKb = procStatusField(pid, "VmRSS") ?: 0L
            threads = procStatusField(pid, "Threads")?.toInt() ?: 0
            fds = runCatching { File("/proc/$pid/fd").listFiles()?.size ?: 0 }.getOrDefault(0)
        }
        val logFile = TermuxEnv.serverLog(appContext)
        val (logLines, logBytes) = runCatching {
            val bytes = logFile.length()
            val lines = logFile.readLines().size
            lines to bytes
        }.getOrDefault(0 to 0L)
        RuntimeDiagnostics(
            pid = pid,
            cpuPercent = cpu,
            memRssKb = memKb,
            threads = threads,
            fds = fds,
            sessions = sessionsCount(),
            logLines = logLines,
            logBytes = logBytes,
            freeBytes = deviceFreeBytes(),
            totalBytes = deviceTotalBytes(),
            lanIps = lanIps(),
        )
    }

    /** /proc/<pid>/stat 的 utime+stime（clock ticks），进程不存在时返回 0。 */
    private fun procJiffies(pid: Long): Long {
        val stat = runCatching { File("/proc/$pid/stat").readText() }.getOrNull() ?: return 0L
        // comm 可能含空格/括号，从最后一个 ')' 之后取数字字段
        val idx = stat.lastIndexOf(')')
        if (idx < 0) return 0L
        val parts = stat.substring(idx + 1).trim().split(' ')
        // 字段 3(utime)/4(stime) → 索引 11/12
        val utime = parts.getOrNull(11)?.toLongOrNull() ?: 0L
        val stime = parts.getOrNull(12)?.toLongOrNull() ?: 0L
        return utime + stime
    }

    /** /proc/<pid>/status 中形如 `VmRSS: 123456 kB` 的数字字段。 */
    private fun procStatusField(pid: Long, name: String): Long? {
        val line = runCatching {
            File("/proc/$pid/status").readLines().firstOrNull { it.startsWith("$name:") }
        }.getOrNull() ?: return null
        val value = line.substringAfter(':').trim().substringBefore(' ').toLongOrNull()
        return value
    }

    /** dsh-home/sessions 下的会话目录数。 */
    private fun sessionsCount(): Int = runCatching {
        File(TermuxEnv.dshHome(appContext), "sessions").listFiles()?.count { it.isDirectory } ?: 0
    }.getOrDefault(0)

    private fun deviceFreeBytes(): Long = runCatching {
        val stat = android.os.StatFs(appContext.filesDir.absolutePath)
        stat.availableBlocksLong * stat.blockSizeLong
    }.getOrDefault(0L)

    private fun deviceTotalBytes(): Long = runCatching {
        val stat = android.os.StatFs(appContext.filesDir.absolutePath)
        stat.blockCountLong * stat.blockSizeLong
    }.getOrDefault(0L)

    /** 枚举所有启用的 IPv4 局域网地址（排除回环）。 */
    private fun lanIps(): List<String> = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filter { it is java.net.Inet4Address && !it.isLoopbackAddress }
            .map { it.hostAddress }
            .toList()
    }.getOrDefault(emptyList())

    private fun fetchMeta(): RuntimeMeta? = try {        val conn = URL(metaUrl).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        val json = JSONObject(text)
        RuntimeMeta(
            version = json.optString("version", "unknown"),
            url = json.getString("url"),
            sha256 = json.optString("sha256", ""),
            sizeBytes = json.optLong("sizeBytes", 0L),
            mirrors = json.optJSONArray("mirrors")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            } ?: emptyList(),
            arch = json.optString("arch", ""),
            termuxApp = json.optString("termuxApp", ""),
            dsh = json.optString("dsh", ""),
            nodeVersion = json.optString("nodeVersion", ""),
            builtAt = json.optString("builtAt", ""),
        )
    } catch (e: Exception) {
        null
    }

    private suspend fun downloadWithFallback(meta: RuntimeMeta, target: File): Boolean {
        val candidates = listOf(meta.url) + meta.mirrors
        for (candidate in candidates) {
            _state.update {
                it.copy(message = "正在下载运行时（${meta.version}）…", speedBytesPerSec = 0L)
            }
            if (downloadFile(candidate, target, meta.sizeBytes)) return true
            _state.update { it.copy(message = "下载源不可用，尝试切换…", speedBytesPerSec = 0L) }
        }
        return false
    }

    private suspend fun downloadFile(url: String, target: File, sizeBytes: Long): Boolean {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            if (conn.responseCode !in 200..299) return false
            val contentLength = if (sizeBytes > 0) sizeBytes else conn.contentLengthLong
            target.parentFile?.mkdirs()
            val out = FileOutputStream(target)
            val input: InputStream = conn.inputStream
            val buf = ByteArray(64 * 1024)
            var total = 0L
            var lastUpdate = 0L
            var lastLoggedPct = -1
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
                        val speed = formatSpeed(speedBps)
                        if (pctInt / 5 > lastLoggedPct) {
                            lastLoggedPct = pctInt / 5
                            appendLog("> 下载中 ${pctInt}%（${speed}）")
                        }
                        _state.update {
                            it.copy(
                                progress = pct.toFloat(),
                                speedBytesPerSec = speedBps,
                                message = "正在下载运行时（${pctInt}%）· ${speed}",
                            )
                        }
                    }
                }
                if (contentLength > 0 && total > contentLength) {
                    input.close()
                    out.close()
                    return false
                }
            }
            input.close()
            out.close()
            if (contentLength > 0 && total != contentLength) {
                target.delete()
                return false
            }
            true
        } catch (e: Exception) {
            target.delete()
            false
        }
    }

    /** 字节速率人类可读格式化（如 12.3 MB/s）；未采样到时返回省略号。 */
    fun formatSpeed(bytesPerSec: Long): String = when {
        bytesPerSec <= 0 -> "…"

        bytesPerSec >= 1024 * 1024 -> String.format("%.1f MB/s", bytesPerSec / 1024.0 / 1024.0)

        bytesPerSec >= 1024 -> String.format("%.0f KB/s", bytesPerSec / 1024.0)

        else -> "${bytesPerSec} B/s"
    }

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

    private fun extractZip(zip: File, dest: File): Boolean = runCatching {
        val tmp = File(dest.parentFile, "usr.tmp")
        tmp.deleteRecursively()
        tmp.mkdirs()
        val input = ZipInputStream(zip.inputStream())
        var entry = input.nextEntry
        var done = 0L
        val total = estimateEntries(zip)
        while (entry != null) {
            val target = safeResolve(tmp, entry.name)
            if (target != null) {
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { out -> input.copyTo(out) }
                }
            }
            entry = input.nextEntry
            done++
            if (done % 200L == 0L && total > 0) {
                _state.update { it.copy(progress = (done.toFloat() / total).coerceIn(0f, 1f)) }
                appendLog("> 解压中 ${done} 个文件…")
            }
        }
        input.close()
        val nested = File(tmp, "usr")
        if (nested.isDirectory) {
            copyRecursively(nested, tmp)
            nested.deleteRecursively()
        }
        makeExecutable(File(tmp, "bin"))
        makeExecutable(File(tmp, "libexec"))
        if (dest.exists()) dest.deleteRecursively()
        if (!tmp.renameTo(dest)) {
            copyRecursively(tmp, dest)
            tmp.deleteRecursively()
        }
        true
    }.getOrDefault(false)

    private fun estimateEntries(zip: File): Long = runCatching {
        val input = ZipInputStream(zip.inputStream())
        var count = 0L
        while (input.nextEntry != null) count++
        input.close()
        count
    }.getOrDefault(0L)

    private fun safeResolve(base: File, name: String): File? {
        val cleaned = name.removePrefix("/")
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
        } else {
            src.copyTo(dst, overwrite = true)
        }
    }

    // ------------------------------------------------------------------ 服务启动

    private suspend fun startServerIfNeeded() {
        if (_state.value.phase == ServerPhase.RUNNING) return
        startServer()
        if (_state.value.phase == ServerPhase.RUNNING) return
        waitForReady()
    }

    fun startServer() {
        if (_state.value.phase == ServerPhase.RUNNING || _state.value.phase == ServerPhase.STARTING) return
        val ctx = appContext
        TermuxEnv.home(ctx).mkdirs()
        TermuxEnv.tmp(ctx).mkdirs()
        TermuxEnv.dshHome(ctx).mkdirs()
        TermuxEnv.workspace(ctx).mkdirs()
        TermuxEnv.logs(ctx).mkdirs()
        TermuxEnv.ensureBinLinks(ctx)

        val port = _state.value.port
        val node = TermuxEnv.nodeBin(ctx)
        val entry = TermuxEnv.dshEntry(ctx)
        val logFile = TermuxEnv.serverLog(ctx)

        // 每次启动服务清空日志，终端日志框只展示本次过程（不做历史累积）。
        clearLog()

        // credentials-local 要求凭证文件仅属主可读（mode 600），否则拒绝读取
        // 导致 DeepSeek API Key 解析失败。Android 解压可能带 group/other 位，先收敛。
        val credentialsFile = File(TermuxEnv.dshHome(ctx), ".credentials.yaml")
        if (credentialsFile.exists()) {
            runCatching {
                credentialsFile.setReadable(true, true)
                credentialsFile.setWritable(true, true)
            }
        }

        val diag = preflight(node, entry)
        if (diag != null) {
            writeDiagnostics(logFile, diag)
            _state.update { it.copy(phase = ServerPhase.ERROR, message = "启动自检失败\n\n$diag") }
            return
        }

        val command = listOf(node.absolutePath, "--expose-internals", entry.absolutePath, "web", "--port", port.toString())
        val pb = ProcessBuilder(command)
        pb.environment().putAll(TermuxEnv.serverEnv(ctx))
        pb.directory(TermuxEnv.workspace(ctx))
        pb.redirectErrorStream(true)
        // PIPE 逐行转发到 server.log：node stdout 重定向到文件时是块缓冲，
        // 小日志不 flush 会导致启动日志框空白；应用侧逐行读+flush 保证实时可见。
        pb.redirectOutput(ProcessBuilder.Redirect.PIPE)

        serverProcess = try {
            pb.start()
        } catch (e: Exception) {
            val detail = "启动失败：${e.message ?: e.javaClass.simpleName}"
            writeDiagnostics(logFile, detail)
            _state.update { it.copy(phase = ServerPhase.ERROR, message = detail) }
            return
        }
        forwardProcessOutput(serverProcess, logFile)
        startedAt = System.currentTimeMillis()
        _state.update {
            it.copy(phase = ServerPhase.STARTING, pid = processPid(serverProcess), message = "正在启动服务…")
        }
    }

    /** 将 node 进程的 stdout/stderr 逐行追加到 server.log（实时 flush）。 */
    private fun forwardProcessOutput(proc: Process?, logFile: File) {
        if (proc == null) return
        scope.launch {
            runCatching {
                val reader = proc.inputStream.bufferedReader()
                val writer = logFile.bufferedWriter()
                try {
                    for (line in reader.lineSequence()) {
                        runCatching { writer.write(line + "\n"); writer.flush() }
                    }
                } finally {
                    runCatching { writer.close() }
                }
            }
        }
    }

    private fun preflight(node: File, entry: File): String? {
        val prefix = TermuxEnv.prefix(appContext).absolutePath
        val nativeLib = TermuxEnv.nativeLibDir(appContext).absolutePath
        val lines = mutableListOf<String>()
        lines += "prefix=$prefix"
        lines += "nativeLib=$nativeLib"
        lines += "node=$node | 存在=${node.exists()} | 可执行=${node.canExecute()} | 大小=${runCatching { node.length() }.getOrNull()}"
        lines += "dsh=$entry | 存在=${entry.exists()}"
        lines += "libc++_shared.so=${File(nativeLib, "libc++_shared.so").exists()}"
        lines += "logs=${TermuxEnv.serverLog(appContext).absolutePath}"
        val missing = lines.filter { it.contains("不存在") || it.contains("false") }
        if (missing.isNotEmpty()) return lines.joinToString("\n")
        return null
    }

    private fun writeDiagnostics(logFile: File, content: String) {
        runCatching {
            logFile.parentFile?.mkdirs()
            logFile.writeText(content)
        }
    }

    private fun appendLog(line: String) {
        runCatching {
            val logFile = TermuxEnv.serverLog(appContext)
            logFile.parentFile?.mkdirs()
            logFile.appendText(line + "\n")
        }
    }

    /** 清空服务日志文件（每次下载/启动时调用，终端日志框不做历史累积）。 */
    private fun clearLog() {
        runCatching {
            val logFile = TermuxEnv.serverLog(appContext)
            logFile.parentFile?.mkdirs()
            logFile.writeText("")
        }
    }

    private fun processPid(p: Process?): Long? {
        val pid = p?.let { proc ->
            runCatching { (proc.javaClass.getMethod("pid").invoke(proc) as Number).toLong() }.getOrNull()
        }
        if (pid != null) return pid
        // serverProcess 引用丢失（如应用重启后旧 node 进程仍存活）时，通过监听端口反查 PID
        return findPidByPort(_state.value.port)
    }

    /** 读取已安装运行时的 dsh 版本（本地，离线可用）。 */
    private fun readRuntimeVersion(): String? = runCatching {
        val pkg = File(
            TermuxEnv.prefix(appContext),
            "lib/node_modules/@deepseek-ai/dsh/package.json",
        )
        JSONObject(pkg.readText()).optString("version").ifBlank { null }
    }.getOrNull()

    /** 通过 /proc/net/tcp 监听端口反查进程 PID（用于 serverProcess 引用丢失的场景）。 */
    private fun findPidByPort(port: Int): Long? {
        val inode = findSocketInode(port) ?: return null
        val target = "socket:[$inode]"
        val procDir = File("/proc")
        val pids = procDir.listFiles()?.filter { it.name.all { c -> c.isDigit() } } ?: return null
        for (dir in pids) {
            val fdDir = File(dir, "fd")
            val fds = fdDir.listFiles() ?: continue
            val hit = fds.any { fd ->
                runCatching { java.nio.file.Files.readSymbolicLink(fd.toPath()).toString().contains(target) }
                    .getOrDefault(false)
            }
            if (hit) return dir.name.toLong()
        }
        return null
    }

    /** 解析 /proc/net/tcp(+tcp6) 中监听指定端口的 socket inode（仅 LISTEN 状态）。 */
    private fun findSocketInode(port: Int): String? {
        val portHex = String.format("%04X", port)
        for (path in listOf("/proc/net/tcp", "/proc/net/tcp6")) {
            val lines = runCatching { File(path).readLines() }.getOrDefault(emptyList())
            for (line in lines.drop(1)) {
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size >= 10 &&
                    parts[1].substringAfter(':').equals(portHex, ignoreCase = true) &&
                    parts[3] == "0A"
                ) {
                    return parts[9]
                }
            }
        }
        return null
    }

    private suspend fun waitForReady() {
        val port = _state.value.port
        val started = System.currentTimeMillis()
        while (System.currentTimeMillis() - started < READY_TIMEOUT_MS) {
            val proc = serverProcess
            if (proc == null || !proc.isAlive) {
                val exit = proc?.let { runCatching { it.exitValue() }.getOrNull() }
                _state.update {
                    it.copy(
                        phase = ServerPhase.ERROR,
                        message = "服务进程已退出（exit=${exit ?: "?"}），请查看日志\n\n${tailLog(60)}",
                    )
                }
                return
            }
            if (ping(port)) {
                _state.update { it.copy(phase = ServerPhase.RUNNING, message = "服务运行中") }
                return
            }
            delay(500)
        }
        _state.update {
            it.copy(phase = ServerPhase.ERROR, message = "服务启动超时，请查看日志\n\n${tailLog(40)}")
        }
    }

    private fun ping(port: Int): Boolean {
        return try {
            val conn = URL("http://127.0.0.1:$port/").openConnection() as HttpURLConnection
            conn.connectTimeout = 800
            conn.readTimeout = 800
            val code = conn.responseCode
            conn.disconnect()
            // 只要拿到任意 HTTP 响应码即说明服务在监听（DSH 根路径可能返回 404/重定向）
            code in 100..599
        } catch (_: Exception) {
            false
        }
    }
}
