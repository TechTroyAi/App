package ai.techtroy.notebook.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Single data access point. Synchronous, call from a background thread (see [ai.techtroy.notebook.App.io]).
 * Emits a change signal after every write so screens can refresh.
 */
class Repo(context: Context, dbName: String? = NotebookDb.DB_NAME) {

    private val helper = NotebookDb(context.applicationContext, dbName)
    private val db: SQLiteDatabase get() = helper.writableDatabase

    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    fun addListener(l: () -> Unit) = listeners.add(l)
    fun removeListener(l: () -> Unit) = listeners.remove(l)
    private fun changed() = listeners.forEach { it() }

    fun close() = helper.close()

    // ---------------------------------------------------------------- folders

    fun folders(): List<Folder> = db.rawQuery(
        """SELECT f.id, f.name, f.color, f.position,
                  (SELECT COUNT(*) FROM notes n WHERE n.folder_id=f.id AND n.deleted_at IS NULL) AS cnt
           FROM folders f ORDER BY f.position, f.name COLLATE NOCASE""", null
    ).useList { Folder(it.getLong(0), it.getString(1), it.getInt(2), it.getInt(3), it.getInt(4)) }

    fun folder(id: Long): Folder? = folders().firstOrNull { it.id == id }

    fun createFolder(name: String, color: Int): Long {
        val pos = (db.rawQuery("SELECT COALESCE(MAX(position),-1)+1 FROM folders", null).useFirst { it.getInt(0) } ?: 0)
        val id = db.insert("folders", null, ContentValues().apply {
            put("name", name.trim()); put("color", color); put("position", pos)
        })
        changed(); return id
    }

    fun renameFolder(id: Long, name: String, color: Int) {
        db.update("folders", ContentValues().apply { put("name", name.trim()); put("color", color) }, "id=?", arrayOf("$id"))
        changed()
    }

    fun deleteFolder(id: Long) {
        // notes fall back to Unfiled (FK ON DELETE SET NULL)
        db.delete("folders", "id=?", arrayOf("$id")); changed()
    }

    fun unfiledCount(): Int = db.rawQuery("SELECT COUNT(*) FROM notes WHERE folder_id IS NULL AND deleted_at IS NULL", null).useFirst { it.getInt(0) } ?: 0
    fun allCount(): Int = db.rawQuery("SELECT COUNT(*) FROM notes WHERE deleted_at IS NULL", null).useFirst { it.getInt(0) } ?: 0
    fun trashCount(): Int = db.rawQuery("SELECT COUNT(*) FROM notes WHERE deleted_at IS NOT NULL", null).useFirst { it.getInt(0) } ?: 0

    // ------------------------------------------------------------------ notes

    /** folderId: null = all, -1 = unfiled, else that folder */
    fun notes(folderId: Long? = null, query: String = "", sort: SortMode = SortMode.EDITED, asc: Boolean = false, trash: Boolean = false): List<Note> {
        val where = StringBuilder(if (trash) "n.deleted_at IS NOT NULL" else "n.deleted_at IS NULL")
        val args = ArrayList<String>()
        when (folderId) {
            null -> {}
            -1L -> where.append(" AND n.folder_id IS NULL")
            else -> { where.append(" AND n.folder_id=?"); args += "$folderId" }
        }
        val q = query.trim()
        if (q.isNotEmpty()) {
            // FTS prefix match on all tokens, plus a LIKE fallback for very short input
            val fts = q.split(Regex("\\s+")).filter { it.isNotBlank() }.joinToString(" ") { sanitizeFts(it) + "*" }
            where.append(" AND (n.id IN (SELECT note_id FROM notes_fts WHERE notes_fts MATCH ?) OR n.title LIKE ? OR n.body LIKE ?)")
            args += fts; args += "%$q%"; args += "%$q%"
        }
        val order = buildString {
            if (!trash) append("n.pinned DESC, ")
            append(
                when (sort) {
                    SortMode.EDITED -> "n.updated_at"
                    SortMode.CREATED -> "n.created_at"
                    SortMode.TITLE -> "CASE WHEN n.title='' THEN 1 ELSE 0 END, n.title COLLATE NOCASE"
                    SortMode.COLOR -> "n.color"
                }
            )
            append(if (asc) " ASC" else " DESC")
            if (sort != SortMode.EDITED) append(", n.updated_at DESC")
        }
        val rows = db.rawQuery("SELECT ${NOTE_COLS} FROM notes n WHERE $where ORDER BY $order", args.toTypedArray()).useList { readNote(it) }
        return rows.map { decorate(it) }
    }

