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
    fun resolve(ctx: Context, queries: List<String>): Pt? {
        val key = ctx.kakaoKey.trim()
        // 업체/건물 이름이 있는 콜이면 "화성시 만세구" 같은 동네 이름 후보로 대충 찍지 않는다
        val specific = queries.firstOrNull()?.let { !isAreaOnly(it) } == true
        for (q in queries.take(5)) {
            if (specific && isAreaOnly(q)) continue
            val ck = "$key|$q"
            cache[ck]?.let { return it }
            if (ck in misses) continue
            val pt = if (key.isNotEmpty()) kakao(key, "address", q) ?: kakao(key, "keyword", q) else geocoder(ctx, q)
            if (pt != null) { cache[ck] = pt; return pt }
            misses += ck
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun geocoder(ctx: Context, q: String): Pt? = runCatching {
        if (!Geocoder.isPresent()) return null
        val a = Geocoder(ctx, Locale.KOREA).getFromLocationName(q, 1)?.firstOrNull() ?: return null
        // 한국 범위를 벗어난 엉뚱한 결과는 버림
        if (a.latitude !in 33.0..39.0 || a.longitude !in 124.0..132.0) return null
        val line = a.getAddressLine(0) ?: q
        // "경기도 화성시 팔탄면" 처럼 번지 없이 동네 이름에서 끝나면 대략적인 위치일 뿐이다.
        // 검색어가 업체/건물 이름이었다면 이 결과는 버리고 지도앱 검색에 맡긴다.
        if (isAreaOnly(line) && !isAreaOnly(q)) return null
        Pt(a.latitude, a.longitude, line, q)
    }.getOrNull()

    private val AREA_END = Regex("""(시|군|구|읍|면|동|리|가|도|대한민국)$""")

    /** 숫자(번지) 없이 행정구역 이름으로만 이루어졌는지 */
    fun isAreaOnly(text: String): Boolean {
        val toks = text.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
        return toks.isNotEmpty() && text.none { it.isDigit() } && toks.all { AREA_END.containsMatchIn(it) || it in PROVINCE_SHORT }
    }

    private val PROVINCE_SHORT = setOf("서울", "부산", "대구", "인천", "광주", "대전", "울산", "세종", "경기", "강원", "충북", "충남", "전북", "전남", "경북", "경남", "제주")

    private fun kakao(key: String, kind: String, q: String): Pt? = runCatching {
        val url = URL("https://dapi.kakao.com/v2/local/search/$kind.json?size=1&query=" + URLEncoder.encode(q, "UTF-8"))
        val con = url.openConnection() as HttpURLConnection
        con.setRequestProperty("Authorization", "KakaoAK $key")
        con.connectTimeout = 5000; con.readTimeout = 5000
        val body = con.inputStream.bufferedReader().use { it.readText() }
        val doc = JSONObject(body).getJSONArray("documents").optJSONObject(0) ?: return null
        val label = doc.optString("place_name").ifEmpty { doc.optString("address_name") }
        Pt(doc.getString("y").toDouble(), doc.getString("x").toDouble(), label, q)
    }.getOrNull()
}
