package com.daeri.navi

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** 현재 콜, 받은 콜 목록, 운행일지, 설정값을 휴대폰 안에만 저장한다. */
object Store {
    private const val PREFS = "daeri"
    private const val CALLS_FILE = "calls.json"
    private const val MAX_DUMPS = 40

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------------- 현재 콜 / 받은 콜 목록 ----------------
    fun current(ctx: Context): CallInfo? =
        p(ctx).getString("current", null)?.let { runCatching { fromJson(JSONObject(it)) }.getOrNull() }

    fun setCurrent(ctx: Context, c: CallInfo?) {
        if (c == null) { p(ctx).edit().remove("current").apply(); return }
        p(ctx).edit().putString("current", toJson(c).toString()).apply()
        // 받은 콜 목록: 개수 제한 없음 (목록삭제 전까지 전부 보관).
        // 시간순 고정 — 이미 있는 콜(목록에서 다시 열었거나 같은 콜을 또 읽음)은 제자리에서 내용만 바꾸고 처음 받은 시각 유지
        val hist = history(ctx)
        val same = hist.indexOfFirst { it.key == c.key && abs(it.capturedAt - c.capturedAt) < 12 * 3600_000L }
        val newHist = if (same >= 0) hist.toMutableList().also { it[same] = c.copy(capturedAt = hist[same].capturedAt) }
                      else listOf(c) + hist
        writeHistory(ctx, newHist.sortedByDescending { it.capturedAt })
        if (c.accepted) recordAccepted(ctx, c)
        // 콜 포인트 기록 (목록삭제와 상관없이 따로 쌓임)
        runCatching { CallPoints.upsert(ctx, c) }
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
        /** false = 자동운행기록에만 있음(일지에 추가 전), true = 운행일지에 들어감 */
        val confirmed: Boolean = true,
        /** 콜을 읽은 대리 앱 패키지 (아이콘 표시용) */
        val pkg: String = "",
        /** "탁송" 또는 "대리" (예전 기록은 빈칸 = 대리) */
        val kind: String = "",
        /** 콜 받은 곳 (내 위치의 동네 이름 + 좌표) */
        val recvArea: String = "", val recvLat: Double = 0.0, val recvLng: Double = 0.0,
        /** 카카오 단독배정: "프로 단독배정" / "단독배정" / "" */
        val solo: String = "",
    ) {
        val isTakson get() = kind == "탁송"
        val income get() = fare + viaFee + tip
        val expense get() = commission + insurance + transport
        val net get() = income - expense
        val title get() = listOf(origin, dest).filter { it.isNotBlank() }.joinToString(" → ")
    }

    /** 운행일지 읽기/쓰기는 한 번에 하나씩 (콜 읽기와 화면이 동시에 고쳐서 기록이 빠지는 일 방지) */
    val tripLock = Any()

    /** 깨진 기록이 하나 있어도 나머지는 그대로 읽는다. 전체가 깨졌으면 직전 상태에서 읽는다 */
    fun trips(ctx: Context): List<Trip> = synchronized(tripLock) {
        fun parse(raw: String?): List<Trip>? = runCatching {
            val arr = JSONArray(raw ?: "[]")
            (0 until arr.length()).mapNotNull { i -> runCatching { tripFromJson(arr.getJSONObject(i)) }.getOrNull() }
        }.getOrNull()
        parse(p(ctx).getString("trips", "[]")) ?: parse(p(ctx).getString("trips_prev", "[]")) ?: emptyList()
    }

    private fun tripFromJson(o: JSONObject): Trip =
                if (o.has("fare")) Trip(
                    o.getLong("id"), o.getLong("time"),
                    o.optString("origin"), o.optString("via"), o.optString("dest"), o.optString("program"),
                    o.optInt("fare"), o.optInt("viaFee"), o.optInt("tip"),
                    o.optInt("commission"), o.optInt("insurance"), o.optInt("transport"), o.optString("transportMode"),
                    o.optString("memo"), o.optString("key"),
                    o.optBoolean("ok", true), o.optString("pkg"), o.optString("kind"),
                    o.optString("recvArea"), o.optDouble("recvLat", 0.0), o.optDouble("recvLng", 0.0),
                    o.optString("solo"),
                ) else {
                    // 예전 형식(수입/지출/제목)에서 옮기기
                    val title = o.optString("title")
                    Trip(
                        o.getLong("id"), o.getLong("time"),
                        origin = title.substringBefore(" → ", title), dest = if (" → " in title) title.substringAfter(" → ") else "",
                        program = o.optString("memo"), fare = o.optInt("in"), commission = o.optInt("out"), callKey = o.optString("key"),
                    )
                }

