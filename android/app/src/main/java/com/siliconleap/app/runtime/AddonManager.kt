package com.siliconleap.app.runtime

import android.content.Context
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * 装配进度（UI 实时反馈）：正在装配哪个插件、当前步骤、第几个/共几个。
 */
data class InstallProgress(
    val id: String,
    val step: String,
    val index: Int,
    val total: Int,
)

/**
 * 可选插件装配：
 * - 主适配插件 dsh-web-mobile（PiUI 翻页器：半开侧边栏页 + 全宽聊天页、聊天卡片
 *   漏半屏，融合自 mexiaosqwq/dsh-web-mobile 与 lehhair/dsh-mobile 的翻页器结构，
 *   由本仓库发布）。
 * - 兼容插件：dshmarket / dsh-usage-stats / dsh-genui / dsh-infinite-gen-4 / dsh-purge
 *   （dsh-web-mobile README 推荐，从 npm tarball / git 装配）。
 *
 * 装配经 `dsh plugin --profile web add <tgz|git>`（需 pnpm 随运行时内置）。
 * pnpm 11 对被忽略的构建脚本（cloudflared/ssh2 等）返回非 0 退出码，
 * 需在 profile 的 pnpm-workspace.yaml 置 strictDepBuilds: false。
 * best effort：单个插件失败不阻塞服务，下次启动重试（按 marker 跟踪）。
 */
object AddonManager {
    // 主适配插件：dsh-web-mobile（PiUI 翻页器，peer 适配 0.2.0-rc）
    private const val MAIN_ID = "dsh-web-mobile"
    private const val MAIN_TGZ_NAME = "dsh-web-mobile-3.0.5.tgz"
    private const val MAIN_TGZ_BASE = "https://github.com/RochelimitDawn/DSHM/releases/download/dsh-plugin-builds"
    /** 主插件的 remove 包名（dsh plugin remove 按包名卸载）。 */
    private const val MAIN_PKG = "dsh-web-mobile"

    /** 兼容插件清单（id / npm tarball / git spec / 自定义 tarball）。 */
    private data class CompatPlugin(
        val id: String,
        /** npm 完整包名（scoped 也含 @scope/ 前缀）。 */
        val npmPkg: String? = null,
        /** npm registry tarball 文件名。 */
        val tgzName: String? = null,
        /** git spec（如 github:org/repo）；git 插件无需 tarball。 */
        val gitSpec: String? = null,
        /** 自定义 tarball 地址（本仓库 release 资产，npm registry 之外的来源）。 */
        val customTgzUrl: String? = null,
    ) {
        /** tarball 下载地址：npmmirror 镜像优先（国内可达），npmjs 直连回退，其次本仓库 release 资产。 */
        val tgzUrls: List<String>
            get() = when {
                npmPkg != null && tgzName != null -> listOf(
                    "https://registry.npmmirror.com/$npmPkg/-/$tgzName",
                    "https://registry.npmjs.org/$npmPkg/-/$tgzName",
                )
                customTgzUrl != null -> listOf(customTgzUrl)
                else -> emptyList()
            }

        /** npm registry tarball 地址；git 插件返回 null。 */
        val npmTgzUrl: String?
            get() = if (npmPkg != null && tgzName != null) {
                "https://registry.npmjs.org/$npmPkg/-/$tgzName"
            } else {
                null
            }

        /**
         * git 插件优先走 GitHub tarball（android 运行时无 git 二进制，
         * pnpm 对 github: spec fork git 会 ENOENT → dsh 误报 npm/corepack 缺失）。
         * archive/HEAD 免查分支，codeload 直连兜底。
         */
        val gitTarballUrls: List<String>
            get() {
                val spec = gitSpec ?: return emptyList()
                val path = spec.removePrefix("github:")
                return listOf(
                    "https://github.com/$path/archive/HEAD.tar.gz",
                    "https://codeload.github.com/$path/tar.gz/HEAD",
                )
            }

        /** git 插件的 tarball 缓存文件名。 */
        val gitTgzName: String?
            get() = if (gitSpec != null) "$id-HEAD.tgz" else null

        /** remove 包名：npm 包用完整包名；git spec 取 repo 名。 */
        val removePkg: String
            get() = npmPkg ?: gitSpec?.substringAfterLast('/') ?: id
    }

    private val COMPAT_PLUGINS = listOf(
        // dsh-web-ui-all 已移除：拉进的子插件（dsh-better-sidebar、@morlay/session-*）
        // peer 依赖要求 dsh ^0.1.x-rc.x，与 0.2.0-rc.2 运行时不兼容，装配必被拒并回滚
        CompatPlugin("dshmarket", npmPkg = "dshmarket", tgzName = "dshmarket-1.66.8.tgz"),
        CompatPlugin("dsh-usage-stats", npmPkg = "dsh-usage-stats", tgzName = "dsh-usage-stats-0.1.16.tgz"),
        // dsh-genui：npm 发布版（GitHub 源码 archive 不含构建产物 lib/，直装必 import 失败）
        CompatPlugin("dsh-genui", npmPkg = "@changfenhuang/dsh-genui", tgzName = "dsh-genui-0.11.3.tgz"),
        // dsh-infinite-gen-4 / dsh-purge：源码 archive（入口文件由上游提交，本地端到端
        // 验证装配与 import 通过），t经本仓库 dsh-plugin-builds release 分发（稳定 + 加速）
        CompatPlugin(
            "dsh-infinite-gen-4",
            tgzName = "dsh-infinite-gen-4.tgz",
            customTgzUrl = "https://github.com/RochelimitDawn/DSHM/releases/download/dsh-plugin-builds/dsh-infinite-gen-4.tgz",
        ),
        CompatPlugin(
            "dsh-purge",
            tgzName = "dsh-purge.tgz",
            customTgzUrl = "https://github.com/RochelimitDawn/DSHM/releases/download/dsh-plugin-builds/dsh-purge.tgz",
        ),
    )

    private lateinit var appContext: Context

    /** 失败重试退避：装配失败后 6h 内跳过重试，避免每次打开应用都重复发起超时的装配尝试。 */
    private val BACKOFF_MS = 6 * 60 * 60 * 1000L

    private val _installProgress = MutableStateFlow<InstallProgress?>(null)
    val installProgress: StateFlow<InstallProgress?> = _installProgress.asStateFlow()

    /** 插件装配结果明细（单插件），供通知逐行列出。 */
    data class PluginResult(val id: String, val ok: Boolean)

    /** 插件装配完成事件（逐插件结果），供系统通知等消费；装配发起时才 emit。 */
    data class PluginInstallSummary(val results: List<PluginResult>) {
        val installedCount: Int get() = results.count { it.ok }
        val failedCount: Int get() = results.count { !it.ok }
    }

    private val _installEvents = MutableSharedFlow<PluginInstallSummary>(extraBufferCapacity = 8)
    val installEvents: SharedFlow<PluginInstallSummary> = _installEvents.asSharedFlow()

