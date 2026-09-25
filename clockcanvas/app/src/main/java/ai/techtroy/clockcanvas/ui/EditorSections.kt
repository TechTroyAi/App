package ai.techtroy.clockcanvas.ui

import ai.techtroy.clockcanvas.BackgroundKind
import ai.techtroy.clockcanvas.ClockDesign
import ai.techtroy.clockcanvas.ClockGravity
import ai.techtroy.clockcanvas.ClockStyle
import ai.techtroy.clockcanvas.ContentMode
import ai.techtroy.clockcanvas.FontRegistry
import ai.techtroy.clockcanvas.GradientKind
import ai.techtroy.clockcanvas.OrientationMode
import ai.techtroy.clockcanvas.R
import ai.techtroy.clockcanvas.WidgetBucket
import ai.techtroy.clockcanvas.media.MediaHandler
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Every form section of the clock editor, in one file, because all six sections
 * edit the same value object and share the same controls (segmented pickers,
 * sliders, colour dots). [EditorActivity] and [WidgetConfigActivity] both mount
 * these - that is the whole reason a widget can be reconfigured from inside the
 * launcher without a second, slightly-different copy of the UI.
 */
object EditorSections {

    // ---- generic controls ---------------------------------------------

    fun sectionCard(context: Context, title: String, hint: String?): LinearLayout {
        val card = Ui.card(context)
        Ui.add(card, Ui.title(context, title), 0f)
        if (hint != null) Ui.add(card, Ui.caption(context, hint), 4f)
        return card
    }

    /**
     * A segmented choice row. The label of the selected option gets a checkmark as
     * well as the violet fill, so state is readable without colour perception.
     */
    fun segmented(
        context: Context,
        title: String,
        options: List<String>,
        selectedIndex: () -> Int,
        hint: String? = null,
        onSelect: (Int) -> Unit,
    ): LinearLayout {
        val card = sectionCard(context, title, hint)
        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        for ((index, label) in options.withIndex()) {
            val chip = Ui.chip(context, label, selectedIndex() == index) {
                onSelect(index)
            }
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            if (index > 0) params.leftMargin = Ui.dp(context, 6f)
            chip.maxLines = 2
            row.addView(chip, params)
        }
        Ui.add(card, row, 10f)
        return card
    }

    fun sliderCard(
        context: Context,
        title: String,
        value: Float,
        min: Float,
        max: Float,
        step: Float,
        suffix: String,
        onValue: (Float) -> Unit,
    ): LinearLayout {
        val card = Ui.card(context)
        val steps = (((max - min) / step)).roundToInt().coerceAtLeast(1)
        val index = (((value - min) / step)).roundToInt().coerceIn(0, steps)
        val row = Ui.slider(
            context = context,
            label = title,
            valueTenths = index,
            minTenths = 0,
            maxTenths = steps,
            scale = 1,
            suffix = "",
        ) { raw ->
            val mapped = min + raw * step
            onValue(mapped)
        }
        row.valueLabel.text = formatValue(value, suffix)
        Ui.add(card, row, 0f)
        return card
    }

    private fun formatValue(value: Float, suffix: String): String {
        val text = String.format(java.util.Locale.US, "%.2f", value)
        var end = text.length
        while (end > 0 && text[end - 1] == '0') end--
        if (end > 0 && text[end - 1] == '.') end--
        return text.substring(0, end) + suffix
    }

    fun toggleCard(context: Context, title: String, hint: String?, value: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        val card = Ui.card(context)
        Ui.add(card, Ui.toggle(context, title, hint, value, onChange), 0f)
        return card
    }

    fun colorCard(
        context: Context,
        title: String,
        value: Int,
        allowTransparent: Boolean,
        onColor: (Int) -> Unit,
    ): LinearLayout {
        val swatches = if (allowTransparent) intArrayOf(Ui.TRANSPARENT) + Ui.PALETTE else Ui.PALETTE
        return Ui.colorRow(context, title, value, swatches, onColor) {
            promptColor(context, value) { picked -> onColor(picked) }
        }
    }

