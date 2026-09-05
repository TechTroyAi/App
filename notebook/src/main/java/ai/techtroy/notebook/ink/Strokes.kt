package ai.techtroy.notebook.ink

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Vector ink model shared by Sketch, Pages and PDF annotation.
 * Coordinates are normalized 0..1 against the page's logical size (w,h) so the same document renders
 * identically on any screen. Timestamps (ms since the doc's t0) enable Replay and audio sync.
 *
 * JSON: {"v":1,"w":1000,"h":1414,"bg":"#0B0B0C","t0":...,"strokes":[{"tool":"pen","color":"#D4AF37","width":3.5,
 *        "pts":[x,y,p,t, x,y,p,t, ...], "shape":"line|rect|circle|arrow"?}]}
 */
enum class Tool(val id: String) {
    PEN("pen"), BALLPOINT("ballpoint"), TECHNICAL("technical"), PENCIL("pencil"), HIGHLIGHTER("highlighter"), LASER("laser"), ERASER("eraser"), LASSO("lasso");
    companion object { fun from(s: String?) = entries.firstOrNull { it.id == s } ?: PEN }
}

class Stroke(
    var tool: Tool,
    var color: Int,
    /** base width in logical units (page width = 1000 units) */
    var width: Float,
    /** x,y,pressure,time interleaved (x,y normalized 0..1, time ms relative to doc t0) */
    val pts: FloatArrayList = FloatArrayList(),
    var shape: String? = null,
    var id: Long = System.nanoTime(),
) {
    val count get() = pts.size / 4
    fun x(i: Int) = pts[i * 4]; fun y(i: Int) = pts[i * 4 + 1]; fun p(i: Int) = pts[i * 4 + 2]; fun t(i: Int) = pts[i * 4 + 3]
    fun add(x: Float, y: Float, p: Float, t: Float) { pts.add(x); pts.add(y); pts.add(p); pts.add(t) }
    val startTime get() = if (count > 0) t(0) else 0f
    val endTime get() = if (count > 0) t(count - 1) else 0f

    fun bounds(out: RectF = RectF()): RectF {
        if (count == 0) { out.setEmpty(); return out }
        var l = 1f; var t = 1f; var r = 0f; var b = 0f
        for (i in 0 until count) { val x = x(i); val y = y(i); if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y }
        out.set(l, t, r, b); return out
    }

    fun transform(m: Matrix) {
        val arr = FloatArray(2)
        for (i in 0 until count) { arr[0] = x(i); arr[1] = y(i); m.mapPoints(arr); pts[i * 4] = arr[0]; pts[i * 4 + 1] = arr[1] }
    }

    fun copy(): Stroke = Stroke(tool, color, width, pts.copy(), shape, System.nanoTime())

    fun toJson(): JSONObject = JSONObject().apply {
        put("tool", tool.id); put("color", String.format("#%08X", color)); put("width", width.toDouble())
        shape?.let { put("shape", it) }
        val arr = JSONArray(); for (i in 0 until pts.size) arr.put(Math.round(pts[i] * 10000f) / 10000.0); put("pts", arr)
    }

    companion object {
        fun fromJson(o: JSONObject): Stroke {
            val s = Stroke(Tool.from(o.optString("tool")), parseColor(o.optString("color", "#FFD4AF37")), o.optDouble("width", 3.5).toFloat(), shape = o.optString("shape", null).takeIf { !it.isNullOrEmpty() && it != "null" })
            val arr = o.optJSONArray("pts") ?: JSONArray()
            var i = 0; while (i + 3 < arr.length()) { s.add(arr.getDouble(i).toFloat(), arr.getDouble(i + 1).toFloat(), arr.getDouble(i + 2).toFloat(), arr.getDouble(i + 3).toFloat()); i += 4 }
            return s
        }
        fun parseColor(s: String): Int = runCatching { Color.parseColor(s) }.getOrDefault(0xFFD4AF37.toInt())
    }
}

class FloatArrayList(cap: Int = 64) {
    private var a = FloatArray(cap); var size = 0; private set
    operator fun get(i: Int) = a[i]
    operator fun set(i: Int, v: Float) { a[i] = v }
    fun add(v: Float) { if (size == a.size) a = a.copyOf(a.size * 2); a[size++] = v }
    fun copy() = FloatArrayList(max(size, 4)).also { System.arraycopy(a, 0, it.a, 0, size); it.size = size }
    fun clear() { size = 0 }
}

/** A text box or image placed on the page. Normalized rect. */
class InkObject(val type: String, var rect: RectF, var text: String = "", var color: Int = 0xFFF5F2EA.toInt(), var size: Float = 28f, var path: String? = null, var rotation: Float = 0f, var id: Long = System.nanoTime()) {
    fun toJson() = JSONObject().apply { put("type", type); put("l", rect.left.toDouble()); put("t", rect.top.toDouble()); put("r", rect.right.toDouble()); put("b", rect.bottom.toDouble()); put("text", text); put("color", String.format("#%08X", color)); put("size", size.toDouble()); put("path", path); put("rot", rotation.toDouble()) }
    companion object {
        fun fromJson(o: JSONObject) = InkObject(o.optString("type", "text"), RectF(o.optDouble("l").toFloat(), o.optDouble("t").toFloat(), o.optDouble("r").toFloat(), o.optDouble("b").toFloat()), o.optString("text"), Stroke.parseColor(o.optString("color", "#FFF5F2EA")), o.optDouble("size", 28.0).toFloat(), o.optString("path", null).takeIf { it != "null" && !it.isNullOrEmpty() }, o.optDouble("rot", 0.0).toFloat())
    }
}

class StrokeDoc(var w: Float = 1000f, var h: Float = 1414f, var bg: Int = 0xFF0B0B0C.toInt()) {
    val strokes = ArrayList<Stroke>()
    val objects = ArrayList<InkObject>()
    var t0: Long = System.currentTimeMillis()

    fun toJson(): String = JSONObject().apply {
        put("v", 1); put("w", w.toDouble()); put("h", h.toDouble()); put("bg", String.format("#%08X", bg)); put("t0", t0)
        put("strokes", JSONArray().also { a -> strokes.forEach { a.put(it.toJson()) } })
    }.toString()

    fun objectsJson(): String = JSONArray().also { a -> objects.forEach { a.put(it.toJson()) } }.toString()

    val isEmpty get() = strokes.isEmpty() && objects.isEmpty()

    companion object {
        fun parse(json: String?, objectsJson: String? = null): StrokeDoc {
            val d = StrokeDoc()
            if (!json.isNullOrBlank()) runCatching {
                val o = JSONObject(json)
                d.w = o.optDouble("w", 1000.0).toFloat(); d.h = o.optDouble("h", 1414.0).toFloat(); d.bg = Stroke.parseColor(o.optString("bg", "#FF0B0B0C")); d.t0 = o.optLong("t0", System.currentTimeMillis())
                val arr = o.optJSONArray("strokes") ?: JSONArray()
                for (i in 0 until arr.length()) d.strokes += Stroke.fromJson(arr.getJSONObject(i))
            }
            if (!objectsJson.isNullOrBlank()) runCatching { val arr = JSONArray(objectsJson); for (i in 0 until arr.length()) d.objects += InkObject.fromJson(arr.getJSONObject(i)) }
            return d
        }
    }
}

/**
 * Renders strokes onto a canvas whose user space is already scaled so that (0..1, 0..1) maps to
 * (0..pageW, 0..pageH) pixels. Width units are relative to page width 1000.
 */
class StrokeRenderer {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()
    private val clear = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR); style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private var pencilShader: android.graphics.Shader? = null

    /** @param pageW pixel width of the page as drawn (used for width scaling) */
    fun draw(c: Canvas, s: Stroke, pageW: Float, pageH: Float, upTo: Int = Int.MAX_VALUE, laserAlpha: Float = 1f) {
        val n = min(s.count, upTo); if (n == 0) return
        val scale = pageW / 1000f
        val base = s.width * scale
        when (s.tool) {
            Tool.HIGHLIGHTER -> {
                paint.color = (s.color and 0x00FFFFFF) or 0x59000000; paint.strokeWidth = base * 4.5f; paint.strokeCap = Paint.Cap.SQUARE; paint.maskFilter = null; paint.shader = null
                drawPolyline(c, s, n, pageW, pageH, paint); paint.strokeCap = Paint.Cap.ROUND
            }
            Tool.LASER -> {
                val a = (laserAlpha.coerceIn(0f, 1f) * 255).toInt()
                paint.maskFilter = BlurMaskFilter(base * 3f, BlurMaskFilter.Blur.NORMAL); paint.color = (0xFFFF3B30.toInt() and 0x00FFFFFF) or (a / 2 shl 24); paint.strokeWidth = base * 4f; paint.shader = null
                drawPolyline(c, s, n, pageW, pageH, paint)
                paint.maskFilter = null; paint.color = (0xFFFFFFFF.toInt() and 0x00FFFFFF) or (a shl 24); paint.strokeWidth = base * 1.2f
                drawPolyline(c, s, n, pageW, pageH, paint)
            }
            Tool.ERASER -> { clear.strokeWidth = base * 3f; drawPolyline(c, s, n, pageW, pageH, clear) }
            Tool.TECHNICAL -> { paint.color = s.color; paint.strokeWidth = base; paint.maskFilter = null; paint.shader = null; drawPolyline(c, s, n, pageW, pageH, paint) }
            Tool.PENCIL -> {
                paint.color = (s.color and 0x00FFFFFF) or 0xC8000000.toInt(); paint.maskFilter = null; paint.shader = null
                drawVariable(c, s, n, pageW, pageH, base * 0.9f, 0.55f, 1.15f, jitter = base * 0.25f)
            }
            Tool.BALLPOINT -> { paint.color = s.color; paint.maskFilter = null; paint.shader = null; drawVariable(c, s, n, pageW, pageH, base * 0.8f, 0.85f, 1.1f) }
            Tool.PEN, Tool.LASSO -> { paint.color = s.color; paint.maskFilter = null; paint.shader = null; drawVariable(c, s, n, pageW, pageH, base, 0.45f, 1.5f) }
        }
        if (s.shape != null && s.count >= 2) drawShape(c, s, pageW, pageH)
    }

    private fun drawPolyline(c: Canvas, s: Stroke, n: Int, pw: Float, ph: Float, p: Paint) {
        path.rewind()
        if (n == 1) { c.drawPoint(s.x(0) * pw, s.y(0) * ph, p); return }
        path.moveTo(s.x(0) * pw, s.y(0) * ph)
        for (i in 1 until n) {
            val px = s.x(i - 1) * pw; val py = s.y(i - 1) * ph; val cx = s.x(i) * pw; val cy = s.y(i) * ph
            path.quadTo(px, py, (px + cx) / 2, (py + cy) / 2)
        }
        path.lineTo(s.x(n - 1) * pw, s.y(n - 1) * ph)
        c.drawPath(path, p)
    }

    /** Speed/pressure-based variable width: fast = thin, slow/pressed = thick. Drawn as segments with smoothed widths. */
    private fun drawVariable(c: Canvas, s: Stroke, n: Int, pw: Float, ph: Float, base: Float, minF: Float, maxF: Float, jitter: Float = 0f) {
        if (n == 1) { fill.color = paint.color; c.drawCircle(s.x(0) * pw, s.y(0) * ph, base * 0.5f, fill); return }
        var wPrev = base
        var lx = s.x(0) * pw; var ly = s.y(0) * ph
        val rnd = java.util.Random(s.id)
        for (i in 1 until n) {
            val x = s.x(i) * pw; val y = s.y(i) * ph
            val dt = max(1f, s.t(i) - s.t(i - 1)); val d = hypot(x - lx, y - ly)
            val speed = d / dt / (pw / 1000f)                       // logical units per ms
            val speedF = (1.25f - speed * 0.55f).coerceIn(minF, maxF)
            val pressF = if (s.p(i) > 0f) (0.6f + s.p(i) * 0.8f) else 1f
            val target = base * speedF * pressF
            val w = wPrev + (target - wPrev) * 0.3f
            paint.strokeWidth = w
            if (jitter > 0f) {
                // pencil: two offset thin passes for grain
                val jx = (rnd.nextFloat() - 0.5f) * jitter; val jy = (rnd.nextFloat() - 0.5f) * jitter
                paint.strokeWidth = w * 0.7f
                c.drawLine(lx + jx, ly + jy, x + jx, y + jy, paint)
                c.drawLine(lx - jx, ly - jy, x - jx, y - jy, paint)
            } else c.drawLine(lx, ly, x, y, paint)
            wPrev = w; lx = x; ly = y
        }
    }

    private fun drawShape(c: Canvas, s: Stroke, pw: Float, ph: Float) {
        // shape strokes store only 2 points (start,end) after snapping; the polyline is already the shape's outline
    }

    /** Snap a freehand stroke to a primitive if it closely resembles one. Returns a replacement stroke or null. */
    fun snapShape(s: Stroke): Stroke? {
        val n = s.count; if (n < 8) return null
        val b = s.bounds(); val diag = hypot(b.width(), b.height()); if (diag < 0.03f) return null
        val x0 = s.x(0); val y0 = s.y(0); val x1 = s.x(n - 1); val y1 = s.y(n - 1)
        val closed = hypot(x1 - x0, y1 - y0) < diag * 0.25f
        // 1. straight line: all points near segment
        run {
            var maxDev = 0f
            for (i in 0 until n) maxDev = max(maxDev, distToSegment(s.x(i), s.y(i), x0, y0, x1, y1))
            if (!closed && maxDev < 0.012f + diag * 0.03f) return line(s, x0, y0, x1, y1)
        }
        if (!closed) return null
        // 2. circle: distance from center roughly constant
        run {
            val cx = b.centerX(); val cy = b.centerY(); val r = (b.width() + b.height()) / 4f
            var dev = 0f; for (i in 0 until n) dev += abs(hypot(s.x(i) - cx, s.y(i) - cy) - r)
            dev /= n
            val aspect = b.width() / max(b.height(), 1e-4f)
            if (dev < r * 0.14f && aspect in 0.7f..1.4f) return circle(s, cx, cy, b.width() / 2, b.height() / 2)
        }
        // 3. rectangle: points near bounding box edges
        run {
            var near = 0
            for (i in 0 until n) { val x = s.x(i); val y = s.y(i); val d = min(min(abs(x - b.left), abs(x - b.right)), min(abs(y - b.top), abs(y - b.bottom))); if (d < diag * 0.05f) near++ }
            if (near > n * 0.85f) return rect(s, b)
        }
        return null
    }

    private fun distToSegment(px: Float, py: Float, x0: Float, y0: Float, x1: Float, y1: Float): Float {
        val dx = x1 - x0; val dy = y1 - y0; val l2 = dx * dx + dy * dy
        if (l2 == 0f) return hypot(px - x0, py - y0)
        val t = ((px - x0) * dx + (py - y0) * dy) / l2
        val tt = t.coerceIn(0f, 1f)
        return hypot(px - (x0 + tt * dx), py - (y0 + tt * dy))
    }

    private fun base(s: Stroke, shape: String) = Stroke(if (s.tool == Tool.HIGHLIGHTER) Tool.HIGHLIGHTER else Tool.TECHNICAL, s.color, s.width, shape = shape)
    fun line(s: Stroke, x0: Float, y0: Float, x1: Float, y1: Float): Stroke = base(s, "line").also { it.add(x0, y0, 1f, s.startTime); it.add(x1, y1, 1f, s.endTime) }
    fun arrow(s: Stroke, x0: Float, y0: Float, x1: Float, y1: Float): Stroke = base(s, "arrow").also {
        val ang = atan2(y1 - y0, x1 - x0); val len = 0.025f
        it.add(x0, y0, 1f, s.startTime); it.add(x1, y1, 1f, s.endTime)
        it.add(x1 - len * cos(ang - 0.5f), y1 - len * sin(ang - 0.5f), 1f, s.endTime); it.add(x1, y1, 1f, s.endTime)
        it.add(x1 - len * cos(ang + 0.5f), y1 - len * sin(ang + 0.5f), 1f, s.endTime)
    }
    fun rect(s: Stroke, b: RectF): Stroke = base(s, "rect").also { val t = s.endTime; it.add(b.left, b.top, 1f, s.startTime); it.add(b.right, b.top, 1f, t); it.add(b.right, b.bottom, 1f, t); it.add(b.left, b.bottom, 1f, t); it.add(b.left, b.top, 1f, t) }
    fun circle(s: Stroke, cx: Float, cy: Float, rx: Float, ry: Float): Stroke = base(s, "circle").also {
        val steps = 48; for (i in 0..steps) { val a = i * 2.0 * Math.PI / steps; it.add(cx + rx * cos(a).toFloat(), cy + ry * sin(a).toFloat(), 1f, s.startTime + (s.endTime - s.startTime) * i / steps) }
    }
}

