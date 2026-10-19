package com.satellite.wallpaper

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.app.Presentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.Window
import android.view.WindowManager
import android.util.Log
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.widget.FrameLayout
import android.webkit.WebViewClient
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * Mecanismo tomado de ZoomEarthDownloader (com.ejemplo.zoomearthdownloader):
 *   WebView visible y adjunto a la ventana -> onPageFinished -> +2,5 s inyecta el CSS de limpieza
 *   -> captura el rectángulo del WebView con PixelCopy -> JPEG.
 * Aquí el JPEG se guarda en `target` (caché interna de la app) en vez de la galería.
 *
 *  - host != null: WebView dentro de ese contenedor (modo fiable, igual que la app original).
 *  - host == null y permiso "Mostrar sobre otras apps": WebView en una ventana overlay casi invisible
 *    y no táctil (segundo plano fiable, sin abrir la app) + WebView.draw().
 *  - host == null sin permiso: WebView fuera de pantalla + WebView.draw() (puede salir en blanco).
 *
 * onDone(null) = éxito; onDone(texto) = motivo del fallo. Debe llamarse desde el hilo principal.
 */
object ZoomEarthCapture {

    const val PAGE_URL =
        "https://zoom.earth/maps/satellite/#view=30.577,-18.135,6z/overlays=radar,labels:off"

    private const val TAG = "ZoomEarthCapture"
    private const val CLEAN_DELAY_MS = 1_500L
    private const val CAPTURE_DELAY_MS = 5_000L // espera menor tras cargar Zoom Earth para acelerar cada actualización
    private const val TIMEOUT_MS = 50_000L
    private const val FOCUS_WAIT_MS = 15_000L // espera máxima a que la ventana de la app vuelva a tener foco

    /** Prefijo de los fallos de red: la cascada de estrategias no se repite, no serviría de nada. */
    const val NET_ERR = "error de red"

    /** Nº de recursos (teselas, scripts…) fallidos por conexión a partir del cual la captura se descarta. */
    private const val MAX_RESOURCE_NET_ERRORS = 5

    /** Códigos de WebViewClient que indican problema de conectividad (DNS, conexión, E/S, tiempo agotado). */
    private val NET_ERROR_CODES = setOf(
        WebViewClient.ERROR_HOST_LOOKUP, WebViewClient.ERROR_CONNECT,
        WebViewClient.ERROR_IO, WebViewClient.ERROR_TIMEOUT
    )

    /** Estrategias de captura en segundo plano (se prueban en cascada, ver [captureBackground]). */
    enum class Mode { VIRTUAL, OVERLAY, DRAW }

    /** Modo con el que terminó la última captura (para el registro de diagnóstico). */
    @Volatile
    var lastMode: String = ""
        private set

    /** CSS de limpieza de ZoomEarthDownloader (clases exactas de Zoom Earth). */
    private const val CLEAN_JS = """
        (function () {
          if (document.getElementById('__clean_css')) return 'ok';
          var style = document.createElement('style');
          style.id = '__clean_css';
          style.type = 'text/css';
          style.innerHTML = 'header, footer, nav, aside, .panel, .ui-container, .overlays, .controls, ' +
            'button, [role=button], .zoom-control, .map-attribution, .logo { ' +
            'display: none !important; opacity: 0 !important; visibility: hidden !important; }';
          document.head.appendChild(style);
          return 'ok';
        })();
    """

    @SuppressLint("SetJavaScriptEnabled")
    fun capture(
        context: Context,
        target: File,
        host: ViewGroup?,
        url: String = PAGE_URL,
        forced: Mode? = null,
        onDone: (String?) -> Unit
    ) {
        val app = context.applicationContext
        val captureSize = ScreenSize.capture(app)
        val sw = captureSize.width
        val sh = captureSize.height
        val handler = Handler(Looper.getMainLooper())
        val wv = WebView(host?.context ?: app)
        var finished = false
        var pageFinished = false
        var loadError: String? = null
        var resourceNetErrors = 0
        var overlayDialog: Dialog? = null
        var offscreen: OffscreenDisplay? = null
        var modeLabel = if (host != null) "ventana" else "?"

        fun release() {
            try {
                offscreen?.release()
                offscreen = null
                overlayDialog?.let { d -> runCatching { d.dismiss() } }
                overlayDialog = null
                (wv.parent as? ViewGroup)?.removeView(wv)
                wv.stopLoading()
                wv.destroy()
            } catch (t: Throwable) {
                Log.w(TAG, "destroy", t)
            }
        }

        fun finish(err: String?) {
            if (finished) return
            finished = true
            handler.removeCallbacksAndMessages(null)
            release()
            lastMode = modeLabel
            val full = err?.let { "$it [$modeLabel]" }
            if (full != null) Log.w(TAG, full)
            onDone(full)
        }

        /**
         * La captura con la ventana de la app ha fallado (p. ej. sin superficie): se reintenta en
         * segundo plano con la cascada (pantalla virtual -> overlay -> draw), que no depende de la Activity.
         */
        fun finishViaBackground(reason: String) {
            if (finished) return
            finished = true
            handler.removeCallbacksAndMessages(null)
            release()
            Log.w(TAG, "$reason [$modeLabel] -> reintento en segundo plano")
            captureBackground(app, target, url) { e2 ->
                onDone(if (e2 == null) null else "$reason [$modeLabel] | segundo plano: $e2")
            }
        }

        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
        }

