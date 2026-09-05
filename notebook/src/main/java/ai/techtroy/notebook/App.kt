package ai.techtroy.notebook

import android.app.Application
import android.os.Handler
import android.os.Looper
import ai.techtroy.notebook.core.FileStore
import ai.techtroy.notebook.core.Prefs
import ai.techtroy.notebook.core.ThemeManager
import ai.techtroy.notebook.data.Repo
import ai.techtroy.notebook.echo.EchoEngine
import ai.techtroy.notebook.sys.Notifications
import ai.techtroy.notebook.sys.ReminderScheduler
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class App : Application() {

    lateinit var repo: Repo; private set
    lateinit var prefs: Prefs; private set
    lateinit var files: FileStore; private set
    lateinit var echoes: EchoEngine; private set

    val io: ExecutorService = Executors.newFixedThreadPool(3)
    val main = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        files = FileStore(this)
        repo = Repo(this)
        echoes = EchoEngine(this, repo, prefs)
        ThemeManager.applyNightMode(prefs.theme)
        Notifications.ensureChannels(this)
        io.execute {
            repo.purgeOldTrash(TimeUnit.DAYS.toMillis(30)) { files.delete(it) }
            ReminderScheduler.rescheduleAll(this)
            echoes.refreshToday()
        }
    }

    /** Run on io thread, deliver result on main. */
    fun <T> async(work: () -> T, then: (T) -> Unit) {
        io.execute {
            val r = try { work() } catch (t: Throwable) { android.util.Log.e("Notebook", "async failed", t); return@execute }
            main.post { then(r) }
        }
    }

    companion object {
        lateinit var instance: App; private set
        fun get() = instance
    }
}
