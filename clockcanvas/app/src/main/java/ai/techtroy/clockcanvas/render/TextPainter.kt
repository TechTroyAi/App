package ai.techtroy.clockcanvas.render

import ai.techtroy.clockcanvas.ClockDesign
import android.graphics.Canvas
import android.graphics.Paint
import android.text.TextPaint
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Draws a block of left/center/right-aligned lines with full control over
 * letter spacing, line height, outline, glow and shadow.
 *
 * This is hand-rolled on purpose instead of using `StaticLayout`: the widget is
 * a rasterised bitmap (fonts, outline and glow cannot be expressed with
 * RemoteViews), and a manual layout pass is what lets a single stroke paint and
 * a single fill paint share identical glyph positions.
 */
object TextPainter {

    /** Letter spacing is expressed in em and applied through Paint when available. */
    private val setLetterSpacing: java.lang.reflect.Method? by lazy {
        try {
            Paint::class.java.getMethod("setLetterSpacing", Float::class.javaPrimitiveType)
        } catch (e: Throwable) {
            null
        }
    }

    class Block(
        val lines: List<String>,
        val paint: TextPaint,
        val lineHeightPx: Float,
        val spacingPx: Float,
        val textAlign: Int,
        var boxWidthPx: Float,
        val extraTopPx: Float,
        val extraBottomPx: Float,
        val perLineShrink: Boolean,
    ) {
        fun heightPx(): Float = extraTopPx + lines.size * lineHeightPx + extraBottomPx

        fun measuredMaxWidth(): Float {
            var w = 0f
            for (line in lines) {
                val value = lineWidth(line)
                if (value > w) w = value
            }
            return w
        }

        fun lineWidth(line: String): Float {
            if (line.isEmpty()) return 0f
            val raw = paint.measureText(line)
            return if (spacingPx > 0f) max(0f, raw - spacingPx) else raw
        }
    }

    fun makePaint(
        typeface: android.graphics.Typeface,
        sizePx: Float,
        color: Int,
        letterSpacingEm: Float,
    ): TextPaint {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
        paint.typeface = typeface
        paint.textSize = sizePx
        paint.color = color
        paint.isFakeBoldText = false
        if (letterSpacingEm != 0f) {
            val spacingPx = letterSpacingEm * sizePx
            try {
                setLetterSpacing?.invoke(paint, letterSpacingEm)
            } catch (e: Throwable) {
                // No public API on this platform: spacing is emulated for centered
                // lines in drawBlock and skipped elsewhere, which is a cosmetic
                // degradation, never a crash.
                paint.textSize = sizePx
            }
        }
        return paint
    }

    fun block(
        lines: List<String>,
        paint: TextPaint,
        sizePx: Float,
        lineHeight: Float,
        textAlign: Int,
        boxWidthPx: Float,
        letterSpacingEm: Float,
        extraTopPx: Float = 0f,
        extraBottomPx: Float = 0f,
        perLineShrink: Boolean = false,
    ): Block {
        val spacingPx = letterSpacingEm * sizePx
        val lineHeightPx = max(sizePx * 1.02f, sizePx * lineHeight)
        return Block(
            lines = lines,
            paint = paint,
            lineHeightPx = lineHeightPx,
            spacingPx = spacingPx,
            textAlign = textAlign,
            boxWidthPx = boxWidthPx,
            extraTopPx = extraTopPx,
            extraBottomPx = extraBottomPx,
            perLineShrink = perLineShrink,
        )
    }

    /**
     * Fits [text] into [maxWidthPx] by shrinking the paint until it fits, then
     * returns a block. The largest number clock and small widget cells rely on
     * this so a very long custom date never spills over the border.
     */
    fun fitting(
        lines: List<String>,
        baseSizePx: Float,
        maxWidthPx: Float,
        paint: TextPaint,
        lineHeight: Float,
        textAlign: Int,
        letterSpacingEm: Float,
        minSizePx: Float = 10f,
    ): Block {
        var size = baseSizePx
        val step = baseSizePx / 24f
        var guard = 0
        while (guard++ < 24) {
            paint.textSize = size
            val candidate = block(lines, paint, size, lineHeight, textAlign, maxWidthPx, letterSpacingEm)
            if (candidate.measuredMaxWidth() <= maxWidthPx || size - step < minSizePx) {
                candidate.copySize(size)
                return candidate
            }
            size = max(minSizePx, size - step)
        }
        paint.textSize = size
        return block(lines, paint, size, lineHeight, textAlign, maxWidthPx, letterSpacingEm)
    }

