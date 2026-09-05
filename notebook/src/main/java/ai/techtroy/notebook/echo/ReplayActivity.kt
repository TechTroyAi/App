package ai.techtroy.notebook.echo

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.widget.ImageViewCompat
import ai.techtroy.notebook.R
import ai.techtroy.notebook.core.Fmt
import ai.techtroy.notebook.core.circle
import ai.techtroy.notebook.core.dp
import ai.techtroy.notebook.core.gold
import ai.techtroy.notebook.core.roundRect
import ai.techtroy.notebook.core.show
import ai.techtroy.notebook.core.tint
import ai.techtroy.notebook.data.Attachment
import ai.techtroy.notebook.data.AttachmentKind
import ai.techtroy.notebook.data.BodyType
import ai.techtroy.notebook.data.ChecklistItem
import ai.techtroy.notebook.data.Note
import ai.techtroy.notebook.data.Page
import ai.techtroy.notebook.ink.InkView
import ai.techtroy.notebook.ink.PaperStyle
import ai.techtroy.notebook.ink.Stroke
import ai.techtroy.notebook.ink.StrokeDoc
import ai.techtroy.notebook.ink.StrokeRenderer
import ai.techtroy.notebook.ink.Tool
import ai.techtroy.notebook.ui.BaseActivity
import ai.techtroy.notebook.ui.LinkSpans
import kotlin.math.max
import kotlin.math.min

/**
 * Replay this note — a slow, dark, gold-lit playback of the note being written.
 *
 * Built from the note's history timeline (create/text/check/attach/link/ink…), plus stroke timestamps for
 * Pages notes and the real voice memo played at the moment it was recorded. Text is revealed character by
 * character in proportion to how the note grew between saves, checks tick on, strokes draw themselves,
 * links snap in. Fully offline; nothing is stored by watching a replay.
 */
class ReplayActivity : BaseActivity() {

    private class Event(val at: Long, val kind: String, val payload: String)

    private var noteId = -1L
    private var note: Note? = null
    private var items: List<ChecklistItem> = emptyList()
    private var atts: List<Attachment> = emptyList()
    private var pages: List<Page> = emptyList()
    private var events: List<Event> = emptyList()
    private var titles: Map<Long, String?> = emptyMap()

    private lateinit var scroll: ScrollView
    private lateinit var titleView: TextView
    private lateinit var bodyView: TextView
    private lateinit var checkCol: LinearLayout
    private lateinit var attachRow: LinearLayout
    private lateinit var status: TextView
    private lateinit var seek: SeekBar
    private lateinit var playBtn: ImageButton
    private lateinit var speedBtn: TextView
    private lateinit var linkRow: LinearLayout
    private var inkView: ReplayInkView? = null
    private lateinit var glow: GlowView

