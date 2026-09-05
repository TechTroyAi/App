package ai.techtroy.notebook.sys

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import ai.techtroy.notebook.R

object Notifications {
    const val CH_REMINDERS = "reminders"
    const val CH_RECORDING = "recording"

    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_REMINDERS, ctx.getString(R.string.reminder_channel), NotificationManager.IMPORTANCE_HIGH).apply {
            description = ctx.getString(R.string.reminder_channel_desc); enableVibration(true)
        })
        nm.createNotificationChannel(NotificationChannel(CH_RECORDING, ctx.getString(R.string.recording_channel), NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null) })
    }
}
