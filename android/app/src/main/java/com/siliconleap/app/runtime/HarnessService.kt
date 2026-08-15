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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Harness 前台服务：node 服务常驻，通知条显示状态并可停止。 */
class HarnessService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification("正在启动 Harness…"))
        observeState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            RuntimeManager.stopServer()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (RuntimeManager.state.value.phase != ServerPhase.RUNNING) {
            RuntimeManager.startServer()
        }
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
            RuntimeManager.state.map { it.phase }.distinctUntilChanged().collect { phase ->
                val text = when (phase) {
                    ServerPhase.RUNNING -> "Harness 运行中 · http://127.0.0.1:${RuntimeManager.state.value.port}"
                    ServerPhase.STARTING -> "正在启动 Harness…"
                    ServerPhase.DOWNLOADING, ServerPhase.EXTRACTING -> "正在安装运行时…"
                    ServerPhase.ERROR -> "服务异常，点击查看"
                    ServerPhase.NOT_READY -> "服务未启动"
                }
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
            }
        }
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Harness 服务", NotificationManager.IMPORTANCE_LOW).apply {
            description = "SiliconLeap Harness 后台服务状态"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
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
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SiliconLeap")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "停止", stopIntent)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "harness"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.siliconleap.app.action.STOP_HARNESS"

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
