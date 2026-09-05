package ai.techtroy.notebook.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Editable
import android.text.Spannable
import android.text.Spanned
import android.text.style.ReplacementSpan
import ai.techtroy.notebook.core.dp
import ai.techtroy.notebook.core.gold
import ai.techtroy.notebook.data.Repo

/**
 * Inline gold chip for a `[[id:123]]` token. The token text stays in the Editable (so it saves
 * and survives round trips); the span just paints a chip over it and carries the target id.
 */
class LinkChipSpan(private val ctx: Context, val noteId: Long, val label: String, private val deleted: Boolean) : ReplacementSpan() {
    private val padH = ctx.dp(8f); private val padV = ctx.dp(2f); private val gold = ctx.gold()
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ctx.dp(1f) }
    private var textWidth = 0f

    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        val p = Paint(paint).apply { textSize = paint.textSize * 0.92f; isFakeBoldText = true }
        textWidth = p.measureText(display())
        return (textWidth + padH * 2 + ctx.dp(16f)).toInt()
    }

    private fun display() = "🔗 " + (if (deleted) "(deleted)" else label.ifBlank { "Untitled" }).take(28)

    override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val p = Paint(paint).apply { textSize = paint.textSize * 0.92f; isFakeBoldText = true }
        val fm = p.fontMetrics
        val h = fm.descent - fm.ascent
        val rect = RectF(x, y + fm.ascent - padV, x + textWidth + padH * 2 + ctx.dp(16f), y + fm.descent + padV)
        val r = h * 0.45f
        bg.color = if (deleted) 0x22808080 else (gold and 0x00FFFFFF) or 0x24000000
        border.color = if (deleted) 0x66808080 else gold
        canvas.drawRoundRect(rect, r, r, bg); canvas.drawRoundRect(rect, r, r, border)
        p.color = if (deleted) 0xFF8A8A8A.toInt() else gold
        canvas.drawText(display(), x + padH + ctx.dp(8f), y.toFloat(), p)
    }
}

object LinkSpans {
    /** Re-apply chip spans over every [[id:n]] token. titles: id -> title (null => deleted). */
    fun apply(ctx: Context, e: Editable, titles: Map<Long, String?>) {
        e.getSpans(0, e.length, LinkChipSpan::class.java).forEach { e.removeSpan(it) }
        Repo.LINK_TOKEN.findAll(e).forEach { m ->
            val id = m.groupValues[1].toLong()
            val title = titles[id]
            e.setSpan(LinkChipSpan(ctx, id, title ?: "", title == null), m.range.first, m.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    fun ids(text: CharSequence): List<Long> = Repo.LINK_TOKEN.findAll(text).map { it.groupValues[1].toLong() }.toList()

    /** Which chip, if any, is at the given offset (used to open on tap). */
    fun chipAt(s: Spannable, offset: Int): LinkChipSpan? = s.getSpans(offset, offset, LinkChipSpan::class.java).firstOrNull()
}
