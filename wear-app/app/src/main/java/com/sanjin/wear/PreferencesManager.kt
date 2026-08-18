// ==================== 配置与数据存储（DataStore Preferences） ====================
package com.sanjin.wear

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.sanjin.wear.model.User
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** DataStore 单例 */
private val Context.dataStore by preferencesDataStore(name = "sanjin_wear_prefs")

/**
 * 本地配置管理
 * - 服务器地址
 * - 登录 token
 * - 登录用户信息（简易序列化）
 * - UI 缩放级别（DPI 适配）
 * - 是否首次启动（用于显示 DPI 选择引导）
 */
class PreferencesManager(private val context: Context) {

    private object Keys {
        val SERVER = stringPreferencesKey("server_addr")
        val TOKEN  = stringPreferencesKey("auth_token")
        val USER_ID = stringPreferencesKey("user_id")
        val USER_NAME = stringPreferencesKey("user_name")
        val USER_NICK = stringPreferencesKey("user_nick")
        val USER_AVATAR_EMOJI = stringPreferencesKey("user_avatar_emoji")
        val USER_ROLE = stringPreferencesKey("user_role")
        val UI_SCALE = floatPreferencesKey("ui_scale")
        val FIRST_LAUNCH_DONE = booleanPreferencesKey("first_launch_done")
    }

    /** 默认服务器地址 */
    companion object {
        const val DEFAULT_SERVER = "your-server.com:8181"
        /** 支持的缩放级别：小(0.85) / 标准(1.0) / 大(1.15) / 特大(1.3) */
        val SUPPORTED_SCALES = listOf(0.85f, 1.0f, 1.15f, 1.3f)
        const val DEFAULT_SCALE = 1.0f
    }

    // ---------- 服务器地址 ----------
    val serverFlow: Flow<String> = context.dataStore.data.map {
        it[Keys.SERVER] ?: DEFAULT_SERVER
    }
    suspend fun getServer(): String =
        context.dataStore.data.first()[Keys.SERVER] ?: DEFAULT_SERVER

    suspend fun setServer(addr: String) {
        context.dataStore.edit { it[Keys.SERVER] = addr }
    }

    // ---------- 登录 Token ----------
    val tokenFlow: Flow<String> = context.dataStore.data.map { it[Keys.TOKEN] ?: "" }
    suspend fun getToken(): String =
        context.dataStore.data.first()[Keys.TOKEN] ?: ""

    // ---------- 保存完整登录信息 ----------
    suspend fun saveLogin(token: String, user: User) {
        context.dataStore.edit {
            it[Keys.TOKEN] = token
            it[Keys.USER_ID] = user.id
            it[Keys.USER_NAME] = user.username
            it[Keys.USER_NICK] = user.nickname ?: ""
            it[Keys.USER_AVATAR_EMOJI] = user.avatarEmoji ?: "👤"
            it[Keys.USER_ROLE] = user.role
        }
    }

    // ---------- 获取当前登录用户 ----------
    suspend fun getUser(): User? {
        val it = context.dataStore.data.first()
        val id = it[Keys.USER_ID]
        if (id.isNullOrBlank()) return null
        return User(
            id = id,
            username = it[Keys.USER_NAME] ?: "",
            nickname = it[Keys.USER_NICK]?.ifBlank { null },
            avatarEmoji = it[Keys.USER_AVATAR_EMOJI],
            avatarType = if (it[Keys.USER_AVATAR_EMOJI] != null) "emoji" else null,
            role = it[Keys.USER_ROLE] ?: "user"
        )
    }

    // ---------- 清空（退出登录） ----------
    suspend fun clearAll() {
        context.dataStore.edit {
            it.remove(Keys.TOKEN)
            it.remove(Keys.USER_ID)
            it.remove(Keys.USER_NAME)
            it.remove(Keys.USER_NICK)
            it.remove(Keys.USER_AVATAR_EMOJI)
            it.remove(Keys.USER_ROLE)
        }
    }

    // ---------- UI 缩放级别（DPI 适配） ----------
    val uiScaleFlow: Flow<Float> = context.dataStore.data.map {
        it[Keys.UI_SCALE] ?: DEFAULT_SCALE
    }
    suspend fun getUiScale(): Float =
        context.dataStore.data.first()[Keys.UI_SCALE] ?: DEFAULT_SCALE
    suspend fun setUiScale(scale: Float) {
        context.dataStore.edit { it[Keys.UI_SCALE] = scale }
    }

    // ---------- 首次启动完成标记（是否已选过 DPI） ----------
    val firstLaunchDoneFlow: Flow<Boolean> = context.dataStore.data.map {
        it[Keys.FIRST_LAUNCH_DONE] ?: false
    }
    suspend fun isFirstLaunchDone(): Boolean =
        context.dataStore.data.first()[Keys.FIRST_LAUNCH_DONE] ?: false
    suspend fun markFirstLaunchDone() {
        context.dataStore.edit { it[Keys.FIRST_LAUNCH_DONE] = true }
    }
    suspend fun resetFirstLaunch() {
        context.dataStore.edit { it.remove(Keys.FIRST_LAUNCH_DONE) }
    }
}
