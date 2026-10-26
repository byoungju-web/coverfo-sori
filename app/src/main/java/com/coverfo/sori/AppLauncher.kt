package com.coverfo.sori

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject

/**
 * ② 말로 폰 앱 열기 ("카톡 켜 줘", "유튜브 열어 줘").
 * · 스토어용(BuildConfig.STORE=true): 아래 KNOWN 목록의 앱만 연다 — 구글 플레이 정책상 '모든 앱 목록 보기' 권한을 쓰지 않는다.
 *   (AndroidManifest.xml 의 <queries> 목록과 같게 유지)
 * · APK용: 목록에 없으면 폰에 설치된 앱 이름 전체에서 찾는다 (QUERY_ALL_PACKAGES — src/full/AndroidManifest.xml).
 */
object AppLauncher {

    /* 부르는 이름들 → 패키지 */
    private val KNOWN: List<Pair<List<String>, String>> = listOf(
        listOf("카카오톡", "카톡", "kakaotalk") to "com.kakao.talk",
        listOf("유튜브", "youtube") to "com.google.android.youtube",
        listOf("유튜브뮤직", "유튜브 뮤직", "youtubemusic") to "com.google.android.apps.youtube.music",
        listOf("카카오맵", "카맵") to "net.daum.android.map",
        listOf("카카오내비", "카카오 내비") to "com.locnall.KimGiSa",
        listOf("카카오T", "카카오티", "카카오택시", "카카오 택시") to "com.kakao.taxi",
        listOf("카카오페이") to "com.kakaopay.app",
        listOf("카카오뱅크") to "com.kakaobank.channel",
        listOf("네이버") to "com.nhn.android.search",
        listOf("네이버지도", "네이버 지도", "네이버맵") to "com.nhn.android.nmap",
        listOf("네이버밴드", "밴드") to "com.nhn.android.band",
        listOf("파파고") to "com.naver.labs.translator",
        listOf("구글지도", "구글 지도", "구글맵") to "com.google.android.apps.maps",
        listOf("티맵") to "com.skt.tmap.ku",
        listOf("쿠팡이츠") to "com.coupang.mobile.eats",
        listOf("쿠팡") to "com.coupang.mobile",
        listOf("배달의민족", "배민") to "com.sampleapp",
        listOf("요기요") to "com.fineapp.yogiyo",
        listOf("당근마켓", "당근") to "com.towneers.www",
        listOf("토스") to "viva.republica.toss",
        listOf("인스타그램", "인스타") to "com.instagram.android",
        listOf("페이스북") to "com.facebook.katana",
        listOf("틱톡") to "com.zhiliaoapp.musically",
        listOf("넷플릭스") to "com.netflix.mediaclient",
        listOf("멜론") to "com.iloen.melon",
        listOf("지니뮤직", "지니") to "com.ktmusic.geniemusic",
        listOf("크롬") to "com.android.chrome",
        listOf("삼성인터넷", "삼성 인터넷") to "com.sec.android.app.sbrowser",
        listOf("지메일", "gmail") to "com.google.android.gm",
        listOf("11번가") to "com.elevenst",
        listOf("지마켓", "g마켓") to "com.ebay.kr.gmarket",
        listOf("무신사") to "com.musinsa.store",
        listOf("야놀자") to "com.cultsotry.yanolja.nativeapp",
        listOf("여기어때") to "kr.goodchoice.abouthere",
        listOf("코레일톡", "코레일") to "com.korail.talk"
    )

    /* 기본 기능 (폰마다 앱이 달라서 '종류'로 연다) */
    private fun systemIntent(n: String): Intent? = when {
        n.contains("카메라") -> Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        n.contains("전화") || n.contains("키패드") -> Intent(Intent.ACTION_DIAL)
        n.contains("문자") || n.contains("메시지") -> Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING)
        n.contains("설정") -> Intent(Settings.ACTION_SETTINGS)
        n.contains("갤러리") || n.contains("사진") || n.contains("앨범") -> Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY)
        n.contains("캘린더") || n.contains("달력") || n.contains("일정") -> Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR)
        n.contains("계산기") -> Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALCULATOR)
        n.contains("연락처") || n.contains("주소록") -> Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CONTACTS)
        n.contains("이메일") || n.contains("메일") -> Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_EMAIL)
        n.contains("알람") || n.contains("시계") -> Intent(AlarmClock.ACTION_SHOW_ALARMS)
        else -> null
    }

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
        .removeSuffix("앱").removeSuffix("어플")

    /** 이름으로 앱을 연다. 돌려주는 값(JSON)은 음성 비서에게 그대로 전달된다 */
    fun open(ctx: Context, rawName: String, store: Boolean, foreground: Boolean): JSONObject {
        val n = norm(rawName)
        val out = JSONObject()
        if (n.isEmpty()) return out.put("ok", false).put("note", "No app name.")
        val pm = ctx.packageManager

        /* 1) 알려진 앱 */
        val known = KNOWN.firstOrNull { (names, _) -> names.any { norm(it) == n } }
            ?: KNOWN.firstOrNull { (names, _) -> names.any { val k = norm(it); k.length >= 2 && (n.contains(k) || k.contains(n)) } }
        var intent: Intent? = null
        var label = rawName
        if (known != null) {
            intent = pm.getLaunchIntentForPackage(known.second)
            label = known.first.first()
            if (intent == null) {
                return out.put("ok", false).put("app", label).put("note", "This app is not installed on the phone.")
            }
        }
        /* 2) 기본 기능 */
        if (intent == null) {
            systemIntent(n)?.let { si ->
                if (si.resolveActivity(pm) != null) { intent = si; label = rawName }
            }
        }
        /* 3) APK용: 설치된 앱 이름 전체에서 */
        if (intent == null && !store) {
            val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            var best: Pair<String, String>? = null
            var bestScore = 0
            for (ri in pm.queryIntentActivities(main, 0)) {
                val lab = ri.loadLabel(pm).toString()
                val l = norm(lab)
                if (l.isEmpty()) continue
                val sc = when {
                    l == n -> 100
                    l.startsWith(n) || n.startsWith(l) -> 80
                    l.contains(n) || (l.length >= 2 && n.contains(l)) -> 60
                    else -> 0
                }
                if (sc > bestScore) { bestScore = sc; best = lab to ri.activityInfo.packageName }
            }
            best?.let { (lab, pkg) -> intent = pm.getLaunchIntentForPackage(pkg); label = lab }
        }
        val i = intent
        if (i == null) {
            val names = JSONArray()
            KNOWN.take(20).forEach { names.put(it.first.first()) }
            return out.put("ok", false).put("note", if (store) "Not in the list of apps this version can open." else "No installed app with that name.")
                .put("examples", names)
        }
        return try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            ctx.startActivity(i)
            out.put("ok", true).put("app", label)
            /* 화면이 다른 앱일 때 백그라운드에서 앱을 여는 것은 '다른 앱 위에 표시' 권한이 있어야 안드로이드가 허락한다 */
            if (!foreground && !Settings.canDrawOverlays(ctx)) out.put("note", "May not open because the 'display over other apps' permission is off.")
            out
        } catch (e: Exception) {
            out.put("ok", false).put("app", label).put("note", "Could not open: " + (e.message ?: ""))
        }
    }
}
