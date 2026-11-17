package com.sanjinchat.wear.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sanjinchat.wear.SanjinChatApp

/** 处理来电通知上的「拒绝」按钮 */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == NotificationHelper.ACTION_DECLINE_CALL) {
            runCatching { SanjinChatApp.instance.callManager.reject() }
            runCatching { SanjinChatApp.instance.notificationHelper.cancelIncomingCallNotification() }
        }
    }
}
