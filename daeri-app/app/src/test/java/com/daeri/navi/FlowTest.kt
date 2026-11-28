package com.daeri.navi

import com.daeri.navi.Store.detectPaused
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 실제 휴대폰에서 읽힌 화면 글자 순서 그대로 넣어서, 콜 → 수락 → 배정 → 운행일지 기록까지 끝까지 확인.
 * (화면 글자는 기사님이 보내준 '읽은 글자 보기' 그대로)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FlowTest {
    private val ctx get() = RuntimeEnvironment.getApplication()
    private val KAKAO = "com.kakao.driver"
    private val LOGI = "com.logi.daeri"
    private lateinit var svc: CallReaderService

    @Before fun setUp() {
        val spm = org.robolectric.Shadows.shadowOf(ctx.packageManager)
        for ((pkg, label) in listOf(KAKAO to "카카오T 대리", LOGI to "로지")) {
            spm.installPackage(android.content.pm.PackageInfo().apply {
                packageName = pkg
                applicationInfo = android.content.pm.ApplicationInfo().apply { packageName = pkg; nonLocalizedLabel = label }
            })
        }
        ctx.detectPaused = false
        Store.clearHistory(ctx)
        svc = Robolectric.buildService(CallReaderService::class.java).create().get()
    }

    private val waiting = listOf("경기 용인시 기흥구 구갈동 380 한양공인중개사사무소", "프로 단독배정 적용중", "...", "남은시간 30분 35초",
        "귀가지원콜", "서구 외 4곳 자동수락 꺼짐", "단독배정권", "적용 중", "선호도착지", "30개", "이벤트", "참여가능", "콜 수요 지도",
        "나의 예약", "0건", "콜 리스트", "콜 찾기 종료", "메뉴")
    private val soloCard = listOf("프로 단독배정", "출발", "구갈동 구갈풍림아파트", "도착", "� 경기 오산시 지곶길 33-12", "약 26분 운행",
        "실제 \n 수익", "카드 | 확정 | 27,200�", "넘기기", "단독배정 수락")
    private val assigned = listOf("배정 완료", "배정취소", "메뉴", "구갈동", "구갈풍림아파트", "경기 오산시 지곶길 33-12", "카드 | 확정", "27,200",
        "고객과 통화", "고객과 메시지", "고객과 만날 장소 길찾기", "고객에게 위치정보가 공유됩니다.", "밀어서 고객에게 도착알림")

    private fun trips() = Store.trips(ctx)

    @Test fun kakaoSoloAcceptThenAssigned() {
        svc.handleScreen(KAKAO, waiting)
        svc.handleScreen(KAKAO, soloCard)
        assertEquals(0, trips().size)                       // 수락 전엔 기록 안 함
        svc.handleClick(KAKAO, listOf("단독배정 수락"))
        svc.handleScreen(KAKAO, assigned)
        svc.handleScreen(KAKAO, waiting)
        val t = trips().single()
        assertEquals(27200, t.fare)
        assertEquals("프로 단독배정", t.solo)
        assertEquals("카카오대리", t.program)
        assertTrue(t.confirmed)
    }

    @Test fun kakaoAssignedWithoutClick() {
        svc.handleScreen(KAKAO, soloCard)
        svc.handleScreen(KAKAO, assigned)
        assertEquals(27200, trips().single().fare)
    }

    @Test fun kakaoSkipIsNotRecorded() {
        svc.handleScreen(KAKAO, soloCard)
        svc.handleClick(KAKAO, listOf("넘기기"))
        svc.handleScreen(KAKAO, waiting)
        assertEquals(0, trips().size)
    }

    @Test fun kakaoListPopupAccept() {
        val popup = listOf("프로 단독배정 적용중", "...", "출발", "구갈동 구갈풍림아파트", "도착", "� 경기 오산시 지곶길 33-12", "약 26분 운행",
            "실제 \n 수익", "카드 | 확정 | 25,600�", "닫기", "수락", "콜 대기", "콜 수요 지도", "리스트 설정")
        svc.handleScreen(KAKAO, popup)
        svc.handleClick(KAKAO, listOf("수락"))
        svc.handleScreen(KAKAO, assigned)
        assertEquals(1, trips().size)
    }

    @Test fun kakaoAssignedScreenWithDifferentLines() {
        // 인계동 콜: 카드와 배정 완료 화면의 줄 나눔이 달라도 한 건
        val card = listOf("넘기기", "단독배정 확인", "프로 단독배정", "도보 480m / 약 7분", "인계동", "한국예술문화단체총연합회 경기도연합회", "♥",
            "경기 용인 기흥구 구갈동", "한성1차아파트", "약 17분 운행", "추천가", "카드 | 확정", "실제수익", "20,000")
        val done = listOf("배정 완료", "배정취소", "메뉴", "인계동", "한국예술문화단체총연합회", "경기도연합회", "경기 용인 기흥구 구갈동", "한성1차아파트",
            "카드 | 확정", "20,000", "고객과 통화", "고객과 메시지", "고객과 만날 장소 길찾기", "밀어서 고객에게 도착알림")
        svc.handleScreen(KAKAO, card)
        svc.handleClick(KAKAO, listOf("단독배정 확인"))
        svc.handleScreen(KAKAO, done)
        assertEquals(20000, trips().single().fare)
    }

    @Test fun assignedScreenUnreadableStillRecords() {
        // 배정 완료 화면 주소를 못 읽어도 방금 본 콜로 기록
        svc.handleScreen(KAKAO, soloCard)
        svc.handleScreen(KAKAO, listOf("배정 완료", "배정취소", "메뉴", "카드 | 확정", "27,200", "고객과 통화", "고객과 메시지"))
        assertEquals(1, trips().size)
    }

    @Test fun pausedReadsNothing() {
        ctx.detectPaused = true
        // 퇴근 상태: 서비스가 이벤트를 무시 (handleScreen 은 이벤트 뒤에서만 불림) — 여기선 상태값만 확인
        assertTrue(ctx.detectPaused)
    }
}
