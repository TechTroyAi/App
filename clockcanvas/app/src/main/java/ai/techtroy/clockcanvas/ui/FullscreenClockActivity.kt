package ai.techtroy.clockcanvas.ui

import ai.techtroy.clockcanvas.BackgroundKind
import ai.techtroy.clockcanvas.ClockDesign
import ai.techtroy.clockcanvas.R
import ai.techtroy.clockcanvas.data.DesignStore
import ai.techtroy.clockcanvas.media.MediaAccess
import ai.techtroy.clockcanvas.media.MediaHandler
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.VideoView
import kotlin.math.abs

/**
 * Full-screen clock - the one place in v1 where video actually *plays*.
 *
 * Why not in the widget: a home-screen widget is a RemoteViews tree owned by the
 * launcher process. There is no surface for a decoder, and a launcher-driven
 * refresh budget measured in minutes. So the widget gets a still frame from the
 * clip, and this activity - our own process, our own surface - plays it with a
 * framework `VideoView` (no Media3 dependency, so the app stays buildable
 * offline), with the clock drawn on top by [ClockCanvasView].
 */
class FullscreenClockActivity : ClockActivity() {

    private val store by lazy { DesignStore.of(this) }
    private val handler = Handler(Looper.getMainLooper())
    private var clockView: ClockCanvasView? = null
    private var videoView: VideoView? = null
    private var imageLayer: ImageView? = null
    private var controls: LinearLayout? = null
    private var design: ClockDesign? = null
    private var controlsVisible = true
    private var muted = true
    private var downX = 0f
    private var downY = 0f
    private val hideControls = Runnable { setControls(false) }

    override fun screenTitle(): String = getString(R.string.title_fullscreen)

    override fun showBackButton(): Boolean = false

