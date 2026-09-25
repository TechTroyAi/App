package ai.techtroy.clockcanvas.render

import ai.techtroy.clockcanvas.BackgroundKind
import ai.techtroy.clockcanvas.ClockDesign
import ai.techtroy.clockcanvas.ClockGravity
import ai.techtroy.clockcanvas.ClockStyle
import ai.techtroy.clockcanvas.ContentMode
import ai.techtroy.clockcanvas.FontRegistry
import ai.techtroy.clockcanvas.GradientKind
import ai.techtroy.clockcanvas.OrientationMode
import ai.techtroy.clockcanvas.WidgetBucket
import ai.techtroy.clockcanvas.data.AppPrefs
import ai.techtroy.clockcanvas.media.MediaAccess
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.net.Uri
import android.os.Build
import android.text.TextPaint
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Turns a [ClockDesign] plus a pixel size into the exact pixels a widget (or the
 * full-screen clock, or the live wallpaper) shows.
 *
 * This is the only place in the app that knows how a design looks. The widget,
 * the editor previews and the full-screen clock all call into it, so what the
 * user sees in the editor is bit-for-bit what lands on the home screen.
 *
 * Three things this class is careful about:
 *  - Sizing: the launcher owns the widget size, so the clock is *derived* from
 *    the size we are given (see [bucketFor]), never assumed.
 *  - Memory: pixel budget is capped and decoded backgrounds are downsampled at
 *    decode time, so a 108-megapixel camera roll photo cannot OOM a launcher.
 *  - Missing media: a deleted or revoked photo silently falls back to the
 *    design's gradient/solid, which is the "don't crash, degrade" rule.
 */
