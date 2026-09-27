package ai.techtroy.clockcanvas.ui

import ai.techtroy.clockcanvas.R
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Full colour picker: a saturation/brightness field over the whole RGB spectrum, a hue bar,
 * an alpha bar, and the same colour typed as R / G / B or hex.
 *
 * HSV is the internal state, because that is the shape the UI has: dragging the field changes
 * saturation and brightness while the hue stays put, and only a hue-based model can paint a
 * spectrum that is *continuous* with what the user picked. Red/green/blue are derived for the
 * number boxes and the hex line, and typing into those recomputes HSV - so the two ways of
 * choosing never disagree about the resulting colour.
 *
 * Drawn with [Canvas] and gradients rather than from a bitmap: the field is two
 * [LinearGradient]s (white→hue across, transparent→black down), which is the entire
 * sRGB gamut at the chosen hue and costs nothing to resize. No image asset, no new
 * dependency, and it renders the same on the offline build path as in Android Studio.
 */
object ColorPicker {

    /**
     * @param allowAlpha false for colours the renderer composites itself (time/date), where an
     *   alpha would fight the design's own opacity slider.
     */
    fun show(
        context: Context,
        title: String,
        initial: Int,
        allowAlpha: Boolean = true,
        onPicked: (Int) -> Unit,
    ) {
        try {
            ColorPickerSheet(context, title, initial, allowAlpha, onPicked).show()
        } catch (e: Throwable) {
            // A dialog that cannot be built must not eat the tap.
        }
    }

