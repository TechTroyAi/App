package ai.techtroy.notebook.core

import android.content.ContentValues
import ai.techtroy.notebook.App
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Backup zip layout:
 *   notebook.json   — all tables as JSON (folders, notes, checklist_items, attachments, note_links, pages, history)
 *   files/<rel>     — attachment/thumb/page files (optional)
 * Import merges: ids are remapped, nothing existing is touched.
 */
object Backup {
    private val TABLES = listOf("folders", "notes", "checklist_items", "attachments", "note_links", "pages", "history")

    fun export(app: App, out: OutputStream, includeFiles: Boolean) {
        val db = app.repo.rawDb()
        val root = JSONObject().put("app", "Notebook by Troy").put("version", 1).put("exported_at", System.currentTimeMillis())
        for (t in TABLES) {
            val arr = JSONArray()
            db.rawQuery("SELECT * FROM $t", null).use { c ->
                val cols = c.columnNames
                while (c.moveToNext()) {
                    val o = JSONObject()
                    cols.forEachIndexed { i, name -> when (c.getType(i)) { android.database.Cursor.FIELD_TYPE_NULL -> o.put(name, JSONObject.NULL); android.database.Cursor.FIELD_TYPE_INTEGER -> o.put(name, c.getLong(i)); android.database.Cursor.FIELD_TYPE_FLOAT -> o.put(name, c.getDouble(i)); else -> o.put(name, c.getString(i)) } }
                    arr.put(o)
                }
            }
            root.put(t, arr)
        }
        ZipOutputStream(out.buffered()).use { z ->
            z.putNextEntry(ZipEntry("notebook.json")); z.write(root.toString().toByteArray()); z.closeEntry()
            if (includeFiles) {
                val rels = HashSet<String>()
                root.getJSONArray("attachments").let { a -> for (i in 0 until a.length()) { val o = a.getJSONObject(i); rels += o.getString("path"); o.optString("thumb_path", null)?.takeIf { it != "null" && it.isNotEmpty() }?.let { rels += it } } }
                root.getJSONArray("pages").let { a -> for (i in 0 until a.length()) { val o = a.getJSONObject(i); o.optString("thumb_path", null)?.takeIf { it != "null" && it.isNotEmpty() }?.let { rels += it } } }
                for (rel in rels) {
                    val f = app.files.file(rel); if (!f.exists()) continue
                    z.putNextEntry(ZipEntry("files/$rel")); f.inputStream().use { it.copyTo(z) }; z.closeEntry()
                }
            }
        }
    }

    /** Returns number of notes imported. */
    fun import(app: App, input: InputStream): Int {
        var json: JSONObject? = null
        val pendingFiles = HashMap<String, ByteArray>()
        ZipInputStream(input.buffered()).use { z ->
            var e: ZipEntry? = z.nextEntry
            while (e != null) {
                if (e.name == "notebook.json") json = JSONObject(z.readBytes().toString(Charsets.UTF_8))
                else if (e.name.startsWith("files/") && !e.isDirectory) pendingFiles[e.name.removePrefix("files/")] = z.readBytes()
                z.closeEntry(); e = z.nextEntry
            }
        }
        val root = json ?: throw IllegalArgumentException("not a Notebook backup")
        val db = app.repo.rawDb()
        val folderMap = HashMap<Long, Long>(); val noteMap = HashMap<Long, Long>(); val fileMap = HashMap<String, String>()
        // files: write under new names to avoid collisions
        for ((rel, bytes) in pendingFiles) {
            val dir = rel.substringBeforeLast('/', "attachments"); val ext = rel.substringAfterLast('.', "")
            val newRel = "$dir/" + app.files.newName(ext)
            app.files.file(newRel).apply { parentFile?.mkdirs(); writeBytes(bytes) }
            fileMap[rel] = newRel
        }
        var count = 0
        db.beginTransaction()
        try {
            root.optJSONArray("folders")?.let { a -> for (i in 0 until a.length()) { val o = a.getJSONObject(i); val id = db.insert("folders", null, cv(o, exclude = setOf("id"))); folderMap[o.getLong("id")] = id } }
            root.optJSONArray("notes")?.let { a -> for (i in 0 until a.length()) {
                val o = a.getJSONObject(i); val cv = cv(o, exclude = setOf("id", "folder_id"))
                val fid = if (o.isNull("folder_id")) null else folderMap[o.getLong("folder_id")]
                if (fid != null) cv.put("folder_id", fid) else cv.putNull("folder_id")
                val id = db.insert("notes", null, cv); noteMap[o.getLong("id")] = id; count++
            } }
            fun remapNote(o: JSONObject, cv: ContentValues, col: String = "note_id"): Boolean { val nid = noteMap[o.optLong(col, -1)] ?: return false; cv.put(col, nid); return true }
            root.optJSONArray("checklist_items")?.let { a -> for (i in 0 until a.length()) { val o = a.getJSONObject(i); val cv = cv(o, setOf("id", "note_id")); if (remapNote(o, cv)) db.insert("checklist_items", null, cv) } }
            root.optJSONArray("attachments")?.let { a -> for (i in 0 until a.length()) {
                val o = a.getJSONObject(i); val cv = cv(o, setOf("id", "note_id", "path", "thumb_path")); if (!remapNote(o, cv)) continue
                val path = fileMap[o.getString("path")] ?: continue
                cv.put("path", path); cv.put("thumb_path", o.optString("thumb_path", null)?.let { fileMap[it] })
                db.insert("attachments", null, cv)
            } }
            root.optJSONArray("pages")?.let { a -> for (i in 0 until a.length()) { val o = a.getJSONObject(i); val cv = cv(o, setOf("id", "note_id", "thumb_path")); if (!remapNote(o, cv)) continue; cv.put("thumb_path", o.optString("thumb_path", null)?.let { fileMap[it] }); db.insert("pages", null, cv) } }
            root.optJSONArray("note_links")?.let { a -> for (i in 0 until a.length()) { val o = a.getJSONObject(i); val f = noteMap[o.getLong("from_id")]; val t = noteMap[o.getLong("to_id")]; if (f != null && t != null) db.insert("note_links", null, ContentValues().apply { put("from_id", f); put("to_id", t); put("created_at", o.optLong("created_at", System.currentTimeMillis())) }) } }
            root.optJSONArray("history")?.let { a -> for (i in 0 until a.length()) { val o = a.getJSONObject(i); val cv = cv(o, setOf("id", "note_id")); if (remapNote(o, cv)) db.insert("history", null, cv) } }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        // rebuild FTS for imported notes
        noteMap.values.forEach { id -> app.repo.note(id)?.let { n -> app.repo.saveText(id, n.title, n.body, touch = false) } }
        app.repo.notifyChanged()
        return count
    }

    private fun cv(o: JSONObject, exclude: Set<String>): ContentValues {
        val cv = ContentValues()
        for (k in o.keys()) {
            if (k in exclude) continue
            val v = o.get(k)
            when (v) { JSONObject.NULL -> cv.putNull(k); is Int -> cv.put(k, v); is Long -> cv.put(k, v); is Double -> cv.put(k, v); is Boolean -> cv.put(k, if (v) 1 else 0); else -> cv.put(k, v.toString()) }
        }
        return cv
    }
}
