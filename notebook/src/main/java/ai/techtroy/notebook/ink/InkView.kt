package ai.techtroy.notebook.ink

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The drawing surface. One page at a time. Handles:
 *  - 1 finger: draw with the current tool (or lasso / erase / select object)
 *  - 2 fingers: pinch zoom + pan; 2-finger tap = undo; 3-finger tap = redo
 *  - Committed strokes are cached in a bitmap layer; the live stroke is drawn on top each frame.
 *  - Optional background (paper template or a rendered PDF page bitmap).
 *  - Ruler mode: straight lines. Shape snap: hold still at the end of a stroke.
 *  - Zoom-to-write: an external controller sets [writeWindow]; touch input is mapped through it.
 */
class InkView(ctx: Context) : View(ctx) {

    interface Listener {
        fun onDocChanged()
        fun onStrokeStarted() {}
        fun onStrokeFinished(s: Stroke) {}
        fun onLassoSelection(selected: List<Stroke>, bounds: RectF?) {}
        fun onObjectTapped(o: InkObject) {}
        fun onStrokeTapped(s: Stroke) {}
        fun onViewChanged() {}
    }

    var doc = StrokeDoc()
        set(v) { field = v; history.clear(); redo.clear(); selection.clear(); invalidateLayer(); }
    var listener: Listener? = null
    var tool = Tool.PEN
    var color = 0xFFD4AF37.toInt()
    var strokeWidth = 3.5f
    var rulerMode = false
    var shapeSnap = true
    var readOnly = false
    var eraserPixel = false
    var timeBase: () -> Float = { (System.currentTimeMillis() - doc.t0).toFloat() }

    /** background image (PDF page render) drawn under the strokes */
    var background: Bitmap? = null
        set(v) { field = v; invalidate() }
    var paper: PaperStyle = PaperStyle.LINED
    var paperDark = true
    var showBackgroundPaper = true
    var annotationsVisible = true

    // view transform: page px -> screen
    val viewMatrix = Matrix()
    private val inverse = Matrix()
    var minScale = 1f; var maxScale = 8f
    private var pageW = 1f; private var pageH = 1f  // pixel size at scale 1 (fit width)

    // layers
    private var layer: Bitmap? = null
    private var layerCanvas: Canvas? = null
    private var layerDirty = true
    private val renderer = StrokeRenderer()
    private var current: Stroke? = null
    private var laserStrokes = ArrayList<Pair<Stroke, Long>>()

    // history: snapshots of stroke list identity (cheap: strokes are immutable once finished)
    private val history = ArrayDeque<List<Stroke>>()
    private val redo = ArrayDeque<List<Stroke>>()
    private var objHistory = ArrayDeque<List<InkObject>>()

    // selection (lasso)
    val selection = ArrayList<Stroke>()
    var selectionBounds: RectF? = null
    private var lassoPath: Stroke? = null
    private var dragSelection = false
    private var lastDx = 0f; private var lastDy = 0f

