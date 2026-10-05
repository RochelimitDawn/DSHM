package com.siliconleap.app.runtime

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 设备 root 检测与授权（Magisk / KernelSU 的 su）。
 * root shell 为可选能力：开启且授权后，DSH agent 命令以真 root 在宿主 Android 执行
 * （su -c "exec bash -c …"），替换 proot 子系统；未 root/未授权时回退 proot/原生。
 */
object RootManager {
    private val SU_CANDIDATES = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/su/bin/su",
        "/data/adb/ksu/bin/su",
        "/data/adb/ap/bin/su",
    )

    @Volatile
    private var granted = false

    @Volatile
    private var lastChecked: Long = 0L

    /** 检测到的 su 二进制路径；未 root 设备返回 null。 */
    fun suPath(): String? {
        for (c in SU_CANDIDATES) {
            val f = File(c)
            if (f.exists() && runCatching { f.canExecute() }.getOrDefault(false)) return c
        }
        return null
    }

    /** 设备是否已 root（su 存在）。 */
    fun rootAvailable(): Boolean = suPath() != null

    /** 是否已获得 su 授权（本进程会话内）。 */
    fun isGranted(): Boolean = granted

    /** 调用 su 触发 root 管理器授权弹窗（Magisk/KernelSU）；成功返回 true 并缓存。 */
    fun requestRoot(): Boolean {
        val su = suPath() ?: return false
        granted = runCatching {
            val p = ProcessBuilder(su, "-c", "id").redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor(15, TimeUnit.SECONDS)
            runCatching { p.destroyForcibly() }
            val ok = out.contains("uid=0") || p.exitValue() == 0
            lastChecked = System.currentTimeMillis()
            ok
        }.getOrDefault(false)
        return granted
    }

    /** 重置会话内授权缓存（卸载/失败时调用）。 */
    fun clearGrant() {
        granted = false
    }
}
