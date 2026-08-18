// ==================== 服务器信息模块 ====================
// 关于页面信息：服务器名称、描述、公告、版权、Logo 等

const storage = require('./storage');
const auth = require('./auth');
const multipart = require('./multipart');

/**
 * 获取服务器信息（对外公开）
 */
function getServerInfo() {
  const info = storage.read('serverInfo');
  return {
    serverName: info.serverName,
    serverDescription: info.serverDescription,
    serverAnnouncement: info.serverAnnouncement,
    copyright: info.copyright,
    logo: info.logo,
    logoType: info.logoType,
    logoEmoji: info.logoEmoji,
    updatedAt: info.updatedAt
  };
}

/**
 * 管理员更新服务器信息
 */
async function updateServerInfo(adminUser, data) {
  if (!adminUser || adminUser.role !== 'admin') throw new Error('无权限');

  const info = storage.read('serverInfo');
  const allowed = ['serverName', 'serverDescription', 'serverAnnouncement', 'copyright'];
  allowed.forEach(k => {
    if (data[k] !== undefined) info[k] = String(data[k]);
  });
  info.updatedAt = Date.now();
  info.updatedBy = adminUser.id;

  await storage.write('serverInfo', info);
  return getServerInfo();
}

/**
 * 管理员上传 Logo 图片
 */
async function uploadLogo(adminUser, file) {
  if (!adminUser || adminUser.role !== 'admin') throw new Error('无权限');

  const saved = await multipart.saveUploadedFile(
    file,
    'logo',
    2 * 1024 * 1024,
    ['image/jpeg', 'image/png', 'image/gif', 'image/webp']
  );

  const info = storage.read('serverInfo');
  // 删除旧图片 logo
  if (info.logoType === 'image' && info.logo) {
    multipart.deleteUploadedFile(info.logo);
  }

  info.logoType = 'image';
  info.logo = saved.url;
  info.logoEmoji = '';
  info.updatedAt = Date.now();
  info.updatedBy = adminUser.id;

  await storage.write('serverInfo', info);

  return { url: saved.url, logoType: 'image' };
}

/**
 * 管理员使用 Emoji 作为 Logo
 */
async function setLogoEmoji(adminUser, emoji) {
  if (!adminUser || adminUser.role !== 'admin') throw new Error('无权限');
  if (!emoji || typeof emoji !== 'string' || emoji.length > 8) {
    throw new Error('无效的 Emoji');
  }

  const info = storage.read('serverInfo');
  if (info.logoType === 'image' && info.logo) {
    multipart.deleteUploadedFile(info.logo);
  }

  info.logoType = 'emoji';
  info.logoEmoji = emoji;
  info.logo = '';
  info.updatedAt = Date.now();
  info.updatedBy = adminUser.id;

  await storage.write('serverInfo', info);

  return { ok: true, logoEmoji: emoji, logoType: 'emoji' };
}

module.exports = {
  getServerInfo,
  updateServerInfo,
  uploadLogo,
  setLogoEmoji
};