    private fun progress(id: String, step: String, index: Int, total: Int) {
        _installProgress.value = InstallProgress(id, step, index, total)
    }

    fun attach(context: Context) {
        if (!::appContext.isInitialized) appContext = context.applicationContext
    }

    private fun markerFile(id: String): File = File(TermuxEnv.dshHome(appContext), ".siliconleap-$id")

    private fun failMarker(id: String): File = File(TermuxEnv.dshHome(appContext), ".siliconleap-fail-$id")

    private fun incompatMarker(id: String): File = File(TermuxEnv.dshHome(appContext), ".siliconleap-incompat-$id")

    /** 插件是否因 peer 依赖不兼容被运行时拒绝（展示「不兼容」而非「失败」）。 */
    fun isIncompatible(id: String): Boolean = runCatching {
        incompatMarker(id).exists()
    }.getOrDefault(false)

    /** 装配输出里出现运行时的不兼容拒绝时标记（装配失败但原因是不兼容）。 */
    private fun markIncompatIfRejected(id: String, out: String) {
        if (out.contains("is incompatible with") || out.contains("installation rejected")) {
            runCatching { incompatMarker(id).writeText(System.currentTimeMillis().toString()) }
        }
    }

    private fun clearIncompat(id: String) {
        runCatching { incompatMarker(id).delete() }
    }

    /** 该插件是否处于失败退避期（失败 marker 存在且未过期）。 */
    private fun inBackoff(id: String): Boolean = runCatching {
        val f = failMarker(id)
        f.exists() && System.currentTimeMillis() - f.lastModified() < BACKOFF_MS
    }.getOrDefault(false)

    /** 插件页展示用：装配失败且仍在 6h 退避期内。 */
    fun isFailed(id: String): Boolean = inBackoff(id)

    private fun recordFailure(id: String) {
        runCatching { failMarker(id).writeText(System.currentTimeMillis().toString()) }
        clearIncompat(id)
    }

    private fun clearFailure(id: String) {
        runCatching { failMarker(id).delete() }
    }

    /** 主适配插件是否已装配（marker 存在且包已落在 profile 工作区 node_modules）。 */
    fun isInstalled(): Boolean =
        runCatching { bundleInstalled(MAIN_PKG) }.getOrDefault(false)

    /** 某兼容插件是否已装配（marker 存在且包已落在 profile 工作区 node_modules）。 */
    fun isCompatInstalled(id: String): Boolean = runCatching {
        val plugin = COMPAT_PLUGINS.firstOrNull { it.id == id } ?: return markerFile(id).exists()
        bundleInstalled(plugin.removePkg)
    }.getOrDefault(false)

    /**
     * bundle 包是否已落在 profile 工作区：旧版装配进程 cwd 固定在 rootfs 根，
     * pnpm 把包装进了错误目录（marker 写了、profile node_modules 里没有），
     * dsh 启动解析 bundle 必失败——按包目录判定，缺包自动触发重装（自愈）。
     */
    private fun bundleInstalled(pkg: String): Boolean =
        markerFile(pkg).exists() &&
            File(TermuxEnv.dshHome(appContext), "profiles/web/node_modules/$pkg").exists()

    /** 兼容插件 id 列表（供 UI 展示装配状态）。 */
    val compatPluginIds: List<String> get() = COMPAT_PLUGINS.map { it.id }

    /** 引导时选择的预装插件集合（空 = 全部安装）。 */
    private fun enabledCompatPlugins(): List<CompatPlugin> {
        val selected = AppSettings.preinstallPlugins(appContext)
        return if (selected.isEmpty()) COMPAT_PLUGINS else COMPAT_PLUGINS.filter { it.id in selected }
    }

    /**
     * 启动服务前装配（挂起等待完成；失败不抛出）。
     * @return 是否有插件在本次调用中被新装配（供调用方决定是否重启服务使插件生效）
     */
    suspend fun ensureBlocking(force: Boolean = false): Boolean {
        val enabled = enabledCompatPlugins()
        if (isInstalled() && enabled.all { isCompatInstalled(it.id) }) return false
        return withContext(Dispatchers.IO) {
            runCatching { installAll(force) }.getOrDefault(false)
        }
    }

    /** @return 任一插件被新装配返回 true；node/dsh 缺失或全部已装配返回 false。 */
    private fun installAll(force: Boolean = false): Boolean {
        val node = TermuxEnv.nodeBin(appContext)
        val dsh = TermuxEnv.dshEntry(appContext)
        if (!node.exists() || !dsh.exists()) {
            log("! dsh 或 node 不存在: node=${node.exists()} dsh=${dsh.exists()}")
            return false
        }
        // 就地修复 dsh plugin 的 spawnSync 数组 bug（旧 runtime Patch 14 产物），
        // 修复幂等：修复后不再匹配旧正则，重跑无副作用。
        fixPnpmSpawnBug()
        // store 路径迁移（ERR_PNPM_UNEXPECTED_STORE）：旧装配在 guest 视角（HOME=/root）
        // 装的 profile，.modules.yaml 记录了 /root/.local/share/pnpm/store/vN；现统一用
        // host 绝对路径 store，字符串对不上会被 pnpm 拒绝。检测到旧记录即清理
        // .modules.yaml 与 lock，让 pnpm 按固定 store 重新生成。
        migratePnpmStoreRecord()
        // 迁移旧 guest 别名 file: spec（/siliconleap-downloads → host downloads 路径）
        migrateDownloadSpecs()
        // 冗余钉死 store：profile 内 .npmrc 写 store-dir（即便调用方清空 env，
        // pnpm 仍按 profile 配置解析到同一固定 store，杜绝字符串漂移）
        ensurePnpmStorePin()
        removeLegacyMobile()
        // 修复破损的 *.patch.yml（"[] 占位 + 追加条目" 的非法 YAML），否则 dsh
        // 解析 profile 直接崩（YAMLException），全部装配与 web 启动都挂
        sanitizePatchYaml()
        // git CA 先行：dsh 自身 reconcile profile 依赖也可能 git clone github: 插件，
        // rootfs 无 ca-certificates，任何 https git 传输都验不过（参考 DSH-Folk）
        ensureGitCa()
        // 逐个装+逐个验（参考 DSH-Folk）：此前「全装完一次性验树、失败整批回滚」，
        // 一个坏插件（如 dshmarket 大版本升级后加载崩溃）会把主插件在内全部连坐回滚
        var anyInstalled = false
        var failureCount = 0
        val results = mutableListOf<PluginResult>()
        val total = 1 + enabledCompatPlugins().size
        try {
            // 主插件先装先验：基线树只有主插件，失败即主插件自身问题，不连坐
            if (!isInstalled() && (force || !inBackoff(MAIN_ID))) {
                progress(MAIN_ID, "装配中", 1, total)
                val pkg = installMain(node, dsh)
                when {
                    pkg == null -> { recordFailure(MAIN_ID); failureCount++; results.add(PluginResult(MAIN_ID, false)) }
                    verifyPluginTree(node, dsh, listOf(pkg)) -> {
                        runCatching { markerFile(MAIN_ID).writeText(MAIN_ID) }
                        clearFailure(MAIN_ID)
                        log("> $MAIN_ID 装配成功（已验证）")
                        anyInstalled = true
                        results.add(PluginResult(MAIN_ID, true))
                    }
                    else -> { recordFailure(MAIN_ID); failureCount++; results.add(PluginResult(MAIN_ID, false)) }
                }
            }
            // 主插件 add 会初始化 profile（含 pnpm-workspace.yaml），此后才能修 strictDepBuilds
            ensurePnpmWorkspaceFix()
            // 兼容插件逐个装+验：单个坏插件只回滚自己（verify 失败即卸载该插件），
            // 记失败退避后继续下一个，好插件照常落位
            for (plugin in enabledCompatPlugins()) {
                if (isCompatInstalled(plugin.id) || (!force && inBackoff(plugin.id))) continue
                progress(plugin.id, "装配中", 1 + enabledCompatPlugins().indexOf(plugin) + 1, total)
                val pkg = installCompat(node, dsh, plugin)
                if (pkg == null) {
                    recordFailure(plugin.id)
                    failureCount++
                    results.add(PluginResult(plugin.id, false))
                    continue
                }
                progress(plugin.id, "验证中", 1 + enabledCompatPlugins().indexOf(plugin) + 1, total)
                if (verifyPluginTree(node, dsh, listOf(pkg))) {
                    runCatching { markerFile(plugin.id).writeText(plugin.id) }
                    clearFailure(plugin.id)
                    log("> ${plugin.id} 装配成功（已验证）")
                    anyInstalled = true
                    results.add(PluginResult(plugin.id, true))
                } else {
                    recordFailure(plugin.id)
                    failureCount++
                    results.add(PluginResult(plugin.id, false))
                }
            }
        } finally {
            _installProgress.value = null
        }
        sweepStaleTgz()
        if (results.isNotEmpty()) {
            _installEvents.tryEmit(PluginInstallSummary(results.toList()))
        }
        return anyInstalled
    }

