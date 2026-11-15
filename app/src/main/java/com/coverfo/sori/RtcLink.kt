package com.coverfo.sori

import android.content.Context
import android.media.MediaRecorder
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * v26 🎙 WebRTC 연결 — coverfo 웹(브라우저)과 같은 방식으로 OpenAI Realtime 에 연결한다.
 * · 마이크 소리는 WebRTC 가 직접 받아 다듬고(에코 제거 · 잡음 줄이기 · 소리 크기 맞추기 = 브라우저와 같은 처리) Opus 로 보낸다.
 * · 휴대폰 자체 잡음 제거(하드웨어)는 쓰지 않는다 → 잡음 제거가 두 번 걸리지 않게. 에코 제거는 휴대폰 것이 있으면 그것을 쓴다.
 * · 목소리는 WebRTC 가 바로 스피커로 재생한다.
 * · 이벤트(도구 호출 · 받아쓰기 등)는 데이터 채널 "oai-events" 로 주고받는다 — WebSocket 때와 같은 JSON.
 * · start() 가 실패하면 null 이 아닌 오류 글을 돌려준다 → SoriService 가 예전 WebSocket 방식으로 연결한다.
 */
class RtcLink(private val ctx: Context, private val cb: Callback) {

    interface Callback {
        fun onRtcOpen()
        fun onRtcMessage(text: String)
        fun onRtcClosed(reason: String?)
    }

