// ==================== 用户数据模型 ====================
package com.sanjin.wear.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

/**
 * 用户信息
 */
@Serializable
data class User(
    val id: String,                      // 用户ID
    val username: String,                // 用户名（登录账号）
    val nickname: String? = null,        // 昵称
    val avatar: String? = null,          // 头像 URL (image 模式)
    @SerialName("avatarType") val avatarType: String? = null, // "image" / "emoji"
    @SerialName("avatarEmoji") val avatarEmoji: String? = null, // Emoji 头像 🐱🐶🐰
    val role: String = "user",           // user / admin
    @SerialName("createdAt") val createdAt: Long = 0L,
) {
    /** 用于显示的名称：昵称优先，空则用用户名 */
    val displayName: String get() = nickname?.ifBlank { null } ?: username

    /** 用于显示的头像 Emoji（或默认） */
    val displayEmoji: String get() = if (avatarType == "emoji" && !avatarEmoji.isNullOrBlank()) avatarEmoji else "👤"
}

/**
 * 群聊信息
 */
@Serializable
data class GroupInfo(
    val id: String,                           // 群ID
    @SerialName("groupNumber") val groupNumber: String, // 6位群号
    val name: String,                         // 群名称
    val description: String? = null,          // 群描述
    @SerialName("ownerId") val ownerId: String,  // 群主ID
    @SerialName("hasPassword") val hasPassword: Boolean = false,
    @SerialName("createdAt") val createdAt: Long = 0L,
)

/**
 * 群成员关系（含角色）
 */
@Serializable
data class GroupMember(
    @SerialName("userId") val userId: String,
    val role: String = "member",   // owner / admin / member
    @SerialName("mutedUntil") val mutedUntil: Long = 0L,
    @SerialName("joinedAt") val joinedAt: Long = 0L,
    val user: User? = null
)

/**
 * 群信息 + 自己在群里的身份
 */
@Serializable
data class GroupMembership(
    val group: GroupInfo,
    val role: String = "member",
    @SerialName("mutedUntil") val mutedUntil: Long = 0L,
    @SerialName("joinedAt") val joinedAt: Long = 0L,
    @SerialName("memberCount") val memberCount: Int = 0,
)

/**
 * 好友信息
 */
@Serializable
data class Friend(
    @SerialName("userId") val userId: String,
    val user: User,
    @SerialName("since") val since: Long = 0L,
    val online: Boolean = false,
)

/**
 * 单条消息（私聊/群聊 通用）
 */
@Serializable
data class ChatMessage(
    val id: String = "",
    val type: String = "private_message", // private_message / group_message
    @SerialName("fromId") val fromId: String = "",
    @SerialName("fromNickname") val fromNickname: String? = null,
    @SerialName("toId") val toId: String? = null,       // 私聊时对方 ID
    @SerialName("groupId") val groupId: String? = null,  // 群聊时群 ID
    @SerialName("contentType") val contentType: String = "text", // text / image / audio
    val content: String = "",                            // 文字内容
    val image: String? = null,                           // 图片 URL
    val audio: String? = null,                           // 语音 URL
    val duration: Int? = null,                           // 语音时长（秒）
    val timestamp: Long = System.currentTimeMillis()
) {
    /** 判断是否自己发的消息 */
    fun isMine(myId: String): Boolean = fromId == myId

    /** 显示时间（HH:mm） */
    fun displayTime(): String {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = timestamp }
        val h = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val m = cal.get(java.util.Calendar.MINUTE)
        return String.format("%02d:%02d", h, m)
    }

    /** 预览文本（用于消息列表） */
    val preview: String get() = when {
        contentType == "image" -> "[图片]"
        contentType == "audio" -> "[语音 ${duration ?: 0}s]"
        content.isNotBlank() -> content
        else -> "(消息)"
    }
}

/** 会话类型：私聊 / 群聊（顶层枚举，便于在各文件引用） */
enum class ChatType { PRIVATE, GROUP }

