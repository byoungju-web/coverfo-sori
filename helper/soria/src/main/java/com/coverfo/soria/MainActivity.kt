package com.coverfo.soria

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** 도우미 켜기 안내 화면 (켜짐 표시 · 접근성 설정 열기 · 제한된 설정 허용 안내) */
class MainActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        val pad = (20 * d).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        fun text(t: String, size: Float, bold: Boolean = false, color: Int = Color.parseColor("#15121F")) = TextView(this).apply {
            text = t
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setPadding(0, (6 * d).toInt(), 0, (6 * d).toInt())
        }
        col.addView(text("🖐 coverfo 화면 도우미", 22f, true))
        status = text("", 16f, true)
        col.addView(status)
        col.addView(text("coverfo 소리에게 말로 시키면, 지금 보고 있는 앱(네이버지도·카카오맵 등) 화면에서 대신 눌러 주고, 뒤로 가고, 화면 글자를 읽어 답해 줘요.\n\n예) \"양양방향 눌러줘\" · \"길찾기 눌러\" · \"뒤로\" · \"이 화면에서 남은 거리 얼마야?\"", 15f))
        col.addView(text("• 소리에게 시킬 때만 움직여요. 화면 내용은 저장하거나 인터넷으로 보내지 않아요 (물어본 화면 글자만 그 대답을 위해 소리 대화로 전달).\n• 결제·구매·송금·보내기·삭제 단추는 누르지 않아요. 그런 건 직접 눌러 주세요.\n• 끄려면 같은 설정에서 끄거나 이 앱을 지우면 돼요.", 13.5f, color = Color.parseColor("#5E5874")))
        col.addView(text("켜는 방법", 17f, true))
        col.addView(text("1) 아래 [접근성 설정 열기] → '설치된 앱'(또는 '다운로드한 앱') → 'coverfo 화면 도우미' → 켜기\n2) '제한된 설정' 이라고 나오면: [앱 정보 열기] → 오른쪽 위 ⋮ → '제한된 설정 허용' → 다시 1)", 14.5f))
        val b1 = Button(this).apply {
            text = "접근성 설정 열기"
            setOnClickListener { try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (e: Exception) { } }
        }
        val b2 = Button(this).apply {
            text = "앱 정보 열기 (제한된 설정 허용)"
            setOnClickListener { try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } catch (e: Exception) { } }
        }
        col.addView(b1)
        col.addView(b2)
        col.gravity = Gravity.TOP
        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        val on = enabled()
        status.text = if (on) "✅ 켜져 있어요 · coverfo 소리에게 말로 시켜 보세요" else "⚪ 꺼져 있어요 · 아래에서 켜 주세요"
        status.setTextColor(Color.parseColor(if (on) "#0E9F6E" else "#B45309"))
    }

    private fun enabled(): Boolean {
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return s.split(':').any { it.startsWith("$packageName/") && it.endsWith("ScreenService") }
    }
}
