package com.daeri.navi

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 뒤로 가기 = 바로 전 화면 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NavTest {
    private fun MainActivity.screen(): String = javaClass.getDeclaredField("screen").let { it.isAccessible = true; it.get(this).toString() }
    private fun MainActivity.go(s: String) {
        val enumCls = Class.forName("com.daeri.navi.MainActivity\$Screen")
        val v = enumCls.enumConstants.first { it.toString() == s }
        javaClass.getDeclaredMethod("go", enumCls).let { it.isAccessible = true; it.invoke(this, v) }
    }

    @Test fun 목록에서_콜상세_뒤로가면_목록() {
        val a = Robolectric.buildActivity(MainActivity::class.java, Intent(RuntimeEnvironment.getApplication(), MainActivity::class.java)).setup().get()
        assertEquals("HOME", a.screen())
        a.go("CALLS"); a.go("CALL")
        a.onBackPressed(); assertEquals("CALLS", a.screen())
        a.onBackPressed(); assertEquals("HOME", a.screen())
    }

    @Test fun 운행일지_하루_수정_뒤로() {
        val a = Robolectric.buildActivity(MainActivity::class.java, Intent(RuntimeEnvironment.getApplication(), MainActivity::class.java)).setup().get()
        a.go("LOG"); a.go("DAY"); a.go("EDIT")
        a.onBackPressed(); assertEquals("DAY", a.screen())
        a.onBackPressed(); assertEquals("LOG", a.screen())
        a.onBackPressed(); assertEquals("HOME", a.screen())
    }

    @Test fun 자동운행기록에서_지도_뒤로() {
        val a = Robolectric.buildActivity(MainActivity::class.java, Intent(RuntimeEnvironment.getApplication(), MainActivity::class.java)).setup().get()
        a.go("AUTO"); a.go("CALL")
        a.onBackPressed(); assertEquals("AUTO", a.screen())
    }

    @Suppress("UNCHECKED_CAST")
    private fun MainActivity.search(q: String): List<Store.Trip> =
        javaClass.getDeclaredMethod("searchTrips", String::class.java).let { it.isAccessible = true; it.invoke(this, q) as List<Store.Trip> }

    @Test fun 운행일지_검색() {
        val ctx = RuntimeEnvironment.getApplication()
        val now = System.currentTimeMillis()
        Store.upsertTrip(ctx, Store.Trip(id = 101, time = now, origin = "가락동 다농마트", dest = "경기 수원시 영통구 매영로 366", program = "카카오대리", fare = 32000, confirmed = true))
        Store.upsertTrip(ctx, Store.Trip(id = 102, time = now - 3600_000, origin = "동탄역", dest = "오산시 누읍로 20", program = "로지", fare = 25000, memo = "대기 20분", confirmed = true))
        Store.upsertTrip(ctx, Store.Trip(id = 103, time = now, origin = "다농마트", dest = "x", program = "로지", fare = 9000, confirmed = false)) // 일지에 안 넣은 건 제외
        val a = Robolectric.buildActivity(MainActivity::class.java, Intent(ctx, MainActivity::class.java)).setup().get()
        assertEquals(listOf(101L), a.search("다농마트").map { it.id })
        assertEquals(listOf(101L), a.search("다농 마트").map { it.id })      // 띄어쓰기 달라도
        assertEquals(listOf(101L), a.search("32,000").map { it.id })        // 금액
        assertEquals(listOf(102L), a.search("동탄 로지").map { it.id })      // 두 단어 모두
        assertEquals(listOf(102L), a.search("대기").map { it.id })           // 메모
        assertEquals(0, a.search("부산").size)
        assertEquals(0, a.search("  ").size)
    }

    /** 폴드 펼침 등으로 앱이 다시 띄워져도 보던 화면 그대로 (영어 이름: 람다 포함) */
    @Test fun keepsScreenAfterRecreate() {
        val ctl = Robolectric.buildActivity(MainActivity::class.java, Intent(RuntimeEnvironment.getApplication(), MainActivity::class.java)).setup()
        ctl.get().go("CALLS"); ctl.get().go("CALL")
        ctl.recreate()
        assertEquals("CALL", ctl.get().screen())
        ctl.get().onBackPressed(); assertEquals("CALLS", ctl.get().screen())
    }

    /** 폴드 펼침(화면 크기 바뀜): 다시 띄우지 않고 그 화면 그대로 */
    @Test fun keepsScreenOnUnfold() {
        val ctl = Robolectric.buildActivity(MainActivity::class.java, Intent(RuntimeEnvironment.getApplication(), MainActivity::class.java)).setup()
        val a = ctl.get()
        a.go("CALLS"); a.go("CALL")
        val cfg = android.content.res.Configuration(a.resources.configuration).apply { screenWidthDp = 840; screenHeightDp = 900; smallestScreenWidthDp = 840 }
        ctl.configurationChange(cfg)
        assertEquals(a, ctl.get())               // 같은 화면(다시 만들지 않음)
        assertEquals("CALL", ctl.get().screen())
    }
}
