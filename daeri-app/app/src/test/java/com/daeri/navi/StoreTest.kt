package com.daeri.navi

import com.daeri.navi.Store.autoToLog
import com.daeri.navi.Store.detectPaused
import com.daeri.navi.Store.kakaoJsKey
import com.daeri.navi.Store.kakaoKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 운행일지 데이터가 사라지지 않는지, 기본 설정이 맞는지 — 버전 올릴 때마다 확인 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StoreTest {
    private val ctx get() = RuntimeEnvironment.getApplication()
    private val now = System.currentTimeMillis()

    private fun call(o: String, d: String, fare: String, accepted: Boolean, src: String = "카카오T 대리") =
        CallInfo(src, o, d, listOf(o), listOf(d), fare, accepted = accepted, capturedAt = now, pkg = "com.kakao.driver")

    @Before fun clean() { Store.clearHistory(ctx) }

    @Test fun 출근_전에는_퇴근_상태() {
        assertTrue(ctx.detectPaused)
    }

    @Test fun 카카오_키_내장() {
        assertEquals(32, ctx.kakaoKey.length)
        assertEquals(32, ctx.kakaoJsKey.length)
    }

    @Test fun 수락한_콜은_바로_운행일지에() {
        assertTrue(ctx.autoToLog)
        Store.setCurrent(ctx, call("가락동 다농마트", "경기 수원시 영통구 매영로 366", "32,000", accepted = true))
        val t = Store.trips(ctx).single()
        assertTrue(t.confirmed)
        assertEquals(32000, t.fare)
        assertEquals(6400, t.commission)
        assertEquals("카카오대리", t.program)
    }

    @Test fun 같은_콜은_한번만_기록() {
        val c = call("가락동 다농마트", "매영로 366", "32,000", accepted = true)
        Store.setCurrent(ctx, c); Store.setCurrent(ctx, c.copy(capturedAt = now + 60_000))
        assertEquals(1, Store.trips(ctx).size)
    }

    @Test fun 수락_전_콜은_일지에_안들어감() {
        Store.setCurrent(ctx, call("고덕동", "오산시 누읍로 20", "36,800", accepted = false))
        assertEquals(0, Store.trips(ctx).size)
        assertEquals(1, Store.history(ctx).size)
    }

    @Test fun 너무_싼_요금은_기록_안함() {
        Store.setCurrent(ctx, call("인계동", "망포동", "500", accepted = true))
        assertEquals(0, Store.trips(ctx).size)
    }

    @Test fun 자동_청소가_운행일지를_지우지_않음() {
        Store.upsertTrip(ctx, Store.Trip(id = 1, time = now, origin = "a", dest = "b", program = "로지", fare = 20000, confirmed = true))
        Store.upsertTrip(ctx, Store.Trip(id = 2, time = now, origin = "c", dest = "d", program = "", fare = 30000, confirmed = true))
        Store.cleanupJunk(ctx)
        assertEquals(2, Store.trips(ctx).size)
    }

    @Test fun 삭제하면_휴지통으로_되살리기_가능() {
        Store.upsertTrip(ctx, Store.Trip(id = 5, time = now, origin = "a", dest = "b", program = "로지", fare = 20000, confirmed = true))
        Store.deleteTrip(ctx, 5)
        assertEquals(0, Store.trips(ctx).size)
        assertEquals(1, Store.trash(ctx).size)
        Store.restoreTrip(ctx, 5)
        assertEquals(1, Store.trips(ctx).size)
    }

    @Test fun 바뀔_때마다_백업() {
        Store.upsertTrip(ctx, Store.Trip(id = 7, time = now, origin = "a", dest = "b", program = "로지", fare = 20000, confirmed = true))
        Store.upsertTrip(ctx, Store.Trip(id = 8, time = now, origin = "c", dest = "d", program = "로지", fare = 25000, confirmed = true))
        assertTrue(Store.backups(ctx).isNotEmpty())
    }

    @Test fun 탁송_대리_구분() {
        val takson = CallInfo("로지", "탁송)매송고색로882", "강원 춘천시 석사동)춘천시 스무숲3길 6", listOf("a"), listOf("b"), "55,000",
            accepted = true, capturedAt = now, pkg = "com.logi")
        assertTrue(takson.isTakson)
        assertTrue(!call("가락동 다농마트", "매영로 366", "32,000", accepted = true).isTakson)
        Store.setCurrent(ctx, takson)
        assertEquals("탁송", Store.trips(ctx).single().kind)
        Store.setCurrent(ctx, call("가락동 다농마트", "매영로 366", "32,000", accepted = true).copy(capturedAt = now + 1))
        assertEquals(setOf("탁송", "대리"), Store.trips(ctx).map { it.kind }.toSet())
    }

    @Test fun 예전_기록은_대리() {
        Store.upsertTrip(ctx, Store.Trip(id = 9, time = now, origin = "a", dest = "b", program = "로지", fare = 20000))
        assertTrue(!Store.trips(ctx).single().isTakson)
    }
}
