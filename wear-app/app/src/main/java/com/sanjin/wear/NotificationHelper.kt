// ==================== 通知工具：震动 + 系统通知 ====================
package com.sanjin.wear

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import com.sanjin.wear.model.ChatMessage

/**
 * 通知帮助类
 * - 新消息震动
 * - 系统状态栏通知（可点击打开 ChatDetailActivity）
 */
object NotificationHelper {

    private const val CHANNEL_ID = "sanjin_msg"
    private const val NOTIF_ID = 10086

    /**
     * 初始化通知渠道（Android 8+ 需要）
     */
    fun initChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = ctx.getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
            val ch = NotificationChannel(
                CHANNEL_ID, "新消息通知",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "收到新聊天消息时通知"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 200, 100, 200)
                setSound(null, AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION).build())
            }
            mgr.createNotificationChannel(ch)
        }
    }

    /**
     * 震动（手表上最重要的通知形式）
     */
    fun vibrate(ctx: Context, pattern: LongArray = longArrayOf(0, 150, 80, 120)) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = ctx.getSystemService(VibratorManager::class.java)
                vm.defaultVibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createWaveform(pattern, -1))
                } else {
                    @Suppress("DEPRECATION")
                    v.vibrate(pattern, -1)
                }
            }
        } catch (_: Exception) {}
    }

    /**
     * 推送一条系统通知（用户点击后进入聊天详情）
     */
    fun showNewMessage(ctx: Context, msg: ChatMessage, senderName: String, chatType: String, targetId: String) {
        initChannel(ctx)
        val title = senderName
        val text = msg.preview
        val intent = Intent(ctx, ChatDetailActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(ChatDetailActivity.KEY_CHAT_TYPE, chatType)
            putExtra(ChatDetailActivity.KEY_TARGET_ID, targetId)
            putExtra(ChatDetailActivity.KEY_TITLE, title)
        }
        val pi = PendingIntent.getActivity(
            ctx, msg.id.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val nb = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
        try {
            val mgr = ctx.getSystemService(NotificationManager::class.java)
            mgr.notify(NOTIF_ID, nb.build())
        } catch (_: Exception) {}
    }

    fun cancelAll(ctx: Context) {
        try {
            ctx.getSystemService(NotificationManager::class.java).cancelAll()
        } catch (_: Exception) {}
    }
}
