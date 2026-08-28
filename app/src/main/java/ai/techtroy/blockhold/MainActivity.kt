package ai.techtroy.blockhold

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.PrintWriter
import java.io.StringWriter

class MainActivity : Activity() {

    private companion object {
        const val CRASH_PREFS = "blockhold_crash_report"
        const val KEY_TRACE = "trace"
        const val KEY_WHEN = "when"
        const val GAME_PREFS = "blockhold_infinite_progress"
    }

    /**
     * Null whenever the game failed to start and the recovery screen is showing instead, so every
     * lifecycle callback has to null-check rather than assume the view exists.
     */
    private var gameView: GameView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashRecorder()
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // A crash recorded by the previous session: surface it instead of silently swallowing it.
        // Without this the render thread can die and the user only ever sees "keeps stopping".
        val pending = crashPrefs().getString(KEY_TRACE, null)
        if (!pending.isNullOrBlank()) {
            showRecoveryScreen(
                title = "Blockhold Defense stopped unexpectedly",
                detail = pending,
                previousSession = true
            )
            return
        }

        startGame()
    }

    private fun startGame() {
        try {
            val view = GameView(this)
            gameView = view
            hideSystemUi()
            setContentView(view)
        } catch (t: Throwable) {
            // Anything thrown while building the game (missing resource, bad saved data from an
            // older version, out of memory) would otherwise kill the process before the first
            // frame, which looks to the player like the app refusing to open at all.
            gameView = null
            recordCrash(t)
            showRecoveryScreen(
                title = "Blockhold Defense could not start",
                detail = describe(t),
                previousSession = false
            )
        }
    }

    // ---------------------------------------------------------------- crash capture

    private fun crashPrefs() = getSharedPreferences(CRASH_PREFS, Context.MODE_PRIVATE)

    private fun installCrashRecorder() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                recordCrash(throwable, thread)
            } catch (_: Throwable) {
                // Never let the recorder itself mask the original failure.
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun recordCrash(throwable: Throwable, thread: Thread? = null) {
        crashPrefs().edit()
            .putString(KEY_TRACE, describe(throwable, thread))
            .putLong(KEY_WHEN, System.currentTimeMillis())
            .commit()
    }

    private fun describe(throwable: Throwable, thread: Thread? = null): String {
        val writer = StringWriter()
        PrintWriter(writer).use { throwable.printStackTrace(it) }
        val stamp = DateFormat.format("yyyy-MM-dd HH:mm:ss", System.currentTimeMillis())
        return buildString {
            append("Blockhold Defense ")
            append(versionLabel())
            append('\n')
            append("Android ").append(android.os.Build.VERSION.RELEASE)
            append(" (API ").append(android.os.Build.VERSION.SDK_INT).append(")\n")
            append(android.os.Build.MANUFACTURER).append(' ').append(android.os.Build.MODEL).append('\n')
            append(stamp).append('\n')
            if (thread != null) append("thread: ").append(thread.name).append('\n')
            append('\n')
            append(writer.toString())
        }
    }

    private fun versionLabel(): String = try {
        val info = packageManager.getPackageInfo(packageName, 0)
        "${info.versionName} (${info.versionCode})"
    } catch (_: Throwable) {
        "unknown build"
    }

    // ---------------------------------------------------------------- recovery screen

    private fun showRecoveryScreen(title: String, detail: String, previousSession: Boolean) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(10, 17, 13))
            setPadding(pad(20), pad(18), pad(20), pad(18))
        }

        root.addView(label(title, 19f, Color.rgb(190, 244, 78), bold = true))
        root.addView(
            label(
                if (previousSession) {
                    "The last session ended with the error below. You can continue playing, or " +
                        "reset saved data if it keeps happening. Long-press the text to copy it " +
                        "and send it to the developer."
                } else {
                    "The game failed to start. Resetting saved data clears progress from older " +
                        "versions, which is the usual cause after an update. Long-press the text " +
                        "to copy it and send it to the developer."
                },
                12f,
                Color.rgb(166, 180, 169)
            ).apply { setPadding(0, pad(8), 0, pad(10)) }
        )

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
            setBackgroundColor(Color.rgb(19, 29, 23))
            setPadding(pad(12), pad(12), pad(12), pad(12))
        }
        scroll.addView(
            label(detail, 11f, Color.rgb(224, 232, 226)).apply {
                setTextIsSelectable(true)
                typeface = android.graphics.Typeface.MONOSPACE
            }
        )
        root.addView(scroll)

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, pad(12), 0, 0)
        }
        buttons.addView(
            button(if (previousSession) "Continue" else "Try again") {
                crashPrefs().edit().remove(KEY_TRACE).remove(KEY_WHEN).commit()
                restart()
            }
        )
        buttons.addView(
            button("Reset saved data") {
                getSharedPreferences(GAME_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
                crashPrefs().edit().clear().commit()
                restart()
            }
        )
        root.addView(buttons)

        setContentView(root)
    }

    private fun restart() {
        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        finish()
        startActivity(intent)
    }

    private fun label(text: String, sizeSp: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            this.text = text
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .apply { rightMargin = pad(8) }
        gravity = Gravity.CENTER
        setOnClickListener { onClick() }
    }

    private fun pad(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------------- lifecycle

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && gameView != null) {
            hideSystemUi()
        }
    }

    override fun onResume() {
        super.onResume()
        val view = gameView ?: return
        hideSystemUi()
        view.resumeFromActivity()
    }

    override fun onPause() {
        gameView?.pauseFromActivity()
        super.onPause()
    }

    override fun onDestroy() {
        gameView?.release()
        super.onDestroy()
    }

    override fun onBackPressed() {
        val view = gameView
        if (view == null || !view.handleBackPressed()) {
            super.onBackPressed()
        }
    }

    private fun hideSystemUi() {
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }
}
