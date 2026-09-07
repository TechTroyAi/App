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
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import java.io.File
import java.io.InputStream

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
        settings.allowFileAccessFromFileURLs = false
        @Suppress("DEPRECATION")
        settings.allowUniversalAccessFromFileURLs = false
        view.setBackgroundColor(Color.parseColor("#0B0F0D"))
        view.webViewClient = JadexAssetClient(this)
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

        // Served through JadexAssetClient rather than file:// so the page is a
        // real secure origin: Web Workers, SharedArrayBuffer and
        // instantiateStreaming all require that, and file:// grants none of them.
        view.loadUrl("https://jadex.local/ide/index.html")
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


    /**
     * Serves app/src/main/assets over a synthetic https origin.
     *
     * Two things this buys us that file:// cannot:
     *  1. Workers + SharedArrayBuffer, gated behind cross-origin isolation, which
     *     needs the COOP/COEP header pair below on the *document*.
     *  2. WebAssembly.instantiateStreaming, which needs a real
     *     application/wasm Content-Type.
     *
     * If a .br sibling exists it is served with Content-Encoding: br so the
     * 10MB interpreter ships compressed inside the APK.
     */
    class JadexAssetClient(private val host: MainActivity) : WebViewClient() {

        private fun mime(path: String): String = when {
            path.endsWith(".html") -> "text/html"
            path.endsWith(".js") -> "text/javascript"
            path.endsWith(".css") -> "text/css"
            path.endsWith(".wasm") -> "application/wasm"
            path.endsWith(".json") -> "application/json"
            path.endsWith(".zip") -> "application/zip"
            path.endsWith(".png") -> "image/png"
            path.endsWith(".svg") -> "image/svg+xml"
            else -> "application/octet-stream"
        }

        private fun open(path: String): InputStream? =
            try { host.assets.open(path) } catch (t: Throwable) { null }

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest
        ): WebResourceResponse? {
            val url = request.url
            if (url.host != "jadex.local") return null
            val path = (url.path ?: "/").trimStart('/')
            if (path.isEmpty() || path.contains("..")) return null

            val headers = LinkedHashMap<String, String>()
            // Cross-origin isolation: required for SharedArrayBuffer, which is how
            // the UI thread interrupts a runaway loop inside the Python worker.
            headers["Cross-Origin-Opener-Policy"] = "same-origin"
            headers["Cross-Origin-Embedder-Policy"] = "require-corp"
            headers["Cross-Origin-Resource-Policy"] = "same-origin"
            headers["Cache-Control"] = "no-cache"

            val brotli = open("$path.br")
            if (brotli != null) {
                headers["Content-Encoding"] = "br"
                return WebResourceResponse(mime(path), null, 200, "OK", headers, brotli)
            }
            val stream = open(path) ?: return null
            return WebResourceResponse(mime(path), "utf-8", 200, "OK", headers, stream)
        }
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