/** Point-in-polygon + stroke hit tests for lasso and stroke-eraser. */
object Geometry {
    fun pointInPoly(x: Float, y: Float, poly: Stroke): Boolean {
        var inside = false; val n = poly.count; if (n < 3) return false
        var j = n - 1
        for (i in 0 until n) {
            val xi = poly.x(i); val yi = poly.y(i); val xj = poly.x(j); val yj = poly.y(j)
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi + 1e-9f) + xi) inside = !inside
            j = i
        }
        return inside
    }
    fun strokeInPoly(s: Stroke, poly: Stroke): Boolean {
        if (s.count == 0) return false
        var hits = 0; for (i in 0 until s.count) if (pointInPoly(s.x(i), s.y(i), poly)) hits++
        return hits >= max(1, (s.count * 0.5f).toInt())
    }
    fun strokeNear(s: Stroke, x: Float, y: Float, r: Float): Boolean {
        for (i in 0 until s.count) if (hypot(s.x(i) - x, s.y(i) - y) < r) return true
        if (s.count >= 2) for (i in 1 until s.count) if (segDist(x, y, s.x(i - 1), s.y(i - 1), s.x(i), s.y(i)) < r) return true
        return false
    }
    private fun segDist(px: Float, py: Float, x0: Float, y0: Float, x1: Float, y1: Float): Float {
        val dx = x1 - x0; val dy = y1 - y0; val l2 = dx * dx + dy * dy
        if (l2 == 0f) return hypot(px - x0, py - y0)
        val t = (((px - x0) * dx + (py - y0) * dy) / l2).coerceIn(0f, 1f)
        return hypot(px - (x0 + t * dx), py - (y0 + t * dy))
    }
}
