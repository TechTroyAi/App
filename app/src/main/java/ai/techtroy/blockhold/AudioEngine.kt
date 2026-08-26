package ai.techtroy.blockhold

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool

internal class AudioEngine(private val context: Context) {

    private val sounds = HashMap<String, Int>()
    private val soundPool: SoundPool
    private var enabled = true

    init {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        soundPool = SoundPool.Builder()
            .setMaxStreams(10)
            .setAudioAttributes(attributes)
            .build()

        load("ui_click")
        load("dig")
        load("build")
        load("bolt")
        load("frost")
        load("cannon")
        load("enemy_down")
        load("wave")
        load("base_hit")
        load("victory")
    }

    private fun load(name: String) {
        val resourceId = context.resources.getIdentifier(name, "raw", context.packageName)
        if (resourceId != 0) {
            sounds[name] = soundPool.load(context, resourceId, 1)
        }
    }

    fun play(name: String, volume: Float = 0.55f, pitch: Float = 1f) {
        if (!enabled) {
            return
        }
        val soundId = sounds[name] ?: return
        soundPool.play(soundId, volume, volume, 1, 0, pitch)
    }

    fun toggle(): Boolean {
        enabled = !enabled
        if (enabled) {
            play("ui_click", 0.35f, 1.08f)
        }
        return enabled
    }

    fun isEnabled(): Boolean {
        return enabled
    }

    fun release() {
        soundPool.release()
    }
}
