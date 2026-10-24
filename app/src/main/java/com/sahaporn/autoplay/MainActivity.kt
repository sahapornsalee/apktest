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
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar

class MainActivity : AppCompatActivity() {
    private lateinit var listView: ListView
    private lateinit var txtEmpty: TextView
    private lateinit var adapter: RowAdapter
    private val collapsed = mutableSetOf<String>()

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
        txtEmpty = findViewById(R.id.txtEmpty)
        adapter = RowAdapter(this)
        listView.adapter = adapter
        findViewById<Button>(R.id.btnAdd).setOnClickListener { showAddDialog(null) }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_ADD_CATEGORY, 0, "+ หมวดหมู่").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        menu.add(0, MENU_EXPAND, 1, "ขยายทุกหมวด")
        menu.add(0, MENU_COLLAPSE, 2, "ยุบทุกหมวด")
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
                    onMore = if (cat.isEmpty()) null else ({ categoryMenu(cat) }),
                ) {
                    if (!collapsed.remove(cat)) collapsed.add(cat)
                    refresh()
                }
            )
            if (!closed) items.forEach { rows.add(seriesRow(it)) }
        }

        if (cats.isEmpty()) {
            all.forEach { rows.add(seriesRow(it)) }
        } else {
            cats.forEach { c -> addGroup(c, c, all.filter { it.category == c }) }
            val loose = all.filter { it.category !in cats }
            if (loose.isNotEmpty()) addGroup("", "ไม่มีหมวดหมู่", loose)
        }

        val empty = rows.isEmpty()
        txtEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        listView.visibility = if (empty) View.GONE else View.VISIBLE
        adapter.rows = rows
    }

    private fun seriesRow(s: Series): Row {
        val at = (s.lastIndex + 1).coerceAtMost(s.episodes.size)
        return Row(
            title = s.name,
            subtitle = "${s.episodes.size} ตอน · ดูถึงตอนที่ $at",
            onMore = { seriesMenu(s) },
        ) { openSeries(s.id) }
    }

    private fun seriesMenu(s: Series) {
        Dialogs.menu(
            this, s.name, listOf(
                "▶ ดูต่อ" to {
                    if (s.episodes.isNotEmpty()) PlayerActivity.start(this, s.id, s.lastIndex, s.lastPosition)
                },
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
    }
}
