package ai.techtroy.notebook.ui

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.text.Editable
import android.text.Selection
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import ai.techtroy.notebook.R
import ai.techtroy.notebook.core.Fmt
import ai.techtroy.notebook.core.ThemeManager
import ai.techtroy.notebook.core.dp
import ai.techtroy.notebook.core.gold
import ai.techtroy.notebook.core.hideKeyboard
import ai.techtroy.notebook.core.muted
import ai.techtroy.notebook.core.show
import ai.techtroy.notebook.core.showKeyboard
import ai.techtroy.notebook.core.snack
import ai.techtroy.notebook.core.toast
import ai.techtroy.notebook.data.Attachment
import ai.techtroy.notebook.data.AttachmentKind
import ai.techtroy.notebook.data.BodyType
import ai.techtroy.notebook.data.ChecklistItem
import ai.techtroy.notebook.data.Note
import ai.techtroy.notebook.data.NoteColor
import ai.techtroy.notebook.data.Repo
import ai.techtroy.notebook.echo.ReplayActivity
import ai.techtroy.notebook.sys.Lock
import ai.techtroy.notebook.sys.RecorderService
import ai.techtroy.notebook.sys.ReminderScheduler
import java.io.File
import java.util.Calendar

class EditorActivity : BaseActivity() {

    private var noteId = -1L
    private var note: Note? = null
    private var isNew = false

    private lateinit var title: EditText
    private lateinit var body: EditText
    private lateinit var scroll: NestedScrollView
    private lateinit var savedLabel: TextView
    private lateinit var folderLabel: TextView
    private lateinit var meta: TextView
    private lateinit var banner: TextView
    private lateinit var btnPin: ImageButton
    private lateinit var btnLock: ImageButton
    private lateinit var btnReminder: ImageButton
    private lateinit var btnChecklist: ImageButton
    private lateinit var btnUndo: ImageButton
    private lateinit var btnRedo: ImageButton
    private lateinit var checklistWrap: View
    private lateinit var attachStrip: LinearLayout
    private lateinit var audioList: LinearLayout
    private lateinit var linksWrap: View
    private lateinit var linksOut: ChipGroup
    private lateinit var linksIn: ChipGroup
    private lateinit var recPill: View
    private lateinit var reminderRow: View

    private lateinit var openAdapter: ChecklistAdapter
    private lateinit var doneAdapter: ChecklistAdapter
    private var completedCollapsed = false

    // undo/redo per chunk (title+body snapshot); checklist edits are stored as separate snapshots too
    private data class Snap(val title: String, val body: String, val sel: Int)
    private val undoStack = ArrayDeque<Snap>()
    private val redoStack = ArrayDeque<Snap>()
    private var lastSnap: Snap? = null
    private var chunkOpen = false
    private var suppressWatch = false
    private var lastEditAt = 0L
    private val saveDelay = 700L
    private val saveRunnable = Runnable { commitChunk(); saveNow() }
    private var dirty = false
    private var linkTitles: Map<Long, String?> = emptyMap()
    private var manualLinks = HashSet<Long>()
    private var checklistDirty = HashMap<Long, ChecklistItem>()

    private var players = ArrayList<AudioPlayerView>()
    private var recService: RecorderService? = null
    private var recBound = false
    private var pendingCameraFile: File? = null
    private var pendingRecordOnOpen = false

    private val repoListener = { runOnUiThread { if (!dirty) refreshSideData() } }

