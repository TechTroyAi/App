package ai.techtroy.clockcanvas.media

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream

/**
 * Everything the app needs from the user's photos and videos, without a single
 * storage permission.
 *
 *  - Selection uses the system Photo Picker: `ACTION_PICK_IMAGES` on Android 13+
 *    and `ACTION_GET_CONTENT` (which the Photo Picker also answers on 12 and
 *    below) otherwise. Both are launched with `EXTRA_ALLOW_MULTIPLE=false`, so
 *    the app receives exactly one URI it may read persistently.
 *  - Camera captures are written into the app's own external files dir and
 *    handed to the camera app through our [ai.techtroy.clockcanvas.io.LocalFileProvider].
 *  - Video *playback* is only ever offered in the full-screen clock and in the
 *    live wallpaper; a normal home-screen widget gets a poster frame, because
 *    RemoteViews cannot render a video surface.
 */
object MediaAccess {

    const val ACTION_PICK_IMAGES = "android.intent.action.PICK_IMAGES"

    /** Constant names are spelled out so this also compiles against an API 33 stub. */
    const val EXTRA_PICK_IMAGES_MAX = "android.intent.extra.PICK_IMAGES_MAX"
    const val REQ_IMAGE = 4101
    const val REQ_VIDEO = 4102
    const val REQ_CAMERA = 4103

    fun pickImages(): Intent {
        return if (Build.VERSION.SDK_INT >= 33) {
            Intent(ACTION_PICK_IMAGES).apply {
                putExtra(EXTRA_PICK_IMAGES_MAX, 1)
            }
        } else {
            Intent(Intent.ACTION_GET_CONTENT)
                .setType("image/*")
                .addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
        }
    }

    fun pickVideos(): Intent {
        return if (Build.VERSION.SDK_INT >= 33) {
            Intent(ACTION_PICK_IMAGES).apply {
                putExtra(EXTRA_PICK_IMAGES_MAX, 1)
                type = "video/*"
            }
        } else {
            Intent(Intent.ACTION_GET_CONTENT)
                .setType("video/*")
                .addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
        }
    }

    /** Result of one successful pick. */
    data class Picked(val uri: Uri, val mimeType: String?, val displayName: String?) {
        val isVideo: Boolean
            get() = (mimeType ?: "").startsWith("video") || looksLikeVideo(displayName)
    }

    fun pickedIntentData(data: Intent?): Picked? {
        val uri = data?.data ?: data?.clipData?.let { if (it.itemCount > 0) it.getItemAt(0).uri else null }
            ?: return null
        return Picked(uri, null, null)
    }

    /** Resolves mime/name lazily and grabs a persistable read grant. Safe to call twice. */
    fun persist(context: Context, uri: Uri): Boolean {
        if (!"content".equals(uri.scheme, ignoreCase = true)) return true
        return try {
            val cr = context.contentResolver
            val already = cr.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
            if (already) return true
            cr.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            true
        } catch (e: Throwable) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                true
            } catch (e2: Throwable) {
                false
            }
        }
    }

    fun describe(context: Context, uri: Uri): Picked {
        var mime: String? = null
        var name: String? = null
        try {
            val c: Cursor? = context.contentResolver.query(
                uri,
                arrayOf(
                    android.provider.BaseColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.MIME_TYPE
                ),
                null, null, null
            )
            c?.use {
                if (it.moveToFirst()) {
                    val nameIdx = it.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    val mimeIdx = it.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                    if (nameIdx >= 0) name = it.getString(nameIdx)
                    if (mimeIdx >= 0) mime = it.getString(mimeIdx)
                }
            }
        } catch (e: Throwable) {
            // Some pickers do not answer queries for their transient URIs.
        }
        return Picked(uri, mime, name)
    }

    fun canRead(context: Context, uriString: String?): Boolean {
        if (uriString.isNullOrEmpty()) return false
        return try {
            val uri = Uri.parse(uriString)
            val p = context.contentResolver.openFileDescriptor(uri, "r")
            p?.close()
            p != null
        } catch (e: Throwable) {
            false
        }
    }

    /** Poster frame for a video, or a downsampled still for an image. */
    fun posterFor(context: Context, uriString: String?, isVideo: Boolean, maxEdgePx: Int): Bitmap? {
        val uri = try {
            Uri.parse(uriString)
        } catch (e: Throwable) {
            return null
        }
        if (isVideo) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                val bmp = retriever.frameAtTime
                if (bmp != null) return scaleDown(bmp, maxEdgePx)
            } catch (e: Throwable) {
                // Codec or file provider refused; caller falls back to the design's colors.
            } finally {
                try {
                    retriever.release()
                } catch (ignored: Throwable) {
                }
            }
            return null
        }
        return decodeScaled(context, uri, maxEdgePx, null)
    }

    fun decodeScaled(context: Context, uri: Uri, maxEdgePx: Int, outSampled: IntArray?): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri).use { input ->
                if (input != null) BitmapFactory.decodeStream(input, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            val longEdge = if (bounds.outWidth > bounds.outHeight) bounds.outWidth else bounds.outHeight
            while (longEdge / sample > maxEdgePx && sample < 32) sample *= 2
            outSampled?.let { if (it.isNotEmpty()) it[0] = sample }
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            context.contentResolver.openInputStream(uri).use { input ->
                if (input == null) null else BitmapFactory.decodeStream(input, null, opts)
            }
        } catch (e: OutOfMemoryError) {
            // A 40-megapixel photo on a low-RAM device: fall back to no image.
            null
        } catch (e: Throwable) {
            null
        }
    }

    fun scaleDown(bitmap: Bitmap, maxEdgePx: Int): Bitmap? {
        val longEdge = if (bitmap.width > bitmap.height) bitmap.width else bitmap.height
        if (longEdge <= maxEdgePx || longEdge <= 0) return bitmap
        val ratio = maxEdgePx.toFloat() / longEdge.toFloat()
        val w = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val h = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return try {
            Bitmap.createScaledBitmap(bitmap, w, h, true)
        } catch (e: Throwable) {
            null
        }
    }

    /** Writes the camera target file and returns a shareable content:// URI for it. */
    fun prepareCameraTarget(activity: Activity, fileName: String): Uri? {
        return try {
            val dir = File(activity.externalCacheDir ?: activity.cacheDir, "captures")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, fileName)
            FileOutputStream(file).use { it.write(ByteArray(0)) }
            val authority = activity.packageName + ".fileprovider"
            ai.techtroy.clockcanvas.io.LocalFileProvider.uriFor(activity, authority, file)
        } catch (e: Throwable) {
            null
        }
    }

    fun cameraIntent(target: Uri): Intent = Intent("android.media.action.IMAGE_CAPTURE")
        .putExtra("output", target)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)

    private fun looksLikeVideo(name: String?): Boolean {
        if (name == null) return false
        val lower = name.lowercase()
        return lower.endsWith(".mp4") || lower.endsWith(".mov") || lower.endsWith(".mkv") ||
            lower.endsWith(".webm") || lower.endsWith(".3gp") || lower.endsWith(".avi")
    }
}
