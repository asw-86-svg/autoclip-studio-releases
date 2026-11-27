package com.daeri.navi

/**
 * 대리/탁송 콜 화면에서 읽은 글자들을 분석해 출발지·도착지를 뽑아낸다.
 * 안드로이드 의존성이 없는 순수 Kotlin 이라 JVM 단위테스트로 검증할 수 있다.
 */
data class CallInfo(
    val source: String,
    val originRaw: String,
    val destRaw: String,
    val originQueries: List<String>,
    val destQueries: List<String>,
    val fare: String = "",
    val phones: List<Pair<String, String>> = emptyList(),
    val notes: String = "",
    val capturedAt: Long = System.currentTimeMillis(),
    /** 수락(배정)된 콜 화면에서 읽었는지. 수락 전 팝업이면 false */
    val accepted: Boolean = false,
    /** 입금액(수수료) */
    val deposit: String = "",
    /** 읽어온 앱의 패키지 이름 (아이콘 표시용) */
    val pkg: String = "",
) {
    val key: String get() = "$originRaw|$destRaw"
    val originDisplay: String get() = originQueries.firstOrNull() ?: originRaw
    val destDisplay: String get() = destQueries.firstOrNull() ?: destRaw
}

object CallParser {

    // 상세배차정보 화면(콜마너/로지 계열)의 항목 이름. 공백은 제거하고 비교한다.
    private val LABELS = mapOf(
        "출발지" to "origin", "도착지" to "dest", "요금" to "fare", "입금액" to "deposit",
        "고객" to "customer", "메모" to "memo", "적요" to "summary", "전화" to "phone",
        "전화2" to "phone2", "고객ID" to "cid", "오더번호" to "order", "차량번호" to "car",
        "발주사이용개시번호" to "x", "이용개시번호" to "x", "발주사" to "x",
        // I-Driver 콜 팝업: 출발 / 도착 / 요금 / 기타
        "출발" to "origin", "도착" to "dest", "기타" to "memo",
    )

    private val PROVINCES = setOf(
        "서울", "부산", "대구", "인천", "광주", "대전", "울산", "세종", "경기", "강원",
        "충북", "충남", "전북", "전남", "경북", "경남", "제주",
    )

    private val PHONE = Regex("""0\d{1,3}[- ]?\d{3,4}[- ]?\d{4}""")
    private val ROAD = Regex("""([가-힣][가-힣A-Za-z0-9]*(?:로|길))\s?(\d+(?:-\d+)?)""")
    private val JIBUN = Regex("""([가-힣][가-힣0-9]*(?:동|리|가))\s?(\d+(?:-\d+)?)""")
    private val PRICE = Regex("""^\d{1,3}(,\d{3})+\s*원?$""")

    private fun norm(s: String) = s.replace(Regex("""\s+"""), "")

