// ==================== 服务器公告模块 ====================
// 管理员发布、修改、删除公告，用户获取公告列表

const storage = require('./storage');
const auth = require('./auth');

/**
 * 获取所有公告（按时间倒序）
 * @param {boolean} includeExpired 是否包含过期的，默认 false
 */
function getAnnouncements(includeExpired = false) {
  const items = storage.read('announcements');
  const now = Date.now();
  let result = items;
  if (!includeExpired) {
    result = items.filter(a => !a.expiresAt || a.expiresAt > now);
  }
  return result.sort((a, b) => b.createdAt - a.createdAt);
}

/**
 * 管理员创建公告
 * @param {Object} adminUser 管理员用户对象
 * @param {Object} data { title, content, type?, expiresAt? }
 */
async function createAnnouncement(adminUser, data) {
  if (!adminUser || adminUser.role !== 'admin') throw new Error('无权限');
  if (!data.title || !data.title.trim()) throw new Error('标题不能为空');
  if (!data.content || !data.content.trim()) throw new Error('内容不能为空');

  const items = storage.read('announcements');
  const ann = {
    id: storage.generateId(),
    title: data.title.trim(),
    content: data.content.trim(),
    type: data.type || 'info', // info/warning/important
    authorId: adminUser.id,
    authorNickname: adminUser.nickname,
    createdAt: Date.now(),
    expiresAt: data.expiresAt || null // 时间戳毫秒，null=永不过期
  };

  items.push(ann);
  await storage.write('announcements', items);
  return ann;
}

/**
 * 管理员修改公告
 */
async function updateAnnouncement(adminUser, id, data) {
  if (!adminUser || adminUser.role !== 'admin') throw new Error('无权限');

  const items = storage.read('announcements');
  const idx = items.findIndex(a => a.id === id);
  if (idx === -1) throw new Error('公告不存在');

  if (data.title !== undefined) items[idx].title = String(data.title).trim();
  if (data.content !== undefined) items[idx].content = String(data.content).trim();
  if (data.type !== undefined) items[idx].type = data.type;
  if (data.expiresAt !== undefined) items[idx].expiresAt = data.expiresAt || null;

  await storage.write('announcements', items);
  return items[idx];
}

/**
 * 管理员删除公告
 */
async function deleteAnnouncement(adminUser, id) {
  if (!adminUser || adminUser.role !== 'admin') throw new Error('无权限');

  const items = storage.read('announcements');
  const filtered = items.filter(a => a.id !== id);
  if (filtered.length === items.length) throw new Error('公告不存在');

  await storage.write('announcements', filtered);
  return { ok: true };
}

/**
 * 清理过期的公告
 */
async function cleanupExpiredAnnouncements() {
  const items = storage.read('announcements');
  const now = Date.now();
  const filtered = items.filter(a => !a.expiresAt || a.expiresAt > now);
  if (filtered.length !== items.length) {
    await storage.write('announcements', filtered);
  }
}

module.exports = {
  getAnnouncements,
  createAnnouncement,
  updateAnnouncement,
  deleteAnnouncement,
  cleanupExpiredAnnouncements
};