    override fun showTopPreview(): Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        themeResIdSafe()
        super.onCreate(savedInstanceState)
        val widgetId = intent.getIntExtra(EXTRA_WIDGET_ID, -1)
        design = when {
            intent.hasExtra(EXTRA_DESIGN_ID) -> store.resolveOrFallback(intent.getStringExtra(EXTRA_DESIGN_ID))
            widgetId > 0 -> store.designFor(widgetId)
            else -> store.resolveOrFallback(ai.techtroy.clockcanvas.data.AppPrefs.of(this).load().lastDesignId)
        }
        mount()
        applyDesign()
    }

    private fun themeResIdSafe() {
        try {
            setTheme(R.style.Theme_ClockCanvas_Fullscreen)
        } catch (e: Throwable) {
        }
    }

    private fun mount() {
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        val image = ImageView(this)
        image.scaleType = ImageView.ScaleType.CENTER_CROP
        imageLayer = image
        root.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val video = VideoView(this)
        videoView = video
        video.setZOrderMediaOverlay(false)
        root.addView(video, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val clock = ClockCanvasView(this)
        clock.fullscreenMode = true
        clockView = clock
        root.addView(clock, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val bar = buildControls()
        controls = bar
        val barParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        barParams.gravity = Gravity.BOTTOM
        root.addView(bar, barParams)

        clock.onUserTap = { toggleControls() }
        clock.setOnTouchListener { _, event ->
            handleGesture(event)
            false
        }
        setContentView(root)
    }


    // ---- media wall ---------------------------------------------------------------

    private var reelStep = -1
    private var reelOverride = -1
    private val decodeExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val reelTick = Runnable {
        // Re-derive from the clock rather than incrementing a counter: that is what
        // keeps this screen in step with the widget and the wallpaper.
        reelOverride = -1
        design?.let { applyMediaWall(it) }
    }

    private fun applyMediaWall(design: ClockDesign) {
        val clock = clockView ?: return
        val reel = ai.techtroy.clockcanvas.media.MediaReel.forDesign(this, design)
        if (reel.isEmpty()) {
            handler.removeCallbacks(reelTick)
            stopVideo()
            imageLayer?.setImageDrawable(null)
            imageLayer?.visibility = View.GONE
            clock.transparentBackground = false
            clock.invalidate()
            return
        }
        val step = reel.stepAt(design.rotateSecs)
        val index = if (reelOverride >= 0) reelOverride else step
        if (index == reelStep) {
            clock.invalidate()
            scheduleReel(design, reel, step)
            return
        }
        reelStep = index
        val item = reel.itemAt(index) ?: return
        if (item.isVideo) {
            startVideo(item.uri)
            clock.transparentBackground = true
            imageLayer?.setImageDrawable(null)
        } else {
            stopVideo()
            // Transparent, on purpose: the activity decoded this frame at screen size and
            // put it in `imageLayer`. Letting the clock view paint its own smaller copy of
            // the same photo would only cover the sharp one.
            clock.transparentBackground = true
            imageLayer?.visibility = View.VISIBLE
            showPhoto(item.uri)
        }
        clock.invalidate()
        scheduleReel(design, reel, step)
    }

    /**
     * Decodes one media-wall frame off the main thread: a 12 MP photo paged in by a tap
     * is not free. `stepTag` is the reel position this call was made for, so a decode
     * that finishes after the reel moved on is dropped instead of overwriting the
     * current frame.
     */
    private fun showPhoto(uri: String, isVideo: Boolean = false, stepTag: Int = reelStep) {
        val layer = imageLayer ?: return
        layer.setImageResource(android.R.color.black)
        val maxEdge = maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        try {
            decodeExecutor.execute {
                val bitmap = ai.techtroy.clockcanvas.media.MediaReel.bitmapFor(
                    this,
                    ai.techtroy.clockcanvas.media.MediaReel.Item(uri, isVideo),
                    maxEdge.coerceAtMost(ai.techtroy.clockcanvas.render.ClockRenderer.MAX_PX),
                )
                handler.post {
                    if (bitmap == null) {
                        toast(getString(R.string.bg_media_missing))
                    } else if (stepTag >= 0 && stepTag != reelStep) {
                        if (!bitmap.isRecycled) bitmap.recycle()
                    } else {
                        layer.setImageBitmap(bitmap)
                        layer.visibility = View.VISIBLE
                    }
                }
            }
        } catch (e: Throwable) {
        }
    }

    private fun scheduleReel(design: ClockDesign, reel: ai.techtroy.clockcanvas.media.MediaReel, step: Int) {
        handler.removeCallbacks(reelTick)
        val wait = reel.msUntilNextStep(design.rotateSecs)
        if (wait == Long.MAX_VALUE) return
        handler.postDelayed(reelTick, (wait + 60L).coerceAtMost(120_000L))
    }

    /** Manual next/previous from the control bar; the schedule takes over at the next boundary. */
    private fun stepReel(delta: Int) {
        val design = this.design ?: return
        val reel = ai.techtroy.clockcanvas.media.MediaReel.forDesign(this, design)
        if (reel.isEmpty()) return
        reelOverride = reel.stepAfter(if (reelStep >= 0) reelStep else reel.stepAt(design.rotateSecs), delta)
        reelStep = -1
        applyMediaWall(design)
    }

    private fun buildControls(): LinearLayout {
        val bar = Ui.column(this)
        bar.setBackgroundColor(0xCC09070F.toInt())
        val pad = dp(16f)
        bar.setPadding(pad, pad, pad, dp(22f))
        Ui.add(bar, Ui.caption(this, getString(R.string.fullscreen_hint)), 0f)
        val row = Ui.columnRow(this)
        row.addView(Ui.ghostButton(this, getString(R.string.fullscreen_edit)) {
            design?.let { startActivity(Intent(this, EditorActivity::class.java).putExtra(EditorActivity.EXTRA_DESIGN_ID, it.id)) }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Ui.ghostButton(this, getString(R.string.fullscreen_add_widget)) {
            startActivity(Intent(this, EditorActivity::class.java).putExtra(EditorActivity.EXTRA_TAB, EditorActivity.TAB_WIDGETS))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(8f) })
        row.addView(Ui.ghostButton(this, getString(R.string.fullscreen_set_wallpaper)) {
            startActivity(Intent(this, WallpaperSetupActivity::class.java))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(8f) })
        Ui.add(bar, row, 12f)
        val second = Ui.columnRow(this)
        second.addView(
            Ui.toggle(this, getString(R.string.fullscreen_keep_awake), null, true) { on -> keepScreenOn(on) },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        second.addView(Ui.chip(this, if (muted) "Sound off" else "Sound on", !muted) { toggleMute() })
        Ui.add(bar, second, 8f)
        if (design?.mediaOnly == true) {
            val third = Ui.columnRow(this)
            third.addView(Ui.ghostButton(this, getString(R.string.media_wall_previous)) { stepReel(-1) },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            third.addView(Ui.ghostButton(this, getString(R.string.media_wall_next)) { stepReel(1) },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(8f) })
            Ui.add(bar, third, 8f)
            Ui.add(bar, Ui.caption(this, getString(R.string.media_wall_hint)), 6f)
        }
        return bar
    }

    private fun applyDesign() {
        val design = this.design ?: return
        val clock = clockView ?: return
        clock.design = design
        if (design.mediaOnly) {
            // Media wall: the reel decides what is on screen, and the clock layer is
            // intentionally absent, so this branch replaces the usual single-media path.
            applyMediaWall(design)
            return
        }
        handler.removeCallbacks(reelTick)
        val media = design.mediaUri
        val video = media != null && design.mediaIsVideo &&
            (design.backgroundKind == BackgroundKind.VIDEO_THUMBNAIL || design.backgroundKind == BackgroundKind.IMAGE)
        if (video && media != null) {
            startVideo(media)
            clock.transparentBackground = true
            imageLayer?.visibility = View.GONE
        } else {
            stopVideo()
            clock.transparentBackground = false
            val bitmap = if (media != null && !design.mediaIsVideo) MediaHandler.of(this).resolve(media) else null
            if (bitmap != null) {
                imageLayer?.setImageBitmap(bitmap)
                imageLayer?.visibility = View.VISIBLE
            } else {
                imageLayer?.setImageDrawable(null)
                imageLayer?.visibility = View.GONE
            }
        }
        clock.invalidate()
    }

    /** Handle on the player VideoView created, so mute can be changed mid-playback. */
    private var player: android.media.MediaPlayer? = null

    private fun startVideo(uri: String) {
        val video = videoView ?: return
        try {
            val parsed = MediaHandler.of(this).resolveUri(uri) ?: Uri.parse(uri)
            video.setVideoURI(parsed)
            video.setOnPreparedListener { prepared ->
                prepared.isLooping = true
                prepared.setVolume(if (muted) 0f else 1f, if (muted) 0f else 1f)
                player = prepared
                video.start()
            }
            video.setOnErrorListener { _, _, _ ->
                // Decoder failed (codec gone, file moved): fall back to the design's own background.
                stopVideo()
                video.visibility = View.GONE
                val current = design
                if (current != null && current.mediaOnly) {
                    // Media wall has no background of its own to fall back to, but a clip we
                    // cannot play still has a frame worth showing, and the reel keeps moving.
                    val item = ai.techtroy.clockcanvas.media.MediaReel
                        .forDesign(this, current).itemAt(if (reelStep >= 0) reelStep else 0)
                    if (item != null) showPhoto(item.uri, isVideo = true)
                } else {
                    clockView?.transparentBackground = false
                }
                clockView?.invalidate()
                true
            }
            video.visibility = View.VISIBLE
        } catch (e: Throwable) {
            video.visibility = View.GONE
        }
    }

    private fun stopVideo() {
        val video = videoView ?: return
        player = null
        try {
            video.pause()
            video.suspend()
            video.setVideoURI(null)
        } catch (e: Throwable) {
        }
        video.visibility = View.GONE
    }

    private fun toggleMute() {
        muted = !muted
        try {
            player?.setVolume(if (muted) 0f else 1f, if (muted) 0f else 1f)
        } catch (e: Throwable) {
        }
        controls?.let { rebuildControls() }
    }

    private fun rebuildControls() {
        val bar = controls ?: return
        val container = bar.parent as? FrameLayout ?: return
        val params = bar.layoutParams as FrameLayout.LayoutParams
        container.removeView(bar)
        val fresh = buildControls()
        controls = fresh
        container.addView(fresh, params)
    }

    private fun toggleControls() {
        setControls(!controlsVisible)
        handler.removeCallbacks(hideControls)
        if (controlsVisible) handler.postDelayed(hideControls, 4500L)
    }

    private fun setControls(visible: Boolean) {
        controlsVisible = visible
        controls?.visibility = if (visible) View.VISIBLE else View.GONE
        immersive(!visible)
    }

    /**
     * Horizontal swipe switches design, a tap toggles the controls. Done with raw
     * touch deltas instead of GestureDetector: two gestures, no ambiguity, and no
     * interference with the control bar underneath.
     */
    private fun handleGesture(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                handler.removeCallbacks(hideControls)
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.x - downX
                if (abs(dx) > dp(80f) && abs(event.y - downY) < dp(120f)) {
                    cycleDesign(if (dx < 0) 1 else -1)
                } else if (abs(dx) < dp(24f)) {
                    toggleControls()
                }
            }
        }
    }

    private fun cycleDesign(step: Int) {
        val designs = store.all()
        if (designs.isEmpty()) return
        val current = designs.indexOfFirst { it.id == design?.id }
        val next = (((if (current < 0) 0 else current) + step) % designs.size + designs.size) % designs.size
        design = designs[next]
        ai.techtroy.clockcanvas.data.AppPrefs.of(this).setLastDesign(designs[next].id)
        // A different design may start on the same reel index; force a fresh frame.
        reelStep = -1
        reelOverride = -1
        applyDesign()
    }

    override fun onResume() {
        super.onResume()
        keepScreenOn(true)
        setControls(true)
        handler.postDelayed(hideControls, 4500L)
        // Coming back to the screen after a long time: re-derive the media wall from
        // the clock instead of resuming where we left off.
        reelStep = -1
        reelOverride = -1
        design?.let { applyDesign() }
        clockView?.invalidate()
    }

    override fun onPause() {
        handler.removeCallbacks(hideControls)
        handler.removeCallbacks(reelTick)
        try {
            videoView?.pause()
        } catch (e: Throwable) {
        }
        super.onPause()
    }

    override fun onDestroy() {
        stopVideo()
        handler.removeCallbacks(reelTick)
        decodeExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun refresh() {
        val reloaded = store.byId(design?.id ?: "")
        if (reloaded != null) {
            design = reloaded
            applyDesign()
        }
    }

    override fun designForPreview(): ClockDesign? = design

    companion object {
        const val ACTION_SHOW = "ai.techtroy.clockcanvas.action.SHOW_CLOCK"
        const val EXTRA_DESIGN_ID = "design_id"
        const val EXTRA_WIDGET_ID = "widget_id"
    }
}
