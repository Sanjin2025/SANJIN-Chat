// ==================== 登录设备管理 ====================
// 记录每个用户当前活跃 / 曾经登录过的设备会话
// - 新设备登录时向同 userId 的其他在线 WS 推 device:login 通知
// - 提供查询 / 取消登录接口（取消后对应 WS 立即关闭，且 token 被吊销）

const storage = require('./storage');
const auth = require('./auth');

/**
 * 设备会话存储：
 * Map<userId, SessionRecord[]>   —— 内存索引（按 userId 聚合）
 * 同时持久化到 data/deviceSessions.json（每 userId 一条：lastSeenAt 最近 30 天内的）
 *
 * SessionRecord 字段：
 *   token          鉴权 token（唯一标识一个"登录"）
 *   deviceId       客户端设备唯一 id（UUID，客户端生成并持久化）
 *   deviceName     客户端上报的设备名（Android Build.MODEL / 浏览器 UA 主段）
 *   ip             登录时客户端 IP
 *   userAgent      登录时 HTTP User-Agent
 *   connectedAt    首次连上时间
 *   lastSeenAt     最后一次活动时间
 *   revokedAt      被取消登录的时间（null 表示仍有效）
 *   wsKey          用于把 WS 实例和这条记录关联起来（_registerSession 里设置）
 */

const FILE_KEY = 'deviceSessions';

/** userId -> SessionRecord[] */
const sessionsByUser = new Map();
/** token -> userId（反向索引，用于 WS 关闭时快速定位） */
const tokenToUser = new Map();

let chatServerRef = null;

function attachChatServer(cs) { chatServerRef = cs; }

function load() {
  let data = null;
  try { data = storage.read(FILE_KEY); } catch (_) { /* ignore */ }
  if (Array.isArray(data)) {
    for (const rec of data) {
      if (!rec || !rec.token || !rec.userId) continue;
      const arr = sessionsByUser.get(rec.userId) || [];
      arr.push(rec);
      sessionsByUser.set(rec.userId, arr);
      tokenToUser.set(rec.token, rec.userId);
    }
  }
}

function persist() {
  try {
    const all = [];
    const now = Date.now();
    const CUTOFF = 30 * 24 * 3600 * 1000; // 30 天
    for (const [userId, arr] of sessionsByUser) {
      for (const rec of arr) {
        if (!rec.lastSeenAt) continue;
        if (now - rec.lastSeenAt > CUTOFF && rec.revokedAt) continue;
        all.push(rec);
      }
    }
    storage.writeSync(FILE_KEY, all);
  } catch (err) {
    console.error('[deviceSessions] persist failed:', err.message);
  }
}

/** 从 req 里解析客户端 IP（兼容反代） */
function extractClientIp(req) {
  const fwd = req.headers['x-forwarded-for'];
  if (fwd && typeof fwd === 'string') {
    const first = fwd.split(',')[0].trim();
    if (first) return first;
  }
  const realIp = req.headers['x-real-ip'];
  if (typeof realIp === 'string' && realIp.trim()) return realIp.trim();
  try { return req.socket && req.socket.remoteAddress || null; } catch (_) { return null; }
}

function sanitizeIp(ip) {
  if (!ip) return null;
  // IPv6-mapped IPv4 ::ffff:127.0.0.1 → 127.0.0.1
  const m = /^::ffff:(\d+\.\d+\.\d+\.\d+)$/.exec(ip);
  if (m) return m[1];
  return ip;
}

/**
 * WS 握手成功后登记一条会话。
 * @param {WebSocket} ws
 * @param {string} token
 * @param {{userId:string, user:Object}} authInfo
 * @param {IncomingMessage} req
 * @param {string} url 原始 ws 请求的完整路径（带 query）
 */
