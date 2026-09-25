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
        return bar
    }

    private fun applyDesign() {
        val design = this.design ?: return
        val clock = clockView ?: return
        clock.design = design
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
                clockView?.transparentBackground = false
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
        applyDesign()
    }

    override fun onResume() {
        super.onResume()
        keepScreenOn(true)
        setControls(true)
        handler.postDelayed(hideControls, 4500L)
        clockView?.invalidate()
    }

    override fun onPause() {
        handler.removeCallbacks(hideControls)
        try {
            videoView?.pause()
        } catch (e: Throwable) {
        }
        super.onPause()
    }

    override fun onDestroy() {
        stopVideo()
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
