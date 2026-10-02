package com.siliconleap.app.runtime

import android.content.Context
import android.content.Intent
import android.provider.Settings
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * GUI 控制通道（dsh 会话 Computer Use 入口）：
 * - HTTP 仅绑定 127.0.0.1，token 鉴权（dsh web token 同源思路）
 * - 端口/token 配置落 dshHome/.gui-config（bionic 与 rootfs 会话都可读）
 * - 服务未连接结构化失败（503），不静默重试（Eta 重试语义）
 * - 不记录请求参数与结果（日志脱敏）
 */
object GuiManager {
    private const val DEFAULT_PORT = 18744
    private const val CONFIG_NAME = ".gui-config"

    @Volatile
    private var started = false

    fun attach(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        writeGuiScript(app)
        writeGuiSkill(app)
        Thread {
            runCatching { serve(app) }
        }.apply { isDaemon = true; name = "gui-control"; start() }
    }

    /**
     * 写入 dshHome/skills/gui.md（dsh-skill-filesystem 自动发现的 skill 目录，
     * frontmatter name + description 必填）：模型经 skill 索引发现 Computer Use
     * 能力与执行循环纪律。skill 缺失时 dsh 不会自发使用 gui 命令。
     */
    private fun writeGuiSkill(app: Context) {
        runCatching {
            val dir = File(TermuxEnv.dshHome(app), "skills")
            dir.mkdirs()
            File(dir, "gui.md").writeText(SKILL_MD)
        }
    }

    private const val SKILL_MD = """---
name: gui
description: 操作 Android 屏幕：检索控件、点按滑动、输入文本、截屏。当任务需要查看或操作手机/平板界面（打开应用、安装应用、点击设置）时使用。
---

# Android GUI 控制（Computer Use）

gui 命令行已在 PATH 中，控制 Android 屏幕。每次动作后界面会变化，必须重新检索。

## 命令

- `gui dump [max=N]` — 当前屏幕结构化控件树（JSON：ref/id/text/desc/bounds/clickable/editable/scrollable）
- `gui tapref N` — 点按 ref 对应控件（bounds 中心，推荐；免算坐标）
- `gui longpressref N` — 长按 ref 对应控件
- `gui tap x y` / `gui longpress x y` — 绝对坐标点按/长按
- `gui swipe x1 y1 x2 y2 [ms]` — 滑动
- `gui text '单行文本'` — 写入焦点输入框（先 tapref/tap 输入框再 text）
- `gui key back|home|recents` — 导航键
- `gui shot` — 截屏（返回 PNG 路径，可用文件工具查看）
- `gui state` — 服务状态

## 执行循环（严格遵守）

1. 先 `gui dump` 找目标控件，用 `gui tapref N` 点按（ref 是最近一次 dump 的节点编号）；
   确实需要绝对坐标时取 bounds 中心，禁止猜测坐标
2. 每次动作后重新 `gui dump` 验证效果（旧的树/截图已失效，ref 也随 dump 刷新）
3. 返回 `ref-expired`：先重新 `gui dump` 再取 ref
4. 找不到控件时：先滑动（dump 找 scrollable 容器）再重新 dump
5. 多个候选控件：把候选的 text/desc 列出来请用户确认，不选中第一个
6. 输入文本前先 tapref 输入框获得焦点，再 `gui text`
7. 返回 `accessibility-service-not-connected`：告知用户在系统设置的无障碍里开启「DSHM GUI 控制」
8. 支付/银行/密码类界面会阻止检索：直接告知用户手动完成，不重试

## WebUI 与目标应用的切换（重要）

- 优先用虚拟屏（`gui vcreate` 创建，第三方 App 在独立虚拟屏运行，不挤占用户前台，
  你可以继续在 WebUI 聊天）：
  - `gui vcreate` → 返回 displayId；`gui vlaunch <包名/Activity> <displayId>` 跨屏拉起
  - `gui vtap <displayId> x y` / `vswipe` / `vtext` / `vkey` 跨屏输入（绝对坐标，禁止归一化）
  - `gui vshot <displayId>` 跨屏截屏；`gui vdestroy` 销毁
  - 返回 shizuku-unavailable / 未授权：告知用户安装并授权 Shizuku（无线调试）
- 无 Shizuku 时的回退：`gui key home` 回桌面后 dump 目标应用（会话不中断，
  完成后告知用户点回 DSHM 查看结果）
- `am start -a android.intent.action.VIEW -d "market://details?id=包名"` 可直达应用市场详情页

## 注意

- text 只支持单行文本（多行分多次输入）
- 点按开关关闭时返回 `tap-disabled`：改用只读（dump/shot）
- 应用安装的确认弹窗也是屏幕控件：dump 后正常点按
"""

