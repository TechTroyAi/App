package ai.techtroy.clockcanvas.ui

import ai.techtroy.clockcanvas.R
import ai.techtroy.clockcanvas.data.DesignStore
import ai.techtroy.clockcanvas.media.MediaAccess
import ai.techtroy.clockcanvas.render.ClockRenderer
import ai.techtroy.clockcanvas.wallpaper.ClockWallpaperService
import ai.techtroy.clockcanvas.widget.ClockWidgetUpdater
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.app.WallpaperManager
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Prepares the live wallpaper and hands off to the system.
 *
 * Android only lets the *system* apply a wallpaper: `attachEngineToWallpaper` is
 * @hide, and [WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER] is the only public
 * route (and OEM-skinned launchers sometimes ignore the component extra). So this
 * screen owns the *content* of the wallpaper - design, clip, clock-only, motion -
 * in the preference file [ClockWallpaperService] reads, previews it with the same
 * renderer the engine uses, and then opens the system sheet with ClockCanvas named.
 * One tap in the sheet, and we never claim to have applied it ourselves.
 */
class WallpaperSetupActivity : ClockActivity() {

    private lateinit var preview: ClockCanvasView
    private lateinit var prefs: SharedPreferences
    private var holder: DesignBinding? = null
    private var mediaLabel: TextView? = null
    private val picker by lazy { BackgroundPicker(this) { uri, isVideo -> setMedia(uri, isVideo) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = getSharedPreferences(ClockWallpaperService.PREFS, Context.MODE_PRIVATE)
        super.onCreate(savedInstanceState)
    }

    override fun screenTitle(): String = getString(R.string.title_wallpaper)

    override fun designForPreview(): ai.techtroy.clockcanvas.ClockDesign? = holder?.design

    override fun buildContent() {
        val store = DesignStore.of(this)
        val design = store.byId(prefs.getString(ClockWallpaperService.KEY_DESIGN, null))
            ?: store.resolveOrFallback(null)
        holder = DesignBinding(design, store).also { it.onChange = { refresh() } }

        preview = ClockCanvasView(this).apply {
            this.design = design
            fullscreenMode = true
            transparentBackground = clockOnly()
        }
        val frame = Ui.card(this)
        val pad = dp(10f)
        frame.setPadding(pad, pad, pad, pad)
        frame.addView(
            preview,
            FrameLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(190f))
        )
        Ui.add(body, frame, 4f)
        Ui.add(body, Ui.caption(this, getString(R.string.wallpicker_hint)), 10f)
        Ui.add(body, designPicker(), Ui.CARD_GAP)
        Ui.add(body, mediaCard(), Ui.CARD_GAP)
        Ui.add(body, toggleFor(
            R.string.wallpaper_clock_only, R.string.wallpaper_clock_only_hint,
            ClockWallpaperService.KEY_CLOCK_ONLY, clockOnly()
        ) { preview.transparentBackground = it }, Ui.CARD_GAP)
        Ui.add(body, toggleFor(
            R.string.wallpaper_seconds, R.string.wallpaper_seconds_hint,
            ClockWallpaperService.KEY_SECONDS, secondsEnabled()
        ), Ui.CARD_GAP)
        Ui.add(body, toggleFor(
            R.string.wallpaper_motion, R.string.wallpaper_motion_hint,
            ClockWallpaperService.KEY_MOTION, motionEnabled()
        ), Ui.CARD_GAP)
        Ui.add(body, actionCard(), Ui.CARD_GAP)
    }

    override fun refresh() {
        super.refresh()
        val current = holder?.design ?: return
        preview.design = current
        preview.transparentBackground = clockOnly()
        preview.requestMediaReload()
        preview.invalidate()
        mediaLabel?.text = mediaSummary()
    }

    override fun onPause() {
        super.onPause()
        saveAll()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (!picker.onActivityResult(requestCode, resultCode, data)) super.onActivityResult(requestCode, resultCode, data)
    }

    // ---- controls -------------------------------------------------------------------

    private fun designPicker(): View {
        val designs = DesignStore.of(this).all()
        val labels = designs.map { it.name.ifEmpty { getString(R.string.app_name) } }
        if (labels.isEmpty()) {
            return EditorSections.sectionCard(this, getString(R.string.wallpaper_design), getString(R.string.home_empty))
        }
        return EditorSections.segmented(
            context = this,
            title = getString(R.string.wallpaper_design),
            options = labels,
            selectedIndex = { designs.indexOfFirst { it.id == currentDesignId() }.coerceAtLeast(0) },
            onSelect = { index ->
            designs.getOrNull(index)?.let { picked ->
                holder?.structural { source ->
                    source.id = picked.id
                    source.name = picked.name
                }
                saveAll()
                refresh()
            }
        },
    )
    }

