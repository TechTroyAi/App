package ai.techtroy.clockcanvas.media

import ai.techtroy.clockcanvas.ClockDesign
import android.content.Context
import android.graphics.Bitmap

/**
 * The ordered stack of photos and clips a design displays, and the maths that picks
 * which one is showing right now.
 *
 * A design always has one *primary* media URI (`mediaUri`) - that is the background
 * the widget paints behind the clock. A **reel** is the longer list used by the media
 * wall (`mediaOnly`), where the picture is the point and the clock steps aside. The
 * two stay coherent in one direction only: the reel is consulted first, and the
 * primary media is appended as a fallback when the reel is empty (or when the user
 * picks their first item, which fills both). So a design cannot be in two minds about
 * what to show, and a paused single-item reel is simply the primary image.
 *
 * The current index is *derived from the clock*, not stored:
 * `stepAt` maps wall-clock time onto the list. That is deliberate - a persisted
 * counter would need writing from four different processes (widget update, editor,
 * full-screen, wallpaper engine), and they would drift apart, so your home screen and
 * your wallpaper would show different photos. Deriving it means every surface shows
 * the same item at the same instant, and a reboot needs no resynchronising.
 *
 * Reading a reel entry is allowed to fail: the user may have deleted the photo, moved
 * it, or restored the app onto a new device. Unreadable entries are dropped when the
 * reel is built, and if everything is gone the caller falls back to the design's
 * colours (see `ClockRenderer`'s media-wall hint).
 */
class MediaReel private constructor(val items: List<Item>) {

    data class Item(val uri: String, val isVideo: Boolean)

    val size: Int get() = items.size

    fun isEmpty(): Boolean = items.isEmpty()

    fun isNotEmpty(): Boolean = items.isNotEmpty()

    fun itemAt(index: Int): Item? = if (index in items.indices) items[index] else null

    /** Index after [delta] steps, wrapping. `delta` may be negative. */
    fun stepAfter(index: Int, delta: Int): Int {
        if (items.isEmpty()) return 0
        val n = items.size
        return (((index + delta) % n) + n) % n
    }

    /**
     * The reel position for `nowMs`, given a step length in seconds. `rotateSecs <= 0`
     * or a single-item reel pins position 0, which is how "no rotation" stays
     * expressible without a second code path.
     */
    fun stepAt(rotateSecs: Int, nowMs: Long = System.currentTimeMillis()): Int {
        if (items.size < 2 || rotateSecs <= 0) return 0
        val stepMs = rotateSecs * 1000L
        return ((nowMs / stepMs) % items.size).toInt()
    }

    /** Milliseconds until the next step, used to sleep precisely rather than poll. */
    fun msUntilNextStep(rotateSecs: Int, nowMs: Long = System.currentTimeMillis()): Long {
        if (items.size < 2 || rotateSecs <= 0) return Long.MAX_VALUE
        val stepMs = rotateSecs * 1000L
        return stepMs - (nowMs % stepMs)
    }

    companion object {

        /** Decoded still for one reel entry, capped so a 108 MP photo cannot end a process. */
        fun bitmapFor(context: Context, item: Item, maxEdgePx: Int): Bitmap? = try {
            if (item.isVideo) {
                MediaAccess.posterFor(context, item.uri, true, maxEdgePx)
            } else {
                MediaAccess.decodeScaled(context, android.net.Uri.parse(item.uri), maxEdgePx, null)
            }
        } catch (e: OutOfMemoryError) {
            null
        } catch (e: Throwable) {
            null
        }

        /**
         * The reel for a design: its explicit list when set, otherwise the single
         * primary URI so that media-only mode works on a design the user never added a
         * stack to. Entries the app can no longer read are dropped here, once, rather
         * than failing at draw time.
         */
        fun forDesign(context: Context, design: ClockDesign): MediaReel {
            val raw = ArrayList<Item>()
            val seen = HashSet<String>()
            design.mediaReel.forEach { uri ->
                if (uri.isNotBlank() && seen.add(uri)) {
                    raw.add(Item(uri, looksLikeVideo(uri)))
                }
            }
            val primary = design.mediaUri
            if (primary != null && seen.add(primary)) {
                raw.add(Item(primary, design.mediaIsVideo))
            }
            val readable = raw.filter { MediaAccess.canRead(context, it.uri) }
            val kept = if (readable.isNotEmpty()) readable else raw.take(0)
            return MediaReel(kept)
        }

        /** Reel URIs are stored one per line; that is the on-disk format. */
        fun join(items: List<String>): String = items.filter { it.isNotBlank() }.joinToString("\n")

        fun split(text: String?): List<String> =
            (text ?: "").split('\n').map { it.trim() }.filter { it.isNotEmpty() }

        fun looksLikeVideo(uri: String?): Boolean {
            val lower = (uri ?: "").lowercase()
            return lower.endsWith(".mp4") || lower.endsWith(".mov") || lower.endsWith(".mkv") ||
                lower.endsWith(".webm") || lower.endsWith(".3gp") || lower.contains("/video") ||
                lower.contains("videopicker") || lower.contains("videos/")
        }
    }
}
