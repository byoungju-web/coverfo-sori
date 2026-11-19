package com.coverfo.sori;
/* coverfo 소리 v28 — 포도랑 앱(kr.podolang.app)의 오디오·스트림 번역을 옮겨 온 파일 (APK판 full 에만 들어감) */

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * v1.0.3 다른 앱 위에 뜨는 자막 한 줄 (선택).
 * '다른 앱 위에 표시' 권한을 준 경우에만 보입니다. 손가락으로 위아래로 옮길 수 있고, 두 번 누르면 숨깁니다.
 */
class SubtitleOverlay {
    private final Context ctx;
    private final WindowManager wm;
    private TextView view;
    private WindowManager.LayoutParams lp;

    SubtitleOverlay(Context c) {
        ctx = c.getApplicationContext();
        wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
    }

    static boolean allowed(Context c) { return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c); }

    void show() {
        if (view != null || !allowed(ctx) || Build.VERSION.SDK_INT < 26) return;
        TextView t = new TextView(ctx);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        t.setMaxLines(3);
        int p = dp(12);
        t.setPadding(p, dp(8), p, dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xCC111827);
        bg.setCornerRadius(dp(14));
        t.setBackground(bg);
        t.setText(ctx.getString(R.string.audioOverlayWait));

        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.x = 0;
        lp.y = dp(96);
        lp.horizontalMargin = 0.03f;

        t.setOnTouchListener(new android.view.View.OnTouchListener() {
            float startY; int startLp; long lastTap;
            @Override public boolean onTouch(android.view.View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startY = e.getRawY(); startLp = lp.y;
                        long now = System.currentTimeMillis();
                        if (now - lastTap < 350) { hide(); return true; }   // 두 번 누르면 숨김
                        lastTap = now;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        lp.y = Math.max(0, startLp - (int) (e.getRawY() - startY));   // 아래 기준이라 반대로
                        try { wm.updateViewLayout(v, lp); } catch (Throwable ignored) { }
                        return true;
                    default:
                        return true;
                }
            }
        });
        try { wm.addView(t, lp); view = t; } catch (Throwable ignored) { view = null; }
    }

    void setText(String s) { if (view != null) view.setText(s); }

    void hide() {
        if (view == null) return;
        try { wm.removeView(view); } catch (Throwable ignored) { }
        view = null;
    }

    private int dp(int v) { return Math.round(v * ctx.getResources().getDisplayMetrics().density); }
}
