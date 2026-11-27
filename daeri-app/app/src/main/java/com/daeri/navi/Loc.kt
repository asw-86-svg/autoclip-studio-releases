package com.daeri.navi

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/** 지금 내 위치 (도착시간 계산용). 결과는 메인 스레드에서 콜백. */
object Loc {
    fun hasPerm(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun pt(l: Location) = Geo.Pt(l.latitude, l.longitude, "현재 위치", "")

    /** 기다리지 않고 바로 쓸 수 있는 최근 위치 (30분 이내) — 주소 찾을 때 근처 우선용 */
    @SuppressLint("MissingPermission")
    fun lastPt(ctx: Context): Geo.Pt? {
        if (!hasPerm(ctx)) return null
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val l = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time } ?: return null
        return if (System.currentTimeMillis() - l.time < 30 * 60_000L) pt(l) else null
    }

    @SuppressLint("MissingPermission")
    fun current(ctx: Context, cb: (Geo.Pt?) -> Unit) {
        if (!hasPerm(ctx)) { cb(null); return }
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        val last = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        if (last != null && System.currentTimeMillis() - last.time < 2 * 60_000) { cb(pt(last)); return }
        if (Build.VERSION.SDK_INT < 30 || providers.isEmpty()) { cb(last?.let(::pt)); return }

        var done = false
        val main = Handler(Looper.getMainLooper())
        main.postDelayed({ if (!done) { done = true; cb(last?.let(::pt)) } }, 8000)
        val p = if (LocationManager.NETWORK_PROVIDER in providers) LocationManager.NETWORK_PROVIDER else providers.first()
        runCatching {
            lm.getCurrentLocation(p, null, ctx.mainExecutor) { loc ->
                if (!done) { done = true; cb((loc ?: last)?.let(::pt)) }
            }
        }.onFailure { if (!done) { done = true; cb(last?.let(::pt)) } }
    }
}
