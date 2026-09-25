package ai.techtroy.clockcanvas.wallpaper

import ai.techtroy.clockcanvas.ClockDesign
import ai.techtroy.clockcanvas.data.DesignStore
import ai.techtroy.clockcanvas.media.MediaAccess
import ai.techtroy.clockcanvas.render.ClockRenderer
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder

/**
 * The live-wallpaper mode.
 *
 * A wallpaper is one `SurfaceView` the system composites *behind the launcher*: the
 * engine owns that surface and nothing else - no child views, no `VideoView`,
 * because a wallpaper surface cannot host a second decode surface. So the clock
 * and its background are both drawn here through the same
 * [ai.techtroy.clockcanvas.render.ClockRenderer] the widgets use. One code path, so
 * a design never looks different on the wallpaper than it does on the home screen.
 *
 * Motion: when the user enables it, [MediaMetadataRetriever] advances the clip one
 * frame per tick, which is how video can move on this surface at all without
 * private APIs. Frame-stepping costs CPU, so it is opt-in, and real 30 fps playback
 * stays in [ai.techtroy.clockcanvas.ui.FullscreenClockActivity] where we own a
 * playback surface. Every failure degrades - poster frame, then the design's
 * gradient - because an exception here takes the user's home screen with it.
 */
class ClockWallpaperService : WallpaperService() {

    /**
     * The stub android.jar this project also compiles against only carries
     * `WallpaperService.Engine` (no `CanvasEngine`, no `onOffsetsChanged`), so the
     * nested type is spelled out and the surface is drawn through the holder the
     * engine is handed. That is the older but universally available canvas route.
     */
    override fun onCreateEngine(): WallpaperService.Engine = ClockEngine(applicationContext)

