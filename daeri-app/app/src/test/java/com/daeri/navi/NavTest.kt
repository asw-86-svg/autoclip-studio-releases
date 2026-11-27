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
}
