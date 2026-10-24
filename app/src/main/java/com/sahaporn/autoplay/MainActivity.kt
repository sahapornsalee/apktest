package com.sahaporn.autoplay

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private lateinit var listView: ListView
    private lateinit var txtEmpty: TextView
    private var series: List<Series> = emptyList()

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
        listView = findViewById(R.id.list)
        txtEmpty = findViewById(R.id.txtEmpty)

        findViewById<Button>(R.id.btnAdd).setOnClickListener { showAddDialog() }
        listView.setOnItemClickListener { _, _, pos, _ -> openSeries(series[pos].id) }
        listView.setOnItemLongClickListener { _, _, pos, _ ->
            showSeriesMenu(series[pos])
            true
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        series = Store.loadAll(this)
        txtEmpty.visibility = if (series.isEmpty()) View.VISIBLE else View.GONE
        listView.visibility = if (series.isEmpty()) View.GONE else View.VISIBLE
        val rows = series.map { s ->
            val at = (s.lastIndex + 1).coerceAtMost(s.episodes.size)
            "${s.name}\n${s.episodes.size} ตอน · ดูถึงตอนที่ $at"
        }
        listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, rows)
    }

    private fun showAddDialog() {
        AlertDialog.Builder(this)
            .setTitle("เพิ่มเรื่องใหม่")
            .setItems(arrayOf("เลือกทั้งโฟลเดอร์ (แนะนำ)", "เลือกไฟล์วิดีโอทีละหลายไฟล์")) { _, which ->
                if (which == 0) pickFolder.launch(null) else pickFiles.launch(arrayOf("video/*"))
            }
            .show()
    }

    private fun scanAndCreate(defaultName: String, scan: () -> List<Episode>) {
        Toast.makeText(this, "กำลังค้นหาวิดีโอ…", Toast.LENGTH_SHORT).show()
        Thread {
            val eps = scan()
            runOnUiThread {
                if (eps.isEmpty()) {
                    Toast.makeText(this, "ไม่พบไฟล์วิดีโอ", Toast.LENGTH_LONG).show()
                } else {
                    askName("ตั้งชื่อเรื่อง (${eps.size} ตอน)", defaultName) { name ->
                        val s = Series(name = name)
                        s.episodes.addAll(eps)
                        Store.put(this, s)
                        openSeries(s.id)
                    }
                }
            }
        }.start()
    }

    private fun askName(title: String, initial: String, onOk: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(initial)
            setSelectAllOnFocus(true)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("ตกลง") { _, _ -> onOk(input.text.toString().trim().ifEmpty { initial }) }
            .setNegativeButton("ยกเลิก", null)
            .show()
    }

    private fun showSeriesMenu(s: Series) {
        AlertDialog.Builder(this)
            .setTitle(s.name)
            .setItems(arrayOf("เปลี่ยนชื่อ", "ลบเรื่องนี้")) { _, which ->
                if (which == 0) {
                    askName("เปลี่ยนชื่อ", s.name) { name ->
                        s.name = name
                        Store.put(this, s)
                        refresh()
                    }
                } else {
                    AlertDialog.Builder(this)
                        .setMessage("ลบ “${s.name}” ออกจากรายการ? (ไฟล์วิดีโอในเครื่องจะไม่ถูกลบ)")
                        .setPositiveButton("ลบ") { _, _ ->
                            Store.delete(this, s.id)
                            refresh()
                        }
                        .setNegativeButton("ยกเลิก", null)
                        .show()
                }
            }
            .show()
    }

    private fun openSeries(id: String) {
        startActivity(Intent(this, SeriesActivity::class.java).putExtra(SeriesActivity.EXTRA_ID, id))
    }
}
