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
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * ☀️ 아침 브리핑 알림 (v6).
 * · 서버(coverfo)는 알림 15분 전에 오늘 브리핑(2분 음성)을 미리 만든다.
 * · 이 앱은 정한 시각(한국시간)에 알람으로 깨어나 /api/hub {op:"brief_latest"} 로 오늘 것이 준비됐는지 묻고,
 *   준비됐으면 "☀️ 오늘 아침 브리핑" 알림을 띄운다 → 누르면 coverfo.com/brief?play=1 이 앱 안에서 열려 바로 재생.
 *   아직이면 5분 뒤 다시 (최대 6번).
 * · 정각 알람 권한(SCHEDULE_EXACT_ALARM)은 쓰지 않는다 (구글 정책) → 폰 절전 상태에 따라 몇 분 늦을 수 있다.
 * · 로그인은 '기기 키'(prefs "device", talk-live op:"device" 로 받아 둔 30일 키)로 한다.
 */
object BriefAlarm {
    private const val CH = "brief"
    private const val REQ = 4177
    private const val HUB = "https://coverfo.com/api/hub"
    private const val MAX_RETRY = 6

    fun prefs(ctx: Context) = ctx.getSharedPreferences("brief", Context.MODE_PRIVATE)

    /** 화면(coverfo.com/brief)에서 저장할 때: {"on":true,"hh":7,"mm":0,"days":[1,2,3,4,5]} */
    fun set(ctx: Context, json: String) {
        val j = try { JSONObject(json) } catch (e: Exception) { JSONObject() }
        val days = j.optJSONArray("days") ?: JSONArray()
        val list = (0 until days.length()).map { days.optInt(it, -1) }.filter { it in 0..6 }
        prefs(ctx).edit()
            .putBoolean("on", j.optBoolean("on"))
            .putInt("hh", j.optInt("hh", 7).coerceIn(0, 23))
            .putInt("mm", j.optInt("mm", 0).coerceIn(0, 59))
            .putString("days", list.joinToString(","))
            .apply()
        schedule(ctx, 0)
    }

    fun stateJson(ctx: Context): String {
        val p = prefs(ctx)
        val hh = p.getInt("hh", 7)
        val mm = p.getInt("mm", 0)
        val days = days(ctx)
        val names = arrayOf("일", "월", "화", "수", "목", "금", "토")
        val dtext = if (days.size == 7) "매일" else if (days == listOf(1, 2, 3, 4, 5)) "평일" else days.joinToString("·") { names[it] }
        return JSONObject()
            .put("on", p.getBoolean("on", false))
            .put("hh", hh).put("mm", mm)
            .put("text", dtext + " " + String.format("%02d:%02d", hh, mm))
            .put("next", p.getLong("next", 0L))
            .toString()
    }

    private fun days(ctx: Context): List<Int> {
        val s = prefs(ctx).getString("days", "0,1,2,3,4,5,6") ?: ""
        val l = s.split(",").mapNotNull { it.trim().toIntOrNull() }.filter { it in 0..6 }.distinct().sorted()
        return if (l.isEmpty()) listOf(0, 1, 2, 3, 4, 5, 6) else l
    }

