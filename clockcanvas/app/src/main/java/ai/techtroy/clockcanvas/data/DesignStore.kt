package ai.techtroy.clockcanvas.data

import ai.techtroy.clockcanvas.BackgroundKind
import ai.techtroy.clockcanvas.ClockDesign
import ai.techtroy.clockcanvas.ClockGravity
import ai.techtroy.clockcanvas.ClockStyle
import ai.techtroy.clockcanvas.ContentMode
import ai.techtroy.clockcanvas.FontRegistry
import ai.techtroy.clockcanvas.GradientKind
import ai.techtroy.clockcanvas.OrientationMode
import ai.techtroy.clockcanvas.data.SamplePresets
import ai.techtroy.clockcanvas.WidgetConfiguration
import android.content.Context
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The single source of truth for saved designs and for widget -> design
 * bindings.
 *
 * Storage layout (all inside the app's private files dir, so it survives a
 * reboot and needs no permissions):
 *
 *   files/clockcanvas/designs/<designId>.json   one file per design
 *   files/clockcanvas/samples.done              marker: sample presets seeded
 *   shared_prefs/clockcanvas_widgets.xml      appWidgetId -> designId bindings
 *
 * Bindings are per `appWidgetId`, which is what makes three installed widgets
 * on the same home screen able to show three different designs.
 */
