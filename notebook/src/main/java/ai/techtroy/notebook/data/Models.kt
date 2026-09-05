package ai.techtroy.notebook.data

/** Body kinds a note can have. A note is text OR checklist; sketches/pages/attachments hang off it. */
enum class BodyType(val db: String) {
    TEXT("text"), CHECKLIST("checklist"), PAGES("pages");

    companion object {
        fun from(s: String?): BodyType = entries.firstOrNull { it.db == s } ?: TEXT
    }
}

/** Note tints. Index is stored; palette is resolved per theme. */
enum class NoteColor(val id: Int) {
    NONE(0), GOLD(1), WINE(2), EMERALD(3), SAPPHIRE(4), PLUM(5), BRONZE(6), SLATE(7);

    companion object {
        fun from(id: Int): NoteColor = entries.firstOrNull { it.id == id } ?: NONE
    }
}

data class Folder(
    val id: Long,
    val name: String,
    val color: Int,
    val position: Int,
    val noteCount: Int = 0,
)

data class Note(
    val id: Long,
    val title: String,
    val body: String,
    val bodyType: BodyType,
    val color: NoteColor,
    val folderId: Long?,          // null = Unfiled
    val pinned: Boolean,
    val locked: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,         // non-null = in Trash
    val reminderAt: Long?,
    val reminderDone: Boolean,
    val capsuleUntil: Long?,      // non-null = sealed time capsule until this epoch ms
    // denormalized for cards (filled by queries)
    val checklistPreview: List<ChecklistItem> = emptyList(),
    val checklistTotal: Int = 0,
    val checklistDone: Int = 0,
    val attachmentCount: Int = 0,
    val linkCount: Int = 0,
    val firstImage: String? = null,   // relative path of first image/sketch thumb
    val hasAudio: Boolean = false,
    val pageCount: Int = 0,
) {
    val inTrash get() = deletedAt != null
    val isCapsule get() = capsuleUntil != null && capsuleUntil > System.currentTimeMillis()
    val displayTitle: String
        get() = title.ifBlank {
            body.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.take(80)
                ?: checklistPreview.firstOrNull()?.text?.take(80)
                ?: ""
        }
}

data class ChecklistItem(
    val id: Long,
    val noteId: Long,
    val text: String,
    val checked: Boolean,
    val position: Int,
)

enum class AttachmentKind(val db: String) {
    IMAGE("image"), VIDEO("video"), AUDIO("audio"), PDF("pdf"), FILE("file"), SKETCH("sketch");

    companion object {
        fun from(s: String?): AttachmentKind = entries.firstOrNull { it.db == s } ?: FILE
        fun fromMime(mime: String?, name: String?): AttachmentKind {
            val m = mime.orEmpty()
            val n = name.orEmpty().lowercase()
            return when {
                m.startsWith("image/") -> IMAGE
                m.startsWith("video/") -> VIDEO
                m.startsWith("audio/") -> AUDIO
                m == "application/pdf" || n.endsWith(".pdf") -> PDF
                else -> FILE
            }
        }
    }
}

data class Attachment(
    val id: Long,
    val noteId: Long,
    val kind: AttachmentKind,
    val name: String,
    val path: String,             // relative to app files dir ("attachments/<uuid>.<ext>")
    val mime: String,
    val size: Long,
    val durationMs: Long,         // audio/video
    val width: Int,
    val height: Int,
    val thumbPath: String?,       // relative; images/videos/pdf/sketch
    val createdAt: Long,
    val position: Int,
    /** For sketches: JSON strokes document. For audio-synced pages recordings: stroke timestamps live in pages. */
    val data: String?,
    val annotations: String?,     // PDF ink layer JSON
)

data class NoteLink(val fromId: Long, val toId: Long, val createdAt: Long)

/** One drawing page inside a PAGES note. */
data class Page(
    val id: Long,
    val noteId: Long,
    val position: Int,
    val paper: String,            // blank|lined|grid|dotted
    val dark: Boolean,
    val strokes: String,          // JSON strokes document (StrokeDoc)
    val objects: String,          // JSON text boxes + images
    val thumbPath: String?,
    val updatedAt: Long,
)

data class Echo(
    val id: Long,
    val noteId: Long,
    val kind: String,             // anniversary|capsule|milestone
    val text: String,
    val forDay: Long,             // epoch day
    val dismissed: Boolean,
)

enum class SortMode(val id: Int) {
    EDITED(0), CREATED(1), TITLE(2), COLOR(3);

    companion object { fun from(id: Int) = entries.firstOrNull { it.id == id } ?: EDITED }
}
