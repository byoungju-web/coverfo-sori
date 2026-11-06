package com.coverfo.soria

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import org.json.JSONObject

/**
 * coverfo 소리 앱 → 도우미 명령 (순서 있는 방송 · 결과를 바로 돌려줌).
 * 매니페스트에서 com.coverfo.soria.CONTROL(서명 권한)을 가진 앱만 보낼 수 있게 막아 두었다 → 같은 키로 서명한 coverfo 소리만.
 *   extras: op = "tap" | "back" | "read" | "scroll" | "ping", text = 누를 글자 (scroll 이면 "down"·"up")
 *   v2: tap 은 글자가 화면에 없으면 아래로 넘기며 3번까지 더 찾는다 (잠깐 걸려서 goAsync 로 답함)
 *   결과(resultData): JSON 글자 { ok, … }
 */
class CmdReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "com.coverfo.soria.CMD") return
        val s = ScreenService.instance
        val op = intent.getStringExtra("op").orEmpty()
        if (s != null && op == "tap" && isOrderedBroadcast) {
            val text = intent.getStringExtra("text").orEmpty().take(60)
            if (!s.visible(text)) { tapSearch(s, text, goAsync()); return }
        }
        val out: JSONObject = if (s == null) {
            JSONObject().put("ok", false).put("off", true)
                .put("note", "The coverfo screen helper is installed but turned off (Settings > Accessibility > coverfo 화면 도우미).")
        } else try {
            when (op) {
                "ping" -> JSONObject().put("ok", true)
                "tap" -> s.tap(intent.getStringExtra("text").orEmpty().take(60))
                "back" -> s.back()
                "scroll" -> s.scroll(intent.getStringExtra("text").orEmpty())
                "read" -> s.read()
                else -> JSONObject().put("ok", false).put("note", "Unknown request.")
            }
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("note", "The screen helper could not do it.")
        }
        if (isOrderedBroadcast) {
            resultCode = 1
            resultData = out.toString()
        }
    }

    /* 화면에 없는 글자: 아래로 넘기고(0.7초 기다림) 다시 찾기 × 3 → 찾으면 누르고, 끝내 없으면 tap 결과(비슷한 글자 목록)를 돌려준다 */
    private fun tapSearch(s: ScreenService, text: String, pr: BroadcastReceiver.PendingResult) {
        val h = Handler(Looper.getMainLooper())
        fun done(j: JSONObject) {
            try { pr.resultCode = 1; pr.resultData = j.toString() } catch (e: Exception) { }
            pr.finish()
        }
        fun step(left: Int) {
            try {
                if (s.visible(text) || left <= 0) { done(s.tap(text)); return }
                val sc = s.scroll("down")
                if (!sc.optBoolean("ok")) { done(s.tap(text)); return }
                h.postDelayed({ step(left - 1) }, 700)
            } catch (e: Exception) {
                done(JSONObject().put("ok", false).put("note", "The screen helper could not do it."))
            }
        }
        step(3)
    }
}
