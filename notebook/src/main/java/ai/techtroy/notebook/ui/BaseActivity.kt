package ai.techtroy.notebook.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import ai.techtroy.notebook.App
import ai.techtroy.notebook.core.Theme
import ai.techtroy.notebook.core.ThemeManager
import ai.techtroy.notebook.sys.Lock

abstract class BaseActivity : AppCompatActivity(), LockHost {
    val app: App get() = application as App
    private var appliedTheme: Theme? = null

    private var lockCallback: ((Boolean) -> Unit)? = null
    private val lockLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
        val cb = lockCallback; lockCallback = null
        cb?.invoke(r.resultCode == android.app.Activity.RESULT_OK)
    }
    override fun launchLock(intent: Intent, cb: (Boolean) -> Unit) { lockCallback = cb; lockLauncher.launch(intent) }

    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { Lock.onScreenOff(app.prefs) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        appliedTheme = app.prefs.theme
        ThemeManager.apply(this, appliedTheme!!)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        androidx.core.content.ContextCompat.registerReceiver(this, screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun attachBaseContext(newBase: Context) {
        val prefs = (newBase.applicationContext as? App)?.prefs
        if (prefs != null) {
            val cfg = Configuration(newBase.resources.configuration)
            cfg.fontScale = cfg.fontScale.coerceIn(0.85f, 1.3f)
            super.attachBaseContext(newBase.createConfigurationContext(cfg))
        } else super.attachBaseContext(newBase)
    }

    override fun onResume() {
        super.onResume()
        if (appliedTheme != app.prefs.theme) recreate()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenOff) }
        super.onDestroy()
    }

    /** Add keyboard/system-bar bottom inset to a view's padding (for bottom bars in edge-to-edge-less mode). */
    protected fun padForIme(view: View) {
        val base = view.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            v.updatePadding(bottom = base + maxOf(ime - nav, 0))
            insets
        }
    }

    protected fun blockScreenshots(block: Boolean) {
        if (block) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    val isLightTheme get() = ThemeManager.isLight(app.prefs.theme)
}
