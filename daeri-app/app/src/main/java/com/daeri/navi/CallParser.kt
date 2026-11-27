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

    /** 콜 화면처럼 보이는지 빠르게 판단 (접근성 이벤트마다 호출됨). */
    fun looksLikeCall(joined: String): Boolean {
        val n = norm(joined)
        return ("출발지" in n && "도착지" in n) || "배정완료" in n ||
            "출발주소" in n || "도착주소" in n
    }

    fun parse(texts: List<String>, source: String = ""): CallInfo? {
        val lines = texts.map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null
        return parseLabeled(lines, source)
            ?: parseKakao(lines, source)
            ?: parseFreeText(lines.joinToString("\n"), source)
    }

    // ---------- 1. 상세배차정보 형식 (항목이름 → 다음 칸이 값) ----------
    private fun parseLabeled(lines: List<String>, source: String): CallInfo? {
        val fields = HashMap<String, String>()
        for (i in lines.indices) {
            val f = LABELS[norm(lines[i])] ?: continue
            val next = lines.getOrNull(i + 1) ?: continue
            if (LABELS.containsKey(norm(next))) continue // 값이 비어 있는 칸
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
        val fare = fields["fare"].orEmpty()
        val phones = extractPhones(summary) +
            listOfNotNull(fields["phone"]?.let { "전화" to it }, fields["phone2"]?.let { "전화2" to it })
        return CallInfo(
            source.ifEmpty { "배차정보" }, originRaw.orEmpty(), destRaw.orEmpty(),
            oq, dq, fare, phones.distinctBy { it.second }, notes,
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
            candidates(origin), candidates(dest), fare,
        )
    }

    // ---------- 3. 문자/카톡에서 붙여넣은 글 ----------
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
        val s = raw.replace('\n', ' ').replace(Regex("""\s+"""), " ").trim()
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

    private fun stripPrefixes(s: String): String {
        // "탁송)13시출)수원도이치오토월드" → "수원도이치오토월드"
        var r = s.trim()
        while (true) {
            val m = Regex("""^[^)\s(]{1,8}\)""").find(r) ?: break
            r = r.substring(m.range.last + 1).trim()
        }
        return r
    }

    private fun cutJunk(s: String): String =
        s.split('/').first().split('(').first()
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

    private fun regionBefore(s: String, idx: Int): String =
        s.substring(0, idx).trim().split(" ").filter { it.isNotEmpty() }
            .takeLastWhile { isRegionToken(it) || it.endsWith("동") || it.endsWith("읍") || it.endsWith("면") }
            .filterNot { it.endsWith("동") || it.endsWith("가") || it.endsWith("리") }
            .joinToString(" ")

    private fun isRegionToken(t: String): Boolean =
        t in PROVINCES || t.endsWith("특별시") || t.endsWith("광역시") ||
            (t.length in 2..5 && (t.endsWith("시") || t.endsWith("군") || t.endsWith("구") ||
                t.endsWith("읍") || t.endsWith("면")) && t.none { it.isDigit() })

    private fun trimZero(n: String) = n.removeSuffix("-0")
}
