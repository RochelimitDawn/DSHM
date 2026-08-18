package com.siliconleap.app.runtime

import android.content.Context
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * 下载源自动测速与解析。
 *
 * - `resolve()` 返回实际生效的下载源：
 *   - 用户选择固定源（github / axisnow / cf / custom）→ 原样返回；
 *   - 用户选择 auto → 测速选优：先并行测延迟（metadata.json 小文件），
 *     再对延迟最优的前 2 个源做真实下载测速（HTTP Range 拉 runtime.zip 前
 *     SPEED_PROBE_BYTES），按「估算下载 100MB 耗时」综合评分（速度为主、
 *     延迟为 tiebreak），选最优源。全部失败回退默认 AxisNow。
 * - 测速结果缓存：内存 + SharedPreferences（带时间戳，CACHE_TTL 内复用）。
 * - `speedTest()` 供设置页手动触发，返回各源指标供 UI 展示。
 *
 * 各模块（runtime / addon / subsystem / update）统一经本对象解析实际源。
 */
object SourceManager {
    /** 测速结果缓存时长：24h。网络环境短期稳定，避免每次打开都重新测速卡顿。 */
    private const val CACHE_TTL_MS = 24 * 60 * 60 * 1000L
    private const val CONNECT_TIMEOUT_MS = 3_000
    private const val READ_TIMEOUT_MS = 3_000

    /** 速度探测下载量：1MB，足够估算真实带宽且开销小（避免安装流程卡顿）。 */
    private const val SPEED_PROBE_BYTES = 1L * 1024 * 1024

    /** 综合评分参考体积：估算下载该体积所需时间。 */
    private const val SCORE_REF_BYTES = 100L * 1024 * 1024

    private const val KEY_AUTO_SOURCE = "auto_source"
    private const val KEY_AUTO_SOURCE_AT = "auto_source_at"

    /** 测速探测文件：runtime-latest 的 metadata.json（数百字节，测延迟）。 */
    private const val PROBE_URL =
        "https://github.com/RochelimitDawn/DSHM/releases/download/runtime-latest/metadata.json"

    /** 真实下载测速文件：runtime.zip（用 Range 拉前段测吞吐）。 */
    private const val SPEED_PROBE_URL =
        "https://github.com/RochelimitDawn/DSHM/releases/download/runtime-latest/runtime.zip"

    /** 测速候选源（key=源 id，value=探测 URL）。 */
    private val CANDIDATES = listOf(
        AppSettings.SOURCE_GHPROXY_AXISNOW to "https://axisnow.gh-proxy.org/$PROBE_URL",
        AppSettings.SOURCE_GHPROXY_CF to "https://v6.gh-proxy.org/$PROBE_URL",
        AppSettings.SOURCE_GITHUB to PROBE_URL,
    )

    /** 真实下载测速候选（key=源 id，value=URL；GitHub 源前缀由候选 URL 决定）。 */
    private val SPEED_CANDIDATES = listOf(
        AppSettings.SOURCE_GHPROXY_AXISNOW to "https://axisnow.gh-proxy.org/$SPEED_PROBE_URL",
        AppSettings.SOURCE_GHPROXY_CF to "https://v6.gh-proxy.org/$SPEED_PROBE_URL",
        AppSettings.SOURCE_GITHUB to SPEED_PROBE_URL,
    )

    /** 单次测速结果：latencyMs=延迟，speedKBps=真实吞吐（0=未测得）。 */
    data class SpeedResult(
        val source: String,
        val latencyMs: Long,
        val speedKBps: Double = 0.0,
    ) {
        /** 估算下载 100MB 的耗时（毫秒）；速度未测得时仅按延迟。 */
        val estimatedMs: Long
            get() = if (speedKBps > 0.0) {
                latencyMs + (SCORE_REF_BYTES / 1024.0 / speedKBps * 1000.0).toLong()
            } else {
                latencyMs + SCORE_REF_BYTES / 1024 / 1024 * 60_000L
            }
    }

    @Volatile
    private var memCache: String? = null

    @Volatile
    private var memCachedAt: Long = 0L

    /** 解析实际生效的下载源（auto → 测速最优源）。 */
    fun resolve(context: Context): String {
        val setting = AppSettings.downloadSource(context)
        if (setting != AppSettings.SOURCE_AUTO) return setting
        return cachedAutoSource(context) ?: measureAndCache(context)
    }

    /** 缓存中的 auto 源（内存或持久化，TTL 内有效）；无则返回 null。 */
    private fun cachedAutoSource(context: Context): String? {
        val now = System.currentTimeMillis()
        memCache?.let {
            if (now - memCachedAt < CACHE_TTL_MS) return it
        }
        val at = prefs(context).getLong(KEY_AUTO_SOURCE_AT, 0L)
        if (at > 0 && now - at < CACHE_TTL_MS) {
            val cached = prefs(context).getString(KEY_AUTO_SOURCE, null)
            if (cached != null) {
                memCache = cached
                memCachedAt = now
                return cached
            }
        }
        return null
    }