        if (host != null) {
            host.addView(
                wv, minOf(1, host.childCount),
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
        } else {
            val allowVirtual = forced == null || forced == Mode.VIRTUAL
            val allowOverlay = forced == null || forced == Mode.OVERLAY
            var virtualError: String? = null
            // 1) Pantalla virtual propia (sin permisos ni ventana visible).
            if (allowVirtual) {
                offscreen = runCatching {
                    OffscreenDisplay(app, wv, sw, sh, captureSize.densityDpi, handler)
                }.onFailure {
                    virtualError = "${it.javaClass.simpleName} ${it.message}"
                    Log.w(TAG, "pantalla virtual", it)
                }.getOrNull()
            }
            // 2) Ventana overlay casi invisible (necesita permiso "Mostrar sobre otras apps").
            if (offscreen == null && allowOverlay && Settings.canDrawOverlays(app)) {
                // Ventana overlay (Dialog) casi invisible y no táctil. Al ser un Window real se
                // puede usar PixelCopy, igual que en la pantalla de la app (fiable con WebGL).
                overlayDialog = runCatching {
                    val themed = ContextThemeWrapper(app, android.R.style.Theme_Material_NoActionBar)
                    Dialog(themed, android.R.style.Theme_Translucent_NoTitleBar).apply {
                        setCancelable(false)
                        val box = FrameLayout(themed)
                        box.addView(
                            wv,
                            FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                            )
                        )
                        setContentView(box)
                        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                        window!!.apply {
                            setType(
                                if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                                else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
                            )
                            setFlags(flags, flags)
                            setLayout(sw, sh)
                            setGravity(Gravity.TOP or Gravity.START)
                            setBackgroundDrawableResource(android.R.color.transparent)
                            attributes = attributes.apply { alpha = 0.01f } // opacidad a nivel de compositor
                        }
                        show()
                    }
                }.onFailure { Log.w(TAG, "overlay dialog", it) }.getOrNull()
            }
            modeLabel = when {
                offscreen != null -> "pantalla virtual"
                overlayDialog != null -> "ventana overlay"
                else -> "WebView.draw()"
            }
            // Con una estrategia forzada no se cae en silencio a otra: lo decide la cascada.
            if (forced == Mode.VIRTUAL && offscreen == null) {
                finish("pantalla virtual no disponible: $virtualError"); return
            }
            if (forced == Mode.OVERLAY && overlayDialog == null) {
                finish(if (Settings.canDrawOverlays(app)) "no se pudo crear la ventana overlay"
                       else "sin permiso \"Mostrar sobre otras apps\""); return
            }
            if (offscreen == null && overlayDialog == null) {
                wv.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                wv.measure(
                    View.MeasureSpec.makeMeasureSpec(sw, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(sh, View.MeasureSpec.EXACTLY)
                )
                wv.layout(0, 0, sw, sh)
            }
        }
        wv.onResume()
        wv.resumeTimers()

        fun takeSnapshot(clean: String, waitedMs: Long) {
            if (finished) return
            // Página de error del WebView o teselas sin descargar: NO se captura (se conserva la imagen anterior).
            if (loadError != null) {
                finish("$NET_ERR: la página no cargó correctamente ($loadError)"); return
            }
            if (resourceNetErrors >= MAX_RESOURCE_NET_ERRORS) {
                finish("$NET_ERR: fallaron $resourceNetErrors recursos de la página (imagen incompleta)"); return
            }
            // Con la app abierta, esperar a que su ventana tenga foco/superficie (p. ej. al volver del selector de vista).
            if (host != null && !wv.hasWindowFocus() && waitedMs < FOCUS_WAIT_MS) {
                handler.postDelayed({ takeSnapshot(clean, waitedMs + 500) }, 500)
                return
            }
            val win: Window? = (host?.context as? Activity)?.window ?: overlayDialog?.window
            val off = offscreen
            val onResult: (String?) -> Unit = { err ->
                if (err != null && host != null) finishViaBackground(err) else finish(err)
            }
            if (off != null) {
                val bmp = runCatching { off.bitmap() }.getOrNull()
                if (bmp == null) finish("pantalla virtual sin fotogramas")
                else save(bmp, target, clean) { finish(it) }
            } else if (win != null) snapshotWindow(wv, win, target, clean, handler, 0, onResult)
            else snapshotOffscreen(wv, sw, sh, target, clean) { finish(it) }
        }

        fun cleanAndSnapshot() {
            if (finished) return
            wv.evaluateJavascript(CLEAN_JS) { res ->
                if (finished) return@evaluateJavascript
                val clean = res ?: "null"
                handler.postDelayed({ takeSnapshot(clean, 0L) }, 500)
            }
        }

        wv.webViewClient = object : WebViewClient() {
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) loadError = "${error.errorCode} ${error.description}"
                else if (error.errorCode in NET_ERROR_CODES) resourceNetErrors++
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame && response.statusCode >= 400) loadError = "HTTP ${response.statusCode}"
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (finished || pageFinished) return
                pageFinished = true
                // Como en la app original: +2,5 s -> inyectar CSS de limpieza
                handler.postDelayed({
                    if (!finished) wv.evaluateJavascript(CLEAN_JS, null)
                }, CLEAN_DELAY_MS)
                // Tras dejar cargar las teselas: reaplicar (idempotente) y capturar
                handler.postDelayed({ cleanAndSnapshot() }, CAPTURE_DELAY_MS)
            }
        }

        handler.postDelayed({
            finish(
                if (!pageFinished) "timeout: la página no terminó de cargar" + (loadError?.let { " ($it)" } ?: "")
                else "timeout tras cargar la página" + (loadError?.let { " ($it)" } ?: "")
            )
        }, TIMEOUT_MS)
        wv.loadUrl(url)
    }

    /**
     * Captura en segundo plano probando estrategias en cascada hasta que una produzca una imagen válida:
     * pantalla virtual -> ventana overlay (si hay permiso) -> WebView.draw().
     * Si la página ni siquiera carga (red), no se repite con las demás estrategias.
     * Debe llamarse desde el hilo principal.
     */
    fun captureBackground(context: Context, target: File, url: String, onDone: (String?) -> Unit) {
        val app = context.applicationContext
        val steps = buildList {
            add(Mode.VIRTUAL)
            if (Settings.canDrawOverlays(app)) add(Mode.OVERLAY)
            add(Mode.DRAW)
        }
        val errors = mutableListOf<String>()
        fun next(i: Int) {
            if (i >= steps.size) { onDone(errors.joinToString(" | ")); return }
            try {
                capture(app, target, null, url, steps[i]) { err ->
                    if (err == null) {
                        onDone(null)
                    } else {
                        errors += err
                        if (err.startsWith("timeout: la página no terminó de cargar") || err.startsWith(NET_ERR)) onDone(errors.joinToString(" | "))
                        else next(i + 1)
                    }
                }
            } catch (t: Throwable) {
                errors += "excepción (${steps[i]}): ${t.javaClass.simpleName} ${t.message}"
                next(i + 1)
            }
        }
        next(0)
    }

    /**
     * Pantalla virtual privada de la app: el WebView se muestra en un Presentation sobre un
     * VirtualDisplay cuyo destino es un ImageReader. No necesita permisos ni ventana visible.
     */
    private class OffscreenDisplay(
        context: Context, wv: WebView, val w: Int, val h: Int, dpi: Int, handler: Handler
    ) {
        private val reader: ImageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        private var vd: VirtualDisplay? = null
        private var presentation: Presentation? = null
        private var latest: Image? = null

        init {
            try {
                reader.setOnImageAvailableListener({ r ->
                    val img = try { r.acquireLatestImage() } catch (t: Throwable) { null }
                    if (img != null) synchronized(this) { latest?.close(); latest = img }
                }, handler)
                val dmgr = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                val display = dmgr.createVirtualDisplay("zoomearth-capture", w, h, dpi, reader.surface, 0)
                    ?: throw IllegalStateException("createVirtualDisplay devolvió null")
                vd = display
                val pres = Presentation(context, display.display, android.R.style.Theme_Material_Light_NoActionBar)
                val box = FrameLayout(pres.context)
                box.addView(
                    wv,
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                )
                pres.setContentView(box)
                pres.window?.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
                pres.show()
                presentation = pres
            } catch (t: Throwable) {
                release()
                throw t
            }
        }

        /** Último fotograma renderizado como Bitmap (null si aún no hay ninguno). */
        fun bitmap(): Bitmap? {
            synchronized(this) {
                val img = latest ?: return null
                val plane = img.planes[0]
                check(plane.pixelStride == 4) { "Formato de píxel inesperado" }
                val rowBytes = img.width * plane.pixelStride
                val source = plane.buffer.duplicate()
                val pixels = ByteBuffer.allocateDirect(rowBytes * img.height)
                val row = ByteArray(rowBytes)
                for (y in 0 until img.height) {
                    source.position(y * plane.rowStride)
                    source.get(row)
                    pixels.put(row)
                }
                pixels.flip()
                return Bitmap.createBitmap(img.width, img.height, Bitmap.Config.ARGB_8888).also {
                    it.copyPixelsFromBuffer(pixels)
                }
            }
        }

        fun release() {
            runCatching { presentation?.dismiss() }
            runCatching { vd?.release() }
            synchronized(this) { runCatching { latest?.close() }; latest = null }
            runCatching { reader.close() }
            presentation = null
            vd = null
        }
    }

    /**
     * PixelCopy del rectángulo del WebView dentro de una ventana (Activity u overlay).
     * Si la ventana aún no tiene superficie ("Window doesn't have a backing surface!") se reintenta
     * unas veces antes de rendirse; el último recurso es WebView.draw().
     */
    private fun snapshotWindow(
        wv: WebView, window: Window, target: File, clean: String, handler: Handler,
        attempt: Int, done: (String?) -> Unit
    ) {
        val w = wv.width
        val h = wv.height
        if (w <= 0 || h <= 0) { done("el WebView no tiene tamaño"); return }
        if (Build.VERSION.SDK_INT < 26) { snapshotOffscreen(wv, w, h, target, clean, done); return }
        val loc = IntArray(2)
        wv.getLocationInWindow(loc)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        fun retry(): Boolean {
            if (attempt >= 6) return false
            bmp.recycle()
            handler.postDelayed({ snapshotWindow(wv, window, target, clean, handler, attempt + 1, done) }, 700)
            return true
        }
        fun fallback(reason: String) {
            bmp.recycle()
            snapshotOffscreen(wv, w, h, target, clean) { err ->
                done(if (err == null) null else "$reason (intento ${attempt + 1}); $err")
            }
        }
        try {
            PixelCopy.request(
                window,
                Rect(loc[0], loc[1], loc[0] + w, loc[1] + h),
                bmp,
                { result ->
                    if (result == PixelCopy.SUCCESS) save(bmp, target, clean) { err ->
                        if (err == null) done(null) else fallback("PixelCopy: $err")
                    } else if (!retry()) fallback("PixelCopy falló (código $result)")
                },
                handler
            )
        } catch (t: Throwable) {
            if (!retry()) fallback("PixelCopy: ${t.javaClass.simpleName} ${t.message}")
        }
    }

    private fun snapshotOffscreen(
        wv: WebView, w: Int, h: Int, target: File, clean: String, done: (String?) -> Unit
    ) {
        try {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            wv.draw(Canvas(bmp))
            save(bmp, target, clean, done)
        } catch (t: Throwable) {
            done("error de captura: ${t.javaClass.simpleName} ${t.message}")
        }
    }

    private fun save(bmp: Bitmap, target: File, clean: String, done: (String?) -> Unit) {
        try {
            if (isBlank(bmp)) {
                done("captura en blanco (limpieza: $clean)")
                return
            }
            FileOutputStream(target).use {
                check(bmp.compress(Bitmap.CompressFormat.JPEG, 90, it)) { "No se pudo comprimir el JPEG" }
            }
            done(null)
        } catch (t: Throwable) {
            done("error al guardar: ${t.message}")
        } finally {
            if (!bmp.isRecycled) bmp.recycle()
        }
    }

    /** Descarta capturas uniformes (WebView sin renderizar). */
    private fun isBlank(b: Bitmap): Boolean {
        var min = 255
        var max = 0
        for (yi in 1..20) for (xi in 1..20) {
            val p = b.getPixel(b.width * xi / 21, b.height * yi / 21)
            val l = (Color.red(p) * 3 + Color.green(p) * 6 + Color.blue(p)) / 10
            if (l < min) min = l
            if (l > max) max = l
        }
        return max - min < 8
    }
}
