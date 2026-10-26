package com.coverfo.sori

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlin.math.max
import kotlin.math.sqrt

/**
 * ① 이름 부르면 시작 (웨이크워드) — 폰 안에서만 듣는다. 인터넷으로 소리를 보내지 않는다.
 * · Vosk(오픈소스, Apache 2.0) 한국어 소형 모델로 받아쓰기 → 그 글자 안에 이름("소리야" 등)이 있으면 깨어난다.
 * · 이름 뒤에 말이 이어지면(소리 크기로 판단) 그때만 서버(음성 대화)에 연결한다 — 이름만 잘못 들은 경우 크레딧이 나가지 않게.
 * · 연결되기 전까지 들은 말(이름 뒤 명령)은 모아 두었다가 연결되면 먼저 보낸다 → "소리야, 부산역 길안내 해 줘"를 한 번에 말해도 된다.
 */

/** 받아쓴 글자에서 이름 찾기 — 띄어쓰기·문장부호 무시, 3글자 이상 이름은 한글 자모 1개 차이까지 같은 것으로 본다 */
object WakeMatch {
    private const val BASE = 0xAC00
    private const val CHO = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"
    private const val JUNG = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ"
    private const val JONG = " ㄱㄲㄳㄴㄵㄶㄷㄹㄺㄻㄼㄽㄾㄿㅀㅁㅂㅄㅅㅆㅇㅈㅊㅋㅌㅍㅎ"

    fun norm(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    fun jamo(s: String): String {
        val sb = StringBuilder()
        for (c in s) {
            val code = c.code - BASE
            if (code in 0..11171) {
                sb.append(CHO[code / 588]).append(JUNG[(code % 588) / 28])
                val j = code % 28
                if (j > 0) sb.append(JONG[j])
            } else sb.append(c)
        }
        return sb.toString()
    }

    private fun dist(a: String, b: String): Int {
        val prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val c = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + c)
            }
            System.arraycopy(cur, 0, prev, 0, cur.size)
        }
        return prev[b.length]
    }

    /** text 안에 names 중 하나가 (거의) 들어 있으면 true */
    fun hit(text: String, names: List<String>): Boolean {
        val t = norm(text)
        if (t.isEmpty()) return false
        for (raw in names) {
            val n = norm(raw)
            if (n.isEmpty()) continue
            if (t.contains(n)) return true
            if (n.length < 3) continue                      // 2글자 이하 이름은 정확히 같을 때만 (잘못 깨는 것 방지)
            val nj = jamo(n)
            for (len in (n.length - 1)..(n.length + 1)) {
                if (len < 2 || len > t.length) continue
                for (i in 0..(t.length - len)) {
                    if (dist(jamo(t.substring(i, i + len)), nj) <= 1) return true
                }
            }
        }
        return false
    }
}

/** 한국어 음성 모델 (약 82MB) — 처음 켤 때 한 번 내려받아 앱 안에 둔다 */
object VoskStore {
    const val NAME = "vosk-model-small-ko-0.22"
    private const val URL = "https://alphacephei.com/vosk/models/$NAME.zip"

    fun dir(ctx: Context) = File(ctx.filesDir, NAME)
    fun ready(ctx: Context) = File(dir(ctx), ".ok").exists()

