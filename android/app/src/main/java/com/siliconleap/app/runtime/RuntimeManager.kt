package com.siliconleap.app.runtime

import android.content.Context
import android.os.StatFs
import com.siliconleap.app.BuildConfig
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
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
import org.json.JSONArray
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
    val authToken: String? = null,
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
    val minAppVersion: String = "",
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

    // 看门狗（参考 DSHA HarnessService）：15s TCP 探测 WebUI 端口，连续 3 次失联
    // 自动重启，120s 冷却防风暴；不撤销用户停止意图（userStopRequested 守卫）
    private const val WATCHDOG_INTERVAL_MS = 15_000L
    private const val WATCHDOG_MAX_FAIL = 3
    private const val RESTART_COOLDOWN_MS = 120_000L

    @Volatile
    private var userStopRequested = false

    @Volatile
    private var lastRestartAt = 0L

    /** 默认元数据地址（GitHub Releases，runtime-latest 资产自动更新）。 */
    const val RUNTIME_TAG = "runtime-latest"
    const val RUNTIME_BETA_TAG = "runtime-beta-latest"

    /** 按测试通道开关返回滚动 tag（runtime-latest / runtime-beta-latest）。 */
    private fun runtimeTag(context: Context): String =
        if (AppSettings.acceptRuntimeBeta(context)) RUNTIME_BETA_TAG else RUNTIME_TAG

    /** 运行时元数据地址（按测试通道开关与仓库推导）。 */
    fun runtimeMetaUrl(context: Context): String =
        "https://github.com/RochelimitDawn/DSHM/releases/download/${runtimeTag(context)}/metadata.json"

    /** 按下载源解析 metadata URL（github / ghproxy_cf / ghproxy_axisnow / custom；auto 先测速解析实际源）。 */
    fun effectiveMetaUrl(context: Context): String = when (SourceManager.resolve(context)) {
        AppSettings.SOURCE_GHPROXY_CF -> "https://v6.gh-proxy.org/${runtimeMetaUrl(context)}"

        AppSettings.SOURCE_GHPROXY_AXISNOW -> "https://axisnow.gh-proxy.org/${runtimeMetaUrl(context)}"

        AppSettings.SOURCE_CUSTOM ->
            AppSettings.customMetaUrl(context).ifBlank { runtimeMetaUrl(context) }

        else -> runtimeMetaUrl(context)
    }

    /** 按当前下载源刷新 metaUrl（切换源后调用）。 */
    fun refreshSource(context: Context) {
        metaUrl = effectiveMetaUrl(context)
    }

    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** metadata 实际获取成功的源 URL（回退后下载跟随，避免 CDN 状态不一致）。 */
    private var lastMetaSource: String = ""

    private val _state = MutableStateFlow(RuntimeState())
    val state: StateFlow<RuntimeState> = _state.asStateFlow()

    /** 运行分区（默认 container；空串 = 未完成引导，App 显示渐进式向导）。 */
    private val _runMode = MutableStateFlow("")
    val runMode: StateFlow<String> = _runMode.asStateFlow()

    /** 更新运行分区（持久化 + 通知 UI）。 */
    fun setRunMode(context: Context, mode: String) {
        AppSettings.setRunMode(context, mode)
        _runMode.value = mode
    }

    /** 是否有新版本运行时待更新（应用期望运行时版本 ≠ 已安装版本）。 */
    private val _runtimeUpdateAvailable = MutableStateFlow(false)
    val runtimeUpdateAvailable: StateFlow<Boolean> = _runtimeUpdateAvailable.asStateFlow()

    private var serverProcess: Process? = null

    // ------------------------------------------------------------------ 插件自愈（dsh-mobile 等价隔离机制）
    // dsh web stdout 命中 loader 导入失败（坏插件/坏 bundle 拖垮进程）时解析包名；
    // 进程退出后从 profile 的 dsh.profile.bundles 摘除（保留 node_modules 文件，
    // 之后 dsh plugin add 重装会 reconcile 回填），再重启服务——不占崩溃配额。

    private val BROKEN_PLUGIN = Regex("failed to import loader entry \\S+ \\(([^)]+)\\)")

    /** web boot 未激活行：`@scope/name: pending (waiting for service: ...)`——单个插件
     *  无法激活时 dsh 直接拒绝 boot，WebView 永远进不去；同样拖垮启动，需隔离。 */
    private val PENDING_PLUGIN = Regex("^([\\w@./-]+):\\s*pending\\b")

    @Volatile
    private var brokenPlugin: String? = null

    /** 本轮进程输出是否出现 boot 失败（仅此后才捕获 pending 行，避免误伤普通进度文案）。 */
    @Volatile
    private var bootEntriesFailed = false

    private val quarantined = mutableSetOf<String>()

    /** 从 profile packages/web/package.json 的 dsh.profile.bundles 摘除坏插件（幂等）。 */
    private fun quarantineBrokenPlugin(pkg: String): Boolean = runCatching {
        if (pkg in quarantined) return false
        val manifest = File(TermuxEnv.dshHome(appContext), "profiles/web/package.json")
        if (!manifest.exists()) return false
        val obj = org.json.JSONObject(manifest.readText())
        val dsh = obj.optJSONObject("dsh") ?: return false
        val profile = dsh.optJSONObject("profile") ?: return false
        val bundles = profile.optJSONArray("bundles") ?: return false
        var found = false
        val kept = org.json.JSONArray()
        for (i in 0 until bundles.length()) {
            val b = bundles.optString(i)
            if (b == pkg) {
                found = true
                continue
            }
            kept.put(b)
        }
        if (!found) return false
        profile.put("bundles", kept)
        val tmp = File(manifest.parentFile, "package.json.tmp")
        tmp.writeText(obj.toString(2) + "\n")
        manifest.delete()
        tmp.renameTo(manifest)
        quarantined.add(pkg)
        true
    }.getOrDefault(false)
    private var startedAt: Long = 0L

    /** 下载源列表（可由设置页调整）。 */
    var metaUrl: String = ""
        private set

    fun setMetaUrl(url: String) {
        metaUrl = url
    }

    fun attach(context: Context) {
        if (!::appContext.isInitialized) {
            appContext = context.applicationContext
            // auto 源惰性测速：attach 在主线程不阻塞，下载前在 IO 线程 resolve 并刷新
            metaUrl = runtimeMetaUrl(context)
            // 旧版本存的 "root" 分区迁移为 container：分区概念已取消，
            // root 执行由 root_shell_enabled（设置页 Root Shell 开关）单独保留
            if (AppSettings.runMode(appContext) == "root") {
                AppSettings.setRunMode(appContext, AppSettings.RUN_MODE_CONTAINER)
            }
            // runMode 保留原始值：空串 = 未完成引导（App 显示渐进式向导，
            // 由向导的「开始安装」设置 container）；有值 = 存量用户直接进主界面
            _runMode.value = AppSettings.runMode(appContext)
            // 恢复上次运行时状态，避免应用重启后"从零开始"观感
            if (AppSettings.runtimeInstalled(appContext)) {
                _state.update {
                    it.copy(
                        installed = isRuntimeInstalled(),
                        runtimeVersion = readRuntimeVersion() ?: AppSettings.runtimeVersion(appContext),
                    )
                }
            }
            // 恢复旧 node 进程的认证 token（0.2.0 起 WebUI 需要 ?token=）：
            // serverProcess 引用丢失但进程仍存活时，从 server.log 反查最近一次的 token
            if (_state.value.authToken == null) {
                readTokenFromLog()?.let { token -> _state.update { it.copy(authToken = token) } }
            }
            // 安装包残留清扫：运行时已就绪时，上次进程中途被杀/校验失败留下的
            // runtime-download.zip 与断点 sidecar 无保留价值，启动即清
            if (AppSettings.runtimeInstalled(appContext)) {
                runCatching {
                    val stale = File(TermuxEnv.filesDir(appContext), "runtime-download.zip")
                    stale.delete()
                    resumeSidecar(stale).delete()
                }
            }
            // 看门狗线程（daemon）：WebUI 掉了自动拉起，不用手动重启
            startWatchdog()
        }
    }

    /** 看门狗：15s TCP 探测，连续 3 次失联自动重启（120s 冷却防风暴）。 */
    private fun startWatchdog() {
        Thread({
            var fail = 0
            while (true) {
                try {
                    Thread.sleep(WATCHDOG_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                if (_state.value.phase != ServerPhase.RUNNING) {
                    fail = 0
                    continue
                }
                // 用户停止意图：探测期间手动启停不沿用旧结果，也不自动拉起
                if (userStopRequested) {
                    fail = 0
                    continue
                }
                // UML 僵死同步：状态标记运行中但进程已死（panic/doze 半开），修正状态
                if (::appContext.isInitialized &&
                    SubsystemManager.umlRunning(appContext) &&
                    !SubsystemManager.isUmlProcessAlive(appContext)
                ) {
                    SubsystemManager.syncUmlStopped(appContext)
                }
                val port = _state.value.port
                if (isWebUp(port)) {
                    fail = 0
                    continue
                }
                fail++
                if (fail < WATCHDOG_MAX_FAIL) continue
                fail = 0
                val now = android.os.SystemClock.elapsedRealtime()
                if (lastRestartAt != 0L && now - lastRestartAt < RESTART_COOLDOWN_MS) continue
                lastRestartAt = now
                appendLog("> WebUI 连续失联（3 次探测未响应），自动重启服务…")
                stopServer(auto = true)
                scope.launch { startServerIfNeeded() }
            }
        }, "web-watchdog").apply { isDaemon = true; start() }
    }

    /** WebUI 存活探测：TCP connect 127.0.0.1:port（3s 超时）。 */
    private fun isWebUp(port: Int): Boolean = runCatching {
        java.net.Socket().use { socket ->
            socket.connect(java.net.InetSocketAddress("127.0.0.1", port), 3_000)
            true
        }
    }.getOrDefault(false)

    /** 0.2.0 起 WebUI 根请求需要 ?token= 认证（token 换 cookie）；无 token 时 401。 */
    private val TOKEN_LINE_REGEX = Regex("""\?token=([A-Za-z0-9_-]+)""")

    /** 从 node stdout 行提取认证 token。 */
    private fun extractToken(line: String): String? =
        TOKEN_LINE_REGEX.find(line)?.groupValues?.get(1)

    /** 从 server.log 反查最近一次的认证 token（应用重启后进程仍存活的场景）。 */
    private fun readTokenFromLog(): String? {
        val log = TermuxEnv.serverLog(appContext)
        if (!log.exists()) return null
        return runCatching {
            var token: String? = null
            log.readText().lineSequence().forEach { line ->
                extractToken(line)?.let { token = it }
            }
            token
        }.getOrNull()
    }

    /** 组装打开 WebUI 的地址：token 可用时携带（0.2.0 认证围栏），否则裸地址。 */
    fun webUrl(port: Int, token: String?): String =
        if (token.isNullOrBlank()) "http://127.0.0.1:$port/" else "http://127.0.0.1:$port/?token=$token"

    /** 已安装运行时是否支持 dsh web 的 --no-open（0.2.0 起提供；0.1.x 会报 unknown option）。 */
    fun supportsWebNoOpen(): Boolean {
        val version = readRuntimeVersion() ?: return false
        return isVersionAtLeast(version, 0, 2)
    }

    /** 版本串是否 >= 指定的 major.minor（如 0.2.0-rc.2 → (0,2)）；无法解析返回 false。 */
    private fun isVersionAtLeast(version: String, major: Int, minor: Int): Boolean {
        val m = Regex("""v?(\d+)\.(\d+)""").find(version.trim()) ?: return false
        val ma = m.groupValues[1].toIntOrNull() ?: return false
        val mi = m.groupValues[2].toIntOrNull() ?: return false
        return ma > major || (ma == major && mi >= minor)
    }

    fun uptimeMillis(): Long {
        if (serverProcess?.isAlive == true) return System.currentTimeMillis() - startedAt
        // 进程引用丢失（应用重启后旧 node 进程仍存活）时按进程启动时间反查，
        // 避免"运行时长 0 秒"直到下次重启才恢复
        val pid = processPid(serverProcess)
        return pid?.let { processUptimeMillis(it) } ?: 0L
    }

    /** 从 /proc/<pid>/stat 的 starttime（自 boot 的 jiffies）与 /proc/uptime 计算进程运行时长。 */
    private fun processUptimeMillis(pid: Long): Long? = runCatching {
        val stat = File("/proc/$pid/stat").readText()
        val idx = stat.lastIndexOf(')')
        if (idx < 0) return@runCatching null
        val parts = stat.substring(idx + 1).trim().split(' ')
        // comm 后 parts[0] 对应字段 3；starttime 是字段 22 → parts[19]（HZ=100）
        val startJiffies = parts.getOrNull(19)?.toLongOrNull() ?: return@runCatching null
        val uptimeSec = File("/proc/uptime").readText().substringBefore(' ').toDoubleOrNull()
            ?: return@runCatching null
        val runMs = ((uptimeSec - startJiffies / 100.0) * 1000.0).toLong()
        runMs.coerceAtLeast(0L)
    }.getOrNull()

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
        return if (log.exists()) LogStore.named(log).tail(lines) else "(暂无日志)"
    }

    /** 检测当前工作区是否实际可写（外部目录需「所有文件访问」权限且授权后重启生效）。 */
    fun workspaceWritable(context: Context): Boolean {
        val dir = TermuxEnv.workspace(context)
        return runCatching {
            if (!dir.exists()) dir.mkdirs()
            if (!dir.isDirectory) return false
            val probe = File(dir, ".probe-${System.currentTimeMillis()}")
            probe.createNewFile()
            val ok = probe.exists()
            probe.delete()
            ok
        }.getOrDefault(false)
    }

    fun isRuntimeInstalled(): Boolean = TermuxEnv.dshEntry(appContext).exists()

    /**
     * 仅下载并安装运行时（不自动启动），供环境页点击拉取。
     * force=true 强制重下：更新横幅点击时运行时已存在，早退会让更新永远没效果
     * （旧版「已安装，无需重复下载」提示）。覆盖安装由 downloadAndInstall 处理。
     */
    fun installRuntime(force: Boolean = false) {
        if (_state.value.phase == ServerPhase.DOWNLOADING ||
            _state.value.phase == ServerPhase.EXTRACTING
        ) {
            return
        }
        if (!force && isRuntimeInstalled()) {
            _state.update {
                it.copy(
                    phase = ServerPhase.NOT_READY,
                    message = "运行时已安装，无需重复下载",
                )
            }
            return
        }
        scope.launch {
            downloadAndInstall()
        }
    }

    /** 启动/检查流程：未安装 → 在线下载安装；已安装 → 启动服务。 */
    fun bootstrap() {
        // 下载/安装/启动进行中：忽略重复触发（自动启动与手动点击并发时防重入）
        if (_state.value.phase == ServerPhase.RUNNING ||
            _state.value.phase == ServerPhase.DOWNLOADING ||
            _state.value.phase == ServerPhase.EXTRACTING ||
            _state.value.phase == ServerPhase.STARTING
        ) {
            return
        }
        val force = forceRestart
        forceRestart = false
        val installed = isRuntimeInstalled()
        val version = if (installed) readRuntimeVersion() ?: _state.value.runtimeVersion else null
        // 持久化状态，供应用重启后恢复显示
        AppSettings.setRuntimeInstalled(appContext, installed)
        AppSettings.setRuntimeVersion(appContext, version)
        _state.update {
            it.copy(
                installed = installed,
                runtimeVersion = version,
            )
        }
        checkRuntimeUpdate()
        scope.launch {
            // 正常启动时若服务已在监听则直接复用；restart()（强制重启）必须重新装配并拉起新进程，
            // 否则旧进程端口未及时释放会误判为"已在运行"而跳过 dsh-mobile 装配
            if (!force && ping(_state.value.port)) {
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
                // 下载/解压失败：保留真实错误，不再进入启动自检（避免被 preflight 诊断覆盖）
                if (_state.value.phase == ServerPhase.ERROR) return@launch
            }
            // 同步应用工作区到 DSH 的 workspace.json（WebUI 默认工作区跟随设置）
            syncWorkspaceToDsh()
            // 服务尽早启动：插件装配与子系统安装改为并行后台任务，
            // 避免两者（pnpm 装配可达分钟级、子系统 50MB 下载）串行阻塞 WebUI 可达
            if (_state.value.phase != ServerPhase.ERROR) {
                _state.update { it.copy(message = "正在启动服务…") }
            }
            // 子系统必装：未装先装完再启动 server（DSH_SUBSYSTEM_ARGV 就绪，
            // server 启动即携带子系统包裹），不再有「先起 bionic server 后补装」的过渡态
            val needSubsystem = AppSettings.runMode(appContext) == AppSettings.RUN_MODE_CONTAINER &&
                isRuntimeInstalled() && !SubsystemManager.isInstalled(appContext)
            val startedAtMs = System.currentTimeMillis()
            var addonInstalledAny = false
            if (needSubsystem) {
                appendLog("> 自动安装 Debian 子系统（首次启动先装子系统，server 启动即携带）…")
                SubsystemManager.resetForAutoInstall()
                SubsystemManager.installAndWait()
                if (!SubsystemManager.isInstalled(appContext)) {
                    appendLog("! 子系统安装失败，可稍后在环境页重试")
                }
            }
            // 混合调度：后台预启动 UML（不阻塞 server），首条重载命令零等待
            SubsystemManager.maybePreboot(appContext)
            val serverJob = launch { startServerIfNeeded() }
            val addonJob = launch {
                val addonStarted = System.currentTimeMillis()
                addonInstalledAny = AddonManager.ensureBlocking()
                if (addonInstalledAny) {
                    appendLog("> WebUI 插件装配完成（${(System.currentTimeMillis() - addonStarted) / 1000}s）")
                }
            }
            serverJob.join()
            addonJob.join()
            // 插件装配改变（新插件需重载）→ 服务已 RUNNING 时做一次静默重启使其生效。
            // 子系统必装后 server 在装完后才启动（DSH_SUBSYSTEM_ARGV 已就绪），无需为它重启。
            if (addonInstalledAny && _state.value.phase == ServerPhase.RUNNING) {
                appendLog("> 装配完成，重启服务使插件生效（启动耗时 ${(System.currentTimeMillis() - startedAtMs) / 1000}s）…")
                stopServer()
                startServerIfNeeded()
            }
        }
    }

    /** 强制重启：跳过"端口已在监听即复用"的短路，确保装配与启动流程完整执行。 */
    private var forceRestart = false

    fun restart() {
        stopServer()
        forceRestart = true
        _state.update { it.copy(phase = ServerPhase.NOT_READY) }
        bootstrap()
    }

    /** 删除运行时并重新下载安装（用于修复损坏/权限异常）。删除移 IO 线程，避免大目录遍历卡 UI。 */
    fun rebuildRuntime() {
        stopServer()
        scope.launch {
            withContext(Dispatchers.IO) { TermuxEnv.prefix(appContext).deleteRecursively() }
            clearRuntimeCache()
            _state.update { it.copy(phase = ServerPhase.NOT_READY, installed = false, runtimeVersion = null) }
            bootstrap()
        }
    }

    /** 卸载运行时（保留 dsh-home/workspace）。删除移 IO 线程，避免大目录遍历卡 UI。 */
    fun uninstallRuntime() {
        stopServer()
        scope.launch {
            withContext(Dispatchers.IO) { TermuxEnv.prefix(appContext).deleteRecursively() }
            clearRuntimeCache()
            _state.update {
                it.copy(phase = ServerPhase.NOT_READY, installed = false, runtimeVersion = null)
            }
        }
    }

    /** 清理运行时状态缓存（卸载/重装时调用）。 */
    private fun clearRuntimeCache() {
        AppSettings.setRuntimeInstalled(appContext, false)
        AppSettings.setRuntimeVersion(appContext, null)
    }

    /**
     * 同步应用工作区到 DSH 的 storages/workspace.json：
     * 更新（title=DSHM）条目的 path，或新增条目，并将其置为默认（workspaceIds 首位），
     * 使 DSH WebUI 默认打开的工作区跟随应用设置。
     *
     * 必须保证写入后的 workspaceIds 与 tables.workspaces 完全一致（自愈）：
     * DSH 启动时 dsh-workspace 的 validateStoredState 校验
     * `initialized=true 时 workspaceIds 集合必须等于 tables.workspaces 的 key 集合`，
     * 不一致会抛 "workspace ... is absent from registry order" 导致整个服务启动失败。
     * 因此这里重建 order：包含全部 table key、剔除失效引用、DSHM 恒在首位，
     * 原有相对顺序尽量保留。
     */
    private fun syncWorkspaceToDsh() {
        runCatching {
            val wsFile = File(TermuxEnv.dshHome(appContext), "storages/workspace.json")
            if (!wsFile.exists()) return
            val newPath = AppSettings.workspacePath(appContext)
            val json = JSONObject(wsFile.readText())
            val tables = json.optJSONObject("tables") ?: return
            val workspaces = tables.optJSONObject("workspaces") ?: return
            val global = json.optJSONObject("global") ?: return
            val now = java.time.Instant.now().toString()

            // 1. 确保 DSHM 条目存在
            var targetKey: String? = null
            val it = workspaces.keys()
            while (it.hasNext()) {
                val k = it.next()
                val w = workspaces.optJSONObject(k) ?: continue
                if (w.optString("title") == "DSHM") {
                    targetKey = k
                    break
                }
            }
            // path 冲突保护：DSH 的 validateStoredState 禁止两个 workspace 使用同一 path，
            // 若新 path 已被其它 workspace 占用，则保留 DSHM 原 path，避免再次让服务启动失败
            val pathTaken = workspaces.keys().asSequence()
                .filter { it != targetKey }
                .map { workspaces.optJSONObject(it)?.optString("path") }
                .any { it == newPath }
            val finalPath = if (pathTaken) {
                val old = targetKey?.let { workspaces.optJSONObject(it)?.optString("path") }
                if (!old.isNullOrBlank()) old else newPath
            } else {
                newPath
            }
            if (targetKey == null) {
                targetKey = java.util.UUID.randomUUID().toString()
                workspaces.put(targetKey, JSONObject().apply {
                    put("path", finalPath)
                    put("title", "DSHM")
                    put("sessionIds", JSONArray())
                    put("createdAt", now)
                    put("updatedAt", now)
                })
            } else {
                workspaces.getJSONObject(targetKey).put("path", finalPath).put("updatedAt", now)
            }

            // 2. 重建 workspaceIds：DSHM 恒在首位；保留原有有效条目相对顺序；剔除失效引用；
            //    补齐 tables 里有但 order 缺失的 key（自愈不一致，避免 DSH 启动校验失败）
            val newIds = JSONArray()
            newIds.put(targetKey)
            val seen = HashSet<String>()
            seen.add(targetKey)
            val old = global.optJSONArray("workspaceIds")
            if (old != null) {
                for (i in 0 until old.length()) {
                    val id = old.optString(i)
                    if (seen.contains(id)) continue
                    if (workspaces.has(id)) {
                        newIds.put(id)
                        seen.add(id)
                    }
                }
            }
            val keys = workspaces.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (!seen.contains(k)) newIds.put(k)
            }
            global.put("workspaceIds", newIds)
            wsFile.writeText(json.toString(2))
        }
    }

    fun clearData() {
        stopServer()
        scope.launch {
            withContext(Dispatchers.IO) { TermuxEnv.dshHome(appContext).deleteRecursively() }
            _state.update { it.copy(phase = ServerPhase.NOT_READY) }
        }
    }

    fun stopServer(auto: Boolean = false) {
        if (!auto) userStopRequested = true
        serverProcess?.destroy()
        runCatching { serverProcess?.waitFor(3, TimeUnit.SECONDS) }
        serverProcess?.destroyForcibly()
        serverProcess = null
        startedAt = 0L
        // 应用重启后 serverProcess 引用丢失，旧 node 进程仍可能存活并占用 usr 文件
        // （导致解压时 usr/bin/ls 等无法删除）。按端口反查并强制结束遗留进程。
        killOrphanByPort()
        // 新进程有新 token，旧 token 失效；启动后由 forwardProcessOutput 重新提取
        _state.update { it.copy(phase = ServerPhase.NOT_READY, pid = null, authToken = null) }
    }

    /** 强制结束监听本应用端口且不属于本进程的遗留 node 进程（引用丢失场景）。 */
    private fun killOrphanByPort() {
        val pid = findPidByPort(_state.value.port) ?: return
        if (pid == android.os.Process.myPid().toLong()) return
        appendLog("> 结束遗留服务进程 pid=$pid…")
        runCatching {
            ProcessBuilder("kill", "-9", pid.toString()).start().waitFor(3, TimeUnit.SECONDS)
        }
        // 等内核释放子进程持有的文件句柄（node worker/pty 子进程可能短暂持有 usr 文件）
        Thread.sleep(500)
    }

    // ------------------------------------------------------------------ 下载安装

    /** 下载源的人类可读名（日志/诊断用）。 */
    private fun sourceName(source: String): String = when (source) {
        AppSettings.SOURCE_GHPROXY_AXISNOW -> "AxisNow"
        AppSettings.SOURCE_GHPROXY_CF -> "Cloudflare"
        AppSettings.SOURCE_GITHUB -> "GitHub"
        AppSettings.SOURCE_CUSTOM -> "自定义"
        else -> source
    }

    private suspend fun downloadAndInstall() {
        clearLog()
        // 下载/安装期间启动前台服务保活进程：退出应用下载继续，断点续传兜底仍在；
        // 通知条同步展示下载与安装实时进度（DOWNLOADING/EXTRACTING 状态文案）
        HarnessService.start(appContext)
        // 先置 DOWNLOADING：测速阶段也显示进度，避免 10s "卡住"观感与并发重入
        _state.update {
            it.copy(phase = ServerPhase.DOWNLOADING, progress = 0f, speedBytesPerSec = 0L, message = "正在获取运行时信息…")
        }
        // auto 源：IO 线程内先测速选源并刷新 metaUrl（首次调用阻塞测速，缓存后不再测）
        if (AppSettings.downloadSource(appContext) == AppSettings.SOURCE_AUTO) {
            appendLog("> 自动测速选择下载源…")
            val results = SourceManager.speedTest()
            for (r in results.sortedBy { it.estimatedMs }) {
                val speed = if (r.speedKBps > 0.0) String.format("%.1f MB/s", r.speedKBps / 1024.0) else "未测速"
                appendLog("> 测速 ${sourceName(r.source)}: 延迟 ${r.latencyMs}ms · $speed")
            }
            val picked = SourceManager.pickBest(results, appContext)
            appendLog("> 已选择下载源: ${sourceName(picked)}")
            metaUrl = effectiveMetaUrl(appContext)
        }
        appendLog("> 获取运行时信息…")
        // metadata 按源回退链：选中源失败（gh-proxy CDN 传播延迟/限流）时逐级回退
        // 直连与其他镜像；镜像源资产刚更新时 CDN 常见 404/旧缓存
        val directMeta = runtimeMetaUrl(appContext)
        val candidates = buildList {
            add(metaUrl)
            for (u in listOf(directMeta, "https://axisnow.gh-proxy.org/$directMeta", "https://v6.gh-proxy.org/$directMeta")) {
                if (u !in this) add(u)
            }
        }
        var meta: RuntimeMeta? = null
        for ((i, url) in candidates.withIndex()) {
            if (i > 0) appendLog("> metadata 获取失败，回退下载源 ${i}/${candidates.size - 1}…")
            meta = runCatching { fetchMeta(url) }.getOrNull()
            if (meta != null) {
                if (i > 0) appendLog("> metadata 已从回退源获取，后续下载跟随该源")
                metaUrl = url
                lastMetaSource = url
                break
            }
        }
        if (meta == null) {
            appendLog("! 获取运行时信息失败，请检查网络或镜像源")
            _state.update {
                it.copy(phase = ServerPhase.ERROR, message = "获取运行时信息失败，请检查网络或镜像源")
            }
            return
        }
        // minAppVersion 门槛：低于要求的应用先更新软件，而不是下载一个装不上的运行时
        if (meta.minAppVersion.isNotBlank() && isVersionLower(BuildConfig.VERSION_NAME, meta.minAppVersion)) {
            appendLog("! 应用版本 ${BuildConfig.VERSION_NAME} 低于运行时要求的 ${meta.minAppVersion}")
            _state.update {
                it.copy(
                    phase = ServerPhase.ERROR,
                    message = "应用版本过低（${BuildConfig.VERSION_NAME}），运行时 v${meta.version} 要求 ${meta.minAppVersion}；请先更新应用",
                )
            }
            return
        }
        val zip = File(TermuxEnv.filesDir(appContext), "runtime-download.zip")
        appendLog("> 开始下载运行时 v${meta.version}")
        val ok = downloadWithFallback(meta, zip)
        if (!ok) {
            appendLog("! 运行时下载/校验失败，请检查网络或切换镜像源")
            _state.update {
                it.copy(phase = ServerPhase.ERROR, message = "运行时下载失败（各源 sha256 校验均不通过或不可达），请稍后重试或切换镜像源")
            }
            return
        }
        appendLog("> sha256 校验通过，开始解压安装…")
        val freeBytes = availableSpace(TermuxEnv.filesDir(appContext))
        // 解压需临时目录双份（usr.tmp + 目标 usr）≈ zip 解压体积 ×2，预留 2.5 倍 zip 体积
        val needBytes = (zip.length() * 2.5).toLong()
        if (freeBytes in 1..needBytes) {
            appendLog("! 存储空间不足: 可用 ${freeBytes / 1024 / 1024}MB，需约 ${needBytes / 1024 / 1024}MB")
            _state.update {
                it.copy(
                    phase = ServerPhase.ERROR,
                    message = "存储空间不足（可用 ${freeBytes / 1024 / 1024}MB，需约 ${needBytes / 1024 / 1024}MB），请清理后重试",
                )
            }
            return
        }
        _state.update { it.copy(phase = ServerPhase.EXTRACTING, progress = 0f, message = "正在安装运行时…") }
        // 旧 node 服务进程可能占用 usr 下文件（bin/ls 等），导致删除/重命名失败，
        // 先停服释放文件句柄（不改变当前 EXTRACTING 阶段）
        stopServer()
        _state.update { it.copy(phase = ServerPhase.EXTRACTING, progress = 0f, message = "正在安装运行时…") }
        val installed = extractZip(zip, TermuxEnv.prefix(appContext))
        zip.delete()
        runCatching { resumeSidecar(zip).delete() }
        if (!installed) {
            appendLog("! 运行时安装失败")
            _state.update { it.copy(phase = ServerPhase.ERROR, message = "运行时安装失败") }
            return
        }
        appendLog("> 运行时安装完成")
        AppSettings.setRuntimeInstalled(appContext, true)
        AppSettings.setRuntimeVersion(appContext, meta.version)
        _runtimeUpdateAvailable.value = false
        _state.update {
            it.copy(
                // 保持 STARTING：BootScreen 持续显示，避免下载完成后到下一阶段间的"空白无响应"
                phase = ServerPhase.STARTING,
                installed = true,
                runtimeVersion = meta.version,
                progress = 1f,
                speedBytesPerSec = 0L,
                message = "运行时已就绪，正在准备环境…",
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

    // 重指标缓存：fd 列目录 / 会话计数 / 磁盘统计 / 网卡枚举每秒做一次开销较大，
    // 5s 采样一次即可（CPU/内存/线程为实时核心指标，保持每秒）。
    private var lastHeavyAt: Long = 0L
    private var cachedFds = 0
    private var cachedSessions = 0
    private var cachedFreeBytes = 0L
    private var cachedTotalBytes = 0L
    private var cachedLanIps: List<String> = emptyList()

    /** 环境页诊断快照（IO 线程执行，每秒轮询）。 */
    suspend fun diagnostics(): RuntimeDiagnostics = withContext(Dispatchers.IO) {
        val pid = processPid(serverProcess)
        var cpu = 0.0
        var memKb = 0L
        var threads = 0
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
            val status = procStatusFields(pid, "VmRSS", "Threads")
            memKb = status["VmRSS"] ?: 0L
            threads = status["Threads"]?.toInt() ?: 0
        }
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastHeavyAt >= 5000L) {
            lastHeavyAt = nowMs
            cachedFds = pid?.let {
                runCatching { File("/proc/$it/fd").listFiles()?.size ?: 0 }.getOrDefault(0)
            } ?: 0
            cachedSessions = sessionsCount()
            val stat = android.os.StatFs(appContext.filesDir.absolutePath)
            cachedFreeBytes = runCatching { stat.availableBlocksLong * stat.blockSizeLong }.getOrDefault(0L)
            cachedTotalBytes = runCatching { stat.blockCountLong * stat.blockSizeLong }.getOrDefault(0L)
            cachedLanIps = lanIps()
        }
        val logFile = TermuxEnv.serverLog(appContext)
        val logLines = if (logFile.exists()) LogStore.named(logFile).count() else 0
        val logBytes = runCatching { logFile.length() }.getOrDefault(0L)
        RuntimeDiagnostics(
            pid = pid,
            cpuPercent = cpu,
            memRssKb = memKb,
            threads = threads,
            fds = cachedFds,
            sessions = cachedSessions,
            logLines = logLines,
            logBytes = logBytes,
            freeBytes = cachedFreeBytes,
            totalBytes = cachedTotalBytes,
            lanIps = cachedLanIps,
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

    /** /proc/<pid>/status 一次性读取多个字段（避免每秒读两次文件）。 */
    private fun procStatusFields(pid: Long, vararg names: String): Map<String, Long> {
        if (names.isEmpty()) return emptyMap()
        val wanted = names.toMutableSet()
        val lines = runCatching { File("/proc/$pid/status").readLines() }.getOrDefault(emptyList())
        val result = HashMap<String, Long>()
        for (line in lines) {
            if (wanted.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val key = line.substring(0, idx)
            if (key in wanted) {
                val v = line.substring(idx + 1).trim().substringBefore(' ').toLongOrNull()
                if (v != null) {
                    result[key] = v
                    wanted.remove(key)
                }
            }
        }
        return result
    }

    /** dsh-home/sessions 下的会话目录数。 */
    private fun sessionsCount(): Int = runCatching {
        File(TermuxEnv.dshHome(appContext), "sessions").listFiles()?.count { it.isDirectory } ?: 0
    }.getOrDefault(0)

    /** 枚举所有启用的 IPv4 局域网地址（排除回环）。 */
    private fun lanIps(): List<String> = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filter { it is java.net.Inet4Address && !it.isLoopbackAddress }
            .map { it.hostAddress }
            .toList()
    }.getOrDefault(emptyList())

    /** 解析 vMAJ.MIN.PATCH[-suffix] 版本串为三元组（缺省段补 0）；无法解析返回 null。 */
    private fun parseVVersion(s: String): Triple<Int, Int, Int>? {
        val m = Regex("""v?(\d+)\.(\d+)(?:\.(\d+))?(?:-.*)?$""").find(s.trim()) ?: return null
        val maj = m.groupValues[1].toIntOrNull() ?: return null
        val min = m.groupValues[2].toIntOrNull() ?: return null
        val pat = m.groupValues[3].toIntOrNull() ?: 0
        return Triple(maj, min, pat)
    }

    /** a 是否低于 b（vMAJ.MIN.PATCH 三段语义化比较，Triple 未实现 Comparable 需逐段比）；任一无法解析返回 false。 */
    private fun isVersionLower(a: String, b: String): Boolean {
        val pa = parseVVersion(a) ?: return false
        val pb = parseVVersion(b) ?: return false
        if (pa.first != pb.first) return pa.first < pb.first
        if (pa.second != pb.second) return pa.second < pb.second
        return pa.third < pb.third
    }

    private fun fetchMeta(url: String? = null): RuntimeMeta? = try {
        // auto 源：首次访问 metadata 前完成测速解析（测速缓存后不重复）
        if (AppSettings.downloadSource(appContext) == AppSettings.SOURCE_AUTO) {
            metaUrl = effectiveMetaUrl(appContext)
        }
        val conn = URL(url ?: metaUrl).openConnection() as HttpURLConnection
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
            minAppVersion = json.optString("minAppVersion", ""),
            builtAt = json.optString("builtAt", ""),
        )
    } catch (e: Exception) {
        null
    }

    private suspend fun downloadWithFallback(meta: RuntimeMeta, target: File): Boolean {
        val candidates = proxyCandidates(meta)
        val sidecar = resumeSidecar(target)
        for ((index, candidate) in candidates.withIndex()) {
            _state.update {
                it.copy(message = "正在下载运行时（${meta.version}）…", speedBytesPerSec = 0L)
            }
            // 残留的部分文件来自其它下载源时先丢弃：跨源续传会拼出损坏内容
            val sidecarUrl = runCatching { sidecar.readText().trim() }.getOrDefault("")
            if (sidecarUrl.isNotEmpty() && sidecarUrl != candidate) {
                runCatching { target.delete() }
            }
            appendLog("> 尝试下载源 [${index + 1}/${candidates.size}]: $candidate${if (sidecarUrl == candidate) "（续传）" else ""}")
            if (downloadFile(candidate, target, meta.sizeBytes)) {
                // 逐源 sha 校验：CDN 旧缓存会返回旧版 zip（传输"成功"但内容不对），
                // 校验失败换下一候选源，尺寸不符单独提示（传输截断特征）
                if (meta.sizeBytes > 0 && target.length() != meta.sizeBytes) {
                    appendLog("! 尺寸不符（${target.length()} ≠ ${meta.sizeBytes}），疑似传输截断")
                }
                if (verifySha256(target, meta.sha256)) {
                    appendLog("> 下载源 [${index + 1}] 成功（sha256 校验通过）")
                    runCatching { sidecar.delete() }
                    return true
                }
                appendLog("! 下载源 [${index + 1}] sha256 校验失败（CDN 旧缓存或内容损坏），换下一候选源")
                runCatching {
                    target.delete()
                    sidecar.delete()
                }
                continue
            }
            appendLog("! 下载源 [${index + 1}] 失败: $candidate")
            _state.update { it.copy(message = "下载源不可用，尝试切换…", speedBytesPerSec = 0L) }
        }
        return false
    }

    /** 记录部分下载文件来源的 sidecar：续传只对同一 URL 安全。 */
    private fun resumeSidecar(target: File): File = File(target.parentFile, "${target.name}.url")

    /** 按当前下载源构造候选下载地址。GHProxy 源给 GitHub 地址加优选前缀，直连与自定义镜像兜底。 */
    private fun proxyCandidates(meta: RuntimeMeta): List<String> {
        // 下载源跟随 metadata 实际获取源（回退源生效时下载同源，避免 CDN 状态不一致）
        val prefix = when {
            lastMetaSource.startsWith("https://v6.gh-proxy.org/") -> "https://v6.gh-proxy.org/"
            lastMetaSource.startsWith("https://axisnow.gh-proxy.org/") -> "https://axisnow.gh-proxy.org/"
            else -> when (SourceManager.resolve(appContext)) {
                AppSettings.SOURCE_GHPROXY_CF -> "https://v6.gh-proxy.org/"

                AppSettings.SOURCE_GHPROXY_AXISNOW -> "https://axisnow.gh-proxy.org/"

                else -> ""
            }
        }
        if (prefix.isEmpty()) return listOf(meta.url) + meta.mirrors
        val candidates = (listOf(meta.url) + meta.mirrors)
            .map { url -> if (url.startsWith("https://github.com/")) prefix + url else url }
        // GHProxy 只代理 GitHub，镜像（非 GitHub 前缀）保持原样作为后续兜底
        return candidates + listOf(meta.url)
    }

    private suspend fun downloadFile(url: String, target: File, sizeBytes: Long): Boolean {
        var conn: HttpURLConnection? = null
        var input: InputStream? = null
        var out: OutputStream? = null
        var ok = false
        return try {
            // 断点续传：同 URL 的残留部分文件用 Range 续传（GitHub release 资产支持 Range）
            val sidecar = resumeSidecar(target)
            val canResume = target.exists() && target.length() > 0L &&
                runCatching { sidecar.readText().trim() == url }.getOrDefault(false)
            val resumeBytes = if (canResume) target.length() else 0L
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            if (resumeBytes > 0) conn.setRequestProperty("Range", "bytes=$resumeBytes-")
            val code = conn.responseCode
            if (code !in 200..299) return false
            // 206 = 服务端支持 Range 且从断点继续；200 = 全量重传（服务端忽略 Range）
            val appending = code == 206 && resumeBytes > 0L
            val contentLength = when {
                sizeBytes > 0 -> sizeBytes
                appending -> conn.contentLengthLong + resumeBytes
                else -> conn.contentLengthLong
            }
            target.parentFile?.mkdirs()
            out = if (appending) {
                BufferedOutputStream(FileOutputStream(target, true))
            } else {
                BufferedOutputStream(FileOutputStream(target))
            }
            sidecar.writeText(url)
            input = conn.inputStream
            val buf = ByteArray(64 * 1024)
            var written = 0L
            var downloaded = resumeBytes
            var lastUpdate = 0L
            var lastLoggedPct = -1
            var speedBps = 0L
            var lastSpeedAt = System.currentTimeMillis()
            var lastSpeedTotal = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                written += n
                downloaded += n
                val now = System.currentTimeMillis()
                if (now - lastSpeedAt >= 500) {
                    val dtSec = (now - lastSpeedAt) / 1000.0
                    if (dtSec > 0.0) speedBps = ((downloaded - lastSpeedTotal) / dtSec).toLong()
                    lastSpeedAt = now
                    lastSpeedTotal = downloaded
                }
                if (downloaded - lastUpdate > 512 * 1024 || (contentLength > 0 && downloaded >= contentLength)) {
                    lastUpdate = downloaded
                    if (contentLength > 0) {
                        val pct = (downloaded.toDouble() / contentLength).coerceIn(0.0, 1.0)
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
                if (contentLength > 0 && downloaded > contentLength) {
                    return false
                }
            }
            if (contentLength > 0 && downloaded != contentLength) {
                appendLog("! 下载不完整: 预期 $contentLength 实际 $downloaded")
                return false
            }
            ok = true
            true
        } catch (e: Exception) {
            appendLog("! 下载异常: ${e.javaClass.simpleName}: ${e.message}")
            false
        } finally {
            runCatching { input?.close() }
            runCatching { out?.close() }
            runCatching { conn?.disconnect() }
            // 失败时保留部分文件（同 URL 下次续传）；无残留意义时由 downloadWithFallback 丢弃
            if (!ok && (target.exists() && target.length() == 0L)) runCatching { target.delete() }
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

    /** 目标目录所在分区的可用字节数；异常返回 -1。 */
    private fun availableSpace(dir: File): Long = runCatching {
        dir.mkdirs()
        val stat = StatFs(dir.absolutePath)
        stat.availableBytes
    }.getOrDefault(-1L)

    private fun extractZip(zip: File, dest: File): Boolean {
        // 调用方（downloadAndInstall）已先 stopServer 释放文件句柄
        return try {
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
            // staging 完整性校验（Eta 同款单事务）：dsh 入口缺失即拒绝换树，旧运行时保留
            val stagingEntry = File(
                tmp,
                TermuxEnv.dshEntry(appContext).absolutePath.removePrefix(TermuxEnv.prefix(appContext).absolutePath),
            )
            if (!stagingEntry.exists()) {
                appendLog("! 新树不完整（dsh 入口缺失），保留现有运行时")
                tmp.deleteRecursively()
                return false
            }
            // 单事务原子换树：旧树改名换出 → 新树换入 → 后台清理旧树；
            // 换入失败把旧树换回来（可回滚），中断不装出半棵树
            if (dest.exists()) {
                val old = File(dest.parentFile, "usr.old")
                old.deleteRecursively()
                if (!dest.renameTo(old)) {
                    appendLog("> 旧树换出失败（跨分区/占用），改用逐文件覆盖安装…")
                    copyRecursively(tmp, dest)
                    // copyTo 不保留权限位，复制后重打 exec（bin/libexec 全树）
                    makeExecutable(File(dest, "bin"))
                    makeExecutable(File(dest, "libexec"))
                    tmp.deleteRecursively()
                } else if (!tmp.renameTo(dest)) {
                    runCatching { old.renameTo(dest) }
                    appendLog("! 新树换入失败，已回滚旧运行时")
                    old.deleteRecursively()
                    return false
                } else {
                    Thread {
                        runCatching { old.deleteRecursively() }
                    }.apply { isDaemon = true; name = "usr-old-cleanup"; start() }
                }
            } else {
            if (!tmp.renameTo(dest)) {
                // Android rename 跨分区/被占用时返回 false：用 overwrite 复制兜底
                appendLog("> 目录重命名失败，改用逐文件复制安装…")
                copyRecursively(tmp, dest)
                // copyTo 不保留权限位，复制后重打 exec（bin/libexec 全树）
                makeExecutable(File(dest, "bin"))
                makeExecutable(File(dest, "libexec"))
                tmp.deleteRecursively()
            }
            }
            if (!TermuxEnv.dshEntry(appContext).exists()) {
                appendLog("! 解压完成但 dsh 入口缺失（dest=${dest.absolutePath}）")
            }
            true
        } catch (e: Exception) {
            appendLog("! 运行时解压失败: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

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
        // 递归修复：java.util.zip 不保留 Unix 权限位，File.copyTo 同样丢权限——
        // 非递归版本只修顶层子项，兜底复制路径下深层的 proot loader 等全部丢 exec
        dir.walkTopDown()
            .onEnter { it.isDirectory && !it.isHidden }
            .filter { it.isFile }
            .forEach { runCatching { it.setExecutable(true, false) } }
    }

    /**
     * 工具链 exec 位自检（服务启动时）：解压/兜底复制不保留权限位的历史树，
     * dsh 入口不可执行时递归修复一次（bin/libexec 全树），已装用户无需重装运行时。
     */
    private fun ensureToolchainExec(ctx: android.content.Context) {
        val entry = TermuxEnv.dshEntry(ctx)
        if (!entry.exists()) return
        if (entry.canExecute()) return
        runCatching {
            makeExecutable(File(TermuxEnv.prefix(ctx), "bin"))
            makeExecutable(File(TermuxEnv.prefix(ctx), "libexec"))
            makeExecutable(File(TermuxEnv.prefix(ctx), "lib"))
        }.onSuccess {
            appendLog(if (entry.canExecute()) "> 工具链 exec 位已修复（dsh 入口恢复可执行）" else "! exec 位修复后仍不可执行，建议重装运行时")
        }
    }

    /** 容错递归复制：单文件失败（如仍被占用）跳过并继续，不中断整体安装。 */
    private fun copyRecursively(src: File, dst: File) {
        if (src.isDirectory) {
            dst.mkdirs()
            src.listFiles()?.forEach { copyRecursively(it, File(dst, it.name)) }
        } else {
            try {
                src.copyTo(dst, overwrite = true)
            } catch (e: Exception) {
                appendLog("! 复制失败跳过: ${src.name} (${e.javaClass.simpleName})")
            }
        }
    }

    /**
     * 旧实例残留清扫：遍历 /proc 杀掉所有持有 libnode dsh 命令行的孤儿进程
     * （旧 app 实例的服务进程，占住 3080 端口导致新实例 EADDRINUSE）。
     * 本进程持有的 serverProcess 不受影响（其 PID 不在遍历目标的 cmdline 判定外）。
     */
    private fun killStaleNodeServers() {
        var killed = 0
        runCatching {
            File("/proc").listFiles()?.forEach { procDir ->
                val pid = procDir.name.toIntOrNull() ?: return@forEach
                if (pid == android.os.Process.myPid()) return@forEach
                val cmdline = runCatching { File(procDir, "cmdline").readText().replace('\u0000', ' ') }
                    .getOrDefault("")
                if (cmdline.contains("libnode.so") && cmdline.contains("bin.js")) {
                    runCatching {
                        android.system.Os.kill(pid, android.system.OsConstants.SIGKILL)
                        killed++
                    }
                }
            }
        }
        if (killed > 0) {
            appendLog("> 清扫了 $killed 个旧实例残留的 dsh 进程（端口占用）")
        }
    }

    // ------------------------------------------------------------------ 服务启动

    internal suspend fun startServerIfNeeded() {
        if (_state.value.phase == ServerPhase.RUNNING) return
        startServer()
        if (_state.value.phase == ServerPhase.RUNNING) return
        waitForReady()
    }

    fun startServer() {
        // 防重入：进程已存活或已 RUNNING 时跳过。STARTING 只是下载完成后的过渡阶段
        // （downloadAndInstall 结束时置 STARTING），此时进程尚未启动，必须继续拉起；
        // 旧守卫按 phase 拦截 STARTING 会导致全新安装后 startServer 直接 return，
        // waitForReady 读到 serverProcess==null 而误报「服务进程已退出（exit=?）」
        if (serverProcess?.isAlive == true || _state.value.phase == ServerPhase.RUNNING) return
        val ctx = appContext
        userStopRequested = false
        TermuxEnv.home(ctx).mkdirs()
        TermuxEnv.tmp(ctx).mkdirs()
        TermuxEnv.dshHome(ctx).mkdirs()
        TermuxEnv.workspace(ctx).mkdirs()
        TermuxEnv.logs(ctx).mkdirs()
        TermuxEnv.ensureBinLinks(ctx)
        ensureToolchainExec(ctx)
        // 子系统路由快照自检：把各判定门的状态落到 server.log，ARGV 为空时
        // 可直接定位是哪个门（rootShell/shell 开关/安装态/UML 可用性）
        runCatching {
            val gates = "engine=${AppSettings.subsystemEngine(ctx)} " +
                "rootShellToggle=${AppSettings.rootShellEnabled(ctx)} su=${RootManager.suPath() != null} " +
                "shellEnabled=${AppSettings.subsystemShellEnabled(ctx)} debian=${SubsystemManager.isInstalled(ctx)} " +
                "umlImg=${SubsystemManager.isUmlInstalled(ctx)} umlAvail=${SubsystemManager.umlAvailable(ctx)} " +
                "argv=${if (TermuxEnv.subsystemArgvJson(ctx) == null) "null(未启用)" else "set"}"
            appendLog("> 子系统路由快照: $gates")
        }

        val port = _state.value.port
        val node = TermuxEnv.nodeBin(ctx)
        val entry = TermuxEnv.dshEntry(ctx)
        val logFile = TermuxEnv.serverLog(ctx)

        // 旧实例残留清扫：上一次 dsh 进程可能存活并占用端口（EADDRINUSE——
        // 启动诊断日志已证实），新实例起不来，WebUI 由旧进程服务（旧 env、
        // ARGV 空快照，一切修复对新会话无效）。杀掉所有非本进程持有的 dsh 进程
        killStaleNodeServers()
        // resolv.conf 直写 rootfs（每次启动刷新）：tawcroot file-over-file bind 失败，
        // 不 bind 直接写——guest DNS 就绪是 pnpm（registry 解析）等网络功能的前提
        SubsystemManager.writeResolvConf()
        // rootfs 工具链幂等补装（node/python3/git/rg）：预装只在子系统安装时跑，
        // 存量子系统（旧版安装）没有 git/python3/rg——git spec 兼容插件装配必失败。
        // 幂等（已在即跳过），后台执行不阻塞启动
        scope.launch(Dispatchers.IO) {
            runCatching { AddonManager.ensureRootfsTools() }
        }

        // 服务启动即清日志：重启后旧内容没有参考价值（安装链的完整历史在
        // subsystem.log/uml.log，环境页可查），混在一起反而难读
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

        writeWorkspaceAgentsNote(ctx)

        // --no-open：WebUI 由系统浏览器打开，避免 dsh 在 Android 上尝试拉起默认浏览器。
        // 0.2.0 起的 dsh 才支持该选项；0.1.x 会报 unknown option 导致服务起不来，
        // 因此按已安装运行时版本条件传参。
        val command = mutableListOf(
            node.absolutePath,
            "--expose-internals",
            entry.absolutePath,
            "web",
            "--port",
            port.toString(),
        )
        if (supportsWebNoOpen()) command.add("--no-open")
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

    /** 将 node 进程的 stdout/stderr 逐行转发到 server.log（LogStore 内存缓冲 + 批量落盘）。 */
    private fun forwardProcessOutput(proc: Process?, logFile: File) {
        if (proc == null) return
        val log = LogStore.named(logFile)
        bootEntriesFailed = false
        scope.launch {
            val reader = proc.inputStream.bufferedReader()
            try {
                for (line in reader.lineSequence()) {
                    log.append(line)
                    // 0.2.0 起 dsh web 就绪行携带 ?token=，提取后供打开 WebUI 使用
                    extractToken(line)?.let { token ->
                        _state.update { it.copy(authToken = token) }
                    }
                    // 坏插件捕获：loader 导入失败行携带包名，进程退出后隔离重启
                    BROKEN_PLUGIN.find(line)?.groupValues?.get(1)?.let { pkg ->
                        brokenPlugin = pkg
                    }
                    // web boot 未激活行：仅在确认 boot 失败后捕获 pending 插件名
                    if (line.contains("did not activate")) {
                        bootEntriesFailed = true
                    } else if (bootEntriesFailed) {
                        PENDING_PLUGIN.find(line.trim())?.groupValues?.get(1)?.let { pkg ->
                            brokenPlugin = pkg
                        }
                    }
                }
            } catch (_: Exception) {
                // 进程被销毁时读流可能中断，属预期
            } finally {
                log.flushForExit()
                runCatching { reader.close() }
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
        val log = LogStore.named(logFile)
        log.clear()
        for (line in content.split("\n")) {
            log.append(line)
        }
    }

    /**
     * 工作区性能引导（AGENTS.md，仅缺失时创建——已有的用户内容不动）：
     * dsh agent 读工作区根的 AGENTS.md。workspace 映射到 sdcard FUSE
     * （/storage/emulated/0/DSHM，顺序写 ~850MB/s），rootfs 内路径走 fscrypt
     * （/tmp、/root/dsh，~1.5GB/s+）——重 I/O（构建/解压/大文件）引导到 rootfs 内
     * 路径执行，AI 重负载任务 I/O 提速近一倍。
     */
    private fun writeWorkspaceAgentsNote(ctx: android.content.Context) {
        val ws = TermuxEnv.workspace(ctx)
        if (!ws.exists() || !ws.canWrite()) return
        val note = File(ws, "AGENTS.md")
        if (note.exists()) return
        runCatching {
            note.writeText(
                """
                # DSHM 运行环境说明

                - 本工作区（/workspace）映射到 sdcard FUSE 存储，顺序写 ~850MB/s。
                - 重 I/O 任务（构建、解压、大文件生成、基准测试的产物）请写到 /tmp 或 /root/dsh（rootfs 内 fscrypt 存储，~1.5GB/s+），完成后把最终产物移回工作区。
                - 常用工具已预装：node（/opt/node/bin）、python3、git、rg；会话 PATH 已指向 Debian rootfs。
                - 持久会话内命令复用同一 bash 进程（无进程创建开销），长任务优先在单个会话内完成。
                """.trimIndent() + "\n",
            )
        }
    }

    private fun appendLog(line: String) {
        LogStore.named(TermuxEnv.serverLog(appContext)).append(line)
    }

    /** 清空服务日志文件（运行时全新安装时调用；服务启动改为超 2MB 轮转，保留装配历史）。 */
    private fun clearLog() {
        LogStore.named(TermuxEnv.serverLog(appContext)).clear()
    }

    private fun processPid(p: Process?): Long? {
        if (p != null) {
            // 1. Java 9+ Process.pid()（部分 Android 实现可用）
            runCatching { (p.javaClass.getMethod("pid").invoke(p) as Number).toLong() }
                .getOrNull()?.let { return it }
            // 2. 反射底层 pid 字段（Android ProcessImpl/JavaProcess）
            runCatching {
                p.javaClass.declaredFields.firstOrNull { it.name == "pid" }?.let { f ->
                    f.isAccessible = true
                    (f.get(p) as Number).toLong()
                }
            }.getOrNull()?.let { return it }
            // 3. /proc 按 cmdline + 父进程匹配
            findChildProcess("node")?.let { return it }
        }
        // 4. serverProcess 引用丢失（应用重启后旧 node 进程仍存活）时，通过监听端口反查 PID
        return findPidByPort(_state.value.port)
    }

    /** 扫描 /proc 下父进程为当前进程、cmdline 含关键字的子进程 PID。 */
    private fun findChildProcess(keyword: String): Long? {
        val myPid = android.os.Process.myPid()
        return runCatching {
            File("/proc").listFiles()?.firstNotNullOfOrNull { dir ->
                val pid = dir.name.toLongOrNull() ?: return@firstNotNullOfOrNull null
                // stat 第 4 字段为 ppid（')' 后第 2 个）
                val ppid = runCatching { File(dir, "stat").readText() }.getOrNull()?.let { stat ->
                    val idx = stat.lastIndexOf(')')
                    if (idx >= 0) stat.substring(idx + 1).trim().split(' ').getOrNull(1)?.toLongOrNull() else null
                }
                if (ppid != myPid.toLong()) return@firstNotNullOfOrNull null
                val cmdline = runCatching { File(dir, "cmdline").readText() }.getOrNull() ?: ""
                if (cmdline.contains(keyword)) pid else null
            }
        }.getOrNull()
    }

    /** 读取已安装运行时的版本（本地，离线可用）。 */
    private fun readRuntimeVersion(): String? {
        // 优先读 runtime 构建写入的版本标记（0.2.0-rc.2-r2 起随 zip 分发），
        // 其次读上次下载时持久化的 metadata 版本（旧 r2 运行时无标记文件，
        // 回退到 dsh 包 package.json 会显示误导性的 0.2.0-rc.2）
        runCatching {
            val marker = File(TermuxEnv.prefix(appContext), "runtime-version")
            if (marker.exists()) {
                marker.readText().trim().takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        runCatching {
            AppSettings.runtimeVersion(appContext)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return runCatching {
            val pkg = File(
                TermuxEnv.prefix(appContext),
                "lib/node_modules/@deepseek-ai/dsh/package.json",
            )
            JSONObject(pkg.readText()).optString("version").ifBlank { null }
        }.getOrNull()
    }

    /** 应用期望的运行时版本（编译期内置，与 runtime 构建的 DSH_VERSION 一致）。 */
    fun expectedRuntimeVersion(): String = BuildConfig.RUNTIME_VERSION

    /**
     * 检查运行时是否需要更新：仅当应用期望运行时版本 ≠ 已安装版本时标记。
     * 纯应用升级（期望版本未变）时保留现有运行时，不触发检查更新。
     */
    fun checkRuntimeUpdate() {
        val installed = readRuntimeVersion() ?: return
        val expected = BuildConfig.RUNTIME_VERSION
        if (expected.isBlank()) return
        _runtimeUpdateAvailable.value = installed != expected
    }

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
                // 自愈：退出前捕获到坏插件 → 从 bundles 摘除并重启（不占崩溃配额）
                val broken = brokenPlugin
                if (broken != null && quarantineBrokenPlugin(broken)) {
                    appendLog("> 检测到坏插件 $broken（拖垮 dsh web），已从 profile bundles 隔离，重启服务…")
                    brokenPlugin = null
                    stopServer()
                    startServerIfNeeded()
                    return
                }
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
