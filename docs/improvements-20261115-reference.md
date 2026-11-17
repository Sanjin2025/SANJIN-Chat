# SanjinChat 改进参考（20261115 轮次）

> 本文件记录 2026-11-15 前后对 Android 客户端（`m_wearos`）的 5 项关键改进，用于后续迭代对照与回查。所有改动基于 **20261024 版服务端**，不涉及服务端协议变更。
>
> 构建环境：JDK 26、AGP 8.7.2、Kotlin 2.2.0、Gradle 9.0.0、Material3 1.5.0-alpha14。

---

## 1. 修复完全断网时误报"登录失效"

### 问题
冷启动完全断网 → BootScreen 先拉 `/api/me` / `/sync` 失败 → 把 HTTP 错误直接当"登录失效"处理 → 强制跳登录页。实际 token 有效，连上网后自动重连即可恢复。

### 改法
- **Http 失败 ≠ 登录失效**：BootScreen 对 HTTP 层的网络异常（SocketTimeout、UnknownHost、ConnectException）与 401/403 区分处理。只有显式 401/403 才视为 token 失效；网络异常仅标记 `ConnectionState.Error("网络不可用")`，停留在 BootScreen 等待恢复。
- **WS 连接作为真正的"登录态信号"**：`ChatWebSocketClient.connect(token)` 收到服务端 `connected` 包才算恢复，期间 BootScreen 显示转圈 + "等待网络…"，不跳登录。
- **WS scope 自动重建**：新增 `ensureScope()`，若 scope 被错误 cancel（比如旧 Activity 的 onDestroy 误调了 cancel），重建一个新的，保证 12s watchdog 和 10s handshake timeout 能正常跑。

### 涉及文件
- `m_wearos/.../ui/screen/BootScreen.kt`
- `m_wearos/.../network/ChatWebSocketClient.kt` —— `ensureScope()`、12s watchdog、10s handshake timeout、`cleanupSocket(ws)` 里的实例校验

### 关键 lesson
> `PeerConnection.dispose()`、`CoroutineScope.cancel()` 这类一次性操作会级联干掉后续流程，**不要把它们放在 Activity/Fragment 的 onDestroy 里**，Application 级单例的生命周期应当独立于 UI。

---

## 2. 群聊通知：标题显示群名，内容显示"发送者：消息"

### 问题
之前群聊通知和私聊用同一模板，标题一律是应用名，正文一律是消息预览，用户无法一眼区分哪个群有人说话、谁在说话。

### 改法
- `NotificationHelper.notifyNewMessage(...)` 新增两个参数：
  - `senderName`：通知标题 —— **私聊用发送者昵称，群聊用群名**（取 `msg.groupId` 对应的 `GroupMembership.group.name`）
  - `bodySenderName`：正文里的发送者前缀 —— **群聊传发送者昵称**，格式化为 `"$nickname: ${contentPreview}"`；私聊传 `null`，正文直接是消息预览
- 用 `msg.type == "group_message"` 判断分支，在 `SanjinChatApp.startMessageNotificationListener()` 转发事件时补全这两个字段。
- **聚合同一会话**：`conversationTag = msg.groupId ?: msg.fromId`，这样同一群连续来的消息只会更新同一条通知，而不是每条弹一条。

### 涉及文件
- `m_wearos/.../notification/NotificationHelper.kt` —— 新增 `senderName` / `bodySenderName` 参数、`EXTRA_IS_GROUP` / `EXTRA_TARGET_ID` Intent extra
- `m_wearos/.../SanjinChatApp.kt` —— 通知触发处按 group/private 分支取群名

---

## 3. 点击通知直达对应会话

### 问题
通知虽然弹出来了，但 PendingIntent 全指向 MainActivity 的默认入口，点了之后还要自己找会话。

### 改法
- `NotificationHelper` 构造 PendingIntent 时在 Intent 里塞了 `EXTRA_IS_GROUP` / `EXTRA_TARGET_ID`（群聊是 `groupId`，私聊是对方 `friendId`）。
- `MainActivity.onCreate` 解析这两个 extra，通过 `NavController` 直接 navigate 到对应的 `ChatScreen`：
  - 私聊：`navigate("chat/private/<friendId>/<friendName>")`
  - 群聊：`navigate("chat/group/<groupId>/<groupName>")`