    fun note(id: Long): Note? = db.rawQuery("SELECT ${NOTE_COLS} FROM notes n WHERE n.id=?", arrayOf("$id")).useFirst { readNote(it) }?.let { decorate(it) }

    fun createNote(bodyType: BodyType = BodyType.TEXT, folderId: Long? = null, color: NoteColor = NoteColor.NONE): Long {
        val now = System.currentTimeMillis()
        val id = db.insert("notes", null, ContentValues().apply {
            put("body_type", bodyType.db); put("color", color.id)
            if (folderId != null && folderId > 0) put("folder_id", folderId) else putNull("folder_id")
            put("created_at", now); put("updated_at", now)
        })
        history(id, "create", bodyType.db, now)
        if (bodyType == BodyType.PAGES) addPage(id)
        changed(); return id
    }

    /** Saves text fields; returns false if the note no longer exists. */
    fun saveText(id: Long, title: String, body: String, touch: Boolean = true): Boolean {
        val cv = ContentValues().apply { put("title", title); put("body", body); if (touch) put("updated_at", System.currentTimeMillis()) }
        val n = db.update("notes", cv, "id=?", arrayOf("$id"))
        if (n > 0) { history(id, "text", "${title.length}|${body.length}"); syncFts(id); changed() }
        return n > 0
    }

    fun setBodyType(id: Long, type: BodyType) {
        touch(id, ContentValues().apply { put("body_type", type.db) }); history(id, "type", type.db)
    }

    fun setPinned(ids: Collection<Long>, pinned: Boolean) = bulk(ids, ContentValues().apply { put("pinned", if (pinned) 1 else 0) }, touch = false)
    fun setColor(ids: Collection<Long>, color: NoteColor) = bulk(ids, ContentValues().apply { put("color", color.id) }, touch = false)
    fun setFolder(ids: Collection<Long>, folderId: Long?) = bulk(ids, ContentValues().apply { if (folderId == null || folderId <= 0) putNull("folder_id") else put("folder_id", folderId) }, touch = false)
    fun setLocked(id: Long, locked: Boolean) { touch(id, ContentValues().apply { put("locked", if (locked) 1 else 0) }, touchTime = false); history(id, if (locked) "lock" else "unlock", "") }
    fun setReminder(id: Long, at: Long?) { touch(id, ContentValues().apply { if (at == null) putNull("reminder_at") else put("reminder_at", at); put("reminder_done", 0) }, touchTime = false) }
    fun setReminderDone(id: Long) { touch(id, ContentValues().apply { put("reminder_done", 1) }, touchTime = false) }
    fun setCapsule(id: Long, until: Long?) { touch(id, ContentValues().apply { if (until == null) putNull("capsule_until") else put("capsule_until", until) }, touchTime = false); history(id, "capsule", "${until ?: 0}") }

    fun trash(ids: Collection<Long>) { bulk(ids, ContentValues().apply { put("deleted_at", System.currentTimeMillis()); put("pinned", 0) }, touch = false); ids.forEach { history(it, "trash", "") } }
    fun restore(ids: Collection<Long>) { bulk(ids, ContentValues().apply { putNull("deleted_at") }, touch = false); ids.forEach { history(it, "restore", "") } }

    fun deleteForever(ids: Collection<Long>, fileDeleter: (String) -> Unit) {
        ids.forEach { id ->
            attachments(id).forEach { a -> fileDeleter(a.path); a.thumbPath?.let(fileDeleter) }
            pages(id).forEach { p -> p.thumbPath?.let(fileDeleter) }
            db.delete("notes_fts", "note_id=?", arrayOf("$id"))
            db.delete("notes", "id=?", arrayOf("$id"))
        }
        changed()
    }

