// ==================== SanjinChat 主服务端 ====================
// 集成：HTTP API 路由 + WebSocket 聊天 + 静态文件服务 + 自动清理

// ===== 启动前自动检测依赖，缺失则自动安装 =====
const REQUIRED_DEPS = ['ws', 'bcryptjs', 'qrcode'];
const MISSING = [];
for (const dep of REQUIRED_DEPS) {
  try { require(dep); }
  catch (_) { MISSING.push(dep); }
}
if (MISSING.length > 0) {
  console.log(`[依赖检测] 缺失包: ${MISSING.join(', ')}，正在自动安装...`);
  const { execSync } = require('child_process');
  try {
    execSync(`npm install ${MISSING.join(' ')}`, {
      cwd: __dirname,
      stdio: 'inherit'
    });
    console.log('[依赖检测] 安装完成，重新加载模块...');
  } catch (e) {
    console.error('[依赖检测] 自动安装失败，请手动执行: npm install');
    process.exit(1);
  }
}

const http = require('http');
const fs = require('fs');
const path = require('path');
const { WebSocketServer } = require('ws');

const storage = require('./src/storage');
const auth = require('./src/auth');
const multipart = require('./src/multipart');
const qrcodeMod = require('./src/qrcode');
const pairMod = require('./src/pair');
const otpMod = require('./src/otp');
const exposureBoardMod = require('./src/exposureBoard');
const announcementMod = require('./src/announcement');
const serverInfoMod = require('./src/serverInfo');
const cleanupMod = require('./src/cleanup');
const ChatServer = require('./src/chatServer');

// ===== 全局兜底：任何未捕获的异常只打印，不退出进程 =====
// 1) 同步未捕获异常：默认 Node 会打印并退出，这里覆盖为只打印
process.on('uncaughtException', (err) => {
  console.error('==================== uncaughtException ====================');
  console.error(err && err.stack ? err.stack : err);
  console.error('==========================================================');
  console.error('[守护] 已捕获 uncaughtException，进程继续运行');
});
// 2) 未处理的 Promise rejection：高版本 Node 默认也会终止进程
process.on('unhandledRejection', (reason) => {
  console.error('==================== unhandledRejection ====================');
  if (reason && reason.stack) console.error(reason.stack);
  else console.error(reason);
  console.error('==========================================================');
  console.error('[守护] 已捕获 unhandledRejection，进程继续运行');
});

// ===== 服务端口 =====
const PORT = 8181;
const PUBLIC_DIR = path.join(__dirname, 'public');

// ===== TLS / WSS 配置 =====
// 优先级：环境变量 SANJIN_TLS > tls.json 配置文件 > 默认关闭
// 启用后，HTTP 升级为 HTTPS，WebSocket 由 ws:// 升级为 wss://
const TLS_CONFIG = (() => {
  const cfg = {
    enabled: false,
    cert: 'certs/fullchain.pem',
    key: 'certs/privkey.pem'
  };
  // 读取 tls.json（若存在）
  try {
    const cfgFile = path.join(__dirname, 'tls.json');
    if (fs.existsSync(cfgFile)) {
      const file = JSON.parse(fs.readFileSync(cfgFile, 'utf8'));
      if (file && typeof file === 'object') Object.assign(cfg, file);
    }
  } catch (e) { /* 忽略配置文件读取错误 */ }
  // 环境变量覆盖
  const envTls = String(process.env.SANJIN_TLS || '').toLowerCase();
  if (envTls === '1' || envTls === 'true' || envTls === 'on') cfg.enabled = true;
  if (envTls === '0' || envTls === 'false' || envTls === 'off') cfg.enabled = false;
  if (process.env.SANJIN_TLS_CERT) cfg.cert = process.env.SANJIN_TLS_CERT;
  if (process.env.SANJIN_TLS_KEY) cfg.key = process.env.SANJIN_TLS_KEY;
  // 解析为绝对路径
  cfg.cert = path.isAbsolute(cfg.cert) ? cfg.cert : path.join(__dirname, cfg.cert);
  cfg.key = path.isAbsolute(cfg.key) ? cfg.key : path.join(__dirname, cfg.key);
  return cfg;
})();

// ===== 创建聊天服务器实例 =====
const chatServer = new ChatServer();

// ==================== HTTP 请求工具 ====================