    /** 验证超时：dsh 冷启动可达 30s+，重型插件（如 dsh-web-ui-all 拉约 20 个依赖）
     *  装配后验证更久，留足余量。 */
    private const val VERIFY_TIMEOUT_MS = 120_000L

    /**
     * 装配后验证插件树能否加载：临时端口（避开服务端口 3080）起一次 dsh web，
     * 健康检查就绪即通过；进程提前退出或超时视为失败并卸载本次装配的插件。
     */
    private fun verifyPluginTree(node: File, dsh: File, packages: List<String>): Boolean {
        sanitizePatchYaml()
        val port = 21000 + (0..9999).random()
        // --no-open 仅 0.2.0+ 的 dsh 支持；旧运行时传它会报 unknown option
        val args = mutableListOf(
            node.absolutePath,
            "--expose-internals",
            dsh.absolutePath,
            "web",
            "--port",
            port.toString(),
        )
        if (RuntimeManager.supportsWebNoOpen()) args.add("--no-open")
        val pb = ProcessBuilder(args)
        pb.environment().putAll(TermuxEnv.serverEnv(appContext))
        pb.directory(TermuxEnv.workspace(appContext))
        pb.redirectErrorStream(true)
        val p = try {
            pb.start()
        } catch (e: Exception) {
            log("! 验证进程启动失败: ${e.message}")
            return false
        }
        // 边读边等：不消费 stdout，dsh 输出超过管道缓冲会阻塞写而永不退出
        Thread {
            runCatching { p.inputStream.bufferedReader().use { it.readText() } }
        }.apply { isDaemon = true; start() }
        val started = System.currentTimeMillis()
        var ready = false
        while (System.currentTimeMillis() - started < VERIFY_TIMEOUT_MS) {
            if (!p.isAlive) break
            if (httpAlive(port)) { ready = true; break }
            Thread.sleep(500)
        }
        runCatching { p.destroy() }
        runCatching { p.waitFor(3, TimeUnit.SECONDS) }
        p.destroyForcibly()
        if (ready) {
            log("> 插件树验证通过（临时端口 $port）")
            Thread.sleep(500)
            return true
        }
        val exit = runCatching { p.exitValue() }.getOrNull()
        log("! 插件树验证失败（exit=${exit ?: "?"}），卸载本次装配: $packages")
        for (pkg in packages) {
            runAdd(node, dsh, listOf("remove", pkg))
        }
        return false
    }

    /** 健康检查：拿到任意 HTTP 响应码即说明服务在监听。 */
    private fun httpAlive(port: Int): Boolean = try {
        val conn = URL("http://127.0.0.1:$port/").openConnection() as HttpURLConnection
        conn.connectTimeout = 800
        conn.readTimeout = 800
        val code = conn.responseCode
        conn.disconnect()
        code in 100..599
    } catch (_: Exception) {
        false
    }

    /**
     * 就地修复 dsh plugin 的 spawnSync 数组 bug（旧 runtime Patch 14 产物）：
     * 旧 patch 生成 `spawnSync([node, pnpmCjs], ...)`，file 参数为数组必抛
     * ERR_INVALID_ARG_TYPE，导致全部插件装配失败。此处直接把文件改回正确写法
     * （node 为 file、pnpm.cjs 作首个 arg），使未重建 runtime 也能装配。幂等。
     */
    private fun fixPnpmSpawnBug() {
        runCatching {
            val lib = File(TermuxEnv.prefix(appContext), "lib/node_modules/@deepseek-ai/dsh/lib")
            val files = lib.listFiles()?.filter { it.name.startsWith("plugin-") && it.name.endsWith(".js") }
                ?: return
            for (f in files) {
                val src = runCatching { f.readText() }.getOrNull() ?: continue
                // 匹配旧 Patch 14 产物（数组 bug）；正确产物不匹配，天然幂等
                val re = Regex(
                    """const _siliconleapPnpm = process\.env\.PNPM_NODE && process\.env\.PNPM_CJS[\s\S]*?args\.map\(\(argument\) => anchorPathSpec\(argument, process\.cwd\(\)\)\), \{""",
                )
                if (!re.containsMatchIn(src)) continue
                val fixed = src.replace(
                    re,
                    """const _siliconleapNode = process.env.PNPM_NODE;
const _siliconleapPnpm = process.env.PNPM_CJS;
const _mapped = args.map((argument) => anchorPathSpec(argument, process.cwd()));
const result = spawnSync(_siliconleapNode || "pnpm", _siliconleapNode && _siliconleapPnpm ? [_siliconleapPnpm, ..._mapped] : _mapped, {""",
                )
                runCatching { f.writeText(fixed) }
                log("> 已就地修复 dsh plugin spawnSync 数组 bug: ${f.name}")
            }
        }
    }

