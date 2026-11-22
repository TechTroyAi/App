package ai.techtroy.floating

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View

class PaintView(context: Context) : View(context) {
    private val strokes = mutableListOf<Pair<Path, Int>>()
    private var current: Path? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 8f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        style = Paint.Style.STROKE
    }
    var brushColor: Int = Color.parseColor("#2EE59B")
    fun clearAll() { strokes.clear(); current = null; invalidate() }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                current = Path().apply { moveTo(event.x, event.y); lineTo(event.x + 0.1f, event.y + 0.1f) }
                strokes.add(current!! to brushColor)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> { current?.lineTo(event.x, event.y); invalidate(); return true }
            MotionEvent.ACTION_UP -> { current?.lineTo(event.x, event.y); current = null; invalidate(); performClick(); return true }
        }
        return super.onTouchEvent(event)
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.parseColor("#111714"))
        strokes.forEach { (path, color) -> paint.color = color; canvas.drawPath(path, paint) }
    }
}
