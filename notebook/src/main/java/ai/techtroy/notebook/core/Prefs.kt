package ai.techtroy.notebook.core

import android.content.Context
import android.content.SharedPreferences
import ai.techtroy.notebook.data.BodyType
import ai.techtroy.notebook.data.SortMode

enum class Theme(val id: Int) { BLACK_GOLD(0), IVORY_GOLD(1), AMOLED(2);
    companion object { fun from(i: Int) = entries.firstOrNull { it.id == i } ?: BLACK_GOLD }
}

class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("notebook", Context.MODE_PRIVATE)

    var theme: Theme
        get() = Theme.from(sp.getInt("theme", 0)); set(v) = sp.edit().putInt("theme", v.id).apply()
    var gridLayout: Boolean
        get() = sp.getBoolean("grid", true); set(v) = sp.edit().putBoolean("grid", v).apply()
    var sort: SortMode
        get() = SortMode.from(sp.getInt("sort", 0)); set(v) = sp.edit().putInt("sort", v.id).apply()
    var sortAsc: Boolean
        get() = sp.getBoolean("sort_asc", false); set(v) = sp.edit().putBoolean("sort_asc", v).apply()
    /** 0 small 1 medium 2 large 3 xlarge */
    var fontSize: Int
        get() = sp.getInt("font", 1); set(v) = sp.edit().putInt("font", v).apply()
    /** null = ask every time */
    var defaultNoteType: BodyType?
        get() = sp.getString("default_type", null)?.let { BodyType.from(it) }
        set(v) = sp.edit().putString("default_type", v?.db).apply()

    // security
    var pinHash: String?
        get() = sp.getString("pin_hash", null); set(v) = sp.edit().putString("pin_hash", v).apply()
    var pinSalt: String?
        get() = sp.getString("pin_salt", null); set(v) = sp.edit().putString("pin_salt", v).apply()
    val hasPin get() = pinHash != null
    var biometric: Boolean
        get() = sp.getBoolean("biometric", true); set(v) = sp.edit().putBoolean("biometric", v).apply()
    /** 0 immediately, 1 = 1min, 2 = 5min, 3 = screen off */
    var autoLock: Int
        get() = sp.getInt("autolock", 1); set(v) = sp.edit().putInt("autolock", v).apply()

    var echoDaily: Boolean
        get() = sp.getBoolean("echo_daily", true); set(v) = sp.edit().putBoolean("echo_daily", v).apply()
    var lastEchoDay: Long
        get() = sp.getLong("echo_day", 0); set(v) = sp.edit().putLong("echo_day", v).apply()
    var eggSeen: Boolean
        get() = sp.getBoolean("egg", false); set(v) = sp.edit().putBoolean("egg", v).apply()

    var lastFolder: Long
        get() = sp.getLong("last_folder", 0); set(v) = sp.edit().putLong("last_folder", v).apply()
    var backupIncludeAttachments: Boolean
        get() = sp.getBoolean("backup_att", true); set(v) = sp.edit().putBoolean("backup_att", v).apply()
    var exactAlarmAsked: Boolean
        get() = sp.getBoolean("exact_asked", false); set(v) = sp.edit().putBoolean("exact_asked", v).apply()
    var firstRunDone: Boolean
        get() = sp.getBoolean("first_run_done", false); set(v) = sp.edit().putBoolean("first_run_done", v).apply()

    // pages defaults
    var pagesPaper: String
        get() = sp.getString("pages_paper", "lined")!!; set(v) = sp.edit().putString("pages_paper", v).apply()
    var pagesDark: Boolean
        get() = sp.getBoolean("pages_dark", true); set(v) = sp.edit().putBoolean("pages_dark", v).apply()
    var penColor: Int
        get() = sp.getInt("pen_color", 0xFFD4AF37.toInt()); set(v) = sp.edit().putInt("pen_color", v).apply()
    var penWidth: Float
        get() = sp.getFloat("pen_width", 3.5f); set(v) = sp.edit().putFloat("pen_width", v).apply()
}
