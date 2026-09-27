package ai.techtroy.clockcanvas.ui

import ai.techtroy.clockcanvas.ClockDesign
import ai.techtroy.clockcanvas.WidgetBucket
import ai.techtroy.clockcanvas.render.ClockRenderer
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.max

/**
 * A live, resizable clock view used by the editor preview, the responsive size
 * comparison screen and the full-screen clock.
 *
 * It renders through the same [ClockRenderer] the widget uses, and it derives
 * its own bucket from *its own* measured size - so dragging the preview slider
 * exercises exactly the same responsive code path as dragging a widget on the
 * home screen.
 */
class ClockCanvasView(context: Context) : View(context) {

    var design: ClockDesign? = null
        set(value) {
            field = value
            invalidate()
        }

    /** When true the view paints no background: a VideoView behind us shows through. */
    var transparentBackground: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    /** Overrides the design's own media URI (session pick, wallpaper video, etc). */
    var mediaOverrideUri: String? = null
    var fullscreenMode: Boolean = false
    var onUserTap: (() -> Unit)? = null
    private var pendingMedia: Bitmap? = null
    private var mediaLoading = false
    private val overlayPaint = Paint()

    init {
        isFocusable = true
        // Software layers would kill the blur quality of RenderNode-free draws; we
        // deliberately stay hardware-accelerated and only rasterise what we must.
        setWillNotDraw(false)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        requestMediaReload()
    }

    override fun onDetachedFromWindow() {
        releaseMedia()
        super.onDetachedFromWindow()
    }

    fun requestMediaReload() {
        if (mediaLoading) return
        val design = this.design ?: return
        val uri = mediaOverrideUri ?: design.mediaUri
        val needsImage = uri != null && !transparentBackground
        if (!needsImage) {
            releaseMedia()
            invalidate()
            return
        }
        mediaLoading = true
        Thread {
            val bitmap = try {
                ai.techtroy.clockcanvas.media.MediaAccess.posterFor(
                    context, uri, design.mediaIsVideo, max(width, height).coerceAtLeast(600)
                )
            } catch (e: Throwable) {
                null
            }
            post {
                pendingMedia?.let { if (it !== bitmap && !it.isRecycled) it.recycle() }
                pendingMedia = bitmap
                mediaLoading = false
                invalidate()
            }
        }.start()
    }

    private fun releaseMedia() {
        pendingMedia?.let { if (!it.isRecycled) it.recycle() }
        pendingMedia = null
    }

    override fun onDraw(canvas: Canvas) {
        val design = this.design ?: run {
            canvas.drawColor(0xFF09070F.toInt())
            return
        }
        val w = max(1, width)
        val h = max(1, height)
        val renderer = ClockRenderer.of(context)
        val bucket = bucketForSize(w, h, design)
        if (transparentBackground) {
            // A VideoView / image surface sits underneath: only the clock, an optional
            // dim and the frame are drawn by us so nothing opaque hides the video.
            if (design.backgroundDarkness > 0.001f && !design.mediaOnly) {
                overlayPaint.color = ((255 * design.backgroundDarkness).toInt().coerceIn(0, 255) shl 24)
                canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), overlayPaint)
            }
            // Media wall: the layer underneath *is* the content, so this view keeps to
            // the frame. Painting a second, smaller copy of the same photo here would
            // only blur the one the activity decoded at screen size.
            if (!design.mediaOnly) renderer.drawClock(canvas, design, bucket, w, h)
            renderer.drawBorder(canvas, design, w, h)
            return
        }
        renderer.drawDesign(canvas, w, h, design, bucket, fullscreenMode, pendingMedia, false)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP && onUserTap != null) {
            onUserTap?.invoke()
            return true
        }
        return super.onTouchEvent(event)
    }

    private fun bucketForSize(wPx: Int, hPx: Int, design: ClockDesign): WidgetBucket {
        val density = resources.displayMetrics.density
        val widthDp = (wPx / density).toInt()
        val heightDp = (hPx / density).toInt()
        return ClockRenderer.bucketFor(design, widthDp, heightDp)
    }

    /** Re-reads the design from the store; used after the editor saves in-place. */
    fun reloadFromStore() {
        val id = design?.id ?: return
        design = ai.techtroy.clockcanvas.data.DesignStore.of(context).byId(id) ?: design
        requestMediaReload()
    }
}