/** 发送 JSON 响应 */
function sendJson(res, status, data) {
  const body = JSON.stringify(data);
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store',
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Headers': '*',
    'Access-Control-Allow-Methods': 'GET,POST,PUT,DELETE,OPTIONS'
  });
  res.end(body);
}

/** 发送纯文本 */
function sendText(res, status, text) {
  res.writeHead(status, {
    'Content-Type': 'text/plain; charset=utf-8',
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Headers': '*',
    'Access-Control-Allow-Methods': 'GET,POST,PUT,DELETE,OPTIONS'
  });
  res.end(text);
}

/** 读取 JSON 请求体 */
function readJsonBody(req) {
  return new Promise((resolve, reject) => {
    let raw = '';
    req.on('data', c => { raw += c.toString(); if (raw.length > 5 * 1024 * 1024) reject(new Error('请求体过大')); });
    req.on('end', () => {
      try {
        if (!raw.trim()) return resolve({});
        resolve(JSON.parse(raw));
      } catch (e) {
        reject(new Error('JSON 解析失败: ' + e.message));
      }
    });
    req.on('error', reject);
  });
}

/** 获取 URL 路径（去掉 query） */
function getUrlPath(url) {
  const q = url.indexOf('?');
  return q === -1 ? url : url.slice(0, q);
}

/** 提取 URL 路径参数，匹配 /xxx/:id 格式 */
function matchPath(pattern, pathname) {
  const pParts = pattern.split('/').filter(Boolean);
  const uParts = pathname.split('/').filter(Boolean);
  if (pParts.length !== uParts.length) return null;
  const params = {};
  for (let i = 0; i < pParts.length; i++) {
    if (pParts[i].startsWith(':')) {
      params[pParts[i].slice(1)] = decodeURIComponent(uParts[i]);
    } else if (pParts[i] !== uParts[i]) {
      return null;
    }
  }
  return params;
}

// ==================== 静态文件服务 ====================

const MIME_MAP = {
  '.html': 'text/html; charset=utf-8',
  '.htm': 'text/html; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.gif': 'image/gif',
  '.webp': 'image/webp',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon',
  '.mp3': 'audio/mpeg',
  '.ogg': 'audio/ogg',
  '.wav': 'audio/wav',
  '.webm': 'audio/webm',
  '.m4a': 'audio/mp4'
};

function serveStaticFile(filePath, res, extraHeaders = {}) {
  fs.stat(filePath, (err, stat) => {
    if (err || !stat.isFile()) {
      sendText(res, 404, '404 Not Found');
      return;
    }
    const ext = path.extname(filePath).toLowerCase();
    const mime = MIME_MAP[ext] || 'application/octet-stream';
    const headers = {
      'Content-Type': mime,
      'Content-Length': stat.size,
      'Access-Control-Allow-Origin': '*',
      ...extraHeaders
    };
    res.writeHead(200, headers);
    const stream = fs.createReadStream(filePath);
    stream.pipe(res);
  });
}

/** 服务 public/ 下的页面 */
function handleStaticRequest(pathname, res) {
  if (pathname === '/' || pathname === '/index.html') {
    const indexPath = path.join(PUBLIC_DIR, 'index.html');
    serveStaticFile(indexPath, res);
    return true;
  }
  // 只允许 /index.html 和根路由访问 public
  if (pathname === '/favicon.ico') {
    sendText(res, 204, '');
    return true;
  }
  return false;
}

/** 服务 /uploads/ 下的文件 */
function handleUploadsRequest(pathname, res) {
  if (!pathname.startsWith('/uploads/')) return false;
  const relative = pathname.slice('/uploads/'.length);
  // 安全：防止 ../ 遍历
  if (relative.includes('..') || path.isAbsolute(relative)) {
    sendText(res, 403, '禁止访问');
    return true;
  }
  const filePath = path.join(storage.DATA_DIR, 'uploads', relative);
  serveStaticFile(filePath, res);
  return true;
}

// ==================== HTTP 路由处理 ====================

