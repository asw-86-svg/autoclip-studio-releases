package com.daeri.navi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 실제 배차 화면 형식을 본뜬 예시 (전화번호·동호수는 가짜로 바꿈). */
class CallParserTest {

    private fun screen(vararg s: String) = s.toList()

    @Test fun 상세배차정보_탁송_상세주소() {
        val c = CallParser.parse(screen(
            "상세배차정보", "요 금", "50000원", "서명", "입금액", "10000원", "고 객", "일반 테스트모터스",
            "메 모", "적 요", "[테스트탁송 1600-0000 12:03]\n고객과의 거리: 500미터",
            "전 화", "0508-0000-0001", "전화", "전화2", "전화",
            "출발지", "탁송)13시출)수원도이치오토월드\n상세:경기 수원시 권선구 고색동 수원도이치오토월드",
            "도착지", "충남 당진시 대덕동)당진시대덕1로 100-50 테스트아파트101동/톨포",
            "고객ID", "1234", "오더번호", "1111111111", "차량번호",
            "경로\n안내", "출발지\n지도",
        ))!!
        assertEquals("50000원", c.fare)
        assertEquals("경기 수원시 권선구 고색동 수원도이치오토월드", c.originQueries.first())
        assertEquals("충남 당진시 당진시대덕1로 100-50", c.destQueries[0])
        assertTrue(c.destQueries.contains("충남 당진시 대덕1로 100-50"))
    }

    @Test fun 적요에_출발주소_도착주소() {
        val c = CallParser.parse(screen(
            "요 금", "50000원", "서명", "고 객", "업소 일반", "메 모",
            "적 요", "[(B)테스트서비스 070-0000-0000\n13:36] {후불50,000/\n종료10분후자동충전}출발주소 : skv1 / B1층 A04기둥\n" +
                "출발번호 : 010-1111-2222\n\n도착주소 : 충남 예산군 응봉면 참샘2길 28-11\n도착번호 : 010-3333-4444\n차량번호 : 00가0000",
            "전 화", "0508-0000-0002", "전화",
        ))!!
        assertEquals("충남 예산군 응봉면 참샘2길 28-11", c.destQueries.first())
        assertTrue(c.phones.any { it.first == "출발번호" && it.second == "010-1111-2222" })
        assertTrue(c.phones.any { it.first == "도착번호" && it.second == "010-3333-4444" })
    }

    @Test fun 출발지_지번_중복제거() {
        val c = CallParser.parse(screen(
            "출발지", "탁송)고색동326-5>수원도이치오토>\n상세:경기 수원시 권선구 고색동 경기 수원시 권선구 고색동 326-5",
            "도착지", "충남 아산시 둔포면)아산시 둔포면",
        ))!!
        assertEquals("경기 수원시 권선구 고색동 326-5", c.originQueries.first())
        assertTrue(c.destQueries.first().contains("아산시 둔포면"))
    }

    @Test fun 도착지_도로명_붙어있음() {
        val c = CallParser.parse(screen(
            "출발지", "탁송)매송고색로843\n상세:경기 수원시 권선구 평동 권선구 평동 54-0(매송고색로 843)",
            "도착지", "대전 대덕구 신탄진동)대전 대덕구 대청로43 테스트스카이1단지",
        ))!!
        assertEquals("대전 대덕구 대청로 43", c.destQueries.first())
        assertEquals("경기 수원시 권선구 매송고색로 843", c.originQueries.first())
        assertTrue(c.originQueries.any { it.contains("평동 54") })
    }

    @Test fun 카카오T대리_배정완료() {
        val c = CallParser.parse(screen(
            "배정 완료", "배정취소", "메뉴", "화서동", "무지개맥주 수원화서역점",
            "경기 용인 기흥구 구갈동", "한성1차아파트 102동", "카드 | 확정", "23,200",
            "고객과 통화", "고객과 메시지",
        ))!!
        assertEquals("화서동 무지개맥주 수원화서역점", c.originRaw)
        assertEquals("경기 용인 기흥구 구갈동 한성1차아파트 102동", c.destRaw)
        assertEquals("경기 용인 기흥구 구갈동 한성1차아파트", c.destQueries.first())
    }

