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
        for (q in queries.take(5)) {
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
        Pt(a.latitude, a.longitude, a.getAddressLine(0) ?: q, q)
    }.getOrNull()

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
