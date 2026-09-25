package ai.techtroy.clockcanvas

import ai.techtroy.clockcanvas.data.AppPrefs
import ai.techtroy.clockcanvas.data.DesignStore
import ai.techtroy.clockcanvas.media.MediaHandler
import ai.techtroy.clockcanvas.render.BitmapUtils
import ai.techtroy.clockcanvas.render.ClockRenderer
import ai.techtroy.clockcanvas.widget.ClockWidgetUpdater
import ai.techtroy.clockcanvas.widget.WidgetThreads
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Process entry point. Three jobs:
 *
 *  1. Warm the render path (thread + fonts) so the first widget frame is not slow.
 *  2. Tick: a single dynamic `ACTION_TIME_TICK` receiver updates *every* installed
 *     widget at minute boundaries while this process lives, which is the practical
 *     way to get minute-exact widgets without abusing JobScheduler.
 *  3. React to time/timezone/locale/boot changes and re-render, so a clock never
 *     shows the wrong format after the user travels or changes their phone setup.
 */
class ClockCanvasApp : Application() {

    private var tickReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        WidgetThreads.ensure()
        FontRegistry.preWarm(resources.assets)
        // Touch the store once so sample presets exist before the first widget render.
        DesignStore.of(this).all()
        registerTick()
        MediaHandler.of(this).restorePending()
    }

    override fun onTerminate() {
        try {
            tickReceiver?.let { unregisterReceiver(it) }
        } catch (e: Throwable) {
        }
        WidgetThreads.quit()
        super.onTerminate()
    }

    private fun registerTick() {
        if (tickReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_TIME_TICK,
                    Intent.ACTION_TIME_CHANGED,
                    Intent.ACTION_DATE_CHANGED,
                    Intent.ACTION_TIMEZONE_CHANGED,
                    Intent.ACTION_LOCALE_CHANGED,
                    Intent.ACTION_BOOT_COMPLETED,
                    Intent.ACTION_SCREEN_ON,
                    Intent.ACTION_USER_PRESENT -> {
                        ClockWidgetUpdater.updateAll(context)
                    }
                }
            }
        }
        tickReceiver = receiver
        val filter = IntentFilter(Intent.ACTION_TIME_TICK)
        filter.addAction(Intent.ACTION_TIME_CHANGED)
        filter.addAction(Intent.ACTION_DATE_CHANGED)
        filter.addAction(Intent.ACTION_TIMEZONE_CHANGED)
        filter.addAction(Intent.ACTION_LOCALE_CHANGED)
        filter.addAction(Intent.ACTION_SCREEN_ON)
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(receiver, filter)
            }
        } catch (e: Throwable) {
            // Android only allows some of those actions from a dynamic receiver on
            // certain OEMs; losing the extra ticks only costs precision, never data.
        }
    }

    /** Called by the UI after the user toggles "keep time exact". */
    fun refreshTickerService() {
        Handler(Looper.getMainLooper()).post {
            val enabled = AppPrefs.of(this).load().preciseRefresh
            if (enabled) ClockWidgetUpdater.startTickerIfEnabled(this) else ClockWidgetUpdater.stopTicker(this)
        }
    }

    fun reRenderAllWidgets() {
        ClockRenderer.of(this).clearCaches()
        ClockWidgetUpdater.updateAll(this)
    }

    companion object {
        @Volatile
        private var instance: ClockCanvasApp? = null

        fun get(): ClockCanvasApp? = instance
    }
}
