package ai.techtroy.clockcanvas.ui

import ai.techtroy.clockcanvas.R
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.min

/**
 * The app's whole widget kit: a handful of factory functions instead of an XML
 * layout per screen. That is not minimalism for its own sake - the editor's forms
 * are rebuilt every time the design changes, so keeping the control and the value
 * it edits in one call site is simpler and less error-prone than layout files,
 * adapters and binder glue.
 *
 * Accessibility rules baked in here (easy to lose with custom UIs):
 *  - every tappable thing is at least 48dp tall ([MIN_TOUCH]);
 *  - selection state is carried by `isSelected` plus a checkmark glyph, so state
 *    is never signalled by colour alone and TalkBack can announce it;
 *  - content descriptions describe purpose, not decoration.
 */
object Ui {

    const val MIN_TOUCH = 48

    /** Vertical rhythm between cards; every screen uses it, so gaps never drift. */
    const val CARD_GAP = 14f

    fun dp(context: Context, value: Float): Int =
        (value * context.resources.displayMetrics.density).toInt()

    fun color(context: Context, resId: Int): Int = context.resources.getColor(resId)

    fun text(
        context: Context,
        value: CharSequence,
        sizeSp: Float,
        color: Int,
        bold: Boolean = false,
    ): TextView {
        val view = TextView(context)
        view.text = value
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        view.setTextColor(color)
        view.setTypeface(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
        return view
    }

    fun heading(context: Context, value: CharSequence): TextView =
        text(context, value, 21f, color(context, R.color.text_primary), true)

    fun title(context: Context, value: CharSequence): TextView =
        text(context, value, 16f, color(context, R.color.text_primary), true)

    fun caption(context: Context, value: CharSequence): TextView {
        val view = text(context, value, 12.5f, color(context, R.color.text_muted))
        view.setLineSpacing(dp(context, 2f).toFloat(), 1f)
        return view
    }

    fun body(context: Context, value: CharSequence): TextView {
        val view = text(context, value, 14f, color(context, R.color.text_secondary))
        view.setLineSpacing(dp(context, 3f).toFloat(), 1f)
        return view
    }

    fun column(context: Context): LinearLayout {
        val layout = LinearLayout(context)
        layout.orientation = LinearLayout.VERTICAL
        return layout
    }

    fun columnRow(context: Context): LinearLayout {
        val layout = LinearLayout(context)
        layout.orientation = LinearLayout.HORIZONTAL
        layout.gravity = Gravity.CENTER_VERTICAL
        return layout
    }

    /** Adds [view] to [parent] with a top margin, which is this app's spacing unit. */
    fun add(parent: LinearLayout, view: View, gapDp: Float = 12f, height: Int = LinearLayout.LayoutParams.WRAP_CONTENT) {
        val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height)
        params.topMargin = dp(parent.context, gapDp)
        parent.addView(view, params)
    }

