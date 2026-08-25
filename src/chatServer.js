// ==================== WebSocket 聊天服务器模块 ====================
// 处理所有 WebSocket 连接：在线状态、好友、群聊、消息广播等

const storage = require('./storage');
const auth = require('./auth');

/**
 * 聊天服务器类
 */
class ChatServer {
  constructor() {
    /** @type {Map<string, Set<WebSocket>>} userId -> 连接集合（多设备） */
    this.userSockets = new Map();
    /** @type {Map<WebSocket, {userId: string, user: Object}>} */
    this.socketMeta = new Map();
    /** 回调：当需要通知 HTTP 层（如公告推送） */
    this.announcementCallback = null;
    this.pairCallback = null;
  }

  /**
   * 处理新的 WebSocket 连接
   * @param {WebSocket} ws
   */
  handleConnection(ws, url) {
    // 整个连接流程外包 try/catch，避免任何同步异常导致进程崩溃
    try {
      // URL 中携带 token：ws://host?token=xxx
      let token = null;
      try {
        const queryIdx = url.indexOf('?');
        if (queryIdx !== -1) {
          const query = url.slice(queryIdx + 1);
          const params = new URLSearchParams(query);
          token = params.get('token');
        }
      } catch (e) { /* ignore */ }

      if (!token) {
        this._sendError(ws, '缺少 token 参数');
        ws.close();
        return;
      }

      const user = auth.verifyToken(token);
      if (!user) {
        this._sendError(ws, 'Token 无效或已过期');
        ws.close();
        return;
      }

      // 注册连接
      this._registerSocket(ws, user);

      // 欢迎消息
      this._send(ws, { type: 'connected', user, timestamp: Date.now() });

      // 立即推送 presence 和在线好友列表
      this._sendPresenceToUser(user.id);

      // 发送给该用户已有的所有好友和群通知（以便刷新）
      this._sendFriendsUpdate(user.id);
      this._sendGroupsUpdate(user.id);
      this._sendFriendRequests(user.id);

      ws.on('message', (data) => this._handleMessage(ws, data));
      ws.on('close', () => this._handleClose(ws));
      ws.on('error', (e) => {
        console.error('[Chat] socket error:', e.message);
        this._handleClose(ws);
      });
    } catch (err) {
      // 任何未预期的同步异常只打印，不抛出
      console.error('[Chat] handleConnection 出错:', err && err.message || err);
      try { this._sendError(ws, '服务器内部错误'); } catch (_) { /* ignore */ }
      try { ws.close(); } catch (_) { /* ignore */ }
    }
  }

  // ==================== 注册/注销 ====================

  _registerSocket(ws, user) {
    if (!this.userSockets.has(user.id)) {
      this.userSockets.set(user.id, new Set());
    }
    this.userSockets.get(user.id).add(ws);
    this.socketMeta.set(ws, { userId: user.id, user });

    // 如果从 0 -> 1 个连接，说明刚上线，通知好友
    if (this.userSockets.get(user.id).size === 1) {
      this._broadcastPresence(user.id, 'online');
    }
  }

  _handleClose(ws) {
    try {
      const meta = this.socketMeta.get(ws);
      if (!meta) return;
      const { userId } = meta;

      const set = this.userSockets.get(userId);
      if (set) {
        set.delete(ws);
        if (set.size === 0) {
          this.userSockets.delete(userId);
          this._broadcastPresence(userId, 'offline');
        }
      }
      this.socketMeta.delete(ws);
    } catch (err) {
      // 任何未预期的异常只打印，不向上抛（ws.on 的回调抛出会导致进程崩溃）
      console.error('[Chat] _handleClose 出错:', err && err.message || err);
    }
  }

  /**
   * 判断某用户是否在线
   */
  isUserOnline(userId) {
    const set = this.userSockets.get(userId);
    return set && set.size > 0;
  }

  /**
   * 获取指定用户的所有 socket
   */
  _getUserSockets(userId) {
    return this.userSockets.get(userId) || new Set();
  }