    // ---- launchers
    private val pickMedia = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> uris.forEach { importUri(it) } }
    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> uris.forEach { importUri(it) } }
    private val takePhoto = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok -> val f = pendingCameraFile; pendingCameraFile = null; if (ok && f != null) adoptCamera(f) else f?.delete() }
    private val cameraPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) launchCamera() else toast(getString(R.string.permission_denied)) }
    private val micPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) startRecording() else toast(getString(R.string.permission_denied)) }
    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { pickReminderDate() }
    private val sketchLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { refreshSideData() }

    private val recConn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            recService = (service as RecorderService.LocalBinder).service; recBound = true
            recService?.listener = { runOnUiThread { updateRecPill() } }
            updateRecPill()
        }
        override fun onServiceDisconnected(name: ComponentName?) { recService = null; recBound = false; updateRecPill() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        noteId = intent.getLongExtra(EXTRA_ID, -1)
        isNew = intent.getBooleanExtra(EXTRA_NEW, false)
        setContentView(R.layout.activity_editor)
        title = findViewById(R.id.title); body = findViewById(R.id.body); scroll = findViewById(R.id.scroll)
        savedLabel = findViewById(R.id.savedLabel); folderLabel = findViewById(R.id.folderLabel); meta = findViewById(R.id.meta); banner = findViewById(R.id.banner)
        btnPin = findViewById(R.id.btnPin); btnLock = findViewById(R.id.btnLock); btnReminder = findViewById(R.id.btnReminder); btnChecklist = findViewById(R.id.btnChecklist)
        btnUndo = findViewById(R.id.btnUndo); btnRedo = findViewById(R.id.btnRedo)
        checklistWrap = findViewById(R.id.checklistWrap); attachStrip = findViewById(R.id.attachStrip); audioList = findViewById(R.id.audioList)
        linksWrap = findViewById(R.id.linksWrap); linksOut = findViewById(R.id.linksOut); linksIn = findViewById(R.id.linksIn)
        recPill = findViewById(R.id.recPill); reminderRow = findViewById(R.id.reminderRow)
        padForIme(findViewById(R.id.bottomBar))

        val scale = ThemeManager.fontScale(app.prefs.fontSize)
        body.textSize = 16f * scale; title.textSize = 23f * scale

        findViewById<View>(R.id.btnBack).setOnClickListener { finishEditor() }
        btnPin.setOnClickListener { val n = note ?: return@setOnClickListener; app.async({ app.repo.setPinned(listOf(n.id), !n.pinned) }) { } }
        findViewById<View>(R.id.btnColor).setOnClickListener { Sheets.colorPicker(this, note?.color) { c -> app.async({ app.repo.setColor(listOf(noteId), c) }) { applyColor(c) } } }
        findViewById<View>(R.id.btnMore).setOnClickListener { showMore() }
        findViewById<View>(R.id.btnAttach).setOnClickListener { showAttachSheet() }
        findViewById<View>(R.id.btnLink).setOnClickListener { Sheets.notePicker(this, noteId) { insertLink(it, manual = !body.hasFocus()) } }
        btnChecklist.setOnClickListener { toggleChecklist() }
        btnReminder.setOnClickListener { reminderTapped() }
        btnLock.setOnClickListener { lockTapped() }
        findViewById<View>(R.id.btnMic).setOnClickListener { micTapped() }
        btnUndo.setOnClickListener { undo() }; btnRedo.setOnClickListener { redo() }
        findViewById<View>(R.id.recPause).setOnClickListener { recService?.let { if (it.paused) it.resume() else it.pause() } }
        findViewById<View>(R.id.recStop).setOnClickListener { stopRecording() }
        findViewById<View>(R.id.pagesOpen).setOnClickListener { openPages() }
        findViewById<View>(R.id.pagesCard).setOnClickListener { openPages() }
        findViewById<View>(R.id.completedHeader).setOnClickListener { completedCollapsed = !completedCollapsed; renderChecklist(openAdapter.items + doneAdapter.items) }
        findViewById<View>(R.id.addItem).setOnClickListener { addChecklistItem("", null) }
        reminderRow.setOnClickListener { reminderTapped() }

        setupWatchers()
        setupChecklist()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() { finishEditor() } })

        if (intent.getBooleanExtra(EXTRA_SKETCH, false)) { intent.removeExtra(EXTRA_SKETCH); newSketch() }
        pendingRecordOnOpen = intent.getBooleanExtra(EXTRA_VOICE, false)
        if (intent.getBooleanExtra(EXTRA_ECHO, false)) { banner.show(true); banner.text = "✦ Echo — this note came back to you today." }
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, RecorderService::class.java), recConn, Context.BIND_AUTO_CREATE)
    }

    override fun onResume() {
        super.onResume()
        app.repo.addListener(repoListener)
        load(first = note == null)
        RecorderService.lastResult?.let { r -> if (r.noteId == noteId) { RecorderService.lastResult = null; saveRecording(r) } }
    }

    override fun onPause() {
        super.onPause()
        app.repo.removeListener(repoListener)
        body.removeCallbacks(saveRunnable); commitChunk(); saveNow(sync = true)
        players.forEach { it.pause() }
    }

    override fun onStop() {
        super.onStop()
        if (recBound) { recService?.listener = null; unbindService(recConn); recBound = false }
    }

    override fun onDestroy() { players.forEach { it.release() }; super.onDestroy() }

    // ------------------------------------------------------------------ load

    private fun load(first: Boolean) {
        app.async({ app.repo.note(noteId) }) { n ->
            if (n == null) { finish(); return@async }
            val wasNull = note == null
            note = n
            blockScreenshots(n.locked)
            if (first || wasNull) {
                suppressWatch = true
                title.setText(n.title); body.setText(n.body); suppressWatch = false
                lastSnap = Snap(n.title, n.body, 0)
                if (isNew && n.bodyType == BodyType.TEXT) { body.post { body.showKeyboard() } }
                if (isNew && n.bodyType == BodyType.PAGES) { isNew = false; openPages() }
            }
            applyColor(n.color)
            btnPin.setImageResource(if (n.pinned) R.drawable.ic_pin_filled else R.drawable.ic_pin)
            btnLock.setImageResource(if (n.locked) R.drawable.ic_lock else R.drawable.ic_unlock)
            btnLock.alpha = if (n.locked) 1f else 0.75f
            btnReminder.setImageResource(if (n.reminderAt != null && !n.reminderDone) R.drawable.ic_bell_filled else R.drawable.ic_bell)
            reminderRow.show(n.reminderAt != null && !n.reminderDone)
            n.reminderAt?.let { findViewById<TextView>(R.id.reminderChip).text = Fmt.dateTime(it) }
            btnChecklist.setImageResource(if (n.bodyType == BodyType.CHECKLIST) R.drawable.ic_text else R.drawable.ic_checklist)
            btnChecklist.contentDescription = getString(if (n.bodyType == BodyType.CHECKLIST) R.string.to_text else R.string.to_checklist)
            btnChecklist.isEnabled = n.bodyType != BodyType.PAGES; btnChecklist.alpha = if (n.bodyType == BodyType.PAGES) 0.3f else 1f
            body.show(n.bodyType == BodyType.TEXT); checklistWrap.show(n.bodyType == BodyType.CHECKLIST)
            findViewById<View>(R.id.pagesCard).show(n.bodyType == BodyType.PAGES)
            if (n.isCapsule) { banner.show(true); banner.text = getString(R.string.capsule_locked_msg, Fmt.date(n.capsuleUntil!!)) }
            refreshSideData()
            if (pendingRecordOnOpen) { pendingRecordOnOpen = false; micTapped() }
        }
    }

    private fun refreshSideData() {
        val id = noteId
        app.async({
            val n = app.repo.note(id) ?: return@async null
            val items = app.repo.checklist(id)
            val atts = app.repo.attachments(id)
            val out = app.repo.links(id).mapNotNull { l -> app.repo.note(l.toId)?.let { l.toId to it } }
            val outAll = app.repo.links(id).map { it.toId }
            val inn = app.repo.backlinks(id).mapNotNull { l -> app.repo.note(l.fromId)?.let { l.fromId to it } }
            val titles = HashMap<Long, String?>()
            LinkSpans.ids(n.body).forEach { lid -> titles[lid] = app.repo.note(lid)?.takeIf { !it.inTrash }?.displayTitle }
            val folder = n.folderId?.let { app.repo.folder(it) }
            val pages = if (n.bodyType == BodyType.PAGES) app.repo.pages(id) else emptyList()
            val words = app.repo.wordCount(n)
            Side(n, items, atts, out, outAll.toSet(), inn, titles, folder?.name, pages.size, pages.firstOrNull()?.thumbPath, words)
        }) { s ->
            if (s == null) return@async
            note = s.note
            linkTitles = s.titles
            manualLinks = HashSet(s.outAll - LinkSpans.ids(body.text).toSet())
            applyLinkSpans()
            if (s.note.bodyType == BodyType.CHECKLIST) renderChecklist(s.items)
            renderAttachments(s.atts)
            renderLinks(s.out, s.inn)
            folderLabel.show(s.folderName != null); folderLabel.text = s.folderName
            findViewById<TextView>(R.id.pagesInfo).text = getString(R.string.pages_count, s.pageCount)
            val thumb = findViewById<ImageView>(R.id.pagesThumb)
            if (s.pageThumb != null) app.async({ app.files.loadBitmap(s.pageThumb, 800) }) { b -> thumb.setImageBitmap(b) } else thumb.setImageDrawable(null)
            meta.text = getString(R.string.edited_at, Fmt.dateTime(s.note.updatedAt)) + "  ·  " + getString(R.string.words_count, s.words)
        }
    }

    private data class Side(val note: Note, val items: List<ChecklistItem>, val atts: List<Attachment>, val out: List<Pair<Long, Note>>, val outAll: Set<Long>, val inn: List<Pair<Long, Note>>, val titles: Map<Long, String?>, val folderName: String?, val pageCount: Int, val pageThumb: String?, val words: Int)

    private fun applyColor(c: NoteColor) {
        val bg = ThemeManager.noteTint(this, c, isLightTheme)
        findViewById<View>(R.id.editorRoot).setBackgroundColor(if (c == NoteColor.NONE) ThemeManager.attr(this, android.R.attr.colorBackground) else bg)
        window.statusBarColor = if (c == NoteColor.NONE) ThemeManager.attr(this, android.R.attr.colorBackground) else bg
        note = note?.copy(color = c)
    }

    // --------------------------------------------------------------- text edit

    private fun setupWatchers() {
        val w = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { if (!suppressWatch) onEdited() }
        }
        title.addTextChangedListener(w)
        body.addTextChangedListener(object : TextWatcher {
            var typedBracket = false
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                typedBracket = !suppressWatch && count == 1 && before == 0 && s != null && start >= 1 && s[start] == '[' && s[start - 1] == '['
            }
            override fun afterTextChanged(s: Editable?) {
                if (suppressWatch) return
                onEdited()
                if (typedBracket && s != null) {
                    typedBracket = false
                    val pos = Selection.getSelectionStart(s)
                    body.post { Sheets.notePicker(this@EditorActivity, noteId) { n -> replaceBrackets(pos, n) } }
                }
            }
        })
        title.setOnFocusChangeListener { _, f -> if (!f) commitChunk() }
        body.setOnFocusChangeListener { _, f -> if (!f) commitChunk() }
        // tap on a link chip opens it
        body.setOnTouchListener { v, ev ->
            if (ev.action == MotionEvent.ACTION_UP) {
                val off = body.getOffsetForPosition(ev.x, ev.y)
                val chip = LinkSpans.chipAt(body.text, off)
                if (chip != null && body.text.getSpanStart(chip) < off && off <= body.text.getSpanEnd(chip)) { openLinked(chip.noteId); return@setOnTouchListener true }
            }
            false
        }
    }

    private fun onEdited() {
        dirty = true
        val now = System.currentTimeMillis()
        val cur = Snap(title.text.toString(), body.text.toString(), body.selectionStart)
        val last = lastSnap
        // new chunk if idle > 700ms or a word boundary was just typed
        val endsSpace = cur.body.length > (last?.body?.length ?: 0) && cur.body.lastOrNull()?.isWhitespace() == true
        if (!chunkOpen || now - lastEditAt > saveDelay || (endsSpace && now - lastEditAt > 250)) {
            if (last != null && chunkOpen) { /* previous chunk already pushed */ }
            if (last != null) { undoStack.addLast(last); if (undoStack.size > 100) undoStack.removeFirst() }
            redoStack.clear(); chunkOpen = true
        }
        lastEditAt = now
        updateUndoButtons()
        savedLabel.animate().alpha(0f).setDuration(100).start()
        body.removeCallbacks(saveRunnable); body.postDelayed(saveRunnable, saveDelay)
    }

    private fun commitChunk() { if (chunkOpen) { lastSnap = Snap(title.text.toString(), body.text.toString(), body.selectionStart); chunkOpen = false } }

    private fun updateUndoButtons() { btnUndo.alpha = if (undoStack.isNotEmpty() || chunkOpen) 1f else 0.35f; btnRedo.alpha = if (redoStack.isNotEmpty()) 1f else 0.35f }

    private fun undo() {
        commitChunk()
        val target = undoStack.removeLastOrNull() ?: return
        lastSnap?.let { redoStack.addLast(it) }
        applySnap(target); lastSnap = target; updateUndoButtons()
    }
    private fun redo() {
        commitChunk()
        val target = redoStack.removeLastOrNull() ?: return
        lastSnap?.let { undoStack.addLast(it) }
        applySnap(target); lastSnap = target; updateUndoButtons()
    }
    private fun applySnap(s: Snap) {
        suppressWatch = true
        if (title.text.toString() != s.title) title.setText(s.title)
        if (body.text.toString() != s.body) { body.setText(s.body); body.setSelection(s.sel.coerceIn(0, s.body.length)) }
        suppressWatch = false
        applyLinkSpans(); dirty = true; body.removeCallbacks(saveRunnable); body.postDelayed(saveRunnable, 200)
    }

    private fun saveNow(sync: Boolean = false) {
        if (!dirty) return
        dirty = false
        val t = title.text.toString(); val b = body.text.toString(); val id = noteId
        val manual = HashSet(manualLinks)
        val work = { app.repo.saveText(id, t, b); app.repo.syncLinksFromBody(id, b, manual) }
        if (sync) { app.io.execute { work() } } else app.async(work) { savedLabel.animate().alpha(1f).setDuration(200).start(); refreshSideData() }
    }

    // ------------------------------------------------------------------ links

    private fun applyLinkSpans() {
        val sel = body.selectionStart
        suppressWatch = true
        LinkSpans.apply(this, body.text, linkTitles)
        suppressWatch = false
        if (sel in 0..body.text.length) body.setSelection(sel)
    }

    private fun replaceBrackets(cursorPos: Int, n: Note) {
        val e = body.text
        val start = (cursorPos - 2).coerceAtLeast(0)
        if (start + 2 <= e.length && e.subSequence(start, start + 2).toString() == "[[") {
            val token = "[[id:${n.id}]] "
            linkTitles = linkTitles + (n.id to n.displayTitle)
            e.replace(start, start + 2, token)
            applyLinkSpans(); body.setSelection((start + token.length).coerceAtMost(body.text.length))
        } else insertLink(n, manual = false)
        app.async({ app.repo.addLink(noteId, n.id) }) { }
    }

    private fun insertLink(n: Note, manual: Boolean) {
        val note = note ?: return
        if (note.bodyType == BodyType.TEXT && !manual) {
            val pos = body.selectionStart.coerceAtLeast(0)
            val token = "[[id:${n.id}]] "
            linkTitles = linkTitles + (n.id to n.displayTitle)
            body.text.insert(pos, token); applyLinkSpans(); body.setSelection((pos + token.length).coerceAtMost(body.text.length))
        } else manualLinks.add(n.id)
        app.async({ app.repo.addLink(noteId, n.id) }) { refreshSideData() }
    }

    private fun renderLinks(out: List<Pair<Long, Note>>, inn: List<Pair<Long, Note>>) {
        linksWrap.show(out.isNotEmpty() || inn.isNotEmpty())
        findViewById<View>(R.id.linksOutTitle).show(out.isNotEmpty()); linksOut.show(out.isNotEmpty())
        findViewById<View>(R.id.linksInTitle).show(inn.isNotEmpty()); linksIn.show(inn.isNotEmpty())
        fun fill(group: ChipGroup, list: List<Pair<Long, Note>>, closable: Boolean) {
            group.removeAllViews()
            list.forEach { (id, n) ->
                val chip = Chip(this).apply {
                    text = (if (n.inTrash) getString(R.string.link_deleted) + " " else "") + n.displayTitle.ifBlank { "Untitled" }.take(30)
                    chipIcon = getDrawable(R.drawable.ic_link); chipIconTint = android.content.res.ColorStateList.valueOf(if (n.inTrash) muted() else gold()); isChipIconVisible = true
                    chipIconSize = dp(14).toFloat(); chipStrokeWidth = dp(1).toFloat()
                    chipStrokeColor = android.content.res.ColorStateList.valueOf(if (n.inTrash) muted() else gold())
                    chipBackgroundColor = android.content.res.ColorStateList.valueOf(ThemeManager.attr(this@EditorActivity, R.attr.nbGoldDim))
                    setTextColor(if (n.inTrash) muted() else ThemeManager.attr(this@EditorActivity, android.R.attr.textColorPrimary)); textSize = 13f
                    isCloseIconVisible = closable; closeIconTint = android.content.res.ColorStateList.valueOf(muted())
                    setOnCloseIconClickListener { removeLink(id) }
                    setOnClickListener { if (!n.inTrash) openLinked(id) }
                }
                group.addView(chip)
            }
        }
        fill(linksOut, out, true); fill(linksIn, inn, false)
    }

    private fun removeLink(id: Long) {
        manualLinks.remove(id)
        val e = body.text
        Repo.LINK_TOKEN.findAll(e.toString()).filter { it.groupValues[1].toLong() == id }.toList().asReversed().forEach { m ->
            var end = m.range.last + 1; if (end < e.length && e[end] == ' ') end++
            e.delete(m.range.first, end)
        }
        app.async({ app.repo.removeLink(noteId, id) }) { refreshSideData() }
    }

    private fun openLinked(id: Long) {
        commitChunk(); saveNow(sync = true)
        app.async({ app.repo.note(id) }) { n ->
            if (n == null || n.inTrash) { toast(getString(R.string.link_deleted)); return@async }
            if (n.locked && !Lock.sessionValid(app.prefs)) LockActivity.unlock(this) { startActivity(intent(this, id)) } else startActivity(intent(this, id))
        }
    }

    // -------------------------------------------------------------- checklist

    private fun setupChecklist() {
        val onText: (ChecklistItem, String) -> Unit = { item, text -> checklistDirty[item.id] = item.copy(text = text); scheduleChecklistSave() }
        val onToggle: (ChecklistItem) -> Unit = { item -> hideKeyboard(); flushChecklist(); app.async({ app.repo.updateChecklistItem(item.copy(checked = !item.checked)) }) { refreshSideData() } }
        val onRemove: (ChecklistItem) -> Unit = { item -> flushChecklist(); app.async({ app.repo.deleteChecklistItem(item.id, noteId) }) { refreshSideData() } }
        val onEnter: (ChecklistItem, String, String) -> Unit = { item, before, after -> checklistDirty[item.id] = item.copy(text = before); flushChecklist(); addChecklistItem(after, item.position + 1) }
        val onReorder: (List<Long>) -> Unit = { ids -> app.async({ app.repo.reorderChecklist(noteId, ids + doneAdapter.items.map { it.id }) }) { } }
        val onBackspace: (ChecklistItem) -> Unit = { item ->
            val list = if (item.checked) doneAdapter else openAdapter
            val idx = list.items.indexOfFirst { it.id == item.id }
            if (idx > 0) { list.focusRequestId = list.items[idx - 1].id }
            flushChecklist(); app.async({ app.repo.deleteChecklistItem(item.id, noteId) }) { refreshSideData() }
        }
        openAdapter = ChecklistAdapter(false, onText, onToggle, onRemove, onEnter, onReorder, onBackspace)
        doneAdapter = ChecklistAdapter(true, onText, onToggle, onRemove, onEnter, onReorder, onBackspace)
        val scale = ThemeManager.fontScale(app.prefs.fontSize); openAdapter.fontScale = scale; doneAdapter.fontScale = scale
        findViewById<RecyclerView>(R.id.checklist).apply { layoutManager = LinearLayoutManager(this@EditorActivity); openAdapter.attachTo(this); itemAnimator = null }
        findViewById<RecyclerView>(R.id.checklistDone).apply { layoutManager = LinearLayoutManager(this@EditorActivity); doneAdapter.attachTo(this); itemAnimator = null }
    }

    private val checklistSave = Runnable { flushChecklist() }
    private fun scheduleChecklistSave() { body.removeCallbacks(checklistSave); body.postDelayed(checklistSave, saveDelay) }
    private fun flushChecklist() {
        body.removeCallbacks(checklistSave)
        if (checklistDirty.isEmpty()) return
        val batch = checklistDirty.values.toList(); checklistDirty.clear()
        app.io.execute { batch.forEach { app.repo.updateChecklistItem(it, notify = false) }; app.repo.notifyChanged() }
        savedLabel.animate().alpha(1f).setDuration(200).start()
    }

    private fun renderChecklist(items: List<ChecklistItem>) {
        // don't clobber rows while typing: merge dirty text
        val merged = items.map { checklistDirty[it.id] ?: it }
        val open = merged.filter { !it.checked }; val done = merged.filter { it.checked }
        openAdapter.submit(open); doneAdapter.submit(if (completedCollapsed) emptyList() else done)
        findViewById<View>(R.id.completedHeader).show(done.isNotEmpty())
        findViewById<TextView>(R.id.completedLabel).text = getString(R.string.completed_count, done.size)
        findViewById<ImageView>(R.id.completedChevron).rotation = if (completedCollapsed) -90f else 0f
        if (isNew && items.isEmpty() && note?.bodyType == BodyType.CHECKLIST) { isNew = false; addChecklistItem("", null) }
    }

    private fun addChecklistItem(text: String, position: Int?) {
        app.async({
            if (position != null) {
                // shift following items
                val all = app.repo.checklist(noteId)
                all.filter { it.position >= position }.forEach { app.repo.updateChecklistItem(it.copy(position = it.position + 1), notify = false) }
            }
            app.repo.addChecklistItem(noteId, text, false, position)
        }) { id -> openAdapter.focusRequestId = id; refreshSideData() }
    }

    private fun toggleChecklist() {
        val n = note ?: return
        commitChunk(); saveNow(sync = true); flushChecklist()
        hideKeyboard()
        app.async({ if (n.bodyType == BodyType.CHECKLIST) app.repo.convertToText(noteId) else app.repo.convertToChecklist(noteId); app.repo.note(noteId) }) { nn ->
            if (nn == null) return@async
            suppressWatch = true; body.setText(nn.body); suppressWatch = false; lastSnap = Snap(title.text.toString(), nn.body, 0)
            note = nn; load(first = false)
        }
    }

    // ------------------------------------------------------------ attachments

    private fun showAttachSheet() {
        Sheets.menu(this, getString(R.string.attach), listOf(
            Sheets.Item(R.drawable.ic_camera, getString(R.string.take_photo)) { if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED || !hasCameraPermissionDeclared()) launchCamera() else cameraPerm.launch(Manifest.permission.CAMERA) },
            Sheets.Item(R.drawable.ic_image, getString(R.string.pick_media)) { pickMedia.launch(arrayOf("image/*", "video/*")) },
            Sheets.Item(R.drawable.ic_mic, getString(R.string.record_memo)) { micTapped() },
            Sheets.Item(R.drawable.ic_sketch, getString(R.string.new_sketch_attach)) { newSketch() },
            Sheets.Item(R.drawable.ic_file, getString(R.string.pick_file)) { pickFile.launch(arrayOf("*/*")) },
        ))
    }

    private fun hasCameraPermissionDeclared(): Boolean = true

    private fun launchCamera() {
        val (f, uri) = app.files.createTempImage(); pendingCameraFile = f
        runCatching { takePhoto.launch(uri) }.onFailure { toast(getString(R.string.error_generic)) }
    }

    private fun adoptCamera(f: File) {
        app.async({
            val rel = app.files.adoptTemp(f, "jpg")
            val (thumb, dims) = app.files.makeImageThumb(rel)
            app.repo.addAttachment(Attachment(0, noteId, AttachmentKind.IMAGE, "Photo " + Fmt.dateTime(System.currentTimeMillis()), rel, "image/jpeg", app.files.file(rel).length(), 0, dims[0], dims[1], thumb, System.currentTimeMillis(), 0, null, null))
        }) { refreshSideData() }
    }

    private fun importUri(uri: Uri) {
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        app.async({
            val imp = app.files.importUri(uri) ?: return@async false
            val kind = AttachmentKind.fromMime(imp.mime, imp.name)
            var thumb: String? = null; var w = 0; var h = 0; var dur = 0L
            when (kind) {
                AttachmentKind.IMAGE -> { val r = app.files.makeImageThumb(imp.rel); thumb = r.first; w = r.second[0]; h = r.second[1] }
                AttachmentKind.VIDEO -> { val m = Media.videoMeta(app, imp.rel); thumb = m.first; dur = m.second }
                AttachmentKind.AUDIO -> dur = Media.audioDuration(app, imp.rel)
                AttachmentKind.PDF -> thumb = Media.pdfThumb(app, imp.rel)
                else -> {}
            }
            app.repo.addAttachment(Attachment(0, noteId, kind, imp.name, imp.rel, imp.mime, imp.size, dur, w, h, thumb, System.currentTimeMillis(), 0, null, null)); true
        }) { ok -> if (!ok) toast(getString(R.string.error_generic)) else refreshSideData() }
    }

    private fun newSketch() { sketchLauncher.launch(SketchActivity.intent(this, noteId, null)) }

    private fun renderAttachments(atts: List<Attachment>) {
        val strip = atts.filter { it.kind != AttachmentKind.AUDIO }
        val audios = atts.filter { it.kind == AttachmentKind.AUDIO }
        findViewById<View>(R.id.attachScroll).show(strip.isNotEmpty())
        attachStrip.removeAllViews()
        strip.forEach { a -> attachStrip.addView(AttachmentTile.create(this, a) { openAttachment(a) }.also { it.setOnLongClickListener { attachmentMenu(a); true } }) }
        // audio players (reuse when same ids)
        val existing = players.associateBy { it.attachment.id }
        val keep = ArrayList<AudioPlayerView>()
        audioList.removeAllViews()
        audios.forEach { a ->
            val p = existing[a.id]?.also { it.attachment = a } ?: AudioPlayerView(this, a)
            p.onLongPress = { attachmentMenu(a) }
            keep += p; audioList.addView(p.view)
        }
        players.filter { it !in keep }.forEach { it.release() }
        players = keep
    }

    private fun openAttachment(a: Attachment) {
        when (a.kind) {
            AttachmentKind.SKETCH -> sketchLauncher.launch(SketchActivity.intent(this, noteId, a.id))
            AttachmentKind.PDF -> startActivity(PdfActivity.intent(this, a.id))
            AttachmentKind.IMAGE, AttachmentKind.VIDEO -> startActivity(ViewerActivity.intent(this, a.id))
            else -> Media.openExternal(this, a)
        }
    }

    private fun attachmentMenu(a: Attachment) {
        Sheets.menu(this, a.name, listOf(
            Sheets.Item(R.drawable.ic_share, getString(R.string.share)) { Media.share(this, a) },
            Sheets.Item(R.drawable.ic_file, getString(R.string.open_with)) { Media.openExternal(this, a) },
            Sheets.Item(R.drawable.ic_text, getString(R.string.rename)) { Sheets.input(this, getString(R.string.rename), a.name, a.name) { n -> app.async({ app.repo.renameAttachment(a.id, n) }) { refreshSideData() } } },
            Sheets.Item(R.drawable.ic_trash, getString(R.string.remove), danger = true) { app.async({ app.repo.removeAttachment(a.id) { app.files.delete(it) } }) { refreshSideData(); findViewById<View>(R.id.editorRoot).snack(getString(R.string.attachment_removed)) } },
        ))
    }

    // ------------------------------------------------------------- recording

    private fun micTapped() {
        if (recService?.isRecording == true) { stopRecording(); return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRecording() else micPerm.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startRecording() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            registerNotifOnce.launch(Manifest.permission.POST_NOTIFICATIONS); return
        }
        RecorderService.start(this, noteId)
        recPill.show(true)
        if (!recBound) bindService(Intent(this, RecorderService::class.java), recConn, Context.BIND_AUTO_CREATE)
    }
    private val registerNotifOnce = registerForActivityResult(ActivityResultContracts.RequestPermission()) { RecorderService.start(this, noteId); recPill.show(true) }

    private fun stopRecording() {
        val r = recService?.stopAndSave()
        updateRecPill()
        if (r != null && r.noteId == noteId) saveRecording(r)
    }

    private fun saveRecording(r: RecorderService.Result) {
        app.async({
            val rel = app.files.adoptTemp(r.file, "m4a")
            val name = "Voice memo " + Fmt.dateTime(System.currentTimeMillis())
            val wave = Media.encodeWave(r.amplitudes)
            app.repo.addAttachment(Attachment(0, noteId, AttachmentKind.AUDIO, name, rel, "audio/mp4", app.files.file(rel).length(), r.durationMs, 0, 0, null, System.currentTimeMillis(), 0, wave, null))
        }) { refreshSideData() }
    }

    private fun updateRecPill() {
        val s = recService
        val on = s?.isRecording == true && s.noteId == noteId
        recPill.show(on)
        if (!on) { findViewById<View>(R.id.recDot).clearAnimation(); return }
        findViewById<TextView>(R.id.recTime).text = Fmt.duration(s!!.elapsedMs)
        findViewById<ImageButton>(R.id.recPause).setImageResource(if (s.paused) R.drawable.ic_play else R.drawable.ic_pause)
        val dot = findViewById<View>(R.id.recDot)
        if (s.paused) { dot.clearAnimation(); dot.alpha = 0.4f } else if (dot.animation == null) {
            dot.alpha = 1f
            dot.startAnimation(android.view.animation.AlphaAnimation(1f, 0.2f).apply { duration = 600; repeatMode = android.view.animation.Animation.REVERSE; repeatCount = android.view.animation.Animation.INFINITE })
        }
    }

    // -------------------------------------------------------------- reminder

    private fun reminderTapped() {
        val n = note ?: return
        if (n.reminderAt != null && !n.reminderDone) {
            Sheets.menu(this, Fmt.dateTime(n.reminderAt), listOf(
                Sheets.Item(R.drawable.ic_clock, getString(R.string.set_reminder)) { ensureNotifThenPick() },
                Sheets.Item(R.drawable.ic_close, getString(R.string.remove_reminder), danger = true) { app.async({ app.repo.setReminder(noteId, null); ReminderScheduler.cancel(this, noteId) }) { toast(getString(R.string.reminder_removed)); load(false) } },
            ))
        } else ensureNotifThenPick()
    }

    private fun ensureNotifThenPick() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS) else pickReminderDate()
    }

    private fun pickReminderDate() {
        val c = Calendar.getInstance().apply { add(Calendar.HOUR_OF_DAY, 1); set(Calendar.MINUTE, 0) }
        DatePickerDialog(this, { _, y, m, d ->
            TimePickerDialog(this, { _, hh, mm ->
                val at = Calendar.getInstance().apply { set(y, m, d, hh, mm, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
                if (at <= System.currentTimeMillis()) { toast(getString(R.string.error_generic)); return@TimePickerDialog }
                app.async({ app.repo.setReminder(noteId, at); ReminderScheduler.schedule(this, noteId, at) }) {
                    toast(getString(R.string.reminder_set, Fmt.dateTime(at))); load(false)
                    if (Build.VERSION.SDK_INT >= 31 && !ReminderScheduler.canScheduleExact(this) && !app.prefs.exactAlarmAsked) {
                        app.prefs.exactAlarmAsked = true
                        Sheets.confirm(this, getString(R.string.reminder), getString(R.string.exact_alarm_hint), getString(R.string.settings)) {
                            runCatching { startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))) }
                        }
                    }
                }
            }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), false).show()
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
    }

    // ------------------------------------------------------------------- lock

    private fun lockTapped() {
        val n = note ?: return
        if (!app.prefs.hasPin) { LockActivity.setupPin(this) { if (it) doLock(true) else toast(getString(R.string.pin_required_first)) }; return }
        if (n.locked) LockActivity.unlock(this) { doLock(false) } else doLock(true)
    }
    private fun doLock(lock: Boolean) { app.async({ app.repo.setLocked(noteId, lock) }) { toast(getString(if (lock) R.string.note_locked else R.string.note_unlocked)); load(false); if (lock) { WidgetsInfoActivity.refreshWidgets(this) } } }

    // ------------------------------------------------------------------- more

    private fun showMore() {
        val n = note ?: return
        hideKeyboard()
        val items = ArrayList<Sheets.Item>()
        items += Sheets.Item(R.drawable.ic_folder, getString(R.string.move_to_folder)) { app.async({ app.repo.folders() }) { fs -> Sheets.folderPicker(this, fs, n.folderId) { fid -> app.async({ app.repo.setFolder(listOf(noteId), fid) }) { refreshSideData() } } } }
        items += Sheets.Item(R.drawable.ic_share, getString(R.string.share)) { shareNote() }
        items += Sheets.Item(R.drawable.ic_copy, getString(R.string.copy_text)) { val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager; cm.setPrimaryClip(android.content.ClipData.newPlainText("note", exportText())); toast(getString(R.string.copy_text)) }
        items += Sheets.Item(R.drawable.ic_duplicate, getString(R.string.duplicate)) { commitChunk(); saveNow(sync = true); app.async({ app.repo.duplicate(noteId) { app.files.copy(it) } }) { id -> if (id != null) startActivity(intent(this, id)) } }
        if (n.bodyType == BodyType.CHECKLIST) {
            items += Sheets.Item(R.drawable.ic_done_all, getString(R.string.uncheck_all)) { app.async({ app.repo.setAllChecked(noteId, false) }) { refreshSideData() } }
            items += Sheets.Item(R.drawable.ic_clear, getString(R.string.delete_checked)) { app.async({ app.repo.deleteChecked(noteId) }) { refreshSideData() } }
        }
        items += Sheets.Item(R.drawable.ic_replay, getString(R.string.replay)) { commitChunk(); saveNow(sync = true); startActivity(ReplayActivity.intent(this, noteId)) }
        items += Sheets.Item(R.drawable.ic_capsule, getString(R.string.seal_capsule)) { CapsuleDialogs.seal(this, noteId) { finish() } }
        items += Sheets.Item(R.drawable.ic_info, getString(R.string.note_info)) { showInfo() }
        items += Sheets.Item(R.drawable.ic_trash, getString(R.string.delete), danger = true) { deleteNote() }
        Sheets.menu(this, null, items)
    }

    private fun exportText(): String {
        val n = note ?: return ""
        val sb = StringBuilder()
        if (n.title.isNotBlank()) sb.append(n.title).append("\n\n")
        if (n.bodyType == BodyType.CHECKLIST) (openAdapter.items + doneAdapter.items).forEach { sb.append(if (it.checked) "☑ " else "☐ ").append(it.text).append('\n') }
        else sb.append(Repo.stripLinkTokens(body.text.toString()))
        return sb.toString().trim()
    }

    private fun shareNote() {
        val text = exportText()
        val atts = players.map { it.attachment } // only for uri grants below
        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).putExtra(Intent.EXTRA_SUBJECT, note?.displayTitle ?: "")
        startActivity(Intent.createChooser(i, getString(R.string.share)))
    }

    private fun showInfo() {
        val n = note ?: return
        app.async({ Triple(app.repo.wordCount(n), app.repo.attachments(noteId).sumOf { it.size }, app.repo.folder(n.folderId ?: -1)?.name) }) { (words, bytes, folder) ->
            val msg = buildString {
                append("Created  ").append(Fmt.long(n.createdAt)).append('\n')
                append("Edited  ").append(Fmt.long(n.updatedAt)).append('\n')
                append("Words  ").append(words).append('\n')
                append("Folder  ").append(folder ?: getString(R.string.folder_unfiled)).append('\n')
                append("Attachments  ").append(ai.techtroy.notebook.core.FileStore.humanSize(bytes)).append('\n')
                append("ID  #").append(n.id)
            }
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle(R.string.note_info).setMessage(msg).setPositiveButton(R.string.ok, null).show()
        }
    }

    private fun deleteNote() {
        dirty = false; body.removeCallbacks(saveRunnable)
        val id = noteId
        app.async({ app.repo.trash(listOf(id)); ReminderScheduler.cancel(this, id) }) { toast(getString(R.string.note_moved_to_trash)); finish() }
    }

    private fun openPages() {
        commitChunk(); saveNow(sync = true)
        startActivity(ai.techtroy.notebook.ink.PagesActivity.intent(this, noteId))
    }

    private fun finishEditor() {
        hideKeyboard()
        body.removeCallbacks(saveRunnable); commitChunk(); flushChecklist()
        val t = title.text.toString(); val b = body.text.toString(); val id = noteId; val wasDirty = dirty; dirty = false
        val manual = HashSet(manualLinks)
        app.async({
            if (wasDirty) { app.repo.saveText(id, t, b); app.repo.syncLinksFromBody(id, b, manual) }
            app.repo.discardIfEmpty(id)
        }) { finish(); overridePendingTransition(R.anim.fade_in, R.anim.slide_down) }
    }

    companion object {
        const val EXTRA_ID = "note_id"; const val EXTRA_NEW = "new"; const val EXTRA_SKETCH = "sketch"; const val EXTRA_VOICE = "voice"; const val EXTRA_ECHO = "echo"
        fun intent(ctx: Context, id: Long, isNew: Boolean = false): Intent = Intent(ctx, EditorActivity::class.java).putExtra(EXTRA_ID, id).putExtra(EXTRA_NEW, isNew)
    }
}
