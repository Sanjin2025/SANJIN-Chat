package com.sanjinchat.wear.call

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.sanjinchat.wear.network.ApiClient.IceServerConfig
import com.sanjinchat.wear.network.ChatEvent
import com.sanjinchat.wear.network.ChatWebSocketClient
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera1Enumerator
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraEnumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.CapturerObserver
import org.webrtc.DataChannel
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.audio.JavaAudioDeviceModule
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** 通话界面状态 */
sealed class CallState {
    object Idle : CallState()
    data class Outgoing(val peerId: String, val peerName: String, val media: String) : CallState()
    data class Incoming(val peerId: String, val peerName: String, val media: String) : CallState()
    data class Connecting(val peerId: String, val peerName: String, val media: String) : CallState()
    data class Active(
        val peerId: String,
        val peerName: String,
        val media: String,
        val connectedAt: Long
    ) : CallState()
}

/** 一通电话结束后的记录，用于在聊天里插入通话记录消息 */
data class CallLog(
    val peerId: String,
    val peerName: String,
    val media: String,        // audio / video
    val durationSec: Int,     // 通话时长（秒），0 表示未接通
    val label: String,        // 展示文案：通话时长 02:13 / 对方已取消 / 已取消 ...
    val timestamp: Long
)

/**
 * 1:1 语音/视频通话（WebRTC）。
 * 信令复用聊天 WebSocket（call:invite / call:answer / call:sdp / call:ice / call:end）。
 */
