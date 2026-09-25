package ai.techtroy.clockcanvas.ui

import ai.techtroy.clockcanvas.ClockSettings
import ai.techtroy.clockcanvas.R
import ai.techtroy.clockcanvas.data.AppPrefs
import ai.techtroy.clockcanvas.data.DesignStore
import ai.techtroy.clockcanvas.io.LocalFileProvider
import ai.techtroy.clockcanvas.widget.ClockWidgetUpdater
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Everything that is not a design: hour and date format, how the widgets are kept
 * on time, and the data controls (export, import, reset, licences).
 *
 * Deliberately *not* here: a "first day of week" switch. Nothing in v1 renders a
 * calendar or a week grid, so the value is stored in [ClockSettings] (and travels
 * in exports) but has no surface - see README.
 */
class SettingsActivity : ClockActivity() {

    private var hourCard: View? = null
    private var dateCard: View? = null
    private var widgetCount: TextView? = null

    override fun screenTitle(): String = getString(R.string.title_settings)

    override fun buildContent() {
        val prefs = AppPrefs.of(this).load()

        // ---- time and date --------------------------------------------------------
        val time = EditorSections.sectionCard(this, getString(R.string.settings_time), null)
        Ui.add(time, hourSelector(), 12f)
        Ui.add(time, dateSelector(), 12f)
        Ui.add(time, Ui.caption(this, getString(R.string.settings_date_hint)), 8f)
        Ui.add(body, time, Ui.CARD_GAP)

        val widgets = EditorSections.sectionCard(this, getString(R.string.settings_widgets), getString(R.string.settings_precise_hint))
        Ui.add(
            widgets,
            Ui.toggle(
                this,
                getString(R.string.settings_precise),
                null,
                prefs.preciseRefresh
            ) { checked ->
                AppPrefs.of(this).setPreciseRefresh(checked)
                if (checked) ClockWidgetUpdater.startTickerIfEnabled(this) else ClockWidgetUpdater.stopTicker(this)
                ClockWidgetUpdater.updateAll(this)
            },
            12f
        )
        val battery = Ui.ghostButton(this, getString(R.string.settings_battery)) { requestBatteryExemption() }
        Ui.add(widgets, battery, 12f)
        Ui.add(widgets, Ui.caption(this, getString(R.string.settings_battery_hint)), 8f)
        widgetCount = Ui.body(this, "")
        Ui.add(widgets, widgetCount!!, 12f)
        Ui.add(body, widgets, Ui.CARD_GAP)

        // ---- data ----------------------------------------------------------------
        val data = EditorSections.sectionCard(this, getString(R.string.settings_data), getString(R.string.settings_privacy))
        val row = Ui.columnRow(this)
        Ui.addFilled(row, Ui.primaryButton(this, getString(R.string.settings_export)) { exportDesigns() }, 12f)
        Ui.addFilled(row, Ui.ghostButton(this, getString(R.string.settings_import)) { importFromClipboard() }, 12f)
        Ui.add(data, row, 12f)
        Ui.add(
            data,
            Ui.ghostButton(this, getString(R.string.settings_reset)) { confirmReset() },
            8f
        )
        Ui.add(body, data, Ui.CARD_GAP)

        // ---- fonts and build -----------------------------------------------------
        val fonts = EditorSections.sectionCard(this, getString(R.string.settings_fonts), getString(R.string.settings_fonts_hint))
        Ui.add(fonts, Ui.ghostButton(this, getString(R.string.settings_licences)) { showLicences() }, 12f)
        Ui.add(body, fonts, Ui.CARD_GAP)

        val about = EditorSections.sectionCard(this, getString(R.string.settings_about), aboutLine())
        Ui.add(body, about, Ui.CARD_GAP)
    }

    override fun refresh() {
        super.refresh()
        hourCard?.let { rebuildHour(it) }
        dateCard?.let { rebuildDate(it) }
        widgetCount?.text = getString(R.string.settings_widgets_installed, ClockWidgetUpdater.installedCount(this))
    }

    // ---- controls -----------------------------------------------------------------

    private fun hourSelector(): View {
        val card = EditorSections.sectionCard(this, getString(R.string.settings_hour), null)
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        card.addView(row, Ui.cardParams(this, 10f))
        hourCard = row
        return card
    }

    private fun rebuildHour(row: View) {
        val container = row as? LinearLayout ?: return
        val current = AppPrefs.of(this).load().use24Hour
        container.removeAllViews()
        listOf(
            getString(R.string.settings_hour_system) to null,
            getString(R.string.settings_hour_12) to false,
            getString(R.string.settings_hour_24) to true,
        ).forEachIndexed { index, entry ->
            val chip = Ui.chip(this, entry.first, current == entry.second) {
                AppPrefs.of(this).set24Hour(entry.second)
                rebuildHour(container)
                ClockWidgetUpdater.updateAll(this)
                topPreview?.invalidate()
            }
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            if (index > 0) params.leftMargin = dp(6f)
            container.addView(chip, params)
        }
    }

