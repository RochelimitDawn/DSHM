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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
 * - 主适配插件 dsh-mobile-nav（PiUI 翻页器 + 全套移动端适配，融合自
 *   mexiaosqwq/dsh-web-mobile 与 lehhair/dsh-mobile 的翻页器，由本仓库发布）。
 * - 兼容插件：dsh-web-ui-all / dshmarket / dsh-usage-stats / dsh-genui
 *   （dsh-web-mobile README 推荐，从 npm tarball / git 装配）。
 *
 * 装配经 `dsh plugin --profile web add <tgz|git>`（需 pnpm 随运行时内置）。
 * pnpm 11 对被忽略的构建脚本（cloudflared/ssh2 等）返回非 0 退出码，
 * 需在 profile 的 pnpm-workspace.yaml 置 strictDepBuilds: false。
 * best effort：单个插件失败不阻塞服务，下次启动重试（按 marker 跟踪）。
 */
object AddonManager {
    // 主适配插件：本仓库发布的融合版（含 PiUI 翻页器）
    private const val MAIN_ID = "dsh-mobile-nav"
    private const val MAIN_TGZ_NAME = "dsh-external-dsh-mobile-nav-1.0.0.tgz"
    private const val MAIN_TGZ_BASE = "https://github.com/RochelimitDawn/DSHM/releases/download/dsh-mobile-nav"
    /** 主插件的 remove 包名（dsh plugin remove 按包名卸载）。 */
    private const val MAIN_PKG = "@dsh-external/dsh-mobile-nav"

    /** 兼容插件清单（id / npm tarball / git spec）。 */
    private data class CompatPlugin(
        val id: String,
        /** npm 完整包名（scoped 也含 @scope/ 前缀）。 */
        val npmPkg: String? = null,
        /** npm registry tarball 文件名。 */
        val tgzName: String? = null,
        /** git spec（如 github:org/repo）；git 插件无需 tarball。 */
        val gitSpec: String? = null,
    ) {
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
        CompatPlugin("dsh-web-ui-all", npmPkg = "@linxin666/dsh-web-ui-all", tgzName = "dsh-web-ui-all-0.3.6.tgz"),
        CompatPlugin("dshmarket", npmPkg = "dshmarket", tgzName = "dshmarket-1.66.8.tgz"),
        CompatPlugin("dsh-usage-stats", npmPkg = "dsh-usage-stats", tgzName = "dsh-usage-stats-0.1.16.tgz"),
        // dsh-genui：npm 发布版（GitHub 源码 archive 不含构建产物 lib/，直装必 import 失败）
        CompatPlugin("dsh-genui", npmPkg = "@changfenhuang/dsh-genui", tgzName = "dsh-genui-0.11.3.tgz"),
        // dsh-infinite-gen-4 / dsh-purge：仓库未发布构建产物（无 lib/、无 npm 版、无
        // release 资产），移动端无构建工具链，自动装配必然失败，已从清单移除，
        // 用户可经 dshmarket 自行处理
    )

    private lateinit var appContext: Context

    /** 失败重试退避：装配失败后 6h 内跳过重试，避免每次打开应用都重复发起超时的装配尝试。 */
    private val BACKOFF_MS = 6 * 60 * 60 * 1000L

    private val _installProgress = MutableStateFlow<InstallProgress?>(null)
    val installProgress: StateFlow<InstallProgress?> = _installProgress.asStateFlow()

    private fun progress(id: String, step: String, index: Int, total: Int) {
        _installProgress.value = InstallProgress(id, step, index, total)
    }

    fun attach(context: Context) {
        if (!::appContext.isInitialized) appContext = context.applicationContext
    }

    private fun markerFile(id: String): File = File(TermuxEnv.dshHome(appContext), ".siliconleap-$id")

    private fun failMarker(id: String): File = File(TermuxEnv.dshHome(appContext), ".siliconleap-fail-$id")

    /** 该插件是否处于失败退避期（失败 marker 存在且未过期）。 */
    private fun inBackoff(id: String): Boolean = runCatching {
        val f = failMarker(id)
        f.exists() && System.currentTimeMillis() - f.lastModified() < BACKOFF_MS
    }.getOrDefault(false)

    /** 插件页展示用：装配失败且仍在 6h 退避期内。 */
    fun isFailed(id: String): Boolean = inBackoff(id)

    private fun recordFailure(id: String) {
        runCatching { failMarker(id).writeText(System.currentTimeMillis().toString()) }
    }

    private fun clearFailure(id: String) {
        runCatching { failMarker(id).delete() }
    }

    /** 主适配插件是否已装配。 */
    fun isInstalled(): Boolean = runCatching { markerFile(MAIN_ID).exists() }.getOrDefault(false)

