package com.siliconleap.app.runtime

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
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

enum class ServerPhase {
    NOT_READY,
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
)

object RuntimeManager {
    private const val READY_TIMEOUT_MS = 120_000L

    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(RuntimeState())
    val state: StateFlow<RuntimeState> = _state.asStateFlow()

    private var serverProcess: Process? = null
    private var startedAt: Long = 0L

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

    fun bootstrap() {
        if (_state.value.phase == ServerPhase.RUNNING) return
        scope.launch {
            try {
                if (!TermuxEnv.isRuntimeReady(appContext)) {
                    _state.update {
                        it.copy(phase = ServerPhase.EXTRACTING, progress = 0f, message = "正在解压运行时环境…")
                    }
                    val ok = extractRuntime()
                    if (!ok) return@launch
                }
                _state.update { it.copy(phase = ServerPhase.STARTING, progress = 1f, message = "正在启动服务…") }
                val started = startServer()
                if (started) {
                    waitForReady()
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(phase = ServerPhase.ERROR, message = "启动失败：${e.message ?: e.javaClass.simpleName}")
                }
            }
        }
    }

    fun restart() {
        stopServer()
        _state.update { it.copy(phase = ServerPhase.NOT_READY) }
        bootstrap()
    }

    /** 删除运行时并重新解压（用于修复损坏/权限异常）。 */
    fun rebuildRuntime() {
        stopServer()
        TermuxEnv.prefix(appContext).deleteRecursively()
        _state.update { it.copy(phase = ServerPhase.NOT_READY) }
        bootstrap()
    }

    /** 清空会话与设置数据（dsh-home），保留运行时与工作区文件。 */
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

    // ------------------------------------------------------------------ 解压

    private fun extractRuntime(): Boolean {
        val dest = TermuxEnv.prefix(appContext)
        val tmp = File(dest.parentFile, "usr.tmp")
        return try {
            tmp.deleteRecursively()
            tmp.mkdirs()

            val count = countEntries()
            if (count <= 0) {
                _state.update { it.copy(phase = ServerPhase.ERROR, message = "运行时资源缺失（assets/runtime.zip）") }
                return false
            }

            val input = ZipInputStream(appContext.assets.open(TermuxEnv.RUNTIME_ASSET))
            var done = 0L
            var entry = input.nextEntry
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
                if (done % 200L == 0L) {
                    _state.update { it.copy(progress = done.toFloat() / count) }
                }
            }
            input.close()

            // 兼容旧版 runtime.zip：若 zip 根目录含 usr/（嵌套），把内容提升一层
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
            _state.update { it.copy(progress = 1f) }
            true
        } catch (e: Exception) {
            _state.update {
                it.copy(phase = ServerPhase.ERROR, message = "解压失败：${e.message ?: e.javaClass.simpleName}")
            }
            false
        }
    }

    private fun countEntries(): Long {
        val input = ZipInputStream(appContext.assets.open(TermuxEnv.RUNTIME_ASSET))
        var count = 0L
        while (input.nextEntry != null) count++
        input.close()
        return count
    }

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

    // ------------------------------------------------------------------ 启动

    private fun startServer(): Boolean {
        val ctx = appContext
        TermuxEnv.home(ctx).mkdirs()
        TermuxEnv.tmp(ctx).mkdirs()
        TermuxEnv.dshHome(ctx).mkdirs()
        TermuxEnv.workspace(ctx).mkdirs()
        TermuxEnv.logs(ctx).mkdirs()

        val port = _state.value.port
        val node = TermuxEnv.nodeBin(ctx)
        val entry = TermuxEnv.dshEntry(ctx)
        val logFile = TermuxEnv.serverLog(ctx)

        val diag = preflight(node, entry)
        if (diag != null) {
            writeDiagnostics(logFile, diag)
            _state.update { it.copy(phase = ServerPhase.ERROR, message = "启动自检失败\n\n$diag") }
            return false
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
            val detail = "启动失败：${e.message ?: e.javaClass.simpleName}\n\n${preflight(node, entry) ?: ""}"
            writeDiagnostics(logFile, detail)
            _state.update { it.copy(phase = ServerPhase.ERROR, message = detail) }
            return false
        }
        startedAt = System.currentTimeMillis()
        _state.update { it.copy(pid = processPid(serverProcess)) }
        return true
    }

    /** 启动前自检，返回诊断文本；通过则返回 null。 */
    private fun preflight(node: File, entry: File): String? {
        val prefix = TermuxEnv.prefix(appContext).absolutePath
        val lines = mutableListOf<String>()
        lines += "prefix=$prefix"
        lines += "node=$node | 存在=${node.exists()} | 可执行=${node.canExecute()} | 大小=${runCatching { node.length() }.getOrNull()}"
        if (node.exists()) {
            val buf = ByteArray(4)
            val n = runCatching { node.inputStream().use { it.read(buf) } }.getOrNull() ?: -1
            val elf = n == 4 && buf[0] == 0x7f.toByte() && buf[1] == 'E'.code.toByte() && buf[2] == 'L'.code.toByte() && buf[3] == 'F'.code.toByte()
            lines += "ELF 魔数校验=$elf"
        }
        lines += "dsh=$entry | 存在=${entry.exists()}"
        lines += "libc++_shared.so=${File(prefix, "lib/libc++_shared.so").exists()}"
        lines += "logs=${TermuxEnv.serverLog(appContext).absolutePath}"
        val missing = lines.filter { it.contains("不存在") || it.contains("false") }
        if (missing.isNotEmpty()) return lines.joinToString("\n")
        return null
    }

    /** 把诊断写入日志文件，保证失败时日志区可读。 */
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
                _state.update {
                    it.copy(phase = ServerPhase.RUNNING, message = "服务运行中")
                }
                return
            }
            delay(500)
        }
        _state.update {
            it.copy(
                phase = ServerPhase.ERROR,
                message = "服务启动超时，请查看日志\n\n${tailLog(40)}",
            )
        }
    }

    private fun ping(port: Int): Boolean {
        return try {
            val conn = URI("http://127.0.0.1:$port/").toURL().openConnection() as HttpURLConnection
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
