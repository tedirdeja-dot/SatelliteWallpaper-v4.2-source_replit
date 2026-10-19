package com.satellite.wallpaper

import android.content.Context
import android.app.ActivityManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlin.math.roundToInt

/** Tamaño real de la pantalla en vertical (incluye barras del sistema): el mismo que usa el fondo animado. */
object ScreenSize {
    data class CaptureSize(val width: Int, val height: Int, val densityDpi: Int) {
        val maxDimension: Int get() = maxOf(width, height)
    }

    fun real(context: Context): Pair<Int, Int> {
        val wm = context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val w: Int
        val h: Int
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            w = b.width(); h = b.height()
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(m)
            w = m.widthPixels; h = m.heightPixels
        }
        return Pair(minOf(w, h), maxOf(w, h))
    }

    /**
     * Limita la superficie de captura según la memoria del dispositivo. WebView, ImageReader y
     * Bitmap pueden coexistir durante unos instantes; limitar sus dimensiones evita picos de RAM.
     */
    fun capture(context: Context): CaptureSize {
        val app = context.applicationContext
        val (screenWidth, screenHeight) = real(app)
        val activityManager = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val maxEdge = when {
            activityManager.isLowRamDevice || activityManager.memoryClass <= 128 -> 960
            activityManager.memoryClass <= 256 -> 1280
            activityManager.memoryClass <= 512 -> 1600
            else -> 1920
        }
        val scale = minOf(1f, maxEdge.toFloat() / maxOf(screenWidth, screenHeight))
        val densityDpi = (app.resources.displayMetrics.densityDpi * scale)
            .roundToInt()
            .coerceAtLeast(120)
        return CaptureSize(
            (screenWidth * scale).roundToInt().coerceAtLeast(1),
            (screenHeight * scale).roundToInt().coerceAtLeast(1),
            densityDpi
        )
    }

    /** Relación ancho/alto en vertical. */
    fun aspect(context: Context): Float {
        val (w, h) = real(context)
        return w.toFloat() / h.toFloat()
    }
}
