package ai.techtroy.notebook.ui

import android.app.Activity
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.widget.ImageViewCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
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
import ai.techtroy.notebook.data.Note
import ai.techtroy.notebook.data.NoteColor

/** Programmatic bottom sheets shared by Home + Editor. All gold-on-black, 26dp top radius (from theme). */
object Sheets {

    class Item(val icon: Int, val label: CharSequence, val danger: Boolean = false, val trailing: CharSequence? = null, val onClick: () -> Unit)

    fun sheet(activity: Activity, title: CharSequence? = null, build: (LinearLayout, BottomSheetDialog) -> Unit): BottomSheetDialog {
        val d = BottomSheetDialog(activity)
        val ctx = d.context
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(0, ctx.dp(8), 0, ctx.dp(18)) }
        root.addView(View(ctx).apply { background = roundRect(ctx.muted(), 2f, ctx); alpha = 0.6f; layoutParams = LinearLayout.LayoutParams(ctx.dp(36), ctx.dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = ctx.dp(10) } })
        if (title != null) root.addView(TextView(ctx).apply { text = title; setTextAppearance(R.style.TextAppearance_Notebook_Caps); setPadding(ctx.dp(20), ctx.dp(6), ctx.dp(20), ctx.dp(8)) })
        build(root, d)
        val scroll = ScrollView(ctx).apply { addView(root); isVerticalScrollBarEnabled = false }
        d.setContentView(scroll)
        d.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        d.behavior.skipCollapsed = true
        d.show()
        return d
    }

    fun menu(activity: Activity, title: CharSequence?, items: List<Item>): BottomSheetDialog = sheet(activity, title) { root, d ->
        items.forEach { it -> root.addView(row(root.context, it) { d.dismiss(); it.onClick() }) }
    }

    fun row(ctx: android.content.Context, item: Item, onClick: () -> Unit): View {
        val tv = TextView(ctx).apply {
            text = item.label; textSize = 15f; setTextColor(if (item.danger) ctx.getColor(R.color.danger) else ctx.textPrimary())
            gravity = Gravity.CENTER_VERTICAL; compoundDrawablePadding = ctx.dp(18)
            setPadding(ctx.dp(20), 0, ctx.dp(20), 0); minHeight = ctx.dp(52)
            val icon = ctx.getDrawable(item.icon)?.mutate()?.apply { setTint(if (item.danger) ctx.getColor(R.color.danger) else ctx.gold()); setBounds(0, 0, ctx.dp(22), ctx.dp(22)) }
            setCompoundDrawables(icon, null, null, null)
            background = with(android.util.TypedValue()) { ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, this, true); ctx.getDrawable(resourceId) }
            setOnClickListener { onClick() }
        }
        if (item.trailing == null) return tv
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(ctx).apply { text = item.trailing; setTextColor(ctx.muted()); textSize = 12f; setPadding(0, 0, ctx.dp(20), 0) })
            setOnClickListener { onClick() }
        }
    }

    fun colorPicker(activity: Activity, current: NoteColor?, onPick: (NoteColor) -> Unit) = sheet(activity, activity.getString(R.string.pick_color)) { root, d ->
        val ctx = root.context
        val light = ThemeManager.isLight((activity.application as App).prefs.theme)
        val grid = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(ctx.dp(12), ctx.dp(10), ctx.dp(12), ctx.dp(10)) }
        NoteColor.entries.forEach { c ->
            val size = ctx.dp(38)
            val fill = if (c == NoteColor.NONE) ThemeManager.attr(ctx, R.attr.nbCardRaised) else ThemeManager.noteTint(ctx, c, light)
            val ring = if (c == current) ctx.gold() else ctx.hairline()
            val v = FrameLayout(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(size, size).apply { marginStart = ctx.dp(4); marginEnd = ctx.dp(4) }
                background = circle(fill, ring, ctx.dp(if (c == current) 2 else 1))
                if (c != NoteColor.NONE) addView(View(ctx).apply { background = circle(ThemeManager.swatch(c)) }, FrameLayout.LayoutParams(ctx.dp(12), ctx.dp(12), Gravity.CENTER))
                if (c == current) addView(ImageView(ctx).apply { setImageResource(R.drawable.ic_check); ImageViewCompat.setImageTintList(this, tint(ctx.gold())) }, FrameLayout.LayoutParams(ctx.dp(18), ctx.dp(18), Gravity.CENTER))
                setOnClickListener { d.dismiss(); onPick(c) }
            }
            grid.addView(v)
        }
        root.addView(grid)
    }

    fun folderPicker(activity: Activity, folders: List<Folder>, current: Long?, onPick: (Long?) -> Unit) = sheet(activity, activity.getString(R.string.pick_folder)) { root, d ->
        val ctx = root.context
        fun add(label: String, color: Int?, id: Long?, selected: Boolean) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = ctx.dp(50); setPadding(ctx.dp(20), 0, ctx.dp(20), 0)
                background = with(android.util.TypedValue()) { ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, this, true); ctx.getDrawable(resourceId) }
                addView(View(ctx).apply { background = if (color != null) circle(color) else circle(0, ctx.gold(), ctx.dp(1)) }, LinearLayout.LayoutParams(ctx.dp(14), ctx.dp(14)).apply { marginEnd = ctx.dp(18) })
                addView(TextView(ctx).apply { text = label; textSize = 15f; setTextColor(ctx.textPrimary()); if (selected) setTypeface(null, Typeface.BOLD) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (selected) addView(ImageView(ctx).apply { setImageResource(R.drawable.ic_check); ImageViewCompat.setImageTintList(this, tint(ctx.gold())) }, LinearLayout.LayoutParams(ctx.dp(20), ctx.dp(20)))
                setOnClickListener { d.dismiss(); onPick(id) }
            }
            root.addView(row)
        }
        add(ctx.getString(R.string.folder_unfiled), null, null, current == null)
        folders.forEach { add(it.name, it.color, it.id, current == it.id) }
        root.addView(row(ctx, Item(R.drawable.ic_add, ctx.getString(R.string.new_folder)) {}) { d.dismiss(); FoldersActivity.newFolderDialog(activity as BaseActivity) { id -> onPick(id) } })
    }

    /** Search-as-you-type note picker used for [[links]] and the 🔗 button. */
    fun notePicker(activity: Activity, excludeId: Long, initialQuery: String = "", onPick: (Note) -> Unit) = sheet(activity, activity.getString(R.string.pick_note)) { root, d ->
        val ctx = root.context
        val app = activity.application as App
        val input = EditText(ctx).apply {
            hint = ctx.getString(R.string.search_hint); setText(initialQuery); setSelection(text.length)
            background = roundRect(ThemeManager.attr(ctx, R.attr.nbCard), 14f, ctx, ctx.hairline()); setPadding(ctx.dp(16), ctx.dp(12), ctx.dp(16), ctx.dp(12))
            setTextColor(ctx.textPrimary()); setHintTextColor(ctx.muted()); textSize = 15f; maxLines = 1; inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        root.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(ctx.dp(16), ctx.dp(4), ctx.dp(16), ctx.dp(8)) })
        val results = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; minimumHeight = ctx.dp(240) }
        root.addView(results)
        fun refresh(q: String) {
            app.async({ app.repo.searchTitles(q, excludeId) }) { notes ->
                results.removeAllViews()
                if (notes.isEmpty()) results.addView(TextView(ctx).apply { text = ctx.getString(R.string.empty_notes_title); setTextColor(ctx.muted()); setPadding(ctx.dp(20), ctx.dp(20), ctx.dp(20), ctx.dp(20)) })
                notes.forEach { n ->
                    val title = n.displayTitle.ifBlank { "Untitled" }
                    val sub = ai.techtroy.notebook.data.Repo.stripLinkTokens(n.body).lineSequence().map { it.trim() }.filter { it.isNotEmpty() && it != title }.firstOrNull()?.take(60)
                    val row = LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL; setPadding(ctx.dp(20), ctx.dp(10), ctx.dp(20), ctx.dp(10))
                        background = with(android.util.TypedValue()) { ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, this, true); ctx.getDrawable(resourceId) }
                        addView(TextView(ctx).apply { text = title; textSize = 15f; setTextColor(ctx.textPrimary()); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                        if (sub != null) addView(TextView(ctx).apply { text = sub; textSize = 12f; setTextColor(ctx.muted()); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                        setOnClickListener { d.dismiss(); onPick(n) }
                    }
                    results.addView(row)
                }
            }
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { refresh(s?.toString().orEmpty()) }
        })
        refresh(initialQuery)
        input.showKeyboard()
    }

    fun confirm(activity: Activity, title: CharSequence, body: CharSequence?, positive: CharSequence, danger: Boolean = false, onOk: () -> Unit) {
        val b = com.google.android.material.dialog.MaterialAlertDialogBuilder(activity).setTitle(title).setPositiveButton(positive) { _, _ -> onOk() }.setNegativeButton(R.string.cancel, null)
        if (body != null) b.setMessage(body)
        val dlg = b.show()
        if (danger) dlg.getButton(android.content.DialogInterface.BUTTON_POSITIVE)?.setTextColor(activity.getColor(R.color.danger))
    }

    fun input(activity: Activity, title: CharSequence, initial: String, hint: CharSequence, onOk: (String) -> Unit) {
        val ctx = activity
        val et = EditText(ctx).apply { setText(initial); setSelection(text.length); this.hint = hint; setSingleLine(); setTextColor(ctx.textPrimary()); setHintTextColor(ctx.muted()) }
        val wrap = FrameLayout(ctx).apply { setPadding(ctx.dp(22), ctx.dp(8), ctx.dp(22), 0); addView(et) }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx).setTitle(title).setView(wrap)
            .setPositiveButton(R.string.save) { _, _ -> onOk(et.text.toString()) }.setNegativeButton(R.string.cancel, null).show()
        et.showKeyboard()
    }
}