    private fun tripToJson(t: Trip) = JSONObject().apply {
        put("id", t.id); put("time", t.time)
        put("origin", t.origin); put("via", t.via); put("dest", t.dest); put("program", t.program)
        put("fare", t.fare); put("viaFee", t.viaFee); put("tip", t.tip)
        put("commission", t.commission); put("insurance", t.insurance); put("transport", t.transport)
        put("transportMode", t.transportMode); put("memo", t.memo); put("key", t.callKey)
        put("ok", t.confirmed); put("pkg", t.pkg); put("kind", t.kind)
        if (t.solo.isNotEmpty()) put("solo", t.solo)
        if (t.recvArea.isNotEmpty() || t.recvLat != 0.0) { put("recvArea", t.recvArea); put("recvLat", t.recvLat); put("recvLng", t.recvLng) }
    }

    private fun saveTrips(ctx: Context, list: List<Trip>) = synchronized(tripLock) {
        val arr = JSONArray()
        list.sortedBy { it.time }.forEach { arr.put(tripToJson(it)) }
        val old = p(ctx).getString("trips", "[]").orEmpty()
        backupSnapshot(ctx, old)
        // commit(): 바로 디스크에 써서 앱이 갑자기 꺼져도 안 날아가게
        p(ctx).edit().putString("trips_prev", old).putString("trips", arr.toString()).commit()
        Unit
    }

    // ---------------- 운행일지 자동 백업 (30일) ----------------
    private fun backupDir(ctx: Context) = java.io.File(ctx.filesDir, "backup").apply { mkdirs() }

    /** 바뀌기 전 상태를 시간 단위 파일로 남긴다 (그 시간 첫 변경 때 한 번) */
    private fun backupSnapshot(ctx: Context, oldJson: String) {
        runCatching {
            if (oldJson.length < 5) return  // 비어 있으면 남길 필요 없음
            val name = "trips-" + java.text.SimpleDateFormat("yyyyMMdd-HH", java.util.Locale.KOREA).format(java.util.Date()) + ".json"
            val f = java.io.File(backupDir(ctx), name)
            if (!f.exists()) f.writeText(oldJson)
            val monthAgo = System.currentTimeMillis() - 30L * 24 * 3600_000L
            backupDir(ctx).listFiles()?.filter { it.lastModified() < monthAgo }?.forEach { it.delete() }
        }
    }

    data class Backup(val file: java.io.File, val label: String, val confirmed: Int, val pending: Int)

    /** 되살릴 수 있는 백업 목록 (최신 먼저) */
    fun backups(ctx: Context): List<Backup> {
        val fmtIn = java.text.SimpleDateFormat("yyyyMMdd-HH", java.util.Locale.KOREA)
        val fmtOut = java.text.SimpleDateFormat("M월 d일 H시 무렵", java.util.Locale.KOREA)
        return (backupDir(ctx).listFiles()?.toList().orEmpty()).sortedByDescending { it.name }.mapNotNull { f ->
            val list = runCatching {
                val arr = JSONArray(f.readText())
                (0 until arr.length()).mapNotNull { i -> runCatching { tripFromJson(arr.getJSONObject(i)) }.getOrNull() }
            }.getOrNull() ?: return@mapNotNull null
            val label = runCatching { fmtOut.format(fmtIn.parse(f.name.removePrefix("trips-").removeSuffix(".json"))!!) }.getOrDefault(f.name)
            Backup(f, label, list.count { it.confirmed }, list.count { !it.confirmed })
        }
    }