    // gestures
    private val scaleDetector = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val s = currentScale(); val f = (s * d.scaleFactor).coerceIn(minScale, maxScale) / s
            viewMatrix.postScale(f, f, d.focusX, d.focusY); clampView(); invalidate(); listener?.onViewChanged(); return true
        }
    })
    private var multiTouch = false
    private var pointerDownCount = 0; private var maxPointers = 0; private var multiDownAt = 0L; private var multiMoved = false
    private var lastPanX = 0f; private var lastPanY = 0f
    private var downX = 0f; private var downY = 0f; private var downAt = 0L; private var moved = false
    private var stillSince = 0L; private var lastStillX = 0f; private var lastStillY = 0f
    private val touchSlop = ViewConfiguration.get(ctx).scaledTouchSlop

    /** zoom-to-write: if set, single-finger input in this view is mapped into that page-space window (normalized). */
    var writeWindow: RectF? = null

    private val objPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val selPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = 0xFFD4AF37.toInt(); pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f) }
    private val paperPainter = PaperPainter()

    init { setLayerType(LAYER_TYPE_HARDWARE, null) }

    // ------------------------------------------------------------- geometry

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fitPage(); invalidateLayer()
    }

    fun fitPage() {
        val vw = getWidth(); val vh = getHeight()
        if (vw == 0 || vh == 0) return
        val aspect = doc.h / doc.w
        pageW = vw.toFloat(); pageH = pageW * aspect
        if (writeWindow == null) {
            viewMatrix.reset()
            // fit whole page if it is shorter than the view; else fit width
            if (pageH < vh) viewMatrix.postTranslate(0f, (vh - pageH) / 2f)
        }
        layer?.recycle(); layer = null
        invalidateLayer()
    }

    fun currentScale(): Float { val v = FloatArray(9); viewMatrix.getValues(v); return v[Matrix.MSCALE_X] }

    private fun clampView() {
        val v = FloatArray(9); viewMatrix.getValues(v)
        val s = v[Matrix.MSCALE_X]; var tx = v[Matrix.MTRANS_X]; var ty = v[Matrix.MTRANS_Y]
        val cw = pageW * s; val ch = pageH * s
        tx = if (cw <= width) (width - cw) / 2 else tx.coerceIn(width - cw, 0f)
        ty = if (ch <= height) (height - ch) / 2 else ty.coerceIn(height - ch, 0f)
        v[Matrix.MTRANS_X] = tx; v[Matrix.MTRANS_Y] = ty; viewMatrix.setValues(v)
    }

    fun resetZoom() { viewMatrix.reset(); if (pageH < height) viewMatrix.postTranslate(0f, (height - pageH) / 2f); invalidate(); listener?.onViewChanged() }

    /** Screen -> normalized page coords. */
    fun toPage(sx: Float, sy: Float, out: FloatArray) {
        val ww = writeWindow
        if (ww != null) { out[0] = ww.left + (sx / width) * ww.width(); out[1] = ww.top + (sy / height) * ww.height(); return }
        viewMatrix.invert(inverse); out[0] = sx; out[1] = sy; inverse.mapPoints(out); out[0] /= pageW; out[1] /= pageH
    }

    /** Normalized page -> screen. */
    fun toScreen(nx: Float, ny: Float, out: FloatArray) {
        val ww = writeWindow
        if (ww != null) { out[0] = (nx - ww.left) / ww.width() * width; out[1] = (ny - ww.top) / ww.height() * height; return }
        out[0] = nx * pageW; out[1] = ny * pageH; viewMatrix.mapPoints(out)
    }

    // --------------------------------------------------------------- layers

    fun invalidateLayer() { layerDirty = true; invalidate() }

    private fun ensureLayer(): Canvas {
        val lw = max(1, (pageW * layerScale()).toInt()); val lh = max(1, (pageH * layerScale()).toInt())
        var b = layer
        if (b == null || b.width != lw || b.height != lh) { b?.recycle(); b = Bitmap.createBitmap(lw, lh, Bitmap.Config.ARGB_8888); layer = b; layerCanvas = Canvas(b); layerDirty = true }
        if (layerDirty) {
            val c = layerCanvas!!
            c.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
            if (annotationsVisible) for (s in doc.strokes) if (s.tool != Tool.LASER) renderer.draw(c, s, lw.toFloat(), lh.toFloat())
            layerDirty = false
        }
        return layerCanvas!!
    }

    /** Render committed strokes at up to 2× device pixels when zoomed, capped for memory. */
    private fun layerScale(): Float {
        val s = if (writeWindow != null) 2f else min(2f, max(1f, currentScale()))
        val cap = 2600f / max(pageW, pageH)
        return min(s, max(1f, cap))
    }

    // ---------------------------------------------------------------- draw

    override fun onDraw(canvas: Canvas) {
        val ww = writeWindow
        canvas.save()
        if (ww != null) {
            // map normalized window to the whole view
            val sx = width / (ww.width() * pageW); val sy = height / (ww.height() * pageH)
            canvas.scale(sx, sy); canvas.translate(-ww.left * pageW, -ww.top * pageH)
        } else canvas.concat(viewMatrix)
        // paper / background
        val bg = background
        if (bg != null) { canvas.drawBitmap(bg, null, RectF(0f, 0f, pageW, pageH), objPaint) }
        else if (showBackgroundPaper) paperPainter.draw(canvas, pageW, pageH, paper, paperDark, doc.bg)
        // objects (images/text)
        if (annotationsVisible) for (o in doc.objects) drawObject(canvas, o)
        // committed layer
        val lc = ensureLayer(); val l = layer!!
        canvas.drawBitmap(l, null, RectF(0f, 0f, pageW, pageH), objPaint)
        // live stroke
        current?.let { s -> if (s.tool != Tool.LASSO) renderer.draw(canvas, s, pageW, pageH) }
        // laser strokes (fading)
        if (laserStrokes.isNotEmpty()) {
            val now = SystemClock.uptimeMillis()
            val it = laserStrokes.iterator()
            while (it.hasNext()) { val (s, t) = it.next(); val age = now - t; if (age > 2000) { it.remove(); continue }; renderer.draw(canvas, s, pageW, pageH, laserAlpha = 1f - age / 2000f) }
            postInvalidateOnAnimation()
        }
        // lasso path + selection outline
        lassoPath?.let { lp -> if (lp.count > 1) { val p = Path(); p.moveTo(lp.x(0) * pageW, lp.y(0) * pageH); for (i in 1 until lp.count) p.lineTo(lp.x(i) * pageW, lp.y(i) * pageH); selPaint.strokeWidth = 2.5f / currentScale(); canvas.drawPath(p, selPaint) } }
        selectionBounds?.let { b -> selPaint.strokeWidth = 2.5f / currentScale(); val r = RectF(b.left * pageW - 12, b.top * pageH - 12, b.right * pageW + 12, b.bottom * pageH + 12); canvas.drawRoundRect(r, 12f, 12f, selPaint) }
        canvas.restore()
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val imageCache = HashMap<String, Bitmap?>()
    var imageLoader: ((String) -> Bitmap?)? = null

    private fun drawObject(c: Canvas, o: InkObject) {
        val r = RectF(o.rect.left * pageW, o.rect.top * pageH, o.rect.right * pageW, o.rect.bottom * pageH)
        c.save(); c.rotate(o.rotation, r.centerX(), r.centerY())
        if (o.type == "image") {
            val p = o.path
            val bmp = if (p != null) imageCache.getOrPut(p) { imageLoader?.invoke(p) } else null
            if (bmp != null) c.drawBitmap(bmp, null, r, objPaint) else { objPaint.color = 0x33888888; c.drawRect(r, objPaint) }
        } else {
            textPaint.color = o.color; textPaint.textSize = o.size * pageW / 1000f
            val layout = android.text.StaticLayout.Builder.obtain(o.text, 0, o.text.length, android.text.TextPaint(textPaint), max(1, r.width().toInt())).build()
            c.translate(r.left, r.top); layout.draw(c)
        }
        c.restore()
    }

    // ---------------------------------------------------------------- touch

    private val tmp = FloatArray(2)

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (readOnly && writeWindow == null) { scaleDetector.onTouchEvent(e); handlePan(e); return true }
        val count = e.pointerCount
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointerDownCount = 1; maxPointers = 1; multiTouch = false; multiMoved = false
                downX = e.x; downY = e.y; downAt = SystemClock.uptimeMillis(); moved = false
                startStroke(e)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                pointerDownCount = count; maxPointers = max(maxPointers, count)
                if (!multiTouch) { multiTouch = true; multiDownAt = SystemClock.uptimeMillis(); cancelStroke(); lastPanX = focusX(e); lastPanY = focusY(e) }
            }
            MotionEvent.ACTION_MOVE -> {
                if (multiTouch) {
                    if (writeWindow == null) { scaleDetector.onTouchEvent(e); handlePan(e) }
                    if (hypot(focusX(e) - lastPanX, focusY(e) - lastPanY) > touchSlop) multiMoved = true
                } else moveStroke(e)
            }
            MotionEvent.ACTION_POINTER_UP -> { pointerDownCount = count - 1 }
            MotionEvent.ACTION_UP -> {
                if (multiTouch) {
                    val quick = SystemClock.uptimeMillis() - multiDownAt < 300 && !multiMoved && !scaleDetector.isInProgress
                    if (quick) { if (maxPointers >= 3) redo() else if (maxPointers == 2) undo() }
                    multiTouch = false
                } else endStroke(e)
            }
            MotionEvent.ACTION_CANCEL -> { cancelStroke(); multiTouch = false }
        }
        if (multiTouch && writeWindow == null) scaleDetector.onTouchEvent(e)
        return true
    }

    private fun focusX(e: MotionEvent): Float { var s = 0f; for (i in 0 until e.pointerCount) s += e.getX(i); return s / e.pointerCount }
    private fun focusY(e: MotionEvent): Float { var s = 0f; for (i in 0 until e.pointerCount) s += e.getY(i); return s / e.pointerCount }

    private fun handlePan(e: MotionEvent) {
        if (e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_POINTER_DOWN || e.actionMasked == MotionEvent.ACTION_POINTER_UP) { lastPanX = focusX(e); lastPanY = focusY(e); return }
        if (e.actionMasked != MotionEvent.ACTION_MOVE) return
        val fx = focusX(e); val fy = focusY(e)
        viewMatrix.postTranslate(fx - lastPanX, fy - lastPanY); clampView(); lastPanX = fx; lastPanY = fy; invalidate(); listener?.onViewChanged()
    }

    private fun startStroke(e: MotionEvent) {
        toPage(e.x, e.y, tmp); val nx = tmp[0]; val ny = tmp[1]
        stillSince = SystemClock.uptimeMillis(); lastStillX = e.x; lastStillY = e.y
        when (tool) {
            Tool.LASSO -> {
                val b = selectionBounds
                if (b != null && b.contains(nx, ny)) { dragSelection = true; lastDx = nx; lastDy = ny; return }
                clearSelection(); lassoPath = Stroke(Tool.LASSO, color, 1f).also { it.add(nx, ny, 0f, 0f) }
            }
            Tool.ERASER -> { if (eraserPixel) { current = Stroke(Tool.ERASER, 0, strokeWidth).also { it.add(nx, ny, e.pressure, timeBase()) }; pushHistory() } else eraseAt(nx, ny, true) }
            else -> {
                val s = Stroke(tool, color, strokeWidth).also { it.add(nx, ny, if (e.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) e.pressure else 0f, timeBase()) }
                current = s; listener?.onStrokeStarted()
            }
        }
        invalidate()
    }

    private fun moveStroke(e: MotionEvent) {
        if (hypot(e.x - downX, e.y - downY) > touchSlop) moved = true
        if (hypot(e.x - lastStillX, e.y - lastStillY) > touchSlop * 0.7f) { stillSince = SystemClock.uptimeMillis(); lastStillX = e.x; lastStillY = e.y }
        toPage(e.x, e.y, tmp); val nx = tmp[0]; val ny = tmp[1]
        if (dragSelection) { moveSelection(nx - lastDx, ny - lastDy); lastDx = nx; lastDy = ny; return }
        lassoPath?.let { it.add(nx, ny, 0f, 0f); invalidate(); return }
        val s = current
        if (s == null) { if (tool == Tool.ERASER && !eraserPixel) eraseAt(nx, ny, false); return }
        if (rulerMode && s.tool != Tool.ERASER) {
            // keep only start + current point
            while (s.count > 1) { s.pts.size.let { } ; break }
            if (s.count >= 2) { s.pts[4] = nx; s.pts[5] = ny; s.pts[7] = timeBase() } else s.add(nx, ny, e.pressure, timeBase())
            invalidate(); return
        }
        val hist = e.historySize
        for (h in 0 until hist) { toPage(e.getHistoricalX(h), e.getHistoricalY(h), tmp); s.add(tmp[0], tmp[1], if (e.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) e.getHistoricalPressure(h) else 0f, timeBase()) }
        s.add(nx, ny, if (e.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) e.pressure else 0f, timeBase())
        invalidate()
    }

    private fun endStroke(e: MotionEvent) {
        if (dragSelection) { dragSelection = false; commitSelectionMove(); return }
        lassoPath?.let { lp ->
            lassoPath = null
            if (lp.count > 3) {
                selection.clear(); doc.strokes.filterTo(selection) { Geometry.strokeInPoly(it, lp) }
                selectionBounds = if (selection.isEmpty()) null else RectF().also { b -> val t = RectF(); selection.forEachIndexed { i, s -> s.bounds(t); if (i == 0) b.set(t) else b.union(t) } }
                listener?.onLassoSelection(selection.toList(), selectionBounds)
            } else if (!moved) {
                // tap: select stroke under finger / object
                toPage(e.x, e.y, tmp); val nx = tmp[0]; val ny = tmp[1]
                doc.objects.lastOrNull { it.rect.contains(nx, ny) }?.let { listener?.onObjectTapped(it); return }
                doc.strokes.lastOrNull { Geometry.strokeNear(it, nx, ny, 0.012f) }?.let { listener?.onStrokeTapped(it) }
            }
            invalidate(); return
        }
        val s = current ?: run { if (tool == Tool.ERASER && !eraserPixel && moved) listener?.onDocChanged(); return }
        current = null
        if (s.tool == Tool.LASER) { laserStrokes += s to SystemClock.uptimeMillis(); invalidate(); return }
        if (s.tool == Tool.ERASER) { doc.strokes += s; invalidateLayer(); listener?.onDocChanged(); return }
        var finalStroke = s
        if (!moved) {
            // dot
            toPage(e.x, e.y, tmp); s.add(tmp[0] + 0.0005f, tmp[1] + 0.0005f, e.pressure, timeBase())
            // tap on object with pen: edit it
            doc.objects.lastOrNull { it.rect.contains(tmp[0], tmp[1]) }?.let { current = null; listener?.onObjectTapped(it); invalidate(); return }
        } else if (rulerMode && s.count >= 2) {
            finalStroke = renderer.line(s, s.x(0), s.y(0), s.x(s.count - 1), s.y(s.count - 1)).also { it.tool = if (s.tool == Tool.HIGHLIGHTER) Tool.HIGHLIGHTER else s.tool }
        } else if (shapeSnap && SystemClock.uptimeMillis() - stillSince > 450 && s.count > 8) {
            renderer.snapShape(s)?.let { finalStroke = it.also { f -> f.tool = if (s.tool == Tool.HIGHLIGHTER) Tool.HIGHLIGHTER else s.tool } }
        }
        pushHistory()
        doc.strokes += finalStroke
        invalidateLayer(); listener?.onStrokeFinished(finalStroke); listener?.onDocChanged()
    }

    private fun cancelStroke() { current = null; lassoPath = null; dragSelection = false; invalidate() }

    private var eraseChanged = false
    private fun eraseAt(nx: Float, ny: Float, first: Boolean) {
        val r = (strokeWidth * 3f / 1000f).coerceAtLeast(0.008f)
        val hits = doc.strokes.filter { it.tool != Tool.ERASER && Geometry.strokeNear(it, nx, ny, r) }
        if (hits.isEmpty()) return
        if (first || !eraseChanged) { pushHistory(); eraseChanged = true }
        doc.strokes.removeAll(hits.toSet()); invalidateLayer(); listener?.onDocChanged()
    }

    // ------------------------------------------------------------- history

    private fun pushHistory() { history.addLast(ArrayList(doc.strokes)); objHistory.addLast(ArrayList(doc.objects)); if (history.size > 60) { history.removeFirst(); objHistory.removeFirst() }; redo.clear(); eraseChanged = false }
    fun canUndo() = history.isNotEmpty(); fun canRedo() = redo.isNotEmpty()
    fun undo() { val prev = history.removeLastOrNull() ?: return; redo.addLast(ArrayList(doc.strokes)); doc.strokes.clear(); doc.strokes.addAll(prev); objHistory.removeLastOrNull()?.let { doc.objects.clear(); doc.objects.addAll(it) }; clearSelection(); invalidateLayer(); listener?.onDocChanged() }
    fun redo() { val next = redo.removeLastOrNull() ?: return; history.addLast(ArrayList(doc.strokes)); objHistory.addLast(ArrayList(doc.objects)); doc.strokes.clear(); doc.strokes.addAll(next); clearSelection(); invalidateLayer(); listener?.onDocChanged() }
    fun clearAll() { if (doc.isEmpty) return; pushHistory(); doc.strokes.clear(); doc.objects.clear(); clearSelection(); invalidateLayer(); listener?.onDocChanged() }
    fun commitExternalChange() { pushHistory(); invalidateLayer(); listener?.onDocChanged() }
    fun beforeExternalChange() { pushHistory() }

    // ------------------------------------------------------------ selection

    fun clearSelection() { selection.clear(); selectionBounds = null; invalidate(); listener?.onLassoSelection(emptyList(), null) }
    private var moveMatrix = Matrix(); private var selectionMoved = false
    private fun moveSelection(dx: Float, dy: Float) {
        if (!selectionMoved) { pushHistory(); selectionMoved = true; // detach copies so history keeps originals
            val copies = selection.map { it.copy() }; selection.forEachIndexed { i, s -> val idx = doc.strokes.indexOf(s); if (idx >= 0) doc.strokes[idx] = copies[i] }; selection.clear(); selection.addAll(copies) }
        moveMatrix.setTranslate(dx, dy); selection.forEach { it.transform(moveMatrix) }; selectionBounds?.offset(dx, dy); invalidateLayer()
    }
    private fun commitSelectionMove() { selectionMoved = false; listener?.onDocChanged(); listener?.onLassoSelection(selection.toList(), selectionBounds) }

    fun transformSelection(m: Matrix) {
        if (selection.isEmpty()) return
        pushHistory()
        val copies = selection.map { it.copy() }; selection.forEachIndexed { i, s -> val idx = doc.strokes.indexOf(s); if (idx >= 0) doc.strokes[idx] = copies[i] }; selection.clear(); selection.addAll(copies)
        selection.forEach { it.transform(m) }
        selectionBounds = RectF().also { b -> val t = RectF(); selection.forEachIndexed { i, s -> s.bounds(t); if (i == 0) b.set(t) else b.union(t) } }
        invalidateLayer(); listener?.onDocChanged(); listener?.onLassoSelection(selection.toList(), selectionBounds)
    }
    fun recolorSelection(c: Int) { if (selection.isEmpty()) return; pushHistory(); val copies = selection.map { it.copy().also { s -> s.color = c } }; selection.forEachIndexed { i, s -> val idx = doc.strokes.indexOf(s); if (idx >= 0) doc.strokes[idx] = copies[i] }; selection.clear(); selection.addAll(copies); invalidateLayer(); listener?.onDocChanged() }
    fun deleteSelection() { if (selection.isEmpty()) return; pushHistory(); doc.strokes.removeAll(selection.toSet()); clearSelection(); invalidateLayer(); listener?.onDocChanged() }
    fun duplicateSelection() { if (selection.isEmpty()) return; pushHistory(); val m = Matrix().apply { setTranslate(0.03f, 0.03f) }; val copies = selection.map { it.copy().also { c -> c.transform(m) } }; doc.strokes.addAll(copies); selection.clear(); selection.addAll(copies); selectionBounds?.offset(0.03f, 0.03f); invalidateLayer(); listener?.onDocChanged(); listener?.onLassoSelection(selection.toList(), selectionBounds) }
    fun selectAll() { selection.clear(); selection.addAll(doc.strokes.filter { it.tool != Tool.ERASER }); selectionBounds = if (selection.isEmpty()) null else RectF().also { b -> val t = RectF(); selection.forEachIndexed { i, s -> s.bounds(t); if (i == 0) b.set(t) else b.union(t) } }; invalidate(); listener?.onLassoSelection(selection.toList(), selectionBounds) }

    // ------------------------------------------------------------ export

    fun renderBitmap(w: Int, withBackground: Boolean = true, transparent: Boolean = false, includeObjects: Boolean = true): Bitmap {
        val h = (w * doc.h / doc.w).toInt().coerceAtLeast(1)
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val bg = background
        if (!transparent) { if (bg != null && withBackground) c.drawBitmap(bg, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), objPaint) else if (withBackground) paperPainter.draw(c, w.toFloat(), h.toFloat(), paper, paperDark, doc.bg) }
        val savedPw = pageW; val savedPh = pageH; pageW = w.toFloat(); pageH = h.toFloat()
        if (includeObjects) doc.objects.forEach { drawObject(c, it) }
        pageW = savedPw; pageH = savedPh
        for (s in doc.strokes) if (s.tool != Tool.LASER) renderer.draw(c, s, w.toFloat(), h.toFloat())
        return b
    }

    fun pageAspect() = doc.h / doc.w
    val pagePixelWidth get() = pageW
    val pagePixelHeight get() = pageH
}