  /**
   * 向指定用户的所有设备推送消息
   */
  _sendToUser(userId, msg) {
    const sockets = this._getUserSockets(userId);
    sockets.forEach(ws => {
      try { this._send(ws, msg); } catch (e) { /* ignore */ }
    });
  }

  /**
   * 向所有在线用户广播
   */
  _broadcastAll(msg) {
    for (const sockets of this.userSockets.values()) {
      sockets.forEach(ws => {
        try { this._send(ws, msg); } catch (e) { /* ignore */ }
      });
    }
  }

  // ==================== 发送工具 ====================

  _send(ws, obj) {
    if (!ws || ws.readyState !== 1) return;
    let data;
    try {
      data = JSON.stringify(obj);
    } catch (e) {
      // 序列化失败（如循环引用）只打印，不抛
      console.error('[Chat] _send JSON.stringify 失败:', e.message);
      return;
    }
    try {
      ws.send(data);
    } catch (e) {
      // socket 已关闭/异常时 send 会抛错，吞掉避免崩溃
      console.warn('[Chat] ws.send 失败（已忽略）:', e.message);
    }
  }
  _sendError(ws, message) {
    this._send(ws, { type: 'error', message, timestamp: Date.now() });
  }

  // ==================== 消息处理主入口 ====================

  _handleMessage(ws, rawData) {
    let msg;
    try {
      msg = JSON.parse(rawData.toString());
    } catch (e) {
      this._sendError(ws, '消息格式错误');
      return;
    }

    const meta = this.socketMeta.get(ws);
    if (!meta) {
      this._sendError(ws, '未登录');
      return;
    }
    const user = meta.user;

    const handler = this.MESSAGE_HANDLERS[msg.type];
    if (!handler) {
      this._sendError(ws, `未知消息类型: ${msg.type}`);
      return;
    }

    try {
      const ret = handler.call(this, user, msg, ws);
      // handler 多为 async 函数，返回 Promise；同步 try/catch 抓不到 await 抛的错，
      // 必须用 .catch() 兜底，否则会变成 unhandledRejection 导致进程崩溃。
      if (ret && typeof ret.then === 'function') {
        ret.catch(err => {
          console.error('[Chat] 异步消息处理出错:', msg.type, err && err.message || err);
          try { this._sendError(ws, err && err.message || '处理失败'); } catch (_) { /* ignore */ }
        });
      }
    } catch (err) {
      console.error('[Chat] 处理消息出错:', msg.type, err && err.message || err);
      try { this._sendError(ws, err && err.message || '处理失败'); } catch (_) { /* ignore */ }
    }
  }

  // ==================== 在线状态查询 ====================

  /**
   * 客户端主动查询指定用户列表的在线状态
   */
  async _handleQueryOnline(user, msg) {
    const ids = Array.isArray(msg.userIds) ? msg.userIds : [];
    const statuses = {};
    ids.forEach(id => {
      statuses[id] = this.isUserOnline(id) ? 'online' : 'offline';
    });
    this._sendToUser(user.id, { type: 'presence', statuses, timestamp: Date.now() });
  }

  /**
   * 向用户发送其所有好友的当前在线状态
   */
  _sendPresenceToUser(userId) {
    const friends = storage.read('friends');
    const friendIds = friends
      .filter(f => f.userIdA === userId || f.userIdB === userId)
      .map(f => f.userIdA === userId ? f.userIdB : f.userIdA);
    const statuses = {};
    friendIds.forEach(id => {
      statuses[id] = this.isUserOnline(id) ? 'online' : 'offline';
    });
    this._sendToUser(userId, { type: 'presence', statuses, timestamp: Date.now() });
  }

  /**
   * 某用户上下线时，向其所有好友广播 presence
   */
  _broadcastPresence(userId, status) {
    const friends = storage.read('friends');
    const friendIds = friends
      .filter(f => f.userIdA === userId || f.userIdB === userId)
      .map(f => f.userIdA === userId ? f.userIdB : f.userIdA);
    const statuses = { [userId]: status };
    friendIds.forEach(fid => {
      this._sendToUser(fid, { type: 'presence', statuses, timestamp: Date.now() });
    });

    // 同时发送给该用户所在的群的成员（可选，但好友优先）
  }

