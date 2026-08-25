package com.sanjinchat.wear.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import java.io.File

/**
 * 录音器：按住说话时使用 MediaRecorder 录制 m4a（audio/mp4），
 * 与服务端上传 MIME 白名单匹配。
 *
 * 使用方式：
 *   val rec = AudioRecorder(context)
 *   rec.start()            // 按下
 *   ...
 *   val file = rec.stop()  // 松开，返回已录制的文件，durationMs 为时长
 *   rec.cancel()           // 取消（不发送）
 */
class AudioRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startElapsedMs: Long = 0L

    /** 开始录音。返回 true 表示成功开始。 */
    fun start(): Boolean {
        if (recorder != null) return true
        return try {
            val dir = File(context.cacheDir, "audio").apply { mkdirs() }
            val file = File(dir, "rec_${System.currentTimeMillis()}.m4a")
            val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            rec.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(64_000)
                setAudioSamplingRate(44_100)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            recorder = rec
            outputFile = file
            startElapsedMs = SystemClock.elapsedRealtime()
            true
        } catch (e: Exception) {
            cleanup()
            false
        }
    }

    /**
     * 停止录音并返回结果。
     * @return 已录制的音频文件与时长（毫秒），如果录制时间过短或失败返回 null
     */
    fun stop(): RecordingResult? {
        val rec = recorder ?: return null
        val file = outputFile ?: run { cleanup(); return null }
        val durMs = (SystemClock.elapsedRealtime() - startElapsedMs).coerceAtLeast(0L)
        return try {
            rec.stop()
            rec.release()
            recorder = null
            outputFile = null
            // 录得太短（< 500ms）视为无效
            if (durMs < 500L) {
                file.delete()
                null
            } else {
                RecordingResult(file = file, durationMs = durMs)
            }
        } catch (e: Exception) {
            file.delete()
            cleanup()
            null
        }
    }

    /** 取消本次录音，删除临时文件。 */
    fun cancel() {
        try {
            recorder?.let {
                runCatching { it.stop() }
                runCatching { it.release() }
            }
        } finally {
            outputFile?.delete()
            cleanup()
        }
    }

    /** 当前是否正在录音 */
    fun isRecording(): Boolean = recorder != null

    /** 已录制时长（毫秒），未开始时返回 0 */
    fun currentDurationMs(): Long =
        if (recorder == null) 0L
        else (SystemClock.elapsedRealtime() - startElapsedMs).coerceAtLeast(0L)

    private fun cleanup() {
        recorder = null
        outputFile = null
        startElapsedMs = 0L
    }

    data class RecordingResult(val file: File, val durationMs: Long)
}
