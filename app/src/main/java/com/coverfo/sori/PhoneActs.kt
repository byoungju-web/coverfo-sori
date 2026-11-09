package com.coverfo.sori

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract

/**
 * v20 📞 끝까지 해 주기 1단계 — 연락처 찾기 · 바로 전화 걸기 · 문자 채워서 열기.
 * · 문자를 앱이 직접 보내는 권한(SEND_SMS)은 쓰지 않는다: 그 권한이 든 앱은 플레이 프로텍트가 '유해한 앱'으로 막았다 (2026-11-09 시험 1).
 *   → 번호와 내용을 채운 문자 앱을 열고, 보내기는 이용자가 한 번 누른다 (시험 2에서 설치·동작 확인).
 * · 연락처는 폰 안에서만 찾는다. 소리 대화에는 이름과 번호 끝 4자리만 넘긴다 (전체 번호는 넘기지 않음).
 */
object PhoneActs {

    class Person(val name: String, val number: String)

    fun digits(s: String): String = s.filter { it.isDigit() || it == '+' }

    /** 번호 끝 4자리만 (소리 대화·화면에 보여 줄 때) */
    fun last4(n: String): String {
        val d = digits(n)
        return if (d.length >= 4) d.takeLast(4) else d
    }

    fun has(ctx: Context, perm: String): Boolean = ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    fun canContacts(ctx: Context) = has(ctx, Manifest.permission.READ_CONTACTS)
    fun canCall(ctx: Context) = has(ctx, Manifest.permission.CALL_PHONE)

    /** 이름으로 찾기 (부분 일치) — "은혁이", "엄마한테" 처럼 말끝이 붙어도 한 번 더 찾아 본다 */
    fun find(ctx: Context, raw: String, max: Int = 8): List<Person> {
        val q = raw.trim().replace(Regex("\\s*(님|씨)?\\s*(한테|에게|께|이한테|이에게)$"), "").trim()
        if (q.isEmpty() || !canContacts(ctx)) return emptyList()
        var r = query(ctx, q, max)
        if (r.isEmpty() && q.length >= 3 && (q.endsWith("이") || q.endsWith("님") || q.endsWith("씨"))) r = query(ctx, q.dropLast(1), max)
        return r
    }

    private fun query(ctx: Context, q: String, max: Int): List<Person> {
        val out = ArrayList<Person>()
        val seen = HashSet<String>()
        try {
            val cols = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER)
            ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI, cols,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?", arrayOf("%$q%"),
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )?.use { c ->
                while (c.moveToNext() && out.size < max) {
                    val nm = c.getString(0) ?: continue
                    val ph = digits(c.getString(1) ?: "")
                    if (ph.length < 3 || !seen.add(nm + "|" + ph)) continue
                    out.add(Person(nm, ph))
                }
            }
        } catch (e: Exception) { }
        /* 이름이 정확히 같은 사람이 있으면 그 사람을 앞으로 */
        out.sortBy { if (it.name == q) 0 else 1 }
        return out
    }

    /** 바로 전화 (전화 권한이 없으면 키패드에 번호만 채워서) */
    fun call(ctx: Context, number: String): Boolean = try {
        val act = if (canCall(ctx)) Intent.ACTION_CALL else Intent.ACTION_DIAL
        ctx.startActivity(Intent(act, Uri.parse("tel:" + digits(number))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) { false }

    /** 번호와 내용을 채운 문자 앱 열기 — 보내기는 이용자가 누른다 */
    fun sms(ctx: Context, number: String, text: String): Boolean = try {
        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + digits(number))).putExtra("sms_body", text.take(1000))
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) { false }
}