    private fun promptColor(context: Context, current: Int, onPicked: (Int) -> Unit) {
        val field = EditText(context)
        field.setSingleLine(true)
        field.setText(String.format(java.util.Locale.US, "#%08X", current))
        field.setTextColor(Color.WHITE)
        val wrapper = LinearLayout(context)
        val pad = Ui.dp(context, 18f)
        wrapper.setPadding(pad, pad, pad, 0)
        wrapper.addView(field)
        AlertDialog.Builder(context)
            .setTitle("Colour (hex)")
            .setMessage("8 digits = AARRGGBB, e.g. CC7C3AED")
            .setView(wrapper)
            .setPositiveButton(R.string.action_ok) { _, _ ->
                val parsed = Ui.parseColor(field.text.toString())
                if (parsed == null) {
                    try {
                        android.widget.Toast.makeText(context, "Not a colour code", android.widget.Toast.LENGTH_SHORT).show()
                    } catch (e: Throwable) {
                    }
                } else {
                    onPicked(parsed)
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    fun textCard(
        context: Context,
        title: String,
        value: String,
        singleLine: Boolean,
        hint: String?,
        onCommit: (String) -> Unit,
    ): LinearLayout {
        val card = sectionCard(context, title, hint)
        val field = Ui.editText(context, value, singleLine) { onCommit(it) }
        // Android gives no callback for "user stopped typing"; losing focus or
        // pressing Done is the moment we commit, so a half-typed name never
        // silently lands in the saved design.
        field.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) onCommit(field.text.toString()) }
        Ui.add(card, field, 10f)
        return card
    }

    // ---- Design --------------------------------------------------------

    fun buildDesign(host: EditorHost, container: LinearLayout) {
        val context = host.editorContext
        val binding = host.binding
        val names = ClockStyle.values().map { label(it) }
        container.addView(
            segmented(
                context = context,
                title = "Clock style",
                options = names,
                selectedIndex = { ClockStyle.values().indexOf(binding.design.style) },
                hint = "Analog and large-number designs rasterise the whole face; digital designs keep crisp text at any size.",
            ) { index ->
                binding.structural { it.style = ClockStyle.values()[index] }
            },
            Ui.cardParams(context)
        )
        container.addView(
            segmented(
                context = context,
                title = "Information",
                options = listOf("Time", "Time + date", "Time + day + date", "Time + day"),
                selectedIndex = { ContentMode.values().indexOf(binding.design.content) },
                hint = "Small cells always fall back to time only, whatever you choose here.",
            ) { index ->
                binding.update { it.content = ContentMode.values()[index] }
            },
            Ui.cardParams(context)
        )
        container.addView(
            segmented(
                context = context,
                title = "Hour format",
                options = listOf("System", "12-hour", "24-hour"),
                selectedIndex = {
                    when (binding.design.use24Hour) {
                        null -> 0
                        false -> 1
                        true -> 2
                    }
                },
            ) { index ->
                binding.update { it.use24Hour = when (index) { 0 -> null; 1 -> false; else -> true } }
            },
            Ui.cardParams(context)
        )
        container.addView(
            toggleCard(
                context,
                "Show seconds",
                "Only stays exact if the widget ticker is enabled in Settings; otherwise seconds refresh with the minute.",
                binding.design.showSeconds,
            ) { value -> binding.update { it.showSeconds = value } },
            Ui.cardParams(context)
        )
        container.addView(
            toggleCard(
                context,
                "Drop the leading zero on the hour",
                "9:05 rather than 09:05.",
                binding.design.suppressLeadingZeroHour,
            ) { value -> binding.update { it.suppressLeadingZeroHour = value } },
            Ui.cardParams(context)
        )
        if (binding.design.style == ClockStyle.ANALOG) {
            container.addView(
                toggleCard(context, "Numerals on the dial", "Draws 1-12 around the ring.", binding.design.analogNumerals) { value ->
                    binding.update { it.analogNumerals = value }
                },
                Ui.cardParams(context)
            )
        }
        if (binding.design.style == ClockStyle.CUSTOM_TEXT) {
            container.addView(
                textCard(context, "Custom text", binding.design.customText, false, "Shown above the time. Keep it short.") { value ->
                    binding.update { it.customText = value }
                },
                Ui.cardParams(context)
            )
        }
        container.addView(
            textCard(context, "Design name", binding.design.name, true, null) { value ->
                binding.update { it.name = value.ifBlank { "Untitled clock" } }
            },
            Ui.cardParams(context)
        )
    }

    private fun label(style: ClockStyle): String = when (style) {
        ClockStyle.DIGITAL -> "Digital"
        ClockStyle.LARGE_NUMBER -> "Large number"
        ClockStyle.MINIMAL -> "Minimal"
        ClockStyle.ANALOG -> "Analog"
        ClockStyle.SPLIT -> "Split"
        ClockStyle.VERTICAL -> "Vertical"
        ClockStyle.CUSTOM_TEXT -> "Custom text"
    }

    // ---- Text --------------------------------------------------------

    fun buildText(host: EditorHost, container: LinearLayout) {
        val context = host.editorContext
        val binding = host.binding
        val fonts = FontRegistry.usable(context.resources.assets)
        val fontCard = sectionCard(context, "Clock font", "Rendered in the typeface itself so the row is the sample.")
        for (entry in fonts) {
            val selected = binding.design.fontId == entry.id
            val chip = Ui.chip(context, entry.label, selected) {
                binding.structural { it.fontId = entry.id }
            }
            chip.typeface = FontRegistry.resolve(context.resources.assets, entry.id, if (selected) 700 else 500)
            chip.gravity = Gravity.CENTER_VERTICAL or Gravity.START
            chip.text = (if (selected) "✓  " else "○  ") + entry.label + "   12:45"
            chip.setTextColor(if (selected) Color.WHITE else Ui.color(context, R.color.text_secondary))
            Ui.add(fontCard, chip, if (fonts.indexOf(entry) == 0) 6f else 8f)
        }
        Ui.add(fontCard, Ui.caption(context, fonts.joinToString(" · ") { it.note }), 10f)
        container.addView(fontCard, Ui.cardParams(context))

        container.addView(
            segmented(
                context = context,
                title = "Weight",
                options = FontRegistry.WEIGHTS.map { it.toString() },
                selectedIndex = { FontRegistry.WEIGHTS.indexOf(binding.design.weight).coerceAtLeast(1) },
                hint = "Applied through the variable-weight axis of the bundled fonts.",
            ) { index ->
                binding.update { it.weight = FontRegistry.WEIGHTS[index] }
            },
            Ui.cardParams(context)
        )
        container.addView(
            sliderCard(context, "Text size", binding.design.textScale, 0.6f, 1.8f, 0.02f, "×") { value ->
                binding.update { it.textScale = value }
            },
            Ui.cardParams(context)
        )
        container.addView(
            sliderCard(context, "Letter spacing", binding.design.letterSpacingEm, -0.05f, 0.4f, 0.01f, " em") { value ->
                binding.update { it.letterSpacingEm = value }
            },
            Ui.cardParams(context)
        )
        container.addView(
            sliderCard(context, "Line spacing", binding.design.lineHeight, 0.85f, 2.0f, 0.01f, "×") { value ->
                binding.update { it.lineHeight = value }
            },
            Ui.cardParams(context)
        )
        container.addView(
            segmented(
                context = context,
                title = "Text alignment",
                options = listOf("Left", "Centre", "Right"),
                selectedIndex = { binding.design.textAlign },
            ) { index ->
                binding.update { it.textAlign = index }
            },
            Ui.cardParams(context)
        )
        container.addView(
            colorCard(context, "Time colour", binding.design.timeColor, false) { value ->
                binding.update { it.timeColor = value }
            },
            Ui.cardParams(context)
        )
        container.addView(
            colorCard(context, "Date colour", binding.design.dateColor, false) { value ->
                binding.update { it.dateColor = value }
            },
            Ui.cardParams(context)
        )
        container.addView(
            sliderCard(context, "Clock opacity", binding.design.clockAlpha, 0.1f, 1.0f, 0.01f, "") { value ->
                binding.update { it.clockAlpha = value }
            },
            Ui.cardParams(context)
        )
        container.addView(
            toggleCard(context, "Outline", "Draws an edge around every glyph - the reliable way to keep a clock legible over a photo.", binding.design.outline) { value ->
                binding.structural { it.outline = value }
            },
            Ui.cardParams(context)
        )
        if (binding.design.outline) {
            container.addView(
                colorCard(context, "Outline colour", binding.design.outlineColor, false) { value ->
                    binding.update { it.outlineColor = value }
                },
                Ui.cardParams(context)
            )
            container.addView(
                sliderCard(context, "Outline thickness", binding.design.outlineWidth, 0f, 12f, 0.25f, "dp") { value ->
                    binding.update { it.outlineWidth = value }
                },
                Ui.cardParams(context)
            )
        }
        container.addView(
            toggleCard(context, "Glow", "A soft halo behind the digits.", binding.design.glow) { value ->
                binding.update { it.glow = value }
            },
            Ui.cardParams(context)
        )
        if (binding.design.glow) {
            container.addView(
                colorCard(context, "Glow colour", binding.design.glowColor, false) { value ->
                    binding.update { it.glowColor = value }
                },
                Ui.cardParams(context)
            )
            container.addView(
                sliderCard(context, "Glow radius", binding.design.glowBlurPx, 4f, 120f, 1f, "px") { value ->
                    binding.update { it.glowBlurPx = value }
                },
                Ui.cardParams(context)
            )
        }
        container.addView(
            toggleCard(context, "Drop shadow", null, binding.design.shadow) { value ->
                binding.update { it.shadow = value }
            },
            Ui.cardParams(context)
        )
        if (binding.design.shadow) {
            container.addView(
                sliderCard(context, "Shadow blur", binding.design.shadowBlurPx, 0f, 80f, 1f, "px") { value ->
                    binding.update { it.shadowBlurPx = value }
                },
                Ui.cardParams(context)
            )
            container.addView(
                Ui.columnRow(context).apply {
                    addView(
                        sliderCard(context, "Shadow X", binding.design.shadowDxPx, -30f, 30f, 1f, "px") { value ->
                            binding.update { it.shadowDxPx = value }
                        },
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    )
                    addView(
                        sliderCard(context, "Shadow Y", binding.design.shadowDyPx, -30f, 30f, 1f, "px") { value ->
                            binding.update { it.shadowDyPx = value }
                        },
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                            leftMargin = Ui.dp(context, 8f)
                        }
                    )
                },
                Ui.cardParams(context)
            )
        }
    }

