# SanjinChat · 三金聊天

一个轻量级、全栈、零数据库依赖的实时聊天系统。包含 **Web 客户端**、**Wear OS 手表客户端（MD2 / MD3 两版）**与 **Node.js WebSocket 服务端**，支持一对一、群组、图片/语音消息、配对码/二维码登录等功能，部署只需一条命令。

> 「三金」取自中文寓意：珍贵、坚实、朋友之间的情谊。纯个人学习/小型社群使用场景。

---

## 目录

- [特性一览](#特性一览)
- [快速启动](#快速启动)
- [TLS / WSS 加密模式](#tls--wss-加密模式)
- [功能截图（占位）](#功能截图占位)
- [项目结构](#项目结构)
- [技术栈](#技术栈)
- [API 协议简表](#api-协议简表)
- [构建手表客户端](#构建手表客户端)
- [部署建议](#部署建议)
- [常见问题](#常见问题)
- [开源协议](#开源协议)

---

## 特性一览

### 服务端
- **零数据库依赖**：所有数据以 JSON 文件存储于 `data/` 目录，开箱即用，备份即复制文件夹
- **一键启动**：Windows `start.bat` / 跨平台 `node server.js`，自动检测并安装 Node.js 与依赖
- **ws / wss 双模**：明文 HTTP+ws 或 HTTPS+wss 可选，自签证书一键生成
- **双鉴权体系**：账密登录 / 扫码登录 / 配对码登录三种方式
- **每日清理**：自动清理过期上传文件（默认 10 天），定期压缩存储
- **彩色启动界面**：`[OK]`/`[警告]`/`[错误]`/`[信息]` 分级输出，一眼看清运行状态

### Web 客户端
- 单文件 SPA：`public/index.html`，无需打包，直接由服务端托管
- 支持文字 / 图片 / 语音消息（录制直接 MP3/WebM 上传）
- 好友系统、群聊系统、在线状态、离线消息
- 公告栏、服务器 Logo/名称/描述 自定义
- 响应式布局，移动端/桌面端自适应
- 自动根据页面协议选择 ws 或 wss（无需手动配置）

### Wear OS 手表客户端（两种实现，共存于仓库）
| 目录 | 风格 | UI 框架 | 最低版本 | 包名 |
|------|------|---------|----------|------|
| `wear-app/` | Material Design 2 | Jetpack Compose (Wear Compose) | Android 8.0 (API 26) | `com.sanjin.wear` |
| `wearos/`   | Material Design 3 | Jetpack Compose (Wear Compose) | Android 9.0 (API 28) | `com.sanjinchat.wear` |

- 账密登录 / 配对码登录
- 语音转文字 + 手写/快捷回复
- 消息列表、会话详情、离线消息拉取
- 支持 ws / wss 切换
- 自动兼容圆形 / 方形 / 圆角屏

---

## 快速启动

### 环境要求
- Windows 10 / 11（推荐使用 `start.bat` 启动脚本）
- 或任意平台：**Node.js 18+**
- 内存：建议 ≥ 512 MB
- 磁盘：建议 ≥ 200 MB（视聊天/上传规模）

### 方式一：Windows 一键启动（推荐新手）

```powershell
# 进入项目根目录
cd SanjinChat

# 双击 start.bat，或命令行执行
start.bat
```

启动脚本会自动：
1. 检测 Node.js，缺失时尝试 `winget` 安装或下载便携版
2. 检测并自动 `npm install` 缺失依赖（`ws`、`bcryptjs`、`qrcode`）
3. 创建数据目录
4. **启动前询问模式**：
   ```
   [1] ws   (HTTP  明文，内网/测试推荐)
   [2] wss  (HTTPS 加密，公网/生产推荐)
   ```
5. 启动服务，显示访问地址

### 方式二：跨平台手动启动

```bash
# 1. 安装依赖
npm install

# 2. 直接启动（默认 HTTP + ws 明文模式）
node server.js
```

启动成功后访问：
- **ws 模式**：<http://localhost:8181>
- **wss 模式**：<https://localhost:8181>（首次使用自签证书需要在浏览器点「高级 → 继续访问」）

> **首次使用**：访问页面 → 注册账号 → 登录 → 邀请好友注册后互相搜索用户名添加，或创建/加入群聊。首个注册账号自动获得「管理员」权限。

---

## TLS / WSS 加密模式

### 启用方式

启动时选择 `[2] wss`，或直接配置以下方式之一：

**方式 A：环境变量**
```bash
SANJIN_TLS=1 node server.js
```

**方式 B：配置文件 `tls.json`（项目根目录）**
```json
{
  "enabled": true,
  "cert": "certs/fullchain.pem",
  "key":  "certs/privkey.pem"
}
```

**方式 C：生产级正式证书**（例如 Let's Encrypt），放到对应路径即可

### 证书自动生成

若选择 wss 模式且证书文件缺失，启动脚本会自动搜索系统 `openssl`（优先 Git for Windows 自带），生成 **10 年有效期的自签名证书** 并启用。

> 自签名证书在浏览器 / 手表客户端会提示「不信任」——内网测试没问题；公网建议使用正式 CA 证书。

---

## 功能截图（占位）

将你项目的真实截图放到 `assets/screenshots/` 目录下，替换下面的路径：

| 登录页 | 聊天主界面 | 手表消息列表 |
|--------|------------|--------------|
| ![login](assets/screenshots/login.png) | ![chat](assets/screenshots/chat.png) | ![wear](assets/screenshots/wear.png) |

| 群聊详情 | 设置页 | 公告管理 |
|----------|--------|----------|
| ![group](assets/screenshots/group.png) | ![settings](assets/screenshots/settings.png) | ![admin](assets/screenshots/admin.png) |

---

## 项目结构

```
SanjinChat/
├── server.js                 # 服务端入口（HTTP/HTTPS + WebSocket 路由）
├── package.json              # 依赖定义
├── start.bat                 # Windows 一键启动（彩色界面）
├── start.ps1                 # 启动脚本核心（TLS 切换/证书生成/Node.js 自动安装）
├── .gitignore                # 开源忽略清单
│
├── public/
│   └── index.html            # Web 客户端 SPA（单文件，无需打包）
│
├── src/                      # 服务端业务代码（模块化拆分）
│   ├── storage.js            # 持久化层（JSON 文件存储，读写锁）
│   ├── auth.js               # 用户鉴权、注册、登录、Token、配对码/二维码
│   ├── chatServer.js         # WebSocket 消息路由、在线状态、消息分发
│   ├── announcement.js       # 公告管理
│   ├── serverInfo.js         # 服务器信息（Logo/名称/描述/关于）
│   ├── qrcode.js             # 二维码生成
│   ├── pair.js               # 配对码登录逻辑
│   ├── multipart.js          # 上传文件解析（头像/Logo/图片/语音）
│   └── cleanup.js            # 每日清理定时器
│
├── wear-app/                 # Wear OS 手表客户端 · MD2 风格
│   └── 详见 wear-app/README.md
│
├── wearos/                   # Wear OS 手表客户端 · MD3 风格
│   └── app/src/main/java/com/sanjinchat/wear/
│
├── data/                     # 运行时数据（.gitignore 忽略）
│   ├── users.json            # 用户库
│   ├── chats/                # 私聊消息
│   ├── groups/               # 群聊信息 + 消息
│   ├── tokens/               # 登录 Token 映射
│   ├── announcements.json    # 公告
│   ├── serverInfo.json       # 服务器配置
│   └── uploads/              # 上传文件（头像/Logo/图片/语音）
│
└── certs/                    # TLS 证书（.gitignore 忽略）
    ├── fullchain.pem
    └── privkey.pem
```

---

## 技术栈

| 层次 | 技术 |
|------|------|
| **服务端运行时** | Node.js 18+ |
| **HTTP / WebSocket** | 原生 `http` + `https` 模块 + [ws](https://www.npmjs.com/package/ws) |
| **鉴权加密** | [bcryptjs](https://www.npmjs.com/package/bcryptjs)（密码哈希）+ HMAC Token |
| **二维码** | [qrcode](https://www.npmjs.com/package/qrcode) |
| **存储** | 零数据库；JSON 文件 + 文件级读写锁 |
| **Web 前端** | 纯原生 HTML/CSS/JavaScript ES6+，零依赖打包工具 |
| **手表客户端** | Kotlin + Jetpack Compose (Wear Compose) + OkHttp + kotlinx.serialization + DataStore |
| **构建工具** | Node.js 自带 npm；Android Gradle Plugin 8.x + Gradle 8/9 |

---

## API 协议简表

### HTTP API（前缀 `/api/`）
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/register` | 注册账号 |
| POST | `/login` | 账密登录 |
| POST | `/login/pair` | 配对码登录 |
| POST | `/logout` | 退出登录 |
| GET  | `/api/server/info` | 服务器信息 |
| PUT  | `/api/server/info` | 修改服务器信息（管理员） |
| GET  | `/api/user/me` | 当前用户资料 |
| GET  | `/api/user/search?username=` | 搜索用户 |
| POST | `/api/friends/add` | 发送好友请求 |
| GET  | `/api/friends` / `/api/friends/requests` | 好友列表 / 请求列表 |
| POST | `/api/friends/accept` / `reject` | 接受 / 拒绝请求 |
| GET  | `/api/groups` | 我加入的群 |
| POST | `/api/groups/create` / `join` | 创建 / 加入群 |
| GET  | `/api/messages/:type/:id?before=` | 拉取历史消息（分页） |
| GET  | `/api/announcements` | 公告列表 |
| POST | `/api/server/logo` (FormData) | 上传 Logo（管理员） |
| PUT  | `/api/server/logo/emoji` | 设置 Emoji Logo（管理员） |

### WebSocket 协议（`/ws?token=XXX`）

客户端与服务器通过 JSON 消息双向通信，典型消息类型：
```jsonc
// 客户端发送
{"type":"presence","online":true}                        // 在线心跳
{"type":"message","targetType":"user","targetId":"xxx","content":"...","msgType":"text"}
{"type":"typing","targetType":"user","targetId":"xxx"}   // 输入提示

// 服务端推送
{"type":"presence","userId":"xxx","online":true,"ts":1720000000000}
{"type":"message","id":"xxx","fromType":"user","fromId":"xxx","targetType":"user","targetId":"yyy","content":"...","msgType":"text","ts":1720000000000}
{"type":"new_friend_request","fromUser":{...}}
```

> 完整字段定义见 `src/chatServer.js` 的 `handleMessage()` 分支。

---

## 构建手表客户端

### 前置要求
- Android Studio Hedgehog (2023.1.1) 或 IntelliJ IDEA
- JDK 17+（AGP 8.x 推荐 JDK 17；AGP 8.7+ 可使用 JDK 21）
- Android SDK（compileSdk 34 或 35，minSdk 26 / 28）

### 命令行构建（任选一个客户端）

```bash
# ===== MD2 风格 (wear-app/) =====
cd wear-app
# Windows
.\gradlew.bat assembleDebug
# macOS / Linux
./gradlew assembleDebug
# 产物: app/build/outputs/apk/debug/app-debug.apk

# ===== MD3 风格 (wearos/) =====
cd wearos
# Windows
.\gradlew.bat assembleDebug
# macOS / Linux
./gradlew assembleDebug
# 产物: app/build/outputs/apk/debug/app-debug.apk
```

### 安装到手表

```bash
# 手表启用「开发者选项 → USB/蓝牙调试」并配对
adb connect <手表IP>:5555
adb install -r app-debug.apk
```

### 修改默认服务器地址

手表内置默认地址 `your-server.com:8181`，首次启动时可在登录页 / 引导页 / 设置页修改。若想硬编码自己的域名：

- **MD3 (wearos)**：`wearos/app/src/main/java/com/sanjinchat/wear/data/PreferenceManager.kt` 的 `SERVER_ADDRESS` 默认值；同步修改 `wearos/app/src/main/res/xml/network_security_config.xml` 的域名白名单
- **MD2 (wear-app)**：`wear-app/app/src/main/java/com/sanjin/wear/PreferencesManager.kt` 的 `DEFAULT_SERVER`

---

## 部署建议

### 小型内网部署
```
用户 <--LAN--> 家里的 Windows 机器（8181 端口）
    ↳ 直接使用 start.bat 选 ws 模式即可
    ↳ 如手表客户端在外网，需配置内网穿透（frp、ZeroTier、ddnspod、花生壳等）
```

### 公网部署（推荐 wss）

```
用户 <--TLS--> Nginx / 云服务器 --> Node.js
                ↳ Nginx 做 443 端口代理，8181 仅本地监听
                ↳ 证书放在 Nginx 层（Let's Encrypt），SanjinChat 本地可选回退到 ws
```

关键端口：
- 服务端 HTTP/HTTPS 端口：`8181`（[server.js](file:///c:/Users/Guoha/Desktop/sanjinchat_new/server.js) `PORT` 常量，可直接改）
- WebSocket 路径：`/ws`（挂在同一 HTTP/HTTPS 服务上，不需额外端口）

### 备份与迁移

```bash
# 备份：仅需拷贝 data 目录
tar -czf sanjinchat-backup-20260818.tar.gz data/

# 迁移：解压到新机器同一位置，启动服务即还原
tar -xzf sanjinchat-backup-20260818.tar.gz
```

### 安全建议

1. **启用 wss**：公网务必走 HTTPS，避免明文传输 Token
2. **修改默认管理员密码**：首次注册的账号即管理员
3. **限制 8181 来源**：使用反向代理或防火墙，只放行可信来源 IP
4. **定期备份 `data/` 目录**
5. **不要提交 `tls.json` / 证书 / `data/` / 私钥到公开仓库**（已在 `.gitignore` 中屏蔽）

---

## 常见问题

**Q：启动报错「找不到或无法加载主类 GradleWrapperMain」（构建手表时）？**
A：这是因为 Gradle Wrapper 的 JAR 不完整。项目内已为每个手表客户端嵌入完整的 wrapper。若仍报错，执行 `cd wear-app && gradle wrapper --gradle-version 8.12`（或重新下载 wrapper）。

**Q：WSS 模式浏览器提示「不安全」？**
A：脚本默认生成的是自签名证书。浏览器需要手动信任（「高级 → 继续」）。公网建议使用正式证书（Let's Encrypt 免费签发）。

**Q：切到 wss 后客户端连不上 / connection reset？**
A：确保浏览器访问的是 **`https://`** 而不是 `http://`。Web 客户端会根据当前页面协议自动切换 ws/wss——如果页面以 http 打开，ws 请求会被 wss-only 的服务端 reset。

**Q：手表客户端连不上自家服务器？**
A：检查：① 手表能否 ping 通服务器 8181；② 服务器若开了 wss，手表需切到 wss 协议；③ 在 `network_security_config.xml` 添加你的域名（允许明文）。

**Q：能否跨设备同步历史消息？**
A：历史消息存在服务端，任意新设备登录同一账号即可拉取 `before=<ts>` 的历史消息。

---

## 开源协议

本项目采用 **MIT License**（附后），允许个人与商业自由使用、修改、分发，**仅保留版权声明**。

```
MIT License

Copyright (c) 2025 SanjinChat Team

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

如果这个项目对你有帮助，点个 ⭐ Star 是最大的支持～ 欢迎 Issue / PR。
