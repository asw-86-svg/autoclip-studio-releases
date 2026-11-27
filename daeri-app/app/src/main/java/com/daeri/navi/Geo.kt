package com.daeri.navi

import android.content.Context
import android.location.Geocoder
import com.daeri.navi.Store.kakaoKey
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** 주소 글자 → 좌표. 카카오 API 키가 있으면 카카오 검색, 없으면 휴대폰 내장 Geocoder. */
object Geo {
    data class Pt(val lat: Double, val lng: Double, val label: String, val query: String)

    private val cache = ConcurrentHashMap<String, Pt>()
    private val misses = ConcurrentHashMap.newKeySet<String>()

    /** 후보들을 순서대로 시도해서 처음 찾은 좌표를 돌려준다. 네트워크를 쓰므로 백그라운드에서 호출. */
    /**
     * @param allowArea true 면 가게 이름을 못 찾을 때 동네(동·읍·면) 위치라도 돌려준다 — 시간 '대략' 계산용.
     *                  길안내에는 false (엉뚱한 동네 한가운데로 안내하지 않게)
     */
    fun resolve(ctx: Context, queries: List<String>, allowArea: Boolean = false): Pt? {
        val key = ctx.kakaoKey.trim()
        // "고덕동"처럼 여러 곳에 있는 이름은 지금 내 위치 근처(약 35km)부터 찾는다
        val near = Loc.lastPt(ctx)
        // 업체/건물 이름이 있는 콜이면 "화성시 만세구" 같은 동네 이름 후보로 대충 찍지 않는다
        val specific = queries.firstOrNull()?.let { !isAreaOnly(it) } == true
        for (q in queries.take(5)) {
            if (specific && isAreaOnly(q)) continue
            val ck = "$key|$q|" + (near?.let { "${(it.lat * 10).toInt()},${(it.lng * 10).toInt()}" } ?: "")
            cache[ck]?.let { return it }
            if (ck in misses) continue
            // 카카오 키가 있으면 카카오 먼저, 그래도 못 찾거나 키 오류면 휴대폰 기본 위치 찾기로
            val pt = (if (key.isNotEmpty()) kakao(key, "address", q) ?: kakao(key, "keyword", q, near) ?: kakao(key, "keyword", q) else null)
                ?: (near?.let { geocoder(ctx, q, it) } ?: geocoder(ctx, q))
            if (pt != null) { cache[ck] = pt; return pt }
            misses += ck
        }
        if (!allowArea) return null
        // 가게 이름은 못 찾았지만 동네 이름은 있으면 그 동네 위치로 (대략)
        val area = queries.firstOrNull()?.split(" ")?.takeWhile { t -> Regex("""(시|군|구|읍|면|동|리|가)$""").containsMatchIn(t) || t in PROVINCE_SHORT }
            ?.joinToString(" ")?.takeIf { it.isNotBlank() } ?: return null
        return (near?.let { geocoder(ctx, area, it) } ?: geocoder(ctx, area))?.copy(label = "$area (대략)")
    }

    /** 마지막 위치 찾기 실패 이유 (문제 해결용) */
    @Volatile var lastError: String = ""

    @Suppress("DEPRECATION")
    private fun geocoder(ctx: Context, q: String, near: Pt? = null): Pt? = runCatching {
        if (!Geocoder.isPresent()) { lastError = "휴대폰 기본 위치 찾기 없음"; return null }
        val g = Geocoder(ctx, Locale.KOREA)
        val a = (if (near != null) g.getFromLocationName(q, 1, near.lat - 0.32, near.lng - 0.4, near.lat + 0.32, near.lng + 0.4)
                 else g.getFromLocationName(q, 1))?.firstOrNull() ?: return null
        // 한국 범위를 벗어난 엉뚱한 결과는 버림
        if (a.latitude !in 33.0..39.0 || a.longitude !in 124.0..132.0) return null
        val line = a.getAddressLine(0) ?: q
        // "경기도 화성시 팔탄면" 처럼 번지 없이 동네 이름에서 끝나면 대략적인 위치일 뿐이다.
        // 검색어가 업체/건물 이름이었다면 이 결과는 버리고 지도앱 검색에 맡긴다.
        if (isAreaOnly(line) && !isAreaOnly(q)) return null
        Pt(a.latitude, a.longitude, line, q)
    }.onFailure { lastError = "기본 위치 찾기 오류: ${it.javaClass.simpleName} ${it.message.orEmpty().take(60)}" }.getOrNull()