class DesignStore private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val dir = File(appContext.filesDir, "clockcanvas/designs")
    private val widgetSp = appContext.getSharedPreferences("clockcanvas_widgets", Context.MODE_PRIVATE)
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile
    private var cache: MutableList<ClockDesign>? = null

    init {
        if (!dir.exists()) dir.mkdirs()
    }

    // ---- designs -------------------------------------------------------

    fun all(): List<ClockDesign> {
        cache?.let { return ArrayList(it) }
        val loaded = ArrayList<ClockDesign>()
        val files = dir.listFiles()
        if (files != null) {
            for (f in files.sortedBy { it.name }) {
                if (!f.name.endsWith(".json")) continue
                val text = tryRead(f) ?: continue
                val design = try {
                    val obj = ai.techtroy.clockcanvas.io.MiniJson.parse(text) ?: continue
                    DesignCodec.decode(obj)
                } catch (e: Throwable) {
                    continue
                }
                if (design.id.isNotEmpty()) loaded.add(design)
            }
        }
        if (loaded.isEmpty() && !samplesSeeded()) {
            loaded.addAll(SamplePresets.all())
            for (d in loaded) write(d)
            markSamplesSeeded()
        }
        cache = loaded
        return ArrayList(loaded)
    }

    fun byId(id: String?): ClockDesign? {
        if (id.isNullOrEmpty()) return null
        return all().firstOrNull { it.id == id }
    }

    /** Never null: falls back to the first saved design, then to a built-in default. */
    fun resolveOrFallback(id: String?): ClockDesign =
        byId(id) ?: all().firstOrNull() ?: SamplePresets.fallback()

    fun get(id: String): ClockDesign? = byId(id)

    fun upsert(design: ClockDesign): ClockDesign {
        val id = if (design.id.isEmpty()) newId() else design.id
        val toSave = if (design.id == id) design else design.copyWith { it.id = id }
        write(toSave)
        invalidate()
        return toSave
    }

    fun duplicate(id: String): ClockDesign? {
        val src = byId(id) ?: return null
        val copy = src.copyWith {
            it.id = newId()
            it.name = shorten(src.name + " copy")
        }
        write(copy)
        invalidate()
        return copy
    }

    fun delete(id: String) {
        // Any widget bound to this design falls back to the default preset on the
        // next render instead of holding a dangling id.
        for (widgetId in boundWidgetIds(id)) widgetSp.edit().remove(bindingKey(widgetId)).apply()
        fileFor(id)?.delete()
        invalidate()
    }

    fun resetAll() {
        dir.listFiles()?.forEach { it.delete() }
        widgetSp.edit().clear().apply()
        samplesFile()?.delete()
        invalidate()
    }

    fun exportAll(): String = DesignCodec.encodeAll(all())

    /** Replaces the whole library with an imported payload; returns how many were kept. */
    fun importAll(json: String): Int {
        val designs = DesignCodec.decodeList(json)
        if (designs.isEmpty()) return 0
        for (d in designs) {
            val fixed = if (d.id.isEmpty() || byId(d.id) != null) d.copyWith { it.id = newId() } else d
            write(fixed)
        }
        invalidate()
        return designs.size
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun invalidate() {
        cache = null
        for (l in listeners) {
            try {
                l.invoke()
            } catch (e: Throwable) {
                // A listener bug must not cost the user their design data.
            }
        }
    }

    private fun write(design: ClockDesign) {
        dir.mkdirs()
        val target = File(dir, safeName(design.id) + ".json")
        val tmp = File(dir, target.name + ".tmp")
        try {
            tmp.writeText(DesignCodec.write(DesignCodec.encode(design)))
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) tmp.copyTo(target, overwrite = true)
        } catch (e: Throwable) {
            try {
                target.writeText(DesignCodec.write(DesignCodec.encode(design)))
            } catch (ignored: Throwable) {
            }
        }
    }

    private fun fileFor(id: String): File? {
        if (id.isEmpty()) return null
        val f = File(dir, safeName(id) + ".json")
        return if (f.exists()) f else null
    }

    private fun tryRead(f: File): String? = try {
        if (f.length() > 1_000_000) null else f.readText()
    } catch (e: Throwable) {
        null
    }

    private fun samplesSeeded(): Boolean = samplesFile()?.exists() == true

    private fun markSamplesSeeded() {
        try {
            samplesFile()?.parentFile?.mkdirs()
            samplesFile()?.writeText("ok")
        } catch (ignored: Throwable) {
        }
    }

    private fun samplesFile(): File? = try {
        File(appContext.filesDir, "clockcanvas/samples.done")
    } catch (e: Throwable) {
        null
    }

    // ---- widget bindings ----------------------------------------------

    fun binding(appWidgetId: Int): WidgetConfiguration? {
        val designId = widgetSp.getString(bindingKey(appWidgetId), null) ?: return null
        return WidgetConfiguration(
            appWidgetId = appWidgetId,
            designId = designId,
            hostPackage = widgetSp.getString(hostKey(appWidgetId), null),
            configured = widgetSp.getBoolean(configuredKey(appWidgetId), false),
            lastBucketWidthDp = widgetSp.getInt(wKey(appWidgetId), 0),
            lastBucketHeightDp = widgetSp.getInt(hKey(appWidgetId), 0),
        )
    }

    fun designFor(appWidgetId: Int): ClockDesign = resolveOrFallback(binding(appWidgetId)?.designId)

    fun bind(appWidgetId: Int, designId: String, widthDp: Int = 0, heightDp: Int = 0) {
        widgetSp.edit()
            .putString(bindingKey(appWidgetId), designId)
            .putBoolean(configuredKey(appWidgetId), true)
            .putInt(wKey(appWidgetId), widthDp)
            .putInt(hKey(appWidgetId), heightDp)
            .apply()
    }

    fun rememberSize(appWidgetId: Int, widthDp: Int, heightDp: Int) {
        if (widthDp <= 0 || heightDp <= 0) return
        widgetSp.edit().putInt(wKey(appWidgetId), widthDp).putInt(hKey(appWidgetId), heightDp).apply()
    }

    fun rememberHost(appWidgetId: Int, hostPackage: String?) {
        if (hostPackage.isNullOrEmpty()) return
        widgetSp.edit().putString(hostKey(appWidgetId), hostPackage).apply()
    }

    fun unbind(appWidgetId: Int) {
        widgetSp.edit()
            .remove(bindingKey(appWidgetId))
            .remove(configuredKey(appWidgetId))
            .remove(hostKey(appWidgetId))
            .remove(wKey(appWidgetId))
            .remove(hKey(appWidgetId))
            .apply()
    }

    fun boundWidgetIds(designId: String): List<Int> {
        val out = ArrayList<Int>()
        for ((key, value) in widgetSp.all) {
            if (key.startsWith(BIND_PREFIX) && value == designId) {
                key.removePrefix(BIND_PREFIX).toIntOrNull()?.let { out.add(it) }
            }
        }
        return out
    }

    fun bindingsSnapshot(): List<WidgetConfiguration> {
        val ids = ArrayList<Int>()
        for (key in widgetSp.all.keys) {
            if (key.startsWith(BIND_PREFIX)) key.removePrefix(BIND_PREFIX).toIntOrNull()?.let { ids.add(it) }
        }
        return ids.sorted().mapNotNull { binding(it) }
    }

    private fun bindingKey(id: Int) = BIND_PREFIX + id
    private fun configuredKey(id: Int) = "configured." + id
    private fun hostKey(id: Int) = "host." + id
    private fun wKey(id: Int) = "w." + id
    private fun hKey(id: Int) = "h." + id

    companion object {
        private const val BIND_PREFIX = "widget."

        @Volatile
        private var instance: DesignStore? = null

        fun of(context: Context): DesignStore = instance ?: synchronized(this) {
            instance ?: DesignStore(context).also { instance = it }
        }

        fun newId(): String = "d" + java.lang.Long.toString(System.nanoTime().and(0xFFFFFFL), 36) +
            java.lang.Integer.toString((Math.random() * 1296).toInt(), 36)

        private fun safeName(id: String): String {
            val sb = StringBuilder(id.length)
            for (c in id) if (c.isLetterOrDigit() || c == '-' || c == '_') sb.append(c)
            val out = sb.toString()
            return if (out.isEmpty()) "design" else out
        }

        private fun shorten(s: String): String = if (s.length <= 40) s else s.substring(0, 39) + "\u2026"
    }
}
