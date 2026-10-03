package com.siliconleap.app.runtime

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import com.siliconleap.app.shizuku.IGuiUserService
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 虚拟屏 + 特权通道（Shizuku，参考 dsh-mobile-apk 实测结论）：
 * - 第三方 App 在独立虚拟屏运行，不挤占用户前台（WebUI 聊天与 GUI 操作并行）
 * - 跨屏拉起唯一可行路径 = shell uid 的 `am start --display <id> -n <component>`
 *   （monkey 无此选项 / 进程内 setLaunchDisplayId 被 SafeActivityOptions 拒）
 * - 坐标输入必须绝对 x/y + `input -d <displayId>`；归一化坐标在虚拟屏上会静默点到真屏
 * - 虚拟屏创建双路线：优先 shell uid 进程内 createVirtualDisplay（隐藏 flag 组合，
 *   LittleWhale 验证方案；屏独立于锁屏/主屏焦点），失败回退 overlay_display_devices
 * - 截屏 `screencap -d <id>` 落目标屏；服务不可用一律结构化失败，不静默降级
 */
object VdisplayManager {

    private const val OVERLAY_KEY = "overlay_display_devices"
    private const val OVERLAY_SPEC = "1280x800/160"

    /** createVirtualDisplay 屏名（dumpsys display 可见，同前缀编号区分）。 */
    private const val VDISPLAY_NAME = "DSHM VirtualScreen"

    /** Shizuku 可用（binder 活着 + 已授权；PERMISSION_GRANTED = 0）。 */
    fun available(): Boolean = runCatching {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == 0
    }.getOrDefault(false)

    /** Shizuku 状态 JSON（未装/未授权分开报，模型可给用户准确指引）。 */
    fun statusJson(): JSONObject {
        val ping = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val granted = runCatching {
            ping && Shizuku.checkSelfPermission() == 0
        }.getOrDefault(false)
        return JSONObject()
            .put("shizukuInstalled", ping)
            .put("shizukuGranted", granted)
            .put("hint", if (!ping) "install Shizuku app and start it (wireless debugging)" else "grant permission in Shizuku app")
    }

    /**
     * Shizuku UserService（shell uid）绑定：Shizuku.newProcess 是私有 API，
     * 公开路径 = bindUserService + 自定义 IGuiUserService（demo 官方模式）。
     * binder 缓存复用；绑定失败/超时结构化返回。
     */
    @Volatile
    private var userService: IGuiUserService? = null

