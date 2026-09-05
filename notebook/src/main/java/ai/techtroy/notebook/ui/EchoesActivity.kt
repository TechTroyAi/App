package ai.techtroy.notebook.ui

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.widget.ImageViewCompat
import ai.techtroy.notebook.R
import ai.techtroy.notebook.core.Fmt
import ai.techtroy.notebook.core.ThemeManager
import ai.techtroy.notebook.core.dp
import ai.techtroy.notebook.core.gold
import ai.techtroy.notebook.core.hairline
import ai.techtroy.notebook.core.muted
import ai.techtroy.notebook.core.roundRect
import ai.techtroy.notebook.core.textPrimary
import ai.techtroy.notebook.core.tint
import ai.techtroy.notebook.data.Echo
import ai.techtroy.notebook.data.Note
import ai.techtroy.notebook.echo.ReplayActivity
import ai.techtroy.notebook.sys.Lock

/**
 * Echoes — the memory shelf. Today's echo, sealed time capsules waiting to open, and past echoes.
 * Nothing here is generated online; it's all the note's own history looking back at you.
 */
class EchoesActivity : BaseActivity() {

    private lateinit var col: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(ThemeManager.attr(this@EchoesActivity, android.R.attr.colorBackground)) }
        root.addView(TopBar.create(this, getString(R.string.nav_echoes), R.drawable.ic_replay) { refresh(force = true) })
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(4), dp(16), dp(32)) }
        root.addView(ScrollView(this).apply { addView(col); isVerticalScrollBarEnabled = false }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    override fun onResume() { super.onResume(); refresh(force = false) }

    private fun refresh(force: Boolean) {
        app.async({
            if (force) app.echoes.refreshToday(force = true)
            val today = app.echoes.today()
            val capsules = app.repo.sealedCapsules()
            val past = app.repo.allEchoes().filter { it.id != today?.id }
            Triple(today, capsules, past)
        }) { (today, capsules, past) -> render(today, capsules, past) }
    }

    private fun header(text: String) = TextView(this).apply { this.text = text.uppercase(); textSize = 11f; letterSpacing = 0.14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(gold()); setPadding(dp(4), dp(22), 0, dp(10)) }

    private fun render(today: Echo?, capsules: List<Note>, past: List<Echo>) {
        col.removeAllViews()
        // intro
        col.addView(TextView(this).apply { text = "Echoes are your own notes coming back to you — on anniversaries, when a capsule opens, or whenever you press Replay. Everything stays on this phone."; textSize = 13f; setTextColor(muted()); setLineSpacing(0f, 1.25f); setPadding(dp(4), dp(10), dp(4), dp(4)) })

        col.addView(header("Today"))
        if (today != null) col.addView(echoCard(today, big = true))
        else col.addView(emptyCard(if (app.prefs.echoDaily) "Nothing is echoing today. Keep writing — a year from now this quiet day will have something to say." else "Daily echoes are off. Turn them on in Settings → Echoes."))

        col.addView(header("Time capsules · ${capsules.size}"))
        if (capsules.isEmpty()) col.addView(emptyCard("Seal any note from its ⋯ menu. It blurs shut until the date you choose, then returns as an Echo."))
        capsules.forEach { n -> col.addView(capsuleCard(n)) }

        col.addView(header("Past echoes"))
        if (past.isEmpty()) col.addView(emptyCard("Past echoes collect here, so you can walk back through them."))
        past.forEach { e -> col.addView(echoCard(e, big = false)) }
    }

    private fun card(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = roundRect(ThemeManager.attr(this@EchoesActivity, R.attr.nbCard), 18f, this@EchoesActivity, hairline()); setPadding(dp(18), dp(16), dp(18), dp(16)) }.also { it.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) } }

    private fun emptyCard(text: String) = card().apply { alpha = 0.85f; addView(TextView(this@EchoesActivity).apply { this.text = text; textSize = 13f; setTextColor(muted()); setLineSpacing(0f, 1.25f) }) }

    private fun echoCard(e: Echo, big: Boolean): View = card().apply {
        if (big) background = roundRect(ThemeManager.attr(this@EchoesActivity, R.attr.nbCard), 18f, this@EchoesActivity, gold(), 1.5f)
        val row = LinearLayout(this@EchoesActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(ImageView(this@EchoesActivity).apply { setImageResource(R.drawable.ic_echo); ImageViewCompat.setImageTintList(this, tint(gold())) }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(8) })
        row.addView(TextView(this@EchoesActivity).apply { text = (if (e.kind == "capsule") "Time capsule opened" else "Echo") + "  ·  " + Fmt.date(e.forDay * 86_400_000L); textSize = 11f; letterSpacing = 0.08f; setTextColor(gold()); typeface = Typeface.DEFAULT_BOLD })
        addView(row)
        addView(TextView(this@EchoesActivity).apply { text = e.text; textSize = if (big) 17f else 14f; setTextColor(textPrimary()); setLineSpacing(0f, 1.3f); setPadding(0, dp(10), 0, dp(12)); if (big) typeface = Typeface.create("serif", Typeface.NORMAL) })
        val actions = LinearLayout(this@EchoesActivity).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(pill(getString(R.string.echo_open)) { openNote(e.noteId, echo = true) })
        actions.addView(pill(getString(R.string.replay)) { openNote(e.noteId, echo = false, replay = true) })
        addView(actions)
    }

    private fun capsuleCard(n: Note): View = card().apply {
        val row = LinearLayout(this@EchoesActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(ImageView(this@EchoesActivity).apply { setImageResource(R.drawable.ic_capsule); ImageViewCompat.setImageTintList(this, tint(gold())) }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(12) })
        val txt = LinearLayout(this@EchoesActivity).apply { orientation = LinearLayout.VERTICAL }
        txt.addView(TextView(this@EchoesActivity).apply { text = n.title.ifBlank { "Time capsule" }; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(textPrimary()) })
        val days = ((n.capsuleUntil ?: 0L) - System.currentTimeMillis()) / 86_400_000L
        txt.addView(TextView(this@EchoesActivity).apply { text = getString(R.string.capsule_sealed_until, Fmt.date(n.capsuleUntil ?: 0)) + (if (days > 0) "  ·  $days day${if (days == 1L) "" else "s"} to go" else "  ·  opens today"); textSize = 12f; setTextColor(muted()); setPadding(0, dp(2), 0, 0) })
        row.addView(txt, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(row)
        setOnClickListener { CapsuleDialogs.showSealed(this@EchoesActivity, n) }
    }

    private fun pill(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 13f; typeface = Typeface.DEFAULT_BOLD; setTextColor(gold()); background = roundRect(0, 16f, this@EchoesActivity, hairline()); setPadding(dp(14), dp(7), dp(14), dp(7)); setOnClickListener { onClick() }
    }.also { it.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) } }

    private fun openNote(id: Long, echo: Boolean, replay: Boolean = false) {
        app.async({ app.repo.note(id) }) { n ->
            if (n == null) return@async
            val go = { if (replay) startActivity(ReplayActivity.intent(this, id)) else startActivity(EditorActivity.intent(this, id).putExtra(EditorActivity.EXTRA_ECHO, echo)) }
            if (n.locked && !Lock.sessionValid(app.prefs)) LockActivity.unlock(this) { go() } else go()
        }
    }
}