    /**
     * 修复 profiles/web 下的 .patch.yml 的 YAML 破损：dsh 建 patch 清单时先写 "[]" 占位，
     * 装配时补写条目会追加在 "[]" 之后，产生 "[] + 注释 + 列表" 的非法文档，
     * dsh 解析（YAMLException: end of the stream or a document separator is expected）
     * 直接崩，主插件+全部插件装配与 web 启动全挂。修复：首个非注释行是 "[]" 且
     * 其后还有内容时去掉占位行（注释与列表保留，重新成为合法清单）。幂等。
     */
    private fun sanitizePatchYaml() {
        runCatching {
            val dir = File(TermuxEnv.dshHome(appContext), "profiles/web")
            dir.listFiles()?.forEach { f ->
                if (!f.isFile || !f.name.endsWith(".patch.yml")) return@forEach
                val lines = runCatching { f.readText().lines() }.getOrNull() ?: return@forEach
                val first = lines.indexOfFirst { it.isNotBlank() && !it.trimStart().startsWith("#") }
                if (first < 0 || lines[first].trim() != "[]") return@forEach
                if (lines.drop(first + 1).none { it.isNotBlank() }) return@forEach
                f.writeText(lines.drop(first + 1).joinToString("\n"))
                log("> 已修复破损的 patch 清单: ${f.name}")
            }
        }
    }

    /** 迁移：卸载旧 dsh-mobile（lehhair）/ dsh-mobile-nav，避免与 dsh-web-mobile 双重适配。 */
    private fun removeLegacyMobile() {
        runCatching {
            val manifest = File(TermuxEnv.dshHome(appContext), "profiles/web/package.json")
            if (!manifest.exists()) return
            val text = manifest.readText()
            val node = TermuxEnv.nodeBin(appContext)
            val dsh = TermuxEnv.dshEntry(appContext)
            if (text.contains("@dsh-external/dsh-mobile")) {
                log("> 移除旧 dsh-mobile 插件…")
                if (node.exists() && dsh.exists()) {
                    runAdd(node, dsh, listOf("remove", "@dsh-external/dsh-mobile"))
                }
                runCatching { File(TermuxEnv.dshHome(appContext), ".siliconleap-dsh-mobile").delete() }
            }
            // dsh-mobile-nav 已被 dsh-web-mobile（含 Pi UI 翻页器）替代
            if (text.contains("@dsh-external/dsh-mobile-nav")) {
                log("> 移除旧 dsh-mobile-nav 插件…")
                if (node.exists() && dsh.exists()) {
                    runAdd(node, dsh, listOf("remove", "@dsh-external/dsh-mobile-nav"))
                }
                runCatching { File(TermuxEnv.dshHome(appContext), ".siliconleap-dsh-mobile-nav").delete() }
            }
        }
    }

    /** @return 装配成功的 remove 包名；失败返回 null（marker 由调用方在验证通过后写入）。 */
    private fun installMain(node: File, dsh: File): String? {
        if (isInstalled()) return null
        // 按实际生效的下载源给 GitHub tgz 加 GHProxy 前缀
        val base = MAIN_TGZ_BASE + "/" + MAIN_TGZ_NAME
        val url = when (SourceManager.resolve(appContext)) {
            AppSettings.SOURCE_GHPROXY_CF -> "https://v6.gh-proxy.org/$base"
            AppSettings.SOURCE_GHPROXY_AXISNOW -> "https://axisnow.gh-proxy.org/$base"
            else -> base
        }
        val tgz = File(TermuxEnv.filesDir(appContext), "downloads/$MAIN_TGZ_NAME")
        if (!tgz.exists() || tgz.length() == 0L) {
            log("> 下载 dsh-mobile-nav: $url")
            if (!downloadTgz(url, tgz)) {
                recordFailure(MAIN_ID)
                log("! dsh-mobile-nav 下载失败")
                return null
            }
            log("> dsh-mobile-nav 下载完成（${tgz.length() / 1024} KB）")
        } else {
            log("> 使用已缓存的 dsh-mobile-nav（${tgz.length() / 1024} KB）")
        }
        if (!runAdd(node, dsh, listOf("add", tgz.absolutePath), MAIN_ID)) {
            log("> 本地路径装配失败，回退远程 URL…")
            if (!runAdd(node, dsh, listOf("add", url), MAIN_ID)) {
                recordFailure(MAIN_ID)
                log("! dsh-mobile-nav 装配失败")
                return null
            }
        }
        return MAIN_PKG
    }

    /** @return 装配成功的 remove 包名；失败返回 null（marker 由调用方在验证通过后写入）。 */
    private fun installCompat(node: File, dsh: File, plugin: CompatPlugin): String? {
        val spec: String
        if (plugin.gitSpec != null) {
            // Git 插件优先 tarball 直装（android 无 git 二进制，pnpm fork git ENOENT），
            // 失败回退 git spec（CA 证书 + insteadOf 镜像重写）
            val tgz = File(TermuxEnv.filesDir(appContext), "downloads/${plugin.gitTgzName}")
            if (tgz.exists() && tgz.length() > 0L) {
                if (runAdd(node, dsh, listOf("add", tgz.absolutePath), plugin.id)) return plugin.removePkg
                runCatching { tgz.delete() }
            }
            for (url in plugin.gitTarballUrls) {
                log("> 下载 ${plugin.id} tarball: $url")
                if (!downloadTgz(url, tgz)) continue
                log("> ${plugin.id} tarball 下载完成（${tgz.length() / 1024} KB）")
                if (runAdd(node, dsh, listOf("add", tgz.absolutePath), plugin.id)) return plugin.removePkg
                runCatching { tgz.delete() }
            }
            log("! ${plugin.id} tarball 全部失败，回退 git spec…")
            if (!installGitPlugin(node, dsh, plugin)) {
                recordFailure(plugin.id)
                log("! 兼容插件 ${plugin.id} 装配失败")
                return null
            }
            return plugin.removePkg
        } else {
            val cacheName = plugin.tgzName ?: "${plugin.id}.tgz"
            val tgz = File(TermuxEnv.filesDir(appContext), "downloads/$cacheName")
            if (!tgz.exists() || tgz.length() == 0L) {
                var downloaded = false
                for (url in plugin.tgzUrls) {
                    log("> 下载兼容插件 ${plugin.id}: $url")
                    if (downloadTgz(url, tgz)) {
                        downloaded = true
                        break
                    }
                    log("! ${plugin.id} 下载失败: $url，尝试下一源")
                }
                if (!downloaded) {
                    recordFailure(plugin.id)
                    log("! 兼容插件 ${plugin.id} 下载失败（全部源）")
                    return null
                }
                log("> 兼容插件 ${plugin.id} 下载完成（${tgz.length() / 1024} KB）")
            }
            spec = tgz.absolutePath
        }
        if (!runAdd(node, dsh, listOf("add", spec), plugin.id)) {
            recordFailure(plugin.id)
            log("! 兼容插件 ${plugin.id} 装配失败")
            return null
        }
        return plugin.removePkg
    }

