package com.siliconleap.app.runtime

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors

/**
 * GUI Computer Use 无障碍服务（参考 Eta GUI Agent 链路）：
 * - dump：UI 树转结构化 JSON（有界：节点数/深度/文本长度），跳过密码输入框
 * - tap / longPress / swipe：dispatchGesture 同步等待完成
 * - text：焦点可编辑节点 ACTION_SET_TEXT
 * - key：全局动作（back / home / recents）
 * - screenshot：takeScreenshot 原始 PNG 落 dshHome/gui/（免 root）
 * 由 GuiControlServer 暴露给 dsh 会话（127.0.0.1 + token 鉴权）。
 */
class GuiAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        internal var instance: GuiAccessibilityService? = null

        /** 真实服务连接（Eta：GUI 执行前确认连接，未连接结构化失败）。 */
        fun connected(): Boolean = instance != null

        /** dump 有界：节点数与深度上限（防止巨型 UI 树拖垮模型上下文）。 */
        private const val MAX_NODES = 400
        private const val MAX_DEPTH = 24
        private const val MAX_TEXT = 120
        private const val GESTURE_TIMEOUT_MS = 10_000L

        /** ref → 屏幕 bounds 缓存（最近一次 dump 有效；ref 寻址免模型算坐标）。 */
        val refBounds = HashMap<Int, Array<Int>>()

        /** 清空 ref 缓存（界面变化后旧 ref 失效）。 */
        fun invalidateRefs() {
            synchronized(refBounds) { refBounds.clear() }
        }

        /** 按 ref 查缓存 bounds；缺失返回 null（模型需重新 dump）。 */
        fun boundsForRef(ref: Int): Array<Int>? =
            synchronized(refBounds) { refBounds[ref] }

        private val screenshotExecutor = Executors.newSingleThreadExecutor()
        private val mainHandler = Handler(Looper.getMainLooper())
        private var screenshotSeq = 0
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /**
     * 当前窗口 UI 树转结构化 JSON：id/text/desc/class/bounds/能力标记。
     * 零尺寸节点跳过，密码框整体剪枝（含子树），截断附带标记。
     */
    fun dumpJson(maxNodes: Int): String {
        val root = rootInActiveWindow
            ?: return JSONObject().put("error", "no-active-window").put("connected", true).toString()
        invalidateRefs()
        var count = 0
        var skippedPassword = 0
        val arr = JSONArray()
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > MAX_DEPTH) return
            if (count >= maxNodes.coerceIn(1, MAX_NODES)) return
            if (node.isPassword) {
                skippedPassword++
                return
            }
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.width() <= 0 || rect.height() <= 0) return
            count++
            val ref = count
            synchronized(refBounds) {
                refBounds[ref] = arrayOf(rect.left, rect.top, rect.right, rect.bottom)
            }
            val o = JSONObject()
            o.put("ref", ref)
            node.viewIdResourceName?.takeIf { it.isNotBlank() }?.let { o.put("id", it) }
            node.text?.takeIf { it.isNotBlank() }?.let { o.put("text", it.toString().take(MAX_TEXT)) }
            node.contentDescription?.takeIf { it.isNotBlank() }
                ?.let { o.put("desc", it.toString().take(MAX_TEXT)) }
            node.className?.let { o.put("class", it.toString().substringAfterLast('.')) }
            o.put("bounds", "${rect.left},${rect.top},${rect.right},${rect.bottom}")
            if (node.isClickable) o.put("clickable", true)
            if (node.isEditable) o.put("editable", true)
            if (node.isScrollable) o.put("scrollable", true)
            if (node.isLongClickable) o.put("longClickable", true)
            if (node.isSelected) o.put("selected", true)
            if (node.isFocused) o.put("focused", true)
            if (node.isEnabled == false) o.put("disabled", true)
            arr.put(o)
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        walk(root, 0)
        return JSONObject()
            .put("pkg", root.packageName?.toString() ?: "")
            .put("nodes", arr)
            .put("truncated", count >= maxNodes.coerceIn(1, MAX_NODES))
            .apply { if (skippedPassword > 0) put("skippedPasswordFields", skippedPassword) }
            .toString()
    }

    /** dispatchGesture 同步等待完成；超时/取消按失败返回（不静默重试）。 */
    private fun dispatch(builder: GestureDescription.Builder): Boolean {
        var ok = false
        val latch = CountDownLatch(1)
        val cb = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                ok = true
                latch.countDown()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                latch.countDown()
            }
        }
        mainHandler.post {
            runCatching { dispatchGesture(builder.build(), cb, null) }
                .onFailure { latch.countDown() }
        }
        latch.await(GESTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        return ok
    }

    fun tap(x: Int, y: Int): Boolean {
        val b = GestureDescription.Builder()
        b.addStroke(GestureDescription.StrokeDescription(android.graphics.Path().apply {
            moveTo(x.toFloat(), y.toFloat())
        }, 0, 60))
        return dispatch(b)
    }

    fun longPress(x: Int, y: Int): Boolean {
        val b = GestureDescription.Builder()
        b.addStroke(GestureDescription.StrokeDescription(android.graphics.Path().apply {
            moveTo(x.toFloat(), y.toFloat())
        }, 0, 650))
        return dispatch(b)
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean {
        val b = GestureDescription.Builder()
        b.addStroke(GestureDescription.StrokeDescription(android.graphics.Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }, 0, durationMs.coerceIn(80, 5_000)))
        return dispatch(b)
    }

    /** 焦点可编辑节点写入文本；先聚焦再 SET_TEXT（模型侧无需模拟逐键输入）。 */
    fun inputText(value: String): Boolean {
        val root = rootInActiveWindow ?: return false
        var target: AccessibilityNodeInfo? = null
        var node: AccessibilityNodeInfo? = root
        var depth = 0
        // 优先当前焦点；缺失时找第一个可编辑节点（有界遍历）
        target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (target == null) {
            while (node != null && depth < MAX_DEPTH && target == null) {
                if (node.isEditable) target = node
                node = node.getChild(0)
                depth++
            }
        }
        target ?: return false
        if (!target.isEditable) return false
        runCatching { target.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }
        val args = android.os.Bundle()
        args.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            value,
        )
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /** 全局动作：back / home / recents。 */
    fun key(action: String): Boolean = when (action) {
        "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
        "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
        "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        else -> false
    }

    /**
     * 无障碍截屏（API 30+）：HardwareBuffer → 软件 Bitmap → PNG 落 dshHome/gui/。
     * 原始尺寸与像素，不缩放不转 JPEG（Eta 同款 preserve_original 语义）。
     * @return PNG 文件绝对路径；失败返回 null。
     */
    fun screenshot(dshHome: File): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        var path: String? = null
        val latch = CountDownLatch(1)
        takeScreenshot(android.view.Display.DEFAULT_DISPLAY, screenshotExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                try {
                    val hardware = Bitmap.wrapHardwareBuffer(
                        result.hardwareBuffer,
                        result.colorSpace,
                    )
                    val software = hardware?.copy(Bitmap.Config.ARGB_8888, false)
                    result.hardwareBuffer.close()
                    if (software != null) {
                        val dir = File(dshHome, "gui")
                        dir.mkdirs()
                        val file = File(dir, "screenshot-${screenshotSeq++ % 8}.png")
                        file.outputStream().use { software.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        path = file.absolutePath
                        software.recycle()
                    }
                } catch (_: Exception) {
                    // 截图失败按结构化返回（path=null），不静默降级
                } finally {
                    latch.countDown()
                }
            }

            override fun onFailure(errorCode: Int) {
                latch.countDown()
            }
        })
        latch.await(GESTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        return path
    }
}
