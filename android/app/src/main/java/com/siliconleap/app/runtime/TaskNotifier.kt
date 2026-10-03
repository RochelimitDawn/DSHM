package com.siliconleap.app.runtime

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.FileObserver
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.siliconleap.app.MainActivity
import com.siliconleap.app.R
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 任务事件与 DSH 会话事件的系统通知。
 *
 * 任务事件（「任务事件」渠道，DEFAULT）：
 * - runtime 安装完成（下载/解压 → 运行跃迁）、服务异常、插件装配完成/失败。
 * - 装配结果用 InboxStyle 逐插件列出；异常附运行日志尾部（BigTextStyle）。
 *
 * 会话事件（「会话更新」渠道，DEFAULT）：
 * - 递归 FileObserver 监听 dshHome，会话持久化文件（jsonl/json/sqlite/db，路径含
 *   session 片段）有写入即节流发「会话有更新」，点按打开 WebUI。
 * - dsh 会话为追加式事件日志（jsonl/sqlite 持久化），写路径有界批处理 + flush 屏障，
 *   文件存在可见写入点；语义字段全部 try 解析，失败降级通用文案（dsh 版本升级兼容）。
 *
 * 渠道分级：前台服务状态条（LOW）由 HarnessService 负责，本对象只发一次性事件；
 * 用户可在系统设置对单渠道静音，应用内不重复提供开关。
 */
object TaskNotifier {
    private const val CHANNEL_ID = "task_events"
    private const val CHANNEL_SESSION_ID = "session_events"
    private const val CHANNEL_CONVERSATION_ID = "conversation"
    private const val NOTIFY_RUNTIME_ID = 2
    private const val NOTIFY_PLUGIN_ID = 3
    private const val NOTIFY_SESSION_ID = 4

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 对话通知状态：每会话最近消息历史（MessagingStyle 聚合，上限 5 条）。 */
    private data class ConvState(val title: String, val messages: ArrayDeque<String>)