    fun emptyTrash(fileDeleter: (String) -> Unit) = deleteForever(notes(trash = true).map { it.id }, fileDeleter)

    fun purgeOldTrash(olderThanMs: Long, fileDeleter: (String) -> Unit) {
        val cutoff = System.currentTimeMillis() - olderThanMs
        val ids = db.rawQuery("SELECT id FROM notes WHERE deleted_at IS NOT NULL AND deleted_at<?", arrayOf("$cutoff")).useList { it.getLong(0) }
        if (ids.isNotEmpty()) deleteForever(ids, fileDeleter)
    }

    /** Empty, never-edited notes are discarded on exit. */
    fun discardIfEmpty(id: Long): Boolean {
        val n = note(id) ?: return true
        val empty = n.title.isBlank() && n.body.isBlank() && n.checklistTotal == 0 && n.attachmentCount == 0 &&
            (n.bodyType != BodyType.PAGES || pages(id).all { it.strokes.length < 40 && it.objects == "[]" })
        if (empty) { db.delete("notes", "id=?", arrayOf("$id")); db.delete("notes_fts", "note_id=?", arrayOf("$id")); changed() }
        return empty
    }

    fun duplicate(id: Long, fileCopier: (String) -> String?): Long? {
        val n = note(id) ?: return null
        val now = System.currentTimeMillis()
        val newId = db.insert("notes", null, ContentValues().apply {
            put("title", if (n.title.isBlank()) "" else n.title + " (copy)"); put("body", n.body); put("body_type", n.bodyType.db)
            put("color", n.color.id); if (n.folderId != null) put("folder_id", n.folderId) else putNull("folder_id")
            put("created_at", now); put("updated_at", now)
        })
        checklist(id).forEach { addChecklistItem(newId, it.text, it.checked, it.position, notify = false) }
        attachments(id).forEach { a ->
            val p = fileCopier(a.path) ?: return@forEach
            val t = a.thumbPath?.let(fileCopier)
            db.insert("attachments", null, attachmentValues(a.copy(noteId = newId, path = p, thumbPath = t, createdAt = now)))
        }
        pages(id).forEach { p -> db.insert("pages", null, pageValues(p.copy(noteId = newId, thumbPath = p.thumbPath?.let(fileCopier), updatedAt = now))) }
        links(id).forEach { addLink(newId, it.toId, notify = false) }
        history(newId, "create", "duplicate:$id", now)
        syncFts(newId); changed(); return newId
    }

    fun wordCount(n: Note): Int {
        val text = n.body + " " + n.title + " " + checklist(n.id).joinToString(" ") { it.text }
        return text.split(Regex("\\s+")).count { it.isNotBlank() }
    }

    // -------------------------------------------------------------- checklist

    fun checklist(noteId: Long): List<ChecklistItem> = db.rawQuery(
        "SELECT id, note_id, text, checked, position FROM checklist_items WHERE note_id=? ORDER BY position, id", arrayOf("$noteId")
    ).useList { ChecklistItem(it.getLong(0), it.getLong(1), it.getString(2), it.getInt(3) == 1, it.getInt(4)) }

    fun addChecklistItem(noteId: Long, text: String = "", checked: Boolean = false, position: Int? = null, notify: Boolean = true): Long {
        val pos = position ?: ((db.rawQuery("SELECT COALESCE(MAX(position),-1)+1 FROM checklist_items WHERE note_id=?", arrayOf("$noteId")).useFirst { it.getInt(0) }) ?: 0)
        val id = db.insert("checklist_items", null, ContentValues().apply { put("note_id", noteId); put("text", text); put("checked", if (checked) 1 else 0); put("position", pos) })
        touchOnly(noteId); if (notify) { syncFts(noteId); changed() }
        return id
    }

    fun updateChecklistItem(item: ChecklistItem, notify: Boolean = true) {
        db.update("checklist_items", ContentValues().apply { put("text", item.text); put("checked", if (item.checked) 1 else 0); put("position", item.position) }, "id=?", arrayOf("${item.id}"))
        touchOnly(item.noteId); history(item.noteId, if (item.checked) "check" else "uncheck", "${item.id}")
        if (notify) { syncFts(item.noteId); changed() }
    }

