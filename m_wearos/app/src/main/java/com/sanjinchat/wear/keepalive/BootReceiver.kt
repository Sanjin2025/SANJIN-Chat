package com.sanjinchat.wear.keepalive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 开机自启动：
 * - 小天才等定制 ROM 默认禁止普通应用自启，此 Receiver 仅作为兜底入口；
 *   真正的保活依赖 RootKeepAlive 写入的自启白名单 + KeepAliveService 的 START_STICKY。
 * - 收到 BOOT_COMPLETED / 应用更新 时只拉起前台保活服务（后台运行），
 *   不主动打开界面，避免用户正在使用其他应用时被突然弹出的聊天界面打断。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        runCatching {
            val svc = Intent(context, com.sanjinchat.wear.KeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svc)
            } else {
                context.startService(svc)
            }
        }
    }
}
