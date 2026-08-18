// ==================== 全局 Application ====================
package com.sanjin.wear

import android.app.Application
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * SanjinChat 全局 Application
 * - 初始化通知渠道
 * - 提供全局协程作用域
 * - 监听 WebSocket 新消息触发震动通知
 */
class WearApp : Application() {

    /** 全局协程作用域（SupervisorJob 子任务失败互不影响） */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    companion object {
        lateinit var instance: WearApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        NotificationHelper.initChannel(this)
        // 监听新消息：震动
        appScope.launch {
            WebSocketManager.instance.notification.collectLatest { msg ->
                NotificationHelper.vibrate(this@WearApp)
            }
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        appScope.cancel()
    }
}
