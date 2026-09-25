package ai.techtroy.clockcanvas

import android.content.res.AssetManager
import android.graphics.Typeface
import java.util.concurrent.ConcurrentHashMap

/**
 * The bundled typefaces.
 *
 * Every font here is shipped inside the APK under `assets/fonts/` with its SIL
 * Open Font License 1.1 text under `assets/licenses/`, i.e. fonts whose license
 * explicitly permits redistribution. `tools/fetch_clockcanvas_fonts.py` is the
 * script that pulled them from the upstream `google/fonts` repository, so the
 * set is reproducible and auditable.
 *
 * Four of the five are *variable* fonts: weight comes from
 * `FontVariationSettings("wght" -> weight)`, which is applied through reflection
 * so this file also compiles against an older `android.jar` (the offline
 * toolchain in this repository). Where the API is unavailable the font still
 * renders - it just keeps its default weight, and faux-bold is applied instead.
 */
object FontRegistry {

    const val INTER = "inter"
    const val SPACE_GROTESK = "space_grotesk"
    const val BEBAS_NEUE = "bebas_neue"
    const val PLAYFAIR = "playfair_display"
    const val SOURCE_CODE_PRO = "source_code_pro"
    const val SYSTEM = "system"

    data class Entry(
        val id: String,
        val label: String,
        val asset: String?,
        val variableWeight: Boolean,
        val note: String,
    )

    val ALL = listOf(
        Entry(INTER, "Inter", "fonts/inter_variable.ttf", true, "Humanist grotesque, great at small sizes"),
        Entry(SPACE_GROTESK, "Space Grotesk", "fonts/space_grotesk_variable.ttf", true, "Technical, tight apertures"),
        Entry(BEBAS_NEUE, "Bebas Neue", "fonts/bebas_neue_regular.ttf", false, "Condensed caps - large number clocks"),
        Entry(PLAYFAIR, "Playfair Display", "fonts/playfair_display_variable.ttf", true, "High-contrast serif"),
        Entry(SOURCE_CODE_PRO, "Source Code Pro", "fonts/source_code_pro_variable.ttf", true, "Monospaced digits, no jitter"),
        Entry(SYSTEM, "System default", null, false, "Roboto/Segoe as the device ships it"),
    )

    /** Weights offered by the editor; mapped onto the variable axis when present. */
    val WEIGHTS = listOf(300, 400, 500, 600, 700, 800)

    private val cache = ConcurrentHashMap<String, Typeface>()

    fun byId(id: String?): Entry = ALL.firstOrNull { it.id == id } ?: ALL.first()

    /**
     * @param weight 100..900, applied as a variable axis when the font has one.
     */
    fun resolve(assets: AssetManager, id: String?, weight: Int, boldFaux: Boolean = true): Typeface {
        val entry = byId(id)
        val key = entry.id + ":" + weight
        cache[key]?.let { return it }
        val tf: Typeface = if (entry.asset == null) {
            if (weight >= 700 && boldFaux) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        } else {
            val loaded = try {
                Typeface.createFromAsset(assets, entry.asset)
            } catch (e: Throwable) {
                null
            } ?: (if (weight >= 700 && boldFaux) Typeface.DEFAULT_BOLD else Typeface.DEFAULT)
            applyVariation(loaded, weight)
        }
        cache[key] = tf
        return tf
    }

    private fun applyVariation(base: Typeface, weight: Int): Typeface {
        if (weight < 100 || weight > 1000) return base
        return try {
            val cls = Class.forName("android.graphics.FontVariationSettings")
            val ctor = cls.getConstructor(Map::class.java)
            @Suppress("UNCHECKED_CAST")
            val settings = ctor.newInstance(mapOf("wght" to weight.toFloat())) as Any
            val m = Typeface::class.java.getMethod(
                "setFontVariationSettings",
                cls
            )
            val copy = Typeface.create(base, base.style)
            m.invoke(copy, settings)
            copy
        } catch (e: Throwable) {
            // Older platform, or a static font without a wght axis: keep bold/normal.
            if (weight >= 700) Typeface.create(base, Typeface.BOLD) else base
        }
    }

    /** Fonts that are actually readable on this device (asset missing => falls back). */
    fun usable(assets: AssetManager): List<Entry> = ALL.filter { entry ->
        if (entry.asset == null) return@filter true
        try {
            assets.open(entry.asset).use { it.read() }
            true
        } catch (e: Throwable) {
            false
        }
    }

    fun preWarm(assets: AssetManager) {
        for (e in ALL) {
            for (w in intArrayOf(400, 700)) resolve(assets, e.id, w)
        }
    }
}
