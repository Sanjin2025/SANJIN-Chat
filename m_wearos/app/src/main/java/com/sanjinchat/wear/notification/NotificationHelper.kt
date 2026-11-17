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
            if (mgr.getNotificationChannel(CHANNEL_CALLS) == null) {
                val callChannel = NotificationChannel(
                    CHANNEL_CALLS,
                    "通话来电",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "语音/视频通话来电提醒"
                    enableVibration(true)
                    enableLights(true)
                    setSound(
                        android.provider.Settings.System.DEFAULT_RINGTONE_URI,
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                }
                mgr.createNotificationChannel(callChannel)
            }
            if (mgr.getNotificationChannel(CHANNEL_DEVICES) == null) {
                val dc = NotificationChannel(
                    CHANNEL_DEVICES,
                    "登录安全提醒",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "账号在新设备上登录 / 被取消登录的提醒"
                    enableVibration(true); enableLights(true)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                }
                mgr.createNotificationChannel(dc)
            }
        }
    }

    /**
     * 来电通知：无论应用在前台还是后台，都在系统通知栏显示。
     * 「接听」会拉起 MainActivity 并自动接听，「拒绝」直接拒绝。
     */
    fun showIncomingCallNotification(peerId: String, peerName: String, media: String) {
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        val isVideo = media == "video"
        val title = if (isVideo) "视频通话邀请" else "语音通话邀请"
        val text = "$peerName 正在呼叫你"

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_ACCEPT_CALL, true)
        }
        val openPi = PendingIntent.getActivity(
            context, CALL_NOTIFICATION_ID, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val declinePi = PendingIntent.getBroadcast(
            context, CALL_NOTIFICATION_ID + 1,
            Intent(context, CallActionReceiver::class.java).setAction(ACTION_DECLINE_CALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_message)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(openPi)
            .setFullScreenIntent(openPi, true)
            .addAction(0, "拒绝", declinePi)
            .addAction(0, "接听", openPi)

        mgr.notify(CALL_NOTIFICATION_ID, builder.build())
    }

    fun cancelIncomingCallNotification() {
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching { mgr.cancel(CALL_NOTIFICATION_ID) }
    }

    /**
     * 发送消息通知。
     * @param msg 收到的消息
     * @param senderName 通知标题（群消息为群名，私聊为发送者昵称）
     * @param conversationTag 用于聚合同会话通知（friendId 或 groupId）
     * @param bodySenderName 非空时正文格式为「该名称：内容」（群消息传发送者昵称）
     */
    fun showMessageNotification(
        msg: ChatMessage,
        senderName: String,
        conversationTag: String,
        bodySenderName: String? = null,
        extraName: String? = null
    ) {
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return

        val rawText = when (msg.contentType) {
            "image" -> "[图片]"
            "audio" -> "[语音]"
            else -> msg.content.ifBlank { "[消息]" }
        }
        val contentText = if (bodySenderName.isNullOrBlank()) rawText else "$bodySenderName：$rawText"

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            // 通过 extra 通知 MainActivity 进入对应会话（MainActivity 可选实现）
            putExtra(EXTRA_CONVERSATION_TAG, conversationTag)
            putExtra(EXTRA_IS_GROUP, msg.type == "group_message")
            putExtra(EXTRA_TARGET_ID, msg.groupId ?: msg.fromId)
            putExtra(EXTRA_CONVERSATION_NAME, extraName ?: senderName)
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

    /** 新设备登录提醒：推给同账号的其他在线设备 */
    fun showDeviceLoginNotification(deviceName: String, ip: String?) {
        val builder = NotificationCompat.Builder(context, CHANNEL_DEVICES)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("你的账号在新设备上登录")
            .setContentText(buildString {
                append(deviceName)
                if (!ip.isNullOrBlank()) append("（$ip）")
            })
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    buildString {
                        append("设备：").append(deviceName).append('\n')
                        if (!ip.isNullOrBlank()) append("IP：").append(ip).append('\n')
                        append("如非本人操作，请立即到「设置 → 登录设备」取消该设备登录")
                    }
                )
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        try { mgr.notify(DEVICE_NOTIFICATION_ID, builder.build()) } catch (_: SecurityException) { /* 通知权限未开 */ }
    }

    /** 本设备在另一台设备上被取消登录（device:logout 推过来时用） */
    fun showDeviceKickedNotification(reason: String) {
        val builder = NotificationCompat.Builder(context, CHANNEL_DEVICES)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("已被取消登录")
            .setContentText(reason)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        try { mgr.notify(DEVICE_NOTIFICATION_ID + 1, builder.build()) } catch (_: SecurityException) { /* ignore */ }
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
        const val CHANNEL_CALLS = "sanjinchat_calls"
        const val CHANNEL_DEVICES = "sanjinchat_devices"
        const val GROUP_MESSAGES = "sanjinchat_messages_group"
        const val CALL_NOTIFICATION_ID = 0x5A11
        const val DEVICE_NOTIFICATION_ID = 0x5A20
        const val ACTION_DECLINE_CALL = "com.sanjinchat.wear.action.DECLINE_CALL"
        const val EXTRA_ACCEPT_CALL = "extra_accept_call"
        const val EXTRA_CONVERSATION_TAG = "extra_conversation_tag"
        const val EXTRA_IS_GROUP = "extra_is_group"
        const val EXTRA_TARGET_ID = "extra_target_id"
        const val EXTRA_CONVERSATION_NAME = "extra_conversation_name"
    }
}
