package ai.techtroy.clockcanvas.ui

import ai.techtroy.clockcanvas.ClockDesign
import ai.techtroy.clockcanvas.R
import ai.techtroy.clockcanvas.data.DesignStore
import ai.techtroy.clockcanvas.render.ClockRenderer
import ai.techtroy.clockcanvas.widget.ClockWidgetUpdater
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The dashboard: saved designs, and a one-tap route to each of the three ways this
 * app can show a clock (widget, full screen, live wallpaper).
 *
 * Thumbnails are rendered with the same [ClockRenderer] the widget uses, so a card
 * shows the real responsive result - not a screenshot from design time.
 */
class HomeActivity : ClockActivity() {

    private val store by lazy { DesignStore.of(this) }
    private val renderer by lazy { ClockRenderer.of(this) }
    private var gallery: LinearLayout? = null
    private var widgetStatus: TextView? = null

    override fun screenTitle(): String = getString(R.string.app_name)

    override fun showBackButton(): Boolean = false

    override fun headerAction(): View {
        val settings = Ui.chip(this, getString(R.string.home_settings), false) {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        settings.setTextColor(color(R.color.text_primary))
        return settings
    }

    override fun buildContent() {
        val body = body
        body.addView(heroCard())
        body.addView(designsSection())
        body.addView(routesSection())
        body.addView(installedWidgetsCard())
        body.addView(Ui.card(this).apply {
            Ui.add(this, Ui.caption(this@HomeActivity, getString(R.string.home_hint_add)), 0f)
        }, Ui.cardParams(this))
    }

    private fun heroCard(): LinearLayout {
        val card = Ui.card(this, elevated = true)
        Ui.add(card, Ui.title(this, getString(R.string.app_tagline)), 0f)
        Ui.add(card, Ui.caption(this, getString(R.string.home_create_hint)), 6f)
        val holder = ImageView(this)
        holder.scaleType = ImageView.ScaleType.FIT_XY
        val heroDesign = store.resolveOrFallback(AppPrefsHolder.last(this))
        try {
            holder.setImageBitmap(renderer.render(heroDesign, 190, 96))
        } catch (e: Throwable) {
        }
        holder.setBackgroundColor(0xFF100D18.toInt())
        holder.contentDescription = "Latest design: " + heroDesign.name
        val create = Ui.primaryButton(this, getString(R.string.home_create)) { openEditor(null) }
        create.contentDescription = getString(R.string.content_desc_create)
        val row = Ui.columnRow(this)
        row.addView(holder, LinearLayout.LayoutParams(0, dp(78f), 1.4f))
        row.addView(create, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(12f)
        })
        Ui.add(card, row, 14f)
        return card
    }

