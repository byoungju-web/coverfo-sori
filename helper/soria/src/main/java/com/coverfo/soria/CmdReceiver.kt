package com.coverfo.soria

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONObject

/**
 * coverfo 소리 앱 → 도우미 명령 (순서 있는 방송 · 결과를 바로 돌려줌).
 * 매니페스트에서 com.coverfo.soria.CONTROL(서명 권한)을 가진 앱만 보낼 수 있게 막아 두었다 → 같은 키로 서명한 coverfo 소리만.
 *   extras: op = "tap" | "back" | "read" | "ping", text = 누를 글자
 *   결과(resultData): JSON 글자 { ok, … }
 */
class CmdReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "com.coverfo.soria.CMD") return
        val s = ScreenService.instance
        val op = intent.getStringExtra("op").orEmpty()
        val out: JSONObject = if (s == null) {
            JSONObject().put("ok", false).put("off", true)
                .put("note", "The coverfo screen helper is installed but turned off (Settings > Accessibility > coverfo 화면 도우미).")
        } else try {
            when (op) {
                "ping" -> JSONObject().put("ok", true)
                "tap" -> s.tap(intent.getStringExtra("text").orEmpty().take(60))
                "back" -> s.back()
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
}