    /** 立即测速并缓存结果（供初次下载前与设置页手动触发）。 */
    fun measureAndCache(context: Context): String {
        return pickBest(speedTest(), context)
    }

    /** 基于已完成的测速结果选中并缓存最优源（避免重复测速）。 */
    fun pickBest(results: List<SpeedResult>, context: Context): String {
        val best = results.minByOrNull { it.estimatedMs }
        val picked = best?.source ?: AppSettings.SOURCE_GHPROXY_AXISNOW
        val now = System.currentTimeMillis()
        memCache = picked
        memCachedAt = now
        prefs(context).edit()
            .putString(KEY_AUTO_SOURCE, picked)
            .putLong(KEY_AUTO_SOURCE_AT, now)
            .apply()
        return picked
    }

    /** 清空 auto 缓存（切换源后调用，下次 resolve 重新测速）。 */
    fun clearCache(context: Context) {
        memCache = null
        memCachedAt = 0L
        prefs(context).edit()
            .remove(KEY_AUTO_SOURCE)
            .remove(KEY_AUTO_SOURCE_AT)
            .apply()
    }

    /**
     * 对所有候选源测速（延迟并行；对延迟最优前 2 源做真实下载测速）。
     * 返回含 latencyMs 与 speedKBps 的结果；失败源不出现。
     */
    fun speedTest(): List<SpeedResult> {
        // 1. 并行测延迟
        val latencies = mutableListOf<Pair<String, Long>>()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(CANDIDATES.size)
        try {
            val futures = CANDIDATES.map { (source, url) ->
                pool.submit {
                    val elapsed = probe(url)
                    if (elapsed != null) {
                        synchronized(latencies) { latencies.add(source to elapsed) }
                    }
                }
            }
            for (f in futures) runCatching { f.get(5, java.util.concurrent.TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
        if (latencies.isEmpty()) return emptyList()

        // 2. 对延迟最优前 2 源做真实下载测速（并行，Range 拉前 4MB）
        val speedPool = java.util.concurrent.Executors.newFixedThreadPool(2)
        val speedResults = java.util.Collections.synchronizedList(mutableListOf<SpeedResult>())
        try {
            val top = latencies.sortedBy { it.second }.take(2)
            val futures = top.map { (source, latency) ->
                speedPool.submit {
                    val url = SPEED_CANDIDATES.firstOrNull { it.first == source }?.second
                    if (url != null) {
                        val kbps = probeSpeed(url)
                        speedResults.add(SpeedResult(source, latency, kbps))
                    }
                }
            }
            for (f in futures) runCatching { f.get(5, java.util.concurrent.TimeUnit.SECONDS) }
        } finally {
            speedPool.shutdownNow()
        }

        // 3. 未被速度探测覆盖的源，用延迟结果补全（speedKBps=0）
        val measured = speedResults.map { it.source }.toSet()
        for ((source, latency) in latencies) {
            if (source !in measured) speedResults.add(SpeedResult(source, latency))
        }
        return speedResults
    }

    /** 探测单个源延迟：下载小文件并计总耗时；失败返回 null。 */
    private fun probe(url: String): Long? {
        var conn: HttpURLConnection? = null
        var input: InputStream? = null
        return try {
            val start = System.nanoTime()
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.instanceFollowRedirects = true
            if (conn.responseCode !in 200..299) return null
            input = conn.inputStream
            val buf = ByteArray(4096)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
            }
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
            if (elapsed <= 0L) 1L else elapsed
        } catch (_: Exception) {
            null
        } finally {
            runCatching { input?.close() }
            runCatching { conn?.disconnect() }
        }
    }

    /** 真实下载测速：Range 拉前 SPEED_PROBE_BYTES，返回吞吐 KB/s；失败返回 0。 */
    private fun probeSpeed(url: String): Double {
        var conn: HttpURLConnection? = null
        var input: InputStream? = null
        return try {
            val start = System.nanoTime()
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = 5_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Range", "bytes=0-${SPEED_PROBE_BYTES - 1}")
            if (conn.responseCode !in 200..299) return 0.0
            input = conn.inputStream
            var total = 0L
            val buf = ByteArray(64 * 1024)
            while (total < SPEED_PROBE_BYTES) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
            }
            val elapsedSec = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) / 1000.0
            if (elapsedSec <= 0.0 || total <= 0) 0.0 else (total / 1024.0) / elapsedSec
        } catch (_: Exception) {
            0.0
        } finally {
            runCatching { input?.close() }
            runCatching { conn?.disconnect() }
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences("siliconleap_prefs", Context.MODE_PRIVATE)
}
