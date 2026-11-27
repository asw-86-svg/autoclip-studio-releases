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
    private var scanScheduled = false
    private var lastScan = 0L
    private var bubble: TextView? = null

    override fun onServiceConnected() {
        instance = this
        if (Store.current(this) != null) showBubble()
    }

    override fun onDestroy() {
        hideBubble()
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg == packageName || pkg in IGNORE_PKGS || detectPaused) return
        // 카카오대리처럼 잠깐 떴다 사라지는 팝업도 잡도록: 바로 한 번 읽고, 이어지는 변화는 0.3초 뒤 한 번 더
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastScan >= 250) {
            lastScan = now
            scan()
        } else if (!scanScheduled) {
            scanScheduled = true
            handler.postDelayed({ scanScheduled = false; lastScan = android.os.SystemClock.uptimeMillis(); scan() }, 300)
        }
    }

    private fun isCallApp(pkg: String) =
        listOf("callmaner", "logi", "kakao.driver", "kakaomobility", "daeri", "driver").any { it in pkg }

    private fun scan() {
        // 지금 화면 + 다른 앱 위에 떠 있는 팝업 창까지 모두 읽는다
        val roots = LinkedHashMap<Int, AccessibilityNodeInfo>()
        rootInActiveWindow?.let { roots[it.windowId] = it }
        runCatching { windows.forEach { w -> w.root?.let { r -> roots.putIfAbsent(r.windowId, r) } } }
        for (root in roots.values) {
            val pkg = root.packageName?.toString().orEmpty()
            if (pkg.isEmpty() || pkg == packageName || pkg in IGNORE_PKGS) continue
            val texts = ArrayList<String>()
            collect(root, texts, 0)
            if (texts.isEmpty()) continue
            val looks = CallParser.looksLikeCall(texts.joinToString("\n"))
            if (looks || isCallApp(pkg)) Store.addDump(this, appName(pkg), texts)
            if (!looks) continue
            val call = CallParser.parse(texts, appName(pkg)) ?: continue
            if (call.originRaw.isBlank() && call.destRaw.isBlank()) continue
            if (call.key == Store.current(this)?.key) return
            Store.setCurrent(this, call)
            showBubble()
            pulse()
            bubbleEta(call)
            return
        }
    }

    /** 말풍선에 출발지까지 도보 시간 표시 (멀면 차 시간) */
    private fun bubbleEta(call: CallInfo) {
        bubble?.text = "출/도"
        Loc.current(this) { here ->
            if (here == null) return@current
            Thread {
                val o = Geo.resolve(this, call.originQueries) ?: return@Thread
                val km = Route.straightKm(here, o)
                val walkMin = (Route.walkSec(km) + 59) / 60
                val label = if (walkMin <= 60) "🚶${walkMin}분" else "🚗${(Route.drive(this, here, o).sec + 59) / 60}분"
                handler.post { if (Store.current(this)?.key == call.key) bubble?.text = "출/도\n$label" }
            }.start()
        }
    }

    private fun collect(node: AccessibilityNodeInfo?, out: MutableList<String>, depth: Int) {
        if (node == null || depth > 60) return
        val t = node.text?.toString()?.takeIf { it.isNotBlank() }
            ?: node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
        if (t != null) out += t
        for (i in 0 until node.childCount) collect(node.getChild(i), out, depth + 1)
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
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xEE1E6FE0.toInt())
                setStroke(dp(2), 0xFFFFFFFF.toInt())
            }
            elevation = dp(6).toFloat()
        }
        val (bx, by) = Store.bubblePos(this)
        val lp = WindowManager.LayoutParams(
            dp(72), dp(72),
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
