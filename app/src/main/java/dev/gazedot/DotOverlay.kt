package dev.gazedot

import android.content.Context
import android.graphics.*
import android.view.*

class DotOverlay(context: Context) {
    private val manager = context.getSystemService(WindowManager::class.java)
    private val dot = object : View(context) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        var position: Pair<Double, Double>? = null
        var dwellProgress = 0f
        override fun onDraw(canvas: Canvas) {
            val p = position ?: return
            val x = p.first.toFloat() * width; val y = p.second.toFloat() * height
            if (dwellProgress > 0f) {
                paint.color = Color.rgb(6, 108, 236)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 3 * resources.displayMetrics.density
                val radius = 15 * resources.displayMetrics.density
                canvas.drawArc(x - radius, y - radius, x + radius, y + radius, -90f,
                    360f * dwellProgress.coerceIn(0f, 1f), false, paint)
                paint.style = Paint.Style.FILL
            }
            paint.color = Color.WHITE
            canvas.drawCircle(x, y, 10 * resources.displayMetrics.density, paint)
            paint.color = Color.RED
            canvas.drawCircle(x, y, 7 * resources.displayMetrics.density, paint)
        }
    }
    init {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Android 12+ blocks touches through untrusted overlays above the opacity threshold.
            alpha = 0.7f
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            if (android.os.Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
        }
        manager.addView(dot, params)
    }
    fun show(position: Pair<Double, Double>?, dwellProgress: Float = 0f) {
        dot.position = position
        dot.dwellProgress = dwellProgress
        dot.invalidate()
    }
    fun close() { if (dot.isAttachedToWindow) manager.removeView(dot) }
}
