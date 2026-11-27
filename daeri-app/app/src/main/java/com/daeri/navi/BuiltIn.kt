package com.daeri.navi

/** 앱에 넣어둔 기본 카카오 키 (설정에서 따로 넣지 않아도 바로 쓰도록) */
internal object BuiltIn {
    private val M = intArrayOf(0x5A, 0x13, 0x77, 0x2C, 0x41)
    private fun d(a: IntArray) = String(CharArray(a.size) { (a[it] xor M[it % M.size]).toChar() })

    /** REST API 키 — 주소·가게 이름 찾기 */
    val rest: String get() = d(intArrayOf(56, 32, 21, 29, 39, 107, 37, 64, 74, 118, 99, 42, 21, 28, 115, 108, 36, 78, 24, 37, 98, 34, 19, 24, 39, 60, 118, 70, 78, 37, 56, 37))

    /** JavaScript 키 — 콜 상세 지도를 카카오 지도로 (도메인 https://appassets.androidplatform.net 등록됨) */
    val js: String get() = d(intArrayOf(62, 33, 66, 77, 121, 59, 42, 21, 20, 113, 109, 118, 67, 73, 114, 57, 43, 21, 24, 36, 109, 117, 64, 29, 32, 60, 118, 20, 29, 117, 109, 36))
}
