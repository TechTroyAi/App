package ai.techtroy.clockcanvas.data

import ai.techtroy.clockcanvas.ClockSettings
import android.content.Context
import android.content.SharedPreferences

/**
 * App-wide settings. Kept in a plain SharedPreferences file rather than
 * Jetpack DataStore: the values are read synchronously by the widget renderer
 * (which has no coroutine scope), they are tiny, and this keeps the module
 * dependency-free so the offline toolchain can build the exact same sources.
 */
class AppPrefs private constructor(private val sp: SharedPreferences) {

    fun load(): ClockSettings = ClockSettings(
        use24Hour = if (sp.contains(KEY_HOUR24)) sp.getBoolean(KEY_HOUR24, false) else null,
        dateFormat = sp.getString(KEY_DATE_FORMAT, "EEEE, MMMM d") ?: "EEEE, MMMM d",
        firstDayOfWeekSunday = sp.getBoolean(KEY_FIRST_SUNDAY, false),
        preciseRefresh = sp.getBoolean(KEY_PRECISE, false),
        rememberLastDesign = sp.getBoolean(KEY_REMEMBER, true),
        lastDesignId = sp.getString(KEY_LAST, "") ?: "",
    )

    fun set24Hour(value: Boolean?) {
        val e = sp.edit()
        if (value == null) e.remove(KEY_HOUR24) else e.putBoolean(KEY_HOUR24, value)
        e.apply()
    }

    fun setDateFormat(pattern: String) = sp.edit().putString(KEY_DATE_FORMAT, pattern).apply()

    fun setFirstDayOfWeekSunday(value: Boolean) = sp.edit().putBoolean(KEY_FIRST_SUNDAY, value).apply()

    fun setPreciseRefresh(value: Boolean) = sp.edit().putBoolean(KEY_PRECISE, value).apply()

    fun setRememberLast(value: Boolean) = sp.edit().putBoolean(KEY_REMEMBER, value).apply()

    fun setLastDesign(id: String) = sp.edit().putString(KEY_LAST, id).apply()

    companion object {
        private const val FILE = "clockcanvas_settings"
        private const val KEY_HOUR24 = "hour24"
        private const val KEY_DATE_FORMAT = "dateFormat"
        private const val KEY_FIRST_SUNDAY = "firstSunday"
        private const val KEY_PRECISE = "precise"
        private const val KEY_REMEMBER = "rememberLast"
        private const val KEY_LAST = "lastDesign"

        @Volatile
        private var instance: AppPrefs? = null

        fun of(context: Context): AppPrefs = instance ?: synchronized(this) {
            instance ?: AppPrefs(context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE))
                .also { instance = it }
        }
    }
}