  // ==================== 好友系统 ====================

  /** 发送好友申请 */
  async _handleFriendRequestSend(user, msg) {
    const { toId, message } = msg;
    if (!toId || toId === user.id) throw new Error('无效的目标用户');
    if (!auth.getUserById(toId)) throw new Error('目标用户不存在');

    const friends = storage.read('friends');
    const alreadyFriends = friends.some(
      f => (f.userIdA === user.id && f.userIdB === toId) ||
           (f.userIdA === toId && f.userIdB === user.id)
    );
    if (alreadyFriends) throw new Error('对方已经是你的好友');

    const requests = storage.read('friendRequests');
    const existsPending = requests.some(
      r => r.fromId === user.id && r.toId === toId && r.status === 'pending'
    );
    if (existsPending) throw new Error('已发送过好友申请，请等待对方处理');

    // 检查反向申请
    const reversePending = requests.find(
      r => r.fromId === toId && r.toId === user.id && r.status === 'pending'
    );
    if (reversePending) {
      // 双方互相发了申请，直接接受
      return this._acceptFriendRequestInternal(user, reversePending.id);
    }

    const req = {
      id: storage.generateId(),
      fromId: user.id,
      toId,
      message: String(message || '').slice(0, 100),
      status: 'pending',
      createdAt: Date.now()
    };
    requests.push(req);
    await storage.write('friendRequests', requests);

    // 通知发送方
    this._sendToUser(user.id, {
      type: 'friend_request:sent',
      request: req,
      timestamp: Date.now()
    });
    // 通知接收方
    this._sendToUser(toId, {
      type: 'friend_request:new',
      request: { ...req, fromUser: auth.getUserById(user.id) },
      timestamp: Date.now()
    });
    // 刷新双方申请列表
    this._sendFriendRequests(user.id);
    this._sendFriendRequests(toId);
  }

  /** 接受好友申请 */
  async _handleFriendRequestAccept(user, msg) {
    if (!msg.requestId) throw new Error('缺少 requestId');
    await this._acceptFriendRequestInternal(user, msg.requestId);
  }

  async _acceptFriendRequestInternal(user, requestId) {
    const requests = storage.read('friendRequests');
    const idx = requests.findIndex(r => r.id === requestId);
    if (idx === -1) throw new Error('申请不存在');
    const req = requests[idx];
    if (req.toId !== user.id) throw new Error('无权处理该申请');
    if (req.status !== 'pending') throw new Error('申请已被处理');

    req.status = 'accepted';
    await storage.write('friendRequests', requests);

    // 添加到好友列表
    const friends = storage.read('friends');
    friends.push({
      userIdA: req.fromId,
      userIdB: req.toId,
      since: Date.now()
    });
    await storage.write('friends', friends);

    // 通知双方
    const fromUser = auth.getUserById(req.fromId);
    const toUser = auth.getUserById(req.toId);
    this._sendToUser(req.fromId, {
      type: 'friend_request:accepted',
      request: req,
      friend: toUser,
      timestamp: Date.now()
    });
    this._sendToUser(req.toId, {
      type: 'friend_request:accepted',
      request: req,
      friend: fromUser,
      timestamp: Date.now()
    });
    this._sendFriendsUpdate(req.fromId);
    this._sendFriendsUpdate(req.toId);
    this._sendFriendRequests(req.fromId);
    this._sendFriendRequests(req.toId);
  }

