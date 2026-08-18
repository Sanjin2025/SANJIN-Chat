// ==================== 配对码登录模块 ====================
// 6 位配对码，2 分钟有效，用户可以在 A 设备生成并在 B 设备输入登录

const storage = require('./storage');
const auth = require('./auth');

// 配对码有效期：2 分钟
const PAIR_EXPIRES_MS = 2 * 60 * 1000;

/**
 * 生成配对码（必须已登录）
 * @param {string} token 用户token
 * @returns {Promise<{pairCode: string, expiresIn: number, expiresAt: number}>}
 */
async function generatePairCode(token) {
  const user = auth.verifyToken(token);
  if (!user) throw new Error('登录已失效');

  const now = Date.now();
  const pairCodes = storage.read('pairCodes');

  // 先取消该用户之前未使用的码
  const cleaned = pairCodes.filter(p => {
    if (p.userId === user.id && p.status === 'active') return false;
    return p.expiresAt > now;
  });

  // 生成不重复的 6 位码
  let pairCode;
  let tries = 0;
  do {
    pairCode = storage.generateSixDigitCode();
    tries++;
  } while (
    cleaned.some(p => p.pairCode === pairCode && p.expiresAt > now) &&
    tries < 50
  );

  const expiresAt = now + PAIR_EXPIRES_MS;
  cleaned.push({
    pairCode,
    userId: user.id,
    createdAt: now,
    expiresAt,
    status: 'active' // active -> used / cancelled / expired
  });

  await storage.write('pairCodes', cleaned);

  return {
    pairCode,
    expiresIn: Math.floor(PAIR_EXPIRES_MS / 1000),
    expiresAt
  };
}

/**
 * 使用配对码登录（新设备）
 * @param {string} pairCode 6位配对码
 * @returns {Promise<{ok: true, token: string, user: Object}>}
 */
async function loginWithPairCode(pairCode) {
  if (!/^\d{6}$/.test(pairCode)) throw new Error('配对码格式错误');

  const now = Date.now();
  const pairCodes = storage.read('pairCodes');
  const idx = pairCodes.findIndex(p => p.pairCode === pairCode);

  if (idx === -1) throw new Error('配对码不存在');
  const rec = pairCodes[idx];

  if (rec.expiresAt < now) throw new Error('配对码已过期');
  if (rec.status !== 'active') throw new Error('配对码已失效');

  // 查找用户
  const user = auth.getUserById(rec.userId);
  if (!user) throw new Error('用户不存在');

  // 标记已使用
  pairCodes[idx].status = 'used';
  await storage.write('pairCodes', pairCodes);

  // 生成 token
  const token = 'tk_' + storage.generateId() + storage.generateId();
  auth.tokenStore.set(token, { userId: user.id, createdAt: now });

  return { ok: true, token, user };
}

/**
 * 取消配对码（在有效期内主动取消）
 * @param {string} token 用户token
 */
async function cancelPairCode(token) {
  const user = auth.verifyToken(token);
  if (!user) throw new Error('登录已失效');

  const now = Date.now();
  const pairCodes = storage.read('pairCodes');
  pairCodes.forEach(p => {
    if (p.userId === user.id && p.status === 'active' && p.expiresAt > now) {
      p.status = 'cancelled';
    }
  });
  await storage.write('pairCodes', pairCodes);

  return { ok: true };
}

/**
 * 查询当前用户的配对码状态
 * @param {string} token 用户token
 */
function getPairStatus(token) {
  const user = auth.verifyToken(token);
  if (!user) throw new Error('登录已失效');

  const now = Date.now();
  const pairCodes = storage.read('pairCodes');
  const rec = pairCodes.find(
    p => p.userId === user.id && p.status === 'active' && p.expiresAt > now
  );

  if (!rec) {
    return { pairCode: null, expiresAt: 0, status: 'none' };
  }
  return {
    pairCode: rec.pairCode,
    expiresAt: rec.expiresAt,
    status: rec.status
  };
}

/**
 * 清理过期配对码
 */
async function cleanupExpiredPairs() {
  const now = Date.now();
  const pairCodes = storage.read('pairCodes');
  const cleaned = pairCodes.filter(p => p.expiresAt > now || p.status === 'used');
  if (cleaned.length !== pairCodes.length) {
    await storage.write('pairCodes', cleaned);
  }
}

// 每 1 分钟清理
setInterval(cleanupExpiredPairs, 60 * 1000).unref();

module.exports = {
  generatePairCode,
  loginWithPairCode,
  cancelPairCode,
  getPairStatus,
  cleanupExpiredPairs
};
