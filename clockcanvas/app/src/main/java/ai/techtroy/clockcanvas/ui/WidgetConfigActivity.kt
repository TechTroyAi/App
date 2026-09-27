package ai.techtroy.clockcanvas.ui

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle

/**
 * The activity the launcher opens when a ClockCanvas widget is dropped on the
 * home screen (`android:configure` in res/xml/widget_clock.xml).
 *
 * It is a thin shell over [EditorActivity] in bind-only mode, for one reason: the
 * first thing a user does after adding a widget must be "which of my designs is
 * this?" and a widget must be able to answer that without duplicating the
 * editor. On exit we always return RESULT_OK, because the widget is usable even
 * with the default design - a cancelled configuration should not delete the
 * widget the user just placed.
 */
class WidgetConfigActivity : EditorActivity() {

    private var resolvedWidgetId = INVALID_WIDGET

    override fun onCreate(savedInstanceState: Bundle?) {
        resolvedWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID, INVALID_WIDGET
        ) ?: INVALID_WIDGET
        if (resolvedWidgetId != INVALID_WIDGET) {
            intent.putExtra(EXTRA_WIDGET_ID, resolvedWidgetId)
            intent.putExtra(EXTRA_BIND_ONLY, true)
        }
        // Report "cancelled but usable" if the process is torn down mid-config.
        setResult(RESULT_OK, resultIntent())
        super.onCreate(savedInstanceState)
    }

    private fun resultIntent(): Intent = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, resolvedWidgetId)

    override fun finish() {
        setResult(RESULT_OK, resultIntent())
        super.finish()
    }
}
