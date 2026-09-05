package ai.techtroy.notebook.echo

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import ai.techtroy.notebook.R
import ai.techtroy.notebook.core.dp
import ai.techtroy.notebook.ui.BaseActivity
import kotlin.math.min
import kotlin.math.sin

/**
 * Settings → About → tap the version 7 times.
 * Black screen. The #7 gold bookmark ribbon unfurls from the top, the three lines and the check draw themselves,
 * then the dedication fades in. Tap anywhere to leave.
 */
class EasterEggActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()); setOnClickListener { finish(); overridePendingTransition(R.anim.fade_in, R.anim.fade_out) } }
        val ribbon = RibbonView(this)
        root.addView(ribbon, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(36), 0, dp(36), dp(64)); alpha = 0f }
        col.addView(TextView(this).apply { text = "NOTEBOOK · WITH ECHOES"; textSize = 11f; letterSpacing = 0.28f; setTextColor(0xFFA8862A.toInt()); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER })
        col.addView(TextView(this).apply { text = getString(R.string.easter_egg); textSize = 16f; setTextColor(0xFFF5F2EA.toInt()); typeface = Typeface.create("serif", Typeface.ITALIC); setLineSpacing(0f, 1.45f); gravity = Gravity.CENTER; setPadding(0, dp(18), 0, 0) })
        col.addView(TextView(this).apply { text = "tap to close"; textSize = 11f; setTextColor(0xFF6F6C66.toInt()); gravity = Gravity.CENTER; setPadding(0, dp(28), 0, 0) })
        root.addView(col, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        setContentView(root)
        ribbon.play { col.animate().alpha(1f).setDuration(1400).start() }
    }

    class RibbonView(ctx: Context) : View(ctx) {
        private var unfurl = 0f       // 0..1 ribbon length
        private var lines = 0f        // 0..1 three lines + check
        private var shimmer = 0f
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        private val path = Path()
        private var onDone: (() -> Unit)? = null

        fun play(onDone: () -> Unit) {
            this.onDone = onDone
            ValueAnimator.ofFloat(0f, 1f).apply { duration = 2200; startDelay = 500; interpolator = DecelerateInterpolator(1.6f); addUpdateListener { unfurl = it.animatedValue as Float; invalidate() } }.start()
            ValueAnimator.ofFloat(0f, 1f).apply { duration = 1800; startDelay = 2000; interpolator = DecelerateInterpolator(); addUpdateListener { lines = it.animatedValue as Float; invalidate() }; addListener(object : android.animation.AnimatorListenerAdapter() { override fun onAnimationEnd(a: android.animation.Animator) { onDone() } }) }.start()
            ValueAnimator.ofFloat(0f, 1f).apply { duration = 3600; repeatCount = ValueAnimator.INFINITE; addUpdateListener { shimmer = it.animatedValue as Float; invalidate() } }.start()
        }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            // faint gold vignette that grows with the ribbon
            p.shader = RadialGradient(w * 0.5f, h * 0.32f, w * 0.85f, intArrayOf(((0x30 * unfurl).toInt() shl 24) or 0xD4AF37, 0x00D4AF37), null, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, h, p); p.shader = null

            // ribbon: hangs from the top, slightly right of centre, like the icon
            val rw = w * 0.16f; val cx = w * 0.62f; val fullLen = h * 0.46f
            val len = fullLen * unfurl
            if (len > 1f) {
                val x0 = cx - rw / 2; val x1 = cx + rw / 2
                val notch = min(rw * 0.55f, len)
                path.rewind(); path.moveTo(x0, 0f); path.lineTo(x1, 0f); path.lineTo(x1, len); path.lineTo(cx, len - notch); path.lineTo(x0, len); path.close()
                // gradient with a moving shimmer band
                val s = (shimmer * 2f - 0.5f)
                p.shader = LinearGradient(x0, 0f, x1, len, intArrayOf(0xFFA8862A.toInt(), 0xFFD4AF37.toInt(), 0xFFF0D77A.toInt(), 0xFFD4AF37.toInt(), 0xFFA8862A.toInt()), floatArrayOf(0f, (s - 0.25f).coerceIn(0f, 1f), s.coerceIn(0f, 1f), (s + 0.25f).coerceIn(0f, 1f), 1f), Shader.TileMode.CLAMP)
                c.drawPath(path, p); p.shader = null
                // edge highlight + shadow like the vector icon
                p.color = 0x33FFFFFF; c.drawRect(x0, 0f, x0 + rw * 0.3f, len - notch * 0.6f, p)
                p.color = 0x40000000; c.drawRect(x1 - rw * 0.3f, 0f, x1, len - notch * 0.2f, p)
                // gentle sway: a soft shadow beneath the tail
                p.color = 0x22000000; c.drawOval(cx - rw, len + 6f + 2f * sin(shimmer * 6.28f), cx + rw, len + 22f, p)
            }

            // three lines + checkbox drawing in, left of the ribbon
            if (lines > 0f) {
                val left = w * 0.16f; val right = cx - rw / 2 - w * 0.06f
                val y0 = h * 0.30f; val gap = h * 0.075f
                stroke.color = 0xFFD4AF37.toInt(); stroke.strokeWidth = context.dp(2.4f)
                val ys = floatArrayOf(y0, y0 + gap, y0 + gap * 2)
                val ends = floatArrayOf(right, right + w * 0.12f + rw, right - w * 0.02f)
                for (i in 0 until 3) {
                    val f = ((lines * 3f) - i).coerceIn(0f, 1f); if (f <= 0f) continue
                    val startX = if (i == 0) left + w * 0.11f else left
                    c.drawLine(startX, ys[i], startX + (ends[i] - startX) * f, ys[i], stroke)
                }
                // checkbox on the first line
                val box = w * 0.07f; val bx = left; val by = y0 - box / 2
                val f = (lines * 1.6f).coerceIn(0f, 1f)
                stroke.strokeWidth = context.dp(2f)
                path.rewind(); path.moveTo(bx, by); path.lineTo(bx + box, by); path.lineTo(bx + box, by + box); path.lineTo(bx, by + box); path.close()
                val pm = android.graphics.PathMeasure(path, false); val seg = Path(); pm.getSegment(0f, pm.length * f, seg, true); c.drawPath(seg, stroke)
                if (lines > 0.7f) {
                    stroke.color = 0xFFF0D77A.toInt()
                    val cf = ((lines - 0.7f) / 0.3f).coerceIn(0f, 1f)
                    path.rewind(); path.moveTo(bx + box * 0.22f, by + box * 0.52f); path.lineTo(bx + box * 0.45f, by + box * 0.75f); path.lineTo(bx + box * 0.82f, by + box * 0.28f)
                    val pm2 = android.graphics.PathMeasure(path, false); val seg2 = Path(); pm2.getSegment(0f, pm2.length * cf, seg2, true); c.drawPath(seg2, stroke)
                }
            }
        }
    }
}
