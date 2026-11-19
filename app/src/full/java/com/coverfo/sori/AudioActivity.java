package com.coverfo.sori;
/* coverfo 소리 v28 — 포도랑 앱(kr.podolang.app)의 오디오·스트림 번역을 옮겨 온 파일 (APK판 full 에만 들어감) */

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * v1.0.3 오디오·스트림 번역 화면.
 * coverfo 소리 v28 (APK판만) — 포도랑 앱의 같은 화면을 옮겨 왔습니다.
 * coverfo.com [AI에게 물어보기 → 오디오·스트림 번역 → 소리 앱에서 시작] 이 이 화면을 엽니다.
 *   주소: coverfosori://audio?from=EN&to=KO&tk=(coverfo 표 cf.… · 12시간)
 *   번역 · 요금은 포도랑 서버(/api/live)가 그 표로 coverfo 지갑에서 뺍니다 (1분 0.5 크레딧).
 * 시작 → 마이크(소리 받기) 권한 → '화면 녹화·공유' 허락 → AudioCaptureService 가 소리를 받아 번역합니다.
 */
public class AudioActivity extends Activity implements AudioCaptureService.Listener {

    private static final int REQ_PERM = 31, REQ_CAPTURE = 32;
    private static final String PREF = "cf_audio";
    private static final int C1 = 0xFF0284C7, BG = 0xFFF0F9FF, FG = 0xFF111827, SUB = 0xFF6B7280;

    private String from = "AUTO", to = "KO", tk = "";
    private TextView langView, statusView, metaView;
    private Button mainBtn;
    private CheckBox overlayBox;
    private LinearLayout list;
    private ScrollView scroll;
    private int shown = -1;

    static boolean overlayWanted(Context c) {
        return c.getSharedPreferences(PREF, MODE_PRIVATE).getBoolean("overlay", false) && SubtitleOverlay.allowed(c);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        readIntent(getIntent());
        buildUi();
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        readIntent(i);
        refresh();
    }

    /* coverfosori://audio?from=..&to=..&tk=..  (없으면 지난번 값) */
    private void readIntent(Intent i) {
        SharedPreferences p = getSharedPreferences(PREF, MODE_PRIVATE);
        from = p.getString("from", "AUTO"); to = p.getString("to", "KO"); tk = p.getString("tk", "");
        Uri u = i == null ? null : i.getData();
        if (u != null && "coverfosori".equals(u.getScheme()) && "audio".equals(u.getHost())) {
            String f = clean(u.getQueryParameter("from")), t = clean(u.getQueryParameter("to")), k = u.getQueryParameter("tk");
            if (!f.isEmpty()) from = f;
            if (!t.isEmpty()) to = t;
            if (k != null && k.matches("[A-Za-z0-9._-]{8,200}")) tk = k;
            p.edit().putString("from", from).putString("to", to).putString("tk", tk).apply();
        }
    }
    private static String clean(String v) { return v == null ? "" : v.replaceAll("[^A-Za-z-]", "").toUpperCase(Locale.ROOT); }

    /* ---------- 화면 (코드로 그립니다) ---------- */
    private void buildUi() {
        if (Build.VERSION.SDK_INT >= 21) { getWindow().setStatusBarColor(C1); }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setBackgroundColor(C1);
        head.setPadding(dp(12), dp(14), dp(16), dp(14));
        TextView back = text("‹ " + getString(R.string.audioBack), 15, Color.WHITE, true);
        back.setPadding(dp(8), dp(4), dp(16), dp(4));
        back.setOnClickListener(v -> finish());
        TextView title = text(getString(R.string.audioTitle), 18, Color.WHITE, true);
        head.addView(back);
        head.addView(title);
        root.addView(head);

        scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(16), dp(16), dp(24));
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout card = card();
        langView = text("", 16, FG, true);
        statusView = text("", 15, FG, true);
        statusView.setPadding(0, dp(10), 0, 0);
        metaView = text("", 13, SUB, false);
        mainBtn = new Button(this);
        mainBtn.setAllCaps(false);
        mainBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        mainBtn.setTextColor(Color.WHITE);
        mainBtn.setTypeface(Typeface.DEFAULT_BOLD);
        mainBtn.setOnClickListener(v -> { if (AudioCaptureService.running) stopCapture(); else startFlow(); });
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        bl.topMargin = dp(14);
        overlayBox = new CheckBox(this);
        overlayBox.setText(getString(R.string.audioOverlay));
        overlayBox.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        overlayBox.setTextColor(FG);
        overlayBox.setPadding(dp(4), dp(10), 0, dp(4));
        overlayBox.setOnClickListener(v -> onOverlayToggle());
        card.addView(langView);
        card.addView(statusView);
        card.addView(metaView);
        card.addView(mainBtn, bl);
        card.addView(overlayBox);
        body.addView(card);

        TextView note = text(getString(R.string.audioNote), 13, SUB, false);
        note.setLineSpacing(0, 1.35f);
        note.setPadding(dp(4), dp(12), dp(4), dp(12));
        body.addView(note);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        body.addView(list);