  /** 拒绝好友申请 */
  async _handleFriendRequestReject(user, msg) {
    if (!msg.requestId) throw new Error('缺少 requestId');
    const requests = storage.read('friendRequests');
    const idx = requests.findIndex(r => r.id === msg.requestId);
    if (idx === -1) throw new Error('申请不存在');
    const req = requests[idx];
    if (req.toId !== user.id) throw new Error('无权处理该申请');
    if (req.status !== 'pending') throw new Error('申请已被处理');

    req.status = 'rejected';
    await storage.write('friendRequests', requests);

    this._sendToUser(req.fromId, {
      type: 'friend_request:rejected',
      request: req,
      timestamp: Date.now()
    });
    this._sendToUser(req.toId, {
      type: 'friend_request:rejected',
      request: req,
      timestamp: Date.now()
    });
    this._sendFriendRequests(req.fromId);
    this._sendFriendRequests(req.toId);
  }

  /** 删除好友 */
  async _handleFriendRemove(user, msg) {
    const friendId = msg.friendId || msg.userId;
    if (!friendId) throw new Error('缺少 friendId');

    const friends = storage.read('friends');
    const lenBefore = friends.length;
    const remaining = friends.filter(f => {
      return !((f.userIdA === user.id && f.userIdB === friendId) ||
               (f.userIdA === friendId && f.userIdB === user.id));
    });
    if (remaining.length === lenBefore) throw new Error('对方不是你的好友');

    await storage.write('friends', remaining);

    this._sendToUser(user.id, {
      type: 'friend:removed',
      friendId,
      timestamp: Date.now()
    });
    this._sendToUser(friendId, {
      type: 'friend:removed',
      friendId: user.id,
      timestamp: Date.now()
    });
    this._sendFriendsUpdate(user.id);
    this._sendFriendsUpdate(friendId);
  }

  _sendFriendsUpdate(userId) {
    const friends = storage.read('friends');
    const myFriends = friends
      .filter(f => f.userIdA === userId || f.userIdB === userId)
      .map(f => {
        const fid = f.userIdA === userId ? f.userIdB : f.userIdA;
        const fuser = auth.getUserById(fid);
        return {
          userId: fid,
          user: fuser,
          since: f.since,
          online: this.isUserOnline(fid)
        };
      })
      .filter(x => x.user); // 防止已删除用户

    this._sendToUser(userId, { type: 'friends:update', friends: myFriends, timestamp: Date.now() });
  }

  _sendFriendRequests(userId) {
    const requests = storage.read('friendRequests');
    const mine = requests
      .filter(r => r.fromId === userId || r.toId === userId)
      .sort((a, b) => b.createdAt - a.createdAt)
      .map(r => ({
        ...r,
        fromUser: auth.getUserById(r.fromId),
        toUser: auth.getUserById(r.toId)
      }));
    this._sendToUser(userId, { type: 'friend_requests:update', requests: mine, timestamp: Date.now() });
  }

  // ==================== 群聊系统 ====================

  /** 创建群 */
  async _handleGroupCreate(user, msg) {
    const name = String(msg.name || '').trim();
    if (!name) throw new Error('群名不能为空');
    if (name.length > 30) throw new Error('群名最长30字符');

    const groups = storage.read('groups');
    let groupNumber;
    let tries = 0;
    do {
      groupNumber = storage.generateSixDigitCode();
      tries++;
    } while (groups.some(g => g.groupNumber === groupNumber) && tries < 50);

    const bcrypt = require('bcryptjs');
    const group = {
      id: storage.generateId(),
      groupNumber,
      name,
      description: String(msg.description || '').slice(0, 100),
      ownerId: user.id,
      passwordHash: msg.password ? bcrypt.hashSync(String(msg.password), 10) : '',
      createdAt: Date.now()
    };
    groups.push(group);
    await storage.write('groups', groups);

    // 加入群主为管理员
    const members = storage.read('groupMembers');
    members.push({
      groupId: group.id,
      userId: user.id,
      role: 'owner',
      mutedUntil: 0,
      joinedAt: Date.now()
    });
    await storage.write('groupMembers', members);

    this._sendToUser(user.id, {
      type: 'group:created',
      group,
      role: 'owner',
      timestamp: Date.now()
    });
    this._sendGroupsUpdate(user.id);
  }

