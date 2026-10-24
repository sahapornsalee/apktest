package com.sahaporn.autoplay

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class SeriesActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_ID = "series_id"
    }

    private lateinit var seriesId: String
    private lateinit var series: Series
    private lateinit var txtInfo: TextView
    private lateinit var btnResume: Button
    private lateinit var listView: ListView

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
        seriesId = intent.getStringExtra(EXTRA_ID) ?: run { finish(); return }
        txtInfo = findViewById(R.id.txtInfo)
        btnResume = findViewById(R.id.btnResume)
        listView = findViewById(R.id.list)

        btnResume.setOnClickListener { play(series.lastIndex, series.lastPosition) }
        findViewById<Button>(R.id.btnSkip).setOnClickListener { showSkipDialog() }
        findViewById<Button>(R.id.btnAdd).setOnClickListener { showAddDialog() }
        listView.setOnItemClickListener { _, _, pos, _ -> play(pos, 0L) }
        listView.setOnItemLongClickListener { _, _, pos, _ ->
            confirmRemove(pos)
            true
        }
    }

    override fun onResume() {
        super.onResume()
        series = Store.get(this, seriesId) ?: run { finish(); return }
        render()
    }

    private fun render() {
        title = series.name
        val intro = if (series.skipIntro && series.introEnd > series.introStart) {
            "ข้ามเพลงเปิด ${TimeText.format(series.introStart.toLong())}–${TimeText.format(series.introEnd.toLong())}"
        } else {
            "ไม่ข้ามเพลงเปิด"
        }
        val outro = if (series.skipOutro && series.outroLength > 0) {
            "ข้ามเพลงปิด ${TimeText.format(series.outroLength.toLong())} สุดท้าย"
        } else {
            "ไม่ข้ามเพลงปิด"
        }
        txtInfo.text = "${series.episodes.size} ตอน · เล่นต่อกันอัตโนมัติ\n$intro · $outro"

        if (series.lastIndex >= series.episodes.size) {
            series.lastIndex = 0
            series.lastPosition = 0
        }
        btnResume.isEnabled = series.episodes.isNotEmpty()
        btnResume.text = if (series.lastIndex == 0 && series.lastPosition < 1000) {
            "▶ เริ่มดูตั้งแต่ตอนแรก"
        } else {
            "▶ ดูต่อ ตอนที่ ${series.lastIndex + 1} (${TimeText.format(series.lastPosition / 1000)})"
        }

        val rows = series.episodes.mapIndexed { i, e ->
            val mark = if (i == series.lastIndex) "▶ " else ""
            "$mark${i + 1}. ${e.name}"
        }
        listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, rows)
    }

    private fun play(index: Int, position: Long) {
        if (series.episodes.isEmpty()) return
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_ID, series.id)
                .putExtra(PlayerActivity.EXTRA_INDEX, index)
                .putExtra(PlayerActivity.EXTRA_POSITION, position)
        )
    }

    private fun showSkipDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_skip, null)
        val chkIntro = view.findViewById<CheckBox>(R.id.chkIntro)
        val chkOutro = view.findViewById<CheckBox>(R.id.chkOutro)
        val edIntroStart = view.findViewById<EditText>(R.id.edIntroStart)
        val edIntroEnd = view.findViewById<EditText>(R.id.edIntroEnd)
        val edOutro = view.findViewById<EditText>(R.id.edOutro)

        chkIntro.isChecked = series.skipIntro
        chkOutro.isChecked = series.skipOutro
        edIntroStart.setText(TimeText.format(series.introStart.toLong()))
        if (series.introEnd > 0) edIntroEnd.setText(TimeText.format(series.introEnd.toLong()))
        if (series.outroLength > 0) edOutro.setText(TimeText.format(series.outroLength.toLong()))

        AlertDialog.Builder(this)
            .setTitle("ตั้งค่าข้ามเพลงเปิด/ปิด")
            .setView(view)
            .setPositiveButton("บันทึก") { _, _ ->
                val start = TimeText.parse(edIntroStart.text.toString())
                val end = TimeText.parse(edIntroEnd.text.toString())
                val outro = TimeText.parse(edOutro.text.toString())
                if (start == null || end == null || outro == null) {
                    Toast.makeText(this, "รูปแบบเวลาไม่ถูกต้อง เช่น 90 หรือ 1:30", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                if (end in 1..start) {
                    Toast.makeText(this, "เวลาจบเพลงเปิดต้องมากกว่าเวลาเริ่ม", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                series.skipIntro = chkIntro.isChecked
                series.skipOutro = chkOutro.isChecked
                series.introStart = start
                series.introEnd = end
                series.outroLength = outro
                Store.put(this, series)
                render()
                Toast.makeText(this, "บันทึกแล้ว ใช้กับทุกตอน", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("ยกเลิก", null)
            .show()
    }

    private fun showAddDialog() {
        AlertDialog.Builder(this)
            .setTitle("เพิ่มตอน")
            .setItems(arrayOf("เพิ่มจากโฟลเดอร์", "เพิ่มไฟล์วิดีโอ")) { _, which ->
                if (which == 0) pickFolder.launch(null) else pickFiles.launch(arrayOf("video/*"))
            }
            .show()
    }

    private fun addEpisodes(scan: () -> List<Episode>) {
        Toast.makeText(this, "กำลังค้นหาวิดีโอ…", Toast.LENGTH_SHORT).show()
        Thread {
            val found = scan()
            runOnUiThread {
                val current = series.episodes.getOrNull(series.lastIndex)?.uri
                val known = series.episodes.map { it.uri }.toSet()
                val fresh = found.filter { it.uri !in known }
                if (fresh.isEmpty()) {
                    Toast.makeText(this, "ไม่พบวิดีโอใหม่", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                val all = MediaScan.sorted(series.episodes + fresh)
                series.episodes.clear()
                series.episodes.addAll(all)
                // Keep "continue watching" pointing at the same file after re-sorting.
                series.lastIndex = all.indexOfFirst { it.uri == current }.coerceAtLeast(0)
                Store.put(this, series)
                render()
                Toast.makeText(this, "เพิ่ม ${fresh.size} ตอนแล้ว", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun confirmRemove(index: Int) {
        val ep = series.episodes[index]
        AlertDialog.Builder(this)
            .setMessage("เอา “${ep.name}” ออกจากรายการ?")
            .setPositiveButton("เอาออก") { _, _ ->
                series.episodes.removeAt(index)
                if (series.lastIndex > index) series.lastIndex--
                if (series.lastIndex == index) series.lastPosition = 0
                Store.put(this, series)
                render()
            }
            .setNegativeButton("ยกเลิก", null)
            .show()
    }
}
