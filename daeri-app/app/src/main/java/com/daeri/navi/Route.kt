package com.daeri.navi

import android.content.Context
import com.daeri.navi.Store.kakaoKey
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** 두 지점 사이 운전 시간/거리/톨비. 카카오 키가 있으면 카카오모빌리티 길찾기(실시간 교통), 없으면 대략 계산. */
object Route {
    data class Info(val sec: Int, val meters: Int, val toll: Int, val estimated: Boolean)

    /** 네트워크를 쓰므로 백그라운드에서 호출 */
    fun drive(ctx: Context, from: Geo.Pt, to: Geo.Pt): Info {
        val key = ctx.kakaoKey.trim()
        if (key.isNotEmpty()) kakao(key, from, to)?.let { return it }
        return estimate(straightKm(from, to))
    }

    private fun kakao(key: String, from: Geo.Pt, to: Geo.Pt): Info? = runCatching {
        val url = URL(
            "https://apis-navi.kakaomobility.com/v1/directions?origin=${from.lng},${from.lat}" +
                "&destination=${to.lng},${to.lat}&priority=RECOMMEND&summary=true",
        )
        val con = url.openConnection() as HttpURLConnection
        con.setRequestProperty("Authorization", "KakaoAK $key")
        con.connectTimeout = 5000; con.readTimeout = 5000
        val body = con.inputStream.bufferedReader().use { it.readText() }
        val r = JSONObject(body).getJSONArray("routes").getJSONObject(0)
        if (r.optInt("result_code", -1) != 0) return null
        val sm = r.getJSONObject("summary")
        Info(sm.getInt("duration"), sm.getInt("distance"), sm.optJSONObject("fare")?.optInt("toll") ?: 0, false)
    }.getOrNull()

    fun straightKm(a: Geo.Pt, b: Geo.Pt): Double {
        val r = 6371.0
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLng / 2).pow(2)
        return 2 * r * asin(sqrt(h))
    }

    /** 직선거리 → 도로거리(×1.3), 시내 25km/h · 근교 40km/h · 고속 75km/h 로 대략 계산 */
    fun estimate(straightKm: Double): Info {
        val km = straightKm * 1.3
        val hours = min(km, 5.0) / 25 + min((km - 5).coerceAtLeast(0.0), 15.0) / 40 + (km - 20).coerceAtLeast(0.0) / 75
        return Info((hours * 3600).toInt(), (km * 1000).toInt(), 0, true)
    }

    /** 걸어서 걸리는 시간(초): 도로거리 ×1.25, 시속 4.5km */
    fun walkSec(straightKm: Double): Int = (straightKm * 1.25 / 4.5 * 3600).toInt()
}
