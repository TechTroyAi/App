package ai.techtroy.clockcanvas.data

import ai.techtroy.clockcanvas.BackgroundKind
import ai.techtroy.clockcanvas.ClockDesign
import ai.techtroy.clockcanvas.ClockGravity
import ai.techtroy.clockcanvas.ClockStyle
import ai.techtroy.clockcanvas.ContentMode
import ai.techtroy.clockcanvas.FontRegistry
import ai.techtroy.clockcanvas.GradientKind
import ai.techtroy.clockcanvas.OrientationMode
import ai.techtroy.clockcanvas.data.DesignCodec
import android.graphics.Color

/**
 * Five built-in designs, seeded on first launch so the app is never empty and
 * so widget/rendering behaviour can be tested without building a design first.
 * They are ordinary editable presets - delete them and they stay deleted.
 */
object SamplePresets {

    fun all(): List<ClockDesign> = listOf(midnightPurple(), minimalWhite(), neonDigital(), studyTimer(), editorialSerif())

    /** Used when a widget is bound to a design that no longer exists. */
    fun fallback(): ClockDesign = midnightPurple().copyWith { it.id = "" }

    fun midnightPurple(): ClockDesign = ClockDesign.Builder(null).apply {
        id = "sample-midnight-purple"
        name = "Midnight Purple"
        style = ClockStyle.DIGITAL
        content = ContentMode.TIME_DATE
        use24Hour = false
        fontId = FontRegistry.INTER
        weight = 700
        textScale = 1.0f
        letterSpacingEm = 0.01f
        timeColor = Color.parseColor("#F5F3FF")
        dateColor = Color.parseColor("#C084FC")
        glow = true
        glowColor = Color.parseColor("#7C3AED")
        glowBlurPx = 30f
        backgroundKind = BackgroundKind.GRADIENT
        gradientStart = Color.parseColor("#2A1650")
        gradientEnd = Color.parseColor("#09070F")
        gradientKind = GradientKind.LINEAR
        gradientAngle = 315f
        borderColor = Color.parseColor("#342750")
        borderWidth = 1.5f
        cornerRadius = 26f
        padding = 16f
        backgroundDarkness = 0.15f
    }.build()

    fun minimalWhite(): ClockDesign = ClockDesign.Builder(null).apply {
        id = "sample-minimal-white"
        name = "Minimal White"
        style = ClockStyle.MINIMAL
        content = ContentMode.TIME
        use24Hour = true
        fontId = FontRegistry.SPACE_GROTESK
        weight = 500
        textScale = 0.92f
        letterSpacingEm = 0.06f
        timeColor = Color.parseColor("#141019")
        dateColor = Color.parseColor("#6B5F80")
        backgroundKind = BackgroundKind.SOLID
        backgroundColor = Color.parseColor("#F5F3FF")
        borderColor = Color.parseColor("#DED6F0")
        borderWidth = 1f
        cornerRadius = 30f
        padding = 18f
        glow = false
        shadow = false
    }.build()

    fun neonDigital(): ClockDesign = ClockDesign.Builder(null).apply {
        id = "sample-neon-digital"
        name = "Neon Digital"
        style = ClockStyle.LARGE_NUMBER
        content = ContentMode.TIME
        use24Hour = true
        fontId = FontRegistry.BEBAS_NEUE
        weight = 400
        textScale = 1.45f
        letterSpacingEm = 0.08f
        timeColor = Color.parseColor("#F5F3FF")
        dateColor = Color.parseColor("#A855F7")
        outline = true
        outlineColor = Color.parseColor("#7C3AED")
        outlineWidth = 3f
        glow = true
        glowColor = Color.parseColor("#A855F7")
        glowBlurPx = 46f
        backgroundKind = BackgroundKind.SOLID
        backgroundColor = Color.parseColor("#09070F")
        cornerRadius = 18f
        borderWidth = 1f
        borderColor = Color.parseColor("#251C3D")
        padding = 12f
    }.build()

    fun studyTimer(): ClockDesign = ClockDesign.Builder(null).apply {
        id = "sample-study-timer"
        name = "Study Timer"
        style = ClockStyle.VERTICAL
        content = ContentMode.TIME_DATE_DAY
        orientation = OrientationMode.VERTICAL
        gravity = ClockGravity.CENTER_LEFT
        use24Hour = false
        fontId = FontRegistry.SOURCE_CODE_PRO
        dateFontId = FontRegistry.INTER
        weight = 600
        textScale = 1.05f
        letterSpacingEm = 0.02f
        timeColor = Color.parseColor("#34D399")
        dateColor = Color.parseColor("#B8AEC9")
        backgroundKind = BackgroundKind.GRADIENT
        gradientStart = Color.parseColor("#161222")
        gradientEnd = Color.parseColor("#100D18")
        gradientKind = GradientKind.RADIAL
        cornerRadius = 14f
        borderWidth = 1f
        borderColor = Color.parseColor("#342750")
        padding = 14f
        customText = "deep work"
    }.build()

    fun editorialSerif(): ClockDesign = ClockDesign.Builder(null).apply {
        id = "sample-editorial-serif"
        name = "Editorial Serif"
        style = ClockStyle.CUSTOM_TEXT
        content = ContentMode.TIME_DATE
        gravity = ClockGravity.BOTTOM_CENTER
        use24Hour = false
        fontId = FontRegistry.PLAYFAIR
        weight = 600
        textScale = 1.15f
        timeColor = Color.parseColor("#F5F3FF")
        dateColor = Color.parseColor("#B8AEC9")
        customText = "make it count"
        backgroundKind = BackgroundKind.GRADIENT
        gradientStart = Color.parseColor("#251C3D")
        gradientEnd = Color.parseColor("#09070F")
        gradientAngle = 200f
        cornerRadius = 0f
        borderWidth = 2f
        borderColor = Color.parseColor("#C084FC")
        padding = 20f
    }.build()
}
