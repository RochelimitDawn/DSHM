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
    val message: String = "",
    val port: Int = 3080,
    val pid: Long? = null,
    val runtimeVersion: String? = null,
    val installed: Boolean = false,
)

/** 运行时下载元数据（发布侧提供）。 */
data class RuntimeMeta(
    val version: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
    val mirrors: List<String> = emptyList(),
)

object RuntimeManager {
    private const val READY_TIMEOUT_MS = 120_000L
    private const val DOWNLOAD_TIMEOUT_MS = 20 * 60_000L

    /** 默认元数据地址（GitHub Releases，runtime-latest 资产自动更新）。 */
    const val DEFAULT_META_URL =
        "https://github.com/RochelimitDawn/SiliconLeap/releases/download/runtime-latest/metadata.json"

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
        }
    }

    fun uptimeMillis(): Long =
        if (serverProcess?.isAlive == true) System.currentTimeMillis() - startedAt else 0L

    fun tailLog(lines: Int = 80): String {
        val log = TermuxEnv.serverLog(appContext)
        return if (log.exists()) log.readLines().takeLast(lines).joinToString("\n") else "(暂无日志)"
    }

    fun isRuntimeInstalled(): Boolean = TermuxEnv.dshEntry(appContext).exists()

    /** 启动/检查流程：未安装 → 在线下载安装；已安装 → 启动服务。 */
    fun bootstrap() {
        if (_state.value.phase == ServerPhase.RUNNING) return
        _state.update { it.copy(installed = isRuntimeInstalled()) }
        scope.launch {
            if (ping(_state.value.port)) {
                _state.update { it.copy(phase = ServerPhase.RUNNING, message = "服务运行中") }
                return@launch
            }
            if (!isRuntimeInstalled()) {
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
        _state.update { it.copy(phase = ServerPhase.DOWNLOADING, progress = 0f, message = "正在获取运行时信息…") }
        val meta = runCatching { fetchMeta() }.getOrNull()
        if (meta == null) {
            _state.update {
                it.copy(phase = ServerPhase.ERROR, message = "获取运行时信息失败，请检查网络或镜像源")
            }
            return
        }
        val zip = File(TermuxEnv.filesDir(appContext), "runtime-download.zip")
        val ok = downloadWithFallback(meta, zip)
        if (!ok) {
            _state.update {
                it.copy(phase = ServerPhase.ERROR, message = "运行时下载失败，请检查网络或切换镜像源")
            }
            return
        }
        if (!verifySha256(zip, meta.sha256)) {
            _state.update { it.copy(phase = ServerPhase.ERROR, message = "运行时校验失败（sha256 不匹配）") }
            return
        }
        _state.update { it.copy(phase = ServerPhase.EXTRACTING, progress = 0f, message = "正在安装运行时…") }
        val installed = extractZip(zip, TermuxEnv.prefix(appContext))
        zip.delete()
        if (!installed) {
            _state.update { it.copy(phase = ServerPhase.ERROR, message = "运行时安装失败") }
            return
        }
        _state.update {
            it.copy(phase = ServerPhase.NOT_READY, installed = true, runtimeVersion = meta.version, progress = 1f)
        }
    }

    private fun fetchMeta(): RuntimeMeta? = try {
        val conn = URL(metaUrl).openConnection() as HttpURLConnection
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
        )
    } catch (e: Exception) {
        null
    }

    private suspend fun downloadWithFallback(meta: RuntimeMeta, target: File): Boolean {
        val candidates = listOf(meta.url) + meta.mirrors
        for (candidate in candidates) {
            _state.update { it.copy(message = "正在下载运行时（${meta.version}）…") }
            if (downloadFile(candidate, target, meta.sizeBytes)) return true
            _state.update { it.copy(message = "下载源不可用，尝试切换…") }
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
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                total += n
                if (total - lastUpdate > 256 * 1024 || (contentLength > 0 && total >= contentLength)) {
                    lastUpdate = total
                    if (contentLength > 0) {
                        val pct = (total.toDouble() / contentLength).coerceIn(0.0, 1.0)
                        _state.update {
                            it.copy(progress = pct.toFloat(), message = "正在下载运行时（${(pct * 100).toInt()}%）…")
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

        val diag = preflight(node, entry)
        if (diag != null) {
            writeDiagnostics(logFile, diag)
            _state.update { it.copy(phase = ServerPhase.ERROR, message = "启动自检失败\n\n$diag") }
            return
        }

        val command = listOf(node.absolutePath, entry.absolutePath, "web", "--port", port.toString())
        val pb = ProcessBuilder(command)
        pb.environment().putAll(TermuxEnv.serverEnv(ctx))
        pb.directory(TermuxEnv.workspace(ctx))
        pb.redirectErrorStream(true)
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))

        serverProcess = try {
            pb.start()
        } catch (e: Exception) {
            val detail = "启动失败：${e.message ?: e.javaClass.simpleName}"
            writeDiagnostics(logFile, detail)
            _state.update { it.copy(phase = ServerPhase.ERROR, message = detail) }
            return
        }
        startedAt = System.currentTimeMillis()
        _state.update {
            it.copy(phase = ServerPhase.STARTING, pid = processPid(serverProcess), message = "正在启动服务…")
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

    private fun processPid(p: Process?): Long? =
        p?.let { proc ->
            runCatching { proc.javaClass.getMethod("pid").invoke(proc) as Long }.getOrNull()
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
            code in 200..399
        } catch (_: Exception) {
            false
        }
    }
}