enum class PaperStyle(val id: String) { BLANK("blank"), LINED("lined"), GRID("grid"), DOTTED("dotted"), CORNELL("cornell");
    companion object { fun from(s: String?) = entries.firstOrNull { it.id == s } ?: LINED }
}

class PaperPainter {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    fun draw(c: Canvas, w: Float, h: Float, style: PaperStyle, dark: Boolean, bgOverride: Int) {
        val bg = if (dark) 0xFF141416.toInt() else 0xFFFBF7EC.toInt()
        c.drawColor(bg)
        val line = if (dark) 0x2ED4AF37 else 0x3AB8922A
        val step = w / 26f  // ~ 8mm lines on a phone
        p.color = line; p.strokeWidth = max(1f, w / 900f); dot.color = if (dark) 0x55D4AF37 else 0x66B8922A
        when (style) {
            PaperStyle.BLANK -> {}
            PaperStyle.LINED -> { var y = step * 2.2f; while (y < h) { c.drawLine(step * 0.8f, y, w - step * 0.8f, y, p); y += step } }
            PaperStyle.GRID -> { var x = step; while (x < w) { c.drawLine(x, 0f, x, h, p); x += step }; var y = step; while (y < h) { c.drawLine(0f, y, w, y, p); y += step } }
            PaperStyle.DOTTED -> { val r = max(1.2f, w / 500f); var y = step; while (y < h) { var x = step; while (x < w) { c.drawCircle(x, y, r, dot); x += step }; y += step } }
            PaperStyle.CORNELL -> {
                val cue = w * 0.3f; val summary = h - step * 5
                p.strokeWidth = max(1.5f, w / 600f); p.color = if (dark) 0x66D4AF37 else 0x77B8922A
                c.drawLine(cue, step * 1.5f, cue, summary, p); c.drawLine(step * 0.8f, summary, w - step * 0.8f, summary, p)
                p.color = line; p.strokeWidth = max(1f, w / 900f)
                var y = step * 2.5f; while (y < summary - step * 0.5f) { c.drawLine(cue + step * 0.4f, y, w - step * 0.8f, y, p); y += step }
            }
        }
        // top margin accent
        if (style == PaperStyle.LINED) { p.color = if (dark) 0x66C7554D.toInt() else 0x66C7554D.toInt(); c.drawLine(step * 2.2f, 0f, step * 2.2f, h, p) }
    }
}
