package ai.techtroy.notebook.ui

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.widget.ImageViewCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import ai.techtroy.notebook.App
import ai.techtroy.notebook.R
import ai.techtroy.notebook.core.ThemeManager
import ai.techtroy.notebook.core.circle
import ai.techtroy.notebook.core.dp
import ai.techtroy.notebook.core.gold
import ai.techtroy.notebook.core.hairline
import ai.techtroy.notebook.core.muted
import ai.techtroy.notebook.core.roundRect
import ai.techtroy.notebook.core.showKeyboard
import ai.techtroy.notebook.core.textPrimary
import ai.techtroy.notebook.core.tint
import ai.techtroy.notebook.data.Folder

class FoldersActivity : BaseActivity() {
    private lateinit var list: LinearLayout
    private val listener = { runOnUiThread { reload() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(TopBar.create(this, getString(R.string.folders), R.drawable.ic_add) { newFolderDialog(this) { } })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(6), dp(12), dp(24)) }
        root.addView(ScrollView(this).apply { addView(list) })
        setContentView(root)
    }

    override fun onResume() { super.onResume(); app.repo.addListener(listener); reload() }
    override fun onPause() { super.onPause(); app.repo.removeListener(listener) }

    private fun reload() {
        app.async({ app.repo.folders() to app.repo.unfiledCount() }) { (folders, unfiled) ->
            list.removeAllViews()
            if (folders.isEmpty()) list.addView(TextView(this).apply { text = getString(R.string.new_folder); setTextColor(muted()); gravity = Gravity.CENTER; setPadding(0, dp(60), 0, 0) })
            folders.forEach { f -> list.addView(row(f)) }
            list.addView(TextView(this).apply { text = getString(R.string.folder_unfiled) + "  ·  $unfiled"; setTextColor(muted()); textSize = 13f; setPadding(dp(16), dp(20), 0, 0) })
        }
    }

    private fun row(f: Folder): View {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(6), dp(6), dp(6))
            background = roundRect(ThemeManager.attr(this@FoldersActivity, R.attr.nbCard), 16f, this@FoldersActivity, hairline())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
            setOnClickListener { editDialog(f) }
        }
        r.addView(View(this).apply { background = circle(f.color) }, LinearLayout.LayoutParams(dp(14), dp(14)).apply { marginEnd = dp(16) })
        r.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@FoldersActivity).apply { text = f.name; textSize = 16f; setTextColor(textPrimary()) })
            addView(TextView(this@FoldersActivity).apply { text = "${f.noteCount} notes"; textSize = 12f; setTextColor(muted()) })
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        r.addView(ImageButton(this).apply { setImageResource(R.drawable.ic_more); background = with(android.util.TypedValue()) { theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, this, true); getDrawable(resourceId) }; ImageViewCompat.setImageTintList(this, tint(muted())); setOnClickListener { menu(f) } }, LinearLayout.LayoutParams(dp(44), dp(44)))
        return r
    }

    private fun menu(f: Folder) = Sheets.menu(this, f.name, listOf(
        Sheets.Item(R.drawable.ic_text, getString(R.string.rename_folder)) { editDialog(f) },
        Sheets.Item(R.drawable.ic_trash, getString(R.string.delete_folder), danger = true) { Sheets.confirm(this, getString(R.string.delete_folder), getString(R.string.folder_delete_hint), getString(R.string.delete), danger = true) { app.async({ app.repo.deleteFolder(f.id) }) { } } },
    ))

    private fun editDialog(f: Folder) = folderDialog(this, f) { }

    companion object {
        fun newFolderDialog(a: BaseActivity, onCreated: (Long) -> Unit) = folderDialog(a, null, onCreated)

        fun folderDialog(a: BaseActivity, existing: Folder?, onDone: (Long) -> Unit) {
            val app = a.application as App
            val ctx = a
            var color = existing?.color ?: ThemeManager.folderColors[0]
            val wrap = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(ctx.dp(22), ctx.dp(10), ctx.dp(22), 0) }
            val et = EditText(ctx).apply { hint = ctx.getString(R.string.folder_name); setText(existing?.name.orEmpty()); setSelection(text.length); setSingleLine(); setTextColor(ctx.textPrimary()); setHintTextColor(ctx.muted()) }
            wrap.addView(et)
            wrap.addView(TextView(ctx).apply { text = ctx.getString(R.string.folder_color); setTextAppearance(R.style.TextAppearance_Notebook_Caps); setPadding(0, ctx.dp(18), 0, ctx.dp(8)) })
            val swatches = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            fun render() {
                swatches.removeAllViews()
                ThemeManager.folderColors.forEach { c ->
                    swatches.addView(FrameLayout(ctx).apply {
                        background = circle(c, if (c == color) ctx.textPrimary() else 0, ctx.dp(2))
                        setOnClickListener { color = c; render() }
                    }, LinearLayout.LayoutParams(ctx.dp(30), ctx.dp(30)).apply { marginEnd = ctx.dp(8) })
                }
            }
            render(); wrap.addView(swatches)
            MaterialAlertDialogBuilder(ctx).setTitle(if (existing == null) R.string.new_folder else R.string.rename_folder).setView(wrap)
                .setPositiveButton(if (existing == null) R.string.create else R.string.save) { _, _ ->
                    val name = et.text.toString().trim(); if (name.isEmpty()) return@setPositiveButton
                    if (existing == null) app.async({ app.repo.createFolder(name, color) }) { onDone(it) } else app.async({ app.repo.renameFolder(existing.id, name, color) }) { onDone(existing.id) }
                }.setNegativeButton(R.string.cancel, null).show()
            et.showKeyboard()
        }
    }
}

/** Simple programmatic top bar used by secondary screens. */
object TopBar {
    fun create(a: BaseActivity, title: CharSequence, actionIcon: Int? = null, onAction: (() -> Unit)? = null): View {
        val bar = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(a.dp(4), a.dp(4), a.dp(4), a.dp(4)); minimumHeight = a.dp(52) }
        bar.addView(ImageButton(a).apply { setImageResource(R.drawable.ic_back); background = with(android.util.TypedValue()) { a.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, this, true); a.getDrawable(resourceId) }; ImageViewCompat.setImageTintList(this, tint(a.gold())); setOnClickListener { a.onBackPressedDispatcher.onBackPressed() } }, LinearLayout.LayoutParams(a.dp(44), a.dp(44)))
        bar.addView(TextView(a).apply { text = title; textSize = 19f; setTextColor(a.textPrimary()); typeface = android.graphics.Typeface.DEFAULT_BOLD; setPadding(a.dp(8), 0, a.dp(8), 0) }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (actionIcon != null) bar.addView(ImageButton(a).apply { setImageResource(actionIcon); background = with(android.util.TypedValue()) { a.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, this, true); a.getDrawable(resourceId) }; ImageViewCompat.setImageTintList(this, tint(a.gold())); setOnClickListener { onAction?.invoke() } }, LinearLayout.LayoutParams(a.dp(44), a.dp(44)))
        return bar
    }
}
