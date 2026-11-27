package com.daeri.navi

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import com.daeri.navi.Store.detectPaused
import kotlin.math.abs

/**
 * 접근성 서비스: 대리 프로그램 화면의 글자를 읽어 콜 정보를 뽑고,
 * 화면 위에 '출/도' 말풍선 버튼을 띄운다. (화면을 읽기만 하고 누르거나 수락하지 않음)
 */
class CallReaderService : AccessibilityService() {

    companion object {
        @Volatile var instance: CallReaderService? = null
            private set

        private val IGNORE_PKGS = setOf(
            "com.android.systemui", "com.sec.android.app.launcher", "com.google.android.apps.nexuslauncher",
            "com.samsung.android.honeyboard", "com.google.android.inputmethod.korean",
            "com.google.android.inputmethod.latin", "com.samsung.android.app.aodservice",
        )
    }

    private val handler = Handler(Looper.getMainLooper())
    // 화면 읽기는 뒤쪽 전용 스레드에서 (대리내비·대리앱 화면이 버벅이지 않게)
    private val scanThread = android.os.HandlerThread("daeri-scan").apply { start() }
    private val bg = Handler(scanThread.looper)
    @Volatile private var pendingAt = 0L
    private val scanTask = Runnable { pendingAt = 0L; scan() }
    private var bubble: TextView? = null

    override fun onServiceConnected() {
        instance = this
        if (Store.current(this) != null) showBubble()
    }

