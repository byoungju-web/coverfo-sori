package com.coverfo.sori

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 🗣️ 소리 음성 대화 서비스 (포그라운드 서비스 · 마이크).
 * · 알림창에 "소리 대화 중" 이 떠 있는 동안은 카카오맵·유튜브가 앞에 있어도 마이크로 듣고 목소리로 대답한다.
 * · coverfo 서버(/api/talk-live op:"token")에서 1회용 임시 키를 받아 OpenAI Realtime WebSocket 에 연결.
 *   마이크: PCM 16bit 24kHz 모노 → input_audio_buffer.append / 목소리: response.output_audio.delta → AudioTrack.
 * · 말하는 도중 끊기: 서버가 input_audio_buffer.speech_started 를 보내면 재생 중인 목소리를 바로 비운다.
 * · 1분마다 /api/talk-live op:"tick" 으로 다음 1분 크레딧 차감. 부족(402)하면 끊는다.
 * · 명령(run_command)·대화 보기(show_chat): coverfo 화면을 앞으로 불러온 뒤 화면(웹)에 넘겨 실행.
 */
class SoriService : Service() {

    companion object {
        @Volatile var instance: SoriService? = null
        private const val CH = "sori"
        private const val API = "https://coverfo.com/api/talk-live"
        private const val RATE = 24000
    }

