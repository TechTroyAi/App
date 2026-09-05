package ai.techtroy.notebook.ui

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import ai.techtroy.notebook.R
import ai.techtroy.notebook.core.ThemeManager
import ai.techtroy.notebook.core.dp
import ai.techtroy.notebook.core.gold
import ai.techtroy.notebook.core.hairline
import ai.techtroy.notebook.core.muted
import ai.techtroy.notebook.core.roundRect
import ai.techtroy.notebook.core.textPrimary
import ai.techtroy.notebook.widget.ListWidget
import ai.techtroy.notebook.widget.NewNoteWidget
import ai.techtroy.notebook.widget.StickyWidget

/** Explains the three widgets and offers "Add to home screen" where the launcher supports pinning (API 26+). */
class WidgetsInfoActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TopBar.create(this, getString(R.string.nav_widgets)))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(4), dp(16), dp(30)) }
        col.addView(ScrollView(this).apply { addView(list) })
        setContentView(col)
        val awm = AppWidgetManager.getInstance(this)
        val canPin = Build.VERSION.SDK_INT >= 26 && awm.isRequestPinAppWidgetSupported
        fun card(title: String, size: String, desc: String, cls: Class<*>) {
            val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(16), dp(18), dp(16)); background = roundRect(ThemeManager.attr(this@WidgetsInfoActivity, R.attr.nbCard), 18f, this@WidgetsInfoActivity, hairline()) }
            c.addView(TextView(this).apply { text = size; setTextAppearance(R.style.TextAppearance_Notebook_Caps) })
            c.addView(TextView(this).apply { text = title; textSize = 17f; setTextColor(textPrimary()); typeface = android.graphics.Typeface.DEFAULT_BOLD; setPadding(0, dp(4), 0, dp(4)) })
            c.addView(TextView(this).apply { text = desc; textSize = 13f; setTextColor(muted()) })
            c.addView(TextView(this).apply {
                text = if (canPin) "Add to home screen" else "Long-press your home screen → Widgets → Notebook"
                textSize = 13f; setTextColor(if (canPin) 0xFF0B0B0C.toInt() else gold()); typeface = android.graphics.Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
                background = if (canPin) roundRect(gold(), 20f, this@WidgetsInfoActivity) else null; setPadding(dp(16), dp(10), dp(16), dp(10))
                if (canPin) setOnClickListener { awm.requestPinAppWidget(ComponentName(this@WidgetsInfoActivity, cls), null, null) }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
            list.addView(c, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        }
        card(getString(R.string.widget_sticky_label), "2×2 · 4×2", "Pins one note to your home screen. Tap to open. Locked notes stay hidden.", StickyWidget::class.java)
        card(getString(R.string.widget_list_label), "4×3 · scrollable", "Your latest notes, pinned first. Tap a row to open it.", ListWidget::class.java)
        card(getString(R.string.widget_new_label), "1×1", "One tap for a new text note. Long-press the widget's mic for a new voice note.", NewNoteWidget::class.java)
    }

    companion object {
        fun refreshWidgets(ctx: Context) {
            val awm = AppWidgetManager.getInstance(ctx)
            StickyWidget.updateAll(ctx, awm)
            ListWidget.notifyData(ctx, awm)
        }
    }
}
