package ai.techtroy.floating

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.camera2.CameraManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.view.Gravity
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.CalendarView
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.VideoView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * All floating mini-apps. Original implementations using only public Android APIs.
 * No code copied from any existing floating app.
 */
enum class MiniApp(val title: String, val icon: String) {
    NOTES("Notes", "N"),
    CALCULATOR("Calculator", "C"),
    BROWSER("Browser", "B"),
    VIDEO("Video", "V"),
    MUSIC("Music", "M"),
    IMAGE("Image", "I"),
    PAINT("Paint", "P"),
    CLOCK("Clock", "T"),
    FLASHLIGHT("Flashlight", "F"),
    RECORDER("Recorder", "R"),
    FILES("Files", "D"),
    CALENDAR("Calendar", "L"),
    TRANSLATOR("Translator", "G"),
    CONTACTS("Contacts", "K"),
    WIFI("Wi-Fi", "W"),
    GAME("Game", "G"),
    SYSTEM("System", "S");

    companion object {
        fun fromTitle(t: String): MiniApp? = values().firstOrNull { it.title == t }
    }
}

object MiniAppFactory {

    private val bg = Color.parseColor("#111714")
    private val titleBg = Color.parseColor("#0F3D2E")
    private val accent = Color.parseColor("#2EE59B")
    private val paper = Color.parseColor("#D7EEE4")

    fun create(context: Context, app: MiniApp): View {
        return when (app) {
            MiniApp.NOTES -> notes(context)
            MiniApp.CALCULATOR -> calculator(context)
            MiniApp.BROWSER -> browser(context)
            MiniApp.VIDEO -> video(context)
            MiniApp.MUSIC -> music(context)
            MiniApp.IMAGE -> image(context)
            MiniApp.PAINT -> paint(context)
            MiniApp.CLOCK -> clock(context)
            MiniApp.FLASHLIGHT -> flashlight(context)
            MiniApp.RECORDER -> recorder(context)
            MiniApp.FILES -> files(context)
            MiniApp.CALENDAR -> calendar(context)
            MiniApp.TRANSLATOR -> translator(context)
            MiniApp.CONTACTS -> contacts(context)
            MiniApp.WIFI -> wifi(context)
            MiniApp.GAME -> game(context)
            MiniApp.SYSTEM -> system(context)
        }
    }

    private fun root(context: Context): LinearLayout {
        val l = LinearLayout(context)
        l.orientation = LinearLayout.VERTICAL
        l.setBackgroundColor(bg)
        l.setPadding(12, 12, 12, 12)
        return l
    }

    private fun label(context: Context, text: String, size: Float = 14f): TextView {
        val t = TextView(context)
        t.text = text
        t.textSize = size
        t.setTextColor(paper)
        t.setPadding(0, 4, 0, 4)
        return t
    }

    private fun btn(context: Context, text: String, onClick: () -> Unit): Button {
        val b = Button(context)
        b.text = text
        b.setTextColor(Color.parseColor("#0B0F0D"))
        b.setBackgroundColor(accent)
        b.setPadding(8, 8, 8, 8)
        b.setOnClickListener { onClick() }
        val p = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        p.setMargins(0, 4, 0, 4)
        b.layoutParams = p
        return b
    }

