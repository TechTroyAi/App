package ai.techtroy.clockcanvas.ui

import ai.techtroy.clockcanvas.ClockDesign
import ai.techtroy.clockcanvas.R
import ai.techtroy.clockcanvas.BackgroundKind
import ai.techtroy.clockcanvas.WidgetBucket
import ai.techtroy.clockcanvas.data.DesignStore
import ai.techtroy.clockcanvas.media.MediaHandler
import ai.techtroy.clockcanvas.render.ClockRenderer
import ai.techtroy.clockcanvas.widget.ClockWidgetUpdater
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The clock editor, and simultaneously the widget configuration screen: pass
 * `EXTRA_WIDGET_ID` (with `EXTRA_BIND_ONLY`) and the same UI turns into the
 * per-widget picker the launcher opens.
 *
 * That reuse is the point. A widget's design and a saved preset are the same
 * object, so "configure widget #7" and "edit the Midnight Purple preset" cannot
 * drift apart, and applying a design to one widget never rewrites the others.
 */
open class EditorActivity : ClockActivity(), EditorHost {

    override fun buildContent() {
        body.addView(previewStrip())
        body.addView(tabStrip(), Ui.cardParams(this, 12f))
        body.addView(sectionContainer, Ui.cardParams(this, 8f))
        rebuildSection()
    }

