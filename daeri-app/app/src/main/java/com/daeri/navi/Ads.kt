package com.daeri.navi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 직접 받는 광고 (업체에 광고비를 받고 거는 배너).
 * 광고 목록은 깃허브의 daeri-app/ads/ads.json 에서 가져온다 → 앱을 다시 설치하지 않아도 광고를 바꿀 수 있다.
 * 광고가 하나도 없으면 홈 화면에 광고 칸이 보이지 않는다.
 */
object Ads {
    const val URL_JSON = "https://raw.githubusercontent.com/asw-86-svg/autoclip-studio-releases/claude/proxy-driving-app-yqzrsn/daeri-app/ads/ads.json"
    private const val PREFS = "ads"
    private const val REFRESH_MS = 60 * 60_000L   // 1시간마다 새로 확인

    data class Ad(
        val id: String,
        val title: String,          // 큰 글씨 (업체 이름 등)
        val text: String,           // 작은 설명
        val link: String,           // 누르면: "tel:01012345678" 전화, "https://…" 웹, "sms:…" 문자
        val button: String,         // 오른쪽 버튼 글자 (예: "전화", "보기")
        val image: String,          // 배너 그림 주소 (있으면 그림으로 보여줌, 가로로 긴 그림)
        val bg: Int?,               // 글자 광고 배경색
        val weight: Int,            // 여러 광고일 때 보여주는 비율
    )

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 저장해 둔 광고 목록 중 지금 보여줄 수 있는 것 */
    fun list(ctx: Context): List<Ad> {
        val json = p(ctx).getString("json", null) ?: return emptyList()
        return parse(json)
    }

    fun parse(json: String): List<Ad> = runCatching { parseRaw(json) }.getOrDefault(emptyList())

    private fun parseRaw(json: String): List<Ad> {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(Date())
        val arr = JSONObject(json).optJSONArray("ads") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            if (o.optBoolean("off", false)) return@mapNotNull null
            // 광고 기간: from ~ until (날짜 글자 비교)
            o.optString("from").takeIf { it.isNotBlank() }?.let { if (today < it) return@mapNotNull null }
            o.optString("until").takeIf { it.isNotBlank() }?.let { if (today > it) return@mapNotNull null }
            val title = o.optString("title"); val image = o.optString("image")
            if (title.isBlank() && image.isBlank()) return@mapNotNull null
            Ad(
                id = o.optString("id").ifBlank { "ad$i" },
                title = title, text = o.optString("text"), link = o.optString("link"),
                button = o.optString("button"), image = image,
                bg = o.optString("bg").takeIf { it.startsWith("#") }?.let { runCatching { android.graphics.Color.parseColor(it) }.getOrNull() },
                weight = o.optInt("weight", 1).coerceAtLeast(1),
            )
        }
    }

    /** 이번에 보여줄 광고 하나 (비율대로 번갈아) */
    fun pick(ctx: Context): Ad? {
        val ads = list(ctx)
        if (ads.isEmpty()) return null
        val n = p(ctx).getInt("turn", 0)
        p(ctx).edit().putInt("turn", n + 1).apply()
        val slots = ads.flatMap { a -> List(a.weight) { a } }
        return slots[n % slots.size]
    }

    /** 배너 그림 (내려받아 둔 것) */
    fun cachedImage(ctx: Context, url: String): Bitmap? {
        val f = imgFile(ctx, url)
        return if (f.exists()) BitmapFactory.decodeFile(f.path) else null
    }

    private fun imgFile(ctx: Context, url: String): File {
        val h = MessageDigest.getInstance("MD5").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(File(ctx.filesDir, "ads").apply { mkdirs() }, "$h.img")
    }

    /** 광고 목록과 그림을 새로 받아온다 (백그라운드). 바뀌었으면 onChanged 호출 */
    fun refresh(ctx: Context, force: Boolean = false, onChanged: () -> Unit) {
        val last = p(ctx).getLong("fetched", 0L)
        if (!force && System.currentTimeMillis() - last < REFRESH_MS) return
        Thread {
            runCatching {
                val body = get(URL_JSON).toString(Charsets.UTF_8)
                JSONObject(body)   // 형식이 깨진 파일이면 저장하지 않음
                val old = p(ctx).getString("json", null)
                p(ctx).edit().putString("json", body).putLong("fetched", System.currentTimeMillis()).apply()
                var changed = old != body
                for (a in parse(body)) {
                    if (a.image.isBlank()) continue
                    val f = imgFile(ctx, a.image)
                    if (!f.exists()) runCatching { f.writeBytes(get(a.image)); changed = true }
                }
                if (changed) onChanged()
            }
        }.start()
    }

    private fun get(url: String): ByteArray {
        val con = URL(url).openConnection() as HttpURLConnection
        con.connectTimeout = 8000; con.readTimeout = 8000
        con.setRequestProperty("Cache-Control", "no-cache")
        return con.inputStream.use { it.readBytes() }
    }

    /** 테스트·미리보기용: 광고 목록을 직접 넣는다 */
    fun setJson(ctx: Context, json: String) = p(ctx).edit().putString("json", json).putLong("fetched", System.currentTimeMillis()).apply()
}
