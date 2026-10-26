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
import android.media.ToneGenerator
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
import org.json.JSONArray
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
 * · 명령(run_command)·대화 보기(show_chat)·저장루틴(run_routine): coverfo 화면을 앞으로 불러온 뒤 화면(웹)에 넘겨 실행.
 * · v4 ① 이름 부르기 대기(WakeListener): 대화가 없을 때도 "'소리야' 하고 부르면 대답해요" 알림과 함께 폰 안에서만 이름을 기다린다.
 *   이름 + 말이 들리면 그때 연결 → 들은 말(이름 뒤 명령)을 먼저 보내고 대화. 20초 동안 아무 말이 없으면 대화를 끝내고 다시 대기.
 * · v4 ② 앱 열기(open_app)·대화 끝내기(end_conversation)는 이 서비스가 직접 처리.
 */
class SoriService : Service(), WakeListener.Callback {

    companion object {
        @Volatile var instance: SoriService? = null
        private const val CH = "sori"
        private const val API = "https://coverfo.com/api/talk-live"
        private const val RATE = 24000
        const val DEFAULT_NAME = "소리야"
        private const val IDLE_MS = 20_000L

        fun prefs(ctx: Context) = ctx.getSharedPreferences("sori", Context.MODE_PRIVATE)
        fun wakeName(ctx: Context): String = prefs(ctx).getString("wakeName", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_NAME
        fun wakeAlts(ctx: Context): List<String> = try {
            val a = JSONArray(prefs(ctx).getString("wakeAlts", "[]"))
            (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
        } catch (e: Exception) { emptyList() }
    }

    @Volatile var running = false          // 음성 대화(연결) 중
    @Volatile var token = ""
    @Volatile var wakeOn = false           // 이름 부르기 대기 켜짐
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
    private var wake: WakeListener? = null
    @Volatile private var fromWake = false
    @Volatile private var lastActive = 0L
    @Volatile private var downloading = false
    private var wakeTest = false
    private var fgStarted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        when (intent?.action) {
            "stop" -> {
                if (running) shutdown("대화를 마쳤어요", null, false) else if (!wakeOn) stopAll()
                return START_NOT_STICKY
            }
            "wake_off" -> {
                wakeOn = false
                prefs(this).edit().putBoolean("wakeOn", false).apply()
                stopWake()
                wakeEmit("off", null)
                if (!running) stopAll() else startFg()
                return START_NOT_STICKY
            }
            "wake_on" -> {
                wakeOn = true
                prefs(this).edit().putBoolean("wakeOn", true).apply()
                startFg(true)
                if (!running) ensureModelThenListen()
                return START_NOT_STICKY
            }
            "wake_test" -> {
                wakeTest = intent?.getBooleanExtra("on", false) == true
                wake?.test = wakeTest
                return START_NOT_STICKY
            }
            "wake_names" -> {
                wake?.names = listOf(wakeName(this)) + wakeAlts(this)
                startFg()
                return START_NOT_STICKY
            }
        }
        /* 화면의 '대화 시작' (로그인 키를 받아서) */
        startFg(true)
        if (running) return START_NOT_STICKY
        token = intent?.getStringExtra("token") ?: ""
        fromWake = false
        beginLive()
        return START_NOT_STICKY
    }

    private fun beginLive() {
        running = true
        tickRetried = false
        lastActive = System.currentTimeMillis()
        startFg()
        state("connecting", null)
        Thread { connect() }.start()
    }

    /* ── 알림 (대화 중 / 이름 기다리는 중 + 끝내기) ── */
    /* force: 화면(앱)이 서비스를 시작했을 때 — 그때만 startForeground. 나머지는 알림 내용만 바꾼다 */
    private fun startFg(force: Boolean = false) {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH) == null) {
            nm.createNotificationChannel(NotificationChannel(CH, "소리 대화", NotificationManager.IMPORTANCE_LOW))
        }
        val openPi = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java).putExtra("openChat", true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_stat)
            .setOngoing(true)
            .setContentIntent(openPi)
        if (running) {
            val stopPi = PendingIntent.getService(this, 1, Intent(this, SoriService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
            b.setContentTitle("🗣️ 소리 대화 중")
                .setContentText(if (wakeOn) "말하면 들어요 · 20초 동안 말이 없으면 대기로 돌아가요" else "말하면 들어요 · 누르면 대화 보기")
                .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat), "끝내기", stopPi).build())
        } else {
            val offPi = PendingIntent.getService(this, 3, Intent(this, SoriService::class.java).setAction("wake_off"), PendingIntent.FLAG_IMMUTABLE)
            b.setContentTitle(if (downloading) "🗣️ 음성 인식 파일 내려받는 중…" else "🗣️ '" + wakeName(this) + "' 하고 부르면 대답해요")
                .setContentText("폰 안에서만 이름을 기다려요 · 이름 뒤의 말만 대화로 보내요")
                .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat), "대기 끄기", offPi).build())
        }
        val n = b.build()
        /* 처음 한 번만 포그라운드로 시작하고, 그 뒤는 알림 내용만 바꾼다 */
        if (fgStarted && !force) {
            try { nm.notify(1, n) } catch (e: Exception) { }
            return
        }
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(1, n)
        fgStarted = true
    }

    /* ── ① 이름 부르기 대기 ── */
    private fun ensureModelThenListen() {
        if (VoskStore.ready(this)) { startWake(); return }
        if (downloading) return
        downloading = true
        startFg()
        Thread {
            try {
                VoskStore.download(this) { p ->
                    val j = JSONObject().put("p", p)
                    wakeEmit("download", j)
                }
                downloading = false
                main.post { if (wakeOn && !running) { startFg(); startWake() } }
            } catch (e: Exception) {
                downloading = false
                main.post {
                    wakeOn = false
                    prefs(this).edit().putBoolean("wakeOn", false).apply()
                    wakeEmit("error", JSONObject().put("msg", (e.message ?: "음성 인식 파일을 내려받지 못했어요") + " · 와이파이에서 다시 켜 주세요"))
                    if (!running) stopAll()
                }
            }
        }.start()
    }

    private fun startWake() {
        if (!wakeOn || running || wake?.running == true) return
        val w = WakeListener(this, this)
        w.names = listOf(wakeName(this)) + wakeAlts(this)
        w.test = wakeTest
        wake = w
        w.start()
        startFg()
        wakeEmit("listening", null)
    }

    private fun stopWake() {
        val w = wake ?: return
        wake = null
        w.stop()
    }

    override fun onWake() {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 60)
            tg.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
            main.postDelayed({ try { tg.release() } catch (e: Exception) { } }, 400)
        } catch (e: Exception) { }
        wakeEmit("heard", null)
    }

    override fun onCommand() {
        main.post {
            if (running) return@post
            val dev = prefs(this).getString("device", "") ?: ""
            if (dev.isEmpty()) {
                wake?.cancelCommand()
                wakeEmit("error", JSONObject().put("msg", "coverfo 소리 앱을 한 번 열어 로그인 상태를 확인해 주세요"))
                return@post
            }
            token = dev
            fromWake = true
            beginLive()
        }
    }

    override fun onCancel() {
        wakeEmit("listening", null)
    }

    override fun onHeard(text: String, hit: Boolean) {
        wakeEmit("test", JSONObject().put("text", text).put("hit", hit))
    }

    override fun onError(msg: String) {
        main.post {
            wake = null
            wakeEmit("error", JSONObject().put("msg", msg))
        }
    }

    private fun wakeEmit(s: String, extra: JSONObject?) {
        val j = extra ?: JSONObject()
        j.put("type", "sori.wake")
        j.put("state", s)
        emit(j.toString())
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
            val body = JSONObject().put("op", "token").put("name", wakeName(this)).put("store", BuildConfig.STORE)
            val (code, j) = post(body.toString())
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
            /* 이름을 듣고 연결했으면: 대기용 마이크를 놓고, 그동안 들은 말(16kHz)을 24kHz 로 바꿔 먼저 보낸다 */
            val w = wake
            if (w != null) {
                val pre = w.takeAndStop()
                w.join(800)
                wake = null
                if (fromWake && pre.isNotEmpty()) {
                    val pcm = up16to24(pre)
                    var i = 0
                    val step = RATE / 10 * 2
                    while (i < pcm.size) {
                        val e = minOf(pcm.size, i + step)
                        val b64 = Base64.encodeToString(pcm.copyOfRange(i, e), Base64.NO_WRAP)
                        try { webSocket.send("{\"type\":\"input_audio_buffer.append\",\"audio\":\"$b64\"}") } catch (x: Exception) { }
                        i = e
                    }
                }
            }
            main.post {
                if (!running) return@post
                if (!startAudio()) {
                    shutdown("마이크를 열지 못했어요", null, false)
                    return@post
                }
                state("live", null)
                lastActive = System.currentTimeMillis()
                main.postDelayed(tick, 60_000)
                main.postDelayed(idle, 1_000)
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val ev = try { JSONObject(text) } catch (e: Exception) { return }
            when (val type = ev.optString("type")) {
                "response.output_audio.delta", "response.audio.delta" -> {
                    lastActive = System.currentTimeMillis()
                    val d = ev.optString("delta")
                    if (d.isNotEmpty()) {
                        try { playQ.offer(Base64.decode(d, Base64.DEFAULT)) } catch (e: Exception) { }
                    }
                }
                "input_audio_buffer.speech_started" -> {
                    lastActive = System.currentTimeMillis()
                    clearPlayback()
                    emit(text)
                }
                "response.function_call_arguments.done" -> {
                    lastActive = System.currentTimeMillis()
                    val name = ev.optString("name")
                    when (name) {
                        "run_command", "show_chat", "press_button", "run_routine" -> {
                            /* coverfo 화면을 앞으로 → 화면이 명령을 실행(지도·내비 앱 열기 등). 대화는 계속 */
                            main.post { bringToFront(false) }
                            main.postDelayed({ emit(text) }, 700)
                        }
                        "open_app" -> main.post { openApp(ev) }
                        "end_conversation" -> {
                            sendOutput(ev.optString("call_id"), JSONObject().put("ok", true), false)
                            emit("{\"type\":\"sori.log\",\"text\":\"■ 대화를 마쳤어요\"}")
                            /* "네" 같은 짧은 말이 끝날 때까지 잠깐 기다렸다가 끝낸다 */
                            main.postDelayed({ if (running) shutdown("대화를 마쳤어요", null, false) }, 1_800)
                        }
                        else -> emit(text)
                    }
                }
                else -> {
                    if (type.startsWith("response.")) lastActive = System.currentTimeMillis()
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

    /* ② 앱 열기: 이 서비스가 직접 열고 결과를 비서에게 돌려준다 */
    private fun openApp(ev: JSONObject) {
        val args = try { JSONObject(ev.optString("arguments", "{}")) } catch (e: Exception) { JSONObject() }
        val name = args.optString("name")
        val r = AppLauncher.open(this, name, BuildConfig.STORE, MainActivity.current?.resumed == true)
        val ok = r.optBoolean("ok")
        sendOutput(ev.optString("call_id"), r, !ok)          // 못 열었으면 비서가 짧게 알려 주도록
        emit(JSONObject().put("type", "sori.log").put("text", (if (ok) "▶ 앱 열기: " else "⚠️ 앱을 열지 못했어요: ") + r.optString("app", name)).toString())
    }

    private fun sendOutput(callId: String, out: JSONObject, respond: Boolean) {
        if (callId.isEmpty()) return
        val item = JSONObject().put("type", "function_call_output").put("call_id", callId).put("output", out.toString())
        sendRaw(JSONObject().put("type", "conversation.item.create").put("item", item).toString())
        if (respond) sendRaw("{\"type\":\"response.create\"}")
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

    /* ── 대기 모드일 때: 20초 동안 아무 말이 없으면 대화를 끝내고 다시 이름 기다리기 (크레딧 절약) ── */
    private val idle: Runnable = object : Runnable {
        override fun run() {
            if (!running) return
            if (wakeOn && !playing && System.currentTimeMillis() - lastActive > IDLE_MS) {
                shutdown("말씀이 없어 대기로 돌아갔어요", null, false)
                return
            }
            main.postDelayed(this, 1_000)
        }
    }

    /* ── 소리 (마이크 · 스피커) ── */
    @SuppressLint("MissingPermission")
    private fun startAudio(): Boolean {
        /* 화면에서 '대화 시작'을 눌렀을 때 이름 대기가 켜져 있으면, 대기용 마이크를 먼저 놓는다 */
        wake?.let { it.stop(); it.join(800); wake = null }
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
                    if (playing) { playing = false; lastActive = System.currentTimeMillis(); emit("{\"type\":\"output_audio_buffer.stopped\"}"); bubble?.speaking(false) }
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

    /* 16kHz → 24kHz (직선 보간, 2개 → 3개) — 이름 뒤에 들은 말을 음성 대화 형식으로 */
    private fun up16to24(b: ByteArray): ByteArray {
        val n = b.size / 2
        if (n < 2) return ByteArray(0)
        val outN = n * 3 / 2
        val out = ByteArray(outN * 2)
        fun s(i: Int): Int = ((b[i * 2].toInt() and 0xff) or (b[i * 2 + 1].toInt() shl 8)).toShort().toInt()
        for (k in 0 until outN) {
            val pos = k * 2.0 / 3.0
            val i = pos.toInt().coerceAtMost(n - 2)
            val f = pos - i
            val v = (s(i) * (1 - f) + s(i + 1) * f).toInt().coerceIn(-32768, 32767)
            out[k * 2] = (v and 0xff).toByte()
            out[k * 2 + 1] = ((v shr 8) and 0xff).toByte()
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

    /* ── 대화 끝내기 (대기가 켜져 있으면 다시 이름 기다리기) ── */
    fun shutdown(msg: String, error: String?, login: Boolean) {
        if (!running) return
        running = false
        main.post {
            main.removeCallbacks(tick)
            main.removeCallbacks(idle)
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
            val j = JSONObject()
            j.put("msg", msg)
            if (error != null) j.put("error", error.take(200))
            if (login) j.put("login", true)
            state("ended", j)
            /* 이름을 듣고 연결하다 실패했으면 모아 둔 말은 버리고 다시 대기 */
            wake?.let { it.stop(); wake = null }
            fromWake = false
            if (wakeOn) {
                startFg()
                main.postDelayed({ if (wakeOn && !running) ensureModelThenListen() }, 600)
            } else stopAll()
        }
    }

    private fun stopAll() {
        stopWake()
        instance = null
        fgStarted = false
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        if (running) {
            wakeOn = false
            shutdown("대화를 마쳤어요", null, false)
        }
        stopWake()
        if (instance === this) instance = null
        super.onDestroy()
    }
}