class ClockRenderer private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val assets = appContext.resources.assets
    private val density = appContext.resources.displayMetrics.density
    private val imageCache = ImageCache(6)
    private val bitmapCache = BitmapCache(10)

    // ---- public API ----------------------------------------------------

    fun render(design: ClockDesign, widthDp: Int, heightDp: Int): Bitmap {
        val w = (widthDp * density).toInt().coerceIn(MIN_PX, MAX_PX)
        val h = (heightDp * density).toInt().coerceIn(MIN_PX, MAX_PX)
        return renderPx(design, w, h)
    }

    /**
     * Cache key: [ClockDesign] is a data class, so its hashCode covers every field
     * that changes pixels. Only a few designs are ever live at once and the cached
     * bitmaps are checked for `isRecycled`, so a hash collision at worst costs a
     * repaint - no cheaper key is worth the risk of showing the wrong clock.
     */
    private fun cacheKey(design: ClockDesign, widthPx: Int, heightPx: Int): String =
        (design.hashCode().toString(36) + "/" + design.mediaUri.hashCode().toString(36)) +
            "@" + widthPx + "x" + heightPx

    fun renderPx(design: ClockDesign, widthPx: Int, heightPx: Int): Bitmap {
        val key = cacheKey(design, widthPx, heightPx)
        bitmapCache.get(key)?.let { cached ->
            if (!cached.isRecycled) return cached
        }
        val w = widthPx.coerceIn(MIN_PX, MAX_PX)
        val h = heightPx.coerceIn(MIN_PX, MAX_PX)
        val bucket = bucketFor(design, (w / density).toInt(), (h / density).toInt())
        val bitmap = try {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        } catch (e: Throwable) {
            Bitmap.createBitmap(min(w, 320), min(h, 120), Bitmap.Config.ARGB_8888)
        }
        val canvas = Canvas(bitmap)
        drawDesign(canvas, w, h, design, bucket, false)
        bitmapCache.put(key, bitmap)
        return bitmap
    }

    /**
     * Renders a widget-sized bitmap. The cap keeps the RemoteViews payload small
     * (the bitmap is written to a launcher-side temp file) without visible
     * softness at typical home-screen cell sizes.
     */
    fun renderForWidget(design: ClockDesign, options: android.os.Bundle?): Bitmap {
        val wDp = optionDp(options, android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,
            android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)
        val hDp = optionDp(options, android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,
            android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
        val widthDp = if (wDp > 0) wDp else FALLBACK_WIDTH_DP
        val heightDp = if (hDp > 0) hDp else FALLBACK_HEIGHT_DP
        val scale = min(WIDGET_MAX_PX.toFloat() / (widthDp * density), 1f).coerceAtLeast(0.45f)
        val w = (widthDp * density * scale).toInt().coerceIn(MIN_PX, WIDGET_MAX_PX)
        val h = (heightDp * density * scale).toInt().coerceIn(MIN_PX, WIDGET_MAX_PX)
        return renderPx(design, w, h)
    }

    /**
     * Draw directly into an existing canvas. Used by the full-screen clock and by
     * the editor's live preview, so those paths share the widget's code exactly.
     */
    fun drawDesign(
        canvas: Canvas,
        widthPx: Int,
        heightPx: Int,
        design: ClockDesign,
        bucket: WidgetBucket,
        fullscreen: Boolean,
        mediaOverride: Bitmap? = null,
        skipBackground: Boolean = false,
    ) {
        val w = max(1, widthPx)
        val h = max(1, heightPx)
        if (!skipBackground) drawBackground(canvas, design, w, h, mediaOverride)
        drawClock(canvas, design, bucket, w, h)
        drawBorder(canvas, design, w, h)
    }

    /** Just the border ring: lets a surface-backed video keep its own pixels. */
    fun drawBorder(canvas: Canvas, design: ClockDesign, w: Int, h: Int) {
        if (design.borderWidth <= 0.05f) return
        val radius = dp(design.cornerRadius)
        canvas.save()
        if (radius > 0.5f) {
            val path = Path()
            path.addRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), radius, radius, Path.Direction.CW)
            canvas.clipPath(path)
        }
        val stroke = dp(design.borderWidth)
        val border = Paint(Paint.ANTI_ALIAS_FLAG)
        border.style = Paint.Style.STROKE
        border.strokeWidth = stroke
        border.color = design.borderColor
        val inset = stroke / 2f
        val rect = RectF(inset, inset, w - inset, h - inset)
        if (radius > 0.5f) canvas.drawRoundRect(rect, radius, radius, border) else canvas.drawRect(rect, border)
        canvas.restore()
    }

    private fun drawBackground(canvas: Canvas, design: ClockDesign, w: Int, h: Int, mediaOverride: Bitmap?) {
        canvas.save()
        val radius = dp(design.cornerRadius)
        if (radius > 0.5f) {
            val path = Path()
            path.addRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), radius, radius, Path.Direction.CW)
            canvas.clipPath(path)
        }
        val bg = Paint(Paint.FILTER_BITMAP_FLAG)
        bg.style = Paint.Style.FILL
        when (design.backgroundKind) {
            BackgroundKind.TRANSPARENT -> Unit
            BackgroundKind.SOLID -> {
                bg.color = design.backgroundColor
                canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bg)
            }
            BackgroundKind.GRADIENT -> {
                bg.shader = gradient(design, w, h)
                canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bg)
                bg.shader = null
            }
            else -> {
                val image = mediaOverride ?: loadImage(design)
                if (image != null && !image.isRecycled) {
                    var toDraw = image
                    val blurPx = dp(design.backgroundBlur)
                    if (blurPx > 0.6f) {
                        val blurred = BitmapUtils.blur(image, blurPx)
                        toDraw = blurred
                    }
                    if (toDraw.width != w || toDraw.height != h) {
                        // Cover-crop the photo to the exact widget aspect ratio.
                        val scale = max(w.toFloat() / toDraw.width.toFloat(), h.toFloat() / toDraw.height.toFloat())
                        val dw = (toDraw.width * scale)
                        val dh = (toDraw.height * scale)
                        bg.shader = null
                        canvas.drawBitmap(
                            toDraw,
                            null,
                            RectF((w - dw) / 2f, (h - dh) / 2f, (w - dw) / 2f + dw, (h - dh) / 2f + dh),
                            null
                        )
                    } else {
                        canvas.drawBitmap(toDraw, 0f, 0f, null)
                    }
                    if (toDraw !== image && mediaOverride == null && !toDraw.isRecycled &&
                        toDraw.width == w && toDraw.height == h
                    ) {
                        toDraw.recycle()
                    }
                } else {
                    bg.shader = gradient(design, w, h)
                    canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bg)
                    bg.shader = null
                }
                val darkness = design.backgroundDarkness
                if (darkness > 0.001f) {
                    bg.color = BitmapUtils.withAlpha(0xFF000000.toInt(), (255 * darkness).toInt())
                    canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bg)
                }
            }
        }
        canvas.restore()
    }

    // ---- sizing --------------------------------------------------------

    companion object {
        const val MIN_PX = 48
        const val WIDGET_MAX_PX = 900
        const val MAX_PX = 1600
        const val FALLBACK_WIDTH_DP = 180
        const val FALLBACK_HEIGHT_DP = 80

        private const val SMALL_DP = 120
        private const val MEDIUM_DP = 180
        private const val LARGE_DP = 250
        private const val HUGE_DP = 340

        @Volatile
        private var instance: ClockRenderer? = null

        /** Clamps a surface size to the renderer's memory budget. */
        fun budgetSize(widthPx: Int, heightPx: Int): Pair<Int, Int> {
            val scale = BitmapUtils.budget(widthPx, heightPx, MAX_PX * MAX_PX / 2)
            val w = max(MIN_PX, (widthPx * scale).toInt())
            val h = max(MIN_PX, (heightPx * scale).toInt())
            return w to h
        }

        fun of(context: Context): ClockRenderer = instance ?: synchronized(this) {
            instance ?: ClockRenderer(context).also { instance = it }
        }

        /**
         * Classifies the geometry the launcher gave us. Sizes are the widget
         * option cells in dp (the same numbers `onAppWidgetOptionsChanged`
         * reports), so this is the app's only interpretation of "how big is it".
         */
        fun bucketFor(design: ClockDesign, widthDp: Int, heightDp: Int): WidgetBucket {
            val w = if (widthDp > 0) widthDp else FALLBACK_WIDTH_DP
            val h = if (heightDp > 0) heightDp else FALLBACK_HEIGHT_DP
            val bucket = when {
                w < SMALL_DP || h < 48 -> WidgetBucket.SMALL
                w >= HUGE_DP && h >= HUGE_DP -> WidgetBucket.HUGE
                w >= LARGE_DP && h >= LARGE_DP -> WidgetBucket.LARGE
                w >= MEDIUM_DP && h >= MEDIUM_DP -> WidgetBucket.MEDIUM
                h >= LARGE_DP && w < LARGE_DP -> WidgetBucket.TALL
                w >= MEDIUM_DP && h < MEDIUM_DP -> WidgetBucket.WIDE
                else -> WidgetBucket.MEDIUM
            }
            return if (design.content == ContentMode.TIME && bucket.showsDate) bucket else bucket
        }
    }

    private fun optionDp(options: android.os.Bundle?, minKey: String, maxKey: String): Int {
        if (options == null) return 0
        val min = options.getInt(minKey, 0)
        val max = options.getInt(maxKey, 0)
        // Use the *minimum* cell: that is the size the launcher guarantees when the
        // user has not stretched the widget yet, and it is what the layout must fit.
        return if (min > 0) min else max
    }

    private fun dp(value: Float): Float = value * density

    private fun gradient(design: ClockDesign, w: Int, h: Int): Shader {
        val cx = w / 2f
        val cy = h / 2f
        return when (design.gradientKind) {
            GradientKind.RADIAL -> RadialGradient(
                cx, cy, max(hypot(w.toFloat(), h.toFloat()) / 2f, 1f),
                design.gradientStart, design.gradientEnd, Shader.TileMode.CLAMP
            )
            GradientKind.SWEEP -> SweepGradient(cx, cy, design.gradientStart, design.gradientEnd)
            GradientKind.LINEAR -> {
                val angle = Math.toRadians(design.gradientAngle.toDouble())
                val dx = cos(angle).toFloat()
                val dy = -sin(angle).toFloat()
                val sx = cx - dx * w / 2f
                val sy = cy - dy * h / 2f
                val ex = cx + dx * w / 2f
                val ey = cy + dy * h / 2f
                LinearGradient(sx, sy, ex, ey, design.gradientStart, design.gradientEnd, Shader.TileMode.CLAMP)
            }
        }
    }

    private fun loadImage(design: ClockDesign): Bitmap? {
        val uri = design.mediaUri ?: return null
        val key = uri + ":" + (if (design.mediaIsVideo) "v" else "i")
        imageCache.get(key)?.let { if (!it.isRecycled) return it }
        val maxEdge = (WIDGET_MAX_PX.toFloat() * 1.2f).toInt()
        val bitmap = try {
            MediaAccess.posterFor(appContext, uri, design.mediaIsVideo, maxEdge)
        } catch (e: Throwable) {
            null
        } ?: return null
        imageCache.put(key, bitmap)
        return bitmap
    }

    // ---- clock layer ---------------------------------------------------

    /** Draw only the clock layer (no background, no border) into an existing canvas. */
    fun drawClock(canvas: Canvas, design: ClockDesign, bucket: WidgetBucket, w: Int, h: Int) {
        val pad = dp(design.padding)
        val left = pad
        val top = pad
        val right = (w - pad).coerceAtLeast(left + 1f)
        val bottom = (h - pad).coerceAtLeast(top + 1f)
        val boxW = right - left
        val boxH = bottom - top

        val alpha255 = (design.clockAlpha.coerceIn(0.05f, 1f) * 255f).toInt()
        if (alpha255 < 255) {
            if (Build.VERSION.SDK_INT >= 29) {
                canvas.saveLayerAlpha(left, top, right, bottom, alpha255)
            } else {
                val layerPaint = Paint()
                layerPaint.alpha = alpha255
                canvas.saveLayer(RectF(left, top, right, bottom), layerPaint)
            }
        }

        if (design.style == ClockStyle.ANALOG) {
            drawAnalog(canvas, design, bucket, left, top, boxW, boxH)
        } else {
            drawTextClock(canvas, design, bucket, left, top, boxW, boxH)
        }
        if (alpha255 < 255) canvas.restore()
    }

    private fun mainPaint(design: ClockDesign, sizePx: Float): TextPaint {
        val typeface = FontRegistry.resolve(assets, design.fontId, design.weight)
        return TextPainter.makePaint(typeface, sizePx, design.timeColor, design.letterSpacingEm)
    }

    private fun subPaint(design: ClockDesign, sizePx: Float): TextPaint {
        val typeface = FontRegistry.resolve(assets, design.dateFontId ?: design.fontId, max(400, design.weight - 200))
        return TextPainter.makePaint(typeface, sizePx, design.dateColor, design.letterSpacingEm * 0.5f)
    }

    private fun effects(design: ClockDesign): FloatArray = floatArrayOf(
        dp(design.glowBlurPx),
        dp(design.shadowBlurPx),
        dp(design.shadowDxPx),
        dp(design.shadowDyPx),
        dp(design.outlineWidth),
    )

    private fun drawTextBlock(
        canvas: Canvas,
        design: ClockDesign,
        block: TextPainter.Block,
        left: Float,
        top: Float,
    ) {
        val fx = effects(design)
        TextPainter.draw(
            canvas = canvas,
            block = block,
            left = left,
            top = top,
            outline = design.outline,
            outlineColor = design.outlineColor,
            outlineWidthPx = fx[4],
            glow = design.glow,
            glowColor = design.glowColor,
            glowRadiusPx = fx[0],
            shadow = design.shadow,
            shadowColor = design.shadowColor,
            shadowRadiusPx = fx[1],
            shadowDxPx = fx[2],
            shadowDyPx = fx[3],
        )
    }

    private fun drawTextClock(
        canvas: Canvas,
        design: ClockDesign,
        bucket: WidgetBucket,
        left: Float,
        top: Float,
        boxW: Float,
        boxH: Float,
    ) {
        val parts = clockLines(design, bucket)
        val verticalStack = design.orientation == OrientationMode.VERTICAL || design.style == ClockStyle.VERTICAL
        val split = design.style == ClockStyle.SPLIT

        if (split) {
            drawSplit(canvas, design, bucket, left, top, boxW, boxH, parts)
            return
        }

        val lineCount = when {
            parts.custom.isNotEmpty() -> 1 + parts.mainLines.size + if (parts.subLines.isEmpty()) 0 else 1
            verticalStack -> parts.mainLines.size + (if (parts.subLines.isEmpty()) 0 else 1)
            else -> 1 + (if (parts.subLines.isEmpty()) 0 else 1)
        }

        val baseSize = when (design.style) {
            ClockStyle.LARGE_NUMBER -> min(boxH / (lineCount * 1.12f), boxW * 0.46f)
            ClockStyle.MINIMAL -> min(boxH / (lineCount * 1.16f), boxW * 0.40f)
            ClockStyle.CUSTOM_TEXT -> min(boxH / (lineCount * 1.14f), boxW * 0.38f)
            ClockStyle.VERTICAL -> min(boxH / (10f), boxW * 0.30f)
            else -> min(boxH / (lineCount * 1.28f), boxW * 0.36f)
        } * design.textScale.coerceIn(0.6f, 1.8f)

        val timePaint = mainPaint(design, baseSize)
        val timeLines: List<String> = when {
            design.style == ClockStyle.VERTICAL -> parts.mainLines.flatMap { it.map { c -> c.toString() } }
            else -> parts.mainLines
        }
        val timeBlock = TextPainter.fitting(
            lines = timeLines,
            baseSizePx = baseSize,
            maxWidthPx = boxW,
            paint = timePaint,
            lineHeight = if (design.style == ClockStyle.VERTICAL) 1.02f else design.lineHeight,
            textAlign = if (design.style == ClockStyle.VERTICAL) design.textAlign else design.textAlign,
            letterSpacingEm = design.letterSpacingEm,
            minSizePx = 9f,
        )

        val subSize = baseSize * (if (design.style == ClockStyle.VERTICAL) 0.34f else 0.36f)
        val subLines: List<String> = ArrayList<String>().apply {
            if (parts.custom.isNotEmpty()) addAll(parts.custom)
            addAll(parts.subLines)
        }
        val subPaint = subPaint(design, subSize)
        val subBlock = if (subLines.isEmpty()) null else TextPainter.fitting(
            lines = subLines,
            baseSizePx = subSize,
            maxWidthPx = boxW,
            paint = subPaint,
            lineHeight = 1.15f,
            textAlign = design.textAlign,
            letterSpacingEm = design.letterSpacingEm * 0.5f,
            minSizePx = 8f,
        )

        val total = timeBlock.heightPx() + (if (subBlock == null) 0f else subBlock.heightPx() + baseSize * 0.10f)
        val blockY = when (design.gravity) {
            ClockGravity.TOP_LEFT, ClockGravity.TOP_CENTER, ClockGravity.TOP_RIGHT -> top
            ClockGravity.BOTTOM_LEFT, ClockGravity.BOTTOM_CENTER, ClockGravity.BOTTOM_RIGHT -> top + max(0f, boxH - total)
            else -> top + max(0f, (boxH - total) / 2f)
        }
        // `measured` is what the text actually needs; the layout box is what we
        // align within, so right-gravity anchors to `left + boxW`, not to a
        // separate right inset (drawTextClock is handed the content box only).
        val measured = max(timeBlock.measuredMaxWidth(), subBlock?.measuredMaxWidth() ?: 0f)
        val blockWidth = max(measured, boxW)
        val blockLeft = left + max(0f, boxW - measured) / 2f
        val effectiveLeft = when (design.gravity) {
            ClockGravity.TOP_LEFT, ClockGravity.CENTER_LEFT, ClockGravity.BOTTOM_LEFT -> left
            ClockGravity.TOP_RIGHT, ClockGravity.CENTER_RIGHT, ClockGravity.BOTTOM_RIGHT -> left + boxW - blockWidth
            else -> blockLeft
        }
        var y = blockY
        timeBlock.boxWidthPx = blockWidth
        drawTextBlock(canvas, design, timeBlock, effectiveLeft, y)
        y += timeBlock.heightPx() + (if (subBlock == null) 0f else baseSize * 0.10f)
        if (subBlock != null) {
            subBlock.boxWidthPx = blockWidth
            drawTextBlock(canvas, design, subBlock, effectiveLeft, y)
        }
    }

    private fun drawSplit(
        canvas: Canvas,
        design: ClockDesign,
        bucket: WidgetBucket,
        left: Float,
        top: Float,
        boxW: Float,
        boxH: Float,
        parts: ClockLines,
    ) {
        val time = parts.mainLines.firstOrNull() ?: "--:--"
        val hourPart = time.substringBefore(":")
        val rest = if (time.contains(":")) time.substring(time.indexOf(":")) else ":00"
        val gap = boxW * 0.06f
        val half = (boxW - gap) / 2f
        val size = min(boxH * 0.62f, max(half, 1f) * 1.5f) * design.textScale
        val paint = mainPaint(design, size)
        val hourBlock = TextPainter.fitting(listOf(hourPart), size, half, paint, 1.0f, ClockDesign.TEXT_ALIGN_RIGHT, design.letterSpacingEm, 12f)
        val minuteBlock = TextPainter.fitting(listOf(rest), size, half, paint, 1.0f, ClockDesign.TEXT_ALIGN_LEFT, design.letterSpacingEm, 12f)
        val cy = top + max(0f, (boxH - max(hourBlock.heightPx(), minuteBlock.heightPx())) / 2f)
        drawTextBlock(canvas, design, hourBlock, left, cy)
        drawTextBlock(canvas, design, minuteBlock, left + half + gap, cy)
        if (parts.subLines.isNotEmpty()) {
            val subSize = size * 0.22f
            val sub = TextPainter.fitting(parts.subLines, subSize, boxW, subPaint(design, subSize), 1.15f, design.textAlign, 0f, 8f)
            drawTextBlock(canvas, design, sub, left, min(cy + max(hourBlock.heightPx(), minuteBlock.heightPx()) + subSize, top + max(0f, boxH - sub.heightPx())))
        }
    }

    // ---- analog --------------------------------------------------------

    private fun drawAnalog(
        canvas: Canvas,
        design: ClockDesign,
        bucket: WidgetBucket,
        left: Float,
        top: Float,
        boxW: Float,
        boxH: Float,
    ) {
        val size = min(boxW, boxH * (if (bucket.showsDate) 0.82f else 1f))
        val cx = horizontalCenter(design, left, boxW, size)
        val cy = cyFor(design, top, boxH, size + if (bucket.showsDate) size * 0.22f else 0f)
        val radius = size / 2f * 0.86f
        val fx = effects(design)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.style = Paint.Style.STROKE
        if (design.glow) {
            paint.color = design.glowColor
            paint.strokeWidth = max(2f, size * 0.012f)
            paint.maskFilter = android.graphics.BlurMaskFilter(max(2f, fx[0]), android.graphics.BlurMaskFilter.Blur.NORMAL)
            canvas.drawCircle(cx, cy, radius, paint)
            paint.maskFilter = null
        }
        paint.color = design.timeColor
        paint.strokeWidth = max(1.5f, size * 0.012f)
        canvas.drawCircle(cx, cy, radius, paint)

        // Hour ticks, with a heavier mark every 3 o'clock.
        val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        tickPaint.color = design.timeColor
        tickPaint.strokeWidth = max(1f, size * 0.008f)
        tickPaint.strokeCap = Paint.Cap.ROUND
        for (i in 0 until 60) {
            val major = i % 5 == 0
            val angle = Math.toRadians(i * 6.0 - 90.0)
            val inner = radius - (if (major) size * 0.075f else size * 0.03f)
            val x1 = cx + (cos(angle) * inner).toFloat()
            val y1 = cy + (sin(angle) * inner).toFloat()
            val x2 = cx + (cos(angle) * radius * 0.94f).toFloat()
            val y2 = cy + (sin(angle) * radius * 0.94f).toFloat()
            tickPaint.alpha = if (major) 255 else 120
            canvas.drawLine(x1, y1, x2, y2, tickPaint)
        }

        if (design.analogNumerals) {
            val numeralPaint = mainPaint(design, size * 0.13f)
            numeralPaint.color = design.timeColor
            val metrics = numeralPaint.fontMetrics
            val baselineFix = -(metrics.ascent + metrics.descent) / 2f
            for (i in 0 until 12) {
                val value = if (i == 0) "12" else i.toString()
                val angle = Math.toRadians((i * 30).toDouble() - 90.0)
                val nx = cx + (cos(angle) * radius * 0.74f).toFloat()
                val ny = cy + (sin(angle) * radius * 0.74f).toFloat()
                canvas.drawText(value, nx, ny + baselineFix, numeralPaint)
            }
        }

        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR)
        val minute = calendar.get(Calendar.MINUTE)
        val second = calendar.get(Calendar.SECOND)
        val hourAngle = Math.toRadians((hour + minute / 60f) * 30.0 - 90.0)
        val minuteAngle = Math.toRadians((minute + second / 60f) * 6.0 - 90.0)

        val hourPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        hourPaint.color = design.timeColor
        hourPaint.strokeWidth = max(3f, size * 0.035f)
        hourPaint.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(cx, cy, (cx + cos(hourAngle) * radius * 0.48f).toFloat(), (cy + sin(hourAngle) * radius * 0.48f).toFloat(), hourPaint)

        val minutePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        minutePaint.color = design.timeColor
        minutePaint.strokeWidth = max(2f, size * 0.02f)
        minutePaint.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(cx, cy, (cx + cos(minuteAngle) * radius * 0.72f).toFloat(), (cy + sin(minuteAngle) * radius * 0.72f).toFloat(), minutePaint)

        if (design.showSeconds) {
            val secondPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            secondPaint.color = design.dateColor
            secondPaint.strokeWidth = max(1f, size * 0.008f)
            secondPaint.pathEffect = DashPathEffect(floatArrayOf(size * 0.03f, size * 0.02f), 0f)
            val secondAngle = Math.toRadians(second * 6.0 - 90.0)
            canvas.drawLine(cx, cy, (cx + cos(secondAngle) * radius * 0.8f).toFloat(), (cy + sin(secondAngle) * radius * 0.8f).toFloat(), secondPaint)
        }

        val hub = Paint(Paint.ANTI_ALIAS_FLAG)
        hub.color = design.dateColor
        hub.style = Paint.Style.FILL
        canvas.drawCircle(cx, cy, max(2.5f, size * 0.018f), hub)

        if (bucket.showsDate) {
            val parts = clockLines(design, bucket)
            val subSize = size * 0.115f
            val block = TextPainter.fitting(parts.subLines, subSize, boxW, subPaint(design, subSize), 1.12f, design.textAlign, 0f, 8f)
            drawTextBlock(canvas, design, block, left, cy + radius + size * 0.06f)
        }
    }

    private fun horizontalCenter(design: ClockDesign, left: Float, boxW: Float, size: Float): Float =
        when (design.gravity) {
            ClockGravity.TOP_LEFT, ClockGravity.CENTER_LEFT, ClockGravity.BOTTOM_LEFT -> left + size / 2f
            ClockGravity.TOP_RIGHT, ClockGravity.CENTER_RIGHT, ClockGravity.BOTTOM_RIGHT -> left + boxW - size / 2f
            else -> left + boxW / 2f
        }

    private fun cyFor(design: ClockDesign, top: Float, boxH: Float, total: Float): Float =
        when (design.gravity) {
            ClockGravity.TOP_LEFT, ClockGravity.TOP_CENTER, ClockGravity.TOP_RIGHT -> top + total / 2f
            ClockGravity.BOTTOM_LEFT, ClockGravity.BOTTOM_CENTER, ClockGravity.BOTTOM_RIGHT -> top + boxH - total / 2f
            else -> top + boxH / 2f
        }

    // ---- text content --------------------------------------------------

    private class ClockLines(val main: String, val sub: List<String>, val day: List<String>, val custom: List<String>) {
        val mainLines: List<String> get() = listOf(main)
        val subLines: List<String>
            get() {
                val out = ArrayList<String>()
                out.addAll(day)
                out.addAll(sub)
                return out
            }
    }

    private fun clockLines(design: ClockDesign, bucket: WidgetBucket): ClockLines {
        val calendar = Calendar.getInstance()
        val settings = AppPrefs.of(appContext).load()
        val hour24 = design.use24Hour ?: settings.use24Hour ?: android.text.format.DateFormat.is24HourFormat(appContext)
        val hourRaw = calendar.get(Calendar.HOUR_OF_DAY)
        val minute = calendar.get(Calendar.MINUTE)
        val second = calendar.get(Calendar.SECOND)
        val main: String
        val suffix: String?
        if (hour24) {
            main = if (design.suppressLeadingZeroHour && hourRaw >= 10) "$hourRaw" else String.format(Locale.US, "%02d", hourRaw)
            suffix = null
        } else {
            var h = hourRaw % 12
            if (h == 0) h = 12
            main = if (design.suppressLeadingZeroHour) h.toString() else String.format(Locale.US, "%02d", h)
            suffix = if (hourRaw < 12) "AM" else "PM"
        }
        val time = if (design.showSeconds) main + ":" + String.format(Locale.US, "%02d", second) else main
        val withSuffix = if (suffix == null) time else time + " " + suffix
        val day = SimpleDateFormat("EEEE", Locale.getDefault()).format(Date(calendar.timeInMillis))
        val date = try {
            SimpleDateFormat(settings.dateFormat, Locale.getDefault()).format(Date(calendar.timeInMillis))
        } catch (e: Throwable) {
            SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(calendar.timeInMillis))
        }
        val sub = ArrayList<String>()
        val dayLines = ArrayList<String>()
        when (design.content) {
            ContentMode.TIME -> if (!bucket.isCompact) {
                // Time only by design; nothing extra, even on large cells.
            }
            ContentMode.TIME_DATE -> if (!bucket.isCompact) sub.addAll(date.split("\n"))
            ContentMode.TIME_DAY -> if (!bucket.isCompact) dayLines.add(day)
            ContentMode.TIME_DATE_DAY -> if (!bucket.isCompact) {
                dayLines.add(day)
                sub.addAll(date.split("\n"))
            }
        }
        val custom = if (design.style == ClockStyle.CUSTOM_TEXT && design.customText.isNotEmpty()) {
            design.customText.split("\n")
        } else {
            emptyList()
        }
        return ClockLines(withSuffix, sub, dayLines, custom)
    }

    fun clearCaches() {
        imageCache.clear()
        bitmapCache.clear()
    }

    // ---- caches --------------------------------------------------------

    /**
     * Weak LRU caches. Entries are only kept while somebody else (a widget
     * ImageViewDrawable, an editor preview) still references the bitmap, which
     * is exactly the lifetime we want for a render result.
     */
    private class ImageCache(private val limit: Int) {
        private val lock = Any()
        private val map = LinkedHashMap<String, java.lang.ref.WeakReference<Bitmap>>(16, 0.75f, true)

        fun get(key: String): Bitmap? = synchronized(lock) {
            val ref = map[key] ?: return null
            val value = ref.get()
            if (value == null || value.isRecycled) {
                map.remove(key)
                null
            } else {
                value
            }
        }

        fun put(key: String, value: Bitmap) = synchronized(lock) {
            map[key] = java.lang.ref.WeakReference(value)
            while (map.size > limit) {
                val it = map.entries.iterator()
                if (it.hasNext()) {
                    it.next()
                    it.remove()
                }
            }
        }

        fun clear() = synchronized(lock) { map.clear() }
    }

    private class BitmapCache(private val limit: Int) {
        private val lock = Any()
        private val map = LinkedHashMap<String, Bitmap>(16, 0.75f, true)

        fun get(key: String): Bitmap? = synchronized(lock) { map[key] }

        fun put(key: String, value: Bitmap) = synchronized(lock) {
            val previous = map.put(key, value)
            if (previous != null && previous !== value && !previous.isRecycled) previous.recycle()
            while (map.size > limit) {
                val it = map.entries.iterator()
                if (it.hasNext()) {
                    val eldest = it.next()
                    val bitmap = eldest.value
                    it.remove()
                    if (!bitmap.isRecycled) bitmap.recycle()
                }
            }
        }

        fun clear() = synchronized(lock) {
            for (bitmap in map.values) if (!bitmap.isRecycled) bitmap.recycle()
            map.clear()
        }
    }
}
