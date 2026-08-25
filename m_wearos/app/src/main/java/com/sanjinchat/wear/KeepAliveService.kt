package com.sanjinchat.wear

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * 轻量保活前台服务：
 * - 挂在常驻通知栏，极大降低系统杀后台概率
 * - 不主动做网络逻辑，实际 WS 连接由 SanjinChatApp/webSocketClient 管理
 * - 启动后维持前台；用户在「保活设置」关闭时会 stopService
 */
class KeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 服务被系统回收后自动重启（有机会再把 WS 拉起来）
        return START_STICKY
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
    }
}
