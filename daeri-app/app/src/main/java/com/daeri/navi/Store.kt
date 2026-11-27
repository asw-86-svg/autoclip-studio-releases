package com.daeri.navi

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** 현재 콜, 받은 콜 목록, 운행일지, 설정값을 휴대폰 안에만 저장한다. */
object Store {
    private const val PREFS = "daeri"
    private const val MAX_CALLS = 100
    private const val MAX_DUMPS = 10

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------------- 현재 콜 / 받은 콜 목록 ----------------
    fun current(ctx: Context): CallInfo? =
        p(ctx).getString("current", null)?.let { runCatching { fromJson(JSONObject(it)) }.getOrNull() }

    fun setCurrent(ctx: Context, c: CallInfo?) {
        if (c == null) { p(ctx).edit().remove("current").apply(); return }
        val hist = history(ctx).filter { it.key != c.key }.toMutableList()
        hist.add(0, c)
        val arr = JSONArray().apply { hist.take(MAX_CALLS).forEach { put(toJson(it, trimNotes = true)) } }
        p(ctx).edit().putString("current", toJson(c).toString()).putString("history", arr.toString()).apply()
        if (c.accepted) recordAccepted(ctx, c)
    }

    fun history(ctx: Context): List<CallInfo> = runCatching {
        val arr = JSONArray(p(ctx).getString("history", "[]"))
        (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun clearHistory(ctx: Context) = p(ctx).edit().remove("history").apply()

    // ---------------- 운행일지 ----------------
    data class Trip(
        val id: Long, val time: Long, val income: Int, val expense: Int,
        val title: String, val memo: String, val callKey: String = "",
    ) { val net get() = income - expense }

    fun trips(ctx: Context): List<Trip> = runCatching {
        val arr = JSONArray(p(ctx).getString("trips", "[]"))
        (0 until arr.length()).map { i ->
            arr.getJSONObject(i).let { o ->
                Trip(o.getLong("id"), o.getLong("time"), o.optInt("in"), o.optInt("out"),
                    o.optString("title"), o.optString("memo"), o.optString("key"))
            }
        }
    }.getOrDefault(emptyList())

    private fun saveTrips(ctx: Context, list: List<Trip>) {
        val arr = JSONArray()
        list.sortedBy { it.time }.forEach { t ->
            arr.put(JSONObject().apply {
                put("id", t.id); put("time", t.time); put("in", t.income); put("out", t.expense)
                put("title", t.title); put("memo", t.memo); put("key", t.callKey)
            })
        }
        p(ctx).edit().putString("trips", arr.toString()).apply()
    }

    fun upsertTrip(ctx: Context, t: Trip) = saveTrips(ctx, trips(ctx).filter { it.id != t.id } + t)
    fun deleteTrip(ctx: Context, id: Long) = saveTrips(ctx, trips(ctx).filter { it.id != id })

    /** 수락된 콜(상세배차정보·배정완료)을 읽으면 운행일지에 자동 기록. 같은 콜은 12시간 안에 한 번만. */
    private fun recordAccepted(ctx: Context, c: CallInfo) {
        val list = trips(ctx)
        if (list.any { it.callKey == c.key && abs(it.time - c.capturedAt) < 12 * 3600_000L }) return
        upsertTrip(ctx, Trip(
            id = c.capturedAt, time = c.capturedAt,
            income = CallParser.won(c.fare), expense = CallParser.won(c.deposit),
            title = "${c.originDisplay} → ${c.destDisplay}", memo = c.source, callKey = c.key,
        ))
    }

    /**
     * 예전 버전이 대리 앱이 아닌 화면(채팅 등)을 콜로 잘못 읽어 남긴 기록을 지운다.
     * 앱 이름을 못 찾아 'com.xxx.yyy' 같은 패키지 이름이 출처로 남은 것들.
     */
    fun cleanupJunk(ctx: Context) {
        val junk = Regex("""^[a-z0-9_]+(\.[a-z0-9_]+)+$""")
        fun bad(src: String) = junk.matches(src.trim()) || "anthropic" in src || "claude" in src.lowercase()
        val hist = history(ctx)
        val keepHist = hist.filterNot { bad(it.source) }
        if (keepHist.size != hist.size) {
            val arr = JSONArray().apply { keepHist.forEach { put(toJson(it, trimNotes = true)) } }
            p(ctx).edit().putString("history", arr.toString()).apply()
        }
        val tr = trips(ctx)
        val keepTrips = tr.filterNot { bad(it.memo) }
        if (keepTrips.size != tr.size) saveTrips(ctx, keepTrips)
        current(ctx)?.let { if (bad(it.source)) p(ctx).edit().remove("current").apply() }
        val dumps = runCatching { JSONArray(p(ctx).getString("dumps", "[]")) }.getOrDefault(JSONArray())
        val keepDumps = JSONArray()
        for (i in 0 until dumps.length()) dumps.getJSONObject(i).let { if (!bad(it.optString("app"))) keepDumps.put(it) }
        p(ctx).edit().putString("dumps", keepDumps.toString()).apply()
    }

    // ---------------- 설정 ----------------
    var Context.homeAddress: String
        get() = p(this).getString("home", "").orEmpty()
        set(v) = p(this).edit().putString("home", v).apply()

    var Context.kakaoKey: String
        get() = p(this).getString("kakaoKey", "").orEmpty()
        set(v) = p(this).edit().putString("kakaoKey", v).apply()

    var Context.detectPaused: Boolean
        get() = p(this).getBoolean("paused", false)
        set(v) = p(this).edit().putBoolean("paused", v).apply()

    // ---------------- 읽은 화면 기록 (문제 해결용) ----------------
    fun addDump(ctx: Context, app: String, texts: List<String>) {
        val body = texts.joinToString("\n") { "• " + it.replace("\n", " ⏎ ") }.take(3000)
        val arr = runCatching { JSONArray(p(ctx).getString("dumps", "[]")) }.getOrDefault(JSONArray())
        if (arr.length() > 0 && arr.getJSONObject(0).optString("body") == body) return
        val out = JSONArray().put(JSONObject().put("app", app).put("at", System.currentTimeMillis()).put("body", body))
        for (i in 0 until minOf(arr.length(), MAX_DUMPS - 1)) out.put(arr.getJSONObject(i))
        p(ctx).edit().putString("dumps", out.toString()).apply()
    }

    fun dumps(ctx: Context): List<Triple<String, Long, String>> = runCatching {
        val arr = JSONArray(p(ctx).getString("dumps", "[]"))
        (0 until arr.length()).map { arr.getJSONObject(it).let { o -> Triple(o.optString("app"), o.optLong("at"), o.optString("body")) } }
    }.getOrDefault(emptyList())

    fun bubblePos(ctx: Context): Pair<Int, Int> = p(ctx).getInt("bx", 0) to p(ctx).getInt("by", 400)
    fun setBubblePos(ctx: Context, x: Int, y: Int) = p(ctx).edit().putInt("bx", x).putInt("by", y).apply()

    // ---------------- JSON ----------------
    private fun toJson(c: CallInfo, trimNotes: Boolean = false) = JSONObject().apply {
        put("source", c.source); put("originRaw", c.originRaw); put("destRaw", c.destRaw)
        put("oq", JSONArray(c.originQueries)); put("dq", JSONArray(c.destQueries))
        put("fare", c.fare); put("notes", if (trimNotes) c.notes.take(300) else c.notes); put("at", c.capturedAt)
        put("accepted", c.accepted); put("deposit", c.deposit); put("pkg", c.pkg)
        put("phones", JSONArray().apply { c.phones.forEach { put(JSONArray(listOf(it.first, it.second))) } })
    }

    private fun JSONArray.strings() = (0 until length()).map { getString(it) }

    private fun fromJson(o: JSONObject) = CallInfo(
        source = o.optString("source"), originRaw = o.optString("originRaw"), destRaw = o.optString("destRaw"),
        originQueries = o.optJSONArray("oq")?.strings().orEmpty(),
        destQueries = o.optJSONArray("dq")?.strings().orEmpty(),
        fare = o.optString("fare"), notes = o.optString("notes"), capturedAt = o.optLong("at"),
        accepted = o.optBoolean("accepted"), deposit = o.optString("deposit"), pkg = o.optString("pkg"),
        phones = o.optJSONArray("phones")?.let { a ->
            (0 until a.length()).map { a.getJSONArray(it).let { p -> p.getString(0) to p.getString(1) } }
        }.orEmpty(),
    )
}
