package com.coverfo.sori

import android.net.Uri

/**
 * v27 🛒 구글 플레이용(store) 설치 파일에서만 — 플레이 밖에서 앱을 내려받거나 업데이트하게 하는 링크를 뺀다.
 * (구글 플레이 정책: 플레이 앱은 플레이 밖에서 자기 자신을 설치·업데이트하게 하면 안 됨)
 * · coverfo.com/app (앱 받기 화면) · .apk 파일 · GitHub 릴리스(coverfo-sori) 주소를 열지 않고, 그 링크를 화면에서 숨긴다.
 * · APK판(full)·웹(coverfo.com)은 아무것도 바뀌지 않는다 (BuildConfig.STORE 가 false 면 모두 그냥 통과).
 * · 크레딧 충전 · 채팅 · 통역톡은 그대로 둔다.
 */
object StoreGuard {
    private val PATH = Regex("^/app(/|$)", RegexOption.IGNORE_CASE)

    /** 플레이 앱에서 열면 안 되는 주소인가 */
    fun blocked(u: String?): Boolean {
        if (!BuildConfig.STORE || u.isNullOrEmpty()) return false
        return try {
            val uri = Uri.parse(u)
            val host = (uri.host ?: "").lowercase()
            val path = uri.path ?: "/"
            if (path.lowercase().endsWith(".apk")) return true
            if (host == "github.com" && path.contains("coverfo-sori", true) && path.contains("release", true)) return true
            val ours = host == "coverfo.com" || host.endsWith(".coverfo.com") || host == "coverfo.world" || host.endsWith(".coverfo.world")
            ours && PATH.containsMatchIn(path)
        } catch (e: Exception) { false }
    }

    /** 화면에 넣는 스크립트 (여러 번 넣어도 한 번만 동작) — 앱 받기 링크만 숨기고 누름을 막는다 */
    val JS: String = """
(function(){
  if(window.__cfStore) return; window.__cfStore=1;
  var d=document; try{ d.documentElement.classList.add('cf-store'); }catch(e){}
  var st=d.createElement('style'); st.id='cf-store-css';
  st.textContent='a[href="/app"],a[href^="/app?"],a[href^="/app#"],a[href="https://coverfo.com/app"],a[href^="https://coverfo.com/app?"],a[href*=".apk"],a[href*="github.com/byoungju-web/coverfo-sori/releases"]{display:none !important}';
  (d.head||d.documentElement).appendChild(st);
  var BAD=/^\/app(\/|$)/i, OURS=/(^|\.)coverfo\.(com|world)$/i;
  window.addEventListener('click',function(e){
    var t=e.target; if(!t||!t.closest) return;
    var a=t.closest('a[href]'); if(!a) return;
    try{ var u=new URL(a.getAttribute('href'),location.href);
      if((OURS.test(u.hostname)&&BAD.test(u.pathname))||/\.apk$/i.test(u.pathname)||(/github\.com$/i.test(u.hostname)&&/coverfo-sori\/releases/i.test(u.pathname))){ e.preventDefault(); e.stopImmediatePropagation(); } }catch(x){}
  },true);
})();
"""
}
