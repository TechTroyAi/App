package ai.techtroy.notebook.ui

import android.annotation.SuppressLint
import android.graphics.Paint
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import ai.techtroy.notebook.R
import ai.techtroy.notebook.core.circle
import ai.techtroy.notebook.core.dp
import ai.techtroy.notebook.core.gold
import ai.techtroy.notebook.core.muted
import ai.techtroy.notebook.core.show
import ai.techtroy.notebook.core.showKeyboard
import ai.techtroy.notebook.core.textPrimary
import ai.techtroy.notebook.data.ChecklistItem

/**
 * Editable checklist rows. Two instances per note: open items (draggable) and completed items.
 * Text edits are debounced by the editor; this adapter reports every change immediately.
 */
class ChecklistAdapter(
    private val completedList: Boolean,
    private val onText: (ChecklistItem, String) -> Unit,
    private val onToggle: (ChecklistItem) -> Unit,
    private val onRemove: (ChecklistItem) -> Unit,
    private val onEnter: (ChecklistItem, String, String) -> Unit,   // split: text before cursor stays, after goes to new item
    private val onReorder: (List<Long>) -> Unit,
    private val onBackspaceEmpty: (ChecklistItem) -> Unit,
) : RecyclerView.Adapter<ChecklistAdapter.VH>() {

    val items = ArrayList<ChecklistItem>()
    var focusRequestId: Long? = null
    var fontScale = 1f
    private var touchHelper: ItemTouchHelper? = null

    fun submit(list: List<ChecklistItem>) {
        items.clear(); items.addAll(list); notifyDataSetChanged()
    }

    fun attachTo(rv: RecyclerView) {
        rv.adapter = this
        if (completedList) return
        touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val from = vh.bindingAdapterPosition; val to = target.bindingAdapterPosition
                if (from < 0 || to < 0) return false
                val it = items.removeAt(from); items.add(to, it); notifyItemMoved(from, to); return true
            }
            override fun onSwiped(vh: RecyclerView.ViewHolder, dir: Int) { val p = vh.bindingAdapterPosition; if (p >= 0) onRemove(items[p]) }
            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) { super.clearView(rv, vh); onReorder(items.map { it.id }) }
            override fun isLongPressDragEnabled() = false
            override fun getSwipeEscapeVelocity(defaultValue: Float) = defaultValue * 3
            override fun getSwipeThreshold(vh: RecyclerView.ViewHolder) = 0.6f
        }).also { it.attachToRecyclerView(rv) }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(LayoutInflater.from(parent.context).inflate(R.layout.item_check, parent, false))
    override fun getItemCount() = items.size
    override fun getItemId(position: Int) = items[position].id
    init { setHasStableIds(true) }

    override fun onBindViewHolder(h: VH, position: Int) = h.bind(items[position])

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        private val drag: ImageView = v.findViewById(R.id.dragHandle)
        private val box: View = v.findViewById(R.id.checkBox)
        private val ring: View = v.findViewById(R.id.checkRing)
        private val tick: View = v.findViewById(R.id.checkTick)
        private val text: EditText = v.findViewById(R.id.itemText)
        private val remove: View = v.findViewById(R.id.itemRemove)
        private var watcher: TextWatcher? = null
        private var bound: ChecklistItem? = null

        @SuppressLint("ClickableViewAccessibility")
        fun bind(item: ChecklistItem) {
            bound = item
            val ctx = itemView.context
            watcher?.let { text.removeTextChangedListener(it) }
            if (text.text.toString() != item.text) text.setText(item.text)
            text.textSize = 16f * fontScale
            val checked = item.checked
            ring.background = if (checked) circle(ctx.gold()) else circle(0, ctx.gold(), ctx.dp(2))
            tick.show(checked)
            text.setTextColor(if (checked) ctx.muted() else ctx.textPrimary())
            text.paintFlags = if (checked) text.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG else text.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            drag.show(!completedList)
            drag.setOnTouchListener { _, ev -> if (ev.actionMasked == MotionEvent.ACTION_DOWN) touchHelper?.startDrag(this); false }
            box.setOnClickListener { bound?.let(onToggle) }
            remove.setOnClickListener { bound?.let(onRemove) }
            text.setOnFocusChangeListener { _, f -> remove.visibility = if (f) View.VISIBLE else View.INVISIBLE }
            watcher = object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val cur = bound ?: return
                    val str = s?.toString().orEmpty()
                    if (str.contains('\n')) {
                        val idx = str.indexOf('\n')
                        val before = str.substring(0, idx); val after = str.substring(idx + 1)
                        text.removeTextChangedListener(this); text.setText(before); text.addTextChangedListener(this)
                        bound = cur.copy(text = before)
                        onEnter(cur, before, after)
                        return
                    }
                    bound = cur.copy(text = str)
                    val i = items.indexOfFirst { it.id == cur.id }; if (i >= 0) items[i] = bound!!
                    onText(cur, str)
                }
            }
            text.addTextChangedListener(watcher)
            text.setOnEditorActionListener { _, actionId, _ -> if (actionId == EditorInfo.IME_ACTION_NEXT) { bound?.let { onEnter(it, text.text.toString(), "") }; true } else false }
            text.setOnKeyListener { _, keyCode, ev ->
                if (keyCode == KeyEvent.KEYCODE_DEL && ev.action == KeyEvent.ACTION_DOWN && text.text.isEmpty()) { bound?.let(onBackspaceEmpty); true } else false
            }
            if (focusRequestId == item.id) { focusRequestId = null; text.post { text.showKeyboard(); text.setSelection(text.text.length) } }
        }
    }
}
