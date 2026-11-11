package com.coverfo.sori

import android.Manifest
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
 * · v5 커넥션 허브 2.0: run_relay · relay_answer 를 이 서비스가 /api/hub 로 직접 실행 (캘린더→드라이브→요약 문서→메일·카톡).
 *   남에게 보내기 전에는 서버가 status "confirm" 을 돌려주고, 비서가 말로 묻고, 대답(relay_answer)을 다시 서버로 보낸다.
 *   끝나면 카톡 보내기는 KakaoSend (1탭 / APK판은 자동 보내기)로 폰에서 한다.
 */
class SoriService : Service(), WakeListener.Callback {

    companion object {
        @Volatile var instance: SoriService? = null
        private const val CH = "sori"
        private const val API = "https://coverfo.com/api/talk-live"
        private const val HUB = "https://coverfo.com/api/hub"
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
    @Volatile private var relayBusy = false        // v5 릴레이 실행 중 (그동안은 '말이 없어 대기로' 하지 않는다)
    @Volatile private var relayState: String? = null   // v5 보내기 확인을 기다리는 중인 서버 상태
    private val hubHttp = OkHttpClient.Builder()
        .callTimeout(170, TimeUnit.SECONDS)
        .readTimeout(170, TimeUnit.SECONDS)
        .build()

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
                keepIcon = false
                hideBubble()
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
                ensureWake()
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
        keepIcon = false
        bubble?.standby(false)
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
        wakeErrors = 0
        /* v12: '소리야' 가 들리면 뒤의 말을 기다리지 않고 바로 대화를 시작하고, coverfo 홈을 앞으로 (대화 창은 접힌 채 🟣 아이콘).
           그동안 들은 말(이름 + 이어서 한 말)은 연결되면 그대로 대화로 보낸다. 시작할 때 첫 1분 크레딧이 든다 */
        wake?.armNow()
        main.post { if (!running) bringToFront(false) }
        onCommand()
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
            /* v14: 대기가 오류로 멈추면 잠시 뒤 다시 켠다 (3초 → 6 → 12 → 24 → 48초, 이후 60초마다) */
            wakeErrors++
            val delay = minOf(60_000L, 3_000L shl minOf(wakeErrors - 1, 4))
            main.postDelayed({ ensureWake() }, delay)
        }
    }

    /* v14: '소리야' 대기가 켜져 있어야 하는데 멈춰 있으면 다시 켠다 (coverfo 화면이 보일 때 · 30초마다 확인) */
    @Volatile private var wakeErrors = 0
    private var watchOn = false
    private val watch = object : Runnable {
        override fun run() {
            ensureWake()
            if (wakeOn) main.postDelayed(this, 30_000L) else watchOn = false
        }
    }

    fun ensureWake() {
        if (wakeOn && !running && wake?.running != true) ensureModelThenListen()
        if (wakeOn && !watchOn) { watchOn = true; main.postDelayed(watch, 30_000L) }
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
            val p = prefs(this)
            /* v7: 지금 있는 나라 · 화면 언어 (화면이 CoverfoApp.setCtx 로 넣어 둔 값 — 없으면 서버가 접속 나라로) */
            val body = JSONObject().put("op", "token").put("name", wakeName(this)).put("store", BuildConfig.STORE).put("relay", 1)
                .put("country", p.getString("ctxCountry", "") ?: "").put("lang", p.getString("ctxLang", "") ?: "")
            /* v8: 최근(6시간 안) 길안내한 목적지 → "가는 길에 휴게소 있어?" 에 목적지를 붙여 검색 */
            if (System.currentTimeMillis() - p.getLong("ctxNaviAt", 0L) < 6 * 3_600_000L) body.put("navi", p.getString("ctxNavi", "") ?: "")
            /* v10: 화면 도우미가 깔려 있으면 누르기·읽기 도구를 받는다 */
            if (ScreenHelper.installed(this)) body.put("screen", 1)
            /* v20 📞 연락처 찾기 · 바로 전화 · 문자 채워서 열기 도구를 받는다 */
            body.put("phone", 1)
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
                /* v8: '소리야' 로 시작했거나 coverfo 화면이 뒤에 있으면 작은 🗣️ 아이콘을 띄운다 (누르면 대화 화면) */
                if (MainActivity.current?.resumed != true) showBubble()
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
                        /* v13: place_list(장소 목록)도 coverfo 화면에 띄우므로 앞으로
                           v21: place_action(가게 열기·길안내·예약 화면)도 — 카카오맵이 앞에 있어도 coverfo 화면이 가게를 열거나 예약 창을 띄운다 */
                        "run_command", "show_chat", "press_button", "run_routine", "open_screen", "recall", "place_list", "place_action" -> {
                            /* v17: 이 명령으로 다른 앱(내비·지도 등)이 열리면 그 앱 위에만 🗣️ 아이콘 */
                            if (name == "run_command" || name == "press_button" || name == "place_list" || name == "place_action") cmdAt = System.currentTimeMillis()
                            /* coverfo 화면을 앞으로 → 화면이 명령을 실행(지도·내비 앱 열기 등). 대화는 계속 */
                            main.post { bringToFront(false) }
                            main.postDelayed({ emit(text) }, 700)
                        }
                        /* v8 웹 검색: 앱이 직접 찾아 답한다 → 내비가 앞에 떠 있어도 coverfo 를 앞으로 가져오지 않는다. 결과는 화면에 번호 단추로 */
                        "web_search" -> webSearch(ev)
                        /* v9 이전 화면: coverfo 가 앞에 있으면 화면이 처리(결과 창 닫기 · 뒤로). 다른 앱(지도·내비)이 앞이면 coverfo 화면으로 돌아온다 (대화 창은 열지 않음) */
                        "go_back" -> {
                            if (MainActivity.current?.resumed == true) emit(text)
                            else main.post { goBackOther(ev) }
                        }
                        /* v10 🖐 화면 도우미: 다른 앱 화면 누르기 · 읽기 */
                        "tap_screen", "read_screen", "scroll_screen" -> main.post { screenTool(ev, text) }
                        "open_app" -> main.post { openApp(ev) }
                        /* v20 📞 연락처 · 전화 · 문자 */
                        "find_contact", "call_phone", "send_sms" -> main.post { phoneTool(ev) }
                        "run_relay", "relay_answer" -> main.post { relay(ev) }
                        "end_conversation" -> {
                            sendOutput(ev.optString("call_id"), JSONObject().put("ok", true), false)
                            emit("{\"type\":\"sori.log\",\"text\":\"■ 대화를 마쳤어요\"}")
                            /* "네" 같은 짧은 말이 끝날 때까지 잠깐 기다렸다가 끝낸다 */
                            main.postDelayed({ if (running) shutdown("대화를 마쳤어요", null, false) }, 1_800)
                        }
                        else -> emit(text)
                    }
                }
                /* v11: 대화 중(아이콘으로 작게 · 다른 앱을 보는 중)에 이름만 부르면("소리야") coverfo 소리 창을 앞으로.
                   대화 중에는 '소리야' 대기(폰 안 인식)가 꺼져 있어서, 대화 받아쓰기로 알아본다 */
                "conversation.item.input_audio_transcription.completed" -> {
                    val said = ev.optString("transcript")
                    lastSaid = said
                    lastSaidAt = System.currentTimeMillis()
                    if (nameOnly(said)) main.post { bringToFront(true) }
                    emit(text)
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

    /* ── v8 웹 검색 (/api/talk-live op:"search" · Brave · 무료) ── 결과를 비서에게 돌려주고 화면에도 보낸다 (sori.search) */
    private fun webSearch(ev: JSONObject) {
        val callId = ev.optString("call_id")
        val args = try { JSONObject(ev.optString("arguments", "{}")) } catch (e: Exception) { JSONObject() }
        val q = args.optString("query").trim().take(150)
        emit(JSONObject().put("type", "sori.log").put("text", "🔎 검색: $q").toString())
        Thread {
            var out: JSONObject
            try {
                val p = prefs(this)
                val body = JSONObject().put("op", "search").put("q", q).put("country", p.getString("ctxCountry", "") ?: "").toString()
                var (code, j) = post(body)
                if (code == 401) {
                    /* 화면 로그인 키(1시간)가 만료됐으면 기기 키로 한 번 더 */
                    val dev = p.getString("device", "") ?: ""
                    if (dev.isNotEmpty() && dev != token) {
                        val req = Request.Builder().url(API)
                            .header("Authorization", "Bearer $dev")
                            .post(body.toRequestBody("application/json".toMediaType()))
                            .build()
                        http.newCall(req).execute().use { r ->
                            code = r.code
                            j = try { JSONObject(r.body?.string() ?: "") } catch (e: Exception) { null }
                        }
                    }
                }
                val items = j?.optJSONArray("items") ?: JSONArray()
                out = if (code == 200 && j?.optBoolean("ok") == true) {
                    val list = JSONArray()
                    for (i in 0 until items.length()) {
                        val o = items.optJSONObject(i) ?: continue
                        list.put(JSONObject().put("n", i + 1).put("title", o.optString("title")).put("site", o.optString("site")).put("snippet", o.optString("desc")))
                    }
                    if (items.length() > 0) emit(JSONObject().put("type", "sori.search").put("q", q).put("items", items).toString())
                    JSONObject().put("ok", true).put("query", q).put("results", list)
                        .put("note", if (items.length() > 0) "The results are on the coverfo screen as numbered buttons (1~${items.length()})." else "Nothing was found.")
                } else {
                    JSONObject().put("ok", false).put("note", when (code) { 401 -> "Not logged in."; else -> j?.optString("error")?.ifBlank { null } ?: "Search failed." })
                }
            } catch (e: Exception) {
                out = JSONObject().put("ok", false).put("note", "Search failed (network).")
            }
            val result = out
            main.post { sendOutput(callId, result, true) }
        }.start()
    }

    /* v11: 받아쓴 말이 거의 이름뿐인지 ("소리야", "소리야?", "소리야 거기 있어") — 긴 명령은 그대로 대화가 처리 */
    private fun nameOnly(said: String): Boolean {
        val t = said.filter { it.isLetterOrDigit() }
        if (t.isEmpty() || t.length > 12) return false
        val names = listOf(wakeName(this)) + wakeAlts(this)
        return names.any { n ->
            val k = n.filter { it.isLetterOrDigit() }
            k.isNotEmpty() && t.startsWith(k) && t.length <= k.length + 5
        } || WakeMatch.hit(t.take(6), names) && t.length <= 8
    }

    /* ── v10 🖐 화면 도우미 (ScreenHelper → com.coverfo.soria) ── */
    /* 다른 앱이 앞에 있을 때 "이전 화면": 도우미가 켜져 있으면 그 앱 안에서 뒤로, 아니면 coverfo 화면으로 돌아온다 */
    private fun goBackOther(ev: JSONObject) {
        val callId = ev.optString("call_id")
        ScreenHelper.call(this, "back", "") { j ->
            if (j.optBoolean("ok")) {
                sendOutput(callId, JSONObject().put("ok", true).put("note", "Pressed back inside " + j.optString("app", "the app") + "."), false)
                emit(JSONObject().put("type", "sori.log").put("text", "▶ 이전 화면 (" + j.optString("app", "앱") + ")").toString())
            } else {
                bringToFront(false)
                sendOutput(callId, JSONObject().put("ok", true).put("note", "Returned from the other app to the coverfo screen."), false)
                emit(JSONObject().put("type", "sori.log").put("text", "▶ 이전 화면 (coverfo)").toString())
            }
        }
    }

    private fun screenTool(ev: JSONObject, raw: String) {
        val callId = ev.optString("call_id")
        val name = ev.optString("name")
        val args = try { JSONObject(ev.optString("arguments", "{}")) } catch (e: Exception) { JSONObject() }
        val t = if (name == "scroll_screen") (if (args.optString("direction") == "up") "up" else "down") else args.optString("text").trim()
        /* coverfo 화면이 앞이면: 누르기는 화면의 결과 단추 누르기(press_button)로, 읽기는 안내만 */
        if (MainActivity.current?.resumed == true) {
            if (name == "tap_screen") {
                emit(JSONObject(raw).put("name", "press_button").put("arguments", JSONObject().put("label", t).toString()).toString())
            } else {
                sendOutput(callId, JSONObject().put("ok", false).put("note", "The coverfo screen is in front; read_screen / scroll_screen work on other apps only."), true)
            }
            return
        }
        val op = when (name) { "tap_screen" -> "tap"; "scroll_screen" -> "scroll"; else -> "read" }
        if (op == "tap") emit(JSONObject().put("type", "sori.log").put("text", "🖐 누르기: $t").toString())
        if (op == "scroll") {
            /* v12: 넘긴 뒤 0.7초 기다려 보이는 글자를 같이 돌려준다 → 소리가 목록 이름을 말해 줄 수 있게 */
            ScreenHelper.call(this, "scroll", t) { j ->
                if (!j.optBoolean("ok")) { sendOutput(callId, j, true); return@call }
                emit(JSONObject().put("type", "sori.log").put("text", if (t == "up") "▲ 위로 넘김" else "▼ 아래로 넘김").toString())
                main.postDelayed({
                    ScreenHelper.call(this, "read", "") { r ->
                        sendOutput(callId, j.put("text", r.optString("text")), true)
                    }
                }, 700)
            }
            return
        }
        ScreenHelper.call(this, op, t) { j ->
            sendOutput(callId, j, true)
            if (op == "tap") emit(JSONObject().put("type", "sori.log").put("text", if (j.optBoolean("ok")) "▶ 누름: " + j.optString("pressed", t) else "⚠️ 누르지 못했어요: $t").toString())
            else if (j.optBoolean("ok")) emit(JSONObject().put("type", "sori.log").put("text", "👀 화면 읽음 (" + j.optString("app") + ")").toString())
        }
    }

    /* ② 앱 열기: 이 서비스가 직접 열고 결과를 비서에게 돌려준다 */
    private fun openApp(ev: JSONObject) {
        val args = try { JSONObject(ev.optString("arguments", "{}")) } catch (e: Exception) { JSONObject() }
        val name = args.optString("name")
        val r = AppLauncher.open(this, name, BuildConfig.STORE, MainActivity.current?.resumed == true)
        val ok = r.optBoolean("ok")
        if (ok) { cmdAt = System.currentTimeMillis(); if (MainActivity.current?.resumed != true) showBubble() }
        sendOutput(ev.optString("call_id"), r, !ok)          // 못 열었으면 비서가 짧게 알려 주도록
        emit(JSONObject().put("type", "sori.log").put("text", (if (ok) "▶ 앱 열기: " else "⚠️ 앱을 열지 못했어요: ") + r.optString("app", name)).toString())
    }

    /* ── v20 📞 끝까지 해 주기 1단계: 연락처 찾기 · 바로 전화 · 문자 채워서 열기 ──
       · 전화·문자는 반드시 한 번 확인: 처음 부르면 need_confirm(무엇을 물을지)을 돌려주고 화면에 확인 카드.
         그 뒤 이용자가 "응/네"라고 말했거나(받아쓰기) 카드의 [네]를 눌렀을 때만 실행한다 (같은 사람·같은 일일 때).
       · 소리 대화에는 이름과 번호 끝 4자리만 넘긴다. 기록은 이 폰의 대화 기록에만 */
    @Volatile private var lastSaid = ""
    @Volatile private var lastSaidAt = 0L
    @Volatile private var uiYesAt = 0L
    private var people: List<PhoneActs.Person> = emptyList()
    private var askKey = ""
    private var askAt = 0L
    private val YES = Regex("(응|으응|어\\s*그래|네|넵|예|그래|좋아|보내|걸어|해\\s*줘|맞아|ㅇㅇ|진행|오케이|okay|ok|yes)", RegexOption.IGNORE_CASE)
    private val NO = Regex("(아니|아냐|취소|하지\\s*마|그만|잠깐|잠시만|\\bno\\b)", RegexOption.IGNORE_CASE)

    private fun phoneTool(ev: JSONObject, tries: Int = 0) {
        val callId = ev.optString("call_id")
        val name = ev.optString("name")
        val a = try { JSONObject(ev.optString("arguments", "{}")) } catch (e: Exception) { JSONObject() }
        fun out(o: JSONObject) = sendOutput(callId, o, true)
        if (name == "find_contact") {
            val q = a.optString("name").trim()
            if (!PhoneActs.canContacts(this)) { askPerm(Manifest.permission.READ_CONTACTS); out(needPerm("contacts")); return }
            val r = PhoneActs.find(this, q)
            if (r.isNotEmpty()) people = r
            out(JSONObject().put("ok", true).put("matches", JSONArray().also { arr -> r.forEach { arr.put(JSONObject().put("name", it.name).put("last4", PhoneActs.last4(it.number))) } })
                .put("note", if (r.isEmpty()) "No contact found with that name." else if (r.size > 1) "Several people match. Ask which one (name and last 4 digits)." else "One person found."))
            relayLog("🔎 연락처: '" + q + "' " + r.size + "명")
            return
        }
        /* 받는 사람: 번호(장소 목록의 가게 번호 등) 또는 연락처 이름 */
        val to = a.optString("to").trim()
        val label = a.optString("label").trim()
        var who: PhoneActs.Person? = null
        val dg = PhoneActs.digits(to)
        if (dg.length >= 7) who = PhoneActs.Person(if (label.isNotEmpty()) label else to, dg)
        else {
            if (!PhoneActs.canContacts(this)) { askPerm(Manifest.permission.READ_CONTACTS); out(needPerm("contacts")); return }
            val t4 = a.optString("last4").filter { it.isDigit() }
            var c = people.filter { it.name == to || it.name.contains(to) || (to.isNotEmpty() && to.contains(it.name)) }
            if (c.isEmpty()) c = PhoneActs.find(this, to).also { if (it.isNotEmpty()) people = it }
            if (t4.length == 4) c = c.filter { it.number.endsWith(t4) }.ifEmpty { c }
            if (c.isEmpty()) { out(JSONObject().put("ok", false).put("note", "No contact found for '" + to + "'. If it is a shop or restaurant (e.g. the place open in KakaoMap), call place_action with action call to get its number, then call this tool again with that number as to and the name as label. Otherwise ask for the exact name as saved in the phone, or a phone number (it does not need to be saved).")); return }
            val exact = c.filter { it.name == to }
            if (c.size > 1 && exact.size != 1) {
                out(JSONObject().put("ok", false).put("choose", JSONArray().also { arr -> c.take(6).forEach { arr.put(JSONObject().put("name", it.name).put("last4", PhoneActs.last4(it.number))) } })
                    .put("note", "Several people match. Ask which one (read names with the last 4 digits), then call again with that name and last4."))
                return
            }
            who = if (exact.size == 1) exact[0] else c[0]
        }
        val p = who ?: return
        val isCall = name == "call_phone"
        val text = a.optString("text").trim().take(500)
        if (!isCall && text.isEmpty()) { out(JSONObject().put("ok", false).put("note", "Write the message text first.")); return }
        if (!isCall && Regex("(계좌|카드\\s*번호|비밀번호|송금|이체)").containsMatchIn(text)) {
            out(JSONObject().put("ok", false).put("note", "Do not put account, card, password or money transfer details in a text. Tell the user to write it themselves."))
            return
        }
        /* 문자는 내용까지 같아야 같은 일 (내용을 바꾸면 다시 확인) */
        val key = if (isCall) "call:" + p.number else "sms:" + p.number + ":" + text
        val now = System.currentTimeMillis()
        val agreed = key == askKey && now - askAt < 180_000L &&
            ((uiYesAt > askAt) || (lastSaidAt > askAt && YES.containsMatchIn(lastSaid) && !NO.containsMatchIn(lastSaid)))
        if (!a.optBoolean("confirmed") || !agreed) {
            /* "응" 받아쓰기가 도구 호출보다 조금 늦게 올 수 있다 → 잠깐 기다렸다가 한 번 더 본다 */
            if (a.optBoolean("confirmed") && key == askKey && tries < 2) { main.postDelayed({ phoneTool(ev, tries + 1) }, 800); return }
            askKey = key
            askAt = now
            val q = if (isCall) p.name + "님께 전화할까요?" else p.name + "님께 '" + text + "'라고 문자 보낼까요?"
            emit(JSONObject().put("type", "sori.confirm").put("kind", if (isCall) "call" else "sms").put("text", q).toString())
            /* 전화 권한이 아직 없으면 묻는 동안 같이 허용 창을 띄운다 (없으면 키패드에 번호만 채워서 연다) */
            if (isCall && !PhoneActs.canCall(this)) askPerm(Manifest.permission.CALL_PHONE)
            out(JSONObject().put("ok", false).put("need_confirm", true).put("ask", q).put("last4", PhoneActs.last4(p.number))
                .put("note", "Ask the user exactly this in one short sentence and wait. Only after they agree, call this tool again with confirmed true."))
            return
        }
        askKey = ""
        emit("{\"type\":\"sori.confirm\",\"done\":true}")
        if (isCall) {
            relayLog("📞 전화: " + p.name + " (…" + PhoneActs.last4(p.number) + ")")
            sendOutput(callId, JSONObject().put("ok", true).put("note", "Calling now. This conversation ends so the call can use the microphone."), false)
            /* 통화가 마이크를 쓰도록 대화를 먼저 끝내고 건다 */
            main.postDelayed({
                if (running) shutdown("📞 " + p.name + "님께 전화를 걸었어요", null, false)
                main.postDelayed({ PhoneActs.call(this, p.number) }, 400)
            }, 600)
        } else {
            val ok = PhoneActs.sms(this, p.number, text)
            if (ok) { cmdAt = System.currentTimeMillis(); if (MainActivity.current?.resumed != true) showBubble() }
            relayLog(if (ok) "💬 문자 준비: " + p.name + " (…" + PhoneActs.last4(p.number) + ") · 문자 앱에서 보내기를 눌러 주세요" else "⚠️ 문자 앱을 열지 못했어요")
            out(JSONObject().put("ok", ok).put("note", if (ok) "The message app is open with the number and text filled in. Tell the user to press 보내기 (send) there." else "Could not open the message app."))
        }
    }

    private fun needPerm(what: String) = JSONObject().put("ok", false).put("need_permission", what)
        .put("note", "The phone is asking the user to allow " + (if (what == "call") "phone calls" else "contacts") + " for coverfo 소리. Tell them to press 허용 on the screen, then ask again.")

    /* 권한은 화면(MainActivity)에서만 물을 수 있다 → coverfo 를 앞으로 가져와 묻는다 */
    private fun askPerm(perm: String) {
        try {
            startActivity(Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("askPerm", perm))
        } catch (e: Exception) { }
    }

    /** 화면의 확인 카드 [네]/[아니요] (MainActivity.soriConfirm) */
    fun uiConfirm(ok: Boolean) {
        if (!running) return
        if (ok) uiYesAt = System.currentTimeMillis() else askKey = ""
        val content = JSONArray().put(JSONObject().put("type", "input_text").put("text", if (ok) "네, 그렇게 해 주세요." else "아니요, 하지 마세요."))
        sendRaw(JSONObject().put("type", "conversation.item.create").put("item", JSONObject().put("type", "message").put("role", "user").put("content", content)).toString())
        sendRaw("{\"type\":\"response.create\"}")
        if (!ok) emit("{\"type\":\"sori.confirm\",\"done\":true}")
    }

    /* ── v5 커넥션 허브 2.0 릴레이 ── */
    private fun relay(ev: JSONObject) {
        val callId = ev.optString("call_id")
        val args = try { JSONObject(ev.optString("arguments", "{}")) } catch (e: Exception) { JSONObject() }
        val body = JSONObject()
        if (ev.optString("name") == "run_relay") {
            val goal = args.optString("goal").trim()
            if (goal.isEmpty()) {
                sendOutput(callId, JSONObject().put("status", "error").put("say", "무엇을 할지 다시 말씀해 주세요."), true)
                return
            }
            body.put("op", "run").put("goal", goal.take(1000)).put("from", "voice")
            relayLog("🔁 릴레이: $goal")
        } else {
            val st = relayState
            if (st == null) {
                sendOutput(callId, JSONObject().put("status", "error").put("say", "지금 확인할 일이 없어요."), true)
                return
            }
            val ok = args.optBoolean("approve", false)
            body.put("op", "confirm").put("state", st).put("approve", ok)
            relayLog(if (ok) "🙆 진행할게요" else "🙅 취소했어요")
        }
        relayBusy = true
        val tok = token
        Thread {
            var out: JSONObject
            var client: JSONArray? = null
            try {
                var (code, j) = hubPost(tok, body.toString())
                if (code == 401) {
                    /* 화면 로그인 키(1시간)가 만료됐으면 기기 키로 한 번 더 */
                    val dev = prefs(this).getString("device", "") ?: ""
                    if (dev.isNotEmpty() && dev != tok) {
                        val r = hubPost(dev, body.toString())
                        code = r.first
                        j = r.second
                    }
                }
                val status = j?.optString("status") ?: ""
                out = if (code == 200 && status == "confirm") {
                    relayState = j?.optString("state")
                    JSONObject().put("status", "confirm").put("question", j?.optString("say") ?: "")
                } else if (code == 200 && j != null) {
                    relayState = null
                    client = j.optJSONArray("client")
                    JSONObject().put("status", "done").put("say", j.optString("say"))
                } else {
                    relayState = null
                    val err = j?.optString("error") ?: ""
                    val msg = when (code) { 401 -> "로그인이 필요해요."; 402 -> "크레딧이 부족해요."; else -> if (err.isNotBlank()) err else "처리하지 못했어요." }
                    JSONObject().put("status", "error").put("say", msg)
                }
            } catch (e: Exception) {
                relayState = null
                out = JSONObject().put("status", "error").put("say", "연결이 불안정해 처리하지 못했어요.")
            }
            val result = out
            val actions = client
            main.post {
                relayBusy = false
                lastActive = System.currentTimeMillis()
                if (running) sendOutput(callId, result, true)
                val say = result.optString("say").ifBlank { result.optString("question") }
                if (say.isNotBlank()) relayLog((if (result.optString("status") == "confirm") "🙋 " else "■ ") + say)
                /* 카톡은 비서가 결과를 말할 시간을 조금 준 뒤에 연다 */
                if (actions != null && actions.length() > 0) main.postDelayed({ runClient(actions) }, 3_500)
            }
        }.start()
    }

    private fun hubPost(tok: String, body: String): Pair<Int, JSONObject?> {
        val req = Request.Builder().url(HUB)
            .header("Authorization", "Bearer $tok")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        return hubHttp.newCall(req).execute().use { r ->
            val t = r.body?.string() ?: ""
            val j = try { JSONObject(t) } catch (e: Exception) { null }
            Pair(r.code, j)
        }
    }

    /* 릴레이가 끝난 뒤 폰에서 할 일 (지금은 카톡 보내기) */
    private fun runClient(c: JSONArray) {
        for (i in 0 until c.length()) {
            val a = c.optJSONObject(i) ?: continue
            if (a.optString("type") != "kakao") continue
            val toA = a.optJSONArray("to")
            val to = if (toA == null) emptyList() else (0 until toA.length()).map { toA.optString(it) }
            val r = KakaoSend.send(this, to, a.optString("text"))
            relayLog(when (r) {
                "auto" -> "💬 카톡: 대화방을 찾아 보내는 중이에요"
                "noapp" -> "💬 카카오톡이 없어 공유 창을 열었어요"
                else -> "💬 카톡에서 보낼 대화방을 눌러 주세요"
            })
            if (r != "auto") break      // 1탭 보내기는 한 번만 (화면이 겹치지 않게)
        }
    }

    fun relayLog(text: String) {
        emit(JSONObject().put("type", "sori.log").put("text", text).toString())
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
            if (wakeOn && !playing && !relayBusy && System.currentTimeMillis() - lastActive > IDLE_MS) {
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

    /* v17: 🗣️ 아이콘은 소리가 명령으로 연 앱(카카오내비·카카오맵·말로 연 앱) 위에만 — 명령 뒤 20초 안에 coverfo 를 떠난 경우.
       내가 직접 연 다른 앱(구글 렌즈 등)으로 갈 때는 띄우지 않는다. coverfo 화면 안에서는 화면의 🟣 */
    @Volatile private var cmdAt = 0L

    fun showBubble() {
        main.post {
            if ((!running && !keepIcon) || bubble != null || !Settings.canDrawOverlays(this)) return@post
            if (System.currentTimeMillis() - cmdAt > 20_000L) return@post
            /* v15: 대화 중이면 누르면 대화 화면, 대기 모양이면 누르면 바로 다시 대화 */
            bubble = Bubble(this) { if (running) bringToFront(true) else talkFront() }.also {
                it.show()
                if (running) it.speaking(playing) else it.standby(true)
            }
        }
    }

    /* v15: '말이 없어서' 대화가 끝나도 아이콘은 대기 모양으로 남긴다 ("들어가"·끝내기로 끝내면 사라짐) */
    @Volatile private var keepIcon = false

    /** 대기 아이콘을 누르면: coverfo 를 열고 바로 대화 시작 (바로가기와 같은 주소 → 화면이 cfLiveTalk) */
    private fun talkFront() {
        try {
            startActivity(Intent(this, MainActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .setData(android.net.Uri.parse("https://coverfo.com/?sori=talk"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        } catch (e: Exception) { bringToFront(true) }
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
            /* v15: 말이 없어 대기로 돌아갔거나 연결이 끊긴 것이면 아이콘은 대기 모양으로 남긴다 */
            keepIcon = wakeOn && (msg.contains("대기로 돌아갔") || msg.contains("연결이 끊겼"))
            if (keepIcon && bubble != null) bubble?.standby(true) else { bubble?.hide(); bubble = null }
            val j = JSONObject()
            j.put("msg", msg)
            if (error != null) j.put("error", error.take(200))
            if (login) j.put("login", true)
            state("ended", j)
            /* 이름을 듣고 연결하다 실패했으면 모아 둔 말은 버리고 다시 대기 */
            wake?.let { it.stop(); wake = null }
            fromWake = false
            relayState = null
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
