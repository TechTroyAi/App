package ai.techtroy.notebook.widget

import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import android.widget.ScrollView
import android.widget.TextView
import ai.techtroy.notebook.App
import ai.techtroy.notebook.R
import ai.techtroy.notebook.core.ThemeManager
import ai.techtroy.notebook.core.dp
import ai.techtroy.notebook.core.hairline
import ai.techtroy.notebook.core.muted
import ai.techtroy.notebook.core.roundRect
import ai.techtroy.notebook.core.textPrimary
import ai.techtroy.notebook.data.BodyType
import ai.techtroy.notebook.data.Note
import ai.techtroy.notebook.data.Repo
import ai.techtroy.notebook.ui.BaseActivity
import ai.techtroy.notebook.ui.EditorActivity
import ai.techtroy.notebook.ui.HomeActivity
import ai.techtroy.notebook.ui.TopBar

private fun openNotePi(ctx: Context, noteId: Long, req: Int): PendingIntent =
    PendingIntent.getActivity(ctx, req, EditorActivity.intent(ctx, noteId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

private fun previewText(n: Note, repo: Repo): CharSequence = when {
    n.locked -> "🔒 Locked note"
    n.isCapsule -> "✦ Time capsule"
    n.bodyType == BodyType.CHECKLIST -> repo.checklist(n.id).take(6).joinToString("\n") { (if (it.checked) "☑ " else "☐ ") + it.text }
    n.bodyType == BodyType.PAGES -> "✎ ${n.pageCount} handwritten pages"
    else -> Repo.stripLinkTokens(if (n.title.isBlank()) n.body.lineSequence().drop(1).joinToString("\n") else n.body).trim()
}

/** 2×2 / 4×2 sticky note. Configure activity picks the note. */
class StickyWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, awm: AppWidgetManager, ids: IntArray) { ids.forEach { update(ctx, awm, it) } }
    override fun onDeleted(ctx: Context, ids: IntArray) { val app = ctx.applicationContext as App; app.io.execute { ids.forEach { app.repo.removeWidget(it) } } }

    companion object {
        fun update(ctx: Context, awm: AppWidgetManager, id: Int) {
            val app = ctx.applicationContext as App
            app.io.execute {
                val noteId = app.repo.widgetNote(id)
                val note = noteId?.let { app.repo.note(it) }?.takeIf { !it.inTrash }
                val rv = RemoteViews(ctx.packageName, R.layout.widget_sticky)
                if (note == null) {
                    rv.setTextViewText(R.id.wTitle, ctx.getString(R.string.widget_sticky_label)); rv.setTextViewText(R.id.wBody, ctx.getString(R.string.widget_pick_note))
                    rv.setOnClickPendingIntent(R.id.wRoot, PendingIntent.getActivity(ctx, id, Intent(ctx, StickyConfigActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                } else {
                    val t = if (note.locked) ctx.getString(R.string.locked_note) else note.displayTitle
                    rv.setTextViewText(R.id.wTitle, t); rv.setViewVisibility(R.id.wTitle, if (t.isBlank()) View.GONE else View.VISIBLE)
                    rv.setTextViewText(R.id.wBody, previewText(note, app.repo))
                    rv.setOnClickPendingIntent(R.id.wRoot, openNotePi(ctx, note.id, id))
                }
                awm.updateAppWidget(id, rv)
            }
        }
        fun updateAll(ctx: Context, awm: AppWidgetManager) { awm.getAppWidgetIds(ComponentName(ctx, StickyWidget::class.java)).forEach { update(ctx, awm, it) } }
    }
}

class StickyConfigActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        setResult(Activity.RESULT_CANCELED)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TopBar.create(this, getString(R.string.widget_pick_note)))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(12), dp(30)) }
        col.addView(ScrollView(this).apply { addView(list) })
        setContentView(col)
        app.async({ app.repo.notes().filter { !it.locked } }) { notes ->
            notes.forEach { n ->
                list.addView(TextView(this).apply {
                    text = n.displayTitle.ifBlank { "Untitled" }; textSize = 15f; setTextColor(textPrimary()); setPadding(dp(16), dp(14), dp(16), dp(14))
                    background = roundRect(ThemeManager.attr(this@StickyConfigActivity, R.attr.nbCard), 14f, this@StickyConfigActivity, hairline())
                    setOnClickListener {
                        app.async({ app.repo.setWidgetNote(widgetId, n.id) }) {
                            StickyWidget.update(this@StickyConfigActivity, AppWidgetManager.getInstance(this@StickyConfigActivity), widgetId)
                            setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)); finish()
                        }
                    }
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            }
            if (notes.isEmpty()) list.addView(TextView(this).apply { text = getString(R.string.empty_notes_title); setTextColor(muted()); gravity = Gravity.CENTER; setPadding(0, dp(60), 0, 0) })
        }
    }
}