    private val AREA_END = Regex("""(시|군|구|읍|면|동|리|가|도|대한민국)$""")

    /** 숫자(번지) 없이 행정구역 이름으로만 이루어졌는지 */
    fun isAreaOnly(text: String): Boolean {
        val toks = text.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
        return toks.isNotEmpty() && text.none { it.isDigit() } && toks.all { AREA_END.containsMatchIn(it) || it in PROVINCE_SHORT }
    }

    private val PROVINCE_SHORT = setOf("서울", "부산", "대구", "인천", "광주", "대전", "울산", "세종", "경기", "강원", "충북", "충남", "전북", "전남", "경북", "경남", "제주")

    private fun kakao(key: String, kind: String, q: String, near: Pt? = null): Pt? = runCatching {
        val nearQ = if (near != null) "&x=${near.lng}&y=${near.lat}&radius=20000" else ""
        val url = URL("https://dapi.kakao.com/v2/local/search/$kind.json?size=1$nearQ&query=" + URLEncoder.encode(q, "UTF-8"))
        val con = url.openConnection() as HttpURLConnection
        con.setRequestProperty("Authorization", "KakaoAK $key")
        con.connectTimeout = 5000; con.readTimeout = 5000
        val code = con.responseCode
        if (code != 200) {
            val err = runCatching { con.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()
            lastError = kakaoError(code, err)
            return null
        }
        val body = con.inputStream.bufferedReader().use { it.readText() }
        val doc = JSONObject(body).getJSONArray("documents").optJSONObject(0) ?: return null
        val label = doc.optString("place_name").ifEmpty { doc.optString("address_name") }
        Pt(doc.getString("y").toDouble(), doc.getString("x").toDouble(), label, q)
    }.onFailure {
        lastError = "카카오 연결 오류(인터넷 확인): ${it.javaClass.simpleName} ${it.message.orEmpty().take(60)}"
    }.getOrNull()

    /** 카카오 오류 코드를 알아듣기 쉬운 말로 */
    fun kakaoError(code: Int, body: String): String {
        val msg = runCatching { JSONObject(body).optString("message") }.getOrNull().orEmpty()
        return when {
            code == 401 -> "카카오 키가 틀렸어요 (401) — REST API 키를 다시 복사해 넣어주세요"
            code == 403 && ("OPEN_MAP_AND_LOCAL" in body || "disabled" in body) ->
                "카카오맵 사용 설정이 꺼져 있어요 (403) — 카카오 개발자 사이트 → 내 앱 → 카카오맵 → 사용 설정 ON"
            code == 403 -> "카카오 사용 권한 없음 (403) $msg"
            code == 429 -> "카카오 하루 사용량 초과 (429)"
            else -> "카카오 오류 ($code) $msg"
        }.trim()
    }

    /** 설정의 [카카오 키 시험] — 결과를 사람 말로 돌려준다 (백그라운드에서 호출) */
    fun testKey(key: String): String = runCatching {
        if (key.isBlank()) return "키 칸이 비어 있어요"
        val con = URL("https://dapi.kakao.com/v2/local/search/keyword.json?size=1&query=" + URLEncoder.encode("수원역", "UTF-8"))
            .openConnection() as HttpURLConnection
        con.setRequestProperty("Authorization", "KakaoAK ${key.trim()}")
        con.connectTimeout = 7000; con.readTimeout = 7000
        val code = con.responseCode
        if (code != 200) return kakaoError(code, runCatching { con.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty())
        val doc = JSONObject(con.inputStream.bufferedReader().use { it.readText() }).getJSONArray("documents").optJSONObject(0)
        if (doc == null) "키는 맞는데 검색 결과가 없어요" else "✅ 카카오 키 정상!\n'수원역' 검색 → ${doc.optString("place_name")}\n(${doc.optString("address_name")})"
    }.getOrElse { "카카오에 연결 못 했어요 (인터넷 확인): ${it.javaClass.simpleName} ${it.message.orEmpty().take(60)}" }
}