  /** 通过群号+密码加入群 */
  async _handleGroupJoin(user, msg) {
    const groupNumber = String(msg.groupNumber || '');
    const password = msg.password ? String(msg.password) : '';
    if (!/^\d{6}$/.test(groupNumber)) throw new Error('群号格式错误');

    const groups = storage.read('groups');
    const group = groups.find(g => g.groupNumber === groupNumber);
    if (!group) throw new Error('群不存在');

    // 验证密码
    const bcrypt = require('bcryptjs');
    if (group.passwordHash) {
      if (!password) throw new Error('该群需要加群密码');
      if (!bcrypt.compareSync(password, group.passwordHash)) {
        throw new Error('加群密码错误');
      }
    }

    const members = storage.read('groupMembers');
    const exists = members.find(m => m.groupId === group.id && m.userId === user.id);
    if (exists) throw new Error('你已在该群中');

    members.push({
      groupId: group.id,
      userId: user.id,
      role: 'member',
      mutedUntil: 0,
      joinedAt: Date.now()
    });
    await storage.write('groupMembers', members);

    this._sendToUser(user.id, {
      type: 'group:joined',
      group,
      role: 'member',
      timestamp: Date.now()
    });

    // 通知群内其他成员
    this._broadcastToGroupMembers(group.id, {
      type: 'group:member_joined',
      groupId: group.id,
      member: auth.getUserById(user.id),
      timestamp: Date.now()
    }, user.id);

    this._sendGroupsUpdate(user.id);
    this._sendGroupMembersUpdate(group.id);
  }

  /** 退出群 */
  async _handleGroupLeave(user, msg) {
    const groupId = msg.groupId;
    if (!groupId) throw new Error('缺少 groupId');
    const groups = storage.read('groups');
    const group = groups.find(g => g.id === groupId);
    if (!group) throw new Error('群不存在');
    if (group.ownerId === user.id) throw new Error('群主不能直接退出，只能解散群');

    const members = storage.read('groupMembers');
    const remaining = members.filter(m => !(m.groupId === groupId && m.userId === user.id));
    if (remaining.length === members.length) throw new Error('你不在该群中');
    await storage.write('groupMembers', remaining);

    this._sendToUser(user.id, {
      type: 'group:left',
      groupId,
      timestamp: Date.now()
    });

    this._broadcastToGroupMembers(groupId, {
      type: 'group:member_left',
      groupId,
      userId: user.id,
      timestamp: Date.now()
    });

    this._sendGroupsUpdate(user.id);
    this._sendGroupMembersUpdate(groupId);
  }

  /** 解散群（仅群主） */
  async _handleGroupDismiss(user, msg) {
    const groupId = msg.groupId;
    if (!groupId) throw new Error('缺少 groupId');
    const groups = storage.read('groups');
    const idx = groups.findIndex(g => g.id === groupId);
    if (idx === -1) throw new Error('群不存在');
    if (groups[idx].ownerId !== user.id) throw new Error('只有群主可以解散群');

    const group = groups[idx];
    const members = storage.read('groupMembers');
    const affectedUserIds = members
      .filter(m => m.groupId === groupId)
      .map(m => m.userId);

    // 删除群
    groups.splice(idx, 1);
    await storage.write('groups', groups);

    // 删除成员关系
    const remainingMembers = members.filter(m => m.groupId !== groupId);
    await storage.write('groupMembers', remainingMembers);

    // 删除群消息
    const messages = storage.read('messages');
    const remainingMessages = messages.filter(m => m.groupId !== groupId);
    await storage.write('messages', remainingMessages);

    // 通知所有成员
    affectedUserIds.forEach(uid => {
      this._sendToUser(uid, {
        type: 'group:dismissed',
        groupId,
        groupName: group.name,
        timestamp: Date.now()
      });
      this._sendGroupsUpdate(uid);
    });
  }

