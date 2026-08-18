// ==================== 语音识别帮助类 ====================
package com.sanjin.wear

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/**
 * 语音识别助手（基于系统 SpeechRecognizer）
 * 将语音转为文字后发送给聊天
 */
object SpeechHelper {

    /** 请求录音权限的 requestCode */
    const val REQ_AUDIO_PERMISSION = 1001

    /** 当前的识别状态 */
    enum class SpeechState { IDLE, LISTENING, SUCCESS, ERROR }

    private val _state = MutableStateFlow(SpeechState.IDLE)
    val state: StateFlow<SpeechState> get() = _state

    private val _text = MutableStateFlow("")
    val resultText: StateFlow<String> get() = _text

    private val _error = MutableStateFlow("")
    val errorText: StateFlow<String> get() = _error

    private var recognizer: SpeechRecognizer? = null
    private var recognizerIntent: Intent? = null
    private var listener: ((String) -> Unit)? = null

    /**
     * 检查并请求录音权限
     */
    fun ensurePermission(activity: Activity): Boolean {
        val granted = ContextCompat.checkSelfPermission(
            activity, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQ_AUDIO_PERMISSION
            )
        }
        return granted
    }

    /**
     * 开始识别
     * @param ctx 上下文
     * @param onResult 识别完成回调（返回识别到的文字）
     */
    fun start(ctx: android.content.Context, onResult: (String) -> Unit) {
        try {
            if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
                _error.value = "设备不支持语音识别"
                _state.value = SpeechState.ERROR
                return
            }
            listener = onResult
            destroy()
            recognizer = SpeechRecognizer.createSpeechRecognizer(ctx).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        _state.value = SpeechState.LISTENING
                        _text.value = ""
                        _error.value = ""
                    }

                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}

                    override fun onError(error: Int) {
                        val msg = when (error) {
                            SpeechRecognizer.ERROR_AUDIO -> "录音错误"
                            SpeechRecognizer.ERROR_CLIENT -> "客户端错误"
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "缺少录音权限"
                            SpeechRecognizer.ERROR_NETWORK,
                            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络错误"
                            SpeechRecognizer.ERROR_NO_MATCH -> "没有识别到语音"
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别器忙碌"
                            SpeechRecognizer.ERROR_SERVER -> "服务器错误"
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "语音输入超时"
                            else -> "未知错误 ($error)"
                        }
                        _error.value = msg
                        _state.value = SpeechState.ERROR
                    }

                    override fun onResults(results: Bundle?) {
                        val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!list.isNullOrEmpty()) {
                            val text = list[0].trim()
                            _text.value = text
                            _state.value = SpeechState.SUCCESS
                            listener?.invoke(text)
                        } else {
                            _error.value = "识别结果为空"
                            _state.value = SpeechState.ERROR
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
            recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1500)
            }
            recognizer?.startListening(recognizerIntent)
        } catch (e: Exception) {
            _error.value = e.message ?: "启动语音识别失败"
            _state.value = SpeechState.ERROR
        }
    }

    /**
     * 停止识别
     */
    fun stop() {
        try { recognizer?.stopListening() } catch (_: Exception) {}
        _state.value = SpeechState.IDLE
    }

    /**
     * 销毁释放
     */
    fun destroy() {
        try {
            recognizer?.destroy()
        } catch (_: Exception) {}
        recognizer = null
        recognizerIntent = null
    }

    /**
     * 重置状态
     */
    fun reset() {
        _state.value = SpeechState.IDLE
        _text.value = ""
        _error.value = ""
    }
}