    fun deleteChecklistItem(id: Long, noteId: Long) { db.delete("checklist_items", "id=?", arrayOf("$id")); touchOnly(noteId); syncFts(noteId); changed() }

    fun reorderChecklist(noteId: Long, orderedIds: List<Long>) {
        db.beginTransaction()
        try {
            orderedIds.forEachIndexed { i, id -> db.update("checklist_items", ContentValues().apply { put("position", i) }, "id=? AND note_id=?", arrayOf("$id", "$noteId")) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        touchOnly(noteId); changed()
    }

    fun setAllChecked(noteId: Long, checked: Boolean) { db.execSQL("UPDATE checklist_items SET checked=? WHERE note_id=?", arrayOf(if (checked) 1 else 0, noteId)); touchOnly(noteId); changed() }
    fun deleteChecked(noteId: Long) { db.delete("checklist_items", "note_id=? AND checked=1", arrayOf("$noteId")); touchOnly(noteId); syncFts(noteId); changed() }

    /** text -> checklist: each non-empty line becomes an item. checklist -> text: items become "- [x] line"s. */
    fun convertToChecklist(noteId: Long) {
        val n = note(noteId) ?: return
        db.beginTransaction()
        try {
            n.body.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEachIndexed { i, line ->
                val done = line.startsWith("[x]", true) || line.startsWith("- [x]", true) || line.startsWith("☑")
                val clean = line.replace(Regex("^(-\\s*)?\\[( |x|X)\\]\\s*|^[•\\-*☐☑]\\s*"), "")
                addChecklistItem(noteId, clean, done, i, notify = false)
            }
            db.update("notes", ContentValues().apply { put("body", ""); put("body_type", BodyType.CHECKLIST.db) }, "id=?", arrayOf("$noteId"))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        history(noteId, "type", "checklist"); syncFts(noteId); changed()
    }

    fun convertToText(noteId: Long) {
        val items = checklist(noteId)
        val body = items.joinToString("\n") { (if (it.checked) "☑ " else "☐ ") + it.text }
        db.beginTransaction()
        try {
            db.delete("checklist_items", "note_id=?", arrayOf("$noteId"))
            db.update("notes", ContentValues().apply { put("body", body); put("body_type", BodyType.TEXT.db) }, "id=?", arrayOf("$noteId"))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        history(noteId, "type", "text"); syncFts(noteId); changed()
    }

    // ------------------------------------------------------------ attachments

    fun attachments(noteId: Long): List<Attachment> = db.rawQuery(
        "SELECT id,note_id,kind,name,path,mime,size,duration_ms,width,height,thumb_path,created_at,position,data,annotations FROM attachments WHERE note_id=? ORDER BY position, id", arrayOf("$noteId")
    ).useList { readAttachment(it) }

    fun attachment(id: Long): Attachment? = db.rawQuery(
        "SELECT id,note_id,kind,name,path,mime,size,duration_ms,width,height,thumb_path,created_at,position,data,annotations FROM attachments WHERE id=?", arrayOf("$id")
    ).useFirst { readAttachment(it) }

    fun addAttachment(a: Attachment): Long {
        val pos = (db.rawQuery("SELECT COALESCE(MAX(position),-1)+1 FROM attachments WHERE note_id=?", arrayOf("${a.noteId}")).useFirst { it.getInt(0) }) ?: 0
        val id = db.insert("attachments", null, attachmentValues(a.copy(position = pos)))
        touchOnly(a.noteId); history(a.noteId, "attach", "${a.kind.db}:$id"); syncFts(a.noteId); changed(); return id
    }

    fun updateAttachment(a: Attachment) { db.update("attachments", attachmentValues(a), "id=?", arrayOf("${a.id}")); touchOnly(a.noteId); changed() }
    fun updateAttachmentData(id: Long, data: String?, thumbPath: String?) {
        db.update("attachments", ContentValues().apply { put("data", data); put("thumb_path", thumbPath) }, "id=?", arrayOf("$id"))
        attachment(id)?.let { touchOnly(it.noteId); history(it.noteId, "sketch", "$id") }; changed()
    }
    fun updateAttachmentAnnotations(id: Long, json: String?) {
        db.update("attachments", ContentValues().apply { put("annotations", json) }, "id=?", arrayOf("$id"))
        attachment(id)?.let { touchOnly(it.noteId); history(it.noteId, "annotate", "$id") }; changed()
    }
    fun renameAttachment(id: Long, name: String) { db.update("attachments", ContentValues().apply { put("name", name) }, "id=?", arrayOf("$id")); attachment(id)?.let { syncFts(it.noteId) }; changed() }

    fun removeAttachment(id: Long, fileDeleter: (String) -> Unit) {
        val a = attachment(id) ?: return
        fileDeleter(a.path); a.thumbPath?.let(fileDeleter)
        db.delete("attachments", "id=?", arrayOf("$id")); touchOnly(a.noteId); syncFts(a.noteId); changed()
    }

    fun totalAttachmentBytes(): Long = db.rawQuery("SELECT COALESCE(SUM(size),0) FROM attachments", null).useFirst { it.getLong(0) } ?: 0L

    // ------------------------------------------------------------------ links

    fun links(noteId: Long): List<NoteLink> = db.rawQuery("SELECT from_id,to_id,created_at FROM note_links WHERE from_id=?", arrayOf("$noteId")).useList { NoteLink(it.getLong(0), it.getLong(1), it.getLong(2)) }
    fun backlinks(noteId: Long): List<NoteLink> = db.rawQuery("SELECT from_id,to_id,created_at FROM note_links WHERE to_id=?", arrayOf("$noteId")).useList { NoteLink(it.getLong(0), it.getLong(1), it.getLong(2)) }
    fun allLinks(): List<NoteLink> = db.rawQuery("SELECT l.from_id,l.to_id,l.created_at FROM note_links l JOIN notes a ON a.id=l.from_id JOIN notes b ON b.id=l.to_id WHERE a.deleted_at IS NULL AND b.deleted_at IS NULL", null).useList { NoteLink(it.getLong(0), it.getLong(1), it.getLong(2)) }

    fun addLink(from: Long, to: Long, notify: Boolean = true) {
        if (from == to) return
        db.insertWithOnConflict("note_links", null, ContentValues().apply { put("from_id", from); put("to_id", to); put("created_at", System.currentTimeMillis()) }, SQLiteDatabase.CONFLICT_IGNORE)
        history(from, "link", "$to"); if (notify) changed()
    }
    fun removeLink(from: Long, to: Long) { db.delete("note_links", "from_id=? AND to_id=?", arrayOf("$from", "$to")); changed() }

    /** Rebuild outgoing links from [[id:123]] tokens in the body. Manual links (not in body) survive. */
    fun syncLinksFromBody(noteId: Long, body: String, manualKeep: Set<Long>) {
        val inBody = LINK_TOKEN.findAll(body).map { it.groupValues[1].toLong() }.toSet()
        val existing = links(noteId).map { it.toId }.toSet()
        (inBody - existing).forEach { addLink(noteId, it, notify = false) }
        (existing - inBody - manualKeep).forEach { db.delete("note_links", "from_id=? AND to_id=?", arrayOf("$noteId", "$it")) }
    }

    fun searchTitles(query: String, excludeId: Long, limit: Int = 12): List<Note> {
        val q = query.trim()
        val args = if (q.isEmpty()) arrayOf("$excludeId") else arrayOf("$excludeId", "%$q%", "%$q%")
        val sql = "SELECT ${NOTE_COLS} FROM notes n WHERE n.deleted_at IS NULL AND n.id<>? " +
            (if (q.isEmpty()) "" else "AND (n.title LIKE ? OR n.body LIKE ?) ") + "ORDER BY n.updated_at DESC LIMIT $limit"
        return db.rawQuery(sql, args).useList { readNote(it) }
    }

    // ------------------------------------------------------------------ pages

    fun pages(noteId: Long): List<Page> = db.rawQuery("SELECT id,note_id,position,paper,dark,strokes,objects,thumb_path,updated_at FROM pages WHERE note_id=? ORDER BY position,id", arrayOf("$noteId")).useList { readPage(it) }
    fun page(id: Long): Page? = db.rawQuery("SELECT id,note_id,position,paper,dark,strokes,objects,thumb_path,updated_at FROM pages WHERE id=?", arrayOf("$id")).useFirst { readPage(it) }

    fun addPage(noteId: Long, paper: String = "lined", dark: Boolean = true): Long {
        val pos = (db.rawQuery("SELECT COALESCE(MAX(position),-1)+1 FROM pages WHERE note_id=?", arrayOf("$noteId")).useFirst { it.getInt(0) }) ?: 0
        val id = db.insert("pages", null, ContentValues().apply { put("note_id", noteId); put("position", pos); put("paper", paper); put("dark", if (dark) 1 else 0); put("updated_at", System.currentTimeMillis()) })
        touchOnly(noteId); changed(); return id
    }
    fun savePage(p: Page) { db.update("pages", pageValues(p.copy(updatedAt = System.currentTimeMillis())), "id=?", arrayOf("${p.id}")); touchOnly(p.noteId); history(p.noteId, "ink", "${p.id}"); changed() }
    fun deletePage(id: Long, fileDeleter: (String) -> Unit) { val p = page(id) ?: return; p.thumbPath?.let(fileDeleter); db.delete("pages", "id=?", arrayOf("$id")); touchOnly(p.noteId); changed() }
    fun setPagesPaper(noteId: Long, paper: String, dark: Boolean) { db.execSQL("UPDATE pages SET paper=?, dark=? WHERE note_id=?", arrayOf(paper, if (dark) 1 else 0, noteId)); changed() }

    // ---------------------------------------------------------------- history

    fun history(noteId: Long, kind: String, payload: String, at: Long = System.currentTimeMillis()) {
        db.insert("history", null, ContentValues().apply { put("note_id", noteId); put("at", at); put("kind", kind); put("payload", payload) })
    }
    fun historyOf(noteId: Long): List<Triple<Long, String, String>> = db.rawQuery("SELECT at,kind,payload FROM history WHERE note_id=? ORDER BY at,id", arrayOf("$noteId")).useList { Triple(it.getLong(0), it.getString(1), it.getString(2)) }

    // ----------------------------------------------------------------- echoes

    fun echoesForDay(day: Long): List<Echo> = db.rawQuery("SELECT e.id,e.note_id,e.kind,e.text,e.for_day,e.dismissed FROM echoes e JOIN notes n ON n.id=e.note_id WHERE e.for_day=? AND e.dismissed=0 AND n.deleted_at IS NULL ORDER BY e.id", arrayOf("$day")).useList { Echo(it.getLong(0), it.getLong(1), it.getString(2), it.getString(3), it.getLong(4), it.getInt(5) == 1) }
    fun addEcho(noteId: Long, kind: String, text: String, day: Long) { db.insertWithOnConflict("echoes", null, ContentValues().apply { put("note_id", noteId); put("kind", kind); put("text", text); put("for_day", day) }, SQLiteDatabase.CONFLICT_IGNORE) }
    fun dismissEcho(id: Long) { db.update("echoes", ContentValues().apply { put("dismissed", 1) }, "id=?", arrayOf("$id")); changed() }
    fun notesCreatedBetween(from: Long, to: Long): List<Note> = db.rawQuery("SELECT ${NOTE_COLS} FROM notes n WHERE n.deleted_at IS NULL AND n.created_at>=? AND n.created_at<? ORDER BY n.created_at", arrayOf("$from", "$to")).useList { readNote(it) }
    fun allEchoes(limit: Int = 200): List<Echo> = db.rawQuery("SELECT e.id,e.note_id,e.kind,e.text,e.for_day,e.dismissed FROM echoes e JOIN notes n ON n.id=e.note_id WHERE n.deleted_at IS NULL ORDER BY e.for_day DESC, e.id DESC LIMIT $limit", null).useList { Echo(it.getLong(0), it.getLong(1), it.getString(2), it.getString(3), it.getLong(4), it.getInt(5) == 1) }
    fun sealedCapsules(): List<Note> = db.rawQuery("SELECT ${NOTE_COLS} FROM notes n WHERE n.deleted_at IS NULL AND n.capsule_until IS NOT NULL AND n.capsule_until>? ORDER BY n.capsule_until", arrayOf("${System.currentTimeMillis()}")).useList { readNote(it) }
    fun capsulesOpening(from: Long, to: Long): List<Note> = db.rawQuery("SELECT ${NOTE_COLS} FROM notes n WHERE n.deleted_at IS NULL AND n.capsule_until>=? AND n.capsule_until<?", arrayOf("$from", "$to")).useList { readNote(it) }

    // ---------------------------------------------------------------- widgets

    fun widgetNote(widgetId: Int): Long? = db.rawQuery("SELECT note_id FROM widgets WHERE widget_id=?", arrayOf("$widgetId")).useFirst { if (it.isNull(0)) null else it.getLong(0) }
    fun setWidgetNote(widgetId: Int, noteId: Long?) { db.insertWithOnConflict("widgets", null, ContentValues().apply { put("widget_id", widgetId); if (noteId == null) putNull("note_id") else put("note_id", noteId) }, SQLiteDatabase.CONFLICT_REPLACE) }
    fun removeWidget(widgetId: Int) { db.delete("widgets", "widget_id=?", arrayOf("$widgetId")) }

    // -------------------------------------------------------------- reminders

    fun pendingReminders(): List<Note> = db.rawQuery("SELECT ${NOTE_COLS} FROM notes n WHERE n.deleted_at IS NULL AND n.reminder_at IS NOT NULL AND n.reminder_done=0", null).useList { readNote(it) }

    // ----------------------------------------------------------------- backup

    fun rawDb(): SQLiteDatabase = db
    fun notifyChanged() = changed()

    // ---------------------------------------------------------------- helpers

    private fun touch(id: Long, cv: ContentValues, touchTime: Boolean = true) {
        if (touchTime) cv.put("updated_at", System.currentTimeMillis())
        db.update("notes", cv, "id=?", arrayOf("$id")); changed()
    }
    private fun touchOnly(id: Long) = db.update("notes", ContentValues().apply { put("updated_at", System.currentTimeMillis()) }, "id=?", arrayOf("$id"))
    private fun bulk(ids: Collection<Long>, cv: ContentValues, touch: Boolean) {
        if (ids.isEmpty()) return
        if (touch) cv.put("updated_at", System.currentTimeMillis())
        db.beginTransaction()
        try { ids.forEach { db.update("notes", cv, "id=?", arrayOf("$it")) }; db.setTransactionSuccessful() } finally { db.endTransaction() }
        changed()
    }

    private fun syncFts(noteId: Long) {
        val n = db.rawQuery("SELECT title, body FROM notes WHERE id=?", arrayOf("$noteId")).useFirst { it.getString(0) to it.getString(1) } ?: return
        val extra = (checklist(noteId).map { it.text } + attachments(noteId).map { it.name }).joinToString(" ")
        db.delete("notes_fts", "note_id=?", arrayOf("$noteId"))
        db.insert("notes_fts", null, ContentValues().apply { put("note_id", noteId); put("title", n.first); put("body", stripLinkTokens(n.second)); put("extra", extra) })
    }

    private fun decorate(n: Note): Note {
        val items = if (n.bodyType == BodyType.CHECKLIST) checklist(n.id) else emptyList()
        val atts = attachments(n.id)
        val pagesCount = if (n.bodyType == BodyType.PAGES) (db.rawQuery("SELECT COUNT(*) FROM pages WHERE note_id=?", arrayOf("${n.id}")).useFirst { it.getInt(0) } ?: 0) else 0
        val firstImage = atts.firstOrNull { it.kind == AttachmentKind.IMAGE || it.kind == AttachmentKind.SKETCH || it.kind == AttachmentKind.VIDEO }?.let { it.thumbPath ?: it.path }
            ?: if (n.bodyType == BodyType.PAGES) db.rawQuery("SELECT thumb_path FROM pages WHERE note_id=? ORDER BY position LIMIT 1", arrayOf("${n.id}")).useFirst { it.getString(0) } else null
        val linkCount = (db.rawQuery("SELECT COUNT(*) FROM note_links WHERE from_id=? OR to_id=?", arrayOf("${n.id}", "${n.id}")).useFirst { it.getInt(0) } ?: 0)
        return n.copy(
            checklistPreview = items.filter { !it.checked }.take(4).ifEmpty { items.take(4) },
            checklistTotal = items.size, checklistDone = items.count { it.checked },
            attachmentCount = atts.size, linkCount = linkCount, firstImage = firstImage,
            hasAudio = atts.any { it.kind == AttachmentKind.AUDIO }, pageCount = pagesCount,
        )
    }

    private fun readNote(c: Cursor) = Note(
        id = c.getLong(0), title = c.getString(1), body = c.getString(2), bodyType = BodyType.from(c.getString(3)),
        color = NoteColor.from(c.getInt(4)), folderId = if (c.isNull(5)) null else c.getLong(5), pinned = c.getInt(6) == 1,
        locked = c.getInt(7) == 1, createdAt = c.getLong(8), updatedAt = c.getLong(9), deletedAt = if (c.isNull(10)) null else c.getLong(10),
        reminderAt = if (c.isNull(11)) null else c.getLong(11), reminderDone = c.getInt(12) == 1, capsuleUntil = if (c.isNull(13)) null else c.getLong(13),
    )

    private fun readAttachment(c: Cursor) = Attachment(
        id = c.getLong(0), noteId = c.getLong(1), kind = AttachmentKind.from(c.getString(2)), name = c.getString(3), path = c.getString(4), mime = c.getString(5),
        size = c.getLong(6), durationMs = c.getLong(7), width = c.getInt(8), height = c.getInt(9), thumbPath = c.getString(10), createdAt = c.getLong(11),
        position = c.getInt(12), data = c.getString(13), annotations = c.getString(14),
    )

    private fun attachmentValues(a: Attachment) = ContentValues().apply {
        put("note_id", a.noteId); put("kind", a.kind.db); put("name", a.name); put("path", a.path); put("mime", a.mime); put("size", a.size)
        put("duration_ms", a.durationMs); put("width", a.width); put("height", a.height); put("thumb_path", a.thumbPath); put("created_at", a.createdAt)
        put("position", a.position); put("data", a.data); put("annotations", a.annotations)
    }

    private fun readPage(c: Cursor) = Page(c.getLong(0), c.getLong(1), c.getInt(2), c.getString(3), c.getInt(4) == 1, c.getString(5), c.getString(6), c.getString(7), c.getLong(8))
    private fun pageValues(p: Page) = ContentValues().apply {
        put("note_id", p.noteId); put("position", p.position); put("paper", p.paper); put("dark", if (p.dark) 1 else 0); put("strokes", p.strokes); put("objects", p.objects); put("thumb_path", p.thumbPath); put("updated_at", p.updatedAt)
    }

    companion object {
        const val NOTE_COLS = "n.id,n.title,n.body,n.body_type,n.color,n.folder_id,n.pinned,n.locked,n.created_at,n.updated_at,n.deleted_at,n.reminder_at,n.reminder_done,n.capsule_until"
        val LINK_TOKEN = Regex("\\[\\[id:(\\d+)\\]\\]")
        fun stripLinkTokens(s: String) = LINK_TOKEN.replace(s, "")
        fun sanitizeFts(t: String) = t.replace(Regex("[^\\p{L}\\p{N}]"), "")

        inline fun <T> Cursor.useList(read: (Cursor) -> T): List<T> = use { c -> ArrayList<T>(c.count).also { out -> while (c.moveToNext()) out += read(c) } }
        inline fun <T> Cursor.useFirst(read: (Cursor) -> T): T? = use { c -> if (c.moveToFirst()) read(c) else null }
    }
}
