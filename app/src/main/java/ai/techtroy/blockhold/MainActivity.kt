package ai.techtroy.blockhold

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import java.io.File

class MainActivity : Activity() {

    private var webView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
        )
        window.navigationBarColor = Color.parseColor("#0B0F0D")
        window.statusBarColor = Color.parseColor("#0B0F0D")
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(true)
        }

        val host = FrameLayout(this)
        host.setBackgroundColor(Color.parseColor("#0B0F0D"))
        val view = WebView(this)
        webView = view
        val settings = view.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        settings.setSupportZoom(false)
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        @Suppress("DEPRECATION")
        settings.allowFileAccessFromFileURLs = true
        @Suppress("DEPRECATION")
        settings.allowUniversalAccessFromFileURLs = true
        view.setBackgroundColor(Color.parseColor("#0B0F0D"))
        view.webViewClient = WebViewClient()
        view.webChromeClient = WebChromeClient()
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        view.addJavascriptInterface(JadexBridge(this), "JadexNative")
        host.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        setContentView(host)

        view.setOnApplyWindowInsetsListener { v, insets ->
            val bottom = if (Build.VERSION.SDK_INT >= 30) {
                val ime = insets.getInsets(WindowInsets.Type.ime()).bottom
                val sys = insets.getInsets(WindowInsets.Type.systemBars()).bottom
                Math.max(ime, sys)
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetBottom
            }
            val cap = (resources.displayMetrics.heightPixels * 0.45f).toInt()
            v.setPadding(0, 0, 0, Math.min(bottom, cap))
            insets
        }

        view.loadUrl("file:///android_asset/ide/index.html")
    }

    fun padDir(): File {
        val dir = File(filesDir, "jadex")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun tickHaptic() {
        try {
            val vib = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= 26) {
                vib.vibrate(VibrationEffect.createOneShot(18, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(18)
            }
        } catch (t: Throwable) {
        }
    }

    fun keepOn(on: Boolean) {
        runOnUiThread {
            if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun onBackPressed() {
        val view = webView
        if (view != null && view.canGoBack()) view.goBack()
        else super.onBackPressed()
    }

    override fun onPause() {
        webView?.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    class JadexBridge(private val host: MainActivity) {
        @JavascriptInterface
        fun haptic() {
            host.tickHaptic()
        }

        @JavascriptInterface
        fun keepScreen(on: Boolean) {
            host.keepOn(on)
        }

        @JavascriptInterface
        fun saveFile(name: String, body: String) {
            val safe = name.replace("..", "").replace("/", "_")
            File(host.padDir(), safe).writeText(body, Charsets.UTF_8)
        }

        @JavascriptInterface
        fun readFile(name: String): String {
            val safe = name.replace("..", "").replace("/", "_")
            val f = File(host.padDir(), safe)
            return if (f.exists()) f.readText(Charsets.UTF_8) else ""
        }

        @JavascriptInterface
        fun listFiles(): String {
            val names = host.padDir().listFiles() ?: return "[]"
            return names.filter { it.isFile }.joinToString(",", "[", "]") { "\"${it.name}\"" }
        }
    }
}