    /** 某兼容插件是否已装配。 */
    fun isCompatInstalled(id: String): Boolean = runCatching { markerFile(id).exists() }.getOrDefault(false)

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
        val total = 1 + enabledCompatPlugins().size
        try {
            // 主插件先装先验：基线树只有主插件，失败即主插件自身问题，不连坐
            if (!isInstalled() && (force || !inBackoff(MAIN_ID))) {
                progress(MAIN_ID, "装配中", 1, total)
                val pkg = installMain(node, dsh)
                when {
                    pkg == null -> recordFailure(MAIN_ID)
                    verifyPluginTree(node, dsh, listOf(pkg)) -> {
                        runCatching { markerFile(MAIN_ID).writeText(MAIN_ID) }
                        clearFailure(MAIN_ID)
                        log("> $MAIN_ID 装配成功（已验证）")
                        anyInstalled = true
                    }
                    else -> recordFailure(MAIN_ID)
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
                    continue
                }
                progress(plugin.id, "验证中", 1 + enabledCompatPlugins().indexOf(plugin) + 1, total)
                if (verifyPluginTree(node, dsh, listOf(pkg))) {
                    runCatching { markerFile(plugin.id).writeText(plugin.id) }
                    clearFailure(plugin.id)
                    log("> ${plugin.id} 装配成功（已验证）")
                    anyInstalled = true
                } else {
                    recordFailure(plugin.id)
                }
            }
        } finally {
            _installProgress.value = null
        }
        sweepStaleTgz()
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

    /** 迁移：卸载旧 dsh-mobile（lehhair）插件，避免与 dsh-mobile-nav 双重适配。 */
    private fun removeLegacyMobile() {
        runCatching {
            val manifest = File(TermuxEnv.dshHome(appContext), "profiles/web/package.json")
            if (!manifest.exists()) return
            val text = manifest.readText()
            if (text.contains("@dsh-external/dsh-mobile")) {
                log("> 移除旧 dsh-mobile 插件…")
                val node = TermuxEnv.nodeBin(appContext)
                val dsh = TermuxEnv.dshEntry(appContext)
                if (node.exists() && dsh.exists()) {
                    runAdd(node, dsh, listOf("remove", "@dsh-external/dsh-mobile"))
                }
                runCatching { File(TermuxEnv.dshHome(appContext), ".siliconleap-dsh-mobile").delete() }
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
        if (!runAdd(node, dsh, listOf("add", tgz.absolutePath))) {
            log("> 本地路径装配失败，回退远程 URL…")
            if (!runAdd(node, dsh, listOf("add", url))) {
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
                if (runAdd(node, dsh, listOf("add", tgz.absolutePath))) return plugin.removePkg
                runCatching { tgz.delete() }
            }
            for (url in plugin.gitTarballUrls) {
                log("> 下载 ${plugin.id} tarball: $url")
                if (!downloadTgz(url, tgz)) continue
                log("> ${plugin.id} tarball 下载完成（${tgz.length() / 1024} KB）")
                if (runAdd(node, dsh, listOf("add", tgz.absolutePath))) return plugin.removePkg
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
            val url = plugin.npmTgzUrl ?: return null
            val cacheName = plugin.tgzName ?: "${plugin.id}.tgz"
            val tgz = File(TermuxEnv.filesDir(appContext), "downloads/$cacheName")
            if (!tgz.exists() || tgz.length() == 0L) {
                log("> 下载兼容插件 ${plugin.id}: $url")
                if (!downloadTgz(url, tgz)) {
                    recordFailure(plugin.id)
                    log("! 兼容插件 ${plugin.id} 下载失败")
                    return null
                }
                log("> 兼容插件 ${plugin.id} 下载完成（${tgz.length() / 1024} KB）")
            }
            spec = tgz.absolutePath
        }
        if (!runAdd(node, dsh, listOf("add", spec))) {
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
            if (runAdd(node, dsh, listOf("add", plugin.gitSpec ?: return false))) {
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

    /** 执行 `dsh plugin --profile web <args...>`，成功返回 true。 */
    private fun runAdd(node: File, dsh: File, args: List<String>): Boolean {
        // dsh 每次 add/remove 都会解析 profile 的 patch 清单，破损即全挂
        sanitizePatchYaml()
        val env = TermuxEnv.serverEnv(appContext)
        val pb = ProcessBuilder(
            listOf(node.absolutePath, dsh.absolutePath, "plugin", "--profile", "web") + args,
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
            return p.exitValue() == 0
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

    /** 装配日志写入 logs/addon.log（便于诊断装配失败）。 */
    private fun log(msg: String) {
        LogStore.named(File(TermuxEnv.logs(appContext), "addon.log")).append(msg)
    }
}
