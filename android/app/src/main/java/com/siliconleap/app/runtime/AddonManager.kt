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
 * 可选插件装配：DSH WebUI 移动端适配插件（dsh-mobile）。
 * 纯客户端 CSS + 轻量控制器，仅窄屏（≤768px）重排为翻页器，桌面宽度不受影响。
 * 首次启动服务前经 `dsh plugin --profile web add <tgz>` 装配（需 pnpm 随运行时内置）。
 * best effort：装配失败不阻塞服务，下次启动重试。
 */
object AddonManager {
    private const val TGZ_URL =
        "https://github.com/lehhair/dsh-mobile/releases/latest/download/dsh-external-dsh-mobile.tgz"
    private const val MARKER = ".siliconleap-dsh-mobile"

    private lateinit var appContext: Context

    fun attach(context: Context) {
        if (!::appContext.isInitialized) appContext = context.applicationContext
    }

    /** 是否已装配（marker 存在）。 */
    fun isInstalled(): Boolean = runCatching {
        File(TermuxEnv.dshHome(appContext), MARKER).exists()
    }.getOrDefault(false)

    /** 启动服务前装配（挂起等待完成；失败不抛出）。 */
    suspend fun ensureBlocking() {
        if (isInstalled()) return
        withContext(Dispatchers.IO) {
            runCatching { install() }
        }
    }

    private fun install() {
        val node = TermuxEnv.nodeBin(appContext)
        val dsh = TermuxEnv.dshEntry(appContext)
        if (!node.exists() || !dsh.exists()) {
            log("! dsh 或 node 不存在: node=${node.exists()} dsh=${dsh.exists()}")
            return
        }
        // 按下载源给 GitHub tgz 加 GHProxy 前缀
        val url = when (AppSettings.downloadSource(appContext)) {
            AppSettings.SOURCE_GHPROXY_CF -> "https://v6.gh-proxy.org/$TGZ_URL"
            AppSettings.SOURCE_GHPROXY_AXISNOW -> "https://axisnow.gh-proxy.org/$TGZ_URL"
            else -> TGZ_URL
        }
        // tgz 本地缓存：已下载过则跳过下载（网络不佳时显著缩短启动等待）
        val tgz = File(TermuxEnv.filesDir(appContext), "downloads/dsh-mobile.tgz")
        if (!tgz.exists() || tgz.length() == 0L) {
            log("> 下载 dsh-mobile: $url")
            if (!downloadTgz(url, tgz)) {
                log("! dsh-mobile 下载失败")
                return
            }
            log("> dsh-mobile 下载完成（${tgz.length() / 1024} KB）")
        } else {
            log("> 使用已缓存的 dsh-mobile（${tgz.length() / 1024} KB）")
        }
        // 优先本地路径装配（dsh plugin add 接受文件路径）；失败再回退远程 URL
        if (!runAdd(node, dsh, tgz.absolutePath)) {
            log("> 本地路径装配失败，回退远程 URL…")
            if (!runAdd(node, dsh, url)) {
                log("! dsh-mobile 装配失败")
                return
            }
        }
        runCatching { File(TermuxEnv.dshHome(appContext), MARKER).writeText("dsh-mobile") }
        log("> dsh-mobile 装配成功")
    }

    /** 执行 `dsh plugin --profile web add <target>`，成功返回 true。 */
    private fun runAdd(node: File, dsh: File, target: String): Boolean {
        val env = TermuxEnv.serverEnv(appContext)
        val pb = ProcessBuilder(node.absolutePath, dsh.absolutePath, "plugin", "--profile", "web", "add", target)
        pb.environment().putAll(env)
        pb.redirectErrorStream(true)
        val p = try {
            pb.start()
        } catch (e: Exception) {
            log("! 启动 dsh plugin 失败: ${e.message}")
            return false
        }
        try {
            p.waitFor(90, TimeUnit.SECONDS)
            val out = runCatching { p.inputStream.bufferedReader().readText() }.getOrDefault("")
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
