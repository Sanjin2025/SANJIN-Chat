package com.sanjinchat.wear

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat

/**
 * 保活前台服务（面向小天才等定制 ROM 的深度保活）：
 * 1) 常驻通知栏 + START_STICKY，系统回收后自动重建
 * 2) 持有 PARTIAL_WAKE_LOCK，避免 CPU 进入深度休眠导致 WS 断连
 * 3) AlarmManager 每 2 分钟自检：服务被 kill 时由闹钟重新拉起
 * 4) onTaskRemoved 时用 AlarmManager 立刻重新启动自己
 */
class KeepAliveService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, buildNotification())
        acquireWakeLock()
        scheduleSelfRestart()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 服务被系统回收后自动重启
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLock()
    }

    /** 最近任务列表中划掉 App 时触发：立即用闹钟把自己拉起来 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        scheduleSelfRestart(delayMs = 1000L)
        stopSelf()
    }

    private fun acquireWakeLock() {
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "sanjinchat:keepalive"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    /**
     * 用 AlarmManager 定时重新启动本服务：
     * 即使进程被系统/第三方清理，闹钟仍会在下次触发时把服务拉起来。
     */
    private fun scheduleSelfRestart(delayMs: Long = 2 * 60 * 1000L) {
        runCatching {
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getService(
                this,
                REQ_RESTART,
                Intent(this, KeepAliveService::class.java),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                else PendingIntent.FLAG_UPDATE_CURRENT
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + delayMs,
                    pi
                )
            } else {
                @Suppress("DEPRECATION")
                am.setRepeating(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + delayMs,
                    delayMs,
                    pi
                )
            }
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    "连接保活",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "三金聊天常驻连接通知"
                    setShowBadge(false)
                    enableVibration(false)
                }
                nm.createNotificationChannel(ch)
            }
        }
    }

    private fun buildNotification(): Notification {
        val ctx = this
        val openAppIntent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi: PendingIntent? =
            PendingIntent.getActivity(
                ctx,
                1001,
                openAppIntent,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                else PendingIntent.FLAG_UPDATE_CURRENT
            )

        val app = applicationContext as? SanjinChatApp
        val connected =
            app?.webSocketClient?.connectionState?.replayCache?.firstOrNull()
                ?.let { it !is com.sanjinchat.wear.network.ConnectionState.Disconnected }
                ?: false

        return NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setOngoing(true)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("三金聊天已保活")
            .setContentText(if (connected) "WebSocket 在线" else "后台连接保持中…")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pi)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "sanjinchat_keepalive"
        const val NOTIF_ID = 20250301
        private const val REQ_RESTART = 20250302
    }
}