    private val store by lazy { DesignStore.of(this) }
    private val renderer by lazy { ClockRenderer.of(this) }
    private val media by lazy { MediaHandler.of(this) }
    private val sectionContainer: LinearLayout by lazy { Ui.column(this) }
    override lateinit var binding: DesignBinding
    private lateinit var picker: BackgroundPicker
    private var widgetId = INVALID_WIDGET
    private var bindOnly = false
    private var tab = TAB_DESIGN
    private var bucket = WidgetBucket.MEDIUM
    private var previewView: ClockCanvasView? = null
    private var bucketRow: LinearLayout? = null
    private var sectionScroll: android.widget.ScrollView? = null
    private var statusLabel: TextView? = null
    private var saveButton: View? = null
    private var footerRow: LinearLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // The design must exist before super.onCreate(): the base class builds the
        // screen hierarchy there, and every section reads through `binding`.
        widgetId = intent.getIntExtra(EXTRA_WIDGET_ID, INVALID_WIDGET)
        bindOnly = widgetId != INVALID_WIDGET && intent.getBooleanExtra(EXTRA_BIND_ONLY, false)
        tab = intent.getIntExtra(EXTRA_TAB, TAB_DESIGN)
        val initialDesign: ClockDesign = if (widgetId != INVALID_WIDGET) {
            store.designFor(widgetId)
        } else {
            store.resolveOrFallback(intent.getStringExtra(EXTRA_DESIGN_ID))
        }
        attachBinding(DesignBinding(initialDesign, store))
        picker = BackgroundPicker(this, ::onMediaPicked)
        super.onCreate(savedInstanceState)
    }

    /**
     * Point the screen at a design and wire the mutation callbacks. Called once at
     * startup and again when the launcher config path hands this widget its own
     * copy - see [buildBindOnlyNote].
     */
    private fun attachBinding(holder: DesignBinding) {
        binding = holder
        holder.onChange = { structural ->
            refresh()
            if (structural) rebuildSection()
            statusLabel?.text = if (holder.dirty) getString(R.string.editor_unsaved) else ""
            saveButton?.isEnabled = holder.dirty && !bindOnly
        }
        previewView?.design = holder.design
    }

    override fun screenTitle(): String = if (widgetId != INVALID_WIDGET) {
        getString(R.string.title_widget_config) + " #" + widgetId
    } else {
        getString(R.string.title_editor)
    }

    override fun showTopPreview(): Boolean = false

    // ---- chrome --------------------------------------------------------

    private fun previewStrip(): LinearLayout {
        val card = Ui.card(this)
        val chips = Ui.columnRow(this)
        bucketRow = chips
        for (label in EditorSections.BUCKET_LABELS) {
            val chip = Ui.chip(this, label, EditorSections.bucketForLabel(label) == bucket) {
                bucket = EditorSections.bucketForLabel(label)
                refreshBucketChips()
                refresh()
            }
            chip.tag = label
            Ui.addFilled(chips, chip, 0f)
        }
        Ui.add(card, chips, 0f)
        val preview = ClockCanvasView(this)
        previewView = preview
        preview.fullscreenMode = false
        preview.design = binding.design
        val frame = android.widget.FrameLayout(this)
        frame.setBackgroundColor(0xFF0B0913.toInt())
        frame.addView(preview, android.widget.FrameLayout.LayoutParams(-1, dp(150f)))
        Ui.add(card, frame, 10f)
        preview.onUserTap = { if (!bindOnly) openTab(TAB_DESIGN) }
        return card
    }

    private fun refreshBucketChips() {
        val row = bucketRow ?: return
        for (i in 0 until row.childCount) {
            val chip = row.getChildAt(i) as? TextView ?: continue
            val label = chip.tag as? String ?: continue
            val selected = EditorSections.bucketForLabel(label) == bucket
            chip.isSelected = selected
            chip.text = if (selected) "✓  $label" else label
            Ui.describeSelectable(chip, label, selected)
        }
    }

    private fun tabStrip(): android.widget.HorizontalScrollView {
        val labels = mutableListOf(
            getString(R.string.tab_design),
            getString(R.string.tab_text),
            getString(R.string.tab_background),
            getString(R.string.tab_border),
            getString(R.string.tab_layout),
            getString(R.string.tab_preview),
            getString(R.string.tab_widgets)
        )
        val row = Ui.columnRow(this)
        row.setBackgroundResource(R.drawable.panel_background)
        val pad = dp(10f)
        row.setPadding(pad, pad, pad, pad)
        val scroll = android.widget.HorizontalScrollView(this)
        for ((index, label) in labels.withIndex()) {
            val chip = Ui.chip(this, label, index == tab) { openTab(index) }
            chip.tag = index
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            if (index > 0) params.leftMargin = dp(6f)
            row.addView(chip, params)
        }
        scroll.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        return scroll
    }

    private fun openTab(index: Int) {
        tab = index
        rebuildSection()
        refreshTabs(body)
    }

    private fun refreshTabs(container: LinearLayout) {
        val strip = container.getChildAtOrNull(1) as? android.widget.HorizontalScrollView ?: return
        val row = strip.getChildAt(0) as? LinearLayout ?: return
        for (i in 0 until row.childCount) {
            val chip = row.getChildAt(i) as? TextView ?: continue
            val index = chip.tag as? Int ?: continue
            chip.isSelected = index == tab
            chip.setTextColor(if (index == tab) Color.WHITE else color(R.color.text_secondary))
        }
    }

    private fun LinearLayout.getChildAtOrNull(index: Int): View? = if (index < childCount) getChildAt(index) else null

    private fun rebuildSection() {
        val container = sectionContainer
        container.removeAllViews()
        when (tab) {
            TAB_DESIGN -> EditorSections.buildDesign(this, container)
            TAB_TEXT -> EditorSections.buildText(this, container)
            TAB_BACKGROUND -> if (bindOnly) buildBindOnlyNote(container) else EditorSections.buildBackground(this, container)
            TAB_BORDER -> EditorSections.buildBorder(this, container)
            TAB_LAYOUT -> EditorSections.buildLayout(this, container)
            TAB_PREVIEW -> EditorSections.buildPreview(this, container)
            else -> if (bindOnly) buildBindOnlyNote(container) else EditorSections.buildWidgets(this, container)
        }
        statusLabel?.text = if (binding.dirty) getString(R.string.editor_unsaved) else ""
        saveButton?.isEnabled = binding.dirty && !bindOnly
    }

    private fun buildBindOnlyNote(container: LinearLayout) {
        val card = EditorSections.sectionCard(this, "Widget #" + widgetId, null)
        Ui.add(card, Ui.body(this, getString(R.string.config_intro, widgetId)), 0f)
        val designs = store.all()
        for (design in designs) {
            val row = Ui.columnRow(this)
            val thumb = ImageView(this)
            thumb.scaleType = ImageView.ScaleType.FIT_CENTER
            thumb.setBackgroundResource(R.drawable.preview_tile)
            try {
                thumb.setImageBitmap(renderer.render(design, 150, 92))
            } catch (e: Throwable) {
            }
            row.addView(thumb, LinearLayout.LayoutParams(dp(96f), dp(58f)))
            row.addView(
                Ui.text(this, design.name, 14f, color(R.color.text_primary), true),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(12f) }
            )
            row.isClickable = true
            row.setOnClickListener {
                store.bind(widgetId, design.id)
                ClockWidgetUpdater.updateAll(this)
                setResult(RESULT_OK)
                toast(getString(R.string.config_bound, widgetId, design.name))
                finish()
            }
            Ui.add(card, row, 12f)
        }
        Ui.add(card, Ui.ghostButton(this, getString(R.string.editor_customize_widget)) {
            // Two widgets can legitimately share one design, so editing must not
            // silently rewrite the shared object: when this design is bound
            // somewhere else too, give this widget its own copy first.
            if (!ensureOwnCopy()) return@ghostButton
            bindOnly = false
            syncFooter()
            openTab(TAB_DESIGN)
        }, 14f)
        Ui.add(card, Ui.caption(card.context, getString(R.string.editor_customize_hint)), 6f)
        container.addView(card, Ui.cardParams(this))
    }

    /**
     * True when the editor may now mutate freely. Returns false if the copy could
     * not be created (a duplicate that vanished, a full disk), because editing a
     * shared design would then surprise the user later.
     */
    private fun ensureOwnCopy(): Boolean {
        if (widgetId == INVALID_WIDGET) return true
        val shared = store.boundWidgetIds(binding.design.id).any { it != widgetId }
        if (!shared) return true
        val copy = store.duplicate(binding.design.id)
        if (copy == null) {
            toast(getString(R.string.error_generic))
            return false
        }
        val owned = copy.copyWith { it.name = copy.name + " (#" + widgetId + ")" }
        store.upsert(owned)
        store.bind(widgetId, owned.id)
        attachBinding(DesignBinding(owned, store))
        return true
    }

    override fun buildFooter(): View? {
        val footer = Ui.columnRow(this)
        footer.setBackgroundColor(color(R.color.bg_secondary))
        val pad = dp(14f)
        footer.setPadding(pad, pad, pad, pad)
        val column = Ui.column(this)
        statusLabel = Ui.text(this, "", 12f, color(R.color.warning), true)
        column.addView(statusLabel)
        footer.addView(column, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val actions = Ui.columnRow(this)
        footerRow = actions
        footer.addView(actions, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        syncFooter()
        return footer
    }

    /** The footer is built once; "Customise this widget" flips it from Cancel to Save. */
    private fun syncFooter() {
        val row = footerRow ?: return
        row.removeAllViews()
        if (bindOnly) {
            saveButton = null
            row.addView(Ui.ghostButton(this, getString(R.string.config_cancel)) { finish() })
        } else {
            val save = Ui.primaryButton(this, getString(R.string.editor_save)) { saveDesign() }
            save.isEnabled = ::binding.isInitialized && binding.dirty
            saveButton = save
            row.addView(save)
        }
    }

    private fun saveDesign() {
        val saved = binding.save()
        store.upsert(saved)
        if (widgetId != INVALID_WIDGET) store.bind(widgetId, saved.id)
        ClockRenderer.of(this).clearCaches()
        ClockWidgetUpdater.updateAll(this)
        setResult(RESULT_OK)
        toast(getString(R.string.editor_saved))
        refresh()
    }

    private fun onMediaPicked(uri: String, isVideo: Boolean) {
        binding.structural {
            it.mediaUri = uri
            it.mediaIsVideo = isVideo
            if (isVideo) {
                it.backgroundKind = BackgroundKind.VIDEO_THUMBNAIL
            } else if (it.backgroundKind == BackgroundKind.TRANSPARENT || it.backgroundKind == BackgroundKind.SOLID || it.backgroundKind == BackgroundKind.GRADIENT) {
                it.backgroundKind = BackgroundKind.IMAGE
            }
        }
        media.addRecent(uri, isVideo)
    }

    // ---- EditorHost ----------------------------------------------------

    override val editorContext: Context get() = this

    override fun pickImage() {
        picker.pickImage()
    }

    override fun pickVideo() {
        picker.pickVideo()
    }

    override fun takePhoto() {
        picker.takePhoto()
    }

    override fun clearMedia() {
        binding.structural {
            it.mediaUri = null
            it.mediaIsVideo = false
            it.backgroundKind = BackgroundKind.GRADIENT
        }
    }

    override fun setMedia(uri: String, isVideo: Boolean) {
        onMediaPicked(uri, isVideo)
    }

    override fun loadPosterInto(view: ImageView) {
        val design = binding.design
        val uri = design.mediaUri
        if (uri == null) {
            view.setImageDrawable(null)
            return
        }
        try {
            val poster = ai.techtroy.clockcanvas.media.MediaAccess.posterFor(this, uri, design.mediaIsVideo, dp(220f))
            if (poster != null) view.setImageBitmap(poster) else view.setImageDrawable(null)
        } catch (e: Throwable) {
            view.setImageDrawable(null)
        }
    }

    override fun renderPreview(bucket: WidgetBucket): Bitmap? {
        val (widthDp, heightDp) = EditorSections.sizeForBucket(bucket)
        return try {
            renderer.render(binding.design, widthDp, heightDp)
        } catch (e: Throwable) {
            null
        }
    }

    override fun installedWidgets(): List<String> {
        val manager = getSystemService(Context.APPWIDGET_SERVICE) as android.appwidget.AppWidgetManager
        val ids = manager.getAppWidgetIds(ai.techtroy.clockcanvas.widget.ClockCanvasWidgetProvider.componentName(this))
        return ids.map { id ->
            val binding = store.binding(id)
            val designName = store.byId(binding?.designId ?: "")?.name ?: "default"
            WidgetLabel.format(this, id, designName, binding?.lastBucketWidthDp ?: 0, binding?.lastBucketHeightDp ?: 0)
        }
    }

    override fun applyToAll() {
        val saved = binding.save()
        val manager = getSystemService(Context.APPWIDGET_SERVICE) as android.appwidget.AppWidgetManager
        val ids = manager.getAppWidgetIds(ai.techtroy.clockcanvas.widget.ClockCanvasWidgetProvider.componentName(this))
        for (id in ids) store.bind(id, saved.id)
        ClockWidgetUpdater.updateAll(this)
        toast(if (ids.isEmpty()) "No widgets installed yet" else "Applied to ${ids.size} widget(s)")
    }

    override fun canOpenComposePreview(): Boolean {
        return try {
            Class.forName(ComposeBridge.PREVIEW_CLASS)
            true
        } catch (e: Throwable) {
            false
        }
    }

    override fun openComposePreview() {
        if (!canOpenComposePreview()) {
            toast("The Compose preview is not in this build.")
            return
        }
        try {
            startActivity(
                Intent(this, Class.forName(ComposeBridge.PREVIEW_CLASS))
                    .putExtra(ComposeBridge.EXTRA_DESIGN_ID, binding.design.id)
            )
        } catch (e: Throwable) {
            toast("Could not open the preview.")
        }
    }

    override fun refresh() {
        super.refresh()
        previewView?.design = binding.design
        previewView?.requestMediaReload()
        previewView?.invalidate()
    }

    override fun designForPreview(): ClockDesign = binding.design

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        picker.onActivityResult(requestCode, resultCode, data)
    }

    companion object {
        const val EXTRA_DESIGN_ID = "design_id"
        const val EXTRA_WIDGET_ID = "widget_id"
        const val EXTRA_BIND_ONLY = "bind_only"
        const val EXTRA_TAB = "tab"
        const val INVALID_WIDGET = -1
        const val TAB_DESIGN = 0
        const val TAB_TEXT = 1
        const val TAB_BACKGROUND = 2
        const val TAB_BORDER = 3
        const val TAB_LAYOUT = 4
        const val TAB_PREVIEW = 5
        const val TAB_WIDGETS = 6
    }
}

/** One place to decide how an installed widget is described in the UI. */
object WidgetLabel {
    fun format(context: Context, id: Int, designName: String, widthDp: Int, heightDp: Int): String {
        val size = if (widthDp > 0 && heightDp > 0) "${widthDp}×${heightDp} dp" else "size pending"
        return ai.techtroy.clockcanvas.WidgetConfiguration.bindingLabel(id, designName, widthDp, heightDp) + " · $size"
    }
}
