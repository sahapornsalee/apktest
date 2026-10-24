package com.sahaporn.autoplay

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Skip times left null fall back to the series-wide values. */
data class Episode(
    val uri: String,
    var name: String,
    var introStart: Int? = null,
    var introEnd: Int? = null,
    var outroLength: Int? = null,
) {
    val hasOverride: Boolean get() = introStart != null || introEnd != null || outroLength != null

    fun clearOverride() {
        introStart = null
        introEnd = null
        outroLength = null
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("uri", uri)
        put("name", name)
        introStart?.let { put("introStart", it) }
        introEnd?.let { put("introEnd", it) }
        outroLength?.let { put("outroLength", it) }
    }

    companion object {
        fun fromJson(o: JSONObject, fallbackName: String) = Episode(
            uri = o.getString("uri"),
            name = o.optString("name", fallbackName),
            introStart = if (o.has("introStart")) o.getInt("introStart") else null,
            introEnd = if (o.has("introEnd")) o.getInt("introEnd") else null,
            outroLength = if (o.has("outroLength")) o.getInt("outroLength") else null,
        )
    }
}

/** A series is an ordered list of episodes that play back to back, with shared skip settings. */
class Series(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var category: String = "",
    val episodes: MutableList<Episode> = mutableListOf(),
    var introStart: Int = 0,
    var introEnd: Int = 0,
    var outroLength: Int = 0,
    var skipIntro: Boolean = true,
    var skipOutro: Boolean = true,
    var lastIndex: Int = 0,
    var lastPosition: Long = 0L,
) {
    fun introStartOf(ep: Episode) = ep.introStart ?: introStart
    fun introEndOf(ep: Episode) = ep.introEnd ?: introEnd
    fun outroOf(ep: Episode) = ep.outroLength ?: outroLength

    /** Edits the episode list while keeping "continue watching" on the same file. */
    fun editEpisodes(block: (MutableList<Episode>) -> Unit) {
        val current = episodes.getOrNull(lastIndex)?.uri
        block(episodes)
        val idx = episodes.indexOfFirst { it.uri == current }
        if (idx >= 0) {
            lastIndex = idx
        } else {
            lastIndex = 0
            lastPosition = 0
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("category", category)
        put("introStart", introStart)
        put("introEnd", introEnd)
        put("outroLength", outroLength)
        put("skipIntro", skipIntro)
        put("skipOutro", skipOutro)
        put("lastIndex", lastIndex)
        put("lastPosition", lastPosition)
        put("episodes", JSONArray().apply { episodes.forEach { put(it.toJson()) } })
    }

    companion object {
        fun fromJson(o: JSONObject): Series {
            val arr = o.optJSONArray("episodes") ?: JSONArray()
            val eps = MutableList(arr.length()) { Episode.fromJson(arr.getJSONObject(it), "ตอนที่ ${it + 1}") }
            return Series(
                id = o.getString("id"),
                name = o.optString("name", "ไม่มีชื่อ"),
                category = o.optString("category", ""),
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
    private const val KEY_CATEGORIES = "categories"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun loadAll(ctx: Context): MutableList<Series> {
        val raw = prefs(ctx).getString(KEY, null) ?: return mutableListOf()
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
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
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

    /** Saved categories in display order, plus any a series still points at. */
    fun categories(ctx: Context): MutableList<String> {
        val list = mutableListOf<String>()
        try {
            val arr = JSONArray(prefs(ctx).getString(KEY_CATEGORIES, "[]"))
            for (i in 0 until arr.length()) list.add(arr.getString(i))
        } catch (e: Exception) {
            // Corrupt list: rebuild from the series below.
        }
        loadAll(ctx).map { it.category }.filter { it.isNotEmpty() && it !in list }.distinct().forEach { list.add(it) }
        return list
    }

    private fun saveCategories(ctx: Context, list: List<String>) {
        prefs(ctx).edit().putString(KEY_CATEGORIES, JSONArray(list).toString()).apply()
    }

    fun addCategory(ctx: Context, name: String): Boolean {
        val list = categories(ctx)
        if (name.isEmpty() || name in list) return false
        list.add(name)
        saveCategories(ctx, list)
        return true
    }

    fun renameCategory(ctx: Context, old: String, new: String): Boolean {
        val list = categories(ctx)
        if (new.isEmpty() || new in list) return false
        val idx = list.indexOf(old)
        if (idx >= 0) list[idx] = new else list.add(new)
        saveCategories(ctx, list)
        saveAll(ctx, loadAll(ctx).onEach { if (it.category == old) it.category = new })
        return true
    }

    /** Removes the category; its series stay, without a category. */
    fun deleteCategory(ctx: Context, name: String) {
        saveCategories(ctx, categories(ctx).filter { it != name })
        saveAll(ctx, loadAll(ctx).onEach { if (it.category == name) it.category = "" })
    }

    fun moveCategory(ctx: Context, name: String, delta: Int) {
        val list = categories(ctx)
        val i = list.indexOf(name)
        val j = i + delta
        if (i < 0 || j !in list.indices) return
        list.add(j, list.removeAt(i))
        saveCategories(ctx, list)
    }
}

object TimeText {
    fun format(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    fun format(seconds: Int): String = format(seconds.toLong())

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