    @Test fun 붙여넣기_문자() {
        val c = CallParser.parse(listOf("출발: 서울 강남구 테헤란로 152\n도착: 경기 성남시 분당구 판교역로 166\n요금 30000"))
        assertNotNull(c)
        assertEquals("서울 강남구 테헤란로 152", c!!.originQueries.first())
        assertEquals("경기 성남시 분당구 판교역로 166", c.destQueries.first())
    }

    @Test fun 후보출력() {
        for (r in listOf(
            "탁송)13시출)수원도이치오토월드\n상세:경기 수원시 권선구 고색동 수원도이치오토월드",
            "충남 당진시 대덕동)당진시대덕1로 100-50 테스트아파트101동/톨포",
            "탁송)매송고색로843\n상세:경기 수원시 권선구 평동 권선구 평동 54-0(매송고색로 843)",
            "대전 대덕구 신탄진동)대전 대덕구 대청로43 테스트스카이1단지",
            "충남 아산시 둔포면)아산시 둔포면",
            "탁송)권선로 308-5\n상세:경기 수원시 권선구 고색동 1191-0",
            "수원 권선구 권선로 308-5 도이치 3층 3002 바닥8라인",
            "화서동 무지개맥주 수원화서역점",
        )) println("$r\n   => " + CallParser.candidates(r))
    }

    @Test fun 콜마너_수락팝업() {
        val c = CallParser.parse(screen(
            "위치 : 수청동 당진버스터미널  잔액 : 21,600원", "거리", "30km", "일반 설정",
            "거리", "요금", "시간", "출발지", "도착지",
            "17", "복합75K", "후불)5천후불/\$평택카길애그리퓨리나공장", "1:45", "(성동구)+옥수동한남하이츠@/톨포~~",
            "28", "후불25K", "1톤탑/화성장안면.수정로215-10", "25분", "(화성시만세구)현대자동차 팔탄서비스/톨포",
            "17", "1:45", "복합75K", "후불)5천후불/\$평택카길애그리퓨리나공장", "(성동구)+옥수동한남하이츠@/톨포~~",
            "닫기", "수락", "예약콜",
        ))!!
        assertEquals("후불)5천후불/\$평택카길애그리퓨리나공장", c.originRaw)
        assertEquals("평택카길애그리퓨리나공장", c.originQueries.first())
        assertEquals("성동구 옥수동 한남하이츠", c.destQueries.first())
        assertEquals("복합75K", c.fare)
    }

    @Test fun 콜목록만_있으면_무시() {
        val c = CallParser.parse(screen(
            "거리", "요금", "시간", "출발지", "도착지",
            "17", "복합75K", "후불)5천후불/평택공장", "1:45", "(성동구)+옥수동한남하이츠@/톨포~~",
        ))
        assertTrue(c == null || (c.originRaw.isBlank() && c.destRaw.isBlank()))
    }

    @Test fun 목록주소_정리() {
        assertEquals("화성 장안면 수정로 215-10", CallParser.candidates("1톤탑/화성장안면.수정로215-10").first())
        assertEquals("화성시 만세구 현대자동차 팔탄서비스", CallParser.candidates("(화성시만세구)현대자동차 팔탄서비스/톨포").first())
    }

