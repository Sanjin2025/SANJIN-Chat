// ==================== 认证与用户模块 ====================
// 处理用户注册、登录、token 验证、头像、昵称修改、账号注销等

const bcrypt = require('bcryptjs');
const storage = require('./storage');
const multipart = require('./multipart');

// token 存储：token -> { userId, createdAt }
const tokenStore = new Map();

// Emoji 头像白名单
const EMOJI_AVATARS = ['🐱', '🐶', '🐰', '🦊', '🐼', '🐨', '🐯', '🦁', '🐮', '🐷', '🐸', '🐵'];

/**
 * 生成随机 Token
 */
function generateToken() {
  return 'tk_' + storage.generateId() + storage.generateId();
}

/**
 * 注册新用户
 * @param {string} username 用户名
 * @param {string} password 密码
 * @param {string} [nickname] 昵称（可选，默认用用户名）
 */
async function register(username, password, nickname) {
  if (!username || !password) throw new Error('用户名和密码不能为空');
  if (username.length < 3) throw new Error('用户名至少3位');
  if (password.length < 6) throw new Error('密码至少6位');

  const users = storage.read('users');

  // 检查用户名是否存在
  if (users.some(u => u.username === username)) {
    throw new Error('用户名已被注册');
  }

  // 密码哈希
  const passwordHash = bcrypt.hashSync(password, 10);

  // 第一个用户设为 admin
  const role = users.length === 0 ? 'admin' : 'user';

  const user = {
    id: storage.generateId(),
    username,
    nickname: nickname || username,
    avatar: '',
    avatarType: 'emoji',
    avatarEmoji: EMOJI_AVATARS[Math.floor(Math.random() * EMOJI_AVATARS.length)],
    passwordHash,
    role,
    createdAt: Date.now()
  };

  users.push(user);
  await storage.write('users', users);

  return sanitizeUser(user);
}

/**
 * 用户登录
 * @param {string} username
 * @param {string} password
 * @returns {{token: string, user: Object}}
 */
async function login(username, password) {
  if (!username || !password) throw new Error('用户名和密码不能为空');

  const users = storage.read('users');
  const user = users.find(u => u.username === username);

  if (!user) throw new Error('用户名或密码错误');
  if (!bcrypt.compareSync(password, user.passwordHash)) {
    throw new Error('用户名或密码错误');
  }

  // 生成 token
  const token = generateToken();
  tokenStore.set(token, { userId: user.id, createdAt: Date.now() });

  return { token, user: sanitizeUser(user) };
}

/**
 * 验证 token，返回用户信息
 * @param {string} token
 * @returns {Object|null} 用户对象（不带密码）或 null
 */
function verifyToken(token) {
  if (!token) return null;
  const record = tokenStore.get(token);
  if (!record) return null;

  const users = storage.read('users');
  const user = users.find(u => u.id === record.userId);
  if (!user) {
    tokenStore.delete(token);
    return null;
  }
  return sanitizeUser(user);
}

/**
 * 从 HTTP 请求头中提取 token 并验证
 * @param {IncomingMessage} req
 * @returns {Object|null}
 */
function verifyRequest(req) {
  const authHeader = req.headers['authorization'] || '';
  const token = authHeader.startsWith('Bearer ') ? authHeader.slice(7) : null;
  return verifyToken(token);
}

/**
 * 根据 ID 查找用户
 * @param {string} id
 * @returns {Object|null}
 */
function getUserById(id) {
  const users = storage.read('users');
  const user = users.find(u => u.id === id);
  return user ? sanitizeUser(user) : null;
}

/**
 * 搜索用户（根据用户名或昵称模糊匹配）
 * @param {string} keyword
 * @param {string} excludeId 排除的用户ID
 * @returns {Array<Object>}
 */
function searchUsers(keyword, excludeId) {
  if (!keyword) return [];
  const kw = keyword.toLowerCase();
  const users = storage.read('users');
  return users
    .filter(u => u.id !== excludeId)
    .filter(u =>
      u.username.toLowerCase().includes(kw) ||
      u.nickname.toLowerCase().includes(kw)
    )
    .map(sanitizeUser)
    .slice(0, 50);
}

