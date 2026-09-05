package ai.techtroy.notebook.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Schema owner. Every schema change = bump [VERSION] + add a step to [MIGRATIONS].
 * Fresh installs run all migrations in order too, so there is exactly one path to any version.
 */
class NotebookDb(context: Context, name: String? = DB_NAME) : SQLiteOpenHelper(context, name, null, VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        onUpgrade(db, 0, VERSION)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        for (v in (oldVersion + 1)..newVersion) {
            val step = MIGRATIONS[v] ?: error("No migration to schema version $v")
            db.beginTransaction()
            try {
                step(db)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Never destroy user data on downgrade; keep whatever schema is there.
    }

    companion object {
        const val DB_NAME = "notebook.db"
        const val VERSION = 1

        /** version -> statements that bring the schema from (version-1) to version */
        val MIGRATIONS: Map<Int, (SQLiteDatabase) -> Unit> = mapOf(
            1 to { db ->
                db.execSQL(
                    """CREATE TABLE folders(
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        name TEXT NOT NULL,
                        color INTEGER NOT NULL DEFAULT 0,
                        position INTEGER NOT NULL DEFAULT 0
                    )"""
                )
                db.execSQL(
                    """CREATE TABLE notes(
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        title TEXT NOT NULL DEFAULT '',
                        body TEXT NOT NULL DEFAULT '',
                        body_type TEXT NOT NULL DEFAULT 'text',
                        color INTEGER NOT NULL DEFAULT 0,
                        folder_id INTEGER REFERENCES folders(id) ON DELETE SET NULL,
                        pinned INTEGER NOT NULL DEFAULT 0,
                        locked INTEGER NOT NULL DEFAULT 0,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                        deleted_at INTEGER,
                        reminder_at INTEGER,
                        reminder_done INTEGER NOT NULL DEFAULT 0,
                        capsule_until INTEGER
                    )"""
                )
                db.execSQL("CREATE INDEX idx_notes_folder ON notes(folder_id)")
                db.execSQL("CREATE INDEX idx_notes_deleted ON notes(deleted_at)")
                db.execSQL("CREATE INDEX idx_notes_updated ON notes(updated_at)")
                db.execSQL("CREATE INDEX idx_notes_reminder ON notes(reminder_at)")
                db.execSQL(
                    """CREATE TABLE checklist_items(
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        note_id INTEGER NOT NULL REFERENCES notes(id) ON DELETE CASCADE,
                        text TEXT NOT NULL DEFAULT '',
                        checked INTEGER NOT NULL DEFAULT 0,
                        position INTEGER NOT NULL DEFAULT 0
                    )"""
                )
                db.execSQL("CREATE INDEX idx_items_note ON checklist_items(note_id, position)")
                db.execSQL(
                    """CREATE TABLE attachments(
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        note_id INTEGER NOT NULL REFERENCES notes(id) ON DELETE CASCADE,
                        kind TEXT NOT NULL,
                        name TEXT NOT NULL DEFAULT '',
                        path TEXT NOT NULL,
                        mime TEXT NOT NULL DEFAULT '',
                        size INTEGER NOT NULL DEFAULT 0,
                        duration_ms INTEGER NOT NULL DEFAULT 0,
                        width INTEGER NOT NULL DEFAULT 0,
                        height INTEGER NOT NULL DEFAULT 0,
                        thumb_path TEXT,
                        created_at INTEGER NOT NULL,
                        position INTEGER NOT NULL DEFAULT 0,
                        data TEXT,
                        annotations TEXT
                    )"""
                )
                db.execSQL("CREATE INDEX idx_att_note ON attachments(note_id, position)")
                db.execSQL(
                    """CREATE TABLE note_links(
                        from_id INTEGER NOT NULL REFERENCES notes(id) ON DELETE CASCADE,
                        to_id INTEGER NOT NULL REFERENCES notes(id) ON DELETE CASCADE,
                        created_at INTEGER NOT NULL,
                        PRIMARY KEY(from_id, to_id)
                    )"""
                )
                db.execSQL("CREATE INDEX idx_links_to ON note_links(to_id)")
                db.execSQL(
                    """CREATE TABLE pages(
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        note_id INTEGER NOT NULL REFERENCES notes(id) ON DELETE CASCADE,
                        position INTEGER NOT NULL DEFAULT 0,
                        paper TEXT NOT NULL DEFAULT 'lined',
                        dark INTEGER NOT NULL DEFAULT 1,
                        strokes TEXT NOT NULL DEFAULT '{"v":1,"strokes":[]}',
                        objects TEXT NOT NULL DEFAULT '[]',
                        thumb_path TEXT,
                        updated_at INTEGER NOT NULL
                    )"""
                )
                db.execSQL("CREATE INDEX idx_pages_note ON pages(note_id, position)")
                // Edit history events power Replay + Echoes. Compact, append-only.
                db.execSQL(
                    """CREATE TABLE history(
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        note_id INTEGER NOT NULL REFERENCES notes(id) ON DELETE CASCADE,
                        at INTEGER NOT NULL,
                        kind TEXT NOT NULL,
                        payload TEXT NOT NULL DEFAULT ''
                    )"""
                )
                db.execSQL("CREATE INDEX idx_hist_note ON history(note_id, at)")
                db.execSQL(
                    """CREATE TABLE echoes(
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        note_id INTEGER NOT NULL REFERENCES notes(id) ON DELETE CASCADE,
                        kind TEXT NOT NULL,
                        text TEXT NOT NULL,
                        for_day INTEGER NOT NULL,
                        dismissed INTEGER NOT NULL DEFAULT 0
                    )"""
                )
                db.execSQL("CREATE UNIQUE INDEX idx_echo_day ON echoes(note_id, kind, for_day)")
                db.execSQL(
                    """CREATE TABLE widgets(
                        widget_id INTEGER PRIMARY KEY,
                        note_id INTEGER
                    )"""
                )
                // FTS for search: title + body + checklist text + attachment names (kept in sync by Repo)
                db.execSQL(
                    """CREATE VIRTUAL TABLE notes_fts USING fts4(
                        note_id, title, body, extra, tokenize=unicode61
                    )"""
                )
            },
        )
    }
}
