package com.satellite.wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

/**
 * Editor de encuadre. Dibuja un recuadro con la MISMA proporción que la pantalla del teléfono y
 * aplica en él exactamente el mismo cálculo que el fondo animado (CropMath), de modo que el encuadre
 * que ves aquí es el que se verá en el teléfono.
 */
class CropPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var bitmap: Bitmap? = null
    private var updatedAt = 0L
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private val screenAspect = ScreenSize.aspect(context)
    private val frame = RectF()
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
        color = 0x99FFFFFF.toInt()
    }

    var state = CropState()
        private set
    var onStateChanged: ((CropState) -> Unit)? = null

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                state = state.copy(zoom = state.zoom * detector.scaleFactor).clamped()
                invalidate()
                onStateChanged?.invoke(state)
                return true
            }
        }
    )

    fun setBitmap(value: Bitmap, initialState: CropState, imageUpdatedAt: Long) {
        bitmap = value
        state = initialState.clamped()
        updatedAt = imageUpdatedAt
        invalidate()
    }

    /** Recuadro con la proporción de la pantalla, centrado y lo más grande posible. */
    private fun computeFrame() {
        val vw = width.toFloat()
        val vh = height.toFloat()
        var w = vh * screenAspect
        var h = vh
        if (w > vw) { w = vw; h = vw / screenAspect }
        val l = (vw - w) / 2f
        val t = (vh - h) / 2f
        frame.set(l, t, l + w, t + h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(0xFF070D12.toInt())
        computeFrame()
        val fw = frame.width().toInt()
        val fh = frame.height().toInt()
        val save = canvas.save()
        canvas.clipRect(frame)
        canvas.translate(frame.left, frame.top)
        canvas.drawColor(0xFF10212B.toInt())
        bitmap?.let {
            CropMath.draw(canvas, it, fw, fh, state)
            CropMath.drawUpdatedAt(canvas, updatedAt, fw, fh)
        }
        canvas.restoreToCount(save)
        canvas.drawRect(frame, border)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        val count = event.pointerCount.coerceAtLeast(1)
        var sx = 0f
        var sy = 0f
        for (i in 0 until count) {
            sx += event.getX(i)
            sy += event.getY(i)
        }
        val fx = sx / count
        val fy = sy / count

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastFocusX = fx
                lastFocusY = fy
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val bmp = bitmap
                if (event.pointerCount == 1 && bmp != null && !scaleDetector.isInProgress) {
                    computeFrame()
                    val (maxX, maxY) = CropMath.maxPan(frame.width().toInt(), frame.height().toInt(), bmp, state.zoom)
                    state = state.copy(
                        offsetX = if (maxX > 0f) state.offsetX + (fx - lastFocusX) / maxX else state.offsetX,
                        offsetY = if (maxY > 0f) state.offsetY + (fy - lastFocusY) / maxY else state.offsetY
                    ).clamped()
                    invalidate()
                    onStateChanged?.invoke(state)
                }
                lastFocusX = fx
                lastFocusY = fy
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                lastFocusX = fx
                lastFocusY = fy
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }
}
