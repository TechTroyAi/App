package ai.techtroy.app

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val ink = Color.rgb(16, 21, 18)
    private val paper = Color.rgb(243, 245, 240)
    private val green = Color.rgb(183, 243, 74)
    private val forest = Color.rgb(24, 59, 42)
    private val muted = Color.rgb(91, 101, 94)
    private val line = Color.rgb(215, 220, 211)
    private val white = Color.WHITE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureWindow(window)
        setContentView(buildScreen())
    }

    private fun configureWindow(appWindow: Window) {
        appWindow.statusBarColor = paper
        appWindow.navigationBarColor = ink
        if (Build.VERSION.SDK_INT >= 23) {
            appWindow.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
        if (Build.VERSION.SDK_INT >= 28) {
            appWindow.navigationBarDividerColor = ink
        }
    }

    private fun buildScreen(): View {
        val root = FrameLayout(this)
        root.background = gradient(
            intArrayOf(Color.rgb(248, 249, 245), paper),
            0f,
            GradientDrawable.Orientation.TOP_BOTTOM
        )
        if (Build.VERSION.SDK_INT >= 35) {
            root.setOnApplyWindowInsetsListener(object : View.OnApplyWindowInsetsListener {
                override fun onApplyWindowInsets(view: View, insets: WindowInsets): WindowInsets {
                    view.setPadding(
                        insets.systemWindowInsetLeft,
                        insets.systemWindowInsetTop,
                        insets.systemWindowInsetRight,
                        insets.systemWindowInsetBottom
                    )
                    return insets
                }
            })
        }

        val scroll = ScrollView(this)
        scroll.isFillViewport = true
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        root.addView(
            scroll,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        val content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(dp(24), dp(24), dp(24), dp(30))
        scroll.addView(
            content,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        content.addView(buildBrandRow())
        content.addView(space(46))
        content.addView(label("NATIVE ANDROID / 01", forest))
        content.addView(space(14))

        val title = text("Built native.\nReady to ship.", 40f, ink, Typeface.BOLD)
        title.typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
        title.setLineSpacing(0f, 0.93f)
        title.letterSpacing = -0.025f
        content.addView(title)

        content.addView(space(18))
        val intro = text(
            "A clean Kotlin foundation for the next TechTroy idea — fast, focused, and made for a real Android phone.",
            17f,
            muted,
            Typeface.NORMAL
        )
        intro.setLineSpacing(dp(4).toFloat(), 1f)
        content.addView(intro)

        content.addView(space(30))
        val statusText = buildStatusCard()
        content.addView(statusText.first)

        content.addView(space(16))
        content.addView(buildMetricRow())

        content.addView(space(30))
        content.addView(buildSectionHeading("THE FOUNDATION", "Everything needed to begin"))
        content.addView(space(14))
        content.addView(buildFeatureCard("01", "Pure Kotlin", "Native Android APIs. No web wrapper and no cross-platform runtime."))
        content.addView(space(10))
        content.addView(buildFeatureCard("02", "Phone ready", "Supports Android 7.0 and newer, with a modern API 35 target."))
        content.addView(space(10))
        content.addView(buildFeatureCard("03", "CI prepared", "GitHub Actions can compile a fresh debug APK after every push."))

        content.addView(space(30))
        val checkButton = buildCheckButton(statusText.second)
        content.addView(checkButton)

        content.addView(space(34))
        content.addView(divider())
        content.addView(space(18))
        content.addView(buildFooter())

        return root
    }

    private fun buildBrandRow(): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL

        val mark = TextView(this)
        mark.text = "T"
        mark.gravity = Gravity.CENTER
        mark.setTextColor(ink)
        mark.textSize = 24f
        mark.typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
        mark.background = rounded(green, 16f)
        row.addView(mark, LinearLayout.LayoutParams(dp(52), dp(52)))

        val names = LinearLayout(this)
        names.orientation = LinearLayout.VERTICAL
        val nameParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        nameParams.leftMargin = dp(13)
        row.addView(names, nameParams)

        val name = text("TECHTROY", 18f, ink, Typeface.BOLD)
        name.typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
        name.letterSpacing = 0.08f
        names.addView(name)

        val sub = text("ANDROID LAB", 10f, muted, Typeface.BOLD)
        sub.letterSpacing = 0.18f
        names.addView(sub)

        val chip = text("READY", 10f, white, Typeface.BOLD)
        chip.gravity = Gravity.CENTER
        chip.letterSpacing = 0.12f
        chip.background = rounded(forest, 30f)
        row.addView(chip, LinearLayout.LayoutParams(dp(76), dp(34)))

        return row
    }

    private fun buildStatusCard(): Pair<View, TextView> {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(20), dp(18), dp(20), dp(18))
        card.background = rounded(ink, 22f)
        card.elevation = dp(2).toFloat()

        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        card.addView(top)

        val dot = View(this)
        dot.background = rounded(green, 100f)
        top.addView(dot, LinearLayout.LayoutParams(dp(10), dp(10)))

        val state = text("BUILD SYSTEM ONLINE", 11f, green, Typeface.BOLD)
        state.letterSpacing = 0.14f
        val stateParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        stateParams.leftMargin = dp(10)
        top.addView(state, stateParams)

        val version = text("v1.0", 11f, Color.rgb(171, 181, 174), Typeface.BOLD)
        top.addView(version)

        card.addView(space(14))
        val status = text("Starter APK compiled", 22f, white, Typeface.BOLD)
        status.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        card.addView(status)

        card.addView(space(5))
        val detail = text("Install it, open it, then build the real product from here.", 14f, Color.rgb(179, 188, 181), Typeface.NORMAL)
        detail.setLineSpacing(dp(2).toFloat(), 1f)
        card.addView(detail)

        return Pair(card, detail)
    }

    private fun buildMetricRow(): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL

        val left = metricCard("LANGUAGE", "Kotlin", green)
        val leftParams = LinearLayout.LayoutParams(0, dp(108), 1f)
        leftParams.rightMargin = dp(5)
        row.addView(left, leftParams)

        val right = metricCard("MINIMUM", "Android 7+", white)
        val rightParams = LinearLayout.LayoutParams(0, dp(108), 1f)
        rightParams.leftMargin = dp(5)
        row.addView(right, rightParams)

        return row
    }

    private fun metricCard(kicker: String, value: String, backgroundColor: Int): View {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.gravity = Gravity.CENTER_VERTICAL
        card.setPadding(dp(17), dp(12), dp(17), dp(12))
        val shape = rounded(backgroundColor, 18f)
        if (backgroundColor == white) {
            shape.setStroke(dp(1), line)
        }
        card.background = shape

        val labelColor = if (backgroundColor == green) forest else muted
        val label = text(kicker, 10f, labelColor, Typeface.BOLD)
        label.letterSpacing = 0.13f
        card.addView(label)
        card.addView(space(8))
        val metric = text(value, 20f, ink, Typeface.BOLD)
        metric.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        card.addView(metric)
        return card
    }

    private fun buildSectionHeading(kicker: String, heading: String): View {
        val block = LinearLayout(this)
        block.orientation = LinearLayout.VERTICAL
        block.addView(label(kicker, forest))
        block.addView(space(7))
        val title = text(heading, 25f, ink, Typeface.BOLD)
        title.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        block.addView(title)
        return block
    }

    private fun buildFeatureCard(number: String, heading: String, body: String): View {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.HORIZONTAL
        card.gravity = Gravity.TOP
        card.setPadding(dp(17), dp(17), dp(17), dp(17))
        val shape = rounded(white, 18f)
        shape.setStroke(dp(1), line)
        card.background = shape

        val numberView = text(number, 11f, forest, Typeface.BOLD)
        numberView.gravity = Gravity.CENTER
        numberView.letterSpacing = 0.08f
        numberView.background = rounded(Color.rgb(229, 236, 222), 12f)
        card.addView(numberView, LinearLayout.LayoutParams(dp(42), dp(42)))

        val copy = LinearLayout(this)
        copy.orientation = LinearLayout.VERTICAL
        val copyParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        copyParams.leftMargin = dp(15)
        card.addView(copy, copyParams)

        copy.addView(text(heading, 17f, ink, Typeface.BOLD))
        copy.addView(space(5))
        val description = text(body, 14f, muted, Typeface.NORMAL)
        description.setLineSpacing(dp(2).toFloat(), 1f)
        copy.addView(description)

        return card
    }

    private fun buildCheckButton(status: TextView): View {
        val button = TextView(this)
        button.text = "RUN SYSTEM CHECK   →"
        button.gravity = Gravity.CENTER
        button.setTextColor(white)
        button.textSize = 14f
        button.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        button.letterSpacing = 0.06f
        button.background = rounded(forest, 17f)
        button.isClickable = true
        button.isFocusable = true
        button.contentDescription = "Run system check"
        button.elevation = dp(2).toFloat()
        button.setOnClickListener {
            button.isEnabled = false
            button.alpha = 0.78f
            button.text = "CHECK COMPLETE   ✓"
            status.text = "All systems operational. This native Kotlin build is ready."
            Toast.makeText(this, "TechTroy is ready to build.", Toast.LENGTH_SHORT).show()
            button.postDelayed(object : Runnable {
                override fun run() {
                    button.isEnabled = true
                    button.alpha = 1f
                    button.text = "RUN SYSTEM CHECK   →"
                    status.text = "Install it, open it, then build the real product from here."
                }
            }, 2800L)
        }

        button.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(58)
        )
        return button
    }

    private fun buildFooter(): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL

        val copy = text("TECHTROY / ANDROID", 10f, muted, Typeface.BOLD)
        copy.letterSpacing = 0.12f
        row.addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val packageName = text("ai.techtroy.app", 10f, muted, Typeface.NORMAL)
        row.addView(packageName)
        return row
    }

    private fun label(value: String, color: Int): TextView {
        val view = text(value, 10f, color, Typeface.BOLD)
        view.letterSpacing = 0.16f
        return view
    }

    private fun text(value: String, size: Float, color: Int, style: Int): TextView {
        val view = TextView(this)
        view.text = value
        view.textSize = size
        view.setTextColor(color)
        view.typeface = Typeface.create("sans-serif", style)
        view.includeFontPadding = false
        return view
    }

    private fun divider(): View {
        val view = View(this)
        view.setBackgroundColor(line)
        view.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(1)
        )
        return view
    }

    private fun space(height: Int): View {
        val view = View(this)
        view.layoutParams = LinearLayout.LayoutParams(1, dp(height))
        return view
    }

    private fun rounded(color: Int, radiusDp: Float): GradientDrawable {
        val drawable = GradientDrawable()
        drawable.setColor(color)
        drawable.cornerRadius = dpFloat(radiusDp)
        return drawable
    }

    private fun gradient(colors: IntArray, radiusDp: Float, orientation: GradientDrawable.Orientation): GradientDrawable {
        val drawable = GradientDrawable(orientation, colors)
        drawable.cornerRadius = dpFloat(radiusDp)
        return drawable
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }

    private fun dpFloat(value: Float): Float {
        return value * resources.displayMetrics.density
    }
}
