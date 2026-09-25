package ai.techtroy.clockcanvas

/**
 * Every value type the app shares: clock designs, widget bindings, background
 * media references and app settings.
 *
 * Design rules baked into these types (and why):
 *
 *  - [ClockDesign] is immutable and copied with [ClockDesign.copy]. The editor
 *    keeps one mutable working copy and pushes it through the renderer.
 *  - Colors are plain ARGB ints rather than resource ids because designs are
 *    built by the user at runtime and stored as data, not as resources.
 *  - Background media is stored as a `content://` URI string captured from the
 *    system Photo Picker together with a persistable read grant (see
 *    `media/MediaAccess.kt`). No storage permissions, no file paths.
 *  - Sizes are device-independent pixels (`dp`) / scaled pixels (`sp`) so a
 *    design survives a move between densities; the renderer converts once.
 */

/** What the clock renders. */
enum class ClockStyle {
    DIGITAL,
    LARGE_NUMBER,
    MINIMAL,
    ANALOG,
    SPLIT,
    VERTICAL,
    CUSTOM_TEXT,
}

/** How much information is shown when the widget is big enough. */
enum class ContentMode {
    /** Time only, at every size. */
    TIME,

    /** Time + date. */
    TIME_DATE,

    /** Time + weekday + date. */
    TIME_DATE_DAY,

    /** Time + weekday, no date number. */
    TIME_DAY,
}

/** Where the widget may put the clock block inside its own bounds. */
enum class ClockGravity {
    TOP_LEFT,
    TOP_CENTER,
    TOP_RIGHT,
    CENTER_LEFT,
    CENTER,
    CENTER_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_CENTER,
    BOTTOM_RIGHT,
}

/** Layout of the clock block. */
enum class OrientationMode {
    HORIZONTAL,
    VERTICAL,
}

/**
 * Sizing bucket derived from the geometry the launcher handed us. The launcher
 * owns the real size - we only classify it so a small cell can drop the date
 * and a wide cell can switch to a horizontal layout.
 */
enum class WidgetBucket {
    SMALL,
    MEDIUM,
    WIDE,
    TALL,
    LARGE,
    HUGE;

    val showsDate: Boolean get() = this != SMALL
    val showsDay: Boolean get() = this == LARGE || this == HUGE || this == TALL
    val isCompact: Boolean get() = this == SMALL
}

/** What sits behind the clock. */
enum class BackgroundKind {
    TRANSPARENT,
    SOLID,
    GRADIENT,
    IMAGE,
    IMAGE_BLURRED,
    IMAGE_DARKENED,
    VIDEO_THUMBNAIL,
}

/** Gradient shape for [BackgroundKind.GRADIENT]. */
enum class GradientKind {
    LINEAR,
    RADIAL,
    SWEEP,
}

/**
 * A complete clock design. Everything a widget instance needs to draw itself is
 * either in here or referenced from here.
 */