    /**
     * 会话状态表：LRU 上限 32 会话（访问序淘汰最旧），防长时间驻留内存无界增长。
     * LinkedHashMap 访问序 + removeEldestEntry；synchronizedMap 包装，compute 需手动同步。
     */
    private val conversations: MutableMap<String, ConvState> =
        java.util.Collections.synchronizedMap(
            object : LinkedHashMap<String, ConvState>(16, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ConvState>?): Boolean =
                    size > 32
            },
        )

    /** 会话完成通知的每会话冷却：agent 连续多回合时防刷屏；失败通知始终放行。 */
    private val convCooldown = ConcurrentHashMap<String, Long>()
    private val CONV_COOLDOWN_MS = 30_000L

    /** 启动事件观察（Application 级，进程存活期间持续收集）。 */
    fun start(context: Context) {
        val app = context.applicationContext
        createChannel(app)
        createSessionChannel(app)
        createConversationChannel(app)
        observeRuntimeState(app)
        observePluginEvents(app)
        observeSessionEvents(app)
    }

    /** runtime 状态跃迁通知：
     * - 安装完成：仅「下载/解压 → 运行」跃迁（进程冷启动时服务已在跑的状态采集不发，避免人在 app 内收到噪音）；
     * - 服务异常：非异常 → 异常跃迁，附运行日志尾部。 */
    private fun observeRuntimeState(app: Context) {
        scope.launch {
            var prev: ServerPhase? = null
            RuntimeManager.state
                .map { it.phase }
                .distinctUntilChanged()
                .collect { phase ->
                    val from = prev
                    prev = phase
                    when {
                        phase == ServerPhase.RUNNING &&
                            (from == ServerPhase.DOWNLOADING || from == ServerPhase.EXTRACTING) ->
                            notify(
                                app,
                                NOTIFY_RUNTIME_ID,
                                "运行时安装完成",
                                "Harness 服务已就绪，点按打开",
                            )

                        phase == ServerPhase.ERROR && from != null && from != ServerPhase.ERROR ->
                            notifyError(
                                app,
                                NOTIFY_RUNTIME_ID,
                                "服务异常",
                                "点按查看运行日志（环境页）",
                            )

                        else -> Unit
                    }
                }
        }
    }

    /** 插件装配完成/失败事件推送（InboxStyle 逐插件列出）。 */
    private fun observePluginEvents(app: Context) {
        scope.launch {
            AddonManager.installEvents.collect { s ->
                val failed = s.failedCount
                val title = when {
                    failed == 0 -> "插件装配完成（${s.installedCount} 个）"
                    s.installedCount == 0 -> "插件装配失败"
                    else -> "插件装配完成（${s.installedCount} 成功 / $failed 失败）"
                }
                val notification = baseBuilder(app, NOTIFY_PLUGIN_ID, title)
                    .apply {
                        // 多插件聚合为一条多行摘要；单插件用普通文本
                        if (s.results.size > 1) {
                            val style = NotificationCompat.InboxStyle()
                            s.results.forEach { r ->
                                style.addLine(if (r.ok) "✓ ${r.id}" else "✗ ${r.id}（6 小时内不重试）")
                            }
                            style.setSummaryText("点按查看插件页")
                            setStyle(style)
                        } else {
                            val r = s.results.first()
                            setContentText(
                                if (r.ok) "${r.id} 装配成功（已验证）"
                                else "${r.id} 装配失败，6 小时内不重试",
                            )
                        }
                    }
                    .build()
                post(app, NOTIFY_PLUGIN_ID, notification)
            }
        }
    }

    /**
     * 会话事件通知：递归监听 dshHome，会话持久化文件有写入即节流通知。
     * dsh 会话持久化为 jsonl（追加式）或 sqlite；只依赖「文件有写入」这一事实，
     * 语义字段全部 try 解析，dsh 版本升级时降级为通用文案。
     * dshHome 在运行时未安装时不存在——先监听 filesDir 等目录出现，再挂递归 watcher。
     */
    private fun observeSessionEvents(app: Context) {
        val home = TermuxEnv.dshHome(app)
        if (home.isDirectory) {
            startSessionWatcher(app, home)
            return
        }
        val fo = object : FileObserver(app.filesDir, OBSERVER_MASK) {
            override fun onEvent(event: Int, path: String?) {
                if (path == "dsh-home" && TermuxEnv.dshHome(app).isDirectory) {
                    runCatching { stopWatching() }
                    startSessionWatcher(app, TermuxEnv.dshHome(app))
                }
            }
        }
        runCatching { fo.startWatching() }
    }

    private fun startSessionWatcher(app: Context, home: File) {
        val watcher = RecursiveFileObserver(home) { _, path ->
            // dsh-notification 插件事件文件走对话通知；会话持久化文件走通用会话通知
            if (isConversationEventPath(path)) {
                scope.launch { onConversationEvent(app, path) }
                return@RecursiveFileObserver
            }
            if (!isSessionPath(path)) return@RecursiveFileObserver
            scope.launch { onSessionActivity(app, path) }
        }
        watcher.begin()
    }

    /** dsh-notification 插件的事件文件（消费即删，不走节流/冷却）。 */
    private fun isConversationEventPath(path: String): Boolean =
        path.contains(".siliconleap-events") && path.lowercase().endsWith(".json")

    /**
     * 消费 dsh-notification 插件事件（JSON 契约：type/sessionId/title/excerpt/reason/error/time），
     * 推 MessagingStyle 对话通知后删除事件文件。turn 失败走 HIGH 渠道（需用户行动）。
     */
    private fun onConversationEvent(app: Context, path: String) {
        val file = File(path)
        val payload = runCatching {
            val text = file.readText()
            file.delete()
            JSONObject(text)
        }.getOrNull() ?: return
        if (payload.optString("type") != "turn_end") return
        val sessionId = payload.optString("sessionId", "")
        val excerpt = payload.optString("excerpt", "")
        val error = payload.optString("error", "")
        val reason = payload.optString("reason", "completed")
        val time = payload.optLong("time", System.currentTimeMillis())
        // 状态键：有 sessionId 用之，缺省用文件名（空会话 id 兜底）
        val key = sessionId.ifEmpty { "file:${file.name}" }
        val state = synchronized(conversations) {
            conversations.compute(key) { _, old ->
                val title = payload.optString("title", "").ifEmpty { old?.title ?: "" }
                val messages = old?.messages ?: ArrayDeque()
                when {
                    error.isNotEmpty() -> messages.addLast("⚠ $error")
                    excerpt.isNotEmpty() -> messages.addLast(excerpt)
                }
                while (messages.size > 5) messages.removeFirst()
                ConvState(title, messages)
            }
        } ?: return
        val title = state.title.ifEmpty { "DSH 会话" }
        val failed = error.isNotEmpty() || reason == "failed"
        // 完成通知每会话冷却 30s（连回合防刷屏）；失败通知始终放行（需用户行动）
        val now = System.currentTimeMillis()
        val lastNotify = convCooldown[key] ?: 0L
        if (now - lastNotify < CONV_COOLDOWN_MS && !failed) return
        convCooldown[key] = now
        val channel = if (failed) CHANNEL_CONVERSATION_ID else CHANNEL_SESSION_ID
        val builder = baseBuilder(app, stableId(key), title, channel)
        // MessagingStyle：sender = DSH，历史消息随通知聚合（Android 13+ 全可用）
        val dsh = androidx.core.app.Person.Builder().setName("DSH").build()
        val style = NotificationCompat.MessagingStyle(dsh)
        state.messages.forEach { msg ->
            style.addMessage(NotificationCompat.MessagingStyle.Message(msg, time, dsh))
        }
        if (state.messages.isEmpty()) {
            style.addMessage(NotificationCompat.MessagingStyle.Message("回合结束", time, dsh))
        }
        if (failed) builder.setPriority(NotificationCompat.PRIORITY_HIGH)
        post(app, stableId(key), builder.setStyle(style).build())
    }

    /** 通知 id：按会话稳定（同会话通知自动替换合并）；范围 5..65540，避开 1-4 的固定 id。 */
    private fun stableId(key: String): Int = NOTIFY_SESSION_ID + 1 + (key.hashCode() and 0xFFFF)

    /** 会话持久化文件判定：路径含 session 片段且为会话数据后缀。 */
    private fun isSessionPath(path: String): Boolean {
        val lower = path.lowercase()
        val looksLikeSession = lower.contains("session") || lower.contains("conversation")
        val isData = lower.endsWith(".jsonl") || lower.endsWith(".json") ||
            lower.endsWith(".sqlite") || lower.endsWith(".sqlite3") || lower.endsWith(".db")
        return looksLikeSession && isData
    }

    /** 节流：事件后 5s 无新写入才通知（会话批处理写入成簇）；同路径 3 分钟冷却。 */
    private val sessionPending = ConcurrentHashMap<String, Long>()
    private val sessionCooldown = ConcurrentHashMap<String, Long>()
    private val SESSION_DEBOUNCE_MS = 5_000L
    private val SESSION_COOLDOWN_MS = 3 * 60 * 1000L

    private suspend fun onSessionActivity(app: Context, path: String) {
        sessionPending[path] = System.currentTimeMillis()
        delay(SESSION_DEBOUNCE_MS)
        // 只有仍是最后一次写入的路径才继续（新事件会再次进入并重置窗口语义）
        val last = sessionPending[path] ?: return
        if (System.currentTimeMillis() - last < SESSION_DEBOUNCE_MS) return
        sessionPending.remove(path)
        val now = System.currentTimeMillis()
        val lastNotify = sessionCooldown[path] ?: 0L
        if (now - lastNotify < SESSION_COOLDOWN_MS) return
        sessionCooldown[path] = now
        val notification = baseBuilder(app, NOTIFY_SESSION_ID, "会话有更新", CHANNEL_SESSION_ID)
            .setContentText(sessionSummary(path))
            .build()
        post(app, NOTIFY_SESSION_ID, notification)
    }

    /**
     * 会话摘要：只读文件尾部 64KB（大 jsonl 全文件流读会拖慢 IO 线程），
     * try 提取尾部事件行的 type 字段，失败降级为文件名。
     */
    private fun sessionSummary(path: String): String {
        val name = File(path).name
        val line = tailLine(File(path)) ?: return name
        if (line.isBlank()) return name
        // jsonl 事件行字段名随 dsh 版本变化，宽松提取 type/时间戳之外不深解析
        val type = Regex("\"type\"\\s*:\\s*\"([^\"]{1,40})\"").find(line)?.groupValues?.get(1)
        return if (type != null) "$name · $type" else name
    }

    /** 尾部读取：RandomAccessFile seek 到 len-64KB，跳过可能截断的首个残行。 */
    private fun tailLine(file: File, maxBytes: Long = 64 * 1024): String? = runCatching {
        val len = file.length()
        if (len <= 0L) return null
        val start = if (len <= maxBytes) 0L else len - maxBytes
        java.io.RandomAccessFile(file, "r").use { raf ->
            raf.seek(start)
            val bytes = ByteArray((len - start).toInt())
            raf.readFully(bytes)
            var text = String(bytes, Charsets.UTF_8)
            if (start > 0L) {
                // 残行（可能半个 UTF-8 字符）：丢弃首个不完整行
                val nl = text.indexOf('\n')
                if (nl >= 0) text = text.substring(nl + 1)
            }
            text.lineSequence().lastOrNull { it.isNotBlank() }
        }
    }.getOrNull()

    private fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "任务事件",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "运行时安装、插件装配等任务完成事件"
            setShowBadge(true)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun createSessionChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_SESSION_ID,
            "会话更新",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "DSH 会话有新活动（后台任务完成、agent 回复）"
            setShowBadge(true)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun createConversationChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_CONVERSATION_ID,
            "对话消息",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "DSH 回合失败等需要用户行动的事件（横幅 + 声音）"
            setShowBadge(true)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun baseBuilder(context: Context, id: Int, title: String, channelId: String = CHANNEL_ID): NotificationCompat.Builder {
        val openIntent = PendingIntent.getActivity(
            context,
            id,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, channelId)
            .setContentTitle(title)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
    }

    /** 异常类通知：BigTextStyle 附运行日志尾部片段。 */
    private fun notifyError(context: Context, id: Int, title: String, fallback: String) {
        val tail = runCatching { RuntimeManager.tailLog(8) }.getOrNull().orEmpty()
        val builder = baseBuilder(context, id, title)
        if (tail.isNotBlank() && tail != "(暂无日志)") {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(tail).setSummaryText(fallback))
        } else {
            builder.setContentText(fallback)
        }
        post(context, id, builder.build())
    }

    private fun notify(context: Context, id: Int, title: String, text: String) {
        post(context, id, baseBuilder(context, id, title).setContentText(text).build())
    }

    private fun post(context: Context, id: Int, notification: android.app.Notification) {
        // POST_NOTIFICATIONS 为运行时权限（minSdk 33），未授予时静默跳过
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        runCatching { context.getSystemService(NotificationManager::class.java).notify(id, notification) }
    }
}