    /** 백업에만 있는 기록을 지금 기록에 더한다 (지금 기록은 그대로) → 더한 건수 */
    fun mergeBackup(ctx: Context, b: Backup): Int = synchronized(tripLock) {
        val arr = runCatching { JSONArray(b.file.readText()) }.getOrNull() ?: return 0
        val fromBackup = (0 until arr.length()).mapNotNull { i -> runCatching { tripFromJson(arr.getJSONObject(i)) }.getOrNull() }
        val now = trips(ctx)
        val ids = now.map { it.id }.toSet()
        val add = fromBackup.filter { it.id !in ids }
        if (add.isNotEmpty()) saveTrips(ctx, now + add)
        add.size
    }

    // ---------------- 삭제된 기록 (되살리기 가능) ----------------
    data class Trashed(val trip: Trip, val deletedAt: Long, val reason: String)

    fun trash(ctx: Context): List<Trashed> = runCatching {
        val arr = JSONArray(p(ctx).getString("tripTrash", "[]"))
        (0 until arr.length()).map { i ->
            arr.getJSONObject(i).let { o -> Trashed(tripFromJson(o.getJSONObject("trip")), o.optLong("at"), o.optString("why")) }
        }
    }.getOrDefault(emptyList())

    private fun saveTrash(ctx: Context, list: List<Trashed>) {
        val monthAgo = System.currentTimeMillis() - 30L * 24 * 3600_000L
        val arr = JSONArray()
        list.filter { it.deletedAt > monthAgo }.sortedByDescending { it.deletedAt }.take(300).forEach { x ->
            arr.put(JSONObject().put("trip", tripToJson(x.trip)).put("at", x.deletedAt).put("why", x.reason))
        }
        p(ctx).edit().putString("tripTrash", arr.toString()).apply()
    }

    /** 기록을 지우지 않고 '삭제된 기록'으로 옮긴다 (30일 보관) */
    private fun moveToTrash(ctx: Context, gone: List<Trip>, reason: String) {
        if (gone.isEmpty()) return
        val now = System.currentTimeMillis()
        saveTrash(ctx, gone.map { Trashed(it, now, reason) } + trash(ctx))
    }

    fun restoreTrip(ctx: Context, id: Long) {
        val all = trash(ctx)
        val x = all.firstOrNull { it.trip.id == id } ?: return
        saveTrips(ctx, trips(ctx).filter { it.id != id } + x.trip)
        saveTrash(ctx, all.filter { it.trip.id != id })
    }

    fun purgeTrash(ctx: Context, id: Long) = saveTrash(ctx, trash(ctx).filter { it.trip.id != id })

    /** 배차취소·거부된 콜: 자동운행기록에서 빼고, 받은 콜 목록에선 '수락 전'으로 되돌린다 */
    fun unrecordCall(ctx: Context, key: String) {
        val (gone, keep) = trips(ctx).partition { it.callKey == key && System.currentTimeMillis() - it.time < 3600_000L }
        moveToTrash(ctx, gone, "배차취소·거부 감지")
        saveTrips(ctx, keep)
        writeHistory(ctx, history(ctx).map { if (it.key == key) it.copy(accepted = false) else it })
        current(ctx)?.let { if (it.key == key) p(ctx).edit().putString("current", toJson(it.copy(accepted = false)).toString()).apply() }
    }

    /** 지금 콜의 경유지를 더 자세한 주소로 바꾼다 (자동운행기록의 같은 콜도) */
    fun updateVia(ctx: Context, key: String, via: String) {
        current(ctx)?.let { if (it.key == key) p(ctx).edit().putString("current", toJson(it.copy(via = via)).toString()).apply() }
        writeHistory(ctx, history(ctx).map { if (it.key == key) it.copy(via = via) else it })
        val tr = trips(ctx)
        if (tr.any { it.callKey == key && !it.confirmed }) saveTrips(ctx, tr.map { if (it.callKey == key && !it.confirmed) it.copy(via = via) else it })
    }