    @Test fun 콜마너_자모분리_글자() {
        // 콜마너는 한글을 자모 분리형(NFD)으로 준다 → 화면엔 같아 보여도 다른 글자
        fun nfd(x: String) = java.text.Normalizer.normalize(x, java.text.Normalizer.Form.NFD)
        val c = CallParser.parse(listOf(
            "17", "1:45", nfd("복합75K"), nfd("*후불)5천후불/\$평택카길애그리퓨리나공장"),
            nfd("(성동구)+옥수동한남하이츠@/톨포~~"), nfd("닫기"), nfd("수락"),
        ))!!
        assertEquals("평택카길애그리퓨리나공장", c.originQueries.first())
        assertEquals("성동구 옥수동 한남하이츠", c.destQueries.first())
        assertEquals("복합75K", c.fare)
        assertTrue(CallParser.looksLikeCall(nfd("출발지\n도착지")))
    }

    @Test fun 금액_변환() {
        assertEquals(50000, CallParser.won("50000원"))
        assertEquals(23200, CallParser.won("23,200"))
        assertEquals(75000, CallParser.won("복합75K"))
        assertEquals(25000, CallParser.won("후불25K"))
        assertEquals(0, CallParser.won(""))
    }

    @Test fun 상세배차정보는_수락된_콜() {
        val c = CallParser.parse(listOf("요 금", "50000원", "서명", "입금액", "10000원", "출발지", "탁송)경기 수원시 권선구 권선로 308-5", "도착지", "대전 유성구 용산1로 99"))!!
        assertTrue(c.accepted)
        assertEquals("10000원", c.deposit)
    }

    @Test fun 자동취소_확인화면() {
        val c = CallParser.parse(listOf(
            "9", "초 후 자동취소",
            "출발지", "탁송)평택카길애그리푸리나공장\n\n상세:경기 평택시 포승읍 신영리 954-0\n카길애그리퓨리나 평택공\n금액: 80000원",
            "도착지", "\n\n서울 성동구\n옥수동)옥수동한남하이츠아파트9동/톨포",
            "추가정보", "출발:탁송)평택카길애그리푸리나공장\n금액: 80000원\n도착:서울 성동구\n회사: <2>서울인천경기",
            "상세정보보기", "배차취소", "퀵서치\n중지",
        ))!!
        assertEquals("경기 평택시 포승읍 신영리 954", c.originQueries.first())
        assertTrue(c.originQueries.none { "금액" in it })
        assertTrue(c.destQueries.first(), c.destQueries.first().startsWith("서울 성동구 옥수동"))
        assertEquals("80000원", c.fare)
        assertTrue(!c.accepted)
    }

    @Test fun 자동읽기는_자유글_형식_안씀() {
        // 채팅 화면 같은 일반 글은 자동 읽기에서 콜로 만들지 않는다
        val chat = listOf("카카오T 대리 출발·도착 읽기 → 운행일지 기록", "출발: 어쩌고", "도착: 저쩌고")
        assertTrue(CallParser.parse(chat, "x", allowFreeText = false) == null)
        assertNotNull(CallParser.parse(chat, "x", allowFreeText = true))
    }

    @Test fun 로지_자동취소_실제글자() {
        val c = CallParser.parse(listOf("9", "초 후 자동취소", "출발지",
            "탁송)ⓓ탁/18:30출/당진운곡로197-8.101동\n상세:충남 당진시 채운동 1125-0 운곡로 197-8\n금액: 60000원",
            "도착지", "인천 연수구 옥련동)톨포]인천송도유원지", "TextView", "TextView", "추가\n정보",
            "상세정보보기", "배차취소", "퀵서치\n중지"), "로지", allowFreeText = false)!!
        assertEquals("충남 당진시 운곡로 197-8", c.originQueries.first())
        assertTrue(c.destQueries.first(), c.destQueries.first().endsWith("인천송도유원지"))
        assertTrue(c.destQueries.none { "톨포" in it })
        assertEquals("60000원", c.fare)
    }

    @Test fun 콜화면_판별() {
        assertTrue(CallParser.looksLikeCall("출발지\n...\n도착지"))
        assertTrue(CallParser.looksLikeCall("배정 완료"))
        assertTrue(!CallParser.looksLikeCall("카카오톡 채팅방"))
    }
}
