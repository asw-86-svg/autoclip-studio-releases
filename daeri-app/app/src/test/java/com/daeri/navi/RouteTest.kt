package com.daeri.navi

import org.junit.Assert.assertTrue
import org.junit.Test

class RouteTest {
    @Test fun 대략_시간_계산() {
        // 당진 → 화성 팔탄 (직선 약 40km): 실제 카카오맵 55분 정도
        val dangjin = Geo.Pt(36.8936, 126.6282, "", "")
        val paltan = Geo.Pt(37.1590, 126.8480, "", "")
        val km = Route.straightKm(dangjin, paltan)
        assertTrue("직선거리 $km", km in 30.0..40.0)
        val min = Route.estimate(km).sec / 60
        assertTrue("예상 $min 분", min in 40..75)
        // 가까운 거리(직선 1km)는 걸어서 15~20분
        assertTrue(Route.walkSec(1.0) / 60 in 15..20)
    }

    @Test fun 출발지까지_도보20분_넘으면_대중교통() {
        val near = Route.toOrigin(1.0)      // 걸어서 약 17분
        assertTrue(near.walk)
        val far = Route.toOrigin(5.0)       // 걸어서 1시간 넘음 → 대중교통
        assertTrue(!far.walk)
        assertTrue("대중교통 ${far.sec / 60}분", far.sec / 60 in 25..45)
    }
}