    private fun designsSection(): LinearLayout {
        val card = Ui.card(this)
        val header = Ui.columnRow(this)
        header.addView(
            Ui.title(this, getString(R.string.home_designs)),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        header.addView(Ui.chip(this, "+", false) { openEditor(null) })
        Ui.add(card, header, 0f)
        val gallery = LinearLayout(this)
        gallery.orientation = LinearLayout.VERTICAL
        this.gallery = gallery
        Ui.add(card, gallery, 6f)
        return card
    }

    private fun routesSection(): LinearLayout {
        val card = Ui.card(this)
        Ui.add(card, routeRow(R.string.home_fullscreen, R.string.home_fullscreen_hint) {
            startActivity(Intent(this, FullscreenClockActivity::class.java))
        }, 0f)
        Ui.add(card, divider(), 12f)
        Ui.add(card, routeRow(R.string.home_wallpaper, R.string.home_wallpaper_hint) {
            startActivity(Intent(this, WallpaperSetupActivity::class.java))
        }, 4f)
        Ui.add(card, divider(), 12f)
        Ui.add(card, routeRow(R.string.home_widgets, R.string.home_hint_add) {
            startActivity(Intent(this, EditorActivity::class.java).putExtra(EditorActivity.EXTRA_TAB, EditorActivity.TAB_WIDGETS))
        }, 4f)
        return card
    }

    private fun divider(): View {
        val line = View(this)
        line.setBackgroundColor(color(R.color.border))
        line.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1f))
        return line
    }

    private fun routeRow(titleRes: Int, hintRes: Int, onClick: () -> Unit): View {
        val row = Ui.columnRow(this)
        val column = Ui.column(this)
        column.addView(Ui.text(this, getString(titleRes), 15f, color(R.color.text_primary), true))
        column.addView(Ui.text(this, getString(hintRes), 12f, color(R.color.text_muted)))
        row.addView(column, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Ui.text(this, "›", 22f, color(R.color.lavender_soft), true))
        row.isClickable = true
        row.contentDescription = getString(titleRes)
        row.setOnClickListener { onClick() }
        row.setMinimumHeight(dp(56f))
        return row
    }

    private fun installedWidgetsCard(): LinearLayout {
        val card = Ui.card(this)
        val label = TextView(this)
        label.setTextColor(color(R.color.text_secondary))
        label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
        widgetStatus = label
        Ui.add(card, label, 0f)
        val actions = Ui.columnRow(this)
        actions.addView(Ui.ghostButton(this, "Re-render all") {
            ClockRenderer.of(this).clearCaches()
            ClockWidgetUpdater.updateAll(this)
            toast("Widget frames refreshed")
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(Ui.ghostButton(this, "Pin widget") {
            requestPin()
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(8f)
        })
        Ui.add(card, actions, 10f)
        return card
    }

    private fun requestPin() {
        if (Build.VERSION.SDK_INT < 26) {
            toast("Pinning needs Android 8.0; drag the widget from the picker instead.")
            return
        }
        try {
            val manager = getSystemService(Context.APPWIDGET_SERVICE) as android.appwidget.AppWidgetManager
            if (!manager.isRequestPinAppWidgetSupported) {
                toast("This launcher does not accept pin requests - drag it from the widget picker.")
                return
            }
            val success = manager.requestPinAppWidget(
                ai.techtroy.clockcanvas.widget.ClockCanvasWidgetProvider.componentName(this),
                null,
                null
            )
            toast(if (success) "Look for the widget on your home screen." else "The launcher refused the request.")
        } catch (e: Throwable) {
            toast("Could not pin the widget here.")
        }
    }

    override fun onResume() {
        super.onResume()
        renderGallery()
        updateWidgetCount()
    }

    private fun updateWidgetCount() {
        val count = try {
            val manager = getSystemService(Context.APPWIDGET_SERVICE) as android.appwidget.AppWidgetManager
            manager.getAppWidgetIds(ai.techtroy.clockcanvas.widget.ClockCanvasWidgetProvider.componentName(this)).size
        } catch (e: Throwable) {
            0
        }
        widgetStatus?.text = getString(R.string.settings_widgets_installed, count)
    }

    private fun renderGallery() {
        val gallery = this.gallery ?: return
        gallery.removeAllViews()
        val designs = store.all()
        if (designs.isEmpty()) {
            gallery.addView(Ui.body(this, getString(R.string.home_empty)))
            return
        }
        var row: LinearLayout? = null
        for ((index, design) in designs.withIndex()) {
            if (index % 2 == 0) {
                row = LinearLayout(this)
                row.orientation = LinearLayout.HORIZONTAL
                gallery.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = if (index == 0) 0 else dp(10f)
                })
            }
            row?.addView(designCard(design), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (index % 2 == 1) leftMargin = dp(10f)
            })
            row?.let { balanceRow(it) }
        }
    }

    private fun balanceRow(row: LinearLayout) {
        if (row.childCount == 1) {
            val filler = View(this)
            row.addView(filler, LinearLayout.LayoutParams(0, 1, 1f))
        }
    }

    private fun designCard(design: ClockDesign): View {
        val card = Ui.card(this)
        val image = ImageView(this)
        image.scaleType = ImageView.ScaleType.FIT_CENTER
        image.setBackgroundColor(Color.TRANSPARENT)
        val thumb = try {
            renderer.render(design, 168, 104)
        } catch (e: Throwable) {
            null
        }
        if (thumb != null && !thumb.isRecycled) image.setImageBitmap(thumb)
        image.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(74f))
        image.contentDescription = "Preview of " + design.name
        card.addView(image)
        Ui.add(card, Ui.text(this, design.name, 13.5f, color(R.color.text_primary), true), 10f)
        Ui.add(card, Ui.text(this, summary(design), 11.5f, color(R.color.text_muted)), 2f)
        val actions = Ui.columnRow(this)
        actions.addView(Ui.chip(this, "Edit", false) { openEditor(design.id) })
        actions.addView(Ui.chip(this, "⋯", false) { showDesignMenu(design) }.apply {
            (layoutParams as? LinearLayout.LayoutParams)?.leftMargin = dp(6f)
        })
        Ui.add(card, actions, 10f)
        card.isClickable = true
        card.contentDescription = design.name
        card.setOnClickListener { openEditor(design.id) }
        card.setOnLongClickListener {
            showDesignMenu(design)
            true
        }
        return card
    }

    private fun summary(design: ClockDesign): String {
        val style = design.style.name.lowercase().replace('_', ' ')
        val bg = when (design.backgroundKind) {
            ai.techtroy.clockcanvas.BackgroundKind.TRANSPARENT -> "clear"
            ai.techtroy.clockcanvas.BackgroundKind.SOLID -> "solid"
            ai.techtroy.clockcanvas.BackgroundKind.GRADIENT -> "gradient"
            ai.techtroy.clockcanvas.BackgroundKind.VIDEO_THUMBNAIL -> "video frame"
            else -> "photo"
        }
        return "$style · $bg"
    }

    private fun openEditor(designId: String?) {
        val intent = Intent(this, EditorActivity::class.java)
        if (designId != null) intent.putExtra(EditorActivity.EXTRA_DESIGN_ID, designId)
        startActivity(intent)
    }

    private fun showDesignMenu(design: ClockDesign) {
        val items = arrayOf(
            getString(R.string.action_rename),
            getString(R.string.action_duplicate),
            getString(R.string.action_share),
            getString(R.string.home_fullscreen),
            getString(R.string.action_delete),
        )
        AlertDialog.Builder(this)
            .setTitle(design.name)
            .setItems(items) { _, which ->
                when (items[which]) {
                    getString(R.string.action_rename) -> promptRename(design)
                    getString(R.string.action_duplicate) -> {
                        store.duplicate(design.id)
                        renderGallery()
                    }
                    getString(R.string.action_share) -> shareDesign(design)
                    getString(R.string.home_fullscreen) -> startActivity(
                        Intent(this, FullscreenClockActivity::class.java)
                            .putExtra(FullscreenClockActivity.EXTRA_DESIGN_ID, design.id)
                    )
                    else -> confirmDelete(design)
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun promptRename(design: ClockDesign) {
        val field = EditText(this)
        field.setText(design.name)
        field.setSingleLine(true)
        field.setSelection(design.name.length)
        AlertDialog.Builder(this)
            .setTitle(R.string.action_rename)
            .setView(field)
            .setPositiveButton(R.string.action_ok) { _, _ ->
                val name = field.text.toString().trim().ifEmpty { design.name }
                store.upsert(design.copyWith { it.name = name })
                renderGallery()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun confirmDelete(design: ClockDesign) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.confirm_delete, design.name))
            .setPositiveButton(R.string.action_delete) { _, _ ->
                store.delete(design.id)
                renderGallery()
                ClockWidgetUpdater.updateAll(this)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun shareDesign(design: ClockDesign) {
        try {
            val payload = ai.techtroy.clockcanvas.data.DesignCodec.encodeAll(listOf(design))
            val intent = Intent(Intent.ACTION_SEND)
            intent.type = "text/plain"
            intent.putExtra(Intent.EXTRA_TEXT, payload)
            startActivity(Intent.createChooser(intent, "Share design"))
        } catch (e: Throwable) {
            toast("Nothing to share here.")
        }
    }

    /** Small helper so the hero card knows which design to show first. */
    private object AppPrefsHolder {
        fun last(context: Context): String =
            ai.techtroy.clockcanvas.data.AppPrefs.of(context).load().lastDesignId
    }
}