    // ---- Background ----------------------------------------------------

    fun buildBackground(host: EditorHost, container: LinearLayout) {
        val context = host.editorContext
        val binding = host.binding
        val kinds = listOf(
            "Transparent", "Solid", "Gradient", "Photo", "Photo blurred", "Photo dimmed", "Video frame"
        )
        val currentKind = binding.design.backgroundKind
        container.addView(
            segmented(
                context = context,
                title = "Background",
                options = kinds,
                selectedIndex = { kinds.indexOf(kindLabel(currentKind)) },
                hint = "Photo and video come from the system Photo Picker - no storage permission is requested.",
            ) { index ->
                binding.structural { it.backgroundKind = kindFromLabel(kinds[index]) }
            },
            Ui.cardParams(context)
        )

        val mediaCard = sectionCard(context, "Media", null)
        val buttons = Ui.columnRow(context)
        buttons.addView(Ui.ghostButton(context, context.getString(R.string.bg_photo)) { host.pickImage() },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(Ui.ghostButton(context, context.getString(R.string.bg_video)) { host.pickVideo() },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = Ui.dp(context, 8f) })
        buttons.addView(Ui.ghostButton(context, context.getString(R.string.bg_camera)) { host.takePhoto() },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = Ui.dp(context, 8f) })
        Ui.add(mediaCard, buttons, 8f)

