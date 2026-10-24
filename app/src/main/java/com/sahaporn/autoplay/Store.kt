package com.sahaporn.autoplay

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Episode(val uri: String, val name: String)

/** A series is an ordered list of episodes that play back to back, with shared skip settings. */
class Series(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    val episodes: MutableList<Episode> = mutableListOf(),
    var introStart: Int = 0,
    var introEnd: Int = 0,
    var outroLength: Int = 0,
    var skipIntro: Boolean = true,
    var skipOutro: Boolean = true,
    var lastIndex: Int = 0,
    var lastPosition: Long = 0L,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("introStart", introStart)
        put("introEnd", introEnd)
        put("outroLength", outroLength)
        put("skipIntro", skipIntro)
        put("skipOutro", skipOutro)
        put("lastIndex", lastIndex)
        put("lastPosition", lastPosition)
        put("episodes", JSONArray().apply {
            episodes.forEach { put(JSONObject().put("uri", it.uri).put("name", it.name)) }
        })
    }

    companion object {
        fun fromJson(o: JSONObject): Series {
            val eps = mutableListOf<Episode>()
            val arr = o.optJSONArray("episodes") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val e = arr.getJSONObject(i)
                eps.add(Episode(e.getString("uri"), e.optString("name", "ตอนที่ ${i + 1}")))
            }
            return Series(
                id = o.getString("id"),
                name = o.optString("name", "ไม่มีชื่อ"),
                episodes = eps,
                introStart = o.optInt("introStart", 0),
                introEnd = o.optInt("introEnd", 0),
                outroLength = o.optInt("outroLength", 0),
                skipIntro = o.optBoolean("skipIntro", true),
                skipOutro = o.optBoolean("skipOutro", true),
                lastIndex = o.optInt("lastIndex", 0),
                lastPosition = o.optLong("lastPosition", 0L),
            )
        }
    }
}

object Store {
    private const val PREFS = "autoplay"
    private const val KEY = "series"

    fun loadAll(ctx: Context): MutableList<Series> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return mutableListOf()
        return try {
            val arr = JSONArray(raw)
            MutableList(arr.length()) { Series.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    private fun saveAll(ctx: Context, list: List<Series>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    fun get(ctx: Context, id: String): Series? = loadAll(ctx).firstOrNull { it.id == id }

    fun put(ctx: Context, series: Series) {
        val list = loadAll(ctx)
        val idx = list.indexOfFirst { it.id == series.id }
        if (idx >= 0) list[idx] = series else list.add(series)
        saveAll(ctx, list)
    }

    fun delete(ctx: Context, id: String) {
        saveAll(ctx, loadAll(ctx).filter { it.id != id })
    }
}

object TimeText {
    fun format(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /** Parses "90", "1:30" or "1:02:03" into seconds; blank means 0, invalid means null. */
    fun parse(text: String): Int? {
        val t = text.trim()
        if (t.isEmpty()) return 0
        val parts = t.split(":", ".")
        if (parts.size > 3) return null
        var total = 0
        for (p in parts) {
            val n = p.trim().toIntOrNull() ?: return null
            if (n < 0) return null
            total = total * 60 + n
        }
        return total
    }
}