        setContentView(root);
        refresh();
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(16), dp(16), dp(14));
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.WHITE);
        g.setCornerRadius(dp(20));
        g.setStroke(dp(1), 0xFFE0F2FE);
        c.setBackground(g);
        return c;
    }
    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private static String langName(String code) {
        if ("AUTO".equals(code)) return "자동 감지";
        String c = code.toLowerCase(Locale.ROOT);
        if (c.startsWith("zh")) return "중국어";
        String n = new Locale(c).getDisplayLanguage(Locale.KOREAN);
        return n == null || n.isEmpty() || n.equalsIgnoreCase(c) ? code : n;
    }

    private void refresh() {
        boolean on = AudioCaptureService.running;
        langView.setText(langName(from) + "  →  " + langName(to));
        String st = AudioCaptureService.status;
        statusView.setText((on ? "● " : "") + (st == null || st.isEmpty() ? getString(R.string.audioIdle) : st));
        statusView.setTextColor(on ? 0xFFDC2626 : FG);
        double s = AudioCaptureService.sec, c = AudioCaptureService.charged;
        metaView.setText(s > 0 || c > 0
                ? String.format(Locale.ROOT, "%d:%02d · 크레딧 %.1f", (int) s / 60, (int) s % 60, c)
                : getString(R.string.audioPrice));
        mainBtn.setText(on ? getString(R.string.audioStop) : getString(R.string.audioStart));
        GradientDrawable g = new GradientDrawable();
        g.setColor(on ? 0xFFDC2626 : C1);
        g.setCornerRadius(dp(16));
        mainBtn.setBackground(g);
        overlayBox.setChecked(overlayWanted(this));

        List<AudioCaptureService.Seg> segs;
        synchronized (AudioCaptureService.SEGS) { segs = new ArrayList<>(AudioCaptureService.SEGS); }
        if (segs.size() != shown) {
            list.removeAllViews();
            for (AudioCaptureService.Seg g2 : segs) {
                LinearLayout c2 = card();
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = dp(10);
                c2.addView(text(g2.text, 13, SUB, false));
                TextView d = text(g2.tr == null || g2.tr.isEmpty() ? g2.text : g2.tr, 17, FG, true);
                d.setPadding(0, dp(4), 0, 0);
                c2.addView(d);
                list.addView(c2, lp);
            }
            if (segs.size() > shown && shown >= 0) scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
            shown = segs.size();
        }
    }

    @Override public void onState() { refresh(); }
    @Override protected void onStart() { super.onStart(); AudioCaptureService.setListener(this); refresh(); }
    @Override protected void onStop() { AudioCaptureService.setListener(null); super.onStop(); }

    /* ---------- 시작 순서 ---------- */
    private void startFlow() {
        if (Build.VERSION.SDK_INT < 29) { toast(getString(R.string.audioNeed10)); return; }
        if (tk.isEmpty()) { toast(getString(R.string.audioNeedLogin)); return; }
        List<String> need = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!need.isEmpty()) { requestPermissions(need.toArray(new String[0]), REQ_PERM); return; }
        askCapture();
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req != REQ_PERM) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) askCapture();
        else toast(getString(R.string.audioNeedMic));
    }

    private void askCapture() {
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        try { startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE); }
        catch (Throwable e) { toast(getString(R.string.audioFail)); }
    }

    @Override
    protected void onActivityResult(int req, int code, Intent data) {
        super.onActivityResult(req, code, data);
        if (req != REQ_CAPTURE) return;
        if (code != RESULT_OK || data == null) { toast(getString(R.string.audioDenied)); return; }
        Intent s = new Intent(this, AudioCaptureService.class)
                .putExtra(AudioCaptureService.EXTRA_CODE, code)
                .putExtra(AudioCaptureService.EXTRA_DATA, data)
                .putExtra(AudioCaptureService.EXTRA_FROM, from)
                .putExtra(AudioCaptureService.EXTRA_TO, to)
                .putExtra(AudioCaptureService.EXTRA_TK, tk);
        AudioCaptureService.status = getString(R.string.audioNotiStart);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(s); else startService(s);
        refresh();
    }

    private void stopCapture() {
        startService(new Intent(this, AudioCaptureService.class).setAction(AudioCaptureService.ACTION_STOP));
    }

    /* ---------- 다른 앱 위 자막 ---------- */
    private void onOverlayToggle() {
        SharedPreferences p = getSharedPreferences(PREF, MODE_PRIVATE);
        boolean want = overlayBox.isChecked();
        if (want && !SubtitleOverlay.allowed(this)) {
            p.edit().putBoolean("overlay", true).apply();
            toast(getString(R.string.audioOverlayPerm));
            try { startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))); }
            catch (Throwable ignored) { }
            overlayBox.setChecked(false);
            return;
        }
        p.edit().putBoolean("overlay", want).apply();
        if (AudioCaptureService.running) toast(getString(R.string.audioOverlayNext));
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
}