  /** 设置/取消管理员（仅群主） */
  async _handleGroupSetAdmin(user, msg) {
    const { groupId, userId, isAdmin } = msg;
    if (!groupId || !userId) throw new Error('参数不全');

    const groups = storage.read('groups');
    const group = groups.find(g => g.id === groupId);
    if (!group) throw new Error('群不存在');
    if (group.ownerId !== user.id) throw new Error('只有群主可设置管理员');
    if (userId === group.ownerId) throw new Error('群主本身就是管理员');

    const members = storage.read('groupMembers');
    const target = members.find(m => m.groupId === groupId && m.userId === userId);
    if (!target) throw new Error('用户不在该群中');

    target.role = isAdmin ? 'admin' : 'member';
    await storage.write('groupMembers', members);

    const evtType = isAdmin ? 'group:admin_set' : 'group:admin_removed';
    this._broadcastToGroupMembers(groupId, {
      type: evtType,
      groupId,
      userId,
      user: auth.getUserById(userId),
      timestamp: Date.now()
    });
    this._sendGroupMembersUpdate(groupId);
  }

  /** 踢人（群主/管理员） */
  async _handleGroupKick(user, msg) {
    const { groupId, userId } = msg;
    if (!groupId || !userId) throw new Error('参数不全');
    if (userId === user.id) throw new Error('不能踢自己');

    const members = storage.read('groupMembers');
    const me = members.find(m => m.groupId === groupId && m.userId === user.id);
    if (!me) throw new Error('你不在该群中');
    const target = members.find(m => m.groupId === groupId && m.userId === userId);
    if (!target) throw new Error('对方不在该群中');

    const groups = storage.read('groups');
    const group = groups.find(g => g.id === groupId);
    const isOwner = me.role === 'owner';
    const isAdmin = me.role === 'admin';

    if (!isOwner && !isAdmin) throw new Error('无权限踢人');
    // 管理员不能踢群主和管理员
    if (isAdmin && (target.role === 'owner' || target.role === 'admin')) {
      throw new Error('管理员不能踢群主和其他管理员');
    }

    const remaining = members.filter(m => !(m.groupId === groupId && m.userId === userId));
    await storage.write('groupMembers', remaining);

    this._sendToUser(userId, {
      type: 'group:member_kicked',
      groupId,
      groupName: group ? group.name : '',
      operatorId: user.id,
      timestamp: Date.now()
    });
    this._broadcastToGroupMembers(groupId, {
      type: 'group:member_kicked',
      groupId,
      userId,
      user: auth.getUserById(userId),
      operatorId: user.id,
      timestamp: Date.now()
    });
    this._sendGroupsUpdate(userId);
    this._sendGroupMembersUpdate(groupId);
  }

  /** 禁言/解除禁言 */
  async _handleGroupMute(user, msg) {
    const { groupId, userId, mutedUntil } = msg;
    if (!groupId || !userId) throw new Error('参数不全');

    const members = storage.read('groupMembers');
    const me = members.find(m => m.groupId === groupId && m.userId === user.id);
    if (!me) throw new Error('你不在该群中');
    const target = members.find(m => m.groupId === groupId && m.userId === userId);
    if (!target) throw new Error('对方不在该群中');

    const isOwner = me.role === 'owner';
    const isAdmin = me.role === 'admin';
    if (!isOwner && !isAdmin) throw new Error('无权限禁言');
    if (isAdmin && (target.role === 'owner' || target.role === 'admin')) {
      throw new Error('管理员不能禁言群主和其他管理员');
    }

    target.mutedUntil = Number(mutedUntil) || 0;
    await storage.write('groupMembers', members);

    const evtType = target.mutedUntil > Date.now() ? 'group:member_muted' : 'group:member_unmuted';
    this._broadcastToGroupMembers(groupId, {
      type: evtType,
      groupId,
      userId,
      user: auth.getUserById(userId),
      mutedUntil: target.mutedUntil,
      operatorId: user.id,
      timestamp: Date.now()
    });
    this._sendGroupMembersUpdate(groupId);
  }

  async _handleGroupUnmute(user, msg) {
    return this._handleGroupMute(user, { ...msg, mutedUntil: 0 });
  }

