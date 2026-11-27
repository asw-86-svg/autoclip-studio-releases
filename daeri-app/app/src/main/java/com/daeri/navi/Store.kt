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
    /**
     * 운행일지 한 건. 수입 = 요금 + 경유비 + 팁, 지출 = 수수료 + 보험료 + 교통비
     */
    data class Trip(
        val id: Long, val time: Long,
        val origin: String = "", val via: String = "", val dest: String = "",
        val program: String = "",
        val fare: Int = 0, val viaFee: Int = 0, val tip: Int = 0,
        val commission: Int = 0, val insurance: Int = 0, val transport: Int = 0, val transportMode: String = "",
        val memo: String = "", val callKey: String = "",
    ) {
        val income get() = fare + viaFee + tip
        val expense get() = commission + insurance + transport
        val net get() = income - expense
        val title get() = listOf(origin, dest).filter { it.isNotBlank() }.joinToString(" → ")
    }

    fun trips(ctx: Context): List<Trip> = runCatching {
        val arr = JSONArray(p(ctx).getString("trips", "[]"))
        (0 until arr.length()).map { i ->
            arr.getJSONObject(i).let { o ->
                if (o.has("fare")) Trip(
                    o.getLong("id"), o.getLong("time"),
                    o.optString("origin"), o.optString("via"), o.optString("dest"), o.optString("program"),
                    o.optInt("fare"), o.optInt("viaFee"), o.optInt("tip"),
                    o.optInt("commission"), o.optInt("insurance"), o.optInt("transport"), o.optString("transportMode"),
                    o.optString("memo"), o.optString("key"),
                ) else {
                    // 예전 형식(수입/지출/제목)에서 옮기기
                    val title = o.optString("title")
                    Trip(
                        o.getLong("id"), o.getLong("time"),
                        origin = title.substringBefore(" → ", title), dest = if (" → " in title) title.substringAfter(" → ") else "",
                        program = o.optString("memo"), fare = o.optInt("in"), commission = o.optInt("out"), callKey = o.optString("key"),
                    )
                }
            }
        }
    }.getOrDefault(emptyList())

    private fun saveTrips(ctx: Context, list: List<Trip>) {
        val arr = JSONArray()
        list.sortedBy { it.time }.forEach { t ->
            arr.put(JSONObject().apply {
                put("id", t.id); put("time", t.time)
                put("origin", t.origin); put("via", t.via); put("dest", t.dest); put("program", t.program)
                put("fare", t.fare); put("viaFee", t.viaFee); put("tip", t.tip)
                put("commission", t.commission); put("insurance", t.insurance); put("transport", t.transport)
                put("transportMode", t.transportMode); put("memo", t.memo); put("key", t.callKey)
            })
        }
        p(ctx).edit().putString("trips", arr.toString()).apply()
    }

    /** 배차취소·거부된 콜: 자동운행기록에서 빼고, 받은 콜 목록에선 '수락 전'으로 되돌린다 */
    fun unrecordCall(ctx: Context, key: String) {
        saveTrips(ctx, trips(ctx).filterNot { it.callKey == key && System.currentTimeMillis() - it.time < 3600_000L })
        writeHistory(ctx, history(ctx).map { if (it.key == key) it.copy(accepted = false) else it })
        current(ctx)?.let { if (it.key == key) p(ctx).edit().putString("current", toJson(it.copy(accepted = false)).toString()).apply() }
    }

    fun upsertTrip(ctx: Context, t: Trip) = saveTrips(ctx, trips(ctx).filter { it.id != t.id } + t)
    fun deleteTrip(ctx: Context, id: Long) = saveTrips(ctx, trips(ctx).filter { it.id != id })

    /** 수락된 콜(상세배차정보·배정완료)을 읽으면 운행일지에 자동 기록. 같은 콜은 12시간 안에 한 번만. */
    private fun recordAccepted(ctx: Context, c: CallInfo) {
        val list = trips(ctx)
        if (list.any { it.callKey == c.key && abs(it.time - c.capturedAt) < 12 * 3600_000L }) return
        val fare = CallParser.won(c.fare)
        val deposit = CallParser.won(c.deposit)
        upsertTrip(ctx, Trip(
            id = c.capturedAt, time = c.capturedAt,
            origin = c.originDisplay, dest = c.destDisplay, program = programName(c.source),
            fare = fare,
            // 입금액(로지)이 있으면 그게 수수료, 없으면 설정한 수수료율로 계산
            commission = if (deposit > 0) deposit else fare * ctx.commissionRate / 100,
            callKey = c.key,
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
        val keepHist = hist.filterNot { bad(it.source) || CallParser.isMenuText(it.originRaw) || CallParser.isMenuText(it.destRaw) }
        if (keepHist.size != hist.size) writeHistory(ctx, keepHist)
        val tr = trips(ctx)
        val keepTrips = tr.filterNot {
            it.callKey.isNotEmpty() && (bad(it.program.ifEmpty { it.memo }) ||
                // 카카오 메뉴 글자를 콜로 잘못 읽은 기록 ("콜 대기 콜 수요 지도 → 리스트 설정")
                CallParser.isMenuText(it.origin) || CallParser.isMenuText(it.dest))
        }
        if (keepTrips.size != tr.size) saveTrips(ctx, keepTrips)
        current(ctx)?.let { if (bad(it.source)) p(ctx).edit().remove("current").apply() }
        val dumps = runCatching { JSONArray(p(ctx).getString("dumps", "[]")) }.getOrDefault(JSONArray())
        val keepDumps = JSONArray()
        for (i in 0 until dumps.length()) dumps.getJSONObject(i).let { if (!bad(it.optString("app"))) keepDumps.put(it) }
        p(ctx).edit().putString("dumps", keepDumps.toString()).apply()
    }

    /** 대리 앱 이름을 일지용 짧은 이름으로 */
    fun programName(source: String) = when {
        "카카오" in source -> "카카오대리"
        "로지" in source -> "로지"
        "콜마너" in source -> "콜마너"
        "driver" in source.lowercase() -> "I-Driver"
        else -> source
    }

    /** 수수료율(%) — 입금액이 없는 콜(카카오 등)의 수수료 자동 계산에 사용. 기본 20% */
    var Context.commissionRate: Int
        get() = p(this).getInt("commissionRate", 20)
        set(v) = p(this).edit().putInt("commissionRate", v.coerceIn(0, 100)).apply()

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

    /** 카카오T 대리 콜에도 말풍선을 띄울지 (기본: 안 띄움 — 카카오 콜카드에 지도·시간이 이미 나옴) */
    var Context.kakaoBubble: Boolean
        get() = p(this).getBoolean("kakaoBubble", false)
        set(v) = p(this).edit().putBoolean("kakaoBubble", v).apply()

    /** 콜이 들어온 뒤 말풍선을 보여주는 시간(초). 지나면 숨기고 다음 콜에 다시 띄움. 기본 5분 */
    var Context.bubbleKeepSec: Int
        get() = p(this).getInt("bubbleKeepSec", 300)
        set(v) = p(this).edit().putInt("bubbleKeepSec", v).apply()

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
        put("accepted", c.accepted); put("deposit", c.deposit); put("pkg", c.pkg); put("auto", c.autoDispatch)
        put("phones", JSONArray().apply { c.phones.forEach { put(JSONArray(listOf(it.first, it.second))) } })
    }

    private fun JSONArray.strings() = (0 until length()).map { getString(it) }

    private fun fromJson(o: JSONObject) = CallInfo(
        source = o.optString("source"), originRaw = o.optString("originRaw"), destRaw = o.optString("destRaw"),
        originQueries = o.optJSONArray("oq")?.strings().orEmpty(),
        destQueries = o.optJSONArray("dq")?.strings().orEmpty(),
        fare = o.optString("fare"), notes = o.optString("notes"), capturedAt = o.optLong("at"),
        accepted = o.optBoolean("accepted"), deposit = o.optString("deposit"), pkg = o.optString("pkg"),
        autoDispatch = o.optBoolean("auto"),
        phones = o.optJSONArray("phones")?.let { a ->
            (0 until a.length()).map { a.getJSONArray(it).let { p -> p.getString(0) to p.getString(1) } }
        }.orEmpty(),
    )
}