    private fun dateSelector(): View {
        val card = EditorSections.sectionCard(this, getString(R.string.settings_dateformat), null)
        val column = Ui.column(this)
        card.addView(column, Ui.cardParams(this, 10f))
        dateCard = column
        return card
    }

    private fun rebuildDate(column: View) {
        val container = column as? LinearLayout ?: return
        val current = AppPrefs.of(this).load().dateFormat
        container.removeAllViews()
        ClockSettings.DATE_FORMAT_PRESETS.forEach { (pattern, example) ->
            val chip = Ui.chip(this, example, current == pattern) {
                AppPrefs.of(this).setDateFormat(pattern)
                rebuildDate(container)
                ClockWidgetUpdater.updateAll(this)
                topPreview?.invalidate()
            }
            chip.maxLines = 2
            Ui.add(container, chip, 6f)
        }
        Ui.add(container, Ui.caption(this, current), 6f)
    }

    // ---- actions -------------------------------------------------------------------

    /**
     * Writes the JSON into the exports root of [LocalFileProvider] so the share
     * sheet can read it without any storage permission.
     */
    private fun exportDesigns() {
        val json = DesignStore.of(this).exportAll()
        val file = java.io.File(cacheDir, "exports/clockcanvas-designs.json")
        try {
            file.parentFile?.mkdirs()
            file.writeText(json)
        } catch (e: Throwable) {
            toast(getString(R.string.error_generic))
            return
        }
        val uri = LocalFileProvider.uriFor(this, LocalFileProvider.authority(this), file)
        if (uri == null) {
            toast(getString(R.string.error_generic))
            return
        }
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(share, getString(R.string.settings_export)))
        } catch (e: ActivityNotFoundException) {
            toast(getString(R.string.settings_no_share_target))
        }
    }

    private fun importFromClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val text = clipboard?.primaryClip?.let { clip ->
            if (clip.itemCount > 0) clip.getItemAt(0).coerceToText(this).toString() else null
        }
        if (text.isNullOrBlank()) {
            toast(getString(R.string.settings_clipboard_empty))
            return
        }
        val added = try {
            DesignStore.of(this).importAll(text)
        } catch (e: Throwable) {
            0
        }
        if (added == 0) {
            toast(getString(R.string.settings_import_failed))
            return
        }
        ClockWidgetUpdater.updateAll(this)
        toast(getString(R.string.settings_imported, added))
    }

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_reset)
            .setMessage(R.string.confirm_reset)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                DesignStore.of(this).resetAll()
                AppPrefs.of(this).setLastDesign("")
                ClockWidgetUpdater.updateAll(this)
                toast(getString(R.string.settings_reset_done))
            }
            .show()
    }

    private fun requestBatteryExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            toast(getString(R.string.settings_battery_unavailable))
            return
        }
        val power = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (power != null && power.isIgnoringBatteryOptimizations(packageName)) {
            toast(getString(R.string.settings_battery_ignored))
            return
        }
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (e: Throwable) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: Throwable) {
                toast(getString(R.string.settings_battery_manual))
            }
        }
    }

    private fun showLicences() {
        val fonts = listOf(
            "inter_OFL.txt" to "Inter",
            "space_grotesk_OFL.txt" to "Space Grotesk",
            "bebas_neue_OFL.txt" to "Bebas Neue",
            "playfair_display_OFL.txt" to "Playfair Display",
            "source_code_pro_OFL.txt" to "Source Code Pro",
        )
        val text = StringBuilder()
        for ((asset, label) in fonts) {
            text.append("=== ").append(label).append(" ===\n")
            text.append(
                try {
                    assets.open("licenses/$asset").use { it.readBytes().toString(Charsets.UTF_8) }
                } catch (e: Throwable) {
                    "licence text not found in assets"
                }
            )
            text.append("\n\n")
        }
        val view = Ui.text(this, text, 12f, color(R.color.text_secondary))
        val pad = dp(20f)
        view.setPadding(pad, pad, pad, pad)
        val scroll = ScrollView(this)
        scroll.addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_licences)
            .setView(scroll)
            .setPositiveButton(R.string.action_ok, null)
            .show()
    }

    private fun aboutLine(): String = "ClockCanvas ${BuildConfig_label()}\n" +
        "Android API ${Build.VERSION.SDK_INT} · ${Build.MANUFACTURER} ${Build.MODEL}\n" +
        "designs: ${filesDir}/clockcanvas/designs"

    /** Kept off [BuildConfig] so the offline build path needs no generated class. */
    private fun BuildConfig_label(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (e: Throwable) {
        "?"
    }
}
