package ai.techtroy.clockcanvas.widget

import ai.techtroy.clockcanvas.R
import ai.techtroy.clockcanvas.data.AppPrefs
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder

/**
 * Optional foreground service behind Settings → "Keep time exact".
 *
 * The Android widget refresh budget (`updatePeriodMillis`) is clamped to 15
 * minutes, so minute-accurate widgets depend on the app process receiving
 * `ACTION_TIME_TICK`. Keeping one low-importance notification up is the honest,
 * battery-visible way to guarantee that; it is OFF by default and the app says
 * so in the settings screen rather than pretending it is free.
 */
class WidgetTickerService : Service() {

    private var receiver: BroadcastReceiver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
        val tick = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val action = intent.action
                if (action == Intent.ACTION_TIME_TICK) {
                    ClockWidgetUpdater.updateAll(context)
                } else if (action == Intent.ACTION_TIME_CHANGED || action == Intent.ACTION_TIMEZONE_CHANGED) {
                    ClockWidgetUpdater.updateAll(context)
                }
            }
        }
        receiver = tick
        try {
            val filter = IntentFilter(Intent.ACTION_TIME_TICK)
            filter.addAction(Intent.ACTION_TIME_CHANGED)
            filter.addAction(Intent.ACTION_TIMEZONE_CHANGED)
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(tick, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(tick, filter)
            }
        } catch (e: Throwable) {
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!AppPrefs.of(this).load().preciseRefresh) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        receiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: Throwable) {
            }
        }
        receiver = null
        ClockWidgetUpdater.stopTicker(this)
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val content = PendingIntent.getActivity(
            this,
            77,
            Intent(this, ai.techtroy.clockcanvas.ui.HomeActivity::class.java),
            if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else PendingIntent.FLAG_UPDATE_CURRENT
        )
        builder.setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_ticker_text))
            .setSmallIcon(R.drawable.ic_stat_clock)
            .setContentIntent(content)
            .setOngoing(true)
            .setPriority(Notification.PRIORITY_MIN)
        return builder.build()
    }

    companion object {
        private const val CHANNEL = "clockcanvas_widgets"
        private const val NOTIFICATION_ID = 4711
    }
}
