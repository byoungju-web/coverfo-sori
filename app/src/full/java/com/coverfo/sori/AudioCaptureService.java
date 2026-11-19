package com.coverfo.sori;
/* coverfo 소리 v28 — 포도랑 앱(kr.podolang.app)의 오디오·스트림 번역을 옮겨 온 파일 (APK판 full 에만 들어감) */

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * v1.0.3 오디오·스트림 번역 — 폰에서 나오는 다른 앱 소리(유튜브·영상·방송)를 받아 자막으로 번역합니다.
 *
 * · Android 10(API 29) 이상 · 쓸 때마다 사용자가 '화면 녹화·공유' 창에서 허락해야 합니다 (MediaProjection).
 * · 소리를 막아 둔 앱과 통화 소리는 받을 수 없습니다 (안드로이드 규칙: 미디어·게임·분류 없음 소리만).
 * · 웹 라이브 청취와 같은 서버(/api/live)로 12초 이하 조각을 보내고, 같은 요금(1분 0.5 크레딧)이 나갑니다.
 * · 조용한 조각은 보내지 않습니다 (요금 없음). 소리는 번역에만 쓰고 저장하지 않습니다.
 */
public class AudioCaptureService extends Service {

    public static final String EXTRA_CODE = "code";
    public static final String EXTRA_DATA = "data";
    public static final String EXTRA_FROM = "from";
    public static final String EXTRA_TO = "to";
    public static final String EXTRA_TK = "tk";
    public static final String ACTION_STOP = "com.coverfo.sori.AUDIO_STOP";

    static final String API = "https://podolang.hasin7jk.workers.dev/api/live";
    private static final String CH_ID = "cf_audio";
    private static final int NOTI_ID = 3107;
    private static final int RATE = 16000;            // 16kHz 모노 16비트 — 12초 ≈ 384KB (서버 한도 900KB 안)
    private static final int FRAME = RATE / 10;       // 0.1초씩 읽기
    private static final double VOICE = 0.012;       // 이보다 크면 소리가 있다고 봄
    private static final int MAX_MS = 11800, MIN_MS = 4000, QUIET_MS = 700, EMPTY_MS = 6000;

    /* ---------- 화면(AudioActivity)과 주고받기 ---------- */
    /* v1.0.7 · 소리 v29: 항목마다 제목(번호 · 받은 시각)과 삭제 — at = 받은 시각, no = 번호(지워도 안 바뀜) */
    public static class Seg { public String text, tr; public long at; public int no; Seg(String a, String b) { text = a; tr = b; at = System.currentTimeMillis(); no = ++segNo; } }
    static int segNo = 0;
    static void removeSeg(Seg g) { synchronized (SEGS) { SEGS.remove(g); } fire(); }
    public interface Listener { void onState(); }
    static final List<Seg> SEGS = new ArrayList<>();
    static volatile boolean running = false;
    static volatile String status = "";
    static volatile double sec = 0, charged = 0;
    private static Listener listener;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    static void setListener(Listener l) { listener = l; }
    private static void fire() { MAIN.post(() -> { if (listener != null) listener.onState(); }); }

