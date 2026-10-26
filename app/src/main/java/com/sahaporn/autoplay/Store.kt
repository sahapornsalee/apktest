package com.sahaporn.autoplay

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Skip times left null fall back to the series-wide values. Progress is kept per clip. */
data class Episode(
    val uri: String,
    var name: String,
    var introStart: Int? = null,
    var introEnd: Int? = null,
    var outroLength: Int? = null,
    var position: Long = 0L,
    var duration: Long = 0L,
    var watched: Boolean = false,
) {
    val hasOverride: Boolean get() = introStart != null || introEnd != null || outroLength != null

    /** Where to start this clip: its saved spot, or the beginning once it was finished. */
    val resumePosition: Long get() = if (watched) 0L else position

    val hasProgress: Boolean get() = watched || position >= 5_000

    /** Sub-folder the clip came from (e.g. "Season 2"), "" when it sat in the chosen folder. */
    val season: String get() = name.substringBeforeLast('/', "")

    /** File name without its sub-folder. */
    val shortName: String get() = name.substringAfterLast('/')

    fun clearOverride() {
        introStart = null
        introEnd = null
        outroLength = null
    }

    fun resetProgress() {
        position = 0L
        watched = false
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("uri", uri)
        put("name", name)
        introStart?.let { put("introStart", it) }
        introEnd?.let { put("introEnd", it) }
        outroLength?.let { put("outroLength", it) }
        put("position", position)
        put("duration", duration)
        put("watched", watched)
    }

    companion object {
        fun fromJson(o: JSONObject, fallbackName: String) = Episode(
            uri = o.getString("uri"),
            name = o.optString("name", fallbackName),
            introStart = if (o.has("introStart")) o.getInt("introStart") else null,
            introEnd = if (o.has("introEnd")) o.getInt("introEnd") else null,
            outroLength = if (o.has("outroLength")) o.getInt("outroLength") else null,
            position = o.optLong("position", 0L),
            duration = o.optLong("duration", 0L),
            watched = o.optBoolean("watched", false),
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
    var autoPlay: Boolean = true,
    var countdown: Boolean = false,
    var skipManual: Boolean = false,
    var pinned: Boolean = false,
    var orientation: Int = 0,
    var resizeMode: Int = 0,
    var speed: Float = 1f,
    var audioLang: String? = null,
    var textLang: String? = null,
    var textOff: Boolean = false,
    var engine: Int = Engines.AUTO,
    var vlcHardware: Boolean = true,
    var lastIndex: Int = 0,
    var lastWatchedAt: Long = 0L,
) {
    fun introStartOf(ep: Episode) = ep.introStart ?: introStart
    fun introEndOf(ep: Episode) = ep.introEnd ?: introEnd
    fun outroOf(ep: Episode) = ep.outroLength ?: outroLength

    val watchedCount: Int get() = episodes.count { it.watched }

    /** Sub-folders in playing order; more than one means the series has seasons. */
    fun seasons(): List<String> = episodes.map { it.season }.distinct()

    /**
     * The clip "continue watching" should open: the last one played if unfinished,
     * otherwise the next unfinished one. Null when every clip has been watched.
     */
    fun resumeIndex(): Int? {
        if (episodes.isEmpty()) return null
        val last = lastIndex.coerceIn(0, episodes.size - 1)
        if (!episodes[last].watched) return last
        val order = (last + 1 until episodes.size) + (0 until last)
        return order.firstOrNull { !episodes[it].watched }
    }

    /** Saves a clip's spot; near the end (or past the outro) it counts as watched. */
    fun recordProgress(ep: Episode, position: Long, duration: Long, ended: Boolean) {
        if (duration > 0) ep.duration = duration
        val d = ep.duration
        val outro = outroOf(ep) * 1000L
        val nearEnd = ended || (d > 0 && (position >= d * 95 / 100 ||
            (skipOutro && outro > 0 && position >= d - outro - 1_500)))
        if (nearEnd) {
            ep.watched = true
            ep.position = 0L
        } else {
            ep.position = position
            if (position >= 5_000) ep.watched = false
        }
    }

    fun resetProgress() {
        episodes.forEach { it.resetProgress() }
        lastIndex = 0
    }

    /** Edits the episode list while keeping "continue watching" on the same file. */
    fun editEpisodes(block: (MutableList<Episode>) -> Unit) {
        val current = episodes.getOrNull(lastIndex)?.uri
        block(episodes)
        val idx = episodes.indexOfFirst { it.uri == current }
        lastIndex = idx.coerceAtLeast(0)
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
        put("autoPlay", autoPlay)
        put("countdown", countdown)
        put("skipManual", skipManual)
        put("pinned", pinned)
        put("orientation", orientation)
        put("resizeMode", resizeMode)
        put("speed", speed.toDouble())
        audioLang?.let { put("audioLang", it) }
        textLang?.let { put("textLang", it) }
        put("textOff", textOff)
        put("engine", engine)
        put("vlcHardware", vlcHardware)
        put("lastIndex", lastIndex)
        put("lastWatchedAt", lastWatchedAt)
        put("episodes", JSONArray().apply { episodes.forEach { put(it.toJson()) } })
    }

    companion object {
        fun fromJson(o: JSONObject): Series {
            val arr = o.optJSONArray("episodes") ?: JSONArray()
            val eps = MutableList(arr.length()) { Episode.fromJson(arr.getJSONObject(it), "ตอนที่ ${it + 1}") }
            val lastIndex = o.optInt("lastIndex", 0)
            // Older versions kept a single position per series; move it onto that clip.
            val legacyPosition = o.optLong("lastPosition", 0L)
            if (legacyPosition > 0 && eps.none { it.position > 0 }) eps.getOrNull(lastIndex)?.position = legacyPosition
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
                autoPlay = o.optBoolean("autoPlay", true),
                countdown = o.optBoolean("countdown", false),
                skipManual = o.optBoolean("skipManual", false),
                pinned = o.optBoolean("pinned", false),
                orientation = o.optInt("orientation", 0),
                resizeMode = o.optInt("resizeMode", 0),
                speed = o.optDouble("speed", 1.0).toFloat(),
                audioLang = if (o.has("audioLang")) o.getString("audioLang") else null,
                textLang = if (o.has("textLang")) o.getString("textLang") else null,
                textOff = o.optBoolean("textOff", false),
                engine = o.optInt("engine", Engines.AUTO),
                vlcHardware = o.optBoolean("vlcHardware", true),
                lastIndex = lastIndex,
                lastWatchedAt = o.optLong("lastWatchedAt", 0L),
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

    /** The series played most recently, if any has been played. */
    fun latest(ctx: Context): Series? =
        loadAll(ctx).filter { it.lastWatchedAt > 0 && it.episodes.isNotEmpty() }.maxByOrNull { it.lastWatchedAt }

    fun resetAllProgress(ctx: Context) {
        saveAll(ctx, loadAll(ctx).onEach { it.resetProgress() })
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

/**
 * Which player opens a series: the main one (ExoPlayer, full features), Android's own player
 * (same as the phone's video app) or VLC (plays nearly any file).
 */
object Engines {
    const val AUTO = 0
    const val MAIN = 1
    const val VLC = 2
    const val SYSTEM = 3

    val NAMES = listOf(
        "อัตโนมัติ (เลือกให้ตามชนิดไฟล์)",
        "ตัวเล่นหลัก (ฟีเจอร์ครบ)",
        "ตัวเล่นสำรอง VLC (เปิดได้แทบทุกไฟล์)",
        "ตัวเล่นของระบบ (แบบเดียวกับแอปวิดีโอในเครื่อง)",
    )

    /** Formats the main player handles poorly or not at all. */
    private val VLC_EXT = setOf(
        "avi", "divx", "xvid", "wmv", "asf", "flv", "f4v", "rm", "rmvb", "vob", "mpg", "mpeg", "mpe",
        "m1v", "m2v", "mxf", "dv", "ogm", "amv", "mts", "m2ts", "m2t", "tp", "trp",
    )

    fun needsVlc(ep: Episode) = ep.name.substringAfterLast('.', "").lowercase() in VLC_EXT

    /** The backup engine ([VLC] or [SYSTEM]) a clip should open in, or null for the main player. */
    fun backupFor(s: Series, index: Int): Int? = when (s.engine) {
        VLC -> VLC
        SYSTEM -> SYSTEM
        MAIN -> null
        else -> if (s.episodes.getOrNull(index)?.let { needsVlc(it) } == true) VLC else null
    }
}

/** App-wide preferences that are not tied to one series. */
object Settings {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun backgroundAudio(ctx: Context) = prefs(ctx).getBoolean("background_audio", true)
    fun setBackgroundAudio(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("background_audio", on).apply()

    fun autoPip(ctx: Context) = prefs(ctx).getBoolean("auto_pip", false)
    fun setAutoPip(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("auto_pip", on).apply()
}

object TimeText {
    fun format(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    fun format(seconds: Int): String = format(seconds.toLong())

    fun seasonLabel(season: String): String = season.substringAfterLast('/').ifEmpty { "ตอนหลัก" }

    fun ms(millis: Long): String = format(millis.coerceAtLeast(0L) / 1000)

    /** Percent watched for a progress bar, or -1 when there is nothing to show. */
    fun percent(ep: Episode): Int = when {
        ep.watched -> 100
        ep.position >= 5_000 && ep.duration > 0 -> (ep.position * 100 / ep.duration).toInt().coerceIn(1, 100)
        else -> -1
    }

    /** "12:34 / 45:00 (27%)" style progress for a clip. */
    fun progress(ep: Episode): String {
        if (ep.watched) return "✓ ดูจบแล้ว"
        if (ep.position < 5_000) return "ยังไม่ได้ดู"
        if (ep.duration <= 0) return "⏸ ค้างที่ ${ms(ep.position)}"
        val pct = (ep.position * 100 / ep.duration).coerceIn(0, 100)
        return "⏸ ค้างที่ ${ms(ep.position)} / ${ms(ep.duration)} ($pct%)"
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
