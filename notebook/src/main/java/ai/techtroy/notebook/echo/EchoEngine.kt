package ai.techtroy.notebook.echo

import android.content.Context
import ai.techtroy.notebook.core.Fmt
import ai.techtroy.notebook.core.Prefs
import ai.techtroy.notebook.data.BodyType
import ai.techtroy.notebook.data.Echo
import ai.techtroy.notebook.data.Repo
import java.util.Calendar

/**
 * Echoes: the notebook remembering with you.
 * Once a day it looks for a note with an anniversary (1 month, 6 months, 1 year, 2 years...) or a
 * time capsule that opens today, and writes a single gold card for Home. Fully offline, uses only
 * data the app already keeps (created_at, history, links, attachments).
 */
class EchoEngine(private val ctx: Context, private val repo: Repo, private val prefs: Prefs) {

    fun refreshToday(force: Boolean = false) {
        val today = Fmt.epochDay()
        if (!force && prefs.lastEchoDay == today) return
        prefs.lastEchoDay = today
        val now = System.currentTimeMillis()

        // 1. Capsules opening today (highest priority)
        val dayStart = startOfDay(now); val dayEnd = dayStart + 86_400_000L
        repo.capsulesOpening(dayStart, dayEnd).forEach { n ->
            repo.addEcho(n.id, "capsule", "A time capsule you sealed on ${Fmt.date(n.createdAt)} opens today.", today)
        }
        repo.capsulesOpening(0, dayStart).forEach { n -> // opened while the app was closed for days
            repo.addEcho(n.id, "capsule", "A time capsule you sealed on ${Fmt.date(n.createdAt)} has opened.", today)
        }

        // 2. Anniversaries
        val cal = Calendar.getInstance()
        val candidates = listOf(1 to Calendar.MONTH, 6 to Calendar.MONTH, 1 to Calendar.YEAR, 2 to Calendar.YEAR, 3 to Calendar.YEAR, 5 to Calendar.YEAR)
        for ((amount, unit) in candidates) {
            cal.timeInMillis = now; cal.add(unit, -amount)
            val from = startOfDay(cal.timeInMillis); val to = from + 86_400_000L
            val notes = repo.notesCreatedBetween(from, to).filter { !it.locked && it.capsuleUntil == null }
            val pick = notes.maxByOrNull { score(it.id) } ?: continue
            repo.addEcho(pick.id, "anniversary", describe(pick.id, pick.displayTitle, pick.bodyType, amount, unit), today)
            break
        }
    }

    fun today(): Echo? {
        if (!prefs.echoDaily) return null
        return repo.echoesForDay(Fmt.epochDay()).firstOrNull()
    }

    private fun score(noteId: Long): Int {
        val h = repo.historyOf(noteId)
        return h.size + repo.links(noteId).size * 5 + repo.backlinks(noteId).size * 5 + repo.attachments(noteId).size * 3
    }

    private fun describe(noteId: Long, title: String, type: BodyType, amount: Int, unit: Int): String {
        val whenTxt = when {
            unit == Calendar.YEAR && amount == 1 -> "One year ago today"
            unit == Calendar.YEAR -> "$amount years ago today"
            amount == 1 -> "One month ago today"
            else -> "$amount months ago today"
        }
        val atts = repo.attachments(noteId)
        val hist = repo.historyOf(noteId)
        val hour = Calendar.getInstance().apply { timeInMillis = hist.firstOrNull()?.first ?: System.currentTimeMillis() }.get(Calendar.HOUR_OF_DAY)
        val timeOfDay = when (hour) { in 0..4 -> " in the small hours"; in 5..11 -> " in the morning"; in 12..17 -> " in the afternoon"; else -> " tonight" }
        val what = when {
            atts.any { it.kind.db == "audio" } -> "with a voice memo"
            type == BodyType.PAGES -> "by hand"
            type == BodyType.CHECKLIST -> "as a list"
            atts.any { it.kind.db == "image" } -> "with a photo"
            else -> ""
        }
        val name = if (title.isBlank()) "an untitled note" else "“${title.take(40)}”"
        val grew = repo.backlinks(noteId).size + repo.links(noteId).size
        val edits = hist.count { it.second == "text" || it.second == "ink" || it.second == "check" }
        val tail = when {
            grew >= 2 -> " $grew notes grew out of it."
            grew == 1 -> " One note grew out of it."
            edits >= 10 -> " You came back to it $edits times."
            else -> ""
        }
        return "$whenTxt$timeOfDay you started $name${if (what.isEmpty()) "" else " $what"}.$tail"
    }

    private fun startOfDay(t: Long): Long = Calendar.getInstance().apply {
        timeInMillis = t; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
