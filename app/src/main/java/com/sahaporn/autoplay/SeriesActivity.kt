package com.sahaporn.autoplay

import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors

/** Series page, Netflix-style: big picture and Play button, a few actions, then the episodes by season. */
class SeriesActivity : ThemedActivity() {
    companion object {
        const val EXTRA_ID = "series_id"
        private const val MENU_MORE = 1
    }

    private lateinit var seriesId: String
    private lateinit var series: Series
    private lateinit var imgHero: ImageView
    private lateinit var txtHeroTitle: TextView
    private lateinit var txtInfo: TextView
    private lateinit var btnResume: Button
    private lateinit var progressResume: ProgressBar
    private lateinit var txtResumeNote: TextView
    private lateinit var txtPlaySummary: TextView
    private lateinit var seasonScroll: View
    private lateinit var seasonChips: ChipGroup
    private lateinit var adapter: SimpleAdapter<Int>

    /** Season shown in the list; null when the series has no seasons. */
    private var season: String? = null

    /** Short episode names, worked out once per render. */
    private var labels: List<String> = emptyList()
    private var durationsFilled = false

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            MediaScan.persist(this, uri)
            if (series.folderUri == null) series.folderUri = uri.toString()
            addEpisodes { MediaScan.scanTree(this, uri) }
        }
    }

    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { MediaScan.persist(this, it) }
            addEpisodes { MediaScan.fromUris(this, uris) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_series)
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        setupBars(findViewById(R.id.root), toolbar)

        seriesId = intent.getStringExtra(EXTRA_ID) ?: run { finish(); return }
        imgHero = findViewById(R.id.imgHero)
        txtHeroTitle = findViewById(R.id.txtHeroTitle)
        txtInfo = findViewById(R.id.txtInfo)
        btnResume = findViewById(R.id.btnResume)
        progressResume = findViewById(R.id.progressResume)
        txtResumeNote = findViewById(R.id.txtResumeNote)
        txtPlaySummary = findViewById(R.id.txtPlaySummary)
        seasonScroll = findViewById(R.id.seasonScroll)
        seasonChips = findViewById(R.id.seasonChips)

        btnResume.setOnClickListener { Resume.play(this, series) }
        findViewById<View>(R.id.btnRestart).setOnClickListener { Resume.confirmReset(this, series) { render() } }
        findViewById<View>(R.id.btnPlaySettings).setOnClickListener { showPlaySettings() }
        findViewById<View>(R.id.btnAdd).setOnClickListener { showAddDialog() }
        findViewById<View>(R.id.btnMoreActions).setOnClickListener { showMoreMenu() }

        adapter = SimpleAdapter(R.layout.item_episode) { v, i -> bindEpisode(v, i) }
        findViewById<RecyclerView>(R.id.rvEpisodes).apply {
            layoutManager = LinearLayoutManager(this@SeriesActivity)
            isNestedScrollingEnabled = false
            adapter = this@SeriesActivity.adapter
        }
    }

    override fun onResume() {
        super.onResume()
        series = Store.get(this, seriesId) ?: run { finish(); return }
        render()
        fillDurations()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_MORE, 0, "อื่นๆ").apply {
            setIcon(R.drawable.ic_more)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        menu.tintIcons(this)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId != MENU_MORE) return super.onOptionsItemSelected(item)
        showMoreMenu()
        return true
    }

    private fun save() {
        Store.put(this, series)
        render()
    }

    // ---- Page ----

    private fun render() {
        // The big title on the picture names the series; the bar above stays clean.
        title = ""
        txtHeroTitle.text = series.name
        if (series.lastIndex >= series.episodes.size) series.lastIndex = 0
        labels = series.episodeLabels()

        val idx = series.resumeIndex()
        val heroEp = series.episodes.getOrNull(idx ?: 0)
        if (heroEp != null) Thumbs.load(this, heroEp.uri, imgHero) else imgHero.setImageDrawable(null)

        val seasons = series.seasons()
        val parts = mutableListOf<String>()
        if (series.category.isNotEmpty()) parts.add(series.category)
        if (seasons.size > 1) parts.add("${seasons.size} ภาค")
        parts.add("${series.episodes.size} ตอน")
        if (series.watchedCount > 0) parts.add("ดูจบ ${series.watchedCount}")
        txtInfo.text = parts.joinToString("  ·  ")

        btnResume.isEnabled = series.episodes.isNotEmpty()
        btnResume.text = resumeText(idx)
        val resumeEp = idx?.let { series.episodes[it] }
        if (idx != null && resumeEp != null && resumeEp.hasProgress && resumeEp.duration > 0) {
            progressResume.visibility = View.VISIBLE
            progressResume.progress = TimeText.percent(resumeEp).coerceAtLeast(0)
            txtResumeNote.visibility = View.VISIBLE
            txtResumeNote.text = "${series.seasonPrefix(idx)}${labels[idx]} · เหลืออีก ${TimeText.ms(resumeEp.duration - resumeEp.position)}"
        } else {
            progressResume.visibility = View.GONE
            txtResumeNote.visibility = View.GONE
        }
        txtPlaySummary.text = playSummary()

        if (seasons.size > 1) {
            if (season !in seasons) season = heroEp?.season ?: seasons.first()
            seasonScroll.visibility = View.VISIBLE
            seasonChips.removeAllViews()
            seasons.forEach { s ->
                val chip = layoutInflater.inflate(R.layout.item_chip, seasonChips, false) as Chip
                chip.text = TimeText.seasonLabel(s)
                chip.isChecked = s == season
                chip.setOnClickListener {
                    season = s
                    render()
                }
                seasonChips.addView(chip)
            }
        } else {
            season = null
            seasonScroll.visibility = View.GONE
        }
        adapter.items = series.episodes.indices.filter { season == null || series.episodes[it].season == season }
    }

    private fun resumeText(idx: Int?): String {
        if (idx == null) return "▶  ดูครบแล้ว · เริ่มดูใหม่"
        val ep = series.episodes[idx]
        if (series.watchedCount == 0 && idx == 0 && !ep.hasProgress) return "▶  เล่น"
        return "▶  ดูต่อ · ${series.seasonPrefix(idx)}${labels[idx]}"
    }

    /** One line saying how this series plays: auto-play, skips, player. */
    private fun playSummary(): String {
        val parts = mutableListOf<String>()
        parts.add(if (series.autoPlay) "เล่นต่ออัตโนมัติ" else "จบตอนแล้วหยุด")
        if (series.skipIntro && series.introEnd > series.introStart) parts.add("ข้ามเพลงเปิด")
        if (series.skipOutro && series.outroLength > 0) parts.add("ข้ามเพลงปิด")
        if (series.engine != Engines.AUTO) parts.add(Engines.shortName(series.engine))
        return "⚙ " + parts.joinToString(" · ")
    }

    private fun bindEpisode(v: View, i: Int) {
        val ep = series.episodes[i]
        Thumbs.load(this, ep.uri, v.findViewById(R.id.imgThumb))
        val current = i == series.lastIndex && series.lastWatchedAt > 0 && !ep.watched
        v.findViewById<TextView>(R.id.txtTitle).apply {
            text = labels.getOrElse(i) { ep.shortName }
            // Accent colour marks the episode being watched; reset it for recycled rows.
            setTextColor(
                MaterialColors.getColor(
                    this, if (current) androidx.appcompat.R.attr.colorPrimary else android.R.attr.textColorPrimary
                )
            )
        }
        val notes = mutableListOf<String>()
        when {
            ep.watched -> notes.add("✓ ดูจบแล้ว")
            ep.position >= 5_000 && ep.duration > 0 -> notes.add("เหลืออีก ${TimeText.ms(ep.duration - ep.position)}")
            ep.position >= 5_000 -> notes.add("ค้างที่ ${TimeText.ms(ep.position)}")
        }
        if (current) notes.add(0, "▶ ดูล่าสุด")
        if (ep.hasOverride) notes.add("⚙ เวลาข้ามเฉพาะตอน")
        v.findViewById<TextView>(R.id.txtSubtitle).apply {
            text = notes.joinToString(" · ")
            visibility = if (notes.isEmpty()) View.GONE else View.VISIBLE
        }
        v.findViewById<TextView>(R.id.txtDuration).apply {
            visibility = if (ep.duration > 0) View.VISIBLE else View.GONE
            text = minutes(ep.duration)
        }
        v.findViewById<ProgressBar>(R.id.progress).showPercent(TimeText.percent(ep))
        v.setOnClickListener { playEpisode(i) }
        v.setOnLongClickListener {
            episodeMenu(i)
            true
        }
        v.findViewById<View>(R.id.btnMore).setOnClickListener { episodeMenu(i) }
    }

    private fun minutes(ms: Long): String {
        val m = ms / 60_000
        return if (m >= 60) "${m / 60} ชม. ${m % 60} น." else "$m นาที"
    }

    /** Reads clip lengths in the background, so unwatched episodes can show theirs too. */
    private fun fillDurations() {
        if (durationsFilled) return
        durationsFilled = true
        val missing = series.episodes.filter { it.duration <= 0 }.map { it.uri }
        if (missing.isEmpty()) return
        val app = applicationContext
        Thread {
            val found = mutableMapOf<String, Long>()
            for (uri in missing) {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(app, Uri.parse(uri))
                    r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                        ?.takeIf { it > 0 }?.let { found[uri] = it }
                } catch (e: Exception) {
                    // Unreadable here; the player fills it in when the clip is played.
                } finally {
                    try {
                        r.release()
                    } catch (e: Exception) {
                        // Nothing left to clean up.
                    }
                }
            }
            if (found.isEmpty()) return@Thread
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                val fresh = Store.get(this, seriesId) ?: return@runOnUiThread
                fresh.episodes.forEach { ep -> if (ep.duration <= 0) found[ep.uri]?.let { ep.duration = it } }
                Store.put(this, fresh)
                series = fresh
                render()
            }
        }.start()
    }

    // ---- Playing ----

    private fun play(index: Int, position: Long) {
        if (series.episodes.isEmpty()) return
        PlayerActivity.start(this, series.id, index, position)
    }

    /** Tapping a clip resumes its own saved spot, or asks when there is one. */
    private fun playEpisode(index: Int) {
        val ep = series.episodes[index]
        if (ep.watched || ep.position < 5_000) {
            play(index, 0L)
            return
        }
        Dialogs.menu(
            this, labels.getOrElse(index) { ep.shortName }, listOf(
                "▶  ดูต่อจาก ${TimeText.ms(ep.position)}" to { play(index, ep.position) },
                "⏮  เริ่มตั้งแต่ต้นตอน" to { startOver(index) },
            )
        )
    }

    private fun startOver(index: Int) {
        series.episodes[index].resetProgress()
        Store.put(this, series)
        play(index, 0L)
    }

    // ---- Menus ----

    private fun check(on: Boolean) = if (on) "✅" else "⬜"

    /** Playback settings for this series, reopened after each change so several can be set in a row. */
    private fun showPlaySettings() {
        fun toggle(change: () -> Unit) {
            change()
            save()
            showPlaySettings()
        }
        Dialogs.menu(
            this, "ตั้งค่าการเล่น · ${series.name}", listOf(
                "${check(series.autoPlay)}  เล่นตอนต่อไปอัตโนมัติ" to { toggle { series.autoPlay = !series.autoPlay } },
                "${check(series.countdown)}  นับถอยหลัง 5 วินาทีก่อนไปตอนต่อไป" to {
                    toggle { series.countdown = !series.countdown }
                },
                "⏭  ข้ามเพลงเปิด / ปิด: ${skipSummary()}" to { showSkipDialog() },
                "🎬  ตัวเล่น: ${Engines.name(series.engine)}" to { chooseEngine() },
            )
        )
    }

    private fun skipSummary(): String {
        val intro = when {
            !series.skipIntro -> "ไม่ข้ามเพลงเปิด"
            series.introEnd > series.introStart ->
                "เปิด ${TimeText.format(series.introStart)}–${TimeText.format(series.introEnd)}"
            else -> "ยังไม่ตั้งเพลงเปิด"
        }
        val outro = when {
            !series.skipOutro -> "ไม่ข้ามเพลงปิด"
            series.outroLength > 0 -> "ปิด ${TimeText.format(series.outroLength)} สุดท้าย"
            else -> "ยังไม่ตั้งเพลงปิด"
        }
        return "$intro · $outro" + if (series.skipManual) " · กดปุ่มเอง" else ""
    }

    private fun showMoreMenu() {
        Dialogs.menu(
            this, series.name, listOf(
                (if (series.pinned) "📌  เลิกปักหมุด" else "📌  ปักหมุดไว้บนสุด") to {
                    series.pinned = !series.pinned
                    save()
                },
                "✏️  เปลี่ยนชื่อเรื่อง" to {
                    Dialogs.askText(this, "เปลี่ยนชื่อเรื่อง", series.name) { name ->
                        series.name = name
                        save()
                    }
                },
                "📁  ย้ายหมวดหมู่" to {
                    Dialogs.chooseCategory(this, series.category) { cat ->
                        series.category = cat
                        save()
                    }
                },
                "🔤  เรียงตอนตามชื่อไฟล์" to {
                    series.editEpisodes { eps ->
                        val sorted = MediaScan.sorted(eps)
                        eps.clear()
                        eps.addAll(sorted)
                    }
                    save()
                },
                Dialogs.danger(this, "↺  รีเซ็ตการดูทั้งเรื่อง") to { Resume.confirmReset(this, series) { render() } },
                Dialogs.danger(this, "🗑  ลบเรื่องนี้") to {
                    Dialogs.confirm(
                        this, "ลบ “${series.name}”?\nเอาออกจากแอปเท่านั้น ไฟล์วิดีโอในเครื่องยังอยู่ครบ",
                        "ลบเรื่องนี้", destructive = true,
                    ) {
                        Store.delete(this, series.id)
                        finish()
                    }
                },
            )
        )
    }

    private fun chooseEngine() {
        Dialogs.choose(this, "ตัวเล่นสำหรับเรื่องนี้", Engines.NAMES, series.engine) { i ->
            series.engine = i
            if (i == Engines.VLC) series.vlcHardware = false
            save()
        }
    }

    private fun episodeMenu(index: Int) {
        val ep = series.episodes[index]
        val items = mutableListOf<Pair<CharSequence, () -> Unit>>()
        if (!ep.watched && ep.position >= 5_000) {
            items.add("▶  ดูต่อจาก ${TimeText.ms(ep.position)}" to { play(index, ep.position) })
        }
        items.add("⏮  เล่นตั้งแต่ต้นตอน" to { startOver(index) })
        if (ep.watched) {
            items.add("○  ทำเครื่องหมายว่ายังไม่ได้ดู" to {
                ep.watched = false
                save()
            })
        } else {
            items.add("✓  ทำเครื่องหมายว่าดูแล้ว" to {
                ep.watched = true
                ep.position = 0L
                save()
            })
        }
        items.add("✏️  เปลี่ยนชื่อตอน" to {
            Dialogs.askText(this, "เปลี่ยนชื่อตอน", ep.shortName) { name ->
                ep.name = if (ep.season.isEmpty()) name else "${ep.season}/$name"
                save()
            }
        })
        items.add("⏭  ตั้งเวลาข้ามเพลงเฉพาะตอนนี้" to { showEpisodeSkipDialog(ep) })
        items.add("⬆  เลื่อนขึ้น" to { moveEpisode(index, -1) })
        items.add("⬇  เลื่อนลง" to { moveEpisode(index, 1) })
        items.add(Dialogs.danger(this, "↺  รีเซ็ตการดูตอนนี้") to {
            ep.resetProgress()
            save()
            toast("รีเซ็ตตอนนี้แล้ว")
        })
        items.add(Dialogs.danger(this, "🗑  ลบตอนนี้ออกจากรายการ") to {
            Dialogs.confirm(
                this, "ลบ “${labels.getOrElse(index) { ep.shortName }}”?\nเอาออกจากรายการเท่านั้น ไฟล์วิดีโอในเครื่องยังอยู่ (${ep.shortName})",
                "ลบตอนนี้", destructive = true,
            ) {
                series.editEpisodes { it.removeAt(index) }
                save()
            }
        })
        Dialogs.menu(this, "${series.seasonPrefix(index)}${labels.getOrElse(index) { ep.shortName }}\n${ep.shortName}", items)
    }

    private fun moveEpisode(index: Int, delta: Int) {
        val target = index + delta
        if (target !in series.episodes.indices) return
        series.editEpisodes { it.add(target, it.removeAt(index)) }
        save()
    }

    private fun showSkipDialog() {
        val initial = SkipValues(
            series.introStart, series.introEnd, series.outroLength, series.skipIntro, series.skipOutro,
            series.skipManual,
        )
        Dialogs.skipSettings(this, "ข้ามเพลงเปิด/ปิด (ทุกตอน)", initial, showFlags = true, onReset = null) { v ->
            series.introStart = v.introStart
            series.introEnd = v.introEnd
            series.outroLength = v.outro
            series.skipIntro = v.skipIntro
            series.skipOutro = v.skipOutro
            series.skipManual = v.skipManual
            save()
            toast("บันทึกแล้ว ใช้กับทุกตอน")
        }
    }

    private fun showEpisodeSkipDialog(ep: Episode) {
        val initial = SkipValues(
            series.introStartOf(ep), series.introEndOf(ep), series.outroOf(ep), series.skipIntro, series.skipOutro,
        )
        Dialogs.skipSettings(
            this, "ข้ามเพลงเฉพาะ: ${ep.shortName}", initial, showFlags = false,
            onReset = {
                ep.clearOverride()
                save()
                toast("กลับไปใช้ค่าของเรื่องแล้ว")
            },
        ) { v ->
            ep.introStart = v.introStart
            ep.introEnd = v.introEnd
            ep.outroLength = v.outro
            save()
            toast("บันทึกแล้ว เฉพาะตอนนี้")
        }
    }

    // ---- Adding episodes ----

    private fun showAddDialog() {
        val items = mutableListOf<Pair<CharSequence, () -> Unit>>()
        if (series.folderUri != null) items.add("🔄  สแกนโฟลเดอร์เดิมหาตอนใหม่" to { rescan() })
        items.add("📁  เพิ่มจากโฟลเดอร์" to { pickFolder.launch(null) })
        items.add("🎞  เพิ่มไฟล์วิดีโอ" to { pickFiles.launch(arrayOf("*/*")) })
        Dialogs.menu(this, "เพิ่มตอน", items)
    }

    /** Looks in the series' folder again and adds any new episodes. */
    private fun rescan() {
        val folder = series.folderUri ?: return
        addEpisodes { MediaScan.scanTree(this, Uri.parse(folder)) }
    }

    private fun addEpisodes(scan: () -> List<Episode>) {
        toast("กำลังค้นหาวิดีโอ…")
        Thread {
            val found = scan()
            runOnUiThread {
                val known = series.episodes.map { it.uri }.toSet()
                val fresh = found.filter { it.uri !in known }
                if (fresh.isEmpty()) {
                    toast("ไม่พบวิดีโอใหม่")
                    return@runOnUiThread
                }
                // Keep a list that was in file-name order in that order; otherwise append.
                val wasSorted = series.episodes.map { it.uri } == MediaScan.sorted(series.episodes).map { it.uri }
                series.editEpisodes { eps ->
                    eps.addAll(fresh)
                    if (wasSorted) {
                        val sorted = MediaScan.sorted(eps)
                        eps.clear()
                        eps.addAll(sorted)
                    }
                }
                save()
                durationsFilled = false
                fillDurations()
                toast("เพิ่มตอนใหม่ ${fresh.size} ตอนแล้ว")
            }
        }.start()
    }
}
