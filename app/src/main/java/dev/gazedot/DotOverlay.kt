package dev.gazedot

import android.content.Context
import android.graphics.*
import android.view.*

class DotOverlay(context: Context) {
    private val manager = context.getSystemService(WindowManager::class.java)
    private val dot = object : View(context) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        var position: Pair<Double, Double>? = null
        var armed = false
        var label = ""
        var suggestion: Pair<Double, Double>? = null
        override fun onDraw(canvas: Canvas) {
            val d=resources.displayMetrics.density
            suggestion?.let { p ->
                paint.color=Color.CYAN; paint.style=Paint.Style.STROKE; paint.strokeWidth=3*d
                canvas.drawCircle((p.first*width).toFloat(),(p.second*height).toFloat(),19*d,paint)
                paint.style=Paint.Style.FILL
            }
            if(label.isNotBlank()) {
                paint.color=Color.BLACK; paint.textSize=14*d; paint.textAlign=Paint.Align.CENTER
                canvas.drawRoundRect(8*d,38*d,width-8*d,80*d,12*d,12*d,paint)
                paint.color=Color.WHITE
                canvas.drawText(label.take(44),width/2f,64*d,paint)
            }
            val p = position ?: return
            val x = p.first.coerceIn(.015,.985).toFloat() * width; val y = p.second.coerceIn(.015,.985).toFloat() * height
            paint.color = Color.WHITE
            canvas.drawCircle(x, y, 10 * resources.displayMetrics.density, paint)
            paint.color = if(armed) Color.rgb(255,170,0) else Color.RED
            canvas.drawCircle(x, y, 7 * resources.displayMetrics.density, paint)
            if(armed) {
                paint.style=Paint.Style.STROKE; paint.strokeWidth=3*d
                canvas.drawCircle(x,y,18*d,paint); paint.style=Paint.Style.FILL
            }
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
    fun show(position: Pair<Double, Double>?, armed: Boolean=false, label: String="", suggestion: Pair<Double,Double>?=null) {
        dot.position = position; dot.armed=armed; dot.label=label; dot.suggestion=suggestion; dot.invalidate()
    }
    fun close() { if (dot.isAttachedToWindow) manager.removeView(dot) }
}
