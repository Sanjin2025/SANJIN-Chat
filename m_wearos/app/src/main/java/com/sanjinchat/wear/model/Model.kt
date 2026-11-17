package com.sanjinchat.wear.model

data class User(
    val id: String,
    val username: String,
    val nickname: String,
    val avatar: String? = null,
    val avatarType: String? = null,
    val avatarEmoji: String? = null,
    val role: String = "user",   // "admin" 表示管理员
    val createdAt: Long = 0L
)

data class Friend(
    val userId: String,
    val user: User,
    val since: Long,
    val online: Boolean
)

data class GroupInfo(
    val id: String,
    val groupNumber: String,
    val name: String,
    val description: String,
    val ownerId: String,
    val hasPassword: Boolean,
    val createdAt: Long
)

data class GroupMembership(
    val group: GroupInfo,
    val role: String,
    val mutedUntil: Long,
    val joinedAt: Long,
    val memberCount: Int
)

data class ChatMessage(
    val id: String,
    val type: String,
    val fromId: String,
    val fromNickname: String,
    val toId: String? = null,
    val groupId: String? = null,
    val contentType: String,
    val content: String,
    val image: String? = null,
    val audio: String? = null,
    val video: String? = null,
    // 通话记录消息（contentType = "call"）专用字段
    val callMedia: String? = null,   // "audio" / "video"
    val callSeconds: Int = 0,        // 通话时长（秒），0 表示未接通
    val timestamp: Long
)

data class FriendRequest(
    val id: String,
    val fromId: String,
    val toId: String,
    val message: String,
    val status: String,
    val createdAt: Long,
    val fromUser: User? = null,
    val toUser: User? = null
)

sealed class ChatTarget {
    data class Private(val friendId: String, val friend: User) : ChatTarget()
    data class Group(val groupId: String, val group: GroupInfo) : ChatTarget()
}
