package ai.techtroy.notebook.sys

import android.os.SystemClock
import android.util.Base64
import ai.techtroy.notebook.core.Prefs
import java.security.MessageDigest
import java.security.SecureRandom

/** App-wide PIN store + unlock session (auto-lock timer). Per-note lock flag lives on the note. */
object Lock {
    @Volatile private var unlockedAt: Long = 0L
    private const val SCREEN_OFF = 3

    fun setPin(prefs: Prefs, pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs.pinSalt = Base64.encodeToString(salt, Base64.NO_WRAP)
        prefs.pinHash = hash(pin, salt)
        unlockedAt = SystemClock.elapsedRealtime()
    }

    fun clearPin(prefs: Prefs) { prefs.pinHash = null; prefs.pinSalt = null; unlockedAt = 0 }

    fun check(prefs: Prefs, pin: String): Boolean {
        val salt = Base64.decode(prefs.pinSalt ?: return false, Base64.NO_WRAP)
        val ok = hash(pin, salt) == prefs.pinHash
        if (ok) unlockedAt = SystemClock.elapsedRealtime()
        return ok
    }

    fun markUnlocked() { unlockedAt = SystemClock.elapsedRealtime() }
    fun lockNow() { unlockedAt = 0 }

    /** True if a recent unlock is still valid under the auto-lock policy. */
    fun sessionValid(prefs: Prefs): Boolean {
        if (unlockedAt == 0L) return false
        val age = SystemClock.elapsedRealtime() - unlockedAt
        return when (prefs.autoLock) {
            0 -> age < 1_500L          // "immediately": tiny grace so a lock→open flow doesn't re-ask
            1 -> age < 60_000L
            2 -> age < 5 * 60_000L
            SCREEN_OFF -> true          // cleared by screen-off receiver
            else -> false
        }
    }

    fun onScreenOff(prefs: Prefs) { if (prefs.autoLock == SCREEN_OFF) lockNow() }

    private fun hash(pin: String, salt: ByteArray): String {
        var d = MessageDigest.getInstance("SHA-256").run { update(salt); digest(pin.toByteArray()) }
        repeat(20_000) { d = MessageDigest.getInstance("SHA-256").run { update(salt); digest(d) } }
        return Base64.encodeToString(d, Base64.NO_WRAP)
    }
}
