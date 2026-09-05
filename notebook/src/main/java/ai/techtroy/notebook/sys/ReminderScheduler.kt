package ai.techtroy.notebook.sys

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import ai.techtroy.notebook.App
import ai.techtroy.notebook.R
import ai.techtroy.notebook.ui.EditorActivity

object ReminderScheduler {
    private const val ACTION_FIRE = "ai.techtroy.notebook.REMINDER"
    const val ACTION_DONE = "ai.techtroy.notebook.REMINDER_DONE"
    const val ACTION_SNOOZE = "ai.techtroy.notebook.REMINDER_SNOOZE"
    const val EXTRA_NOTE = "note_id"

    fun canScheduleExact(ctx: Context): Boolean {
        val am = ctx.getSystemService(AlarmManager::class.java)
        return Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
    }

    fun schedule(ctx: Context, noteId: Long, at: Long) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = firePi(ctx, noteId)
        am.cancel(pi)
        if (at <= System.currentTimeMillis()) return
        val showIntent = PendingIntent.getActivity(ctx, (noteId % Int.MAX_VALUE).toInt(), EditorActivity.intent(ctx, noteId), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        try {
            if (canScheduleExact(ctx)) {
                // Alarm-clock style: survives Doze, shows in status bar on some OEMs. Exact.
                am.setAlarmClock(AlarmManager.AlarmClockInfo(at, showIntent), pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun cancel(ctx: Context, noteId: Long) {
        ctx.getSystemService(AlarmManager::class.java).cancel(firePi(ctx, noteId))
        ctx.getSystemService(NotificationManager::class.java).cancel(notifId(noteId))
    }

    fun rescheduleAll(ctx: Context) {
        val app = ctx.applicationContext as App
        app.repo.pendingReminders().forEach { n ->
            val at = n.reminderAt ?: return@forEach
            if (at > System.currentTimeMillis()) schedule(ctx, n.id, at) else fire(ctx, n.id)
        }
    }

    private fun firePi(ctx: Context, noteId: Long): PendingIntent = PendingIntent.getBroadcast(
        ctx, (noteId % Int.MAX_VALUE).toInt(),
        Intent(ctx, ReminderReceiver::class.java).setAction(ACTION_FIRE).putExtra(EXTRA_NOTE, noteId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun notifId(noteId: Long) = 10_000 + (noteId % 100_000).toInt()

    fun fire(ctx: Context, noteId: Long) {
        val app = ctx.applicationContext as App
        val note = app.repo.note(noteId) ?: return
        if (note.inTrash) return
        val title = if (note.locked) ctx.getString(R.string.locked_note) else note.displayTitle.ifBlank { ctx.getString(R.string.reminder_generic) }
        val text = if (note.locked) "" else when {
            note.checklistTotal > 0 -> note.checklistPreview.joinToString("  ·  ") { it.text }.take(120)
            else -> note.body.take(160)
        }
        val open = PendingIntent.getActivity(ctx, (noteId % Int.MAX_VALUE).toInt(), EditorActivity.intent(ctx, noteId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val done = PendingIntent.getBroadcast(ctx, (noteId % Int.MAX_VALUE).toInt() + 1, Intent(ctx, ReminderReceiver::class.java).setAction(ACTION_DONE).putExtra(EXTRA_NOTE, noteId), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val snooze = PendingIntent.getBroadcast(ctx, (noteId % Int.MAX_VALUE).toInt() + 2, Intent(ctx, ReminderReceiver::class.java).setAction(ACTION_SNOOZE).putExtra(EXTRA_NOTE, noteId), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, Notifications.CH_REMINDERS)
            .setSmallIcon(R.drawable.ic_bookmark)
            .setColor(ContextCompat.getColor(ctx, R.color.gold))
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setDefaults(Notification.DEFAULT_ALL)
            .addAction(R.drawable.ic_note, ctx.getString(R.string.action_open), open)
            .addAction(R.drawable.ic_check, ctx.getString(R.string.action_done), done)
            .addAction(R.drawable.ic_snooze, ctx.getString(R.string.action_snooze), snooze)
            .build()
        if (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            ctx.getSystemService(NotificationManager::class.java).notify(notifId(noteId), n)
        }
        app.repo.setReminderDone(noteId)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val app = ctx.applicationContext as App
        val id = intent.getLongExtra(ReminderScheduler.EXTRA_NOTE, -1)
        val pending = goAsync()
        app.io.execute {
            try {
                when (intent.action) {
                    ReminderScheduler.ACTION_DONE -> { app.repo.setReminder(id, null); ReminderScheduler.cancel(ctx, id) }
                    ReminderScheduler.ACTION_SNOOZE -> {
                        val at = System.currentTimeMillis() + 10 * 60_000L
                        app.repo.setReminder(id, at); ReminderScheduler.schedule(ctx, id, at)
                        ctx.getSystemService(NotificationManager::class.java).cancel(10_000 + (id % 100_000).toInt())
                    }
                    else -> if (id > 0) ReminderScheduler.fire(ctx, id)
                }
            } finally { pending.finish() }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val app = ctx.applicationContext as App
        val pending = goAsync()
        app.io.execute { try { ReminderScheduler.rescheduleAll(ctx) } finally { pending.finish() } }
    }
}
