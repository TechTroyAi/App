package ai.techtroy.notebook.ink

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.ImageViewCompat
import ai.techtroy.notebook.R
import ai.techtroy.notebook.core.FileStore
import ai.techtroy.notebook.core.Fmt
import ai.techtroy.notebook.core.ThemeManager
import ai.techtroy.notebook.core.circle
import ai.techtroy.notebook.core.dp
import ai.techtroy.notebook.core.gold
import ai.techtroy.notebook.core.hairline
import ai.techtroy.notebook.core.muted
import ai.techtroy.notebook.core.roundRect
import ai.techtroy.notebook.core.show
import ai.techtroy.notebook.core.showKeyboard
import ai.techtroy.notebook.core.textPrimary
import ai.techtroy.notebook.core.tint
import ai.techtroy.notebook.core.toast
import ai.techtroy.notebook.data.Attachment
import ai.techtroy.notebook.data.AttachmentKind
import ai.techtroy.notebook.data.Page
import ai.techtroy.notebook.sys.RecorderService
import ai.techtroy.notebook.ui.BaseActivity
import ai.techtroy.notebook.ui.Media
import ai.techtroy.notebook.ui.Sheets
import java.io.File

/**
 * "Pages" handwriting editor. Multi-page, paper templates, 7 brushes, lasso, ruler, zoom-to-write,
 * text boxes + images, audio-synced writing, PDF export. Finger-first: 1 finger draws, 2 fingers zoom/pan,
 * 2-finger tap undo, 3-finger tap redo.
 */
class PagesActivity : BaseActivity(), InkView.Listener {

    private var noteId = -1L
    private var pages: List<Page> = emptyList()
    private var index = 0
    private lateinit var ink: InkView
    private lateinit var zoomInk: InkView
    private lateinit var zoomStrip: View
    private lateinit var thumbStrip: LinearLayout
    private lateinit var pageLabel: TextView
    private lateinit var lassoMenu: View
    private lateinit var brushBar: LinearLayout
    private lateinit var toolRow: LinearLayout
    private lateinit var recPill: View
    private lateinit var audioBar: View
    private lateinit var undoBtn: ImageButton
    private lateinit var redoBtn: ImageButton
    private var dirty = false
    private var saving = false
    private val handler = Handler(Looper.getMainLooper())
    private val saveRunnable = Runnable { save() }

    private var brush = Tool.PEN
    private val brushWidths = mapOf(Tool.PEN to 3.5f, Tool.BALLPOINT to 2.6f, Tool.TECHNICAL to 2.2f, Tool.PENCIL to 3.2f, Tool.HIGHLIGHTER to 6f, Tool.LASER to 3f, Tool.ERASER to 6f)
    private var widthScale = 1f
    private var zoomMode = false
    private var zoomWindow = RectF(0.05f, 0.1f, 0.55f, 0.22f)
    private lateinit var brushBtns: LinkedHashMap<Tool, View>
    private lateinit var toolBtns: LinkedHashMap<String, View>

    // audio sync
    private var recService: RecorderService? = null
    private var recStartDocTime = 0f
    private var audioAtt: Attachment? = null
    private var audioOffsetMs = 0f     // doc time at which audio started
    private var player: MediaPlayer? = null

