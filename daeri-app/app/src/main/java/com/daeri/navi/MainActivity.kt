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
import com.daeri.navi.Store.bubbleKeepSec
import com.daeri.navi.Store.bubbleMode
import com.daeri.navi.Store.bubbleVibrate
import com.daeri.navi.Store.autoToLog
import com.daeri.navi.Store.commissionRate
import com.daeri.navi.Store.dayStartHour
import com.daeri.navi.Store.dayFixed
import com.daeri.navi.Store.monthFixed
import com.daeri.navi.Store.detectPaused
import com.daeri.navi.Store.workStart
import com.daeri.navi.Store.homeAddress
import com.daeri.navi.Store.kakaoBubble
import com.daeri.navi.Store.kakaoKey
import com.daeri.navi.Store.kakaoJsKey
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private enum class Screen { HOME, CALL, CALLS, AUTO, LOG, DAY, EDIT, TRASH, PASTE, SETTINGS }

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
    private var hourOpen = false
    private var reportPreview = false
    /** 콜 상세 지도 (⊙ 버튼으로 출발/도착 확대) */
    private var callWeb: android.webkit.WebView? = null
    private var day: Calendar = Calendar.getInstance().apply { add(Calendar.HOUR_OF_DAY, -6) }
    private var showDump = false
    private var callsShown = 50
    private var dayBack = Screen.LOG
    private var askedLocation = false

    private val dayKeyFmt = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA)
    /** 운행 기준 날짜: 새벽 (기본 6시) 전까지는 전날로 친다 */
    private fun dayKey(t: Long) = dayKeyFmt.format(Date(t - dayStartHour * 3600_000L))
    /** 달력 칸처럼 그냥 그 날짜 */
    private fun dateKey(t: Long) = dayKeyFmt.format(Date(t))
    /** 운행 기준 '오늘' 날짜의 Calendar */
    private fun bizToday() = Calendar.getInstance().apply { timeInMillis = System.currentTimeMillis() - dayStartHour * 3600_000L }
    private fun won(n: Int) = String.format(Locale.KOREA, "%,d", n)

    /** 앱 전체 글자를 10% 작게 */
    override fun attachBaseContext(base: android.content.Context) {
        val c = android.content.res.Configuration(base.resources.configuration)
        c.fontScale = c.fontScale * 0.9f
        super.attachBaseContext(base.createConfigurationContext(c))
    }

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
        // 광고 목록 새로 확인 (1시간마다). 바뀌면 홈 화면 다시 그림
        Ads.refresh(this) { runOnUiThread { if (screen == Screen.HOME && !isFinishing) render() } }
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
            Screen.EDIT -> go(editBack)
            Screen.TRASH -> go(trashBack)
            else -> go(Screen.HOME)
        }
    }

    private fun go(s: Screen) { screen = s; render() }

    private fun handleIntent(i: Intent?) {
        when (i?.getStringExtra("open")) {
            "auto" -> screen = Screen.AUTO
            "call" -> screen = Screen.CALL
            "calls" -> screen = Screen.CALLS
            "log" -> screen = Screen.LOG
            "report" -> { screen = Screen.HOME; reportPreview = true }
            "settings" -> { screen = Screen.SETTINGS; hourOpen = i.getBooleanExtra("hourOpen", false) }
            "trash" -> screen = Screen.TRASH
            "day" -> { day = bizToday(); dayBack = Screen.HOME; screen = Screen.DAY }
            "edit" -> {
                val id = i.getLongExtra("trip", -1L)
                editing = Store.trips(this).firstOrNull { it.id == id }
                editBack = Screen.DAY; screen = Screen.EDIT
            }
        }
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
        // 미리보기(화면 확인용): 퇴근 리포트만 그린다
        if (reportPreview) { root.addView(dailyReport(dayKey(System.currentTimeMillis()), {}, {})); return }
        when (screen) {
            Screen.HOME -> renderHome()
            Screen.CALL -> renderCallScreen()
            Screen.CALLS -> renderCalls()
            Screen.LOG -> renderLog()
            Screen.AUTO -> renderAuto()
            Screen.DAY -> renderDay()
            Screen.EDIT -> renderEditor()
            Screen.TRASH -> renderTrash()
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
        val tToday = Store.trips(this).filter { it.confirmed && dayKey(it.time) == today }
        val netToday = tToday.sumOf { it.net } - dayFix(tToday)
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
            addView(totalsRow(tToday.sumOf { it.income }, tToday.sumOf { it.expense } + dayFix(tToday)))
            fixedNote(dayFix(tToday), "일고정비")?.let { addView(it) }
        })

        // 지금 콜
        Store.current(this)?.let { c ->
            // 카드 전체를 누르면 콜 상세(지도·길안내)로
            root.addView(card().apply {
                setOnClickListener { go(Screen.CALL) }
                val fareWon = CallParser.won(c.fare)
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    addView(text("지금 콜 · ${c.source}", 13f, grey), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                    if (c.recommended) addView(recBadge())
                    if (fareWon > 0) addView(text("${won(fareWon)}원", 17f, blue, bold = true))
                    addView(text("  ›", 22f, grey))
                })
                addView(addressBox(c))
            })
        }

        // 메뉴
        val todayCalls = Store.history(this).count { dayKey(it.capturedAt) == today }
        root.addView(card(pad = 0).apply {
            addView(menuItem("📋", "받은 콜 목록", if (todayCalls > 0) "$todayCalls" else null) { callsShown = 50; go(Screen.CALLS) })
            addView(divider())
            val pending = Store.trips(this@MainActivity).count { !it.confirmed }
            addView(menuItem("🚗", "자동운행기록", if (pending > 0) "$pending" else null) { go(Screen.AUTO) })
            addView(divider())
            addView(menuItem("📅", "운행일지", null) { logMonth = bizToday(); go(Screen.LOG) })
            addView(divider())
            addView(menuItem("✍️", "직접 입력 (문자·카톡 콜)", null) { go(Screen.PASTE) })
            addView(divider())
            addView(menuItem("⚙️", "설정", null) { go(Screen.SETTINGS) })
        })

        // 광고 (광고가 없으면 칸 자체가 안 보임)
        adView()?.let { root.addView(it) }
    }

    /** 직접 받는 광고 배너 — 깃허브 ads.json 에서 가져온 것 */
    private fun adView(): View? {
        val a = Ads.pick(this) ?: return null
        val open = {
            if (a.link.isNotBlank()) runCatching {
                val uri = Uri.parse(a.link)
                startActivity(Intent(if (a.link.startsWith("tel:")) Intent.ACTION_DIAL else Intent.ACTION_VIEW, uri))
            }.onFailure { toast("열 수 없어요") }
        }
        val adTag = text("광고", 11f, grey).apply {
            setPadding(dp(7), dp(1), dp(7), dp(1)); background = rounded(0x33FFFFFF, 6)
        }
        val img = a.image.takeIf { it.isNotBlank() }?.let { Ads.cachedImage(this, it) }
        return card(color = a.bg ?: cardBg).apply {
            setOnClickListener { open() }
            if (img != null) {
                setPadding(dp(10), dp(10), dp(10), dp(10))
                addView(android.widget.FrameLayout(this@MainActivity).apply {
                    addView(android.widget.ImageView(this@MainActivity).apply {
                        setImageBitmap(img); adjustViewBounds = true
                        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                        clipToOutline = true; background = rounded(cardBg, 12)
                    }, android.widget.FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                    addView(adTag, android.widget.FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(6); topMargin = dp(6) })
                })
            } else {
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    addView(LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(LinearLayout(this@MainActivity).apply {
                            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                            addView(adTag)
                            addView(text(a.title, 17f, white, bold = true).apply { setPadding(dp(8), 0, 0, 0) })
                        })
                        if (a.text.isNotBlank()) addView(text(a.text, 13f, 0xFFCCCCD2.toInt()).apply { setPadding(0, dp(6), 0, 0) })
                    }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                    if (a.link.isNotBlank()) addView(Button(this@MainActivity).apply {
                        text = a.button.ifBlank { if (a.link.startsWith("tel:")) "📞 전화" else "보기 ›" }
                        isAllCaps = false; textSize = 14f; setTextColor(white); setTypeface(typeface, Typeface.BOLD)
                        background = rounded(blue, 12); minHeight = dp(44); minimumHeight = dp(44)
                        setPadding(dp(14), 0, dp(14), 0)
                        setOnClickListener { open() }
                    }, LinearLayout.LayoutParams(WRAP_CONTENT, dp(44)).apply { leftMargin = dp(10) })
                })
            }
        }
    }

    private fun clockIn(today: String) {
        if (!serviceEnabled()) { toast("먼저 접근성에서 대리내비를 켜주세요"); startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); return }
        // 이미 출근 중이 아니었으면 출근 시각 기록 (같은 영업일에 다시 누르면 처음 시각 유지)
        if (!(workStart > 0 && dayKey(workStart) == today)) workStart = System.currentTimeMillis()
        detectPaused = false
        CallReaderService.instance?.applyBubbleMode()
        askGoal(today) { toast("출근! 콜 화면을 자동으로 읽어요") }
    }

    private fun clockOut(today: String) {
        val dlg = android.app.Dialog(this)
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        // 화면이 작아도(폴드 겉화면) 끝까지 보이게 스크롤
        dlg.setContentView(android.widget.ScrollView(this).apply {
            addView(dailyReport(today, onCancel = { dlg.dismiss() }) {
                dlg.dismiss()
                detectPaused = true; CallReaderService.instance?.hideBubble(); toast("수고하셨어요! 안전운행 하세요"); render()
            })
        })
        dlg.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        dlg.show()
        dlg.window?.setLayout((resources.displayMetrics.widthPixels * 0.9).toInt(), WRAP_CONTENT)
    }

    /** 퇴근할 때 보여주는 일일 리포트 */
    private fun dailyReport(today: String, onCancel: () -> Unit, onOk: () -> Unit): View {
        val list = Store.trips(this).filter { it.confirmed && dayKey(it.time) == today }.sortedBy { it.time }
        val income = list.sumOf { it.income }
        val expense = list.sumOf { it.expense } + dayFix(list)
        val goal = Store.goal(this, today)
        val now = System.currentTimeMillis()
        val start = workStart.takeIf { it > 0 && dayKey(it) == today } ?: list.firstOrNull()?.time ?: now
        val hours = (now - start) / 3600_000.0
        val full = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA)

        fun dash() = View(this).apply {
            background = object : android.graphics.drawable.Drawable() {
                val p = android.graphics.Paint().apply {
                    color = 0xFF9A9AA0.toInt(); strokeWidth = 1.5f * resources.displayMetrics.density; style = android.graphics.Paint.Style.STROKE
                    pathEffect = android.graphics.DashPathEffect(floatArrayOf(dp(6).toFloat(), dp(4).toFloat()), 0f)
                }
                override fun draw(c: android.graphics.Canvas) { val y = bounds.exactCenterY(); c.drawLine(0f, y, bounds.width().toFloat(), y, p) }
                override fun setAlpha(a: Int) {}
                override fun setColorFilter(f: android.graphics.ColorFilter?) {}
                @Deprecated("") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
            }
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(14)).apply { topMargin = dp(6); bottomMargin = dp(6) }
        }
        // "목표금액: 200,000원" — 콜론 위치를 맞추려고 왼쪽/오른쪽 두 칸으로
        fun line(label: String, value: String, color: Int = white) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(text("$label: ", 17f, grey).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(text(value, 17f, color, bold = true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1.1f))
            setPadding(0, dp(3), 0, dp(3))
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(cardBg, 18, line)
            setPadding(dp(20), dp(18), dp(20), dp(16))
            addView(text("📊 일일 리포트", 19f, white, bold = true))
            addView(View(this@MainActivity).apply { setBackgroundColor(line) },
                LinearLayout.LayoutParams(MATCH_PARENT, dp(1)).apply { topMargin = dp(12); bottomMargin = dp(10) })
            addView(text(full.format(Date(start)) + " ~ " + SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(now)), 16f, white).apply { gravity = Gravity.CENTER })
            addView(dash())
            addView(line("목표금액", goal?.let { won(it) + "원" } ?: "없음"))
            addView(line("매출금액", won(income) + "원", blue))
            addView(line("지출금액", won(expense) + "원", red))
            addView(line("수익금액", won(income - expense) + "원"))
            goal?.let { g ->
                val d = income - expense - g
                addView(line("목표대비", (if (d >= 0) "+" else "-") + won(kotlin.math.abs(d)) + "원", if (d >= 0) blue else red))
            }
            addView(dash())
            addView(line("경유금액", won(list.sumOf { it.viaFee }) + "원"))
            addView(line("팁금액", won(list.sumOf { it.tip }) + "원"))
            addView(dash())
            if (list.isEmpty()) addView(line("운행", "0콜"))
            list.groupBy { it.program.ifBlank { "기타" } }.toList().sortedByDescending { it.second.size }.forEach { (prog, l) ->
                addView(line(prog, "${l.size}콜"))
            }
            addView(dash())
            addView(line("총콜수", "${list.size}콜"))
            addView(line("콜평균", if (list.isEmpty()) "0원" else won(income / list.size) + "원"))
            addView(line("시급", if (hours < 0.1) "-" else won(((income / hours) / 100).toInt() * 100) + "원"))
            addView(dash())
            addView(row(listOf(
                button("계속 운행", innerBg) { onCancel() },
                button("퇴근", blue) { onOk() },
            )).apply { setPadding(0, dp(6), 0, 0) })
        }
    }

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
                    addView(LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL or Gravity.END
                        if (c.recommended) addView(recBadge())
                        addView(text(if (fareWon > 0) "${won(fareWon)}원" else c.fare, 27f, blue, bold = true).apply { maxLines = 1 })
                    })
                })
            })

            // 지도: 출발(파란 핀) / 도착(빨간 핀)
            addView(mapView(c))

            // 출발지 → 대중교통 길안내
            addView(space(14))
            addView(text(dot(blue, "●  출발지"), 14f, blue, bold = true))
            addView(addrRow(c.originDisplay.ifEmpty { c.originRaw }.ifEmpty { "(없음)" }, blue) { callWeb?.evaluateJavascript("focusPoint('o')", null) })
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
            addView(addrRow(c.destDisplay.ifEmpty { c.destRaw }.ifEmpty { "(없음)" }, red) { callWeb?.evaluateJavascript("focusPoint('d')", null) })
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

    /** 주소 + 오른쪽 ⊙ 버튼 (누르면 지도에서 그 위치로 확대) */
    private fun addrRow(addr: String, color: Int, onFocus: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(text(addr, 21f, white, bold = true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(TextView(this@MainActivity).apply {
            text = "⌖"; textSize = 30f; setTextColor(color); gravity = Gravity.CENTER
            background = rounded(innerBg, 12)
            setOnClickListener { onFocus() }
            contentDescription = "지도에서 보기"
        }, LinearLayout.LayoutParams(dp(52), dp(52)).apply { leftMargin = dp(10) })
    }

    /** 앱 안의 작은 지도 (Leaflet + 무료 지도). 출발·도착 위치를 찾으면 핀을 찍는다 */
    @android.annotation.SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun mapView(c: CallInfo): View {
        val web = android.webkit.WebView(this)
        callWeb = web
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
        // 지도 서버(OpenStreetMap)가 요구하는 출처(Referer)를 주려고, 앱 안 파일을 https 주소로 연다
        val host = "appassets.androidplatform.net"
        web.settings.userAgentString = web.settings.userAgentString + " DaeriNavi/" + versionName()
        web.webViewClient = object : android.webkit.WebViewClient() {
            override fun shouldInterceptRequest(view: android.webkit.WebView, req: android.webkit.WebResourceRequest): android.webkit.WebResourceResponse? {
                if (req.url.host != host) return null
                val path = req.url.path.orEmpty().removePrefix("/assets/")
                val mime = when {
                    path.endsWith(".html") -> "text/html"
                    path.endsWith(".js") -> "application/javascript"
                    path.endsWith(".css") -> "text/css"
                    path.endsWith(".png") -> "image/png"
                    else -> "application/octet-stream"
                }
                return runCatching { android.webkit.WebResourceResponse(mime, "utf-8", assets.open(path)) }.getOrNull()
            }
            override fun onPageFinished(view: android.webkit.WebView, url: String) {
                loaded = true
                pending?.let { view.evaluateJavascript(it, null) }
            }
        }
        // 카카오 JavaScript 키가 있으면 카카오 지도 (도메인 https://appassets.androidplatform.net 등록 필요)
        // 휴대폰이 다크 모드면 어두운 지도, 아니면 밝은 지도
        val dark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        web.loadUrl("https://$host/assets/map.html?dark=" + (if (dark) "1" else "0") + kakaoJsKey.takeIf { it.isNotBlank() }?.let { "&kakao=" + Uri.encode(it) }.orEmpty())
        Thread {
            val o = Geo.resolve(this, c.originQueries)
            val d = Geo.resolve(this, c.destQueries)
            fun js(p: Geo.Pt?) = if (p == null) "null,null" else "${p.lat},${p.lng}"
            // 못 찾았으면 이유(카카오 키 오류 등)를 지도에 글자로
            val why = if (o == null || d == null) Geo.lastError.replace("\\", "").replace("'", "") else ""
            fun q(t: String) = "'" + t.replace("\\", "").replace("'", "").replace("\n", " ") + "'"
            val call = "setPoints(${js(o)},${js(d)},'$why',${q(c.originDisplay.ifEmpty { c.originRaw })},${q(c.destDisplay.ifEmpty { c.destRaw })});"
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
                        addView(LinearLayout(this@MainActivity).apply {
                            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL or Gravity.END
                            if (h.recommended) addView(recBadge())
                            addView(text(CallParser.won(h.fare).takeIf { it > 0 }?.let { won(it) } ?: h.fare, 22f, blue, bold = true).apply { maxLines = 1 })
                        })
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

    /** 금액 옆 빨간 '추천가' 표시 */
    private fun recBadge() = TextView(this).apply {
        text = "추천가"; textSize = 11f; setTextColor(white); setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(6), dp(2), dp(6), dp(2)); background = rounded(red, 6)
        layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = dp(6) }
    }

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
        "T맵" in source || "TMAP" in source.uppercase() -> "T"
        else -> "✍"
    }

    // ---------------- 운행일지 ----------------
    private val monthFmt = SimpleDateFormat("yyyy년 M월", Locale.KOREA)
    private val ymFmt = SimpleDateFormat("yyyy-MM", Locale.KOREA)

    private fun renderLog() {
        setTop("운행일지", true)
        dayBack = Screen.LOG
        val ym = ymFmt.format(logMonth.time)
        val monthTrips = Store.trips(this).filter { it.confirmed && ymFmt.format(Date(it.time - dayStartHour * 3600_000L)) == ym }

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
        // 월고정비 + 일고정비 × 운행한 날
        val fixed = monthFixed + dayFixed * byDay.size
        val note = listOfNotNull(
            monthFixed.takeIf { it > 0 }?.let { "월 ${won(it)}" },
            dayFixed.takeIf { it > 0 && byDay.isNotEmpty() }?.let { "일 ${won(it)}×${byDay.size}일" },
        ).joinToString(" + ")
        footer.addView(summaryBlock("${logMonth.get(Calendar.MONTH) + 1}월 합계", monthTrips, fixed = fixed, fixedLabel = "고정비($note)"))
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
                val key = dateKey(c2.timeInMillis)
                val dow = i % 7
                cell.addView(TextView(this).apply {
                    text = "$d"; textSize = 15f; gravity = Gravity.CENTER
                    setTextColor(if (key == todayKey) white else if (dow == 0) red else if (dow == 6) blue else white)
                    if (key == todayKey) background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(blue) }
                }, LinearLayout.LayoutParams(dp(30), dp(30)))
                val list = byDay[key].orEmpty()
                cell.addView(text(if (list.isNotEmpty()) "${list.size}콜" else " ", 11f, white).apply { gravity = Gravity.CENTER })
                cell.addView(text(if (list.isNotEmpty()) won(list.sumOf { it.net } - dayFix(list)) else " ", 11f, blue).apply { gravity = Gravity.CENTER })
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
            val dow = Calendar.getInstance().apply { timeInMillis = t0 - dayStartHour * 3600_000L }.get(Calendar.DAY_OF_WEEK)
            root.addView(card().apply {
                setOnClickListener { day = Calendar.getInstance().apply { timeInMillis = t0 - dayStartHour * 3600_000L }; go(Screen.DAY) }
                addView(summaryBlock(dFmt.format(Date(t0 - dayStartHour * 3600_000L)), list, titleColor = if (dow == 1) red else if (dow == 7) blue else white, fixed = dayFix(list)))
            })
        }
    }

    // ---------------- 자동운행기록 (자동으로 잡힌 콜 → [일지에 추가]) ----------------
    private var trashBack = Screen.DAY

    private fun renderAuto() {
        val todayKey = dayKey(System.currentTimeMillis())
        val all = Store.trips(this)
        val pending = all.filter { !it.confirmed }
        // 아직 일지에 안 넣은 콜 + 오늘 자동으로 잡혀서 일지에 넣은 콜 (넣어도 사라지지 않게)
        val shown = all.filter { !it.confirmed || (it.callKey.isNotEmpty() && dayKey(it.time) == todayKey) }.sortedByDescending { it.time }
        setTop("자동운행기록", true, if (pending.isNotEmpty()) "목록삭제" else null) {
            confirm("자동운행기록 ${pending.size}개를 지울까요?\n(삭제된 기록에서 30일 동안 되살릴 수 있어요)") {
                pending.forEach { Store.deleteTrip(this, it.id) }; render()
            }
        }
        if (shown.isEmpty()) {
            root.addView(card().apply {
                addView(text("자동으로 잡힌 콜이 없어요.\n대리 앱에서 콜을 수락하면 여기에 쌓여요.", 15f, grey))
            })
        }
        val f = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA)
        shown.forEach { t ->
            root.addView(card().apply {
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    addView(appIcon(CallInfo(t.program, "", "", emptyList(), emptyList(), pkg = t.pkg)))
                    addView(LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0)
                        addView(text(t.program.ifEmpty { "대리 앱" }, 18f, white, bold = true).apply { maxLines = 1 })
                        addView(text(f.format(Date(t.time)), 13f, grey).apply { maxLines = 1 })
                    }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                    addView(LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL; gravity = Gravity.END
                        addView(text("요금", 12f, grey).apply { gravity = Gravity.END })
                        addView(text(won(t.fare), 22f, blue, bold = true).apply { maxLines = 1 })
                    })
                })
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    background = rounded(innerBg, 12)
                    layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) }
                    listOfNotNull(
                        dot(blue, "●  ${t.origin.ifEmpty { "(출발지 없음)" }}"),
                        if (t.via.isNotEmpty()) dot(0xFFF5A623.toInt(), "●  ${t.via} 경유") else null,
                        dot(red, "●  ${t.dest.ifEmpty { "(도착지 없음)" }}"),
                    ).forEach { line ->
                        addView(text(line, 15f, white).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(5), 0, dp(5)) })
                    }
                })
                addView(row(listOf(
                    android.widget.ImageView(this@MainActivity).apply {
                        setImageResource(R.drawable.ic_map); scaleType = android.widget.ImageView.ScaleType.CENTER
                        background = rounded(innerBg, 12); minimumHeight = dp(52)
                        setOnClickListener {
                            val h = Store.history(this@MainActivity).firstOrNull { it.key == t.callKey }
                            if (h != null) openCall(h) else navigate(App.KAKAO, listOf(t.dest).filter { it.isNotBlank() })
                        }
                    },
                    smallButton("삭제", 0xFF3A2224.toInt(), red) {
                        confirm("이 기록을 지울까요?") { Store.deleteTrip(this@MainActivity, t.id); render() }
                    },
                    if (t.confirmed) smallButton("✓ 일지에 추가됨 · 수정", 0xFF1F3354.toInt(), blue) { openEditor(t) }
                    else smallButton("일지에 추가", blue) { openEditor(t) },
                ), weights = listOf(0.7f, 1.2f, 2.6f)))
            })
        }
        // 휴지통은 항상 보이게
        root.addView(button("🗑 휴지통 (${Store.trash(this).size})", innerBg) { trashBack = Screen.AUTO; go(Screen.TRASH) })

        // 일일 합계 (오늘 일지에 넣은 것)
        val today = dayKey(System.currentTimeMillis())
        val done = Store.trips(this).filter { it.confirmed && dayKey(it.time) == today }
        footer.visibility = View.VISIBLE
        footer.setPadding(dp(16), dp(12), dp(16), dp(14))
        footer.addView(summaryBlock("일일 합계", done, fixed = dayFix(done)))
    }

    // ---------------- 하루 기록 ----------------
    private fun renderDay() {
        val title = SimpleDateFormat("M월 d일 (E)", Locale.KOREA).format(day.time)
        setTop(title, true, "+ 입력") { openEditor(null) }
        val key = dateKey(day.timeInMillis)
        val list = Store.trips(this).filter { it.confirmed && dayKey(it.time) == key }.sortedBy { it.time }
        if (list.isEmpty()) {
            root.addView(card().apply { addView(text("이 날은 기록이 없어요.", 15f, grey)) })
        }
        val hm = SimpleDateFormat("HH:mm", Locale.KOREA)
        list.forEach { t ->
            root.addView(card().apply {
                setOnClickListener { openEditor(t) }
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    addView(text(hm.format(Date(t.time)) + "  " + t.program, 15f, grey).apply { maxLines = 1 },
                        LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                    addView(text("순익 ${won(t.net)}", 17f, white, bold = true))
                })
                if (t.origin.isNotEmpty()) addView(text(dot(blue, "●  ${t.origin}"), 15f, white).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                if (t.via.isNotEmpty()) addView(text(dot(0xFFF5A623.toInt(), "●  ${t.via}"), 15f, white).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                if (t.dest.isNotEmpty()) addView(text(dot(red, "●  ${t.dest}"), 15f, white).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                addView(totalsRow(t.income, t.expense, showNet = false))
                addView(row(listOf(
                    button("수정", innerBg) { openEditor(t) },
                    button("삭제", 0xFF4A2A2C.toInt()) { confirm("이 기록을 지울까요?") { Store.deleteTrip(this@MainActivity, t.id); render() } },
                )))
            })
        }
        root.addView(button("+ 새일지 등록", blue) { openEditor(null) })
        root.addView(button("🗑 휴지통 (${Store.trash(this).size})", innerBg) { trashBack = Screen.DAY; go(Screen.TRASH) })
        footer.visibility = View.VISIBLE
        footer.setPadding(dp(16), dp(10), dp(16), dp(12))
        footer.addView(summaryBlock("일일 합계", list, fixed = dayFix(list)))
    }

    // ---------------- 삭제된 기록 (30일 보관, 되살리기) ----------------
    private fun renderTrash() {
        setTop("휴지통", true)
        val list = Store.trash(this)
        root.addView(text("지운 기록은 30일 동안 여기 남아 있어요. [되살리기]를 누르면 원래 날짜로 돌아가요.", 13f, grey).apply { setPadding(dp(6), 0, dp(6), dp(8)) })
        if (list.isEmpty()) { root.addView(card().apply { addView(text("삭제된 기록이 없어요.", 15f, grey)) }); return }
        val f = SimpleDateFormat("MM-dd HH:mm", Locale.KOREA)
        list.forEach { x ->
            val t = x.trip
            root.addView(card().apply {
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    addView(text(f.format(Date(t.time)) + "  " + t.program, 14f, grey), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                    addView(text("순익 ${won(t.net)}", 15f, white, bold = true))
                })
                if (t.title.isNotEmpty()) addView(text(t.title, 14f, white).apply { maxLines = 2 })
                addView(text("${f.format(Date(x.deletedAt))} 삭제 · ${x.reason}", 12f, 0xFFE5A0A0.toInt()))
                addView(row(listOf(
                    button("되살리기", blue) { Store.restoreTrip(this@MainActivity, t.id); toast("되살렸어요"); render() },
                    button("완전 삭제", 0xFF4A2A2C.toInt()) { confirm("완전히 지울까요? 되살릴 수 없어요.") { Store.purgeTrash(this@MainActivity, t.id); render() } },
                )))
            })
        }
    }

    // ---------------- 새일지 등록 / 일지 수정 ----------------
    private var editing: Store.Trip? = null
    private var editBack = Screen.DAY

    private fun openEditor(t: Store.Trip?) {
        editing = t
        editBack = screen
        go(Screen.EDIT)
    }

    private fun renderEditor() {
        val t = editing
        setTop(if (t == null) "새일지 등록" else "일지 수정", true)
        val cal = Calendar.getInstance().apply {
            timeInMillis = t?.time ?: run {
                val now = Calendar.getInstance()
                if (dayKey(now.timeInMillis) == dateKey(day.timeInMillis)) now.timeInMillis
                else (day.clone() as Calendar).apply { set(Calendar.HOUR_OF_DAY, 12); set(Calendar.MINUTE, 0) }.timeInMillis
            }
        }
        val dFmt = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA)
        val tFmt = SimpleDateFormat("HH:mm", Locale.KOREA)

        // ---- 운행 정보 ----
        root.addView(sectionTitle("운행 정보"))
        val dateBtn = smallButton(dFmt.format(cal.time), innerBg)
        val timeBtn = smallButton(tFmt.format(cal.time), innerBg)
        dateBtn.setOnClickListener {
            android.app.DatePickerDialog(this, { _, y, m, d ->
                cal.set(y, m, d); dateBtn.text = dFmt.format(cal.time)
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
        }
        timeBtn.setOnClickListener {
            android.app.TimePickerDialog(this, { _, h, mi ->
                cal.set(Calendar.HOUR_OF_DAY, h); cal.set(Calendar.MINUTE, mi); timeBtn.text = tFmt.format(cal.time)
            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
        }
        val origin = edit("출발지").apply { setText(t?.origin.orEmpty()) }
        val via = edit("경유지 (없으면 비워두세요)").apply { setText(t?.via.orEmpty()) }
        val dest = edit("도착지").apply { setText(t?.dest.orEmpty()) }
        root.addView(card().apply {
            addView(formRow("일시", row(listOf(dateBtn, timeBtn), weights = listOf(1.4f, 1f))))
            addView(formRow(dot(blue, "● 출발"), origin))
            addView(formRow(dot(0xFFF5A623.toInt(), "● 경유"), via))
            addView(formRow(dot(red, "● 도착"), dest))
        })

        // ---- 요금 · 비용 ----
        root.addView(sectionTitle("요금 · 비용"))
        val fare = numEdit(t?.fare).apply { setTextColor(blue) }
        // 프로그램: 누르면 목록에서 고르기
        val program = edit("눌러서 선택").apply {
            setText(t?.program.orEmpty())
            isFocusable = false; isCursorVisible = false; isLongClickable = false
            setCompoundDrawablesWithIntrinsicBounds(null, null, null, null)
        }
        val programRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = rounded(0xFF2A2A2E.toInt(), 10)
            program.background = null
            addView(program, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(text("▾", 18f, grey).apply { setPadding(0, 0, dp(14), 0) })
        }
        val pick = View.OnClickListener {
            val names = arrayOf("카카오대리", "로지", "콜마너", "I-Driver", "T맵대리", "직접 입력…")
            AlertDialog.Builder(this).setTitle("프로그램").setItems(names) { _, i ->
                if (i < names.size - 1) program.setText(names[i])
                else {
                    val et = edit("프로그램 이름").apply { setText(program.text) }
                    AlertDialog.Builder(this).setTitle("직접 입력")
                        .setView(LinearLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(et) })
                        .setPositiveButton("확인") { _, _ -> program.setText(et.text.toString().trim()) }
                        .setNegativeButton("취소", null).show()
                }
            }.show()
        }
        program.setOnClickListener(pick); programRow.setOnClickListener(pick)
        val viaFee = numEdit(t?.viaFee)
        val tip = numEdit(t?.tip)
        val commission = numEdit(t?.commission).apply { setTextColor(red) }
        val insurance = numEdit(t?.insurance)
        val transport = numEdit(t?.transport)
        var transportMode = t?.transportMode.orEmpty()
        val modeBtn = smallButton(transportMode.ifEmpty { "교통수단" }, innerBg)
        modeBtn.setOnClickListener {
            val modes = arrayOf("버스", "지하철", "택시", "킥보드", "자전거", "도보", "기타")
            AlertDialog.Builder(this).setTitle("교통수단").setItems(modes) { _, i ->
                transportMode = modes[i]; modeBtn.text = transportMode
            }.show()
        }
        fun add(e: EditText, n: Int) = e.setText(((e.text.toString().toIntOrNull() ?: 0) + n).toString())
        fun put(e: EditText, n: Int) = e.setText(n.toString())

        root.addView(card().apply {
            addView(formRow("요금", fare))
            addView(formRow("프로그램", programRow))
            addView(formDivider())
            addView(formRow("경유비", row(listOf(viaFee, smallButton("+1000", 0xFF1F3354.toInt(), blue) { add(viaFee, 1000) }, smallButton("+5000", 0xFF1F3354.toInt(), blue) { add(viaFee, 5000) }), weights = listOf(1.6f, 1f, 1f))))
            addView(formRow("팁", row(listOf(tip, smallButton("+1000", 0xFF1F3354.toInt(), blue) { add(tip, 1000) }, smallButton("+5000", 0xFF1F3354.toInt(), blue) { add(tip, 5000) }), weights = listOf(1.6f, 1f, 1f))))
            addView(formDivider())
            addView(formRow("수수료", row(listOf(commission, smallButton("0원", innerBg) { put(commission, 0) }), weights = listOf(2.6f, 1f))))
            addView(formRow("보험료", row(listOf(insurance, smallButton("1000", innerBg) { put(insurance, 1000) }, smallButton("5000", innerBg) { put(insurance, 5000) }), weights = listOf(1.6f, 1f, 1f))))
            addView(formRow("교통비", row(listOf(transport, modeBtn), weights = listOf(1.6f, 1.2f))))
            addView(text("수수료는 요금의 ${commissionRate}%로 자동 계산돼요 (설정에서 바꿀 수 있어요)", 12f, grey).apply { setPadding(0, dp(8), 0, 0) })
        })

        // ---- 메모 ----
        root.addView(sectionTitle("메모"))
        val memo = edit("특이사항 (예: 대기 20분, 우천)").apply { setText(t?.memo.orEmpty()) }
        root.addView(card().apply { addView(memo) })

        // ---- 정산 (아래 고정) ----
        val inTv = text("0", 20f, blue, bold = true)
        val outTv = text("0", 20f, red, bold = true)
        val netTv = text("0", 20f, white, bold = true)
        fun v(e: EditText) = e.text.toString().filter { it.isDigit() }.toIntOrNull() ?: 0
        fun recalc() {
            val i = v(fare) + v(viaFee) + v(tip)
            val o = v(commission) + v(insurance) + v(transport)
            inTv.text = won(i); outTv.text = won(o); netTv.text = won(i - o)
        }
        // 요금을 바꾸면 수수료 자동 계산 (수수료를 직접 고치기 전까지)
        var commissionTouched = t != null && t.commission != t.fare * commissionRate / 100
        var settingCommission = false
        val watcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { recalc() }
        }
        listOf(viaFee, tip, insurance, transport).forEach { it.addTextChangedListener(watcher) }
        commission.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { if (!settingCommission) commissionTouched = true; recalc() }
        })
        fare.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (!commissionTouched) {
                    settingCommission = true
                    val c = v(fare) * commissionRate / 100
                    commission.setText(if (c > 0) c.toString() else "")
                    settingCommission = false
                }
                recalc()
            }
        })
        recalc()

        fun save(zeroCommission: Boolean) {
            if (zeroCommission) { settingCommission = true; commission.setText("0"); settingCommission = false }
            Store.upsertTrip(this, Store.Trip(
                id = t?.id ?: System.currentTimeMillis(), time = cal.timeInMillis,
                origin = origin.text.toString().trim(), via = via.text.toString().trim(), dest = dest.text.toString().trim(),
                program = program.text.toString().trim(),
                fare = v(fare), viaFee = v(viaFee), tip = v(tip),
                commission = v(commission), insurance = v(insurance), transport = v(transport), transportMode = transportMode,
                memo = memo.text.toString().trim(), callKey = t?.callKey.orEmpty(),
                confirmed = true, pkg = t?.pkg.orEmpty(),
            ))
            toast(if (t != null && !t.confirmed) "운행일지에 추가했어요" else "저장했어요")
            go(editBack)
        }

        footer.visibility = View.VISIBLE
        footer.setPadding(dp(16), dp(10), dp(16), dp(12))
        footer.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(text("정산", 17f, white, bold = true), LinearLayout.LayoutParams(dp(56), WRAP_CONTENT))
            listOf(Triple("수입", inTv, blue), Triple("지출", outTv, red), Triple("순익", netTv, white)).forEach { (l, tv, _) ->
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text(l, 12f, grey)); addView(tv)
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            }
        })
        footer.addView(row(listOf(
            button("저장", blue) { save(false) },
            button("수수료 0원 저장", innerBg) { save(true) },
        ), weights = listOf(1.2f, 1f)))
    }

    private fun sectionTitle(s: String) = text(s, 14f, grey, bold = true).apply { setPadding(dp(6), dp(6), 0, dp(6)) }

    private fun formRow(label: CharSequence, v: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) }
        addView(text(label, 15f, 0xFFBBBBC0.toInt(), bold = true).apply { gravity = Gravity.CENTER_VERTICAL },
            LinearLayout.LayoutParams(dp(80), WRAP_CONTENT))
        // 이름과 입력칸 높이를 맞추려고 안쪽 위 여백을 없앤다
        if (v is LinearLayout && v.orientation == LinearLayout.HORIZONTAL) {
            for (i in 0 until v.childCount) (v.getChildAt(i).layoutParams as? LinearLayout.LayoutParams)?.topMargin = 0
        }
        addView(v, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    }

    private fun formDivider() = View(this).apply {
        setBackgroundColor(line)
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(1)).apply { topMargin = dp(12); bottomMargin = dp(6) }
    }

    private fun numEdit(value: Int?) = edit("0").apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        if (value != null && value > 0) setText(value.toString())
    }

    private fun smallButton(label: String, color: Int, textColor: Int = white, onClick: (() -> Unit)? = null) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 15f; setTextColor(textColor); setTypeface(typeface, Typeface.BOLD)
        minHeight = dp(50); minimumHeight = dp(50); setPadding(dp(4), 0, dp(4), 0)
        background = rounded(color, 12)
        if (onClick != null) setOnClickListener { onClick() }
    }

    private fun chip(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 12f; setTextColor(0xFFCCCCCC.toInt()); maxLines = 1; isSingleLine = true
        setPadding(dp(2), dp(8), dp(2), dp(8)); background = rounded(innerBg, 14, line)
        setOnClickListener { onClick() }
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
            addView(text("말풍선 보이는 시간", 15f, white, bold = true).apply { setPadding(0, dp(14), 0, 0) })
            val keeps = listOf(10 to "10초", 30 to "30초", 60 to "1분", 300 to "5분")
            addView(row(keeps.map { (sec, label) ->
                button(label, if (bubbleKeepSec == sec) blue else innerBg) {
                    bubbleKeepSec = sec
                    toast("콜이 들어오면 말풍선을 ${label} 동안 보여줘요")
                    render()
                }
            }))
            addView(text("시간이 지나면 말풍선이 숨겨지고, 다음 콜이 오면 다시 떠요.", 12f, grey).apply { setPadding(0, dp(8), 0, 0) })
            addView(button("새 콜일 때 진동: " + if (bubbleVibrate) "켜짐" else "꺼짐", if (bubbleVibrate) blue else innerBg) {
                bubbleVibrate = !bubbleVibrate
                toast(if (bubbleVibrate) "새 콜이 오면 짧게 진동해요" else "진동을 껐어요")
                render()
            })
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
            addView(button("수락한 콜 바로 일지에 넣기: " + if (autoToLog) "켜짐" else "꺼짐", if (autoToLog) blue else innerBg) {
                autoToLog = !autoToLog
                toast(if (autoToLog) "수락한 콜이 바로 운행일지에 들어가요" else "자동운행기록에서 [일지에 추가]를 눌러야 들어가요")
                render()
            })
            addView(space(12))
            addView(text("하루가 바뀌는 시각", 16f, white, bold = true))
            addView(text("이 시각 전까지는 전날 운행으로 계산해요 (목표·오늘 합계·운행일지)", 12f, grey))
            // 누르면 아래로 00시~23시 목록이 펼쳐지고, 고르면 닫힌다
            addView(button("%02d시  ".format(dayStartHour) + if (hourOpen) "▲" else "▼", innerBg) {
                hourOpen = !hourOpen; render()
            }.apply { background = rounded(innerBg, 12, line); gravity = Gravity.CENTER_VERTICAL or Gravity.START; setPadding(dp(18), 0, dp(18), 0) })
            if (hourOpen) addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(0xFF2A2A2E.toInt(), 12, line)
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4) }
                for (h in 0 until 24) {
                    if (h > 0) addView(View(this@MainActivity).apply { setBackgroundColor(line) },
                        LinearLayout.LayoutParams(MATCH_PARENT, 1).apply { leftMargin = dp(14); rightMargin = dp(14) })
                    addView(TextView(this@MainActivity).apply {
                        text = "%02d시".format(h) + if (h == dayStartHour) "  ✓" else ""
                        textSize = 16f; setTextColor(if (h == dayStartHour) blue else white)
                        if (h == dayStartHour) setTypeface(typeface, Typeface.BOLD)
                        gravity = Gravity.CENTER_VERTICAL; setPadding(dp(18), 0, dp(18), 0)
                        minHeight = dp(46)
                        setOnClickListener { dayStartHour = h; hourOpen = false; toast("%02d시에 하루가 바뀌어요".format(h)); render() }
                    })
                }
            })
        })

        root.addView(card().apply {
            addView(text("고정비", 16f, white, bold = true))
            addView(text("월고정비: 보험료·통신비처럼 한 달에 한 번 나가는 돈 → 운행일지 월 합계 지출에 들어가요\n일고정비: 프로그램 일비처럼 운행한 날마다 나가는 돈 → 그날 합계 지출에 들어가요", 12f, grey))
            fun money(v: Int) = edit("0").apply { inputType = InputType.TYPE_CLASS_NUMBER; if (v > 0) setText(v.toString()) }
            val mf = money(monthFixed); val df = money(dayFixed)
            addView(row(listOf(
                LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; addView(text("월고정비 (원)", 13f, grey)); addView(mf) },
                LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; addView(text("일고정비 (원)", 13f, grey)); addView(df) },
            )))
            addView(button("고정비 저장", blue) {
                monthFixed = mf.text.toString().filter { it.isDigit() }.toIntOrNull() ?: 0
                dayFixed = df.text.toString().filter { it.isDigit() }.toIntOrNull() ?: 0
                toast("월 ${won(monthFixed)}원 · 일 ${won(dayFixed)}원 저장했어요"); render()
            })
        })

        root.addView(card().apply {
            addView(text("집(복귀) 주소", 13f, grey))
            val home = edit("예) 경기 수원시 팔달구 효원로 241").apply { setText(homeAddress) }
            addView(home)
            addView(text("수수료율 (%) — 입금액이 안 나오는 콜(카카오 등) 수수료 자동 계산", 13f, grey).apply { setPadding(0, dp(10), 0, 0) })
            val rate = edit("20").apply { inputType = InputType.TYPE_CLASS_NUMBER; setText(commissionRate.toString()) }
            addView(rate)
            addView(button("저장", blue) {
                homeAddress = home.text.toString().trim()
                rate.text.toString().toIntOrNull()?.let { commissionRate = it }
                toast("저장했어요")
            })
            // 카카오 키는 앱에 들어 있음 — 잘 되는지만 확인
            addView(button("🔑 카카오 연결 시험", innerBg) {
                toast("카카오에 물어보는 중…")
                Thread {
                    val r = Geo.testKey(kakaoKey)
                    runOnUiThread { if (!isFinishing) AlertDialog.Builder(this@MainActivity).setTitle("카카오 연결 시험").setMessage(r).setPositiveButton("확인", null).show() }
                }.start()
            })
        })

        // 운행일지 데이터 상태 + 백업에서 되살리기
        root.addView(card().apply {
            val all = Store.trips(this@MainActivity)
            addView(text("📒 운행일지 데이터", 16f, white, bold = true))
            addView(text("일지 ${all.count { it.confirmed }}건 · 일지에 안 넣은 자동운행기록 ${all.count { !it.confirmed }}건 · 휴지통 ${Store.trash(this@MainActivity).size}건", 14f, 0xFF7FB2FF.toInt()))
            addView(text("운행일지가 바뀔 때마다 바뀌기 전 상태를 자동으로 백업해요 (30일 보관).", 12f, grey))
            addView(button("백업에서 되살리기", innerBg) {
                val list = Store.backups(this@MainActivity)
                if (list.isEmpty()) { toast("아직 백업이 없어요"); return@button }
                val names = list.map { "${it.label} · 일지 ${it.confirmed}건 · 자동 ${it.pending}건" }.toTypedArray()
                AlertDialog.Builder(this@MainActivity).setTitle("어느 때로 되살릴까요?").setItems(names) { _, i ->
                    confirm("${list[i].label} 백업에만 있는 기록을 지금 기록에 더할까요?\n(지금 기록은 그대로 남아요)") {
                        val n = Store.mergeBackup(this@MainActivity, list[i])
                        toast(if (n > 0) "${n}건을 되살렸어요" else "되살릴 기록이 없어요 (이미 다 있어요)")
                        render()
                    }
                }.show()
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

    /** 운행한 날이면 일고정비 */
    private fun dayFix(list: List<Store.Trip>) = if (list.isNotEmpty()) dayFixed else 0

    private fun fixedNote(fixed: Int, label: String): View? =
        if (fixed <= 0) null else text("지출에 $label ${won(fixed)}원 포함", 12f, grey).apply { setPadding(0, dp(4), 0, 0) }

    private fun summaryBlock(title: String, list: List<Store.Trip>, titleColor: Int = white, fixed: Int = 0, fixedLabel: String = "일고정비") = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(text(title, 18f, titleColor, bold = true))
            addView(badge("${list.size}콜", 0xFF1F3354.toInt(), blue).apply {
                layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(10) }
            })
        })
        addView(totalsRow(list.sumOf { it.income }, list.sumOf { it.expense } + fixed))
        fixedNote(fixed, fixedLabel)?.let { addView(it) }
    }

    private fun confirm(msg: String, onYes: () -> Unit) {
        AlertDialog.Builder(this).setMessage(msg)
            .setPositiveButton("확인") { _, _ -> onYes() }
            .setNegativeButton("취소", null).show()
    }

    private fun edit(hint: String, multi: Boolean = false) = EditText(this).apply {
        this.hint = hint; setHintTextColor(0xFF777777.toInt()); setTextColor(white); textSize = 15f
        gravity = if (multi) Gravity.TOP or Gravity.START else Gravity.CENTER_VERTICAL or Gravity.START
        if (multi) { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE; minLines = 3 }
        else inputType = InputType.TYPE_CLASS_TEXT
        background = rounded(0xFF2A2A2E.toInt(), 10)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        minHeight = dp(50)
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) }
    }
}