        val mediaRow = Ui.columnRow(context)
        val thumb = ImageView(context)
        thumb.scaleType = ImageView.ScaleType.CENTER_CROP
        thumb.setBackgroundResource(R.drawable.preview_tile)
        host.loadPosterInto(thumb)
        mediaRow.addView(thumb, LinearLayout.LayoutParams(Ui.dp(context, 76f), Ui.dp(context, 76f)))
        val mediaColumn = Ui.column(context)
        val uri = binding.design.mediaUri
        mediaColumn.addView(Ui.text(context, if (uri == null) "No media selected" else labelFor(uri), 13f, Ui.color(context, R.color.text_primary), true))
        mediaColumn.addView(Ui.text(context, if (uri == null) "" else if (binding.design.mediaIsVideo) "Video · widget shows a frame" else "Photo", 12f, Ui.color(context, R.color.text_muted)))
        mediaRow.addView(mediaColumn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = Ui.dp(context, 12f)
        })
        if (uri != null) {
            mediaRow.addView(Ui.chip(context, context.getString(R.string.bg_clear), false) {
                host.clearMedia()
            })
        }
        Ui.add(mediaCard, mediaRow, 12f)

        val recents = MediaHandler.of(context).recentRows()
        if (recents.isNotEmpty()) {
            Ui.add(mediaCard, Ui.caption(context, context.getString(R.string.bg_recent)), 12f)
            val recentRow = LinearLayout(context)
            recentRow.orientation = LinearLayout.HORIZONTAL
            for (recent in recents.take(6)) {
                val dot = ImageView(context)
                dot.scaleType = ImageView.ScaleType.CENTER_CROP
                dot.setBackgroundResource(R.drawable.preview_tile)
                val bitmap = MediaHandler.of(context).resolve(recent.uri)
                if (bitmap != null) dot.setImageBitmap(bitmap)
                dot.contentDescription = if (recent.isVideo) "Recent video" else "Recent photo"
                dot.setOnClickListener {
                    host.setMedia(recent.uri, recent.isVideo)
                }
                recentRow.addView(dot, LinearLayout.LayoutParams(Ui.dp(context, 54f), Ui.dp(context, 54f)).apply {
                    rightMargin = Ui.dp(context, 8f)
                })
            }
            Ui.add(mediaCard, recentRow, 8f)
        }
        container.addView(mediaCard, Ui.cardParams(context))

        if (currentKind == BackgroundKind.VIDEO_THUMBNAIL) {
            container.addView(
                Ui.card(context).apply {
                    Ui.add(this, Ui.caption(context, context.getString(R.string.bg_video_widget_note)), 0f)
                },
                Ui.cardParams(context)
            )
        }

        when (currentKind) {
            BackgroundKind.SOLID -> container.addView(
                colorCard(context, "Background colour", binding.design.backgroundColor, true) { value ->
                    binding.update { it.backgroundColor = value }
                },
                Ui.cardParams(context)
            )
            BackgroundKind.GRADIENT -> {
                container.addView(
                    colorCard(context, "Gradient start", binding.design.gradientStart, false) { value ->
                        binding.update { it.gradientStart = value }
                    },
                    Ui.cardParams(context)
                )
                container.addView(
                    colorCard(context, "Gradient end", binding.design.gradientEnd, false) { value ->
                        binding.update { it.gradientEnd = value }
                    },
                    Ui.cardParams(context)
                )
                container.addView(
                    segmented(
                        context = context,
                        title = "Gradient shape",
                        options = listOf("Linear", "Radial", "Sweep"),
                        selectedIndex = { GradientKind.values().indexOf(binding.design.gradientKind) },
                    ) { index ->
                        binding.update { it.gradientKind = GradientKind.values()[index] }
                    },
                    Ui.cardParams(context)
                )
                if (binding.design.gradientKind == GradientKind.LINEAR) {
                    container.addView(
                        sliderCard(context, "Gradient angle", binding.design.gradientAngle, 0f, 360f, 1f, "°") { value ->
                            binding.update { it.gradientAngle = value }
                        },
                        Ui.cardParams(context)
                    )
                }
            }
            else -> if (uri != null) {
                container.addView(
                    sliderCard(context, "Background blur", binding.design.backgroundBlur, 0f, 40f, 0.5f, "dp") { value ->
                        binding.update { it.backgroundBlur = value }
                    },
                    Ui.cardParams(context)
                )
                container.addView(
                    sliderCard(context, "Background darkness", binding.design.backgroundDarkness, 0f, 0.9f, 0.01f, "") { value ->
                        binding.update { it.backgroundDarkness = value }
                    },
                    Ui.cardParams(context)
                )
            }
        }
    }

    private fun kindLabel(kind: BackgroundKind): String = when (kind) {
        BackgroundKind.TRANSPARENT -> "Transparent"
        BackgroundKind.SOLID -> "Solid"
        BackgroundKind.GRADIENT -> "Gradient"
        BackgroundKind.IMAGE -> "Photo"
        BackgroundKind.IMAGE_BLURRED -> "Photo blurred"
        BackgroundKind.IMAGE_DARKENED -> "Photo dimmed"
        BackgroundKind.VIDEO_THUMBNAIL -> "Video frame"
    }

    private fun kindFromLabel(label: String): BackgroundKind = when (label) {
        "Transparent" -> BackgroundKind.TRANSPARENT
        "Solid" -> BackgroundKind.SOLID
        "Gradient" -> BackgroundKind.GRADIENT
        "Photo" -> BackgroundKind.IMAGE
        "Photo blurred" -> BackgroundKind.IMAGE_BLURRED
        "Photo dimmed" -> BackgroundKind.IMAGE_DARKENED
        else -> BackgroundKind.VIDEO_THUMBNAIL
    }

    private fun labelFor(uri: String): String {
        val decoded = try {
            android.net.Uri.parse(uri).lastPathSegment
        } catch (e: Throwable) {
            null
        }
        val name = decoded ?: uri
        return if (name.length <= 28) name else name.substring(name.length - 24)
    }

    // ---- Border --------------------------------------------------------

    fun buildBorder(host: EditorHost, container: LinearLayout) {
        val context = host.editorContext
        val binding = host.binding
        container.addView(
            sliderCard(context, "Border thickness", binding.design.borderWidth, 0f, 12f, 0.25f, "dp") { value ->
                binding.update { it.borderWidth = value }
            },
            Ui.cardParams(context)
        )
        container.addView(
            colorCard(context, "Border colour", binding.design.borderColor, true) { value ->
                binding.update { it.borderColor = value }
            },
            Ui.cardParams(context)
        )
        container.addView(
            sliderCard(context, "Corner radius", binding.design.cornerRadius, 0f, 120f, 1f, "dp") { value ->
                binding.update { it.cornerRadius = value }
            },
            Ui.cardParams(context)
        )
        container.addView(
            sliderCard(context, "Internal padding", binding.design.padding, 0f, 64f, 0.5f, "dp") { value ->
                binding.update { it.padding = value }
            },
            Ui.cardParams(context)
        )
        container.addView(
            Ui.card(context).apply {
                Ui.add(
                    this,
                    Ui.caption(
                        context,
                        "The border is drawn inside the widget bounds, so a rounded frame still fits a square cell. Corner radius clips the background photo too, which is what makes a widget look like a card rather than a rectangle of pixels."
                    ),
                    0f
                )
            },
            Ui.cardParams(context)
        )
    }

    // ---- Layout --------------------------------------------------------

    fun buildLayout(host: EditorHost, container: LinearLayout) {
        val context = host.editorContext
        val binding = host.binding
        val gravities = ClockGravity.values()
        val gridCard = sectionCard(context, "Clock position", "Where the clock sits when the widget is bigger than the clock needs.")
        for (row in 0 until 3) {
            val line = LinearLayout(context)
            line.orientation = LinearLayout.HORIZONTAL
            for (col in 0 until 3) {
                val gravity = gravities[row * 3 + col]
                val chip = Ui.chip(context, glyphFor(gravity), binding.design.gravity == gravity) {
                    binding.update { it.gravity = gravity }
                }
                chip.contentDescription = "Position " + (row * 3 + col + 1)
                chip.gravity = Gravity.CENTER
                line.addView(chip, LinearLayout.LayoutParams(0, Ui.dp(context, 52f), 1f).apply {
                    if (col > 0) leftMargin = Ui.dp(context, 6f)
                })
            }
            Ui.add(gridCard, line, if (row == 0) 8f else 6f)
        }
        container.addView(gridCard, Ui.cardParams(context))
        container.addView(
            segmented(
                context = context,
                title = "Stacking",
                options = listOf("Side by side", "Stacked"),
                selectedIndex = { if (binding.design.orientation == OrientationMode.VERTICAL) 1 else 0 },
                hint = "Side by side puts the date under the time on a wide cell; stacked keeps a tall column.",
            ) { index ->
                binding.update { it.orientation = if (index == 0) OrientationMode.HORIZONTAL else OrientationMode.VERTICAL }
            },
            Ui.cardParams(context)
        )
        container.addView(
            segmented(
                context = context,
                title = "Tap on the widget opens",
                options = listOf("Full screen", "Editor", "Nothing"),
                selectedIndex = { binding.design.tapAction },
            ) { index ->
                binding.update { it.tapAction = index }
            },
            Ui.cardParams(context)
        )
    }

    private fun glyphFor(gravity: ClockGravity): String = when (gravity) {
        ClockGravity.TOP_LEFT -> "↖"
        ClockGravity.TOP_CENTER -> "↑"
        ClockGravity.TOP_RIGHT -> "↗"
        ClockGravity.CENTER_LEFT -> "←"
        ClockGravity.CENTER -> "•"
        ClockGravity.CENTER_RIGHT -> "→"
        ClockGravity.BOTTOM_LEFT -> "↙"
        ClockGravity.BOTTOM_CENTER -> "↓"
        ClockGravity.BOTTOM_RIGHT -> "↘"
    }

    // ---- Preview -------------------------------------------------------

    val BUCKET_LABELS = listOf("Small", "Medium", "Wide", "Tall", "Large", "Huge")

    fun bucketForLabel(label: String): WidgetBucket = when (label) {
        "Small" -> WidgetBucket.SMALL
        "Medium" -> WidgetBucket.MEDIUM
        "Wide" -> WidgetBucket.WIDE
        "Tall" -> WidgetBucket.TALL
        "Large" -> WidgetBucket.LARGE
        else -> WidgetBucket.HUGE
    }

    /** Approximate cell sizes used to preview the buckets (the launcher decides real ones). */
    fun sizeForBucket(bucket: WidgetBucket): Pair<Int, Int> = when (bucket) {
        WidgetBucket.SMALL -> 110 to 40
        WidgetBucket.MEDIUM -> 250 to 110
        WidgetBucket.WIDE -> 300 to 70
        WidgetBucket.TALL -> 110 to 300
        WidgetBucket.LARGE -> 300 to 250
        WidgetBucket.HUGE -> 340 to 340
    }

    fun buildPreview(host: EditorHost, container: LinearLayout) {
        val context = host.editorContext
        val binding = host.binding
        val card = sectionCard(context, "Size buckets", "Each block is drawn by the widget renderer at the launcher's cell size for that bucket.")
        for (label in BUCKET_LABELS) {
            val bucket = bucketForLabel(label)
            val (widthDp, heightDp) = sizeForBucket(bucket)
            val row = Ui.columnRow(context)
            val frame = ImageView(context)
            frame.scaleType = ImageView.ScaleType.FIT_CENTER
            frame.setBackgroundResource(R.drawable.preview_tile)
            try {
                frame.setImageBitmap(host.renderPreview(bucket))
            } catch (e: Throwable) {
            }
            val widthPx = Ui.dp(context, widthDp.toFloat())
            val heightPx = Ui.dp(context, heightDp.toFloat())
            row.addView(frame, LinearLayout.LayoutParams(widthPx.coerceAtMost(Ui.dp(context, 200f)), heightPx.coerceAtMost(Ui.dp(context, 150f))))
            val note = Ui.column(context)
            note.addView(Ui.text(context, "$widthDp × $heightDp dp", 12.5f, Ui.color(context, R.color.text_secondary), true))
            note.addView(
                Ui.text(
                    context,
                    "date " + if (bucket.showsDate) "on" else "off" + " · day " + if (bucket.showsDay) "on" else "off",
                    11.5f,
                    Ui.color(context, R.color.text_muted)
                )
            )
            row.addView(note, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = Ui.dp(context, 12f)
            })
            Ui.add(card, row, if (label == BUCKET_LABELS.first()) 12f else 14f)
        }
        container.addView(card, Ui.cardParams(context))
        if (host.canOpenComposePreview()) {
            container.addView(
                Ui.card(context).apply {
                    Ui.add(this, Ui.ghostButton(context, "Open interactive preview") { host.openComposePreview() }, 0f)
                },
                Ui.cardParams(context)
            )
        }
    }

    // ---- Widgets -------------------------------------------------------

    fun buildWidgets(host: EditorHost, container: LinearLayout) {
        val context = host.editorContext
        val card = sectionCard(context, "Installed widgets", "Each home-screen widget keeps its own design; binding is stored per appWidgetId.")
        val bindings = host.installedWidgets()
        if (bindings.isEmpty()) {
            Ui.add(card, Ui.body(context, context.getString(R.string.home_hint_add)), 10f)
        } else {
            for (entry in bindings) {
                val row = Ui.columnRow(context)
                row.addView(
                    Ui.text(context, entry, 13f, Ui.color(context, R.color.text_secondary)),
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                )
                row.addView(Ui.chip(context, "Use this design", false) { host.applyToAll() })
                Ui.add(card, row, 10f)
            }
        }
        Ui.add(card, Ui.body(context, "Sizes and aspect ratio are owned by your launcher: the clock re-renders on every resize, but no app can force an exact ratio."), 12f)
        container.addView(card, Ui.cardParams(context))
    }
}

/** What the section builders may ask of the screen that hosts them. */
interface EditorHost {
    val editorContext: Context
    val binding: DesignBinding
    fun pickImage()
    fun pickVideo()
    fun takePhoto()
    fun clearMedia()
    fun setMedia(uri: String, isVideo: Boolean)
    fun loadPosterInto(view: ImageView)
    fun renderPreview(bucket: WidgetBucket): android.graphics.Bitmap?
    fun installedWidgets(): List<String>
    fun applyToAll()
    fun canOpenComposePreview(): Boolean
    fun openComposePreview()
}
