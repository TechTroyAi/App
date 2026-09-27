package ai.techtroy.clockcanvas.render

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min

/**
 * Small, allocation-conscious bitmap helpers used by the renderer.
 *
 * Blur is a real separable box blur (two passes of a sliding window per
 * channel, three times to approximate a Gaussian) implemented on
 * `getPixels`/`setPixels`. There is no RenderScript in this app: it is
 * deprecated, its behaviour differs across OEMs, and the widget is a one-shot
 * render where a predictable ~10 ms CPU blur beats a driver lottery.
 */
object BitmapUtils {

    /** @param radiusPx blur radius in device pixels; 0 returns [src] unchanged. */
    fun blur(src: Bitmap, radiusPx: Float): Bitmap {
        if (radiusPx < 0.6f) return src
        val w = src.width
        val h = src.height
        if (w == 0 || h == 0) return src
        val radius = min(48, max(1, radiusPx.toInt()))
        // Downscale first: blurring at 1/N size and scaling back up is visually
        // equivalent for large radii and 6-16x cheaper.
        val factor = when {
            radius >= 16 -> 4
            radius >= 8 -> 3
            radius >= 4 -> 2
            else -> 1
        }
        val sw = max(1, w / factor)
        val sh = max(1, h / factor)
        val small = try {
            Bitmap.createScaledBitmap(src, sw, sh, true)
        } catch (e: Throwable) {
            return src
        }
        val pixels = IntArray(sw * sh)
        small.getPixels(pixels, 0, sw, 0, 0, sw, sh)
        var r = (radius / factor).coerceAtLeast(1)
        for (pass in 0 until 3) {
            boxBlur(pixels, sw, sh, r)
            if (pass == 1 && r > 1) r = r - 1
        }
        small.setPixels(pixels, 0, sw, 0, 0, sw, sh)
        val out = try {
            if (sw == w && sh == h) small else Bitmap.createScaledBitmap(small, w, h, true)
        } catch (e: Throwable) {
            small
        }
        if (out !== small && out !== src && !small.isRecycled) small.recycle()
        return out
    }

    private fun boxBlur(argb: IntArray, w: Int, h: Int, radius: Int) {
        if (w == 0 || h == 0) return
        val tmp = IntArray(argb.size)
        blurHorizontal(argb, tmp, w, h, radius)
        blurVertical(tmp, argb, w, h, radius)
    }

    private fun blurHorizontal(src: IntArray, dst: IntArray, w: Int, h: Int, radius: Int) {
        val a = IntArray(w)
        val r = IntArray(w)
        val g = IntArray(w)
        val b = IntArray(w)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val px = src[row + x]
                a[x] = px ushr 24
                r[x] = (px shr 16) and 0xFF
                g[x] = (px shr 8) and 0xFF
                b[x] = px and 0xFF
            }
            for (x in 0 until w) {
                var from = x - radius
                var to = x + radius
                if (from < 0) from = 0
                if (to >= w) to = w - 1
                val count = to - from + 1
                var sa = 0
                var sr = 0
                var sg = 0
                var sb = 0
                for (i in from..to) {
                    sa += a[i]
                    sr += r[i]
                    sg += g[i]
                    sb += b[i]
                }
                dst[row + x] = (sa / count shl 24) or (sr / count shl 16) or (sg / count shl 8) or (sb / count)
            }
        }
    }

    private fun blurVertical(src: IntArray, dst: IntArray, w: Int, h: Int, radius: Int) {
        val col = IntArray(h)
        val col2 = IntArray(h)
        val col3 = IntArray(h)
        val col4 = IntArray(h)
        for (x in 0 until w) {
            for (y in 0 until h) {
                val px = src[y * w + x]
                col[y] = px ushr 24
                col2[y] = (px shr 16) and 0xFF
                col3[y] = (px shr 8) and 0xFF
                col4[y] = px and 0xFF
            }
            for (y in 0 until h) {
                var from = y - radius
                var to = y + radius
                if (from < 0) from = 0
                if (to >= h) to = h - 1
                val count = to - from + 1
                var sa = 0
                var sr = 0
                var sg = 0
                var sb = 0
                for (i in from..to) {
                    sa += col[i]
                    sr += col2[i]
                    sg += col3[i]
                    sb += col4[i]
                }
                dst[y * w + x] = (sa / count shl 24) or (sr / count shl 16) or (sg / count shl 8) or (sb / count)
            }
        }
    }

    /** Uniform alpha multiplication, used for `backgroundDarkness` on the image layer. */
    fun multipliedAlpha(src: Bitmap, factor: Float): Bitmap {
        if (factor >= 0.999f) return src
        val w = src.width
        val h = src.height
        val out = src.copy(Bitmap.Config.ARGB_8888, false) ?: return src
        val pixels = IntArray(w * h)
        out.getPixels(pixels, 0, w, 0, 0, w, h)
        val f = factor.coerceIn(0f, 1f)
        for (i in pixels.indices) {
            val px = pixels[i]
            val a = ((px ushr 24) * f).toInt().coerceIn(0, 255)
            pixels[i] = (a shl 24) or (px and 0x00FFFFFF)
        }
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    /** Total pixel budget so a 1080p render cannot blow up a low-memory device. */
    fun budget(widthPx: Int, heightPx: Int, maxPixels: Int): Float {
        val total = widthPx.toLong() * heightPx.toLong()
        if (total <= maxPixels || total <= 0L) return 1f
        return kotlin.math.sqrt(maxPixels.toDouble() / total.toDouble()).toFloat()
    }

    fun withAlpha(color: Int, alpha: Int): Int {
        val a = alpha.coerceIn(0, 255)
        return (a shl 24) or (color and 0x00FFFFFF)
    }
}
