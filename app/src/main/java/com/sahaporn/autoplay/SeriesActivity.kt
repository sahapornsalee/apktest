package com.sahaporn.autoplay

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.appbar.MaterialToolbar

class SeriesActivity : ThemedActivity() {
    companion object {
        const val EXTRA_ID = "series_id"
        private const val MENU_ADD = 1
        private const val MENU_SKIP = 2
        private const val MENU_RENAME = 3
        private const val MENU_CATEGORY = 4
        private const val MENU_SORT = 5
        private const val MENU_RESET = 6
        private const val MENU_DELETE = 7
        private const val MENU_THEME = 8
    }

    private lateinit var seriesId: String
    private lateinit var series: Series
    private lateinit var txtInfo: TextView
    private lateinit var btnResume: Button
    private lateinit var swAutoPlay: SwitchCompat
    private lateinit var swCountdown: SwitchCompat
    private lateinit var adapter: RowAdapter

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
        txtInfo = findViewById(R.id.txtInfo)
        btnResume = findViewById(R.id.btnResume)
        swAutoPlay = findViewById(R.id.swAutoPlay)
        swCountdown = findViewById(R.id.swCountdown)
        adapter = RowAdapter(this)
        findViewById<ListView>(R.id.list).adapter = adapter

        btnResume.setOnClickListener { Resume.play(this, series) }
        findViewById<Button>(R.id.btnResetAll).setOnClickListener { Resume.confirmReset(this, series) { render() } }
        findViewById<Button>(R.id.btnSkip).setOnClickListener { showSkipDialog() }
        findViewById<Button>(R.id.btnAdd).setOnClickListener { showAddDialog() }
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
        menu.add(0, MENU_ADD, 0, "เพิ่มตอน")
        menu.add(0, MENU_SKIP, 1, "ตั้งค่าข้ามเพลง (ทุกตอน)")
        menu.add(0, MENU_RENAME, 2, "เปลี่ยนชื่อเรื่อง")
        menu.add(0, MENU_CATEGORY, 3, "ย้ายหมวดหมู่")
        menu.add(0, MENU_SORT, 4, "เรียงตอนตามชื่อไฟล์")
        menu.add(0, MENU_RESET, 5, "รีเซ็ตการดูทั้งเรื่อง")
        menu.add(0, MENU_THEME, 6, "🎨 ธีมและสี")
        menu.add(0, MENU_DELETE, 7, "ลบเรื่องนี้")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            MENU_ADD -> showAddDialog()
            MENU_THEME -> AppTheme.showPicker(this)
            MENU_SKIP -> showSkipDialog()
            MENU_RENAME -> Dialogs.askText(this, "เปลี่ยนชื่อเรื่อง", series.name) { name ->
                series.name = name
                save()
            }
            MENU_CATEGORY -> Dialogs.chooseCategory(this, series.category) { cat ->
                series.category = cat
                save()
            }
            MENU_SORT -> {
                series.editEpisodes { eps ->
                    val sorted = MediaScan.sorted(eps)
                    eps.clear()
                    eps.addAll(sorted)
                }
                save()
            }
            MENU_RESET -> Resume.confirmReset(this, series) { render() }
            MENU_DELETE -> Dialogs.confirm(this, "ลบ “${series.name}” ออกจากรายการ?\n(ไฟล์วิดีโอในเครื่องจะไม่ถูกลบ)", "ลบ") {
                Store.delete(this, series.id)
                finish()
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun save() {
        Store.put(this, series)
        render()
    }

    private fun render() {
        title = series.name
        val intro = if (series.skipIntro && series.introEnd > series.introStart) {
            "ข้ามเพลงเปิด ${TimeText.format(series.introStart)}–${TimeText.format(series.introEnd)}"
        } else if (series.skipIntro) {
            "ยังไม่ได้ตั้งเวลาเพลงเปิด"
        } else {
            "ปิดการข้ามเพลงเปิด"
        }
        val outro = if (series.skipOutro && series.outroLength > 0) {
            "ข้ามเพลงปิด ${TimeText.format(series.outroLength)} สุดท้าย"
        } else if (series.skipOutro) {
            "ยังไม่ได้ตั้งเวลาเพลงปิด"
        } else {
            "ปิดการข้ามเพลงปิด"
        }
        val mode = if (series.skipManual) " · แสดงปุ่มให้กดข้ามเอง" else ""
        val cat = series.category.ifEmpty { "ไม่มีหมวดหมู่" }
        txtInfo.text = "📁 $cat · ${series.episodes.size} ตอน · ดูจบแล้ว ${series.watchedCount} ตอน\n$intro\n$outro$mode"
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

        if (series.lastIndex >= series.episodes.size) series.lastIndex = 0
        btnResume.isEnabled = series.episodes.isNotEmpty()
        btnResume.text = Resume.label(series)

        adapter.rows = series.episodes.mapIndexed { i, e ->
            val notes = mutableListOf<String>()
            if (i == series.lastIndex && series.lastWatchedAt > 0) notes.add("👁 ดูล่าสุด")
            notes.add(TimeText.progress(e))
            if (e.hasOverride) notes.add("⚙ ตั้งเวลาข้ามเฉพาะตอนนี้")
            Row(
                title = "${i + 1}. ${e.name}",
                subtitle = notes.joinToString(" · "),
                highlight = i == series.lastIndex,
                thumb = e.uri,
                progress = TimeText.percent(e),
                onMore = { episodeMenu(i) },
            ) { playEpisode(i) }
        }
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
            this, "${index + 1}. ${ep.name}", listOf(
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
        val items = mutableListOf<Pair<String, () -> Unit>>()
        if (!ep.watched && ep.position >= 5_000) {
            items.add("▶ ดูต่อจาก ${TimeText.ms(ep.position)}" to { play(index, ep.position) })
        }
        items.add("⏮ เล่นตั้งแต่ต้นตอน" to { startOver(index) })
        items.add("↺ รีเซ็ตการดูตอนนี้" to {
            ep.resetProgress()
            save()
            toast("รีเซ็ตตอนนี้แล้ว")
        })
        items.add(
            if (ep.watched) {
                "ทำเครื่องหมายว่ายังไม่ได้ดู" to {
                    ep.watched = false
                    save()
                }
            } else {
                "✓ ทำเครื่องหมายว่าดูแล้ว" to {
                    ep.watched = true
                    ep.position = 0L
                    save()
                }
            }
        )
        Dialogs.menu(
            this, "${index + 1}. ${ep.name}", items + listOf(
                "เปลี่ยนชื่อตอน" to {
                    Dialogs.askText(this, "เปลี่ยนชื่อตอน", ep.name) { name ->
                        ep.name = name
                        save()
                    }
                },
                "ตั้งเวลาข้ามเพลงเฉพาะตอนนี้" to { showEpisodeSkipDialog(ep) },
                "เลื่อนขึ้น" to { moveEpisode(index, -1) },
                "เลื่อนลง" to { moveEpisode(index, 1) },
                "ลบตอนนี้ออกจากรายการ" to {
                    Dialogs.confirm(this, "เอา “${ep.name}” ออกจากรายการ?", "เอาออก") {
                        series.editEpisodes { it.removeAt(index) }
                        save()
                    }
                },
            )
        )
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
            this, "ข้ามเพลงเฉพาะ: ${ep.name}", initial, showFlags = false,
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
                "เพิ่มไฟล์วิดีโอ" to { pickFiles.launch(arrayOf("video/*")) },
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
                toast("เพิ่ม ${fresh.size} ตอนต่อท้ายแล้ว (เรียงใหม่ได้จากเมนู)")
            }
        }.start()
    }
}
