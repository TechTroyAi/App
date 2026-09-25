package ai.techtroy.clockcanvas.media

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.util.Collections

/**
 * Bridges "the user just picked a photo or video" to the renderer and back.
 *
 * Two different lifetimes live here, and conflating them is where clock apps
 * usually leak:
 *
 *  - *Session* media: the URI picked in the editor or the full-screen screen.
 *    It is only valid while the app owns it; we do not try to persist it, and we
 *    do not write it into the saved design unless the user presses Apply.
 *  - *Saved* media: a `content://` URI stored in a design, for which we took a
 *    persistable read grant. If the grant is later revoked (photo deleted, moved
 *    to SD, app restored on a new device), [resolve] returns null and the
 *    renderer falls back to the design's colors - see ClockRenderer's "missing
 *    media" rule.
 *
 * [recent] keeps a small history so the background picker can offer "Recent
 * backgrounds" without scanning storage or holding any permission.
 */
class MediaHandler private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("clockcanvas_media", Context.MODE_PRIVATE)
    private val session = Collections.synchronizedMap(HashMap<String, String>())
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Records a picked URI for the current editing session (not persisted). */
    fun putSession(scope: String, uri: String?) {
        if (uri == null) session.remove(scope) else session[scope] = uri
    }

    fun session(scope: String): String? = session[scope]

    fun clearSession(scope: String) {
        session.remove(scope)
    }

    /** Persist a design's background URI (grant + recents). Safe to call repeatedly. */
    fun commitDesignMedia(designId: String, uri: String?, isVideo: Boolean) {
        if (uri.isNullOrEmpty()) return
        MediaAccess.persist(appContext, Uri.parse(uri))
        addRecent(uri, isVideo)
    }

    fun addRecent(uri: String, isVideo: Boolean) {
        val rows = ArrayList(recentRows())
        rows.removeAll { it.uri == uri }
        rows.add(0, Recent(uri, isVideo, System.currentTimeMillis()))
        while (rows.size > RECENT_LIMIT) rows.removeAt(rows.size - 1)
        prefs.edit().putString(KEY_RECENT, encodeRecents(rows)).apply()
    }

    fun forgetRecent(uri: String) {
        val rows = ArrayList(recentRows())
        rows.removeAll { it.uri == uri }
        prefs.edit().putString(KEY_RECENT, encodeRecents(rows)).apply()
    }

    fun recentRows(): List<Recent> = decodeRecents(prefs.getString(KEY_RECENT, ""))

    /** Verifies the grant still works; called on launch so dead URIs are dropped early. */
    fun restorePending() {
        val rows = recentRows().filter { MediaAccess.canRead(appContext, it.uri) }
        if (rows.size != recentRows().size) {
            prefs.edit().putString(KEY_RECENT, encodeRecents(rows)).apply()
        }
    }

    fun resolve(uriString: String?): Bitmap? {
        if (uriString.isNullOrEmpty()) return null
        return try {
            MediaAccess.decodeScaled(appContext, Uri.parse(uriString), IMAGE_MAX_EDGE, null)
        } catch (e: Throwable) {
            null
        }
    }

    fun resolveUri(uriString: String?): Uri? {
        if (uriString.isNullOrEmpty()) return null
        if (!MediaAccess.canRead(appContext, uriString)) return null
        return try {
            Uri.parse(uriString)
        } catch (e: Throwable) {
            null
        }
    }

    fun postToMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
    }

    data class Recent(val uri: String, val isVideo: Boolean, val at: Long)

    private fun encodeRecents(rows: List<Recent>): String {
        val sb = StringBuilder()
        for (row in rows) {
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(if (row.isVideo) "v" else "i").append('|').append(row.uri)
        }
        return sb.toString()
    }

    private fun decodeRecents(text: String?): List<Recent> {
        if (text.isNullOrEmpty()) return emptyList()
        val out = ArrayList<Recent>()
        for (line in text.split('\n')) {
            val index = line.indexOf('|')
            if (index <= 0) continue
            out.add(Recent(line.substring(index + 1), line[0] == 'v', 0L))
        }
        return out
    }

    companion object {
        private const val KEY_RECENT = "recent"
        private const val RECENT_LIMIT = 12
        private const val IMAGE_MAX_EDGE = 1600

        @Volatile
        private var instance: MediaHandler? = null

        fun of(context: Context): MediaHandler = instance ?: synchronized(this) {
            instance ?: MediaHandler(context).also { instance = it }
        }
    }
}