/**
 * 会话项（消息列表展示）
 */
data class ConversationItem(
    val chatType: ChatType,    // 私聊 or 群聊
    val targetId: String,      // 对方用户ID 或 群ID
    val title: String,         // 标题（昵称或群名）
    val avatarEmoji: String,   // Emoji 头像
    val lastMessage: String,   // 最后一条消息预览
    val lastTime: Long,        // 最后一条消息时间
    val unreadCount: Int = 0,  // 未读数量
    val online: Boolean = false, // 是否在线（仅私聊）
)

/**
 * 会话项 Builder（在加载流程中可随时更新 lastMessage / lastTime / unreadCount）
 */
class ConversationBuilder(
    var chatType: ChatType,
    var targetId: String,
    var title: String,
    var avatarEmoji: String,
    var online: Boolean = false,
    var lastMessage: String = "",
    var lastTime: Long = 0L,
    var unreadCount: Int = 0
) {
    fun build(): ConversationItem = ConversationItem(
        chatType = chatType,
        targetId = targetId,
        title = title,
        avatarEmoji = avatarEmoji,
        lastMessage = lastMessage,
        lastTime = lastTime,
        unreadCount = unreadCount,
        online = online
    )
}

/**
 * 好友申请
 */
@Serializable
data class FriendRequest(
    val id: String = "",
    @SerialName("fromId") val fromId: String = "",
    @SerialName("toId") val toId: String = "",
    val message: String = "",
    val status: String = "pending", // pending / accepted / rejected
    @SerialName("createdAt") val createdAt: Long = 0L,
    @SerialName("fromUser") val fromUser: User? = null,
    @SerialName("toUser") val toUser: User? = null,
)

/**
 * 服务器公告
 */
@Serializable
data class Announcement(
    val id: String = "",
    val title: String = "",
    val content: String = "",
    val type: String = "info",
    @SerialName("authorId") val authorId: String = "",
    @SerialName("authorNickname") val authorNickname: String = "",
    @SerialName("createdAt") val createdAt: Long = 0L,
)

/**
 * 服务器信息（关于页面）
 */
@Serializable
data class ServerInfo(
    @SerialName("serverName") val serverName: String = "三金聊天",
    @SerialName("serverDescription") val serverDescription: String = "",
    @SerialName("serverAnnouncement") val serverAnnouncement: String = "",
    val copyright: String = "",
    val logo: String? = null,
    @SerialName("logoType") val logoType: String? = null,
    @SerialName("logoEmoji") val logoEmoji: String? = null,
)

/**
 * 通用 API 响应包装
 */
@Serializable
data class ApiResponse<T>(
    val ok: Boolean = false,
    val error: String? = null,
    // login 字段
    val token: String? = null,
    val user: User? = null,
    // 列表字段
    val friends: List<Friend>? = null,
    val groups: List<GroupMembership>? = null,
    @SerialName("friend_requests") val requests: List<FriendRequest>? = null,
    val requestsList: List<FriendRequest>? = null,
    val messages: List<ChatMessage>? = null,
    val members: List<GroupMember>? = null,
    val users: List<User>? = null,
    val groupsList: List<GroupInfo>? = null,
    val announcements: List<Announcement>? = null,
    // server info
    @SerialName("serverName") val serverName: String? = null,
    // qrcode / pair
    @SerialName("qrSessionId") val qrSessionId: String? = null,
    @SerialName("qrImage") val qrImage: String? = null,
    val status: String? = null,
    @SerialName("pairCode") val pairCode: String? = null,
    @SerialName("expiresIn") val expiresIn: Int? = null,
    @SerialName("expiresAt") val expiresAt: Long? = null,
    // avatar / upload
    val url: String? = null,
    val avatar: String? = null,
    @SerialName("duration") val duration: Int? = null,
    val data: T? = null
) {
    /** 当请求失败时，抛出异常用的错误消息 */
    val errorMsg: String get() = error ?: "未知错误"
}
