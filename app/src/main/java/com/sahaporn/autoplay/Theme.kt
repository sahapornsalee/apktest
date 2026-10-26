package com.sahaporn.autoplay

import android.content.Context
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate

/** Light/dark mode and accent colour chosen by the user. */
object AppTheme {
    class Accent(val name: String, val color: Int, val style: Int)

    val ACCENTS = listOf(
        Accent("แดง", 0xFFE50914.toInt(), R.style.Accent_Red),
        Accent("ชมพู", 0xFFD81B60.toInt(), R.style.Accent_Pink),
        Accent("ม่วง", 0xFF8E24AA.toInt(), R.style.Accent_Purple),
        Accent("คราม", 0xFF3949AB.toInt(), R.style.Accent_Indigo),
        Accent("น้ำเงิน", 0xFF1E88E5.toInt(), R.style.Accent_Blue),
        Accent("เขียวอมฟ้า", 0xFF00897B.toInt(), R.style.Accent_Teal),
        Accent("เขียว", 0xFF43A047.toInt(), R.style.Accent_Green),
        Accent("ส้ม", 0xFFF4511E.toInt(), R.style.Accent_Orange),
        Accent("เหลืองทอง", 0xFFFFA000.toInt(), R.style.Accent_Amber),
        Accent("น้ำตาล", 0xFF6D4C41.toInt(), R.style.Accent_Brown),
        Accent("เทา", 0xFF546E7A.toInt(), R.style.Accent_Gray),
    )

    val MODES = listOf("ตามระบบ", "สว่าง", "มืด", "มืดสนิท (ดำล้วน ประหยัดแบตจอ OLED)")

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun mode(ctx: Context) = prefs(ctx).getInt("theme_mode", 2).coerceIn(0, MODES.size - 1)
    fun accent(ctx: Context) = prefs(ctx).getInt("theme_accent", 0).coerceIn(0, ACCENTS.size - 1)

    /** Changes whenever the look changes, so open screens know to redraw. */
    fun key(ctx: Context) = "${mode(ctx)}/${accent(ctx)}"

    private fun nightMode(mode: Int) = when (mode) {
        1 -> AppCompatDelegate.MODE_NIGHT_NO
        2, 3 -> AppCompatDelegate.MODE_NIGHT_YES
        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }

    /** Call before super.onCreate. */
    fun apply(activity: AppCompatActivity) {
        val mode = mode(activity)
        AppCompatDelegate.setDefaultNightMode(nightMode(mode))
        activity.theme.applyStyle(ACCENTS[accent(activity)].style, true)
        if (mode == 3) activity.theme.applyStyle(R.style.ThemeOverlay_AutoPlay_Black, true)
    }

    fun dot(color: Int, label: String): CharSequence =
        SpannableString("●  $label").apply { setSpan(ForegroundColorSpan(color), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }

    fun showPicker(act: AppCompatActivity) {
        val current = ACCENTS[accent(act)]
        Dialogs.menu(
            act, "🎨 ธีมและสี", listOf(
                "🌓 โหมด: ${MODES[mode(act)]}" to { pickMode(act) },
                dot(current.color, "สีหลัก: ${current.name}") to { pickAccent(act) },
            )
        )
    }

    fun pickMode(act: AppCompatActivity) {
        val now = mode(act)
        Dialogs.menu(act, "โหมด", MODES.mapIndexed { i, name ->
            (if (i == now) "✓ $name" else name) to {
                prefs(act).edit().putInt("theme_mode", i).apply()
                AppCompatDelegate.setDefaultNightMode(nightMode(i))
                act.recreate()
            }
        })
    }

    fun pickAccent(act: AppCompatActivity) {
        val now = accent(act)
        Dialogs.menu(act, "สีหลัก", ACCENTS.mapIndexed { i, a ->
            dot(a.color, if (i == now) "${a.name}  ✓" else a.name) to {
                prefs(act).edit().putInt("theme_accent", i).apply()
                act.recreate()
            }
        })
    }
}

/** Base screen that picks up the chosen theme, and redraws when it changed elsewhere. */
open class ThemedActivity : AppCompatActivity() {
    private var themeKey = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        AppTheme.apply(this)
        themeKey = AppTheme.key(this)
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        if (themeKey != AppTheme.key(this)) recreate()
    }
}
