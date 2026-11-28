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
import com.daeri.navi.Store.bubbleKeepSec
import com.daeri.navi.Store.bubbleMode
import com.daeri.navi.Store.bubbleVibrate
import com.daeri.navi.Store.detectPaused
import com.daeri.navi.Store.kakaoBubble
import kotlin.math.abs

/**
 * 접근성 서비스: 대리 프로그램 화면의 글자를 읽어 콜 정보를 뽑고,
 * 화면 위에 '출/도' 말풍선 버튼을 띄운다. (화면을 읽기만 하고 누르거나 수락하지 않음)
 */
class CallReaderService : AccessibilityService() {

    companion object {
        /** 제휴 콜이면 "출 → 도" 의 '도' 옆에 빨간 (제) */
        fun bubbleText(lines: List<String>, partner: Boolean): CharSequence {
            val sb = android.text.SpannableStringBuilder()
            lines.forEachIndexed { i, l ->
                if (i > 0) sb.append("\n")
                val mark = "출 → 도"
                if (partner && l.startsWith(mark)) {
                    sb.append(mark)
                    val st = sb.length
                    sb.append("(제)")
                    sb.setSpan(android.text.style.ForegroundColorSpan(0xFFFF3B30.toInt()), st, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.append(l.removePrefix(mark))
                } else sb.append(l)
            }
            return sb
        }

        /** 말풍선 모양 (캡처 테스트에서도 같은 모양을 쓰려고 따로 둠) */
        fun makeBubbleView(ctx: android.content.Context): TextView {
            fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()
            return TextView(ctx).apply {
                text = "출/도"
                textSize = 13f
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
        }

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
        // 앱을 다시 켰을 때 지난 콜로 빈 '출/도' 말풍선을 띄우지 않는다 (새 콜이 오면 뜸)
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
        // '대리 앱 볼 때만' 모드: 화면에 떠 있는 앱 창이 바뀌면 말풍선 보이기/숨기기
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            updateForeground()
        }
        if (pkg != null && (pkg == packageName || pkg in IGNORE_PKGS || !isCallApp(pkg))) return
        // 기사님이 대리 앱에서 [수락]/[즉시배차]를 누르면 → 지금 콜을 자동운행기록에
        if (type == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            if (pkg != null) {
                // 버튼 글자가 이벤트에 없으면(카카오 등) 누른 버튼 안쪽 글자까지 확인
                val words = ArrayList<String>()
                event.text?.forEach { words += it.toString() }
                event.contentDescription?.let { words += it.toString() }
                runCatching { event.source?.let { collect(it, words, 0, intArrayOf(0)) } }
                val n = words.map { it.replace(Regex("""\s+"""), "") }
                // 버튼 하나를 누른 경우만 (카드 전체를 누르면 안쪽에 [수락] 글자가 있어도 무시)
                // 버튼 글자가 수락 버튼 이름과 '정확히' 같을 때만 (확인·화면 글자로 짐작하지 않음)
                if (n.size <= 2 && n.any { it in ACCEPT_WORDS }) bg.post { acceptCurrent(pkg, "수락 버튼") }
            }
            schedule(450L)
            return
        }
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

    private val ACCEPT_WORDS = setOf("수락", "콜수락", "수락하기", "즉시배차", "단독배정확인", "단독배정수락")
    private val ASSIGNED_WORDS = listOf("배정완료", "도착알림", "고객과통화", "고객과메시지", "운행시작", "운행을시작", "배차완료")
    private val CANCEL_WORDS = listOf("취소되었습니다", "거부성공", "거부되었습니다", "배차가취소", "오더거부성공")

    /** 지금 콜을 '배정'으로 바꾸고 자동운행기록에 넣는다 */
    private fun acceptCurrent(pkg: String, why: String) {
        val cur = Store.current(this) ?: run { status("⚠️ 수락($why)을 봤지만 지금 콜이 없어요"); return }
        if (cur.accepted) return
        if (cur.pkg != pkg) { status("⚠️ 수락($why)을 봤지만 지금 콜이 다른 앱 콜이에요 (${cur.source})"); return }
        if (System.currentTimeMillis() - cur.capturedAt > 30 * 60_000L) { status("⚠️ 수락($why)을 봤지만 지금 콜이 30분 넘게 지났어요"); return }
        Store.setCurrent(this, cur.copy(accepted = true))
        status("🚗 ${cur.source} 콜 수락($why) → 자동운행기록에 넣었어요: ${cur.originDisplay} → ${cur.destDisplay}")
    }

    /** 배차취소·거부가 확인되면 방금 넣은 기록을 뺀다 */
    private fun cancelCurrent(pkg: String) {
        val cur = Store.current(this) ?: return
        if (!cur.accepted || cur.pkg != pkg || System.currentTimeMillis() - cur.capturedAt > 10 * 60_000L) return
        Store.unrecordCall(this, cur.key)
        status("↩ ${cur.source} 배차취소/거부 → 자동운행기록에서 뺐어요")
    }

    private val callAppCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /** 대리·탁송 앱만 읽는다. 카톡·문자·브라우저·채팅앱 등은 '출발/도착' 글자가 있어도 무시 */
    private fun isCallApp(pkg: String): Boolean = callAppCache.getOrPut(pkg) {
        // 최강대리기사 같은 기록·관리용 앱은 대리 앱이 아니므로 읽지 않는다
        if ("choigang" in pkg.lowercase() || appLabel(pkg).let { "최강" in it }) false
        else if (listOf("callmaner", "logi", "kakao.driver", "kakaomobility.driver", "daeri", "takson", "tmap.driver", "tmapdriver", "tmap.daeri", "idrive", "i-drive").any { it in pkg.lowercase() }) true
        else appLabel(pkg).let { label ->
            // 아이드라이브(앱 이름에 '드라이브')도 대리 앱
            listOf("대리", "로지", "콜마너", "탁송", "기사", "드라이버", "드라이브", "커넥트").any { it in label } ||
                "driver" in label.lowercase() || "idrive" in label.lowercase().replace("-", "").replace(" ", "")
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
            if (bubbleFor(pkg)) handler.post { showBubble(); pulse(); bubbleEta(call); keepBubble() }
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
            val joined = texts.joinToString("\n")
            val looks = CallParser.looksLikeCall(joined)
            Store.addDump(this, appName(pkg), texts)
            // "취소 되었습니다", "오더거부 성공" → 방금 자동배차로 넣은 기록 빼기
            val n = joined.replace(Regex("""\s+"""), "")
            // 카카오 '경유지 상세주소' 창 → 지금 콜의 경유지를 자세하게
            CallParser.parseViaDetail(texts)?.let { via ->
                Store.current(this)?.takeIf { it.pkg == pkg && it.via != via }?.let { cur ->
                    Store.updateVia(this, cur.key, via)
                    status("🟠 경유지 자세히: $via")
                }
            }
            if (CANCEL_WORDS.any { it in n }) cancelCurrent(pkg)
            // 카카오 '배정 완료' 화면인데 주소를 못 읽었으면: 방금 본 같은 앱 콜을 '수락'으로 (확실한 배정 화면 글자일 때만)
            val assignedScreen = "배정완료" in n && ("고객과통화" in n || "도착알림" in n || "고객과메시지" in n)
            if (!looks) { if (assignedScreen) acceptCurrent(pkg, "배정 완료 화면"); continue }
            val parsed = CallParser.parse(texts, appName(pkg), allowFreeText = false)?.copy(pkg = pkg)
            if (parsed == null || parsed.originRaw.isBlank() || parsed.destRaw.isBlank()) {
                if (assignedScreen) { acceptCurrent(pkg, "배정 완료 화면"); status("⚠️ 배정 완료 화면 주소를 못 읽어 방금 콜로 기록") }
                continue
            }
            // 자동배차 화면(로지 'N초 후 자동취소', I-Driver 'N초 자동배차 중')은 취소·거부 안 하면 배차 → 바로 기록
            val call = if (parsed.autoDispatch) parsed.copy(accepted = true) else parsed
            // 콜창을 닫은 뒤 나오는 목록·메뉴 화면 글자로 덮어쓰지 않도록 출발·도착 둘 다 있어야 새 콜
            if (call.originRaw.isBlank() || call.destRaw.isBlank()) continue
            val cur = Store.current(this)
            if (call.key == cur?.key && call.accepted && !cur.accepted) {
                Store.setCurrent(this, cur.copy(accepted = true, fare = call.fare.ifEmpty { cur.fare }, deposit = call.deposit.ifEmpty { cur.deposit }))
                status("🚗 ${call.source} 배정 → 자동운행기록에 넣었어요")
            }
            if (call.key == cur?.key) {
                // 같은 콜: 5분 유지 타이머 다시 시작, 말풍선이 숨겨져 있으면 다시 띄운다
                if (bubbleFor(pkg)) handler.post { keepBubble(); if (bubble == null) { showBubble(); bubbleEta(call) } }
                return
            }
            Store.setCurrent(this, call)
            saveRecvLoc(call)
            status("✅ ${appName(pkg)} 콜 읽음: ${call.originDisplay} → ${call.destDisplay}")
            if (bubbleFor(pkg)) handler.post { showBubble(); pulse(); expandedUntil = System.currentTimeMillis() + 10_000; bubbleEta(call); keepBubble() }
            return
        }
    }

    /** 콜 받은 곳 = 콜을 읽은 순간의 내 위치 → 동네 이름으로 저장 */
    private fun saveRecvLoc(call: CallInfo) {
        // 콜 상세를 열면 바로 핀이 찍히도록 출발·도착 위치를 미리 찾아 둔다 (찾은 위치는 저장됨)
        bg.post {
            runCatching { Geo.resolve(this, call.originQueries) }
            runCatching { Geo.resolve(this, call.destQueries) }
        }
        Loc.current(this) { here ->
            if (here == null) return@current
            bg.post {
                val area = runCatching { Geo.areaName(this, here.lat, here.lng) }.getOrDefault("")
                Store.setRecvLoc(this, call.key, area, here.lat, here.lng)
                // 콜 포인트: 받은 곳 + 출발지(손님 위치) 동네
                val o = runCatching { Geo.resolve(this, call.originQueries, allowArea = true) }.getOrNull()
                val oArea = o?.let { runCatching { Geo.areaName(this, it.lat, it.lng) }.getOrDefault("") }.orEmpty()
                runCatching {
                    CallPoints.upsert(this, call) { p ->
                        p.copy(recvLat = here.lat, recvLng = here.lng, recvArea = p.recvArea.ifEmpty { area },
                            oLat = o?.lat ?: p.oLat, oLng = o?.lng ?: p.oLng, oArea = oArea.ifEmpty { p.oArea })
                    }
                }
            }
        }
    }

    /** 카카오T 대리 콜은 설정에서 켜지 않으면 말풍선을 바꾸지 않는다 (콜카드에 지도가 이미 나옴) */
    fun bubbleFor(pkg: String) = kakaoBubble || "kakao" !in pkg.lowercase()

    /** 마지막으로 콜을 본 뒤 5분 동안 말풍선 내용 유지, 그 뒤엔 작은 '출/도'로 */
    // 설정한 시간(10초/30초/1분/5분)이 지나면 말풍선을 숨기고, 다음 콜이 오면 다시 띄운다
    private val resetBubble = Runnable {
        bubbleCall = null
        timeLines = emptyList()
        hideBubble()
    }

    private fun keepBubble() {
        handler.removeCallbacks(resetBubble)
        handler.postDelayed(resetBubble, bubbleKeepSec * 1000L)
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
        // 출발지·도착지 이름은 빼고 시간만 (새 콜 직후엔 '시간 계산 중…')
        lines += timeLines.ifEmpty { if (expanded) listOf("시간 계산 중…") else listOf("출/도") }
        b.text = bubbleText(lines, c.partner)
        b.textSize = 13f
    }

    /** 말풍선: "출 - 🚶12분"(걸어서 20분 넘으면 🚌) / "출 → 도 🚗55분" */
    fun bubbleEta(call: CallInfo) {
        bubbleCall = call
        timeLines = emptyList()
        // 콜 목록에서 열어 띄운 말풍선도 설정한 시간이 지나면 숨김
        keepBubble()
        renderBubble()
        if (expandedUntil > System.currentTimeMillis()) {
            handler.postDelayed({ renderBubble() }, expandedUntil - System.currentTimeMillis() + 50)
        }
        Loc.current(this) { here ->
            bg.post {
                val lines = ArrayList<String>()
                // 1줄: 출발지까지 — 콜카드에 "도보 1.0km / 약 16분"이 있으면 그대로
                val o = if (call.walkMin > 0) null else Geo.resolve(this, call.originQueries, allowArea = true)
                when {
                    call.walkMin in 1..Route.WALK_LIMIT_MIN -> lines += "출 - 🚶${call.walkMin}분"
                    call.walkMin > Route.WALK_LIMIT_MIN -> lines += "출 - 🚌약${(Route.transitSec(call.walkKm / 1.25) + 59) / 60}분"
                    here != null && o != null -> {
                        val t = Route.toOrigin(Route.straightKm(here, o))
                        val min = (t.sec + 59) / 60
                        lines += "출 - " + (if (t.walk) "🚶${min}분" else "🚌약${min}분") + if (o.label.endsWith("(대략)")) "(대략)" else ""
                    }
                    o == null -> lines += "출 - 위치 못 찾음"
                    else -> lines += "출 - 내 위치 모름"
                }
                // 2줄: 출발→도착 — 콜카드에 "약 18분 운행"이 있으면 그대로
                if (call.driveMin > 0) lines += "출 → 도 🚗${shortDur(call.driveMin * 60)}"
                else {
                    val o2 = o ?: Geo.resolve(this, call.originQueries, allowArea = true)
                    val d = Geo.resolve(this, call.destQueries, allowArea = true)
                    lines += if (o2 != null && d != null) "출 → 도 🚗${shortDur(Route.drive(this, o2, d).sec)}" else "출 → 도 ${if (d == null) "도착지 못 찾음" else "-"}"
                }
                val d = null as Geo.Pt?
                if (lines.any { "못 찾음" in it }) status("📍 위치 못 찾음 (출발 ${call.originQueries.firstOrNull()} / 도착 ${call.destQueries.firstOrNull()}) ${Geo.lastError}")
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
        "tmap" in pkg.lowercase() -> "T맵대리"
        else -> appLabel(pkg)
    }

    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    // ---------------- 말풍선 버튼 ----------------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    @SuppressLint("ClickableViewAccessibility")
    /**
     * 지금 실제로 떠 있는 앱 창들 중 대리 앱이 있는지 직접 확인한다.
     * (말풍선 자신의 변화나 토스트 같은 신호로 판단하면 스스로 숨어버리는 문제가 있었음)
     * 화면 분할이면 둘 중 하나라도 대리 앱이면 보인다.
     */
    private fun updateForeground() {
        bg.post {
            val appPkgs = runCatching {
                windows.filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }
                    .mapNotNull { it.root?.packageName?.toString() }
            }.getOrNull() ?: return@post
            if (appPkgs.isEmpty()) return@post
            val call = appPkgs.any { it != packageName && it !in SYSTEM_PKGS && isCallApp(it) }
            if (call != fgIsCallApp) {
                fgIsCallApp = call
                handler.post { applyBubbleMode() }
            }
        }
    }

    /** 지금 화면에 떠 있는 앱이 대리 앱인지 */
    @Volatile private var fgIsCallApp = true

    /** 설정(항상 / 대리 앱 볼 때만 / 안 띄움)에 맞춰 말풍선을 보이거나 숨긴다 */
    fun applyBubbleMode() {
        val mode = bubbleMode
        // 퇴근 상태(출근 전)면 말풍선을 띄우지 않는다
        if (mode == 2 || detectPaused) { hideBubble(); return }
        // 보이는 시간(10초 등)이 지나 숨긴 말풍선은 대리 앱을 다시 열어도 띄우지 않는다 — 다음 새 콜에서만
        if (bubble == null && bubbleCall != null) showBubble()
        bubble?.visibility = if (mode == 1 && !fgIsCallApp) View.GONE else View.VISIBLE
    }

    fun showBubble() {
        if (bubbleMode == 2 || detectPaused) return
        if (bubble != null) { applyBubbleVisibility(); return }
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val v = makeBubbleView(this)
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
        if (bubbleVibrate) runCatching {
            @Suppress("DEPRECATION")
            val vib = getSystemService(VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= 26) vib.vibrate(VibrationEffect.createOneShot(80, 120))
        }
    }
}