- `ChatScreen` 里用 `LaunchedEffect` 在进入时调 `webSocketClient.clearUnread(convId)`，点进会话自动消红点。

### 涉及文件
- `m_wearos/.../notification/NotificationHelper.kt` —— PendingIntent 携带会话信息
- `m_wearos/.../MainActivity.kt` —— onCreate 解析 extra + 直达路由

### 路由约定
```kotlin
// NavHost
composable("chat/private/{friendId}/{friendName}") { ... }
composable("chat/group/{groupId}/{groupName}")   { ... }
```

---

## 4. 聊天列表未读红点 + 未读会话自动置顶

### 问题
主界面 MainScreen 的会话列表按最后消息时间排序，但没有"未读"视觉提示，也不会把有未读的会话顶到最上面。

### 改法
- `ChatWebSocketClient` 增加 `_unread: MutableStateFlow<Map<String, Int>>`，key 是 `MessageCache.privateConvId / groupConvId`，方法：
  - `bumpUnread(convId)` —— 收到不属于当前聊天界面的消息时 +1
  - `clearUnread(convId)` —— 进入对应 ChatScreen 时清零
- `SanjinChatApp` 的事件监听里，只有 `currentScreenConvId != 新消息.convId` 时才 `bumpUnread`，避免自己正在看的会话也累加。
- `MainScreen` 会话列表排序规则改为 **(未读数 > 0) 优先，其次按 lastMessageTs 降序**，未读会话顶上加一个小红点（`🔴` 图标或 CircleIndicator）。

### 涉及文件
- `m_wearos/.../network/ChatWebSocketClient.kt` —— `_unread`、`bumpUnread()`、`clearUnread()`
- `m_wearos/.../SanjinChatApp.kt` —— 事件里条件触发 bumpUnread
- `m_wearos/.../ui/screen/MainScreen.kt` —— 列表排序 + 红点渲染

### 红点与置顶的优先级
```kotlin
val sorted = convs.sortedWith(
    compareByDescending<Conv> { unread[it.convId] ?: 0 > 0 }
        .thenByDescending { it.lastMessageTs }
)
```

---

## 5. 修复主界面旧服务器地址覆盖导致好友列表为空

### 问题
应用更新后，新代码把默认服务器从 `sanjin666.dpdns.org` 改成了新地址，但 DataStore 里存的还是旧域名 → WS 连到旧服务端（可能已下线） → 好友列表永远拿不到数据。用户以为是好友全被清了。

### 改法
在 `SanjinChatApp.onCreate` 里加一个**一次性迁移逻辑**：
```kotlin
var savedAddress = runBlocking { preferenceManager.serverAddress.first() }
// 老版本迁移：硬编码一个需要强制替换的旧域名集合
val legacyHosts = listOf("sanjin666.dpdns.org")
if (legacyHosts.any { savedAddress.contains(it) }) {
    savedAddress = defaultAddress
    runBlocking { preferenceManager.saveServerAddress(defaultAddress) }
}
```
这样升级覆盖安装后，旧域名用户自动被迁到新服务器，不再卡在"空好友列表"。

### 涉及文件
- `m_wearos/.../SanjinChatApp.kt` —— onCreate 里 defaultAddress 初始化后的迁移块

### 注意事项
- 这段逻辑只匹配已知的老域名；如果以后再换域名，往 `legacyHosts` 列表里追加即可。
- 如果有用户手动配了这个旧域名，**也会被强制迁走** —— 权衡下来这是合理的，因为旧域名大概率已不可用。

---

## 服务端兼容性

所有改动基于 **20261024 版服务端**，仅依赖以下既有协议：

| 协议 | 用途 |
|------|------|
| `POST /api/me`、`POST /api/sync` | BootScreen 登录态校验 |
| `WS connected` 包 | 恢复登录信号 |
| `friend_request:new` / `friends:update` | 好友列表 |
| `private_message` / `group_message` | 通知触发 + 未读计数 |
| 消息结构 `{ fromId, fromNickname, toId, groupId, content, contentType }` | 通知标题/正文拼接 |

20261024 之后的服务端版本全部向后兼容，无需为这 5 项做任何服务端改动。
