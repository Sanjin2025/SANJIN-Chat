# SanjinChat 三大功能详细使用指南

本指南详细介绍 SanjinChat 内置的三个高级功能：

1. **曝光板**：自愿加入、展示公开资料、发现新朋友
2. **扫码登录**：待登录端 + 已登录端双端协作，免输密码
3. **验证码登录**：用 6 位数字验证码替代密码登录（演示环境/生产适配短信）

---

## 快速索引

| 功能 | 服务端模块 | 路由前缀 | 是否登录 |
|------|------------|----------|----------|
| 曝光板：列表 | `src/exposureBoard.js` | `GET /api/exposure/list` | ❌ 公开 |
| 曝光板：查我状态 | 同上 | `GET /api/exposure/me` | ✅ |
| 曝光板：加入/更新 | 同上 | `POST /api/exposure/upsert` | ✅ |
| 曝光板：退出 | 同上 | `POST /api/exposure/leave` | ✅ |
| 曝光板：管理员下架 | 同上 | `POST /api/exposure/admin/remove/:id` | ✅ 管理员 |
| 扫码：生成会话 | `src/qrcode.js` | `GET /api/qrcode/generate` | ❌ |
| 扫码：轮询状态 | 同上 | `GET /api/qrcode/status/:qrSessionId` | ❌ |
| 扫码：已登录端确认 | 同上 | `POST /api/qrcode/confirm` | ✅ |
| 验证码：生成 | `src/otp.js` | `POST /api/otp/generate` | ❌ |
| 验证码：登录 | 同上 | `POST /login/otp` | ❌ |

---

# 一、曝光板

## 1.1 设计理念

曝光板是一个**自愿加入**的公开用户墙。加入后，你的头像、昵称、用户名、简介、标签会展示给**所有访问曝光板页面的人**（包括未登录访客），方便用户互相认识、快速找到兴趣相投的朋友。

- ✅ 你可以随时加入/修改/退出
- ✅ 管理员可违规下架（附原因）
- ✅ 简介、标签支持随时修改
- ✅ 支持「仅本人可见」开关（可用于草稿）
- ✅ 修改头像/昵称后，曝光板资料自动同步
- ✅ 注销账号时，自动下架曝光板记录

## 1.2 数据模型

`data/exposureBoard.json` 单条记录结构：

```json
{
  "id": "ex_ex_1abc23",
  "userId": "u123",
  "username": "alice",
  "nickname": "Alice · 摄影",
  "avatar": "/uploads/avatars/178xx.jpg",
  "avatarType": "image",
  "avatarEmoji": null,
  "bio": "喜欢拍照和徒步，深圳本地欢迎约拍～",
  "tags": ["摄影", "徒步", "深圳"],
  "visible": true,
  "removed": false,
  "removedAt": null,
  "removedBy": null,
  "removeReason": "",
  "createdAt": 1787038000000,
  "updatedAt": 1787038000000
}
```

