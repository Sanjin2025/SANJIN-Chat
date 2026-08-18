package com.sanjinchat.wear

import android.app.Application
import com.sanjinchat.wear.data.PreferenceManager
import com.sanjinchat.wear.network.ApiClient
import com.sanjinchat.wear.network.ChatWebSocketClient

class SanjinChatApp : Application() {

    lateinit var preferenceManager: PreferenceManager
        private set

    lateinit var apiClient: ApiClient
        private set

    lateinit var webSocketClient: ChatWebSocketClient
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        preferenceManager = PreferenceManager(this)
        apiClient = ApiClient(this)
        webSocketClient = ChatWebSocketClient()
    }

    companion object {
        lateinit var instance: SanjinChatApp
            private set
    }
}