    override fun onDestroy() {
        hideBubble()
        scanThread.quitSafely()
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || detectPaused) return
        val type = event.eventType
        if (type == AccessibilityEvent.TYPE_VIEW_SCROLLED) return
        val pkg = event.packageName?.toString()
        if (pkg != null && (pkg == packageName || pkg in IGNORE_PKGS || !isCallApp(pkg))) return
        // 새 화면·팝업이 뜨면 거의 바로(0.08초), 화면 안 내용이 바뀌는 건 모아서 0.45초에 한 번
        val windowChange = type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        schedule(if (windowChange) 80L else 450L)
    }

    private fun schedule(delay: Long) {
        val at = android.os.SystemClock.uptimeMillis() + delay
        val p = pendingAt
        if (p != 0L && p <= at) return // 이미 더 빨리 읽기로 예약됨
        bg.removeCallbacks(scanTask)
        pendingAt = at
        bg.postAtTime(scanTask, at)
    }

    private val callAppCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /** 대리·탁송 앱만 읽는다. 카톡·문자·브라우저·채팅앱 등은 '출발/도착' 글자가 있어도 무시 */
    private fun isCallApp(pkg: String): Boolean = callAppCache.getOrPut(pkg) {
        if (listOf("callmaner", "logi", "kakao.driver", "kakaomobility.driver", "daeri", "takson").any { it in pkg.lowercase() }) true
        else appLabel(pkg).let { label -> listOf("대리", "로지", "콜마너", "탁송", "기사").any { it in label } }
    }

    /** 뒤쪽 스레드에서 실행 */
    private fun scan() {
        // 지금 화면 + 다른 앱 위에 떠 있는 팝업 창까지 모두 읽는다
        val roots = LinkedHashMap<Int, AccessibilityNodeInfo>()
        runCatching { rootInActiveWindow?.let { roots[it.windowId] = it } }
        runCatching { windows.forEach { w -> w.root?.let { r -> roots.putIfAbsent(r.windowId, r) } } }
        for (root in roots.values) {
            val pkg = root.packageName?.toString().orEmpty()
            if (pkg.isEmpty() || pkg == packageName || pkg in IGNORE_PKGS) continue
            if (!isCallApp(pkg)) continue
            val texts = ArrayList<String>()
            runCatching { collect(root, texts, 0, intArrayOf(0)) }
            if (texts.isEmpty()) continue
            val looks = CallParser.looksLikeCall(texts.joinToString("\n"))
            Store.addDump(this, appName(pkg), texts)
            if (!looks) continue
            val call = CallParser.parse(texts, appName(pkg), allowFreeText = false)?.copy(pkg = pkg) ?: continue
            if (call.originRaw.isBlank() && call.destRaw.isBlank()) continue
            if (call.key == Store.current(this)?.key) return
            Store.setCurrent(this, call)
            handler.post { showBubble(); pulse(); bubbleEta(call) }
            return
        }
    }

    /** 말풍선: "출 - 🚶12분"(걸어서 20분 넘으면 🚌) / "출 → 도 🚗55분" */
    fun bubbleEta(call: CallInfo) {
        bubble?.text = "출/도"
        Loc.current(this) { here ->
            bg.post {
                val o = Geo.resolve(this, call.originQueries)
                val d = Geo.resolve(this, call.destQueries)
                val lines = ArrayList<String>()
                if (here != null && o != null) {
                    val t = Route.toOrigin(Route.straightKm(here, o))
                    val min = (t.sec + 59) / 60
                    lines += "출 - " + if (t.walk) "🚶${min}분" else "🚌약${min}분"
                }
                if (o != null && d != null) lines += "출 → 도 🚗${shortDur(Route.drive(this, o, d).sec)}"
                if (lines.isNotEmpty()) handler.post {
                    if (Store.current(this)?.key == call.key) bubble?.text = lines.joinToString("\n")
                }
            }
        }
    }

    private fun shortDur(sec: Int): String {
        val m = (sec + 59) / 60
        return if (m >= 60) "${m / 60}시간${if (m % 60 > 0) " ${m % 60}분" else ""}" else "${m}분"
    }

    private fun collect(node: AccessibilityNodeInfo?, out: MutableList<String>, depth: Int, count: IntArray) {
        if (node == null || depth > 60 || count[0] > 900) return
        count[0]++
        val t = node.text?.toString()?.takeIf { it.isNotBlank() }
            ?: node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
        if (t != null) out += t
        for (i in 0 until node.childCount) collect(node.getChild(i), out, depth + 1, count)
    }

    private fun appName(pkg: String): String = when {
        "callmaner" in pkg -> "콜마너"
        "logi" in pkg -> "로지"
        "kakao" in pkg -> "카카오T 대리"
        else -> appLabel(pkg)
    }

    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    // ---------------- 말풍선 버튼 ----------------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    @SuppressLint("ClickableViewAccessibility")
    fun showBubble() {
        if (bubble != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val v = TextView(this).apply {
            text = "출/도"
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setLineSpacing(dp(2).toFloat(), 1f)
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            minWidth = dp(64); minHeight = dp(64)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(32).toFloat()
                setColor(0xEE1E6FE0.toInt())
                setStroke(dp(2), 0xFFFFFFFF.toInt())
            }
            elevation = dp(6).toFloat()
        }
        val (bx, by) = Store.bubblePos(this)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START; x = bx; y = by }

        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (abs(dx) > dp(8) || abs(dy) > dp(8)) moved = true
                    if (moved) { lp.x = startX + dx.toInt(); lp.y = startY + dy.toInt(); wm.updateViewLayout(v, lp) }
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) Store.setBubblePos(this, lp.x, lp.y) else openApp()
                }
            }
            true
        }
        runCatching { wm.addView(v, lp); bubble = v }
    }

    fun hideBubble() {
        val v = bubble ?: return
        runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v) }
        bubble = null
    }

    private fun openApp() {
        startActivity(
            Intent(this, MainActivity::class.java).putExtra("open", "call")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
    }

    private fun pulse() {
        bubble?.animate()?.scaleX(1.3f)?.scaleY(1.3f)?.setDuration(180)?.withEndAction {
            bubble?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(180)?.start()
        }?.start()
        runCatching {
            @Suppress("DEPRECATION")
            val vib = getSystemService(VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= 26) vib.vibrate(VibrationEffect.createOneShot(80, 120))
        }
    }
}
