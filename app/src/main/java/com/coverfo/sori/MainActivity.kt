package com.coverfo.sori

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.view.WindowInsets
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.browser.customtabs.CustomTabsIntent
import com.kakao.sdk.common.KakaoSdk
import com.kakao.sdk.navi.NaviClient
import com.kakao.sdk.navi.model.CoordType
import com.kakao.sdk.navi.model.Location
import com.kakao.sdk.navi.model.NaviOption
import org.json.JSONObject

/**
 * coverfo 소리 — coverfo.com 을 그대로 보여 주는 화면 + 음성 비서(SoriService) 연결.
 * · 웹 화면의 🗣️ 소리 → window.CoverfoApp.startSori(로그인키) → 이 앱의 마이크 서비스가 대화를 맡는다.
 *   (다른 앱 위에서도 계속 듣고 말하려면 웹이 아니라 앱의 '포그라운드 서비스'가 마이크를 써야 한다)
 * · 길안내·지도·쇼핑 같은 다른 앱 주소(intent:// · kakaomap:// · 외부 사이트)는 그 앱/브라우저로 넘긴다.
 */
class MainActivity : Activity() {

    companion object {
        @Volatile var current: MainActivity? = null
        private const val REQ_MIC = 11
        private const val REQ_NOTI = 12
        private const val REQ_FILE = 13
        private const val HOME = "https://coverfo.com/"
        /* Google 로그인이 끝나면 돌아올 coverfo 페이지 → 거기서 coverfosori://login 으로 앱에 넘겨줌 (Supabase Redirect URLs 에 등록 필요) */
        private const val APP_LOGIN = "https://coverfo.com/app-login.html"
        /* 이 앱 안(웹뷰)에서 그대로 여는 곳: coverfo 와 로그인(카카오·네이버·애플·수파베이스) */
        private val INSIDE = listOf("coverfo.com", "podotalk.kr", "supabase.co", "kauth.kakao.com", "accounts.kakao.com",
            "logins.daum.net", "nid.naver.com", "appleid.apple.com", "accounts.google.com")
    }

    lateinit var web: WebView
    @Volatile var resumed = false
    private var pendingToken: String? = null       // 마이크·다른 앱 위 권한을 받은 뒤 시작할 로그인 키
    private var waitOverlay = false
    private var webPermission: PermissionRequest? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingLogin: String? = null       // 화면을 다 불러온 뒤 넣을 로그인 값(JSON)
    private var kakaoKey = ""

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = this

