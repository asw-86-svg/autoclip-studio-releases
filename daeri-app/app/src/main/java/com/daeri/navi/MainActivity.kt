package com.daeri.navi

import android.app.Activity
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
import com.daeri.navi.Store.detectPaused
import com.daeri.navi.Store.homeAddress
import com.daeri.navi.Store.kakaoKey
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private val bg = 0xFF121212.toInt()
    private val cardBg = 0xFF1F1F1F.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val grey = 0xFFA0A0A0.toInt()
    private val kakaoYellow = 0xFFFEE500.toInt()
    private val naverGreen = 0xFF03C75A.toInt()
    private val blue = 0xFF1E6FE0.toInt()

    private lateinit var root: LinearLayout
    private var showDump = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(14), dp(14), dp(40)) }
        setContentView(ScrollView(this).apply { setBackgroundColor(bg); addView(root) })
        handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun handleShare(i: Intent?) {
        if (i?.action != Intent.ACTION_SEND) return
        i.getStringExtra(Intent.EXTRA_TEXT)?.let { parseAndSet(it, "공유") }
    }

    private fun parseAndSet(text: String, source: String) {
        val c = CallParser.parse(text.lines(), source)
        if (c == null || (c.originRaw.isBlank() && c.destRaw.isBlank())) {
            toast("출발지/도착지를 찾지 못했어요. '출발:' '도착:' 처럼 적혀 있는지 확인해 주세요")
            return
        }
        Store.setCurrent(this, c)
        CallReaderService.instance?.showBubble()
    }

    // =================== 화면 그리기 ===================
    private fun render() {
        root.removeAllViews()
        root.addView(text("대리내비", 24f, white, bold = true))
        root.addView(space(8))
        renderStatus()
        Store.current(this)?.let { renderCall(it) } ?: root.addView(card().apply {
            addView(text("아직 읽어온 콜이 없어요.\n대리 프로그램에서 콜 상세화면을 열면 자동으로 나타납니다.", 15f, grey))
        })
        renderPaste()
        renderHistory()
        renderSettings()
        renderDebug()
    }

    private fun serviceEnabled(): Boolean {
        if (CallReaderService.instance != null) return true
        val list = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        return list.contains("$packageName/") && list.contains("CallReaderService")
    }

    private fun renderStatus() {
        val on = serviceEnabled()
        root.addView(card(if (on) 0xFF15351F.toInt() else 0xFF3A1717.toInt()).apply {
            if (on) {
                addView(text(if (detectPaused) "⏸ 자동 읽기 일시정지" else "✅ 자동 읽기 켜짐", 17f, white, bold = true))
                addView(button(if (detectPaused) "자동 읽기 다시 켜기" else "자동 읽기 잠시 멈추기", 0xFF444444.toInt()) {
                    detectPaused = !detectPaused; render()
                })
            } else {
                addView(text("⚠️ 자동 읽기가 꺼져 있어요", 17f, white, bold = true))
                addView(text(
                    "1) 아래 [접근성 설정 열기] → 설치된 앱 → '대리내비' 켜기\n" +
                        "2) 회색으로 눌리지 않으면: [앱 정보 열기] → 오른쪽 위 ⋮ → '제한된 설정 허용' 후 1)번 다시",
                    14f, white,
                ))
                addView(button("접근성 설정 열기", blue) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
                addView(button("앱 정보 열기 (제한된 설정 허용)", 0xFF444444.toInt()) {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                })
            }
        })
    }

    private fun renderCall(c: CallInfo) {
        val time = SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(c.capturedAt))
        root.addView(card().apply {
            addView(text("${c.source} · $time" + if (c.fare.isNotEmpty()) " · ${c.fare}" else "", 13f, grey))

            // 출발지
            addView(text("🟢 출발지", 14f, naverGreen, bold = true))
            addView(text(c.originDisplay.ifEmpty { "(없음)" }, 20f, white, bold = true))
            if (c.originRaw.isNotEmpty() && c.originRaw != c.originDisplay) addView(text(c.originRaw, 12f, grey))
            val oLabel = text("", 12f, 0xFF7FB2FF.toInt()); addView(oLabel)
            addView(row(
                button("카카오맵\n출발지로", kakaoYellow, 0xFF000000.toInt()) { navigate(App.KAKAO, c.originQueries) },
                button("네이버\n출발지로", naverGreen) { navigate(App.NAVER, c.originQueries) },
            ))
            addView(space(10))

            // 도착지
            addView(text("🔴 도착지", 14f, 0xFFFF6B6B.toInt(), bold = true))
            addView(text(c.destDisplay.ifEmpty { "(없음)" }, 20f, white, bold = true))
            if (c.destRaw.isNotEmpty() && c.destRaw != c.destDisplay) addView(text(c.destRaw, 12f, grey))
            val dLabel = text("", 12f, 0xFF7FB2FF.toInt()); addView(dLabel)
            addView(row(
                button("카카오맵\n도착지로", kakaoYellow, 0xFF000000.toInt()) { navigate(App.KAKAO, c.destQueries) },
                button("네이버\n도착지로", naverGreen) { navigate(App.NAVER, c.destQueries) },
            ))
            addView(row(
                button("출발→도착 경로\n(카카오)", 0xFF3A3A3A.toInt()) { fullRoute(App.KAKAO, c) },
                button("출발→도착 경로\n(네이버)", 0xFF3A3A3A.toInt()) { fullRoute(App.NAVER, c) },
            ))

            // 복귀
            addView(space(6))
            addView(row(
                button("🚌 집까지 대중교통\n(카카오)", 0xFF2B3A55.toInt()) { goHome(App.KAKAO) },
                button("🚌 집까지 대중교통\n(네이버)", 0xFF2B3A55.toInt()) { goHome(App.NAVER) },
            ))

            // 전화번호
            if (c.phones.isNotEmpty()) {
                addView(space(6))
                c.phones.take(6).forEach { (label, num) ->
                    addView(button("📞 $label  $num", 0xFF333333.toInt()) {
                        startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + num.filter { it.isDigit() })))
                    })
                }
            }
            if (c.notes.isNotEmpty()) {
                addView(space(6))
                addView(text(c.notes, 13f, 0xFFDDDDDD.toInt()))
            }
            addView(space(6))
            addView(button("운행 끝 (말풍선 숨기기)", 0xFF555555.toInt()) {
                Store.setCurrent(this@MainActivity, null)
                CallReaderService.instance?.hideBubble()
                render()
            })

            // 지도에서 찾은 실제 위치를 미리 표시 (주소가 맞는지 확인용)
            resolveAsync(c.originQueries) { oLabel.text = it?.let { p -> "📍 지도 위치: ${p.label}" } ?: "📍 정확한 위치를 못 찾음 → 누르면 지도앱 검색창에 주소가 들어간 채로 열려요 (카카오 키를 넣으면 더 정확해져요)" }
            resolveAsync(c.destQueries) { dLabel.text = it?.let { p -> "📍 지도 위치: ${p.label}" } ?: "📍 정확한 위치를 못 찾음 → 누르면 지도앱 검색창에 주소가 들어간 채로 열려요 (카카오 키를 넣으면 더 정확해져요)" }
        })
    }

    private fun renderPaste() {
        root.addView(card().apply {
            addView(text("✍️ 직접 붙여넣기 (문자·카톡 콜)", 16f, white, bold = true))
            val et = edit("예) 출발: 수원시 권선구 권선로 308-5\n     도착: 대전 유성구 용산1로 99", multi = true)
            addView(et)
            addView(row(
                button("클립보드 붙여넣기", 0xFF444444.toInt()) {
                    val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    et.setText(cm.primaryClip?.getItemAt(0)?.coerceToText(this@MainActivity) ?: "")
                },
                button("분석하기", blue) {
                    parseAndSet(et.text.toString(), "붙여넣기"); render()
                },
            ))
        })
    }

    private fun renderHistory() {
        val hist = Store.history(this)
        if (hist.isEmpty()) return
        root.addView(card().apply {
            addView(text("🕘 최근 콜 (눌러서 다시 보기)", 16f, white, bold = true))
            val fmt = SimpleDateFormat("MM/dd HH:mm", Locale.KOREA)
            hist.take(15).forEach { h ->
                addView(TextView(this@MainActivity).apply {
                    text = "${fmt.format(Date(h.capturedAt))}  ${h.fare}\n  ${h.originDisplay}\n  → ${h.destDisplay}"
                    textSize = 13f; setTextColor(0xFFDDDDDD.toInt()); setPadding(0, dp(8), 0, dp(8))
                    setOnClickListener { Store.setCurrent(this@MainActivity, h); CallReaderService.instance?.showBubble(); render() }
                })
            }
            addView(button("기록 지우기", 0xFF444444.toInt()) { Store.clearHistory(this@MainActivity); render() })
        })
    }

    private fun renderSettings() {
        root.addView(card().apply {
            addView(text("⚙️ 설정", 16f, white, bold = true))
            addView(text("집(복귀) 주소", 13f, grey))
            val home = edit("예) 경기 수원시 팔달구 효원로 241").apply { setText(homeAddress) }
            addView(home)
            addView(text("카카오 REST API 키 (선택 · 넣으면 주소 찾기가 훨씬 정확해져요)", 13f, grey))
            val key = edit("developers.kakao.com 에서 무료 발급").apply { setText(kakaoKey) }
            addView(key)
            addView(button("저장", blue) {
                homeAddress = home.text.toString().trim()
                kakaoKey = key.text.toString().trim()
                toast("저장했어요"); render()
            })
        })
    }

    private fun renderDebug() {
        root.addView(card().apply {
            addView(text("🛠 주소가 이상하게 나올 때", 16f, white, bold = true))
            addView(text("마지막으로 읽은 대리앱 화면 글자를 복사해서 개발자에게 보내주세요.", 13f, grey))
            addView(row(
                button(if (showDump) "숨기기" else "읽은 글자 보기", 0xFF444444.toInt()) { showDump = !showDump; render() },
                button("복사하기", 0xFF444444.toInt()) {
                    val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("dump", Store.dump(this@MainActivity)))
                    toast("복사했어요")
                },
            ))
            if (showDump) addView(text(Store.dump(this@MainActivity).ifEmpty { "(아직 없음)" }, 11f, 0xFFCCCCCC.toInt()))
        })
    }

    // =================== 지도 동작 ===================
    private fun resolveAsync(queries: List<String>, done: (Geo.Pt?) -> Unit) {
        if (queries.isEmpty()) { done(null); return }
        Thread {
            val pt = Geo.resolve(this, queries)
            runOnUiThread { if (!isFinishing) done(pt) }
        }.start()
    }

    private fun navigate(app: App, queries: List<String>) {
        if (queries.isEmpty()) { toast("주소가 없어요"); return }
        resolveAsync(queries) { pt ->
            if (pt != null) MapLauncher.route(this, app, null, pt)
            else { toast("정확한 위치를 못 찾아 지도 검색으로 엽니다"); MapLauncher.search(this, app, queries.first()) }
        }
    }

    private fun fullRoute(app: App, c: CallInfo) {
        resolveAsync(c.originQueries) { o ->
            resolveAsync(c.destQueries) { d ->
                when {
                    o != null && d != null -> MapLauncher.route(this, app, o, d)
                    else -> { toast("출발지/도착지 위치를 못 찾아 도착지만 엽니다"); navigate(app, c.destQueries) }
                }
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

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(2), 0, dp(2)); setTextIsSelectable(!bold)
    }

    private fun card(color: Int = cardBg) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = GradientDrawable().apply { setColor(color); cornerRadius = dp(14).toFloat() }
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(12) }
    }

    private fun button(label: String, color: Int, textColor: Int = white, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 15f; setTextColor(textColor)
        setTypeface(typeface, Typeface.BOLD)
        minHeight = dp(56)
        background = GradientDrawable().apply { setColor(color); cornerRadius = dp(10).toFloat() }
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) }
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        isBaselineAligned = false
        views.forEachIndexed { i, v ->
            v.layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                topMargin = dp(6); if (i > 0) leftMargin = dp(8)
            }
            addView(v)
        }
    }

    private fun edit(hint: String, multi: Boolean = false) = EditText(this).apply {
        this.hint = hint; setHintTextColor(0xFF777777.toInt()); setTextColor(white); textSize = 15f
        gravity = Gravity.TOP or Gravity.START
        if (multi) { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE; minLines = 3 }
        else inputType = InputType.TYPE_CLASS_TEXT
        background = GradientDrawable().apply { setColor(0xFF2A2A2A.toInt()); cornerRadius = dp(8).toFloat() }
        setPadding(dp(10), dp(10), dp(10), dp(10))
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4) }
    }
}