    private fun pending(ctx: Context, retry: Int): PendingIntent {
        val i = Intent(ctx, BriefReceiver::class.java).setAction("com.coverfo.sori.BRIEF").putExtra("retry", retry)
        return PendingIntent.getBroadcast(ctx, REQ, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** 다음 알림 시각 (한국시간 규칙 → 폰 시각 ms) */
    fun nextTime(ctx: Context, from: Long): Long {
        val p = prefs(ctx)
        val hh = p.getInt("hh", 7)
        val mm = p.getInt("mm", 0)
        val ds = days(ctx)
        val c = Calendar.getInstance(TimeZone.getTimeZone("Asia/Seoul"))
        c.timeInMillis = from
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        c.set(Calendar.HOUR_OF_DAY, hh)
        c.set(Calendar.MINUTE, mm)
        for (off in 0..8) {
            if (off > 0) c.add(Calendar.DAY_OF_MONTH, 1)
            val wd = c.get(Calendar.DAY_OF_WEEK) - 1   // 일=0 … 토=6
            if (c.timeInMillis > from && ds.contains(wd)) return c.timeInMillis
        }
        return from + 24 * 3600_000L
    }

    /** retry=0: 다음 정해진 시각 · retry>0: 5분 뒤 다시 확인 */
    fun schedule(ctx: Context, retry: Int) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pending(ctx, retry)
        am.cancel(pi)
        if (!prefs(ctx).getBoolean("on", false)) {
            prefs(ctx).edit().putLong("next", 0L).apply()
            return
        }
        val t = if (retry > 0) System.currentTimeMillis() + 5 * 60_000L else nextTime(ctx, System.currentTimeMillis())
        prefs(ctx).edit().putLong("next", t).apply()
        try {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi)
        } catch (e: Exception) {
            try { am.set(AlarmManager.RTC_WAKEUP, t, pi) } catch (x: Exception) { }
        }
    }

    /** 알람이 울렸을 때 (백그라운드 스레드에서) */
    fun check(ctx: Context, retry: Int) {
        val dev = SoriService.prefs(ctx).getString("device", "") ?: ""
        var ready = false
        var title = ""
        var gotAnswer = false
        if (dev.isNotEmpty()) {
            try {
                val http = OkHttpClient.Builder().callTimeout(25, TimeUnit.SECONDS).build()
                val req = Request.Builder().url(HUB)
                    .header("Authorization", "Bearer $dev")
                    .post("{\"op\":\"brief_latest\"}".toRequestBody("application/json".toMediaType()))
                    .build()
                http.newCall(req).execute().use { r ->
                    val j = try { JSONObject(r.body?.string() ?: "") } catch (e: Exception) { null }
                    if (r.code == 200 && j != null) {
                        gotAnswer = true
                        ready = j.optBoolean("ready")
                        title = j.optJSONObject("item")?.optString("title") ?: ""
                    }
                }
            } catch (e: Exception) { }
        }
        when {
            ready -> { notify(ctx, title.ifBlank { "오늘 아침 브리핑" }, "눌러서 2분 브리핑 듣기"); schedule(ctx, 0) }
            retry < MAX_RETRY && (dev.isNotEmpty()) -> schedule(ctx, retry + 1)
            else -> {
                if (dev.isEmpty()) notify(ctx, "아침 브리핑", "coverfo 소리 앱을 한 번 열어 로그인 상태를 확인해 주세요")
                else if (gotAnswer) notify(ctx, "아침 브리핑", "오늘 브리핑을 만들지 못했어요 · 눌러서 확인")
                schedule(ctx, 0)
            }
        }
    }

    private fun notify(ctx: Context, title: String, text: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CH) == null) {
            nm.createNotificationChannel(NotificationChannel(CH, "아침 브리핑", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = Intent(ctx, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse("https://coverfo.com/brief?play=1"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(ctx, REQ + 1, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(ctx, CH)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("☀️ $title")
            .setContentText(text)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try { nm.notify(REQ, n) } catch (e: Exception) { }   // 알림 권한이 없으면 조용히 넘어감
    }
}

/** 알람 받기 (이 앱 안에서만 — exported=false) */
class BriefReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "com.coverfo.sori.BRIEF") return
        val app = context.applicationContext
        val retry = intent.getIntExtra("retry", 0)
        val pr = goAsync()
        Thread {
            try { BriefAlarm.check(app, retry) } finally { pr.finish() }
        }.start()
    }
}

/** 폰을 껐다 켜거나 앱을 업데이트하면 알람이 지워지므로 다시 건다 */
class BriefBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val a = intent.action
        if (a == Intent.ACTION_BOOT_COMPLETED || a == Intent.ACTION_MY_PACKAGE_REPLACED) BriefAlarm.schedule(context.applicationContext, 0)
    }
}