        val root = FrameLayout(this)
        web = WebView(this)
        root.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        /* 안드로이드 15: 화면이 상태바 밑까지 그려지므로 그만큼 띄운다 */
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener { v, insets ->
                val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(b.left, b.top, b.right, b.bottom)
                insets
            }
        }

        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.setSupportMultipleWindows(false)
        s.userAgentString = s.userAgentString + " CoverfoSori/1"
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.addJavascriptInterface(Bridge(), "CoverfoApp")

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                return handleUrl(request.url.toString())
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                pendingLogin?.let { p ->
                    pendingLogin = null
                    /* 로그인 스크립트(supabase)가 준비될 시간을 조금 준다 */
                    web.postDelayed({ js("window.cfAppSession&&window.cfAppSession($p)") }, 1200)
                }
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            /* 웹의 🎙 음성 입력(마이크) */
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        request.grant(request.resources)
                    } else {
                        webPermission = request
                        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
                    }
                }
            }

            /* 사진 올리기 */
            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                return try {
                    startActivityForResult(params.createIntent(), REQ_FILE)
                    true
                } catch (e: Exception) {
                    fileCallback = null
                    false
                }
            }
        }

        takeLogin(intent)
        val start = intent?.dataString?.takeIf { it.startsWith("https://coverfo.com") } ?: HOME
        web.loadUrl(start)
    }

    /* ── 주소 처리: coverfo·로그인은 안에서, 나머지는 그 앱(카카오맵·카카오내비·쿠팡…)이나 브라우저로 ── */
    fun handleUrl(u: String): Boolean {
        val uri = Uri.parse(u)
        val scheme = (uri.scheme ?: "").lowercase()
        if (scheme == "http" || scheme == "https") {
            val host = (uri.host ?: "").lowercase()
            /* Google 로그인: Google 은 앱 안 화면(웹뷰) 로그인을 막는다 → 크롬 창(Custom Tab)에서 열고, 끝나면 APP_LOGIN 으로 돌아오게 */
            if (host.endsWith("supabase.co") && (uri.path ?: "").contains("/auth/v1/authorize") && uri.getQueryParameter("provider") == "google") {
                val b = uri.buildUpon().clearQuery()
                for (k in uri.queryParameterNames) {
                    if (k == "redirect_to") b.appendQueryParameter(k, APP_LOGIN)
                    else for (v in uri.getQueryParameters(k)) b.appendQueryParameter(k, v)
                }
                if (uri.getQueryParameter("redirect_to") == null) b.appendQueryParameter("redirect_to", APP_LOGIN)
                val target = b.build()
                try {
                    CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(this, target)
                } catch (e: Exception) {
                    openExternal(Intent(Intent.ACTION_VIEW, target))
                }
                return true
            }
            if (INSIDE.any { host == it || host.endsWith(".$it") }) return false
            openExternal(Intent(Intent.ACTION_VIEW, uri))
            return true
        }
        if (scheme == "intent") {
            try {
                val it = Intent.parseUri(u, Intent.URI_INTENT_SCHEME)
                it.addCategory(Intent.CATEGORY_BROWSABLE)
                it.component = null
                it.selector = null
                try {
                    startActivity(it)
                } catch (e: ActivityNotFoundException) {
                    val fb = it.getStringExtra("browser_fallback_url")
                    if (!fb.isNullOrEmpty()) {
                        if (!handleUrl(fb)) web.loadUrl(fb)
                    } else {
                        val pkg = it.`package`
                        if (!pkg.isNullOrEmpty()) openExternal(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")))
                    }
                }
            } catch (e: Exception) {
            }
            return true
        }
        if (scheme == "about" || scheme == "javascript" || scheme == "data" || scheme == "blob") return false
        /* tel: sms: kakaomap: kakaonavi-sdk: supertoss: market: … */
        openExternal(Intent(Intent.ACTION_VIEW, uri))
        return true
    }

    private fun openExternal(i: Intent) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        } catch (e: Exception) {
        }
    }

    /* ── 화면(웹)에 알리기: window.cfLiveNative('{...}') ── */
    fun emit(json: String) {
        runOnUiThread {
            try {
                web.evaluateJavascript("window.cfLiveNative&&window.cfLiveNative(" + JSONObject.quote(json) + ")", null)
            } catch (e: Exception) {
            }
        }
    }

    fun js(code: String) {
        runOnUiThread {
            try {
                web.evaluateJavascript(code, null)
            } catch (e: Exception) {
            }
        }
    }

    /* ── 소리 시작: 마이크 권한 → (처음 한 번) 다른 앱 위에 표시 권한 안내 → 서비스 ── */
    fun startSori(token: String) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingToken = token
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            /* 알림(대화 중 표시·끝내기 단추)은 거절해도 대화는 된다 — 한 번만 묻는다 */
            val p = getSharedPreferences("sori", MODE_PRIVATE)
            if (!p.getBoolean("askedNoti", false)) {
                p.edit().putBoolean("askedNoti", true).apply()
                pendingToken = token
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTI)
                return
            }
        }
        if (!Settings.canDrawOverlays(this)) {
            val p = getSharedPreferences("sori", MODE_PRIVATE)
            if (!p.getBoolean("overlayLater", false)) {
                AlertDialog.Builder(this)
                    .setTitle("다른 앱 위에 표시를 허용할까요?")
                    .setMessage("카카오맵·유튜브 같은 다른 앱을 쓰는 중에도 🗣️ 소리 아이콘을 띄우고, 말로 한 명령(길안내 등)을 바로 실행하려면 허용해 주세요.")
                    .setNegativeButton("다음에 하기") { _, _ ->
                        p.edit().putBoolean("overlayLater", true).apply()
                        launchService(token)
                    }
                    .setPositiveButton("권한 설정") { _, _ ->
                        pendingToken = token
                        waitOverlay = true
                        try {
                            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                        } catch (e: Exception) {
                            waitOverlay = false
                            launchService(token)
                        }
                    }
                    .setCancelable(false)
                    .show()
                return
            }
        }
        launchService(token)
    }

    private fun launchService(token: String) {
        pendingToken = null
        val i = Intent(this, SoriService::class.java).putExtra("token", token)
        try {
            startForegroundService(i)
        } catch (e: Exception) {
            emit("{\"type\":\"sori.state\",\"state\":\"ended\",\"msg\":\"시작하지 못했어요\",\"error\":" + JSONObject.quote(e.message ?: "") + "}")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val ok = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        if (requestCode == REQ_MIC) {
            webPermission?.let { if (ok) it.grant(it.resources) else it.deny() }
            webPermission = null
            val t = pendingToken
            if (t != null) {
                if (ok) startSori(t) else {
                    pendingToken = null
                    emit("{\"type\":\"sori.state\",\"state\":\"ended\",\"msg\":\"마이크 사용을 허용해 주세요\"}")
                }
            }
        } else if (requestCode == REQ_NOTI) {
            pendingToken?.let { startSori(it) }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_FILE) {
            fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            fileCallback = null
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        current = this
        SoriService.instance?.hideBubble()
        /* '권한 설정' 에서 돌아왔으면(허용 여부와 상관없이) 시작 */
        if (waitOverlay) {
            waitOverlay = false
            pendingToken?.let { launchService(it) }
        }
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        /* 다른 앱으로 넘어가면(대화 중일 때) 다른 앱 위에 🗣️ 아이콘 */
        SoriService.instance?.showBubble()
    }

    /* coverfosori://login?at=…&rt=… (또는 ?code=…) → 화면(웹)에 로그인 값 전달 */
    private fun takeLogin(i: Intent?): Boolean {
        val d = i?.data ?: return false
        if (d.scheme != "coverfosori" || d.host != "login") return false
        val j = JSONObject()
        d.getQueryParameter("code")?.let { j.put("code", it) }
        d.getQueryParameter("at")?.let { j.put("at", it) }
        d.getQueryParameter("rt")?.let { j.put("rt", it) }
        if (j.length() == 0) return true
        pendingLogin = j.toString()
        return true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (takeLogin(intent)) {
            /* 앱이 이미 열려 있으면 바로 넣는다 */
            pendingLogin?.let { p -> pendingLogin = null; js("window.cfAppSession&&window.cfAppSession($p)") }
            return
        }
        if (intent.getBooleanExtra("openChat", false)) js("window.cfLiveOpen&&window.cfLiveOpen()")
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }

    /* ── 웹 → 앱 (window.CoverfoApp) ── */
    inner class Bridge {
        @JavascriptInterface
        fun startSori(token: String) {
            runOnUiThread { this@MainActivity.startSori(token) }
        }

        @JavascriptInterface
        fun stopSori() {
            SoriService.instance?.shutdown("대화를 마쳤어요", null, false)
        }

        @JavascriptInterface
        fun send(json: String) {
            SoriService.instance?.sendRaw(json)
        }

        @JavascriptInterface
        fun setToken(token: String) {
            SoriService.instance?.token = token
        }

        @JavascriptInterface
        fun isRunning(): Boolean = SoriService.instance?.running == true

        @JavascriptInterface
        fun isForeground(): Boolean = resumed

        /* 카카오내비 바로 안내 (카카오 공식 Android SDK). 돌려주는 값: ok · noapp(카카오내비 없음) · err:… */
        @JavascriptInterface
        fun kakaoNavi(name: String, lat: String, lng: String, nativeKey: String): String {
            return try {
                if (kakaoKey != nativeKey) {
                    KakaoSdk.init(applicationContext, nativeKey)
                    kakaoKey = nativeKey
                }
                if (!NaviClient.instance.isKakaoNaviInstalled(this@MainActivity)) return "noapp"
                val i = NaviClient.instance.navigateIntent(
                    Location(name, lng, lat),
                    NaviOption(coordType = CoordType.WGS84)
                )
                runOnUiThread {
                    try { startActivity(i) } catch (e: Exception) { }
                }
                "ok"
            } catch (e: Throwable) {
                "err:" + (e.message ?: e.javaClass.simpleName)
            }
        }

        @JavascriptInterface
        fun version(): String = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }
    }
}
