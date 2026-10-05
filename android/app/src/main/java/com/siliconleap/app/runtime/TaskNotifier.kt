package com.siliconleap.app.runtime

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.siliconleap.app.MainActivity
import com.siliconleap.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 任务事件系统通知（精简版）：runtime 安装完成/服务异常、插件装配结果，
 * 纯文字单渠道（「任务事件」DEFAULT），用户可在系统设置静音。
 * 前台服务状态条由 HarnessService 负责，本对象只发一次性完成事件。
 */
object TaskNotifier {
    private const val CHANNEL_ID = "task_events"
    private const val NOTIFY_RUNTIME_ID = 2
    private const val NOTIFY_PLUGIN_ID = 3

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 启动事件观察（Application 级，进程存活期间持续收集）。 */
    fun start(context: Context) {
        val app = context.applicationContext
        createChannel(app)
        observeRuntimeState(app)
        observePluginEvents(app)
    }

    /** runtime 状态跃迁通知：
     * - 安装完成：仅「下载/解压 → 运行」跃迁（进程冷启动时服务已在跑的状态采集不发，避免噪音）；
     * - 服务异常：非异常 → 异常跃迁。 */
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
                            notify(
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

    /** 插件装配完成/失败事件推送（纯文字）。 */
    private fun observePluginEvents(app: Context) {
        scope.launch {
            AddonManager.installEvents.collect { s ->
                val title = when {
                    s.failedCount == 0 -> "插件装配完成（${s.installedCount} 个）"
                    s.installedCount == 0 -> "插件装配失败"
                    else -> "插件装配完成（${s.installedCount} 成功 / ${s.failedCount} 失败）"
                }
                notify(
                    app,
                    NOTIFY_PLUGIN_ID,
                    title,
                    if (s.failedCount > 0) "失败插件 6 小时内不重试，点按查看插件页" else "点按查看插件页",
                )
            }
        }
    }

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

    private fun notify(context: Context, id: Int, title: String, text: String) {
        // POST_NOTIFICATIONS 为运行时权限（minSdk 33），未授予时静默跳过
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val openIntent = PendingIntent.getActivity(
            context,
            id,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            // 单色白图标（ic_notification）：系统按 alpha 渲染小图标，彩色 PNG
            // （ic_launcher_foreground）会原样渲染为红色，与状态栏其他单色图标不一致
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(id, notification) }
    }
}
