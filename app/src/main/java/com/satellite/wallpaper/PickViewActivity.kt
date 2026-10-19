package com.satellite.wallpaper

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONTokener

/**
 * Pantalla de selección: muestra Zoom Earth interactivo. El usuario mueve/hace zoom/elige capa
 * y al pulsar "Guardar esta vista" se recuerda la URL actual (incluye posición, zoom y capas).
 */
class PickViewActivity : Activity() {

    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF10212B.toInt())
        }
        val hint = TextView(this).apply {
            text = getString(R.string.pick_title)
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding((16 * d).toInt(), (10 * d).toInt(), (16 * d).toInt(), (10 * d).toInt())
        }
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient()
        }
        val save = Button(this).apply {
            text = getString(R.string.pick_save)
            isAllCaps = false
            setOnClickListener { saveSelection() }
        }
        root.addView(hint)
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(save)
        setContentView(root)

        root.setOnApplyWindowInsetsListener { v, insets ->
            val top: Int
            val bottom: Int
            if (Build.VERSION.SDK_INT >= 30) {
                val b = insets.getInsets(WindowInsets.Type.systemBars())
                top = b.top; bottom = b.bottom
            } else {
                @Suppress("DEPRECATION")
                top = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                bottom = insets.systemWindowInsetBottom
            }
            v.setPadding(0, top, 0, bottom)
            insets
        }
        root.requestApplyInsets()

        web.loadUrl(WallpaperRepository.viewUrl(this))
    }

    private fun saveSelection() {
        // location.href refleja siempre la vista actual del mapa (la SPA actualiza el #hash).
        web.evaluateJavascript("location.href") { res ->
            val url = runCatching { JSONTokener(res).nextValue() as? String }.getOrNull()
            if (url.isNullOrBlank() || !url.contains("zoom.earth")) {
                Toast.makeText(this, R.string.pick_invalid, Toast.LENGTH_LONG).show()
                return@evaluateJavascript
            }
            WallpaperRepository.saveViewUrl(this, url)
            setResult(RESULT_OK)
            finish()
        }
    }

    override fun onDestroy() {
        (web.parent as? ViewGroup)?.removeView(web)
        web.destroy()
        super.onDestroy()
    }
}
