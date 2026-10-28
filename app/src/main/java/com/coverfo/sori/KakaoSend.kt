package com.coverfo.sori

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 💬 카톡으로 보내기 (커넥션 허브 2.0 릴레이의 마지막 단계).
 * 카카오톡은 개인 계정으로 다른 사람에게 자동 발송하는 공식 API 가 없어서 폰에서 보낸다.
 * · 1탭 보내기 (모든 버전): 카톡 '공유' 화면을 내용이 채워진 채로 연다 → 이용자가 대화방만 누르면 끝.
 * · 자동 보내기 (APK판 full 만): 이용자가 접근성(KakaoAutoService)을 켜 두었으면 그 서비스가 이름으로 대화방을 찾아 대신 누른다.
 *   스토어판(store)에는 그 서비스가 아예 들어 있지 않다 (구글 플레이 접근성 정책).
 */
object KakaoSend {
    const val PKG = "com.kakao.talk"
    private const val AUTO_CLASS = "KakaoAutoService"
    private const val JOB_MS = 25_000L

    class Job(val to: String, val text: String)

    private val queue = ConcurrentLinkedQueue<Job>()
    private val main = Handler(Looper.getMainLooper())

    /* 자동 보내기 서비스가 읽는 지금 할 일 */
    @Volatile var current: Job? = null
    @Volatile var deadline = 0L
    @Volatile var phase = 0

    fun installed(ctx: Context): Boolean = ctx.packageManager.getLaunchIntentForPackage(PKG) != null

    /** 이 설치 파일에 자동 보내기가 들어 있는지.
     *  v5.1: 꺼 둔다 — 접근성 서비스를 넣은 APK 는 Google Play 프로텍트가 설치를 막아서(2026-10-28) 모든 버전이 1탭 보내기로 보낸다. */
    fun autoAvailable(): Boolean = false

    /** 이용자가 설정 → 접근성에서 'coverfo 소리 카톡 자동 보내기'를 켰는지 */
    fun autoEnabled(ctx: Context): Boolean {
        if (!autoAvailable()) return false
        val s = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return s.split(':').any { it.startsWith(ctx.packageName + "/") && it.endsWith(AUTO_CLASS) }
    }

    /**
     * 보내기. 돌려주는 값: "auto"(자동으로 대화방을 찾는 중) · "share"(대화방을 눌러 주세요) · "noapp"(카톡 없음 → 일반 공유 창)
     */
    fun send(ctx: Context, to: List<String>, text: String): String {
        val t = text.take(1500)
        if (t.isBlank()) return "share"
        if (!installed(ctx)) {
            /* 카톡이 없으면 일반 공유 창 (문자·메일 등으로라도 보낼 수 있게) */
            try {
                val i = Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t), "보내기")
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(i)
            } catch (e: Exception) { }
            return "noapp"
        }
        val names = to.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(10)
        if (names.isNotEmpty() && autoEnabled(ctx)) {
            names.forEach { queue.add(Job(it, t)) }
            if (current == null) next(ctx)
            return "auto"
        }
        share(ctx, t)
        return "share"
    }

    private fun share(ctx: Context, text: String): Boolean = try {
        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).setPackage(PKG)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        ctx.startActivity(i)
        true
    } catch (e: Exception) { false }

    private fun next(ctx: Context) {
        val j = queue.poll()
        current = j
        phase = 0
        if (j == null) return
        deadline = System.currentTimeMillis() + JOB_MS
        val app = ctx.applicationContext
        if (!share(app, j.text)) finished(app, false)
        /* 시간 안에 못 끝내면 공유 화면을 그대로 두고(직접 고르기) 다음으로 */
        main.postDelayed({ if (current === j) finished(app, false) }, JOB_MS + 500)
    }

    /** 자동 보내기 서비스가 한 명을 끝냈을 때(또는 못 찾았을 때) 부른다 */
    fun finished(ctx: Context, ok: Boolean) {
        val j = current ?: return
        current = null
        phase = 0
        val app = ctx.applicationContext
        SoriService.instance?.relayLog(if (ok) "💬 카톡 보냄 → " + j.to else "💬 '" + j.to + "' 대화방을 못 찾았어요 · 카톡 화면에서 직접 골라 주세요")
        if (!ok) {
            /* 못 찾았으면 남은 사람도 직접 고르게: 대기열을 비운다 (화면이 겹치지 않게) */
            queue.clear()
            return
        }
        main.postDelayed({ if (current == null) next(app) }, 1_500)
    }
}
