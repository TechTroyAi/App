package ai.techtroy.notebook.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import ai.techtroy.notebook.R
import ai.techtroy.notebook.core.ThemeManager
import ai.techtroy.notebook.core.dp
import ai.techtroy.notebook.core.gold
import ai.techtroy.notebook.core.muted
import ai.techtroy.notebook.core.show
import ai.techtroy.notebook.core.textPrimary
import ai.techtroy.notebook.data.Note
import ai.techtroy.notebook.data.NoteLink
import ai.techtroy.notebook.sys.Lock
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Force-directed graph of note links. Tap a node to open it; pinch/drag to navigate. */
class GraphActivity : BaseActivity() {
    private lateinit var graph: GraphView
    private lateinit var empty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TopBar.create(this, getString(R.string.graph_title)))
        val frame = FrameLayout(this)
        graph = GraphView(this) { id -> open(id) }
        empty = TextView(this).apply { text = getString(R.string.graph_empty); setTextColor(muted()); gravity = android.view.Gravity.CENTER; setPadding(dp(40), 0, dp(40), 0); show(false) }
        frame.addView(graph); frame.addView(empty)
        col.addView(frame, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(col)
    }

    override fun onResume() {
        super.onResume()
        app.async({
            val links = app.repo.allLinks()
            val ids = (links.map { it.fromId } + links.map { it.toId }).toSet()
            val notes = ids.mapNotNull { app.repo.note(it) }.filter { !it.inTrash }
            notes to links
        }) { (notes, links) -> graph.setData(notes, links); empty.show(notes.isEmpty()) }
    }

    private fun open(id: Long) {
        app.async({ app.repo.note(id) }) { n -> if (n == null) return@async; if (n.locked && !Lock.sessionValid(app.prefs)) LockActivity.unlock(this) { startActivity(EditorActivity.intent(this, id)) } else startActivity(EditorActivity.intent(this, id)) }
    }
}

class GraphView(ctx: Context, private val onOpen: (Long) -> Unit) : View(ctx) {
    private class Node(val note: Note, var x: Float, var y: Float, var vx: Float = 0f, var vy: Float = 0f, var degree: Int = 0)
    private var nodes = ArrayList<Node>(); private var edges = ArrayList<Pair<Node, Node>>()
    private var byId = HashMap<Long, Node>()
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = (ctx.gold() and 0x00FFFFFF) or 0x66000000 }
    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f; color = ctx.gold() }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ctx.textPrimary(); textSize = ctx.dp(12).toFloat(); textAlign = Paint.Align.CENTER }
    private var scale = 1f; private var tx = 0f; private var ty = 0f
    private var iterations = 0
    private val colors = ThemeManager.folderColors
    private val gold = ctx.gold(); private val cardColor = ThemeManager.attr(ctx, R.attr.nbCardRaised)

    private val scaleDet = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean { val f = (scale * d.scaleFactor).coerceIn(0.4f, 3f) / scale; scale *= f; tx = d.focusX - (d.focusX - tx) * f; ty = d.focusY - (d.focusY - ty) * f; invalidate(); return true }
    })
    private val gest = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean { tx -= dx; ty -= dy; invalidate(); return true }
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val wx = (e.x - tx) / scale; val wy = (e.y - ty) / scale
            nodes.minByOrNull { hypot(it.x - wx, it.y - wy) }?.let { n -> if (hypot(n.x - wx, n.y - wy) < radius(n) + 24) onOpen(n.note.id) }
            return true
        }
        override fun onDown(e: MotionEvent) = true
    })

    fun setData(notes: List<Note>, links: List<NoteLink>) {
        nodes.clear(); edges.clear(); byId.clear()
        val w = max(width, 600); val h = max(height, 900)
        val rnd = java.util.Random(7)
        notes.forEachIndexed { i, n -> val ang = i * 2.399963f; val r = 80f + 18f * sqrt(i.toFloat()); Node(n, w / 2f + r * Math.cos(ang.toDouble()).toFloat() + rnd.nextFloat() * 8, h / 2f + r * Math.sin(ang.toDouble()).toFloat() + rnd.nextFloat() * 8).also { nodes += it; byId[n.id] = it } }
        links.forEach { l -> val a = byId[l.fromId]; val b = byId[l.toId]; if (a != null && b != null && a !== b) { edges += a to b; a.degree++; b.degree++ } }
        iterations = 0; scale = 1f; tx = 0f; ty = 0f
        postInvalidateOnAnimation()
    }

    private fun radius(n: Node) = context.dp(10) + min(n.degree, 8) * context.dp(2)

    private fun step() {
        val k = 120f
        for (a in nodes) { a.vx *= 0.85f; a.vy *= 0.85f }
        for (i in nodes.indices) for (j in i + 1 until nodes.size) {
            val a = nodes[i]; val b = nodes[j]; var dx = a.x - b.x; var dy = a.y - b.y; var d = hypot(dx, dy); if (d < 1f) { dx = 1f; dy = 0.5f; d = 1f }
            val f = (k * k / d) * 0.06f; dx /= d; dy /= d
            a.vx += dx * f; a.vy += dy * f; b.vx -= dx * f; b.vy -= dy * f
        }
        for ((a, b) in edges) {
            val dx = b.x - a.x; val dy = b.y - a.y; val d = max(1f, hypot(dx, dy)); val f = (d - k) * 0.02f
            a.vx += dx / d * f; a.vy += dy / d * f; b.vx -= dx / d * f; b.vy -= dy / d * f
        }
        val cx = width / 2f; val cy = height / 2f
        for (a in nodes) { a.vx += (cx - a.x) * 0.002f; a.vy += (cy - a.y) * 0.002f; a.x += a.vx; a.y += a.vy }
        iterations++
    }

    override fun onDraw(c: Canvas) {
        if (nodes.isEmpty()) return
        if (iterations < 300) { repeat(3) { step() }; postInvalidateOnAnimation() }
        c.save(); c.translate(tx, ty); c.scale(scale, scale)
        for ((a, b) in edges) c.drawLine(a.x, a.y, b.x, b.y, edgePaint)
        for (n in nodes) {
            val r = radius(n).toFloat()
            nodePaint.color = cardColor; c.drawCircle(n.x, n.y, r, nodePaint)
            nodePaint.color = if (n.note.color.id == 0) gold else ThemeManager.swatch(n.note.color); c.drawCircle(n.x, n.y, r * 0.55f, nodePaint)
            c.drawCircle(n.x, n.y, r, ringPaint)
            val label = n.note.displayTitle.ifBlank { "Untitled" }.take(22)
            c.drawText(label, n.x, n.y + r + textPaint.textSize + 4, textPaint)
        }
        c.restore()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean { scaleDet.onTouchEvent(e); if (!scaleDet.isInProgress) gest.onTouchEvent(e); return true }
}
