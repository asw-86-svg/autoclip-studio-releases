package com.daeri.navi

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 현재 콜, 최근 콜 기록, 설정값을 휴대폰 안에만 저장한다. */
object Store {
    private const val PREFS = "daeri"
    private const val MAX_HISTORY = 30

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun current(ctx: Context): CallInfo? =
        p(ctx).getString("current", null)?.let { runCatching { fromJson(JSONObject(it)) }.getOrNull() }

    fun setCurrent(ctx: Context, c: CallInfo?) {
        if (c == null) { p(ctx).edit().remove("current").apply(); return }
        val hist = history(ctx).filter { it.key != c.key }.toMutableList()
        hist.add(0, c)
        val arr = JSONArray().apply { hist.take(MAX_HISTORY).forEach { put(toJson(it)) } }
        p(ctx).edit().putString("current", toJson(c).toString()).putString("history", arr.toString()).apply()
    }

    fun history(ctx: Context): List<CallInfo> = runCatching {
        val arr = JSONArray(p(ctx).getString("history", "[]"))
        (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun clearHistory(ctx: Context) = p(ctx).edit().remove("history").apply()

    var Context.homeAddress: String
        get() = p(this).getString("home", "").orEmpty()
        set(v) = p(this).edit().putString("home", v).apply()

    var Context.kakaoKey: String
        get() = p(this).getString("kakaoKey", "").orEmpty()
        set(v) = p(this).edit().putString("kakaoKey", v).apply()

    var Context.detectPaused: Boolean
        get() = p(this).getBoolean("paused", false)
        set(v) = p(this).edit().putBoolean("paused", v).apply()

    fun saveDump(ctx: Context, pkg: String, texts: List<String>) {
        val body = "[$pkg]\n" + texts.joinToString("\n") { "• " + it.replace("\n", " ⏎ ") }
        p(ctx).edit().putString("dump", body).apply()
    }

    fun dump(ctx: Context): String = p(ctx).getString("dump", "").orEmpty()

    fun bubblePos(ctx: Context): Pair<Int, Int> = p(ctx).getInt("bx", 0) to p(ctx).getInt("by", 400)
    fun setBubblePos(ctx: Context, x: Int, y: Int) = p(ctx).edit().putInt("bx", x).putInt("by", y).apply()

    private fun toJson(c: CallInfo) = JSONObject().apply {
        put("source", c.source); put("originRaw", c.originRaw); put("destRaw", c.destRaw)
        put("oq", JSONArray(c.originQueries)); put("dq", JSONArray(c.destQueries))
        put("fare", c.fare); put("notes", c.notes); put("at", c.capturedAt)
        put("phones", JSONArray().apply { c.phones.forEach { put(JSONArray(listOf(it.first, it.second))) } })
    }

    private fun JSONArray.strings() = (0 until length()).map { getString(it) }

    private fun fromJson(o: JSONObject) = CallInfo(
        source = o.optString("source"), originRaw = o.optString("originRaw"), destRaw = o.optString("destRaw"),
        originQueries = o.optJSONArray("oq")?.strings().orEmpty(),
        destQueries = o.optJSONArray("dq")?.strings().orEmpty(),
        fare = o.optString("fare"), notes = o.optString("notes"), capturedAt = o.optLong("at"),
        phones = o.optJSONArray("phones")?.let { a ->
            (0 until a.length()).map { a.getJSONArray(it).let { p -> p.getString(0) to p.getString(1) } }
        }.orEmpty(),
    )
}
