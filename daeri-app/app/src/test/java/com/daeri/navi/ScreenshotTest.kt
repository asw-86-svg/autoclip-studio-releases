package com.daeri.navi

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 실제 앱 화면을 그려서 PNG 로 저장한다 (올리기 전에 눈으로 확인용).
 * SCREENSHOTS=1 gradle testDebugUnitTest --tests '*ScreenshotTest*'
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotTest {

    private val out = File(System.getProperty("user.dir"), "build/screens").apply { mkdirs() }

    private fun seed() {
        val ctx = RuntimeEnvironment.getApplication()
        val now = System.currentTimeMillis()
        with(Store) { ctx.monthFixed = 300000; ctx.dayFixed = 10000; ctx.dayStartHour = 0; ctx.workStart = now - 5 * 3600_000L }
        Store.upsertTrip(ctx, Store.Trip(
            id = 1L, time = now, origin = "인계동 포포인츠 바이 쉐라톤 수원", dest = "경기 수원 영통구 망포동 힐스테이트영통아파트",
            program = "카카오대리", fare = 16800, commission = 3360, callKey = "k1",
        ))
        Store.setCurrent(ctx, CallInfo("로지", "탁송)매송고색로882", "강원 춘천시 석사동)춘천시 스무숲3길 6/톨포",
            listOf("경기 수원시 권선구 매송고색로 882"), listOf("춘천시 스무숲3길 6"), "55000원", pkg = "com.logi.test", recommended = true))
        Store.upsertTrip(ctx, Store.Trip(id = 2L, time = now - 3600_000L, origin = "서천동 남촌홍어", dest = "인계동 수원센트럴아이파크자이", program = "카카오대리", fare = 22400, commission = 4480, callKey = "k2"))
        Store.deleteTrip(ctx, 2L)
        Store.upsertTrip(ctx, Store.Trip(id = 3L, time = now - 600_000L, origin = "고덕동 에이스퍼스트고덕", dest = "경기 오산시 누읍로 20",
            program = "카카오대리", fare = 36800, commission = 7360, callKey = "k3", confirmed = false, via = "서정동, 가수동"))
    }

    private fun shot(name: String, open: String, h: Int = 2340, extra: (Intent) -> Unit = {}) {
        assumeTrue(System.getenv("SCREENSHOTS") == "1")
        if (name != "call_kakao") seed()
        val intent = Intent(RuntimeEnvironment.getApplication(), MainActivity::class.java).putExtra("open", open)
        extra(intent)
        val act = Robolectric.buildActivity(MainActivity::class.java, intent).setup().get()
        val root = act.window.decorView
        val w = 1080
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun bubble() {
        assumeTrue(System.getenv("SCREENSHOTS") == "1")
        val v = CallReaderService.makeBubbleView(RuntimeEnvironment.getApplication())
        v.text = CallReaderService.bubbleText(listOf("출 - 🚶5분", "출 → 도 🚗43분"), partner = true)
        v.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
        val bmp = Bitmap.createBitmap(v.measuredWidth + 40, v.measuredHeight + 40, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); c.drawColor(0xFF7A2A2A.toInt()); c.translate(20f, 20f); v.draw(c)
        File(out, "bubble.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun editor() = shot("editor", "edit", 3900) { it.putExtra("trip", 1L) }
    @Test fun newEditor() = shot("editor_new", "edit", 3900)
    @Test fun day() = shot("day", "day")
    @Test fun home() = shot("home", "home")
    @Test fun call() = shot("call", "call", 2700)
    @Test fun calls() = shot("calls", "calls")
    @Test fun auto() = shot("auto", "auto", 2600)
    @Test fun trash() = shot("trash", "trash")
    @Test fun settings() = shot("settings", "settings", 4600)
    @Test fun log() = shot("log", "log")
    /** 지도 미리보기용: 실제로 받았던 카카오 콜 */
    @Test fun callKakao() {
        assumeTrue(System.getenv("SCREENSHOTS") == "1")
        seed()
        val ctx = RuntimeEnvironment.getApplication()
        Store.setCurrent(ctx, CallInfo("카카오T 대리", "수원시 영통구 영통동 1012-4", "화성시 동탄구 영천동 701-12",
            listOf("수원시 영통구 영통동 1012-4"), listOf("화성시 동탄구 영천동 701-12"), "12,000", pkg = "com.kakao.driver", partner = true))
        shot("call_kakao", "call", 2700)
    }
    @Test fun homeAd() {
        Ads.setJson(RuntimeEnvironment.getApplication(), """{"ads":[{"id":"t1","title":"○○ 대리기사 보험","text":"하루 3,000원부터 · 24시간 상담","link":"tel:01000000000","button":"📞 전화"}]}""")
        shot("home_ad", "home", 4400)
    }
    @Test fun report() {
        val ctx = RuntimeEnvironment.getApplication()
        val now = System.currentTimeMillis()
        val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.KOREA).format(java.util.Date(now))
        Store.setGoal(ctx, day, 200000)
        Store.upsertTrip(ctx, Store.Trip(id = 11L, time = now - 120_000L, origin = "가락동 다농마트", dest = "경기 수원시 영통구 매영로 366",
            program = "카카오대리", fare = 32000, commission = 6400, viaFee = 5000, tip = 2000, callKey = "k11"))
        Store.upsertTrip(ctx, Store.Trip(id = 12L, time = now - 60_000L, origin = "탁송 매송고색로 882", dest = "춘천시 스무숲3길 6",
            program = "로지", fare = 55000, commission = 11000, callKey = "k12"))
        shot("report", "report")
    }
    @Test fun settingsHour() = shot("settings_hour", "settings", 5600) { it.putExtra("hourOpen", true) }
}
