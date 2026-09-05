package ai.techtroy.notebook.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.ImageViewCompat
import ai.techtroy.notebook.App
import ai.techtroy.notebook.R
import ai.techtroy.notebook.core.FileStore
import ai.techtroy.notebook.core.Fmt
import ai.techtroy.notebook.core.ThemeManager
import ai.techtroy.notebook.core.dp
import ai.techtroy.notebook.core.gold
import ai.techtroy.notebook.core.hairline
import ai.techtroy.notebook.core.muted
import ai.techtroy.notebook.core.roundRect
import ai.techtroy.notebook.core.textPrimary
import ai.techtroy.notebook.core.tint
import ai.techtroy.notebook.data.Attachment
import ai.techtroy.notebook.data.AttachmentKind

/** 96dp square tile for the editor attachment strip. */
object AttachmentTile {
    fun create(a: BaseActivity, att: Attachment, onClick: () -> Unit): View {
        val ctx = a
        val size = ctx.dp(96)
        val tile = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = ctx.dp(10) }
            background = roundRect(ThemeManager.attr(ctx, R.attr.nbCardRaised), 14f, ctx, ctx.hairline())
            clipToOutline = true; isClickable = true; isFocusable = true
            foreground = with(android.util.TypedValue()) { ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, this, true); ctx.getDrawable(resourceId) }
            setOnClickListener { onClick() }
        }
        val img = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        tile.addView(img, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val thumb = att.thumbPath ?: if (att.kind == AttachmentKind.IMAGE) att.path else null
        if (thumb != null) a.app.async({ a.app.files.loadBitmap(thumb, 400) }) { b -> img.setImageBitmap(b) }
        else {
            val icon = ImageView(ctx).apply { setImageResource(iconFor(att.kind)); ImageViewCompat.setImageTintList(this, tint(ctx.gold())) }
            tile.addView(icon, FrameLayout.LayoutParams(ctx.dp(30), ctx.dp(30), Gravity.CENTER).apply { topMargin = -ctx.dp(8) })
        }
        // bottom label
        val label = TextView(ctx).apply {
            text = when (att.kind) { AttachmentKind.VIDEO -> "▶ " + Fmt.duration(att.durationMs); AttachmentKind.PDF -> "PDF · " + att.name; AttachmentKind.SKETCH -> ctx.getString(R.string.new_sketch); AttachmentKind.IMAGE -> ""; else -> att.name }
            textSize = 10f; setTextColor(0xFFF5F2EA.toInt()); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(ctx.dp(8), ctx.dp(14), ctx.dp(8), ctx.dp(6))
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x00000000, 0xB3000000.toInt()))
            visibility = if (text.isBlank()) View.GONE else View.VISIBLE
        }
        tile.addView(label, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        if (att.kind == AttachmentKind.VIDEO) {
            val play = ImageView(ctx).apply { setImageResource(R.drawable.ic_play); ImageViewCompat.setImageTintList(this, tint(0xFFFFFFFF.toInt())); background = ai.techtroy.notebook.core.circle(0x88000000.toInt()); setPadding(ctx.dp(7), ctx.dp(7), ctx.dp(5), ctx.dp(7)) }
            tile.addView(play, FrameLayout.LayoutParams(ctx.dp(32), ctx.dp(32), Gravity.CENTER))
        }
        return tile
    }

    fun iconFor(k: AttachmentKind) = when (k) {
        AttachmentKind.IMAGE -> R.drawable.ic_image; AttachmentKind.VIDEO -> R.drawable.ic_video; AttachmentKind.AUDIO -> R.drawable.ic_mic
        AttachmentKind.PDF -> R.drawable.ic_pdf; AttachmentKind.SKETCH -> R.drawable.ic_sketch; AttachmentKind.FILE -> R.drawable.ic_file
    }
}

/** Inline audio player row: play/pause, waveform scrub, time, speed. */
class AudioPlayerView(private val a: BaseActivity, var attachment: Attachment) {
    val view: View
    var onLongPress: (() -> Unit)? = null
    private var player: MediaPlayer? = null
    private var prepared = false
    private var speedIdx = 0
    private val speeds = floatArrayOf(1f, 1.5f, 2f)
    private val handler = Handler(Looper.getMainLooper())
    private val wave: WaveView
    private val time: TextView
    private val playBtn: ImageButton
    private val speedBtn: TextView
    private val tick = object : Runnable { override fun run() { val p = player ?: return; if (prepared) { wave.progress = p.currentPosition / p.duration.coerceAtLeast(1).toFloat(); time.text = Fmt.duration(p.currentPosition.toLong()) + " / " + Fmt.duration(p.duration.toLong()) }; if (p.isPlaying) handler.postDelayed(this, 100) } }

