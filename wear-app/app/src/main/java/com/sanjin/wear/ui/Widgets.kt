// ==================== 通用 UI 小组件 ====================
package com.sanjin.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Card
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Circle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 头像组件：显示 Emoji + 可选在线小绿点 / 未读红点
 */
@Composable
fun Avatar(
    emoji: String,
    modifier: Modifier = Modifier,
    size: Int = 36,
    showOnline: Boolean = false,
    online: Boolean = false,
    showUnread: Boolean = false,
    unreadCount: Int = 0
) {
    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .size(size.dp)
                .clip(CircleShape)
                .background(WearColors.SurfaceVariant)
                .border(BorderStroke(1.dp, WearColors.Divider), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = emoji,
                fontSize = (size * 0.55).sp
            )
        }
        // 在线小圆点
        if (showOnline) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (online) WearColors.Online else WearColors.TextHint)
                    .border(1.5.dp, MaterialTheme.colors.background, CircleShape)
            )
        }
        // 未读小圆点
        if (showUnread && unreadCount > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(if (unreadCount < 10) 10.dp else 14.dp)
                    .clip(CircleShape)
                    .background(WearColors.Unread),
                contentAlignment = Alignment.Center
            ) {
                if (unreadCount >= 10) {
                    Text("!", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/**
 * 卡片容器（列表项使用）
 */
@Composable
fun WearCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        backgroundColor = MaterialTheme.colors.surface,
        shape = RoundedCornerShape(12.dp),
        elevation = 2.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(10.dp)) { content() }
    }
}

/**
 * 会话列表项（消息列表）
 */
@Composable
fun ConversationItem(
    title: String,
    preview: String,
    time: String,
    avatarEmoji: String,
    unread: Int = 0,
    online: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Avatar(
            emoji = avatarEmoji,
            size = 40,
            showOnline = true,
            online = online,
            showUnread = unread > 0,
            unreadCount = unread
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.subtitle1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = time,
                    style = MaterialTheme.typography.caption,
                    color = WearColors.TextSecondary
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = preview,
                style = MaterialTheme.typography.body2,
                color = WearColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 加载中指示
 */
@Composable
fun LoadingIndicator(label: String = "加载中…") {
    Column(
        modifier = Modifier.fillMaxWidth().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        androidx.compose.material.CircularProgressIndicator(
            color = MaterialTheme.colors.primary,
            strokeWidth = 2.dp,
            modifier = Modifier.size(28.dp)
        )
        Spacer(Modifier.height(10.dp))
        Text(label, style = MaterialTheme.typography.body2, color = WearColors.TextSecondary)
    }
}

/**
 * 分隔线
 */
@Composable
fun Divider() {
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(WearColors.Divider))
}

/**
 * 顶部标题栏
 */
@Composable
fun TopBar(title: String, left: @Composable (() -> Unit)? = null, right: @Composable (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        left?.invoke()
        Text(
            text = title,
            style = MaterialTheme.typography.h6,
            modifier = Modifier.weight(1f)
        )
        right?.invoke()
    }
}

/**
 * 空状态（无消息、无好友等）
 */
@Composable
fun EmptyState(text: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("😴", fontSize = 36.sp)
        Spacer(Modifier.height(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.body2,
            color = WearColors.TextSecondary,
            lineHeight = 18.sp
        )
    }
}