    fun addFilled(parent: LinearLayout, view: View, gapDp: Float, weight: Float = 1f): LinearLayout.LayoutParams {
        val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight)
        params.topMargin = dp(parent.context, gapDp)
        parent.addView(view, params)
        return params
    }

    fun card(context: Context, elevated: Boolean = false): LinearLayout {
        val layout = column(context)
        layout.setBackgroundResource(if (elevated) R.drawable.card_elevated_background else R.drawable.card_background)
        val pad = dp(context, 16f)
        layout.setPadding(pad, pad, pad, pad)
        return layout
    }

    fun scrollCard(context: Context): FrameLayout {
        val frame = FrameLayout(context)
        frame.setBackgroundResource(R.drawable.card_background)
        val pad = dp(context, 14f)
        frame.setPadding(pad, pad, pad, pad)
        return frame
    }

    /** A selectable label pill. [selected] also gets a checkmark: never colour alone. */
    fun chip(context: Context, label: String, selected: Boolean, onClick: () -> Unit): TextView {
        val chip = TextView(context)
        chip.text = if (selected) "✓  $label" else label
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
        chip.setTextColor(if (selected) Color.WHITE else color(context, R.color.text_secondary))
        chip.setTypeface(Typeface.SANS_SERIF, if (selected) Typeface.BOLD else Typeface.NORMAL)
        chip.setBackgroundResource(R.drawable.chip_background)
        val padH = dp(context, 14f)
        chip.setPadding(padH, dp(context, 11f), padH, dp(context, 11f))
        chip.gravity = Gravity.CENTER
        chip.minHeight = dp(context, MIN_TOUCH.toFloat())
        chip.isClickable = true
        chip.isSelected = selected
        chip.contentDescription = label + if (selected) ", selected" else ""
        chip.setOnClickListener { onClick() }
        return chip
    }

    fun button(context: Context, label: String, primary: Boolean, onClick: () -> Unit): Button {
        val button = Button(context)
        button.text = label
        button.isAllCaps = false
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        button.setTextColor(if (primary) Color.WHITE else color(context, R.color.text_primary))
        button.setBackgroundResource(if (primary) R.drawable.button_primary else R.drawable.button_ghost)
        button.minHeight = dp(context, 52f)
        button.setPadding(dp(context, 18f), dp(context, 12f), dp(context, 18f), dp(context, 12f))
        button.contentDescription = label
        button.setOnClickListener { onClick() }
        return button
    }

    fun ghostButton(context: Context, label: String, onClick: () -> Unit): Button =
        button(context, label, false, onClick)

    fun primaryButton(context: Context, label: String, onClick: () -> Unit): Button =
        button(context, label, true, onClick)

    fun toggle(
        context: Context,
        label: String,
        hint: String?,
        checked: Boolean,
        onChange: (Boolean) -> Unit,
    ): LinearLayout {
        val row = columnRow(context)
        row.setPadding(0, dp(context, 6f), 0, dp(context, 6f))
        val column = column(context)
        column.addView(text(context, label, 15f, color(context, R.color.text_primary), true))
        if (!hint.isNullOrEmpty()) column.addView(text(context, hint, 12f, color(context, R.color.text_muted)))
        row.addView(column, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val sw = android.widget.Switch(context)
        sw.isChecked = checked
        sw.contentDescription = label
        sw.setOnCheckedChangeListener { _, isChecked -> onChange(isChecked) }
        val swParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        swParams.leftMargin = dp(context, 12f)
        row.addView(sw, swParams)
        row.isClickable = true
        row.setOnClickListener { sw.toggle() }
        row.setMinimumHeight(dp(context, MIN_TOUCH.toFloat()))
        return row
    }

    fun editText(context: Context, value: String, singleLine: Boolean, onDone: (String) -> Unit): android.widget.EditText {
        val field = android.widget.EditText(context)
        field.setText(value)
        field.setSingleLine(singleLine)
        field.maxLines = if (singleLine) 1 else 4
        field.inputType = if (singleLine) {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        } else {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        field.setTextColor(color(context, R.color.text_primary))
        field.setHintTextColor(color(context, R.color.text_muted))
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        field.setBackgroundResource(R.drawable.input_background)
        val pad = dp(context, 12f)
        field.setPadding(pad, pad, pad, pad)
        field.setHorizontallyScrolling(false)
        return field
    }

    /**
     * Integer slider with a scaled value and a live readout. `scale` lets the
     * editor expose fractional tweaks (corner radius in 0.5dp steps, alpha in 1%)
     * without a float SeekBar, which does not exist.
     */
    fun slider(
        context: Context,
        label: String,
        valueTenths: Int,
        minTenths: Int,
        maxTenths: Int,
        scale: Int,
        suffix: String,
        onChange: (Float) -> Unit,
    ): SeekRow = SeekRow(context, label, valueTenths, minTenths, maxTenths, scale, suffix, onChange)

    fun sliderPercent(
        context: Context,
        label: String,
        percent: Float,
        onChange: (Float) -> Unit,
    ): SeekRow = slider(context, label, (percent * 100).toInt(), 0, 100, 100, "%") { onChange(it / 100f) }

    class SeekRow(
        context: Context,
        label: String,
        valueTenths: Int,
        private val minTenths: Int,
        maxTenths: Int,
        private val scale: Int,
        private val suffix: String,
        private val onChange: (Float) -> Unit,
    ) : LinearLayout(context) {

        val valueLabel: TextView = TextView(context)
        val seek: SeekBar = SeekBar(context)
        private val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val raw = progress + minTenths
                valueLabel.text = format(raw)
                if (fromUser) onChange(raw.toFloat() / scale.toFloat())
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                onChange((seekBar.progress + minTenths).toFloat() / scale.toFloat())
            }
        }

        init {
            orientation = VERTICAL
            val header = columnRow(context)
            valueLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            valueLabel.setTextColor(color(context, R.color.lavender_soft))
            valueLabel.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD)
            header.addView(
                text(context, label, 14f, color(context, R.color.text_secondary)),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            header.addView(valueLabel)
            addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            seek.max = (maxTenths - minTenths).coerceAtLeast(1)
            seek.progress = (valueTenths - minTenths).coerceIn(0, seek.max)
            seek.contentDescription = label + ", " + format(valueTenths)
            addView(seek, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            seek.setOnSeekBarChangeListener(listener)
            setMinimumHeight(dp(context, MIN_TOUCH.toFloat()))
        }

        private fun format(raw: Int): String {
            val value = raw.toFloat() / scale.toFloat()
            return if (scale <= 1) {
                raw.toString() + suffix
            } else {
                trimZeros(value) + suffix
            }
        }

        private fun trimZeros(value: Float): String {
            val text = String.format(java.util.Locale.US, "%.2f", value)
            var end = text.length
            while (end > 0 && text[end - 1] == '0') end--
            if (end > 0 && text[end - 1] == '.') end--
            return text.substring(0, end)
        }
    }

    /** A row of colour dots plus the current value as hex text. */
    fun colorRow(
        context: Context,
        label: String,
        selectedColor: Int,
        swatches: IntArray,
        onPick: (Int) -> Unit,
        onCustom: () -> Unit,
    ): LinearLayout {
        val card = card(context)
        add(card, text(context, label, 13f, color(context, R.color.text_secondary)), 0f)
        val row = columnRow(context)
        for ((index, swatch) in swatches.withIndex()) {
            val dot = ColorDot(context, swatch)
            val params = LinearLayout.LayoutParams(dp(context, 36f), dp(context, 36f))
            if (index > 0) params.leftMargin = dp(context, 8f)
            dot.isSelected = swatch == selectedColor
            dot.contentDescription = "Colour ${hexOf(swatch)}" + if (dot.isSelected) ", selected" else ""
            dot.setOnClickListener { onPick(swatch) }
            row.addView(dot, params)
        }
        val custom = chip(context, "custom…", false, onCustom)
        val customParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        customParams.leftMargin = dp(context, 8f)
        row.addView(custom, customParams)
        add(card, row, 8f)
        add(card, text(context, "Current ${hexOf(selectedColor)} · alpha ${Color.alpha(selectedColor)}", 11.5f, color(context, R.color.text_muted)), 8f)
        return card
    }

    fun hexOf(value: Int): String = String.format(java.util.Locale.US, "#%08X", value)

    /**
     * Parses "#rgb", "#aarrggbb", "#rrggbb" or a bare hex. Returns null when the
     * user typed something unusable, so callers can show a warning instead of
     * silently applying black.
     */
    fun parseColor(input: String?): Int? {
        val text = (input ?: "").trim()
        if (text.isEmpty()) return null
        val body = if (text.startsWith("#")) text.substring(1) else text
        return try {
            when (body.length) {
                3 -> {
                    val r = Integer.parseInt("" + body[0] + body[0], 16)
                    val g = Integer.parseInt("" + body[1] + body[1], 16)
                    val b = Integer.parseInt("" + body[2] + body[2], 16)
                    Color.rgb(r, g, b)
                }
                6 -> Color.parseColor("#FF$body")
                8 -> Color.parseColor("#$body")
                else -> null
            }
        } catch (e: Throwable) {
            null
        }
    }

    val PALETTE = intArrayOf(
        0xFFF5F3FF.toInt(), 0xFFFFFFFF.toInt(), 0xFF09070F.toInt(), 0xFF000000.toInt(),
        0xFF7C3AED.toInt(), 0xFFA855F7.toInt(), 0xFFC084FC.toInt(), 0xFF342750.toInt(),
        0xFF161222.toInt(), 0xFF1D1730.toInt(), 0xFF34D399.toInt(), 0xFFFBBF24.toInt(),
        0xFFF87171.toInt(), 0xFFB8AEC9.toInt(), 0xFF60A5FA.toInt(), 0xFFF472B6.toInt(),
    )

    val TRANSPARENT = 0x00000000

    /** Round swatch with a white ring when selected. */
    class ColorDot(context: Context, private val argb: Int) : View(context) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG)
        private val checker = Paint()

        /** View already owns `isSelected`; we only add the repaint it does not do. */
        override fun setSelected(selected: Boolean) {
            super.setSelected(selected)
            invalidate()
        }

        init {
            fill.style = Paint.Style.FILL
            ring.style = Paint.Style.STROKE
            checker.color = 0x33FFFFFF
        }

        override fun onDraw(canvas: Canvas) {
            val inset = dp(context, 3f)
            val radius = (minOf(width, height) / 2f) - inset
            if (radius <= 0f) return
            val cx = width / 2f
            val cy = height / 2f
            if (Color.alpha(argb) < 250) {
                // A transparent swatch must look transparent, not black: draw a
                // two-tone checker under it so "no background" is legible.
                val step = radius / 2f
                var row = 0
                var x = cx - radius
                while (x < cx + radius) {
                    var y = cy - radius
                    var column = 0
                    while (y < cy + radius) {
                        if ((row + column) % 2 == 0) {
                            canvas.drawRect(x, y, min(cx + radius, x + step), min(cy + radius, y + step), checker)
                        }
                        y += step
                        column++
                    }
                    x += step
                    row++
                }
            }
            fill.color = argb
            canvas.drawCircle(cx, cy, radius, fill)
            ring.color = if (isSelected) 0xFFFFFFFF.toInt() else 0x33FFFFFF
            ring.strokeWidth = dp(context, if (isSelected) 2.5f else 1f).toFloat()
            canvas.drawCircle(cx, cy, radius + ring.strokeWidth, ring)
        }
    }

    fun imageTile(context: Context, bitmap: android.graphics.Bitmap?, placeholder: String): ImageView {
        val view = ImageView(context)
        view.scaleType = ImageView.ScaleType.CENTER_CROP
        view.setBackgroundResource(R.drawable.preview_tile)
        val size = dp(context, 64f)
        view.layoutParams = LinearLayout.LayoutParams(size, size)
        if (bitmap != null && !bitmap.isRecycled) {
            view.setImageBitmap(bitmap)
            view.contentDescription = "Selected background"
        } else {
            view.contentDescription = placeholder
        }
        return view
    }

    fun gap(context: Context, dp: Float): View {
        val view = View(context)
        view.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(context, dp))
        return view
    }

    fun tabStrip(context: Context, labels: List<String>, selected: Int, onSelect: (Int) -> Unit): LinearLayout {
        val strip = android.widget.HorizontalScrollView(context)
        val row = columnRow(context)
        row.setPadding(dp(context, 4f), 0, dp(context, 4f), 0)
        for ((index, label) in labels.withIndex()) {
            val tab = chip(context, label, index == selected) { onSelect(index) }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            if (index > 0) params.leftMargin = dp(context, 8f)
            tab.tag = index
            row.addView(tab, params)
        }
        strip.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        return row
    }

    fun emptyState(context: Context, headline: String, detail: String): LinearLayout {
        val layout = card(context)
        layout.gravity = Gravity.CENTER
        add(layout, text(context, headline, 16f, color(context, R.color.text_primary), true), 0f)
        add(layout, text(context, detail, 13f, color(context, R.color.text_secondary)), 6f)
        return layout
    }

    fun selectedMark(selected: Boolean): String = if (selected) "✓" else "○"

    fun describeSelectable(view: View, label: String, selected: Boolean) {
        view.contentDescription = "$label${if (selected) ", selected" else ""}"
    }

    fun announce(view: View, text: String) {
        view.announceForAccessibility(text)
    }

    fun cardParams(context: Context, gapDp: Float = 14f): LinearLayout.LayoutParams {
        val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        params.topMargin = dp(context, gapDp)
        return params
    }

    fun dialogTitle(context: Context, value: String): TextView =
        text(context, value, 18f, color(context, R.color.text_primary), true)
}