    init {
        val ctx = a
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dp(10) }
            background = roundRect(ThemeManager.attr(ctx, R.attr.nbCardRaised), 16f, ctx, ctx.hairline()); setPadding(ctx.dp(10), ctx.dp(8), ctx.dp(12), ctx.dp(8))
            setOnLongClickListener { onLongPress?.invoke(); true }
        }
        playBtn = ImageButton(ctx).apply {
            setImageResource(R.drawable.ic_play); background = ai.techtroy.notebook.core.circle(ctx.gold()); ImageViewCompat.setImageTintList(this, tint(0xFF0B0B0C.toInt()))
            setPadding(ctx.dp(10), ctx.dp(9), ctx.dp(8), ctx.dp(9)); setOnClickListener { toggle() }
        }
        row.addView(playBtn, LinearLayout.LayoutParams(ctx.dp(38), ctx.dp(38)))
        val mid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(ctx.dp(12), 0, ctx.dp(8), 0) }
        val name = TextView(ctx).apply { text = attachment.name; textSize = 13f; setTextColor(ctx.textPrimary()); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        wave = WaveView(ctx, Media.decodeWave(attachment.data)) { frac -> seekTo(frac) }
        time = TextView(ctx).apply { text = Fmt.duration(attachment.durationMs); textSize = 11f; setTextColor(ctx.muted()) }
        mid.addView(name); mid.addView(wave, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ctx.dp(30)).apply { topMargin = ctx.dp(4) }); mid.addView(time)
        row.addView(mid, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        speedBtn = TextView(ctx).apply {
            text = "1×"; textSize = 12f; setTextColor(ctx.gold()); typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = roundRect(0, 12f, ctx, ctx.gold()); setPadding(ctx.dp(8), ctx.dp(4), ctx.dp(8), ctx.dp(4))
            setOnClickListener { speedIdx = (speedIdx + 1) % speeds.size; text = speeds[speedIdx].let { if (it == 1f) "1×" else "${it}×" }; applySpeed() }
        }
        row.addView(speedBtn)
        view = row
    }

    private fun ensure(): MediaPlayer {
        player?.let { return it }
        val p = MediaPlayer()
        p.setDataSource(a.app.files.file(attachment.path).absolutePath)
        p.setOnCompletionListener { playBtn.setImageResource(R.drawable.ic_play); wave.progress = 0f; handler.removeCallbacks(tick) }
        p.prepare(); prepared = true; player = p
        return p
    }

    private fun toggle() {
        val p = runCatching { ensure() }.getOrElse { a.app.let { _ -> }; return }
        if (p.isPlaying) { p.pause(); playBtn.setImageResource(R.drawable.ic_play); handler.removeCallbacks(tick) }
        else { applySpeed(); p.start(); playBtn.setImageResource(R.drawable.ic_pause); handler.post(tick) }
    }

    private fun applySpeed() {
        val p = player ?: return
        if (android.os.Build.VERSION.SDK_INT >= 23) runCatching { val was = p.isPlaying; p.playbackParams = p.playbackParams.setSpeed(speeds[speedIdx]); if (!was) p.pause() }
    }

    private fun seekTo(frac: Float) { val p = runCatching { ensure() }.getOrNull() ?: return; p.seekTo((frac * p.duration).toInt()); wave.progress = frac; if (!p.isPlaying) time.text = Fmt.duration(p.currentPosition.toLong()) + " / " + Fmt.duration(p.duration.toLong()) }
    fun pause() { player?.let { if (it.isPlaying) { it.pause(); playBtn.setImageResource(R.drawable.ic_play) } }; handler.removeCallbacks(tick) }
    fun release() { handler.removeCallbacks(tick); player?.release(); player = null; prepared = false }
}

class WaveView(ctx: android.content.Context, private val bars: FloatArray, private val onSeek: (Float) -> Unit) : View(ctx) {
    var progress = 0f
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val gold = ctx.gold(); private val dim = ctx.muted()

    override fun onDraw(c: Canvas) {
        val n = bars.size; if (n == 0) return
        val w = width.toFloat(); val h = height.toFloat()
        val gap = w / n; p.strokeWidth = (gap * 0.55f).coerceAtLeast(2f)
        for (i in 0 until n) {
            val x = gap * i + gap / 2
            val bh = (bars[i].coerceIn(0.08f, 1f)) * (h - 4f)
            p.color = if (i / n.toFloat() <= progress) gold else (dim and 0x00FFFFFF) or 0x66000000
            c.drawLine(x, h / 2 - bh / 2, x, h / 2 + bh / 2, p)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { parent.requestDisallowInterceptTouchEvent(true); progress = (e.x / width).coerceIn(0f, 1f) }
            MotionEvent.ACTION_MOVE -> progress = (e.x / width).coerceIn(0f, 1f)
            MotionEvent.ACTION_UP -> { onSeek((e.x / width).coerceIn(0f, 1f)); parent.requestDisallowInterceptTouchEvent(false) }
            MotionEvent.ACTION_CANCEL -> parent.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }
}
