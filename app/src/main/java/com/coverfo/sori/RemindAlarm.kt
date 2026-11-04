package com.coverfo.sori

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.OffsetDateTime
import java.util.Calendar
import java.util.SimpleTimeZone
import java.util.concurrent.TimeUnit

/**
 * ⏰ 알림 (v7) — coverfo 홈의 "3시에 회의 알려줘" · "매달 25일 월세 내라고 알려줘".
 * · 앱 안(WebView)에서는 웹 알림(Web Push)이 오지 않아서, 이 앱이 직접 폰 알람을 걸어 알림을 띄운다.
 * · 화면이 알림을 더하거나 지우면 CoverfoApp.remindSync(로그인키) 를 부른다 → /api/hub {op:"remind_list"} 로 받아 둔다.
 *   앱을 열 때도 한 번 받는다. 받은 목록은 이 폰(SharedPreferences "remind")에만 둔다.
 * · 가장 가까운 알림 시각 하나에 알람을 건다. 울리면 그때 된 알림을 띄우고, 반복 알림은 다음 시각으로 옮긴 뒤 다시 건다.
 *   (매일 +1일 · 매주 +7일 · 매달 같은 날짜 — 서버 hub.js remindNext 와 같은 규칙)
 * · 정각 알람 권한(SCHEDULE_EXACT_ALARM)은 쓰지 않는다 (아침 브리핑과 같음, 구글 정책) → 폰 절전 상태에 따라 몇 분 늦을 수 있다.
 * · 백그라운드에서 다시 받을 때는 '기기 키'(prefs "device")를 쓴다.
 */
object RemindAlarm {
    private const val CH = "remind"
    private const val REQ = 4277
    private const val HUB = "https://coverfo.com/api/hub"

    fun prefs(ctx: Context) = ctx.getSharedPreferences("remind", Context.MODE_PRIVATE)

    /** 화면에서 부름 (UI 스레드가 아닌 곳에서 받아 온다) */
    fun sync(ctx: Context, token: String) {
        val app = ctx.applicationContext
        Thread { try { syncNow(app, token) } catch (e: Exception) { } }.start()
    }

