// ==================== Multipart 表单解析模块 ====================
// 解析 multipart/form-data 请求体，支持文件上传和普通字段

const fs = require('fs');
const path = require('path');
const storage = require('./storage');

/**
 * 解析 multipart/form-data 请求体
 * @param {IncomingMessage} req HTTP 请求对象
 * @returns {Promise<{fields: Object, files: Array}>} 解析后的字段和文件
 */
function parseMultipart(req) {
  return new Promise((resolve, reject) => {
    // 从 Content-Type 获取 boundary
    const contentType = req.headers['content-type'] || '';
    const boundaryMatch = contentType.match(/boundary=([^;]+)/);
    if (!boundaryMatch) {
      return reject(new Error('缺少 boundary'));
    }
    const boundary = Buffer.from('--' + boundaryMatch[1]);
    const boundaryEnd = Buffer.from('--' + boundaryMatch[1] + '--');

    const chunks = [];
    req.on('data', chunk => chunks.push(chunk));
    req.on('end', () => {
      try {
        const body = Buffer.concat(chunks);
        const result = parseBody(body, boundary, boundaryEnd);
        resolve(result);
      } catch (err) {
        reject(err);
      }
    });
    req.on('error', reject);
  });
}

/**
 * 解析请求体 Buffer
 * @param {Buffer} body 请求体
 * @param {Buffer} boundary 分隔符
 * @param {Buffer} boundaryEnd 结束分隔符
 * @returns {{fields: Object, files: Array}}
 */
function parseBody(body, boundary, boundaryEnd) {
  const fields = {};
  const files = [];

  // 按 boundary 分割
  let idx = 0;
  while (idx < body.length) {
    // 查找下一个 boundary
    const bIdx = body.indexOf(boundary, idx);
    if (bIdx === -1) break;

    // 跳过 boundary 和换行符 (\r\n)
    let partStart = bIdx + boundary.length;
    if (body[partStart] === 0x0d && body[partStart + 1] === 0x0a) {
      partStart += 2;
    }

    // 查找 part 的结束位置（下一个 boundary）
    const nextBIdx = body.indexOf(boundary, partStart);
    if (nextBIdx === -1) break;

    // 提取 part 内容（去掉末尾的 \r\n）
    let partEnd = nextBIdx;
    if (body[partEnd - 2] === 0x0d && body[partEnd - 1] === 0x0a) {
      partEnd -= 2;
    }
    if (body[partEnd - 1] === 0x0a) {
      partEnd -= 1;
    }

    const part = body.slice(partStart, partEnd);
    parsePart(part, fields, files);

    idx = nextBIdx;

    // 检查是否是结束 boundary
    if (body.slice(nextBIdx, nextBIdx + boundaryEnd.length).equals(boundaryEnd)) {
      break;
    }
  }

  return { fields, files };
}

/**
 * 解析单个 part
 * @param {Buffer} part
 * @param {Object} fields
 * @param {Array} files
 */
function parsePart(part, fields, files) {
  // 查找头部和内容的分隔符 (\r\n\r\n)
  const sep = Buffer.from('\r\n\r\n');
  const sepIdx = part.indexOf(sep);
  if (sepIdx === -1) return;

  const headersPart = part.slice(0, sepIdx).toString('utf-8');
  const content = part.slice(sepIdx + sep.length);

  // 解析 headers
  const headers = {};
  headersPart.split('\r\n').forEach(line => {
    const colonIdx = line.indexOf(':');
    if (colonIdx > 0) {
      const key = line.slice(0, colonIdx).trim().toLowerCase();
      const value = line.slice(colonIdx + 1).trim();
      headers[key] = value;
    }
  });

  const disposition = headers['content-disposition'] || '';
  const nameMatch = disposition.match(/name="([^"]*)"/);
  const filenameMatch = disposition.match(/filename="([^"]*)"/);

  if (!nameMatch) return;
  const name = nameMatch[1];

  if (filenameMatch) {
    // 文件字段
    files.push({
      fieldname: name,
      filename: filenameMatch[1],
      contentType: headers['content-type'] || 'application/octet-stream',
      content: content,
      size: content.length
    });
  } else {
    // 普通字段
    fields[name] = content.toString('utf-8');
  }
}

/**
 * 保存上传的文件到指定目录
 * @param {Object} file 文件对象（来自 parseMultipart）
 * @param {string} subDir 子目录名 (avatars/logo/images/audios)
 * @param {number} maxSize 最大文件大小(字节)，默认 5MB
 * @param {Array<string>} allowedTypes 允许的 MIME 类型，null 则不限制
 * @returns {Promise<{url: string, filename: string, path: string}>}
 */
async function saveUploadedFile(file, subDir, maxSize = 5 * 1024 * 1024, allowedTypes = null) {
  // 检查大小
  if (maxSize && file.size > maxSize) {
    throw new Error(`文件过大，最大允许 ${Math.floor(maxSize / 1024)}KB`);
  }

  // 检查 MIME 类型
  if (allowedTypes && allowedTypes.length > 0) {
    const match = allowedTypes.some(t => {
      if (t.endsWith('/*')) {
        return file.contentType.startsWith(t.slice(0, -1));
      }
      return file.contentType === t;
    });
    if (!match) {
      throw new Error(`不支持的文件类型: ${file.contentType}`);
    }
  }

  // 生成文件名（时间戳+随机+扩展名）
  const ext = path.extname(file.filename) || getExtFromMime(file.contentType);
  const newFilename = Date.now().toString(36) + Math.random().toString(36).substring(2, 8) + ext;

  const uploadDir = path.join(storage.DATA_DIR, 'uploads', subDir);
  if (!fs.existsSync(uploadDir)) {
    fs.mkdirSync(uploadDir, { recursive: true });
  }

  const filePath = path.join(uploadDir, newFilename);
  fs.writeFileSync(filePath, file.content);

  // 访问 URL
  const url = `/uploads/${subDir}/${newFilename}`;

  return {
    url,
    filename: newFilename,
    path: filePath
  };
}

/**
 * 根据 MIME 类型获取扩展名
 * @param {string} mimeType
 * @returns {string}
 */
function getExtFromMime(mimeType) {
  const map = {
    'image/jpeg': '.jpg',
    'image/png': '.png',
    'image/gif': '.gif',
    'image/webp': '.webp',
    'image/bmp': '.bmp',
    'audio/webm': '.webm',
    'audio/ogg': '.ogg',
    'audio/mp4': '.m4a',
    'audio/mpeg': '.mp3',
    'audio/wav': '.wav'
  };
  return map[mimeType] || '.bin';
}

/**
 * 获取文件真实绝对路径（从 URL 转换）
 * @param {string} url 文件访问URL
 * @returns {string} 绝对路径
 */
function getFilePathFromUrl(url) {
  if (!url.startsWith('/uploads/')) return null;
  const relative = url.substring('/uploads/'.length);
  return path.join(storage.DATA_DIR, 'uploads', relative);
}

/**
 * 删除上传文件
 * @param {string} url 文件访问URL
 */
function deleteUploadedFile(url) {
  try {
    const filePath = getFilePathFromUrl(url);
    if (filePath && fs.existsSync(filePath)) {
      fs.unlinkSync(filePath);
    }
  } catch (err) {
    console.error('[Multipart] 删除文件失败:', err.message);
  }
}

module.exports = {
  parseMultipart,
  saveUploadedFile,
  getFilePathFromUrl,
  deleteUploadedFile,
  getExtFromMime
};
