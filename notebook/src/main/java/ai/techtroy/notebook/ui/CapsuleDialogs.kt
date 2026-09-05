package ai.techtroy.notebook.ui

import android.app.DatePickerDialog
import android.graphics.Typeface
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.ImageViewCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import ai.techtroy.notebook.R
import ai.techtroy.notebook.core.Fmt
import ai.techtroy.notebook.core.dp
import ai.techtroy.notebook.core.gold
import ai.techtroy.notebook.core.muted
import ai.techtroy.notebook.core.textPrimary
import ai.techtroy.notebook.core.tint
import ai.techtroy.notebook.core.toast
import ai.techtroy.notebook.data.Note
import java.util.Calendar

/** Time Capsule: seal a note until a date. It shows blurred + gold-sealed on Home and returns as an Echo. */
object CapsuleDialogs {

    fun seal(a: BaseActivity, noteId: Long, onSealed: () -> Unit) {
        val c = Calendar.getInstance().apply { add(Calendar.MONTH, 1) }
        MaterialAlertDialogBuilder(a).setTitle(R.string.capsule_seal_title).setMessage(R.string.capsule_seal_body)
            .setPositiveButton(R.string.apply) { _, _ ->
                DatePickerDialog(a, { _, y, m, d ->
                    val until = Calendar.getInstance().apply { set(y, m, d, 8, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
                    if (until <= System.currentTimeMillis()) { a.toast(a.getString(R.string.error_generic)); return@DatePickerDialog }
                    a.app.async({ a.app.repo.setCapsule(noteId, until) }) { a.toast(a.getString(R.string.capsule_sealed, Fmt.date(until))); onSealed() }
                }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).apply { datePicker.minDate = System.currentTimeMillis() + 86_400_000L }.show()
            }.setNegativeButton(R.string.cancel, null).show()
    }

    fun showSealed(a: BaseActivity, n: Note) {
        val ctx = a
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(ctx.dp(24), ctx.dp(28), ctx.dp(24), ctx.dp(8)) }
        col.addView(ImageView(ctx).apply { setImageResource(R.drawable.ic_capsule); ImageViewCompat.setImageTintList(this, tint(ctx.gold())) }, LinearLayout.LayoutParams(ctx.dp(48), ctx.dp(48)))
        col.addView(TextView(ctx).apply { text = n.title.ifBlank { "Time capsule" }; textSize = 18f; typeface = Typeface.DEFAULT_BOLD; setTextColor(ctx.textPrimary()); gravity = Gravity.CENTER; setPadding(0, ctx.dp(14), 0, ctx.dp(6)) })
        col.addView(TextView(ctx).apply { text = ctx.getString(R.string.capsule_locked_msg, Fmt.date(n.capsuleUntil ?: 0)); textSize = 14f; setTextColor(ctx.muted()); gravity = Gravity.CENTER })
        col.addView(TextView(ctx).apply { text = "Sealed " + Fmt.date(n.updatedAt); textSize = 12f; setTextColor(ctx.muted()); gravity = Gravity.CENTER; setPadding(0, ctx.dp(10), 0, 0); alpha = 0.7f })
        MaterialAlertDialogBuilder(ctx).setView(col).setPositiveButton(R.string.ok, null)
            .setNeutralButton(R.string.delete) { _, _ -> Sheets.confirm(a, a.getString(R.string.delete), null, a.getString(R.string.delete), danger = true) { a.app.async({ a.app.repo.trash(listOf(n.id)) }) { } } }
            .show()
    }
}
