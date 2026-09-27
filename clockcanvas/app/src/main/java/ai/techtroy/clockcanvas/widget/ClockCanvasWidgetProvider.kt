package ai.techtroy.clockcanvas.widget

import ai.techtroy.clockcanvas.ClockDesign
import ai.techtroy.clockcanvas.R
import ai.techtroy.clockcanvas.data.DesignStore
import ai.techtroy.clockcanvas.render.ClockRenderer
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.RemoteViews

/**
 * The home-screen widget.
 *
 * Contract with the launcher:
 *  - `res/xml/widget_clock.xml` declares `minWidth/minHeight` of one cell and
 *    `resizeMode="horizontal|vertical"`, so the user can drag the widget to any
 *    size the *launcher* allows. We never promise an exact aspect ratio, because
 *    the launcher owns the final geometry.
 *  - Every size the launcher hands us arrives in `onAppWidgetOptionsChanged` as
 *    min/max cells; [ClockRenderer] classifies that into a [ai.techtroy.clockcanvas.WidgetBucket]
 *    and derives font size, padding, date visibility and crop from it.
 *  - Each `appWidgetId` has its own design binding (see DesignStore), so two
 *    widgets on the same screen can show two different clocks.
 *
 * Rendering is a single ImageView holding a Canvas-produced bitmap. That is the
 * only way to get custom fonts, outlines, glow, blur, corner radius and border
 * thickness into a RemoteViews layout, and it is drawn once per change - not per
 * animation frame - so it stays cheap.
 */
class ClockCanvasWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        ClockWidgetUpdater.update(context, appWidgetManager, appWidgetIds)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        val widthDp = newOptions.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
        val heightDp = newOptions.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
        DesignStore.of(context).rememberSize(appWidgetId, widthDp, heightDp)
        ClockWidgetUpdater.update(context, appWidgetManager, intArrayOf(appWidgetId))
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        // A deleted widget must not leave a binding row behind: the id space is
        // recycled by the launcher, and a stale binding would make a brand-new
        // widget show someone else's clock.
        val store = DesignStore.of(context)
        appWidgetIds.forEach { store.unbind(it) }
    }

    override fun onEnabled(context: Context) {
        ClockWidgetUpdater.startTickerIfEnabled(context)
    }

    override fun onDisabled(context: Context) {
        ClockWidgetUpdater.stopTicker(context)
    }

    /**
     * Device-to-device restore. This compiles against a stub jar that predates the
     * callback, so it is not marked `override`; on API 26+ the framework resolves it
     * by signature and calls it. `onUpdate` also binds unknown ids, which is what
     * actually covers a reboot restore on every launcher.
     */
    @Suppress("unused")
    fun onRestored(
        context: Context,
        newAppWidgetId: Int,
        oldAppWidgetId: Int,
        oldData: Bundle?
    ) {
        // Device-to-device restore: the binding lives in the app's own prefs, so
        // re-render once the new id is known.
        ClockWidgetUpdater.update(context, AppWidgetManager.getInstance(context), intArrayOf(newAppWidgetId))
    }

    companion object {
        fun componentName(context: Context) = ComponentName(context, ClockCanvasWidgetProvider::class.java)
    }
}
