package com.sahaporn.autoplay

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.appbar.MaterialToolbar

/** App-wide settings: look, playback and data, in one place. */
class SettingsActivity : ThemedActivity() {
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = "ตั้งค่า"
        setupBars(findViewById(R.id.root), toolbar)
        content = findViewById(R.id.content)
        render()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun render() {
        content.removeAllViews()

        section("การแสดงผล")
        val accent = AppTheme.ACCENTS[AppTheme.accent(this)]
        action("โหมดธีม", AppTheme.MODES[AppTheme.mode(this)]) { AppTheme.pickMode(this) }
        action("สีหลักของแอป", AppTheme.dot(accent.color, accent.name)) { AppTheme.pickAccent(this) }

        section("การเล่นวิดีโอ")
        action("ตัวเล่นเริ่มต้น", Engines.name(Settings.defaultEngine(this)) + "\nถ้าวิดีโอภาพแตกบ่อย ลองเลือก “ตัวเล่นของระบบ”") {
            Dialogs.choose(this, "ตัวเล่นเริ่มต้น", Engines.NAMES, Settings.defaultEngine(this)) {
                Settings.setDefaultEngine(this, it)
                render()
            }
        }
        toggle("เล่นเสียงต่อเมื่อปิดจอ", "ฟังต่อได้ขณะจอดับ พร้อมปุ่มควบคุมบนแถบแจ้งเตือน", Settings.backgroundAudio(this)) {
            Settings.setBackgroundAudio(this, it)
        }
        toggle("เข้าจอลอยอัตโนมัติ", "กดปุ่มโฮมระหว่างดู วิดีโอจะย่อเป็นหน้าต่างเล็ก", Settings.autoPip(this)) {
            Settings.setAutoPip(this, it)
        }
        toggle(
            "ปัดจอขณะดู",
            "ปัดซ้าย-ขวาเพื่อกรอ · ปัดขึ้นลงฝั่งซ้ายปรับแสง ฝั่งขวาปรับเสียง",
            Settings.gestures(this),
        ) { Settings.setGestures(this, it) }
        val steps = listOf(5, 10, 15, 30)
        action("ระยะกรอ (แตะสองครั้ง / ปุ่ม ↺ ↻)", "${Settings.seekStepSec(this)} วินาที") {
            Dialogs.choose(this, "ระยะกรอ", steps.map { "$it วินาที" }, steps.indexOf(Settings.seekStepSec(this))) {
                Settings.setSeekStepSec(this, steps[it])
                render()
            }
        }

        section("ข้อมูล")
        action("รีเซ็ตการดูทุกเรื่อง", "ล้างตำแหน่งที่ดูค้างไว้และสถานะดูจบของทุกตอน") {
            Dialogs.confirm(this, "ล้างการจดจำการดูของทุกเรื่อง ทุกตอน?", "รีเซ็ตทั้งหมด") {
                Store.resetAllProgress(this)
                toast("รีเซ็ตทั้งหมดแล้ว")
            }
        }
        action("ล้างภาพตัวอย่าง", "สร้างภาพตัวอย่างใหม่เมื่อเปิดครั้งถัดไป") {
            Thumbs.clear(this)
            toast("ล้างภาพตัวอย่างแล้ว")
        }

        section("เกี่ยวกับ")
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            null
        }
        action(getString(R.string.app_name), "เวอร์ชัน ${version ?: "-"} · เล่นวิดีโอต่อเนื่อง ข้ามเพลงเปิด-ปิด") {}
    }

    private fun section(title: String) {
        content.addView(TextView(this, null, 0, R.style.SectionTitle).apply { text = title })
    }

    private fun row(title: String, summary: CharSequence): View {
        val v = layoutInflater.inflate(R.layout.item_setting, content, false)
        v.findViewById<TextView>(R.id.txtSettingTitle).text = title
        v.findViewById<TextView>(R.id.txtSettingSummary).text = summary
        content.addView(v)
        return v
    }

    private fun action(title: String, summary: CharSequence, onClick: () -> Unit) {
        row(title, summary).setOnClickListener { onClick() }
    }

    private fun toggle(title: String, summary: String, on: Boolean, onChange: (Boolean) -> Unit) {
        val v = row(title, summary)
        val sw = v.findViewById<SwitchCompat>(R.id.swSetting)
        sw.visibility = View.VISIBLE
        sw.isChecked = on
        v.setOnClickListener {
            sw.isChecked = !sw.isChecked
            onChange(sw.isChecked)
        }
    }
}