data class ClockDesign(
    val id: String,
    val name: String,
    val style: ClockStyle = ClockStyle.DIGITAL,
    val content: ContentMode = ContentMode.TIME_DATE,
    val orientation: OrientationMode = OrientationMode.HORIZONTAL,
    val gravity: ClockGravity = ClockGravity.CENTER,
    /** null = follow the system 12/24-hour setting from [ClockSettings]. */
    val use24Hour: Boolean? = null,
    val suppressLeadingZeroHour: Boolean = true,
    val fontId: String = FontRegistry.INTER,
    val dateFontId: String? = null,
    val weight: Int = 700,
    /** User multiplier on the automatically computed size, 0.60 - 1.80. */
    val textScale: Float = 1.0f,
    /** 1.0 means "no manual letter spacing tweak". */
    val letterSpacingEm: Float = 0.0f,
    val lineHeight: Float = 1.12f,
    val textAlign: Int = TEXT_ALIGN_CENTER,
    val timeColor: Int = 0xFFF5F3FF.toInt(),
    val dateColor: Int = 0xFFC084FC.toInt(),
    val clockAlpha: Float = 1.0f,
    val shadow: Boolean = false,
    val shadowColor: Int = 0xB3000000.toInt(),
    val shadowBlurPx: Float = 18f,
    val shadowDxPx: Float = 0f,
    val shadowDyPx: Float = 6f,
    val glow: Boolean = false,
    val glowColor: Int = 0xFF7C3AED.toInt(),
    val glowBlurPx: Float = 26f,
    val outline: Boolean = false,
    val outlineColor: Int = 0xFF09070F.toInt(),
    /** dp */
    val outlineWidth: Float = 2f,
    val backgroundKind: BackgroundKind = BackgroundKind.GRADIENT,
    val backgroundColor: Int = 0xFF161222.toInt(),
    val gradientStart: Int = 0xFF1D1730.toInt(),
    val gradientEnd: Int = 0xFF09070F.toInt(),
    val gradientKind: GradientKind = GradientKind.LINEAR,
    /** degrees, 0 = bottom-to-top, like android:angle. */
    val gradientAngle: Float = 315f,
    val mediaUri: String? = null,
    val mediaIsVideo: Boolean = false,
    /** dp */
    val backgroundBlur: Float = 0f,
    /** 0 - 0.85 */
    val backgroundDarkness: Float = 0.22f,
    val borderColor: Int = 0xFF342750.toInt(),
    /** dp, 0 disables the border */
    val borderWidth: Float = 1.5f,
    /** dp */
    val cornerRadius: Float = 22f,
    /** dp */
    val padding: Float = 14f,
    val customText: String = "",
    val showSeconds: Boolean = false,
    val analogNumerals: Boolean = false,
    val tapAction: Int = TAP_FULLSCREEN,
    /** Wallpapers use a lighter render so the whole screen stays cheap. */
    val wallpaperUseClockOnly: Boolean = false,
) {
    fun copyWith(transform: (Builder) -> Unit): ClockDesign {
        val b = Builder(this)
        transform(b)
        return b.build()
    }

    /** Mutable mirror of [ClockDesign] used by the editor and JSON decoding. */
    class Builder(source: ClockDesign? = null) {
        var id: String = source?.id ?: ""
        var name: String = source?.name ?: "Untitled clock"
        var style: ClockStyle = source?.style ?: ClockStyle.DIGITAL
        var content: ContentMode = source?.content ?: ContentMode.TIME_DATE
        var orientation: OrientationMode = source?.orientation ?: OrientationMode.HORIZONTAL
        var gravity: ClockGravity = source?.gravity ?: ClockGravity.CENTER
        var use24Hour: Boolean? = source?.use24Hour
        var suppressLeadingZeroHour: Boolean = source?.suppressLeadingZeroHour ?: true
        var fontId: String = source?.fontId ?: FontRegistry.INTER
        var dateFontId: String? = source?.dateFontId
        var weight: Int = source?.weight ?: 700
        var textScale: Float = source?.textScale ?: 1f
        var letterSpacingEm: Float = source?.letterSpacingEm ?: 0f
        var lineHeight: Float = source?.lineHeight ?: 1.12f
        var textAlign: Int = source?.textAlign ?: TEXT_ALIGN_CENTER
        var timeColor: Int = source?.timeColor ?: 0xFFF5F3FF.toInt()
        var dateColor: Int = source?.dateColor ?: 0xFFC084FC.toInt()
        var clockAlpha: Float = source?.clockAlpha ?: 1f
        var shadow: Boolean = source?.shadow ?: false
        var shadowColor: Int = source?.shadowColor ?: 0xB3000000.toInt()
        var shadowBlurPx: Float = source?.shadowBlurPx ?: 18f
        var shadowDxPx: Float = source?.shadowDxPx ?: 0f
        var shadowDyPx: Float = source?.shadowDyPx ?: 6f
        var glow: Boolean = source?.glow ?: false
        var glowColor: Int = source?.glowColor ?: 0xFF7C3AED.toInt()
        var glowBlurPx: Float = source?.glowBlurPx ?: 26f
        var outline: Boolean = source?.outline ?: false
        var outlineColor: Int = source?.outlineColor ?: 0xFF09070F.toInt()
        var outlineWidth: Float = source?.outlineWidth ?: 2f
        var backgroundKind: BackgroundKind = source?.backgroundKind ?: BackgroundKind.GRADIENT
        var backgroundColor: Int = source?.backgroundColor ?: 0xFF161222.toInt()
        var gradientStart: Int = source?.gradientStart ?: 0xFF1D1730.toInt()
        var gradientEnd: Int = source?.gradientEnd ?: 0xFF09070F.toInt()
        var gradientKind: GradientKind = source?.gradientKind ?: GradientKind.LINEAR
        var gradientAngle: Float = source?.gradientAngle ?: 315f
        var mediaUri: String? = source?.mediaUri
        var mediaIsVideo: Boolean = source?.mediaIsVideo ?: false
        var backgroundBlur: Float = source?.backgroundBlur ?: 0f
        var backgroundDarkness: Float = source?.backgroundDarkness ?: 0.22f
        var borderColor: Int = source?.borderColor ?: 0xFF342750.toInt()
        var borderWidth: Float = source?.borderWidth ?: 1.5f
        var cornerRadius: Float = source?.cornerRadius ?: 22f
        var padding: Float = source?.padding ?: 14f
        var customText: String = source?.customText ?: ""
        var showSeconds: Boolean = source?.showSeconds ?: false
        var analogNumerals: Boolean = source?.analogNumerals ?: false
        var tapAction: Int = source?.tapAction ?: TAP_FULLSCREEN
        var wallpaperUseClockOnly: Boolean = source?.wallpaperUseClockOnly ?: false

        fun build() = ClockDesign(
            id = id, name = name, style = style, content = content,
            orientation = orientation, gravity = gravity, use24Hour = use24Hour,
            suppressLeadingZeroHour = suppressLeadingZeroHour, fontId = fontId,
            dateFontId = dateFontId, weight = weight, textScale = textScale,
            letterSpacingEm = letterSpacingEm, lineHeight = lineHeight,
            textAlign = textAlign, timeColor = timeColor, dateColor = dateColor,
            clockAlpha = clockAlpha, shadow = shadow, shadowColor = shadowColor,
            shadowBlurPx = shadowBlurPx, shadowDxPx = shadowDxPx, shadowDyPx = shadowDyPx,
            glow = glow, glowColor = glowColor, glowBlurPx = glowBlurPx,
            outline = outline, outlineColor = outlineColor, outlineWidth = outlineWidth,
            backgroundKind = backgroundKind, backgroundColor = backgroundColor,
            gradientStart = gradientStart, gradientEnd = gradientEnd,
            gradientKind = gradientKind, gradientAngle = gradientAngle,
            mediaUri = mediaUri, mediaIsVideo = mediaIsVideo,
            backgroundBlur = backgroundBlur, backgroundDarkness = backgroundDarkness,
            borderColor = borderColor, borderWidth = borderWidth,
            cornerRadius = cornerRadius, padding = padding, customText = customText,
            showSeconds = showSeconds, analogNumerals = analogNumerals,
            tapAction = tapAction, wallpaperUseClockOnly = wallpaperUseClockOnly,
        )
    }

    companion object {
        const val TEXT_ALIGN_LEFT = 0
        const val TEXT_ALIGN_CENTER = 1
        const val TEXT_ALIGN_RIGHT = 2

        const val TAP_FULLSCREEN = 0
        const val TAP_EDITOR = 1
        const val TAP_NONE = 2
    }
}

