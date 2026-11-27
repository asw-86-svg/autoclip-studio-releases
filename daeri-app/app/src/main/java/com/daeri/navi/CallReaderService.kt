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
import com.daeri.navi.Store.bubbleMode
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

        /** 화면 앱이 바뀐 걸로 치지 않는 것 (상단바·키보드 등) */
        private val SYSTEM_PKGS = setOf(
            "com.android.systemui", "com.samsung.android.honeyboard", "com.google.android.inputmethod.korean",
            "com.google.android.inputmethod.latin", "com.samsung.android.app.aodservice",
        )

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
        // '대리 앱 볼 때만' 모드: 지금 화면 앱이 바뀌면 말풍선 보이기/숨기기
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg != null && pkg !in SYSTEM_PKGS) {
            fgIsCallApp = pkg != packageName && isCallApp(pkg)
            applyBubbleMode()
        }
        if (pkg != null && (pkg == packageName || pkg in IGNORE_PKGS || !isCallApp(pkg))) return
        // 대리 앱이 알림으로만 콜을 알려줄 때 (화면이 홈이어도)
        if (type == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            if (pkg != null) readNotification(event, pkg)
            return
        }
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
        else appLabel(pkg).let { label ->
            listOf("대리", "로지", "콜마너", "탁송", "기사", "드라이버", "커넥트").any { it in label } || "driver" in label.lowercase()
        }
    }

    private fun readNotification(event: AccessibilityEvent, pkg: String) {
        val texts = ArrayList<String>()
        event.text?.forEach { t -> t?.toString()?.takeIf { it.isNotBlank() }?.let { texts += it } }
        (event.parcelableData as? android.app.Notification)?.extras?.let { ex ->
            listOf("android.title", "android.text", "android.bigText", "android.subText", "android.summaryText").forEach { k ->
                ex.getCharSequence(k)?.toString()?.takeIf { it.isNotBlank() }?.let { texts += it }
            }
            ex.getCharSequenceArray("android.textLines")?.forEach { l -> l?.toString()?.takeIf { it.isNotBlank() }?.let { texts += it } }
        }
        val uniq = texts.distinct()
        if (uniq.isEmpty()) return
        bg.post {
            Store.addDump(this, appName(pkg) + " 알림", uniq)
            // 알림은 대리 앱에서 온 것만 받으므로 '출발: / 도착:' 같은 자유 글 형식도 허용
            val call = CallParser.parse(uniq, appName(pkg), allowFreeText = true)?.copy(pkg = pkg) ?: return@post
            if (call.originRaw.isBlank() || call.destRaw.isBlank()) return@post
            if (call.key == Store.current(this)?.key) return@post
            Store.setCurrent(this, call.copy(accepted = false))
            handler.post { showBubble(); pulse(); bubbleEta(call) }
        }
    }

    private val hm = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.KOREA)
    private fun status(msg: String) = Store.setStatus(this, "${hm.format(java.util.Date())} $msg")

    /** 뒤쪽 스레드에서 실행 */
    private fun scan() {
        try { scanInner() } catch (e: Throwable) { status("⚠️ 읽기 오류: ${e.javaClass.simpleName} ${e.message.orEmpty().take(80)}") }
    }

    private fun scanInner() {
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
            if (call.key == Store.current(this)?.key) {
                // 같은 콜: 말풍선이 숨겨져 있으면 다시 띄운다
                handler.post { if (bubble == null) { showBubble(); bubbleEta(call) } }
                return
            }
            Store.setCurrent(this, call)
            status("✅ ${appName(pkg)} 콜 읽음: ${call.originDisplay} → ${call.destDisplay}")
            handler.post { showBubble(); pulse(); expandedUntil = System.currentTimeMillis() + 10_000; bubbleEta(call) }
            return
        }
    }

    /** 새 콜이 잡히면 이 시각까지 말풍선을 크게 펼쳐 출발지·도착지까지 보여준다 */
    private var expandedUntil = 0L
    private var timeLines: List<String> = emptyList()
    private var bubbleCall: CallInfo? = null

    private fun renderBubble() {
        val c = bubbleCall ?: return
        val b = bubble ?: return
        val expanded = System.currentTimeMillis() < expandedUntil
        val lines = ArrayList<String>()
        if (expanded) {
            lines += "🔵 " + c.originDisplay.take(16)
            lines += "🔴 " + c.destDisplay.take(16)
        }
        lines += timeLines.ifEmpty { if (expanded) listOf("시간 계산 중…") else listOf("출/도") }
        b.text = lines.joinToString("\n")
        b.textSize = if (expanded) 15f else 14f
    }

    /** 말풍선: "출 - 🚶12분"(걸어서 20분 넘으면 🚌) / "출 → 도 🚗55분" */
    fun bubbleEta(call: CallInfo) {
        bubbleCall = call
        timeLines = emptyList()
        renderBubble()
        if (expandedUntil > System.currentTimeMillis()) {
            handler.postDelayed({ renderBubble() }, expandedUntil - System.currentTimeMillis() + 50)
        }
        Loc.current(this) { here ->
            bg.post {
                val o = Geo.resolve(this, call.originQueries)
                val d = Geo.resolve(this, call.destQueries)
                val lines = ArrayList<String>()
                if (here != null && o != null) {
                    val t = Route.toOrigin(Route.straightKm(here, o))
                    val min = (t.sec + 59) / 60
                    lines += "출 - " + if (t.walk) "🚶${min}분" else "🚌약${min}분"
                } else if (o == null) lines += "출 - 위치 못 찾음"
                else lines += "출 - 내 위치 모름"
                lines += if (o != null && d != null) "출 → 도 🚗${shortDur(Route.drive(this, o, d).sec)}" else "출 → 도 ${if (d == null) "도착지 못 찾음" else "-"}"
                if (o == null || d == null) status("📍 위치 못 찾음 (${if (o == null) "출발 " + call.originQueries.firstOrNull() else "도착 " + call.destQueries.firstOrNull()}) ${Geo.lastError}")
                handler.post {
                    if (Store.current(this)?.key == call.key) { timeLines = lines; renderBubble() }
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
    /** 지금 화면에 떠 있는 앱이 대리 앱인지 */
    @Volatile private var fgIsCallApp = true

    /** 설정(항상 / 대리 앱 볼 때만 / 안 띄움)에 맞춰 말풍선을 보이거나 숨긴다 */
    fun applyBubbleMode() {
        val mode = bubbleMode
        if (mode == 2) { hideBubble(); return }
        if (bubble == null && Store.current(this) != null) showBubble()
        bubble?.visibility = if (mode == 1 && !fgIsCallApp) View.GONE else View.VISIBLE
    }

    fun showBubble() {
        if (bubbleMode == 2) return
        if (bubble != null) { applyBubbleVisibility(); return }
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
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = dp(200) }
        bubbleLp = lp

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
                    if (moved) {
                        val (w, h) = screenSize()
                        Store.setBubbleFrac(this,
                            lp.x.toFloat() / (w - v.width).coerceAtLeast(1),
                            lp.y.toFloat() / (h - v.height).coerceAtLeast(1))
                        placeBubble()
                    } else openApp()
                }
            }
            true
        }
        runCatching { wm.addView(v, lp); bubble = v }
        // 크기가 정해진 뒤 저장된 비율 위치로, 내용이 바뀌어 크기가 바뀌어도 화면 안에 있도록
        v.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or2, ob ->
            if (r - l != or2 - ol || b - t != ob - ot) handler.post { placeBubble() }
        }
        v.post { placeBubble() }
        applyBubbleVisibility()
    }

    private fun applyBubbleVisibility() {
        bubble?.visibility = if (bubbleMode == 1 && !fgIsCallApp) View.GONE else View.VISIBLE
    }

    private var bubbleLp: WindowManager.LayoutParams? = null

    private fun screenSize(): Pair<Int, Int> {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        return if (android.os.Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val dm = android.util.DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels to dm.heightPixels
        }
    }

    /** 저장된 비율 위치를 지금 화면 크기에 맞춰 적용 (폴드를 접고 펴도 화면 안에) */
    private fun placeBubble() {
        val v = bubble ?: return
        val lp = bubbleLp ?: return
        val (w, h) = screenSize()
        val (fx, fy) = Store.bubbleFrac(this)
        val maxX = (w - v.width).coerceAtLeast(0)
        val maxY = (h - v.height - dp(24)).coerceAtLeast(0)
        lp.x = (fx * maxX).toInt().coerceIn(0, maxX)
        lp.y = (fy * maxY).toInt().coerceIn(dp(24).coerceAtMost(maxY), maxY)
        runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).updateViewLayout(v, lp) }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // 폴드 접기/펴기, 화면 회전 → 말풍선을 새 화면 안으로
        handler.postDelayed({ placeBubble() }, 300)
        handler.postDelayed({ placeBubble() }, 1200)
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
