package com.siliconleap.app.runtime

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
    val engine: String = AppSettings.SUBSYSTEM_ENGINE_AUTO,
    val umlRunning: Boolean = false,
)

/** Debian/Ubuntu 子系统元数据（debian-subsystem / ubuntu-subsystem release 提供）。 */
data class SubsystemMeta(
    val version: String,
    val flavor: String,
    val rootfsUrl: String,
    val rootfsSha256: String,
    val rootfsSizeBytes: Long,
)

/**
 * 子系统管理（Debian / Ubuntu）：在线下载 rootfs、解压安装、卸载。
 * proot 二进制由 APK 内置（nativeLibraryDir，SELinux 允许执行）；
 * 实际 shell 命令由 DSH 服务经 proot 包裹执行（每次调用冷启动，无常驻进程）。
 */
object SubsystemManager {
    private const val DEBIAN_META_URL =
        "https://github.com/RochelimitDawn/DSHM/releases/download/debian-subsystem/metadata.json"

    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(SubsystemState())
    val state: StateFlow<SubsystemState> = _state.asStateFlow()

    fun attach(context: Context) {
        if (!::appContext.isInitialized) {
            appContext = context.applicationContext
            // 安装包残留清扫：子系统已就绪时，上次进程中途被杀/校验失败留下的 tar 无保留价值
            if (isInstalled(appContext)) {
                runCatching { File(TermuxEnv.filesDir(appContext), "subsystem-rootfs.tar.gz").delete() }
            }
        }
    }

    // ------------------------------------------------------------- 路径

    fun subsystemDir(context: Context): File = File(TermuxEnv.filesDir(context), "subsystem")

    fun rootfsDir(context: Context): File = File(subsystemDir(context), "rootfs")

    /** proot 二进制（APK 内置，nativeLibraryDir 为 SELinux 允许执行区）。 */
    fun prootBin(context: Context): File = File(TermuxEnv.nativeLibDir(context), "libproot.so")

    /**
     * tawcroot 二进制（APK 内置，systrap 方案 proot 替代；vendor 源码见
     * runtime-builder/vendor/tawcroot）。seccomp RET_TRAP + SIGSYS in-process
     * handler，单进程无 ptrace，路径 syscall 比 proot 快 2.5-7.5 倍。
     * 旧 APK 无此文件，缺失时回退 proot。
     */
    fun tawcrootBin(context: Context): File = File(TermuxEnv.nativeLibDir(context), "libtawcroot.so")

    /** tawcroot 是否可用（新 APK 内置；旧 APK 缺失时回退 proot）。 */
    fun tawcrootAvailable(context: Context): Boolean = tawcrootBin(context).exists()

    fun resolvConf(context: Context): File = File(subsystemDir(context), "resolv.conf")

    private fun subsystemLog(context: Context): File = File(TermuxEnv.logs(context), "subsystem.log")

    fun isInstalled(context: Context): Boolean =
        File(rootfsDir(context), "etc").isDirectory && File(rootfsDir(context), "bin/bash").exists()

    fun subsystemSize(context: Context): Long = runCatching {
        subsystemDir(context).walkTopDown().filter { it.isFile }.map { it.length() }.sum()
    }.getOrDefault(0L)

    fun metaUrl(context: Context): String {
        val base = DEBIAN_META_URL
        return when (SourceManager.resolve(context)) {
            AppSettings.SOURCE_GHPROXY_CF -> "https://v6.gh-proxy.org/$base"

            AppSettings.SOURCE_GHPROXY_AXISNOW -> "https://axisnow.gh-proxy.org/$base"

            AppSettings.SOURCE_CUSTOM -> AppSettings.customMetaUrl(context).ifBlank { base }

            else -> base
        }
    }

    fun tailLog(context: Context, lines: Int = 80): String {
        val f = subsystemLog(context)
        return if (f.exists()) LogStore.named(f).tail(lines) else "(暂无日志)"
    }

    // ------------------------------------------------------------- 安装/卸载

