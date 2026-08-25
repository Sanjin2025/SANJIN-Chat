// ==================== 曝光板模块 ====================
// 允许用户自愿「加入曝光板」，展示自己的公开资料（头像/昵称/用户名/简介/标签）
// 任何人无需登录即可查看曝光板列表
// 管理员可将违规条目下架（removed=true，列表不返回）

const storage = require('./storage');
const auth = require('./auth');

/** 最大简介长度 */
const MAX_BIO_LENGTH = 200;
/** 最大标签数量 */
const MAX_TAGS = 8;
/** 单个标签最大长度 */
const MAX_TAG_LENGTH = 10;
/** 曝光时限：加入/更新后 30 分钟自动下架（updatedAt 起算） */
const EXPOSURE_TTL_MS = 30 * 60 * 1000;

// ---- 工具 ----
function normalizeTags(arr) {
  if (!Array.isArray(arr)) return [];
  return [...new Set(arr
    .map(t => String(t).trim())
    .filter(t => t && t.length <= MAX_TAG_LENGTH)
  )].slice(0, MAX_TAGS);
}

// ============================================================
// 1. 加入曝光板 / 更新自己的曝光资料
// ============================================================
async function upsertExposure(user, opts = {}) {
  if (!user) throw new Error('未登录');

  // 字段校验
  const bio = (opts.bio === undefined ? '' : String(opts.bio)).slice(0, MAX_BIO_LENGTH);
  const tags = normalizeTags(opts.tags);
  const visible = (opts.visible === undefined) ? true : Boolean(opts.visible);

  const items = storage.read('exposureBoard');

  const now = Date.now();
  const idx = items.findIndex(x => x.userId === user.id && !x.removed);
  if (idx >= 0) {
    // 已有曝光记录，做更新（保留原 createdAt）
    items[idx].bio = bio;
    items[idx].tags = tags;
    items[idx].visible = visible;
    items[idx].updatedAt = now;
  } else {
    items.push({
      id: 'ex_' + storage.generateId(),
      userId: user.id,
      username: user.username,        // 快照：用户名（用户修改昵称/头像时会同步更新）
      nickname: user.nickname || user.username,
      avatar: user.avatar || null,
      avatarType: user.avatarType || null,
      avatarEmoji: user.avatarEmoji || null,
      bio,
      tags,
      visible,
      removed: false,
      createdAt: now,
      updatedAt: now,
    });
  }
  await storage.write('exposureBoard', items);

  return getMyExposure(user.id);
}

// ============================================================
// 2. 我退出曝光板（软删，设置 removed=true）
// ============================================================
async function leaveExposure(userId) {
  if (!userId) throw new Error('未登录');
  const items = storage.read('exposureBoard');
  const idx = items.findIndex(x => x.userId === userId && !x.removed);
  if (idx === -1) return { ok: true, message: '未在曝光板中' };
  items[idx].removed = true;
  items[idx].updatedAt = Date.now();
  await storage.write('exposureBoard', items);
  return { ok: true, message: '已退出曝光板' };
}

// ============================================================
// 3. 管理员下架（任何管理员可下架）
// ============================================================
async function adminRemove(adminUser, exposureId, reason) {
  if (!adminUser || adminUser.role !== 'admin') {
    throw new Error('仅管理员可下架');
  }
  const items = storage.read('exposureBoard');
  const idx = items.findIndex(x => x.id === exposureId);
  if (idx === -1) throw new Error('曝光记录不存在');
  items[idx].removed = true;
  items[idx].removedAt = Date.now();
  items[idx].removedBy = adminUser.id;
  items[idx].removeReason = reason ? String(reason).slice(0, 200) : '';
  items[idx].updatedAt = Date.now();
  await storage.write('exposureBoard', items);
  return { ok: true };
}

