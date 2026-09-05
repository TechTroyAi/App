package ai.techtroy.notebook.ui

import android.content.Context
import android.content.Intent
import android.graphics.Matrix
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.TextView
import android.widget.VideoView
import androidx.core.widget.ImageViewCompat
import ai.techtroy.notebook.R
import ai.techtroy.notebook.core.dp
import ai.techtroy.notebook.core.tint
import ai.techtroy.notebook.data.AttachmentKind

/** Full-screen image (pinch zoom) or in-app video player. */
class ViewerActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getLongExtra(EXTRA_ATT, -1)
        val root = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        setContentView(root)
        window.statusBarColor = 0xFF000000.toInt(); window.navigationBarColor = 0xFF000000.toInt()
        app.async({ app.repo.attachment(id) }) { a ->
            if (a == null) { finish(); return@async }
            if (a.kind == AttachmentKind.VIDEO) {
                val vv = VideoView(this)
                root.addView(vv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))
                val mc = MediaController(this); mc.setAnchorView(vv); vv.setMediaController(mc)
                vv.setVideoURI(app.files.uriFor(a.path)); vv.setOnPreparedListener { it.isLooping = false; vv.start() }
            } else {
                val iv = ZoomImageView(this)
                root.addView(iv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                app.async({ app.files.loadBitmap(a.path, 2048) }) { b -> iv.setImageBitmap(b) }
            }
            val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(4), dp(4), dp(4)); fitsSystemWindows = true }
            fun ib(icon: Int, onClick: () -> Unit) = ImageButton(this).apply { setImageResource(icon); background = with(android.util.TypedValue()) { theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, this, true); getDrawable(resourceId) }; ImageViewCompat.setImageTintList(this, tint(0xFFFFFFFF.toInt())); setOnClickListener { onClick() } }
            bar.addView(ib(R.drawable.ic_back) { finish() }, LinearLayout.LayoutParams(dp(44), dp(44)))
            bar.addView(TextView(this).apply { text = a.name; setTextColor(0xFFFFFFFF.toInt()); textSize = 15f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(dp(8), 0, dp(8), 0) }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            bar.addView(ib(R.drawable.ic_share) { Media.share(this, a) }, LinearLayout.LayoutParams(dp(44), dp(44)))
            bar.addView(ib(R.drawable.ic_file) { Media.openExternal(this, a) }, LinearLayout.LayoutParams(dp(44), dp(44)))
            bar.background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xAA000000.toInt(), 0x00000000))
            root.addView(bar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        }
    }

    companion object {
        const val EXTRA_ATT = "att_id"
        fun intent(ctx: Context, attachmentId: Long) = Intent(ctx, ViewerActivity::class.java).putExtra(EXTRA_ATT, attachmentId)
    }
}

class ZoomImageView(ctx: Context) : androidx.appcompat.widget.AppCompatImageView(ctx) {
    private val m = Matrix(); private var scale = 1f; private var lastX = 0f; private var lastY = 0f; private var fitted = false
    private val det = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean { val f = (scale * d.scaleFactor).coerceIn(1f, 6f) / scale; scale *= f; m.postScale(f, f, d.focusX, d.focusY); imageMatrix = m; return true }
    })
    init { scaleType = ScaleType.MATRIX }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) { super.onLayout(changed, l, t, r, b); fit() }
    override fun setImageBitmap(bm: android.graphics.Bitmap?) { super.setImageBitmap(bm); fitted = false; fit() }
    private fun fit() {
        val d = drawable ?: return; if (width == 0) return
        val s = minOf(width / d.intrinsicWidth.toFloat(), height / d.intrinsicHeight.toFloat())
        m.reset(); m.postScale(s, s); m.postTranslate((width - d.intrinsicWidth * s) / 2, (height - d.intrinsicHeight * s) / 2); scale = 1f; imageMatrix = m; fitted = true
    }
    override fun onTouchEvent(e: MotionEvent): Boolean {
        det.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = e.x; lastY = e.y }
            MotionEvent.ACTION_MOVE -> if (!det.isInProgress && e.pointerCount == 1 && scale > 1f) { m.postTranslate(e.x - lastX, e.y - lastY); imageMatrix = m; lastX = e.x; lastY = e.y }
            MotionEvent.ACTION_POINTER_UP -> { val i = if (e.actionIndex == 0) 1 else 0; lastX = e.getX(i); lastY = e.getY(i) }
        }
        return true
    }
}