    private fun ensureUserService(context: Context): IGuiUserService? {
        userService?.let { return it }
        if (!available()) return null
        val latch = CountDownLatch(1)
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                userService = IGuiUserService.Stub.asInterface(binder)
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                userService = null
            }
        }
        var bound = false
        try {
            val args = Shizuku.UserServiceArgs(ComponentName(context, com.siliconleap.app.shizuku.GuiUserService::class.java))
                .processNameSuffix("gui_user_service")
                .version(2)
            Shizuku.bindUserService(args, conn)
            bound = true
        } catch (e: Exception) {
            bound = false
        }
        if (!bound) return null
        latch.await(20, TimeUnit.SECONDS)
        return userService
    }

    /** Shizuku shell uid 执行（UserService exec；首行退出码，其后输出）。 */
    private fun exec(context: Context, cmd: String, timeoutMs: Long): Pair<Boolean, String> {
        val svc = ensureUserService(context)
            ?: return false to "shizuku user service unavailable (install Shizuku, grant permission)"
        return try {
            val raw = svc.exec(cmd)
            val first = raw.lineSequence().firstOrNull()?.toIntOrNull()
            val output = raw.lineSequence().drop(1).joinToString("\n")
            (first == 0) to output
        } catch (e: Exception) {
            false to (e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * 创建虚拟屏（幂等）：
     * 1. 优先 shell uid 进程内 createVirtualDisplay（隐藏 flag 组合，LittleWhale
     *    验证方案）——displayId 直接返回，屏独立于锁屏与主屏焦点，activity 能落上去；
     * 2. 失败回退 overlay_display_devices 模拟显示（settings put + dumpsys 解析）。
     */
    fun create(context: Context): JSONObject {
        if (!available()) return statusJson().put("error", "shizuku-unavailable")
        val svc = ensureUserService(context)
        if (svc != null) {
            try {
                val id = svc.createDisplay(VDISPLAY_NAME, 1280, 800, 160)
                if (id >= 0) {
                    nativeDisplayId = id
                    return JSONObject().put("ok", true).put("displayId", id)
                        .put("engine", "virtual-display")
                }
            } catch (_: Exception) {
            }
        }
        val (ok, out) = exec(context, "settings put global $OVERLAY_KEY '$OVERLAY_SPEC'", 15_000)
        if (!ok) {
            return JSONObject().put("error", "overlay-display-write-failed").put("detail", out.takeLast(300))
        }
        val id = findOverlayDisplayId(context)
        return if (id >= 0) {
            nativeDisplayId = -1
            JSONObject().put("ok", true).put("displayId", id).put("spec", OVERLAY_SPEC)
                .put("engine", "overlay")
        } else {
            JSONObject().put("error", "overlay-display-not-found").put("detail", out.takeLast(300))
        }
    }

    /** 销毁虚拟屏：优先释放 createVirtualDisplay 创建的屏，再 overlay 置 none。 */
    fun destroy(context: Context): JSONObject {
        if (!available()) return statusJson().put("error", "shizuku-unavailable")
        ensureUserService(context)?.let { svc ->
            val id = nativeDisplayId
            if (id >= 0) {
                try {
                    svc.releaseDisplay(id)
                } catch (_: Exception) {
                }
            }
        }
        nativeDisplayId = -1
        val (ok, out) = exec(context, "settings put global $OVERLAY_KEY none", 15_000)
        return JSONObject().put("ok", ok).put("detail", out.takeLast(300)).takeIf { ok }
            ?: JSONObject().put("error", "overlay-display-destroy-failed").put("detail", out.takeLast(300))
    }

    /** createVirtualDisplay 创建的屏的 displayId（overlay 回退时为 -1）。 */
    @Volatile
    private var nativeDisplayId = -1

    /** 解析 overlay 虚拟屏的 displayId（dumpsys display 的 Overlay 块）。 */
    private fun findOverlayDisplayId(context: Context): Int {
        val (_, out) = exec(context, "dumpsys display", 15_000)
        val lines = out.lines()
        for (i in lines.indices) {
            if (lines[i].contains("mName=Overlay")) {
                // 向上找同块的 mDisplayId=N
                for (j in i downTo maxOf(0, i - 12)) {
                    val m = Regex("mDisplayId=(\\d+)").find(lines[j])
                    if (m != null) return m.groupValues[1].toInt()
                }
                // 向下兜底
                for (j in i..minOf(lines.size - 1, i + 12)) {
                    val m = Regex("mDisplayId=(\\d+)").find(lines[j])
                    if (m != null) return m.groupValues[1].toInt()
                }
            }
        }
        return -1
    }

    /** 当前虚拟屏 displayId（createVirtualDisplay 优先，无则 overlay 解析；无则 -1）。 */
    fun currentDisplayId(context: Context): Int {
        if (!available()) return -1
        if (nativeDisplayId >= 0) return nativeDisplayId
        return findOverlayDisplayId(context)
    }

    /**
     * 跨屏拉起（唯一可行路径）：`am start --display <id> -n <component>`；
     * component 缺失时先 resolve-activity。
     */
    fun launch(context: Context, component: String?, pkg: String?, displayId: Int): JSONObject {
        if (!available()) return statusJson().put("error", "shizuku-unavailable")
        val cmd = if (component != null) {
            "am start --display $displayId -n '$component'"
        } else if (pkg != null) {
            return JSONObject().put("error", "use-component")
        } else {
            return JSONObject().put("error", "invalid-args")
        }
        val (ok, out) = exec(context, cmd, 20_000)
        return JSONObject()
            .put("ok", ok)
            .put("displayId", displayId)
            .put("detail", out.takeLast(400))
    }

    /**
     * 跨屏输入：绝对坐标 + `input -d <displayId>`（归一化坐标明确拒绝，分母歧义
     * 会静默点到真屏）。
     */
    fun input(context: Context, displayId: Int, op: String, json: JSONObject): JSONObject {
        if (!available()) return statusJson().put("error", "shizuku-unavailable")
        val cmd = when (op) {
            "tap" -> {
                val x = json.optInt("x", -1)
                val y = json.optInt("y", -1)
                if (x < 0 || y < 0) return JSONObject().put("error", "invalid-args")
                "input -d $displayId tap $x $y"
            }
            "swipe" -> {
                val x1 = json.optInt("x1", -1)
                val y1 = json.optInt("y1", -1)
                val x2 = json.optInt("x2", -1)
                val y2 = json.optInt("y2", -1)
                val ms = json.optLong("ms", 300)
                if (x1 < 0 || y1 < 0 || x2 < 0 || y2 < 0) return JSONObject().put("error", "invalid-args")
                "input -d $displayId swipe $x1 $y1 $x2 $y2 $ms"
            }
            "text" -> {
                val value = json.optString("value", "")
                if (value.isEmpty() || value.contains('\n') || value.contains('\'')) return JSONObject().put("error", "invalid-args")
                "input -d $displayId text '$value'"
            }
            "key" -> {
                val action = json.optString("action", "")
                if (action !in listOf("back", "home", "recents")) return JSONObject().put("error", "invalid-args")
                "input -d $displayId keyevent " + when (action) {
                    "back" -> "4"
                    "home" -> "3"
                    else -> "187"
                }
            }
            else -> return JSONObject().put("error", "invalid-args")
        }
        val (ok, out) = exec(context, cmd, 15_000)
        return JSONObject().put("ok", ok).put("detail", out.takeLast(300))
    }

    /**
     * 跨屏截屏：`screencap -d <id> -p` 落目标屏 PNG（dshHome/gui/vshot-<id>.png）。
     * @return 相对 dshHome 的路径；失败返回 null。
     */
    fun screenshot(context: Context, displayId: Int): String? {
        if (!available()) return null
        val dir = File(TermuxEnv.dshHome(context), "gui")
        dir.mkdirs()
        val file = File(dir, "vshot-$displayId.png")
        val (ok, _) = exec(
            context,
            "screencap -d $displayId -p '${file.absolutePath}'",
            30_000,
        )
        return if (ok && file.exists() && file.length() > 0) {
            file.absolutePath.substringAfter(TermuxEnv.dshHome(context).absolutePath).removePrefix("/")
        } else {
            runCatching { file.delete() }
            null
        }
    }
}
