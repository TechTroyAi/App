package ai.techtroy.notebook.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.os.ParcelFileDescriptor
import ai.techtroy.notebook.App
import ai.techtroy.notebook.R
import ai.techtroy.notebook.core.toast
import ai.techtroy.notebook.data.Attachment
import java.io.File

object Media {
    /** Returns (thumbRel, durationMs) for a video file. */
    fun videoMeta(app: App, rel: String): Pair<String?, Long> {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(app.files.file(rel).absolutePath)
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val frame = r.getFrameAtTime(minOf(1_000_000L, dur * 500), MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            val thumb = frame?.let { f ->
                val s = 640f / maxOf(f.width, f.height)
                val b = if (s < 1f) Bitmap.createScaledBitmap(f, (f.width * s).toInt(), (f.height * s).toInt(), true) else f
                app.files.saveThumb(b, png = false)
            }
            thumb to dur
        } catch (_: Throwable) { null to 0L } finally { runCatching { r.release() } }
    }

    fun audioDuration(app: App, rel: String): Long {
        val r = MediaMetadataRetriever()
        return try { r.setDataSource(app.files.file(rel).absolutePath); r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L } catch (_: Throwable) { 0L } finally { runCatching { r.release() } }
    }

    fun pdfThumb(app: App, rel: String): String? = try {
        ParcelFileDescriptor.open(app.files.file(rel), ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { pr ->
                if (pr.pageCount == 0) return null
                pr.openPage(0).use { page ->
                    val w = 480; val h = (480f * page.height / page.width).toInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    app.files.saveThumb(bmp, png = false)
                }
            }
        }
    } catch (_: Throwable) { null }

    fun pdfPageCount(file: File): Int = try { ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { PdfRenderer(it).use { r -> r.pageCount } } } catch (_: Throwable) { 0 }

    /** Compress amplitude samples (0..32767) to a compact base-36 string of 64 buckets, 0..35 each. */
    fun encodeWave(amps: List<Int>): String {
        if (amps.isEmpty()) return ""
        val buckets = 64
        val out = StringBuilder()
        val max = amps.maxOrNull()!!.coerceAtLeast(1).toFloat()
        for (i in 0 until buckets) {
            val a = (i * amps.size / buckets); val b = ((i + 1) * amps.size / buckets).coerceAtLeast(a + 1).coerceAtMost(amps.size)
            var s = 0f; for (j in a until b) s += amps[j]
            val v = (Math.sqrt((s / (b - a) / max).toDouble()) * 35).toInt().coerceIn(0, 35)
            out.append(Character.forDigit(v, 36))
        }
        return out.toString()
    }

    fun decodeWave(s: String?): FloatArray {
        if (s.isNullOrEmpty()) return FloatArray(64) { 0.25f + 0.5f * Math.abs(Math.sin(it * 0.6)).toFloat() }
        return FloatArray(s.length) { (Character.digit(s[it], 36).coerceAtLeast(2)) / 35f }
    }

    fun openExternal(activity: Activity, a: Attachment) {
        val app = activity.application as App
        val uri = app.files.uriFor(a.path)
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, a.mime.ifEmpty { "*/*" }).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { activity.startActivity(Intent.createChooser(i, activity.getString(R.string.open_with))) }.onFailure { activity.toast(activity.getString(R.string.error_generic)) }
    }

    fun share(activity: Activity, a: Attachment) {
        val app = activity.application as App
        val uri = app.files.uriFor(a.path)
        val i = Intent(Intent.ACTION_SEND).setType(a.mime.ifEmpty { "*/*" }).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { activity.startActivity(Intent.createChooser(i, activity.getString(R.string.share))) }
    }
}