    /** 내려받고 풀기. progress: 0~100. 실패하면 IOException */
    fun download(ctx: Context, progress: (Int) -> Unit) {
        val tmp = File(ctx.cacheDir, "$NAME.zip")
        val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
        http.newCall(Request.Builder().url(URL).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("음성 모델을 내려받지 못했어요 (${r.code})")
            val body = r.body ?: throw IOException("음성 모델을 내려받지 못했어요")
            val total = body.contentLength()
            body.byteStream().use { inp ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var got = 0L
                    var last = -1
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        got += n
                        if (total > 0) {
                            val p = (got * 90 / total).toInt()
                            if (p != last) { last = p; progress(p) }
                        }
                    }
                }
            }
        }
        /* 임시 폴더에 풀고 → 모델 폴더(am·conf 가 들어 있는 곳)를 찾아 제자리로 */
        val work = File(ctx.filesDir, "vosk-unzip")
        work.deleteRecursively()
        work.mkdirs()
        val rootPath = work.canonicalPath + File.separator
        ZipInputStream(tmp.inputStream().buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val f = File(work, e.name)
                if (!f.canonicalPath.startsWith(rootPath)) continue          // 이상한 경로는 건너뜀
                if (e.isDirectory) f.mkdirs() else {
                    f.parentFile?.mkdirs()
                    FileOutputStream(f).use { z.copyTo(it) }
                }
            }
        }
        tmp.delete()
        progress(95)
        val found = (listOf(work) + (work.listFiles()?.filter { it.isDirectory } ?: emptyList()))
            .firstOrNull { File(it, "am").exists() || File(it, "conf").exists() }
            ?: run { work.deleteRecursively(); throw IOException("음성 모델 파일이 올바르지 않아요") }
        val target = dir(ctx)
        target.deleteRecursively()
        if (!found.renameTo(target)) {
            found.copyRecursively(target, overwrite = true)
        }
        work.deleteRecursively()
        File(target, ".ok").writeText("ok")
        progress(100)
    }
}

/** 마이크로 이름을 기다린다 (16kHz). 깨어나면 이름 뒤의 말을 모아 두었다가 takeAndStop() 으로 넘긴다 */
class WakeListener(private val ctx: Context, private val cb: Callback) {

    interface Callback {
        fun onWake()                                  // 이름이 들림 → 짧게 '띵'
        fun onCommand()                               // 이름 뒤에 말이 이어짐 → 음성 대화 연결 시작
        fun onCancel()                                // 이름만 듣고 말이 없었음
        fun onHeard(text: String, hit: Boolean)       // 시험 모드: 들린 말과 이름으로 알아들었는지
        fun onError(msg: String)
    }

    companion object {
        private const val RATE = 16000
        private const val RING = RATE * 2 * 3          // 최근 3초
        private const val PRE_MS = 1200                // 깨어날 때 앞쪽으로 포함할 소리 (이름 끝 + 바로 이어진 말)
    }

    @Volatile var names: List<String> = listOf("소리야")
    @Volatile var test = false
    @Volatile private var run = false
    private var thread: Thread? = null
    private val lock = Object()
    private val ring = ByteArray(RING)
    private var ringPos = 0
    private var ringFull = false
    private var cmd: ByteArrayOutputStream? = null
    @Volatile private var state = 0                    // 0 이름 기다림 · 1 이름 들음(말 기다림) · 2 연결 중(계속 모음)
    @Volatile private var armedAt = 0L

    val running: Boolean get() = run

    fun start() {
        if (run) return
        run = true
        thread = Thread({ loop() }, "sori-wake").also { it.start() }
    }

    fun stop() {
        run = false
    }

    /** 듣기 스레드가 마이크를 놓을 때까지 기다린다 (최대 ms) */
    fun join(ms: Long) {
        try { thread?.join(ms) } catch (e: InterruptedException) { }
    }

    /** 음성 대화가 연결되면 부른다: 모아 둔 소리(16kHz PCM)를 넘기고 듣기를 멈춘다 */
    fun takeAndStop(): ByteArray {
        synchronized(lock) {
            val b = cmd?.toByteArray() ?: ByteArray(0)
            cmd = null
            state = 0
            run = false
            return b
        }
    }

    /** 연결이 안 되었으면 다시 이름 기다리기 */
    fun cancelCommand() {
        synchronized(lock) { cmd = null; state = 0 }
    }

    private fun ringWrite(b: ByteArray, n: Int) {
        var i = 0
        while (i < n) {
            val k = minOf(n - i, RING - ringPos)
            System.arraycopy(b, i, ring, ringPos, k)
            ringPos = (ringPos + k) % RING
            if (ringPos == 0) ringFull = true
            i += k
        }
    }

