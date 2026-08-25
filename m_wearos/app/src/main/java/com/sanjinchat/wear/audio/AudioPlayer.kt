package com.sanjinchat.wear.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException

/**
 * 简易在线音频播放器：用于播放语音消息（m4a/aac/webm/mp3 等）。
 *
 * - 同一时刻只允许播放一条音频（收到 play 新请求会先停掉旧的）
 * - 暴露 playing/position/duration 三个 Flow 给 UI 展示进度条/播放状态
 * - 使用方式：
 *   ```
 *   val player = remember { AudioPlayer(context) }
 *   DisposableEffect(Unit) { onDispose { player.release() } }
 *   player.toggle(url)  // 切换播放/暂停
 *   ```
 */
class AudioPlayer(private val context: Context) {

    private var mediaPlayer: MediaPlayer? = null
    private val _currentUrl = MutableStateFlow<String?>(null)
    val currentUrl: StateFlow<String?> = _currentUrl.asStateFlow()

    private var tickThread: Thread? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    // 当前播放位置（毫秒），未开始时为 0
    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    // 总时长（毫秒），未知时为 0
    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    /**
     * 切换播放/暂停。
     * - 如果传入的 url 与当前播放不同 → 停止旧的，开始新的
     * - 如果相同且正在播放 → 暂停
     * - 如果相同且已暂停 → 继续
     */
    fun toggle(url: String) {
        val mp = mediaPlayer
        if (mp != null && _currentUrl.value == url) {
            if (mp.isPlaying) {
                pauseInternal()
            } else {
                try {
                    mp.start()
                    startTicker()
                    _isPlaying.value = true
                } catch (_: Exception) {
                    startInternal(url)
                }
            }
            return
        }
        // 切换到新音频或首次播放
        stopInternal()
        startInternal(url)
    }

    /** 停止并释放资源。切换会话时调用。 */
    fun stop() {
        stopInternal()
    }

    /** 完全释放（Activity 销毁时调用）。 */
    fun release() {
        stopInternal()
    }

    // ================== 内部 ==================

    private fun startInternal(url: String) {
        val mp = try {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(url)
                prepareAsync()
            }
        } catch (e: IOException) {
            _isPlaying.value = false
            return
        } catch (e: IllegalArgumentException) {
            _isPlaying.value = false
            return
        }
        _currentUrl.value = url
        mediaPlayer = mp
        _positionMs.value = 0L
        _durationMs.value = 0L

        mp.setOnPreparedListener { prepared ->
            try {
                _durationMs.value = prepared.duration.toLong().coerceAtLeast(0L)
                prepared.start()
                _isPlaying.value = true
                startTicker()
            } catch (_: Exception) {}
        }
        mp.setOnCompletionListener {
            _isPlaying.value = false
            stopTicker()
            try {
                _positionMs.value = _durationMs.value
            } catch (_: Exception) {}
        }
        mp.setOnErrorListener { _, _, _ ->
            _isPlaying.value = false
            stopTicker()
            true
        }
    }

    private fun pauseInternal() {
        try {
            mediaPlayer?.takeIf { it.isPlaying }?.pause()
        } catch (_: Exception) {}
        _isPlaying.value = false
        stopTicker()
    }

    private fun stopInternal() {
        stopTicker()
        val mp = mediaPlayer
        mediaPlayer = null
        _currentUrl.value = null
        _isPlaying.value = false
        _positionMs.value = 0L
        _durationMs.value = 0L
        if (mp != null) {
            try {
                if (mp.isPlaying) mp.stop()
            } catch (_: Exception) {}
            try { mp.release() } catch (_: Exception) {}
        }
    }

    // 进度轮询（每 100ms 同步一次 position）
    @Synchronized
    private fun startTicker() {
        if (tickThread?.isAlive == true) return
        val t = Thread {
            try {
                while (!Thread.interrupted()) {
                    val mp = mediaPlayer
                    if (mp == null || !mp.isPlaying) break
                    try {
                        _positionMs.value = mp.currentPosition.toLong().coerceAtLeast(0L)
                    } catch (_: Exception) {
                    }
                    Thread.sleep(100)
                }
            } catch (_: InterruptedException) {
            }
        }.apply { isDaemon = true; name = "AudioPlayerTicker" }
        tickThread = t
        t.start()
    }

    @Synchronized
    private fun stopTicker() {
        tickThread?.interrupt()
        tickThread = null
    }
}
