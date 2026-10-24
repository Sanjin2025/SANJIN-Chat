// ==================== 客户端更新模块 ====================
// 提供 /api/update/check 接口，从 update/ 目录读取 version.json
// update/ 目录结构：
//   update/version.json          —— stable 通道
//   update/sanjinchat-wear.apk   —— stable 通道 APK
//   update/beta/version.json     —— beta 通道
//   update/beta/sanjinchat-wear.apk
//
// version.json 格式：
// {
//   "versionCode": 20261024,        // 整型，与客户端 versionCode 比较
//   "versionName": "20261024",      // 显示用版本号
//   "apkUrl": "sanjinchat-wear.apk",// 相对路径（相对于通道目录）或完整 http(s) URL
//   "changelog": "更新内容...",      // 更新说明
//   "channel": "stable",            // 通道名
//   "minSdk": 26,                   // 最低支持 SDK
//   "forceUpdate": false            // 是否强制更新
// }

const fs = require('fs');
const path = require('path');

const UPDATE_DIR = path.join(__dirname, '..', 'update');

function ensureUpdateDir() {
  if (!fs.existsSync(UPDATE_DIR)) {
    fs.mkdirSync(UPDATE_DIR, { recursive: true });
  }
}

/**
 * 读取指定通道的版本信息
 * @param {string} channel stable / beta / 其他子目录名
 * @returns {object|null} 版本信息对象，或 null（文件不存在/解析失败）
 */
function getUpdateInfo(channel) {
  ensureUpdateDir();

  const ch = (channel || 'stable').trim();
  // 自定义通道：完整 URL，由客户端自己拉取，服务端只返回占位
  if (ch.startsWith('http://') || ch.startsWith('https://')) {
    return { customUrl: ch, channel: 'custom' };
  }

  const channelDir = ch === 'stable' ? UPDATE_DIR : path.join(UPDATE_DIR, ch);
  const versionFile = path.join(channelDir, 'version.json');

  if (!fs.existsSync(versionFile)) {
    return null;
  }

  try {
    const raw = fs.readFileSync(versionFile, 'utf8');
    const data = JSON.parse(raw);

    // apkUrl 处理：如果是相对路径，拼成 /update/<channel>/<apkUrl>
    let apkUrl = data.apkUrl || '';
    if (apkUrl && !apkUrl.startsWith('http://') && !apkUrl.startsWith('https://')) {
      const prefix = ch === 'stable' ? '/update' : `/update/${encodeURIComponent(ch)}`;
      apkUrl = `${prefix}/${apkUrl}`;
    }

    return {
      versionCode: Number(data.versionCode) || 0,
      versionName: String(data.versionName || data.versionCode || ''),
      apkUrl: apkUrl,
      changelog: String(data.changelog || ''),
      channel: ch,
      minSdk: Number(data.minSdk) || 0,
      forceUpdate: !!data.forceUpdate,
      releasedAt: Number(data.releasedAt) || Date.now()
    };
  } catch (e) {
    console.error('[update] 读取 version.json 失败:', e.message);
    return null;
  }
}

module.exports = { getUpdateInfo, UPDATE_DIR };