    /**
     * Git 装配 spec 按下载源构造：
     * - GitHub 直连：`github:user/repo`（pnpm 原生 git spec）
     * - GHProxy 加速源：`git+https://<proxy>/https://github.com/<user>/<repo>`（Git 协议代理）
     */
    private fun gitProxySpec(gitSpec: String): String {
        if (!gitSpec.startsWith("github:")) return gitSpec
        val path = gitSpec.removePrefix("github:")
        val base = "https://github.com/$path"
        return when (SourceManager.resolve(appContext)) {
            AppSettings.SOURCE_GHPROXY_CF -> "git+https://v6.gh-proxy.org/$base"
            AppSettings.SOURCE_GHPROXY_AXISNOW -> "git+https://axisnow.gh-proxy.org/$base"
            else -> gitSpec
        }
    }

    // ------------------------------------------------------------- git 传输修复
    // proot rootfs 没装 ca-certificates，git 的 https 传输全部验不过
    // （"server certificate verification failed. CAfile: none"）——不管走 gh-proxy
    // 还是直连 github。参考 DSH-Folk：node 导出 tls.rootCertificates 落 pem +
    // git 全局 http.sslCAInfo；镜像重写用 git insteadOf（pnpm 在子进程 fork git，
    // 命令行传不进去，只有 ~/.gitconfig 能被继承）。

    private fun gitCaFile(): File = File(TermuxEnv.dshHome(appContext), ".git-ca.pem")

    /** 容器 env 下的 shell 命令执行（git config / node 导出证书等）。 */
    private fun runShell(cmd: String, timeoutMs: Long): Boolean {
        val pb = ProcessBuilder(listOf(TermuxEnv.binLinks(appContext).absolutePath + "/bash", "-c", cmd))
        pb.environment().putAll(TermuxEnv.serverEnv(appContext))
        pb.redirectErrorStream(true)
        val p = try {
            pb.start()
        } catch (e: Exception) {
            log("! shell 启动失败: ${e.message}")
            return false
        }
        return try {
            p.waitFor(timeoutMs, TimeUnit.MILLISECONDS) && p.exitValue() == 0
        } finally {
            runCatching { p.destroyForcibly() }
        }
    }

    /**
     * 给 git 喂一份 CA 根证书，让它能校验 https（gh-proxy 与 github 都要）。
     * 用容器 node 导出 tls.rootCertificates（与 pnpm 同一套 Mozilla 根证书，不降级安全），
     * 落 pem + 一行全局 git 配置。幂等：pem 已在就只重设配置。
     * HOME（.gitconfig 所在）不在跨运行时更新的保留场景内（运行时更新重建 prefix），
     * dshHome 持久且每次装配/启动前重跑，任何 git 路径都拿得到 CA。
     */
    private fun ensureGitCa() {
        val ca = gitCaFile()
        runCatching { ca.parentFile?.mkdirs() }
        val js = "const fs=require('fs'),tls=require('tls');" +
            "fs.writeFileSync(process.argv[1],tls.rootCertificates.join('\\n')+'\\n')"
        val cmd = "if [ -s '${ca.absolutePath}' ]; then :; else " +
            "node -e \"$js\" '${ca.absolutePath}'; fi; " +
            "git config --global http.sslCAInfo '${ca.absolutePath}'"
        if (!runShell(cmd, 60_000)) log("! git CA 配置失败（git 插件装配可能受影响）")
    }

    /** 给全局 git 配 insteadOf，把 github 流量重写到 [prefix]（空串 = 直连，先清重写）。 */
    private fun applyGitRewrite(prefix: String) {
        clearGitRewrite()
        if (prefix.isEmpty()) return
        val cmds = GIT_REWRITE_BASES.joinToString("; ") { base ->
            "git config --global \"url.$prefix$base.insteadOf\" \"$base\""
        }
        runShell(cmds, 30_000)
    }

    /** 清掉所有 github insteadOf 重写（幂等，换线路后旧键不留）。 */
    private fun clearGitRewrite() {
        val cmds = buildString {
            for (prefix in GIT_PROXY_PREFIXES) {
                if (prefix.isEmpty()) continue
                for (base in GIT_REWRITE_BASES) {
                    append("git config --global --unset-all \"url.$prefix$base.insteadOf\" 2>/dev/null; ")
                }
            }
            append("true")
        }
        runShell(cmds, 30_000)
    }

    /** known 线路前缀（清重写用，含全部代理源；空串=直连不算）。 */
    private val GIT_PROXY_PREFIXES = listOf(
        "https://v6.gh-proxy.org/",
        "https://axisnow.gh-proxy.org/",
        "https://ghproxy.net/",
    )

    /** insteadOf 键里被重写的源（github 的等价写法）。 */
    private val GIT_REWRITE_BASES = listOf(
        "https://github.com/",
        "git+https://github.com/",
    )

    /**
     * 装配 git 插件：CA 先行，按当前下载源线路重写 github 流量，失败回退直连，
     * 装完清掉重写。pnpm 对 `github:` spec 走 git 传输。
     */
    private fun installGitPlugin(node: File, dsh: File, plugin: CompatPlugin): Boolean {
        ensureGitCa()
        val proxy = when (SourceManager.resolve(appContext)) {
            AppSettings.SOURCE_GHPROXY_CF -> "https://v6.gh-proxy.org/"
            AppSettings.SOURCE_GHPROXY_AXISNOW -> "https://axisnow.gh-proxy.org/"
            else -> ""
        }
        val prefixes = if (proxy.isEmpty()) listOf("") else listOf(proxy, "")
        for (prefix in prefixes) {
            applyGitRewrite(prefix)
            if (runAdd(node, dsh, listOf("add", plugin.gitSpec ?: return false), plugin.id)) {
                clearGitRewrite()
                return true
            }
            log("! git 装配失败（线路 ${if (prefix.isEmpty()) "直连" else prefix}）")
        }
        clearGitRewrite()
        return false
    }

    /** 清扫 downloads/ 里清单之外的旧版本 tgz（版本升级/插件下架后残留）。 */
    private fun sweepStaleTgz() {
        runCatching {
            val keep = setOf(MAIN_TGZ_NAME) + COMPAT_PLUGINS.mapNotNull { it.tgzName } +
                COMPAT_PLUGINS.mapNotNull { it.gitTgzName }
            File(TermuxEnv.filesDir(appContext), "downloads").listFiles()?.forEach { f ->
                if (f.isFile && f.name.endsWith(".tgz") && f.name !in keep) f.delete()
            }
        }
    }