    /** 콜 받은 곳 저장: 지금 콜·받은 콜 목록·(이미 기록됐으면) 운행일지까지. 이미 있으면 덮어쓰지 않음 */
    fun setRecvLoc(ctx: Context, key: String, area: String, lat: Double, lng: Double) {
        fun CallInfo.fill() = if (key == this.key && recvLat == 0.0) copy(recvArea = area, recvLat = lat, recvLng = lng) else this
        current(ctx)?.let { c -> val n = c.fill(); if (n !== c) p(ctx).edit().putString("current", toJson(n).toString()).apply() }
        val hist = history(ctx)
        if (hist.any { it.key == key && it.recvLat == 0.0 }) writeHistory(ctx, hist.map { it.fill() })
        synchronized(tripLock) {
            val tr = trips(ctx)
            if (tr.any { it.callKey == key && it.recvLat == 0.0 })
                saveTrips(ctx, tr.map { if (it.callKey == key && it.recvLat == 0.0) it.copy(recvArea = area, recvLat = lat, recvLng = lng) else it })
        }
    }

    fun upsertTrip(ctx: Context, t: Trip) = synchronized(tripLock) { saveTrips(ctx, trips(ctx).filter { it.id != t.id } + t) }
    fun deleteTrip(ctx: Context, id: Long) = synchronized(tripLock) {
        val (gone, keep) = trips(ctx).partition { it.id == id }
        moveToTrash(ctx, gone, "직접 삭제")
        saveTrips(ctx, keep)
    }

    /** 수락된 콜(상세배차정보·배정완료)을 읽으면 운행일지에 자동 기록. 같은 콜은 12시간 안에 한 번만. */
    /** 대리 요금이 이보다 낮으면 콜이 아닌 화면을 잘못 읽은 것으로 보고 자동으로 넣지 않는다 */
    const val MIN_FARE = 5000

    private fun recordAccepted(ctx: Context, c: CallInfo) = synchronized(tripLock) {
        if (CallParser.won(c.fare) in 1 until MIN_FARE) { setStatus(ctx, "⛔ 기록 안 함 (요금 ${c.fare} 너무 낮음): ${c.originDisplay}"); return }
        val list = trips(ctx)
        if (list.any { it.callKey == c.key && abs(it.time - c.capturedAt) < 12 * 3600_000L }) { setStatus(ctx, "ℹ️ 이미 기록된 콜: ${c.originDisplay} → ${c.destDisplay}"); return }
        val fare = CallParser.won(c.fare)
        val deposit = CallParser.won(c.deposit)
        upsertTrip(ctx, Trip(
            id = c.capturedAt, time = c.capturedAt,
            origin = c.originDisplay, via = c.via, dest = c.destDisplay, program = programName(c.source),
            fare = fare,
            // 입금액(로지)이 있으면 그게 수수료, 없으면 설정한 수수료율로 계산
            commission = if (deposit > 0) deposit else fare * ctx.commissionRate / 100,
            callKey = c.key, confirmed = ctx.autoToLog, pkg = c.pkg, kind = if (c.isTakson) "탁송" else "대리",
            recvArea = c.recvArea, recvLat = c.recvLat, recvLng = c.recvLng, solo = c.solo,
        ))
    }

    /**
     * 예전 버전이 대리 앱이 아닌 화면(채팅 등)을 콜로 잘못 읽어 남긴 기록을 지운다.
     * 앱 이름을 못 찾아 'com.xxx.yyy' 같은 패키지 이름이 출처로 남은 것들.
     */
    fun cleanupJunk(ctx: Context) {
        // 대리 앱 이름이나 직접 입력한 것만 진짜 콜로 인정하고, 나머지(채팅앱 등에서 잘못 읽은 것)는 지운다
        val okNames = listOf("로지", "콜마너", "카카오", "대리", "탁송", "기사", "직접 입력", "공유", "붙여넣기", "배차정보", "콜 수락 화면", "드라이버", "드라이브", "커넥트", "Driver", "driver", "T맵", "TMAP", "tmap")
        fun bad(src: String) = src.isBlank() || okNames.none { it in src } ||
            "anthropic" in src.lowercase() || "claude" in src.lowercase()
        val hist = history(ctx)
        val keepHist = hist.filterNot { bad(it.source) || CallParser.isMenuText(it.originRaw) || CallParser.isMenuText(it.destRaw) }
        if (keepHist.size != hist.size) writeHistory(ctx, keepHist)
        // 운행일지는 자동 청소에서 지우지 않는다. 단, 일지에 안 넣은 자동 카드 중 요금이 5,000원 미만인
        // 잘못 잡힌 것만 휴지통으로 옮긴다 (되살릴 수 있음)
        synchronized(tripLock) {
            val tr = trips(ctx)
            val (bogus, keep) = tr.partition { !it.confirmed && it.callKey.isNotEmpty() && it.fare in 1 until MIN_FARE }
            if (bogus.isNotEmpty()) { moveToTrash(ctx, bogus, "요금이 너무 낮아 잘못 잡힌 것으로 보임"); saveTrips(ctx, keep) }
        }
        current(ctx)?.let { if (bad(it.source)) p(ctx).edit().remove("current").apply() }
        val dumps = runCatching { JSONArray(p(ctx).getString("dumps", "[]")) }.getOrDefault(JSONArray())
        val keepDumps = JSONArray()
        for (i in 0 until dumps.length()) dumps.getJSONObject(i).let { if (!bad(it.optString("app"))) keepDumps.put(it) }
        p(ctx).edit().putString("dumps", keepDumps.toString()).apply()
    }