/**
 * 修改昵称
 * @param {string} userId
 * @param {string} nickname
 */
async function updateNickname(userId, nickname) {
  if (!nickname || nickname.trim().length < 1) throw new Error('昵称不能为空');
  if (nickname.length > 20) throw new Error('昵称最长20个字符');

  const users = storage.read('users');
  const idx = users.findIndex(u => u.id === userId);
  if (idx === -1) throw new Error('用户不存在');

  users[idx].nickname = nickname.trim();
  await storage.write('users', users);

  return sanitizeUser(users[idx]);
}

/**
 * 设置 Emoji 头像
 * @param {string} userId
 * @param {string} emoji
 */
async function setAvatarEmoji(userId, emoji) {
  if (!EMOJI_AVATARS.includes(emoji)) throw new Error('无效的 Emoji 头像');

  const users = storage.read('users');
  const idx = users.findIndex(u => u.id === userId);
  if (idx === -1) throw new Error('用户不存在');

  // 如果之前是图片头像，删除旧图片
  if (users[idx].avatarType === 'image' && users[idx].avatar) {
    multipart.deleteUploadedFile(users[idx].avatar);
  }

  users[idx].avatarType = 'emoji';
  users[idx].avatarEmoji = emoji;
  users[idx].avatar = '';

  await storage.write('users', users);

  return sanitizeUser(users[idx]);
}

/**
 * 上传图片头像
 * @param {string} userId
 * @param {Object} file 上传文件对象
 * @returns {Promise<{url: string}>}
 */
async function uploadAvatarImage(userId, file) {
  // 保存文件
  const saved = await multipart.saveUploadedFile(
    file,
    'avatars',
    2 * 1024 * 1024, // 2MB
    ['image/jpeg', 'image/png', 'image/gif', 'image/webp', 'image/bmp']
  );

  const users = storage.read('users');
  const idx = users.findIndex(u => u.id === userId);
  if (idx === -1) {
    multipart.deleteUploadedFile(saved.url);
    throw new Error('用户不存在');
  }

  // 删除旧头像
  if (users[idx].avatarType === 'image' && users[idx].avatar) {
    multipart.deleteUploadedFile(users[idx].avatar);
  }

  users[idx].avatarType = 'image';
  users[idx].avatar = saved.url;
  users[idx].avatarEmoji = '';

  await storage.write('users', users);

  return { url: saved.url };
}

/**
 * 注销账号（永久删除）
 * @param {string} userId
 * @param {string} password 验证密码
 * @returns {Promise<{deletedUserId: string, friendsIds: string[], groupIds: string[]}>}
 */
