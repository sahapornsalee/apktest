package com.sahaporn.autoplay

import android.graphics.Typeface
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar

/** Series detail: big play button, quick actions and the episode list, split by season. */
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
    private lateinit var txtSkipInfo: TextView
    private lateinit var btnResume: Button
    private lateinit var progressResume: ProgressBar
    private lateinit var txtResumeNote: TextView
    private lateinit var swAutoPlay: SwitchCompat
    private lateinit var swCountdown: SwitchCompat
    private lateinit var btnSeason: Button
    private lateinit var adapter: SimpleAdapter<Int>

    /** Season shown in the list; null when the series has no seasons. */
    private var season: String? = null

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            MediaScan.persist(this, uri)
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
        txtSkipInfo = findViewById(R.id.txtSkipInfo)
        btnResume = findViewById(R.id.btnResume)
        progressResume = findViewById(R.id.progressResume)
        txtResumeNote = findViewById(R.id.txtResumeNote)
        swAutoPlay = findViewById(R.id.swAutoPlay)
        swCountdown = findViewById(R.id.swCountdown)
        btnSeason = findViewById(R.id.btnSeason)

        btnResume.setOnClickListener { Resume.play(this, series) }
        findViewById<View>(R.id.btnRestart).setOnClickListener { Resume.confirmReset(this, series) { render() } }
        findViewById<View>(R.id.btnSkip).setOnClickListener { showSkipDialog() }
        findViewById<View>(R.id.btnAdd).setOnClickListener { showAddDialog() }
        findViewById<View>(R.id.btnMoreActions).setOnClickListener { showMoreMenu() }
        btnSeason.setOnClickListener { chooseSeason() }

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
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId != MENU_MORE) return super.onOptionsItemSelected(item)
        showMoreMenu()
        return true
    }

    private fun showMoreMenu() {
        Dialogs.menu(
            this, series.name, listOf(
                "＋ เพิ่มตอน" to { showAddDialog() },
                "⏭ ตั้งค่าข้ามเพลง (ทุกตอน)" to { showSkipDialog() },
                (if (series.pinned) "เลิกปักหมุด" else "📌 ปักหมุดไว้บนสุด") to {
                    series.pinned = !series.pinned
                    save()
                },
                "เปลี่ยนชื่อเรื่อง" to {
                    Dialogs.askText(this, "เปลี่ยนชื่อเรื่อง", series.name) { name ->
                        series.name = name
                        save()
                    }
                },
                "ย้ายหมวดหมู่" to {
                    Dialogs.chooseCategory(this, series.category) { cat ->
                        series.category = cat
                        save()
                    }
                },
                "🎬 ตัวเล่น: ${Engines.NAMES[series.engine.coerceIn(0, Engines.NAMES.size - 1)]}" to { chooseEngine() },
                "เรียงตอนตามชื่อไฟล์" to {
                    series.editEpisodes { eps ->
                        val sorted = MediaScan.sorted(eps)
                        eps.clear()
                        eps.addAll(sorted)
                    }
                    save()
                },
                "↺ รีเซ็ตการดูทั้งเรื่อง" to { Resume.confirmReset(this, series) { render() } },
                "🎨 ธีมและสี" to { AppTheme.showPicker(this) },
                "ลบเรื่องนี้" to {
                    Dialogs.confirm(this, "ลบ “${series.name}” ออกจากรายการ?\n(ไฟล์วิดีโอในเครื่องจะไม่ถูกลบ)", "ลบ") {
                        Store.delete(this, series.id)
                        finish()
                    }
                },
            )
        )
    }

    private fun chooseEngine() {
        Dialogs.menu(this, "เลือกตัวเล่น", Engines.NAMES.mapIndexed { i, name ->
            (if (i == series.engine) "✓ $name" else name) to {
                series.engine = i
                save()
            }
        })
    }

    private fun save() {
        Store.put(this, series)
        render()
    }

    private fun render() {
        title = series.name
        txtHeroTitle.text = series.name
        if (series.lastIndex >= series.episodes.size) series.lastIndex = 0

        val idx = series.resumeIndex()
        val heroEp = series.episodes.getOrNull(idx ?: 0)
        if (heroEp != null) Thumbs.load(this, heroEp.uri, imgHero) else imgHero.setImageDrawable(null)

        val cat = series.category.ifEmpty { "ไม่มีหมวดหมู่" }
        val seasons = series.seasons()
        val seasonNote = if (seasons.size > 1) " · ${seasons.size} ภาค" else ""
        txtInfo.text = "📁 $cat$seasonNote · ${series.episodes.size} ตอน · ดูจบแล้ว ${series.watchedCount}"

        btnResume.isEnabled = series.episodes.isNotEmpty()
        btnResume.text = Resume.label(series)
        val resumeEp = idx?.let { series.episodes[it] }
        if (idx != null && resumeEp != null && resumeEp.hasProgress && resumeEp.duration > 0) {
            progressResume.visibility = View.VISIBLE
            progressResume.progress = TimeText.percent(resumeEp).coerceAtLeast(0)
            txtResumeNote.visibility = View.VISIBLE
            txtResumeNote.text = "ตอนที่ ${idx + 1} · เหลืออีก ${TimeText.ms(resumeEp.duration - resumeEp.position)}"
        } else {
            progressResume.visibility = View.GONE
            txtResumeNote.visibility = View.GONE
        }

        txtSkipInfo.text = skipSummary()
        swAutoPlay.setOnCheckedChangeListener(null)
        swAutoPlay.isChecked = series.autoPlay
        swAutoPlay.setOnCheckedChangeListener { _, checked ->
            series.autoPlay = checked
            Store.put(this, series)
            toast(if (checked) "จบตอนแล้วไปตอนต่อไปเลย" else "จบตอนแล้วจะหยุดรอ")
        }
        swCountdown.setOnCheckedChangeListener(null)
        swCountdown.isChecked = series.countdown
        swCountdown.setOnCheckedChangeListener { _, checked ->
            series.countdown = checked
            Store.put(this, series)
            toast(if (checked) "จะนับถอยหลัง 5 วินาทีก่อนไปตอนต่อไป" else "ไปตอนต่อไปทันที")
        }

        if (seasons.size > 1) {
            if (season !in seasons) season = heroEp?.season ?: seasons.first()
            btnSeason.visibility = View.VISIBLE
            btnSeason.text = "${TimeText.seasonLabel(season.orEmpty())} ▾"
        } else {
            season = null
            btnSeason.visibility = View.GONE
        }
        adapter.items = series.episodes.indices.filter { season == null || series.episodes[it].season == season }
    }

    private fun skipSummary(): String {
        val intro = when {
            !series.skipIntro -> "ไม่ข้ามเพลงเปิด"
            series.introEnd > series.introStart ->
                "ข้ามเพลงเปิด ${TimeText.format(series.introStart)}–${TimeText.format(series.introEnd)}"
            else -> "ยังไม่ได้ตั้งเวลาเพลงเปิด"
        }
        val outro = when {
            !series.skipOutro -> "ไม่ข้ามเพลงปิด"
            series.outroLength > 0 -> "ข้ามเพลงปิด ${TimeText.format(series.outroLength)} สุดท้าย"
            else -> "ยังไม่ได้ตั้งเวลาเพลงปิด"
        }
        val mode = if (series.skipManual) " · แบบกดปุ่มเอง" else ""
        return "$intro · $outro$mode"
    }

    private fun chooseSeason() {
        Dialogs.menu(this, "เลือกภาค", series.seasons().map { s ->
            val count = series.episodes.count { it.season == s }
            "${if (s == season) "✓ " else ""}${TimeText.seasonLabel(s)} ($count ตอน)" to {
                season = s
                render()
            }
        })
    }

    private fun bindEpisode(v: View, index: Int) {
        val ep = series.episodes[index]
        Thumbs.load(this, ep.uri, v.findViewById(R.id.imgThumb))
        val current = index == series.lastIndex && series.lastWatchedAt > 0
        v.findViewById<TextView>(R.id.txtTitle).apply {
            text = "${index + 1}. ${ep.shortName}"
            setTypeface(null, if (current) Typeface.BOLD else Typeface.NORMAL)
        }
        val notes = mutableListOf<String>()
        if (current) notes.add("👁 ดูล่าสุด")
        notes.add(TimeText.progress(ep))
        if (ep.duration > 0 && !ep.hasProgress) notes.add(TimeText.ms(ep.duration))
        if (ep.hasOverride) notes.add("⚙ เวลาข้ามเฉพาะตอน")
        v.findViewById<TextView>(R.id.txtSubtitle).text = notes.joinToString(" · ")
        v.findViewById<ProgressBar>(R.id.progress).showPercent(TimeText.percent(ep))
        v.setOnClickListener { playEpisode(index) }
        v.setOnLongClickListener {
            episodeMenu(index)
            true
        }
        v.findViewById<View>(R.id.btnMore).setOnClickListener { episodeMenu(index) }
    }

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
            this, "${index + 1}. ${ep.shortName}", listOf(
                "▶ ดูต่อจาก ${TimeText.ms(ep.position)}" to { play(index, ep.position) },
                "⏮ เริ่มตั้งแต่ต้นตอน" to { startOver(index) },
            )
        )
    }

    private fun startOver(index: Int) {
        series.episodes[index].resetProgress()
        Store.put(this, series)
        play(index, 0L)
    }

    private fun episodeMenu(index: Int) {
        val ep = series.episodes[index]
        val items = mutableListOf<Pair<CharSequence, () -> Unit>>()
        if (!ep.watched && ep.position >= 5_000) {
            items.add("▶ ดูต่อจาก ${TimeText.ms(ep.position)}" to { play(index, ep.position) })
        }
        items.add("⏮ เล่นตั้งแต่ต้นตอน" to { startOver(index) })
        items.add("↺ รีเซ็ตการดูตอนนี้" to {
            ep.resetProgress()
            save()
            toast("รีเซ็ตตอนนี้แล้ว")
        })
        if (ep.watched) {
            items.add("ทำเครื่องหมายว่ายังไม่ได้ดู" to {
                ep.watched = false
                save()
            })
        } else {
            items.add("✓ ทำเครื่องหมายว่าดูแล้ว" to {
                ep.watched = true
                ep.position = 0L
                save()
            })
        }
        items.add("เปลี่ยนชื่อตอน" to {
            Dialogs.askText(this, "เปลี่ยนชื่อตอน", ep.name) { name ->
                ep.name = name
                save()
            }
        })
        items.add("ตั้งเวลาข้ามเพลงเฉพาะตอนนี้" to { showEpisodeSkipDialog(ep) })
        items.add("เลื่อนขึ้น" to { moveEpisode(index, -1) })
        items.add("เลื่อนลง" to { moveEpisode(index, 1) })
        items.add("ลบตอนนี้ออกจากรายการ" to {
            Dialogs.confirm(this, "เอา “${ep.shortName}” ออกจากรายการ?", "เอาออก") {
                series.editEpisodes { it.removeAt(index) }
                save()
            }
        })
        Dialogs.menu(this, "${index + 1}. ${ep.shortName}", items)
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

    private fun showAddDialog() {
        Dialogs.menu(
            this, "เพิ่มตอน", listOf(
                "เพิ่มจากโฟลเดอร์" to { pickFolder.launch(null) },
                "เพิ่มไฟล์วิดีโอ" to { pickFiles.launch(arrayOf("*/*")) },
            )
        )
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
                series.editEpisodes { it.addAll(fresh) }
                save()
                toast("เพิ่ม ${fresh.size} ตอนต่อท้ายแล้ว (เรียงใหม่ได้จากเมนู ⋯)")
            }
        }.start()
    }
}