    /** 대리 앱 이름을 일지용 짧은 이름으로 */
    fun programName(source: String) = when {
        "카카오" in source -> "카카오대리"
        "t맵" in source.lowercase() || "tmap" in source.lowercase() -> "T맵대리"
        "로지" in source -> "로지"
        "콜마너" in source -> "콜마너"
        "driver" in source.lowercase() -> "I-Driver"
        "드라이브" in source || "idrive" in source.lowercase().replace("-", "").replace(" ", "") -> "아이드라이브"
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
        get() = p(this).getString("kakaoKey", "").orEmpty().ifBlank { BuiltIn.rest }
        set(v) = p(this).edit().putString("kakaoKey", v).apply()

    /** 카카오 JavaScript 키 — 콜 상세 지도를 카카오 지도로 (없으면 OpenStreetMap) */
    var Context.kakaoJsKey: String
        get() = p(this).getString("kakaoJsKey", "").orEmpty().ifBlank { BuiltIn.js }
        set(v) = p(this).edit().putString("kakaoJsKey", v.trim()).apply()

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

    /** 새 콜로 말풍선이 바뀔 때 진동 (기본 켜짐) */
    var Context.bubbleVibrate: Boolean
        get() = p(this).getBoolean("bubbleVibrate", true)
        set(v) = p(this).edit().putBoolean("bubbleVibrate", v).apply()

    /** 하루가 바뀌는 시각(시). 대리운전은 밤을 넘기므로 기본 새벽 6시 — 그 전까지는 전날 운행으로 계산 */
    var Context.dayStartHour: Int
        get() = p(this).getInt("dayStartHour", 6)
        set(v) = p(this).edit().putInt("dayStartHour", v).apply()

    /** 월고정비: 보험료·통신비처럼 한 달에 한 번 나가는 돈 (월 합계 지출에 더함) */
    var Context.monthFixed: Int
        get() = p(this).getInt("monthFixed", 0)
        set(v) = p(this).edit().putInt("monthFixed", v.coerceAtLeast(0)).apply()

    /** 일고정비: 프로그램 일비처럼 운행한 날마다 나가는 돈 (그날 합계 지출에 더함) */
    var Context.dayFixed: Int
        get() = p(this).getInt("dayFixed", 0)
        set(v) = p(this).edit().putInt("dayFixed", v.coerceAtLeast(0)).apply()

    /** 수락한 콜을 바로 운행일지에 넣을지 (기본 켜짐). 끄면 자동운행기록에서 [일지에 추가]로 넣는다 */
    var Context.autoToLog: Boolean
        get() = p(this).getBoolean("autoToLog", true)
        set(v) = p(this).edit().putBoolean("autoToLog", v).apply()

    var Context.detectPaused: Boolean
        // 출근 누르기 전까지는 콜을 읽지 않는다 (기본 = 퇴근 상태)
        get() = p(this).getBoolean("paused", true)
        set(v) = p(this).edit().putBoolean("paused", v).apply()

    /** 출근 누른 시각 (퇴근 리포트·시급 계산용) */
    var Context.workStart: Long
        get() = p(this).getLong("workStart", 0L)
        set(v) = p(this).edit().putLong("workStart", v).apply()

    // ---------------- 읽은 화면 기록 (문제 해결용) ----------------
    fun addDump(ctx: Context, app: String, texts: List<String>) {
        val body = texts.joinToString("\n") { "• " + it.replace("\n", " ⏎ ") }.take(3000)
        val arr = runCatching { JSONArray(p(ctx).getString("dumps", "[]")) }.getOrDefault(JSONArray())
        // 숫자만 바뀐 같은 화면(카카오 '남은시간 30분 35초' 대기 화면 등)은 한 번만 — 중요한 화면이 밀려나지 않게
        fun shape(b: String) = b.replace(Regex("""\d+"""), "#")
        for (i in 0 until minOf(arr.length(), 5)) {
            val o = arr.getJSONObject(i)
            if (o.optString("app") == app && shape(o.optString("body")) == shape(body)) return
        }
        val out = JSONArray().put(JSONObject().put("app", app).put("at", System.currentTimeMillis()).put("body", body))
        for (i in 0 until minOf(arr.length(), MAX_DUMPS - 1)) out.put(arr.getJSONObject(i))
        p(ctx).edit().putString("dumps", out.toString()).apply()
    }

    fun dumps(ctx: Context): List<Triple<String, Long, String>> = runCatching {
        val arr = JSONArray(p(ctx).getString("dumps", "[]"))
        (0 until arr.length()).map { arr.getJSONObject(it).let { o -> Triple(o.optString("app"), o.optLong("at"), o.optString("body")) } }
    }.getOrDefault(emptyList())

    /** 마지막 읽기 결과 (문제 해결용) */
    fun setStatus(ctx: Context, msg0: String) {
        val msg = if (msg0.firstOrNull()?.isDigit() == true) msg0 else java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.KOREA).format(java.util.Date()) + " " + msg0
        // 지난 기록도 남긴다 (최근 50줄) — 콜이 왜 기록 안 됐는지 찾을 때 씀
        val old = p(ctx).getString("statusLog", "").orEmpty()
        val log = (msg + "\n" + old).lineSequence().take(50).joinToString("\n")
        p(ctx).edit().putString("status", msg).putString("statusLog", log).apply()
    }
    fun statusLog(ctx: Context): String = p(ctx).getString("statusLog", "").orEmpty()
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
        put("accepted", c.accepted); put("deposit", c.deposit); put("pkg", c.pkg); put("auto", c.autoDispatch); put("rec", c.recommended); put("partner", c.partner); put("via", c.via)
        put("walkMin", c.walkMin); put("walkKm", c.walkKm); put("driveMin", c.driveMin)
        put("recvArea", c.recvArea); put("recvLat", c.recvLat); put("recvLng", c.recvLng); put("solo", c.solo)
        put("phones", JSONArray().apply { c.phones.forEach { put(JSONArray(listOf(it.first, it.second))) } })
    }

    private fun JSONArray.strings() = (0 until length()).map { getString(it) }

    private fun fromJson(o: JSONObject) = CallInfo(
        source = o.optString("source"), originRaw = o.optString("originRaw"), destRaw = o.optString("destRaw"),
        originQueries = o.optJSONArray("oq")?.strings().orEmpty(),
        destQueries = o.optJSONArray("dq")?.strings().orEmpty(),
        fare = o.optString("fare"), notes = o.optString("notes"), capturedAt = o.optLong("at"),
        accepted = o.optBoolean("accepted"), deposit = o.optString("deposit"), pkg = o.optString("pkg"),
        autoDispatch = o.optBoolean("auto"), recommended = o.optBoolean("rec"), partner = o.optBoolean("partner"), via = o.optString("via"),
        walkMin = o.optInt("walkMin"), walkKm = o.optDouble("walkKm", 0.0), driveMin = o.optInt("driveMin"),
        recvArea = o.optString("recvArea"), recvLat = o.optDouble("recvLat", 0.0), recvLng = o.optDouble("recvLng", 0.0),
        solo = o.optString("solo"),
        phones = o.optJSONArray("phones")?.let { a ->
            (0 until a.length()).map { a.getJSONArray(it).let { p -> p.getString(0) to p.getString(1) } }
        }.orEmpty(),
    )
}
