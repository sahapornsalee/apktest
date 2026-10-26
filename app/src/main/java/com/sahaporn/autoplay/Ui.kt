package com.sahaporn.autoplay

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.PowerManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** RecyclerView adapter that binds each item with a plain function. */
class SimpleAdapter<T>(
    private val layout: Int,
    private val bind: (View, T) -> Unit,
) : RecyclerView.Adapter<SimpleAdapter.Holder>() {
    class Holder(view: View) : RecyclerView.ViewHolder(view)

    var items: List<T> = emptyList()
        @SuppressLint("NotifyDataSetChanged")
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(layout, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) = bind(holder.itemView, items[position])
}

/** Shows a clip's watched share on a bar, hiding it when there is nothing to show. */
fun ProgressBar.showPercent(pct: Int) {
    visibility = if (pct in 1..99) View.VISIBLE else View.GONE
    progress = pct.coerceAtLeast(0)
}

/** Android 15 draws apps edge to edge; pad so content never sits under the status or nav bar. */
fun AppCompatActivity.setupBars(root: View, toolbar: View) {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    window.statusBarColor = Color.TRANSPARENT
    WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false
    ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
        val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        root.setPadding(b.left, 0, b.right, b.bottom)
        toolbar.setPadding(toolbar.paddingLeft, b.top, toolbar.paddingRight, toolbar.paddingBottom)
        insets
    }
}

/**
 * Whether playback should go on as the player leaves the screen: only when the screen was
 * switched off (not when the user went to another app) and the user allows background sound.
 */
fun Activity.keepPlayingInBackground(playing: Boolean): Boolean {
    val screenOff = !(getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
    return playing && screenOff && !isFinishing && Settings.backgroundAudio(this)
}

fun Context.toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

class SkipValues(
    val introStart: Int,
    val introEnd: Int,
    val outro: Int,
    val skipIntro: Boolean,
    val skipOutro: Boolean,
    val skipManual: Boolean = false,
)

object Dialogs {
    /** A list of choices in a sheet that slides up from the bottom. */
    fun menu(act: Activity, title: String, items: List<Pair<CharSequence, () -> Unit>>) {
        val sheet = BottomSheetDialog(act)
        val view = act.layoutInflater.inflate(R.layout.sheet_menu, null)
        view.findViewById<TextView>(R.id.txtSheetTitle).text = title
        val list = view.findViewById<LinearLayout>(R.id.sheetItems)
        items.forEach { (label, action) ->
            val row = act.layoutInflater.inflate(R.layout.item_sheet_row, list, false) as TextView
            row.text = label
            row.setOnClickListener {
                sheet.dismiss()
                action()
            }
            list.addView(row)
        }
        sheet.setContentView(view)
        sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        sheet.behavior.skipCollapsed = true
        sheet.show()
    }

    /** Pick one of [options]; the current one is ticked. */
    fun choose(act: Activity, title: String, options: List<String>, selected: Int, onPick: (Int) -> Unit) {
        menu(act, title, options.mapIndexed { i, name ->
            (if (i == selected) "✓  $name" else name) to { onPick(i) }
        })
    }

    fun askText(act: Activity, title: String, initial: String, onOk: (String) -> Unit) {
        val view = act.layoutInflater.inflate(R.layout.dialog_text, null)
        val input = view.findViewById<EditText>(R.id.edText)
        input.setText(initial)
        MaterialAlertDialogBuilder(act)
            .setTitle(title)
            .setView(view)
            .setPositiveButton("ตกลง") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isEmpty()) act.toast("ยังไม่ได้ใส่ชื่อ") else onOk(text)
            }
            .setNegativeButton("ยกเลิก", null)
            .show()
        input.requestFocus()
    }

    fun confirm(act: Activity, message: String, okText: String, onOk: () -> Unit) {
        MaterialAlertDialogBuilder(act)
            .setMessage(message)
            .setPositiveButton(okText) { _, _ -> onOk() }
            .setNegativeButton("ยกเลิก", null)
            .show()
    }

    /** Lets the user pick a category ("" = none) or create a new one. */
    fun chooseCategory(act: Activity, current: String?, onChosen: (String) -> Unit) {
        val cats = Store.categories(act)
        val items = mutableListOf<Pair<String, () -> Unit>>()
        fun mark(c: String) = if (c == current) "✓ " else ""
        items.add("${mark("")}ไม่มีหมวดหมู่" to { onChosen("") })
        cats.forEach { c -> items.add("${mark(c)}$c" to { onChosen(c) }) }
        items.add("+ สร้างหมวดหมู่ใหม่" to {
            askText(act, "ชื่อหมวดหมู่ใหม่", "") { name ->
                Store.addCategory(act, name)
                onChosen(name)
            }
        })
        menu(act, "เลือกหมวดหมู่", items)
    }

    /**
     * Skip-time editor. [showFlags] shows the series-wide on/off switches;
     * [onReset] adds a button that drops a per-episode override.
     */
    fun skipSettings(
        act: Activity,
        title: String,
        initial: SkipValues,
        showFlags: Boolean,
        onReset: (() -> Unit)?,
        onSave: (SkipValues) -> Unit,
    ) {
        val view = act.layoutInflater.inflate(R.layout.dialog_skip, null)
        val chkIntro = view.findViewById<CheckBox>(R.id.chkIntro)
        val chkOutro = view.findViewById<CheckBox>(R.id.chkOutro)
        val chkManual = view.findViewById<CheckBox>(R.id.chkManual)
        val edIntroStart = view.findViewById<EditText>(R.id.edIntroStart)
        val edIntroEnd = view.findViewById<EditText>(R.id.edIntroEnd)
        val edOutro = view.findViewById<EditText>(R.id.edOutro)

        chkIntro.isChecked = initial.skipIntro
        chkOutro.isChecked = initial.skipOutro
        chkIntro.visibility = if (showFlags) View.VISIBLE else View.GONE
        chkOutro.visibility = if (showFlags) View.VISIBLE else View.GONE
        chkManual.isChecked = initial.skipManual
        chkManual.visibility = if (showFlags) View.VISIBLE else View.GONE
        edIntroStart.setText(TimeText.format(initial.introStart))
        if (initial.introEnd > 0) edIntroEnd.setText(TimeText.format(initial.introEnd))
        if (initial.outro > 0) edOutro.setText(TimeText.format(initial.outro))

        val builder = MaterialAlertDialogBuilder(act)
            .setTitle(title)
            .setView(view)
            .setPositiveButton("บันทึก") { _, _ ->
                val start = TimeText.parse(edIntroStart.text.toString())
                val end = TimeText.parse(edIntroEnd.text.toString())
                val outro = TimeText.parse(edOutro.text.toString())
                if (start == null || end == null || outro == null) {
                    act.toast("รูปแบบเวลาไม่ถูกต้อง เช่น 90 หรือ 1:30")
                    return@setPositiveButton
                }
                if (end in 1..start) {
                    act.toast("เวลาจบเพลงเปิดต้องมากกว่าเวลาเริ่ม")
                    return@setPositiveButton
                }
                onSave(SkipValues(start, end, outro, chkIntro.isChecked, chkOutro.isChecked, chkManual.isChecked))
            }
            .setNegativeButton("ยกเลิก", null)
        if (onReset != null) builder.setNeutralButton("ใช้ค่าของเรื่อง") { _, _ -> onReset() }
        builder.show()
    }
}