    /**
     * pnpm 11 对被忽略的构建脚本（如 cloudflared/ssh2 的 prepare）返回非 0 退出码，
     * 导致 `dsh plugin` 判定失败、bundle 不激活。置 strictDepBuilds: false 让 pnpm
     * 以警告代替失败。仅追加，不覆盖 pnpm 自身写入的内容。
     */
    private fun ensurePnpmWorkspaceFix() {
        runCatching {
            val ws = File(TermuxEnv.dshHome(appContext), "profiles/web/pnpm-workspace.yaml")
            if (!ws.exists()) return
            val text = ws.readText()
            if (!text.contains("strictDepBuilds")) {
                ws.appendText("\nstrictDepBuilds: false\n")
                log("> 已在 pnpm-workspace.yaml 追加 strictDepBuilds: false")
            }
        }
    }

    /**
     * pnpm store 记录迁移（ERR_PNPM_UNEXPECTED_STORE 修复）：
     * 旧版装配在 guest 视角（HOME=/root）执行，profile 的 node_modules/.modules.yaml
     * 记录了 storeDir=/root/.local/share/pnpm/store/vN；现统一用 host 绝对路径 store，
     * pnpm 词法比较两者不等即拒绝安装。检测到残留即删除 .modules.yaml、lock 与
     * 虚拟 store（.pnpm 内同样记录绝对路径），让 pnpm 按固定 store 重新生成
     * （幂等：仅命中旧记录才动）。
     */
    private fun migratePnpmStoreRecord() {
        runCatching {
            val profile = File(TermuxEnv.dshHome(appContext), "profiles/web")
            if (!profile.isDirectory) return
            val modules = File(profile, "node_modules/.modules.yaml")
            val stale = modules.isFile && runCatching {
                modules.readText().contains("/root/.local/share/pnpm/store")
            }.getOrDefault(false)
            if (!stale) return
            runCatching { modules.delete() }
            runCatching { File(profile, "pnpm-lock.yaml").delete() }
            runCatching { File(profile, "node_modules/.pnpm").deleteRecursively() }
            log("> 检测到旧 guest 视角 pnpm store 记录，已清理 .modules.yaml/lock/.pnpm（将按固定 store 重装）")
        }
    }

    /**
     * 迁移旧 guest 别名 `file:` spec：旧装配把 tgz 路径重映射为
     * `file:/siliconleap-downloads/<name>`（guest bind 点）并持久化进 profile
     * manifest/lock；市场在 host 视角重解析该 spec 时别名不存在（ENOENT）。
     * 现统一直写 host 绝对路径（assemblyArgv 以同字符串 bind），迁移旧记录。
     * 幂等：无别名残留则不动。
     */
    private fun migrateDownloadSpecs() {
        runCatching {
            val profile = File(TermuxEnv.dshHome(appContext), "profiles/web")
            if (!profile.isDirectory) return
            val hostDownloads = File(TermuxEnv.filesDir(appContext), "downloads").absolutePath
            var hit = false
            for (name in listOf("package.json", "pnpm-lock.yaml", "pnpm-workspace.yaml")) {
                val f = File(profile, name)
                if (!f.isFile) continue
                val text = runCatching { f.readText() }.getOrNull() ?: continue
                if (!text.contains("/siliconleap-downloads/")) continue
                f.writeText(text.replace("/siliconleap-downloads/", "$hostDownloads/"))
                hit = true
            }
            if (hit) log("> 已将 profile 内旧 /siliconleap-downloads file: spec 迁移为 host 绝对路径")
        }
    }

    /**
     * 把固定 store 与导入策略写进 profile 的 .npmrc：
     *   store-dir=<host 绝对路径>     固定 store 身份（与 env 双保险）
     *   virtual-store-dir=.pnpm        显式声明虚拟 store 位置
     *   package-import-method=copy     跨挂载/FUSE 硬链接不可用，改复制（稳定优先）
     *   node-linker=hoisted            减少对 .pnpm symlink 的依赖，多层路径映射下更稳
     * 幂等：四项均已是目标值则跳过。
     */
    private fun ensurePnpmStorePin() {
        runCatching {
            val profile = File(TermuxEnv.dshHome(appContext), "profiles/web")
            if (!profile.isDirectory) return
            val npmrc = File(profile, ".npmrc")
            val desired = linkedMapOf(
                "store-dir" to TermuxEnv.pnpmStoreDir(appContext).absolutePath,
                "virtual-store-dir" to ".pnpm",
                "package-import-method" to "copy",
                "node-linker" to "hoisted",
            )
            val text = if (npmrc.exists()) npmrc.readText() else ""
            val others = text.lineSequence()
                .filterNot { line -> desired.keys.any { line.trim().startsWith("$it=") } }
                .filter { it.isNotBlank() }
                .toList()
            val body = (others + desired.map { (k, v) -> "$k=$v" }).joinToString("\n") + "\n"
            if (body == text) return
            npmrc.writeText(body)
        }
    }