字段约束详见 [src/exposureBoard.js](file:///c:/Users/Guoha/Desktop/sanjinchat_new/src/exposureBoard.js)：
- `bio` 最长 200 字符
- `tags` 最多 8 个，每个最长 10 字符
- 返回的公开列表永远不含 `userId`（隐私）和 `removed: true` 的记录

## 1.3 API 接口与调用示例

### 1.3.1 加入曝光板 / 更新我自己的资料

```http
POST /api/exposure/upsert HTTP/1.1
Host: your-server.com:8181
Authorization: Bearer tk_xxx
Content-Type: application/json

{
  "bio": "喜欢拍照和徒步，深圳本地欢迎约拍～",
  "tags": ["摄影", "徒步", "深圳"],
  "visible": true
}
```

**cURL：**

```bash
curl -X POST http://your-server.com:8181/api/exposure/upsert \
  -H "Authorization: Bearer tk_xxx" \
  -H "Content-Type: application/json" \
  -d '{"bio":"喜欢拍照和徒步","tags":["摄影","徒步","深圳"],"visible":true}'
```

**响应示例（首次加入）：**

```json
{
  "ok": true,
  "data": {
    "onBoard": true,
    "id": "ex_xxxxx",
    "bio": "喜欢拍照和徒步",
    "tags": ["摄影", "徒步", "深圳"],
    "visible": true,
    "createdAt": 1787038000000,
    "updatedAt": 1787038000000
  }
}
```

> 说明：`upsert` 是插入或更新。如果没加入就新增；已加入则更新原记录的 bio/tags/visible。

### 1.3.2 查我自己的曝光板状态

```http
GET /api/exposure/me HTTP/1.1
Host: your-server.com:8181
Authorization: Bearer tk_xxx
```

**cURL：**

```bash
curl http://your-server.com:8181/api/exposure/me \
  -H "Authorization: Bearer tk_xxx"
```

**响应示例：**

```json
{
  "ok": true,
  "data": {
    "onBoard": false
  }
}
```

### 1.3.3 查询曝光板列表（公开，所有人可读）

```http
GET /api/exposure/list?tag=摄影&page=1&pageSize=20 HTTP/1.1
Host: your-server.com:8181
```

**cURL：**

```bash
# 不按标签过滤
curl "http://your-server.com:8181/api/exposure/list?page=1&pageSize=20"

# 按标签过滤（支持路径参数形式也行）
curl "http://your-server.com:8181/api/exposure/list/摄影"
```

**响应：**

```json
{
  "total": 37,
  "page": 1,
  "pageSize": 20,
  "list": [
    {
      "id": "ex_abc123",
      "username": "alice",
      "nickname": "Alice · 摄影",
      "avatar": null,
      "avatarType": "emoji",
      "avatarEmoji": "🐱",
      "bio": "喜欢拍照和徒步，深圳本地欢迎约拍～",
      "tags": ["摄影", "徒步", "深圳"],
      "createdAt": 1787038000000,
      "updatedAt": 1787038000000
    }
    // ...
  ]
}
```

排序规则：`updatedAt` 倒序（最近有更新的在最前）。

### 1.3.4 我退出曝光板

```http
POST /api/exposure/leave HTTP/1.1
Host: your-server.com:8181
Authorization: Bearer tk_xxx
Content-Length: 0
```

**cURL：**

```bash
curl -X POST http://your-server.com:8181/api/exposure/leave \
  -H "Authorization: Bearer tk_xxx"
```

响应：`{ "ok": true, "message": "已退出曝光板" }`

### 1.3.5 管理员下架曝光板条目

```http
POST /api/exposure/admin/remove/ex_abc123 HTTP/1.1
Host: your-server.com:8181
Authorization: Bearer tk_xxx
Content-Type: application/json

{ "reason": "简介包含违规联系方式" }
```

**cURL：**

```bash
curl -X POST http://your-server.com:8181/api/exposure/admin/remove/ex_abc123 \
  -H "Authorization: Bearer tk_xxx" \
  -H "Content-Type: application/json" \
  -d '{"reason":"简介包含违规联系方式"}'
```

非管理员会返回 `{"error":"仅管理员可下架"}` (400)。

## 1.4 Web 端使用步骤

1. **登录 SanjinChat** → 进入「我的」Tab
2. **加入曝光板**：点击「🪧 加入曝光板」
   - 填写「简介」（可选，≤200字）
   - 填写「标签」（英文逗号/空格分隔，最多 8 个）
   - 勾选「对所有人可见」（若未勾选，你的卡片不会出现在公开列表，但你可以先存成草稿）
   - 点击「加入曝光板」
3. **浏览曝光板**：回到「我的」Tab → 点击「👥 浏览曝光板」
   - 卡片展示头像 + 昵称 + @用户名 + 简介 + 标签
   - 顶部输入框支持按标签筛选（回车或点「筛选」按钮）
   - **点任意标签**可直接跳转同标签过滤
   - 每张卡「加好友」按钮能快速发起好友请求（附言默认："嗨，我是 @xxx，从曝光板来的～"）
4. **管理员下架**：管理员在每张卡片看到红色「下架」按钮，点击可填原因确认

## 1.5 完整 Node.js 脚本示例：曝光板 API 走一遍

```js
const BASE = 'http://your-server.com:8181';

async function run() {
  // 1) 注册一个测试用户
  await fetch(BASE + '/register', { method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'bob_expo', password: '123456', nickname: 'Bob Dev' }) });
  // 2) 登录拿 token
  const login = await fetch(BASE + '/login', { method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'bob_expo', password: '123456' }) }).then(r => r.json());
  const TOKEN = login.token;
  const auth = { Authorization: 'Bearer ' + TOKEN, 'Content-Type': 'application/json' };

  // 3) 查我状态：onBoard:false
  console.log('before:', await fetch(BASE + '/api/exposure/me', { headers: auth }).then(r => r.json()));

  // 4) 加入曝光板
  console.log('upsert:', await fetch(BASE + '/api/exposure/upsert', {
    method: 'POST', headers: auth,
    body: JSON.stringify({ bio: 'Java / Kotlin / Web', tags: ['开发', 'Java', '杭州'], visible: true }),
  }).then(r => r.json()));

  // 5) 浏览公开列表（无需鉴权）
  const list = await fetch(BASE + '/api/exposure/list?page=1&pageSize=5').then(r => r.json());
  console.log('list.total =', list.total, ', 第1条 =', list.list[0]);

  // 6) 按标签过滤
  const filtered = await fetch(BASE + '/api/exposure/list?tag=开发').then(r => r.json());
  console.log('filtered by #开发 count =', filtered.total);
}
run().catch(console.error);
```

---

# 二、扫码登录

## 2.1 架构

扫码登录是 **双端协作** 的 3 步流程：

```
┌────────────┐  (1) GET generate    ┌────────────┐
│ 待登录端 A │ ─────────────────────▶│  服务端     │
│ (新设备)    │ ◀─ qrSessionId+QR图 ─│            │
└─────┬──────┘                      └───────▲─────┘
      │ 显示QR                             │ (3) GET status/:id 轮询
      │                                    │  → confirmed: 返回 token+user
   ┌──▼──────────────────────┐             │  → pending: 继续等
   │   QR: {"type":"sanjinchat_login","qrSessionId":"qr_xxx"}
   └──┬──────────────────────┘             │
      │                                    │
┌─────▼──────┐   (2) POST confirm ────────┘
│ 已登录端 B │  Bearer Token_B
│ (本设备)    │
└────────────┘
```

- **A 端（待登录端）**：什么都不知道，只展示二维码，轮询状态 → 拿到 token 登录完成
- **B 端（已登录端）**：仅「扫码 → 调用确认 API」，**绝不返回 token 给 B**
- **服务端**：保存 5 分钟会话、状态机、一次性消费 token

## 2.2 状态机

```
pending (等待扫码)
   │  B 端 POST confirm 成功
   ▼
confirmed (已扫码)
   │  A 端轮询命中，发放 token，会话立即置 used
   ▼
used (已消费，无法再用)

任何状态 ────── 5 分钟过期 ──────▶ expired
```

服务端每次生成/轮询接口都要判断 `expiresAt < now` 作为 `expired` 返回。

## 2.3 API 与示例

### 2.3.1 步骤 1：待登录端生成二维码

```http
GET /api/qrcode/generate HTTP/1.1
Host: your-server.com:8181
```

**cURL：**

```bash
curl http://your-server.com:8181/api/qrcode/generate
```

**响应：**

```json
{
  "ok": true,
  "qrSessionId": "qr_a1b2c3d4",
  "qrDataUrl": "data:image/png;base64,iVBORw0KGgoAAAANSUhEU...（完整二维码图片）",
  "expiresAt": 1787041000000
}
```

Web 端可直接 `<img src={qrDataUrl}>` 渲染。二维码扫码后的**明文内容**是一段 JSON：

```json
{"type":"sanjinchat_login","qrSessionId":"qr_a1b2c3d4"}
```

任何通用扫码器都能扫出这段 JSON；客户端扫码逻辑只需解析出 `qrSessionId` 字段。

### 2.3.2 步骤 2：已登录端扫码确认

```http
POST /api/qrcode/confirm HTTP/1.1
Host: your-server.com:8181
Authorization: Bearer tk_xxx
Content-Type: application/json

{ "qrSessionId": "qr_a1b2c3d4" }
```

**cURL：**

```bash
curl -X POST http://your-server.com:8181/api/qrcode/confirm \
  -H "Authorization: Bearer tk_xxx" \
  -H "Content-Type: application/json" \
  -d '{"qrSessionId":"qr_a1b2c3d4"}'
```

成功响应：`{ "ok": true }`
失败响应（可能的 error 值）：
- `未登录`
- `登录已失效，请重新登录`
- `二维码不存在`
- `二维码已过期`
- `二维码已被使用`（会话已 confirmed 就不允许再 confirm，避免争抢）

> 安全说明：确认接口**不返回 token**。确认动作只是把 pending → confirmed 并记录 userId。token 只在下一轮 A 端轮询时**直接发给 A**。

### 2.3.3 步骤 3：待登录端轮询状态

```http
GET /api/qrcode/status/qr_a1b2c3d4 HTTP/1.1
Host: your-server.com:8181
```

**cURL：**

```bash
# 轮询建议：每 1.5 秒一次
curl http://your-server.com:8181/api/qrcode/status/qr_a1b2c3d4
```

**可能的响应：**

```jsonc
// 仍等待扫码
{ "ok": true, "status": "pending", "expiresAt": 1787041000000 }

// 已过期（二维码超时或已被扫码但 token 被重复消费）
{ "ok": true, "status": "expired", "expiresAt": ... }

// 已扫码，立即发放 token（一次性）
{
  "ok": true,
  "status": "confirmed",
  "token": "tk_xxxxxxxxxxxxxxxxxxxxxxxx",
  "user": {
    "id": "u123",
    "username": "alice",
    "nickname": "Alice · 摄影",
    "avatar": null,
    "avatarType": "emoji",
    "avatarEmoji": "🐱",
    "role": "user"
  }
}
```

拿到 `confirmed` 响应后：
1. 存 `token` 到 localStorage
2. 调 WebSocket `ws://server:8181/ws?token=xxx` 建立聊天连接
3. 登录完成

## 2.4 防刷与安全

- **5 分钟有效期**：`qrSessionId` 必须在短时间内完成扫码+轮询
- **每 3 分钟自动清理**：`setInterval` 清理过期会话（不占内存）
- **一次性 token**：confirmed 后 A 端轮询第一次拿到 token，会话状态立刻变 used，再次轮询返回 expired
- **二维码内容无敏感信息**：仅 `qrSessionId`，恶意扫走也需要**已登录端** confirm 才能生效

## 2.5 Web 端使用步骤

1. 打开 SanjinChat 登录页 → 切 Tab「扫码登录」
2. 页面显示二维码；服务端在每 1.5s 轮询一次
3. 在**另一台已登录设备**上（任何 SanjinChat Web / 手表端有扫码能力的）打开扫码功能
4. 对准二维码扫码 → 确认 → 确认成功后页面会显示「扫码成功！正在登录...」并自动登录

> 如果二维码过期，页面会显示红色的「二维码已过期」并出现「刷新二维码」按钮。

## 2.6 完整示例脚本：扫码登录三步骤模拟

```js
const BASE = 'http://your-server.com:8181';

async function run() {
  // 准备：先注册一个用户，让 B 端有登录态
  await fetch(BASE + '/register', { method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'qr_bob', password: '123456', nickname: 'Bob' }) });
  const login = await fetch(BASE + '/login', { method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'qr_bob', password: '123456' }) }).then(r => r.json());
  const B_TOKEN = login.token;

  // ========= 步骤 1：A 端生成二维码 =========
  const gen = await fetch(BASE + '/api/qrcode/generate').then(r => r.json());
  console.log('[A] generate qrSessionId =', gen.qrSessionId, 'expiresAt =', new Date(gen.expiresAt));

  // 解析二维码 payload（模拟扫码后的内容）
  const payload = { type: 'sanjinchat_login', qrSessionId: gen.qrSessionId };
  // 真实情况下，这是 B 端用摄像头扫码解析出来的

  // ========= 步骤 2：B 端（已登录）扫码确认 =========
  const cfm = await fetch(BASE + '/api/qrcode/confirm', {
    method: 'POST',
    headers: { Authorization: 'Bearer ' + B_TOKEN, 'Content-Type': 'application/json' },
    body: JSON.stringify({ qrSessionId: payload.qrSessionId }),
  }).then(r => r.json());
  console.log('[B] confirm result =', cfm);

  // ========= 步骤 3：A 端轮询拿到 token =========
  const status = await fetch(BASE + '/api/qrcode/status/' + gen.qrSessionId).then(r => r.json());
  console.log('[A] status =', status.status);
  if (status.status === 'confirmed') {
    console.log('[A] OK!  token =', status.token.slice(0, 16) + '...');
    console.log('[A] 登录用户 =', status.user.username);
  }
}
run().catch(console.error);
```

---

# 三、验证码登录

## 3.1 什么时候用验证码登录

- 用户忘记了密码，但记得用户名
- 测试环境：不想每次查密码，快速拿 token 联调
- 生产环境：把「生成时的 console.log」替换成短信/邮件/站内推送，做成通用「短信验证码登录」

## 3.2 流程两步

```
┌──────────┐  (1) POST /api/otp/generate?username=alice   ┌──────────┐
│ 客户端   │ ─────────────────────────────────────────────▶│ 服务端   │
│          │ ◀── otpId + expiresAt + tip ──────────────────│          │
│          │                                               │  生成6位数字
│          │                                               │  code=874592
│          │                                               │  打印控制台/发短信
│          │  (2) POST /login/otp                         │
│          │  {username, otpId, code} ───────────────────▶│  校验:
│          │                                               │   username 匹配
│          │ ◀── token + user ─────────────────────────────│   code 匹配
└──────────┘                                               │   未过期、未使用
```

## 3.3 API 与示例

### 3.3.1 生成验证码

```http
POST /api/otp/generate HTTP/1.1
Host: your-server.com:8181
Content-Type: application/json

{ "username": "alice" }
```

**cURL：**

```bash
curl -X POST http://your-server.com:8181/api/otp/generate \
  -H "Content-Type: application/json" \
  -d '{"username":"alice"}'
```

**成功响应：**

```json
{
  "ok": true,
  "otpId": "otp_a1b2c3d4",
  "expiresAt": 1787044000000,
  "tip": "已生成验证码。当前为演示环境：验证码已打印在服务端控制台，开发测试请从控制台复制 code；生产部署请接入短信/邮件发送。"
}
```

开发测试环境：**服务端控制台会打印一行**：

```
[OTP] 验证码已生成: 用户=alice  code=874592  expiresAt=2026/8/18 23:50:00
```

生产部署：把 `otp.js` 的 `console.log` 替换成短信/邮件/推送通道调用即可，接口契约**完全不变**。

### 3.3.2 用验证码登录

```http
POST /login/otp HTTP/1.1
Host: your-server.com:8181
Content-Type: application/json

{
  "username": "alice",
  "otpId": "otp_a1b2c3d4",
  "code": "874592"
}
```

**cURL：**

```bash
curl -X POST http://your-server.com:8181/login/otp \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","otpId":"otp_a1b2c3d4","code":"874592"}'
```

**成功响应：**

```json
{
  "ok": true,
  "token": "tk_xxxxxxxxxxxxxxxxxxxxxxxx",
  "user": {
    "id": "u123", "username": "alice",
    "nickname": "Alice", "avatar": null,
    "avatarType": "emoji", "avatarEmoji": "🐱",
    "role": "user"
  }
}
```

**常见错误：**

| error | 原因 |
|-------|------|
| `用户名不能为空` / `用户不存在` | 用户名错了 |
| `验证码会话不存在` | otpId 错了 |
| `验证码已过期` | 超过 3 分钟有效期 |
| `验证码已被使用` | 用过一次（一次性） |
| `验证码与用户名不匹配` | 用 A 生成的 otpId 去登录 B 的账号 |
| `验证码错误` | 6 位数字不匹配 |
| `生成太频繁，请 X 秒后再试` | 同一用户名 30 秒内连续生成 |
| `该账号 1 小时内获取次数已达上限，请稍后再试` | 同一用户名 1h 内超过 10 次（防刷） |

## 3.4 防刷机制（内置）

| 限制项 | 阈值 | 说明 |
|--------|------|------|
| **生成间隔** | 30 秒 / 同用户名 | 避免脚本狂刷 |
| **每小时次数上限** | 10 次 / 同用户名 | 防短信轰炸（接短信通道后很重要） |
| **有效期** | 3 分钟 | 超时作废 |
| **一次性** | 用过即 used | 拦截重放攻击 |
| **5 分钟清理** | `setInterval` 5min | 自动删除过期 > 1h 的记录 |

## 3.5 Web 端使用步骤

1. 打开 SanjinChat 登录页 → 切 Tab「验证码」
2. 输入**已注册用户名** → 点击「获取验证码」
   - 开发测试：打开服务端控制台，找到 `[OTP] 验证码已生成: ... code=874592`
   - 生产：等待短信/邮件/推送到达
3. 验证码输入框会显示出来，输入 6 位数字 → 点「登录」
4. 成功后自动登录、连接 WebSocket

> 「获取验证码」按钮点击后会倒计时 30s（符合防刷限制），然后才可以「重新获取」。

## 3.6 完整示例脚本：验证码登录

```js
const BASE = 'http://your-server.com:8181';

async function run() {
  // 先注册一个账号（假设已有可以跳过）
  await fetch(BASE + '/register', { method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'otp_cat', password: '123456', nickname: 'Cat' }) });

  // 步骤 1：生成验证码 —— 在真实服务端 console 会打印出 code
  const gen = await fetch(BASE + '/api/otp/generate', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'otp_cat' }),
  }).then(r => r.json());
  console.log('generate:', gen);

  // 开发环境需要从服务端 console.log 取 code
  // 这里为了跑通演示，直接让我们「假设」拿到了 000000，会失败 → 让我们演示错误场景
  try {
    const failed = await fetch(BASE + '/login/otp', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: 'otp_cat', otpId: gen.otpId, code: '000000' }),
    }).then(r => r.json());
    console.log('wrong:', failed); // 会返回 error: '验证码错误'
  } catch (e) { /* ignore */ }

  // ——— 注意：真实使用时把下面 xxxxxxx 换成服务端 console 打印的 6 位数字，然后登录 ———
  // const real = await fetch(BASE + '/login/otp', {
  //   method: 'POST', headers: { 'Content-Type': 'application/json' },
  //   body: JSON.stringify({ username: 'otp_cat', otpId: gen.otpId, code: '874592' }),
  // }).then(r => r.json());
  // console.log('real login:', real.ok, 'token =', real.token.slice(0, 10) + '...');
}
run().catch(console.error);
```

> 提示：上面示例故意用错误的 code `000000` 走错误分支；真实场景请把 `code` 替换为服务端控制台打印的真实 6 位数字。

## 3.7 生产环境：接入短信

替换 [src/otp.js](file:///c:/Users/Guoha/Desktop/sanjinchat_new/src/otp.js) 里 `generate()` 函数末尾的这段：

```js
// ---------- 控制台打印（便于测试直接拿）----------
// 生产环境：这里应该接入短信/邮件/推送等真实发送通道
console.log(`[OTP] 验证码已生成: 用户=${username}  code=${code}  expiresAt=${new Date(expiresAt).toLocaleString()}`);
```

改成例如阿里云短信、腾讯云 SMS、SendGrid 邮件、Server 酱等任意通道即可。接口契约 (`otpId`/`expiresAt`/`tip`/`login/otp` 调用) 保持**不变**，客户端无任何改动。

---

## 四、常见问题

### Q1：曝光板里的头像/昵称和我最新不一致怎么办？

A：曝光板在你**修改昵称/头像（图片或 Emoji）** 时会自动同步。如果因升级迁移等出现不一致，可重新进入「加入曝光板」点一次「保存修改」强制刷新。

### Q2：扫码登录时二维码扫了没反应？

A：按顺序检查：① 确认是 SanjinChat 原生扫码入口而不是微信/手机自带扫码器；② 两台设备服务器地址是否完全一致（同一 `your-server.com:8181`）；③ 看轮询接口 `status/:qrSessionId` 返回什么（pending / confirmed / expired）。

### Q3：验证码看不到怎么办？

A：演示环境验证码打在**服务端控制台**日志里（`[OTP] ... code=xxxxxx`）。生产部署请把 `otp.js` `generate()` 里 `console.log` 换成短信/邮件发送通道。

### Q4：配对登录和验证码登录的区别？

| 维度 | 配对码登录（Pair） | 验证码登录（OTP） |
|------|-------------------|------------------|
| **发起端** | 必须有一台**已登录设备**才能生成 | 只要记得用户名即可生成 |
| **有效期** | 2 分钟 | 3 分钟 |
| **防刷** | 弱（必须先登录） | 强（同用户 30s 间隔 + 1h 10 次上限） |
| **适合场景** | 用户自己两台设备互登 | 用户忘密码 / 测试环境快速取 token |
| **位数** | 6 位数字 | 6 位数字 |
| **代码模块** | `src/pair.js` | `src/otp.js` |

### Q5：曝光板能匿名吗？

A：不行。加入曝光板**必须登录**，列表显示的是你的**真实头像/昵称/用户名**（这正是「自愿曝光」的含义）。如果你只想展示部分信息，可以把昵称改成你想公开的样子、用 Emoji 头像，简介里也可以不填联系方式。

---

## 五、服务端模块参考

| 功能 | 模块文件 | 数据表 | 自动清理 |
|------|----------|--------|----------|
| 曝光板 | [src/exposureBoard.js](file:///c:/Users/Guoha/Desktop/sanjinchat_new/src/exposureBoard.js) | `data/exposureBoard.json` | 账号注销时级联删除 |
| 扫码登录 | [src/qrcode.js](file:///c:/Users/Guoha/Desktop/sanjinchat_new/src/qrcode.js) | `data/qrSessions.json` | 每 3 分钟清理过期会话 |
| 验证码登录 | [src/otp.js](file:///c:/Users/Guoha/Desktop/sanjinchat_new/src/otp.js) | `data/otpSessions.json` | 每 5 分钟清理过期会话 |
| 配对码登录 | [src/pair.js](file:///c:/Users/Guoha/Desktop/sanjinchat_new/src/pair.js) | `data/pairCodes.json` | 每 1 分钟清理过期 |
| 路由注册 | [server.js](file:///c:/Users/Guoha/Desktop/sanjinchat_new/server.js) | — | — |
