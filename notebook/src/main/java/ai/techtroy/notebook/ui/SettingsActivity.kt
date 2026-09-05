package ai.techtroy.notebook.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import ai.techtroy.notebook.BuildConfig
import ai.techtroy.notebook.R
import ai.techtroy.notebook.core.Backup
import ai.techtroy.notebook.core.FileStore
import ai.techtroy.notebook.core.Theme
import ai.techtroy.notebook.core.ThemeManager
import ai.techtroy.notebook.core.dp
import ai.techtroy.notebook.core.hairline
import ai.techtroy.notebook.core.muted
import ai.techtroy.notebook.core.roundRect
import ai.techtroy.notebook.core.textPrimary
import ai.techtroy.notebook.core.toast
import ai.techtroy.notebook.data.BodyType
import ai.techtroy.notebook.data.SortMode
import ai.techtroy.notebook.echo.EasterEggActivity
import ai.techtroy.notebook.sys.Lock

class SettingsActivity : BaseActivity() {
    private lateinit var content: LinearLayout
    private var eggTaps = 0
    private var eggLastTap = 0L

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> if (uri != null) doExport(uri) }
    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) confirmImport(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TopBar.create(this, getString(R.string.settings)))
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(40)) }
        col.addView(ScrollView(this).apply { addView(content); isVerticalScrollBarEnabled = false })
        setContentView(col)
        build()
    }

    override fun onResume() { super.onResume(); build() }

    private fun build() {
        content.removeAllViews()
        val p = app.prefs
        header(R.string.settings_appearance)
        group {
            choice(getString(R.string.settings_theme), listOf(getString(R.string.theme_black_gold), getString(R.string.theme_ivory_gold), getString(R.string.theme_amoled)), p.theme.id) { i -> p.theme = Theme.from(i); ThemeManager.applyNightMode(p.theme); recreate() }
            choice(getString(R.string.settings_layout), listOf(getString(R.string.layout_grid), getString(R.string.layout_list)), if (p.gridLayout) 0 else 1) { p.gridLayout = it == 0 }
            choice(getString(R.string.settings_sort), listOf(getString(R.string.sort_edited), getString(R.string.sort_created), getString(R.string.sort_title), getString(R.string.sort_color)), p.sort.id) { p.sort = SortMode.from(it); p.sortAsc = it == 2 }
            choice(getString(R.string.settings_font_size), listOf(getString(R.string.font_small), getString(R.string.font_medium), getString(R.string.font_large), getString(R.string.font_xlarge)), p.fontSize) { p.fontSize = it }
            val types = listOf(getString(R.string.default_ask), getString(R.string.new_text_note), getString(R.string.new_checklist), getString(R.string.new_pages))
            val cur = when (p.defaultNoteType) { null -> 0; BodyType.TEXT -> 1; BodyType.CHECKLIST -> 2; BodyType.PAGES -> 3 }
            choice(getString(R.string.settings_default_note), types, cur, last = true) { p.defaultNoteType = when (it) { 1 -> BodyType.TEXT; 2 -> BodyType.CHECKLIST; 3 -> BodyType.PAGES; else -> null } }
        }
        header(R.string.settings_security)
        group {
            row(if (p.hasPin) getString(R.string.settings_change_pin) else getString(R.string.settings_set_pin), if (p.hasPin) "PIN set" else null) { LockActivity.setupPin(this) { if (it) toast(getString(R.string.done)); build() } }
            toggle(getString(R.string.settings_biometric), p.biometric, enabled = p.hasPin) { p.biometric = it }
            choice(getString(R.string.settings_autolock), listOf(getString(R.string.autolock_immediately), getString(R.string.autolock_1m), getString(R.string.autolock_5m), getString(R.string.autolock_screen_off)), p.autoLock, last = !p.hasPin) { p.autoLock = it; Lock.lockNow() }
            if (p.hasPin) row("Remove PIN", "Unlocks all locked notes", last = true, danger = true) { LockActivity.unlock(this) { app.async({ Lock.clearPin(p); app.repo.notes().filter { it.locked }.forEach { n -> app.repo.setLocked(n.id, false) } }) { build() } } }
        }
        header(R.string.settings_echoes)
        group { toggle(getString(R.string.settings_echo_daily), p.echoDaily, last = true) { p.echoDaily = it } }
        header(R.string.settings_data)
        group {
            val used = FileStore.humanSize(app.files.totalBytes())
            row(getString(R.string.storage_used), getString(R.string.storage_summary, used, app.repo.allCount())) {}
            toggle(getString(R.string.settings_include_attachments), p.backupIncludeAttachments) { p.backupIncludeAttachments = it }
            row(getString(R.string.settings_export), "Zip file you choose where to save") { exportLauncher.launch("notebook-backup-" + java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date()) + ".zip") }
            row(getString(R.string.settings_import), "Merge notes from a backup zip", last = true) { importLauncher.launch(arrayOf("application/zip", "application/octet-stream")) }
        }
        header(R.string.settings_about)
        group {
            row(getString(R.string.settings_version), "v" + BuildConfig.VERSION_NAME + " · with Echoes", last = true) { eggTap() }
        }
        content.addView(TextView(this).apply { text = "Notebook by Troy · offline · no accounts"; setTextColor(muted()); textSize = 11f; gravity = Gravity.CENTER; setPadding(0, dp(24), 0, 0); alpha = 0.7f })
    }

    private fun eggTap() {
        val now = System.currentTimeMillis()
        if (now - eggLastTap > 1500) eggTaps = 0
        eggLastTap = now; eggTaps++
        if (eggTaps in 4..6) toast("${7 - eggTaps}…")
        if (eggTaps >= 7) { eggTaps = 0; startActivity(Intent(this, EasterEggActivity::class.java)); overridePendingTransition(R.anim.fade_in, R.anim.fade_out) }
    }

    // ---------------------------------------------------------------- widgets

    private var currentGroup: LinearLayout? = null
    private fun header(res: Int) { content.addView(TextView(this).apply { text = getString(res); setTextAppearance(R.style.TextAppearance_Notebook_Caps); setPadding(dp(20), dp(22), dp(20), dp(8)) }) }
    private fun group(build: () -> Unit) {
        val g = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = roundRect(ThemeManager.attr(this@SettingsActivity, R.attr.nbCard), 18f, this@SettingsActivity, hairline()); clipToOutline = true }
        content.addView(g, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(16), 0, dp(16), 0) })
        currentGroup = g; build(); currentGroup = null
    }
    private fun rowBase(title: String, sub: String?, last: Boolean, danger: Boolean = false, trailing: View? = null, onClick: (() -> Unit)?): View {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(18), dp(14), dp(14), dp(14)); minimumHeight = dp(54)
            background = with(android.util.TypedValue()) { theme.resolveAttribute(android.R.attr.selectableItemBackground, this, true); getDrawable(resourceId) }
            if (onClick != null) setOnClickListener { onClick() }
        }
        r.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@SettingsActivity).apply { text = title; textSize = 15f; setTextColor(if (danger) getColor(R.color.danger) else textPrimary()) })
            if (sub != null) addView(TextView(this@SettingsActivity).apply { text = sub; textSize = 12f; setTextColor(muted()); setPadding(0, dp(2), 0, 0) })
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        trailing?.let { r.addView(it) }
        currentGroup!!.addView(r)
        if (!last) currentGroup!!.addView(View(this).apply { setBackgroundColor(hairline()) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply { marginStart = dp(18) })
        return r
    }
    private fun row(title: String, sub: String? = null, last: Boolean = false, danger: Boolean = false, onClick: () -> Unit) = rowBase(title, sub, last, danger, null, onClick)
    private fun toggle(title: String, value: Boolean, enabled: Boolean = true, last: Boolean = false, onChange: (Boolean) -> Unit) {
        val sw = MaterialSwitch(this).apply { isChecked = value; isEnabled = enabled; setOnCheckedChangeListener { _, v -> onChange(v) } }
        val r = rowBase(title, null, last, trailing = sw) { if (enabled) sw.toggle() }
        r.alpha = if (enabled) 1f else 0.5f
    }
    private fun choice(title: String, options: List<String>, current: Int, last: Boolean = false, onPick: (Int) -> Unit) {
        val value = TextView(this).apply { text = options.getOrNull(current) ?: ""; textSize = 13f; setTextColor(ai.techtroy.notebook.core.ThemeManager.attr(this@SettingsActivity, R.attr.nbGold)) }
        rowBase(title, null, last, trailing = value) {
            MaterialAlertDialogBuilder(this).setTitle(title).setSingleChoiceItems(options.toTypedArray(), current) { d, i -> d.dismiss(); onPick(i); build() }.show()
        }
    }

    // ----------------------------------------------------------------- backup

    private fun doExport(uri: Uri) {
        toast("Exporting…")
        app.async({ runCatching { contentResolver.openOutputStream(uri)!!.use { Backup.export(app, it, app.prefs.backupIncludeAttachments) } } }) { r ->
            r.onSuccess { toast(getString(R.string.backup_done)) }.onFailure { toast(getString(R.string.backup_failed, it.message ?: "?")) }
        }
    }

    private fun confirmImport(uri: Uri) {
        Sheets.confirm(this, getString(R.string.settings_import), "Notes in the backup are added to your notebook. Existing notes are kept.", getString(R.string.settings_import)) {
            toast("Importing…")
            app.async({ runCatching { contentResolver.openInputStream(uri)!!.use { Backup.import(app, it) } } }) { r ->
                r.onSuccess { toast(getString(R.string.restore_done, it)); build() }.onFailure { toast(getString(R.string.restore_failed, it.message ?: "?")) }
            }
        }
    }
}