async function handleHttpRequest(req, res) {
  // CORS OPTIONS 预检
  if (req.method === 'OPTIONS') {
    res.writeHead(204, {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Headers': '*',
      'Access-Control-Allow-Methods': 'GET,POST,PUT,DELETE,OPTIONS',
      'Access-Control-Max-Age': '86400'
    });
    res.end();
    return;
  }

  const urlPath = getUrlPath(req.url);

  // ------ 静态页面 / 上传文件 ------
  if (req.method === 'GET' && handleStaticRequest(urlPath, res)) return;
  if (req.method === 'GET' && handleUploadsRequest(urlPath, res)) return;

  // ------ 动态路由匹配 ------
  try {
    // ====== 注册 / 登录 ======
    if (urlPath === '/register' && req.method === 'POST') {
      const body = await readJsonBody(req);
      const user = await auth.register(body.username, body.password, body.nickname);
      return sendJson(res, 200, { ok: true, user });
    }

    if (urlPath === '/login' && req.method === 'POST') {
      const body = await readJsonBody(req);
      const result = await auth.login(body.username, body.password);
      return sendJson(res, 200, { ok: true, ...result });
    }

    // ====== 用户信息 ======
    const userParam = matchPath('/user/:id', urlPath);
    if (userParam && req.method === 'GET') {
      const user = auth.getUserById(userParam.id);
      if (!user) return sendJson(res, 404, { error: '用户不存在' });
      return sendJson(res, 200, { ok: true, user });
    }

    if (urlPath === '/user/nickname' && req.method === 'PUT') {
      const user = auth.verifyRequest(req);
      if (!user) return sendJson(res, 401, { error: '未登录' });
      const body = await readJsonBody(req);
      const updated = await auth.updateNickname(user.id, body.nickname);
      // 广播
      chatServer.broadcastUserNicknameUpdated(user.id, updated.nickname);
      // 级联：曝光板快照同步
      try { await exposureBoardMod.syncUserProfile(updated); } catch (_) {}
      return sendJson(res, 200, { ok: true, user: updated });
    }

    // 修改密码（验证旧密码）
    if (urlPath === '/api/user/password' && req.method === 'PUT') {
      const user = auth.verifyRequest(req);
      if (!user) return sendJson(res, 401, { error: '未登录' });
      const body = await readJsonBody(req);
      try {
        await auth.changePassword(user.id, body.oldPassword || '', body.newPassword || '');
        return sendJson(res, 200, { ok: true });
      } catch (e) {
        return sendJson(res, 400, { error: e.message || '修改密码失败' });
      }
    }

    if (urlPath === '/api/user' && req.method === 'DELETE') {
      const user = auth.verifyRequest(req);
      if (!user) return sendJson(res, 401, { error: '未登录' });
      const body = await readJsonBody(req);
      const result = await auth.deleteAccount(user.id, body.password);
      // 级联：曝光板同步软删
      try { await exposureBoardMod.handleUserDeleted(result.deletedUserId); } catch (_) {}
      // 广播
      chatServer.broadcastUserAccountDeleted(
        result.deletedUserId,
        result.friendsIds,
        result.groupIds,
        result.ownedGroupIds
      );
      return sendJson(res, 200, { ok: true });
    }

    // ====== 头像上传 ======
    if (urlPath === '/api/user/avatar' && req.method === 'POST') {
      const user = auth.verifyRequest(req);
      if (!user) return sendJson(res, 401, { error: '未登录' });
      if (!req.headers['content-type'] || !req.headers['content-type'].includes('multipart/form-data')) {
        return sendJson(res, 400, { error: '需要 multipart/form-data' });
      }
      const { files } = await multipart.parseMultipart(req);
      const avatarFile = files.find(f => f.fieldname === 'avatar' || f.fieldname === 'file');
      if (!avatarFile) return sendJson(res, 400, { error: '未找到文件字段 avatar' });
      const result = await auth.uploadAvatarImage(user.id, avatarFile);
      // 广播
      const newUser = auth.getUserById(user.id);
      chatServer.broadcastUserAvatarUpdated(user.id, newUser.avatar, newUser.avatarType, newUser.avatarEmoji);
      // 级联：曝光板快照同步
      try { await exposureBoardMod.syncUserProfile(newUser); } catch (_) {}
      return sendJson(res, 200, { ok: true, url: result.url });
    }

    if (urlPath === '/api/user/avatar/emoji' && req.method === 'PUT') {
      const user = auth.verifyRequest(req);
      if (!user) return sendJson(res, 401, { error: '未登录' });
      const body = await readJsonBody(req);
      const updated = await auth.setAvatarEmoji(user.id, body.emoji);
      chatServer.broadcastUserAvatarUpdated(user.id, updated.avatar, updated.avatarType, updated.avatarEmoji);
      // 级联：曝光板快照同步
      try { await exposureBoardMod.syncUserProfile(updated); } catch (_) {}
      return sendJson(res, 200, { ok: true, avatar: updated.avatarEmoji });
    }

    // ====== 二维码登录 ======
    if (urlPath === '/api/qrcode/generate' && req.method === 'GET') {
      const result = await qrcodeMod.generateQrSession();
      return sendJson(res, 200, { ok: true, ...result });
    }

    const qrStatusParam = matchPath('/api/qrcode/status/:qrSessionId', urlPath);
    if (qrStatusParam && req.method === 'GET') {
      // getQrStatus 现在是 async，且 confirmed 时直接返回 token+user（原子发放，不再二次调 consume）
      const status = await qrcodeMod.getQrStatus(qrStatusParam.qrSessionId);
      if (status.status === 'confirmed' && status.token) {
        return sendJson(res, 200, { ok: true, status: 'confirmed', token: status.token, user: status.user });
      }
      return sendJson(res, 200, { ok: true, status: status.status, expiresAt: status.expiresAt });
    }

    if (urlPath === '/api/qrcode/confirm' && req.method === 'POST') {
      const body = await readJsonBody(req);
      const authHeader = req.headers['authorization'] || '';
      const token = authHeader.startsWith('Bearer ') ? authHeader.slice(7) : (body.token || '');
      const result = await qrcodeMod.confirmQrLogin(body.qrSessionId, token);
      return sendJson(res, 200, result);
    }

    // ====== 验证码登录 ======
    if (urlPath === '/api/otp/generate' && req.method === 'POST') {
      const body = await readJsonBody(req);
      const result = await otpMod.generate(body.username);
      return sendJson(res, 200, result);
    }
    // OTP 登录：路由从 /login/otp 改为 /api/otp/login，且只传 username + code（不要 otpId）
    if (urlPath === '/api/otp/login' && req.method === 'POST') {
      const body = await readJsonBody(req);
      const result = await otpMod.login(body.username, body.code);
      return sendJson(res, 200, result);
    }

    // ====== 曝光板 ======
    if (urlPath === '/api/exposure/list' && req.method === 'GET') {
      const tagMatch = matchPath('/api/exposure/list/:tag', urlPath);
      const urlObj = new URL(req.url, 'http://x');
      const tagParam = tagMatch ? tagMatch.tag : (urlObj.searchParams.get('tag') || '');
      const page = parseInt(urlObj.searchParams.get('page') || '1', 10);
      const pageSize = parseInt(urlObj.searchParams.get('pageSize') || '20', 10);
      return sendJson(res, 200, { ok: true, ...exposureBoardMod.listExposure({ tag: tagParam, page, pageSize }) });
    }
    if (urlPath === '/api/exposure/mine' && req.method === 'GET') {
      const user = auth.verifyRequest(req);
      if (!user) return sendJson(res, 401, { error: '未登录' });
      return sendJson(res, 200, { ok: true, ...exposureBoardMod.getMyExposure(user.id) });
    }
    if (urlPath === '/api/exposure/upsert' && req.method === 'POST') {
      const user = auth.verifyRequest(req);
      if (!user) return sendJson(res, 401, { error: '未登录' });
      const body = await readJsonBody(req);
      const result = await exposureBoardMod.upsertExposure(user, body);
      return sendJson(res, 200, { ok: true, ...result });
    }
    if (urlPath === '/api/exposure/leave' && req.method === 'POST') {
      const user = auth.verifyRequest(req);
      if (!user) return sendJson(res, 401, { error: '未登录' });
      const result = await exposureBoardMod.leaveExposure(user.id);
      return sendJson(res, 200, result);
    }
    const adminRemoveMatch = matchPath('/api/exposure/admin/remove/:id', urlPath);
    if (adminRemoveMatch && req.method === 'POST') {
      const user = auth.verifyRequest(req);
      if (!user) return sendJson(res, 401, { error: '未登录' });
      const body = await readJsonBody(req);
      const result = await exposureBoardMod.adminRemove(user, adminRemoveMatch.id, body.reason);
      return sendJson(res, 200, result);
    }

    // ====== 配对码登录 ======
    if (urlPath === '/api/pair/generate' && req.method === 'POST') {
      const user = auth.verifyRequest(req);
      if (!user) return sendJson(res, 401, { error: '未登录' });
      const authHeader = req.headers['authorization'] || '';
      const token = authHeader.startsWith('Bearer ') ? authHeader.slice(7) : '';
      const result = await pairMod.generatePairCode(token);
      // 广播给当前用户，告知码状态
      chatServer.broadcastPairStatusUpdate(user.id, {
        pairCode: result.pairCode,
        expiresAt: result.expiresAt,
        status: 'active'
      });
      return sendJson(res, 200, { ok: true, ...result });
    }

    if (urlPath === '/api/pair/login' && req.method === 'POST') {
      const body = await readJsonBody(req);
      const result = await pairMod.loginWithPairCode(body.pairCode);
      return sendJson(res, 200, result);
    }

    if (urlPath === '/api/pair/cancel' && req.method === 'POST') {
      const user = auth.verifyRequest(req);
      if (!user) return sendJson(res, 401, { error: '未登录' });
      const authHeader = req.headers['authorization'] || '';
      const token = authHeader.startsWith('Bearer ') ? authHeader.slice(7) : '';
      const result = await pairMod.cancelPairCode(token);
      chatServer.broadcastPairStatusUpdate(user.id, { pairCode: null, status: 'cancelled' });
      return sendJson(res, 200, result);
    }

    if (urlPath === '/api/pair/status' && req.method === 'GET') {
      const user = auth.verifyRequest(req);
      if (!user) return sendJson(res, 401, { error: '未登录' });
      const authHeader = req.headers['authorization'] || '';
      const token = authHeader.startsWith('Bearer ') ? authHeader.slice(7) : '';
      const result = pairMod.getPairStatus(token);
      return sendJson(res, 200, { ok: true, ...result });
    }

    // ====== 搜索 ======
    // ====== 获取当前登录用户资料（WS 未连上时的 fallback） ======
    if (urlPath === '/api/user/me' && req.method === 'GET') {
      const user = auth.verifyRequest(req);
      if (!user) return sendJson(res, 401, { error: '未登录' });
      return sendJson(res, 200, { ok: true, user: auth.sanitizeUser(user) });
    }

    if (urlPath === '/search/users' && req.method === 'POST') {
      const user = auth.verifyRequest(req);
      if (!user) return sendJson(res, 401, { error: '未登录' });
      const body = await readJsonBody(req);
      const result = auth.searchUsers(body.keyword, user.id);
      return sendJson(res, 200, { ok: true, users: result });
    }

    if (urlPath === '/search/groups' && req.method === 'POST') {
      const user = auth.verifyRequest(req);
      if (!user) return sendJson(res, 401, { error: '未登录' });
      const body = await readJsonBody(req);
      const groups = storage.read('groups');
      const members = storage.read('groupMembers');
      const kw = String(body.keyword || '').toLowerCase();
      const result = groups
        .filter(g =>
          g.groupNumber.includes(kw) ||
          g.name.toLowerCase().includes(kw)
        )
        .slice(0, 50)
        .map(g => ({
          id: g.id,
          groupNumber: g.groupNumber,
          name: g.name,
          description: g.description,
          memberCount: members.filter(m => m.groupId === g.id).length,
          hasPassword: !!g.passwordHash,
          joined: members.some(m => m.groupId === g.id && m.userId === user.id),
          isOwner: g.ownerId === user.id
        }));
      return sendJson(res, 200, { ok: true, groups: result });
    }

    // ====== 好友 / 群 / 申请 ======
    if (urlPath === '/friends' && req.method === 'GET') {
      const user = auth.verifyRequest(req);
      if (!user) return sendJson(res, 401, { error: '未登录' });
      const friends = storage.read('friends');
      const list = friends
        .filter(f => f.userIdA === user.id || f.userIdB === user.id)
        .map(f => {
          const fid = f.userIdA === user.id ? f.userIdB : f.userIdA;
          const fuser = auth.getUserById(fid);
          return fuser ? {
            userId: fid, user: fuser, since: f.since,
            online: chatServer.isUserOnline(fid)
          } : null;
        })
        .filter(Boolean);
      return sendJson(res, 200, { ok: true, friends: list });
    }

    if (urlPath === '/friend_requests' && req.method === 'GET') {
      const user = auth.verifyRequest(req);
      if (!user) return sendJson(res, 401, { error: '未登录' });
      const requests = storage.read('friendRequests');
      const list = requests
        .filter(r => r.fromId === user.id || r.toId === user.id)
        .sort((a, b) => b.createdAt - a.createdAt)
        .map(r => ({
          ...r,
          fromUser: auth.getUserById(r.fromId),
          toUser: auth.getUserById(r.toId)
        }));
      return sendJson(res, 200, { ok: true, requests: list });
    }

    if (urlPath === '/groups' && req.method === 'GET') {
      const user = auth.verifyRequest(req);
      if (!user) return sendJson(res, 401, { error: '未登录' });
      const groups = storage.read('groups');
      const members = storage.read('groupMembers');
      const myMemberships = members.filter(m => m.userId === user.id);
      const list = myMemberships.map(m => {
        const g = groups.find(g => g.id === m.groupId);
        if (!g) return null;
        return {
          group: {
            id: g.id, groupNumber: g.groupNumber, name: g.name,
            description: g.description, ownerId: g.ownerId,
            hasPassword: !!g.passwordHash, createdAt: g.createdAt
          },
          role: m.role,
          mutedUntil: m.mutedUntil,
          joinedAt: m.joinedAt,
          memberCount: members.filter(mm => mm.groupId === g.id).length
        };
      }).filter(Boolean);
      return sendJson(res, 200, { ok: true, groups: list });
    }

    const groupMembersParam = matchPath('/groups/:id/members', urlPath);
    if (groupMembersParam && req.method === 'GET') {
      const user = auth.verifyRequest(req);
      if (!user) return sendJson(res, 401, { error: '未登录' });
      const members = storage.read('groupMembers');
      const groupMembers = members.filter(m => m.groupId === groupMembersParam.id);
      const isMember = groupMembers.some(m => m.userId === user.id);
      if (!isMember) return sendJson(res, 403, { error: '不是群成员' });
      const list = groupMembers.map(m => ({
        userId: m.userId, role: m.role, mutedUntil: m.mutedUntil,
        joinedAt: m.joinedAt, user: auth.getUserById(m.userId)
      })).filter(x => x.user);
      return sendJson(res, 200, { ok: true, members: list });
    }

    // ====== 消息列表 ======

    // 云端同步：返回当前用户 10 天内参与的所有私聊/群聊消息
    if (urlPath === '/api/messages/sync' && req.method === 'GET') {
      const user = auth.verifyRequest(req);
      if (!user) return sendJson(res, 401, { error: '未登录' });
      const cutoff = Date.now() - 10 * 24 * 60 * 60 * 1000;
      const messages = storage.read('messages');
      // 用户所在群列表
      const groupMembers = storage.read('groupMembers');
      const myGroupIds = new Set(
        groupMembers.filter(gm => gm.userId === user.id).map(gm => gm.groupId)
      );
      const list = messages
        .filter(m => m.timestamp >= cutoff)
        .filter(m => {
          if (m.type === 'private_message') {
            return m.fromId === user.id || m.toId === user.id;
          }
          if (m.type === 'group_message') {
            return m.groupId && myGroupIds.has(m.groupId);
          }
          return false;
        })
        .sort((a, b) => a.timestamp - b.timestamp);
      return sendJson(res, 200, { ok: true, messages: list });
    }

    if (urlPath === '/messages/private/:friendId' && req.method === 'GET') {
      const user = auth.verifyRequest(req);
      if (!user) return sendJson(res, 401, { error: '未登录' });
      const parts = urlPath.split('/');
      const friendId = decodeURIComponent(parts[parts.length - 1]);
      const messages = storage.read('messages');
      const list = messages
        .filter(m => m.type === 'private_message' &&
          ((m.fromId === user.id && m.toId === friendId) ||
           (m.fromId === friendId && m.toId === user.id)))
        .sort((a, b) => a.timestamp - b.timestamp)
        .slice(-200);
      return sendJson(res, 200, { ok: true, messages: list });
    }
    if (req.method === 'GET') {
      const msgPrivateMatch = matchPath('/messages/private/:friendId', urlPath);
      if (msgPrivateMatch) return; // 上面已处理
    }
    if (req.method === 'GET') {
      const msgGroupMatch = matchPath('/messages/group/:groupId', urlPath);
      if (msgGroupMatch) {
        const user = auth.verifyRequest(req);
        if (!user) return sendJson(res, 401, { error: '未登录' });
        const groupId = msgGroupMatch.groupId;
        const members = storage.read('groupMembers');
        if (!members.some(m => m.groupId === groupId && m.userId === user.id)) {
          return sendJson(res, 403, { error: '不是群成员' });
        }
        const messages = storage.read('messages');
        const list = messages
          .filter(m => m.type === 'group_message' && m.groupId === groupId)
          .sort((a, b) => a.timestamp - b.timestamp)
          .slice(-500);
        return sendJson(res, 200, { ok: true, messages: list });
      }
    }

    // ====== 文件上传（图片/语音） ======
    if (urlPath === '/upload/image' && req.method === 'POST') {
      const user = auth.verifyRequest(req);
      if (!user) return sendJson(res, 401, { error: '未登录' });
      const { files } = await multipart.parseMultipart(req);
      const file = files.find(f => f.fieldname === 'image' || f.fieldname === 'file');
      if (!file) return sendJson(res, 400, { error: '缺少文件' });
      const saved = await multipart.saveUploadedFile(
        file, 'images', 10 * 1024 * 1024,
        ['image/jpeg', 'image/png', 'image/gif', 'image/webp', 'image/bmp']
      );
      return sendJson(res, 200, { ok: true, url: saved.url });
    }

    if (urlPath === '/upload/audio' && req.method === 'POST') {
      const user = auth.verifyRequest(req);
      if (!user) return sendJson(res, 401, { error: '未登录' });
      const { files, fields } = await multipart.parseMultipart(req);
      const file = files.find(f => f.fieldname === 'audio' || f.fieldname === 'file');
      if (!file) return sendJson(res, 400, { error: '缺少文件' });
      const saved = await multipart.saveUploadedFile(
        file, 'audios', 20 * 1024 * 1024,
        ['audio/webm', 'audio/ogg', 'audio/mp4', 'audio/mpeg', 'audio/wav']
      );
      const duration = Number(fields.duration) || 0;
      return sendJson(res, 200, { ok: true, url: saved.url, duration });
    }

    // ====== 公告接口 ======
    if (urlPath === '/api/announcements' && req.method === 'GET') {
      // 公开，不需要登录
      const list = announcementMod.getAnnouncements(false);
      return sendJson(res, 200, { ok: true, announcements: list });
    }
    if (urlPath === '/api/announcements' && req.method === 'POST') {
      const user = auth.verifyRequest(req);
      if (!user) return sendJson(res, 401, { error: '未登录' });
      const body = await readJsonBody(req);
      const created = await announcementMod.createAnnouncement(user, body);
      chatServer.broadcastNewAnnouncement(created);
      return sendJson(res, 200, { ok: true, announcement: created });
    }
    const annUpdateParam = matchPath('/api/announcements/:id', urlPath);
    if (annUpdateParam && req.method === 'PUT') {
      const user = auth.verifyRequest(req);
      if (!user) return sendJson(res, 401, { error: '未登录' });
      const body = await readJsonBody(req);
      const updated = await announcementMod.updateAnnouncement(user, annUpdateParam.id, body);
      return sendJson(res, 200, { ok: true, announcement: updated });
    }
    if (annUpdateParam && req.method === 'DELETE') {
      const user = auth.verifyRequest(req);
      if (!user) return sendJson(res, 401, { error: '未登录' });
      const result = await announcementMod.deleteAnnouncement(user, annUpdateParam.id);
      return sendJson(res, 200, result);
    }

    // ====== 服务器信息 ======
    if (urlPath === '/api/server/info' && req.method === 'GET') {
      const info = serverInfoMod.getServerInfo();
      return sendJson(res, 200, { ok: true, ...info });
    }
    if (urlPath === '/api/server/info' && req.method === 'PUT') {
      const user = auth.verifyRequest(req);
      if (!user) return sendJson(res, 401, { error: '未登录' });
      const body = await readJsonBody(req);
      const result = await serverInfoMod.updateServerInfo(user, body);
      return sendJson(res, 200, { ok: true, ...result });
    }
    if (urlPath === '/api/server/logo' && req.method === 'POST') {
      const user = auth.verifyRequest(req);
      if (!user) return sendJson(res, 401, { error: '未登录' });
      const { files } = await multipart.parseMultipart(req);
      const file = files.find(f => f.fieldname === 'logo' || f.fieldname === 'file');
      if (!file) return sendJson(res, 400, { error: '缺少文件' });
      const result = await serverInfoMod.uploadLogo(user, file);
      return sendJson(res, 200, { ok: true, ...result });
    }
    if (urlPath === '/api/server/logo/emoji' && req.method === 'PUT') {
      const user = auth.verifyRequest(req);
      if (!user) return sendJson(res, 401, { error: '未登录' });
      const body = await readJsonBody(req);
      const result = await serverInfoMod.setLogoEmoji(user, body.emoji);
      return sendJson(res, 200, result);
    }

    // ====== 兜底：404 ======
    sendText(res, 404, '404 Not Found');

  } catch (err) {
    console.error('[HTTP] 出错:', urlPath, err.message);
    sendJson(res, 400, { error: err.message || '服务器错误' });
  }
}

