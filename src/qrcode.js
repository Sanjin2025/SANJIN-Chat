// ==================== 二维码登录模块 ====================
// 生成二维码会话、查询状态、扫码确认登录
// 客户端轮询 /api/qrcode/status/:qrSessionId，confirmed 时直接拿到 token+user

const QRCode = require('qrcode');
const storage = require('./storage');
const auth = require('./auth');

// 二维码有效期：5分钟
const QR_EXPIRES_MS = 5 * 60 * 1000;

/**
 * 生成二维码会话
 * @returns {Promise<{qrSessionId: string, qrDataUrl: string, expiresAt: number}>}
 */
async function generateQrSession() {
  const qrSessionId = 'qr_' + storage.generateId();
  const expiresAt = Date.now() + QR_EXPIRES_MS;

  // 清理过期会话，同时加入新的
  const sessions = storage.read('qrSessions');
  const now = Date.now();
  const cleaned = sessions.filter(s => s.expiresAt > now);
  cleaned.push({
    qrSessionId,
    userId: null,
    status: 'pending', // pending -> confirmed -> used / expired
    createdAt: Date.now(),
    expiresAt
  });
  await storage.write('qrSessions', cleaned);

  // 生成二维码图片（data URL）
  // payload 中只携带 qrSessionId，实际扫码确认由已登录设备发起
  const payload = JSON.stringify({ type: 'sanjinchat_login', qrSessionId });
  const qrDataUrl = await QRCode.toDataURL(payload, {
    width: 240,
    margin: 2,
    color: { dark: '#000000', light: '#ffffff' }
  });

  return { qrSessionId, qrDataUrl, expiresAt };
}

/**
 * 查询二维码状态（轮询接口）
 * confirmed 时直接生成 token、标记 used、返回 token+user（原子操作，消除竞态）
 * @param {string} qrSessionId
 * @returns {Promise<{status: string, expiresAt: number, token?: string, user?: Object}>}
 */
async function getQrStatus(qrSessionId) {
  const sessions = storage.read('qrSessions');
  const session = sessions.find(s => s.qrSessionId === qrSessionId);

  const now = Date.now();
  if (!session || session.expiresAt < now) {
    return { status: 'expired', expiresAt: session ? session.expiresAt : 0 };
  }

  if (session.status === 'confirmed' && session.userId) {
    // 原子发放：找到 session 后直接生成 token 并标记 used
    // 不再依赖 server.js 二次调 consumeQrToken，消除竞态
    const user = auth.getUserById(session.userId);
    if (!user) return { status: 'expired', expiresAt: session.expiresAt };

    const tokenStore = auth.tokenStore;
    const token = 'tk_' + storage.generateId() + storage.generateId();
    tokenStore.set(token, { userId: user.id, createdAt: now });

    // 标记 used，防止重复发 token
    session.status = 'used';
    await storage.write('qrSessions', sessions);

    return { status: 'confirmed', expiresAt: session.expiresAt, token, user };
  }

  return { status: session.status, expiresAt: session.expiresAt };
}

/**
 * 已登录设备扫码确认
 * @param {string} qrSessionId
 * @param {string} token 已登录用户的 token
 */
async function confirmQrLogin(qrSessionId, token) {
  const user = auth.verifyToken(token);
  if (!user) throw new Error('登录已失效，请重新登录');

  const sessions = storage.read('qrSessions');
  const idx = sessions.findIndex(s => s.qrSessionId === qrSessionId);
  if (idx === -1) throw new Error('二维码不存在');
  if (sessions[idx].expiresAt < Date.now()) throw new Error('二维码已过期');
  if (sessions[idx].status !== 'pending') throw new Error('二维码已被使用');

  sessions[idx].status = 'confirmed';
  sessions[idx].userId = user.id;
  await storage.write('qrSessions', sessions);

  return { ok: true };
}

/**
 * 清理过期的二维码会话
 */
async function cleanupExpiredSessions() {
  const sessions = storage.read('qrSessions');
  const now = Date.now();
  const cleaned = sessions.filter(s => s.expiresAt > now);
  if (cleaned.length !== sessions.length) {
    await storage.write('qrSessions', cleaned);
  }
}

// 每 3 分钟清理一次过期会话
setInterval(cleanupExpiredSessions, 3 * 60 * 1000).unref();

module.exports = {
  generateQrSession,
  getQrStatus,
  confirmQrLogin,
  cleanupExpiredSessions
};
