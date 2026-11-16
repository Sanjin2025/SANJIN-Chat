package com.sanjinchat.wear.call

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.sanjinchat.wear.network.ChatEvent
import com.sanjinchat.wear.network.ChatWebSocketClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
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

/**
 * 1:1 语音通话（WebRTC，手表端仅音频）。
 * 信令复用聊天 WebSocket（call:invite / call:answer / call:sdp / call:ice / call:end），
 * 服务端已在 src/chatServer.js 中实现透传。
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

    private var factory: PeerConnectionFactory? = null
    private val initialized = AtomicBoolean(false)

    private var pc: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null

    private var peerId: String? = null
    private var media = "audio"
    private var answered = false
    private var pendingOffer: SessionDescription? = null
    private val pendingIce = mutableListOf<IceCandidate>()

    private var audioManager: AudioManager? = null

    fun isBusy(): Boolean = _state.value !is CallState.Idle

    fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

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
        if (isBusy()) { toast("正在通话中"); return }
        if (!hasAudioPermission()) { toast("需要麦克风权限才能通话"); return }
        this.peerId = peerId
        this.media = if (media == "video") "video" else "audio"
        answered = false
        pendingOffer = null
        pendingIce.clear()
        _state.value = CallState.Outgoing(peerId, peerName, this.media)
        _status.value = "正在呼叫..."
        applyAudioMode()
        ws.sendCallInvite(peerId, this.media)

        val connection = createPeerConnection() ?: run {
            toast("无法创建通话连接")
            cleanup()
            return
        }
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }
        connection.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                desc ?: return
                connection.setLocalDescription(SdpObserverAdapter(), desc)
                ws.sendCallSdp(peerId, desc.type.canonicalForm(), desc.description)
            }

            override fun onCreateFailure(err: String?) {
                mainHandler.post { toast("发起通话失败：${err ?: "未知错误"}") }
                cleanup()
            }
        }, constraints)
    }

    // ==================== 接听 / 拒绝 / 挂断 ====================

    fun accept() {
        val st = _state.value as? CallState.Incoming ?: return
        if (!hasAudioPermission()) { toast("需要麦克风权限才能通话"); reject(); return }
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
                    createAnswer(connection)
                }
            }, offer)
        }
    }

    fun reject() {
        peerId?.let { ws.sendCallEnd(it, "rejected") }
        cleanup()
    }

    fun hangup() {
        peerId?.let { ws.sendCallEnd(it, "hangup") }
        cleanup()
    }

    fun toggleMute(): Boolean {
        val next = !_muted.value
        _muted.value = next
        audioTrack?.setEnabled(!next)
        return next
    }

    // ==================== 远端事件处理 ====================

    private fun onInvite(e: ChatEvent.CallInvite) {
        if (e.fromId.isBlank()) return
        if (isBusy()) {
            ws.sendCallAnswer(e.fromId, false, e.media)
            return
        }
        peerId = e.fromId
        media = if (e.media == "video") "video" else "audio"
        answered = false
        pendingOffer = null
        pendingIce.clear()
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
            cleanup()
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
        if (!isBusy()) { cleanup(); return }
        val msg = when (e.reason) {
            "rejected" -> "对方已拒绝通话"
            "failed" -> "通话连接失败"
            else -> "通话已结束"
        }
        toast(msg)
        cleanup()
    }

    // ==================== 内部实现 ====================

    private fun createAnswer(connection: PeerConnection) {
        if (answered) return
        answered = true
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }
        connection.createAnswer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                desc ?: return
                connection.setLocalDescription(SdpObserverAdapter(), desc)
                peerId?.let { ws.sendCallSdp(it, desc.type.canonicalForm(), desc.description) }
            }

            override fun onCreateFailure(err: String?) {
                mainHandler.post { toast("接听失败：${err ?: "未知错误"}") }
                cleanup()
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
        mainHandler.post {
            _state.value = CallState.Active(pid, name, media, System.currentTimeMillis())
            _status.value = "通话中"
        }
    }

    private fun ensureFactory(): PeerConnectionFactory {
        factory?.let { return it }
        if (initialized.compareAndSet(false, true)) {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions
                    .builder(context.applicationContext)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions()
            )
        }
        val created = PeerConnectionFactory.builder().createPeerConnectionFactory()
        factory = created
        return created
    }

    private fun createPeerConnection(): PeerConnection? {
        pc?.let { return it }
        val f = ensureFactory()
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(newState: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                when (newState) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> markConnected()
                    PeerConnection.IceConnectionState.FAILED -> {
                        toast("通话连接失败")
                        peerId?.let { ws.sendCallEnd(it, "failed") }
                        cleanup()
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
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
        }
        val connection = f.createPeerConnection(config, observer) ?: return null
        val source = f.createAudioSource(MediaConstraints())
        val track = f.createAudioTrack("audio0", source)
        connection.addTrack(track, listOf("stream0"))
        audioSource = source
        audioTrack = track
        pc = connection
        return connection
    }

    private fun cleanup() {
        media = "audio"
        answered = false
        pendingOffer = null
        pendingIce.clear()
        runCatching { audioTrack?.setEnabled(true) }
        runCatching { pc?.close() }
        runCatching { pc?.dispose() }
        runCatching { audioTrack?.dispose() }
        runCatching { audioSource?.dispose() }
        pc = null
        audioTrack = null
        audioSource = null
        peerId = null
        _muted.value = false
        _status.value = ""
        _state.value = CallState.Idle
        restoreAudio()
    }

    private fun applyAudioMode() {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        audioManager = am
        runCatching {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = true
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

    /** SdpObserver 空实现基类，按需覆写 */
    private open class SdpObserverAdapter : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription?) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(err: String?) = Unit
        override fun onSetFailure(err: String?) = Unit
    }
}
