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
        assertTrue("자동취소 화면은 자동배차", c.autoDispatch)
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

    @Test fun IDriver_자동배차_팝업() {
        val lines = listOf("5초 자동배차 중....", "[즉시배차]시 배차 확정 됩니다.",
            "출발", "자배)(801M)SK)후불)탁)30일/당진.밤절로 2-8",
            "도착", "평택.원원정길 15-17",
            "요금", "40,000", "기타", "원천징수 3.3% 차감",
            "현재위치에서 출발지까지의 거리 : 800.0M", "※ 거부해도 패널티가 발생하지 않음.",
            "즉시배차", "바로 배차됩니다", "거부")
        assertTrue(CallParser.looksLikeCall(lines.joinToString("\n")))
        val c = CallParser.parse(lines, "I-Driver", allowFreeText = false)!!
        assertEquals("당진시 밤절로 2-8", c.originQueries.first())
        assertEquals("평택시 원원정길 15-17", c.destQueries.first())
        assertEquals("40,000", c.fare)
        assertTrue("제안 화면은 운행 확정 아님", !c.accepted)
        assertTrue("자동배차 중 화면", c.autoDispatch)
    }

    @Test fun IDriver_목록화면은_콜아님() {
        val c = CallParser.parse(listOf("조회", "배차", "거리", "출발지", "도착지", "요금",
            "7.9\n당진푸르지오1차\$;;\n25", "태)완후)송산바른식탁", "1.4\n1)안성시새터1길63/톨포/\n65"), "I-Driver", allowFreeText = false)
        assertTrue(c == null)
    }

    @Test fun 결제정보는_주소가_아님() {
        val c = CallParser.parse(listOf("콜 수락", "카드 | 확정 | 20,000P", "콜받도착", "실제수익", "닫기", "수락"), "카카오T 대리", allowFreeText = false)
        assertTrue(c == null)
    }

    @Test fun 카카오_콜카드() {
        val lines = listOf("콜 대기", "콜 수요 지도", "리스트 설정",
            "도착", "약 40분 운행", "출발", "도보 10.2km(약203분 )",
            "출발", "지산동 클린히트복싱짐 지산점",
            "도착", "♥", "경기 화성시 동탄구\n동탄순환대로3길 27-22", "약 40분 운행",
            "실제\n수익", "카드 | 확정 | 22,400", "닫기", "수락")
        assertTrue(CallParser.looksLikeCall(lines.joinToString("\n")))
        val c = CallParser.parse(lines, "카카오T 대리", allowFreeText = false)!!
        assertEquals("지산동 클린히트복싱짐 지산점", c.originRaw)
        assertEquals("경기 화성시 동탄구 동탄순환대로3길 27-22", c.destQueries.first())
        assertEquals(22400, CallParser.won(c.fare))
        assertTrue(!c.accepted)
    }

    @Test fun 카카오_콜카드_하트가_한칸() {
        val c = CallParser.parse(listOf("출발", "지산동 클린히트복싱짐 지산점", "도착", "♥ 경기 화성시 동탄구 동탄순환대로3길 27-22\n약 40분 운행",
            "실제수익", "카드 | 확정 | 22,400", "닫기", "수락"), "카카오T 대리", allowFreeText = false)!!
        assertEquals("경기 화성시 동탄구 동탄순환대로3길 27-22", c.destQueries.first())
    }

    @Test fun 아이콘_글꼴문자_제거() {
        val c = CallParser.parse(listOf("출발", "화성시 효행구 정남면보통리 수원과학대학교", "도착", "\uE001 화성시 동탄구 산척동 중흥에스클래스에듀하이아파트",
            "실제수익", "카드 | 확정 | 22,400", "닫기", "수락"), "카카오T 대리", allowFreeText = false)!!
        assertTrue(c.destQueries.first(), c.destQueries.first().startsWith("화성시 동탄구 산척동"))
        assertTrue(c.destRaw.none { it == '\uE001' })
    }

    @Test fun 카카오_콜카드_넘기기_여러줄() {
        val c = CallParser.parse(listOf("출발", "도보 714m(약12분", "도착", "약 23분 운행",
            "출발", "권선동 한라비발디파크오피스텔",
            "도착", "경기 화성 효행구 봉담읍", "화성봉담2그랑드비체아파트", "약 23분 운행",
            "실제\n수익", "카드 | 확정 | 23,200", "넘기기", "수락"), "카카오T 대리", allowFreeText = false)!!
        assertEquals("권선동 한라비발디파크오피스텔", c.originRaw)
        assertTrue(c.destRaw, c.destRaw.contains("화성봉담2그랑드비체아파트"))
        assertEquals(23200, CallParser.won(c.fare))
    }

    @Test fun 카카오_콜카드_이름값_한덩어리() {
        val c = CallParser.parse(listOf("출발, 권선동 한라비발디파크오피스텔",
            "도착, 경기 화성 효행구 봉담읍, 화성봉담2그랑드비체아파트, 약 23분 운행",
            "실제 수익, 카드 | 확정 | 23,200", "넘기기", "수락"), "카카오T 대리", allowFreeText = false)!!
        assertEquals("권선동 한라비발디파크오피스텔", c.originRaw)
        assertTrue(c.destQueries.first(), c.destQueries.first().contains("봉담"))
        assertEquals(23200, CallParser.won(c.fare))
    }

    @Test fun 카카오_메뉴글자는_콜아님() {
        val c = CallParser.parse(listOf("콜 대기", "콜 수요 지도", "리스트 설정", "운행중", "콜 대기", "콜 수요 지도", "리스트 설정"), "카카오T 대리", allowFreeText = false)
        assertTrue(c == null)
    }

    @Test fun 카카오_주소확인필요() {
        val c = CallParser.parse(listOf("출발", "수원시영통구 영통동 영통동 975-7",
            "도착", "수원시권선구 오목천동 주소확인필요", "제휴 | 약 24분 운행",
            "실제\n수익", "카드 | 확정 | 20,000", "콜받도착", "넘기기", "수락"), "카카오T 대리", allowFreeText = false)!!
        assertEquals("수원시 권선구 오목천동", c.destQueries.first())
        assertTrue(c.destQueries.none { "주소확인" in it || "제휴" in it })
        assertEquals("수원시 영통구 영통동 975-7", c.originQueries.first())
    }

    @Test fun 카카오_단독배정_카드() {
        val lines = listOf("넘기기", "단독배정 확인", "프로 단독배정", "영통구 신동", "아이오라여성의원",
            "♥", "경기 용인시 기흥구 서천동로91번길 8-11", "약 11분 운행", "콜받도착", "카드 | 확정", "실제수익", "16,800")
        assertTrue(CallParser.looksLikeCall(lines.joinToString("\n")))
        val c = CallParser.parse(lines, "카카오T 대리", allowFreeText = false)!!
        assertEquals("영통구 신동 아이오라여성의원", c.originRaw)
        assertEquals("경기 용인시 기흥구 서천동로91번길 8-11", c.destQueries.first())
        assertEquals(16800, CallParser.won(c.fare))
        assertTrue(!c.accepted)
    }

    @Test fun 카카오_단독배정_추천가() {
        val c = CallParser.parse(listOf("넘기기", "단독배정 확인", "프로 단독배정", "도보 1.9km / 약 31분", "서천동", "남촌홍어",
            "♥", "경기 수원 팔달구 인계동", "수원센트럴아이파크자이아파트 GATE1", "추천가", "콜받도착", "카드 | 확정", "실제수익", "22,400"),
            "카카오T 대리", allowFreeText = false)!!
        assertEquals("서천동 남촌홍어", c.originRaw)
        assertTrue(c.destRaw, c.destRaw.startsWith("경기 수원 팔달구 인계동"))
        assertEquals(22400, CallParser.won(c.fare))
        assertTrue(c.recommended)
    }

    @Test fun 카카오_제휴() {
        val c = CallParser.parse(listOf("도착", "약 42분 운행", "출발", "수원시영통구 신동 신동 338",
            "도착", "용인시처인구 원삼면사암리 SK아카데미", "제휴 | 약 42분 운행", "실제\n수익", "현금 | 확정 | 36,000", "넘기기", "단독배정 수락"),
            "카카오T 대리", allowFreeText = false)!!
        assertTrue(c.partner)
        assertTrue(!c.recommended)
        assertEquals(36000, CallParser.won(c.fare))
        assertTrue(c.destQueries.first(), c.destQueries.first().contains("원삼면"))
    }

    @Test fun 카카오_배정완료_경유() {
        val c = CallParser.parse(listOf("배정 완료", "배정취소", "메뉴", "고덕동", "에이스퍼스트고덕", "서정동, 가수동 경유",
            "경기 오산시 누읍동", "경기 오산시 누읍로 20", "카드 | 확정", "36,800", "고객과 통화"), "카카오T 대리", allowFreeText = false)!!
        assertEquals("고덕동 에이스퍼스트고덕", c.originRaw)
        assertEquals("서정동, 가수동", c.via)
        assertEquals("경기 오산시 누읍로 20", c.destQueries.first())
    }

    @Test fun 카카오_경유지_상세주소() {
        val v = CallParser.parseViaDetail(listOf("운행 시작 전", "경유지 상세주소", "중앙동", "(서정동) 서정리역", "초평동", "오산시 가수행복로 19", "닫기"))
        assertEquals("중앙동 (서정동) 서정리역 → 초평동 오산시 가수행복로 19", v)
        assertTrue(CallParser.parseViaDetail(listOf("배정 완료", "고덕동")) == null)
    }

    @Test fun 카카오_카드_시간_읽기() {
        val c = CallParser.parse(listOf("넘기기", "확인", "제휴", "도보 1.0km / 약 16분", "수원시권선구 권선동 찬스돔나이트",
            "♥", "용인시기흥구 마북동", "e편한세상구성역플랫폼시티아파트", "약 18분 운행", "현금 | 확정", "실제수익", "20,000"),
            "카카오T 대리", allowFreeText = false)!!
        assertEquals(16, c.walkMin)
        assertEquals(1.0, c.walkKm, 0.01)
        assertEquals(18, c.driveMin)
        assertTrue(c.partner)
        assertEquals("수원시 권선구 권선동 찬스돔나이트", c.originQueries.first())
    }

    @Test fun 카카오_카드_광주_능평동() {
        val c = CallParser.parse(listOf("도착", "약 32분 운행", "출발", "도보 1.1km(약18분 )",
            "출발", "매탄동 매탄위브하늘채아파트 102동", "도착", "경기 광주시 능평동", "광주오포금강KCC아파트", "약 32분 운행",
            "실제\n수익", "카드 | 확정 | 28,000", "넘기기", "수락"), "카카오T 대리", allowFreeText = false)!!
        assertEquals(18, c.walkMin)
        assertEquals(32, c.driveMin)
        assertEquals(28000, CallParser.won(c.fare))
        assertTrue(c.destRaw, c.destRaw.contains("광주오포금강KCC아파트"))
    }

    @Test fun 카카오_콜목록은_수집안함() {
        val c = CallParser.parse(listOf("콜 대기", "콜 수요 지도", "리스트 설정", "프로 단독배정 적용중", "내위치 : 경기 수원시 팔달구 인계동 1122-14",
            "프로 단독배정 35분 29초", "출발지", "도착지", "실제수익",
            "0.4km", "인계동", "화성시동탄구 장지동 동…", "콜받도착 | 29분", "24,000", "현금 | 확정",
            "0.7km", "인계동", "경기 화성 동탄구 산척동", "콜받도착 | 29분", "25,600", "카드 | 확정",
            "정렬옵션", "새로고침"), "카카오T 대리", allowFreeText = false)
        assertTrue(c == null)
    }

    @Test fun 카카오_배정완료_요금() {
        val c = CallParser.parse(listOf("배정 완료", "배정취소", "메뉴", "가락동", "다농마트", "경기 수원 영통구 영통동",
            "경기 수원시 영통구 매영로 366", "카드 | 확정", "32,000", "고객과 통화", "고객과 메시지", "고객과 만날 장소 길찾기"),
            "카카오T 대리", allowFreeText = false)!!
        assertTrue(c.accepted)
        assertEquals(32000, CallParser.won(c.fare))
        assertEquals("가락동 다농마트", c.originRaw)
        assertEquals("경기 수원시 영통구 매영로 366", c.destQueries.first())
    }

    @Test fun 콜화면_판별() {
        assertTrue(CallParser.looksLikeCall("출발지\n...\n도착지"))
        assertTrue(CallParser.looksLikeCall("배정 완료"))
        assertTrue(!CallParser.looksLikeCall("카카오톡 채팅방"))
    }
}
