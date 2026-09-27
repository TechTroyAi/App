package ai.techtroy.clockcanvas.ui

import ai.techtroy.clockcanvas.R
import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Shared chrome for every screen: the violet-on-black background, a header with a
 * back affordance and a title, and a scrolling content column.
 *
 * Header, content and the optional footer bar are separate hooks so a screen never
 * re-implements the top bar (and so the back affordance is always 48dp tall and
 * labelled for TalkBack - a detail custom UI kits tend to forget).
 */
abstract class ClockActivity : Activity() {

    protected lateinit var root: LinearLayout
        private set
    protected lateinit var content: ScrollView
        private set
    protected lateinit var body: LinearLayout
        private set
    protected var headerTitle: TextView? = null
        private set

    abstract fun screenTitle(): String

    open fun showBackButton(): Boolean = true

    open fun headerAction(): View? = null

    /** Called after the header exists, before content is built. */
    open fun onHeaderReady(header: LinearLayout) {}

    open fun showTopPreview(): Boolean = false

    protected var topPreview: ClockCanvasView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(color(R.color.bg_main))

        root.addView(buildHeader(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        if (showTopPreview()) {
            val preview = ClockCanvasView(this)
            topPreview = preview
            val holder = FrameLayout(this)
            holder.setBackgroundColor(color(R.color.bg_secondary))
            val pad = Ui.dp(this, 18f)
            holder.setPadding(pad, pad, pad, pad)
            val previewParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(150f))
            previewParams.topMargin = dp(8f)
            previewParams.leftMargin = dp(16f)
            previewParams.rightMargin = dp(16f)
            holder.addView(preview, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            root.addView(holder, previewParams)
        }

        body = Ui.column(this)
        val bodyPad = dp(16f)
        body.setPadding(bodyPad, dp(6f), bodyPad, dp(28f))
        content = ScrollView(this)
        content.isVerticalScrollBarEnabled = false
        content.addView(body, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val footer = buildFooter()
        if (footer != null) root.addView(footer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        buildContent()
        setContentView(root)
    }

    private fun buildHeader(): LinearLayout {
        val header = Ui.columnRow(this)
        header.setPadding(dp(12f), dp(10f), dp(12f), dp(6f))
        if (showBackButton()) {
            val back = iconButtonLabel(this, "←", getString(R.string.content_desc_back)) { onBackPressedCompat() }
            back.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 20f)
            header.addView(back)
        }
        val title = Ui.heading(this, screenTitle())
        val titleParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        titleParams.leftMargin = dp(if (showBackButton()) 8f else 4f)
        title.gravity = Gravity.START
        header.addView(title, titleParams)
        headerTitle = title
        headerAction()?.let { header.addView(it) }
        onHeaderReady(header)
        return header
    }

    open fun onBackPressedCompat() {
        @Suppress("DEPRECATION")
        onBackPressed()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    open fun buildContent() {}

    /** Optional fixed bar below the scrolling content (editor save row, etc). */
    open fun buildFooter(): View? = null

    open fun refresh() {
        topPreview?.let { preview ->
            designForPreview()?.let { preview.design = it }
            preview.invalidate()
        }
    }

    open fun designForPreview(): ai.techtroy.clockcanvas.ClockDesign? = null

    fun color(resId: Int): Int = resources.getColor(resId)

    fun dp(value: Float): Int = Ui.dp(this, value)

    fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    fun addSpacing(parent: LinearLayout, dpValue: Float) {
        parent.addView(Ui.gap(this, dpValue))
    }

    protected fun keepScreenOn(on: Boolean) {
        if (on) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    protected fun immersive(on: Boolean) {
        try {
            val decor = window.decorView
            if (on) {
                decor.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
                if (android.os.Build.VERSION.SDK_INT >= 28) {
                    window.attributes.layoutInDisplayCutoutMode =
                        android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            } else {
                decor.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
                if (android.os.Build.VERSION.SDK_INT >= 28) {
                    window.attributes.layoutInDisplayCutoutMode =
                        android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                }
            }
        } catch (e: Throwable) {
        }
    }
}

/** TextView icon-button helper kept here because half the screens need it. */
fun iconButtonLabel(context: Context, glyph: String, description: String, onClick: () -> Unit): TextView {
    val view = TextView(context)
    view.text = glyph
    view.setTextColor(Ui.color(context, R.color.text_secondary))
    view.gravity = Gravity.CENTER
    view.isClickable = true
    view.minWidth = Ui.dp(context, 44f)
    view.minHeight = Ui.dp(context, 48f)
    view.setPadding(Ui.dp(context, 8f), 0, Ui.dp(context, 8f), 0)
    view.contentDescription = description
    view.setOnClickListener { onClick() }
    return view
}
