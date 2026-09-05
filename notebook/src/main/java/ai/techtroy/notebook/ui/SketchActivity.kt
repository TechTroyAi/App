package ai.techtroy.notebook.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.core.widget.ImageViewCompat
import ai.techtroy.notebook.R
import ai.techtroy.notebook.core.circle
import ai.techtroy.notebook.core.dp
import ai.techtroy.notebook.core.gold
import ai.techtroy.notebook.core.hairline
import ai.techtroy.notebook.core.muted
import ai.techtroy.notebook.core.tint
import ai.techtroy.notebook.core.toast
import ai.techtroy.notebook.data.Attachment
import ai.techtroy.notebook.data.AttachmentKind
import ai.techtroy.notebook.ink.InkView
import ai.techtroy.notebook.ink.PaperStyle
import ai.techtroy.notebook.ink.StrokeDoc
import ai.techtroy.notebook.ink.Tool

/** Quick single-canvas sketch attached to a text/checklist note. Pen / highlighter / eraser, 12 colors, 3 widths, white/dark bg, zoom+pan. */
class SketchActivity : BaseActivity() {
    private var noteId = -1L
    private var attachmentId: Long? = null
    private lateinit var ink: InkView
    private lateinit var undo: ImageButton
    private lateinit var redo: ImageButton
    private var dark = true
    private var dirty = false
    private val widths = floatArrayOf(2.2f, 4f, 7.5f)
    private var widthIdx = 1
    private lateinit var toolBtns: Map<Tool, ImageButton>
    private lateinit var widthBtns: List<View>
    private lateinit var palette: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        noteId = intent.getLongExtra(EXTRA_NOTE, -1); attachmentId = intent.getLongExtra(EXTRA_ATT, -1).takeIf { it > 0 }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // top bar
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(4), dp(4), dp(4)) }
        fun ib(icon: Int, desc: Int, onClick: () -> Unit) = ImageButton(this).apply { setImageResource(icon); contentDescription = getString(desc); background = with(android.util.TypedValue()) { theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, this, true); getDrawable(resourceId) }; ImageViewCompat.setImageTintList(this, tint(gold())); setOnClickListener { onClick() } }
        top.addView(ib(R.drawable.ic_back, R.string.back) { saveAndFinish() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        undo = ib(R.drawable.ic_undo, R.string.undo) { ink.undo() }; redo = ib(R.drawable.ic_redo, R.string.redo) { ink.redo() }
        top.addView(undo, LinearLayout.LayoutParams(dp(44), dp(44))); top.addView(redo, LinearLayout.LayoutParams(dp(44), dp(44)))
        top.addView(ib(R.drawable.ic_theme, R.string.paper_dark) { dark = !dark; ink.paperDark = dark; ink.doc.bg = if (dark) 0xFF141416.toInt() else 0xFFFFFFFF.toInt(); autoInk(); ink.invalidate(); dirty = true }, LinearLayout.LayoutParams(dp(44), dp(44)))
        top.addView(ib(R.drawable.ic_clear, R.string.clear_canvas) { Sheets.confirm(this, getString(R.string.clear_canvas), null, getString(R.string.clear_canvas), danger = true) { ink.clearAll() } }, LinearLayout.LayoutParams(dp(44), dp(44)))
        top.addView(ib(R.drawable.ic_check, R.string.done) { saveAndFinish() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        root.addView(top)
        // canvas
        ink = InkView(this).apply { paper = PaperStyle.BLANK; paperDark = dark; doc = StrokeDoc(1000f, 1300f); strokeWidth = widths[widthIdx]; color = app.prefs.penColor; shapeSnap = false }
        ink.listener = object : InkView.Listener { override fun onDocChanged() { dirty = true; updateUndo() } }
        val frame = FrameLayout(this).apply { addView(ink) }
        root.addView(frame, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        // palette
        palette = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(10), dp(6), dp(10), dp(6)) }
        root.addView(HorizontalScrollView(this).apply { addView(palette); isHorizontalScrollBarEnabled = false })
        // tools
        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), 0, dp(8), dp(6)); minimumHeight = dp(52) }
        val map = LinkedHashMap<Tool, ImageButton>()
        listOf(Tool.PEN to R.drawable.ic_pen, Tool.HIGHLIGHTER to R.drawable.ic_highlighter, Tool.ERASER to R.drawable.ic_eraser).forEach { (t, icon) -> map[t] = ib(icon, R.string.pen) { setTool(t) }.also { tools.addView(it, LinearLayout.LayoutParams(0, dp(46), 1f)) } }
        toolBtns = map
        widthBtns = widths.mapIndexed { i, w -> FrameLayout(this).apply { addView(View(this@SketchActivity).apply { background = circle(gold()) }, FrameLayout.LayoutParams(dp((6 + w * 2).toInt()), dp((6 + w * 2).toInt()), Gravity.CENTER)); setOnClickListener { widthIdx = i; ink.strokeWidth = w; renderWidths() } } }
        widthBtns.forEach { tools.addView(it, LinearLayout.LayoutParams(0, dp(46), 1f)) }
        root.addView(tools)
        padForIme(tools)
        setContentView(root)
        renderPalette(); setTool(Tool.PEN); renderWidths()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() { saveAndFinish() } })
        load()
    }

    private fun load() {
        val aid = attachmentId ?: return
        app.async({ app.repo.attachment(aid) }) { a ->
            if (a?.data == null) return@async
            val d = StrokeDoc.parse(a.data)
            dark = d.bg != 0xFFFFFFFF.toInt(); ink.paperDark = dark
            ink.doc = d; ink.fitPage()
        }
    }

    private fun autoInk() { if (!dark && ink.color == 0xFFF5F2EA.toInt()) { ink.color = 0xFF111111.toInt(); renderPalette() } else if (dark && ink.color == 0xFF111111.toInt()) { ink.color = 0xFFF5F2EA.toInt(); renderPalette() } }

    private fun setTool(t: Tool) { ink.tool = t; toolBtns.forEach { (k, b) -> b.alpha = if (k == t) 1f else 0.45f; b.background = if (k == t) ai.techtroy.notebook.core.roundRect(ai.techtroy.notebook.core.ThemeManager.attr(this, R.attr.nbGoldDim), 12f, this) else null } }
    private fun renderWidths() { widthBtns.forEachIndexed { i, v -> v.alpha = if (i == widthIdx) 1f else 0.4f } }
    private fun renderPalette() {
        palette.removeAllViews()
        ai.techtroy.notebook.core.ThemeManager.penColors.forEach { c ->
            palette.addView(FrameLayout(this).apply {
                background = circle(c, if (c == ink.color) gold() else hairline(), dp(if (c == ink.color) 3 else 1))
                setOnClickListener { ink.color = c; app.prefs.penColor = c; if (ink.tool == Tool.ERASER) setTool(Tool.PEN); renderPalette() }
            }, LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(10) })
        }
    }
    private fun updateUndo() { undo.alpha = if (ink.canUndo()) 1f else 0.35f; redo.alpha = if (ink.canRedo()) 1f else 0.35f }

    private fun saveAndFinish() {
        if (!dirty || (ink.doc.isEmpty && attachmentId == null)) { finish(); return }
        val json = ink.doc.toJson()
        val bmp: Bitmap = ink.renderBitmap(720)
        val aid = attachmentId; val nid = noteId
        app.async({
            val thumb = app.files.saveThumb(bmp, png = true)
            if (aid != null) { val old = app.repo.attachment(aid); old?.thumbPath?.let { app.files.delete(it) }; app.repo.updateAttachmentData(aid, json, thumb) }
            else {
                val rel = thumb  // for sketches the "file" is the PNG render; vector data lives in `data`
                app.repo.addAttachment(Attachment(0, nid, AttachmentKind.SKETCH, "Sketch", rel, "image/png", app.files.file(rel).length(), 0, bmp.width, bmp.height, thumb, System.currentTimeMillis(), 0, json, null))
            }
        }) { finish() }
    }

    companion object {
        const val EXTRA_NOTE = "note_id"; const val EXTRA_ATT = "att_id"
        fun intent(ctx: Context, noteId: Long, attachmentId: Long?) = Intent(ctx, SketchActivity::class.java).putExtra(EXTRA_NOTE, noteId).putExtra(EXTRA_ATT, attachmentId ?: -1L)
    }
}
