package ai.techtroy.notebook.core

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import androidx.appcompat.app.AppCompatDelegate
import ai.techtroy.notebook.R
import ai.techtroy.notebook.data.NoteColor

object ThemeManager {
    fun applyNightMode(theme: Theme) {
        AppCompatDelegate.setDefaultNightMode(if (theme == Theme.IVORY_GOLD) AppCompatDelegate.MODE_NIGHT_NO else AppCompatDelegate.MODE_NIGHT_YES)
    }

    fun styleFor(theme: Theme): Int = when (theme) {
        Theme.BLACK_GOLD -> R.style.Theme_Notebook
        Theme.IVORY_GOLD -> R.style.Theme_Notebook_Ivory
        Theme.AMOLED -> R.style.Theme_Notebook_Amoled
    }

    fun apply(activity: Activity, theme: Theme) { activity.setTheme(styleFor(theme)) }

    fun isLight(theme: Theme) = theme == Theme.IVORY_GOLD

    fun attr(context: Context, attr: Int): Int {
        val tv = TypedValue()
        context.theme.resolveAttribute(attr, tv, true)
        return if (tv.resourceId != 0) context.getColor(tv.resourceId) else tv.data
    }

    /** Card background for a note tint in the current theme. */
    fun noteTint(context: Context, color: NoteColor, light: Boolean): Int {
        if (color == NoteColor.NONE) return attr(context, R.attr.nbCard)
        val res = if (light) when (color) {
            NoteColor.GOLD -> R.color.tint_gold_l; NoteColor.WINE -> R.color.tint_wine_l; NoteColor.EMERALD -> R.color.tint_emerald_l
            NoteColor.SAPPHIRE -> R.color.tint_sapphire_l; NoteColor.PLUM -> R.color.tint_plum_l; NoteColor.BRONZE -> R.color.tint_bronze_l
            NoteColor.SLATE -> R.color.tint_slate_l; NoteColor.NONE -> R.color.ivory_card
        } else when (color) {
            NoteColor.GOLD -> R.color.tint_gold; NoteColor.WINE -> R.color.tint_wine; NoteColor.EMERALD -> R.color.tint_emerald
            NoteColor.SAPPHIRE -> R.color.tint_sapphire; NoteColor.PLUM -> R.color.tint_plum; NoteColor.BRONZE -> R.color.tint_bronze
            NoteColor.SLATE -> R.color.tint_slate; NoteColor.NONE -> R.color.graphite
        }
        return context.getColor(res)
    }

    /** Swatch color shown in the picker (saturated version). */
    fun swatch(color: NoteColor): Int = when (color) {
        NoteColor.NONE -> Color.parseColor("#2A2A2E"); NoteColor.GOLD -> Color.parseColor("#D4AF37"); NoteColor.WINE -> Color.parseColor("#8E3A43")
        NoteColor.EMERALD -> Color.parseColor("#2F8F63"); NoteColor.SAPPHIRE -> Color.parseColor("#3D5FA0"); NoteColor.PLUM -> Color.parseColor("#7A4B8F")
        NoteColor.BRONZE -> Color.parseColor("#9A6B3A"); NoteColor.SLATE -> Color.parseColor("#5E6B78")
    }

    val folderColors = intArrayOf(
        0xFFD4AF37.toInt(), 0xFF6E9BD1.toInt(), 0xFF4FB08A.toInt(), 0xFFB15A8C.toInt(),
        0xFFE0873A.toInt(), 0xFF8E7CC3.toInt(), 0xFF57C7C0.toInt(), 0xFFC7554D.toInt(),
    )

    val penColors = intArrayOf(
        0xFFD4AF37.toInt(), 0xFFF5F2EA.toInt(), 0xFF6E9BD1.toInt(), 0xFF4FB08A.toInt(), 0xFFC7554D.toInt(), 0xFFB15A8C.toInt(),
        0xFFE0873A.toInt(), 0xFF8E7CC3.toInt(), 0xFF57C7C0.toInt(), 0xFFA7A39A.toInt(), 0xFF111111.toInt(), 0xFFE5C36B.toInt(),
    )

    fun fontScale(size: Int): Float = when (size) { 0 -> 0.9f; 2 -> 1.15f; 3 -> 1.32f; else -> 1f }
}