    private fun ringTail(ms: Int): ByteArray {
        val want = minOf(RATE * 2 * ms / 1000, if (ringFull) RING else ringPos) and 0x7ffffffe.toInt()
        val out = ByteArray(want)
        val start = (ringPos - want + RING) % RING
        val first = minOf(want, RING - start)
        System.arraycopy(ring, start, out, 0, first)
        if (first < want) System.arraycopy(ring, 0, out, first, want - first)
        return out
    }

    private fun rms(b: ByteArray, n: Int): Double {
        var s = 0.0
        var i = 0
        var c = 0
        while (i + 1 < n) {
            val v = ((b[i].toInt() and 0xff) or (b[i + 1].toInt() shl 8)).toShort().toDouble()
            s += v * v
            c++
            i += 2
        }
        return if (c == 0) 0.0 else sqrt(s / c)
    }

    @SuppressLint("MissingPermission")
    private fun loop() {
        var model: Model? = null
        var rec: Recognizer? = null
        var ar: AudioRecord? = null
        try {
            try { LibVosk.setLogLevel(LogLevel.WARNINGS) } catch (e: Throwable) { }
            model = Model(VoskStore.dir(ctx).absolutePath)
            rec = Recognizer(model, RATE.toFloat())
            val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            ar = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, max(min, RATE))
            if (ar.state != AudioRecord.STATE_INITIALIZED) throw IOException("마이크를 열지 못했어요")
            ar.startRecording()
            val buf = ByteArray(RATE / 10 * 2)          // 100ms
            var noise = 300.0
            var speech = 0
            while (run) {
                val n = ar.read(buf, 0, buf.size)
                if (n <= 0) { if (n < 0) break else continue }
                val level = rms(buf, n)
                val now = System.currentTimeMillis()
                synchronized(lock) {
                    if (state == 0) ringWrite(buf, n) else cmd?.write(buf, 0, n)
                }
                when (state) {
                    0 -> {
                        if (level < noise * 1.5) noise = noise * 0.95 + level * 0.05      // 조용할 때 바닥 소음을 천천히 따라감
                        val fin = rec.acceptWaveForm(buf, n)
                        val text = try {
                            if (fin) JSONObject(rec.getResult()).optString("text") else JSONObject(rec.getPartialResult()).optString("partial")
                        } catch (e: Exception) { "" }
                        if (text.isBlank()) continue
                        val hit = WakeMatch.hit(text, names)
                        if (test) {
                            if (fin) cb.onHeard(text, hit)
                            continue
                        }
                        if (hit) {
                            synchronized(lock) {
                                cmd = ByteArrayOutputStream().also { it.write(ringTail(PRE_MS)) }
                                state = 1
                            }
                            rec.reset()
                            armedAt = now
                            speech = 0
                            cb.onWake()
                        }
                    }
                    1 -> {
                        /* '띵' 소리가 다시 들어오는 처음 0.35초는 빼고, 말소리가 0.3초 이상 이어지면 연결 */
                        if (now - armedAt > 350 && level > max(noise * 2.5, 450.0)) speech++
                        if (speech >= 3) {
                            state = 2
                            cb.onCommand()
                        } else if (now - armedAt > 5000) {
                            synchronized(lock) { cmd = null; state = 0 }
                            cb.onCancel()
                        }
                    }
                    else -> {
                        if (now - armedAt > 20000) {                     // 20초 안에 연결이 안 되면 포기
                            synchronized(lock) { cmd = null; state = 0 }
                            cb.onCancel()
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            if (run) cb.onError(e.message ?: e.javaClass.simpleName)
        } finally {
            try { ar?.stop() } catch (e: Exception) { }
            try { ar?.release() } catch (e: Exception) { }
            try { rec?.close() } catch (e: Throwable) { }
            try { model?.close() } catch (e: Throwable) { }
            run = false
        }
    }
}