object Resume {
    /** Button text for continuing a series, e.g. "▶ ดูต่อ ตอนที่ 3 · 12:34". */
    fun label(s: Series): String {
        val idx = s.resumeIndex() ?: return "✓ ดูครบแล้ว · เริ่มดูใหม่"
        val ep = s.episodes[idx]
        if (s.watchedCount == 0 && idx == 0 && !ep.hasProgress) return "▶ เริ่มดูตั้งแต่ตอนแรก"
        return "▶ ดูต่อ ตอนที่ ${idx + 1} · ${TimeText.ms(ep.resumePosition)}"
    }

    /** Opens the clip left unfinished at its saved second; offers a fresh start once all are watched. */
    fun play(act: Activity, s: Series) {
        if (s.episodes.isEmpty()) return
        val idx = s.resumeIndex()
        if (idx == null) {
            Dialogs.confirm(act, "ดู “${s.name}” ครบทุกตอนแล้ว\nเริ่มดูใหม่ตั้งแต่ตอนแรก?", "เริ่มใหม่") {
                s.resetProgress()
                Store.put(act, s)
                PlayerActivity.start(act, s.id, 0, 0L)
            }
        } else {
            PlayerActivity.start(act, s.id, idx, s.episodes[idx].resumePosition)
        }
    }

    fun confirmReset(act: Activity, s: Series, onDone: () -> Unit) {
        Dialogs.confirm(act, "ล้างการจดจำการดูของ “${s.name}” ทุกตอน แล้วเริ่มดูใหม่ตั้งแต่ตอนแรก?", "รีเซ็ต") {
            s.resetProgress()
            Store.put(act, s)
            act.toast("รีเซ็ตแล้ว")
            onDone()
        }
    }
}