    /**
     * 앱마다 한글을 다르게 저장한다. 콜마너는 자모가 분리된 형태(NFD)라 화면엔 같아 보여도
     * 글자 비교가 안 된다 → 완성형으로 합치고(NFKC: 전각 기호도 일반 기호로),
     * 눈에 안 보이는 문자(폭 없는 공백 등)는 지우고, 특수 공백은 일반 공백으로.
     */
    fun clean(s: String): String =
        java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKC)
            .replace(Regex("""\p{Cf}"""), "")
            .replace(Regex("""[\u00A0\u2000-\u200A\u202F\u3000]"""), " ")

    /** 콜 화면처럼 보이는지 빠르게 판단 (접근성 이벤트마다 호출됨). */
    /** "50000원" → 50000, "23,200" → 23200, "복합75K" → 75000 */
    fun won(s: String): Int {
        val t = clean(s)
        Regex("""(\d+(?:\.\d+)?)\s*[Kk]""").find(t)?.let { return (it.groupValues[1].toDouble() * 1000).toInt() }
        val m = Regex("""\d[\d,]*""").find(t) ?: return 0
        return m.value.replace(",", "").toIntOrNull() ?: 0
    }

    fun looksLikeCall(joined: String): Boolean {
        val n = norm(clean(joined))
        return ("출발지" in n && "도착지" in n) || "배정완료" in n ||
            "출발주소" in n || "도착주소" in n || ("수락" in n && "닫기" in n) ||
            ("출발" in n && "도착" in n) // 대리 앱만 읽으므로 넓게 잡아도 된다
    }

    /** allowFreeText=false: 자동 읽기에서는 '출발: / 도착:' 자유 글 형식은 쓰지 않는다 (채팅 등 오인 방지) */
    fun parse(texts: List<String>, source: String = "", allowFreeText: Boolean = true): CallInfo? {
        val lines = texts.map { clean(it).trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null
        return parseLabeled(lines, source)
            ?: parseKakao(lines, source)
            ?: parseOffer(lines, source)
            ?: if (allowFreeText) parseFreeText(lines.joinToString("\n"), source) else null
    }

    // ---------- 1. 상세배차정보 형식 (항목이름 → 다음 칸이 값) ----------
    private fun parseLabeled(lines: List<String>, source: String): CallInfo? {
        val fields = HashMap<String, String>()
        for (i in lines.indices) {
            val f = LABELS[norm(lines[i])] ?: continue
            val next = lines.getOrNull(i + 1) ?: continue
            if (LABELS.containsKey(norm(next))) continue // 값이 비어 있는 칸
            // 콜 목록 제목줄("거리 요금 시간 출발지 도착지")을 콜로 착각하지 않도록 값 검사
            if ((f == "origin" || f == "dest") && !isAddrLike(next)) continue
            if (f == "fare" && next.none { it.isDigit() }) continue
            fields.putIfAbsent(f, next)
        }
        val summary = listOfNotNull(fields["summary"], fields["memo"]).joinToString("\n")
        val sumOrigin = Regex("""출발주소\s*[:：]\s*([^\n]+)""").find(summary)?.groupValues?.get(1)
        val sumDest = Regex("""도착주소\s*[:：]\s*([^\n]+)""").find(summary)?.groupValues?.get(1)

        val originRaw = fields["origin"] ?: sumOrigin
        val destRaw = fields["dest"] ?: sumDest
        if (originRaw == null && destRaw == null) return null

        val oq = (listOfNotNull(fields["origin"]) + listOfNotNull(sumOrigin))
            .flatMap { candidates(it) }.distinct()
        val dq = (listOfNotNull(fields["dest"]) + listOfNotNull(sumDest))
            .flatMap { candidates(it) }.distinct()

        val notes = buildString {
            fields["memo"]?.let { append("[메모] ").append(it).append("\n") }
            fields["summary"]?.let { append("[적요] ").append(it) }
        }.trim()
        // 요금 칸이 없으면 "금액: 80000원" 같은 줄에서
        val fare = fields["fare"] ?: listOfNotNull(fields["origin"], fields["dest"], summary).firstNotNullOfOrNull {
            Regex("""금액\s*[:：]\s*([\d,]+\s*원?)""").find(it)?.groupValues?.get(1)
        }.orEmpty()
        // 상세배차정보(요금 칸이 있는 화면)만 '배정된 콜'로 본다. "N초 후 자동취소" 같은 확인 화면은 제외
        // 단, "N초 자동배차 중", "자동취소", [거부]/[즉시배차]/[수락] 버튼이 있는 제안·확인 화면은 아직 확정이 아님
        val offerWords = listOf("자동배차", "자동취소", "즉시배차", "거부", "수락")
        val isOffer = lines.any { l -> offerWords.any { it in norm(l) } }
        val accepted = !isOffer && (fields.containsKey("fare") || lines.any { norm(it) == "상세배차정보" })
        val phones = extractPhones(summary) +
            listOfNotNull(fields["phone"]?.let { "전화" to it }, fields["phone2"]?.let { "전화2" to it })
        return CallInfo(
            source.ifEmpty { "배차정보" }, originRaw.orEmpty(), destRaw.orEmpty(),
            oq, dq, fare, phones.distinctBy { it.second }, notes,
            accepted = accepted, deposit = fields["deposit"].orEmpty(),
        )
    }

    // ---------- 2. 카카오T 대리 "배정 완료" 화면 ----------
    private val KAKAO_SKIP = setOf("배정취소", "메뉴", "배정완료", "운행중", "운행 중")

    private fun parseKakao(lines: List<String>, source: String): CallInfo? {
        val start = lines.indexOfFirst { norm(it).startsWith("배정완료") || norm(it) == "운행중" }
        if (start < 0) return null
        val body = ArrayList<String>()
        var fare = ""
        for (l in lines.drop(start + 1)) {
            val n = norm(l)
            if (PRICE.matches(l.trim())) { fare = l.trim(); break }
            if ("카드" in n || "현금" in n || n.startsWith("고객과")) break
            if (n in KAKAO_SKIP.map(::norm)) continue
            body += l.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        }
        if (body.size < 2) return null
        // 도착지는 보통 '경기 …', '서울 …' 처럼 시·도로 시작한다
        var split = (1 until body.size).firstOrNull { body[it].split(" ").first() in PROVINCES }
            ?: ((body.size + 1) / 2)
        if (split <= 0 || split >= body.size) split = body.size / 2
        val origin = body.subList(0, split).joinToString(" ")
        val dest = body.subList(split, body.size).joinToString(" ")
        return CallInfo(
            source.ifEmpty { "카카오T 대리" }, origin, dest,
            candidates(origin), candidates(dest), fare, accepted = true,
        )
    }

    // ---------- 3. 콜 목록에서 콜을 눌렀을 때 뜨는 수락 팝업 (콜마너 등) ----------
    private val UI_WORDS = setOf("닫기", "수락", "거절", "취소", "확인", "예약콜", "일반설정", "거리", "요금", "시간", "출발지", "도착지")
    private val FARE_K = Regex("""^(?:[가-힣]{0,3})\d+(?:\.\d+)?K$""", RegexOption.IGNORE_CASE)

    // 주소가 아닌 줄(결제·정산·메뉴 글자)에 자주 나오는 말
    private val NOT_ADDR = listOf("수익", "확정", "포인트", "정산", "잔액", "충전", "수수료", "콜받", "카드|", "현금|", "결제", "고객과")
    private val MONEY = Regex("""\d{1,3}(,\d{3})+""")

    private fun isAddrLike(l: String): Boolean {
        val t = l.trim()
        if (norm(t) in UI_WORDS) return false
        if (FARE_K.matches(norm(t))) return false
        if ('|' in t || MONEY.containsMatchIn(t)) return false
        if (NOT_ADDR.any { it in norm(t) }) return false
        return t.count { it in '가'..'힣' } >= 3 && t.length >= 4
    }

    private fun parseOffer(lines: List<String>, source: String): CallInfo? {
        val accept = lines.indexOfLast { norm(it) == "수락" }
        if (accept < 0) return null
        val close = lines.subList(0, accept).indexOfLast { norm(it) == "닫기" }
        val end = if (close >= 0 && accept - close <= 3) close else accept
        val addr = ArrayList<String>()
        var fare = ""
        var i = end - 1
        while (i >= 0 && i >= end - 8) {
            val l = lines[i].trim()
            if (addr.size < 2 && isAddrLike(l)) addr.add(0, l)
            else if (addr.size == 2 && fare.isEmpty() && FARE_K.matches(norm(l))) { fare = l; break }
            i--
        }
        if (addr.size < 2) return null
        return CallInfo(
            source.ifEmpty { "콜 수락 화면" }, addr[0], addr[1],
            candidates(addr[0]), candidates(addr[1]), fare,
        )
    }

    // ---------- 4. 문자/카톡에서 붙여넣은 글 ----------
    private fun parseFreeText(text: String, source: String): CallInfo? {
        fun grab(vararg keys: String): String? {
            for (k in keys) {
                val m = Regex("""$k\s*[:：)\]]\s*([^\n]+)""").find(text) ?: continue
                val v = m.groupValues[1].trim()
                if (v.isNotEmpty() && !PHONE.matchesAt(v, 0)) return v
            }
            return null
        }
        var origin = grab("출발주소", "출발지", "출발")
        var dest = grab("도착주소", "도착지", "도착")
        if (origin == null && dest == null) {
            // "A → B" 또는 "A > B" 한 줄 형식
            val arrow = Regex("""(.+?)\s*(?:→|->|=>|▶)\s*(.+)""").find(text) ?: return null
            origin = arrow.groupValues[1]; dest = arrow.groupValues[2]
        }
        return CallInfo(
            source.ifEmpty { "붙여넣기" }, origin.orEmpty(), dest.orEmpty(),
            origin?.let(::candidates).orEmpty(), dest?.let(::candidates).orEmpty(),
            phones = extractPhones(text), notes = text.take(500),
        )
    }

    private fun extractPhones(text: String): List<Pair<String, String>> =
        PHONE.findAll(text).map { m ->
            val before = text.substring(0, m.range.first).takeLast(8)
            val label = Regex("""([가-힣]+)\s*[:：]?\s*$""").find(before)?.groupValues?.get(1) ?: "전화"
            label to m.value
        }.toList()

    // ---------- 주소 정리: 지도 검색에 쓸 후보를 좋은 순서대로 ----------
    fun candidates(raw: String): List<String> {
        val out = LinkedHashSet<String>()
        val s = preclean(raw)
        if (s.isEmpty()) return emptyList()

        val detailIdx = s.indexOf("상세")
        val parts = if (detailIdx >= 0) {
            listOf(s.substring(detailIdx).replace(Regex("""^상세\s*[:：]?\s*"""), ""), s.substring(0, detailIdx))
        } else listOf(s)

        val loose = ArrayList<String>()
        for (part0 in parts) {
            for (part in stripPrefixes(part0).split('>').map { it.trim() }.filter { it.isNotEmpty() }) {
                var region = ""
                var body = part
                // "충남 당진시 대덕동)당진시대덕1로 102-57 …" 형식
                val paren = part.indexOf(')')
                if (paren in 1 until part.length - 1 && '(' !in part.substring(0, paren)) {
                    region = part.substring(0, paren).trim()
                    body = part.substring(paren + 1).trim()
                }
                val clean = dedupeRepeat(cutJunk(body))
                val regionTokens = (region.split(" ") + clean.split(" ")).filter(::isRegionToken).distinct()

                ROAD.find(clean)?.let { m ->
                    val road = m.groupValues[1]; val num = trimZero(m.groupValues[2])
                    val pre = regionBefore(clean, m.range.first).ifEmpty { regionTokens.joinToString(" ") }
                    // "당진 밤절로 2-8" 처럼 '시'를 뺀 도시 이름이 바로 앞에 있으면 "당진시 …" 로도
                    val shortCity = clean.substring(0, m.range.first).trim().split(" ").lastOrNull()
                        ?.takeIf { pre.isEmpty() && it.length in 2..3 && it.all { ch -> ch in '가'..'힣' } && !isRegionToken(it) && !it.endsWith("동") }
                    if (shortCity != null) {
                        out += "${shortCity}시 $road $num"
                        out += "$shortCity $road $num"
                    }
                    out += "$pre $road $num".trim()
                    // '당진시대덕1로' → '대덕1로' 처럼 도로명 앞에 붙은 시·군 이름 제거
                    regionTokens.firstOrNull { road.startsWith(it) && road.length > it.length + 1 }?.let {
                        out += "$pre ${road.removePrefix(it)} $num".trim()
                    }
                }
                Regex("""\(([^)]*)""").find(part)?.let { p ->
                    ROAD.find(p.groupValues[1])?.let { m ->
                        out += "${regionTokens.joinToString(" ")} ${m.groupValues[1]} ${trimZero(m.groupValues[2])}".trim()
                    }
                }
                JIBUN.find(clean)?.let { m ->
                    val pre = regionBefore(clean, m.range.first)
                    out += "$pre ${m.groupValues[1]} ${trimZero(m.groupValues[2])}".trim()
                }
                if (clean.isNotEmpty()) {
                    val province = region.split(" ").first().takeIf { it in PROVINCES }
                    loose += when {
                        region.isNotEmpty() && clean.split(" ").none(::isRegionToken) -> "$region $clean"
                        province != null && clean.split(" ").first() !in PROVINCES -> "$province $clean"
                        else -> clean
                    }
                }
                if (region.isNotEmpty()) loose += region
            }
        }
        out += loose
        return out.map { it.replace(Regex("""\s+"""), " ").trim() }.filter { it.length >= 2 }.distinct()
    }

    private fun preclean(raw: String): String {
        // "금액: 80000원", "회사: …" 같은 줄은 주소가 아니므로 뺀다
        var s = clean(raw).lines()
            .filterNot { Regex("""^\s*(금액|요금|회사|전화|연락처)\s*[:：]""").containsMatchIn(it) }
            .joinToString(" ")
        // "(성동구)+옥수동…" → "성동구)옥수동…"  (뒤에서 '지역)본문' 형식으로 처리됨)
        s = s.replace(Regex("""^\s*\(([^)]+)\)\s*\+?\s*"""), "$1)")
        s = s.replace(Regex("""[\uFE0E\uFE0F]"""), "")   // 이모지 꾸밈 문자
        s = s.replace(Regex("""[\[\]]"""), "/")           // "톨포]인천송도유원지" → 조각으로 나눠 '톨포' 제거
        s = s.replace(Regex("""[@~$+*●★☆◎♨▶◀]"""), " ")
        // "화성장안면.수정로" 처럼 점으로 이어진 부분
        s = s.replace(Regex("""(?<=[가-힣0-9])\.(?=[가-힣])"""), " ")
        s = s.replace(Regex("""\s+"""), " ").trim()
        return Regex("""[가-힣]+""").replace(s) { splitGlued(it.value) }
    }

    /** "화성시만세구" → "화성시 만세구", "화성장안면" → "화성 장안면", "옥수동한남하이츠" → "옥수동 한남하이츠" */
    private fun splitGlued(t: String): String {
        Regex("""^([가-힣]{1,4}[시군])([가-힣]{1,4}[구읍면])$""").find(t)?.let { return "${it.groupValues[1]} ${it.groupValues[2]}" }
        Regex("""^([가-힣]{2})([가-힣]{1,2}[읍면])$""").find(t)?.let { return "${it.groupValues[1]} ${it.groupValues[2]}" }
        Regex("""^([가-힣]{1,3}동)([가-힣A-Za-z][가-힣A-Za-z0-9]+)$""").find(t)?.let {
            if (!it.groupValues[1].endsWith("자동")) return "${it.groupValues[1]} ${it.groupValues[2]}"
        }
        return t
    }

    private val JUNK_SEG = Regex("""후불|선불|톨포|톨비|현금|카드|\d+톤|^급|요금|\d+천|\d+원|기사부담""")

    private fun stripPrefixes(s: String): String {
        // "탁송)13시출)수원도이치오토월드" → "수원도이치오토월드"
        var r = s.trim()
        while (true) {
            val m = Regex("""^[^)\s(]{1,8}\)""").find(r) ?: break
            if (isRegionToken(m.value.dropLast(1))) break // "성동구)…" 의 지역명은 남김
            r = r.substring(m.range.last + 1).trim()
        }
        return r
    }

    private fun cutJunk(s: String): String =
        s.split('/').map { it.trim() }.let { segs ->
            segs.firstOrNull { seg -> seg.isNotEmpty() && seg.any { it in '가'..'힣' } && !JUNK_SEG.containsMatchIn(seg) }
                ?: segs.first()
        }.split('(').first()
            .replace(Regex("""\s*\d+\s*동\s*$"""), "") // 아파트 동 번호
            .trim()

    private fun dedupeRepeat(s: String): String {
        // "경기 수원시 권선구 고색동 경기 수원시 권선구 고색동 326-5" → 뒤쪽만 남김
        val t = s.split(" ").filter { it.isNotEmpty() }
        for (i in t.indices) for (j in i + 1 until t.size) {
            if (t[i] != t[j]) continue
            val len = j - i
            if (j + len <= t.size && t.subList(i, j) == t.subList(j, j + len)) {
                return (t.subList(0, i) + t.subList(j, t.size)).joinToString(" ")
            }
        }
        return t.joinToString(" ")
    }

    private fun regionBefore(s: String, idx: Int): String {
        val toks = s.substring(0, idx).trim().split(" ").filter { it.isNotEmpty() }
        val kept = ArrayList<String>()
        for (t in toks.asReversed()) {
            val ok = isRegionToken(t) || t.endsWith("동") || t.endsWith("읍") || t.endsWith("면") ||
                // "수원 권선구", "화성 장안면" 처럼 '시'를 뺀 도시 이름
                (t.length in 2..3 && t.all { it in '가'..'힣' } &&
                    kept.firstOrNull()?.let { it.endsWith("구") || it.endsWith("군") || it.endsWith("읍") || it.endsWith("면") } == true)
            if (!ok) break
            kept.add(0, t)
        }
        return kept.filterNot { it.endsWith("동") || it.endsWith("가") || it.endsWith("리") }.joinToString(" ")
    }

    private fun isRegionToken(t: String): Boolean =
        t in PROVINCES || t.endsWith("특별시") || t.endsWith("광역시") ||
            (t.length in 2..5 && (t.endsWith("시") || t.endsWith("군") || t.endsWith("구") ||
                t.endsWith("읍") || t.endsWith("면")) && t.none { it.isDigit() })

    private fun trimZero(n: String) = n.removeSuffix("-0")
}
