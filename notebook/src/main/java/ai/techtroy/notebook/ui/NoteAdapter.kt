package ai.techtroy.notebook.ui

import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import ai.techtroy.notebook.App
import ai.techtroy.notebook.R
import ai.techtroy.notebook.core.Fmt
import ai.techtroy.notebook.core.ThemeManager
import ai.techtroy.notebook.core.dp
import ai.techtroy.notebook.core.gold
import ai.techtroy.notebook.core.hairline
import ai.techtroy.notebook.core.muted
import ai.techtroy.notebook.core.show
import ai.techtroy.notebook.data.BodyType
import ai.techtroy.notebook.data.Echo
import ai.techtroy.notebook.data.Note
import ai.techtroy.notebook.data.Repo

sealed class Row {
    data class Section(val title: String) : Row()
    data class NoteRow(val note: Note) : Row()
    data class EchoRow(val echo: Echo) : Row()
}

class NoteAdapter(
    private val light: Boolean,
    private val onClick: (Note) -> Unit,
    private val onLongClick: (Note) -> Unit,
    private val onEchoOpen: (Echo) -> Unit,
    private val onEchoDismiss: (Echo) -> Unit,
) : ListAdapter<Row, RecyclerView.ViewHolder>(DIFF) {

    var selected: Set<Long> = emptySet()
        set(v) { val old = field; field = v; currentList.forEachIndexed { i, r -> if (r is Row.NoteRow && ((r.note.id in old) != (r.note.id in v))) notifyItemChanged(i) } }
    var selecting = false
    var grid = true

    override fun getItemViewType(position: Int) = when (getItem(position)) { is Row.Section -> 0; is Row.NoteRow -> 1; is Row.EchoRow -> 2 }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return when (viewType) {
            0 -> SectionVH(inf.inflate(R.layout.item_section, parent, false))
            2 -> EchoVH(inf.inflate(R.layout.item_echo, parent, false))
            else -> NoteVH(inf.inflate(R.layout.item_note, parent, false))
        }
    }

    override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is Row.Section -> (h as SectionVH).title.text = row.title
            is Row.EchoRow -> (h as EchoVH).bind(row.echo)
            is Row.NoteRow -> (h as NoteVH).bind(row.note)
        }
    }

    fun spanFor(position: Int): Int = if (position in 0 until itemCount && getItem(position) is Row.NoteRow) 1 else 2

    class SectionVH(v: View) : RecyclerView.ViewHolder(v) { val title: TextView = v.findViewById(R.id.sectionTitle) }

    inner class EchoVH(v: View) : RecyclerView.ViewHolder(v) {
        private val text: TextView = v.findViewById(R.id.echoText)
        private val dismiss: View = v.findViewById(R.id.echoDismiss)
        fun bind(e: Echo) {
            text.text = e.text
            itemView.setOnClickListener { onEchoOpen(e) }
            dismiss.setOnClickListener { onEchoDismiss(e) }
        }
    }

    inner class NoteVH(v: View) : RecyclerView.ViewHolder(v) {
        private val card = v as MaterialCardView
        private val image: ImageView = v.findViewById(R.id.cardImage)
        private val title: TextView = v.findViewById(R.id.cardTitle)
        private val body: TextView = v.findViewById(R.id.cardBody)
        private val checks: LinearLayout = v.findViewById(R.id.cardChecks)
        private val capsule: View = v.findViewById(R.id.cardCapsule)
        private val capsuleText: TextView = v.findViewById(R.id.cardCapsuleText)
        private val time: TextView = v.findViewById(R.id.cardTime)
        private val ribbon: View = v.findViewById(R.id.ribbon)
        private val check: View = v.findViewById(R.id.checkMark)
        private val bLock: View = v.findViewById(R.id.badgeLock)
        private val bBell: View = v.findViewById(R.id.badgeBell)
        private val bAudio: View = v.findViewById(R.id.badgeAudio)
        private val bLinks: TextView = v.findViewById(R.id.badgeLinks)
        private val bAtt: TextView = v.findViewById(R.id.badgeAtt)
        private val bPages: TextView = v.findViewById(R.id.badgePages)

        fun bind(n: Note) {
            val ctx = itemView.context
            card.setCardBackgroundColor(ThemeManager.noteTint(ctx, n.color, light))
            val sel = n.id in selected
            card.strokeColor = if (sel) ctx.gold() else ctx.hairline()
            card.strokeWidth = ctx.dp(if (sel) 2 else 1)
            check.show(sel)
            ribbon.show(n.pinned && !sel)

            val hidden = n.locked || n.isCapsule
            val t = if (n.locked) ctx.getString(R.string.locked_note) else n.displayTitle
            title.text = t; title.show(t.isNotBlank())

            // body preview
            checks.removeAllViews(); checks.show(false); body.show(false); capsule.show(false)
            if (n.isCapsule) {
                capsule.show(true); capsuleText.text = ctx.getString(R.string.capsule_sealed_until, Fmt.date(n.capsuleUntil!!))
                title.text = if (n.title.isBlank()) "Time capsule" else n.title
            } else if (n.locked) {
                body.show(true); body.text = "• • • • •"
            } else when (n.bodyType) {
                BodyType.CHECKLIST -> if (n.checklistTotal > 0) {
                    checks.show(true)
                    n.checklistPreview.forEach { item -> checks.addView(checkRow(item.text, item.checked)) }
                    val more = n.checklistTotal - n.checklistPreview.size
                    if (more > 0) checks.addView(TextView(ctx).apply { text = "+ $more more"; setTextColor(ctx.muted()); textSize = 12f; setPadding(ctx.dp(22), ctx.dp(2), 0, 0) })
                }
                BodyType.PAGES -> { }
                BodyType.TEXT -> {
                    val preview = Repo.stripLinkTokens(if (n.title.isBlank()) n.body.lineSequence().drop(1).joinToString("\n") else n.body).trim()
                    if (preview.isNotBlank()) { body.show(true); body.text = preview.take(400) }
                }
            }
            if (title.visibility != View.VISIBLE && body.visibility != View.VISIBLE && checks.visibility != View.VISIBLE && capsule.visibility != View.VISIBLE) {
                body.show(true); body.text = if (n.bodyType == BodyType.PAGES) ctx.getString(R.string.pages_count, n.pageCount) else if (n.attachmentCount > 0) ctx.getString(R.string.attach) else " "
            }

            // header image
            val img = if (hidden) null else n.firstImage
            if (img != null) {
                image.show(true)
                val app = ctx.applicationContext as App
                image.setImageDrawable(null); image.tag = img
                app.async({ app.files.loadBitmap(img, 640) }) { bmp -> if (image.tag == img && bmp != null) image.setImageBitmap(bmp) }
            } else { image.show(false); image.setImageDrawable(null); image.tag = null }

            time.text = Fmt.relative(ctx, n.updatedAt)
            bLock.show(n.locked); bBell.show(n.reminderAt != null && !n.reminderDone); bAudio.show(n.hasAudio && !hidden)
            bLinks.show(n.linkCount > 0); bLinks.text = "${n.linkCount}"
            bAtt.show(n.attachmentCount > 0 && !hidden); bAtt.text = "${n.attachmentCount}"
            bPages.show(n.bodyType == BodyType.PAGES && n.pageCount > 0); bPages.text = "${n.pageCount}"

            itemView.setOnClickListener { onClick(n) }
            itemView.setOnLongClickListener { onLongClick(n); true }
        }

        private fun checkRow(text: String, checked: Boolean): View {
            val ctx = itemView.context
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(0, ctx.dp(2), 0, ctx.dp(2)) }
            val dot = View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(ctx.dp(14), ctx.dp(14)).apply { marginEnd = ctx.dp(8) }
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; if (checked) setColor(ctx.gold()) else { setColor(0); setStroke(ctx.dp(1.5f).toInt(), ctx.gold()) } }
            }
            val tv = TextView(ctx).apply {
                this.text = text; textSize = 13f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(if (checked) ctx.muted() else ThemeManager.attr(ctx, android.R.attr.textColorPrimary))
                if (checked) paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            }
            row.addView(dot); row.addView(tv); return row
        }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<Row>() {
            override fun areItemsTheSame(a: Row, b: Row): Boolean = when {
                a is Row.NoteRow && b is Row.NoteRow -> a.note.id == b.note.id
                a is Row.Section && b is Row.Section -> a.title == b.title
                a is Row.EchoRow && b is Row.EchoRow -> a.echo.id == b.echo.id
                else -> false
            }
            override fun areContentsTheSame(a: Row, b: Row): Boolean = a == b
        }
    }
}
