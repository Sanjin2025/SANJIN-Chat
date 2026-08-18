// ==================== 自动清理模块 ====================
// 每天凌晨 3 点删除 data/uploads/ 下超过 10 天的文件，启动时也执行一次

const fs = require('fs');
const path = require('path');
const storage = require('./storage');

// 超过多少天删除
const EXPIRE_DAYS = 10;
const EXPIRE_MS = EXPIRE_DAYS * 24 * 60 * 60 * 1000;

/**
 * 递归遍历目录下所有文件
 */
function walkDir(dir, result = []) {
  if (!fs.existsSync(dir)) return result;
  const entries = fs.readdirSync(dir, { withFileTypes: true });
  for (const entry of entries) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      walkDir(full, result);
    } else if (entry.isFile()) {
      result.push(full);
    }
  }
  return result;
}

/**
 * 执行一次清理
 * @param {boolean} dryRun 是否只打印不删除
 * @returns {Promise<{deleted: number, skipped: number, freedBytes: number}>}
 */
async function runCleanup(dryRun = false) {
  const uploadsDir = path.join(storage.DATA_DIR, 'uploads');
  if (!fs.existsSync(uploadsDir)) {
    return { deleted: 0, skipped: 0, freedBytes: 0 };
  }

  const files = walkDir(uploadsDir);
  const now = Date.now();
  let deleted = 0;
  let skipped = 0;
  let freedBytes = 0;

  for (const file of files) {
    try {
      const stat = fs.statSync(file);
      const age = now - stat.mtimeMs;
      if (age > EXPIRE_MS) {
        freedBytes += stat.size;
        if (!dryRun) {
          try {
            fs.unlinkSync(file);
          } catch (e) {
            console.warn('[清理] 删除文件失败:', file, e.message);
          }
        }
        deleted++;
      } else {
        skipped++;
      }
    } catch (e) {
      console.warn('[清理] 访问文件失败:', file, e.message);
    }
  }

  const logMsg = `[清理] 已删除 ${deleted} 个过期文件（${EXPIRE_DAYS}天以上），跳过 ${skipped} 个，释放 ${(freedBytes / 1024 / 1024).toFixed(2)} MB`;
  console.log(logMsg);

  return { deleted, skipped, freedBytes };
}

/**
 * 调度：每天凌晨 3 点执行
 */
function scheduleDailyCleanup() {
  // 计算距离下一个凌晨 3 点还有多少毫秒
  function getMsToNext3AM() {
    const now = new Date();
    const next = new Date(now);
    next.setHours(3, 0, 0, 0);
    if (next.getTime() <= now.getTime()) {
      next.setDate(next.getDate() + 1);
    }
    return next.getTime() - now.getTime();
  }

  function schedule() {
    const delay = getMsToNext3AM();
    const nextDate = new Date(Date.now() + delay);
    console.log(`[清理] 下次执行时间: ${nextDate.toLocaleString()}`);

    setTimeout(() => {
      runCleanup(false).catch(e => console.error('[清理] 执行失败:', e));
      // 执行完毕继续安排下一次
      setTimeout(schedule, 60 * 1000);
    }, delay).unref();
  }

  schedule();
}

module.exports = {
  runCleanup,
  scheduleDailyCleanup,
  EXPIRE_DAYS,
  EXPIRE_MS
};
