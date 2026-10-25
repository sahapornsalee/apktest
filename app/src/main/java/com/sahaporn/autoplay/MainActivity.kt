package com.sahaporn.autoplay

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.SearchView
import com.google.android.material.appbar.MaterialToolbar

class MainActivity : ThemedActivity() {
    private lateinit var listView: ListView
    private lateinit var txtEmpty: TextView
    private lateinit var adapter: RowAdapter
    private lateinit var btnLatest: Button
    private val collapsed = mutableSetOf<String>()
    private var searchText = ""
    private var emptyText: CharSequence = ""

    /** Category for the series being added; null means ask after naming it. */
    private var pendingCategory: String? = null

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            MediaScan.persist(this, uri)
            val name = MediaScan.folderName(this, uri) ?: "เรื่องใหม่"
            scanAndCreate(name) { MediaScan.scanTree(this, uri) }
        }
    }

    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { MediaScan.persist(this, it) }
            scanAndCreate("เรื่องใหม่") { MediaScan.fromUris(this, uris) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        setupBars(findViewById(R.id.root), toolbar)

        listView = findViewById(R.id.list)
        btnLatest = findViewById(R.id.btnLatest)
        txtEmpty = findViewById(R.id.txtEmpty)
        emptyText = txtEmpty.text
        adapter = RowAdapter(this)
        listView.adapter = adapter
        findViewById<Button>(R.id.btnAdd).setOnClickListener { showAddDialog(null) }
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
            setIcon(android.R.drawable.ic_menu_search)
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
        menu.add(0, MENU_ADD_CATEGORY, 1, "+ หมวดหมู่").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(0, MENU_EXPAND, 1, "ขยายทุกหมวด")
        menu.add(0, MENU_COLLAPSE, 2, "ยุบทุกหมวด")
        menu.add(0, MENU_THEME, 3, "🎨 ธีมและสี")
        menu.add(0, MENU_RESET_ALL, 4, "รีเซ็ตการดูทุกเรื่อง")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            MENU_ADD_CATEGORY -> Dialogs.askText(this, "หมวดหมู่ใหม่", "") { name ->
                if (!Store.addCategory(this, name)) toast("มีหมวดหมู่นี้แล้ว")
                refresh()
            }
            MENU_EXPAND -> {
                collapsed.clear()
                refresh()
            }
            MENU_THEME -> AppTheme.showPicker(this)
            MENU_RESET_ALL -> Dialogs.confirm(this, "ล้างการจดจำการดูของทุกเรื่อง ทุกตอน?", "รีเซ็ตทั้งหมด") {
                Store.resetAllProgress(this)
                toast("รีเซ็ตทั้งหมดแล้ว")
                refresh()
            }
            MENU_COLLAPSE -> {
                collapsed.addAll(Store.categories(this))
                collapsed.add("")
                refresh()
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun refresh() {
        val all = Store.loadAll(this)
        val cats = Store.categories(this)
        val rows = mutableListOf<Row>()

        fun addGroup(cat: String, label: String, items: List<Series>) {
            val closed = cat in collapsed
            rows.add(
                Row(
                    title = "${if (closed) "▸" else "▾"} $label",
                    subtitle = if (items.isEmpty()) "ว่าง — กด ⋮ เพื่อเพิ่มเรื่องในหมวดนี้" else "${items.size} เรื่อง",
                    header = true,
                    onMore = if (cat.isEmpty() || cat == PINNED_KEY) null else ({ categoryMenu(cat) }),
                ) {
                    if (!collapsed.remove(cat)) collapsed.add(cat)
                    refresh()
                }
            )
            if (!closed) items.forEach { rows.add(seriesRow(it)) }
        }

        val pinned = all.filter { it.pinned }
        val rest = all.filter { !it.pinned }
        if (searchText.isNotEmpty()) {
            all.filter { matches(it) }.forEach { rows.add(seriesRow(it)) }
        } else {
            if (pinned.isNotEmpty()) addGroup(PINNED_KEY, "📌 ปักหมุด", pinned)
            if (cats.isEmpty()) {
                if (pinned.isEmpty()) {
                    rest.forEach { rows.add(seriesRow(it)) }
                } else if (rest.isNotEmpty()) {
                    addGroup("", "เรื่องอื่นๆ", rest)
                }
            } else {
                cats.forEach { c -> addGroup(c, c, rest.filter { it.category == c }) }
                val loose = rest.filter { it.category !in cats }
                if (loose.isNotEmpty()) addGroup("", "ไม่มีหมวดหมู่", loose)
            }
        }

        val latest = Store.latest(this)
        btnLatest.visibility = if (latest == null) View.GONE else View.VISIBLE
        if (searchText.isNotEmpty()) btnLatest.visibility = View.GONE
        if (latest != null) {
            btnLatest.text = "${Resume.label(latest)}\n${latest.name}"
            btnLatest.setOnClickListener { Resume.play(this, latest) }
        }

        val empty = rows.isEmpty()
        txtEmpty.text = if (searchText.isNotEmpty()) "ไม่พบ “$searchText”" else emptyText
        txtEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        listView.visibility = if (empty) View.GONE else View.VISIBLE
        adapter.rows = rows
    }

    private fun matches(s: Series): Boolean =
        s.name.contains(searchText, ignoreCase = true) ||
            s.category.contains(searchText, ignoreCase = true) ||
            s.episodes.any { it.name.contains(searchText, ignoreCase = true) }

    private fun seriesRow(s: Series): Row {
        val idx = s.resumeIndex()
        val status = when {
            idx == null -> "✓ ดูครบแล้ว"
            s.watchedCount == 0 && !s.episodes[idx].hasProgress -> "ยังไม่ได้ดู"
            else -> "ค้างตอนที่ ${idx + 1} · ${TimeText.ms(s.episodes[idx].resumePosition)}"
        }
        val found = if (searchText.isNotEmpty() && !s.name.contains(searchText, ignoreCase = true)) {
            s.episodes.firstOrNull { it.name.contains(searchText, ignoreCase = true) }?.let { "\n🔍 พบในตอน: ${it.name}" }
        } else {
            null
        }
        val cover = s.episodes.getOrNull(idx ?: 0)?.uri
        return Row(
            title = if (s.pinned) "📌 ${s.name}" else s.name,
            thumb = cover,
            progress = if (s.episodes.isEmpty()) -1 else s.watchedCount * 100 / s.episodes.size,
            subtitle = "${s.episodes.size} ตอน · ดูจบ ${s.watchedCount} · $status${found ?: ""}",
            onMore = { seriesMenu(s) },
        ) { openSeries(s.id) }
    }

    private fun seriesMenu(s: Series) {
        Dialogs.menu(
            this, s.name, listOf(
                Resume.label(s) to { Resume.play(this, s) },
                (if (s.pinned) "เลิกปักหมุด" else "📌 ปักหมุดไว้บนสุด") to {
                    s.pinned = !s.pinned
                    Store.put(this, s)
                    refresh()
                },
                "↺ รีเซ็ตการดูเรื่องนี้" to { Resume.confirmReset(this, s) { refresh() } },
                "แก้ไขตอน / ตั้งค่า" to { openSeries(s.id) },
                "เปลี่ยนชื่อ" to {
                    Dialogs.askText(this, "เปลี่ยนชื่อเรื่อง", s.name) { name ->
                        s.name = name
                        Store.put(this, s)
                        refresh()
                    }
                },
                "ย้ายหมวดหมู่" to {
                    Dialogs.chooseCategory(this, s.category) { cat ->
                        s.category = cat
                        Store.put(this, s)
                        refresh()
                    }
                },
                "ลบเรื่องนี้" to {
                    Dialogs.confirm(this, "ลบ “${s.name}” ออกจากรายการ?\n(ไฟล์วิดีโอในเครื่องจะไม่ถูกลบ)", "ลบ") {
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
                "+ เพิ่มเรื่องในหมวดนี้" to { showAddDialog(cat) },
                "เปลี่ยนชื่อหมวดหมู่" to {
                    Dialogs.askText(this, "เปลี่ยนชื่อหมวดหมู่", cat) { name ->
                        if (name != cat && !Store.renameCategory(this, cat, name)) toast("มีหมวดหมู่นี้แล้ว")
                        refresh()
                    }
                },
                "เลื่อนขึ้น" to {
                    Store.moveCategory(this, cat, -1)
                    refresh()
                },
                "เลื่อนลง" to {
                    Store.moveCategory(this, cat, 1)
                    refresh()
                },
                "ลบหมวดหมู่" to {
                    Dialogs.confirm(this, "ลบหมวดหมู่ “$cat”?\nเรื่องในหมวดนี้จะยังอยู่ ย้ายไปที่ “ไม่มีหมวดหมู่”", "ลบ") {
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
                "เลือกไฟล์วิดีโอทีละหลายไฟล์" to { pickFiles.launch(arrayOf("video/*")) },
            )
        )
    }

    private fun scanAndCreate(defaultName: String, scan: () -> List<Episode>) {
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
                        val s = Series(name = name, category = cat)
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
        private const val MENU_EXPAND = 2
        private const val MENU_COLLAPSE = 3
        private const val MENU_RESET_ALL = 4
        private const val MENU_SEARCH = 5
        private const val MENU_THEME = 6
        private const val PINNED_KEY = "\u0000pinned"
    }
}
