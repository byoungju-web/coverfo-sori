package com.coverfo.sori

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import kotlin.math.log10

/**
 * v43 🗣 말로 끊기 — 폰 음성 인식(구글) 대화에서 소리가 말하는(대답을 만드는) 동안에만 마이크 크기를 폰 안에서 잰다.
 * · 내 말소리가 한동안(약 0.25초) 이어지면 onHit → 소리 대답을 멈추고 바로 다시 듣기.
 * · 통화용 마이크(VOICE_COMMUNICATION) + 에코 제거 → 스피커로 나오는 소리 목소리는 덜 잡힌다.
 *   처음 0.4초 동안 들리는 크기(소리 목소리가 새어 들어오는 정도)를 기준으로 삼고, 그보다 확실히 클 때만 내 말로 본다.
 * · 소리를 서버로 보내지 않는다 (크기만 잼 · 요금 없음). 설정 bargeVoice=false 면 쓰지 않음 (캐릭터·아이콘 누르기만).
 */
class VoiceBarge(private val ctx: Context, private val onHit: () -> Unit) {
    @Volatile private var on = false

    fun start() {
        if (on) return
        on = true
        Thread { run() }.start()
    }

    fun stop() { on = false }

    @SuppressLint("MissingPermission")
    private fun run() {
        val rate = 16000
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) { on = false; return }
        val ar = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate / 5 * 2))
        } catch (e: Exception) { null }
        if (ar == null || ar.state != AudioRecord.STATE_INITIALIZED) {
            try { ar?.release() } catch (e: Exception) { }
            on = false
            return
        }
        var aec: AcousticEchoCanceler? = null
        var ns: NoiseSuppressor? = null
        try { if (AcousticEchoCanceler.isAvailable()) aec = AcousticEchoCanceler.create(ar.audioSessionId)?.also { it.enabled = true } } catch (e: Exception) { }
        try { if (NoiseSuppressor.isAvailable()) ns = NoiseSuppressor.create(ar.audioSessionId)?.also { it.enabled = true } } catch (e: Exception) { }
        try { ar.startRecording() } catch (e: Exception) { on = false }

        val frame = ShortArray(rate / 50)          /* 20ms */
        val t0 = System.currentTimeMillis()
        val base = ArrayList<Double>()
        var floor = Double.NaN
        val win = BooleanArray(12)                 /* 최근 0.24초 */
        var wi = 0
        var hits = 0
        var hit = false
        while (on) {
            val n = try { ar.read(frame, 0, frame.size) } catch (e: Exception) { -1 }
            if (n < 0) break
            if (n == 0) continue
            var s = 0.0
            for (i in 0 until n) { val v = frame[i] / 32768.0; s += v * v }
            val db = 10.0 * log10(s / n + 1e-12)
            if (System.currentTimeMillis() - t0 < 400) { base.add(db); continue }
            if (floor.isNaN()) { base.sort(); floor = if (base.isEmpty()) -70.0 else base[base.size / 2] }
            val loud = db > maxOf(floor + 14.0, -40.0)
            if (win[wi]) hits--
            win[wi] = loud
            if (loud) hits++
            wi = (wi + 1) % win.size
            if (hits >= 9) { hit = true; break }
        }
        on = false
        try { ar.stop() } catch (e: Exception) { }
        try { ar.release() } catch (e: Exception) { }
        try { aec?.release() } catch (e: Exception) { }
        try { ns?.release() } catch (e: Exception) { }
        if (hit) Handler(Looper.getMainLooper()).post { onHit() }
    }
}
