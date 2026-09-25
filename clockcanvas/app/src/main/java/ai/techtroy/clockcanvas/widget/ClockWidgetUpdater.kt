package ai.techtroy.clockcanvas.widget

import ai.techtroy.clockcanvas.ClockDesign
import ai.techtroy.clockcanvas.R
import ai.techtroy.clockcanvas.data.AppPrefs
import ai.techtroy.clockcanvas.data.DesignStore
import ai.techtroy.clockcanvas.render.ClockRenderer
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.widget.RemoteViews

/**
 * Renders and pushes widget updates off the main thread.
 *
 * `updatePeriodMillis` is clamped to 15 minutes by the platform, so minute-level
 * refresh comes from the `TIME_TICK` receiver in [ai.techtroy.clockcanvas.ClockCanvasApp]
 * (alive whenever the app process is alive, which for a launcher-resident widget
 * is most of the time). [ai.techtroy.clockcanvas.widget.WidgetTickerService] can
 * keep that process alive on purpose when the user turns "keep time exact" on.
 */
object ClockWidgetUpdater {

    private const val NOTIFICATION_CHANNEL = "clockcanvas_widgets"
    private val heldBitmaps = ArrayList<Bitmap>()
    @Volatile
    private var busy = false
    @Volatile
    private var queued = false

    fun update(context: Context, manager: AppWidgetManager?, ids: IntArray?) {
        val appContext = context.applicationContext
        val widgetManager = manager ?: AppWidgetManager.getInstance(appContext)
        val targets = ids?.takeIf { it.isNotEmpty() } ?: widgetManager.getAppWidgetIds(
            ClockCanvasWidgetProvider.componentName(appContext)
        )
        if (targets == null || targets.isEmpty()) return
        val work = IntArray(targets.size) { targets[it] }
        if (!enqueue(appContext, widgetManager, work)) {
            applyDirect(appContext, widgetManager, work)
        }
    }

    /** How many ClockCanvas widgets the user currently has on their screens. */
    fun installedCount(context: Context): Int = try {
        AppWidgetManager.getInstance(context.applicationContext)
            .getAppWidgetIds(ClockCanvasWidgetProvider.componentName(context.applicationContext)).size
    } catch (e: Throwable) {
        0
    }

    fun updateAll(context: Context) {
        val appContext = context.applicationContext
        val manager = AppWidgetManager.getInstance(appContext)
        update(appContext, manager, manager.getAppWidgetIds(ClockCanvasWidgetProvider.componentName(appContext)))
    }

    private fun enqueue(context: Context, manager: AppWidgetManager, ids: IntArray): Boolean {
        val handler = WidgetThreads.handler ?: return false
        if (busy) {
            queued = true
            return true
        }
        busy = true
        handler.post {
            var again = false
            try {
                applyDirect(context, manager, ids)
            } catch (e: Throwable) {
                // A render failure must never crash the launcher process; the widget
                // simply keeps its last frame.
            } finally {
                again = queued
                queued = false
                busy = false
            }
            if (again) updateAll(context)
        }
        return true
    }

    private fun applyDirect(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val store = DesignStore.of(context)
        val renderer = ClockRenderer.of(context)
        val keepAlive = ArrayList<Bitmap>()
        for (id in ids) {
            val design = store.designFor(id)
            val options = try {
                manager.getAppWidgetOptions(id)
            } catch (e: Throwable) {
                null
            }
            val bitmap = try {
                renderer.renderForWidget(design, options)
            } catch (e: Throwable) {
                null
            } catch (e: OutOfMemoryError) {
                null
            }
            if (bitmap == null || bitmap.isRecycled) continue
            keepAlive.add(bitmap)
            val views = buildViews(context, id, design, bitmap)
            try {
                manager.updateAppWidget(id, views)
            } catch (e: Throwable) {
                // Widget removed while we were rendering.
            }
        }
        synchronized(heldBitmaps) {
            heldBitmaps.clear()
            heldBitmaps.addAll(keepAlive)
            while (heldBitmaps.size > 12) heldBitmaps.removeAt(0)
        }
    }

    private fun buildViews(context: Context, widgetId: Int, design: ClockDesign, bitmap: Bitmap): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_clock)
        views.setImageViewBitmap(R.id.widget_image, bitmap)
        when (design.tapAction) {
            ClockDesign.TAP_NONE -> {
                views.setOnClickPendingIntent(R.id.widget_root, null)
            }
            ClockDesign.TAP_EDITOR -> {
                views.setOnClickPendingIntent(R.id.widget_root, editorIntent(context, widgetId))
            }
            else -> {
                views.setOnClickPendingIntent(R.id.widget_root, fullscreenIntent(context, widgetId))
            }
        }
        return views
    }

    fun fullscreenIntent(context: Context, widgetId: Int): PendingIntent {
        val intent = Intent(context, ai.techtroy.clockcanvas.ui.FullscreenClockActivity::class.java)
            .setAction(ai.techtroy.clockcanvas.ui.FullscreenClockActivity.ACTION_SHOW)
            .putExtra(ai.techtroy.clockcanvas.ui.FullscreenClockActivity.EXTRA_WIDGET_ID, widgetId)
        return PendingIntent.getActivity(context, 900 + widgetId, intent, pendingFlags())
    }

    fun editorIntent(context: Context, widgetId: Int): PendingIntent {
        val intent = Intent(context, ai.techtroy.clockcanvas.ui.EditorActivity::class.java)
            .putExtra(ai.techtroy.clockcanvas.ui.EditorActivity.EXTRA_WIDGET_ID, widgetId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(context, 1900 + widgetId, intent, pendingFlags())
    }

    private fun pendingFlags(): Int {
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= 23) flags = flags or PendingIntent.FLAG_IMMUTABLE
        return flags
    }

    fun startTickerIfEnabled(context: Context) {
        val enabled = AppPrefs.of(context).load().preciseRefresh
        if (!enabled) return
        ensureChannel(context)
        try {
            val intent = Intent(context, WidgetTickerService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        } catch (e: Throwable) {
            // Background-start restrictions: the widget still refreshes via TIME_TICK
            // while the app is alive, and via updatePeriodMillis otherwise.
        }
    }

    fun stopTicker(context: Context) {
        try {
            context.stopService(Intent(context, WidgetTickerService::class.java))
        } catch (e: Throwable) {
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        try {
            val manager = context.getSystemService(android.app.NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(NOTIFICATION_CHANNEL) != null) return
            val channel = android.app.NotificationChannel(
                NOTIFICATION_CHANNEL,
                "Clock widgets",
                android.app.NotificationManager.IMPORTANCE_MIN
            )
            channel.setShowBadge(false)
            channel.description = "Keeps the home-screen clock ticking on time while enabled in Settings."
            manager.createNotificationChannel(channel)
        } catch (e: Throwable) {
        }
    }
}
