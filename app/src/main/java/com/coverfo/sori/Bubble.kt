package com.coverfo.sori

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.abs

/**
 * 다른 앱(카카오맵·유튜브…) 위에 떠 있는 🗣️ 아이콘. '다른 앱 위에 표시' 권한이 있을 때만.
 * · 끌어서 옮기기(위치는 기억) · 누르면 coverfo 소리 화면(대화 내용)으로.
 * · 소리가 말하는 중이면 분홍색, 듣는 중이면 보라색.
 * · v8: 작게 (60dp → 44dp) — '소리야' 로 대화가 시작될 때도 뜬다.
 * · v23: coverfo 소리 화면에서 고른 큐브(8색)면 큐브 그림으로 — 듣는 중 = 듣기 · 말하는 중 = 말하기 · 대화가 끝난 대기 = 대기 표정
 *        (고른 색은 웹이 CoverfoApp.setChar 로 알려 준다 · prefs "chr" 0~8 · 0 이면 예전 보라 🗣️)
 */
class Bubble(private val ctx: Context, private val onTap: () -> Unit) {

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val prefs = ctx.getSharedPreferences("sori", Context.MODE_PRIVATE)
    private val chr: Int = prefs.getInt("chr", 0).let { if (it in 1..8) it else 0 }
    private val size = ((if (chr > 0) 56 else 44) * ctx.resources.displayMetrics.density).toInt()
    /* 큐브 그림: [색 1~8][대기·듣기·말하기] */
    private val cube = arrayOf(
        intArrayOf(R.drawable.cube_c1_idle, R.drawable.cube_c1_listen, R.drawable.cube_c1_speak),
        intArrayOf(R.drawable.cube_c2_idle, R.drawable.cube_c2_listen, R.drawable.cube_c2_speak),
        intArrayOf(R.drawable.cube_c3_idle, R.drawable.cube_c3_listen, R.drawable.cube_c3_speak),
        intArrayOf(R.drawable.cube_c4_idle, R.drawable.cube_c4_listen, R.drawable.cube_c4_speak),
        intArrayOf(R.drawable.cube_c5_idle, R.drawable.cube_c5_listen, R.drawable.cube_c5_speak),
        intArrayOf(R.drawable.cube_c6_idle, R.drawable.cube_c6_listen, R.drawable.cube_c6_speak),
        intArrayOf(R.drawable.cube_c7_idle, R.drawable.cube_c7_listen, R.drawable.cube_c7_speak),
        intArrayOf(R.drawable.cube_c8_idle, R.drawable.cube_c8_listen, R.drawable.cube_c8_speak),
    )
    private var face = 1          // 0 대기 · 1 듣기 · 2 말하기 (대화 중에는 듣기로 시작)
    private var standbyOn = false
    private var anim: ObjectAnimator? = null
    private val bg = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.parseColor("#6D3DF0"))
        setStroke((2 * ctx.resources.displayMetrics.density).toInt(), Color.WHITE)
    }
    private val view = TextView(ctx).apply {
        gravity = Gravity.CENTER
        if (chr > 0) {
            text = ""
            setBackgroundResource(cube[chr - 1][1])
        } else {
            text = "🗣️"
            textSize = 19f
            background = bg
            elevation = 12f
        }
    }

    /** v23 큐브: 표정 그림 + 살짝 움직임 (대기 = 없음 · 듣기 = 작게 숨쉬기 · 말하기 = 꿈틀) */
    private fun cubeFace(f: Int) {
        if (chr == 0) return
        face = f
        main.post {
            view.setBackgroundResource(cube[chr - 1][f])
            anim?.cancel(); anim = null
            view.scaleX = 1f; view.scaleY = 1f
            if (f == 0) return@post
            val big = if (f == 2) 1.07f else 1.03f
            anim = ObjectAnimator.ofPropertyValuesHolder(view,
                PropertyValuesHolder.ofFloat("scaleX", 1f, if (f == 2) 1.05f else big),
                PropertyValuesHolder.ofFloat("scaleY", 1f, if (f == 2) 0.95f else big)
            ).apply {
                duration = if (f == 2) 280L else 900L
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                start()
            }
        }
    }
    private val lp = WindowManager.LayoutParams(
        size, size,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = prefs.getInt("bx", ctx.resources.displayMetrics.widthPixels - size - 24)
        y = prefs.getInt("by", ctx.resources.displayMetrics.heightPixels / 2)
    }
    private var shown = false

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (shown) return
        var sx = 0f
        var sy = 0f
        var ox = 0
        var oy = 0
        var moved = false
        view.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y; moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - sx
                    val dy = e.rawY - sy
                    if (!moved && abs(dx) + abs(dy) > 12) moved = true
                    if (moved) {
                        lp.x = ox + dx.toInt()
                        lp.y = oy + dy.toInt()
                        try { wm.updateViewLayout(view, lp) } catch (x: Exception) { }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) prefs.edit().putInt("bx", lp.x).putInt("by", lp.y).apply() else onTap()
                    true
                }
                else -> false
            }
        }
        try {
            wm.addView(view, lp)
            shown = true
            cubeFace(if (standbyOn) 0 else face)
        } catch (x: Exception) {
        }
    }

    fun hide() {
        if (!shown) return
        main.post { anim?.cancel(); anim = null }
        try { wm.removeView(view) } catch (x: Exception) { }
        shown = false
    }

    /** v15: 대화는 끝났지만 아이콘은 남아 있는 '대기' 모양 (회색) — 누르면 다시 대화 */
    fun standby(on: Boolean) {
        standbyOn = on
        if (chr > 0) { cubeFace(if (on) 0 else 1); main.post { view.alpha = if (on) 0.85f else 1f }; return }
        main.post {
            bg.setColor(Color.parseColor(if (on) "#8E8AA0" else "#6D3DF0"))
            view.alpha = if (on) 0.85f else 1f
        }
    }

    fun speaking(on: Boolean) {
        if (chr > 0) { cubeFace(if (on) 2 else if (standbyOn) 0 else 1); return }
        main.post { bg.setColor(Color.parseColor(if (on) "#E548A8" else "#6D3DF0")) }
    }
}