/** App-wide preferences (defaults for new designs, not per-widget rendering data). */
data class ClockSettings(
    val use24Hour: Boolean? = null,
    val dateFormat: String = "EEEE, MMMM d",
    val firstDayOfWeekSunday: Boolean = false,
    val preciseRefresh: Boolean = false,
    val rememberLastDesign: Boolean = true,
    val lastDesignId: String = "",
) {
    companion object {
        val DATE_FORMAT_PRESETS = listOf(
            "EEEE, MMMM d" to "Friday, September 25",
            "MMM d" to "Sep 25",
            "d MMM yyyy" to "25 Sep 2026",
            "MM/dd/yy" to "09/25/26",
            "d.M.yy" to "25.9.26",
            "EEEE\nd MMMM" to "Friday (newline) 25 September",
        )
    }
}

/**
 * A media entry as the user sees it in "Recent backgrounds".
 *
 * `uri` is the persistable grant target; [missing] is set when the file has been
 * deleted or moved, and the renderer then falls back to the color/gradient path
 * instead of crashing.
 */
data class BackgroundMedia(
    val uri: String,
    val isVideo: Boolean,
    val label: String = "",
    val addedAt: Long = 0L,
)

/** Per-installed-widget record: which design id this appWidgetId is bound to. */
data class WidgetConfiguration(
    val appWidgetId: Int,
    val designId: String,
    val hostPackage: String? = null,
    val configured: Boolean = false,
    val lastBucketWidthDp: Int = 0,
    val lastBucketHeightDp: Int = 0,
) {
    companion object {
        fun bindingLabel(id: Int, designName: String, widthDp: Int, heightDp: Int): String {
            val size = if (widthDp > 0 && heightDp > 0) "${widthDp}×${heightDp}dp" else "size unknown"
            return "#$id · $designName · $size"
        }
    }
}
