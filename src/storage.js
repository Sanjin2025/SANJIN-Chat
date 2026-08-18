// ==================== 数据存储模块 ====================
// 负责所有 JSON 数据文件的读写操作，提供统一的数据持久化接口

const fs = require('fs');
const path = require('path');

// 数据目录
const DATA_DIR = path.join(__dirname, '..', 'data');

// 存储文件名映射
const FILES = {
  users: 'users.json',
  friends: 'friends.json',
  friendRequests: 'friendRequests.json',
  groups: 'groups.json',
  groupMembers: 'groupMembers.json',
  messages: 'messages.json',
  qrSessions: 'qrSessions.json',
  pairCodes: 'pairCodes.json',
  announcements: 'announcements.json',
  serverInfo: 'serverInfo.json'
};

// 内存缓存，避免频繁读写文件
const cache = {};
// 文件锁，防止并发写入冲突
const writeLocks = {};

/**
 * 确保数据目录存在
 */
function ensureDataDir() {
  if (!fs.existsSync(DATA_DIR)) {
    fs.mkdirSync(DATA_DIR, { recursive: true });
  }
}

/**
 * 获取数据文件的完整路径
 * @param {string} key 文件键名
 * @returns {string} 文件路径
 */
function getFilePath(key) {
  return path.join(DATA_DIR, FILES[key]);
}

/**
 * 获取默认数据（文件不存在时使用）
 * @param {string} key 文件键名
 * @returns {any} 默认数据
 */
function getDefaultData(key) {
  const defaults = {
    users: [],
    friends: [],
    friendRequests: [],
    groups: [],
    groupMembers: [],
    messages: [],
    qrSessions: [],
    pairCodes: [],
    announcements: [],
    serverInfo: {
      serverName: 'SanjinChat',
      serverDescription: '欢迎来到 SanjinChat 聊天室',
      serverAnnouncement: '',
      copyright: '© 2026 SanjinChat',
      logo: '',
      logoType: 'emoji',
      logoEmoji: '💬',
      updatedAt: Date.now(),
      updatedBy: null
    }
  };
  return defaults[key] || null;
}

/**
 * 读取数据文件
 * @param {string} key 文件键名
 * @returns {any} 解析后的数据
 */
function read(key) {
  // 如果缓存中有数据，直接返回缓存副本
  if (cache[key] !== undefined) {
    return JSON.parse(JSON.stringify(cache[key]));
  }

  ensureDataDir();
  const filePath = getFilePath(key);

  // 文件不存在则返回默认值
  if (!fs.existsSync(filePath)) {
    const defaultData = getDefaultData(key);
    cache[key] = JSON.parse(JSON.stringify(defaultData));
    return defaultData;
  }

  try {
    const content = fs.readFileSync(filePath, 'utf-8');
    const data = content ? JSON.parse(content) : getDefaultData(key);
    cache[key] = JSON.parse(JSON.stringify(data));
    return data;
  } catch (err) {
    console.error(`[存储] 读取 ${key} 失败:`, err.message);
    const defaultData = getDefaultData(key);
    cache[key] = JSON.parse(JSON.stringify(defaultData));
    return defaultData;
  }
}

/**
 * 写入数据文件（带锁机制）
 * @param {string} key 文件键名
 * @param {any} data 要写入的数据
 * @returns {Promise<void>}
 */
async function write(key, data) {
  // 更新缓存
  cache[key] = JSON.parse(JSON.stringify(data));

  // 如果有写入锁，加入等待队列
  if (writeLocks[key]) {
    await writeLocks[key];
  }

  // 创建新的写入锁
  let resolveLock;
  writeLocks[key] = new Promise(resolve => {
    resolveLock = resolve;
  });

  try {
    ensureDataDir();
    const filePath = getFilePath(key);
    const content = JSON.stringify(data, null, 2);
    fs.writeFileSync(filePath, content, 'utf-8');
  } catch (err) {
    console.error(`[存储] 写入 ${key} 失败:`, err.message);
    throw err;
  } finally {
    // 释放锁
    resolveLock();
    delete writeLocks[key];
  }
}

/**
 * 同步写入数据（用于启动时初始化等场景）
 * @param {string} key 文件键名
 * @param {any} data 要写入的数据
 */
function writeSync(key, data) {
  cache[key] = JSON.parse(JSON.stringify(data));
  ensureDataDir();
  const filePath = getFilePath(key);
  fs.writeFileSync(filePath, JSON.stringify(data, null, 2), 'utf-8');
}

/**
 * 更新数据：读取 -> 修改函数 -> 写入
 * @param {string} key 文件键名
 * @param {Function} updater 修改函数，接收当前数据，返回修改后的数据
 * @returns {Promise<any>} 修改后的数据
 */
async function update(key, updater) {
  const current = read(key);
  const updated = updater(current);
  await write(key, updated);
  return updated;
}

/**
 * 生成唯一 ID（基于时间戳+随机数）
 * @returns {string} 唯一ID
 */
function generateId() {
  return Date.now().toString(36) + Math.random().toString(36).substring(2, 8);
}

/**
 * 生成 6 位数字码（用于群号、配对码等）
 * @returns {string} 6位数字码
 */
function generateSixDigitCode() {
  return Math.floor(100000 + Math.random() * 900000).toString();
}

/**
 * 清除指定键的缓存
 * @param {string} key 文件键名
 */
function clearCache(key) {
  if (key) {
    delete cache[key];
  } else {
    Object.keys(cache).forEach(k => delete cache[k]);
  }
}

// 导出模块
module.exports = {
  read,
  write,
  writeSync,
  update,
  generateId,
  generateSixDigitCode,
  clearCache,
  DATA_DIR,
  FILES
};
