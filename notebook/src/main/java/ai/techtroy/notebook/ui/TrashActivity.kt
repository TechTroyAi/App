package ai.techtroy.notebook.ui

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import ai.techtroy.notebook.R
import ai.techtroy.notebook.core.dp
import ai.techtroy.notebook.core.muted
import ai.techtroy.notebook.core.show
import ai.techtroy.notebook.core.snack
import ai.techtroy.notebook.data.Note

class TrashActivity : BaseActivity() {
    private lateinit var adapter: NoteAdapter
    private lateinit var empty: TextView
    private lateinit var root: View
    private val listener = { runOnUiThread { reload() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TopBar.create(this, getString(R.string.nav_trash), R.drawable.ic_trash) { emptyTrash() })
        col.addView(TextView(this).apply { text = getString(R.string.trash_hint); setTextColor(muted()); textSize = 12f; setPadding(dp(20), 0, dp(20), dp(6)) })
        val rv = RecyclerView(this).apply { layoutManager = StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL); setPadding(dp(10), 0, dp(10), dp(24)); clipToPadding = false }
        adapter = NoteAdapter(isLightTheme, ::menu, ::menu, {}, {})
        rv.adapter = adapter
        empty = TextView(this).apply { text = getString(R.string.empty_notes_title); setTextColor(muted()); gravity = Gravity.CENTER; setPadding(0, dp(80), 0, 0) }
        col.addView(empty); col.addView(rv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root = col; setContentView(col)
    }

    override fun onResume() { super.onResume(); app.repo.addListener(listener); reload() }
    override fun onPause() { super.onPause(); app.repo.removeListener(listener) }

    private fun reload() { app.async({ app.repo.notes(trash = true) }) { notes -> adapter.submitList(notes.map { Row.NoteRow(it) }); empty.show(notes.isEmpty()) } }

    private fun menu(n: Note) = Sheets.menu(this, n.displayTitle.ifBlank { "Untitled" }, listOf(
        Sheets.Item(R.drawable.ic_restore, getString(R.string.restore)) { app.async({ app.repo.restore(listOf(n.id)) }) { root.snack(getString(R.string.note_restored)) } },
        Sheets.Item(R.drawable.ic_trash, getString(R.string.delete_forever), danger = true) { Sheets.confirm(this, getString(R.string.delete_forever), n.displayTitle, getString(R.string.delete), danger = true) { app.async({ app.repo.deleteForever(listOf(n.id)) { app.files.delete(it) } }) { root.snack(getString(R.string.note_deleted_forever)) } } },
    ))

    private fun emptyTrash() = Sheets.confirm(this, getString(R.string.empty_trash), getString(R.string.trash_hint), getString(R.string.delete), danger = true) { app.async({ app.repo.emptyTrash { app.files.delete(it) } }) { root.snack(getString(R.string.trash_emptied)) } }
}
