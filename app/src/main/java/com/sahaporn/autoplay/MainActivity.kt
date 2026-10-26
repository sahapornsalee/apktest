package com.sahaporn.autoplay

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.SearchView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.MaterialColors

/** Home screen: a "continue watching" hero, then Netflix-style rows per category. */
class MainActivity : ThemedActivity() {
    private lateinit var content: LinearLayout
    private var searchText = ""

    /** Category for the series being added; null means ask after naming it. */
    private var pendingCategory: String? = null

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            MediaScan.persist(this, uri)
            val name = MediaScan.folderName(this, uri) ?: "เรื่องใหม่"
            scanAndCreate(name, uri.toString()) { MediaScan.scanTree(this, uri) }
        }
    }

    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { MediaScan.persist(this, it) }
            scanAndCreate("เรื่องใหม่", null) { MediaScan.fromUris(this, uris) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        setupBars(findViewById(R.id.root), toolbar)
        // App name as a bold, accent-coloured logo, the way streaming apps show theirs.
        title = SpannableString(getString(R.string.app_name)).apply {
            setSpan(ForegroundColorSpan(MaterialColors.getColor(toolbar, androidx.appcompat.R.attr.colorPrimary)), 0, length, 0)
            setSpan(StyleSpan(Typeface.BOLD), 0, length, 0)
            setSpan(RelativeSizeSpan(1.25f), 0, length, 0)
        }
        content = findViewById(R.id.content)
        findViewById<View>(R.id.btnAdd).setOnClickListener { showAddDialog(null) }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val search = SearchView(this).apply {
            queryHint = "ค้นหาเรื่องหรือตอน"
            setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(text: String?): Boolean = false

                override fun onQueryTextChange(text: String?): Boolean {
                    searchText = text.orEmpty().trim()
                    refresh()
                    return true
                }
            })
        }
        menu.add(0, MENU_SEARCH, 0, "ค้นหา").apply {
            setIcon(R.drawable.ic_search)
            actionView = search
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS or MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW)
            setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
                override fun onMenuItemActionExpand(item: MenuItem): Boolean = true

                override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                    searchText = ""
                    refresh()
                    return true
                }
            })
        }
        menu.add(0, MENU_SETTINGS, 1, "ตั้งค่า").apply {
            setIcon(R.drawable.ic_settings)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        menu.add(0, MENU_ADD_CATEGORY, 2, "+ เพิ่มหมวดหมู่")
        menu.add(0, MENU_THEME, 3, "🎨 ธีมและสี")
        menu.tintIcons(this)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            MENU_ADD_CATEGORY -> Dialogs.askText(this, "หมวดหมู่ใหม่", "") { name ->
                if (!Store.addCategory(this, name)) toast("มีหมวดหมู่นี้แล้ว")
                refresh()
            }
            MENU_THEME -> AppTheme.showPicker(this)
            MENU_SETTINGS -> startActivity(Intent(this, SettingsActivity::class.java))
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun refresh() {
        content.removeAllViews()
        val all = Store.loadAll(this)
        val cats = Store.categories(this)

        if (searchText.isNotEmpty()) {
            val found = all.filter { matches(it) }
            if (found.isEmpty()) addMessage("ไม่พบ “$searchText”") else addSection("ผลการค้นหา", found, grid = true)
            return
        }
        if (all.isEmpty() && cats.isEmpty()) {
            addWelcome()
            return
        }

        Store.latest(this)?.let { if (it.resumeIndex() != null) addHero(it) }
        val watching = all.filter { inProgress(it) }.sortedByDescending { it.lastWatchedAt }
        if (watching.isNotEmpty()) addSection("ดูต่อ", watching)
        val pinned = all.filter { it.pinned }
        if (pinned.isNotEmpty()) addSection("📌 ปักหมุด", pinned)
        cats.forEach { c -> addSection(c, all.filter { it.category == c }, category = c) }
        val loose = all.filter { it.category !in cats }
        if (loose.isNotEmpty()) addSection(if (cats.isEmpty()) "เรื่องทั้งหมด" else "ไม่มีหมวดหมู่", loose)
    }

    private fun inProgress(s: Series): Boolean {
        val idx = s.resumeIndex() ?: return false
        return s.watchedCount > 0 || s.episodes[idx].hasProgress
    }

    private fun matches(s: Series): Boolean =
        s.name.contains(searchText, ignoreCase = true) ||
            s.category.contains(searchText, ignoreCase = true) ||
            s.episodes.any { it.name.contains(searchText, ignoreCase = true) }

    private fun addMessage(text: String) {
        val pad = (32 * resources.displayMetrics.density).toInt()
        content.addView(TextView(this).apply {
            this.text = text
            gravity = Gravity.CENTER
            textSize = 16f
            setPadding(pad, pad * 2, pad, pad)
        })
    }

    /** First run: explain the app and offer the two ways to add videos right away. */
    private fun addWelcome() {
        val v = layoutInflater.inflate(R.layout.item_empty, content, false)
        v.findViewById<View>(R.id.btnEmptyFolder).setOnClickListener {
            pendingCategory = null
            pickFolder.launch(null)
        }
        v.findViewById<View>(R.id.btnEmptyFiles).setOnClickListener {
            pendingCategory = null
            pickFiles.launch(arrayOf("*/*"))
        }
        content.addView(v)
    }

    private fun addHero(s: Series) {
        val v = layoutInflater.inflate(R.layout.item_hero, content, false)
        val idx = s.resumeIndex() ?: 0
        val ep = s.episodes[idx]
        Thumbs.load(this, ep.uri, v.findViewById(R.id.imgHero))
        v.findViewById<TextView>(R.id.txtHeroTitle).text = s.name
        val left = if (ep.duration > 0 && ep.hasProgress && !ep.watched) " · เหลือ ${TimeText.ms(ep.duration - ep.position)}" else ""
        v.findViewById<TextView>(R.id.txtHeroSub).text = "${s.seasonPrefix(idx)}${s.episodeLabels()[idx]}$left"
        v.findViewById<ProgressBar>(R.id.heroProgress).showPercent(TimeText.percent(ep))
        v.findViewById<Button>(R.id.btnHeroPlay).apply {
            text = if (ep.hasProgress) "▶  ดูต่อ" else "▶  เล่น"
            setOnClickListener { Resume.play(this@MainActivity, s) }
        }
        v.findViewById<Button>(R.id.btnHeroInfo).setOnClickListener { openSeries(s.id) }
        v.setOnClickListener { openSeries(s.id) }
        content.addView(v)
    }

    private fun addSection(title: String, items: List<Series>, category: String? = null, grid: Boolean = false) {
        val v = layoutInflater.inflate(R.layout.item_section, content, false)
        v.findViewById<TextView>(R.id.txtSection).text = title
        val more = v.findViewById<View>(R.id.btnMore)
        if (category != null) {
            more.visibility = View.VISIBLE
            more.setOnClickListener { categoryMenu(category) }
        } else {
            more.visibility = View.GONE
        }
        if (items.isEmpty()) {
            v.findViewById<TextView>(R.id.txtHint).apply {
                visibility = View.VISIBLE
                text = "ยังว่าง — กด ⋮ เพื่อเพิ่มเรื่องในหมวดนี้"
            }
        }
        val rv = v.findViewById<RecyclerView>(R.id.rv)
        rv.layoutManager = if (grid) {
            GridLayoutManager(this, 2)
        } else {
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        }
        rv.isNestedScrollingEnabled = false
        rv.adapter = SimpleAdapter<Series>(R.layout.item_card) { card, s -> bindCard(card, s, grid) }.also { it.items = items }
        content.addView(v)
    }

    private fun bindCard(v: View, s: Series, grid: Boolean) {
        if (grid) v.layoutParams = v.layoutParams.apply { width = ViewGroup.LayoutParams.MATCH_PARENT }
        val idx = s.resumeIndex()
        val cover = s.episodes.getOrNull(idx ?: 0)
        val img = v.findViewById<ImageView>(R.id.imgThumb)
        if (cover != null) Thumbs.load(this, cover.uri, img) else img.setImageDrawable(null)
        v.findViewById<TextView>(R.id.txtTitle).text = if (s.pinned) "📌 ${s.name}" else s.name
        v.findViewById<TextView>(R.id.txtBadge).text = when {
            idx == null -> "✓ ดูครบ"
            s.seasons().size > 1 -> "${s.seasons().size} ภาค"
            else -> "${s.episodes.size} ตอน"
        }
        v.findViewById<ProgressBar>(R.id.progress).showPercent(cover?.let { TimeText.percent(it) } ?: -1)
        v.findViewById<TextView>(R.id.txtSubtitle).text = status(s)
        v.setOnClickListener { openSeries(s.id) }
        v.setOnLongClickListener {
            seriesMenu(s)
            true
        }
    }

    private fun status(s: Series): String {
        val idx = s.resumeIndex()
        val base = when {
            idx == null -> "✓ ดูครบ ${s.episodes.size} ตอน"
            s.watchedCount == 0 && !s.episodes[idx].hasProgress -> "${s.episodes.size} ตอน"
            else -> "${s.seasonPrefix(idx)}${s.episodeLabels()[idx]} · ${TimeText.ms(s.episodes[idx].resumePosition)}"
        }
        if (searchText.isEmpty() || s.name.contains(searchText, ignoreCase = true)) return base
        val hit = s.episodes.firstOrNull { it.name.contains(searchText, ignoreCase = true) } ?: return base
        return "🔍 ${hit.shortName}"
    }

    private fun seriesMenu(s: Series) {
        Dialogs.menu(
            this, s.name, listOf(
                Resume.label(s) to { Resume.play(this, s) },
                "ⓘ  รายละเอียด / แก้ไขตอน" to { openSeries(s.id) },
                (if (s.pinned) "📌  เลิกปักหมุด" else "📌  ปักหมุดไว้บนสุด") to {
                    s.pinned = !s.pinned
                    Store.put(this, s)
                    refresh()
                },
                "✏️  เปลี่ยนชื่อ" to {
                    Dialogs.askText(this, "เปลี่ยนชื่อเรื่อง", s.name) { name ->
                        s.name = name
                        Store.put(this, s)
                        refresh()
                    }
                },
                "📁  ย้ายหมวดหมู่" to {
                    Dialogs.chooseCategory(this, s.category) { cat ->
                        s.category = cat
                        Store.put(this, s)
                        refresh()
                    }
                },
                Dialogs.danger(this, "↺  รีเซ็ตการดูเรื่องนี้") to { Resume.confirmReset(this, s) { refresh() } },
                Dialogs.danger(this, "🗑  ลบเรื่องนี้") to {
                    Dialogs.confirm(
                        this, "ลบ “${s.name}”?\nเอาออกจากแอปเท่านั้น ไฟล์วิดีโอในเครื่องยังอยู่ครบ",
                        "ลบเรื่องนี้", destructive = true,
                    ) {
                        Store.delete(this, s.id)
                        refresh()
                    }
                },
            )
        )
    }

    private fun categoryMenu(cat: String) {
        Dialogs.menu(
            this, cat, listOf(
                "＋  เพิ่มเรื่องในหมวดนี้" to { showAddDialog(cat) },
                "✏️  เปลี่ยนชื่อหมวดหมู่" to {
                    Dialogs.askText(this, "เปลี่ยนชื่อหมวดหมู่", cat) { name ->
                        if (name != cat && !Store.renameCategory(this, cat, name)) toast("มีหมวดหมู่นี้แล้ว")
                        refresh()
                    }
                },
                "⬆  เลื่อนขึ้น" to {
                    Store.moveCategory(this, cat, -1)
                    refresh()
                },
                "⬇  เลื่อนลง" to {
                    Store.moveCategory(this, cat, 1)
                    refresh()
                },
                Dialogs.danger(this, "🗑  ลบหมวดหมู่") to {
                    Dialogs.confirm(
                        this, "ลบหมวดหมู่ “$cat”?\nเรื่องในหมวดนี้ยังอยู่ จะย้ายไปที่ “ไม่มีหมวดหมู่”",
                        "ลบหมวดหมู่", destructive = true,
                    ) {
                        Store.deleteCategory(this, cat)
                        refresh()
                    }
                },
            )
        )
    }

    private fun showAddDialog(category: String?) {
        pendingCategory = category
        Dialogs.menu(
            this, "เพิ่มเรื่องใหม่", listOf(
                "เลือกทั้งโฟลเดอร์ (แนะนำ)" to { pickFolder.launch(null) },
                "เลือกไฟล์วิดีโอทีละหลายไฟล์" to { pickFiles.launch(arrayOf("*/*")) },
            )
        )
    }

    private fun scanAndCreate(defaultName: String, folderUri: String?, scan: () -> List<Episode>) {
        toast("กำลังค้นหาวิดีโอ…")
        Thread {
            val eps = scan()
            runOnUiThread {
                if (eps.isEmpty()) {
                    toast("ไม่พบไฟล์วิดีโอ")
                    return@runOnUiThread
                }
                Dialogs.askText(this, "ตั้งชื่อเรื่อง (${eps.size} ตอน)", defaultName) { name ->
                    val create = { cat: String ->
                        val s = Series(name = name, category = cat, folderUri = folderUri)
                        s.episodes.addAll(eps)
                        Store.put(this, s)
                        openSeries(s.id)
                    }
                    val preset = pendingCategory
                    when {
                        preset != null -> create(preset)
                        Store.categories(this).isEmpty() -> create("")
                        else -> Dialogs.chooseCategory(this, null, create)
                    }
                }
            }
        }.start()
    }

    private fun openSeries(id: String) {
        startActivity(Intent(this, SeriesActivity::class.java).putExtra(SeriesActivity.EXTRA_ID, id))
    }

    companion object {
        private const val MENU_ADD_CATEGORY = 1
        private const val MENU_SETTINGS = 4
        private const val MENU_SEARCH = 5
        private const val MENU_THEME = 6
    }
}