    private fun input(context: Context, hint: String, multiline: Boolean = false): EditText {
        val e = EditText(context)
        e.hint = hint
        e.setHintTextColor(Color.parseColor("#7A8A82"))
        e.setTextColor(paper)
        e.setBackgroundColor(Color.parseColor("#1A221E"))
        e.setPadding(10, 10, 10, 10)
        if (multiline) {
            e.minLines = 6
            e.gravity = Gravity.TOP
            e.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        } else {
            e.inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        val p = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        p.setMargins(0, 4, 0, 4)
        e.layoutParams = p
        return e
    }

    private fun destroyWebViewOnDetach(web: WebView) {
        web.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) { web.stopLoading(); web.destroy() }
        })
    }

    // ---------- Notes ----------
    private fun notes(context: Context): View {
        val l = root(context)
        val file = File(context.filesDir, "floating-notes.txt")
        val e = input(context, "Type your note...", multiline = true)
        if (file.exists()) {
            try { e.setText(file.readText()) } catch (_: Exception) {}
        }
        l.addView(e)
        l.addView(btn(context, "Save note") {
            try { file.writeText(e.text.toString()) } catch (_: Exception) {}
        })
        l.addView(btn(context, "Clear") { e.text.clear() })
        val scroll = ScrollView(context)
        scroll.addView(l)
        return scroll
    }

    // ---------- Calculator ----------
    private fun calculator(context: Context): View {
        val l = root(context)
        val display = label(context, "0", 28f)
        display.gravity = Gravity.END
        l.addView(display)
        var expr = ""
        fun update() { display.text = if (expr.isEmpty()) "0" else expr }
        val keys = listOf(
            listOf("7", "8", "9", "/"),
            listOf("4", "5", "6", "*"),
            listOf("1", "2", "3", "-"),
            listOf("0", ".", "=", "+")
        )
        for (row in keys) {
            val r = LinearLayout(context)
            r.orientation = LinearLayout.HORIZONTAL
            for (k in row) {
                val b = Button(context)
                b.text = k
                b.setTextColor(Color.parseColor("#0B0F0D"))
                b.setBackgroundColor(accent)
                b.setOnClickListener {
                    if (k == "=") {
                        try {
                            val v = eval(expr)
                            expr = if (v == v.roundToInt().toDouble()) v.roundToInt().toString() else v.toString()
                        } catch (_: Exception) { expr = "" }
                    } else {
                        expr += k
                    }
                    update()
                }
                val p = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                p.setMargins(2, 2, 2, 2)
                b.layoutParams = p
                r.addView(b)
            }
            l.addView(r)
        }
        l.addView(btn(context, "Clear (C)") { expr = ""; update() })
        val scroll = ScrollView(context)
        scroll.addView(l)
        return scroll
    }

    private fun eval(s: String): Double {
        // Tiny shunting-yard-free evaluator: handles + - * / and decimals.
        var i = 0
        fun parseFactor(): Double {
            var sign = 1.0
            if (i < s.length && (s[i] == '-' || s[i] == '+')) {
                if (s[i] == '-') sign = -1.0
                i++
            }
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            if (start == i) throw IllegalArgumentException("bad expr")
            return sign * s.substring(start, i).toDouble()
        }
        fun parseTerm(): Double {
            var v = parseFactor()
            while (i < s.length && (s[i] == '*' || s[i] == '/')) {
                val op = s[i++]
                val r = parseFactor()
                v = if (op == '*') v * r else v / r
            }
            return v
        }
        fun parseExpr(): Double {
            var v = parseTerm()
            while (i < s.length && (s[i] == '+' || s[i] == '-')) {
                val op = s[i++]
                val r = parseTerm()
                v = if (op == '+') v + r else v - r
            }
            return v
        }
        val v = parseExpr()
        if (i != s.length) throw IllegalArgumentException("trailing")
        return v
    }

    // ---------- Browser ----------
    private fun browser(context: Context): View {
        val l = root(context)
        val url = input(context, "https://example.com")
        url.setText("https://example.com")
        val web = WebView(context)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = WebViewClient()
        val wp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        )
        web.layoutParams = wp
        l.addView(url)
        l.addView(btn(context, "Go") {
            var u = url.text.toString().trim()
            if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
            web.loadUrl(u)
        })
        l.addView(web)
        web.loadUrl("https://example.com")
        destroyWebViewOnDetach(web)
        return l
    }

    // ---------- Video ----------
    private fun video(context: Context): View {
        val l = root(context)
        val url = input(context, "Paste video URL (mp4)...")
        val vv = VideoView(context)
        vv.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 400
        )
        l.addView(url)
        l.addView(vv)
        l.addView(btn(context, "Play") {
            val u = url.text.toString().trim()
            if (u.isNotEmpty()) {
                vv.setVideoURI(Uri.parse(u))
                vv.start()
            }
        })
        l.addView(btn(context, "Pause") { vv.pause() })
        l.addView(btn(context, "Stop") { vv.stopPlayback() })
        l.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) { vv.stopPlayback() }
        })
        return l
    }

    // ---------- Music ----------
    private fun music(context: Context): View {
        val l = root(context)
        val url = input(context, "Paste audio URL (mp3)...")
        val status = label(context, "Stopped")
        var player: MediaPlayer? = null
        l.addView(url)
        l.addView(status)
        l.addView(btn(context, "Play") {
            try {
                player?.release()
                player = MediaPlayer()
                player?.setDataSource(url.text.toString().trim())
                player?.setOnPreparedListener { it.start(); status.text = "Playing" }
                player?.setOnErrorListener { _, _, _ -> status.text = "Playback error"; true }
                status.text = "Loading..."
                player?.prepareAsync()
            } catch (e: Exception) {
                status.text = "Error: ${e.message}"
            }
        })
        l.addView(btn(context, "Pause") { player?.pause(); status.text = "Paused" })
        l.addView(btn(context, "Stop") {
            try { player?.stop(); player?.release() } catch (_: Exception) {}
            player = null
            status.text = "Stopped"
        })
        l.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) { player?.release(); player = null }
        })
        return l
    }

    // ---------- Image ----------
    private fun image(context: Context): View {
        val l = root(context)
        val url = input(context, "Paste image URL...")
        val iv = ImageView(context)
        iv.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 400
        )
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
        l.addView(url)
        l.addView(iv)
        l.addView(btn(context, "Load") {
            val u = url.text.toString().trim()
            if (u.isNotEmpty()) {
                Thread {
                    try {
                        val bmp = android.graphics.BitmapFactory.decodeStream(
                            java.net.URL(u).openStream()
                        )
                        iv.post { iv.setImageBitmap(bmp) }
                    } catch (_: Exception) {}
                }.start()
            }
        })
        l.addView(btn(context, "Open in browser") {
            val u = url.text.toString().trim()
            if (u.isNotEmpty()) {
                val i = Intent(Intent.ACTION_VIEW, Uri.parse(u))
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try { context.startActivity(i) } catch (_: Exception) {}
            }
        })
        return l
    }

    // ---------- Paint ----------
    private fun paint(context: Context): View {
        val l = root(context)
        val pv = PaintView(context)
        pv.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        )
        l.addView(pv)
        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        val colors = listOf(
            "#2EE59B" to "Green",
            "#FF5252" to "Red",
            "#40C4FF" to "Blue",
            "#FFD740" to "Yellow",
            "#FFFFFF" to "White"
        )
        for ((hex, name) in colors) {
            val b = Button(context)
            b.text = name
            b.setTextColor(Color.BLACK)
            b.setBackgroundColor(Color.parseColor(hex))
            b.setOnClickListener { pv.brushColor = Color.parseColor(hex) }
            val p = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            p.setMargins(2, 2, 2, 2)
            b.layoutParams = p
            row.addView(b)
        }
        l.addView(row)
        l.addView(btn(context, "Clear canvas") { pv.clearAll() })
        return l
    }

    // ---------- Clock / Stopwatch ----------
    private fun clock(context: Context): View {
        val l = root(context)
        val time = label(context, "--:--:--", 28f)
        time.gravity = Gravity.CENTER
        l.addView(time)
        val date = label(context, "", 14f)
        date.gravity = Gravity.CENTER
        l.addView(date)
        val handler = Handler(Looper.getMainLooper())
        var running = true
        val tick = object : Runnable {
            override fun run() {
                if (!running) return
                val now = Date()
                time.text = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(now)
                date.text = SimpleDateFormat("EEE, MMM d yyyy", Locale.getDefault()).format(now)
                handler.postDelayed(this, 1000)
            }
        }
        handler.post(tick)
        // Stopwatch
        val sw = label(context, "Stopwatch: 00:00.0", 18f)
        l.addView(sw)
        var startMs = 0L
        var elapsed = 0L
        var swRunning = false
        val swTick = object : Runnable {
            override fun run() {
                if (!swRunning) return
                val ms = elapsed + (System.currentTimeMillis() - startMs)
                val s = ms / 1000
                val tenths = (ms % 1000) / 100
                sw.text = String.format(Locale.getDefault(), "Stopwatch: %02d:%02d.%d", s / 60, s % 60, tenths)
                handler.postDelayed(this, 100)
            }
        }
        l.addView(btn(context, "Start stopwatch") {
            if (!swRunning) { startMs = System.currentTimeMillis(); swRunning = true; handler.post(swTick) }
        })
        l.addView(btn(context, "Pause stopwatch") {
            if (swRunning) { elapsed += System.currentTimeMillis() - startMs; swRunning = false }
        })
        l.addView(btn(context, "Reset stopwatch") {
            swRunning = false; elapsed = 0; sw.text = "Stopwatch: 00:00.0"
        })
        l.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) { running = false; swRunning = false; handler.removeCallbacks(tick); handler.removeCallbacks(swTick) }
        })
        return l
    }

    // ---------- Flashlight ----------
    private fun flashlight(context: Context): View {
        val l = root(context)
        val status = label(context, "Flashlight off")
        l.addView(status)
        var on = false
        l.addView(btn(context, "Toggle flashlight") {
            try {
                val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val id = cm.cameraIdList.firstOrNull { cid ->
                    val c = cm.getCameraCharacteristics(cid)
                    c.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                } ?: throw IllegalStateException("No flash available")
                on = !on
                cm.setTorchMode(id, on)
                status.text = if (on) "Flashlight ON" else "Flashlight OFF"
            } catch (e: Exception) {
                status.text = "No flashlight: ${e.message}"
            }
        })
        return l
    }

    // ---------- Recorder ----------
    private fun recorder(context: Context): View {
        val l = root(context)
        val status = label(context, "Recorder ready")
        l.addView(status)
        var rec: MediaRecorder? = null
        var outFile: File? = null
        l.addView(btn(context, "Start recording") {
            try {
                outFile = File(context.filesDir, "rec-${System.currentTimeMillis()}.m4a")
                rec = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
                rec?.setAudioSource(MediaRecorder.AudioSource.MIC)
                rec?.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                rec?.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                rec?.setOutputFile(outFile?.absolutePath)
                rec?.prepare()
                rec?.start()
                status.text = "Recording..."
            } catch (e: Exception) {
                status.text = "Error: ${e.message}"
            }
        })
        l.addView(btn(context, "Stop recording") {
            try {
                rec?.stop()
                rec?.release()
                status.text = "Saved: ${outFile?.name}"
            } catch (e: Exception) {
                status.text = "Error: ${e.message}"
            }
            rec = null
        })
        l.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) { try { rec?.stop() } catch (_: Exception) {} ; rec?.release(); rec = null }
        })
        return l
    }

    // ---------- Files ----------
    private fun files(context: Context): View {
        val l = root(context)
        val list = label(context, "", 13f)
        fun refresh() {
            val f = context.filesDir.listFiles()
            list.text = if (f.isNullOrEmpty()) "(empty)" else f.joinToString("\n") {
                (if (it.isDirectory) "[D] " else "[F] ") + it.name + " (" + it.length() + "b)"
            }
        }
        refresh()
        l.addView(list)
        l.addView(btn(context, "Refresh") { refresh() })
        val scroll = ScrollView(context)
        scroll.addView(l)
        return scroll
    }

    // ---------- Calendar ----------
    private fun calendar(context: Context): View {
        val l = root(context)
        val cv = CalendarView(context)
        cv.setBackgroundColor(bg)
        l.addView(cv)
        val sel = label(context, "Pick a date")
        l.addView(sel)
        cv.setOnDateChangeListener { _, y, m, d ->
            val c = Calendar.getInstance()
            c.set(y, m, d)
            sel.text = SimpleDateFormat("EEE, MMM d yyyy", Locale.getDefault()).format(c.time)
        }
        return l
    }

    // ---------- Translator ----------
    private fun translator(context: Context): View {
        val l = root(context)
        val web = WebView(context)
        web.settings.javaScriptEnabled = true
        web.webViewClient = WebViewClient()
        web.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        )
        l.addView(web)
        web.loadUrl("https://translate.google.com")
        destroyWebViewOnDetach(web)
        return l
    }

    // ---------- Contacts ----------
    private fun contacts(context: Context): View {
        val l = root(context)
        val out = label(context, "Tap Load. Needs Contacts permission.", 13f)
        l.addView(out)
        l.addView(btn(context, "Load contacts") {
            try {
                val cr = context.contentResolver
                val cur = cr.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    null, null, null, null
                )
                val sb = StringBuilder()
                if (cur != null) {
                    val nameIdx = cur.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    val numIdx = cur.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    var n = 0
                    while (cur.moveToNext() && n < 50) {
                        sb.append(cur.getString(nameIdx)).append(": ")
                            .append(cur.getString(numIdx)).append("\n")
                        n++
                    }
                    cur.close()
                }
                out.text = if (sb.isEmpty()) "(no contacts)" else sb.toString()
            } catch (e: Exception) {
                out.text = "Error (grant permission in Settings): ${e.message}"
            }
        })
        val scroll = ScrollView(context)
        scroll.addView(l)
        return scroll
    }

    // ---------- Wi-Fi ----------
    private fun wifi(context: Context): View {
        val l = root(context)
        val out = label(context, "", 14f)
        fun refresh() {
            try {
                @Suppress("DEPRECATION")
                val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                val info = wm.connectionInfo
                out.text = "SSID: ${info.ssid}\nIP: ${info.ipAddress}\nSpeed: ${info.linkSpeed} Mbps\nRSSI: ${info.rssi}"
            } catch (e: Exception) {
                out.text = "Wi-Fi info unavailable: ${e.message}"
            }
        }
        refresh()
        l.addView(out)
        l.addView(btn(context, "Refresh") { refresh() })
        return l
    }

    // ---------- Game: Guess the number ----------
    private fun game(context: Context): View {
        val l = root(context)
        var target = (1..100).random()
        var tries = 0
        val status = label(context, "Guess 1-100. Tries: 0")
        val guess = input(context, "Your guess...")
        l.addView(status)
        l.addView(guess)
        l.addView(btn(context, "Guess") {
            val g = guess.text.toString().toIntOrNull()
            if (g == null) { status.text = "Type a number"; return@btn }
            tries++
            status.text = when {
                g < target -> "Too low! Tries: $tries"
                g > target -> "Too high! Tries: $tries"
                else -> "You win! Tries: $tries. Tap New game."
            }
        })
        l.addView(btn(context, "New game") {
            target = (1..100).random(); tries = 0
            status.text = "Guess 1-100. Tries: 0"
        })
        return l
    }

    // ---------- System info ----------
    private fun system(context: Context): View {
        val l = root(context)
        val info = "Brand: ${Build.BRAND}\nModel: ${Build.MODEL}\n" +
            "Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
            "Device: ${Build.DEVICE}\nManufacturer: ${Build.MANUFACTURER}\n" +
            "Board: ${Build.BOARD}\nHardware: ${Build.HARDWARE}\n" +
            "Fingerprint: ${Build.FINGERPRINT}"
        l.addView(label(context, info, 13f))
        val scroll = ScrollView(context)
        scroll.addView(l)
        return scroll
    }
}