    private MediaProjection projection;
    private AudioRecord record;
    private Thread reader;
    private ExecutorService sender;
    private SubtitleOverlay overlay;
    private String from = "AUTO", to = "KO", tk = "";

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) { stopAll(null); return START_NOT_STICKY; }
        if (running) return START_NOT_STICKY;

        from = val(intent.getStringExtra(EXTRA_FROM), "AUTO");
        to = val(intent.getStringExtra(EXTRA_TO), "KO");
        tk = val(intent.getStringExtra(EXTRA_TK), "");

        // 안드로이드 14 이상: 허락 결과로 MediaProjection 을 받기 전에 먼저 포그라운드(mediaProjection 종류)로 올라가야 합니다.
        Notification n = buildNoti(getString(R.string.audioNotiStart));
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTI_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        else startForeground(NOTI_ID, n);

        if (Build.VERSION.SDK_INT < 29) { stopAll(getString(R.string.audioNeed10)); return START_NOT_STICKY; }
        try {
            int code = intent.getIntExtra(EXTRA_CODE, 0);
            Intent data = Build.VERSION.SDK_INT >= 33
                    ? intent.getParcelableExtra(EXTRA_DATA, Intent.class)
                    : intent.getParcelableExtra(EXTRA_DATA);
            MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            projection = mpm.getMediaProjection(code, data);
            if (projection == null) { stopAll(getString(R.string.audioDenied)); return START_NOT_STICKY; }
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() { stopAll(null); }   // 사용자가 빠른 설정에서 공유를 끈 경우
            }, MAIN);
            startCapture();
        } catch (Throwable e) {
            stopAll(getString(R.string.audioFail) + " (" + e.getClass().getSimpleName() + ")");
        }
        return START_NOT_STICKY;
    }

    @SuppressLint("MissingPermission")   // RECORD_AUDIO 는 AudioActivity 에서 먼저 받습니다
    private void startCapture() {
        AudioPlaybackCaptureConfiguration cfg = new AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build();
        AudioFormat fmt = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build();
        int minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        record = new AudioRecord.Builder()
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(Math.max(minBuf, FRAME * 2 * 4))
                .setAudioPlaybackCaptureConfig(cfg)
                .build();
        if (record.getState() != AudioRecord.STATE_INITIALIZED) { stopAll(getString(R.string.audioFail)); return; }

        synchronized (SEGS) { SEGS.clear(); segNo = 0; }
        sec = 0; charged = 0;
        running = true;
        status = getString(R.string.audioListening);
        sender = Executors.newSingleThreadExecutor();   // 보낸 순서대로 결과를 놓으려고 한 줄로 보냅니다
        overlay = new SubtitleOverlay(this);
        if (AudioActivity.overlayWanted(this)) overlay.show();
        record.startRecording();
        reader = new Thread(this::readLoop, "cf-audio");
        reader.start();
        fire();
    }

    private void readLoop() {
        short[] buf = new short[FRAME];
        ByteArrayOutputStream seg = new ByteArrayOutputStream();
        long segStart = System.currentTimeMillis(), lastVoice = 0;
        boolean voiced = false;
        while (running && record != null) {
            int n = record.read(buf, 0, buf.length);
            if (n <= 0) continue;
            double s = 0;
            for (int i = 0; i < n; i++) { double v = buf[i] / 32768.0; s += v * v; }
            double rms = Math.sqrt(s / n);
            long now = System.currentTimeMillis();
            if (rms > VOICE) { voiced = true; lastVoice = now; }
            for (int i = 0; i < n; i++) { seg.write(buf[i] & 0xff); seg.write((buf[i] >> 8) & 0xff); }
            long len = now - segStart, quiet = lastVoice > 0 ? now - lastVoice : len;
            boolean cut = (voiced && len > MIN_MS && quiet > QUIET_MS) || len >= MAX_MS || (!voiced && len > EMPTY_MS);
            if (cut) {
                if (voiced) { final byte[] pcm = seg.toByteArray(); sender.execute(() -> send(pcm)); }
                seg.reset(); segStart = now; lastVoice = 0; voiced = false;
            }
        }
    }

    /* 조각 하나를 WAV 로 만들어 서버로 보냅니다 */
    private void send(byte[] pcm) {
        if (!running || pcm.length < RATE) return;   // 0.5초보다 짧으면 버림
        String boundary = "----coverfo" + System.nanoTime();
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(API).openConnection();
            c.setConnectTimeout(15000); c.setReadTimeout(45000);
            c.setDoOutput(true); c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            DataOutputStream o = new DataOutputStream(c.getOutputStream());
            field(o, boundary, "sourceLang", from);
            field(o, boundary, "targetLang", to);
            field(o, boundary, "uid", tk);
            field(o, boundary, "via", "app-audio");
            o.writeBytes("--" + boundary + "\r\nContent-Disposition: form-data; name=\"audio\"; filename=\"audio.wav\"\r\nContent-Type: audio/wav\r\n\r\n");
            o.write(wav(pcm));
            o.writeBytes("\r\n--" + boundary + "--\r\n");
            o.flush(); o.close();
            int code = c.getResponseCode();
            InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
            String body = read(in);
            JSONObject d = new JSONObject(body.isEmpty() ? "{}" : body);
            sec += d.optDouble("sec", 0);
            charged += d.optDouble("charged", 0);
            if (code >= 400 || d.has("error")) {
                String err = d.optString("error", getString(R.string.audioFail));
                if (d.optBoolean("needCredit", false) || code == 402) { stopAll(err); return; }
                status = err; fire();
                return;
            }
            String text = d.optString("text", "").trim(), tr = d.optString("translated", "").trim();
            if (!text.isEmpty()) {
                synchronized (SEGS) { SEGS.add(new Seg(text, tr)); if (SEGS.size() > 200) SEGS.remove(0); }
                String show = tr.isEmpty() ? text : tr;
                MAIN.post(() -> { if (overlay != null) overlay.setText(show); });
                updateNoti(show);
            }
            status = getString(R.string.audioListening);
            if (d.optBoolean("stop", false)) { stopAll(getString(R.string.audioNoCredit)); return; }
            fire();
        } catch (Throwable e) {
            status = getString(R.string.audioNet); fire();
        } finally { if (c != null) c.disconnect(); }
    }

    private static void field(DataOutputStream o, String b, String k, String v) throws Exception {
        o.writeBytes("--" + b + "\r\nContent-Disposition: form-data; name=\"" + k + "\"\r\n\r\n");
        o.write(v.getBytes(StandardCharsets.UTF_8));
        o.writeBytes("\r\n");
    }

    private static byte[] wav(byte[] pcm) {
        int total = pcm.length + 36, byteRate = RATE * 2;
        byte[] h = new byte[44];
        System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, h, 0, 4);
        le32(h, 4, total);
        System.arraycopy("WAVEfmt ".getBytes(StandardCharsets.US_ASCII), 0, h, 8, 8);
        le32(h, 16, 16); le16(h, 20, 1); le16(h, 22, 1); le32(h, 24, RATE); le32(h, 28, byteRate); le16(h, 32, 2); le16(h, 34, 16);
        System.arraycopy("data".getBytes(StandardCharsets.US_ASCII), 0, h, 36, 4);
        le32(h, 40, pcm.length);
        byte[] out = new byte[44 + pcm.length];
        System.arraycopy(h, 0, out, 0, 44);
        System.arraycopy(pcm, 0, out, 44, pcm.length);
        return out;
    }
    private static void le32(byte[] b, int i, int v) { b[i] = (byte) v; b[i + 1] = (byte) (v >> 8); b[i + 2] = (byte) (v >> 16); b[i + 3] = (byte) (v >> 24); }
    private static void le16(byte[] b, int i, int v) { b[i] = (byte) v; b[i + 1] = (byte) (v >> 8); }

    private static String read(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] t = new byte[4096]; int n;
        while ((n = in.read(t)) > 0) b.write(t, 0, n);
        in.close();
        return b.toString("UTF-8");
    }

    /* ---------- 알림 ---------- */
    private Notification buildNoti(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH_ID) == null) {
            NotificationChannel ch = new NotificationChannel(CH_ID, getString(R.string.audioTitle), NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        int pf = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent open = PendingIntent.getActivity(this, 1, new Intent(this, AudioActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), pf);
        PendingIntent stop = PendingIntent.getService(this, 2, new Intent(this, AudioCaptureService.class).setAction(ACTION_STOP), pf);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH_ID) : new Notification.Builder(this);
        b.setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getString(R.string.audioTitle))
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, getString(R.string.audioStop), stop).build());
        return b.build();
    }
    private void updateNoti(String text) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            nm.notify(NOTI_ID, buildNoti(text));
        } catch (Throwable ignored) { }
    }

    /* ---------- 끝내기 ---------- */
    private void stopAll(String why) {
        running = false;
        if (why != null) status = why; else status = getString(R.string.audioStopped);
        try { if (record != null) { record.stop(); record.release(); } } catch (Throwable ignored) { }
        record = null;
        try { if (projection != null) projection.stop(); } catch (Throwable ignored) { }
        projection = null;
        if (sender != null) { sender.shutdown(); sender = null; }
        MAIN.post(() -> { if (overlay != null) { overlay.hide(); overlay = null; } });
        fire();
        try { if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE); else stopForeground(true); } catch (Throwable ignored) { }
        stopSelf();
    }

    @Override public void onDestroy() { if (running) stopAll(null); super.onDestroy(); }

    private static String val(String v, String d) { return v == null || v.trim().isEmpty() ? d : v.trim(); }
}