    /** 执行 `dsh plugin --profile web <args...>`，成功返回 true。 */
    private fun runAdd(node: File, dsh: File, args: List<String>, pluginId: String? = null): Boolean {
        // dsh 每次 add/remove 都会解析 profile 的 patch 清单，破损即全挂
        sanitizePatchYaml()
        // 子系统优先（DSH-Folk 方案）：rootfs 内原生 Linux 工具链（真实 node/pnpm/CA），
        // 返回 null = 子系统不可用或引导失败，回退原生 bionic 路径
        val viaRootfs = runAddInRootfs(args)
        if (viaRootfs != null) return viaRootfs
        val env = TermuxEnv.serverEnv(appContext)
        val pb = ProcessBuilder(
            listOf(node.absolutePath, dsh.absolutePath, "plugin", "--profile", "web") + args,
        )
        // pnpm 安装目标 = 调用目录（dsh plugin 把 cwd 原样交给 pnpm）：
        // 必须在 profile 工作区目录内执行，包才能落进 profiles/web/node_modules——
        // 装错目录时 dsh 启动解析 bundle（向上查 node_modules）必失败
        pb.directory(
            File(TermuxEnv.dshHome(appContext), "profiles/web").apply { mkdirs() },
        )
        pb.environment().putAll(env)
        pb.redirectErrorStream(true)
        val p = try {
            pb.start()
        } catch (e: Exception) {
            log("! 启动 dsh plugin 失败: ${e.message}")
            return false
        }
        // 边读边等：waitFor 期间若不消费 stdout，pnpm 输出超过管道缓冲会阻塞写而永不退出
        val out = StringBuilder()
        val pump = Thread {
            runCatching {
                p.inputStream.bufferedReader().use { r ->
                    var line = r.readLine()
                    while (line != null) {
                        out.append(line).append('\n')
                        if (out.length > 32_000) out.delete(0, 16_000)
                        line = r.readLine()
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        try {
            // 超时 180s：重型插件（dsh-web-ui-all 拉约 20 个依赖）在移动端网络下
            // pnpm 安装远超 90s，超时即装配失败
            val done = p.waitFor(180, TimeUnit.SECONDS)
            pump.join(5_000)
            if (!done) {
                log("! dsh plugin 超时（180s），进程仍在运行\n${out.takeLast(400)}")
                return false
            }
            log("> exit=${p.exitValue()}\n${out.takeLast(400)}")
            val ok = p.exitValue() == 0
            if (!ok && pluginId != null) markIncompatIfRejected(pluginId, out.toString())
            return ok
        } finally {
            runCatching { p.destroyForcibly() }
        }
    }

    private fun downloadTgz(url: String, target: File): Boolean {
        var conn: HttpURLConnection? = null
        var input: InputStream? = null
        var out: OutputStream? = null
        var ok = false
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            if (conn.responseCode !in 200..299) return false
            target.parentFile?.mkdirs()
            input = conn.inputStream
            out = BufferedOutputStream(FileOutputStream(target))
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
            ok = target.length() > 0L
            ok
        } catch (_: Exception) {
            false
        } finally {
            runCatching { input?.close() }
            runCatching { out?.close() }
            runCatching { conn?.disconnect() }
            if (!ok) runCatching { target.delete() }
        }
    }

    // ------------------------------------------------------------- 子系统装配（DSH-Folk 方案）
    // rootfs 内原生 Linux 工具链：真实 node + pnpm + CA，插件装配一条链全通，
    // 绕开 Android 裸环境的 noexec / 无 git / 无 CA 全部限制。失败回退原生路径。

    private const val ROOTFS_NODE_VERSION = "v22.20.0"
    private const val ROOTFS_NODE_TARBALL = "node-runtime.tar.gz"
    private val ROOTFS_NODE_URLS = listOf(
        "https://registry.npmmirror.com/-/binary/node/$ROOTFS_NODE_VERSION/node-$ROOTFS_NODE_VERSION-linux-arm64.tar.gz",
        "https://nodejs.org/dist/$ROOTFS_NODE_VERSION/node-$ROOTFS_NODE_VERSION-linux-arm64.tar.gz",
    )

    /**
     * 子系统装配：rootfs 内执行 `dsh plugin --profile web <args>`。
     * @return true/false = 执行结果；null = 子系统不可用（调用方回退原生路径）。
     */
    private fun runAddInRootfs(args: List<String>): Boolean? {
        if (!SubsystemManager.isInstalled(appContext)) return null
        val dshBin = File(
            TermuxEnv.prefix(appContext),
            "lib/node_modules/@deepseek-ai/dsh/lib/bin.js",
        )
        if (!dshBin.exists()) return null
        if (!ensureRootfsNode()) {
            log("! rootfs node 引导失败，回退原生装配路径")
            return null
        }
        // tgz 的 host 绝对路径在 guest 内以同字符串 bind 可见（assemblyArgv），
        // 无需重映射：pnpm 会把该 host 路径写进 profile manifest，host 视角读时同样有效
        val quoted = args.joinToString(" ") { raw ->
            "'" + raw.replace("'", "'\\''") + "'"
        }
        val cmd = "mkdir -p /root/dsh/profiles/web && cd /root/dsh/profiles/web; " +
            "export DSH_HOME=/root/dsh PATH=/opt/node/bin:\$PATH " +
            "NPM_CONFIG_UPDATE_NOTIFIER=false " +
            "npm_config_registry=https://registry.npmmirror.com " +
            "npm_config_store_dir=${TermuxEnv.pnpmStoreDir(appContext).absolutePath} " +
            "PNPM_NODE=/opt/node/bin/node PNPM_CJS=/opt/node_modules/pnpm/bin/pnpm.cjs; " +
            "node /opt/node_modules/@deepseek-ai/dsh/lib/bin.js plugin --profile web $quoted"
        val argv = TermuxEnv.assemblyArgv(appContext, cmd) ?: return null
        val pb = ProcessBuilder(argv)
        pb.environment().putAll(TermuxEnv.assemblyEnv(appContext))
        // cwd 固定 rootfs 根：继承宿主可变目录会 getcwd() failed（apt/dpkg 连锁失败）
        pb.directory(SubsystemManager.rootfsDir(appContext))
        pb.redirectErrorStream(true)
        val p = try {
            pb.start()
        } catch (e: Exception) {
            log("! 子系统装配进程启动失败: ${e.message}")
            return null
        }
        val out = StringBuilder()
        val pump = Thread {
            runCatching {
                p.inputStream.bufferedReader().use { r ->
                    var line = r.readLine()
                    while (line != null) {
                        out.append(line).append('\n')
                        if (out.length > 32_000) out.delete(0, 16_000)
                        line = r.readLine()
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        return try {
            val done = p.waitFor(180, TimeUnit.SECONDS)
            pump.join(5_000)
            if (!done) {
                log("! 子系统装配超时（180s）\n${out.takeLast(400)}")
                runCatching { p.destroyForcibly() }
                return false
            }
            log("> 子系统装配 exit=${p.exitValue()}\n${out.takeLast(400)}")
            p.exitValue() == 0
        } finally {
            runCatching { p.destroyForcibly() }
        }
    }

    /** 在 rootfs 内执行一条一次性命令（工具链引导用），成功返回 true；失败输出落日志。 */
    private fun runInRootfs(cmd: String, timeoutMs: Long): Boolean {
        val argv = TermuxEnv.assemblyArgv(appContext, cmd) ?: return false
        val pb = ProcessBuilder(argv)
        pb.environment().putAll(TermuxEnv.assemblyEnv(appContext))
        // cwd 固定 rootfs 根：继承宿主可变目录会 getcwd() failed（apt 连锁失败）
        pb.directory(SubsystemManager.rootfsDir(appContext))
        pb.redirectErrorStream(true)
        val p = try {
            pb.start()
        } catch (e: Exception) {
            log("! rootfs 命令启动失败: ${e.message}")
            return false
        }
        val out = StringBuilder()
        val pump = Thread {
            runCatching {
                p.inputStream.bufferedReader().use { r ->
                    var line = r.readLine()
                    while (line != null) {
                        out.append(line).append('\n')
                        if (out.length > 16_000) out.delete(0, 8_000)
                        line = r.readLine()
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        return try {
            val done = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            pump.join(3_000)
            if (!done) {
                log("! rootfs 命令超时（${timeoutMs / 1000}s）\n${out.takeLast(400)}")
                runCatching { p.destroyForcibly() }
                false
            } else {
                if (p.exitValue() != 0) log("! rootfs 命令 exit=${p.exitValue()}\n${out.takeLast(400)}")
                p.exitValue() == 0
            }
        } finally {
            runCatching { p.destroyForcibly() }
        }
    }

    /**
     * rootfs 内 node 引导：npmmirror 镜像 tar.gz（应用侧下载缓存 → rootfs 内解压），
     * 幂等（/opt/node/bin/node 已在即跳过）。CA 用 node 内置 Mozilla 根证书落 pem +
     * git 全局配置（git 插件兜底用）。失败返回 false。
     */
    private fun ensureRootfsNode(): Boolean {
        val rootfs = SubsystemManager.rootfsDir(appContext)
        val nodeBin = File(rootfs, "opt/node/bin/node")
        if (nodeBin.exists()) return true
        val tar = File(TermuxEnv.filesDir(appContext), "downloads/$ROOTFS_NODE_TARBALL")
        if (!tar.exists() || tar.length() == 0L) {
            log("> 下载 rootfs node $ROOTFS_NODE_VERSION…")
            var ok = false
            for (url in ROOTFS_NODE_URLS) {
                if (downloadTgz(url, tar)) {
                    ok = true
                    break
                }
                log("! rootfs node 下载失败: $url，尝试下一镜像")
            }
            if (!ok) {
                log("! rootfs node 下载失败（全部镜像），回退原生装配路径")
                return false
            }
            log("> rootfs node 下载完成（${tar.length() / 1024 / 1024} MB）")
        }
        val ok = runInRootfs(
            "mkdir -p /opt/node && tar -xzf ${TermuxEnv.filesDir(appContext).absolutePath}/downloads/$ROOTFS_NODE_TARBALL " +
                "--strip-components=1 -C /opt/node && /opt/node/bin/node -v",
            120_000,
        )
        if (!ok) {
            log("! rootfs node 解压/校验失败（tar 或 node -v），回退原生装配路径")
            return false
        }
        log("> rootfs node 就绪（$ROOTFS_NODE_VERSION），装配走子系统原生工具链")
        // CA：node 内置 Mozilla 根证书落 pem + git 全局配置（git 插件兜底路径）
        val js = "const fs=require('fs'),tls=require('tls');" +
            "fs.writeFileSync('/opt/dsh-ca.pem',tls.rootCertificates.join('\\n')+'\\n')"
        runInRootfs(
            "/opt/node/bin/node -e \"$js\"; git config --global http.sslCAInfo /opt/dsh-ca.pem || true",
            30_000,
        )
        return nodeBin.exists()
    }

    /**
     * rootfs 工具链预装（子系统安装完成后调用，幂等）：
     * node（/opt/node 引导）+ python3/git/ripgrep（rootfs 内 apt 安装）。
     * AI 会话与插件装配的工具全部就绪，缺哪个装哪个。失败仅记日志（不阻塞）。
     */
    /**
     * rootfs 内 apt/dpkg 暂存与缓存目录脱离易失的 /tmp：
     *   - 子系统 /tmp 是每 spawn 独立的 tmpfs，apt 下载与 dpkg 解包跨进程时暂存目录
     *     会消失（dpkg code 2，实测 5 连败）；
     *   - 会话/装配命令的 TMPDIR 统一指向 rootfs 持久区 /var/tmp；
     *   - Dir::Cache::archives 指到 /var/cache/apt/archives（持久）。
     * 幂等。
     */
    private fun writeAptTmpConfig(rootfs: File) {
        runCatching {
            val conf = File(rootfs, "etc/apt/apt.conf.d/99dsh-tmp")
            conf.parentFile?.mkdirs()
            val body = "Dir::Cache::archives \"/var/cache/apt/archives\";\n"
            if (!conf.exists() || conf.readText() != body) conf.writeText(body)
        }
    }

    internal fun ensureRootfsTools() {
        ensureRootfsNode()
        val rootfs = SubsystemManager.rootfsDir(appContext)
        val missing = listOf("python3", "git", "rg").filter {
            !File(rootfs, "usr/bin/$it").exists()
        }
        if (missing.isEmpty()) return
        log("> 预装 rootfs 工具链（apt）：${missing.joinToString("/")}")
        // dpkg 默认对每个解压文件 fsync，f2fs+fscrypt 栈上单次 ~0.3ms、数千文件秒级放大。
        // 写 dpkg.cfg.d 强制关闭同步 + 排除 docs/man/locale（docker 同款做法，幂等）
        val dpkgCfg = File(rootfs, "etc/dpkg/dpkg.cfg.d/99dsh-fast")
        if (!dpkgCfg.exists()) {
            dpkgCfg.parentFile?.mkdirs()
            dpkgCfg.writeText(
                "force-unsafe-io\n" +
                    "path-exclude=/usr/share/doc/*\n" +
                    "path-exclude=/usr/share/man/*\n" +
                    "path-exclude=/usr/share/locale/*\n",
            )
        }
        // Debian minbase rootfs 无 /var/log/apt——dpkg 必需，缺失直接报错退出
        //（"Directory '/var/log/apt/' missing"）。先建目录再 apt。
        // apt 源国内镜像回退：deb.debian.org 国内慢/超时，失败后切 USTC 镜像重试
        //
        // TMPDIR 必须指向 rootfs 持久区（/var/tmp），不能用 /tmp：子系统 /tmp 是
        // 每个 spawn 独立的 tmpfs 内存盘，apt 下载到 /tmp/apt-dpkg-install-* 后，
        // dpkg 解包阶段若跨 spawn / 实例重建，暂存目录凭空消失 → dpkg code 2
        // "cannot stat pathname .../N-perl-modules...deb"（实测 5 次全败于此）。
        // /var/tmp 在 rootfs 上（未单独挂载），跨 spawn 存活。
        writeAptTmpConfig(rootfs)
        val ok = runInRootfs(
            "export DEBIAN_FRONTEND=noninteractive; " +
                "mkdir -p /var/log/apt /var/cache/apt/archives/partial /var/tmp; " +
                "chmod 1777 /var/tmp; chmod 755 /var/cache/apt; " +
                "TMPDIR=/var/tmp apt-get update -qq && TMPDIR=/var/tmp apt-get install -y --no-install-recommends -qq python3 git ripgrep",
            600_000,
        ) || runInRootfs(
            "export DEBIAN_FRONTEND=noninteractive; " +
                "mkdir -p /var/log/apt /var/cache/apt/archives/partial /var/tmp; " +
                "chmod 1777 /var/tmp; chmod 755 /var/cache/apt; " +
                "sed -i 's|deb.debian.org|mirrors.ustc.edu.cn|g; s|security.debian.org|mirrors.ustc.edu.cn|g' /etc/apt/sources.list; " +
                "TMPDIR=/var/tmp apt-get update -qq && TMPDIR=/var/tmp apt-get install -y --no-install-recommends -qq python3 git ripgrep",
            600_000,
        )
        if (ok) {
            log("> rootfs 工具链就绪（python3/git/rg）")
        } else {
            log("! rootfs 工具链 apt 安装失败（双源均失败），可稍后在会话内手动执行：" +
                "apt-get update && apt-get install -y python3 git ripgrep")
        }
    }

    /** 装配日志写入 logs/addon.log（便于诊断装配失败）。 */
    private fun log(msg: String) {
        val l = LogStore.named(File(TermuxEnv.logs(appContext), "addon.log"))
        l.append(msg)
        // 即时落盘（LogStore 攒 32 行才 flush，低频日志文件 0 字节——AI 诊断必须可见）
        l.flushForExit()
    }
}
