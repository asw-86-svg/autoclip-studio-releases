package com.daeri.navi

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/** 카카오맵 / 네이버지도 앱을 길안내 화면으로 바로 연다. */
object MapLauncher {
    const val KAKAO = "net.daum.android.map"
    const val NAVER = "com.nhn.android.nmap"

    enum class App { KAKAO, NAVER }
    enum class Mode { CAR, TRANSIT }

    private fun enc(s: String) = Uri.encode(s)

    /** from == null 이면 현재 위치에서 출발. */
    fun route(ctx: Context, app: App, from: Geo.Pt?, to: Geo.Pt, mode: Mode = Mode.CAR) {
        val uri = when (app) {
            App.KAKAO -> buildString {
                append("kakaomap://route?")
                from?.let { append("sp=${it.lat},${it.lng}&") }
                append("ep=${to.lat},${to.lng}&by=")
                append(if (mode == Mode.CAR) "CAR" else "PUBLICTRANSIT")
            }
            App.NAVER -> buildString {
                append("nmap://route/").append(if (mode == Mode.CAR) "car" else "public").append("?")
                from?.let { append("slat=${it.lat}&slng=${it.lng}&sname=${enc(it.label)}&") }
                append("dlat=${to.lat}&dlng=${to.lng}&dname=${enc(to.label)}&appname=${ctx.packageName}")
            }
        }
        open(ctx, app, uri)
    }

    /** 좌표를 못 찾았을 때: 지도 앱 검색창에 주소를 넣어서 연다. */
    fun search(ctx: Context, app: App, query: String) {
        val uri = when (app) {
            App.KAKAO -> "kakaomap://search?q=${enc(query)}"
            App.NAVER -> "nmap://search?query=${enc(query)}&appname=${ctx.packageName}"
        }
        open(ctx, app, uri)
    }

    private fun open(ctx: Context, app: App, uri: String) {
        val pkg = if (app == App.KAKAO) KAKAO else NAVER
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(ctx, "지도 앱이 설치되어 있지 않아 설치 화면으로 이동합니다", Toast.LENGTH_SHORT).show()
            runCatching {
                ctx.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }
}