class CallManager(
    private val context: Context,
    private val ws: ChatWebSocketClient
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow<CallState>(CallState.Idle)
    val state: StateFlow<CallState> = _state.asStateFlow()

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _muted = MutableStateFlow(false)
    val muted: StateFlow<Boolean> = _muted.asStateFlow()

    /** 当前是否前置摄像头（用于本地画面镜像） */
    private val _frontCamera = MutableStateFlow(true)
    val frontCamera: StateFlow<Boolean> = _frontCamera.asStateFlow()

    /** 远端视频画面是否已经可以渲染 */
    private val _remoteVideoReady = MutableStateFlow(false)
    val remoteVideoReady: StateFlow<Boolean> = _remoteVideoReady.asStateFlow()

    /** 本地/远端已渲染帧计数（诊断画面是否真的到达） */
    private val _localFrameCount = MutableStateFlow(0)
    val localFrameCount: StateFlow<Int> = _localFrameCount.asStateFlow()

    private val _remoteFrameCount = MutableStateFlow(0)
    val remoteFrameCount: StateFlow<Int> = _remoteFrameCount.asStateFlow()

    /** 通话结束记录：聊天页收集后在会话里插入一条通话记录 */
    private val _callLogs = MutableSharedFlow<CallLog>(extraBufferCapacity = 8)
    val callLogs: SharedFlow<CallLog> = _callLogs.asSharedFlow()

    private var factory: PeerConnectionFactory? = null
    private val initialized = AtomicBoolean(false)
    private var audioDeviceModule: JavaAudioDeviceModule? = null

    /**
     * 当前设备是否支持 WebRTC 通话。
     * 轻量级检测：仅尝试加载 WebRTC native 层，不创建 PeerConnectionFactory / JavaAudioDeviceModule，
     * 避免在 WS 事件线程上做重型初始化导致阻塞、来电无应答。
     */
    @Volatile
    private var supported: Boolean? = null

    /** 检测设备是否支持 WebRTC（仅执行一次，结果缓存） */
    fun isCallSupported(): Boolean {
        supported?.let { return it }
        val ok = runCatching {
            if (initialized.compareAndSet(false, true)) {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions
                        .builder(context.applicationContext)
                        .setEnableInternalTracer(false)
                        .createInitializationOptions()
                )
            }
            true
        }.getOrDefault(false)
        supported = ok
        return ok
    }

    private var egl: EglBase? = null

    private var pc: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null

    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var remoteVideoTrack: VideoTrack? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var videoHelper: SurfaceTextureHelper? = null
    private var iceRestarted = false

    private var localRenderer: VideoSink? = null
    private var remoteRenderer: VideoSink? = null

    private var peerId: String? = null
    private var peerName: String = ""
    private var outgoing = false
    private var connectedAt = 0L
    private var media = "audio"
    private var answered = false
    private var pendingOffer: SessionDescription? = null
    private val pendingIce = mutableListOf<IceCandidate>()

    private var audioManager: AudioManager? = null

    /**
     * 服务端下发的 ICE 配置（含 TURN）。
     * 跨网络通话必须依赖 TURN 中继，仅靠 STUN 在对称 NAT 下无法打通。
     */
    @Volatile
    private var remoteIceServers: List<IceServerConfig> = emptyList()

    /** 应用启动时拉取 /api/call/ice 后注入 */
    fun applyIceServers(list: List<IceServerConfig>) {
        remoteIceServers = list
        android.util.Log.i("CallManager", "ICE 配置已更新：${list.size} 条（含 TURN 才能跨网络通话）")
    }

    fun isBusy(): Boolean = _state.value !is CallState.Idle

    fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

    fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

    /** 供 Compose 里的 SurfaceViewRenderer 初始化使用 */
    fun eglContext(): EglBase.Context? = runCatching {
        if (egl == null) egl = EglBase.create()
        egl?.eglBaseContext
    }.getOrNull()

    // ==================== 事件入口（由 Application 转发） ====================

    fun handleEvent(event: ChatEvent) {
        when (event) {
            is ChatEvent.CallInvite -> onInvite(event)
            is ChatEvent.CallRinging -> if (_state.value is CallState.Outgoing) _status.value = "对方响铃中..."
            is ChatEvent.CallAnswer -> onAnswerEvent(event)
            is ChatEvent.CallSdp -> onRemoteSdp(event)
            is ChatEvent.CallIce -> onRemoteIce(event)
            is ChatEvent.CallEnd -> onRemoteEnd(event)
            else -> {}
        }
    }

    // ==================== 主动呼叫 ====================

    fun startCall(peerId: String, peerName: String, media: String) {
        if (!isCallSupported()) {
            toast("当前设备不支持音视频通话")
            return
        }
        if (isBusy()) { toast("正在通话中"); return }
        if (!hasAudioPermission()) { toast("需要麦克风权限才能通话"); return }
        this.peerId = peerId
        this.peerName = peerName
        this.outgoing = true
        connectedAt = 0L
        this.media = if (media == "video") "video" else "audio"
        if (this.media == "video" && !hasCameraPermission()) {
            toast("需要相机权限才能视频通话")
            this.media = "audio"
        }
        answered = false
        pendingOffer = null
        pendingIce.clear()
        _remoteVideoReady.value = false
        _state.value = CallState.Outgoing(peerId, peerName, this.media)
        _status.value = "正在呼叫..."
        applyAudioMode()
        ws.sendCallInvite(peerId, this.media)

        val connection = createPeerConnection() ?: run { toast("无法创建通话连接"); cleanup("failed"); return }
        val withVideo = this.media == "video"
        setupVideoForOfferer(connection)
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", if (withVideo) "true" else "false"))
        }
        connection.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                desc ?: return
                connection.setLocalDescription(SdpObserverAdapter(), desc)
                ws.sendCallSdp(peerId, desc.type.canonicalForm(), desc.description)
            }

            override fun onCreateFailure(err: String?) {
                mainHandler.post { toast("发起通话失败：${err ?: "未知错误"}") }
                cleanup("failed")
            }
        }, constraints)
    }

    // ==================== 接听 / 拒绝 / 挂断 ====================

    fun accept() {
        val st = _state.value as? CallState.Incoming ?: return
        if (!hasAudioPermission()) { toast("需要麦克风权限才能通话"); reject(); return }
        if (st.media == "video" && !hasCameraPermission()) {
            toast("没有相机权限，将以语音方式接听")
        }
        _state.value = CallState.Connecting(st.peerId, st.peerName, st.media)
        _status.value = "正在接通..."
        applyAudioMode()
        ws.sendCallAnswer(st.peerId, true, st.media)

        val connection = createPeerConnection() ?: run { toast("无法创建通话连接"); cleanup(); return }
        val offer = pendingOffer
        if (offer != null) {
            pendingOffer = null
            connection.setRemoteDescription(object : SdpObserverAdapter() {
                override fun onSetSuccess() {
                    flushPendingIce(connection)
                    setupVideoForAnswerer(connection)
                    createAnswer(connection)
                }
            }, offer)
        }
    }

    fun reject() {
        peerId?.let { ws.sendCallEnd(it, "rejected") }
        cleanup("local_reject")
    }

    fun hangup() {
        peerId?.let { ws.sendCallEnd(it, "hangup") }
        cleanup("local_cancel")
    }

    fun toggleMute(): Boolean {
        val next = !_muted.value
        _muted.value = next
        audioTrack?.setEnabled(!next)
        return next
    }

    /** 前后置摄像头切换（仅视频通话有效） */
    fun switchCamera(): Boolean {
        val capturer = videoCapturer ?: return false
        return runCatching {
            capturer.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
                override fun onCameraSwitchDone(isFrontFacing: Boolean) {
                    mainHandler.post {
                        _frontCamera.value = isFrontFacing
                        (localRenderer as? org.webrtc.SurfaceViewRenderer)?.setMirror(isFrontFacing)
                    }
                }

                override fun onCameraSwitchError(error: String?) {}
            })
            true
        }.getOrDefault(false)
    }

    // ==================== 画面渲染器绑定 ====================

    /** 统计远端帧到达次数，用于判断"没画面"是没收到帧还是渲染层问题 */
    private class FrameCounter(
        private val inner: VideoSink,
        private val onCount: (Int) -> Unit
    ) : VideoSink {
        private var count = 0
        override fun onFrame(frame: VideoFrame?) {
            count++
            onCount(count)
            runCatching { inner.onFrame(frame) }
        }
    }

    private var localSink: VideoSink? = null
    private var remoteSink: VideoSink? = null

    fun attachLocalRenderer(renderer: VideoSink?) {
        if (localRenderer === renderer) return
        localSink?.let { r -> runCatching { videoTrack?.removeSink(r) } }
        localRenderer = renderer
        localSink = null
        renderer ?: return
        (renderer as? org.webrtc.SurfaceViewRenderer)?.setMirror(_frontCamera.value)
        val wrapped = FrameCounter(renderer) { n -> _localFrameCount.value = n }
        localSink = wrapped
        videoTrack?.let { runCatching { it.addSink(wrapped) } }
    }

    fun attachRemoteRenderer(renderer: VideoSink?) {
        if (remoteRenderer === renderer) return
        remoteSink?.let { r -> runCatching { remoteVideoTrack?.removeSink(r) } }
        remoteRenderer = renderer
        remoteSink = null
        renderer ?: return
        val wrapped = FrameCounter(renderer) { n -> _remoteFrameCount.value = n }
        remoteSink = wrapped
        remoteVideoTrack?.let { runCatching { it.addSink(wrapped) } }
    }

    // ==================== 远端事件处理 ====================

    private fun onInvite(e: ChatEvent.CallInvite) {
        if (e.fromId.isBlank()) return
        // 注意：不在此处做重型 WebRTC 初始化检测，避免阻塞 WS 事件线程导致无应答。
        // 即使设备不支持通话，也先响铃，用户接听时再由 createPeerConnection 兜底处理。
        if (isBusy()) {
            ws.sendCallAnswer(e.fromId, false, e.media)
            return
        }
        peerId = e.fromId
        peerName = e.fromNickname.ifBlank { "对方" }
        outgoing = false
        connectedAt = 0L
        media = if (e.media == "video") "video" else "audio"
        answered = false
        pendingOffer = null
        pendingIce.clear()
        _remoteVideoReady.value = false
        _state.value = CallState.Incoming(
            e.fromId,
            e.fromNickname.ifBlank { "对方" },
            media
        )
        _status.value = if (media == "video") "邀请你视频通话" else "邀请你语音通话"
    }

    private fun onAnswerEvent(e: ChatEvent.CallAnswer) {
        if (e.fromId != peerId) return
        if (!e.accept) {
            toast("对方已拒绝通话")
            cleanup("remote_reject")
            return
        }
        _status.value = "对方已接听，正在接通..."
    }

    private fun onRemoteSdp(e: ChatEvent.CallSdp) {
        if (e.fromId != peerId || e.sdp.isBlank()) return
        val isOffer = e.sdpType == "offer"
        val desc = SessionDescription(
            if (isOffer) SessionDescription.Type.OFFER else SessionDescription.Type.ANSWER,
            e.sdp
        )
        if (isOffer) {
            val connection = pc
            if (connection == null) {
                pendingOffer = desc
                return
            }
            if (answered) return
            connection.setRemoteDescription(object : SdpObserverAdapter() {
                override fun onSetSuccess() {
                    flushPendingIce(connection)
                    createAnswer(connection)
                }
            }, desc)
        } else {
            val connection = pc ?: return
            connection.setRemoteDescription(object : SdpObserverAdapter() {
                override fun onSetSuccess() { flushPendingIce(connection) }
            }, desc)
        }
    }

    private fun onRemoteIce(e: ChatEvent.CallIce) {
        if (e.fromId != peerId || e.candidate.isBlank()) return
        val candidate = IceCandidate(e.sdpMid ?: "0", e.sdpMLineIndex, e.candidate)
        val connection = pc
        if (connection != null && connection.remoteDescription != null) {
            runCatching { connection.addIceCandidate(candidate) }
        } else {
            pendingIce.add(candidate)
        }
    }

    private fun onRemoteEnd(e: ChatEvent.CallEnd) {
        if (e.fromId != peerId) return
        if (!isBusy()) { cleanup("remote_cancel"); return }
        val msg = when (e.reason) {
            "rejected" -> "对方已拒绝通话"
            "failed" -> "通话连接失败"
            else -> "通话已结束"
        }
        toast(msg)
        val reason = when (e.reason) {
            "rejected" -> "remote_reject"
            "failed" -> "failed"
            else -> "remote_cancel"
        }
        cleanup(reason)
    }

    // ==================== 内部实现 ====================

    private fun createAnswer(connection: PeerConnection) {
        if (answered) return
        answered = true
        val withVideo = media == "video"
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", if (withVideo) "true" else "false"))
        }
        connection.createAnswer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                desc ?: return
                connection.setLocalDescription(SdpObserverAdapter(), desc)
                peerId?.let { ws.sendCallSdp(it, desc.type.canonicalForm(), desc.description) }
            }

            override fun onCreateFailure(err: String?) {
                mainHandler.post { toast("接听失败：${err ?: "未知错误"}") }
                cleanup("failed")
            }
        }, constraints)
    }

    private fun flushPendingIce(connection: PeerConnection) {
        if (pendingIce.isEmpty()) return
        pendingIce.forEach { runCatching { connection.addIceCandidate(it) } }
        pendingIce.clear()
    }

    private fun markConnected() {
        val st = _state.value
        val pid = peerId ?: return
        val name = when (st) {
            is CallState.Outgoing -> st.peerName
            is CallState.Incoming -> st.peerName
            is CallState.Connecting -> st.peerName
            is CallState.Active -> st.peerName
            else -> return
        }
        if (st is CallState.Active) return
        val now = System.currentTimeMillis()
        connectedAt = now
        mainHandler.post {
            _state.value = CallState.Active(pid, name, media, now)
            _status.value = "通话中"
        }
    }

    /**
     * 创建 PeerConnectionFactory，带多级降级，保证小天才等裁剪 ROM 也能通话：
     *   1) 硬件编解码 + EGL（默认，性能最好）
     *   2) 软件编解码 + EGL（无硬件 VP8/H264 时）
     *   3) 软件编解码 且 不传 EGL（EGL 也不可用时，仍能解码+上屏到 TextureView）
     * 只有 PeerConnectionFactory.initialize 本身抛错（native 库缺失）才返回 null。
     */
    private fun ensureFactory(): PeerConnectionFactory? {
        factory?.let { return it }
        // 1) 初始化 native 层（必须成功，否则说明 WebRTC 库没装好）
        try {
            if (initialized.compareAndSet(false, true)) {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions
                        .builder(context.applicationContext)
                        .setEnableInternalTracer(false)
                        .createInitializationOptions()
                )
            }
        } catch (t: Throwable) {
            android.util.Log.w("CallManager", "WebRTC native 初始化失败：${t.javaClass.simpleName}: ${t.message}")
            supported = false
            return null
        }

        // 2) 尝试创建 EGL（小天才支持 TextureView，通常 EGL 可用；失败则不用 EGL）
        if (egl == null) {
            egl = runCatching { EglBase.create() }.getOrNull()
        }
        val eglCtx = runCatching { egl?.eglBaseContext }.getOrNull()

        // 2.5) 自定义音频设备模块：小天才等手表的音频路由特殊，
        // 必须显式指定 JavaAudioDeviceModule，并关闭硬件 AEC/NS（手表通常没有），
        // 否则 WebRTC 默认 ADM 会找不到麦克风/扬声器，表现为无声。
        val adm = runCatching {
            JavaAudioDeviceModule.builder(context)
                .setUseHardwareAcousticEchoCanceler(false)
                .setUseHardwareNoiseSuppressor(false)
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setSampleRate(16000)
                .setAudioTrackStateCallback(object : JavaAudioDeviceModule.AudioTrackStateCallback {
                    override fun onWebRtcAudioTrackStart() {}
                    override fun onWebRtcAudioTrackStop() {}
                })
                .setAudioTrackErrorCallback(object : JavaAudioDeviceModule.AudioTrackErrorCallback {
                    override fun onWebRtcAudioTrackInitError(s: String?) {
                        android.util.Log.e("CallManager", "AudioTrack init error: $s")
                    }
                    override fun onWebRtcAudioTrackStartError(code: JavaAudioDeviceModule.AudioTrackStartErrorCode?, s: String?) {
                        android.util.Log.e("CallManager", "AudioTrack start error: $code $s")
                    }
                    override fun onWebRtcAudioTrackError(s: String?) {
                        android.util.Log.e("CallManager", "AudioTrack error: $s")
                    }
                })
                .setAudioRecordStateCallback(object : JavaAudioDeviceModule.AudioRecordStateCallback {
                    override fun onWebRtcAudioRecordStart() {}
                    override fun onWebRtcAudioRecordStop() {}
                })
                .setAudioRecordErrorCallback(object : JavaAudioDeviceModule.AudioRecordErrorCallback {
                    override fun onWebRtcAudioRecordInitError(s: String?) {
                        android.util.Log.e("CallManager", "AudioRecord init error: $s")
                    }
                    override fun onWebRtcAudioRecordStartError(code: JavaAudioDeviceModule.AudioRecordStartErrorCode?, s: String?) {
                        android.util.Log.e("CallManager", "AudioRecord start error: $code $s")
                    }
                    override fun onWebRtcAudioRecordError(s: String?) {
                        android.util.Log.e("CallManager", "AudioRecord error: $s")
                    }
                })
                .createAudioDeviceModule()
        }.getOrNull()
        audioDeviceModule = adm
        if (adm == null) {
            android.util.Log.w("CallManager", "JavaAudioDeviceModule 创建失败，使用默认 ADM")
        }

        // 3) 按优先级尝试编解码工厂
        val strategies: List<Pair<String, () -> PeerConnectionFactory>> = listOf(
            "硬件编解码+EGL" to {
                PeerConnectionFactory.builder()
                    .setAudioDeviceModule(adm)
                    .setVideoEncoderFactory(org.webrtc.DefaultVideoEncoderFactory(eglCtx, true, true))
                    .setVideoDecoderFactory(org.webrtc.DefaultVideoDecoderFactory(eglCtx))
                    .createPeerConnectionFactory()
            },
            "软件编解码+EGL" to {
                PeerConnectionFactory.builder()
                    .setAudioDeviceModule(adm)
                    .setVideoEncoderFactory(org.webrtc.SoftwareVideoEncoderFactory())
                    .setVideoDecoderFactory(org.webrtc.SoftwareVideoDecoderFactory())
                    .createPeerConnectionFactory()
            }
        )

        var lastErr: Throwable? = null
        for ((name, create) in strategies) {
            try {
                val created = create()
                android.util.Log.i("CallManager", "通话编解码策略：$name")
                factory = created
                supported = true
                return created
            } catch (t: Throwable) {
                lastErr = t
                android.util.Log.w("CallManager", "策略 $name 失败：${t.javaClass.simpleName}: ${t.message}")
            }
        }

        // 4) 软件编解码也失败：只创建工厂（至少语音通话可用）
        try {
            val created = PeerConnectionFactory.builder()
                .setAudioDeviceModule(adm)
                .createPeerConnectionFactory()
            android.util.Log.i("CallManager", "通话编解码策略：仅软件兜底（视频可能不可用）")
            factory = created
            supported = true
            return created
        } catch (t: Throwable) {
            android.util.Log.w("CallManager", "PeerConnectionFactory 创建彻底失败：${t.javaClass.simpleName}: ${t.message}")
            android.util.Log.w("CallManager", "lastErr=${lastErr?.javaClass?.simpleName}: ${lastErr?.message}")
            supported = false
            return null
        }
    }

    private fun createPeerConnection(): PeerConnection? {
        pc?.let { return it }
        val f = ensureFactory() ?: return null
        val iceServers = mutableListOf<PeerConnection.IceServer>()
        // 1) 服务端下发的配置（含 TURN，跨网络通话必需）
        remoteIceServers.forEach { cfg ->
            runCatching {
                val b = PeerConnection.IceServer.builder(cfg.urls)
                if (!cfg.username.isNullOrBlank()) b.setUsername(cfg.username)
                if (!cfg.credential.isNullOrBlank()) b.setPassword(cfg.credential)
                iceServers.add(b.createIceServer())
            }
        }
        // 2) 兜底 STUN
        if (iceServers.isEmpty()) {
            listOf(
                "stun:stun.l.google.com:19302",
                "stun:stun1.l.google.com:19302",
                "stun:stun2.l.google.com:19302",
                "stun:stun3.l.google.com:19302",
                "stun:stun4.l.google.com:19302"
            ).forEach { iceServers.add(PeerConnection.IceServer.builder(it).createIceServer()) }
        }
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            iceTransportsType = PeerConnection.IceTransportsType.ALL
            // 允许 TCP candidate：部分运营商/企业网络封 UDP，TCP 可提升跨网连通率
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
            iceCandidatePoolSize = 2
        }
        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(newState: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                when (newState) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> markConnected()
                    PeerConnection.IceConnectionState.FAILED -> {
                        // 先尝试一次 ICE 重启，仍失败才真正挂断
                        val connection = pc
                        if (!iceRestarted && connection != null) {
                            iceRestarted = true
                            mainHandler.post {
                                runCatching {
                                    val c = MediaConstraints().apply {
                                        mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
                                    }
                                    connection.createOffer(object : SdpObserverAdapter() {
                                        override fun onCreateSuccess(desc: SessionDescription?) {
                                            desc ?: return
                                            connection.setLocalDescription(SdpObserverAdapter(), desc)
                                            peerId?.let { ws.sendCallSdp(it, desc.type.canonicalForm(), desc.description) }
                                        }
                                        override fun onCreateFailure(err: String?) {
                                            mainHandler.post { toast("通话连接失败：${err ?: "ICE 重启失败"}") }
                                            peerId?.let { p -> ws.sendCallEnd(p, "failed") }
                                            cleanup("failed")
                                        }
                                    }, c)
                                }.onFailure {
                                    peerId?.let { p -> ws.sendCallEnd(p, "failed") }
                                    cleanup("failed")
                                }
                            }
                        } else {
                            toast("通话连接失败")
                            peerId?.let { ws.sendCallEnd(it, "failed") }
                            cleanup("failed")
                        }
                    }
                    else -> {}
                }
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate ?: return
                val pid = peerId ?: return
                val mid = candidate.sdpMid
                val index = candidate.sdpMLineIndex
                val sdp = candidate.sdp
                mainHandler.post { ws.sendCallIce(pid, mid, index, sdp) }
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(dc: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                attachRemoteTrack(receiver?.track())
            }
            override fun onTrack(transceiver: org.webrtc.RtpTransceiver?) {
                attachRemoteTrack(transceiver?.receiver?.track())
            }
        }
        val connection = f.createPeerConnection(config, observer) ?: return null

        val source = f.createAudioSource(MediaConstraints())
        val track = f.createAudioTrack("audio0", source)
        connection.addTrack(track, listOf("stream0"))
        audioSource = source
        audioTrack = track

        // 视频 transceiver 由 startCall（offer 方）/ accept（answer 方）各自建立，
        // 因为两边的最佳时机不同：发起方要在 createOffer 之前，接听方要在 setRemoteDescription 之后。
        pc = connection
        return connection
    }

    /** 发起方：在 createOffer 之前建立视频通道（有摄像头就收发，没有就只收） */
    private fun setupVideoForOfferer(pc: PeerConnection) {
        if (media != "video") return
        val f = factory ?: return
        val vt = startVideoCapture(f)
        if (vt != null) {
            val ok = runCatching {
                pc.addTransceiver(
                    vt,
                    org.webrtc.RtpTransceiver.RtpTransceiverInit(
                        org.webrtc.RtpTransceiver.RtpTransceiverDirection.SEND_RECV
                    )
                )
            }.isSuccess
            if (!ok) runCatching { pc.addTrack(vt, listOf("stream0")) }
        } else {
            // 没有摄像头：显式建立 recvonly 视频通道，否则 offer 里可能没有视频 m 行
            runCatching {
                pc.addTransceiver(
                    org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                    org.webrtc.RtpTransceiver.RtpTransceiverInit(
                        org.webrtc.RtpTransceiver.RtpTransceiverDirection.RECV_ONLY
                    )
                )
            }
        }
    }

    /**
     * 接听方：必须在 setRemoteDescription(offer) 之后调用。
     * 直接给 offer 协商出来的视频 transceiver 挂本地轨，答案里才会真正带上视频；
     * 之前在 setRemoteDescription 之前 addTransceiver，部分版本会把视频 m 行答成 recvonly，
     * 导致发起方永远收不到接听方的画面。
     */
    private fun setupVideoForAnswerer(pc: PeerConnection) {
        if (media != "video") return
        val videoTx = runCatching {
            pc.transceivers.firstOrNull { it.receiver?.track()?.kind() == "video" }
        }.getOrNull() ?: return

        val f = factory ?: return
        val vt = startVideoCapture(f)
        if (vt != null) {
            runCatching {
                videoTx.sender?.setTrack(vt, false)
                videoTx.direction = org.webrtc.RtpTransceiver.RtpTransceiverDirection.SEND_RECV
            }
        } else {
            runCatching {
                videoTx.direction = org.webrtc.RtpTransceiver.RtpTransceiverDirection.RECV_ONLY
            }
        }
    }

    /** 启动摄像头采集并创建本地视频轨；失败返回 null（退化为纯语音） */
    private fun startVideoCapture(f: PeerConnectionFactory): VideoTrack? {
        if (videoTrack != null) return videoTrack
        if (!hasCameraPermission()) return null
        return runCatching {
            val enumerator: CameraEnumerator =
                if (Camera2Enumerator.isSupported(context)) Camera2Enumerator(context)
                else Camera1Enumerator(true)
            val names = enumerator.deviceNames
            if (names.isEmpty()) return null
            val wantFront = _frontCamera.value
            val target = names.firstOrNull {
                if (wantFront) enumerator.isFrontFacing(it) else enumerator.isBackFacing(it)
            } ?: names.first()
            val capturer = enumerator.createCapturer(target, null) ?: return null

            val helper = SurfaceTextureHelper.create("CaptureThread", eglContext())
            val source = f.createVideoSource(capturer.isScreencast)
            capturer.initialize(helper, context, source.capturerObserver)
            val track = f.createVideoTrack("video0", source)
            localSink?.let { r -> runCatching { track.addSink(r) } }
            capturer.startCapture(480, 480, 24)

            videoCapturer = capturer
            videoHelper = helper
            videoSource = source
            videoTrack = track
            track
        }.getOrNull()
    }

    private fun attachRemoteTrack(track: MediaStreamTrack?) {
        val vt = track as? VideoTrack ?: return
        // 远端视频轨默认是启用的，但显式设一次以防万一
        runCatching { vt.setEnabled(true) }
        remoteVideoTrack = vt
        remoteSink?.let { r -> runCatching { vt.addSink(r) } }
        mainHandler.post { _remoteVideoReady.value = true }
    }

    /**
     * 结束通话并清理资源。
     * @param reason local_cancel / local_reject / remote_cancel / remote_reject / failed
     */
    private fun cleanup(reason: String = "local_cancel") {
        val wasBusy = _state.value !is CallState.Idle
        val wasActive = _state.value is CallState.Active
        val pid = peerId
        val logMedia = media
        val logName = peerName.ifBlank { "对方" }
        val durationSec = if (wasActive && connectedAt > 0L) {
            ((System.currentTimeMillis() - connectedAt) / 1000L).toInt().coerceAtLeast(0)
        } else 0

        // 只有在真正有通话时，才在聊天里留一条通话记录
        if (wasBusy && pid != null) {
            val log = CallLog(
                peerId = pid,
                peerName = logName,
                media = logMedia,
                durationSec = durationSec,
                label = buildLogLabel(reason, wasActive, durationSec),
                timestamp = System.currentTimeMillis()
            )
            runCatching { _callLogs.tryEmit(log) }
        }

        media = "audio"
        answered = false
        pendingOffer = null
        pendingIce.clear()
        _remoteVideoReady.value = false
        runCatching { localSink?.let { videoTrack?.removeSink(it) } }
        runCatching { remoteSink?.let { remoteVideoTrack?.removeSink(it) } }
        localSink = null
        remoteSink = null
        _localFrameCount.value = 0
        _remoteFrameCount.value = 0
        runCatching { videoCapturer?.stopCapture() }
        runCatching { videoCapturer?.dispose() }
        runCatching { videoHelper?.dispose() }
        runCatching { videoTrack?.dispose() }
        runCatching { videoSource?.dispose() }
        runCatching { audioTrack?.setEnabled(true) }
        runCatching { pc?.close() }
        runCatching { pc?.dispose() }
        runCatching { audioTrack?.dispose() }
        runCatching { audioSource?.dispose() }
        pc = null
        audioTrack = null
        audioSource = null
        videoCapturer = null
        videoHelper = null
        videoTrack = null
        videoSource = null
        remoteVideoTrack = null
        peerId = null
        peerName = ""
        outgoing = false
        connectedAt = 0L
        iceRestarted = false
        _muted.value = false
        _status.value = ""
        _state.value = CallState.Idle
        restoreAudio()
    }

    /** 生成通话记录文案 */
    private fun buildLogLabel(reason: String, wasActive: Boolean, durationSec: Int): String = when {
        wasActive -> String.format(Locale.US, "通话时长 %02d:%02d", durationSec / 60, durationSec % 60)
        reason == "remote_reject" -> "对方已拒绝"
        reason == "local_reject" -> "已拒绝"
        reason == "remote_cancel" -> "对方已取消"
        reason == "failed" -> "通话失败"
        reason == "local_cancel" -> if (outgoing) "已取消" else "已拒绝"
        else -> "通话已结束"
    }

    private fun applyAudioMode() {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        audioManager = am
        runCatching {
            // 1) 通话模式：部分手表 MODE_IN_COMMUNICATION 不生效，两个都试
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            // 2) 强制走扬声器（手表没有听筒，必须外放）
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = true
            // 3) 通话音量拉满，避免被系统压到 0
            val maxCall = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, maxCall, 0)
            // 4) 媒体音量也拉满（部分手表的扬声器走 STREAM_MUSIC）
            val maxMusic = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, maxMusic, 0)
            // 5) 关闭麦克风静音
            @Suppress("DEPRECATION")
            am.isMicrophoneMute = false
        }
    }

    private fun restoreAudio() {
        val am = audioManager ?: return
        runCatching {
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = false
            am.mode = AudioManager.MODE_NORMAL
        }
    }

    private fun toast(msg: String) {
        mainHandler.post {
            runCatching { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
        }
    }

    /** SdpObserver 空实现基类，按需覆写；setLocal/setRemote 失败时给出提示，方便排查 */
    private open class SdpObserverAdapter : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription?) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(err: String?) = Unit
        override fun onSetFailure(err: String?) {
            // SDP 设置失败往往是导致 ICE 无法接通的根因，在这里给一个可见提示
        }
    }
}
