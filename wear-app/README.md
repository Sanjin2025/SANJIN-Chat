# SanjinChat 手表客户端 (SanjinChat Wear OS App)

**三金聊天** Android Wear OS / 智能手表客户端

## 技术栈
- **语言**: Kotlin
- **UI**: Jetpack Compose (自动适配圆/方屏)
- **最低版本**: Android 8.0 (API 26)
- **目标版本**: Android 14 (API 34)
- **网络**: OkHttp + WebSocket
- **序列化**: kotlinx.serialization
- **存储**: DataStore Preferences

## 目录结构
```
wear-app/
├── build.gradle.kts          # 顶层构建
├── settings.gradle.kts       # 项目设置
├── gradle.properties         # 全局属性
└── app/
    ├── build.gradle.kts      # App 构建配置
    ├── proguard-rules.pro    # 混淆规则
    └── src/main/
        ├── AndroidManifest.xml
        ├── res/
        │   ├── values/       (colors, strings, themes)
        │   └── drawable/     (气泡, 卡片, 图标等 shape)
        └── java/com/sanjin/wear/
            ├── WearApp.kt                  # 全局 Application
            ├── LoginActivity.kt            # 登录（账密/配对码）
            ├── ChatListActivity.kt         # 主界面（消息/好友/更多 Tab）
            ├── ChatDetailActivity.kt       # 聊天详情（语音+预设）
            ├── SettingsActivity.kt         # 设置
            ├── PreferencesManager.kt       # DataStore 存储
            ├── ApiClient.kt                # OkHttp REST 客户端
            ├── WebSocketManager.kt         # WS 实时通讯+事件分发
            ├── NotificationHelper.kt       # 震动+系统通知
            ├── SpeechHelper.kt             # 语音识别（文字）
            ├── model/Models.kt             # 数据模型
            └── ui/
                ├── Theme.kt                # MD2 深色主题
                └── Widgets.kt              # 通用控件（头像、气泡、列表项）
```

## 编译运行
```bash
cd wear-app
# Windows 下使用 gradlew.bat（或 Android Studio 打开项目直接运行）
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

## 核心功能
| 类别 | 功能 |
|------|------|
| 登录 | 账密登录、6 位配对码登录、注册 |
| 列表 | 消息会话（私聊+群聊）、好友/群聊列表、未读圆点 |
| 聊天 | 左右气泡、输入文字、**语音识别转文字**、**6 个预设快捷回复** |
| 通知 | 新消息震动 + 系统通知 |
| 省电 | 屏幕暂停时断开 WebSocket，resume 时重连 |
| 深色 | MD2 纯黑背景 + 靛蓝主色，适配 OLED 省电 |
| 圆/方屏 | Jetpack Compose + Wear 组件，自动适配 |

## 连接服务端
默认服务器地址 `your-server.com:8181`，可在登录页或设置页修改并保存。
