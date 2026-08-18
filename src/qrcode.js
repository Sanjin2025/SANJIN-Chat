// ==================== 二维码登录模块 ====================
// 生成二维码会话、查询状态、扫码确认登录

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
 * @param {string} qrSessionId
 * @returns {{status: string, expiresAt: number, token?: string, user?: Object}}
 */
function getQrStatus(qrSessionId) {
  const sessions = storage.read('qrSessions');
  const session = sessions.find(s => s.qrSessionId === qrSessionId);

  const now = Date.now();
  if (!session || session.expiresAt < now) {
    return { status: 'expired', expiresAt: session ? session.expiresAt : 0 };
  }

  // 如果已被确认，并且未被使用，则发放 token
  if (session.status === 'confirmed' && session.userId) {
    const user = auth.getUserById(session.userId);
    if (user) {
      // 生成 token，标记 used
      // eslint-disable-next-line no-async-promise-executor
      return { status: 'confirmed', expiresAt: session.expiresAt, _userId: session.userId, _sessionId: qrSessionId };
    }
  }

  return { status: session.status, expiresAt: session.expiresAt };
}

/**
 * 当状态为 confirmed 时，真正创建 token
 */
async function consumeQrToken(qrSessionId) {
  const sessions = storage.read('qrSessions');
  const idx = sessions.findIndex(s => s.qrSessionId === qrSessionId);
  if (idx === -1) throw new Error('会话不存在');
  if (sessions[idx].status !== 'confirmed') throw new Error('会话未确认');
  if (!sessions[idx].userId) throw new Error('会话无效');

  const userId = sessions[idx].userId;
  sessions[idx].status = 'used';
  await storage.write('qrSessions', sessions);

  const user = auth.getUserById(userId);
  if (!user) throw new Error('用户不存在');

  // 生成 token
  const tokenStore = auth.tokenStore;
  const token = 'tk_' + storage.generateId() + storage.generateId();
  tokenStore.set(token, { userId, createdAt: Date.now() });

  return { token, user };
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
  consumeQrToken,
  cleanupExpiredSessions
};