    private val handler = Handler(Looper.getMainLooper())
    private var playing = false
    private var speed = 1f
    private var t = 0f              // virtual ms since start
    private var total = 1f
    private var lastTick = 0L
    private var player: MediaPlayer? = null
    private var playerAtt: Attachment? = null
    private var playerStartT = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        noteId = intent.getLongExtra(EXTRA_ID, -1)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(buildUi())
        load()
    }

    private fun buildUi(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(0xFF070708.toInt()) }
        glow = GlowView(this); root.addView(glow, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(6), dp(10), 0) }
        top.addView(ImageButton(this).apply { setImageResource(R.drawable.ic_close); background = null; ImageViewCompat.setImageTintList(this, tint(0xFFA8862A.toInt())); setOnClickListener { finish() } }, LinearLayout.LayoutParams(dp(44), dp(44)))
        top.addView(TextView(this).apply { text = "✦ REPLAY"; textSize = 11f; letterSpacing = 0.22f; setTextColor(0xFFD4AF37.toInt()); typeface = Typeface.DEFAULT_BOLD }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        status = TextView(this).apply { textSize = 11f; setTextColor(0xFF8C7A4A.toInt()); typeface = Typeface.MONOSPACE }
        top.addView(status)
        col.addView(top)

        scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(26), dp(30), dp(26), dp(40)) }
        titleView = TextView(this).apply { textSize = 26f; setTextColor(0xFFF5F2EA.toInt()); typeface = Typeface.create("serif", Typeface.NORMAL); setLineSpacing(0f, 1.1f) }
        content.addView(titleView)
        bodyView = TextView(this).apply { textSize = 17f; setTextColor(0xFFE3DCCB.toInt()); setLineSpacing(0f, 1.35f); setPadding(0, dp(14), 0, 0) }
        content.addView(bodyView)
        checkCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(12), 0, 0) }
        content.addView(checkCol)
        attachRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(18), 0, 0) }
        content.addView(attachRow)
        linkRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(18), 0, 0) }
        content.addView(linkRow)
        scroll.addView(content)
        col.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // transport
        val bar = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(6), dp(16), dp(14)) }
        seek = SeekBar(this).apply {
            max = 1000; progressTintList = tint(0xFFD4AF37.toInt()); thumbTintList = tint(0xFFF0D77A.toInt()); progressBackgroundTintList = tint(0x33D4AF37)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) { t = total * p / 1000f; render(); syncAudio(force = true) } }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        bar.addView(seek)
        val ctl = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val dateLabel = TextView(this).apply { id = R.id.replayDate; textSize = 12f; setTextColor(0xFF8C7A4A.toInt()) }
        ctl.addView(dateLabel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        speedBtn = TextView(this).apply { text = "1×"; textSize = 13f; typeface = Typeface.DEFAULT_BOLD; setTextColor(0xFFD4AF37.toInt()); setPadding(dp(14), dp(6), dp(14), dp(6)); background = roundRect(0, 16f, this@ReplayActivity, 0x66D4AF37); setOnClickListener { speed = when (speed) { 1f -> 2f; 2f -> 4f; else -> 1f }; text = "${speed.toInt()}×"; player?.let { p -> if (android.os.Build.VERSION.SDK_INT >= 23) runCatching { p.playbackParams = p.playbackParams.setSpeed(min(speed, 2f)) } } } }
        ctl.addView(speedBtn)
        playBtn = ImageButton(this).apply { setImageResource(R.drawable.ic_play); background = circle(0xFFD4AF37.toInt()); ImageViewCompat.setImageTintList(this, tint(0xFF0B0B0C.toInt())); setOnClickListener { if (playing) pause() else play() } }
        ctl.addView(playBtn, LinearLayout.LayoutParams(dp(52), dp(52)).apply { marginStart = dp(12) })
        bar.addView(ctl)
        col.addView(bar)
        root.addView(col)
        return root
    }

    // ------------------------------------------------------------------ data

    private fun load() {
        app.async({
            val n = app.repo.note(noteId) ?: return@async null
            val its = app.repo.checklist(noteId)
            val at = app.repo.attachments(noteId)
            val pg = if (n.bodyType == BodyType.PAGES) app.repo.pages(noteId) else emptyList()
            val hist = app.repo.historyOf(noteId).map { Event(it.first, it.second, it.third) }
            val titles = HashMap<Long, String?>(); LinkSpans.ids(n.body).forEach { id -> titles[id] = app.repo.note(id)?.takeIf { !it.inTrash }?.displayTitle }
            app.repo.links(noteId).forEach { l -> titles[l.toId] = app.repo.note(l.toId)?.takeIf { !it.inTrash }?.displayTitle }
            Triple(n, Triple(its, at, pg), hist to titles)
        }) { r ->
            if (r == null) { finish(); return@async }
            note = r.first; items = r.second.first; atts = r.second.second; pages = r.second.third; events = r.third.first; titles = r.third.second
            buildTimeline(); render()
            handler.postDelayed({ play() }, 600)
        }
    }

    // The virtual timeline compresses real time: every event gets a slot proportional to log(gap), so a
    // note written over weeks still replays in about a minute, while keeping the feeling of pauses.
    private class Slot(val start: Float, val end: Float, val ev: Event)
    private val slots = ArrayList<Slot>()
    private var strokeTimes: List<Pair<Float, Stroke>> = emptyList()   // virtual start time -> stroke
    private var strokeDocs: List<StrokeDoc> = emptyList()
    private var textSteps: List<Pair<Float, Int>> = emptyList()          // virtual time -> chars revealed of (title+body)
    private var checkSteps: List<Pair<Float, Long>> = emptyList()
    private var attachSteps: List<Pair<Float, Long>> = emptyList()
    private var linkSteps: List<Pair<Float, Long>> = emptyList()
    private var audioSteps: List<Pair<Float, Attachment>> = emptyList()

    private fun buildTimeline() {
        val n = note ?: return
        slots.clear()
        val evs = events.filter { it.kind in setOf("create", "text", "check", "uncheck", "attach", "sketch", "annotate", "ink", "link", "type") }.ifEmpty { listOf(Event(n.createdAt, "create", n.bodyType.db), Event(n.updatedAt, "text", "${n.title.length}|${n.body.length}")) }
        var cursor = 0f
        var prev = evs.first().at
        val realSpan = max(1L, evs.last().at - evs.first().at)
        for (e in evs) {
            val gap = (e.at - prev).coerceAtLeast(0L)
            // pauses: 0.4s min; long gaps get up to 2.2s of "breathing"
            val pause = if (gap <= 0) 0.4f else (0.4f + 1.8f * min(1f, Math.log10(1.0 + gap / 1000.0).toFloat() / 6f))
            val dur = when (e.kind) { "text" -> textDuration(e); "ink" -> 4.5f; "attach" -> 1.4f; "sketch", "annotate" -> 1.6f; "link" -> 1.0f; "check", "uncheck" -> 0.7f; else -> 0.8f }
            val start = cursor + pause * 1000f
            slots += Slot(start, start + dur * 1000f, e)
            cursor = start + dur * 1000f; prev = e.at
        }
        total = max(cursor + 1200f, 3000f)

        // text: total chars revealed progresses through the "text" slots
        val fullLen = n.title.length + 1 + n.body.length
        val textSlots = slots.filter { it.ev.kind == "text" }
        val steps = ArrayList<Pair<Float, Int>>()
        if (textSlots.isEmpty()) steps += 0f to fullLen
        else {
            // payload "titleLen|bodyLen" = size at that save; map to chars of the current text, monotone
            var last = 0
            textSlots.forEach { s ->
                val (tl, bl) = s.ev.payload.split("|").let { p -> (p.getOrNull(0)?.toIntOrNull() ?: 0) to (p.getOrNull(1)?.toIntOrNull() ?: 0) }
                val target = min(fullLen, max(last, tl + 1 + bl))
                steps += s.start to last; steps += s.end to target; last = target
            }
            if (last < fullLen) steps += total - 800f to fullLen
        }
        textSteps = steps
        checkSteps = slots.filter { it.ev.kind == "check" }.mapNotNull { s -> s.ev.payload.toLongOrNull()?.let { s.start to it } }
        attachSteps = slots.filter { it.ev.kind == "attach" || it.ev.kind == "sketch" }.mapNotNull { s -> s.ev.payload.substringAfter(':').toLongOrNull()?.let { s.start to it } }
        linkSteps = slots.filter { it.ev.kind == "link" }.mapNotNull { s -> s.ev.payload.toLongOrNull()?.let { s.start to it } }
        // voice memos start playing at the moment they were attached
        audioSteps = attachSteps.mapNotNull { (st, id) -> atts.firstOrNull { it.id == id && it.kind == AttachmentKind.AUDIO }?.let { st to it } }

        // pages: strokes draw in their real relative order inside the ink slots
        if (pages.isNotEmpty()) {
            strokeDocs = pages.map { StrokeDoc.parse(it.strokes, it.objects) }
            val all = strokeDocs.flatMap { d -> d.strokes.filter { it.tool != Tool.ERASER && it.tool != Tool.LASER && it.count > 0 } }.sortedBy { it.startTime }
            val inkSlots = slots.filter { it.ev.kind == "ink" }
            val span = if (inkSlots.isEmpty()) (1000f to total - 1500f) else (inkSlots.first().start to inkSlots.last().end)
            val n0 = all.size.coerceAtLeast(1)
            strokeTimes = all.mapIndexed { i, s -> (span.first + (span.second - span.first) * i / n0) to s }
            if (inkView == null) {
                val v = ReplayInkView(this, strokeDocs.first(), pages.first())
                inkView = v
                (bodyView.parent as LinearLayout).addView(v, 2, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
            }
        }
        findViewById<TextView>(R.id.replayDate).text = "Started " + Fmt.dateTime(n.createdAt) + "  ·  " + (if (evs.size > 2) "${evs.size} moments" else "one sitting")
    }

    private fun textDuration(e: Event): Float {
        val (tl, bl) = e.payload.split("|").let { p -> (p.getOrNull(0)?.toIntOrNull() ?: 0) to (p.getOrNull(1)?.toIntOrNull() ?: 0) }
        return (1.2f + (tl + bl) / 60f).coerceIn(1.2f, 7f)
    }

    // ---------------------------------------------------------------- render

    private fun render() {
        val n = note ?: return
        // text reveal
        val full = (n.title + "\n" + n.body)
        var chars = 0
        for (i in textSteps.indices) {
            val (tt, c) = textSteps[i]
            if (t >= tt) chars = c else { if (i > 0) { val (pt, pc) = textSteps[i - 1]; val f = ((t - pt) / max(1f, tt - pt)).coerceIn(0f, 1f); chars = pc + ((c - pc) * f).toInt() }; break }
        }
        chars = chars.coerceIn(0, full.length)
        val shown = full.substring(0, chars)
        val nl = shown.indexOf('\n')
        val titleShown = if (nl < 0) shown else shown.substring(0, nl)
        val bodyShown = if (nl < 0) "" else shown.substring(nl + 1)
        titleView.text = titleShown; titleView.show(titleShown.isNotEmpty() || n.title.isNotEmpty() && chars > 0)
        val sb = SpannableStringBuilder(bodyShown)
        LinkSpans.apply(this, sb, titles)
        if (chars < full.length && playing) sb.append("▍")
        bodyView.text = sb; bodyView.show(bodyShown.isNotEmpty() || playing)

        // checklist
        if (n.bodyType == BodyType.CHECKLIST) {
            if (checkCol.childCount != items.size) { checkCol.removeAllViews(); items.forEach { checkCol.addView(checkRow(it)) } }
            items.forEachIndexed { i, it ->
                val row = checkCol.getChildAt(i) as LinearLayout
                val when_ = checkSteps.firstOrNull { it2 -> it2.second == it.id }?.first
                val checked = it.checked && (when_ == null || t >= when_)
                val dot = row.getChildAt(0); val tv = row.getChildAt(1) as TextView
                dot.background = if (checked) circle(0xFFD4AF37.toInt()) else circle(0, 0xFF8C7A4A.toInt(), dp(2))
                tv.paintFlags = if (checked) tv.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG else tv.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
                tv.alpha = if (checked) 0.55f else 1f
                row.show(chars >= full.length * 0.5f || textSteps.size <= 1)
            }
        }
        // attachments
        val visibleAtts = atts.filter { a -> val st = attachSteps.firstOrNull { it.second == a.id }?.first; st == null || t >= st }
        if (attachRow.childCount != visibleAtts.size) {
            attachRow.removeAllViews()
            visibleAtts.forEach { a ->
                val tile = FrameLayout(this).apply { background = roundRect(0xFF161618.toInt(), 12f, this@ReplayActivity, 0x66D4AF37); clipToOutline = true }
                val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                tile.addView(iv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                val thumb = a.thumbPath ?: (if (a.kind == AttachmentKind.IMAGE) a.path else null)
                if (thumb != null) app.async({ app.files.loadBitmap(thumb, 300) }) { b -> iv.setImageBitmap(b) }
                else tile.addView(ImageView(this).apply { setImageResource(when (a.kind) { AttachmentKind.AUDIO -> R.drawable.ic_mic; AttachmentKind.PDF -> R.drawable.ic_file; AttachmentKind.VIDEO -> R.drawable.ic_play; else -> R.drawable.ic_file }); ImageViewCompat.setImageTintList(this, tint(0xFFD4AF37.toInt())) }, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
                tile.alpha = 0f; tile.animate().alpha(1f).setDuration(700).start()
                attachRow.addView(tile, LinearLayout.LayoutParams(dp(74), dp(74)).apply { marginEnd = dp(10) })
            }
        }
        // links snapping in
        val visibleLinks = linkSteps.filter { t >= it.first }.map { it.second }.distinct()
        if (linkRow.childCount != visibleLinks.size) {
            linkRow.removeAllViews()
            visibleLinks.forEach { id ->
                val chip = TextView(this).apply { text = "🔗  " + (titles[id] ?: "(deleted)"); textSize = 13f; setTextColor(0xFFF0D77A.toInt()); background = roundRect(0x22D4AF37, 14f, this@ReplayActivity, 0x66D4AF37); setPadding(dp(12), dp(7), dp(12), dp(7)) }
                chip.scaleX = 0.6f; chip.scaleY = 0.6f; chip.alpha = 0f; chip.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(450).setInterpolator(android.view.animation.OvershootInterpolator()).start()
                linkRow.addView(chip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
            }
        }
        // ink
        inkView?.let { v -> v.upTo = strokeTimes.count { t >= it.first }; v.partialFrac = strokeTimes.firstOrNull { t < it.first }?.let { nxt -> val idx = strokeTimes.indexOf(nxt); if (idx > 0) { val (ps, _) = strokeTimes[idx - 1]; ((t - ps) / max(1f, nxt.first - ps)).coerceIn(0f, 1f) } else 0f } ?: 1f; v.invalidate() }

        status.text = Fmt.duration(t.toLong()) + " / " + Fmt.duration(total.toLong())
        seek.progress = (t / total * 1000).toInt()
        glow.intensity = if (playing) 1f else 0.5f
        if (playing) scroll.post { scroll.smoothScrollTo(0, max(0f, (scroll.getChildAt(0).height - scroll.height) * (t / total)).toInt()) }
    }

    private fun checkRow(it: ChecklistItem): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, dp(6))
        addView(View(this@ReplayActivity), LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(14) })
        addView(TextView(this@ReplayActivity).apply { text = it.text; textSize = 16f; setTextColor(0xFFE3DCCB.toInt()) })
    }

    // ------------------------------------------------------------- transport

    private val tick = object : Runnable {
        override fun run() {
            if (!playing) return
            val now = SystemClock.uptimeMillis()
            t += (now - lastTick) * speed; lastTick = now
            if (t >= total) { t = total; render(); pause(); return }
            render(); syncAudio(force = false)
            handler.postDelayed(this, 33)
        }
    }
    private fun play() { if (t >= total) t = 0f; playing = true; lastTick = SystemClock.uptimeMillis(); playBtn.setImageResource(R.drawable.ic_pause); handler.post(tick); glow.start() }
    private fun pause() { playing = false; playBtn.setImageResource(R.drawable.ic_play); handler.removeCallbacks(tick); player?.let { if (it.isPlaying) it.pause() }; render() }

    private fun syncAudio(force: Boolean) {
        val step = audioSteps.lastOrNull { t >= it.first } ?: run { player?.let { if (it.isPlaying) it.pause() }; return }
        val (st, a) = step
        val pos = ((t - st) / max(1f, speed)).toLong()     // audio runs at real speed (capped 2× by params)
        if (pos > a.durationMs + 500) { player?.let { if (it.isPlaying) it.pause() }; return }
        if (playerAtt?.id != a.id) {
            player?.release(); player = null
            player = runCatching { MediaPlayer().apply { setDataSource(app.files.file(a.path).absolutePath); prepare() } }.getOrNull()
            playerAtt = a; playerStartT = st
        }
        val p = player ?: return
        if (force || !p.isPlaying) { runCatching { p.seekTo(pos.toInt()); if (playing) p.start() } }
    }

    override fun onPause() { super.onPause(); pause() }
    override fun onDestroy() { player?.release(); player = null; super.onDestroy() }

    // ---------------------------------------------------------------- views

    /** Warm gold light that breathes behind the text while replay runs. */
    class GlowView(ctx: Context) : View(ctx) {
        var intensity = 0.6f
        private var phase = 0f
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private var anim: ValueAnimator? = null
        fun start() { if (anim != null) return; anim = ValueAnimator.ofFloat(0f, 1f).apply { duration = 5200; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE; addUpdateListener { phase = it.animatedValue as Float; invalidate() }; start() } }
        override fun onDetachedFromWindow() { anim?.cancel(); anim = null; super.onDetachedFromWindow() }
        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val a = (0.10f + 0.10f * phase) * intensity
            p.shader = RadialGradient(w * (0.2f + 0.6f * phase), h * 0.18f, w * 0.9f, intArrayOf(((a * 255).toInt() shl 24) or 0xD4AF37, 0x00D4AF37), null, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, h, p)
            p.shader = LinearGradient(0f, h * 0.7f, 0f, h, 0x00000000, 0xAA000000.toInt(), Shader.TileMode.CLAMP)
            c.drawRect(0f, h * 0.7f, w, h, p)
        }
    }

    /** Draws the first page's strokes progressively (upTo strokes fully + partialFrac of the next). */
    class ReplayInkView(ctx: Context, private val doc: StrokeDoc, private val page: Page) : View(ctx) {
        var upTo = 0
        var partialFrac = 0f
        private val renderer = StrokeRenderer()
        private val paperPainter = ai.techtroy.notebook.ink.PaperPainter()
        private val ordered = doc.strokes.filter { it.tool != Tool.ERASER && it.tool != Tool.LASER && it.count > 0 }.sortedBy { it.startTime }
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = 0x66F0D77A; maskFilter = android.graphics.BlurMaskFilter(18f, android.graphics.BlurMaskFilter.Blur.NORMAL) }
        init { setLayerType(LAYER_TYPE_SOFTWARE, null); background = roundRect(0, 14f, ctx, 0x55D4AF37); clipToOutline = true }
        override fun onMeasure(w: Int, h: Int) { val ww = MeasureSpec.getSize(w); setMeasuredDimension(ww, (ww * doc.h / doc.w).toInt()) }
        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            paperPainter.draw(c, w, h, PaperStyle.from(page.paper), page.dark, doc.bg)
            for (i in 0 until min(upTo, ordered.size)) renderer.draw(c, ordered[i], w, h)
            if (upTo < ordered.size && partialFrac > 0f) {
                val s = ordered[upTo]; val n = (s.count * partialFrac).toInt().coerceAtLeast(1)
                renderer.draw(c, s, w, h, upTo = n)
                // gold tip glow at the pen position
                val x = s.x(n - 1) * w; val y = s.y(n - 1) * h
                glowPaint.strokeWidth = 14f; c.drawPoint(x, y, glowPaint)
            }
        }
    }

    companion object {
        const val EXTRA_ID = "note_id"
        fun intent(ctx: Context, noteId: Long) = Intent(ctx, ReplayActivity::class.java).putExtra(EXTRA_ID, noteId)
    }
}
