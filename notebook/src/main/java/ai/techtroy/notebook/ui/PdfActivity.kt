package ai.techtroy.notebook.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.view.Gravity
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
import ai.techtroy.notebook.ink.InkView
import ai.techtroy.notebook.ink.PaperStyle
import ai.techtroy.notebook.ink.Stroke
import ai.techtroy.notebook.ink.StrokeDoc
import ai.techtroy.notebook.ink.Tool
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * PDF annotation: an ink layer over PdfRenderer pages. Same brushes as Pages, lasso, quick colors,
 * page thumbnails/jump, PDF text search (Android 15+ renderer API; otherwise a graceful "not supported" note),
 * export = flattened annotated PDF via SAF.
 *
 * Storage: attachment.annotations = {"v":1,"pages":{"0":<StrokeDoc json>,"3":...}}
 */
class PdfActivity : BaseActivity(), InkView.Listener {

    private var attId = -1L
    private var att: Attachment? = null
    private var pfd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private var pageCount = 0
    private var index = 0
    private val docs = HashMap<Int, StrokeDoc>()
    private val handler = Handler(Looper.getMainLooper())
    private var dirty = false
    private val saveRunnable = Runnable { save() }

    private lateinit var ink: InkView
    private lateinit var pageLabel: TextView
    private lateinit var thumbStrip: LinearLayout
    private lateinit var lassoMenu: View
    private lateinit var undoBtn: ImageButton
    private lateinit var redoBtn: ImageButton
    private lateinit var brushBtns: LinkedHashMap<Tool, View>
    private lateinit var quickColors: LinearLayout
    private var brush = Tool.PEN
    private var widthScale = 1f
    private val brushWidths = mapOf(Tool.PEN to 3f, Tool.BALLPOINT to 2.4f, Tool.TECHNICAL to 2f, Tool.PENCIL to 3f, Tool.HIGHLIGHTER to 7f, Tool.LASER to 3f, Tool.ERASER to 6f)
    private val palette = intArrayOf(0xFFC7554D.toInt(), 0xFF3B6FD6.toInt(), 0xFF3FA36B.toInt(), 0xFFD4AF37.toInt(), 0xFF111111.toInt(), 0xFFF5F2EA.toInt())

