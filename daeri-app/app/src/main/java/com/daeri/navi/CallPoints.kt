package com.daeri.navi

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 콜 포인트 기록 — 나중에 "어디서·언제 콜이 많이 나오나" 등급을 매기려고 모으는 자료.
 * 받은 콜 목록과 따로 저장한다 → 목록삭제를 해도 지워지지 않는다. 바뀔 때마다 직전 파일을 _prev 로 남긴다.
 *
 * 한 건: 받은 시각, 콜 받은 곳(내 위치), 출발지(손님 위치), 요금, 프로그램, 대리/탁송, 수락 여부
 */
object CallPoints {
    data class Point(
        val key: String, val at: Long,
        val recvLat: Double = 0.0, val recvLng: Double = 0.0, val recvArea: String = "",
        val oLat: Double = 0.0, val oLng: Double = 0.0, val oArea: String = "",
        val fare: Int = 0, val program: String = "", val takson: Boolean = false, val accepted: Boolean = false,
        val solo: String = "",
    )

    private const val FILE = "callpoints.json"
    private val lock = Any()
    private fun file(ctx: Context) = File(ctx.filesDir, FILE)
    private fun prev(ctx: Context) = File(ctx.filesDir, "$FILE.prev")

    fun all(ctx: Context): List<Point> = synchronized(lock) {
        fun read(f: File): List<Point>? = runCatching {
            if (!f.exists()) return@runCatching emptyList<Point>()
            val a = JSONArray(f.readText())
            (0 until a.length()).mapNotNull { i -> runCatching { from(a.getJSONObject(i)) }.getOrNull() }
        }.getOrNull()
        read(file(ctx)) ?: read(prev(ctx)) ?: emptyList()
    }

    private fun save(ctx: Context, list: List<Point>) = synchronized(lock) {
        val f = file(ctx)
        if (f.exists()) runCatching { f.copyTo(prev(ctx), overwrite = true) }
        val a = JSONArray(); list.sortedBy { it.at }.forEach { a.put(to(it)) }
        val tmp = File(ctx.filesDir, "$FILE.tmp")
        tmp.writeText(a.toString())
        if (!tmp.renameTo(f)) { f.writeText(a.toString()); tmp.delete() }
    }

    /** 같은 콜(같은 출발·도착, 12시간 안)이면 빈 칸만 채우고, 아니면 새로 추가 */
    fun upsert(ctx: Context, c: CallInfo, change: (Point) -> Point = { it }) = synchronized(lock) {
        val list = all(ctx).toMutableList()
        val i = list.indexOfLast { it.key == c.key && kotlin.math.abs(it.at - c.capturedAt) < 12 * 3600_000L }
        val base = if (i >= 0) list[i] else Point(c.key, c.capturedAt)
        val merged = change(base.copy(
            recvLat = base.recvLat.takeIf { it != 0.0 } ?: c.recvLat,
            recvLng = base.recvLng.takeIf { it != 0.0 } ?: c.recvLng,
            recvArea = base.recvArea.ifEmpty { c.recvArea },
            fare = base.fare.takeIf { it > 0 } ?: CallParser.won(c.fare),
            program = base.program.ifEmpty { Store.programName(c.source) },
            takson = base.takson || c.isTakson,
            accepted = base.accepted || c.accepted,
            solo = base.solo.ifEmpty { c.solo },
        ))
        if (i >= 0) list[i] = merged else list += merged
        save(ctx, list)
    }

    private fun to(p: Point) = JSONObject().apply {
        put("key", p.key); put("at", p.at)
        put("rLat", p.recvLat); put("rLng", p.recvLng); put("rArea", p.recvArea)
        put("oLat", p.oLat); put("oLng", p.oLng); put("oArea", p.oArea)
        put("fare", p.fare); put("prog", p.program); put("tak", p.takson); put("ok", p.accepted); put("solo", p.solo)
    }

    private fun from(o: JSONObject) = Point(
        o.getString("key"), o.getLong("at"),
        o.optDouble("rLat", 0.0), o.optDouble("rLng", 0.0), o.optString("rArea"),
        o.optDouble("oLat", 0.0), o.optDouble("oLng", 0.0), o.optString("oArea"),
        o.optInt("fare"), o.optString("prog"), o.optBoolean("tak"), o.optBoolean("ok"), o.optString("solo"),
    )

    /** 나중에 엑셀 등으로 보려고: CSV 글자 */
    fun csv(ctx: Context): String {
        val f = java.text.SimpleDateFormat("yyyy-MM-dd,HH:mm,E", java.util.Locale.KOREA)
        return buildString {
            append("날짜,시각,요일,대기위치,대기위치위도,대기위치경도,출발동네,출발위도,출발경도,요금,프로그램,종류,수락,단독배정\n")
            all(ctx).forEach { p ->
                append(f.format(java.util.Date(p.at))).append(',')
                append(p.recvArea).append(',').append(p.recvLat).append(',').append(p.recvLng).append(',')
                append(p.oArea).append(',').append(p.oLat).append(',').append(p.oLng).append(',')
                append(p.fare).append(',').append(p.program).append(',').append(if (p.takson) "탁송" else "대리").append(',')
                append(if (p.accepted) "수락" else "").append(',').append(p.solo).append('\n')
            }
        }
    }
}
