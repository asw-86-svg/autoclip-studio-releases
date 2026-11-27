package com.daeri.navi

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** 현재 콜, 받은 콜 목록, 운행일지, 설정값을 휴대폰 안에만 저장한다. */
object Store {
    private const val PREFS = "daeri"
    private const val CALLS_FILE = "calls.json"
    private const val MAX_DUMPS = 10

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------------- 현재 콜 / 받은 콜 목록 ----------------
    fun current(ctx: Context): CallInfo? =
        p(ctx).getString("current", null)?.let { runCatching { fromJson(JSONObject(it)) }.getOrNull() }

    fun setCurrent(ctx: Context, c: CallInfo?) {
        if (c == null) { p(ctx).edit().remove("current").apply(); return }
        p(ctx).edit().putString("current", toJson(c).toString()).apply()
        // 받은 콜 목록: 개수 제한 없음 (목록삭제 전까지 전부 보관)
        writeHistory(ctx, listOf(c) + history(ctx).filter { it.key != c.key })
        if (c.accepted) recordAccepted(ctx, c)
    }

    // 받은 콜 목록은 콜이 많이 쌓여도 느려지지 않게 별도 파일 + 메모리 캐시
    private val histLock = Any()
    @Volatile private var histCache: List<CallInfo>? = null
    private fun callsFile(ctx: Context) = java.io.File(ctx.filesDir, CALLS_FILE)

    fun history(ctx: Context): List<CallInfo> = synchronized(histLock) {
        histCache?.let { return it }
        val f = callsFile(ctx)
        val legacy = !f.exists() && p(ctx).contains("history")   // 예전 버전 저장분 옮기기
        val text = when {
            f.exists() -> runCatching { f.readText() }.getOrDefault("[]")
            legacy -> p(ctx).getString("history", "[]").orEmpty()
            else -> "[]"
        }
        val list = runCatching {
            val arr = JSONArray(text)
            (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
        histCache = list
        if (legacy) { writeHistory(ctx, list); p(ctx).edit().remove("history").apply() }
        list
    }

    private fun writeHistory(ctx: Context, list: List<CallInfo>) = synchronized(histLock) {
        histCache = list
        val arr = JSONArray().apply { list.forEach { put(toJson(it, trimNotes = true)) } }
        runCatching {
            val tmp = java.io.File(ctx.filesDir, "$CALLS_FILE.tmp")
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(callsFile(ctx))) callsFile(ctx).writeText(arr.toString())
        }
    }

    fun clearHistory(ctx: Context) = writeHistory(ctx, emptyList())

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
        // 대리 앱 이름이나 직접 입력한 것만 진짜 콜로 인정하고, 나머지(채팅앱 등에서 잘못 읽은 것)는 지운다
        val okNames = listOf("로지", "콜마너", "카카오", "대리", "탁송", "기사", "직접 입력", "공유", "붙여넣기", "배차정보", "콜 수락 화면", "드라이버", "커넥트", "Driver", "driver")
        fun bad(src: String) = src.isBlank() || okNames.none { it in src } ||
            "anthropic" in src.lowercase() || "claude" in src.lowercase()
        val hist = history(ctx)
        val keepHist = hist.filterNot { bad(it.source) }
        if (keepHist.size != hist.size) writeHistory(ctx, keepHist)
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

    /** 말풍선 표시: 0 = 항상, 1 = 대리 앱 볼 때만, 2 = 안 띄움 */
    var Context.bubbleMode: Int
        get() = p(this).getInt("bubbleMode", 0)
        set(v) = p(this).edit().putInt("bubbleMode", v).apply()

    /** 오늘의 목표 금액 (출근할 때 입력). 날짜가 바뀌면 다시 입력 */
    fun goal(ctx: Context, day: String): Int? =
        if (p(ctx).getString("goalDay", "") == day) p(ctx).getInt("goal", 0) else null
    fun setGoal(ctx: Context, day: String, won: Int) = p(ctx).edit().putString("goalDay", day).putInt("goal", won).apply()

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

    /** 마지막 읽기 결과 (문제 해결용) */
    fun setStatus(ctx: Context, msg: String) = p(ctx).edit().putString("status", msg).apply()
    fun status(ctx: Context): String = p(ctx).getString("status", "").orEmpty()

    /** 말풍선 위치를 화면 비율(0~1)로 저장 → 폴드 외부/내부 화면 어디서든 같은 자리 */
    fun bubbleFrac(ctx: Context): Pair<Float, Float> = p(ctx).getFloat("bfx", 1f) to p(ctx).getFloat("bfy", 0.25f)
    fun setBubbleFrac(ctx: Context, fx: Float, fy: Float) =
        p(ctx).edit().putFloat("bfx", fx.coerceIn(0f, 1f)).putFloat("bfy", fy.coerceIn(0f, 1f)).apply()

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