function registerSession(ws, token, authInfo, req, url) {
  const userId = authInfo.id || authInfo.userId;
  const ip = sanitizeIp(extractClientIp(req));
  const userAgent = (req.headers['user-agent'] || '').toString().slice(0, 240);
  let deviceId = null, deviceName = null;
  try {
    const queryIdx = (url || '').indexOf('?');
    if (queryIdx !== -1) {
      const params = new URLSearchParams(url.slice(queryIdx + 1));
      deviceId = (params.get('deviceId') || '').toString().slice(0, 80) || null;
      deviceName = (params.get('deviceName') || '').toString().slice(0, 80) || null;
    }
  } catch (_) { /* ignore */ }
  if (!deviceName) {
    deviceName = (userAgent.split(') ')[1] || userAgent).split('(')[0].trim().slice(0, 40)
      || '未知设备';
  }

  // 同一 token 的旧记录：合并，用新的 ip/ua/deviceName 刷新
  let arr = sessionsByUser.get(userId);
  let existing = null;
  if (arr) existing = arr.find(r => r.token === token);
  if (!arr) { arr = []; sessionsByUser.set(userId, arr); }

  const now = Date.now();
  const wsKey = Symbol('ws');
  ws.deviceSessionKey = wsKey;
  ws.deviceSessionToken = token;

  if (existing) {
    existing.ip = ip || existing.ip;
    existing.userAgent = userAgent || existing.userAgent;
    if (deviceName) existing.deviceName = deviceName;
    if (deviceId) existing.deviceId = deviceId;
    existing.lastSeenAt = now;
    existing.revokedAt = null;
    existing.wsKey = wsKey;
  } else {
    const rec = {
      token,
      userId,
      deviceId,
      deviceName,
      ip,
      userAgent,
      connectedAt: now,
      lastSeenAt: now,
      revokedAt: null,
      wsKey
    };
    arr.push(rec);
    tokenToUser.set(token, userId);

    // 新设备登录 → 通知同 userId 的其他在线 WS
    _broadcastLoginEvent(userId, rec);
  }
  persist();
}

function _broadcastLoginEvent(userId, rec) {
  if (!chatServerRef) return;
  const sockets = chatServerRef.userSockets.get(userId);
  if (!sockets || sockets.size === 0) return;
  const payload = {
    type: 'device:login',
    device: {
      deviceName: rec.deviceName,
      deviceId: rec.deviceId,
      ip: rec.ip,
      connectedAt: rec.connectedAt
    }
  };
  for (const otherWs of sockets) {
    // 新登录的那只 ws 自己也在 set 里，排除掉
    if (otherWs.deviceSessionToken === rec.token) continue;
    try { otherWs.send(JSON.stringify(payload)); } catch (_) { /* ignore */ }
  }
}

/** WS 关闭 / 网络断开：更新 lastSeenAt，不删除（保留"已下线"记录） */
function markDisconnected(ws) {
  const token = ws.deviceSessionToken;
  if (!token) return;
  const userId = tokenToUser.get(token);
  if (!userId) return;
  const arr = sessionsByUser.get(userId);
  const rec = arr && arr.find(r => r.token === token);
  if (rec) {
    rec.wsKey = null;
    rec.lastSeenAt = Date.now();
    persist();
  }
}

/** HTTP GET /api/auth/devices：返回指定用户的全部会话 */
function listForUser(userId, currentToken) {
  const arr = sessionsByUser.get(userId) || [];
  return arr.map(r => ({
    token: r.token,
    deviceId: r.deviceId,
    deviceName: r.deviceName,
    ip: r.ip,
    userAgent: r.userAgent,
    connectedAt: r.connectedAt,
    lastSeenAt: r.lastSeenAt,
    revokedAt: r.revokedAt,
    online: r.wsKey != null,
    isCurrent: r.token === currentToken
  })).sort((a, b) => b.connectedAt - a.connectedAt);
}

/** HTTP POST /api/auth/logout-device：取消某设备登录 */
function logoutDevice(userId, tokenToRevoke) {
  const arr = sessionsByUser.get(userId);
  if (!arr) return { ok: false, error: '该账号无登录设备' };
  const rec = arr.find(r => r.token === tokenToRevoke);
  if (!rec) return { ok: false, error: '未找到该设备的登录记录' };
  if (rec.revokedAt != null) return { ok: false, error: '该设备已被取消登录' };

  rec.revokedAt = Date.now();
  rec.wsKey = null;

  // 立即关闭对应 WS
  const sockets = chatServerRef && chatServerRef.userSockets.get(userId);
  if (sockets) {
    for (const ws of sockets) {
      if (ws.deviceSessionToken === tokenToRevoke) {
        try {
          ws.send(JSON.stringify({ type: 'device:logout', reason: '被用户在其他设备上取消登录' }));
          ws.close(4001, 'device_logout');
        } catch (_) { /* ignore */ }
      }
    }
  }

  // 吊销 token：auth.verifyToken 时需要跳过被吊销的
  auth.tokenStore.delete(tokenToRevoke);

  persist();
  return { ok: true, revokedToken: tokenToRevoke, deviceName: rec.deviceName };
}

/** 启动时调用一次 */
function init(chatServer) {
  chatServerRef = chatServer;
  load();
  // 每分钟更新一次在线设备的 lastSeenAt（心跳）
  setInterval(() => {
    const now = Date.now();
    for (const arr of sessionsByUser.values()) {
      for (const r of arr) {
        if (r.wsKey != null) r.lastSeenAt = now;
      }
    }
    persist();
  }, 60 * 1000);
}

module.exports = {
  init,
  attachChatServer,
  registerSession,
  markDisconnected,
  listForUser,
  logoutDevice
};