/** Scrollable list of notes. */
class ListWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, awm: AppWidgetManager, ids: IntArray) {
        ids.forEach { id ->
            val rv = RemoteViews(ctx.packageName, R.layout.widget_list)
            rv.setRemoteAdapter(R.id.wList, Intent(ctx, ListWidgetService::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id).apply { data = android.net.Uri.parse(toUri(Intent.URI_INTENT_SCHEME)) })
            rv.setEmptyView(R.id.wList, R.id.wEmpty)
            rv.setOnClickPendingIntent(R.id.wHeader, PendingIntent.getActivity(ctx, 0, Intent(ctx, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            rv.setOnClickPendingIntent(R.id.wAdd, PendingIntent.getActivity(ctx, 1, HomeActivity.newNoteIntent(ctx, BodyType.TEXT), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            val template = PendingIntent.getActivity(ctx, 2, Intent(ctx, EditorActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            rv.setPendingIntentTemplate(R.id.wList, template)
            awm.updateAppWidget(id, rv)
        }
        awm.notifyAppWidgetViewDataChanged(ids, R.id.wList)
    }
    companion object {
        fun notifyData(ctx: Context, awm: AppWidgetManager) { val ids = awm.getAppWidgetIds(ComponentName(ctx, ListWidget::class.java)); if (ids.isNotEmpty()) awm.notifyAppWidgetViewDataChanged(ids, R.id.wList) }
    }
}

class ListWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = Factory(applicationContext)
    class Factory(private val ctx: Context) : RemoteViewsFactory {
        private var notes: List<Note> = emptyList()
        override fun onCreate() {}
        override fun onDataSetChanged() { notes = (ctx.applicationContext as App).repo.notes().take(40) }
        override fun onDestroy() {}
        override fun getCount() = notes.size
        override fun getViewAt(i: Int): RemoteViews {
            val n = notes[i]
            val rv = RemoteViews(ctx.packageName, R.layout.widget_list_row)
            val app = ctx.applicationContext as App
            rv.setTextViewText(R.id.rTitle, (if (n.pinned) "▮ " else "") + (if (n.locked) ctx.getString(R.string.locked_note) else n.displayTitle.ifBlank { "Untitled" }))
            val sub = if (n.locked || n.isCapsule) "" else previewText(n, app.repo).toString().replace('\n', ' ').take(80)
            rv.setTextViewText(R.id.rBody, sub); rv.setViewVisibility(R.id.rBody, if (sub.isBlank()) View.GONE else View.VISIBLE)
            rv.setOnClickFillInIntent(R.id.rRoot, Intent().putExtra(EditorActivity.EXTRA_ID, n.id))
            return rv
        }
        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount() = 1
        override fun getItemId(i: Int) = notes[i].id
        override fun hasStableIds() = true
    }
}

/** 1×1 New note; long-press area → new voice note. */
class NewNoteWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, awm: AppWidgetManager, ids: IntArray) {
        ids.forEach { id ->
            val rv = RemoteViews(ctx.packageName, R.layout.widget_new)
            rv.setOnClickPendingIntent(R.id.wNew, PendingIntent.getActivity(ctx, 10, HomeActivity.newNoteIntent(ctx, BodyType.TEXT), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            rv.setOnClickPendingIntent(R.id.wNewVoice, PendingIntent.getActivity(ctx, 11, HomeActivity.newNoteIntent(ctx, BodyType.TEXT, voice = true), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            awm.updateAppWidget(id, rv)
        }
    }
}