    private fun mediaCard(): View {
        val card = EditorSections.sectionCard(this, getString(R.string.wallpaper_media), null)
        val label = Ui.body(this, mediaSummary())
        mediaLabel = label
        Ui.add(card, label, 0f)
        val row = Ui.columnRow(this)
        Ui.addFilled(row, Ui.ghostButton(this, getString(R.string.bg_video)) { picker.pickVideo() }, 10f)
        Ui.addFilled(row, Ui.ghostButton(this, getString(R.string.bg_photo)) { picker.pickImage() }, 10f)
        Ui.add(card, row, 8f)
        Ui.add(card, Ui.ghostButton(this, getString(R.string.bg_clear)) { clearMedia() }, 6f)
        Ui.add(card, Ui.caption(this, getString(R.string.bg_video_widget_note)), 8f)
        return card
    }

    private fun toggleFor(
        labelRes: Int,
        hintRes: Int,
        key: String,
        initial: Boolean,
        after: (Boolean) -> Unit = {},
    ): View = EditorSections.toggleCard(this, getString(labelRes), getString(hintRes), initial) { checked ->
        put(key, checked)
        after(checked)
    }

    private fun actionCard(): View {
        val card = Ui.card(this, elevated = true)
        Ui.add(card, Ui.title(this, getString(R.string.home_wallpaper)), 0f)
        Ui.add(card, Ui.body(this, getString(R.string.wallpaper_intro)), 6f)
        val row = Ui.columnRow(this)
        Ui.addFilled(row, Ui.primaryButton(this, getString(R.string.wallpaper_apply)) { applyWallpaper() }, 12f)
        Ui.addFilled(row, Ui.ghostButton(this, getString(R.string.wallpaper_open_settings)) { openWallpaperSettings() }, 12f)
        Ui.add(card, row, 8f)
        return card
    }

    // ---- hand-off ---------------------------------------------------------------------

    private fun applyWallpaper() {
        saveAll()
        val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
            .putExtra(
                WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                ComponentName(packageName, ClockWallpaperService::class.java.name)
            )
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (e: Throwable) {
            openWallpaperSettings()
        }
    }

    private fun openWallpaperSettings() {
        saveAll()
        try {
            startActivity(Intent("android.intent.action.SET_WALLPAPER").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Throwable) {
            toast(getString(R.string.wallpicker_failed))
        }
    }

    // ---- persistence -------------------------------------------------------------------

    private fun setMedia(uri: String, isVideo: Boolean) {
        holder?.update { design ->
            design.mediaUri = uri
            design.mediaIsVideo = isVideo
        }
        put(ClockWallpaperService.KEY_MEDIA, uri)
        put(ClockWallpaperService.KEY_IS_VIDEO, isVideo)
        mediaLabel?.text = mediaSummary()
        preview.requestMediaReload()
        preview.invalidate()
    }

    private fun clearMedia() {
        holder?.update { design -> design.mediaUri = null }
        prefs.edit()
            .remove(ClockWallpaperService.KEY_MEDIA)
            .remove(ClockWallpaperService.KEY_IS_VIDEO)
            .apply()
        mediaLabel?.text = mediaSummary()
        preview.requestMediaReload()
        preview.invalidate()
    }

    private fun saveAll() {
        val current = holder?.design ?: return
        put(ClockWallpaperService.KEY_DESIGN, current.id)
        current.mediaUri?.let {
            put(ClockWallpaperService.KEY_MEDIA, it)
            put(ClockWallpaperService.KEY_IS_VIDEO, current.mediaIsVideo)
        }
        // A running engine picks the change up on its next frame; widgets are
        // re-rendered too so both surfaces stay in step with the same design.
        ClockWidgetUpdater.updateAll(this)
    }

    private fun put(key: String, value: Any) {
        val editor = prefs.edit()
        when (value) {
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            else -> editor.putString(key, value.toString())
        }
        editor.apply()
    }

    private fun clockOnly(): Boolean = prefs.getBoolean(ClockWallpaperService.KEY_CLOCK_ONLY, false)
    private fun secondsEnabled(): Boolean = prefs.getBoolean(ClockWallpaperService.KEY_SECONDS, false)
    private fun motionEnabled(): Boolean = prefs.getBoolean(ClockWallpaperService.KEY_MOTION, true)
    private fun currentDesignId(): String = prefs.getString(ClockWallpaperService.KEY_DESIGN, "") ?: ""

    private fun mediaSummary(): String {
        val uri = prefs.getString(ClockWallpaperService.KEY_MEDIA, null) ?: return getString(R.string.bg_none)
        val video = prefs.getBoolean(ClockWallpaperService.KEY_IS_VIDEO, false)
        val kind = if (video) getString(R.string.bg_video) else getString(R.string.bg_photo)
        return if (MediaAccess.canRead(this, uri)) {
            "$kind · " + uri.substringAfterLast('/').take(48)
        } else {
            getString(R.string.bg_media_missing)
        }
    }
}