    /** Paints [block] with the given effects. Baseline origin is the block top-left. */
    fun draw(
        canvas: Canvas,
        block: Block,
        left: Float,
        top: Float,
        outline: Boolean,
        outlineColor: Int,
        outlineWidthPx: Float,
        glow: Boolean,
        glowColor: Int,
        glowRadiusPx: Float,
        shadow: Boolean,
        shadowColor: Int,
        shadowRadiusPx: Float,
        shadowDxPx: Float,
        shadowDyPx: Float,
    ) {
        val paint = block.paint
        val originalColor = paint.color
        val originalStyle = paint.style
        val originalWidth = paint.strokeWidth
        val originalFilter = paint.maskFilter
        val originalAlpha = paint.alpha

        var y = top + block.extraTopPx + ascent(paint)
        for (line in block.lines) {
            val x = left + xFor(block, line)
            if (line.isEmpty()) {
                y += block.lineHeightPx
                continue
            }
            if (glow) {
                paint.color = glowColor
                paint.style = Paint.Style.FILL
                paint.maskFilter = android.graphics.BlurMaskFilter(glowRadiusPx.coerceAtLeast(1f), android.graphics.BlurMaskFilter.Blur.NORMAL)
                canvas.drawText(line, x, y, paint)
                canvas.drawText(line, x, y, paint)
            }
            if (shadow) {
                paint.color = shadowColor
                paint.style = Paint.Style.FILL
                paint.maskFilter = android.graphics.BlurMaskFilter(shadowRadiusPx.coerceAtLeast(0.5f), android.graphics.BlurMaskFilter.Blur.NORMAL)
                canvas.drawText(line, x + shadowDxPx, y + shadowDyPx, paint)
            }
            paint.maskFilter = null
            if (outline && outlineWidthPx > 0f) {
                paint.color = outlineColor
                paint.style = Paint.Style.FILL_AND_STROKE
                paint.strokeWidth = outlineWidthPx * 2f
                paint.strokeJoin = Paint.Join.ROUND
                paint.strokeCap = Paint.Cap.ROUND
                canvas.drawText(line, x, y, paint)
            }
            paint.color = originalColor
            paint.style = Paint.Style.FILL
            paint.strokeWidth = originalWidth
            canvas.drawText(line, x, y, paint)
            y += block.lineHeightPx
        }

        paint.color = originalColor
        paint.style = originalStyle
        paint.strokeWidth = originalWidth
        paint.maskFilter = originalFilter
        paint.alpha = originalAlpha
    }

    private fun xFor(block: Block, line: String): Float {
        val width = block.lineWidth(line)
        return when (block.textAlign) {
            ClockDesign.TEXT_ALIGN_LEFT -> 0f
            ClockDesign.TEXT_ALIGN_RIGHT -> block.boxWidthPx - width
            else -> (block.boxWidthPx - width) / 2f
        }
    }

    private fun ascent(paint: Paint): Float {
        val metrics = paint.fontMetrics
        // Distance from the block top to the first baseline.
        return -metrics.ascent + max(0f, (metrics.bottom - metrics.top) * 0.06f)
    }

    fun singleLineWidth(paint: Paint, text: String): Float = paint.measureText(text)

    /** Height needed by [count] lines at [sizePx] with [lineHeight]. */
    fun stackHeight(sizePx: Float, lineHeight: Float, count: Int): Float =
        ceil(max(sizePx * 1.02f, sizePx * lineHeight) * count)

    /** Convenience: shrink [sizePx] until [text] fits [maxWidth] x [maxHeight]. */
    fun fitSize(paint: Paint, text: String, sizePx: Float, maxWidth: Float, maxHeight: Float, lineHeight: Float): Float {
        var size = max(6f, sizePx)
        var guard = 0
        while (guard++ < 30) {
            paint.textSize = size
            val width = paint.measureText(text)
            val height = stackHeight(size, lineHeight, 1)
            if ((maxWidth <= 0f || width <= maxWidth) && (maxHeight <= 0f || height <= maxHeight)) return size
            size = max(6f, size * 0.92f)
            if (size <= 6.5f) return size
        }
        return size
    }

    fun Block.copySize(sizePx: Float) {
        paint.textSize = sizePx
    }
}
