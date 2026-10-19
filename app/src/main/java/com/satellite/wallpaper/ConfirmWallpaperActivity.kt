package com.satellite.wallpaper

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Vista previa a pantalla completa: dibuja la imagen con el mismo cálculo que el fondo animado
 * (CropMath, mismo encuadre, mismo tamaño de pantalla) y pide confirmación con Aceptar / Cancelar.
 * RESULT_OK = aplicar el fondo; RESULT_CANCELED = no hacer nada.
 */
class ConfirmWallpaperActivity : Activity() {

    private var bitmap: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bmp = WallpaperRepository.loadCached(this)
        if (bmp == null) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        bitmap = bmp
        val state = WallpaperRepository.loadState(this)
        val updatedAt = WallpaperRepository.updatedAt(this)
        val d = resources.displayMetrics.density
        val ctx = this

        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        val image = object : View(ctx) {
            override fun onDraw(canvas: Canvas) {
                canvas.drawColor(0xFF10212B.toInt())
                // Mismo cálculo que SatelliteWallpaperService.draw() (sin desplazamiento del launcher).
                CropMath.draw(canvas, bmp, width, height, state, 0.5f)
                CropMath.drawUpdatedAt(canvas, updatedAt, width, height)
            }
        }

        fun pill(text: String, fill: Int, stroke: Int): Button = Button(ctx).apply {
            this.text = text
            isAllCaps = false
            textSize = 17f
            setTextColor(Color.WHITE)
            stateListAnimator = null
            minHeight = (54 * d).toInt()
            background = GradientDrawable().apply {
                setColor(fill)
                cornerRadius = 28 * d
                if (stroke != 0) setStroke((2 * d).toInt(), stroke)
            }
        }

        val hint = TextView(ctx).apply {
            text = getString(R.string.confirm_hint)
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            setShadowLayer(8f, 0f, 1f, 0xFF000000.toInt())
            setPadding(0, 0, 0, (12 * d).toInt())
        }
        val cancel = pill(getString(R.string.confirm_cancel), 0xB3000000.toInt(), 0x99FFFFFF.toInt()).apply {
            setOnClickListener { setResult(RESULT_CANCELED); finish() }
        }
        val accept = pill(getString(R.string.confirm_accept), 0xFFD46A3A.toInt(), 0).apply {
            setOnClickListener { setResult(RESULT_OK); finish() }
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(cancel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = (8 * d).toInt()
            })
            addView(accept, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (8 * d).toInt()
            })
        }
        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(hint)
            addView(row)
        }

        val root = FrameLayout(ctx)
        root.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(
            panel,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
                setMargins((24 * d).toInt(), 0, (24 * d).toInt(), (48 * d).toInt())
            }
        )
        setContentView(root)
        enterImmersive()
    }

    private fun enterImmersive() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && bitmap != null) enterImmersive()
    }

    override fun onDestroy() {
        bitmap?.recycle()
        bitmap = null
        super.onDestroy()
    }
}
