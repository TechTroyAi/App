package ai.techtroy.clockcanvas.widget

import android.os.Handler
import android.os.HandlerThread

/**
 * Single background thread for widget rasterisation. Widget rendering must not
 * run on the main thread (a 900px blur plus text layout is tens of
 * milliseconds), and one shared thread means two resizes in quick succession
 * cannot interleave bitmap writes.
 */
object WidgetThreads {

    @Volatile
    private var thread: HandlerThread? = null

    @Volatile
    var handler: Handler? = null
        private set

    @Synchronized
    fun ensure(): Handler? {
        handler?.let { if (thread?.isAlive == true) return it }
        return try {
            val created = HandlerThread("clockcanvas-widget", android.os.Process.THREAD_PRIORITY_BACKGROUND)
            created.start()
            thread = created
            Handler(created.looper).also { handler = it }
        } catch (e: Throwable) {
            null
        }
    }

    @Synchronized
    fun quit() {
        try {
            thread?.quitSafely()
        } catch (e: Throwable) {
        }
        thread = null
        handler = null
    }
}
