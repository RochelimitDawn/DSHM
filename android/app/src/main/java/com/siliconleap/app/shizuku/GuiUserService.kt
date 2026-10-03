package com.siliconleap.app.shizuku

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.os.RemoteException
import android.util.Log

/**
 * Shizuku UserService（shell uid 进程内实例化，由 Shizuku 服务器加载本 APK 的类）：
 * exec 在 shell uid 下原生执行命令（虚拟屏创建/跨屏拉起/输入/截屏全部经此通道）。
 * 首行 = 退出码，其后 = stdout+stderr 合并输出；不记录命令内容与输出（日志脱敏）。
 *
 * createDisplay 在本进程（shell uid）创建真实虚拟屏：使虚拟屏成为「屏」的隐藏 flag
 * 受 CAPTURE_VIDEO_OUTPUT 门控，app 进程拿不到（LittleWhale 验证方案）；shell uid
 * 可以通过。虚拟屏独立于锁屏（ALWAYS_UNLOCKED）与主屏焦点（OWN_FOCUS），
 * activity 能落上去（TRUSTED + OWN_DISPLAY_GROUP）。
 */
class GuiUserService : IGuiUserService.Stub() {

    private var context: Context? = null
    private var manager: DisplayManager? = null

    /** 本进程持有的虚拟屏，按 displayId 索引（release 用）。 */
    private val screens = mutableMapOf<Int, VirtualDisplay>()

    override fun exec(cmd: String): String {
        val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
        val out = StringBuilder()
        val err = StringBuilder()
        val t1 = Thread {
            runCatching { p.inputStream.bufferedReader().forEachLine { out.append(it).append('\n') } }
        }.apply { isDaemon = true; start() }
        val t2 = Thread {
            runCatching { p.errorStream.bufferedReader().forEachLine { err.append(it).append('\n') } }
        }.apply { isDaemon = true; start() }
        val code = runCatching { p.waitFor() }.getOrDefault(-1)
        t1.join(5_000)
        t2.join(5_000)
        return "$code\n$out$err"
    }

    override fun createDisplay(name: String, width: Int, height: Int, dpi: Int): Int {
        val ctx = context ?: attachContext() ?: return -1
        val displays = manager ?: newManager(ctx) ?: return -1
        // 同名屏已存在先释放（幂等：重复创建同一形状不堆积）
        synchronized(screens) {
            screens.entries.removeAll { (id, screen) ->
                if (screen.display.name == name) {
                    runCatching { screen.release() }
                    true
                } else {
                    false
                }
            }
        }
        val display = try {
            displays.createVirtualDisplay(name, width, height, dpi, null, flags())
        } catch (e: Throwable) {
            Log.w(TAG, "createDisplay failed: ${e.message}")
            return -1
        } ?: return -1
        val id = display.display.displayId
        synchronized(screens) { screens[id] = display }
        return id
    }

    override fun releaseDisplay(displayId: Int) {
        synchronized(screens) { screens.remove(displayId) }?.let { screen ->
            runCatching { screen.release() }
        }
    }

    override fun exit() {
        synchronized(screens) {
            screens.values.forEach { runCatching { it.release() } }
            screens.clear()
        }
        throw RemoteException("exit")
    }

    /**
     * 虚拟屏 flag 组合（平台隐藏 flag 的字面量，公开 SDK 不命名；LittleWhale 验证）。
     * TRUSTED + OWN_DISPLAY_GROUP 让 activity 能落在屏上且不镜像手机；
     * ALWAYS_UNLOCKED 使屏独立于锁屏；OWN_FOCUS + STEAL_TOP_FOCUS_DISABLED
     * 让屏持有焦点但抢主屏焦点。
     */
    private fun flags(): Int {
        var flags = PUBLIC or OWN_CONTENT_ONLY or SUPPORTS_TOUCH or DESTROY_CONTENT_ON_REMOVAL
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            flags = flags or TRUSTED or OWN_DISPLAY_GROUP or ALWAYS_UNLOCKED or TOUCH_FEEDBACK_DISABLED
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            flags = flags or OWN_FOCUS or DEVICE_DISPLAY_GROUP or STEAL_TOP_FOCUS_DISABLED
        }
        return flags
    }

    /** DisplayManager 构造器是隐藏 API，且系统实例会替 system 包回答——必须自建。 */
    private fun newManager(ctx: Context): DisplayManager? = runCatching {
        val constructor = DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
        constructor.isAccessible = true
        constructor.newInstance(ctx).also { manager = it }
    }.getOrNull()

    private fun attachContext(): Context? = runCatching {
        val activityThread = Class.forName("android.app.ActivityThread")
        val current = activityThread.getMethod("currentActivityThread").invoke(null)
        activityThread.getMethod("getSystemContext").invoke(current) as? Context
    }.getOrNull().also { context = it }

    private companion object {
        const val TAG = "GuiUserService"

        // 隐藏虚拟屏 flag 字面量（LittleWhale 验证组合）
        const val PUBLIC = 1 shl 0
        const val OWN_CONTENT_ONLY = 1 shl 3
        const val SUPPORTS_TOUCH = 1 shl 6
        const val DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
        const val TRUSTED = 1 shl 10
        const val OWN_DISPLAY_GROUP = 1 shl 11
        const val ALWAYS_UNLOCKED = 1 shl 12
        const val TOUCH_FEEDBACK_DISABLED = 1 shl 13
        const val OWN_FOCUS = 1 shl 14
        const val DEVICE_DISPLAY_GROUP = 1 shl 15
        const val STEAL_TOP_FOCUS_DISABLED = 1 shl 16
    }
}