  _sendGroupsUpdate(userId) {
    const groups = storage.read('groups');
    const members = storage.read('groupMembers');
    const myMemberships = members.filter(m => m.userId === userId);
    const myGroups = myMemberships.map(m => {
      const g = groups.find(g => g.id === m.groupId);
      if (!g) return null;
      return {
        group: {
          id: g.id,
          groupNumber: g.groupNumber,
          name: g.name,
          description: g.description,
          ownerId: g.ownerId,
          hasPassword: !!g.passwordHash,
          createdAt: g.createdAt
        },
        role: m.role,
        mutedUntil: m.mutedUntil,
        joinedAt: m.joinedAt,
        memberCount: members.filter(mm => mm.groupId === g.id).length
      };
    }).filter(Boolean);

    this._sendToUser(userId, { type: 'groups:update', groups: myGroups, timestamp: Date.now() });
  }

  _sendGroupMembersUpdate(groupId) {
    const members = storage.read('groupMembers');
    const groupMembers = members.filter(m => m.groupId === groupId).map(m => ({
      userId: m.userId,
      role: m.role,
      mutedUntil: m.mutedUntil,
      joinedAt: m.joinedAt,
      user: auth.getUserById(m.userId)
    })).filter(x => x.user);

    this._broadcastToGroupMembers(groupId, {
      type: 'group:members_update',
      groupId,
      members: groupMembers,
      timestamp: Date.now()
    });
  }

  _broadcastToGroupMembers(groupId, msg, excludeUserId = null) {
    const members = storage.read('groupMembers');
    const userIds = members
      .filter(m => m.groupId === groupId)
      .map(m => m.userId);
    userIds.forEach(uid => {
      if (excludeUserId && uid === excludeUserId) return;
      this._sendToUser(uid, msg);
    });
  }

  // ==================== 消息发送 ====================

  /** 私聊消息 */
  async _handlePrivateMessage(user, msg) {
    const toId = msg.toId;
    if (!toId) throw new Error('缺少 toId');

    // 检查是否是好友
    const friends = storage.read('friends');
    const isFriend = friends.some(
      f => (f.userIdA === user.id && f.userIdB === toId) ||
           (f.userIdA === toId && f.userIdB === user.id)
    );
    if (!isFriend) throw new Error('只能与好友私聊');

    const saved = await this._saveMessage({
      type: 'private_message',
      fromId: user.id,
      fromNickname: user.nickname,
      toId,
      contentType: msg.contentType || 'text',
      content: msg.content || '',
      image: msg.image || null,
      audio: msg.audio || null
    });

    this._sendToUser(user.id, { type: 'private_message', message: saved, timestamp: Date.now() });
    this._sendToUser(toId, { type: 'private_message', message: saved, timestamp: Date.now() });
  }

  /** 群聊消息 */
  async _handleGroupMessage(user, msg) {
    const groupId = msg.groupId;
    if (!groupId) throw new Error('缺少 groupId');

    const members = storage.read('groupMembers');
    const me = members.find(m => m.groupId === groupId && m.userId === user.id);
    if (!me) throw new Error('你不在该群中');

    // 检查禁言
    if (me.mutedUntil && me.mutedUntil > Date.now()) {
      throw new Error('你已被禁言，无法发送消息');
    }

    const saved = await this._saveMessage({
      type: 'group_message',
      fromId: user.id,
      fromNickname: user.nickname,
      groupId,
      contentType: msg.contentType || 'text',
      content: msg.content || '',
      image: msg.image || null,
      audio: msg.audio || null
    });

    this._broadcastToGroupMembers(groupId, {
      type: 'group_message',
      message: saved,
      timestamp: Date.now()
    });
  }

  async _saveMessage(data) {
    const messages = storage.read('messages');
    const msg = {
      id: storage.generateId(),
      type: data.type,
      fromId: data.fromId,
      fromNickname: data.fromNickname,
      toId: data.toId || null,
      groupId: data.groupId || null,
      contentType: data.contentType || 'text',
      content: data.content || '',
      image: data.image || null,
      audio: data.audio || null,
      timestamp: Date.now()
    };
    messages.push(msg);
    await storage.write('messages', messages);
    return msg;
  }

