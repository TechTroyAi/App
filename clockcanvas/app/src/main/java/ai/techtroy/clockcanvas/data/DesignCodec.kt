package ai.techtroy.clockcanvas.data

import ai.techtroy.clockcanvas.BackgroundKind
import ai.techtroy.clockcanvas.ClockDesign
import ai.techtroy.clockcanvas.ClockGravity
import ai.techtroy.clockcanvas.ClockStyle
import ai.techtroy.clockcanvas.ContentMode
import ai.techtroy.clockcanvas.GradientKind
import ai.techtroy.clockcanvas.OrientationMode
import ai.techtroy.clockcanvas.io.MiniJson

/**
 * Design <-> JSON. Field names are stable on purpose: they are the on-disk
 * format for `files/clockcanvas/designs/<id>.json`, the export/share payload and
 * the import payload, so a rename is a migration, not a refactor.
 */
object DesignCodec {

    const val SCHEMA = 1

    fun encode(d: ClockDesign): MiniJson.Obj {
        val o = MiniJson.Obj()
        o.put("schema", SCHEMA)
        o.put("id", d.id)
        o.put("name", d.name)
        o.put("style", d.style.name.lowercase())
        o.put("content", d.content.name.lowercase())
        o.put("orientation", d.orientation.name.lowercase())
        o.put("gravity", d.gravity.name.lowercase())
        if (d.use24Hour != null) o.put("hour24", d.use24Hour)
        o.put("suppressZeroHour", d.suppressLeadingZeroHour)
        o.put("font", d.fontId)
        if (d.dateFontId != null) o.put("dateFont", d.dateFontId)
        o.put("weight", d.weight)
        o.put("textScale", d.textScale)
        o.put("letterSpacing", d.letterSpacingEm)
        o.put("lineHeight", d.lineHeight)
        o.put("textAlign", d.textAlign)
        o.put("timeColor", d.timeColor)
        o.put("dateColor", d.dateColor)
        o.put("clockAlpha", d.clockAlpha)
        o.put("shadow", d.shadow)
        o.put("shadowColor", d.shadowColor)
        o.put("shadowBlur", d.shadowBlurPx)
        o.put("shadowDx", d.shadowDxPx)
        o.put("shadowDy", d.shadowDyPx)
        o.put("glow", d.glow)
        o.put("glowColor", d.glowColor)
        o.put("glowBlur", d.glowBlurPx)
        o.put("outline", d.outline)
        o.put("outlineColor", d.outlineColor)
        o.put("outlineWidth", d.outlineWidth)
        o.put("bg", d.backgroundKind.name.lowercase())
        o.put("bgColor", d.backgroundColor)
        o.put("gradStart", d.gradientStart)
        o.put("gradEnd", d.gradientEnd)
        o.put("gradKind", d.gradientKind.name.lowercase())
        o.put("gradAngle", d.gradientAngle)
        if (d.mediaUri != null) o.put("media", d.mediaUri)
        o.put("mediaIsVideo", d.mediaIsVideo)
        o.put("blur", d.backgroundBlur)
        o.put("darkness", d.backgroundDarkness)
        o.put("borderColor", d.borderColor)
        o.put("borderWidth", d.borderWidth)
        o.put("corner", d.cornerRadius)
        o.put("padding", d.padding)
        if (d.customText.isNotEmpty()) o.put("customText", d.customText)
        o.put("seconds", d.showSeconds)
        o.put("analogNumerals", d.analogNumerals)
        o.put("tapAction", d.tapAction)
        o.put("wallpaperClockOnly", d.wallpaperUseClockOnly)
        return o
    }

    fun encodeAll(items: List<ClockDesign>): String = MiniJson.writeList(items.map { encode(it) })

    /** Text of one encoded design; the on-disk and export format. */
    fun write(obj: MiniJson.Obj): String = MiniJson.write(obj)