// ============================================================
// 4. 查询曝光板列表（公开，任何人可读）
//    按 updatedAt 倒序；支持按标签过滤；支持分页
// ============================================================
function listExposure({ tag, page = 1, pageSize = 20 } = {}) {
  const items = storage.read('exposureBoard');
  const now = Date.now();
  let list = items.filter(x =>
    !x.removed &&
    x.visible !== false &&
    // 曝光 10h 有效：updatedAt + TTL > now
    ((x.updatedAt || 0) + EXPOSURE_TTL_MS) > now
  );

  // 标签过滤（任一标签匹配即返回）
  if (tag) {
    const t = String(tag).trim();
    if (t) list = list.filter(x => x.tags && x.tags.includes(t));
  }

  // 按 updatedAt 倒序
  list.sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0));

  // 分页
  const p = Math.max(1, parseInt(page, 10) || 1);
  const s = Math.min(100, Math.max(1, parseInt(pageSize, 10) || 20));
  const total = list.length;
  const paged = list.slice((p - 1) * s, p * s);

  // 返回字段：公开信息 + userId（用于加好友）
  return {
    total,
    page: p,
    pageSize: s,
    list: paged.map(x => ({
      id: x.id,
      userId: x.userId,
      username: x.username,
      nickname: x.nickname,
      avatar: x.avatar,
      avatarType: x.avatarType,
      avatarEmoji: x.avatarEmoji,
      bio: x.bio,
      tags: x.tags,
      createdAt: x.createdAt,
      updatedAt: x.updatedAt,
      expiresAt: (x.updatedAt || 0) + EXPOSURE_TTL_MS,
    })),
  };
}

// ============================================================
// 5. 查询我自己的曝光资料（用于「我的」Tab 显示/编辑）
// ============================================================
function getMyExposure(userId) {
  if (!userId) return null;
  const items = storage.read('exposureBoard');
  const row = items.find(x => x.userId === userId && !x.removed);
  if (!row) return { onBoard: false };
  const now = Date.now();
  const expiresAt = (row.updatedAt || 0) + EXPOSURE_TTL_MS;
  const expired = expiresAt <= now;
  return {
    onBoard: true,
    id: row.id,
    bio: row.bio,
    tags: row.tags,
    visible: row.visible,
    createdAt: row.createdAt,
    updatedAt: row.updatedAt,
    expiresAt,
    expired,      // true 表示当前虽然还有记录，但已过 10h，列表不会返回；用户可以重新点 upsert 续期
    expiresInMs: Math.max(0, expiresAt - now),
  };
}

// ============================================================
// 6. 当用户资料变化时，同步曝光板快照（由 auth.js 或 server.js 的变更点调用）
// ============================================================
async function syncUserProfile(user) {
  if (!user) return;
  const items = storage.read('exposureBoard');
  let changed = false;
  for (const row of items) {
    if (row.removed) continue;
    if (row.userId !== user.id) continue;
    row.username = user.username;
    row.nickname = user.nickname || user.username;
    row.avatar = user.avatar || null;
    row.avatarType = user.avatarType || null;
    row.avatarEmoji = user.avatarEmoji || null;
    row.updatedAt = Date.now();
    changed = true;
    break;
  }
  if (changed) await storage.write('exposureBoard', items);
}

// ============================================================
// 7. 用户注销时，同步软删其曝光板记录
// ============================================================
async function handleUserDeleted(userId) {
  if (!userId) return;
  const items = storage.read('exposureBoard');
  let changed = false;
  for (const row of items) {
    if (row.removed) continue;
    if (row.userId !== userId) continue;
    row.removed = true;
    row.removedAt = Date.now();
    row.removedBy = userId;
    row.removeReason = '用户已注销';
    row.updatedAt = Date.now();
    changed = true;
  }
  if (changed) await storage.write('exposureBoard', items);
}

module.exports = {
  upsertExposure,
  leaveExposure,
  adminRemove,
  listExposure,
  getMyExposure,
  syncUserProfile,
  handleUserDeleted,
};
