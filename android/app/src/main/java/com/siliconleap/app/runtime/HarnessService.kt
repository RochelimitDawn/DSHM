package com.siliconleap.app.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.siliconleap.app.MainActivity
import com.siliconleap.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Harness 前台服务：node 服务常驻，通知条显示状态并可停止。 */
class HarnessService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        // 初始通知用当前实际状态（服务重建/已运行时不再固定"正在启动"）
        val init = RuntimeManager.state.value
        try {
            startForeground(NOTIFICATION_ID, buildNotification(statusText(init.phase), init))
        } catch (e: Exception) {
            // Android 14 起，前台服务通知发布失败（渠道状态异常、OEM SystemUI 渲染拒绝等，
            // 如 vivo OriginOS）系统会包成 BadForegroundServiceNotificationException 直接崩。
            // 降级重试：最小通知 + 平台内置图标（排除应用侧矢量图标/渠道因素）。
            try {
                startForeground(
                    NOTIFICATION_ID,
                    NotificationCompat.Builder(this, CHANNEL_ID)
                        .setContentTitle("DSHM")
                        .setSmallIcon(android.R.drawable.stat_notify_sync)
                        .setOngoing(true)
                        .build(),
                )
            } catch (e2: Exception) {
                // 连平台图标都发不出去：放弃前台身份避免必崩循环。
                // 服务随之可被系统回收，好过打开即崩。
                stopSelf()
            }
        }
        observeState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            RuntimeManager.stopServer()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        // 下载/解压进行中只展示进度通知，重复 startServer 会与下载流程竞争
        val phase = RuntimeManager.state.value.phase
        if (phase != ServerPhase.RUNNING && phase != ServerPhase.DOWNLOADING && phase != ServerPhase.EXTRACTING) {
            RuntimeManager.startServer()
        }
        // 启动后立即刷新通知（覆盖启动占位文本）
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(statusText(RuntimeManager.state.value.phase), RuntimeManager.state.value))
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        RuntimeManager.stopServer()
        super.onDestroy()
    }

    private fun observeState() {
        scope.launch {
            // 下载/解压阶段展示实时进度文案与进度条，其余阶段用固定状态文案
            RuntimeManager.state.collect { state ->
                val text = when (state.phase) {
                    ServerPhase.DOWNLOADING, ServerPhase.EXTRACTING -> state.message
                    else -> statusText(state.phase)
                }
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, buildNotification(text, state))
            }
        }
    }

    private fun statusText(phase: ServerPhase): String = when (phase) {
        ServerPhase.RUNNING -> "Harness 运行中 · http://127.0.0.1:${RuntimeManager.state.value.port}"
        ServerPhase.STARTING -> "正在启动 Harness…"
        ServerPhase.DOWNLOADING, ServerPhase.EXTRACTING -> "正在安装运行时…"
        ServerPhase.ERROR -> "服务异常，点击查看"
        ServerPhase.NOT_READY -> "服务未启动"
    }

    private fun buildNotification(text: String, state: RuntimeState = RuntimeManager.state.value): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, HarnessService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DSHM")
            .setContentText(text)
            // 单色白图标（ic_notification）：系统按 alpha 渲染小图标，彩色 PNG
            // （ic_launcher_foreground）会原样渲染为红色，与状态栏其他单色图标不一致
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "停止", stopIntent)
        // 下载/解压阶段展示确定性进度条（progress < 0 表示不确定进度，仅当 > 0 时展示）
        if (state.phase == ServerPhase.DOWNLOADING || state.phase == ServerPhase.EXTRACTING) {
            builder.setProgress(100, (state.progress * 100).toInt().coerceIn(0, 100), state.progress <= 0f)
        }
        return builder.build()
    }

    companion object {
        // 渠道 id 带版本：旧渠道若被用户/OEM 系统删除或异常屏蔽，同名重建是静默 no-op，
        // 换新 id 保证人人拿到全新渠道（Android 14 FGS 通知发布失败的常见诱因）
        const val CHANNEL_ID = "harness_v2"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.siliconleap.app.action.STOP_HARNESS"

        /** 渠道尽早创建：Application onCreate 调用，先于任何 startForeground/notify。 */
        fun ensureChannel(context: Context) {
            val channel = NotificationChannel(CHANNEL_ID, "Harness 服务", NotificationManager.IMPORTANCE_LOW).apply {
                description = "SiliconLeap Harness 后台服务状态"
                setShowBadge(false)
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        fun start(context: Context) {
            val intent = Intent(context, HarnessService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, HarnessService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }
    }
}