    fun config(context: Context): JSONObject {
        val prefs = AppSettings.guiToken(context)
        val token = if (prefs.isBlank()) {
            val generated = (0 until 32).map { "0123456789abcdef"[(Math.random() * 16).toInt()] }
                .joinToString("")
            AppSettings.setGuiToken(context, generated)
            generated
        } else {
            prefs
        }
        return JSONObject()
            .put("port", AppSettings.guiPort(context))
            .put("token", token)
    }

    private fun configPath(context: Context): File =
        File(TermuxEnv.dshHome(context), CONFIG_NAME)

    private fun serve(app: Context) {
        val cfg = config(app)
        val port = cfg.getInt("port")
        val token = cfg.getString("token")
        runCatching {
            configPath(app).parentFile?.mkdirs()
            configPath(app).writeText(cfg.toString())
            configPath(app).setReadable(true, true)
            configPath(app).setWritable(true, true)
            configPath(app).setExecutable(false, false)
        }
        val server = ServerSocket(port, 8, InetAddress.getLoopbackAddress())
        while (true) {
            val socket = try {
                server.accept()
            } catch (_: Exception) {
                return
            }
            Thread { runCatching { handle(app, socket, token) } }.apply { isDaemon = true }.start()
        }
    }

    private fun handle(app: Context, socket: Socket, token: String) {
        socket.use { s ->
            s.soTimeout = 10_000
            val reader = s.getInputStream().bufferedReader(StandardCharsets.UTF_8)
            val requestLine = reader.readLine() ?: return
            var contentLength = 0
            var authToken: String? = null
            while (true) {
                val line = reader.readLine() ?: return
                if (line.isEmpty()) break
                val lower = line.lowercase()
                if (lower.startsWith("content-length:")) {
                    contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                }
                if (lower.startsWith("x-gui-token:")) {
                    authToken = line.substringAfter(':').trim()
                }
            }
            val body = if (contentLength > 0) {
                val buf = CharArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = reader.read(buf, read, contentLength - read)
                    if (n < 0) break
                    read += n
                }
                String(buf, 0, read)
            } else {
                ""
            }
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val path = parts[1].substringBefore('?')
            val query = parts[1].substringAfter('?', "")
            // 鉴权：token 缺失或错误一律 401（计时恒定，防枚举）
            if (authToken != token) {
                respond(s, 401, JSONObject().put("error", "unauthorized"))
                return
            }
            val svc = GuiAccessibilityService.connected()
            val json = runCatching { JSONObject(body) }.getOrNull()
            when {
                path == "/state" -> respond(
                    s, 200,
                    JSONObject()
                        .put("connected", svc)
                        .put("tapEnabled", AppSettings.guiTapEnabled(app))
                        .put("settingsUrl", "android.settings.ACCESSIBILITY_SETTINGS"),
                )
                path == "/dump" -> {
                    if (!svc) {
                        respond(s, 503, notConnected())
                        return
                    }
                    val max = query.substringAfter("max=", "").toIntOrNull() ?: 200
                    respond(s, 200, JSONObject(GuiAccessibilityService.instance!!.dumpJson(max)))
                }
                path == "/tap" && method == "POST" -> requireTap(app, s) {
                    resolveRefOrPoint(json) { x, y ->
                        GuiAccessibilityService.instance!!.tap(x, y)
                    }
                }
                path == "/longpress" && method == "POST" -> requireTap(app, s) {
                    resolveRefOrPoint(json) { x, y ->
                        GuiAccessibilityService.instance!!.longPress(x, y)
                    }
                }
                path == "/swipe" && method == "POST" -> requireTap(app, s) {
                    val x1 = json?.optInt("x1", -1) ?: -1
                    val y1 = json?.optInt("y1", -1) ?: -1
                    val x2 = json?.optInt("x2", -1) ?: -1
                    val y2 = json?.optInt("y2", -1) ?: -1
                    val ms = json?.optLong("ms", 300) ?: 300
                    if (x1 < 0 || y1 < 0 || x2 < 0 || y2 < 0) {
                        JSONObject().put("error", "invalid-args")
                    } else {
                        JSONObject().put("ok", GuiAccessibilityService.instance!!.swipe(x1, y1, x2, y2, ms))
                    }
                }
                path == "/text" && method == "POST" -> requireTap(app, s) {
                    val value = json?.optString("value", "") ?: ""
                    if (value.isEmpty()) {
                        JSONObject().put("error", "invalid-args")
                    } else {
                        JSONObject().put("ok", GuiAccessibilityService.instance!!.inputText(value))
                    }
                }
                path == "/key" && method == "POST" -> requireTap(app, s) {
                    val action = json?.optString("action", "") ?: ""
                    if (action.isEmpty()) {
                        JSONObject().put("error", "invalid-args")
                    } else {
                        JSONObject().put("ok", GuiAccessibilityService.instance!!.key(action))
                    }
                }
                path == "/screenshot" -> {
                    if (!svc) {
                        respond(s, 503, notConnected())
                        return
                    }
                    val png = GuiAccessibilityService.instance!!.screenshot(TermuxEnv.dshHome(app))
                    if (png == null) {
                        respond(s, 500, JSONObject().put("error", "screenshot-failed"))
                    } else {
                        // 相对 dshHome 的路径：rootfs 会话（/root/dsh）与 bionic 会话都可拼
                        respond(
                            s, 200,
                            JSONObject()
                                .put("path", png.substringAfter(TermuxEnv.dshHome(app).absolutePath).removePrefix("/"))
                                .put("absolute", png)
                                .put("guest", "/root/dsh"),
                        )
                    }
                }
                path == "/vdisplay/status" -> {
                    val id = VdisplayManager.currentDisplayId(app)
                    respond(
                        s, 200,
                        VdisplayManager.statusJson().put("displayId", if (id >= 0) id else JSONObject.NULL),
                    )
                }
                path == "/vdisplay/create" && method == "POST" ->
                    respond(s, 200, VdisplayManager.create(app))
                path == "/vdisplay/destroy" && method == "POST" ->
                    respond(s, 200, VdisplayManager.destroy(app))
                path == "/vlaunch" && method == "POST" -> {
                    if (!AppSettings.guiTapEnabled(app)) {
                        respond(s, 403, JSONObject().put("error", "tap-disabled"))
                        return
                    }
                    val displayId = json?.optInt("display", VdisplayManager.currentDisplayId(app)) ?: -1
                    if (displayId < 0) {
                        respond(s, 400, JSONObject().put("error", "no-vdisplay").put("hint", "run gui vcreate first"))
                        return
                    }
                    respond(
                        s, 200,
                        VdisplayManager.launch(
                            app,
                            json?.optString("component", "").takeIf { !it.isNullOrBlank() },
                            json?.optString("pkg", "").takeIf { !it.isNullOrBlank() },
                            displayId,
                        ),
                    )
                }
                path == "/vinput" && method == "POST" -> {
                    if (!AppSettings.guiTapEnabled(app)) {
                        respond(s, 403, JSONObject().put("error", "tap-disabled"))
                        return
                    }
                    val displayId = json?.optInt("display", -1) ?: -1
                    if (displayId < 0) {
                        respond(s, 400, JSONObject().put("error", "invalid-args"))
                        return
                    }
                    val op = json?.optString("op", "") ?: ""
                    respond(s, 200, VdisplayManager.input(app, displayId, op, json ?: JSONObject()))
                }
                path == "/vscreenshot" -> {
                    val displayId = query.substringAfter("display=", "").toIntOrNull()
                        ?: VdisplayManager.currentDisplayId(app)
                    if (displayId < 0) {
                        respond(s, 400, JSONObject().put("error", "no-vdisplay").put("hint", "run gui vcreate first"))
                        return
                    }
                    val png = VdisplayManager.screenshot(app, displayId)
                    if (png == null) {
                        respond(s, 500, JSONObject().put("error", "screenshot-failed"))
                    } else {
                        respond(
                            s, 200,
                            JSONObject()
                                .put("path", png)
                                .put("absolute", File(TermuxEnv.dshHome(app), png).absolutePath)
                                .put("guest", "/root/dsh"),
                        )
                    }
                }
                else -> respond(s, 404, JSONObject().put("error", "not-found"))
            }
        }
    }

    /** tap 类动作的用户开关（Eta：入口请求只能缩小能力，不能自行授权）。 */
    private inline fun requireTap(app: Context, socket: Socket, respondFn: () -> JSONObject) {
        if (!AppSettings.guiTapEnabled(app)) {
            respond(socket, 403, JSONObject().put("error", "tap-disabled"))
            return
        }
        if (!GuiAccessibilityService.connected()) {
            respond(socket, 503, notConnected())
            return
        }
        respond(socket, 200, respondFn())
    }

    /**
     * ref 寻址优先：`{"ref": N}` 从最近一次 dump 的缓存取 bounds 中心（免模型算坐标），
     * `{"x":..,"y":..}` 直接坐标；ref 过期返回 ref-expired（模型需重新 dump）。
     */
    private fun resolveRefOrPoint(json: JSONObject?, act: (Int, Int) -> Boolean): JSONObject {
        val ref = json?.optInt("ref", -1) ?: -1
        if (ref >= 0) {
            val b = GuiAccessibilityService.boundsForRef(ref)
                ?: return JSONObject().put("error", "ref-expired").put("hint", "re-run gui dump")
            val x = (b[0] + b[2]) / 2
            val y = (b[1] + b[3]) / 2
            return JSONObject().put("ok", act(x, y)).put("x", x).put("y", y)
        }
        val x = json?.optInt("x", -1) ?: -1
        val y = json?.optInt("y", -1) ?: -1
        return if (x < 0 || y < 0) {
            JSONObject().put("error", "invalid-args")
        } else {
            JSONObject().put("ok", act(x, y))
        }
    }

    private fun notConnected(): JSONObject = JSONObject()
        .put("error", "accessibility-service-not-connected")
        .put("hint", "enable DSHM GUI service in system accessibility settings")

    private fun respond(socket: Socket, code: Int, json: JSONObject) {
        val body = json.toString()
        val status = when (code) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            500 -> "Internal Server Error"
            503 -> "Service Unavailable"
            else -> "OK"
        }
        val head = "HTTP/1.1 $code $status\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: ${body.toByteArray(StandardCharsets.UTF_8).size}\r\n" +
            "Connection: close\r\n\r\n"
        socket.getOutputStream().let { out ->
            out.write((head + body).toByteArray(StandardCharsets.UTF_8))
            out.flush()
        }
    }

    /**
     * 生成 `gui` CLI：从 assets/gui.sh 复制（单一可执行事实来源，避免 Kotlin
     * 模板转义问题），仅替换 __DSH_HOME__ 占位符为 dshHome 绝对路径。
     * bionic 会话 PATH 含 prefix/bin；rootfs 会话由单文件 bind 到 /usr/local/bin/gui。
     */
    private fun writeGuiScript(context: Context) {
        runCatching {
            val template = context.assets.open("gui.sh").bufferedReader().use { it.readText() }
            val script = template.replace("__DSH_HOME__", TermuxEnv.dshHome(context).absolutePath)
            val binDir = File(TermuxEnv.prefix(context), "bin")
            binDir.mkdirs()
            val f = File(binDir, "gui")
            f.writeText(script)
            f.setExecutable(true, false)
            f.setReadable(true, false)
            f.setWritable(true, true)
        }
    }

    /** 系统无障碍设置入口（设置页/引导用）。 */
    fun openAccessibilitySettings(context: Context) {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
