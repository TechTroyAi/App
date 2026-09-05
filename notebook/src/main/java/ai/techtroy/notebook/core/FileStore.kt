package ai.techtroy.notebook.core

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID

/** All note files live under filesDir/notebook/. Paths stored in DB are relative to [root]. */
class FileStore(private val context: Context) {
    val root: File = File(context.filesDir, "notebook").apply { mkdirs() }
    val attachments: File = File(root, "attachments").apply { mkdirs() }
    val thumbs: File = File(root, "thumbs").apply { mkdirs() }
    val pages: File = File(root, "pages").apply { mkdirs() }
    val temp: File = File(context.cacheDir, "tmp").apply { mkdirs() }

    fun file(rel: String): File = File(root, rel)
    fun exists(rel: String?) = rel != null && file(rel).exists()
    fun delete(rel: String) { runCatching { file(rel).delete() } }
    fun newName(ext: String) = UUID.randomUUID().toString().replace("-", "").take(20) + (if (ext.isEmpty()) "" else ".$ext")

    fun copy(rel: String): String? {
        val src = file(rel); if (!src.exists()) return null
        val ext = src.extension
        val dstRel = rel.substringBeforeLast('/', "attachments") + "/" + newName(ext)
        src.copyTo(file(dstRel), overwrite = true)
        return dstRel
    }

    /** Copies content from a SAF/gallery Uri into attachments/. Returns (relPath, displayName, mime, size). */
    fun importUri(uri: Uri): Imported? {
        val cr = context.contentResolver
        val mime = cr.getType(uri) ?: guessMime(uri.lastPathSegment)
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        var size = -1L
        runCatching {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                    val si = c.getColumnIndex(OpenableColumns.SIZE); if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        }
        val ext = name.substringAfterLast('.', "").ifEmpty { extFor(mime) }
        val rel = "attachments/" + newName(ext)
        val out = file(rel)
        val input: InputStream = cr.openInputStream(uri) ?: return null
        input.use { i -> FileOutputStream(out).use { o -> i.copyTo(o) } }
        return Imported(rel, name, mime, if (size >= 0) size else out.length())
    }

    data class Imported(val rel: String, val name: String, val mime: String, val size: Long)

    fun createTempImage(): Pair<File, Uri> {
        val f = File(temp, "cam_" + newName("jpg"))
        f.createNewFile()
        return f to uriFor(f)
    }

    fun uriFor(f: File): Uri = FileProvider.getUriForFile(context, context.packageName + ".files", f)
    fun uriFor(rel: String): Uri = uriFor(file(rel))

    /** Moves a temp camera file into attachments and returns its rel path. */
    fun adoptTemp(f: File, ext: String = "jpg"): String {
        val rel = "attachments/" + newName(ext)
        if (!f.renameTo(file(rel))) { f.copyTo(file(rel), overwrite = true); f.delete() }
        return rel
    }

    /** Makes a JPEG thumbnail (max edge 640) for an image; returns rel path or null. */
    fun makeImageThumb(rel: String): Pair<String?, IntArray> {
        val src = file(rel)
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(src.path, opts)
        val w = opts.outWidth; val h = opts.outHeight
        if (w <= 0 || h <= 0) return null to intArrayOf(0, 0)
        var sample = 1
        while ((w / sample) > 1280 || (h / sample) > 1280) sample *= 2
        val bmp = BitmapFactory.decodeFile(src.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null to intArrayOf(w, h)
        val rotated = applyExif(src, bmp)
        val scale = 640f / maxOf(rotated.width, rotated.height)
        val thumb = if (scale < 1f) Bitmap.createScaledBitmap(rotated, (rotated.width * scale).toInt().coerceAtLeast(1), (rotated.height * scale).toInt().coerceAtLeast(1), true) else rotated
        val tRel = "thumbs/" + newName("jpg")
        FileOutputStream(file(tRel)).use { thumb.compress(Bitmap.CompressFormat.JPEG, 82, it) }
        val dims = intArrayOf(rotated.width, rotated.height)
        if (thumb !== rotated) thumb.recycle(); if (rotated !== bmp) bmp.recycle()
        return tRel to dims
    }

    private fun applyExif(f: File, bmp: Bitmap): Bitmap {
        val o = runCatching { ExifInterface(f).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val m = Matrix()
        when (o) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            else -> return bmp
        }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    fun saveThumb(bmp: Bitmap, dir: String = "thumbs", png: Boolean = true): String {
        val rel = "$dir/" + newName(if (png) "png" else "jpg")
        FileOutputStream(file(rel)).use { bmp.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 85, it) }
        return rel
    }

    fun loadBitmap(rel: String?, maxEdge: Int = 1024): Bitmap? {
        if (rel == null) return null
        val f = file(rel); if (!f.exists()) return null
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, opts)
        var sample = 1
        while ((opts.outWidth / sample) > maxEdge || (opts.outHeight / sample) > maxEdge) sample *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    fun totalBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    companion object {
        fun guessMime(name: String?): String {
            val ext = name?.substringAfterLast('.', "")?.lowercase() ?: return "application/octet-stream"
            return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
        fun extFor(mime: String): String = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: when {
            mime.startsWith("image/") -> "jpg"; mime.startsWith("video/") -> "mp4"; mime.startsWith("audio/") -> "m4a"; mime == "application/pdf" -> "pdf"; else -> "bin"
        }
        fun humanSize(bytes: Long): String {
            if (bytes < 1024) return "$bytes B"
            val kb = bytes / 1024.0; if (kb < 1024) return String.format(java.util.Locale.US, "%.0f KB", kb)
            val mb = kb / 1024.0; if (mb < 1024) return String.format(java.util.Locale.US, "%.1f MB", mb)
            return String.format(java.util.Locale.US, "%.2f GB", mb / 1024.0)
        }
        fun contentResolverName(cr: ContentResolver, uri: Uri): String? = runCatching {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()
    }
}
