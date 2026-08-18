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
import kotlinx.coroutines.withContext

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
    }

    private val COMPAT_PLUGINS = listOf(
        CompatPlugin("dsh-web-ui-all", npmPkg = "@linxin666/dsh-web-ui-all", tgzName = "dsh-web-ui-all-0.1.20.tgz"),
        CompatPlugin("dshmarket", npmPkg = "dshmarket", tgzName = "dshmarket-1.11.1.tgz"),
        CompatPlugin("dsh-usage-stats", npmPkg = "dsh-usage-stats", tgzName = "dsh-usage-stats-0.1.15.tgz"),
        CompatPlugin("dsh-genui", gitSpec = "github:omdsh-dev/dsh-genui"),
    )

    private lateinit var appContext: Context

    fun attach(context: Context) {
        if (!::appContext.isInitialized) appContext = context.applicationContext
    }

    private fun markerFile(id: String): File = File(TermuxEnv.dshHome(appContext), ".siliconleap-$id")

    /** 主适配插件是否已装配。 */
    fun isInstalled(): Boolean = runCatching { markerFile(MAIN_ID).exists() }.getOrDefault(false)

    /** 某兼容插件是否已装配。 */
    fun isCompatInstalled(id: String): Boolean = runCatching { markerFile(id).exists() }.getOrDefault(false)

    /** 兼容插件 id 列表（供 UI 展示装配状态）。 */
    val compatPluginIds: List<String> get() = COMPAT_PLUGINS.map { it.id }

    /** 启动服务前装配（挂起等待完成；失败不抛出）。 */
    suspend fun ensureBlocking() {
        if (isInstalled() && COMPAT_PLUGINS.all { isCompatInstalled(it.id) }) return
        withContext(Dispatchers.IO) {
            runCatching { installAll() }
        }
    }

    private fun installAll() {
        val node = TermuxEnv.nodeBin(appContext)
        val dsh = TermuxEnv.dshEntry(appContext)
        if (!node.exists() || !dsh.exists()) {
            log("! dsh 或 node 不存在: node=${node.exists()} dsh=${dsh.exists()}")
            return
        }
        // 就地修复 dsh plugin 的 spawnSync 数组 bug（旧 runtime Patch 14 产物），
        // 修复幂等：修复后不再匹配旧正则，重跑无副作用。
        fixPnpmSpawnBug()
        removeLegacyMobile()
        installMain(node, dsh)
        // 主插件 add 会初始化 profile（含 pnpm-workspace.yaml），此后才能修 strictDepBuilds
        ensurePnpmWorkspaceFix()
        for (plugin in COMPAT_PLUGINS) {
            if (!isCompatInstalled(plugin.id)) installCompat(node, dsh, plugin)
        }
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

    private fun installMain(node: File, dsh: File) {
        if (isInstalled()) return
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
                log("! dsh-mobile-nav 下载失败")
                return
            }
            log("> dsh-mobile-nav 下载完成（${tgz.length() / 1024} KB）")
        } else {
            log("> 使用已缓存的 dsh-mobile-nav（${tgz.length() / 1024} KB）")
        }
        if (!runAdd(node, dsh, listOf("add", tgz.absolutePath))) {
            log("> 本地路径装配失败，回退远程 URL…")
            if (!runAdd(node, dsh, listOf("add", url))) {
                log("! dsh-mobile-nav 装配失败")
                return
            }
        }
        runCatching { markerFile(MAIN_ID).writeText("dsh-mobile-nav") }
        log("> dsh-mobile-nav 装配成功")
    }

    private fun installCompat(node: File, dsh: File, plugin: CompatPlugin) {
        val spec: String
        if (plugin.gitSpec != null) {
            // Git 装配（如 dsh-genui）跟随下载源：GitHub 直连或 GHProxy 加速源代理
            spec = gitProxySpec(plugin.gitSpec)
        } else {
            val url = plugin.npmTgzUrl ?: return
            val tgz = File(TermuxEnv.filesDir(appContext), "downloads/${plugin.id}.tgz")
            if (!tgz.exists() || tgz.length() == 0L) {
                log("> 下载兼容插件 ${plugin.id}: $url")
                if (!downloadTgz(url, tgz)) {
                    log("! 兼容插件 ${plugin.id} 下载失败")
                    return
                }
                log("> 兼容插件 ${plugin.id} 下载完成（${tgz.length() / 1024} KB）")
            }
            spec = tgz.absolutePath
        }
        if (!runAdd(node, dsh, listOf("add", spec))) {
            log("! 兼容插件 ${plugin.id} 装配失败")
            return
        }
        runCatching { markerFile(plugin.id).writeText(plugin.id) }
        log("> 兼容插件 ${plugin.id} 装配成功")
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
            val done = p.waitFor(90, TimeUnit.SECONDS)
            pump.join(5_000)
            if (!done) {
                log("! dsh plugin 超时（90s），进程仍在运行\n${out.takeLast(400)}")
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