    private val pickImage = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) insertImage(uri) }
    private val exportPdf = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri -> if (uri != null) doExport(uri) }
    private val micPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) startRec() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        noteId = intent.getLongExtra(EXTRA_NOTE, -1)
        setContentView(buildUi())
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() { if (zoomMode) setZoom(false) else { save(sync = true); finish() } } })
        load()
    }

    // ------------------------------------------------------------------- UI

    private fun ib(icon: Int, desc: Int, onClick: () -> Unit) = ImageButton(this).apply {
        setImageResource(icon); contentDescription = getString(desc)
        background = with(android.util.TypedValue()) { theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, this, true); getDrawable(resourceId) }
        ImageViewCompat.setImageTintList(this, tint(gold())); setOnClickListener { onClick() }
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(ThemeManager.attr(this@PagesActivity, android.R.attr.colorBackground)) }
        // top bar
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(4), dp(4), 0) }
        top.addView(ib(R.drawable.ic_back, R.string.back) { onBackPressedDispatcher.onBackPressed() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        pageLabel = TextView(this).apply { textSize = 13f; setTextColor(muted()); setPadding(dp(6), 0, dp(6), 0) }
        top.addView(pageLabel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        recPill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = roundRect(0, 15f, this@PagesActivity, getColor(R.color.danger)); setPadding(dp(10), dp(4), dp(6), dp(4)); visibility = View.GONE
            addView(View(this@PagesActivity).apply { background = circle(getColor(R.color.danger)); startAnimation(android.view.animation.AlphaAnimation(1f, 0.2f).apply { duration = 600; repeatMode = android.view.animation.Animation.REVERSE; repeatCount = android.view.animation.Animation.INFINITE }) }, LinearLayout.LayoutParams(dp(8), dp(8)))
            addView(TextView(this@PagesActivity).apply { id = R.id.recTime; text = "REC 0:00"; textSize = 12f; setTextColor(textPrimary()); typeface = android.graphics.Typeface.MONOSPACE; setPadding(dp(6), 0, dp(6), 0) })
            addView(ib(R.drawable.ic_stop, R.string.stop) { stopRec() }.apply { ImageViewCompat.setImageTintList(this, tint(getColor(R.color.danger))) }, LinearLayout.LayoutParams(dp(28), dp(28)))
        }
        top.addView(recPill)
        undoBtn = ib(R.drawable.ic_undo, R.string.undo) { current().undo() }; redoBtn = ib(R.drawable.ic_redo, R.string.redo) { current().redo() }
        top.addView(undoBtn, LinearLayout.LayoutParams(dp(44), dp(44))); top.addView(redoBtn, LinearLayout.LayoutParams(dp(44), dp(44)))
        top.addView(ib(R.drawable.ic_paper, R.string.paper) { paperSheet() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        top.addView(ib(R.drawable.ic_more, R.string.more) { moreSheet() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        root.addView(top)

        // canvas area
        val frame = FrameLayout(this)
        ink = InkView(this).apply { listener = this@PagesActivity; imageLoader = { p -> app.files.loadBitmap(p, 1200) } }
        frame.addView(ink, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        // lasso floating menu
        lassoMenu = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; background = roundRect(ThemeManager.attr(this@PagesActivity, R.attr.nbCardRaised), 14f, this@PagesActivity, gold()); visibility = View.GONE; setPadding(dp(4), 0, dp(4), 0); elevation = dp(8).toFloat()
            fun t(label: String, onClick: () -> Unit) = addView(TextView(this@PagesActivity).apply { text = label; textSize = 13f; setTextColor(gold()); typeface = android.graphics.Typeface.DEFAULT_BOLD; setPadding(dp(10), dp(10), dp(10), dp(10)); setOnClickListener { onClick() } })
            t("Resize") { transformSelection(1.2f, 0f) }; t("Shrink") { transformSelection(1 / 1.2f, 0f) }; t("Rotate") { transformSelection(1f, 15f) }
            t("Color") { Sheets.sheet(this@PagesActivity, getString(R.string.recolor)) { r, d -> r.addView(paletteRow { c -> current().recolorSelection(c); d.dismiss() }) } }
            t("Dup") { current().duplicateSelection() }; t("Delete") { current().deleteSelection() }
        }
        frame.addView(lassoMenu, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(10) })
        // audio playback bar (when an audio-synced recording exists)
        audioBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = roundRect(ThemeManager.attr(this@PagesActivity, R.attr.nbCardRaised), 22f, this@PagesActivity, hairline()); visibility = View.GONE; setPadding(dp(6), dp(4), dp(14), dp(4)); elevation = dp(6).toFloat()
            addView(ib(R.drawable.ic_play, R.string.playback_synced) { togglePlayback() }.apply { id = R.id.btnPlay }, LinearLayout.LayoutParams(dp(40), dp(40)))
            addView(TextView(this@PagesActivity).apply { id = R.id.audioLabel; text = getString(R.string.playback_synced); textSize = 12f; setTextColor(muted()) })
        }
        frame.addView(audioBar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(12) })
        root.addView(frame, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // zoom-to-write strip
        zoomStrip = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE; setBackgroundColor(ThemeManager.attr(this@PagesActivity, R.attr.nbCard))
            addView(View(this@PagesActivity).apply { setBackgroundColor(gold()) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(2)))
            val row = LinearLayout(this@PagesActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(2), dp(8), dp(2)) }
            row.addView(TextView(this@PagesActivity).apply { text = "ZOOM · write here, the strip advances as you reach the edge"; textSize = 10f; setTextColor(gold()); letterSpacing = 0.08f }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(ib(R.drawable.ic_arrow_left, R.string.back) { shiftZoom(-0.4f) }, LinearLayout.LayoutParams(dp(36), dp(36)))
            row.addView(ib(R.drawable.ic_arrow_right, R.string.back) { shiftZoom(0.4f) }, LinearLayout.LayoutParams(dp(36), dp(36)))
            row.addView(ib(R.drawable.ic_reorder, R.string.back) { newZoomLine() }, LinearLayout.LayoutParams(dp(36), dp(36)))
            row.addView(ib(R.drawable.ic_close, R.string.close) { setZoom(false) }, LinearLayout.LayoutParams(dp(36), dp(36)))
            addView(row)
            zoomInk = InkView(this@PagesActivity).apply { showBackgroundPaper = true; imageLoader = { p -> app.files.loadBitmap(p, 800) } }
            addView(zoomInk, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(170)))
        }
        root.addView(zoomStrip)

        // page thumbnails strip
        thumbStrip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(10), dp(6), dp(10), dp(4)); gravity = Gravity.CENTER_VERTICAL }
        root.addView(HorizontalScrollView(this).apply { addView(thumbStrip); isHorizontalScrollBarEnabled = false; setBackgroundColor(ThemeManager.attr(this@PagesActivity, R.attr.nbCard)) })

        // brush bar
        brushBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), dp(4), dp(6), 0); setBackgroundColor(ThemeManager.attr(this@PagesActivity, R.attr.nbCard)) }
        brushBtns = LinkedHashMap()
        listOf(Tool.PEN to R.drawable.ic_pen, Tool.BALLPOINT to R.drawable.ic_pen, Tool.TECHNICAL to R.drawable.ic_ruler, Tool.PENCIL to R.drawable.ic_pencil, Tool.HIGHLIGHTER to R.drawable.ic_highlighter, Tool.LASER to R.drawable.ic_laser, Tool.ERASER to R.drawable.ic_eraser).forEach { (t, icon) ->
            val b = ib(icon, R.string.pen) { setBrush(t) }
            b.setOnLongClickListener { brushOptions(t); true }
            brushBtns[t] = b; brushBar.addView(b, LinearLayout.LayoutParams(0, dp(44), 1f))
        }
        val colorDot = FrameLayout(this).apply { id = R.id.colorDot; addView(View(this@PagesActivity).apply { id = R.id.colorDotInner; background = circle(app.prefs.penColor, gold(), dp(2)) }, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER)); setOnClickListener { Sheets.sheet(this@PagesActivity, getString(R.string.color)) { r, d -> r.addView(paletteRow { c -> setColor(c); d.dismiss() }) } } }
        brushBar.addView(colorDot, LinearLayout.LayoutParams(0, dp(44), 1f))
        root.addView(brushBar)

        // tools row
        toolRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), 0, dp(6), dp(4)); setBackgroundColor(ThemeManager.attr(this@PagesActivity, R.attr.nbCard)) }
        toolBtns = LinkedHashMap()
        fun tool(key: String, icon: Int, desc: Int, onClick: () -> Unit) { val b = ib(icon, desc) { onClick() }; toolBtns[key] = b; toolRow.addView(b, LinearLayout.LayoutParams(0, dp(44), 1f)) }
        tool("lasso", R.drawable.ic_lasso, R.string.lasso) { setBrush(Tool.LASSO) }
        tool("ruler", R.drawable.ic_ruler, R.string.ruler) { ink.rulerMode = !ink.rulerMode; zoomInk.rulerMode = ink.rulerMode; refreshToolStates() }
        tool("zoom", R.drawable.ic_zoom_write, R.string.zoom_write) { setZoom(!zoomMode) }
        tool("text", R.drawable.ic_text_box, R.string.text_box) { addTextBox() }
        tool("image", R.drawable.ic_image, R.string.insert_image) { pickImage.launch(arrayOf("image/*")) }
        tool("mic", R.drawable.ic_mic, R.string.record_while_writing) { if (recService?.isRecording == true) stopRec() else micTapped() }
        tool("page", R.drawable.ic_page_add, R.string.add_page) { addPage() }
        root.addView(toolRow)
        padForIme(toolRow)
        return root
    }

    private fun paletteRow(onPick: (Int) -> Unit): View {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(14), dp(8), dp(14), dp(12)) }
        val scroll = HorizontalScrollView(this).apply { addView(wrap); isHorizontalScrollBarEnabled = false }
        ThemeManager.penColors.forEach { c -> wrap.addView(FrameLayout(this).apply { background = circle(c, if (c == ink.color) gold() else hairline(), dp(if (c == ink.color) 3 else 1)); setOnClickListener { onPick(c) } }, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(10) }) }
        return scroll
    }

    private fun current(): InkView = if (zoomMode) zoomInk else ink

    private fun setBrush(t: Tool) {
        brush = t
        listOf(ink, zoomInk).forEach { v -> v.tool = t; v.strokeWidth = (brushWidths[t] ?: 3.5f) * widthScale }
        brushBtns.forEach { (k, b) -> b.alpha = if (k == t) 1f else 0.45f; b.background = if (k == t) roundRect(ThemeManager.attr(this, R.attr.nbGoldDim), 12f, this) else null }
        if (t != Tool.LASSO) { ink.clearSelection(); lassoMenu.show(false) }
        refreshToolStates()
    }

    private fun refreshToolStates() {
        toolBtns["lasso"]?.let { it.alpha = if (brush == Tool.LASSO) 1f else 0.6f; it.background = if (brush == Tool.LASSO) roundRect(ThemeManager.attr(this, R.attr.nbGoldDim), 12f, this) else null }
        toolBtns["ruler"]?.let { it.alpha = if (ink.rulerMode) 1f else 0.6f; it.background = if (ink.rulerMode) roundRect(ThemeManager.attr(this, R.attr.nbGoldDim), 12f, this) else null }
        toolBtns["zoom"]?.let { it.alpha = if (zoomMode) 1f else 0.6f; it.background = if (zoomMode) roundRect(ThemeManager.attr(this, R.attr.nbGoldDim), 12f, this) else null }
        toolBtns["mic"]?.let { ImageViewCompat.setImageTintList(it as ImageButton, tint(if (recService?.isRecording == true) getColor(R.color.danger) else gold())) }
        undoBtn.alpha = if (current().canUndo()) 1f else 0.35f; redoBtn.alpha = if (current().canRedo()) 1f else 0.35f
    }

    private fun setColor(c: Int) { ink.color = c; zoomInk.color = c; app.prefs.penColor = c; findViewById<View>(R.id.colorDotInner).background = circle(c, gold(), dp(2)); if (brush == Tool.ERASER || brush == Tool.LASSO) setBrush(Tool.PEN) }

    private fun brushOptions(t: Tool) {
        Sheets.sheet(this, getString(when (t) { Tool.PEN -> R.string.pen; Tool.BALLPOINT -> R.string.ballpoint; Tool.TECHNICAL -> R.string.technical_pen; Tool.PENCIL -> R.string.pencil; Tool.HIGHLIGHTER -> R.string.highlighter; Tool.LASER -> R.string.laser; else -> R.string.eraser })) { r, d ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(12)) }
            listOf(0.6f to "Thin", 1f to "Medium", 1.6f to "Thick", 2.4f to "Bold").forEach { (s, label) ->
                row.addView(TextView(this).apply { text = label; textSize = 13f; setTextColor(if (widthScale == s) 0xFF0B0B0C.toInt() else gold()); typeface = android.graphics.Typeface.DEFAULT_BOLD; background = if (widthScale == s) roundRect(gold(), 18f, this@PagesActivity) else roundRect(0, 18f, this@PagesActivity, hairline()); setPadding(dp(16), dp(8), dp(16), dp(8)); setOnClickListener { widthScale = s; setBrush(t); d.dismiss() } }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
            }
            r.addView(row)
            if (t == Tool.ERASER) r.addView(Sheets.row(this, Sheets.Item(R.drawable.ic_eraser, if (ink.eraserPixel) "Mode: pixel eraser (tap for stroke eraser)" else "Mode: stroke eraser (tap for pixel eraser)") {}) { ink.eraserPixel = !ink.eraserPixel; zoomInk.eraserPixel = ink.eraserPixel; d.dismiss() })
            r.addView(Sheets.row(this, Sheets.Item(R.drawable.ic_ruler, if (ink.shapeSnap) "Shape snap: on (hold still at the end of a stroke)" else "Shape snap: off") {}) { ink.shapeSnap = !ink.shapeSnap; zoomInk.shapeSnap = ink.shapeSnap; d.dismiss() })
        }
    }

    // ------------------------------------------------------------------ data

    private fun load() {
        app.async({ var ps = app.repo.pages(noteId); if (ps.isEmpty()) { app.repo.addPage(noteId, app.prefs.pagesPaper, app.prefs.pagesDark); ps = app.repo.pages(noteId) }; ps to app.repo.attachments(noteId).firstOrNull { it.kind == AttachmentKind.AUDIO && it.name.startsWith("Pages audio") } }) { (ps, audio) ->
            pages = ps; audioAtt = audio
            audioBar.show(audio != null)
            showPage(index.coerceIn(0, pages.size - 1))
            setBrush(Tool.PEN); setColor(app.prefs.penColor)
            renderThumbs()
        }
    }

    private fun showPage(i: Int) {
        if (pages.isEmpty()) return
        if (dirty) save(sync = true)
        index = i
        val p = pages[i]
        val doc = StrokeDoc.parse(p.strokes, p.objects)
        ink.paper = PaperStyle.from(p.paper); ink.paperDark = p.dark
        ink.doc = doc; ink.fitPage()
        zoomInk.paper = ink.paper; zoomInk.paperDark = p.dark; zoomInk.doc = doc
        pageLabel.text = getString(R.string.page_x_of_y, i + 1, pages.size)
        ink.clearSelection(); lassoMenu.show(false)
        if (!p.dark && ink.color == 0xFFF5F2EA.toInt()) setColor(0xFF111111.toInt())
        renderThumbs(); refreshToolStates()
    }

    private fun renderThumbs() {
        thumbStrip.removeAllViews()
        pages.forEachIndexed { i, p ->
            val v = FrameLayout(this).apply {
                background = roundRect(if (p.dark) 0xFF141416.toInt() else 0xFFFBF7EC.toInt(), 6f, this@PagesActivity, if (i == index) gold() else hairline(), if (i == index) 2f else 1f)
                clipToOutline = true
                val iv = ImageView(this@PagesActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                addView(iv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                if (p.thumbPath != null) app.async({ app.files.loadBitmap(p.thumbPath, 200) }) { b -> iv.setImageBitmap(b) }
                addView(TextView(this@PagesActivity).apply { text = "${i + 1}"; textSize = 9f; setTextColor(gold()); setPadding(dp(3), 0, dp(3), 0) }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END))
                setOnClickListener { showPage(i) }
                setOnLongClickListener { pageMenu(i); true }
            }
            thumbStrip.addView(v, LinearLayout.LayoutParams(dp(34), dp(46)).apply { marginEnd = dp(8) })
        }
        thumbStrip.addView(ib(R.drawable.ic_add, R.string.add_page) { addPage() }, LinearLayout.LayoutParams(dp(34), dp(46)))
    }

    private fun addPage() {
        save(sync = true)
        val p = pages.getOrNull(index)
        app.async({ app.repo.addPage(noteId, p?.paper ?: app.prefs.pagesPaper, p?.dark ?: app.prefs.pagesDark); app.repo.pages(noteId) }) { ps -> pages = ps; dirty = false; showPage(ps.size - 1) }
    }

    private fun pageMenu(i: Int) = Sheets.menu(this, getString(R.string.page_x_of_y, i + 1, pages.size), listOf(
        Sheets.Item(R.drawable.ic_trash, getString(R.string.delete_page), danger = true) { if (pages.size <= 1) { toast("Last page can't be deleted"); return@Item }; val id = pages[i].id; app.async({ app.repo.deletePage(id) { app.files.delete(it) }; app.repo.pages(noteId) }) { ps -> pages = ps; dirty = false; showPage(minOf(index, ps.size - 1)) } },
    ))

    private fun save(sync: Boolean = false) {
        handler.removeCallbacks(saveRunnable)
        if (!dirty || pages.isEmpty()) return
        dirty = false
        val p = pages[index]
        val json = ink.doc.toJson(); val objects = ink.doc.objectsJson()
        val thumb = ink.renderBitmap(360)
        val work = {
            val tRel = app.files.saveThumb(thumb, "pages", png = false)
            p.thumbPath?.let { app.files.delete(it) }
            val np = p.copy(strokes = json, objects = objects, thumbPath = tRel)
            app.repo.savePage(np)
            np
        }
        if (sync) { val np = work(); pages = pages.map { if (it.id == np.id) np else it } }
        else app.async(work) { np -> pages = pages.map { if (it.id == np.id) np else it }; renderThumbs() }
    }

    // ---------------------------------------------------------- InkView.Listener

    override fun onDocChanged() { dirty = true; handler.removeCallbacks(saveRunnable); handler.postDelayed(saveRunnable, 1200); refreshToolStates(); if (zoomMode) ink.invalidateLayer() else zoomInk.invalidateLayer() }
    override fun onLassoSelection(selected: List<Stroke>, bounds: RectF?) { lassoMenu.show(selected.isNotEmpty()) }
    override fun onStrokeFinished(s: Stroke) { if (zoomMode) autoAdvance(s) }
    override fun onObjectTapped(o: InkObject) { objectMenu(o) }
    override fun onStrokeTapped(s: Stroke) {
        // audio sync: jump playback to when this stroke was written
        val a = audioAtt ?: return
        val t = s.startTime - audioOffsetMs
        if (t >= 0 && t < a.durationMs) { ensurePlayer()?.let { p -> p.seekTo(t.toInt()); if (!p.isPlaying) { p.start(); findViewById<ImageButton>(R.id.btnPlay).setImageResource(R.drawable.ic_pause) } } }
    }

    private fun transformSelection(scale: Float, rotate: Float) {
        val b = ink.selectionBounds ?: return
        val m = Matrix(); val aspect = ink.pageAspect()
        // scale/rotate around center; correct for non-square normalized space
        m.postTranslate(-b.centerX(), -b.centerY()); m.postScale(1f, aspect); m.postRotate(rotate); m.postScale(scale, scale); m.postScale(1f, 1f / aspect); m.postTranslate(b.centerX(), b.centerY())
        ink.transformSelection(m)
    }

    // ---------------------------------------------------------- zoom-to-write

    private fun setZoom(on: Boolean) {
        zoomMode = on
        zoomStrip.show(on)
        if (on) {
            val w = 0.5f
            // window height keeps aspect with the strip
            val stripAspect = dp(170).toFloat() / ink.width.coerceAtLeast(1).toFloat()
            zoomWindow = RectF(0.04f, 0.08f, 0.04f + w, 0.08f + w * stripAspect / ink.pageAspect())
            zoomInk.doc = ink.doc; zoomInk.paper = ink.paper; zoomInk.paperDark = ink.paperDark; zoomInk.tool = ink.tool; zoomInk.color = ink.color; zoomInk.strokeWidth = ink.strokeWidth
            zoomInk.writeWindow = zoomWindow; zoomInk.listener = this; zoomInk.fitPage(); zoomInk.invalidateLayer()
            ink.readOnly = true
        } else { zoomInk.writeWindow = null; ink.readOnly = false; ink.invalidateLayer() }
        refreshToolStates(); ink.invalidate()
    }

    private fun shiftZoom(frac: Float) { val w = zoomWindow.width(); zoomWindow.offset(w * frac, 0f); if (zoomWindow.right > 1f) newZoomLine() else if (zoomWindow.left < 0f) zoomWindow.offsetTo(0f, zoomWindow.top); zoomInk.writeWindow = zoomWindow; zoomInk.invalidateLayer() }
    private fun newZoomLine() { val h = zoomWindow.height(); zoomWindow.offsetTo(0.04f, (zoomWindow.top + h * 0.8f).coerceAtMost(1f - h)); zoomInk.writeWindow = zoomWindow; zoomInk.invalidateLayer() }
    private fun autoAdvance(s: Stroke) { val b = s.bounds(); if (b.right > zoomWindow.right - zoomWindow.width() * 0.12f) shiftZoom(0.5f) }

    // --------------------------------------------------------------- objects

    private fun addTextBox() {
        val et = EditText(this).apply { hint = getString(R.string.text_box); setTextColor(textPrimary()); setHintTextColor(muted()); minLines = 2 }
        val wrap = FrameLayout(this).apply { setPadding(dp(22), dp(8), dp(22), 0); addView(et) }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle(R.string.text_box).setView(wrap).setPositiveButton(R.string.ok) { _, _ ->
            val text = et.text.toString(); if (text.isBlank()) return@setPositiveButton
            ink.beforeExternalChange()
            ink.doc.objects += InkObject("text", RectF(0.1f, 0.15f, 0.9f, 0.3f), text, if (ink.paperDark) 0xFFF5F2EA.toInt() else 0xFF1E1A12.toInt(), 30f)
            ink.commitExternalChange()
        }.setNegativeButton(R.string.cancel, null).show()
        et.showKeyboard()
    }

    private fun insertImage(uri: Uri) {
        app.async({ val imp = app.files.importUri(uri); if (imp == null) null else { val (thumb, dims) = app.files.makeImageThumb(imp.rel); Triple(thumb ?: imp.rel, dims[0], dims[1]) } }) { r ->
            if (r == null) { toast(getString(R.string.error_generic)); return@async }
            val (path, w, h) = r
            val aspect = if (w > 0 && h > 0) h.toFloat() / w else 1f
            val nw = 0.6f; val nh = nw * aspect / ink.pageAspect()
            ink.beforeExternalChange(); ink.doc.objects += InkObject("image", RectF(0.2f, 0.2f, 0.2f + nw, 0.2f + nh), path = path); ink.commitExternalChange()
        }
    }

    private fun objectMenu(o: InkObject) {
        val items = ArrayList<Sheets.Item>()
        items += Sheets.Item(R.drawable.ic_swap, getString(R.string.move) + " ↓") { ink.beforeExternalChange(); o.rect.offset(0f, 0.08f); ink.commitExternalChange() }
        items += Sheets.Item(R.drawable.ic_swap, getString(R.string.move) + " ↑") { ink.beforeExternalChange(); o.rect.offset(0f, -0.08f); ink.commitExternalChange() }
        items += Sheets.Item(R.drawable.ic_zoom_write, getString(R.string.resize) + " +") { ink.beforeExternalChange(); val c = o.rect.centerX(); val cy = o.rect.centerY(); val w = o.rect.width() * 1.2f; val h = o.rect.height() * 1.2f; o.rect.set(c - w / 2, cy - h / 2, c + w / 2, cy + h / 2); ink.commitExternalChange() }
        items += Sheets.Item(R.drawable.ic_zoom_write, getString(R.string.resize) + " −") { ink.beforeExternalChange(); val c = o.rect.centerX(); val cy = o.rect.centerY(); val w = o.rect.width() / 1.2f; val h = o.rect.height() / 1.2f; o.rect.set(c - w / 2, cy - h / 2, c + w / 2, cy + h / 2); ink.commitExternalChange() }
        if (o.type == "text") items += Sheets.Item(R.drawable.ic_text, "Edit text") { Sheets.input(this, getString(R.string.text_box), o.text, "") { t -> ink.beforeExternalChange(); o.text = t; ink.commitExternalChange() } }
        items += Sheets.Item(R.drawable.ic_trash, getString(R.string.delete), danger = true) { ink.beforeExternalChange(); ink.doc.objects.remove(o); ink.commitExternalChange() }
        Sheets.menu(this, if (o.type == "text") getString(R.string.text_box) else getString(R.string.insert_image), items)
    }

    // ----------------------------------------------------------------- paper

    private fun paperSheet() {
        Sheets.sheet(this, getString(R.string.paper)) { r, d ->
            val grid = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(16), dp(6), dp(16), dp(10)) }
            PaperStyle.entries.forEach { st ->
                val cell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setOnClickListener { applyPaper(st, ink.paperDark); d.dismiss() } }
                val preview = object : View(this) { override fun onDraw(c: Canvas) { PaperPainter().draw(c, width.toFloat(), height.toFloat(), st, ink.paperDark, 0) } }.apply { background = roundRect(0, 8f, this@PagesActivity, if (ink.paper == st) gold() else hairline(), if (ink.paper == st) 2f else 1f); clipToOutline = true }
                cell.addView(preview, LinearLayout.LayoutParams(dp(52), dp(68)))
                cell.addView(TextView(this).apply { text = st.name.lowercase().replaceFirstChar { it.uppercase() }; textSize = 11f; setTextColor(if (ink.paper == st) gold() else muted()); setPadding(0, dp(4), 0, 0) })
                grid.addView(cell, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            r.addView(grid)
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, dp(4), 0, dp(10)) }
            listOf(true to getString(R.string.paper_dark), false to getString(R.string.paper_ivory)).forEach { (dark, label) ->
                row.addView(TextView(this).apply { text = label; textSize = 13f; typeface = android.graphics.Typeface.DEFAULT_BOLD; setTextColor(if (ink.paperDark == dark) 0xFF0B0B0C.toInt() else gold()); background = if (ink.paperDark == dark) roundRect(gold(), 18f, this@PagesActivity) else roundRect(0, 18f, this@PagesActivity, hairline()); setPadding(dp(18), dp(8), dp(18), dp(8)); setOnClickListener { applyPaper(ink.paper, dark); d.dismiss() } }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(10) })
            }
            r.addView(row)
        }
    }

    private fun applyPaper(style: PaperStyle, dark: Boolean) {
        val flip = dark != ink.paperDark
        ink.paper = style; ink.paperDark = dark; zoomInk.paper = style; zoomInk.paperDark = dark
        app.prefs.pagesPaper = style.id; app.prefs.pagesDark = dark
        if (flip) {
            // auto-flip ink: warm white <-> near-black so writing stays legible
            ink.beforeExternalChange()
            ink.doc.strokes.forEach { s -> if (dark && s.color == 0xFF111111.toInt()) s.color = 0xFFF5F2EA.toInt() else if (!dark && s.color == 0xFFF5F2EA.toInt()) s.color = 0xFF111111.toInt() }
            ink.doc.objects.forEach { o -> if (o.type == "text") o.color = if (dark) 0xFFF5F2EA.toInt() else 0xFF1E1A12.toInt() }
            ink.commitExternalChange()
            if (dark && ink.color == 0xFF111111.toInt()) setColor(0xFFF5F2EA.toInt()) else if (!dark && ink.color == 0xFFF5F2EA.toInt()) setColor(0xFF111111.toInt())
        }
        val p = pages[index]; pages = pages.map { if (it.id == p.id) it.copy(paper = style.id, dark = dark) else it }
        app.async({ app.repo.setPagesPaper(noteId, style.id, dark) }) { }
        pages = pages.map { it.copy(paper = style.id, dark = dark) }
        dirty = true; ink.invalidateLayer(); zoomInk.invalidateLayer(); renderThumbs()
    }

    // ------------------------------------------------------------------ audio

    private fun micTapped() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) startRec() else micPerm.launch(android.Manifest.permission.RECORD_AUDIO)
    }
    private val recConn = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName?, service: android.os.IBinder?) { recService = (service as RecorderService.LocalBinder).service; recService?.listener = { runOnUiThread { findViewById<TextView>(R.id.recTime).text = "REC " + Fmt.duration(recService?.elapsedMs ?: 0) } }; refreshToolStates() }
        override fun onServiceDisconnected(name: android.content.ComponentName?) { recService = null }
    }
    private var bound = false
    override fun onStart() { super.onStart(); bindService(Intent(this, RecorderService::class.java), recConn, Context.BIND_AUTO_CREATE); bound = true }
    override fun onStop() { super.onStop(); if (bound) { recService?.listener = null; unbindService(recConn); bound = false }; save(sync = true); player?.pause() }
    override fun onDestroy() { player?.release(); player = null; super.onDestroy() }

    private fun startRec() {
        recStartDocTime = (System.currentTimeMillis() - ink.doc.t0).toFloat()
        RecorderService.start(this, noteId); recPill.show(true); refreshToolStates()
    }
    private fun stopRec() {
        val r = recService?.stopAndSave(); recPill.show(false); refreshToolStates()
        if (r == null) return
        val offset = recStartDocTime
        app.async({
            val rel = app.files.adoptTemp(r.file, "m4a")
            val id = app.repo.addAttachment(Attachment(0, noteId, AttachmentKind.AUDIO, "Pages audio · offset ${offset.toLong()}", rel, "audio/mp4", app.files.file(rel).length(), r.durationMs, 0, 0, null, System.currentTimeMillis(), 0, Media.encodeWave(r.amplitudes), null))
            app.repo.attachment(id)
        }) { a -> audioAtt = a; audioOffsetMs = offset; audioBar.show(true); toast(getString(R.string.playback_synced)) }
    }
    private fun ensurePlayer(): MediaPlayer? {
        player?.let { return it }
        val a = audioAtt ?: return null
        audioOffsetMs = a.name.substringAfter("offset ", "0").trim().toFloatOrNull() ?: 0f
        return runCatching { MediaPlayer().apply { setDataSource(app.files.file(a.path).absolutePath); prepare(); setOnCompletionListener { findViewById<ImageButton>(R.id.btnPlay).setImageResource(R.drawable.ic_play) } } }.getOrNull()?.also { player = it }
    }
    private fun togglePlayback() {
        val p = ensurePlayer() ?: return
        val btn = findViewById<ImageButton>(R.id.btnPlay)
        if (p.isPlaying) { p.pause(); btn.setImageResource(R.drawable.ic_play) } else { p.start(); btn.setImageResource(R.drawable.ic_pause) }
    }

    // ------------------------------------------------------------------- more

    private fun moreSheet() = Sheets.menu(this, null, listOf(
        Sheets.Item(R.drawable.ic_export, getString(R.string.export_pdf)) { save(sync = true); exportPdf.launch("notebook-pages.pdf") },
        Sheets.Item(R.drawable.ic_select_all, getString(R.string.select_all)) { setBrush(Tool.LASSO); ink.selectAll() },
        Sheets.Item(R.drawable.ic_clear, getString(R.string.clear_canvas), danger = true) { Sheets.confirm(this, getString(R.string.clear_canvas), null, getString(R.string.clear_canvas), danger = true) { ink.clearAll() } },
    ))

    private fun doExport(uri: Uri) {
        toast("Exporting…")
        val list = pages
        app.async({
            val pdf = PdfDocument()
            list.forEachIndexed { i, p ->
                val doc = StrokeDoc.parse(p.strokes, p.objects)
                val v = InkView(this).apply { paper = PaperStyle.from(p.paper); paperDark = p.dark; this.doc = doc; imageLoader = { path -> app.files.loadBitmap(path, 1200) } }
                val bmp = v.renderBitmap(1240)
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(bmp.width, bmp.height, i + 1).create())
                page.canvas.drawBitmap(bmp, 0f, 0f, null); pdf.finishPage(page); bmp.recycle()
            }
            contentResolver.openOutputStream(uri)!!.use { pdf.writeTo(it) }; pdf.close(); true
        }) { toast(getString(R.string.exported)) }
    }

    companion object {
        const val EXTRA_NOTE = "note_id"
        fun intent(ctx: Context, noteId: Long) = Intent(ctx, PagesActivity::class.java).putExtra(EXTRA_NOTE, noteId)
    }
}
