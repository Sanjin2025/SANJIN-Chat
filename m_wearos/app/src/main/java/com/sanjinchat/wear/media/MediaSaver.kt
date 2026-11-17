package com.sanjinchat.wear.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream
import java.io.OutputStream

/**
 * 把聊天里对方发来的图片 / 视频保存到系统相册。
 * 通过 OkHttp 下载流式写入 MediaStore，避免落盘临时文件。
 */
object MediaSaver {

    data class SaveRequest(
        val url: String,
        val isVideo: Boolean,
        val displayName: String
    )

    private val client by lazy {
        OkHttpClient.Builder()
            .callTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    /**
     * 下载并保存。返回是否成功；失败时已 Toast 提示。
     */
    suspend fun save(context: Context, req: SaveRequest): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(req.url).build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                val body = resp.body ?: throw IllegalStateException("响应为空")
                val contentLength = body.contentLength()
                val ext = req.displayName.substringAfterLast('.', "")
                val mime = if (req.isVideo) guessVideoMime(ext) else "image/${ext.ifBlank { "jpeg" }}"

                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, req.displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    if (contentLength > 0) put(MediaStore.MediaColumns.SIZE, contentLength)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.MediaColumns.RELATIVE_PATH, if (req.isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES)
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                }

                val resolver = context.contentResolver
                val collection = if (req.isVideo) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                } else {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                }

                val uri: Uri = resolver.insert(collection, values)
                    ?: throw IllegalStateException("无法创建相册条目")

                try {
                    val out: OutputStream = requireNotNull(resolver.openOutputStream(uri)) {
                        "无法打开输出流"
                    }
                    val input: InputStream = body.byteStream()
                    input.use { i -> out.use { o -> i.copyTo(o) } }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        values.clear()
                        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                        resolver.update(uri, values, null, null)
                    }
                } catch (e: Exception) {
                    runCatching { resolver.delete(uri, null, null) }
                    throw e
                }
            }
        }.fold(
            onSuccess = {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        if (req.isVideo) "视频已保存到相册" else "图片已保存到相册",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                true
            },
            onFailure = { e ->
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        "保存失败：${e.message ?: "未知错误"}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                false
            }
        )
    }

    private fun guessVideoMime(ext: String): String = when (ext.lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "3gp" -> "video/3gpp"
        "mov" -> "video/quicktime"
        "mkv" -> "video/x-matroska"
        else -> "video/mp4"
    }
}
