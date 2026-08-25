package com.sanjinchat.wear.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.sanjinchat.wear.MainActivity
import com.sanjinchat.wear.R
import com.sanjinchat.wear.model.ChatMessage

/**
 * 安卓消息系统接入：
 * - 创建消息通知 Channel
 * - 在收到新私聊/群聊消息且当前不在对应聊天界面时，发送系统通知
 */
class NotificationHelper(private val context: Context) {

    init {
        createChannel()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = context.getSystemService(NotificationManager::class.java) ?: return
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "消息通知",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "接收新的私聊与群聊消息通知"
                    enableVibration(true)
                    enableLights(true)
                }
                mgr.createNotificationChannel(channel)
            }
        }
    }

    /**
     * 发送消息通知。
     * @param msg 收到的消息
     * @param senderName 用于显示的发送方名称（好友昵称 / 群名+发送者）
     * @param conversationTag 用于聚合同会话通知（friendId 或 groupId）
     */
    fun showMessageNotification(
        msg: ChatMessage,
        senderName: String,
        conversationTag: String
    ) {
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return

        val contentText = when (msg.contentType) {
            "image" -> "[图片]"
            "audio" -> "[语音]"
            else -> msg.content.ifBlank { "[消息]" }
        }

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            // 通过 extra 通知 MainActivity 进入对应会话（MainActivity 可选实现）
            putExtra(EXTRA_CONVERSATION_TAG, conversationTag)
            putExtra(EXTRA_IS_GROUP, msg.type == "group_message")
            putExtra(EXTRA_TARGET_ID, msg.groupId ?: msg.fromId)
        }

        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val contentIntent = PendingIntent.getActivity(
            context,
            conversationTag.hashCode(),
            tapIntent,
            pendingFlags
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_message)
            .setContentTitle(senderName)
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(contentIntent)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setGroup(GROUP_MESSAGES)

        // 颜色：使用 accent 色（如果有），避免传入无效颜色
        runCatching {
            val ta = context.theme
            val attrs = intArrayOf(android.R.attr.colorAccent)
            val arr = ta.obtainStyledAttributes(attrs)
            val color = arr.getColor(0, 0)
            arr.recycle()
            if (color != 0) builder.setColor(color)
        }

        mgr.notify(conversationTag.hashCode(), builder.build())
    }

    /**
     * 取消指定会话的通知（用户进入对应聊天界面时调用）
     */
    fun cancel(conversationTag: String) {
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        mgr.cancel(conversationTag.hashCode())
    }

    companion object {
        const val CHANNEL_ID = "sanjinchat_messages"
        const val GROUP_MESSAGES = "sanjinchat_messages_group"
        const val EXTRA_CONVERSATION_TAG = "extra_conversation_tag"
        const val EXTRA_IS_GROUP = "extra_is_group"
        const val EXTRA_TARGET_ID = "extra_target_id"
    }
}