    /** 서버 목록 받기 → 이 폰에 저장 → 알람 다시 걸기. 받지 못하면 저장된 목록으로 건다 */
    fun syncNow(ctx: Context, token: String): Boolean {
        val auth = token.ifBlank { SoriService.prefs(ctx).getString("device", "") ?: "" }
        if (auth.isEmpty()) { schedule(ctx); return false }
        var ok = false
        try {
            val http = OkHttpClient.Builder().callTimeout(25, TimeUnit.SECONDS).build()
            val req = Request.Builder().url(HUB)
                .header("Authorization", "Bearer $auth")
                .post("{\"op\":\"remind_list\"}".toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(req).execute().use { r ->
                val j = try { JSONObject(r.body?.string() ?: "") } catch (e: Exception) { null }
                if (r.code == 200 && j != null) {
                    val items = j.optJSONArray("items") ?: JSONArray()
                    val out = JSONArray()
                    for (i in 0 until items.length()) {
                        val o = items.optJSONObject(i) ?: continue
                        if (!o.optBoolean("is_on", true)) continue
                        val next = parseTime(o.optString("next_at"))
                        if (next <= 0L) continue
                        out.put(JSONObject()
                            .put("id", o.optLong("id"))
                            .put("text", o.optString("text").take(60))
                            .put("kind", o.optString("kind", "once"))
                            .put("next", next)
                            .put("off", o.optInt("off_min", 540))
                            .put("dom", o.optInt("dom", 1)))
                    }
                    prefs(ctx).edit().putString("list", out.toString()).apply()
                    ok = true
                }
            }
        } catch (e: Exception) { }
        schedule(ctx)
        return ok
    }

    /* Supabase 시각 "2026-11-25T00:00:00+00:00" (안드로이드 8 의 Instant.parse 는 +00:00 을 못 읽어서 OffsetDateTime 으로) */
    private fun parseTime(s: String): Long = try { OffsetDateTime.parse(s.trim().replace(" ", "T")).toInstant().toEpochMilli() } catch (e: Exception) { 0L }

    private fun list(ctx: Context): JSONArray = try { JSONArray(prefs(ctx).getString("list", "[]") ?: "[]") } catch (e: Exception) { JSONArray() }

    private fun pending(ctx: Context): PendingIntent {
        val i = Intent(ctx, RemindReceiver::class.java).setAction("com.coverfo.sori.REMIND")
        return PendingIntent.getBroadcast(ctx, REQ, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** 가장 가까운 알림 시각 하나에 알람 */
    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pending(ctx)
        am.cancel(pi)
        val l = list(ctx)
        var t = Long.MAX_VALUE
        for (i in 0 until l.length()) {
            val n = l.optJSONObject(i)?.optLong("next") ?: continue
            if (n > 0L && n < t) t = n
        }
        if (t == Long.MAX_VALUE) return
        val at = maxOf(t, System.currentTimeMillis() + 5_000L)
        try {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (e: Exception) {
            try { am.set(AlarmManager.RTC_WAKEUP, at, pi) } catch (x: Exception) { }
        }
    }

    /** 다음 시각 (서버 hub.js remindNext 와 같은 규칙). 한 번짜리는 0 */
    private fun nextOf(o: JSONObject, from: Long, now: Long): Long {
        val kind = o.optString("kind")
        if (kind == "once") return 0L
        val off = o.optInt("off", 540)
        var t = from
        var guard = 0
        while (t <= now && guard < 400) {
            guard++
            when (kind) {
                "daily" -> t += 86_400_000L
                "weekly" -> t += 7 * 86_400_000L
                "monthly" -> {
                    val c = Calendar.getInstance(SimpleTimeZone(off * 60_000, "cf"))
                    c.timeInMillis = t
                    c.set(Calendar.DAY_OF_MONTH, 1)
                    c.add(Calendar.MONTH, 1)
                    val dom = o.optInt("dom", 1).coerceIn(1, 31)
                    c.set(Calendar.DAY_OF_MONTH, minOf(dom, c.getActualMaximum(Calendar.DAY_OF_MONTH)))
                    t = c.timeInMillis
                }
                else -> return 0L
            }
        }
        return if (t > now) t else 0L
    }

    /** 알람이 울렸을 때 (백그라운드 스레드에서) */
    fun fire(ctx: Context) {
        val now = System.currentTimeMillis()
        val l = list(ctx)
        val keep = JSONArray()
        for (i in 0 until l.length()) {
            val o = l.optJSONObject(i) ?: continue
            val n = o.optLong("next")
            if (n <= now + 30_000L) {
                /* 6시간 넘게 늦게 깨어났으면 지난 알림은 띄우지 않는다 (서버와 같은 규칙) */
                if (now - n <= 6 * 3_600_000L) notify(ctx, o.optLong("id"), o.optString("text").ifBlank { "알림" })
                val nx = nextOf(o, n, now)
                if (nx > 0L) keep.put(o.put("next", nx))
            } else keep.put(o)
        }
        prefs(ctx).edit().putString("list", keep.toString()).apply()
        /* 서버 목록으로 한 번 더 맞춘다 (다른 기기에서 더하거나 지운 것) — 실패하면 위에서 옮긴 목록으로 */
        syncNow(ctx, "")
    }

    private fun notify(ctx: Context, id: Long, text: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CH) == null) {
            nm.createNotificationChannel(NotificationChannel(CH, "알림 (내가 맞춘 것)", NotificationManager.IMPORTANCE_HIGH))
        }
        val open = Intent(ctx, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse("https://coverfo.com/"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val nid = REQ + 10 + (id % 1000).toInt()
        val pi = PendingIntent.getActivity(ctx, nid, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(ctx, CH)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("⏰ coverfo 알림")
            .setContentText(text)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try { nm.notify(nid, n) } catch (e: Exception) { }   // 알림 권한이 없으면 조용히 넘어감
    }
}

/** 알람 받기 (이 앱 안에서만 — exported=false) */
class RemindReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "com.coverfo.sori.REMIND") return
        val app = context.applicationContext
        val pr = goAsync()
        Thread {
            try { RemindAlarm.fire(app) } finally { pr.finish() }
        }.start()
    }
}