    fun decode(o: MiniJson.Obj): ClockDesign {
        val b = ClockDesign.Builder(null)
        b.id = o.str("id")
        b.name = o.str("name", "Untitled clock")
        b.style = enumOr(o.strOrNull("style"), ClockStyle::class.java, ClockStyle.DIGITAL)
        b.content = enumOr(o.strOrNull("content"), ContentMode::class.java, ContentMode.TIME_DATE)
        b.orientation = enumOr(o.strOrNull("orientation"), OrientationMode::class.java, OrientationMode.HORIZONTAL)
        b.gravity = enumOr(o.strOrNull("gravity"), ClockGravity::class.java, ClockGravity.CENTER)
        b.use24Hour = if (o.has("hour24")) o.bool("hour24", false) else null
        b.suppressLeadingZeroHour = o.bool("suppressZeroHour", true)
        b.fontId = o.str("font", "inter")
        b.dateFontId = o.strOrNull("dateFont")
        b.weight = o.int("weight", 700)
        b.textScale = o.float("textScale", 1f).coerceIn(0.6f, 1.8f)
        b.letterSpacingEm = o.float("letterSpacing", 0f).coerceIn(-0.1f, 0.5f)
        b.lineHeight = o.float("lineHeight", 1.12f).coerceIn(0.8f, 2.0f)
        b.textAlign = o.int("textAlign", ClockDesign.TEXT_ALIGN_CENTER).let { if (it in 0..2) it else ClockDesign.TEXT_ALIGN_CENTER }
        b.timeColor = o.int("timeColor", b.timeColor)
        b.dateColor = o.int("dateColor", b.dateColor)
        b.clockAlpha = o.float("clockAlpha", 1f).coerceIn(0.05f, 1f)
        b.shadow = o.bool("shadow", false)
        b.shadowColor = o.int("shadowColor", b.shadowColor)
        b.shadowBlurPx = o.float("shadowBlur", 18f).coerceIn(0f, 80f)
        b.shadowDxPx = o.float("shadowDx", 0f)
        b.shadowDyPx = o.float("shadowDy", 6f)
        b.glow = o.bool("glow", false)
        b.glowColor = o.int("glowColor", b.glowColor)
        b.glowBlurPx = o.float("glowBlur", 26f).coerceIn(2f, 120f)
        b.outline = o.bool("outline", false)
        b.outlineColor = o.int("outlineColor", b.outlineColor)
        b.outlineWidth = o.float("outlineWidth", 2f).coerceIn(0f, 12f)
        b.backgroundKind = enumOr(o.strOrNull("bg"), BackgroundKind::class.java, BackgroundKind.GRADIENT)
        b.backgroundColor = o.int("bgColor", b.backgroundColor)
        b.gradientStart = o.int("gradStart", b.gradientStart)
        b.gradientEnd = o.int("gradEnd", b.gradientEnd)
        b.gradientKind = enumOr(o.strOrNull("gradKind"), GradientKind::class.java, GradientKind.LINEAR)
        b.gradientAngle = o.float("gradAngle", 315f)
        b.mediaUri = o.strOrNull("media")
        b.mediaIsVideo = o.bool("mediaIsVideo", false)
        b.backgroundBlur = o.float("blur", 0f).coerceIn(0f, 40f)
        b.backgroundDarkness = o.float("darkness", 0.22f).coerceIn(0f, 0.9f)
        b.borderColor = o.int("borderColor", b.borderColor)
        b.borderWidth = o.float("borderWidth", 1.5f).coerceIn(0f, 12f)
        b.cornerRadius = o.float("corner", 22f).coerceIn(0f, 120f)
        b.padding = o.float("padding", 14f).coerceIn(0f, 64f)
        b.customText = o.str("customText", "")
        b.showSeconds = o.bool("seconds", false)
        b.analogNumerals = o.bool("analogNumerals", false)
        b.tapAction = o.int("tapAction", ClockDesign.TAP_FULLSCREEN).let { if (it in 0..2) it else ClockDesign.TAP_FULLSCREEN }
        b.wallpaperUseClockOnly = o.bool("wallpaperClockOnly", false)
        return b.build()
    }

    fun decodeList(text: String?): List<ClockDesign> = MiniJson.parseList(text).mapNotNull {
        try {
            decode(it)
        } catch (e: Throwable) {
            null
        }
    }

    private fun <E : Enum<E>> enumOr(name: String?, cls: Class<E>, fallback: E): E {
        if (name.isNullOrEmpty()) return fallback
        return try {
            java.lang.Enum.valueOf(cls, name.uppercase())
        } catch (e: Throwable) {
            fallback
        }
    }
}
