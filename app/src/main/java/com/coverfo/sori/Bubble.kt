package com.coverfo.sori

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
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
 */
class Bubble(private val ctx: Context, private val onTap: () -> Unit) {

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val prefs = ctx.getSharedPreferences("sori", Context.MODE_PRIVATE)
    private val size = (60 * ctx.resources.displayMetrics.density).toInt()
    private val bg = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.parseColor("#6D3DF0"))
        setStroke((3 * ctx.resources.displayMetrics.density).toInt(), Color.WHITE)
    }
    private val view = TextView(ctx).apply {
        text = "🗣️"
        textSize = 26f
        gravity = Gravity.CENTER
        background = bg
        elevation = 12f
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
        } catch (x: Exception) {
        }
    }

    fun hide() {
        if (!shown) return
        try { wm.removeView(view) } catch (x: Exception) { }
        shown = false
    }

    fun speaking(on: Boolean) {
        main.post { bg.setColor(Color.parseColor(if (on) "#E548A8" else "#6D3DF0")) }
    }
}