  // ==================== 用户变更广播 ====================

  /** 昵称变更：广播给好友和群成员 */
  broadcastUserNicknameUpdated(userId, newNickname) {
    const friends = storage.read('friends');
    const friendsIds = new Set(
      friends
        .filter(f => f.userIdA === userId || f.userIdB === userId)
        .map(f => f.userIdA === userId ? f.userIdB : f.userIdA)
    );

    const members = storage.read('groupMembers');
    const userGroupIds = members.filter(m => m.userId === userId).map(m => m.groupId);
    const groupMembers = members
      .filter(m => userGroupIds.includes(m.groupId))
      .map(m => m.userId);
    groupMembers.forEach(id => friendsIds.add(id));

    const payload = {
      type: 'user:nickname_updated',
      userId,
      nickname: newNickname,
      timestamp: Date.now()
    };
    friendsIds.forEach(uid => this._sendToUser(uid, payload));
    // 发给自己
    this._sendToUser(userId, payload);
  }

  /** 头像变更：广播给好友和群成员 */
  broadcastUserAvatarUpdated(userId, avatar, avatarType, avatarEmoji) {
    const friends = storage.read('friends');
    const friendsIds = new Set(
      friends
        .filter(f => f.userIdA === userId || f.userIdB === userId)
        .map(f => f.userIdA === userId ? f.userIdB : f.userIdA)
    );
    const members = storage.read('groupMembers');
    const userGroupIds = members.filter(m => m.userId === userId).map(m => m.groupId);
    members
      .filter(m => userGroupIds.includes(m.groupId))
      .forEach(m => friendsIds.add(m.userId));

    const payload = {
      type: 'user:avatar_updated',
      userId,
      avatar, avatarType, avatarEmoji,
      timestamp: Date.now()
    };
    friendsIds.forEach(uid => this._sendToUser(uid, payload));
    this._sendToUser(userId, payload);
  }

  /** 账号注销广播 */
  broadcastUserAccountDeleted(deletedUserId, friendsIds, groupIds, ownedGroupIds) {
    const payload = {
      type: 'user:account_deleted',
      userId: deletedUserId,
      timestamp: Date.now()
    };
    friendsIds.forEach(uid => this._sendToUser(uid, payload));

    groupIds.forEach(gid => this._sendGroupsUpdate(gid));

    // 自己所有设备强制下线
    const sockets = this._getUserSockets(deletedUserId);
    sockets.forEach(ws => {
      try { ws.close(); } catch (e) { /* ignore */ }
    });
  }

  /** 公告推送（给所有在线用户） */
  broadcastNewAnnouncement(announcement) {
    this._broadcastAll({
      type: 'new_announcement',
      announcement,
      timestamp: Date.now()
    });
  }

  /** 配对状态更新：推送给配对码用户 */
  broadcastPairStatusUpdate(userId, statusInfo) {
    this._sendToUser(userId, {
      type: 'pair:status_update',
      ...statusInfo,
      timestamp: Date.now()
    });
  }

  // ==================================================================
  // 消息处理器映射表
  // ==================================================================
  get MESSAGE_HANDLERS() {
    return {
      'query_online': this._handleQueryOnline,
      'friend_request:send': this._handleFriendRequestSend,
      'friend_request:accept': this._handleFriendRequestAccept,
      'friend_request:reject': this._handleFriendRequestReject,
      'friend:remove': this._handleFriendRemove,
      'private_message': this._handlePrivateMessage,
      'group_message': this._handleGroupMessage,
      'group:create': this._handleGroupCreate,
      'group:join': this._handleGroupJoin,
      'group:leave': this._handleGroupLeave,
      'group:dismiss': this._handleGroupDismiss,
      'group:set_admin': this._handleGroupSetAdmin,
      'group:kick': this._handleGroupKick,
      'group:mute': this._handleGroupMute,
      'group:unmute': this._handleGroupUnmute
    };
  }
}

module.exports = ChatServer;
