package ai.techtroy.notebook.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.isVisible
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import ai.techtroy.notebook.R
import ai.techtroy.notebook.core.circle
import ai.techtroy.notebook.core.dp
import ai.techtroy.notebook.core.gold
import ai.techtroy.notebook.core.hideKeyboard
import ai.techtroy.notebook.core.roundRect
import ai.techtroy.notebook.core.show
import ai.techtroy.notebook.core.snack
import ai.techtroy.notebook.data.BodyType
import ai.techtroy.notebook.data.Echo
import ai.techtroy.notebook.data.Folder
import ai.techtroy.notebook.data.Note
import ai.techtroy.notebook.data.NoteColor
import ai.techtroy.notebook.data.SortMode
import ai.techtroy.notebook.sys.Lock

class HomeActivity : BaseActivity() {

    private lateinit var drawer: DrawerLayout
    private lateinit var list: RecyclerView
    private lateinit var search: EditText
    private lateinit var chips: ChipGroup
    private lateinit var fab: FloatingActionButton
    private lateinit var fabMenu: View
    private lateinit var fabScrim: View
    private lateinit var adapter: NoteAdapter
    private lateinit var selectBar: View
    private lateinit var searchBar: View
    private lateinit var empty: View
    private lateinit var btnLayout: ImageButton

    /** null = all, -1 = unfiled, else folder id */
    private var folderFilter: Long? = null
    private var query = ""
    private var selected = LinkedHashSet<Long>()
    private var folders: List<Folder> = emptyList()
    private var fabOpen = false
    private var pendingNewType: BodyType? = null

    private val repoListener = { runOnUiThread { reload() } }

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        drawer = findViewById(R.id.drawer)
        list = findViewById(R.id.list)
        search = findViewById(R.id.search)
        chips = findViewById(R.id.chips)
        fab = findViewById(R.id.fab)
        fabMenu = findViewById(R.id.fabMenu)
        fabScrim = findViewById(R.id.fabScrim)
        selectBar = findViewById(R.id.selectBar)
        searchBar = findViewById(R.id.searchBar)
        empty = findViewById(R.id.empty)
        btnLayout = findViewById(R.id.btnLayout)

        findViewById<View>(R.id.topBar).apply { setPadding(paddingLeft, paddingTop, paddingRight, paddingBottom) }
        searchBar.background = roundRect(ai.techtroy.notebook.core.ThemeManager.attr(this, R.attr.nbCard), 25f, this, ai.techtroy.notebook.core.ThemeManager.attr(this, R.attr.nbHairline))
        selectBar.background = roundRect(ai.techtroy.notebook.core.ThemeManager.attr(this, R.attr.nbCardRaised), 25f, this, gold())

        adapter = NoteAdapter(isLightTheme, ::openNote, ::toggleSelect, ::openEcho, ::dismissEcho)
        adapter.grid = app.prefs.gridLayout
        applyLayoutManager()
        list.adapter = adapter
        list.itemAnimator?.changeDuration = 120