async function deleteAccount(userId, password) {
  const users = storage.read('users');
  const idx = users.findIndex(u => u.id === userId);
  if (idx === -1) throw new Error('用户不存在');
  if (!bcrypt.compareSync(password, users[idx].passwordHash)) {
    throw new Error('密码错误');
  }

  const deletedUserId = userId;
  const userAvatar = users[idx].avatar;
  const userAvatarType = users[idx].avatarType;

  // ====== 收集要通知的好友和群成员 ======
  const friends = storage.read('friends');
  const friendsIds = friends
    .filter(f => f.userIdA === userId || f.userIdB === userId)
    .map(f => f.userIdA === userId ? f.userIdB : f.userIdA);

  const groupMembers = storage.read('groupMembers');
  const groups = storage.read('groups');

  // 用户加入的群
  const userGroupIds = groupMembers.filter(gm => gm.userId === userId).map(gm => gm.groupId);
  // 这些群的成员
  const groupNotifyUserIds = new Set();
  groupMembers
    .filter(gm => userGroupIds.includes(gm.groupId))
    .forEach(gm => groupNotifyUserIds.add(gm.userId));

  // 群主是该用户的群（需要解散）
  const ownedGroupIds = groups.filter(g => g.ownerId === userId).map(g => g.id);

  // ====== 删除用户头像 ======
  if (userAvatarType === 'image' && userAvatar) {
    multipart.deleteUploadedFile(userAvatar);
  }

  // ====== 删除群聊：群主的群全部解散 ======
  const remainingGroups = groups.filter(g => g.ownerId !== userId);
  const remainingGroupMembers = groupMembers.filter(gm => {
    // 如果群被解散（群主删除），删除所有成员
    if (ownedGroupIds.includes(gm.groupId)) return false;
    // 该用户的成员关系删除
    if (gm.userId === userId) return false;
    return true;
  });

  // 如果用户是管理员，移除管理员身份
  remainingGroupMembers.forEach(gm => {
    if (gm.userId === userId) gm.role = 'member';
  });

  await storage.write('groups', remainingGroups);
  await storage.write('groupMembers', remainingGroupMembers);

  // ====== 删除好友关系 ======
  const remainingFriends = friends.filter(
    f => f.userIdA !== userId && f.userIdB !== userId
  );
  await storage.write('friends', remainingFriends);

  // ====== 删除好友申请 ======
  const friendRequests = storage.read('friendRequests');
  const remainingRequests = friendRequests.filter(
    r => r.fromId !== userId && r.toId !== userId
  );
  await storage.write('friendRequests', remainingRequests);

  // ====== 删除消息 ======
  const messages = storage.read('messages');
  // 删除该用户的头像文件引用...这里只删除消息记录
  const remainingMessages = messages.filter(m => {
    // 私聊：发送或接收方是该用户
    if (m.toId && (m.fromId === userId || m.toId === userId)) return false;
    // 群聊：发送方是该用户，或群被解散
    if (m.groupId && (m.fromId === userId || ownedGroupIds.includes(m.groupId))) return false;
    return true;
  });
  await storage.write('messages', remainingMessages);

  // ====== 删除配对码和二维码会话 ======
  const pairCodes = storage.read('pairCodes');
  await storage.write('pairCodes', pairCodes.filter(p => p.userId !== userId));

  const qrSessions = storage.read('qrSessions');
  await storage.write('qrSessions', qrSessions.filter(q => q.userId !== userId));

  // ====== 删除公告作者信息转移 ======
  const announcements = storage.read('announcements');
  announcements.forEach(a => {
    if (a.authorId === userId) {
      a.authorId = null;
      a.authorNickname = '(已注销)';
    }
  });
  await storage.write('announcements', announcements);

  // ====== 最后删除用户 ======
  users.splice(idx, 1);
  await storage.write('users', users);

  // 清除该用户的所有 token
  for (const [t, rec] of tokenStore) {
    if (rec.userId === userId) tokenStore.delete(t);
  }

  return {
    deletedUserId,
    friendsIds: Array.from(new Set(friendsIds)),
    groupIds: remainingGroupMembers
      .filter(gm => userGroupIds.includes(gm.groupId) && !ownedGroupIds.includes(gm.groupId))
      .map(gm => gm.groupId),
    ownedGroupIds
  };
}

/**
 * 清除用户的敏感字段（密码哈希），用于对外返回
 */
function sanitizeUser(user) {
  if (!user) return null;
  return {
    id: user.id,
    username: user.username,
    nickname: user.nickname,
    avatar: user.avatar,
    avatarType: user.avatarType,
    avatarEmoji: user.avatarEmoji,
    role: user.role,
    createdAt: user.createdAt
  };
}

/**
 * 判断 token 是否属于某个用户
 */
function isTokenUser(token, userId) {
  const rec = tokenStore.get(token);
  return rec && rec.userId === userId;
}

module.exports = {
  EMOJI_AVATARS,
  register,
  login,
  verifyToken,
  verifyRequest,
  getUserById,
  searchUsers,
  updateNickname,
  setAvatarEmoji,
  uploadAvatarImage,
  deleteAccount,
  sanitizeUser,
  isTokenUser,
  tokenStore
};
