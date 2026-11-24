package com.coverfo.sori

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * v16 🎙 대화 받아쓰기를 폰 기본 음성 인식(구글)으로 — coverfo 웹(크롬)의 🎙 마이크와 같은 엔진.
 * · 한 마디가 끝날 때마다 글자로 받아 → SoriService 가 그 글자를 음성 대화(OpenAI)에 '글'로 넘기고 목소리로 대답을 듣는다.
 * · 소리가 말하는 동안은 듣기를 잠깐 멈춘다(hold) — 스피커 소리를 내 말로 받아쓰지 않게.
 * · 이 폰에서 쓸 수 없으면(음성 인식 앱 없음 · 권한 · 계속 오류) onFatal → 예전 방식(소리를 그대로 보내기)으로 돌아간다.
 * · 모든 호출은 메인 스레드에서.
 */
class GoogleStt(private val ctx: Context, private val lang: String, private val cb: Cb) : RecognitionListener {

    interface Cb {
        fun onBegin()                 // 말하기 시작
        fun onText(text: String)      // 한 마디 받아쓰기 끝
        fun onFatal(why: String)      // 이 폰에서는 쓸 수 없음 → 예전 방식으로
    }

    companion object {
        fun available(ctx: Context): Boolean = try { SpeechRecognizer.isRecognitionAvailable(ctx) } catch (e: Exception) { false }

        /** 화면 언어(ko·en…) → 받아쓰기 언어 (ko-KR …) */
        fun langTag(code: String): String {
            val c = code.trim()
            if (c.contains('-')) return c
            return when (c.lowercase()) {
                "", "ko" -> "ko-KR"
                "en" -> "en-US"
                "ja" -> "ja-JP"
                "zh" -> "zh-CN"
                "th" -> "th-TH"
                "vi" -> "vi-VN"
                else -> c
            }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var sr: SpeechRecognizer? = null
    private var on = false
    private var held = false
    private var listening = false
    private var errs = 0
    private var gotAny = false

    /** h: 처음부터 멈춘 채로 시작 (소리가 말하는 중 등) */
    fun start(h: Boolean) {
        if (on) return
        on = true
        held = h
        create()
        if (!h) next(0)
    }

    fun stop() {
        on = false
        listening = false
        main.removeCallbacks(go)
        try { sr?.cancel() } catch (e: Exception) { }
        try { sr?.destroy() } catch (e: Exception) { }
        sr = null
        BeepMute.release(300)   /* v41 */
    }

    /** true: 잠깐 멈춤(소리가 말하는 중) · false: 다시 듣기 */
    fun hold(h: Boolean) {
        if (!on || held == h) return
        held = h
        if (h) {
            main.removeCallbacks(go)
            if (listening) {
                listening = false
                BeepMute.mute(ctx, 800)   /* v41 멈출 때 나는 틱 소리도 */
                try { sr?.cancel() } catch (e: Exception) { }
                BeepMute.release(400)
            }
        } else next(350)
    }

    private fun create() {
        try { sr?.destroy() } catch (e: Exception) { }
        sr = try { SpeechRecognizer.createSpeechRecognizer(ctx).also { it.setRecognitionListener(this) } } catch (e: Exception) { null }
    }

    private fun next(ms: Long) {
        main.removeCallbacks(go)
        main.postDelayed(go, ms)
    }

    private val go = Runnable {
        if (!on || held || listening) return@Runnable
        val s = sr ?: run { cb.onFatal("no recognizer"); return@Runnable }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang)   /* v38 고른 언어로만 받아쓰기 */
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
        BeepMute.mute(ctx, 2500)   /* v41 🔇 듣기 시작 '틱' 소리 끄기 (준비되면 곧 되돌림) */
        try {
            s.startListening(i)
            listening = true
        } catch (e: Exception) {
            BeepMute.release(0)
            fail("start")
        }
    }

    private fun fail(why: String) {
        errs++
        if (errs >= 4 && !gotAny) { on = false; cb.onFatal(why); return }
        create()
        next(minOf(3000L, 300L * errs))
    }

    override fun onResults(results: Bundle?) {
        listening = false
        BeepMute.release(350)   /* v41 */
        errs = 0
        gotAny = true
        val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
        if (t.isNotEmpty() && !held && on) cb.onText(t)
        if (on) next(120)
    }

    override fun onError(error: Int) {
        listening = false
        BeepMute.release(350)   /* v41 */
        if (!on || held) return                       // 멈추는 중에 오는 오류는 무시
        when (error) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> { errs = 0; next(120) }   // 말이 없었음 → 다시 듣기
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> { on = false; cb.onFatal("permission") }
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> { create(); next(400) }
            12, 13 -> { on = false; cb.onFatal("language $error") }   // ERROR_LANGUAGE_NOT_SUPPORTED · ERROR_LANGUAGE_UNAVAILABLE
            else -> fail("error $error")
        }
    }

    override fun onBeginningOfSpeech() { if (on && !held) cb.onBegin() }
    override fun onReadyForSpeech(params: Bundle?) { BeepMute.release(400) }   /* v41 시작 틱이 지나간 뒤 되돌림 */
    override fun onRmsChanged(rmsdB: Float) { }
    override fun onBufferReceived(buffer: ByteArray?) { }
    override fun onEndOfSpeech() { BeepMute.mute(ctx, 2500) }   /* v41 🔇 말이 끝날 때 '틱' 소리 끄기 (결과가 오면 되돌림) */
    override fun onPartialResults(partialResults: Bundle?) { }
    override fun onEvent(eventType: Int, params: Bundle?) { }
}
