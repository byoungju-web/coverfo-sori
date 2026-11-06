package com.coverfo.soria

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * 🖐 coverfo 화면 도우미 (접근성 서비스) — coverfo 소리 앱이 시킬 때만 일한다 (CmdReceiver).
 * · tap(text): 지금 앞에 있는 앱 화면에서 그 글자가 보이는 단추·항목을 찾아 누른다.
 *   결제·구매·송금·보내기·삭제 같은 글자가 있는 것은 누르지 않는다 (BLOCK).
 * · back(): 그 앱 안에서 '뒤로' 한 번.
 * · v2 scroll(dir): 목록을 아래(down)·위(up)로 한 화면. 누를 글자가 화면에 없으면 tapSearch 가 아래로 넘기며 3번까지 더 찾는다.
 * · read(): 보이는 글자를 위→아래 순서로 1500자까지 (비밀번호 칸은 빼고). 저장하지 않는다.
 * · 화면 이벤트는 모으지 않는다 (onAccessibilityEvent 는 비워 둠).
 */
class ScreenService : AccessibilityService() {

    companion object {
        @Volatile var instance: ScreenService? = null
        private const val SORI = "com.coverfo.sori"
        private const val MAX_NODES = 3000
        private val BLOCK = Regex("(결제|구매|구입|주문하기|송금|이체|보내기|전송|삭제|탈퇴|해지|계좌|비밀번호|pay|purchase|buy|checkout|send|transfer|delete|remove)", RegexOption.IGNORE_CASE)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { }
    override fun onInterrupt() { }

    private class Item(val node: AccessibilityNodeInfo, val label: String, val r: Rect)

    private fun norm(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    private fun label(n: AccessibilityNodeInfo): String {
        val t = n.text?.toString()?.trim().orEmpty()
        if (t.isNotEmpty()) return t
        return n.contentDescription?.toString()?.trim().orEmpty()
    }

    /** 앞에 있는 앱 화면의 보이는 글자들 */
    private fun collect(root: AccessibilityNodeInfo): List<Item> {
        val out = ArrayList<Item>()
        val stack = ArrayList<AccessibilityNodeInfo>()
        stack.add(root)
        var seen = 0
        while (stack.isNotEmpty() && seen < MAX_NODES) {
            val n = stack.removeAt(stack.size - 1)
            seen++
            if (!n.isVisibleToUser) continue
            if (!n.isPassword) {
                val l = label(n)
                if (l.isNotEmpty()) {
                    val r = Rect()
                    n.getBoundsInScreen(r)
                    if (r.width() > 0 && r.height() > 0) out.add(Item(n, l, r))
                }
            }
            for (i in n.childCount - 1 downTo 0) {
                val c = n.getChild(i) ?: continue
                stack.add(c)
            }
        }
        return out
    }

    private fun front(): AccessibilityNodeInfo? = rootInActiveWindow

    private fun appOf(root: AccessibilityNodeInfo): String = root.packageName?.toString().orEmpty()

    private fun appName(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) { pkg }

    /** 누를 글자를 정리 ("영금물회 열어줘" → "영금물회") */
    fun clean(text: String): String = norm(text.replace(Regex("(을|를)?\\s*(눌러\\s*줘|눌러|선택해\\s*줘|선택|열어\\s*줘|열어|클릭)\\s*$"), ""))

    /** 지금 화면에 그 글자가 보이는지 */
    fun visible(text: String): Boolean {
        val root = front() ?: return false
        val q = clean(text)
        if (q.isEmpty()) return false
        return collect(root).any { val l = norm(it.label); l == q || l.contains(q) }
    }

    /** v2: 목록 넘기기. 화면에서 가장 큰 넘길 수 있는 칸에 '앞으로/뒤로' — 없으면 손가락으로 쓸어 올리기·내리기 */
    fun scroll(dir: String): JSONObject {
        val root = front() ?: return JSONObject().put("ok", false).put("note", "No screen right now.")
        val pkg = appOf(root)
        if (pkg == SORI) return JSONObject().put("ok", false).put("coverfo", true).put("note", "coverfo is in front.")
        val down = dir != "up"
        var best: AccessibilityNodeInfo? = null
        var area = 0
        val stack = ArrayList<AccessibilityNodeInfo>()
        stack.add(root)
        var seen = 0
        while (stack.isNotEmpty() && seen < MAX_NODES) {
            val n = stack.removeAt(stack.size - 1)
            seen++
            if (!n.isVisibleToUser) continue
            if (n.isScrollable) {
                val r = Rect()
                n.getBoundsInScreen(r)
                val a = r.width() * r.height()
                if (a > area) { area = a; best = n }
            }
            for (i in n.childCount - 1 downTo 0) { val c = n.getChild(i) ?: continue; stack.add(c) }
        }
        val b = best
        if (b != null && b.performAction(if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
            return JSONObject().put("ok", true).put("app", appName(pkg))
        }
        val dm = resources.displayMetrics
        val x = dm.widthPixels / 2f
        val y1 = dm.heightPixels * (if (down) 0.78f else 0.35f)
        val y2 = dm.heightPixels * (if (down) 0.35f else 0.78f)
        val p = Path().apply { moveTo(x, y1); lineTo(x, y2) }
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 350)).build()
        val ok = try { dispatchGesture(g, null, null) } catch (e: Exception) { false }
        return JSONObject().put("ok", ok).put("app", appName(pkg)).put("swiped", true)
    }

    fun tap(text: String): JSONObject {
        val root = front() ?: return JSONObject().put("ok", false).put("note", "No screen to read right now.")
        val pkg = appOf(root)
        if (pkg == SORI) return JSONObject().put("ok", false).put("coverfo", true).put("note", "coverfo is in front.")
        val q = clean(text)
        if (q.isEmpty()) return JSONObject().put("ok", false).put("note", "Say which text to press.")
        val items = collect(root)
        var best: Item? = null
        var bs = 0
        for (it in items) {
            val l = norm(it.label)
            if (l.isEmpty()) continue
            val sc = when {
                l == q -> 100
                l.contains(q) -> 80 - minOf(30, l.length - q.length)
                q.contains(l) && l.length >= 2 -> 50
                else -> 0
            }
            if (sc > bs || (sc == bs && sc > 0 && best != null && it.r.top < best.r.top)) { bs = sc; best = it }
        }
        val hit = best
        if (hit == null || bs <= 0) {
            val names = JSONArray()
            items.map { it.label }.filter { it.length in 2..30 && !BLOCK.containsMatchIn(it) }.distinct().take(15).forEach { names.put(it) }
            return JSONObject().put("ok", false).put("note", "No matching text on the screen.").put("candidates", names).put("app", appName(pkg))
        }
        if (BLOCK.containsMatchIn(hit.label)) {
            return JSONObject().put("ok", false).put("blocked", true).put("note", "This looks like a payment/send/delete button. The user must press it themselves.").put("label", hit.label)
        }
        /* 누를 수 있는 것: 그 항목 또는 위로 6단계 안의 누를 수 있는 칸 */
        var n: AccessibilityNodeInfo? = hit.node
        var depth = 0
        while (n != null && depth < 7) {
            if (n.isClickable && n.isEnabled) {
                val l2 = label(n)
                if (l2.isNotEmpty() && BLOCK.containsMatchIn(l2)) {
                    return JSONObject().put("ok", false).put("blocked", true).put("note", "This looks like a payment/send/delete button. The user must press it themselves.").put("label", l2)
                }
                if (n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return JSONObject().put("ok", true).put("pressed", hit.label).put("app", appName(pkg))
                }
            }
            n = n.parent
            depth++
        }
        /* 누를 수 있는 칸이 없으면 그 글자 가운데를 톡 */
        val p = Path().apply { moveTo(hit.r.exactCenterX(), hit.r.exactCenterY()) }
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 60)).build()
        val ok = try { dispatchGesture(g, null, null) } catch (e: Exception) { false }
        return JSONObject().put("ok", ok).put("pressed", hit.label).put("app", appName(pkg)).put("tapped", true)
    }

    fun back(): JSONObject {
        val root = front()
        val pkg = root?.let { appOf(it) }.orEmpty()
        if (pkg == SORI) return JSONObject().put("ok", false).put("coverfo", true).put("note", "coverfo is in front.")
        val ok = performGlobalAction(GLOBAL_ACTION_BACK)
        return JSONObject().put("ok", ok).put("app", if (pkg.isEmpty()) "" else appName(pkg))
    }

    fun read(): JSONObject {
        val root = front() ?: return JSONObject().put("ok", false).put("note", "No screen to read right now.")
        val pkg = appOf(root)
        if (pkg == SORI) return JSONObject().put("ok", false).put("coverfo", true).put("note", "coverfo is in front.")
        val items = collect(root).sortedWith(compareBy<Item>({ it.r.top / 24 }, { it.r.left }))
        val sb = StringBuilder()
        var last = ""
        for (it in items) {
            val t = it.label.replace(Regex("\\s+"), " ").trim()
            if (t.isEmpty() || t == last) continue
            last = t
            if (sb.length + t.length + 3 > 1500) break
            if (sb.isNotEmpty()) sb.append(" · ")
            sb.append(t)
        }
        return JSONObject().put("ok", true).put("app", appName(pkg)).put("text", sb.toString())
    }
}