    /** The dialog body as its own class: it owns the HSV state and keeps every control in sync. */
    private class ColorPickerSheet(
        private val context: Context,
        private val title: String,
        private val initial: Int,
        private val allowAlpha: Boolean,
        private val onPicked: (Int) -> Unit,
    ) {

        private val hsv = FloatArray(3)
        private var alpha = 255
        private val root = Ui.column(context)
        private val field = SatValueView(context)
        private val hueBar = SpectrumBar(context)
        private val alphaBar = SpectrumBar(context)
        private val preview = View(context)
        private val hexField = EditText(context)
        private val redField = EditText(context)
        private val greenField = EditText(context)
        private val blueField = EditText(context)
        private var syncing = false

        fun show() {
            Color.colorToHSV(initial or 0xFF000000.toInt(), hsv)
            alpha = Color.alpha(initial)
            build()
            syncFromHsv()
            android.app.AlertDialog.Builder(context)
                .setTitle(title)
                .setView(root)
                .setPositiveButton(R.string.action_ok) { _, _ -> commit() }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }

        private fun commit() {
            val typed = parseHex(hexField.text.toString())
            val color = if (typed != null) {
                // A typed hex/RGB wins over the sliders: it is the more exact input, and
                // dragging the field to land on 0x7C3AED exactly is not realistic.
                (alpha shl 24) or (typed and 0x00FFFFFF)
            } else {
                currentColor()
            }
            onPicked(color)
        }

        private fun currentColor(): Int =
            (alpha shl 24) or (Color.HSVToColor(hsv) and 0x00FFFFFF)

        private fun build() {
            val pad = Ui.dp(context, 18f)
            root.setPadding(pad, pad / 2, pad, 0)

            field.onChange = { _, _ -> syncFromHsv(); }
            Ui.add(root, field, 10f)

            hueBar.colors = hueStops()
            hueBar.position = hsv[0] / 360f
            hueBar.onChange = { fraction ->
                hsv[0] = fraction * 360f
                syncFromHsv()
            }
            Ui.add(root, hueBar, 10f)

            if (allowAlpha) {
                alphaBar.colors = intArrayOf(0x00000000, currentColor() or 0xFF000000.toInt())
                alphaBar.position = alpha / 255f
                alphaBar.onChange = { fraction ->
                    alpha = (fraction * 255f).roundToInt().coerceIn(0, 255)
                    alphaBar.colors = intArrayOf(0x00000000, currentColor() or 0xFF000000.toInt())
                    syncFromHsv()
                }
                alphaBar.contentDescription = context.getString(R.string.content_desc_alpha)
            Ui.add(root, alphaBar, 12f)
            }

            Ui.add(root, preview, 12f, Ui.dp(context, 30f))

            // Numbers, because "make it exactly #7C3AED" is a real request.
            val rgbRow = Ui.columnRow(context)
            Ui.addFilled(rgbRow, labelled(redField, "R"), 0f)
            Ui.addFilled(rgbRow, labelled(greenField, "G"), 0f)
            Ui.addFilled(rgbRow, labelled(blueField, "B"), 0f)
            Ui.add(root, rgbRow, 8f)

            Ui.add(root, labelled(hexField, "Hex"), 4f)

            for (edit in listOf(hexField, redField, greenField, blueField)) {
                edit.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                    override fun afterTextChanged(s: Editable?) {
                        if (syncing) return
                        syncFromText()
                    }
                })
                edit.setOnEditorActionListener { _, actionId, _ ->
                    if (actionId == EditorInfo.IME_ACTION_DONE) {
                        commit()
                        true
                    } else {
                        false
                    }
                }
            }
        }

        private fun labelled(field: EditText, label: String): LinearLayout {
            field.inputType = if (label == "Hex") InputType.TYPE_CLASS_TEXT else InputType.TYPE_CLASS_NUMBER
            field.filters = arrayOf(InputFilter.LengthFilter(if (label == "Hex") 9 else 3))
            field.setTextColor(Ui.color(context, R.color.text_primary))
            field.textSize = 14f
            field.isSingleLine = true
            val row = Ui.column(context)
            row.addView(
                TextView(context).apply {
                    text = label
                    setTextColor(Ui.color(context, R.color.text_muted))
                    textSize = 11f
                    letterSpacing = 0.06f
                }
            )
            row.addView(field, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            return row
        }

        private fun hueStops(): IntArray {
            // Six pure hues, doubled at the ends so position 0 and 1 are the same red.
            val out = IntArray(7)
            for (i in 0..6) out[i] = Color.HSVToColor(floatArrayOf(i * 60f, 1f, 1f))
            return out
        }

        private fun syncFromHsv() {
            syncing = true
            val rgb = currentColor()
            redField.setText(Color.red(rgb).toString())
            greenField.setText(Color.green(rgb).toString())
            blueField.setText(Color.blue(rgb).toString())
            hexField.setText(
                if (allowAlpha && alpha != 255) String.format(java.util.Locale.US, "#%08X", (alpha shl 24) or (rgb and 0xFFFFFF))
                else String.format(java.util.Locale.US, "#%06X", rgb and 0xFFFFFF)
            )
            field.hue = hsv[0]
            field.saturation = hsv[1]
            field.value = hsv[2]
            hueBar.position = hsv[0] / 360f
            alphaBar.position = alpha / 255f
            preview.setBackgroundColor(rgb)
            syncing = false
        }

        /** Text boxes → sliders. Partial input is ignored rather than fought over. */
        private fun syncFromText() {
            val raw = hexField.text.toString().trim().removePrefix("#")
            val typed = parseHex(raw)
            if (typed != null) {
                Color.colorToHSV(typed or 0xFF000000.toInt(), hsv)
                // An 8-digit entry carries its own alpha; a 6-digit one means "opaque"
                // rather than "leave whatever the slider says".
                if (allowAlpha) {
                    alpha = if (raw.length == 8) (typed ushr 24) and 0xFF else 255
                    alphaBar.position = alpha / 255f
                }
                syncing = true
                redField.setText(Color.red(typed).toString())
                greenField.setText(Color.green(typed).toString())
                blueField.setText(Color.blue(typed).toString())
                field.hue = hsv[0]
                field.saturation = hsv[1]
                field.value = hsv[2]
                hueBar.position = hsv[0] / 360f
                preview.setBackgroundColor(currentColor())
                syncing = false
                return
            }
            val r = redField.text.toString().toIntOrNull()
            val g = greenField.text.toString().toIntOrNull()
            val b = blueField.text.toString().toIntOrNull()
            if (r != null && g != null && b != null && r in 0..255 && g in 0..255 && b in 0..255) {
                val rgb = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                Color.colorToHSV(rgb, hsv)
                syncing = true
                hexField.setText(String.format(java.util.Locale.US, "#%06X", rgb and 0xFFFFFF))
                field.hue = hsv[0]
                field.saturation = hsv[1]
                field.value = hsv[2]
                hueBar.position = hsv[0] / 360f
                preview.setBackgroundColor(currentColor())
                syncing = false
            }
        }

        private fun parseHex(raw: String?): Int? {
            val text = (raw ?: "").trim().removePrefix("#")
            if (text.length != 6 && text.length != 8) return null
            val value = text.toLongOrNull(16) ?: return null
            return if (text.length == 6) 0xFF000000.toInt() or value.toInt() else value.toInt()
        }
    }

    /** Saturation (x) × brightness (y) for one hue: the whole spectrum, not a swatch grid. */
    private class SatValueView(context: Context) : View(context) {

        var hue = 0f
            set(value) {
                field = ((value % 360f) + 360f) % 360f
                invalidate()
            }
        var saturation = 1f
            set(value) {
                field = value.coerceIn(0f, 1f)
                invalidate()
            }
        var value = 1f
            set(v) {
                field = v.coerceIn(0f, 1f)
                invalidate()
            }
        var onChange: ((Float, Float) -> Unit)? = null

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val marker = Paint(Paint.ANTI_ALIAS_FLAG)
        private val outline = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private val path = Path()
        private val radius = Ui.dp(context, 12f).toFloat()
        private var down = false

        init {
            minimumHeight = Ui.dp(context, 150f)
            marker.style = Paint.Style.STROKE
            marker.strokeWidth = Ui.dp(context, 2.5f).toFloat()
            marker.color = Color.WHITE
            outline.style = Paint.Style.STROKE
            outline.strokeWidth = Ui.dp(context, 1f).toFloat()
            outline.color = Ui.color(context, R.color.border)
            isFocusable = false
            contentDescription = context.getString(R.string.content_desc_spectrum)
        }

        override fun onDraw(canvas: Canvas) {
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            path.reset()
            path.addRoundRect(rect, radius, radius, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(path)
            val hueColor = Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
            paint.shader = LinearGradient(rect.left, 0f, rect.right, 0f, Color.WHITE, hueColor, Shader.TileMode.CLAMP)
            canvas.drawRect(rect, paint)
            paint.shader = LinearGradient(
                0f, rect.top, 0f, rect.bottom, 0x00000000, 0xFF000000.toInt(), Shader.TileMode.CLAMP
            )
            canvas.drawRect(rect, paint)
            paint.shader = null
            canvas.restore()
            canvas.drawRoundRect(rect, radius, radius, outline)
            canvas.drawCircle(markerX(), markerY(), Ui.dp(context, 7f).toFloat(), marker)
        }

        private fun markerX(): Float = saturation * (width - 2 * radius) + radius
        private fun markerY(): Float = (1f - value) * (height - 2 * radius) + radius

        private fun updateFromTouch(event: MotionEvent) {
            val x = (event.x - radius) / max(1f, width - 2 * radius)
            val y = (event.y - radius) / max(1f, height - 2 * radius)
            saturation = x.coerceIn(0f, 1f)
            value = 1f - y.coerceIn(0f, 1f)
            onChange?.invoke(saturation, value)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    down = true
                    updateFromTouch(event)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (down) updateFromTouch(event)
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    down = false
                    if (event.actionMasked == MotionEvent.ACTION_UP) updateFromTouch(event)
                    performClick()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }
    }

    /** A horizontal gradient bar with a draggable thumb: hue, or alpha. */
    private class SpectrumBar(context: Context) : View(context) {

        var colors: IntArray = intArrayOf(Color.RED, Color.MAGENTA)
            set(v) {
                field = if (v.size < 2) intArrayOf(Color.BLACK, Color.WHITE) else v
                invalidate()
            }
        var position = 0f
            set(v) {
                field = v.coerceIn(0f, 1f)
                invalidate()
            }
        var onChange: ((Float) -> Unit)? = null

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val thumb = Paint(Paint.ANTI_ALIAS_FLAG)
        private val outline = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        // The bar is 26 dp tall but the view is 48 dp, because `Ui.MIN_TOUCH` is this app's own
        // rule and a slider you cannot hit is worse than no slider at all.
        private val barHeight = Ui.dp(context, 26f).toFloat()
        private val viewHeight = Ui.dp(context, Ui.MIN_TOUCH.toFloat()).toFloat()
        private val radius = barHeight / 2f
        private var down = false

        init {
            minimumHeight = viewHeight.toInt()
            thumb.style = Paint.Style.STROKE
            thumb.strokeWidth = Ui.dp(context, 2.5f).toFloat()
            thumb.color = Color.WHITE
            outline.style = Paint.Style.STROKE
            outline.strokeWidth = Ui.dp(context, 1f).toFloat()
            outline.color = Ui.color(context, R.color.border)
            contentDescription = context.getString(R.string.content_desc_hue)
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            setMeasuredDimension(w, viewHeight.toInt())
        }

        override fun onDraw(canvas: Canvas) {
            val inset = radius
            val top = (height - barHeight) / 2f
            rect.set(inset, top, max(inset + 1f, width - inset), top + barHeight)
            paint.shader = LinearGradient(rect.left, 0f, rect.right, 0f, colors, null, Shader.TileMode.CLAMP)
            canvas.drawRoundRect(rect, radius, radius, paint)
            paint.shader = null
            canvas.drawRoundRect(rect, radius, radius, outline)
            val cx = rect.left + position * rect.width()
            canvas.drawCircle(cx, height / 2f, Ui.dp(context, 7f).toFloat(), thumb)
        }

        private fun updateFromTouch(event: MotionEvent) {
            val inset = radius
            val usable = max(1f, width - 2 * inset)
            position = ((event.x - inset) / usable).coerceIn(0f, 1f)
            onChange?.invoke(position)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    down = true
                    updateFromTouch(event)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (down) updateFromTouch(event)
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    down = false
                    if (event.actionMasked == MotionEvent.ACTION_UP) updateFromTouch(event)
                    performClick()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }
    }
}
