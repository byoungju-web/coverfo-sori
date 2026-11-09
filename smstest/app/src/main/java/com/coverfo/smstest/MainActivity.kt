package com.coverfo.smstest

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * coverfo 문자 시험 — 소리 앱에 '문자 대신 보내기'를 넣기 전에 확인하는 화면.
 * 시험 2 (문자 권한 뺌): ① 설치가 되는지(플레이 프로텍트) ② 권한 허용 창 ③ 문자 앱 채워서 열기 ④ 연락처 이름 찾기 ⑤ 바로 전화 걸기
 * 결과는 화면 아래 기록에만 남는다 (저장·전송 안 함).
 */
class MainActivity : Activity() {

    companion object {
        private const val REQ = 7
        private val PERMS = arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE)
    }

    private lateinit var status: TextView
    private lateinit var num: EditText
    private lateinit var body: EditText
    private lateinit var name: EditText
    private lateinit var found: LinearLayout
    private lateinit var log: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        val pad = (18 * d).toInt()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad * 2, pad, pad * 2) }
        fun text(t: String, size: Float, bold: Boolean = false, color: Int = Color.parseColor("#15121F")) = TextView(this).apply {
            text = t; textSize = size; setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setPadding(0, (6 * d).toInt(), 0, (6 * d).toInt())
        }
        fun field(hint: String, type: Int) = EditText(this).apply { this.hint = hint; inputType = type; textSize = 16f }
        fun btn(t: String, f: () -> Unit) = Button(this).apply { text = t; isAllCaps = false; setOnClickListener { f() } }

        col.addView(text("🧪 coverfo 문자 시험", 22f, true))
        col.addView(text("시험 2: 연락처 찾기 · 바로 전화 걸기 권한만 든 앱이 설치되는지 확인해요. 받은 문자는 읽지 않고, 인터넷으로 아무것도 보내지 않아요.", 14.5f, color = Color.parseColor("#5E5874")))
        status = text("", 15f, true)
        col.addView(status)
        col.addView(btn("① 권한 허용하기 (연락처 · 전화)") { askPerms() })

        col.addView(text("② 문자 (채워서 열기)", 17f, true))
        col.addView(text("시험 2에서는 문자를 직접 보내지 않고, 번호와 내용을 채운 문자 앱을 열어요 (보내기는 직접).", 13.5f, color = Color.parseColor("#5E5874")))
        num = field("받는 번호 (예: 01012345678)", InputType.TYPE_CLASS_PHONE)
        body = field("보낼 내용", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE).apply { setText("coverfo 문자 시험이에요 ✅") }
        col.addView(num); col.addView(body)
        col.addView(btn("문자 앱 열기") { openSms() })

        col.addView(text("③ 연락처에서 이름 찾기", 17f, true))
        name = field("이름 (예: 엄마)", InputType.TYPE_CLASS_TEXT)
        col.addView(name)
        col.addView(btn("찾기") { findContact() })
        found = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(found)

        col.addView(text("④ 바로 전화 걸기", 17f, true))
        col.addView(text("위 '받는 번호'로 바로 전화를 걸어요 (키패드를 거치지 않음).", 13.5f, color = Color.parseColor("#5E5874")))
        col.addView(btn("전화 걸기") { callNow() })

        col.addView(text("기록", 17f, true))
        log = text("", 13.5f, color = Color.parseColor("#3A3460"))
        col.addView(log)

        setContentView(ScrollView(this).apply { addView(col) })

        add("앱 열림 · 안드로이드 ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL}")
    }

    override fun onResume() {
        super.onResume()
        showStatus()
    }

    private fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun showStatus() {
        fun s(p: String, n: String) = (if (has(p)) "✅ " else "⚪ ") + n
        status.text = s(Manifest.permission.READ_CONTACTS, "연락처") + "   " + s(Manifest.permission.CALL_PHONE, "전화")
    }

    private fun askPerms() {
        val need = PERMS.filter { !has(it) }
        if (need.isEmpty()) { add("권한: 이미 모두 허용됨"); return }
        requestPermissions(need.toTypedArray(), REQ)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ) return
        for (i in permissions.indices) {
            val n = when (permissions[i]) {
                Manifest.permission.READ_CONTACTS -> "연락처"
                Manifest.permission.CALL_PHONE -> "전화"
                else -> permissions[i]
            }
            add("권한 $n: " + if (grantResults.getOrNull(i) == PackageManager.PERMISSION_GRANTED) "허용" else "거부")
        }
        showStatus()
    }

    private fun digits(s: String) = s.filter { it.isDigit() || it == '+' }

    private fun openSms() {
        val to = digits(num.text.toString())
        val t = body.text.toString().trim()
        try {
            val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$to")).putExtra("sms_body", t)
            startActivity(i)
            add("💬 문자 앱 열림 → " + (if (to.isEmpty()) "(번호 없음)" else to))
        } catch (e: Exception) {
            add("❌ 문자 앱 열기 실패: " + (e.message ?: e.javaClass.simpleName))
        }
    }

    private fun findContact() {
        val q = name.text.toString().trim()
        found.removeAllViews()
        if (q.isEmpty()) { add("⚠️ 찾을 이름을 써 주세요"); return }
        if (!has(Manifest.permission.READ_CONTACTS)) { add("⚠️ 연락처 권한이 없어요 → ① 권한 허용하기"); return }
        try {
            val cols = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER)
            val c = contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI, cols,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?", arrayOf("%$q%"),
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )
            var n = 0
            c?.use {
                while (it.moveToNext() && n < 8) {
                    val nm = it.getString(0) ?: ""
                    val ph = it.getString(1) ?: ""
                    n++
                    found.addView(Button(this).apply {
                        text = "$nm  ·  $ph"
                        isAllCaps = false
                        setOnClickListener { num.setText(digits(ph)); add("받는 번호에 넣음: $nm") }
                    })
                }
            }
            add("🔎 '$q' 찾음: ${n}명" + if (n > 0) " (누르면 받는 번호에 들어가요)" else "")
        } catch (e: Exception) {
            add("❌ 연락처 찾기 실패: " + (e.message ?: e.javaClass.simpleName))
        }
    }

    private fun callNow() {
        val to = digits(num.text.toString())
        if (to.length < 3) { add("⚠️ 받는 번호를 확인해 주세요"); return }
        if (!has(Manifest.permission.CALL_PHONE)) { add("⚠️ 전화 권한이 없어요 → ① 권한 허용하기"); return }
        try {
            startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$to")))
            add("📞 전화 거는 중 → $to")
        } catch (e: Exception) {
            add("❌ 전화 걸기 실패: " + (e.message ?: e.javaClass.simpleName))
        }
    }

    private fun add(s: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date())
        log.text = "[$t] $s\n" + log.text
    }
}
