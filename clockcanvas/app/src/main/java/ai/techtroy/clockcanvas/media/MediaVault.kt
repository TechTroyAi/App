package ai.techtroy.clockcanvas.media

import ai.techtroy.clockcanvas.ClockDesign
import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * The app's own copy of a photo or clip the user picked.
 *
 * Why a copy is necessary rather than merely nice: the URIs a picker hands back are
 * *borrowed*. `ACTION_PICK_IMAGES` grants read access for the lifetime of our process and
 * refuses a persistable grant; `ACTION_OPEN_DOCUMENT` can persist, but the grant dies if the
 * user clears the original, moves it, or restores the app on another device. A home-screen
 * widget is exactly the worst case for that: the launcher often updates it long after our
 * process was killed, so "I picked a photo and the widget ignores it" is the symptom.
 *
 * So a pick is imported here once — one bounded `File` copy under
 * `files/clockcanvas/media/`, which is private to the app and needs no permission at all.
 * From then on the design refers to a plain file: it cannot be revoked, deleted by a gallery
 * app, or lost on reboot, and the renderer never has to care which picker produced it.
 *
 * Files are pruned, not kept forever: [prune] drops the oldest copies no saved design
 * references any more, so a design the user deleted cannot leave media on disk.
 */
object MediaVault {

    /** Anything bigger than this stays where it is instead of being duplicated. */
    private const val MAX_IMAGE_BYTES = 32L * 1024 * 1024
    private const val MAX_VIDEO_BYTES = 128L * 1024 * 1024
    private const val KEEP_LIMIT = 24
    private const val BUFFER = 64 * 1024

    fun dir(context: Context): File {
        val out = File(File(context.filesDir, "clockcanvas"), "media")
        if (!out.exists()) out.mkdirs()
        return out
    }

    fun isVaultPath(context: Context, uriString: String?): Boolean {
        if (uriString.isNullOrEmpty()) return false
        return try {
            val uri = Uri.parse(uriString)
            "file".equals(uri.scheme, ignoreCase = true) && uri.path?.startsWith(dir(context).absolutePath) == true
        } catch (e: Throwable) {
            false
        }
    }

    /** Every media URI a design can point at, so pruning never deletes something in use. */
    fun referencedBy(designs: List<ClockDesign>): Set<String> {
        val out = HashSet<String>()
        for (design in designs) {
            design.mediaUri?.let { out.add(it) }
            out.addAll(design.mediaReel)
        }
        return out
    }

    /**
     * Copies (or moves, for a camera capture we already own) one picked URI into the vault.
     *
     * @return a `file://` URI to keep in the design, or null when the source was too big to
     *         duplicate or unreadable — the caller then falls back to the original URI, which
     *         still works for the rest of this session.
     */
    fun import(context: Context, uri: Uri, isVideo: Boolean, displayName: String?): String? {
        return try {
            if ("file".equals(uri.scheme, ignoreCase = true)) return adoptFile(context, uri, isVideo, displayName)
            val size = sizeOf(context, uri)
            val cap = if (isVideo) MAX_VIDEO_BYTES else MAX_IMAGE_BYTES
            if (size > cap) return null
            val target = File(dir(context), name(isVideo, displayName, context, uri))
            var written = 0L
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(target).use { out ->
                    val buffer = ByteArray(BUFFER)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        written += read
                        if (written > cap) break
                    }
                    out.flush()
                }
            }
            if (written <= 0L || written > cap) {
                target.delete()
                return null
            }
            Uri.fromFile(target).toString()
        } catch (e: Throwable) {
            null
        }
    }

    /** Camera captures land in our cache dir, which the system may clear: move them in. */
    private fun adoptFile(context: Context, uri: Uri, isVideo: Boolean, displayName: String?): String? {
        val source = try {
            uri.path?.let { File(it) }
        } catch (e: Throwable) {
            null
        } ?: return null
        if (!source.exists() || source.length() <= 0L) return null
        val vaultDir = dir(context)
        if (source.absolutePath.startsWith(vaultDir.absolutePath)) return uri.toString()
        val target = File(vaultDir, name(isVideo, displayName ?: source.name, context, uri))
        return try {
            source.copyTo(target, overwrite = true)
            source.delete()
            Uri.fromFile(target).toString()
        } catch (e: Throwable) {
            target.delete()
            null
        }
    }

    private fun sizeOf(context: Context, uri: Uri): Long = try {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
    } catch (e: Throwable) {
        -1L
    }

    private fun name(isVideo: Boolean, displayName: String?, context: Context, uri: Uri): String {
        val extension = extensionOf(displayName, context, uri, isVideo)
        val stamp = System.currentTimeMillis().toString(36)
        val unique = UUID.randomUUID().toString().substring(0, 4)
        return (if (isVideo) "clip-" else "photo-") + stamp + "-" + unique + extension
    }

    private fun extensionOf(displayName: String?, context: Context, uri: Uri, isVideo: Boolean): String {
        val fromName = displayName?.substringAfterLast('.', "")?.lowercase()
        if (!fromName.isNullOrEmpty() && fromName.length <= 5 && fromName.all { it.isLetterOrDigit() }) {
            return "." + fromName
        }
        val mime = try {
            context.contentResolver.getType(uri)
        } catch (e: Throwable) {
            null
        }
        return when {
            mime?.contains("png") == true -> ".png"
            mime?.contains("webp") == true -> ".webp"
            mime?.contains("gif") == true -> ".gif"
            mime?.contains("quicktime") == true -> ".mov"
            mime?.contains("matroska") == true -> ".mkv"
            isVideo -> ".mp4"
            else -> ".jpg"
        }
    }

    /**
     * Deletes the oldest copies nothing references any more, down to [KEEP_LIMIT] files.
     * Referenced files are never touched, however old they are.
     */
    fun prune(context: Context, keep: Set<String>, limit: Int = KEEP_LIMIT) {
        try {
            val files = dir(context).listFiles()?.toList() ?: return
            val excess = files.size - limit
            if (excess <= 0) return
            files.asSequence()
                .filter { !keep.contains(Uri.fromFile(it).toString()) }
                .sortedBy { it.lastModified() }
                .take(excess)
                .forEach { it.delete() }
        } catch (e: Throwable) {
            // A failed tidy-up must never take the app down with it.
        }
    }

    /** Bytes the vault is holding, for the Settings screen. */
    fun usedBytes(context: Context): Long = try {
        (dir(context).listFiles()?.toList() ?: emptyList()).sumOf { it.length() }
    } catch (e: Throwable) {
        0L
    }

    /** Copies that no design references, so Settings can offer "remove unused". */
    fun unusedFiles(context: Context, keep: Set<String>): List<File> = try {
        (dir(context).listFiles()?.toList() ?: emptyList()).filter { !keep.contains(Uri.fromFile(it).toString()) }
    } catch (e: Throwable) {
        emptyList()
    }
}
