package com.coverfo.sori

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 💬 카톡 자동 보내기 — APK판(full)에만 들어 있는 접근성 서비스.
 * · 이용자가 설정 → 접근성에서 직접 켰을 때만 동작하고, 카카오톡(com.kakao.talk) 화면만 본다 (res/xml/kakao_auto.xml).
 * · 하는 일은 딱 하나: coverfo 소리가 카톡 '공유' 화면을 연 뒤(KakaoSend.current 가 있을 때만)
 *   ① 검색 칸에 이름을 넣고 ② 그 이름의 대화방을 누르고 ③ 확인(전송)을 누른다.
 * · 그 밖의 시간에는 아무것도 읽거나 누르지 않는다. 화면 내용은 저장하거나 보내지 않는다.
 * · 카톡 화면 구성이 바뀌면 못 찾을 수 있다 → 25초 안에 못 하면 공유 화면을 그대로 두고 이용자가 직접 고르게 한다.
 */
class KakaoAutoService : AccessibilityService() {

    private val h = Handler(Looper.getMainLooper())
    private var waitSince = 0L
    private val step = Runnable { act() }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (KakaoSend.current == null) return
        if (event?.packageName?.toString() != KakaoSend.PKG) return
        if (System.currentTimeMillis() > KakaoSend.deadline) {
            KakaoSend.finished(this, false)
            return
        }
        /* 화면이 바뀌는 동안 여러 번 오므로 잠깐 모아서 한 번만 */
        h.removeCallbacks(step)
        h.postDelayed(step, 450)
    }

    override fun onInterrupt() { }

    private fun act() {
        val job = KakaoSend.current ?: return
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != KakaoSend.PKG) return
        when (KakaoSend.phase) {
            0 -> {
                /* ① 검색 칸 → 이름 넣기 (검색 칸이 숨어 있으면 '검색' 단추를 먼저 누른다) */
                val edit = all(root).firstOrNull { it.isEditable && it.isVisibleToUser }
                if (edit != null) {
                    edit.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    val b = Bundle()
                    b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, job.to)
                    if (edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)) {
                        KakaoSend.phase = 1
                        waitSince = System.currentTimeMillis()
                        h.postDelayed(step, 900)
                    }
                } else {
                    val search = all(root).firstOrNull { n ->
                        n.isVisibleToUser && (n.contentDescription?.toString()?.contains("검색") == true || n.text?.toString() == "검색")
                    }
                    if (search != null) { click(search); h.postDelayed(step, 700) }
                }
            }
            1 -> {
                /* ② 이름이 똑같은 대화방 누르기 (검색 칸 자신은 빼고) */
                val name = job.to
                val hit = all(root).firstOrNull { n ->
                    !n.isEditable && n.isVisibleToUser && n.text?.toString()?.trim() == name
                } ?: all(root).firstOrNull { n ->
                    !n.isEditable && n.isVisibleToUser && n.text?.toString()?.trim()?.startsWith(name) == true
                }
                if (hit != null && click(hit)) {
                    KakaoSend.phase = 2
                    h.postDelayed(step, 700)
                } else if (System.currentTimeMillis() - waitSince > 6_000) {
                    KakaoSend.finished(this, false)     // 못 찾음 → 이용자가 직접 고르게
                }
            }
            2 -> {
                /* ③ 확인 · 전송 · 보내기 */
                val btn = findButton(root, listOf("확인", "전송", "보내기"))
                if (btn != null && click(btn)) {
                    KakaoSend.phase = 3
                    waitSince = System.currentTimeMillis()
                    h.postDelayed(step, 900)
                }
            }
            3 -> {
                /* 한 번 더 묻는 창이 뜨면 '전송'/'보내기' 를 누르고 끝 */
                val again = findButton(root, listOf("전송", "보내기"))
                if (again != null) click(again)
                KakaoSend.finished(this, true)
            }
        }
    }

    private fun findButton(root: AccessibilityNodeInfo, words: List<String>): AccessibilityNodeInfo? {
        val nodes = all(root).filter { it.isVisibleToUser && !it.isEditable }
        for (w in words) {
            val n = nodes.firstOrNull { x ->
                val t = (x.text ?: x.contentDescription)?.toString()?.trim() ?: ""
                t == w || (t.length <= w.length + 4 && t.contains(w))      // "확인 1" 같은 것
            }
            if (n != null) return n
        }
        return null
    }

    /* 누를 수 있는 부모까지 올라가서 누르기 */
    private fun click(n: AccessibilityNodeInfo): Boolean {
        var x: AccessibilityNodeInfo? = n
        var depth = 0
        while (x != null && depth < 6) {
            if (x.isClickable && x.isEnabled) return x.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            x = x.parent
            depth++
        }
        return false
    }

    private fun all(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        while (stack.isNotEmpty() && out.size < 600) {
            val n = stack.removeLast()
            out.add(n)
            for (i in n.childCount - 1 downTo 0) {
                n.getChild(i)?.let { stack.add(it) }
            }
        }
        return out
    }
}
