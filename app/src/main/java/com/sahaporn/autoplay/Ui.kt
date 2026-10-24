package com.sahaporn.autoplay

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** One line in a list: either a group header or an item, each with an optional ⋮ menu. */
class Row(
    val title: String,
    val subtitle: String? = null,
    val header: Boolean = false,
    val highlight: Boolean = false,
    val onMore: (() -> Unit)? = null,
    val onClick: () -> Unit,
)

class RowAdapter(private val ctx: Context) : BaseAdapter() {
    var rows: List<Row> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun getCount() = rows.size
    override fun getItem(position: Int) = rows[position]
    override fun getItemId(position: Int) = position.toLong()
    override fun getViewTypeCount() = 2
    override fun getItemViewType(position: Int) = if (rows[position].header) 1 else 0

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val row = rows[position]
        val layout = if (row.header) R.layout.item_header else R.layout.item_row
        val v = convertView ?: LayoutInflater.from(ctx).inflate(layout, parent, false)
        val title = v.findViewById<TextView>(R.id.txtTitle)
        title.text = row.title
        if (!row.header) title.setTypeface(null, if (row.highlight) Typeface.BOLD else Typeface.NORMAL)
        val sub = v.findViewById<TextView>(R.id.txtSubtitle)
        sub.text = row.subtitle
        sub.visibility = if (row.subtitle.isNullOrEmpty()) View.GONE else View.VISIBLE
        val more = v.findViewById<View>(R.id.btnMore)
        more.visibility = if (row.onMore == null) View.INVISIBLE else View.VISIBLE
        more.setOnClickListener { row.onMore?.invoke() }
        v.setOnClickListener { row.onClick() }
        v.setOnLongClickListener {
            row.onMore?.invoke()
            row.onMore != null
        }
        return v
    }
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

fun Context.toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

class SkipValues(
    val introStart: Int,
    val introEnd: Int,
    val outro: Int,
    val skipIntro: Boolean,
    val skipOutro: Boolean,
)

object Dialogs {
    fun menu(act: Activity, title: String, items: List<Pair<String, () -> Unit>>) {
        AlertDialog.Builder(act)
            .setTitle(title)
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .show()
    }

    fun askText(act: Activity, title: String, initial: String, onOk: (String) -> Unit) {
        val input = EditText(act).apply {
            setText(initial)
            setSelectAllOnFocus(true)
            isSingleLine = true
        }
        val pad = (20 * act.resources.displayMetrics.density).toInt()
        val box = FrameLayout(act).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(act)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("ตกลง") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isEmpty()) act.toast("ยังไม่ได้ใส่ชื่อ") else onOk(text)
            }
            .setNegativeButton("ยกเลิก", null)
            .show()
        input.requestFocus()
    }

    fun confirm(act: Activity, message: String, okText: String, onOk: () -> Unit) {
        AlertDialog.Builder(act)
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
        val edIntroStart = view.findViewById<EditText>(R.id.edIntroStart)
        val edIntroEnd = view.findViewById<EditText>(R.id.edIntroEnd)
        val edOutro = view.findViewById<EditText>(R.id.edOutro)

        chkIntro.isChecked = initial.skipIntro
        chkOutro.isChecked = initial.skipOutro
        chkIntro.visibility = if (showFlags) View.VISIBLE else View.GONE
        chkOutro.visibility = if (showFlags) View.VISIBLE else View.GONE
        edIntroStart.setText(TimeText.format(initial.introStart))
        if (initial.introEnd > 0) edIntroEnd.setText(TimeText.format(initial.introEnd))
        if (initial.outro > 0) edOutro.setText(TimeText.format(initial.outro))

        val builder = AlertDialog.Builder(act)
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
                onSave(SkipValues(start, end, outro, chkIntro.isChecked, chkOutro.isChecked))
            }
            .setNegativeButton("ยกเลิก", null)
        if (onReset != null) builder.setNeutralButton("ใช้ค่าของเรื่อง") { _, _ -> onReset() }
        builder.show()
    }
}
