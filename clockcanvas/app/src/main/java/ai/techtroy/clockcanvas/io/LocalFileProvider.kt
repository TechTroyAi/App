package ai.techtroy.clockcanvas.io

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.BaseColumns
import android.webkit.MimeTypeMap
import java.io.File

/**
 * A ~100 line FileProvider: the same "grant a content:// URI for a file inside
 * our own storage" contract as `androidx.core.content.FileProvider`, without
 * pulling AndroidX into the app. URIs look like
 *
 *     content://ai.techtroy.clockcanvas.fileprovider/captures/IMG_xxx.jpg
 *
 * and only resolve to files below [rootFor] - nothing else in the app's storage
 * is reachable, and the provider is not exported.
 */
class LocalFileProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? {
        val file = resolve(uri) ?: return null
        val ext = file.name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    override fun openFile(uri: Uri, mode: String, signal: CancellationSignal?): ParcelFileDescriptor? {
        val file = resolve(uri) ?: return null
        if (!file.exists() && !mode.contains("w")) return null
        val access = if (mode.contains("w")) {
            ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE
        } else {
            ParcelFileDescriptor.MODE_READ_ONLY
        }
        return ParcelFileDescriptor.open(file, access)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        val file = resolve(uri) ?: return null
        val rows = android.database.MatrixCursor(arrayOf(BaseColumns._ID, "display_name", "mime_type", "_size"))
        if (file.exists()) {
            rows.addRow(
                arrayOf<Any?>(
                    1L,
                    file.name,
                    getType(uri) ?: "application/octet-stream",
                    file.length()
                )
            )
        }
        return rows
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()

    private fun resolve(uri: Uri): File? {
        val context = context ?: return null
        val segments = uri.pathSegments
        if (segments.isEmpty()) return null
        val root = rootFor(context, segments[0]) ?: return null
        var current = root
        for (index in 1 until segments.size) {
            val part = segments[index]
            if (part == ".." || part == "." || part.contains("/")) return null
            current = File(current, part)
            if (!canonicalInside(current, root)) return null
        }
        return current
    }

    private fun canonicalInside(file: File, root: File): Boolean = try {
        file.canonicalPath.startsWith(root.canonicalPath + File.separator) ||
            file.canonicalPath == root.canonicalPath
    } catch (e: Throwable) {
        false
    }

    companion object {
        private const val ROOT_CAPTURES = "captures"
        private const val ROOT_EXPORTS = "exports"

        private fun rootFor(context: Context, name: String): File? = when (name) {
            ROOT_CAPTURES -> File(context.externalCacheDir ?: context.cacheDir, "captures")
            ROOT_EXPORTS -> File(context.cacheDir, "exports")
            else -> null
        }

        /** Build a grantable URI for [file]; null when the file is outside our roots. */
        fun uriFor(context: Context, authority: String, file: File): Uri? {
            val captures = rootFor(context, ROOT_CAPTURES)
            val exports = rootFor(context, ROOT_EXPORTS)
            val canonical = try {
                file.canonicalPath
            } catch (e: Throwable) {
                file.absolutePath
            }
            val pair: Pair<String, File> = when {
                captures != null && canonical.startsWith(captures.absolutePath) -> ROOT_CAPTURES to captures
                exports != null && canonical.startsWith(exports.absolutePath) -> ROOT_EXPORTS to exports
                else -> return null
            }
            val relative = canonical.substring(pair.second.absolutePath.length).trimStart('/')
            val segments = ArrayList<String>()
            segments.add(pair.first)
            for (part in relative.split('/')) if (part.isNotEmpty()) segments.add(part)
            return Uri.Builder().scheme("content").authority(authority).apply {
                for (s in segments) appendPath(s)
            }.build()
        }

        fun authority(context: Context): String = context.packageName + ".fileprovider"
    }
}
