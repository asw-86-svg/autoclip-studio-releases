package com.daeri.navi

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.daeri.navi.MapLauncher.App
import com.daeri.navi.MapLauncher.Mode
import com.daeri.navi.Store.bubbleMode
import com.daeri.navi.Store.detectPaused
import com.daeri.navi.Store.homeAddress
import com.daeri.navi.Store.kakaoBubble
import com.daeri.navi.Store.kakaoKey
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private enum class Screen { HOME, CALL, CALLS, LOG, DAY, PASTE, SETTINGS }

    // ---------- 색 ----------
    private val black = 0xFF000000.toInt()
    private val bg = 0xFF2B2B2E.toInt()
    private val cardBg = 0xFF1E1E21.toInt()
    private val innerBg = 0xFF28282C.toInt()
    private val line = 0xFF3A3A3F.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val grey = 0xFF9A9AA0.toInt()
    private val blue = 0xFF3D85F0.toInt()
    private val red = 0xFFE5484D.toInt()
    private val kakaoYellow = 0xFFFEE500.toInt()
    private val naverGreen = 0xFF03C75A.toInt()

    private lateinit var topBar: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var root: LinearLayout
    private lateinit var footer: LinearLayout

    private var screen = Screen.HOME
    private var lastScreen: Screen? = null
    private var logMonth: Calendar = Calendar.getInstance()
    private var logListMode = false
    private var day: Calendar = Calendar.getInstance()
    private var showDump = false
    private var callsShown = 50
    private var dayBack = Screen.LOG
    private var askedLocation = false

    private val dayKeyFmt = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA)
    private fun dayKey(t: Long) = dayKeyFmt.format(Date(t))
    private fun won(n: Int) = String.format(Locale.KOREA, "%,d", n)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(black); setPadding(dp(8), dp(10), dp(16), dp(10))
        }
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(24)) }
        scroll = ScrollView(this).apply { setBackgroundColor(bg); addView(root) }
        footer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF1A2130.toInt()) }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(bg)
            addView(topBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            addView(footer, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        })
        Store.cleanupJunk(this)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
        render()
    }

    override fun onResume() {
        super.onResume()
        Store.cleanupJunk(this)
        if (!Loc.hasPerm(this) && !askedLocation) {
            askedLocation = true
            requestPermissions(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION), 1)
        }
        render()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when (screen) {
            Screen.HOME -> super.onBackPressed()
            Screen.DAY -> go(dayBack)
            else -> go(Screen.HOME)
        }
    }

    private fun go(s: Screen) { screen = s; render() }

    private fun handleIntent(i: Intent?) {
        if (i?.getStringExtra("open") == "call") screen = Screen.CALL
        if (i?.action == Intent.ACTION_SEND) {
            i.getStringExtra(Intent.EXTRA_TEXT)?.let { if (parseAndSet(it, "공유")) screen = Screen.CALL }
        }
    }

    private fun parseAndSet(text: String, source: String): Boolean {
        val c = CallParser.parse(text.lines(), source)
        if (c == null || (c.originRaw.isBlank() && c.destRaw.isBlank())) {
            toast("출발지/도착지를 찾지 못했어요. '출발:' '도착:' 처럼 적혀 있는지 확인해 주세요")
            return false
        }
        Store.setCurrent(this, c)
        CallReaderService.instance?.let { it.showBubble(); it.bubbleEta(c) }
        return true
    }

    // =================== 화면 그리기 ===================
    private fun render() {
        root.removeAllViews()
        footer.removeAllViews(); footer.visibility = View.GONE
        when (screen) {
            Screen.HOME -> renderHome()
            Screen.CALL -> renderCallScreen()
            Screen.CALLS -> renderCalls()
            Screen.LOG -> renderLog()
            Screen.DAY -> renderDay()
            Screen.PASTE -> renderPaste()
            Screen.SETTINGS -> renderSettings()
        }
        if (lastScreen != screen) scroll.post { scroll.scrollTo(0, 0) }
        lastScreen = screen
    }

    private fun setTop(title: String, back: Boolean, right: String? = null, onRight: (() -> Unit)? = null) {
        topBar.removeAllViews()
        topBar.addView(TextView(this).apply {
            text = if (back) "‹" else ""; textSize = 34f; setTextColor(blue); gravity = Gravity.CENTER
            if (back) setOnClickListener { onBackPressed() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        topBar.addView(TextView(this).apply {
            text = title; textSize = 20f; setTextColor(blue); setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        topBar.addView(TextView(this).apply {
            text = right.orEmpty(); textSize = 16f; setTextColor(blue); setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER or Gravity.END
            if (onRight != null) setOnClickListener { onRight() }
        }, LinearLayout.LayoutParams(dp(96), dp(48)))
    }

    // ---------------- 홈 ----------------
    private fun renderHome() {
        setTop("대리내비 v" + versionName(), false, "설정") { go(Screen.SETTINGS) }

        if (!serviceEnabled()) root.addView(accessibilityWarning())

        // 오늘의 운행 (출근 = 목표 입력 + 자동 읽기 켜기, 퇴근 = 끄기)
        val working = serviceEnabled() && !detectPaused
        val today = dayKey(System.currentTimeMillis())
        val tToday = Store.trips(this).filter { dayKey(it.time) == today }
        val netToday = tToday.sumOf { it.net }
        val goal = Store.goal(this, today)
        root.addView(card().apply {
            addView(text("오늘의 운행", 14f, grey, bold = true))
            if (working) {
                // 목표까지 남은 금액: 모자라면 빨간 -금액 ⬇, 넘으면 파란 +금액 ⬆
                val diff = netToday - (goal ?: 0)
                val amount = TextView(this@MainActivity).apply {
                    text = if (goal == null) "목표 입력 ›" else (if (diff >= 0) "+" else "-") + won(kotlin.math.abs(diff)) + "원 " + if (diff >= 0) "⬆" else "⬇"
                    textSize = if (goal == null) 20f else 27f
                    setTextColor(if (goal == null) blue else if (diff >= 0) blue else red)
                    setTypeface(typeface, Typeface.BOLD)
                    gravity = Gravity.END or Gravity.CENTER_VERTICAL
                    setOnClickListener { askGoal(today) }
                }
                addView(row(
                    listOf(amount, pillButton("퇴 근", innerBg, white) { clockOut(today) }),
                    weights = listOf(2.6f, 1f),
                ))
                addView(text(
                    "🟢 출근 중 · " + (goal?.let { "목표 ${won(it)}원 · 오늘 순익 ${won(netToday)}원 (금액을 누르면 목표 수정)" } ?: "콜 화면을 자동으로 읽고 있어요"),
                    13f, grey,
                ).apply { setPadding(0, dp(8), 0, 0) })
            } else {
                addView(row(listOf(pillButton("출 근", blue, white) { clockIn(today) })))
                addView(text("⚪ 퇴근 · 자동 읽기 꺼짐", 13f, grey).apply { setPadding(0, dp(8), 0, 0) })
            }
        })

        // 오늘 합계
        root.addView(card().apply {
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(text("오늘 합계", 17f, white, bold = true))
                addView(badge("${tToday.size}콜", 0xFF1F3354.toInt(), blue).apply {
                    layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(10) }
                })
            })
            addView(totalsRow(tToday.sumOf { it.income }, tToday.sumOf { it.expense }))
        })

        // 지금 콜
        Store.current(this)?.let { c ->
            root.addView(card().apply {
                addView(text("지금 콜 · ${c.source}" + if (c.fare.isNotEmpty()) " · ${c.fare}" else "", 13f, grey))
                addView(addressBox(c))
                val (big, small) = etaViews()
                addView(big); addView(small)
                computeEta(c, big, small)
                addView(button("자세히 보기 · 길안내", blue) { go(Screen.CALL) })
            })
        }

        // 메뉴
        val todayCalls = Store.history(this).count { dayKey(it.capturedAt) == today }
        root.addView(card(pad = 0).apply {
            addView(menuItem("📋", "받은 콜 목록", if (todayCalls > 0) "$todayCalls" else null) { callsShown = 50; go(Screen.CALLS) })
            addView(divider())
            addView(menuItem("🚗", "자동운행기록", if (tToday.isNotEmpty()) "${tToday.size}" else null) { day = Calendar.getInstance(); dayBack = Screen.HOME; go(Screen.DAY) })
            addView(divider())
            addView(menuItem("📅", "운행일지", null) { logMonth = Calendar.getInstance(); go(Screen.LOG) })
            addView(divider())
            addView(menuItem("✍️", "직접 입력 (문자·카톡 콜)", null) { go(Screen.PASTE) })
            addView(divider())
            addView(menuItem("⚙️", "설정", null) { go(Screen.SETTINGS) })
        })
    }

    private fun clockIn(today: String) {
        if (!serviceEnabled()) { toast("먼저 접근성에서 대리내비를 켜주세요"); startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); return }
        detectPaused = false
        CallReaderService.instance?.applyBubbleMode()
        askGoal(today) { toast("출근! 콜 화면을 자동으로 읽어요") }
    }

    private fun clockOut(today: String) {
        val list = Store.trips(this).filter { dayKey(it.time) == today }
        val net = list.sumOf { it.net }
        val goal = Store.goal(this, today)
        val msg = buildString {
            append("오늘 ${list.size}콜\n")
            append("수입 ${won(list.sumOf { it.income })}원 · 지출 ${won(list.sumOf { it.expense })}원\n")
            append("순익 ${won(net)}원")
            goal?.let { g ->
                val d = net - g
                append("\n\n목표 ${won(g)}원 대비 ")
                append(if (d >= 0) "+${won(d)}원 달성! 🎉" else "${won(-d)}원 모자라요")
            }
            append("\n\n퇴근하면 콜 화면 자동 읽기를 멈춰요.")
        }
        AlertDialog.Builder(this).setTitle("퇴근할까요?").setMessage(msg)
            .setPositiveButton("퇴근") { _, _ ->
                detectPaused = true; CallReaderService.instance?.hideBubble(); toast("수고하셨어요! 안전운행 하세요"); render()
            }
            .setNegativeButton("취소", null).show()
    }

    /** 오늘의 목표 금액 입력 */
    private fun askGoal(today: String, after: (() -> Unit)? = null) {
        val et = edit("예) 300000").apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            gravity = Gravity.CENTER; textSize = 22f
            Store.goal(this@MainActivity, today)?.takeIf { it > 0 }?.let { setText(it.toString()) }
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0)
            addView(et)
            addView(text("단위: 원", 13f, grey).apply { gravity = Gravity.END })
        }
        AlertDialog.Builder(this)
            .setTitle("🎯 목표")
            .setMessage("오늘의 목표 금액을 입력하세요")
            .setView(box)
            .setPositiveButton("확인") { _, _ ->
                et.text.toString().filter { it.isDigit() }.toIntOrNull()?.let { Store.setGoal(this, today, it) }
                after?.invoke(); render()
            }
            .setNegativeButton("건너뛰기") { _, _ -> after?.invoke(); render() }
            .show()
    }

    private fun versionName(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    }.getOrDefault("")

    // ---------------- 콜 상세 ----------------
    private fun renderCallScreen() {
        setTop("콜 상세", true)
        val c = Store.current(this)
        if (c == null) {
            root.addView(card().apply { addView(text("아직 읽어온 콜이 없어요.\n대리 프로그램에서 콜 화면을 열면 자동으로 나타납니다.", 15f, grey)) })
            return
        }
        root.addView(card().apply {
            // 대리 앱 이름 · 수락 전 / 요금
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(appIcon(c))
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0)
                    addView(text(c.source, 19f, white, bold = true).apply { maxLines = 1 })
                    addView(badge(if (c.accepted) "배정" else "수락 전", innerBg, grey).apply {
                        textSize = 12f; setPadding(dp(8), dp(2), dp(8), dp(2))
                        layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(4) }
                    })
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                val fareWon = CallParser.won(c.fare)
                if (c.fare.isNotEmpty()) addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.END
                    addView(text("요금", 12f, grey).apply { gravity = Gravity.END })
                    addView(text(if (fareWon > 0) "${won(fareWon)}원" else c.fare, 27f, blue, bold = true).apply { maxLines = 1 })
                })
            })

            // 지도: 출발(파란 핀) / 도착(빨간 핀)
            addView(mapView(c))

            // 출발지 → 대중교통 길안내
            addView(space(14))
            addView(text(dot(blue, "●  출발지"), 14f, blue, bold = true))
            addView(text(c.originDisplay.ifEmpty { c.originRaw }.ifEmpty { "(없음)" }, 21f, white, bold = true))
            addView(row(listOf(
                button("🚌 카카오맵", kakaoYellow, black) { navigate(App.KAKAO, c.originQueries, Mode.TRANSIT) },
                button("🚌 네이버지도", naverGreen) { navigate(App.NAVER, c.originQueries, Mode.TRANSIT) },
            )))

            addView(View(this@MainActivity).apply {
                setBackgroundColor(line)
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(1)).apply { topMargin = dp(20); bottomMargin = dp(16) }
            })

            // 도착지 + 출발→도착 경로 (자동차)
            addView(text(dot(red, "●  도착지"), 14f, red, bold = true))
            addView(text(c.destDisplay.ifEmpty { c.destRaw }.ifEmpty { "(없음)" }, 21f, white, bold = true))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(14))
                background = rounded(innerBg, 14)
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) }
                addView(text("🧭 출발 → 도착 경로", 15f, white, bold = true))
                addView(row(listOf(
                    button("🚗 카카오맵", kakaoYellow, black) { fullRoute(App.KAKAO, c) },
                    button("🚗 네이버지도", naverGreen) { fullRoute(App.NAVER, c) },
                )))
            })
            addView(text("출발지 버튼은 대중교통, 경로 버튼은 자동차 길안내로 열려요", 12f, 0xFF7D7D84.toInt()).apply { setPadding(0, dp(8), 0, 0) })
        })
    }

    /** 앱 안의 작은 지도 (Leaflet + 무료 지도). 출발·도착 위치를 찾으면 핀을 찍는다 */
    @android.annotation.SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun mapView(c: CallInfo): View {
        val web = android.webkit.WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.setBackgroundColor(0xFF1E1E21.toInt())
        // 지도를 움직일 때 바깥 화면이 같이 스크롤되지 않게
        web.setOnTouchListener { v, e ->
            if (e.action == android.view.MotionEvent.ACTION_DOWN) v.parent.requestDisallowInterceptTouchEvent(true)
            if (e.action == android.view.MotionEvent.ACTION_UP || e.action == android.view.MotionEvent.ACTION_CANCEL) v.parent.requestDisallowInterceptTouchEvent(false)
            false
        }
        var loaded = false
        var pending: String? = null
        web.webViewClient = object : android.webkit.WebViewClient() {
            override fun onPageFinished(view: android.webkit.WebView, url: String) {
                loaded = true
                pending?.let { view.evaluateJavascript(it, null) }
            }
        }
        web.loadUrl("file:///android_asset/map.html")
        Thread {
            val o = Geo.resolve(this, c.originQueries)
            val d = Geo.resolve(this, c.destQueries)
            fun js(p: Geo.Pt?) = if (p == null) "null,null" else "${p.lat},${p.lng}"
            val call = "setPoints(${js(o)},${js(d)});"
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (loaded) web.evaluateJavascript(call, null) else pending = call
            }
        }.start()
        return android.widget.FrameLayout(this).apply {
            background = rounded(innerBg, 14)
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(330)).apply { topMargin = dp(14) }
            addView(web, android.widget.FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
    }

    private fun locLabel(p: Geo.Pt?) =
        p?.let { "📍 지도 위치: ${it.label}" } ?: "📍 정확한 위치를 못 찾음 → 누르면 지도앱 검색창에 주소가 들어간 채로 열려요"

    // ---------------- 받은 콜 목록 ----------------
    private fun renderCalls() {
        val hist = Store.history(this)
        setTop("받은 콜 목록" + if (hist.isNotEmpty()) " (${hist.size})" else "", true, "목록삭제") {
            confirm("받은 콜 목록 ${hist.size}개를 모두 지울까요?\n(운행일지는 지워지지 않아요)") { Store.clearHistory(this); render() }
        }
        if (hist.isEmpty()) {
            root.addView(card().apply { addView(text("아직 받은 콜이 없어요.", 15f, grey)) })
            return
        }
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.KOREA)
        hist.take(callsShown).forEach { h ->
            root.addView(card().apply {
                setOnClickListener { openCall(h) }
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    addView(appIcon(h))
                    addView(LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0)
                        addView(text(h.source, 18f, white, bold = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                        addView(LinearLayout(this@MainActivity).apply {
                            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                            addView(text(fmt.format(Date(h.capturedAt)), 14f, grey).apply { maxLines = 1 })
                            if (!h.accepted) addView(badge("수락 전", innerBg, grey).apply {
                                textSize = 11f; maxLines = 1
                                setPadding(dp(8), dp(2), dp(8), dp(2))
                                layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(6) }
                            })
                        })
                    }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                    if (h.fare.isNotEmpty()) addView(LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL; gravity = Gravity.END
                        addView(text("요금", 12f, grey).apply { gravity = Gravity.END })
                        addView(text(CallParser.won(h.fare).takeIf { it > 0 }?.let { won(it) } ?: h.fare, 22f, blue, bold = true).apply { maxLines = 1 })
                    })
                })
                addView(space(10))
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(10), dp(10), dp(10))
                    background = rounded(innerBg, 12)
                    addView(addressBox(h, oneLine = true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                    addView(android.widget.ImageView(this@MainActivity).apply {
                        setImageResource(R.drawable.ic_map)
                        scaleType = android.widget.ImageView.ScaleType.CENTER
                        background = rounded(cardBg, 14, line)
                        setOnClickListener { openCall(h) }
                    }, LinearLayout.LayoutParams(dp(54), dp(54)).apply { leftMargin = dp(8) })
                })
            })
        }
        // 한꺼번에 다 그리면 버벅이므로 50개씩 더 보기
        if (hist.size > callsShown) {
            root.addView(button("더 보기 (${hist.size - callsShown}개 남음)", innerBg) { callsShown += 50; render() })
        }
    }

    private fun openCall(h: CallInfo) {
        Store.setCurrent(this, h)
        CallReaderService.instance?.let { it.showBubble(); it.bubbleEta(h) }
        go(Screen.CALL)
    }

    private val iconCache = HashMap<String, android.graphics.drawable.Drawable?>()

    /** 설치된 대리 앱의 진짜 아이콘 (없으면 글자 아이콘) */
    private fun appIcon(h: CallInfo): View {
        val icon = if (h.pkg.isEmpty()) null else iconCache.getOrPut(h.pkg) {
            runCatching { packageManager.getApplicationIcon(h.pkg) }.getOrNull()
        }
        if (icon == null) return iconBox(appEmoji(h.source))
        return android.widget.ImageView(this).apply {
            setImageDrawable(icon)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = rounded(innerBg, 16, line)
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
        }
    }

    private fun appEmoji(source: String) = when {
        "콜마너" in source -> "콜"
        "로지" in source -> "로"
        "카카오" in source -> "K"
        else -> "✍"
    }

    // ---------------- 운행일지 ----------------
    private val monthFmt = SimpleDateFormat("yyyy년 M월", Locale.KOREA)
    private val ymFmt = SimpleDateFormat("yyyy-MM", Locale.KOREA)

    private fun renderLog() {
        setTop("운행일지", true)
        dayBack = Screen.LOG
        val ym = ymFmt.format(logMonth.time)
        val monthTrips = Store.trips(this).filter { ymFmt.format(Date(it.time)) == ym }

        // 월 이동 + 달력/목록
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
            addView(TextView(this@MainActivity).apply {
                text = "‹"; textSize = 30f; setTextColor(blue); gravity = Gravity.CENTER
                setOnClickListener { logMonth.add(Calendar.MONTH, -1); render() }
            }, LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(text(monthFmt.format(logMonth.time), 20f, white, bold = true).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(TextView(this@MainActivity).apply {
                text = "›"; textSize = 30f; setTextColor(blue); gravity = Gravity.CENTER
                setOnClickListener { logMonth.add(Calendar.MONTH, 1); render() }
            }, LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(Button(this@MainActivity).apply {
                text = if (logListMode) "달력" else "목록"; isAllCaps = false; setTextColor(blue); textSize = 15f
                background = rounded(cardBg, 10, line)
                setOnClickListener { logListMode = !logListMode; render() }
            }, LinearLayout.LayoutParams(dp(96), dp(44)).apply { leftMargin = dp(8) })
        })

        val byDay = monthTrips.groupBy { dayKey(it.time) }
        if (logListMode) renderLogList(byDay) else renderCalendar(byDay)

        // 월 합계 (아래 고정)
        footer.visibility = View.VISIBLE
        footer.setPadding(dp(16), dp(10), dp(16), dp(12))
        footer.addView(summaryBlock("${logMonth.get(Calendar.MONTH) + 1}월 합계", monthTrips))
    }

    private fun renderCalendar(byDay: Map<String, List<Store.Trip>>) {
        val cal = logMonth.clone() as Calendar
        cal.set(Calendar.DAY_OF_MONTH, 1)
        val firstDow = cal.get(Calendar.DAY_OF_WEEK) - 1
        val days = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val todayKey = dayKey(System.currentTimeMillis())

        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = rounded(cardBg, 12) }
        grid.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            listOf("일", "월", "화", "수", "목", "금", "토").forEachIndexed { i, d ->
                addView(text(d, 15f, if (i == 0) red else if (i == 6) blue else grey, bold = true).apply {
                    gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(10))
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            }
        })
        val cells = ((firstDow + days + 6) / 7) * 7
        var rowView = LinearLayout(this)
        for (i in 0 until cells) {
            if (i % 7 == 0) {
                rowView = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                grid.addView(rowView)
            }
            val d = i - firstDow + 1
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(6), 0, dp(6))
                background = rounded(cardBg, 0, line)
            }
            if (d in 1..days) {
                val c2 = logMonth.clone() as Calendar
                c2.set(Calendar.DAY_OF_MONTH, d)
                val key = dayKey(c2.timeInMillis)
                val dow = i % 7
                cell.addView(TextView(this).apply {
                    text = "$d"; textSize = 15f; gravity = Gravity.CENTER
                    setTextColor(if (key == todayKey) white else if (dow == 0) red else if (dow == 6) blue else white)
                    if (key == todayKey) background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(blue) }
                }, LinearLayout.LayoutParams(dp(30), dp(30)))
                val list = byDay[key].orEmpty()
                cell.addView(text(if (list.isNotEmpty()) "${list.size}콜" else " ", 11f, white).apply { gravity = Gravity.CENTER })
                cell.addView(text(if (list.isNotEmpty()) won(list.sumOf { it.net }) else " ", 11f, blue).apply { gravity = Gravity.CENTER })
                cell.setOnClickListener { day = c2; go(Screen.DAY) }
            }
            rowView.addView(cell, LinearLayout.LayoutParams(0, dp(84), 1f))
        }
        root.addView(grid)
        root.addView(text("날짜를 누르면 그날 기록을 보고 직접 입력할 수 있어요 (칸 숫자는 순익)", 12f, grey).apply { setPadding(dp(4), dp(8), 0, 0) })
    }

    private fun renderLogList(byDay: Map<String, List<Store.Trip>>) {
        if (byDay.isEmpty()) {
            root.addView(card().apply { addView(text("이 달에는 기록이 없어요.\n달력에서 날짜를 눌러 직접 입력할 수 있어요.", 15f, grey)) })
            return
        }
        val dFmt = SimpleDateFormat("d(E)", Locale.KOREA)
        byDay.toSortedMap().forEach { (_, list) ->
            val t0 = list.first().time
            val dow = Calendar.getInstance().apply { timeInMillis = t0 }.get(Calendar.DAY_OF_WEEK)
            root.addView(card().apply {
                setOnClickListener { day = Calendar.getInstance().apply { timeInMillis = t0 }; go(Screen.DAY) }
                addView(summaryBlock(dFmt.format(Date(t0)), list, titleColor = if (dow == 1) red else if (dow == 7) blue else white))
            })
        }
    }

    // ---------------- 하루 기록 ----------------
    private fun renderDay() {
        val isToday = dayKey(day.timeInMillis) == dayKey(System.currentTimeMillis())
        val title = if (isToday) "자동운행기록" else SimpleDateFormat("M월 d일 (E)", Locale.KOREA).format(day.time)
        setTop(title, true, "+ 입력") { editTrip(null) }
        val key = dayKey(day.timeInMillis)
        val list = Store.trips(this).filter { dayKey(it.time) == key }.sortedBy { it.time }
        if (list.isEmpty()) {
            root.addView(card().apply { addView(text("이 날은 기록이 없어요.", 15f, grey)) })
        }
        val hm = SimpleDateFormat("HH:mm", Locale.KOREA)
        list.forEach { t ->
            root.addView(card().apply {
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    addView(text(hm.format(Date(t.time)) + if (t.memo.isNotEmpty()) "  ${t.memo}" else "", 15f, grey),
                        LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                    addView(text("순익 ${won(t.net)}", 17f, white, bold = true))
                })
                if (t.title.isNotEmpty()) addView(text(t.title, 15f, white))
                addView(totalsRow(t.income, t.expense, showNet = false))
                addView(row(listOf(
                    button("수정", innerBg) { editTrip(t) },
                    button("삭제", 0xFF4A2A2C.toInt()) { confirm("이 기록을 지울까요?") { Store.deleteTrip(this@MainActivity, t.id); render() } },
                )))
            })
        }
        root.addView(button("+ 일지 직접 입력 (콜·교통비 등)", blue) { editTrip(null) })
        footer.visibility = View.VISIBLE
        footer.setPadding(dp(16), dp(10), dp(16), dp(12))
        footer.addView(summaryBlock("일일 합계", list))
    }

    private fun editTrip(t: Store.Trip?) {
        val inc = edit("수입 (원)").apply { inputType = InputType.TYPE_CLASS_NUMBER; setText(t?.income?.takeIf { it > 0 }?.toString().orEmpty()) }
        val exp = edit("지출 (원) · 입금액/수수료/교통비").apply { inputType = InputType.TYPE_CLASS_NUMBER; setText(t?.expense?.takeIf { it > 0 }?.toString().orEmpty()) }
        val title = edit("내용 (예: 수원 → 당진)").apply { setText(t?.title.orEmpty()) }
        val memo = edit("메모 (예: 택시비)").apply { setText(t?.memo.orEmpty()) }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0)
            addView(inc); addView(exp); addView(title); addView(memo)
        }
        AlertDialog.Builder(this)
            .setTitle(if (t == null) "일지 입력" else "일지 수정")
            .setView(box)
            .setPositiveButton("저장") { _, _ ->
                val time = t?.time ?: run {
                    val now = Calendar.getInstance()
                    if (dayKey(now.timeInMillis) == dayKey(day.timeInMillis)) now.timeInMillis
                    else (day.clone() as Calendar).apply { set(Calendar.HOUR_OF_DAY, 12); set(Calendar.MINUTE, 0) }.timeInMillis
                }
                Store.upsertTrip(this, Store.Trip(
                    id = t?.id ?: System.currentTimeMillis(), time = time,
                    income = inc.text.toString().filter { it.isDigit() }.toIntOrNull() ?: 0,
                    expense = exp.text.toString().filter { it.isDigit() }.toIntOrNull() ?: 0,
                    title = title.text.toString().trim(), memo = memo.text.toString().trim(), callKey = t?.callKey.orEmpty(),
                ))
                render()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // ---------------- 직접 입력 ----------------
    private fun renderPaste() {
        setTop("직접 입력", true)
        root.addView(card().apply {
            addView(text("✍️ 문자·카톡으로 받은 콜 붙여넣기", 16f, white, bold = true))
            val et = edit("예) 출발: 수원시 권선구 권선로 308-5\n     도착: 대전 유성구 용산1로 99", multi = true)
            addView(et)
            addView(row(listOf(
                button("클립보드 붙여넣기", innerBg) {
                    val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    et.setText(cm.primaryClip?.getItemAt(0)?.coerceToText(this@MainActivity) ?: "")
                },
                button("분석하기", blue) { if (parseAndSet(et.text.toString(), "직접 입력")) go(Screen.CALL) },
            )))
            addView(text("문자나 카톡에서 '공유 → 대리내비'로 보내도 돼요.", 13f, grey).apply { setPadding(0, dp(8), 0, 0) })
        })
    }

    // ---------------- 설정 ----------------
    private fun renderSettings() {
        setTop("설정", true)
        root.addView(if (serviceEnabled()) card().apply {
            addView(text("✅ 접근성(자동 읽기) 허용됨", 16f, white, bold = true))
            addView(text("홈의 출근/퇴근 버튼으로 켜고 끌 수 있어요.", 13f, grey))
        } else accessibilityWarning())

        // 말풍선(출/도) 표시 설정
        root.addView(card().apply {
            addView(text("말풍선(출/도) 표시", 16f, white, bold = true))
            val labels = listOf("항상", "대리 앱\n볼 때만", "안 띄움")
            addView(row(labels.mapIndexed { i, l ->
                button(l, if (bubbleMode == i) blue else innerBg) {
                    bubbleMode = i
                    CallReaderService.instance?.applyBubbleMode()
                    toast(when (i) { 0 -> "말풍선을 항상 보여줘요"; 1 -> "대리 앱 화면에서만 보여줘요"; else -> "말풍선을 안 띄워요 (콜 읽기·기록은 계속)" })
                    render()
                }
            }))
            addView(text("'안 띄움'이어도 콜 읽기와 받은 콜 목록 기록은 계속 돼요.", 12f, grey).apply { setPadding(0, dp(8), 0, 0) })
            addView(button(
                "카카오T 대리 콜에도 말풍선 띄우기: " + if (kakaoBubble) "켜짐" else "꺼짐",
                if (kakaoBubble) blue else innerBg,
            ) {
                kakaoBubble = !kakaoBubble
                toast(if (kakaoBubble) "카카오 콜도 말풍선을 바꿔요" else "카카오 콜은 말풍선을 안 바꿔요 (목록·일지 기록은 계속)")
                render()
            })
        })

        root.addView(card().apply {
            addView(text("집(복귀) 주소", 13f, grey))
            val home = edit("예) 경기 수원시 팔달구 효원로 241").apply { setText(homeAddress) }
            addView(home)
            addView(text("카카오 REST API 키 (넣으면 주소 찾기·도착시간·톨비가 정확해져요)", 13f, grey).apply { setPadding(0, dp(10), 0, 0) })
            val key = edit("developers.kakao.com 에서 무료 발급").apply { setText(kakaoKey) }
            addView(key)
            addView(button("저장", blue) {
                homeAddress = home.text.toString().trim()
                kakaoKey = key.text.toString().trim()
                toast("저장했어요")
            })
        })

        root.addView(card().apply {
            addView(text("🛠 주소가 이상하거나 콜을 못 읽을 때", 16f, white, bold = true))
            addView(text("마지막 읽기 결과: " + Store.status(this@MainActivity).ifEmpty { "(아직 없음)" }, 13f, 0xFF7FB2FF.toInt()))
            addView(text("대리 앱 화면에서 읽은 글자(최근 10개)를 복사해서 보내주세요.", 13f, grey))
            val all = Store.dumps(this@MainActivity).joinToString("\n\n") { (app, at, body) ->
                "[$app ${SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date(at))}]\n$body"
            }
            addView(row(listOf(
                button(if (showDump) "숨기기" else "읽은 글자 보기", innerBg) { showDump = !showDump; render() },
                button("복사하기", innerBg) {
                    val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("dump", all))
                    toast("복사했어요")
                },
            )))
            if (showDump) addView(text(all.ifEmpty { "(아직 없음)" }, 11f, 0xFFCCCCCC.toInt()).apply { setTextIsSelectable(true) })
        })
    }

    private fun serviceEnabled(): Boolean {
        if (CallReaderService.instance != null) return true
        val list = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        return list.contains("$packageName/") && list.contains("CallReaderService")
    }

    private fun accessibilityWarning() = card(0xFF3A1717.toInt()).apply {
        addView(text("⚠️ 자동 읽기(접근성)가 꺼져 있어요", 17f, white, bold = true))
        addView(text(
            "1) [접근성 설정 열기] → 설치된 앱 → '대리내비' 켜기\n" +
                "2) 회색으로 눌리지 않으면: [앱 정보 열기] → 오른쪽 위 ⋮ → '제한된 설정 허용' 후 1)번 다시",
            14f, white,
        ))
        addView(button("접근성 설정 열기", blue) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
        addView(button("앱 정보 열기 (제한된 설정 허용)", innerBg) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })
    }

    // =================== 도착시간 (도보 1순위) ===================
    private fun etaViews(): Pair<TextView, TextView> {
        val big = text("🚶 도보 시간 계산 중…", 21f, white, bold = true).apply {
            setPadding(dp(12), dp(10), dp(12), dp(2))
            background = rounded(0xFF22314A.toInt(), 10)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) }
        }
        val small = text("", 14f, 0xFFD6E2F5.toInt()).apply {
            setPadding(dp(12), dp(2), dp(12), dp(10))
            background = rounded(0xFF22314A.toInt(), 10)
        }
        return big to small
    }

    private fun computeEta(c: CallInfo, big: TextView, small: TextView) {
        val again = View.OnClickListener { big.text = "🚶 다시 계산 중…"; computeEta(c, big, small) }
        big.setOnClickListener(again); small.setOnClickListener(again)
        Loc.current(this) { here ->
            Thread {
                val o = Geo.resolve(this, c.originQueries)
                val d = Geo.resolve(this, c.destQueries)
                val walkKm = if (here != null && o != null) Route.straightKm(here, o) else null
                val drive1 = if (here != null && o != null) Route.drive(this, here, o) else null
                val leg2 = if (o != null && d != null) Route.drive(this, o, d) else null
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    val (b, s) = etaText(here, o, walkKm, drive1, leg2)
                    big.text = b; small.text = s
                }
            }.start()
        }
    }

    private fun dur(sec: Int): String {
        val m = (sec + 59) / 60
        return if (m >= 60) "${m / 60}시간 ${m % 60}분" else "${m}분"
    }

    private fun km(meters: Int) = if (meters < 1000) "${meters}m" else String.format(Locale.KOREA, "%.1fkm", meters / 1000.0)

    private fun etaText(here: Geo.Pt?, o: Geo.Pt?, walkKm: Double?, drive1: Route.Info?, leg2: Route.Info?): Pair<String, String> {
        val now = System.currentTimeMillis()
        val hm = SimpleDateFormat("HH:mm", Locale.KOREA)
        val big: String
        val sb = StringBuilder()
        var walkSec = 0
        if (walkKm != null) {
            // 걸어서 20분 이하면 도보, 넘으면 대중교통(대략)
            val t = Route.toOrigin(walkKm)
            walkSec = t.sec
            big = if (t.walk) "🚶 출발지까지 걸어서 ${dur(t.sec)}" else "🚌 출발지까지 대중교통 약 ${dur(t.sec)}"
            sb.append("   ${km((walkKm * 1250).toInt())} · ${hm.format(Date(now + t.sec * 1000L))} 도착")
            if (!t.walk) sb.append("  (🚶 걸어서 ${dur(Route.walkSec(walkKm))})")
            drive1?.let { sb.append("  (🚗 차로 ${dur(it.sec)})") }
            if (!t.walk) sb.append("\n   ※ 🚌 시간은 거리로 계산한 대략값 · 정확한 건 [🚌 출발지로] 버튼")
            sb.append("\n")
        } else {
            big = if (here == null) "🚶 지금 위치를 몰라요" else "🚶 출발지 위치를 못 찾았어요"
            sb.append(if (here == null) "   위치 권한·GPS를 확인해 주세요\n" else "   카카오 키를 넣으면 대부분 찾아요\n")
        }
        if (leg2 != null) {
            sb.append("🚗 출발지 → 도착지  ${km(leg2.meters)} · ${dur(leg2.sec)}")
            if (leg2.toll > 0) sb.append(" · 톨비 ${won(leg2.toll)}원")
            sb.append("\n🏁 도착지 도착 예정  ${hm.format(Date(now + (walkSec + leg2.sec) * 1000L))}")
            sb.append(if (walkKm != null) " (출발지까지 이동 + 차로 이동)" else " (지금 출발지에서 출발 기준)")
        } else {
            sb.append("🏁 ${if (o == null) "출발지" else "도착지"} 위치를 못 찾아 도착 예정 시간은 계산 못 했어요")
        }
        sb.append(if (leg2?.estimated == false) "\n※ 차 시간은 카카오 실시간 교통 반영" else "\n※ 대략적인 시간이에요 (카카오 키를 넣으면 차 시간이 정확해져요)")
        sb.append(" · 눌러서 다시 계산")
        return big to sb.toString()
    }

    // =================== 지도 동작 ===================
    private fun resolveAsync(queries: List<String>, done: (Geo.Pt?) -> Unit) {
        if (queries.isEmpty()) { done(null); return }
        Thread {
            val pt = Geo.resolve(this, queries)
            runOnUiThread { if (!isFinishing) done(pt) }
        }.start()
    }

    private fun navigate(app: App, queries: List<String>, mode: Mode = Mode.CAR) {
        if (queries.isEmpty()) { toast("주소가 없어요"); return }
        resolveAsync(queries) { pt ->
            if (pt != null) MapLauncher.route(this, app, null, pt, mode)
            else { toast("정확한 위치를 못 찾아 지도 검색으로 엽니다"); MapLauncher.search(this, app, queries.first()) }
        }
    }

    private fun fullRoute(app: App, c: CallInfo) {
        resolveAsync(c.originQueries) { o ->
            resolveAsync(c.destQueries) { d ->
                if (o != null && d != null) MapLauncher.route(this, app, o, d)
                else { toast("출발지/도착지 위치를 못 찾아 도착지만 엽니다"); navigate(app, c.destQueries) }
            }
        }
    }

    private fun goHome(app: App) {
        val home = homeAddress
        if (home.isBlank()) { toast("설정에서 집 주소를 먼저 저장해 주세요"); return }
        resolveAsync(CallParser.candidates(home).ifEmpty { listOf(home) }) { pt ->
            if (pt != null) MapLauncher.route(this, app, null, pt, Mode.TRANSIT)
            else MapLauncher.search(this, app, home)
        }
    }

    // =================== 작은 도우미들 ===================
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun space(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }

    private fun rounded(color: Int, radiusDp: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radiusDp).toFloat()
        stroke?.let { setStroke(dp(1), it) }
    }

    private fun text(s: CharSequence, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(2), 0, dp(2))
    }

    private fun card(color: Int = cardBg, pad: Int = 16) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(pad), dp(if (pad == 0) 4 else 14), dp(pad), dp(if (pad == 0) 4 else 14))
        background = rounded(color, 18)
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(12) }
    }

    private fun button(label: String, color: Int, textColor: Int = white, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 15f; setTextColor(textColor)
        setTypeface(typeface, Typeface.BOLD)
        minHeight = dp(56)
        background = rounded(color, 12)
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) }
        setOnClickListener { onClick() }
    }

    private fun pillButton(label: String, color: Int, textColor: Int, onClick: () -> Unit) =
        button(label, color, textColor, onClick).apply {
            textSize = 19f; minHeight = dp(60)
            background = rounded(color, 16, if (color == innerBg) line else null)
        }

    private fun row(views: List<View>, weights: List<Float>? = null) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        isBaselineAligned = false
        views.forEachIndexed { i, v ->
            v.layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, weights?.getOrNull(i) ?: 1f).apply {
                topMargin = dp(8); if (i > 0) leftMargin = dp(10)
            }
            addView(v)
        }
    }

    private fun badge(s: String, color: Int, textColor: Int) = TextView(this).apply {
        text = s; textSize = 14f; setTextColor(textColor); setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(12), dp(4), dp(12), dp(4)); background = rounded(color, 14)
    }

    private fun iconBox(emoji: String) = TextView(this).apply {
        text = emoji; textSize = 22f; gravity = Gravity.CENTER; setTextColor(white); setTypeface(typeface, Typeface.BOLD)
        background = rounded(innerBg, 16)
        layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(line)
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(1)).apply { leftMargin = dp(84); rightMargin = dp(16) }
    }

    private fun menuItem(emoji: String, label: String, badgeText: String?, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(12))
        setOnClickListener { onClick() }
        addView(iconBox(emoji))
        addView(text(label, 18f, white, bold = true).apply { setPadding(dp(16), 0, 0, 0) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        if (badgeText != null) addView(badge(badgeText, red, white).apply {
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = dp(10) }
        })
        addView(text("›", 26f, grey))
    }

    /** 출발(파란 점) / 도착(빨간 점) 두 줄 */
    private fun addressBox(c: CallInfo, oneLine: Boolean = false) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(4), 0, dp(4))
        val o = c.originDisplay.ifEmpty { c.originRaw.replace('\n', ' ') }.ifEmpty { "(없음)" }
        val d = c.destDisplay.ifEmpty { c.destRaw.replace('\n', ' ') }.ifEmpty { "(없음)" }
        listOf(dot(blue, "●  $o"), dot(red, "●  $d")).forEach { s ->
            addView(text(s, 15f, white).apply {
                if (oneLine) { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(5), 0, dp(5)) }
            })
        }
    }

    /** 맨 앞 ● 만 색칠 */
    private fun dot(color: Int, s: String) = android.text.SpannableString(s).apply {
        setSpan(android.text.style.ForegroundColorSpan(color), 0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun totalsRow(income: Int, expense: Int, showNet: Boolean = true) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(8), 0, 0)
        val cols = mutableListOf(Triple("수입", income, blue), Triple("지출", expense, red))
        if (showNet) cols += Triple("순익", income - expense, white)
        cols.forEach { (label, v, color) ->
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(label, 13f, grey))
                addView(text(won(v), 20f, color, bold = true))
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
    }

    private fun summaryBlock(title: String, list: List<Store.Trip>, titleColor: Int = white) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(text(title, 18f, titleColor, bold = true))
            addView(badge("${list.size}콜", 0xFF1F3354.toInt(), blue).apply {
                layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(10) }
            })
        })
        addView(totalsRow(list.sumOf { it.income }, list.sumOf { it.expense }))
    }

    private fun confirm(msg: String, onYes: () -> Unit) {
        AlertDialog.Builder(this).setMessage(msg)
            .setPositiveButton("확인") { _, _ -> onYes() }
            .setNegativeButton("취소", null).show()
    }

    private fun edit(hint: String, multi: Boolean = false) = EditText(this).apply {
        this.hint = hint; setHintTextColor(0xFF777777.toInt()); setTextColor(white); textSize = 15f
        gravity = Gravity.TOP or Gravity.START
        if (multi) { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE; minLines = 3 }
        else inputType = InputType.TYPE_CLASS_TEXT
        background = rounded(0xFF2A2A2E.toInt(), 8)
        setPadding(dp(10), dp(10), dp(10), dp(10))
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) }
    }
}