        findViewById<View>(R.id.btnMenu).setOnClickListener { hideKeyboard(); drawer.openDrawer(GravityCompat.START) }
        btnLayout.setOnClickListener { app.prefs.gridLayout = !app.prefs.gridLayout; adapter.grid = app.prefs.gridLayout; applyLayoutManager(); reload() }
        findViewById<View>(R.id.btnSort).setOnClickListener { showSortMenu(it) }
        findViewById<View>(R.id.btnClearSearch).setOnClickListener { search.setText(""); hideKeyboard() }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { query = s?.toString().orEmpty(); findViewById<View>(R.id.btnClearSearch).show(query.isNotEmpty()); reload() }
        })

        fab.setOnClickListener { if (fabOpen) closeFab() else openFab() }
        fab.setOnLongClickListener { createNote(app.prefs.defaultNoteType ?: BodyType.TEXT); true }
        fabScrim.setOnClickListener { closeFab() }
        setupMini(R.id.fabText, R.drawable.ic_text, R.string.new_text_note) { createNote(BodyType.TEXT) }
        setupMini(R.id.fabChecklist, R.drawable.ic_checklist, R.string.new_checklist) { createNote(BodyType.CHECKLIST) }
        setupMini(R.id.fabSketch, R.drawable.ic_sketch, R.string.new_sketch) { createSketchNote() }
        setupMini(R.id.fabPages, R.drawable.ic_pages, R.string.new_pages) { createNote(BodyType.PAGES) }

        findViewById<View>(R.id.selClose).setOnClickListener { clearSelection() }
        findViewById<View>(R.id.selAll).setOnClickListener { selectAll() }
        findViewById<View>(R.id.selPin).setOnClickListener { val ids = selected.toList(); app.async({ val anyUnpinned = ids.any { app.repo.note(it)?.pinned == false }; app.repo.setPinned(ids, anyUnpinned) }) { clearSelection() } }
        findViewById<View>(R.id.selColor).setOnClickListener { Sheets.colorPicker(this, null) { c -> val ids = selected.toList(); app.async({ app.repo.setColor(ids, c) }) { clearSelection() } } }
        findViewById<View>(R.id.selFolder).setOnClickListener { Sheets.folderPicker(this, folders, null) { fid -> val ids = selected.toList(); app.async({ app.repo.setFolder(ids, fid) }) { clearSelection() } } }
        findViewById<View>(R.id.selDelete).setOnClickListener { trashNotes(selected.toList()) }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    drawer.isDrawerOpen(GravityCompat.START) -> drawer.closeDrawers()
                    fabOpen -> closeFab()
                    selected.isNotEmpty() -> clearSelection()
                    query.isNotEmpty() -> search.setText("")
                    folderFilter != null -> selectFolder(null)
                    else -> { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
                }
            }
        })

        folderFilter = app.prefs.lastFolder.let { if (it == 0L) null else it }
        handleIntent(intent)
        if (Build.VERSION.SDK_INT >= 33 && !app.prefs.firstRunDone) {
            app.prefs.firstRunDone = true
        }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handleIntent(intent) }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            ACTION_NEW -> { val t = BodyType.from(intent.getStringExtra(EXTRA_TYPE)); intent.action = null; createNote(t, voice = intent.getBooleanExtra(EXTRA_VOICE, false) || intent.getStringExtra(EXTRA_VOICE) == "true") }
            Intent.ACTION_SEND -> { intent.action = null; receiveShare(intent) }
        }
    }

    /** "Send to Notebook": shared text becomes the body, a shared file becomes an attachment on a fresh note. */
    private fun receiveShare(intent: Intent) {
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty()
        @Suppress("DEPRECATION") val uri: android.net.Uri? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java) else intent.getParcelableExtra(Intent.EXTRA_STREAM)
        app.async({
            val id = app.repo.createNote(BodyType.TEXT, null)
            if (!text.isNullOrBlank() || subject.isNotBlank()) app.repo.saveText(id, subject.take(120), text.orEmpty())
            if (uri != null) runCatching {
                val imp = app.files.importUri(uri)
                if (imp != null) {
                    val kind = ai.techtroy.notebook.data.AttachmentKind.fromMime(imp.mime, imp.name)
                    var thumb: String? = null; var w = 0; var h = 0; var dur = 0L
                    when (kind) {
                        ai.techtroy.notebook.data.AttachmentKind.IMAGE -> { val r = app.files.makeImageThumb(imp.rel); thumb = r.first; w = r.second[0]; h = r.second[1] }
                        ai.techtroy.notebook.data.AttachmentKind.VIDEO -> { val m = Media.videoMeta(app, imp.rel); thumb = m.first; dur = m.second }
                        ai.techtroy.notebook.data.AttachmentKind.AUDIO -> dur = Media.audioDuration(app, imp.rel)
                        ai.techtroy.notebook.data.AttachmentKind.PDF -> thumb = Media.pdfThumb(app, imp.rel)
                        else -> {}
                    }
                    app.repo.addAttachment(ai.techtroy.notebook.data.Attachment(0, id, kind, imp.name, imp.rel, imp.mime, imp.size, dur, w, h, thumb, System.currentTimeMillis(), 0, null, null))
                }
            }
            id
        }) { id -> startActivity(EditorActivity.intent(this, id, isNew = false)) }
    }

    override fun onResume() {
        super.onResume()
        app.repo.addListener(repoListener)
        reload(); reloadFolders()
    }

    override fun onPause() { super.onPause(); app.repo.removeListener(repoListener) }

    // ------------------------------------------------------------------ layout

    private fun applyLayoutManager() {
        btnLayout.setImageResource(if (adapter.grid) R.drawable.ic_list else R.drawable.ic_grid)
        if (adapter.grid) {
            list.layoutManager = StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL).apply { gapStrategy = StaggeredGridLayoutManager.GAP_HANDLING_MOVE_ITEMS_BETWEEN_SPANS }
        } else {
            list.layoutManager = GridLayoutManager(this, 1)
        }
    }

    private fun bindFullSpan() {
        // staggered: sections + echo take full width
        list.post {
            for (i in 0 until list.childCount) {
                val child = list.getChildAt(i)
                val pos = list.getChildAdapterPosition(child)
                val lp = child.layoutParams
                if (lp is StaggeredGridLayoutManager.LayoutParams) {
                    val full = adapter.spanFor(pos) == 2
                    if (lp.isFullSpan != full) { lp.isFullSpan = full; child.layoutParams = lp }
                }
            }
        }
    }

    // ------------------------------------------------------------------- data

    private fun reload() {
        val f = folderFilter; val q = query
        app.async({
            val notes = app.repo.notes(f, q, app.prefs.sort, app.prefs.sortAsc)
            val echo = if (q.isEmpty() && f == null) app.echoes.today() else null
            Triple(notes, echo, app.repo.allCount())
        }) { (notes, echo, total) ->
            val rows = ArrayList<Row>()
            echo?.let { rows += Row.EchoRow(it) }
            val pinned = notes.filter { it.pinned }; val others = notes.filter { !it.pinned }
            if (pinned.isNotEmpty()) { rows += Row.Section(getString(R.string.section_pinned)); pinned.forEach { rows += Row.NoteRow(it) } }
            if (others.isNotEmpty()) { if (pinned.isNotEmpty()) rows += Row.Section(getString(R.string.section_others)); others.forEach { rows += Row.NoteRow(it) } }
            adapter.submitList(rows) { bindFullSpan() }
            empty.show(notes.isEmpty())
            if (notes.isEmpty()) {
                findViewById<TextView>(R.id.emptyTitle).text = if (q.isNotEmpty()) getString(R.string.empty_search, q) else getString(R.string.empty_notes_title)
                findViewById<TextView>(R.id.emptyBody).text = if (q.isNotEmpty()) "" else getString(R.string.empty_notes_body)
            }
            // keep selection valid
            val ids = notes.map { it.id }.toSet()
            if (selected.retainAll(ids)) updateSelectionBar()
            adapter.selected = selected.toSet()
        }
        list.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(v: View?, a: Int, b: Int, c: Int, d: Int, e: Int, f: Int, g: Int, h: Int) { bindFullSpan() }
        })
    }

    private fun reloadFolders() {
        app.async({ Triple(app.repo.folders(), app.repo.unfiledCount(), app.repo.trashCount()) }) { (fs, unfiled, trash) ->
            folders = fs
            if (folderFilter != null && folderFilter != -1L && fs.none { it.id == folderFilter }) { folderFilter = null; reload() }
            buildChips(fs, unfiled)
            buildDrawer(fs, unfiled, trash)
        }
    }

    private fun buildChips(fs: List<Folder>, unfiled: Int) {
        chips.setOnCheckedStateChangeListener(null)
        chips.removeAllViews()
        fun chip(label: String, id: Long?, color: Int?): Chip = (LayoutInflater.from(this).inflate(R.layout.view_chip, chips, false) as Chip).apply {
            text = label; tag = id
            if (color != null) { chipIcon = circle(color); isChipIconVisible = true; chipIconSize = dp(10).toFloat(); iconStartPadding = dp(6).toFloat() }
            isChecked = (id == folderFilter)
            setOnClickListener { selectFolder(id) }
        }
        chips.addView(chip(getString(R.string.folder_all), null, null))
        fs.forEach { f -> chips.addView(chip(f.name, f.id, f.color)) }
        if (fs.isNotEmpty() && unfiled > 0) chips.addView(chip(getString(R.string.folder_unfiled), -1L, null))
        chips.addView(chip("+ " + getString(R.string.new_folder), -2L, null).apply { isCheckable = false; setOnClickListener { FoldersActivity.newFolderDialog(this@HomeActivity) { reloadFolders() } } })
    }

    private fun selectFolder(id: Long?) {
        folderFilter = id
        app.prefs.lastFolder = id ?: 0L
        for (i in 0 until chips.childCount) { val c = chips.getChildAt(i) as Chip; if (c.isCheckable) c.isChecked = c.tag == id }
        reload()
    }

    private fun buildDrawer(fs: List<Folder>, unfiled: Int, trash: Int) {
        val panel = findViewById<LinearLayout>(R.id.drawerItems)
        panel.removeAllViews()
        fun row(icon: Int?, label: String, count: String? = null, dot: Int? = null, selectedRow: Boolean = false, onClick: () -> Unit) {
            val v = LayoutInflater.from(this).inflate(R.layout.item_drawer, panel, false)
            v.findViewById<ImageView>(R.id.drawerIcon).apply { if (icon != null) setImageResource(icon) else show(false) }
            v.findViewById<View>(R.id.drawerDot).apply { if (dot != null) { show(true); background = circle(dot) } }
            v.findViewById<TextView>(R.id.drawerLabel).text = label
            v.findViewById<TextView>(R.id.drawerCount).text = count ?: ""
            v.isSelected = selectedRow
            v.setOnClickListener { drawer.closeDrawers(); onClick() }
            panel.addView(v)
        }
        fun header(t: String) { panel.addView(TextView(this).apply { text = t; setTextAppearance(R.style.TextAppearance_Notebook_Caps); setPadding(dp(14), dp(18), dp(14), dp(6)) }) }
        row(R.drawable.ic_note, getString(R.string.nav_all_notes), "${app.repo.allCount()}", selectedRow = folderFilter == null) { selectFolder(null) }
        row(R.drawable.ic_graph, getString(R.string.nav_graph)) { startActivity(Intent(this, GraphActivity::class.java)) }
        row(R.drawable.ic_echo, getString(R.string.nav_echoes)) { startActivity(Intent(this, EchoesActivity::class.java)) }
        header(getString(R.string.nav_folders))
        fs.forEach { f -> row(null, f.name, "${f.noteCount}", dot = f.color, selectedRow = folderFilter == f.id) { selectFolder(f.id) } }
        if (unfiled > 0 && fs.isNotEmpty()) row(R.drawable.ic_folder, getString(R.string.folder_unfiled), "$unfiled", selectedRow = folderFilter == -1L) { selectFolder(-1L) }
        row(R.drawable.ic_add, getString(R.string.nav_manage_folders)) { startActivity(Intent(this, FoldersActivity::class.java)) }
        header("")
        row(R.drawable.ic_widgets, getString(R.string.nav_widgets)) { startActivity(Intent(this, WidgetsInfoActivity::class.java)) }
        row(R.drawable.ic_trash, getString(R.string.nav_trash), if (trash > 0) "$trash" else null) { startActivity(Intent(this, TrashActivity::class.java)) }
        row(R.drawable.ic_settings, getString(R.string.nav_settings)) { startActivity(Intent(this, SettingsActivity::class.java)) }
    }

    // ---------------------------------------------------------------- actions

    private fun setupMini(id: Int, icon: Int, label: Int, onClick: () -> Unit) {
        val v = findViewById<View>(id)
        v.findViewById<ImageView>(R.id.miniIcon).setImageResource(icon)
        v.findViewById<TextView>(R.id.miniLabel).setText(label)
        v.findViewById<View>(R.id.miniBtn).setOnClickListener { closeFab(); onClick() }
        v.findViewById<View>(R.id.miniLabel).setOnClickListener { closeFab(); onClick() }
    }

    private fun openFab() {
        fabOpen = true
        fabScrim.alpha = 0f; fabScrim.show(true); fabScrim.animate().alpha(1f).setDuration(160).start()
        fabMenu.show(true)
        val group = fabMenu as ViewGroup
        for (i in 0 until group.childCount) {
            val c = group.getChildAt(i); c.alpha = 0f; c.translationY = dp(24).toFloat(); c.scaleX = 0.9f; c.scaleY = 0.9f
            c.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setStartDelay((group.childCount - 1 - i) * 35L).setDuration(200).setInterpolator(OvershootInterpolator(1.4f)).start()
        }
        fab.animate().rotation(45f).setDuration(180).start()
    }

    private fun closeFab() {
        if (!fabOpen) return
        fabOpen = false
        fabScrim.animate().alpha(0f).setDuration(140).withEndAction { fabScrim.show(false) }.start()
        fabMenu.animate().alpha(0f).setDuration(120).withEndAction { fabMenu.show(false); fabMenu.alpha = 1f }.start()
        fab.animate().rotation(0f).setDuration(160).start()
    }

    private fun createNote(type: BodyType, voice: Boolean = false) {
        val folder = folderFilter?.takeIf { it > 0 }
        app.async({ app.repo.createNote(type, folder) }) { id ->
            startActivity(EditorActivity.intent(this, id, isNew = true).putExtra(EditorActivity.EXTRA_VOICE, voice))
            overridePendingTransition(R.anim.slide_up, R.anim.fade_out)
        }
    }

    private fun createSketchNote() {
        val folder = folderFilter?.takeIf { it > 0 }
        app.async({ app.repo.createNote(BodyType.TEXT, folder) }) { id ->
            startActivity(EditorActivity.intent(this, id, isNew = true).putExtra(EditorActivity.EXTRA_SKETCH, true))
            overridePendingTransition(R.anim.slide_up, R.anim.fade_out)
        }
    }

    private fun openNote(n: Note) {
        if (selected.isNotEmpty()) { toggleSelect(n); return }
        if (n.isCapsule) { CapsuleDialogs.showSealed(this, n); return }
        if (n.locked && !Lock.sessionValid(app.prefs)) {
            LockActivity.unlock(this) { startActivity(EditorActivity.intent(this, n.id)) }
            return
        }
        startActivity(EditorActivity.intent(this, n.id))
    }

    private fun openEcho(e: Echo) { startActivity(EditorActivity.intent(this, e.noteId).putExtra(EditorActivity.EXTRA_ECHO, true)) }
    private fun dismissEcho(e: Echo) { app.async({ app.repo.dismissEcho(e.id) }) { } }

    private fun toggleSelect(n: Note) {
        if (n.id in selected) selected.remove(n.id) else selected.add(n.id)
        adapter.selected = selected.toSet(); updateSelectionBar()
    }
    private fun selectAll() { adapter.currentList.forEach { if (it is Row.NoteRow) selected.add(it.note.id) }; adapter.selected = selected.toSet(); updateSelectionBar() }
    private fun clearSelection() { selected.clear(); adapter.selected = emptySet(); updateSelectionBar() }
    private fun updateSelectionBar() {
        val on = selected.isNotEmpty()
        selectBar.show(on); searchBar.show(!on)
        findViewById<TextView>(R.id.selCount).text = getString(R.string.selected_count, selected.size)
        if (on) hideKeyboard()
    }

    private fun trashNotes(ids: List<Long>) {
        app.async({ app.repo.trash(ids); ids.forEach { ai.techtroy.notebook.sys.ReminderScheduler.cancel(this, it) } }) {
            clearSelection()
            val msg = if (ids.size == 1) getString(R.string.note_moved_to_trash) else getString(R.string.notes_moved_to_trash, ids.size)
            findViewById<View>(R.id.coordinator).snack(msg, getString(R.string.undo), fab) { app.async({ app.repo.restore(ids) }) { } }
        }
    }

    private fun showSortMenu(anchor: View) {
        val items = arrayOf(getString(R.string.sort_edited), getString(R.string.sort_created), getString(R.string.sort_title), getString(R.string.sort_color))
        MaterialAlertDialogBuilder(this).setTitle(R.string.settings_sort)
            .setSingleChoiceItems(items, app.prefs.sort.id) { d, i -> app.prefs.sort = SortMode.from(i); app.prefs.sortAsc = (i == 2); d.dismiss(); reload() }
            .show()
    }

    companion object {
        const val ACTION_NEW = "ai.techtroy.notebook.NEW"
        const val EXTRA_TYPE = "type"
        const val EXTRA_VOICE = "voice"
        fun newNoteIntent(ctx: Context, type: BodyType, voice: Boolean = false): Intent =
            Intent(ctx, HomeActivity::class.java).setAction(ACTION_NEW).putExtra(EXTRA_TYPE, type.db).putExtra(EXTRA_VOICE, voice)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
