package ai.techtroy.notebook.core

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import com.google.android.material.snackbar.Snackbar
import ai.techtroy.notebook.R
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

val Context.dp: Float get() = resources.displayMetrics.density
fun Context.dp(v: Int): Int = (v * dp + 0.5f).toInt()
fun Context.dp(v: Float): Float = v * dp
fun View.dp(v: Int): Int = context.dp(v)

fun Context.gold() = ThemeManager.attr(this, R.attr.nbGold)
fun Context.muted() = ThemeManager.attr(this, R.attr.nbTextMuted)
fun Context.faint() = ThemeManager.attr(this, R.attr.nbTextFaint)
fun Context.card() = ThemeManager.attr(this, R.attr.nbCard)
fun Context.cardRaised() = ThemeManager.attr(this, R.attr.nbCardRaised)
fun Context.hairline() = ThemeManager.attr(this, R.attr.nbHairline)
fun Context.textPrimary() = ThemeManager.attr(this, android.R.attr.textColorPrimary)
fun Context.bg() = ThemeManager.attr(this, android.R.attr.colorBackground)

fun roundRect(fill: Int, radiusDp: Float, ctx: Context, stroke: Int = 0, strokeDp: Float = 1f): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.RECTANGLE; cornerRadius = ctx.dp(radiusDp); setColor(fill)
    if (stroke != 0) setStroke(ctx.dp(strokeDp).coerceAtLeast(1f).toInt(), stroke)
}
fun circle(fill: Int, stroke: Int = 0, strokePx: Int = 0): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.OVAL; setColor(fill); if (stroke != 0) setStroke(strokePx, stroke)
}
fun tint(c: Int): ColorStateList = ColorStateList.valueOf(c)

fun View.show(v: Boolean) { visibility = if (v) View.VISIBLE else View.GONE }
fun View.invisible(v: Boolean) { visibility = if (v) View.INVISIBLE else View.VISIBLE }

fun Activity.hideKeyboard() {
    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    currentFocus?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
}
fun View.showKeyboard() {
    requestFocus()
    post { (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(this, InputMethodManager.SHOW_IMPLICIT) }
}

fun Context.toast(msg: CharSequence) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
fun View.snack(msg: CharSequence, action: String? = null, anchor: View? = null, onAction: (() -> Unit)? = null): Snackbar {
    val s = Snackbar.make(this, msg, if (action != null) 5000 else Snackbar.LENGTH_SHORT)
    if (action != null && onAction != null) s.setAction(action) { onAction() }
    anchor?.let { s.anchorView = it }
    s.show(); return s
}

object Fmt {
    private val timeFmt get() = SimpleDateFormat("h:mm a", Locale.getDefault())
    private val dayFmt get() = SimpleDateFormat("MMM d", Locale.getDefault())
    private val fullFmt get() = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
    private val fullTimeFmt get() = SimpleDateFormat("EEE, MMM d · h:mm a", Locale.getDefault())
    private val longFmt get() = SimpleDateFormat("EEEE, MMMM d, yyyy 'at' h:mm a", Locale.getDefault())

    fun relative(ctx: Context, t: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - t
        if (diff < 60_000) return ctx.getString(R.string.just_now)
        if (DateUtils.isToday(t)) return timeFmt.format(Date(t))
        if (DateUtils.isToday(t + DateUtils.DAY_IN_MILLIS)) return ctx.getString(R.string.yesterday)
        val c = Calendar.getInstance(); val thisYear = c.get(Calendar.YEAR); c.timeInMillis = t
        return if (c.get(Calendar.YEAR) == thisYear) dayFmt.format(Date(t)) else fullFmt.format(Date(t))
    }
    fun dateTime(t: Long): String = fullTimeFmt.format(Date(t))
    fun long(t: Long): String = longFmt.format(Date(t))
    fun date(t: Long): String = fullFmt.format(Date(t))
    fun duration(ms: Long): String {
        val s = (ms / 1000).toInt(); val m = s / 60; val h = m / 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m % 60, s % 60) else String.format(Locale.US, "%d:%02d", m, s % 60)
    }
    fun epochDay(t: Long = System.currentTimeMillis()): Long = (t + java.util.TimeZone.getDefault().getOffset(t)) / DateUtils.DAY_IN_MILLIS
}

fun Context.spToPx(sp: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)
