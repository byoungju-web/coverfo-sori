package com.coverfo.sori

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/**
 * v41 🔇 폰 음성 인식(구글)이 듣기 시작·끝날 때 내는 "틱" 소리를 잠깐 끈다.
 * · 듣기를 시작하기 직전 ~ 듣기 준비가 끝난 직후, 말이 끝난 순간 ~ 결과가 온 직후 — 이 짧은 동안만
 *   미디어·알림·시스템 소리를 음소거(ADJUST_MUTE)했다가 바로 되돌린다.
 * · 소리(대답 목소리)는 통화용 소리(USAGE_VOICE_COMMUNICATION)라 이 음소거와 상관없이 들린다.
 * · 이용자가 원래 꺼 둔 소리는 건드리지 않는다 (우리가 끈 것만 다시 켬). 끈 채로 남지 않게 최대 4초 뒤에는 꼭 되돌린다.
 * · 모든 호출은 메인 스레드에서 처리.
 */
object BeepMute {
    private val STREAMS = intArrayOf(AudioManager.STREAM_MUSIC, AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_SYSTEM)
    private const val MAX_MS = 4000L
    private val main = Handler(Looper.getMainLooper())
    private val mine = HashSet<Int>()
    private var am: AudioManager? = null
    private var mutedAt = 0L
    private val undo = Runnable { restoreNow() }

    /** 지금 끄고, holdMs 뒤에 저절로 되돌림 (그 사이 release 를 부르면 더 일찍) */
    fun mute(ctx: Context, holdMs: Long) {
        main.post {
            try {
                val a = am ?: (ctx.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager).also { am = it }
                if (mine.isEmpty()) mutedAt = System.currentTimeMillis()
                for (s in STREAMS) {
                    if (mine.contains(s)) continue
                    try {
                        if (a.isStreamMute(s)) continue          // 이용자가 이미 꺼 둔 소리
                        a.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, 0)
                        mine.add(s)
                    } catch (e: Exception) { }                    // 방해 금지 모드 등에서 못 바꾸는 소리는 건너뜀
                }
                val left = MAX_MS - (System.currentTimeMillis() - mutedAt)
                main.removeCallbacks(undo)
                main.postDelayed(undo, maxOf(0L, minOf(holdMs.coerceIn(100L, MAX_MS), left)))
            } catch (e: Exception) { }
        }
    }

    /** afterMs 뒤에 되돌림 (0 이면 바로) */
    fun release(afterMs: Long) {
        main.post {
            if (mine.isEmpty()) return@post
            main.removeCallbacks(undo)
            if (afterMs <= 0) restoreNow() else main.postDelayed(undo, afterMs.coerceAtMost(MAX_MS))
        }
    }

    private fun restoreNow() {
        main.removeCallbacks(undo)
        val a = am ?: return
        for (s in mine) { try { a.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, 0) } catch (e: Exception) { } }
        mine.clear()
    }
}
