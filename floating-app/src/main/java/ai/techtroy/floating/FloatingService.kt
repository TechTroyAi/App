package ai.techtroy.floating

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max

class FloatingService : Service() {
    private lateinit var wm: WindowManager
    private val windows = mutableListOf<View>()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("floating", "Floating windows", NotificationManager.IMPORTANCE_LOW))
        val pending = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, "floating")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Floating Fan is running")
            .setContentText("Tap to open mini-apps or close all windows")
            .setContentIntent(pending).setOngoing(true).build()
        startForeground(1, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
        val app = try { MiniApp.valueOf(intent?.getStringExtra("app") ?: "") } catch (_: IllegalArgumentException) { null }
        if (app != null) showWindow(app) else if (windows.isEmpty()) stopSelf()
        return START_NOT_STICKY
    }

    private fun showWindow(app: MiniApp) {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(17, 23, 20)); elevation = dp(8).toFloat() }
        val params = WindowManager.LayoutParams(
            dp(320), dp(450), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = dp(16 + (windows.size % 4) * 18); y = dp(80 + (windows.size % 4) * 18); softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Color.rgb(15, 61, 46)); gravity = Gravity.CENTER_VERTICAL }
        fun control(text: String): TextView = TextView(this).apply { this.text = text; textSize = 20f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setPadding(dp(10), dp(6), dp(10), dp(6)) }
        val title = control(app.title).apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL }
        bar.addView(title, LinearLayout.LayoutParams(0, dp(48), 1f))
        val minimize = control("−")
        val close = control("×")
        bar.addView(minimize); bar.addView(close); container.addView(bar)
        val content = MiniAppFactory.create(this, app)
        container.addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        val resize = control("↘").apply { gravity = Gravity.END; setBackgroundColor(Color.rgb(15, 61, 46)) }
        container.addView(resize, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(38)))
        var expandedHeight = params.height
        minimize.setOnClickListener {
            if (content.visibility == View.VISIBLE) {
                expandedHeight = params.height; content.visibility = View.GONE; resize.visibility = View.GONE
                params.height = dp(48); minimize.text = "+"
            } else {
                content.visibility = View.VISIBLE; resize.visibility = View.VISIBLE
                params.height = expandedHeight; minimize.text = "−"
            }
            wm.updateViewLayout(container, params)
        }
        close.setOnClickListener { removeWindow(container) }
        var downX = 0f; var downY = 0f; var initialX = 0; var initialY = 0
        title.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; initialX = params.x; initialY = params.y; true }
                MotionEvent.ACTION_MOVE -> { params.x = initialX + (event.rawX - downX).toInt(); params.y = initialY + (event.rawY - downY).toInt(); wm.updateViewLayout(container, params); true }
                MotionEvent.ACTION_UP -> { title.performClick(); true }
                else -> false
            }
        }
        var width = 0; var height = 0
        resize.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; width = params.width; height = params.height; true }
                MotionEvent.ACTION_MOVE -> { params.width = max(dp(220), width + (event.rawX - downX).toInt()); params.height = max(dp(180), height + (event.rawY - downY).toInt()); wm.updateViewLayout(container, params); true }
                MotionEvent.ACTION_UP -> { resize.performClick(); true }
                else -> false
            }
        }
        try { wm.addView(container, params); windows.add(container) } catch (_: RuntimeException) { if (windows.isEmpty()) stopSelf() }
    }

    private fun removeWindow(view: View) {
        if (windows.remove(view)) try { wm.removeView(view) } catch (_: IllegalArgumentException) {}
        if (windows.isEmpty()) stopSelf()
    }
    override fun onDestroy() {
        windows.toList().forEach { try { wm.removeView(it) } catch (_: IllegalArgumentException) {} }
        windows.clear()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