    private val exportPdf = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri -> if (uri != null) doExport(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        attId = intent.getLongExtra(EXTRA_ATT, -1)
        setContentView(buildUi())
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() { save(sync = true); finish() } })
        load()
    }

    private fun ib(icon: Int, desc: Int, onClick: () -> Unit) = ImageButton(this).apply {
        setImageResource(icon); contentDescription = getString(desc)
        background = with(android.util.TypedValue()) { theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, this, true); getDrawable(resourceId) }
        ImageViewCompat.setImageTintList(this, tint(gold())); setOnClickListener { onClick() }
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF0B0B0C.toInt()) }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(4), dp(4), 0); setBackgroundColor(ThemeManager.attr(this@PdfActivity, android.R.attr.colorBackground)) }
        top.addView(ib(R.drawable.ic_back, R.string.back) { onBackPressedDispatcher.onBackPressed() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        pageLabel = TextView(this).apply { textSize = 13f; setTextColor(muted()); setPadding(dp(6), 0, dp(6), 0); setOnClickListener { jumpDialog() } }
        top.addView(pageLabel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        undoBtn = ib(R.drawable.ic_undo, R.string.undo) { ink.undo() }; redoBtn = ib(R.drawable.ic_redo, R.string.redo) { ink.redo() }
        top.addView(undoBtn, LinearLayout.LayoutParams(dp(44), dp(44))); top.addView(redoBtn, LinearLayout.LayoutParams(dp(44), dp(44)))
        top.addView(ib(R.drawable.ic_search, R.string.search_in_pdf) { searchDialog() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        top.addView(ib(R.drawable.ic_more, R.string.more) { moreSheet() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        root.addView(top)

        val frame = FrameLayout(this)
        ink = InkView(this).apply { listener = this@PdfActivity; paper = PaperStyle.BLANK; paperDark = false; showBackgroundPaper = false; maxScale = 10f }
        frame.addView(ink, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        lassoMenu = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; background = roundRect(ThemeManager.attr(this@PdfActivity, R.attr.nbCardRaised), 14f, this@PdfActivity, gold()); visibility = View.GONE; setPadding(dp(4), 0, dp(4), 0); elevation = dp(8).toFloat()
            fun t(label: String, onClick: () -> Unit) = addView(TextView(this@PdfActivity).apply { text = label; textSize = 13f; setTextColor(gold()); typeface = android.graphics.Typeface.DEFAULT_BOLD; setPadding(dp(10), dp(10), dp(10), dp(10)); setOnClickListener { onClick() } })
            t("Color") { Sheets.sheet(this@PdfActivity, getString(R.string.recolor)) { r, d -> r.addView(paletteRow { c -> ink.recolorSelection(c); d.dismiss() }) } }
            t("Dup") { ink.duplicateSelection() }; t("Delete") { ink.deleteSelection() }
        }
        frame.addView(lassoMenu, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(10) })
        // prev / next page arrows
        frame.addView(ib(R.drawable.ic_arrow_left, R.string.back) { goTo(index - 1) }.apply { background = circle(0x99161618.toInt(), hairline(), 1) }, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.BOTTOM or Gravity.START).apply { leftMargin = dp(10); bottomMargin = dp(10) })
        frame.addView(ib(R.drawable.ic_arrow_right, R.string.back) { goTo(index + 1) }.apply { background = circle(0x99161618.toInt(), hairline(), 1) }, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.BOTTOM or Gravity.END).apply { rightMargin = dp(10); bottomMargin = dp(10) })
        root.addView(frame, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        thumbStrip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(10), dp(6), dp(10), dp(4)); gravity = Gravity.CENTER_VERTICAL }
        root.addView(HorizontalScrollView(this).apply { addView(thumbStrip); isHorizontalScrollBarEnabled = false; setBackgroundColor(ThemeManager.attr(this@PdfActivity, R.attr.nbCard)) })

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), dp(2), dp(6), dp(2)); setBackgroundColor(ThemeManager.attr(this@PdfActivity, R.attr.nbCard)) }
        brushBtns = LinkedHashMap()
        listOf(Tool.PEN to R.drawable.ic_pen, Tool.HIGHLIGHTER to R.drawable.ic_highlighter, Tool.PENCIL to R.drawable.ic_pencil, Tool.ERASER to R.drawable.ic_eraser, Tool.LASSO to R.drawable.ic_lasso).forEach { (t, icon) ->
            val b = ib(icon, R.string.pen) { setBrush(t) }; b.setOnLongClickListener { widthSheet(t); true }
            brushBtns[t] = b; bar.addView(b, LinearLayout.LayoutParams(0, dp(44), 1f))
        }
        quickColors = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        palette.take(4).forEach { c -> quickColors.addView(View(this).apply { background = circle(c, hairline(), dp(1)); setOnClickListener { setColor(c) } }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginStart = dp(6) }) }
        quickColors.addView(ib(R.drawable.ic_more, R.string.color) { Sheets.sheet(this, getString(R.string.color)) { r, d -> r.addView(paletteRow { c -> setColor(c); d.dismiss() }) } }, LinearLayout.LayoutParams(dp(36), dp(44)))
        bar.addView(quickColors)
        root.addView(bar)
        padForIme(bar)
        return root
    }

    private fun paletteRow(onPick: (Int) -> Unit): View {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(14), dp(8), dp(14), dp(12)) }
        val scroll = HorizontalScrollView(this).apply { addView(wrap); isHorizontalScrollBarEnabled = false }
        (palette.toList() + ThemeManager.penColors.toList()).distinct().forEach { c -> wrap.addView(FrameLayout(this).apply { background = circle(c, if (c == ink.color) gold() else hairline(), dp(if (c == ink.color) 3 else 1)); setOnClickListener { onPick(c) } }, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(10) }) }
        return scroll
    }

    private fun setBrush(t: Tool) {
        brush = t; ink.tool = t; ink.strokeWidth = (brushWidths[t] ?: 3f) * widthScale
        brushBtns.forEach { (k, b) -> b.alpha = if (k == t) 1f else 0.45f; b.background = if (k == t) roundRect(ThemeManager.attr(this, R.attr.nbGoldDim), 12f, this) else null }
        if (t != Tool.LASSO) { ink.clearSelection(); lassoMenu.show(false) }
        refresh()
    }
    private fun setColor(c: Int) {
        ink.color = c
        for (i in 0 until 4) { val v = quickColors.getChildAt(i); v.background = circle(palette[i], if (palette[i] == c) gold() else hairline(), dp(if (palette[i] == c) 3 else 1)) }
        if (brush == Tool.ERASER || brush == Tool.LASSO) setBrush(Tool.PEN)
    }
    private fun widthSheet(t: Tool) = Sheets.sheet(this, getString(R.string.pen)) { r, d ->
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(12)) }
        listOf(0.6f to "Thin", 1f to "Medium", 1.6f to "Thick", 2.4f to "Bold").forEach { (s, label) ->
            row.addView(TextView(this).apply { text = label; textSize = 13f; setTextColor(if (widthScale == s) 0xFF0B0B0C.toInt() else gold()); typeface = android.graphics.Typeface.DEFAULT_BOLD; background = if (widthScale == s) roundRect(gold(), 18f, this@PdfActivity) else roundRect(0, 18f, this@PdfActivity, hairline()); setPadding(dp(16), dp(8), dp(16), dp(8)); setOnClickListener { widthScale = s; setBrush(t); d.dismiss() } }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
        }
        r.addView(row)
        if (t == Tool.ERASER) r.addView(Sheets.row(this, Sheets.Item(R.drawable.ic_eraser, if (ink.eraserPixel) "Mode: pixel eraser (tap for stroke eraser)" else "Mode: stroke eraser (tap for pixel eraser)") {}) { ink.eraserPixel = !ink.eraserPixel; d.dismiss() })
    }
    private fun refresh() { undoBtn.alpha = if (ink.canUndo()) 1f else 0.35f; redoBtn.alpha = if (ink.canRedo()) 1f else 0.35f; pageLabel.text = getString(R.string.page_x_of_y, index + 1, pageCount) }

    // ------------------------------------------------------------------ load

    private fun load() {
        app.async({
            val a = app.repo.attachment(attId) ?: return@async null
            val f = app.files.file(a.path); if (!f.exists()) return@async null
            val p = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
            val r = PdfRenderer(p)
            Triple(a, p, r)
        }) { t ->
            if (t == null) { toast(getString(R.string.error_generic)); finish(); return@async }
            att = t.first; pfd = t.second; renderer = t.third; pageCount = t.third.pageCount
            parseAnnotations(t.first.annotations)
            setBrush(Tool.PEN); setColor(palette[0])
            renderThumbs(); goTo(0)
        }
    }

    private fun parseAnnotations(json: String?) {
        docs.clear()
        if (json.isNullOrBlank()) return
        runCatching { val o = JSONObject(json); val pages = o.optJSONObject("pages") ?: return; pages.keys().forEach { k -> docs[k.toInt()] = StrokeDoc.parse(pages.getString(k)) } }
    }
    private fun annotationsJson(): String? {
        val pages = JSONObject(); docs.forEach { (i, d) -> if (!d.isEmpty) pages.put("$i", d.toJson()) }
        return if (pages.length() == 0) null else JSONObject().put("v", 1).put("pages", pages).toString()
    }

    private fun renderPage(i: Int, width: Int): Bitmap? {
        val r = renderer ?: return null
        return synchronized(r) {
            runCatching { r.openPage(i).use { page -> val h = (width.toFloat() * page.height / page.width).toInt().coerceAtLeast(1); val b = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888); b.eraseColor(Color.WHITE); page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY); b } }.getOrNull()
        }
    }

    private fun goTo(i: Int) {
        if (pageCount == 0) return
        val n = i.coerceIn(0, pageCount - 1)
        if (dirty) save(sync = true)
        index = n
        val doc = docs.getOrPut(n) { StrokeDoc() }
        val w = (resources.displayMetrics.widthPixels * 1.5f).toInt().coerceIn(600, 2200)
        app.async({ renderPage(n, w) }) { bmp ->
            if (bmp != null) { doc.w = bmp.width.toFloat(); doc.h = bmp.height.toFloat() }
            ink.background = bmp; ink.doc = doc; ink.fitPage()
            highlightThumb(); refresh()
        }
    }

    private fun renderThumbs() {
        thumbStrip.removeAllViews()
        for (i in 0 until pageCount) {
            val v = FrameLayout(this).apply {
                background = roundRect(0xFFFFFFFF.toInt(), 6f, this@PdfActivity, if (i == index) gold() else hairline(), if (i == index) 2f else 1f); clipToOutline = true
                val iv = ImageView(this@PdfActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                addView(iv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                addView(TextView(this@PdfActivity).apply { text = "${i + 1}"; textSize = 9f; setTextColor(0xFF7A5A12.toInt()); setPadding(dp(3), 0, dp(3), 0) }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END))
                setOnClickListener { goTo(i) }
                if (i < 40) app.async({ renderPage(i, 120) }) { b -> iv.setImageBitmap(b) }
            }
            thumbStrip.addView(v, LinearLayout.LayoutParams(dp(34), dp(46)).apply { marginEnd = dp(8) })
        }
    }
    private fun highlightThumb() {
        for (i in 0 until thumbStrip.childCount) thumbStrip.getChildAt(i).background = roundRect(0xFFFFFFFF.toInt(), 6f, this, if (i == index) gold() else hairline(), if (i == index) 2f else 1f)
        val v = thumbStrip.getChildAt(index) ?: return
        (thumbStrip.parent as? HorizontalScrollView)?.smoothScrollTo((v.left - dp(120)).coerceAtLeast(0), 0)
    }

    private fun jumpDialog() {
        val et = EditText(this).apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER; hint = "1 – $pageCount"; setTextColor(textPrimary()); setHintTextColor(muted()) }
        val wrap = FrameLayout(this).apply { setPadding(dp(22), dp(8), dp(22), 0); addView(et) }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle(R.string.page_x_of_y.let { getString(it, index + 1, pageCount) }).setView(wrap).setPositiveButton(R.string.ok) { _, _ -> et.text.toString().toIntOrNull()?.let { goTo(it - 1) } }.setNegativeButton(R.string.cancel, null).show()
        et.showKeyboard()
    }

    // ------------------------------------------------------------------ save

    private fun save(sync: Boolean = false) {
        handler.removeCallbacks(saveRunnable)
        if (!dirty) return
        dirty = false
        val json = annotationsJson()
        val work = { app.repo.updateAttachmentAnnotations(attId, json); true }
        if (sync) work() else app.async(work) { }
    }

    override fun onDocChanged() { dirty = true; handler.removeCallbacks(saveRunnable); handler.postDelayed(saveRunnable, 1200); refresh() }
    override fun onLassoSelection(selected: List<Stroke>, bounds: RectF?) { lassoMenu.show(selected.isNotEmpty()) }
    override fun onStop() { super.onStop(); save(sync = true) }
    override fun onDestroy() { runCatching { renderer?.close() }; runCatching { pfd?.close() }; super.onDestroy() }

    // ---------------------------------------------------------------- search

    private fun searchDialog() {
        val et = EditText(this).apply { hint = getString(R.string.search_in_pdf); setTextColor(textPrimary()); setHintTextColor(muted()); isSingleLine = true }
        val wrap = FrameLayout(this).apply { setPadding(dp(22), dp(8), dp(22), 0); addView(et) }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle(R.string.search_in_pdf).setView(wrap).setPositiveButton(R.string.search) { _, _ -> runSearch(et.text.toString().trim()) }.setNegativeButton(R.string.cancel, null).show()
        et.showKeyboard()
    }

    private fun runSearch(q: String) {
        if (q.isEmpty()) return
        if (Build.VERSION.SDK_INT < 35) { toast("PDF text search needs Android 15+. Jump to a page with the page counter instead."); return }
        toast(getString(R.string.searching))
        app.async({ searchPages(q) }) { hits ->
            if (hits.isEmpty()) { toast(getString(R.string.no_results)); return@async }
            Sheets.menu(this, "\"$q\" · ${hits.size}", hits.map { (page, n) -> Sheets.Item(R.drawable.ic_file, getString(R.string.page_x_of_y, page + 1, pageCount), trailing = "$n") { goTo(page) } })
        }
    }

    @androidx.annotation.RequiresApi(35)
    private fun searchPages(q: String): List<Pair<Int, Int>> {
        val r = renderer ?: return emptyList()
        val out = ArrayList<Pair<Int, Int>>()
        synchronized(r) { for (i in 0 until pageCount) { runCatching { r.openPage(i).use { p -> val m = p.searchText(q); if (m.isNotEmpty()) out += i to m.size } } } }
        return out
    }

    // ------------------------------------------------------------------ more

    private fun moreSheet() = Sheets.menu(this, att?.name, listOf(
        Sheets.Item(R.drawable.ic_export, getString(R.string.export_annotated_pdf)) { save(sync = true); exportPdf.launch((att?.name?.substringBeforeLast('.') ?: "annotated") + "-annotated.pdf") },
        Sheets.Item(if (ink.annotationsVisible) R.drawable.ic_eye_off else R.drawable.ic_eye, getString(if (ink.annotationsVisible) R.string.hide_annotations else R.string.show_annotations)) { ink.annotationsVisible = !ink.annotationsVisible; ink.readOnly = !ink.annotationsVisible; ink.invalidateLayer() },
        Sheets.Item(R.drawable.ic_select_all, getString(R.string.select_all)) { setBrush(Tool.LASSO); ink.selectAll() },
        Sheets.Item(R.drawable.ic_share, getString(R.string.share)) { att?.let { Media.share(this, it) } },
        Sheets.Item(R.drawable.ic_clear, getString(R.string.clear_canvas), danger = true) { Sheets.confirm(this, getString(R.string.clear_canvas), getString(R.string.page_x_of_y, index + 1, pageCount), getString(R.string.clear_canvas), danger = true) { ink.clearAll() } },
    ))

    private fun doExport(uri: Uri) {
        toast("Exporting…")
        val snapshot = HashMap(docs)
        app.async({
            val r = renderer ?: return@async false
            val pdf = PdfDocument()
            synchronized(r) {
                for (i in 0 until pageCount) {
                    r.openPage(i).use { page ->
                        val scale = 2f
                        val w = (page.width * scale).toInt(); val h = (page.height * scale).toInt()
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                        val doc = snapshot[i]
                        if (doc != null && !doc.isEmpty) {
                            val v = InkView(this).apply { this.doc = doc; showBackgroundPaper = false }
                            val layer = v.renderBitmap(w, withBackground = false, transparent = true)
                            Canvas(bmp).drawBitmap(layer, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG)); layer.recycle()
                        }
                        val out = pdf.startPage(PdfDocument.PageInfo.Builder(page.width, page.height, i + 1).create())
                        out.canvas.drawBitmap(bmp, null, RectF(0f, 0f, page.width.toFloat(), page.height.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
                        pdf.finishPage(out); bmp.recycle()
                    }
                }
            }
            contentResolver.openOutputStream(uri)!!.use { pdf.writeTo(it) }; pdf.close(); true
        }) { ok -> toast(if (ok) getString(R.string.exported) else getString(R.string.error_generic)) }
    }

    companion object {
        const val EXTRA_ATT = "att_id"
        fun intent(ctx: Context, attId: Long) = Intent(ctx, PdfActivity::class.java).putExtra(EXTRA_ATT, attId)
    }
}
