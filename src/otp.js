// ==================== 验证码登录模块 ====================
// 流程：
//   1. 客户端 POST /api/otp/generate 传 { username }（无需登录）
//      服务端校验用户名存在 → 生成 6 位数字验证码 → 存 otpSessions 表
//      → 返回 { ok:true, code, expiresAt, expiresIn }
//      （演示环境直接返回 code 便于客户端自动填入；生产环境请删掉 code 字段，改短信/邮件发送）
//   2. 客户端 POST /api/otp/login 传 { username, code }（不需要 otpId）
//      服务端按 username 找最新一条 pending 且未过期的 session 校验 code
//      → 成功：标记 used、生成 token、返回 { ok, token, user }

const storage = require('./storage');
const auth = require('./auth');

/** 6 位数字验证码长度 */
const OTP_LENGTH = 6;
/** 有效期：5 分钟（与客户端默认 expiresIn=300s 对齐） */
const OTP_EXPIRES_MS = 5 * 60 * 1000;
/** 同用户名连续生成最短间隔（防刷）：30 秒 */
const MIN_INTERVAL_MS = 30 * 1000;
/** 同用户名每小时最大生成次数（防刷） */
const MAX_GENERATE_PER_HOUR = 10;

/**
 * 生成 6 位数字验证码
 * @returns {string}
 */
function generateNumericCode() {
  let code = '';
  for (let i = 0; i < OTP_LENGTH; i++) {
    code += Math.floor(Math.random() * 10).toString();
  }
  return code;
}

/**
 * 生成验证码
 * @param {string} username
 * @returns {Promise<{ok:boolean, code:string, expiresAt:number, expiresIn:number}>}
 */
async function generate(username) {
  if (!username) throw new Error('用户名不能为空');
  username = String(username).trim();
  if (username.length < 3) throw new Error('用户名长度至少 3 位');

  // 用户必须存在（否则随便填用户名就能刷接口）
  const users = storage.read('users');
  const user = users.find(u => u.username === username);
  if (!user) throw new Error('用户不存在');

  const sessions = storage.read('otpSessions');
  const now = Date.now();

  // ---------- 防刷限制 1：连续间隔 ----------
  const last = sessions
    .filter(s => s.username === username)
    .sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0))[0];
  if (last && now - (last.createdAt || 0) < MIN_INTERVAL_MS) {
    const wait = Math.ceil((MIN_INTERVAL_MS - (now - last.createdAt)) / 1000);
    throw new Error(`生成太频繁，请 ${wait} 秒后再试`);
  }

  // ---------- 防刷限制 2：每小时次数 ----------
  const hourAgo = now - 60 * 60 * 1000;
  const countInHour = sessions.filter(s =>
    s.username === username && (s.createdAt || 0) > hourAgo
  ).length;
  if (countInHour >= MAX_GENERATE_PER_HOUR) {
    throw new Error('该账号 1 小时内获取次数已达上限，请稍后再试');
  }

  // ---------- 生成会话 ----------
  const otpId = 'otp_' + storage.generateId();
  const code = generateNumericCode();
  const expiresAt = now + OTP_EXPIRES_MS;

  sessions.push({
    otpId,
    userId: user.id,
    username,
    code,                // 纯 6 位数字
    status: 'pending',   // pending → used
    createdAt: now,
    expiresAt,
  });
  await storage.write('otpSessions', sessions);

  console.log(`[OTP] 验证码已生成: 用户=${username}  code=${code}  expiresAt=${new Date(expiresAt).toLocaleString()}`);

  return {
    ok: true,
    code,               // 演示环境直接返回 code，客户端自动填入；生产部署请删掉此行改短信发送
    expiresAt,
    expiresIn: Math.max(1, Math.floor((expiresAt - now) / 1000)),
  };
}

/**
 * 用验证码登录（按 username 找最新 pending session，不需要 otpId）
 * @param {string} username
 * @param {string} code
 * @returns {Promise<{ok:boolean, token:string, user:Object}>}
 */
async function login(username, code) {
  if (!username) throw new Error('用户名不能为空');
  if (!code) throw new Error('缺少验证码');
  username = String(username).trim();
  code = String(code).trim();

  const sessions = storage.read('otpSessions');
  const now = Date.now();

  // 找该 username 最新一条 pending 且未过期的 session
  const candidates = sessions
    .map((s, i) => ({ s, i }))
    .filter(x => x.s.username === username && x.s.status === 'pending' && x.s.expiresAt > now)
    .sort((a, b) => (b.s.createdAt || 0) - (a.s.createdAt || 0));

  if (candidates.length === 0) throw new Error('验证码已过期或不存在，请重新获取');

  const { s, i } = candidates[0];

  // 6 位数字验证码：严格相等
  if (s.code !== code) throw new Error('验证码错误');

  // 通过：标记 used
  sessions[i].status = 'used';
  sessions[i].usedAt = now;
  await storage.write('otpSessions', sessions);

  // 取用户 + 发 token
  const user = auth.getUserById(s.userId);
  if (!user) throw new Error('用户不存在');
  const tokenStore = auth.tokenStore;
  const token = 'tk_' + storage.generateId() + storage.generateId();
  tokenStore.set(token, { userId: user.id, createdAt: now });

  return { ok: true, token, user };
}

/**
 * 清理过期验证码会话（每 5 分钟）
 */
async function cleanupExpiredSessions() {
  const sessions = storage.read('otpSessions');
  const now = Date.now();
  // 清理过期 1 小时以上的历史记录（保留最近 1h 供审计）
  const cutoff = now - 60 * 60 * 1000;
  const cleaned = sessions.filter(s => (s.expiresAt || 0) > cutoff);
  if (cleaned.length !== sessions.length) {
    await storage.write('otpSessions', cleaned);
  }
}

// 每 5 分钟清理一次
setInterval(cleanupExpiredSessions, 5 * 60 * 1000).unref();

module.exports = {
  generate,
  login,
  cleanupExpiredSessions,
};