    inner class ClockEngine(appContext: Context) : WallpaperService.Engine() {

        private val app = appContext.applicationContext
        private val prefs: SharedPreferences = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        private val handler = Handler(Looper.getMainLooper())
        private var holder: SurfaceHolder? = null
        private var frame: Bitmap? = null
        private var poster: Bitmap? = null
        private var retriever: MediaMetadataRetriever? = null
        // Media-wall stepping: which reel entry the poster currently holds, and the
        // worker that decodes the next one. A wallpaper surface has no second chance
        // at a janky main thread, so decoding never happens in draw().
        private var reelStep = -1
        private var reelPending = false
        private val reelThread = android.os.HandlerThread("ClockCanvasWall").apply { start() }
        private val reelWorker = android.os.Handler(reelThread.looper)
        private var seekMs = 0L
        private var width = 0
        private var height = 0
        private var drawing = false
        private var visible = false

        private val ticker = object : Runnable {
            override fun run() {
                val design = draw()
                handler.postDelayed(this, if (design == null) IDLE_MS else intervalMs(design))
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder?) {
            super.onCreate(surfaceHolder)
            holder = surfaceHolder
        }

        override fun onSurfaceCreated(surfaceHolder: SurfaceHolder?) {
            super.onSurfaceCreated(surfaceHolder)
            holder = surfaceHolder
            openMedia()
        }

        override fun onSurfaceChanged(surfaceHolder: SurfaceHolder?, format: Int, widthPx: Int, heightPx: Int) {
            super.onSurfaceChanged(surfaceHolder, format, widthPx, heightPx)
            width = widthPx
            height = heightPx
            recycleFrame()
        }

        override fun onSurfaceDestroyed(surfaceHolder: SurfaceHolder?) {
            stop()
            holder = null
            super.onSurfaceDestroyed(surfaceHolder)
        }

        override fun onVisibilityChanged(showing: Boolean) {
            visible = showing
            if (showing) {
                handler.removeCallbacks(ticker)
                handler.post(ticker)
            } else {
                handler.removeCallbacks(ticker)
            }
        }

        override fun onDestroy() {
            stop()
            super.onDestroy()
        }

        private fun stop() {
            handler.removeCallbacks(ticker)
            reelWorker.removeCallbacksAndMessages(null)
            releaseMedia()
            recycleFrame()
            reelThread.quitSafely()
        }

        private fun recycleFrame() {
            frame?.let {
                if (!it.isRecycled) it.recycle()
            }
            frame = null
        }

        // ---- what to draw ----------------------------------------------------------

        private fun designOrNull(): ClockDesign? = try {
            val store = DesignStore.of(app)
            store.byId(prefs.getString(KEY_DESIGN, null)) ?: store.resolveOrFallback(null)
        } catch (e: Throwable) {
            null
        }

        /**
         * Never a fixed "poll every N": an unaligned 60 s loop drifts, so the minute
         * hand could lag by up to a minute. Instead the engine sleeps exactly until
         * the next minute boundary (plus a small pad so the change is visible), and
         * only shortens that when seconds or video motion ask for it.
         */
        private fun intervalMs(design: ClockDesign): Long {
            if (design.mediaOnly) {
                return mediaWallIntervalMs(design)
            }
            val animate = design.showSeconds && prefs.getBoolean(KEY_SECONDS, false)
            if (animate) return TICK_MS
            if (motionEnabled()) return MOTION_MS
            val now = java.util.Calendar.getInstance()
            val intoMinute = now.get(java.util.Calendar.SECOND) * 1000L + now.get(java.util.Calendar.MILLISECOND)
            return (60_000L - intoMinute).coerceAtLeast(1_000L) + MINUTE_PAD_MS
        }

        private fun motionEnabled(): Boolean = prefs.getBoolean(KEY_MOTION, false) && retriever != null

        /**
         * A media wall has no clock to keep in step, so the only reason to wake up is
         * the next reel boundary. With one photo (or rotation off) we settle for a slow
         * re-check so a media file the user deleted still gets noticed.
         */
        private fun mediaWallIntervalMs(design: ClockDesign): Long {
            val extra = design.mediaReel.size + if (design.mediaUri != null && design.mediaReel.isEmpty()) 1 else 0
            if (extra < 2 || design.rotateSecs <= 0) return STATIC_MS
            val stepMs = design.rotateSecs * 1000L
            val remaining = stepMs - (System.currentTimeMillis() % stepMs)
            return (remaining + MINUTE_PAD_MS).coerceAtMost(60_000L).coerceAtLeast(500L)
        }

        /**
         * The poster for a media wall. The reel index for this instant is asked for
         * here; when it has moved we kick off a decode and keep serving the previous
         * frame until the new one lands, so the wallpaper never blanks between items.
         */
        private fun reelFrame(design: ClockDesign): Bitmap? {
            val reel = ai.techtroy.clockcanvas.media.MediaReel.forDesign(app, design)
            if (reel.isEmpty()) return null
            val step = reel.stepAt(design.rotateSecs)
            if (step != reelStep || poster == null) {
                reelStep = step
                val item = reel.itemAt(step)
                if (item != null && !reelPending) {
                    reelPending = true
                    reelWorker.post {
                        val bitmap = ai.techtroy.clockcanvas.media.MediaReel.bitmapFor(app, item, MAX_EDGE)
                        handler.post {
                            reelPending = false
                            val previous = poster
                            if (bitmap != null) {
                                poster = bitmap
                                if (previous != null && previous !== bitmap && !previous.isRecycled) {
                                    previous.recycle()
                                }
                            }
                            if (visible) draw()
                        }
                    }
                }
            }
            return poster
        }

        /** One stepped frame of the clip, else the still we fell back to. */
        private fun backgroundFor(design: ClockDesign): Bitmap? {
            if (design.mediaOnly) return reelFrame(design)
            val local = retriever
            if (local != null && motionEnabled()) {
                seekMs += FRAME_STEP_MS
                val grabbed = try {
                    local.getFrameAtTime(seekMs * 1000L)
                } catch (e: Throwable) {
                    null
                }
                if (grabbed != null) {
                    val scaled = MediaAccess.scaleDown(grabbed, MAX_EDGE)
                    if (scaled !== grabbed && !grabbed.isRecycled) grabbed.recycle()
                    poster?.let {
                        if (it !== scaled && !it.isRecycled) it.recycle()
                    }
                    poster = scaled
                    return scaled
                }
            }
            poster?.let { if (!it.isRecycled) return it }
            if (design.mediaUri != null) {
                poster = MediaAccess.posterFor(
                    app, design.mediaUri, prefs.getBoolean(KEY_IS_VIDEO, false), MAX_EDGE
                )
            }
            return poster
        }

        // ---- the frame -------------------------------------------------------------

        private fun draw(): ClockDesign? {
            if (drawing || !visible) return null
            val design = designOrNull() ?: return null
            val target = holder ?: return null
            if (target.surface == null || !target.surface.isValid) return null
            val w = if (width > 0) width else target.surfaceFrame.width()
            val h = if (height > 0) height else target.surfaceFrame.height()
            if (w <= 0 || h <= 0) return null
            drawing = true
            var canvas: Canvas? = null
            try {
                val offscreen = ensureFrame(w, h) ?: return design
                val local = Canvas(offscreen)
                local.drawColor(BG_FALLBACK)
                val bucket = ClockRenderer.bucketFor(
                    design,
                    (w / app.resources.displayMetrics.density).toInt(),
                    (h / app.resources.displayMetrics.density).toInt()
                )
                // "clock only over the wallpaper" cannot apply to a media wall: the
                // picture is the whole point of that mode, and the renderer already
                // omits the clock layer for it.
                val clockOnly = prefs.getBoolean(KEY_CLOCK_ONLY, false) && !design.mediaOnly
                ClockRenderer.of(app).drawDesign(
                    canvas = local,
                    widthPx = offscreen.width,
                    heightPx = offscreen.height,
                    design = design,
                    bucket = bucket,
                    fullscreen = true,
                    mediaOverride = if (clockOnly) null else backgroundFor(design),
                    skipBackground = clockOnly,
                )
                canvas = target.lockCanvas() ?: return design
                canvas.drawColor(Color.BLACK)
                canvas.drawBitmap(offscreen, null, Rect(0, 0, w, h), null)
            } catch (e: OutOfMemoryError) {
                recycleFrame()
            } catch (e: Throwable) {
                // A dropped frame beats a dead launcher.
            } finally {
                canvas?.let { open ->
                    try {
                        target.unlockCanvasAndPost(open)
                    } catch (e: Throwable) {
                    }
                }
                drawing = false
            }
            return design
        }

        /**
         * Offscreen buffer at a capped size. A wallpaper surface is screen-sized and
         * we repaint it from software, so the same pixel budget the widgets use keeps
         * a 1440x3200 panel from allocating three full-screen bitmaps per tick.
         */
        private fun ensureFrame(w: Int, h: Int): Bitmap? {
            val capped = ClockRenderer.budgetSize(w, h)
            val existing = frame
            if (existing != null && !existing.isRecycled && existing.width == capped.first && existing.height == capped.second) {
                return existing
            }
            recycleFrame()
            val created = try {
                Bitmap.createBitmap(
                    capped.first.coerceAtLeast(1),
                    capped.second.coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888
                )
            } catch (e: OutOfMemoryError) {
                null
            } catch (e: Throwable) {
                null
            }
            frame = created
            return created
        }

        // ---- media -------------------------------------------------------------------

        private fun openMedia() {
            releaseMedia()
            val uri = prefs.getString(KEY_MEDIA, null) ?: return
            if (!MediaAccess.canRead(app, uri)) return
            try {
                val created = MediaMetadataRetriever()
                created.setDataSource(app, Uri.parse(uri))
                retriever = created
            } catch (e: Throwable) {
                releaseMedia()
            }
        }

        private fun releaseMedia() {
            retriever?.let { local ->
                try {
                    local.release()
                } catch (e: Throwable) {
                }
            }
            retriever = null
        }
    }

    // Keys live on the service (not on the engine) so the setup screen can name them;
    // `ClockEngine` is an inner class, and inner classes cannot host a companion the
    // rest of the app can reach without an engine instance.
    companion object {
        const val PREFS = "clockcanvas_wallpaper"
        const val KEY_DESIGN = "design"
        const val KEY_MEDIA = "media"
        const val KEY_IS_VIDEO = "isVideo"
        const val KEY_SECONDS = "seconds"
        const val KEY_MOTION = "motion"
        const val KEY_CLOCK_ONLY = "clockOnly"

        private const val MAX_EDGE = 720
        private const val FRAME_STEP_MS = 1000L
        private const val TICK_MS = 500L
        /** Retry delay while there is no design to read (first run, store error). */
        private const val IDLE_MS = 5_000L
        private const val MOTION_MS = 1000L
        /** How often a completely still media wall re-reads its files. */
        private const val STATIC_MS = 60_000L
        /** Pad after the :00 boundary so the new minute is already rendered. */
        private const val MINUTE_PAD_MS = 400L
        private val BG_FALLBACK = Color.parseColor("#09070F")
    }
}