    /**
     * 安装子系统（仅 Debian）。已安装时先清空再装（重装）。
     */
    fun installSubsystem(context: Context) {
        val p = _state.value.phase
        if (p == SubsystemPhase.DOWNLOADING || p == SubsystemPhase.EXTRACTING) return
        scope.launch {
            runCatching {
                // 重装只清 Debian 目录树：subsystemDir.deleteRecursively() 会连 UML
                // 镜像（rootfs.ext4）与 share/（订阅/心跳通道）一起删掉，两条安装流
                // 互相打架（Debian 重装清 UML 镜像、UML 安装翻引擎）
                if (isInstalled(context)) rootfsDir(context).deleteRecursively()
            }
            downloadAndInstall()
        }
    }

    /**
     * 挂起等待子系统安装完成（容器分区首次自动安装用）。
     * 已在安装中则直接返回（由发起方持有状态更新）；已安装时由调用方判断。
     */
    suspend fun installAndWait() {
        val p = _state.value.phase
        if (p == SubsystemPhase.DOWNLOADING || p == SubsystemPhase.EXTRACTING) return
        downloadAndInstall()
    }

    /** 复位子系统状态（自动安装前调用，清除上次会话残留的进行中标记）。 */
    fun resetForAutoInstall() {
        if (_state.value.phase == SubsystemPhase.DOWNLOADING || _state.value.phase == SubsystemPhase.EXTRACTING) {
            _state.update { it.copy(phase = SubsystemPhase.NOT_INSTALLED) }
        }
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
        // 子系统下载/安装期间保活：运行时已就绪时启动前台服务（退出应用下载不断；
        // 运行时未装时启动服务会触发 startServer 自检失败，跳过）
        if (RuntimeManager.isRuntimeInstalled()) HarnessService.start(appContext)
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
        appendLog("> 开始下载 ${meta.flavor} 子系统（${meta.version}）…")
        val ok = downloadWithFallback(meta.rootfsUrl, tar, meta.rootfsSizeBytes)
        if (!ok) {
            appendLog("! 子系统下载失败，请检查网络或切换下载源")
            _state.update { it.copy(phase = SubsystemPhase.ERROR, message = "子系统下载失败，请检查网络或切换下载源") }
            return
        }
        appendLog("> 下载完成（${tar.length() / 1024 / 1024} MB），校验 sha256…")
        if (!verifySha256(tar, meta.rootfsSha256)) {
            appendLog("! 子系统校验失败（sha256 不匹配）")
            runCatching { tar.delete() }
            _state.update { it.copy(phase = SubsystemPhase.ERROR, message = "子系统校验失败（sha256 不匹配）") }
            return
        }
        appendLog("> sha256 校验通过，开始安装…")
        _state.update { it.copy(phase = SubsystemPhase.EXTRACTING, progress = 0f, message = "正在安装子系统…") }
        // 切换发行版（Debian ↔ Ubuntu）必须先清空旧 rootfs 再解压：tar 合并覆盖会
        // 残留旧发行版文件（etc/debian_version），readDistroVersion 优先读它导致
        // Ubuntu 显示为 Debian；旧工具链（/opt/node 等）也随新 rootfs 重新引导
        val rootfs = rootfsDir(appContext)
        if (rootfs.exists()) {
            appendLog("> 检测到旧子系统（${runDistroLabel()}），清空后安装新发行版…")
            rootfs.deleteRecursively()
        }
        val installed = extractTarGz(tar, rootfs)
        tar.delete()
        if (!installed) {
            appendLog("! 子系统安装失败")
            _state.update { it.copy(phase = SubsystemPhase.ERROR, message = "子系统安装失败") }
            return
        }
        writeResolvConf()
        appendLog("> 子系统安装完成（${meta.flavor} ${readDistroVersion()}）")
        _state.update {
            it.copy(
                phase = SubsystemPhase.READY,
                version = meta.version,
                progress = 1f,
                speedBytesPerSec = 0L,
                message = "子系统已就绪",
            )
        }
        // DSH_SUBSYSTEM_ARGV 是服务启动时的静态快照——装完后必须重启服务重算
        appendLog("> 重启服务使子系统路由生效…")
        com.siliconleap.app.runtime.RuntimeManager.stopServer(auto = false)
        com.siliconleap.app.runtime.RuntimeManager.startServerIfNeeded()
        // 预启动补跑（bootstrap 早已过去，镜像后装的场景 UML 不会预启动）
        maybePreboot(appContext)
        // rootfs 工具链预装（node/python3/git/rg）：AI 会话工具开箱即用，
        // 后台执行不阻塞（apt 源慢时允许会话先行使用）
        scope.launch(Dispatchers.IO) {
            runCatching { AddonManager.ensureRootfsTools() }
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
                version = json.optString("version", "unknown"),
                flavor = json.optString("flavor", "debian"),
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
        // 镜像链固定全量尝试：ghproxy CF/AxisNow + 直连（用户当前源只决定第一个候选，
        // github 直连国内不可达时镜像兜底）
        val candidates = mutableListOf<String>()
        val prefix = when (SourceManager.resolve(appContext)) {
            AppSettings.SOURCE_GHPROXY_CF -> "https://v6.gh-proxy.org/"
            AppSettings.SOURCE_GHPROXY_AXISNOW -> "https://axisnow.gh-proxy.org/"
            else -> ""
        }
        if (prefix.isNotEmpty() && url.startsWith("https://github.com/")) candidates.add(prefix + url)
        if (url.startsWith("https://github.com/")) {
            candidates.add("https://v6.gh-proxy.org/$url")
            candidates.add("https://axisnow.gh-proxy.org/$url")
        }
        candidates.add(url)
        for (c in candidates.distinct()) {
            if (downloadSingle(c, target, sizeBytes)) return true
        }
        return false
    }

    private suspend fun downloadSingle(url: String, target: File, sizeBytes: Long): Boolean =
        withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            var input: InputStream? = null
            var out: OutputStream? = null
            var ok = false
            try {
                conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.instanceFollowRedirects = true
                // 断点续传：上次中断留下的 .part 存在且 HTTP 支持时从断点继续
                val part = File(target.parentFile, target.name + ".part")
                var resumed = 0L
                if (part.exists() && part.length() > 0) {
                    resumed = part.length()
                    conn.setRequestProperty("Range", "bytes=$resumed-")
                }
                val code = conn.responseCode
                if (resumed > 0 && code == 206) {
                    // 服务器支持续传：从 .part 追加
                } else if (code in 200..299) {
                    // 服务器不支持 Range（200）或无 .part：全量重下
                    resumed = 0L
                } else {
                    return@withContext false
                }
                val contentLength = if (sizeBytes > 0) sizeBytes - resumed else conn.contentLengthLong
                val totalCount = if (sizeBytes > 0) sizeBytes else resumed + (conn.contentLengthLong.takeIf { it > 0 } ?: 0)
                target.parentFile?.mkdirs()
                out = BufferedOutputStream(FileOutputStream(part, resumed > 0))
                input = conn.inputStream
                val buf = ByteArray(256 * 1024)
                var total = resumed
                var lastUpdate = total
                var speedBps = 0L
                var lastSpeedAt = System.currentTimeMillis()
                var lastSpeedTotal = total
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
                    if (total - lastUpdate > 512 * 1024 || (totalCount > 0 && total >= totalCount)) {
                        lastUpdate = total
                        if (totalCount > 0) {
                            val pct = (total.toDouble() / totalCount).coerceIn(0.0, 1.0)
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
                    if (totalCount > 0 && total > totalCount) {
                        return@withContext false
                    }
                }
                if (totalCount > 0 && total != totalCount) {
                    // 中断：保留 .part 供下次续传
                    return@withContext false
                }
                runCatching { out?.flush() }
                if (!part.renameTo(target)) {
                    part.copyTo(target, overwrite = true)
                    part.delete()
                }
                ok = true
                true
            } catch (_: Exception) {
                false
            } finally {
                runCatching { input?.close() }
                runCatching { out?.close() }
                runCatching { conn?.disconnect() }
                // 失败时保留 .part（断点续传），全量失败且服务器不支持 Range 时清空重来
                if (!ok) {
                    val part = File(target.parentFile, target.name + ".part")
                    if (!part.exists() || part.length() == 0L) part.delete()
                }
            }
        }

    // ------------------------------------------------------------- 校验/空间

    private fun verifySha256(file: File, expected: String): Boolean {
        if (expected.isBlank()) return true
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            BufferedInputStream(file.inputStream(), 256 * 1024).use { input ->
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

    /**
     * resolv.conf 直写 rootfs（不 bind）：tawcroot 的 file-over-file bind
     * （外部文件 → /etc/resolv.conf）在 path_add_bind 失败（host 复现），
     * bind-skip 后 guest 用 rootfs 自带的无效 nameserver → DNS 全断。
     * 直写 rootfs/etc/resolv.conf 无此问题。
     */
    internal fun writeResolvConf() {
        runCatching {
            val f = File(rootfsDir(appContext), "etc/resolv.conf")
            f.parentFile?.mkdirs()
            // 国内 DNS（阿里/腾讯）：8.8.8.8/1.1.1.1 在国内网络 UDP 53 通常被墙/劫持，
            // guest DNS 全断 → pnpm（registry 解析）等全部失败
            f.writeText("nameserver 223.5.5.5\nnameserver 119.29.29.29\n")
        }
    }

    /** 旧 rootfs 的发行版标签（切换日志用；未安装返回 unknown）。 */
    private fun runDistroLabel(): String = runCatching {
        val rootfs = rootfsDir(appContext)
        when {
            File(rootfs, "etc/debian_version").exists() -> "Debian"
            File(rootfs, "etc/os-release").exists() -> "Ubuntu"
            else -> "unknown"
        }
    }.getOrDefault("unknown")

    /** 读取 rootfs 发行版版本号：优先 debian_version（Debian），否则 os-release（Ubuntu 等）。 */
    private fun readDistroVersion(): String = runCatching {
        val debian = File(rootfsDir(appContext), "etc/debian_version")
        if (debian.exists()) return@runCatching debian.readText().trim()
        File(rootfsDir(appContext), "etc/os-release").readLines()
            .firstOrNull { it.startsWith("VERSION_ID=") }
            ?.substringAfter('=')?.trim('"') ?: ""
    }.getOrDefault("")

    // ------------------------------------------------------------- tar.gz 解压

    private fun extractTarGz(file: File, dest: File): Boolean = runCatching {
        val tmp = File(dest.parentFile, "subsys.tmp")
        tmp.deleteRecursively()
        tmp.mkdirs()
        // 并发提取：目录/链接顺序内联（依赖顺序），普通文件写入派发 IO 池并行
        val writeScope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO + SupervisorJob())
        val pending = mutableListOf<kotlinx.coroutines.Deferred<Boolean>>()
        GZIPInputStream(BufferedInputStream(file.inputStream(), 256 * 1024)).use { input ->
            val header = ByteArray(512)
            var pendingName: String? = null
            var pendingLink: String? = null
            while (true) {
                val n = readFully(input, header, 0, 512)
                if (n <= 0) break
                if (header.all { it == 0.toByte() }) break
                val type = header[156].toInt().toChar()
                val size = parseOctal(header, 124, 12)
                val mode = parseOctal(header, 100, 8)
                when (type) {
                    'L' -> { pendingName = readTarBlock(input, size); continue }
                    'K' -> { pendingLink = readTarBlock(input, size); continue }
                    'x' -> { val pax = readTarBlock(input, size); pendingName = parsePaxPath(pax) ?: pendingName; continue }
                    'g' -> { skipTarData(input, size); continue }
                }
                var name = parseTarName(header)
                if (pendingName != null) { name = pendingName!!; pendingName = null }
                var link = parseTarLink(header)
                if (pendingLink != null) { link = pendingLink!!; pendingLink = null }
                val target = safeResolve(tmp, name)
                if (target == null) { skipTarData(input, size); skipPadding(input, size); continue }
                when (type) {
                    '5' -> {
                        target.mkdirs()
                        chmodBestEffort(target, mode)
                    }
                    '0', '\u0000' -> {
                        val dest2 = target
                        target.parentFile?.mkdirs()
                        // 保留 tar 中的权限位（尤其是执行位），否则 proot 无法 exec 二进制
                        val m = mode
                        if (size in 1 until 8 * 1024 * 1024) {
                            // 小文件：同步读入内存（避免与主循环竞争输入流），写入派发 IO 池并行
                            val data = ByteArray(size.toInt())
                            readFully(input, data, 0, size.toInt())
                            pending.add(writeScope.async {
                                val ok = runCatching {
                                    BufferedOutputStream(FileOutputStream(dest2), 256 * 1024).use { out ->
                                        out.write(data)
                                    }
                                    true
                                }.getOrDefault(false)
                                if (ok) chmodBestEffort(dest2, m)
                                ok
                            })
                        } else {
                            // 大文件：同步写（避免内存放大）
                            copyTarFile(input, target, size)
                            chmodBestEffort(target, m)
                        }
                    }
                    '2' -> {
                        target.parentFile?.mkdirs()
                        if (link != null) {
                            // 已存在（残留/先前的文件条目）先删，否则 createSymbolicLink
                            // 抛 FileAlreadyExistsException 被吞，符号链接静默缺失
                            if (target.exists() || java.nio.file.Files.isSymbolicLink(target.toPath())) {
                                runCatching { target.delete() }
                            }
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
        runBlocking {
            // 全部文件写入完成才算成功；并发上限由 IO 池自然限流
            pending.forEach { if (!it.await()) throw IllegalStateException("tar file extract failed") }
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

    /** pax 扩展头中提取 path= 长文件名。 */
    private fun parsePaxPath(data: String): String? {
        for (line in data.split("\n")) {
            val s = line.trim()
            if (s.startsWith("path=")) return s.substring(5)
        }
        return null
    }

    /** 应用 tar 权限位（mode & 0777）。Android 上 Os.chmod 可完整设置 r/w/x。 */
    private fun chmodBestEffort(f: File, mode: Long) {
        runCatching { android.system.Os.chmod(f.absolutePath, (mode and 0x1FF).toInt()) }
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

    private fun copyTarFile(input: InputStream, target: File, size: Long) {
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
        // [子系统] 标签：安装日志镜像到 server.log 时与服务输出（dsh web 就绪等）
        // 交错，标签区分两条流；Debian/UML 安装、UML 引擎日志都经此处
        val tagged = "[子系统] $line"
        LogStore.named(subsystemLog(appContext)).append(line)
        // 镜像到 server.log：BootScreen 的终端框只读 server.log，安装时能实时看到进度
        LogStore.named(TermuxEnv.serverLog(appContext)).append(tagged)
        // 即时落盘：LogStore 攒 32 行才 flush，低频日志永远留在内存文件 0 字节——
        // AI 会话读 /root/dsh/logs/* 诊断时必须可见
        LogStore.named(subsystemLog(appContext)).flushForExit()
        LogStore.named(TermuxEnv.serverLog(appContext)).flushForExit()
    }

    private fun clearLog() {
        LogStore.named(subsystemLog(appContext)).clear()
        // 不清 server.log：它由 RuntimeManager 持有并含运行时下载日志，追加即可
    }

    // ------------------------------------------------------------- UML 引擎
    // linux-um-arm64（ARCH=um SUBARCH=arm64，bionic 静态内核）+ umnetx 零特权网络栈。
    // 内核/stub/umnetx/umarm-cmd 由 APK jniLibs 携带（nativeLibraryDir 为唯一可执行区），
    // ext4 rootfs 运行时在线下载（仅被内核映射，不受 noexec 限制）。

    /** UML 内核（APK jniLibs）。 */
    fun umlBin(context: Context): File = File(TermuxEnv.nativeLibDir(context), "liblinux.so")

    /** UML syscall stub（stub_exe= 需绝对路径）。 */
    fun stubBin(context: Context): File = File(TermuxEnv.nativeLibDir(context), "libumarm-stub.so")

    /** umnetx 用户态网络栈（jniLibs）。 */
    fun umnetxBin(context: Context): File = File(TermuxEnv.nativeLibDir(context), "libumnetx.so")

    /** umarm-cmd host 侧 wrapper（jniLibs 脚本，DSH bash argv 前缀）。 */
    fun umarmCmdBin(context: Context): File = File(TermuxEnv.nativeLibDir(context), "libumarm-cmd.so")

    /** 混合调度器 wrapper（jniLibs 脚本，DSH bash argv 前缀）。 */
    fun dispatchBin(context: Context): File = File(TermuxEnv.nativeLibDir(context), "libdsh-dispatch.so")

    /** UML ext4 rootfs 镜像（运行时下载）。 */
    fun rootfsImg(context: Context): File = File(subsystemDir(context), "rootfs.ext4")

    /** hostfs 共享目录（req/res 命令通道文件协议）。 */
    fun shareDir(context: Context): File = File(subsystemDir(context), "share")

    /** bess 通道双 socket：内核 bind 自己端（src），connect umnetx（dst）。 */
    fun umlSocket(context: Context): File = File(TermuxEnv.tmp(context), "uml.sock")

    fun netSocket(context: Context): File = File(TermuxEnv.tmp(context), "net.sock")

    private fun umlLog(context: Context): File = File(TermuxEnv.logs(context), "uml.log")

    /** UML 引擎运行条件：内核、stub、umnetx、wrapper 均在 jniLibs 就绪。 */
    fun umlAvailable(context: Context): Boolean =
        umlBin(context).exists() && stubBin(context).exists() &&
            umnetxBin(context).exists() && umarmCmdBin(context).exists()

    /** UML 子系统是否已安装（ext4 镜像就绪）。 */
    fun isUmlInstalled(context: Context): Boolean = rootfsImg(context).isFile

    /** 当前引擎是否解析为 UML（设置 uml/hybrid/auto + 内核就绪）；hybrid 走按命令调度，UML_READY 是其前提。 */
    fun isUmlEngine(context: Context): Boolean =
        AppSettings.subsystemEngine(context) == AppSettings.SUBSYSTEM_ENGINE_UML ||
            AppSettings.subsystemEngine(context) == AppSettings.SUBSYSTEM_ENGINE_HYBRID ||
            AppSettings.subsystemEngine(context) == AppSettings.SUBSYSTEM_ENGINE_AUTO

    fun umlRunning(context: Context): Boolean {
        val p = umlProcess ?: return false
        return p.isAlive
    }

    /** 进程真实存活探测（看门狗僵死同步用：状态标记运行中但进程已死时为 false）。 */
    fun isUmlProcessAlive(context: Context): Boolean = umlProcess?.isAlive ?: false

    /** 僵死同步：状态标记运行中但进程已死，修正状态并清理（看门狗调用）。 */
    fun syncUmlStopped(context: Context) {
        val ctx = context.applicationContext
        runCatching { umlProcess?.destroy() }
        umlProcess = null
        runCatching { umnetxProcess?.destroy() }
        umnetxProcess = null
        umlSocket(ctx).delete()
        netSocket(ctx).delete()
        _state.update { it.copy(umlRunning = false) }
        LogStore.named(umlLog(ctx)).append("> UML 进程已死（panic/doze），状态已同步")
        idleJob?.cancel()
    }

    fun tailUmlLog(context: Context, lines: Int = 80): String {
        val f = umlLog(context)
        return if (f.exists()) LogStore.named(f).tail(lines) else "(暂无日志)"
    }

    // UML 运行句柄（仅本进程持有；进程被系统回收时内核以孤儿进程退出，下次启动清扫 socket）
    private var umlProcess: Process? = null
    private var umnetxProcess: Process? = null

    /**
     * 启动 UML 引擎：umnetx 先行（listen），内核再 connect（顺序不可反）。
     * 已运行时直接返回；rootfs 镜像缺失返回 false。
     */
    fun startUml(context: Context): Boolean = try {
        if (umlRunning(context)) return true
        if (!umlAvailable(context) || !isUmlInstalled(context)) return false
        val ctx = context.applicationContext
        val share = shareDir(ctx).apply { mkdirs() }
        val tmp = TermuxEnv.tmp(ctx).apply { mkdirs() }
        // socket 残留清扫（bind err=98）
        umlSocket(ctx).delete()
        netSocket(ctx).delete()
        val log = LogStore.named(umlLog(ctx))
        // slog 即时落盘：LogStore 攒 32 行才 flush，关键启动行只有 1-2 行会永远留在
        // 内存缓冲，文件读出来是 0 字节——排障时日志全空
        fun slog(line: String) {
            log.append(line)
            log.flushForExit()
        }
        slog("> 启动 UML 引擎…")
        // 重置空闲时钟：dispatch.last 可能是陈旧时间戳（上次 UML 使用是很久以前），
        // 空闲循环 60s 后第一次检查就误判「已空闲很久」→ 预启动 1 分钟即被回收
        runCatching {
            share.mkdirs()
            dispatchStamp(ctx).writeText((System.currentTimeMillis() / 1000).toString())
        }

        umnetxProcess = try {
            ProcessBuilder(umnetxBin(ctx).absolutePath, "--listen", netSocket(ctx).absolutePath)
                .directory(tmp)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log.file))
                .start()
        } catch (e: Exception) {
            slog("! umnetx 启动失败: ${e.javaClass.simpleName}: ${e.message}")
            return false
        }
        // umnetx 需先完成 listen，内核 connect 才能成功
        Thread.sleep(500)

        val argv = listOf(
            umlBin(ctx).absolutePath,
            umlMemArg(ctx),
            "ubd0=${rootfsImg(ctx).absolutePath}",
            "root=/dev/ubda",
            "rw",
            "init=/umarm-init",
            "umarm.share=${share.absolutePath}",
            "stub_exe=${stubBin(ctx).absolutePath}",
            "vec0:transport=bess,src=${umlSocket(ctx).absolutePath},dst=${netSocket(ctx).absolutePath},mac=02:00:00:00:00:01",
            "con=null",
            "con0=null,fd:1",
            "panic=0",
            // quiet 已移除：内核立即退出（exit=159=SIGSYS）时零输出无法排障，
            // 早期启动消息落 uml.log 才能看到死因
        )
        umlProcess = try {
            ProcessBuilder(argv)
                .directory(tmp)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log.file))
                .start()
        } catch (e: Exception) {
            slog("! UML 内核启动失败: ${e.javaClass.simpleName}: ${e.message}")
            runCatching { umnetxProcess?.destroy() }
            umnetxProcess = null
            return false
        }
        // 内核瞬时退出探测：exec 失败/段错误会在 1s 内死掉且无输出
        Thread.sleep(1000)
        if (umlProcess?.isAlive != true) {
            slog("! UML 内核启动后立即退出（exit=${umlProcess?.exitValue() ?: "?"}），详见上方内核输出")
            runCatching { umnetxProcess?.destroy() }
            umnetxProcess = null
            umlProcess = null
            return false
        }
        slog("> UML 内核已启动（1s 存活探测通过）")
        _state.update { it.copy(umlRunning = true) }
        startHeartbeat(ctx)
        true
    } catch (e: Exception) {
        LogStore.named(umlLog(context)).let { l ->
            l.append("! UML 启动异常: ${e.javaClass.simpleName}: ${e.message}")
            l.flushForExit()
        }
        false
    }

    /**
     * 心跳（uml.running 时间戳）：dispatch wrapper 的 uml_usable 以其新鲜度为准
     * （DSH_DISPATCH_UML_READY 是服务启动时算的，运行期回收/僵死会变陈旧）。
     */
    private fun startHeartbeat(context: Context) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            val f = File(shareDir(context), "uml.running")
            while (umlProcess?.isAlive == true) {
                runCatching {
                    f.parentFile?.mkdirs()
                    f.writeText("${System.currentTimeMillis() / 1000}\n")
                }
                delay(15_000)
            }
            runCatching { f.delete() }
        }
    }

    /** 停止 UML 引擎：先发 poweroff 标记优雅关机（避免 ext4 写入中强杀损坏），进程终止兜底。 */
    fun stopUml(context: Context) {
        val ctx = context.applicationContext
        // 优雅关机：写标记，guest umarm-daemon 收到后 poweroff -f，最多等 10s
        if (umlProcess?.isAlive == true) {
            runCatching {
                val f = File(shareDir(ctx), "poweroff")
                f.parentFile?.mkdirs()
                f.writeText("1")
            }
            var waited = 0
            while (umlProcess?.isAlive == true && waited < 10_000) {
                Thread.sleep(500)
                waited += 500
            }
        }
        heartbeatJob?.cancel()
        runCatching { umlProcess?.destroy() }
        umlProcess = null
        runCatching { umnetxProcess?.destroy() }
        umnetxProcess = null
        umlSocket(ctx).delete()
        netSocket(ctx).delete()
        File(shareDir(ctx), "poweroff").delete()
        _state.update { it.copy(umlRunning = false) }
        LogStore.named(umlLog(ctx)).append("> UML 引擎已停止")
    }

    // ------------------------------------------------------------- UML 预启动 + 空闲回收（混合调度）

    private var idleJob: Job? = null
    private var heartbeatJob: Job? = null

    /** 粘性标记（调度器 wrapper touch，跨进程使用时间数据源）。 */
    private fun dispatchStamp(context: Context): File = File(shareDir(context), "dispatch.last")

    /** UML 空闲秒数（自最后一次调度器使用起算；无标记视为已空闲很久）。 */
    fun umlIdleSeconds(context: Context): Long = runCatching {
        val t = dispatchStamp(context).readText().trim().toLongOrNull() ?: return@runCatching Long.MAX_VALUE
        System.currentTimeMillis() / 1000 - t
    }.getOrDefault(Long.MAX_VALUE)

    /** 设备可用内存（ActivityManager 真实值，MB）。 */
    fun availableMemMB(context: Context): Long = runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager

        val mi = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        mi.availMem / 1024 / 1024
    }.getOrDefault(0L)

    /** 设备总内存（真实值，MB），预启动决策日志用。 */
    fun totalMemMB(context: Context): Long = runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        mi.totalMem / 1024 / 1024
    }.getOrDefault(0L)

    /**
     * UML 内存参数自适应：可用内存 < 2GB 时 256M（内存吃紧设备），否则 512M。
     * 预分配是虚拟地址空间，RSS 按需增长，但小参数降低内核页表/缓存开销。
     */
    fun umlMemArg(context: Context): String =
        if (availableMemMB(context) < 2 * 1024) "mem=256M" else "mem=512M"

    /**
     * UML 预启动内存门槛：UML mem 参数 + 64MB 余量。固定 1.5GB 过保守——
     * mem=256M 的 UML RSS 按需增长（ext4 rootfs 映射是可回收 page cache），
     * 溢出由 zram swap（12GB）吸收，空闲回收释放。
     */
    private fun umlPrebootThresholdMB(context: Context): Long =
        if (availableMemMB(context) < 2 * 1024) 320L else 576L

    /**
     * 预启动 UML（混合调度/hybrid 与 uml 引擎，服务启动时后台调用）：
     * 消除首条重载命令的内核冷启动；可用内存低于门槛时跳过（按命令降级 proot），
     * 决策带真实读数（total/avail/门槛）写入 uml.log，设备内存占用可核查；
     * 失败静默（降级 proot）。
     */
    fun maybePreboot(context: Context) {
        // UML 已停用：Android 16 应用 seccomp 杀死 UML 内核需要的系统调用
        // （exit=159=SIGSYS，应用侧无法绕过）。全部负载走 proot。
        LogStore.named(umlLog(context.applicationContext)).append("> UML 预启动已停用（宿主 seccomp 兼容性），负载走 proot")
    }


    /** 空闲回收：每 60s 检查，UML 空闲超过阈值且运行中则停止（释放内存）。 */
    fun scheduleIdleRecycle(context: Context) {
        idleJob?.cancel()
        idleJob = scope.launch {
            val ctx = context.applicationContext
            val thresholdMin = AppSettings.umlIdleMinutes(ctx)
            while (umlRunning(ctx)) {
                delay(60_000)
                if (thresholdMin <= 0) return@launch
                if (umlIdleSeconds(ctx) >= thresholdMin * 60) {
                    LogStore.named(umlLog(ctx)).append("> UML 空闲超过 ${thresholdMin} 分钟，自动回收")
                    stopUml(ctx)
                    return@launch
                }
            }
        }
    }

    /** 引擎切换/卸载时取消空闲回收与心跳。 */
    fun cancelIdleRecycle() {
        idleJob?.cancel()
        idleJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        runCatching { File(shareDir(appContext), "uml.running").delete() }
    }

}
