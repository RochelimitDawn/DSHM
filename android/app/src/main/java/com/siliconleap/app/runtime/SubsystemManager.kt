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
    private const val UBUNTU_META_URL =
        "https://github.com/RochelimitDawn/DSHM/releases/download/ubuntu-subsystem/metadata.json"

    /** 按子系统发行版返回默认 metadata 地址。 */
    fun defaultMetaUrl(flavor: String): String = when (flavor) {
        AppSettings.SUBSYSTEM_UBUNTU -> UBUNTU_META_URL
        else -> DEBIAN_META_URL
    }

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

    fun resolvConf(context: Context): File = File(subsystemDir(context), "resolv.conf")

    private fun subsystemLog(context: Context): File = File(TermuxEnv.logs(context), "subsystem.log")

    fun isInstalled(context: Context): Boolean =
        File(rootfsDir(context), "etc").isDirectory && File(rootfsDir(context), "bin/bash").exists()

    fun subsystemSize(context: Context): Long = runCatching {
        subsystemDir(context).walkTopDown().filter { it.isFile }.map { it.length() }.sum()
    }.getOrDefault(0L)

    fun metaUrl(context: Context): String {
        val base = defaultMetaUrl(AppSettings.subsystemFlavor(context))
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

    /** 安装子系统（默认当前选择的发行版）。 */
    fun installSubsystem(context: Context) {
        installSubsystem(context, AppSettings.subsystemFlavor(context))
    }

    /**
     * 安装指定发行版的子系统。若已安装其它发行版则先卸载再装（切换发行版）。
     * @param flavor debian / ubuntu
     */
    fun installSubsystem(context: Context, flavor: String) {
        val p = _state.value.phase
        if (p == SubsystemPhase.DOWNLOADING || p == SubsystemPhase.EXTRACTING) return
        if (isInstalled(context) && AppSettings.subsystemFlavor(context) != flavor) {
            appendLog("> 切换子系统发行版（${AppSettings.subsystemFlavor(context)} → $flavor），先卸载…")
            AppSettings.setSubsystemFlavor(context, flavor)
            scope.launch {
                runCatching { subsystemDir(context).deleteRecursively() }
                downloadAndInstall()
            }
        } else {
            AppSettings.setSubsystemFlavor(context, flavor)
            scope.launch { downloadAndInstall() }
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
                flavor = json.optString("flavor", AppSettings.subsystemFlavor(appContext)),
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
        val prefix = when (SourceManager.resolve(appContext)) {
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
                target.parentFile?.mkdirs()
                out = BufferedOutputStream(FileOutputStream(target))
                input = conn.inputStream
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
                    if (total - lastUpdate > 512 * 1024 || (contentLength > 0 && total >= contentLength)) {
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
                        return@withContext false
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

    private fun writeResolvConf() {
        runCatching {
            val f = resolvConf(appContext)
            f.parentFile?.mkdirs()
            f.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
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
                        target.parentFile?.mkdirs()
                        copyTarData(input, target, size)
                        // 保留 tar 中的权限位（尤其是执行位），否则 proot 无法 exec 二进制
                        chmodBestEffort(target, mode)
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
        LogStore.named(subsystemLog(appContext)).append(line)
        // 镜像到 server.log：BootScreen 的终端框只读 server.log，安装时能实时看到进度
        LogStore.named(TermuxEnv.serverLog(appContext)).append(line)
    }

    private fun clearLog() {
        LogStore.named(subsystemLog(appContext)).clear()
        // 不清 server.log：它由 RuntimeManager 持有并含运行时下载日志，追加即可
    }

    // ------------------------------------------------------------- UML 引擎
    // linux-um-arm64（ARCH=um SUBARCH=arm64，bionic 静态内核）+ umnetx 零特权网络栈。
    // 内核/stub/umnetx/umarm-cmd 由 APK jniLibs 携带（nativeLibraryDir 为唯一可执行区），
    // ext4 rootfs 运行时在线下载（仅被内核映射，不受 noexec 限制）。

    private const val UML_META_URL =
        "https://github.com/RochelimitDawn/DSHM/releases/download/uml-debian-subsystem/metadata.json"

    /** UML 内核（APK jniLibs）。 */
    fun umlBin(context: Context): File = File(TermuxEnv.nativeLibDir(context), "liblinux.so")

    /** UML syscall stub（stub_exe= 需绝对路径）。 */
    fun stubBin(context: Context): File = File(TermuxEnv.nativeLibDir(context), "libumarm-stub.so")

    /** umnetx 用户态网络栈（jniLibs）。 */
    fun umnetxBin(context: Context): File = File(TermuxEnv.nativeLibDir(context), "libumnetx.so")

    /** umarm-cmd host 侧 wrapper（jniLibs 脚本，DSH bash argv 前缀）。 */
    fun umarmCmdBin(context: Context): File = File(TermuxEnv.nativeLibDir(context), "libumarm-cmd.so")

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

    /** 当前引擎是否解析为 UML（设置 uml/auto + 内核就绪）。 */
    fun isUmlEngine(context: Context): Boolean =
        AppSettings.subsystemEngine(context) == AppSettings.SUBSYSTEM_ENGINE_UML ||
            AppSettings.subsystemEngine(context) == AppSettings.SUBSYSTEM_ENGINE_AUTO

    fun umlMetaUrl(context: Context): String {
        val base = UML_META_URL
        return when (SourceManager.resolve(context)) {
            AppSettings.SOURCE_GHPROXY_CF -> "https://v6.gh-proxy.org/$base"

            AppSettings.SOURCE_GHPROXY_AXISNOW -> "https://axisnow.gh-proxy.org/$base"

            AppSettings.SOURCE_CUSTOM -> AppSettings.customMetaUrl(context).ifBlank { base }

            else -> base
        }
    }

    fun umlRunning(context: Context): Boolean {
        val p = umlProcess ?: return false
        return p.isAlive
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
    fun startUml(context: Context): Boolean = runCatching {
        if (umlRunning(context)) return true
        if (!umlAvailable(context) || !isUmlInstalled(context)) return false
        val ctx = context.applicationContext
        val share = shareDir(ctx).apply { mkdirs() }
        val tmp = TermuxEnv.tmp(ctx).apply { mkdirs() }
        // socket 残留清扫（bind err=98）
        umlSocket(ctx).delete()
        netSocket(ctx).delete()
        val log = umlLog(ctx)
        LogStore.named(log).append("> 启动 UML 引擎…")

        umnetxProcess = ProcessBuilder(umnetxBin(ctx).absolutePath, "--listen", netSocket(ctx).absolutePath)
            .directory(tmp)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
            .start()
        // umnetx 需先完成 listen，内核 connect 才能成功
        Thread.sleep(500)

        val argv = listOf(
            umlBin(ctx).absolutePath,
            "mem=512M",
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
            "quiet",
        )
        umlProcess = ProcessBuilder(argv)
            .directory(tmp)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
            .start()
        LogStore.named(log).append("> UML 内核已启动")
        _state.update { it.copy(umlRunning = true) }
        true
    }.getOrDefault(false)

    /** 停止 UML 引擎：先终止内核，再终止 umnetx，清理 socket。 */
    fun stopUml(context: Context) {
        runCatching { umlProcess?.destroy() }
        umlProcess = null
        runCatching { umnetxProcess?.destroy() }
        umnetxProcess = null
        val ctx = context.applicationContext
        umlSocket(ctx).delete()
        netSocket(ctx).delete()
        _state.update { it.copy(umlRunning = false) }
        LogStore.named(umlLog(ctx)).append("> UML 引擎已停止")
    }

    /** 下载 UML ext4 rootfs 并校验安装（复用通用下载/校验/镜像源逻辑）。 */
    fun installUmlSubsystem(context: Context) {
        val p = _state.value.phase
        if (p == SubsystemPhase.DOWNLOADING || p == SubsystemPhase.EXTRACTING) return
        AppSettings.setSubsystemEngine(context, AppSettings.SUBSYSTEM_ENGINE_UML)
        scope.launch {
            _state.update {
                it.copy(phase = SubsystemPhase.DOWNLOADING, progress = 0f, speedBytesPerSec = 0L, message = "正在获取 UML 子系统信息…", engine = AppSettings.SUBSYSTEM_ENGINE_UML)
            }
            appendLog("> 获取 UML 子系统信息…")
            val meta = runCatching {
                val conn = URL(umlMetaUrl(appContext)).openConnection() as HttpURLConnection
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
            }.getOrNull()
            if (meta == null) {
                appendLog("! 获取 UML 子系统信息失败，请检查网络或下载源")
                _state.update { it.copy(phase = SubsystemPhase.ERROR, message = "获取 UML 子系统信息失败") }
                return@launch
            }
            // gzip 分发镜像：解压后约 4x，空间校验按解压量级
            if (!hasEnoughSpace(meta.rootfsSizeBytes * 4)) {
                appendLog("! 存储空间不足，安装已阻止")
                _state.update { it.copy(phase = SubsystemPhase.ERROR, message = "存储空间不足，无法安装 UML 子系统") }
                return@launch
            }
            val img = rootfsImg(appContext)
            val tmpImg = File(TermuxEnv.filesDir(appContext), "uml-rootfs.ext4.tmp")
            appendLog("> 开始下载 UML rootfs（${meta.version}）…")
            val ok = downloadWithFallback(meta.rootfsUrl, tmpImg, meta.rootfsSizeBytes)
            if (!ok) {
                appendLog("! UML rootfs 下载失败，请检查网络或切换下载源")
                _state.update { it.copy(phase = SubsystemPhase.ERROR, message = "UML rootfs 下载失败") }
                return@launch
            }
            appendLog("> 下载完成（${tmpImg.length() / 1024 / 1024} MB），校验 sha256…")
            if (!verifySha256(tmpImg, meta.rootfsSha256)) {
                appendLog("! UML rootfs 校验失败（sha256 不匹配）")
                tmpImg.delete()
                _state.update { it.copy(phase = SubsystemPhase.ERROR, message = "UML rootfs 校验失败（sha256 不匹配）") }
                return@launch
            }
            img.parentFile?.mkdirs()
            // 分发镜像为 gzip 压缩 ext4（稀疏镜像压缩后 ~150-200MB），解压到 rootfs.ext4
            if (meta.rootfsUrl.endsWith(".gz")) {
                _state.update { it.copy(phase = SubsystemPhase.EXTRACTING, progress = 0f, message = "正在解压子系统镜像…") }
                appendLog("> sha256 校验通过，解压镜像…")
                val ok = runCatching {
                    GZIPInputStream(BufferedInputStream(tmpImg.inputStream(), 256 * 1024)).use { input ->
                        BufferedOutputStream(FileOutputStream(img), 256 * 1024).use { out ->
                            val buf = ByteArray(256 * 1024)
                            var total = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                total += n
                            }
                            total > 0
                        }
                    }
                }.getOrDefault(false)
                tmpImg.delete()
                if (!ok) {
                    appendLog("! UML 镜像解压失败")
                    _state.update { it.copy(phase = SubsystemPhase.ERROR, message = "UML 镜像解压失败") }
                    return@launch
                }
            } else {
                if (!tmpImg.renameTo(img)) {
                    tmpImg.copyTo(img, overwrite = true)
                    tmpImg.delete()
                }
            }
            appendLog("> UML 子系统安装完成（${meta.flavor} ${meta.version}）")
            _state.update {
                it.copy(phase = SubsystemPhase.READY, version = meta.version, progress = 1f, speedBytesPerSec = 0L, message = "UML 子系统已就绪")
            }
        }
    }

    // ------------------------------------------------------------- 子系统代理（Clash/mihomo）
    // mihomo 跑在 guest 内（UML 真内核 root，TUN 透明分流）；App 下载订阅 YAML 写入
    // hostfs share/clash/，guest 侧 merge 脚本合并规则模板后启动 mihomo。

    /** 代理配置目录（hostfs，guest 可见）。 */
    fun clashDir(context: Context): File = File(shareDir(context), "clash")

    fun clashProfile(context: Context): File = File(clashDir(context), "config.yaml")

    /** 代理是否已配置（订阅 + 开关标记齐备，guest 启动时生效）。 */
    fun isProxyConfigured(context: Context): Boolean = clashProfile(context).isFile

    /**
     * 下载订阅并写入 hostfs share：enabled/mode 标记 + config.yaml。
     * 订阅解析失败时保留上一份可用配置（仅提示，覆盖前先校验可读）。
     */
    fun updateClashProfile(context: Context, onResult: (Boolean, String) -> Unit) {
        val url = AppSettings.proxySubUrl(context).trim()
        if (url.isBlank()) {
            onResult(false, "订阅地址为空")
            return
        }
        if (_state.value.phase == SubsystemPhase.DOWNLOADING) return
        scope.launch {
            _state.update {
                it.copy(phase = SubsystemPhase.DOWNLOADING, message = "正在下载订阅…")
            }
            val tmp = File(TermuxEnv.filesDir(appContext), "clash-profile.tmp")
            val ok = downloadWithFallback(url, tmp, 0L)
            if (!ok) {
                _state.update { it.copy(phase = SubsystemPhase.READY, message = "订阅下载失败") }
                appendLog("! Clash 订阅下载失败")
                onResult(false, "订阅下载失败，请检查网络或订阅地址")
                return@launch
            }
            // 基本校验：非空且含 proxies 字段（YAML/URI 订阅均可读）
            val text = runCatching { tmp.readText() }.getOrDefault("")
            if (text.isBlank()) {
                tmp.delete()
                _state.update { it.copy(phase = SubsystemPhase.READY, message = "订阅内容为空") }
                onResult(false, "订阅内容为空")
                return@launch
            }
            val dir = clashDir(appContext)
            dir.mkdirs()
            val target = clashProfile(appContext)
            // 保留上一份可用配置：写入 tmp 校验后原子替换
            if (target.exists()) runCatching { target.copyTo(File(dir, "config.yaml.bak"), overwrite = true) }
            runCatching {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            File(dir, "enabled").writeText("1")
            File(dir, "mode").writeText(AppSettings.proxyMode(context))
            AppSettings.setProxyUpdatedAt(context, java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date()))
            appendLog("> Clash 订阅已更新（${AppSettings.proxyMode(context)} 模式），重启子系统生效")
            _state.update { it.copy(phase = SubsystemPhase.READY, message = "订阅已更新") }
            onResult(true, "订阅已更新，重启子系统后生效")
        }
    }

    /** 写入代理开关标记（下次 guest 启动生效；mihomo 随 guest 收口）。 */
    fun setProxyEnabled(context: Context, enabled: Boolean) {
        AppSettings.setProxyEnabled(context, enabled)
        runCatching {
            val dir = clashDir(context)
            dir.mkdirs()
            val f = File(dir, "enabled")
            if (enabled) {
                f.writeText("1")
            } else {
                f.delete()
            }
        }
    }
}