// ==================== 启动服务 ====================

async function bootstrap() {
  console.log('============================================');
  console.log(' SanjinChat 聊天室服务器');
  console.log('============================================');

  // 1. 启动时执行一次清理
  console.log('[启动] 执行启动清理...');
  try {
    await cleanupMod.runCleanup(false);
  } catch (e) {
    console.warn('[启动] 清理失败:', e.message);
  }

  // 2. 确保默认数据
  storage.read('serverInfo');

  // 3. 创建 HTTP/HTTPS 服务器（TLS 启用时为 HTTPS，WebSocket 自动为 wss://）
  const requestHandler = (req, res) => {
    handleHttpRequest(req, res).catch(err => {
      console.error('[HTTP] 未捕获异常:', err);
      try { sendJson(res, 500, { error: '内部错误' }); } catch (_) { }
    });
  };

  let httpServer;
  let scheme;        // 'http' 或 'https'
  let wsScheme;      // 'ws' 或 'wss'

  if (TLS_CONFIG.enabled) {
    let certPem, keyPem;
    try {
      certPem = fs.readFileSync(TLS_CONFIG.cert);
      keyPem = fs.readFileSync(TLS_CONFIG.key);
    } catch (e) {
      console.error(`[启动] TLS 已启用但证书读取失败: ${e.message}`);
      console.error(`[启动]   cert: ${TLS_CONFIG.cert}`);
      console.error(`[启动]   key : ${TLS_CONFIG.key}`);
      console.error('[启动] 回退到 HTTP (ws://) 模式');
      TLS_CONFIG.enabled = false;
    }
    if (TLS_CONFIG.enabled) {
      const https = require('https');
      httpServer = https.createServer({ cert: certPem, key: keyPem }, requestHandler);
      scheme = 'https';
      wsScheme = 'wss';
    }
  }
  if (!httpServer) {
    httpServer = http.createServer(requestHandler);
    scheme = 'http';
    wsScheme = 'ws';
  }

  // 4. 挂载 WebSocket 服务器（挂到 HTTPS 服务器即为 wss://）
  const wss = new WebSocketServer({ server: httpServer, path: '/ws' });
  wss.on('connection', (ws, req) => {
    try {
      chatServer.handleConnection(ws, req.url || '');
    } catch (err) {
      // 兜底：handleConnection 内部已有 try/catch，但再保一层
      console.error('[HTTP] wss connection 异常:', err && err.message || err);
      try { ws.close(); } catch (_) { /* ignore */ }
    }
  });
  wss.on('error', (err) => {
    console.error('[HTTP] WebSocketServer error:', err && err.message || err);
  });

  // 5. 启动定时清理
  cleanupMod.scheduleDailyCleanup();

  // 6. 启动服务
  httpServer.on('error', (err) => {
    // 监听错误（端口冲突等）只打印，不退出
    console.error('==================== httpServer error ====================');
    if (err && err.code === 'EADDRINUSE') {
      console.error(`[启动] 端口 ${PORT} 已被占用`);
    } else if (err && err.code === 'EACCES') {
      console.error(`[启动] 无权限监听端口 ${PORT}（<1024 需要 root/管理员）`);
    }
    console.error(err && err.stack ? err.stack : err);
    console.error('==========================================================');
    console.error('[守护] httpServer 异常已记录，进程继续运行');
  });
  httpServer.listen(PORT, () => {
    console.log(`[启动] 服务启动成功: ${scheme}://localhost:${PORT}`);
    console.log(`[启动] WebSocket: ${wsScheme}://localhost:${PORT}/ws`);
    console.log(`[启动] 静态页面: ${scheme}://localhost:${PORT}/`);
    if (TLS_CONFIG.enabled) {
      console.log(`[启动] TLS 模式已启用 (证书: ${TLS_CONFIG.cert})`);
    } else {
      console.log('[启动] TLS 未启用 (明文 ws:// 模式)');
    }
  });
}

bootstrap().catch(err => {
  // 启动失败：只打印，不退出进程，让用户能看日志
  console.error('==================== bootstrap 失败 ====================');
  console.error(err && err.stack ? err.stack : err);
  console.error('=======================================================');
  console.error('[守护] 启动失败，进程不退出（避免脚本闪退看不清错误）');
});
