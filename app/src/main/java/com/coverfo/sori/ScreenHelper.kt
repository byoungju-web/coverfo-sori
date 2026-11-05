package com.coverfo.sori

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import org.json.JSONObject

/**
 * 🖐 v10 coverfo 화면 도우미(따로 설치하는 앱 com.coverfo.soria)에게 일 시키기.
 * · 도우미는 접근성 서비스라서 소리 앱에 넣지 않고 따로 뒀다 (접근성을 넣은 앱은 Play 프로텍트가 설치를 막은 적이 있음 · 2026-10-28).
 *   도우미가 막혀도 소리 앱은 그대로 쓸 수 있다.
 * · 순서 있는 방송으로 보내고 결과(JSON)를 바로 받는다. 같은 서명 키의 앱만 주고받을 수 있다 (서명 권한 com.coverfo.soria.CONTROL).
 *   op: "tap"(글자 누르기) · "back"(그 앱에서 뒤로) · "read"(보이는 글자 읽기) · "ping"
 */
object ScreenHelper {
    const val PKG = "com.coverfo.soria"
    private const val ACTION = "com.coverfo.soria.CMD"
    private val main = Handler(Looper.getMainLooper())

    fun installed(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo(PKG, 0)
        true
    } catch (e: Exception) { false }

    /** cb 는 메인 스레드에서 한 번만 불린다 (4초 안에 답이 없으면 실패로) */
    fun call(ctx: Context, op: String, text: String, cb: (JSONObject) -> Unit) {
        if (!installed(ctx)) {
            cb(JSONObject().put("ok", false).put("missing", true)
                .put("note", "The coverfo screen helper app is not installed (coverfo.com/app → 화면 도우미)."))
            return
        }
        var done = false
        val finish = { j: JSONObject -> if (!done) { done = true; cb(j) } }
        val rr = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val d = resultData
                val j = try { if (d.isNullOrEmpty()) null else JSONObject(d) } catch (e: Exception) { null }
                finish(j ?: JSONObject().put("ok", false).put("note", "The screen helper did not answer. Open the 화면 도우미 app once, or reinstall it."))
            }
        }
        try {
            val i = Intent(ACTION).setPackage(PKG).putExtra("op", op).putExtra("text", text.take(60))
            ctx.applicationContext.sendOrderedBroadcast(i, null, rr, main, 0, null, null)
        } catch (e: Exception) {
            finish(JSONObject().put("ok", false).put("note", "Could not reach the screen helper."))
            return
        }
        main.postDelayed({ finish(JSONObject().put("ok", false).put("note", "The screen helper did not answer in time.")) }, 4_000)
    }
}
