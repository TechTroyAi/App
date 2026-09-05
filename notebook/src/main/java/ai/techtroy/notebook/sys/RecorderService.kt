package ai.techtroy.notebook.sys

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import ai.techtroy.notebook.App
import ai.techtroy.notebook.R
import ai.techtroy.notebook.ui.EditorActivity
import java.io.File

/**
 * Foreground voice-memo recorder (AAC/M4A, 64 kbps mono). Keeps recording when the editor is
 * backgrounded; the editor rebinds and shows the REC pill. Also samples amplitude every 100 ms so
 * the player can draw a waveform without decoding.
 */
class RecorderService : Service() {

    inner class LocalBinder : Binder() { val service get() = this@RecorderService }
    private val binder = LocalBinder()

    private var recorder: MediaRecorder? = null
    var noteId: Long = -1; private set
    var file: File? = null; private set
    var startedAt = 0L; private set          // elapsedRealtime when recording (re)started
    var accumulated = 0L; private set        // ms recorded before the current segment
    var paused = false; private set
    val amplitudes = ArrayList<Int>()        // 0..32767 per 100 ms tick
    val isRecording get() = recorder != null
    val elapsedMs: Long get() = accumulated + if (paused || startedAt == 0L) 0 else SystemClock.elapsedRealtime() - startedAt

    var listener: (() -> Unit)? = null
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            recorder?.let { r -> if (!paused) amplitudes += runCatching { r.maxAmplitude }.getOrDefault(0) }
            listener?.invoke()
            if (recorder != null) handler.postDelayed(this, 100)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val id = intent.getLongExtra(EXTRA_NOTE, -1)
                if (recorder == null && id > 0) start(id)
            }
            ACTION_STOP -> stopAndSave()
        }
        return START_NOT_STICKY
    }

    private fun start(id: Long) {
        val app = application as App
        noteId = id
        val f = File(app.files.temp, "rec_" + app.files.newName("m4a")); file = f
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1); r.setAudioSamplingRate(44100); r.setAudioEncodingBitRate(64_000)
            r.setOutputFile(f.absolutePath)
            r.prepare(); r.start()
        } catch (t: Throwable) {
            runCatching { r.release() }; file = null; stopSelf(); return
        }
        recorder = r; startedAt = SystemClock.elapsedRealtime(); accumulated = 0; paused = false; amplitudes.clear()
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= 30) ServiceCompat.startForeground(this, NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) else startForeground(NOTIF_ID, notif)
        handler.post(tick)
    }

    fun pause() {
        val r = recorder ?: return
        if (paused || Build.VERSION.SDK_INT < 24) return
        runCatching { r.pause() }; accumulated += SystemClock.elapsedRealtime() - startedAt; paused = true; listener?.invoke()
    }

    fun resume() {
        val r = recorder ?: return
        if (!paused) return
        runCatching { r.resume() }; startedAt = SystemClock.elapsedRealtime(); paused = false; listener?.invoke()
    }

    /** Stops, returns (file, durationMs, amplitudes) or null if nothing usable was recorded. */
    fun stopAndSave(): Result? {
        val r = recorder ?: return null
        val dur = elapsedMs
        recorder = null
        handler.removeCallbacks(tick)
        runCatching { r.stop() }; runCatching { r.release() }
        val f = file
        val res = if (f != null && f.exists() && f.length() > 1024 && dur > 500) Result(noteId, f, dur, amplitudes.toList()) else { f?.delete(); null }
        lastResult = res
        listener?.invoke()
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
        return res
    }

    data class Result(val noteId: Long, val file: File, val durationMs: Long, val amplitudes: List<Int>)

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(this, 900, EditorActivity.intent(this, noteId), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 901, Intent(this, RecorderService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, Notifications.CH_RECORDING)
            .setSmallIcon(R.drawable.ic_mic).setColor(getColor(R.color.gold))
            .setContentTitle(getString(R.string.recording)).setContentText(getString(R.string.recording_notification))
            .setOngoing(true).setContentIntent(open).setOnlyAlertOnce(true)
            .addAction(R.drawable.ic_stop, getString(R.string.stop), stop)
            .build()
    }

    override fun onDestroy() { recorder?.let { runCatching { it.stop() }; runCatching { it.release() } }; recorder = null; handler.removeCallbacks(tick); super.onDestroy() }

    companion object {
        const val ACTION_START = "ai.techtroy.notebook.REC_START"
        const val ACTION_STOP = "ai.techtroy.notebook.REC_STOP"
        const val EXTRA_NOTE = "note_id"
        const val NOTIF_ID = 7001
        /** Result of the last stop, consumed by the editor if it wasn't bound at the time. */
        @Volatile var lastResult: Result? = null

        fun start(ctx: Context, noteId: Long) {
            val i = Intent(ctx, RecorderService::class.java).setAction(ACTION_START).putExtra(EXTRA_NOTE, noteId)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }
}