    @Volatile var running = false
    @Volatile var token = ""
    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val main = Handler(Looper.getMainLooper())
    private var ws: WebSocket? = null
    private var rec: AudioRecord? = null
    private var recRate = RATE
    private var track: AudioTrack? = null
    private val playQ = LinkedBlockingQueue<ByteArray>()
    @Volatile private var playing = false
    private var bubble: Bubble? = null
    private var cost = 1
    private var tickRetried = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") {
            shutdown("대화를 마쳤어요", null, false)
            return START_NOT_STICKY
        }
        startFg()
        if (running) return START_NOT_STICKY
        instance = this
        token = intent?.getStringExtra("token") ?: ""
        running = true
        tickRetried = false
        state("connecting", null)
        Thread { connect() }.start()
        return START_NOT_STICKY
    }

    /* ── 알림 (대화 중 표시 + 끝내기) ── */
    private fun startFg() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH) == null) {
            nm.createNotificationChannel(NotificationChannel(CH, "소리 대화", NotificationManager.IMPORTANCE_LOW))
        }
        val stopPi = PendingIntent.getService(this, 1, Intent(this, SoriService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        val openPi = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java).putExtra("openChat", true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("🗣️ 소리 대화 중")
            .setContentText("말하면 들어요 · 누르면 대화 보기")
            .setOngoing(true)
            .setContentIntent(openPi)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat), "끝내기", stopPi).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(1, n)
    }

    /* ── 연결 ── */
    private fun post(body: String): Pair<Int, JSONObject?> {
        val req = Request.Builder().url(API)
            .header("Authorization", "Bearer $token")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        return http.newCall(req).execute().use { r ->
            val t = r.body?.string() ?: ""
            val j = try { JSONObject(t) } catch (e: Exception) { null }
            Pair(r.code, j)
        }
    }

    private fun connect() {
        try {
            val (code, j) = post("{\"op\":\"token\"}")
            if (code != 200 || j == null || j.optString("key").isEmpty()) {
                val msg = when (code) { 401 -> "로그인 후 사용할 수 있어요"; 402 -> "크레딧이 부족해요"; else -> "연결하지 못했어요" }
                shutdown(msg, if (code == 401 || code == 402) null else (j?.optString("error") ?: "code $code"), code == 401)
                return
            }
            cost = j.optInt("cost", 1)
            val url = j.optString("url", "wss://api.openai.com/v1/realtime?model=" + j.optString("model"))
            val req = Request.Builder().url(url).header("Authorization", "Bearer " + j.optString("key")).build()
            ws = http.newWebSocket(req, Listener())
        } catch (e: Exception) {
            shutdown("연결하지 못했어요", e.message, false)
        }
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            main.post {
                if (!running) return@post
                if (!startAudio()) {
                    shutdown("마이크를 열지 못했어요", null, false)
                    return@post
                }
                state("live", null)
                main.postDelayed(tick, 60_000)
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val ev = try { JSONObject(text) } catch (e: Exception) { return }
            when (val type = ev.optString("type")) {
                "response.output_audio.delta", "response.audio.delta" -> {
                    val d = ev.optString("delta")
                    if (d.isNotEmpty()) {
                        try { playQ.offer(Base64.decode(d, Base64.DEFAULT)) } catch (e: Exception) { }
                    }
                }
                "input_audio_buffer.speech_started" -> {
                    clearPlayback()
                    emit(text)
                }
                "response.function_call_arguments.done" -> {
                    val name = ev.optString("name")
                    if (name == "run_command" || name == "show_chat") {
                        /* coverfo 화면을 앞으로 → 화면이 명령을 실행(지도·내비 앱 열기 등). 대화는 계속 */
                        main.post { bringToFront(false) }
                        main.postDelayed({ emit(text) }, 700)
                    } else emit(text)
                }
                else -> {
                    if (type.endsWith(".delta") && type.contains("audio") && !type.contains("transcript")) return
                    emit(text)
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            shutdown("연결이 끊겼어요", t.message, false)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            shutdown("대화를 마쳤어요", null, false)
        }
    }

    /* 웹 화면이 보내는 것(function_call_output 등)을 그대로 OpenAI 로 */
    fun sendRaw(json: String) {
        try { ws?.send(json) } catch (e: Exception) { }
    }

    /* ── 1분 차감 ── */
    private val tick: Runnable = object : Runnable {
        override fun run() {
            if (!running) return
            Thread {
                try {
                    val (code, _) = post("{\"op\":\"tick\"}")
                    when (code) {
                        200 -> { tickRetried = false; main.postDelayed(this, 60_000) }
                        402 -> shutdown("크레딧이 부족해 대화를 마쳤어요", null, false)
                        401 -> {
                            if (tickRetried) shutdown("로그인이 만료되어 대화를 마쳤어요", null, true)
                            else {
                                /* 화면에 새 로그인 키를 달라고 하고 3초 뒤 다시 */
                                tickRetried = true
                                MainActivity.current?.js("window.cfLiveAppToken&&window.cfLiveAppToken()")
                                main.postDelayed(this, 3_000)
                            }
                        }
                        else -> main.postDelayed(this, 60_000)
                    }
                } catch (e: Exception) {
                    main.postDelayed(this, 20_000)
                }
            }.start()
        }
    }

    /* ── 소리 (마이크 · 스피커) ── */
    @SuppressLint("MissingPermission")
    private fun startAudio(): Boolean {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        routeOutput(am)

        /* 마이크: 통화용(에코 제거) 24kHz. 기기가 못 하면 48kHz 로 받아 반으로 줄인다 */
        for (r in intArrayOf(RATE, 48000)) {
            val min = AudioRecord.getMinBufferSize(r, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) continue
            val a = try {
                AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, r, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, r / 5 * 2))
            } catch (e: Exception) { null }
            if (a != null && a.state == AudioRecord.STATE_INITIALIZED) {
                rec = a; recRate = r; break
            }
            a?.release()
        }
        val ar = rec ?: return false
        try { if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(ar.audioSessionId)?.setEnabled(true) } catch (e: Exception) { }
        try { if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(ar.audioSessionId)?.setEnabled(true) } catch (e: Exception) { }

        val outMin = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val at = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(outMin, RATE))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = at
        at.play()
        ar.startRecording()

        Thread {
            val chunk = recRate / 10 * 2          /* 100ms */
            val buf = ByteArray(chunk)
            while (running) {
                val n = try { ar.read(buf, 0, buf.size) } catch (e: Exception) { -1 }
                if (n <= 0) { if (n < 0) break else continue }
                val pcm = if (recRate == RATE) buf.copyOf(n) else half(buf, n)
                val b64 = Base64.encodeToString(pcm, Base64.NO_WRAP)
                try { ws?.send("{\"type\":\"input_audio_buffer.append\",\"audio\":\"$b64\"}") } catch (e: Exception) { }
            }
        }.start()

        Thread {
            while (running) {
                val c = try { playQ.poll(250, TimeUnit.MILLISECONDS) } catch (e: Exception) { null }
                if (c == null) {
                    if (playing) { playing = false; emit("{\"type\":\"output_audio_buffer.stopped\"}"); bubble?.speaking(false) }
                    continue
                }
                if (!playing) { playing = true; emit("{\"type\":\"output_audio_buffer.started\"}"); bubble?.speaking(true) }
                try { track?.write(c, 0, c.size) } catch (e: Exception) { }
            }
        }.start()
        return true
    }

    /* 48kHz → 24kHz (두 개씩 평균) */
    private fun half(b: ByteArray, n: Int): ByteArray {
        val samples = n / 2
        val out = ByteArray((samples / 2) * 2)
        var o = 0
        var i = 0
        while (i + 3 < n && o + 1 < out.size) {
            val s1 = (b[i].toInt() and 0xff) or (b[i + 1].toInt() shl 8)
            val s2 = (b[i + 2].toInt() and 0xff) or (b[i + 3].toInt() shl 8)
            val m = (s1.toShort() + s2.toShort()) / 2
            out[o] = (m and 0xff).toByte()
            out[o + 1] = ((m shr 8) and 0xff).toByte()
            o += 2
            i += 4
        }
        return out
    }

    /* 이어폰·블루투스가 있으면 그쪽, 없으면 스피커로 (통화 모드는 기본이 귀 대는 스피커라서) */
    private fun routeOutput(am: AudioManager) {
        val headset = setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_USB_HEADSET, 26 /* TYPE_BLE_HEADSET */)
        if (Build.VERSION.SDK_INT >= 31) {
            val list = am.availableCommunicationDevices
            val dev = list.firstOrNull { it.type in headset } ?: list.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            if (dev != null) try { am.setCommunicationDevice(dev) } catch (e: Exception) { }
        } else {
            val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = outs.none { it.type in headset || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
        }
    }

    private fun clearPlayback() {
        playQ.clear()
        try {
            track?.pause()
            track?.flush()
            track?.play()
        } catch (e: Exception) { }
    }

    /* ── 화면 · 아이콘 ── */
    private fun emit(json: String) {
        MainActivity.current?.emit(json)
    }

    private fun state(s: String, extra: JSONObject?) {
        val j = extra ?: JSONObject()
        j.put("type", "sori.state")
        j.put("state", s)
        j.put("cost", cost)
        emit(j.toString())
    }

    fun bringToFront(openChat: Boolean) {
        try {
            val i = Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (openChat) i.putExtra("openChat", true)
            startActivity(i)
        } catch (e: Exception) { }
    }

    fun showBubble() {
        main.post {
            if (!running || bubble != null || !Settings.canDrawOverlays(this)) return@post
            bubble = Bubble(this) { bringToFront(true) }.also { it.show(); it.speaking(playing) }
        }
    }

    fun hideBubble() {
        main.post {
            bubble?.hide()
            bubble = null
        }
    }

    /* ── 끝내기 ── */
    fun shutdown(msg: String, error: String?, login: Boolean) {
        if (!running && instance == null) return
        val wasRunning = running
        running = false
        main.post {
            main.removeCallbacks(tick)
            try { ws?.close(1000, null) } catch (e: Exception) { }
            ws = null
            try { rec?.stop() } catch (e: Exception) { }
            try { rec?.release() } catch (e: Exception) { }
            rec = null
            playQ.clear()
            try { track?.stop() } catch (e: Exception) { }
            try { track?.release() } catch (e: Exception) { }
            track = null
            try {
                val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice() else {
                    @Suppress("DEPRECATION")
                    am.isSpeakerphoneOn = false
                }
                am.mode = AudioManager.MODE_NORMAL
            } catch (e: Exception) { }
            bubble?.hide()
            bubble = null
            if (wasRunning || instance != null) {
                val j = JSONObject()
                j.put("msg", msg)
                if (error != null) j.put("error", error.take(200))
                if (login) j.put("login", true)
                state("ended", j)
            }
            instance = null
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
        }
    }

    override fun onDestroy() {
        if (running) shutdown("대화를 마쳤어요", null, false)
        super.onDestroy()
    }
}