    companion object {
        private const val CALLS = "https://api.openai.com/v1/realtime/calls"
        @Volatile private var inited = false
        private fun init(ctx: Context) {
            if (inited) return
            synchronized(this) {
                if (inited) return
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(ctx.applicationContext).createInitializationOptions()
                )
                inited = true
            }
        }
    }

    private var adm: JavaAudioDeviceModule? = null
    private var factory: PeerConnectionFactory? = null
    private var pc: PeerConnection? = null
    private var src: AudioSource? = null
    private var mic: AudioTrack? = null
    private var dc: DataChannel? = null
    @Volatile private var opened = false
    @Volatile private var closed = false
    private val openLatch = CountDownLatch(1)
    private val iceLatch = CountDownLatch(1)

    /** 연결해서 데이터 채널이 열릴 때까지 기다린다 (작업 스레드에서 부를 것). 성공하면 null, 실패하면 오류 글 */
    fun start(key: String, http: OkHttpClient): String? {
        try {
            init(ctx)
            val a = JavaAudioDeviceModule.builder(ctx.applicationContext)
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setUseHardwareAcousticEchoCanceler(JavaAudioDeviceModule.isBuiltInAcousticEchoCancelerSupported())
                .setUseHardwareNoiseSuppressor(false)
                .createAudioDeviceModule()
            adm = a
            val f = PeerConnectionFactory.builder().setAudioDeviceModule(a).createPeerConnectionFactory()
            factory = f

            val cfg = PeerConnection.RTCConfiguration(emptyList())
            val p = f.createPeerConnection(cfg, Obs()) ?: return "PeerConnection 을 만들지 못했어요"
            pc = p

            /* 마이크 (기본값 = 에코 제거 · 잡음 줄이기 · 소리 크기 맞추기 켜짐, 브라우저와 같은 처리) */
            val s = f.createAudioSource(MediaConstraints())
            src = s
            val t = f.createAudioTrack("mic", s)
            mic = t
            p.addTrack(t, listOf("sori"))

            val d = p.createDataChannel("oai-events", DataChannel.Init())
            dc = d
            d.registerObserver(object : DataChannel.Observer {
                override fun onBufferedAmountChange(previousAmount: Long) {}
                override fun onStateChange() {
                    when (d.state()) {
                        DataChannel.State.OPEN -> if (!opened) { opened = true; openLatch.countDown() }
                        DataChannel.State.CLOSED -> lost("데이터 채널이 닫혔어요")
                        else -> {}
                    }
                }
                override fun onMessage(buffer: DataChannel.Buffer) {
                    try {
                        val bb = buffer.data
                        val bytes = ByteArray(bb.remaining())
                        bb.get(bytes)
                        cb.onRtcMessage(String(bytes, Charsets.UTF_8))
                    } catch (e: Exception) { }
                }
            })

            /* 제안(offer) 만들기 → 내 쪽 설정 → 접속 후보 모으기(최대 3초) */
            val offer = sdpCall { o -> p.createOffer(o, MediaConstraints()) } ?: return "연결 제안을 만들지 못했어요"
            if (!setCall { o -> p.setLocalDescription(o, offer) }) return "연결 제안을 설정하지 못했어요"
            iceLatch.await(3, TimeUnit.SECONDS)
            val local = p.localDescription?.description ?: offer.description

            /* OpenAI 에 보내고 답(answer) 받기 — 키는 서버가 발급한 1회용 임시 키 */
            val req = Request.Builder().url(CALLS)
                .header("Authorization", "Bearer $key")
                .post(local.toRequestBody("application/sdp".toMediaType()))
                .build()
            val (code, answer) = http.newCall(req).execute().use { r -> Pair(r.code, r.body?.string() ?: "") }
            if (code !in 200..299 || !answer.startsWith("v=0")) return "연결 응답 오류 ($code): " + answer.take(160)
            if (!setCall { o -> p.setRemoteDescription(o, SessionDescription(SessionDescription.Type.ANSWER, answer)) }) return "연결 응답을 설정하지 못했어요"

            if (!openLatch.await(12, TimeUnit.SECONDS)) return "연결이 열리지 않았어요"
            if (closed) return "연결이 닫혔어요"
            cb.onRtcOpen()
            return null
        } catch (e: Throwable) {
            return "WebRTC 오류: " + (e.message ?: e.javaClass.simpleName)
        }
    }

    fun send(json: String): Boolean {
        val d = dc ?: return false
        if (closed || d.state() != DataChannel.State.OPEN) return false
        return try { d.send(DataChannel.Buffer(ByteBuffer.wrap(json.toByteArray(Charsets.UTF_8)), false)) } catch (e: Exception) { false }
    }

    /** 끊기 (모두 정리). 여러 번 불러도 된다. 정리는 작업 스레드에서 */
    fun close() {
        if (closed && pc == null) return
        closed = true
        openLatch.countDown()
        iceLatch.countDown()
        val d = dc; val p = pc; val s = src; val f = factory; val a = adm
        dc = null; pc = null; mic = null; src = null; factory = null; adm = null
        /* 정리 순서는 Google WebRTC 예제(AppRTC)와 같게: 데이터 채널 → 연결(마이크 트랙도 함께 정리됨) → 마이크 소스 → 공장 → 오디오 장치 */
        Thread {
            try { d?.unregisterObserver() } catch (e: Throwable) { }
            try { d?.dispose() } catch (e: Throwable) { }
            try { p?.dispose() } catch (e: Throwable) { }
            try { s?.dispose() } catch (e: Throwable) { }
            try { f?.dispose() } catch (e: Throwable) { }
            try { a?.release() } catch (e: Throwable) { }
        }.start()
    }

    /* 연결 중(열린 뒤) 끊기면 알린다. 우리가 닫은 것이면 알리지 않는다 */
    private fun lost(reason: String) {
        if (closed) return
        if (!opened) { closed = true; openLatch.countDown(); return }
        closed = true
        cb.onRtcClosed(reason)
    }

    private inner class Obs : PeerConnection.Observer {
        override fun onSignalingChange(newState: PeerConnection.SignalingState?) {}
        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
            if (newState == PeerConnection.IceConnectionState.FAILED) lost("연결이 끊겼어요")
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {
            if (newState == PeerConnection.IceGatheringState.COMPLETE) iceLatch.countDown()
        }
        override fun onIceCandidate(candidate: IceCandidate?) {}
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
        override fun onAddStream(stream: MediaStream?) {}
        override fun onRemoveStream(stream: MediaStream?) {}
        override fun onDataChannel(dataChannel: DataChannel?) {}
        override fun onRenegotiationNeeded() {}
    }

    /* SDP 만들기/설정하기를 기다려서 결과를 받는다 (최대 5초) */
    private fun sdpCall(run: (SdpObserver) -> Unit): SessionDescription? {
        val l = CountDownLatch(1)
        var out: SessionDescription? = null
        run(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) { out = sdp; l.countDown() }
            override fun onSetSuccess() { l.countDown() }
            override fun onCreateFailure(error: String?) { l.countDown() }
            override fun onSetFailure(error: String?) { l.countDown() }
        })
        l.await(5, TimeUnit.SECONDS)
        return out
    }

    private fun setCall(run: (SdpObserver) -> Unit): Boolean {
        val l = CountDownLatch(1)
        var ok = false
        run(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) { l.countDown() }
            override fun onSetSuccess() { ok = true; l.countDown() }
            override fun onCreateFailure(error: String?) { l.countDown() }
            override fun onSetFailure(error: String?) { l.countDown() }
        })
        l.await(5, TimeUnit.SECONDS)
        return ok
    }
}