/**
 * 递归 FileObserver：Android FileObserver 单次只监听一个目录，此实现
 * walkTopDown 收集全部目录分别 watch，目录新建时（CREATE）自动扩 watch。
 * 注意：onEvent 的 path 参数是相对被 watch 目录的相对路径，回调整合时
 * 拼上被 watch 的目录前缀还原绝对路径。事件回调在 FileObserver 专用线程，
 * 回调体保持轻量，重活由调用方转协程。
 */
private class RecursiveFileObserver(
    root: File,
    private val onEvent: (Int, String) -> Unit,
) : FileObserver(root, OBSERVER_MASK) {
    private val rootPath = root.absolutePath
    private val watchers = ConcurrentHashMap<String, FileObserver>()

    /** FileObserver.startWatching 为 final 不可 override，此为批量挂载入口。 */
    fun begin() {
        runCatching {
            File(rootPath).walkTopDown().filter { it.isDirectory }.forEach { watch(it.absolutePath) }
        }
    }

    override fun stopWatching() {
        watchers.values.forEach { runCatching { it.stopWatching() } }
        watchers.clear()
    }

    override fun onEvent(event: Int, path: String?) {
        dispatch(rootPath, event, path)
    }

    /** 整合子 watcher 事件：拼前缀还原绝对路径后回调。 */
    private fun dispatch(watchDir: String, event: Int, relative: String?) {
        if (relative == null) return
        val file = File(watchDir, relative)
        // 目录新建：扩 watch（含其子树，mkdir -p 场景可能一次出现多级）
        if (event == CREATE && file.isDirectory) {
            file.walkTopDown().filter { it.isDirectory }.forEach { watch(it.absolutePath) }
            return
        }
        onEvent(event, file.absolutePath)
    }

    private fun watch(dirPath: String) {
        if (watchers.containsKey(dirPath)) return
        val fo = object : FileObserver(File(dirPath), OBSERVER_MASK) {
            override fun onEvent(event: Int, path: String?) {
                this@RecursiveFileObserver.dispatch(dirPath, event, path)
            }
        }
        runCatching { fo.startWatching() }
        watchers[dirPath] = fo
    }
}

// 文件级事件位；目录新建走 CREATE
private val OBSERVER_MASK = FileObserver.CREATE or FileObserver.MODIFY or
    FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO
