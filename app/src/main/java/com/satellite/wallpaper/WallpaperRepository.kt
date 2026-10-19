package com.satellite.wallpaper

import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.system.Os
import android.util.Log
import android.view.ViewGroup
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object WallpaperRepository {

    private const val TAG = "WallpaperRepository"
    private const val CACHE_NAME = "satellite_cache.jpg"
    private const val TEMP_DIR_NAME = "zoom_earth_downloads"
    private const val PREFS = "satellite_wallpaper"
    private const val KEY_ZOOM = "zoom"
    private const val KEY_OFFSET_X = "offset_x"
    private const val KEY_OFFSET_Y = "offset_y"
    private const val KEY_UPDATED_AT = "updated_at"
    private const val KEY_LAST_ATTEMPT_AT = "last_attempt_at"
    private const val KEY_VIEW_URL = "view_url"
    private const val KEY_LOCK_STATIC = "lock_static"
    private const val KEY_REFRESH_INTERVAL_MINUTES = "refresh_interval_minutes"
    private const val KEY_LOG = "attempt_log"

    /** Si una captura no termina en este tiempo se libera el bloqueo para no quedar atascados. */
    private const val WATCHDOG_MS = 300_000L

    const val DEFAULT_INTERVAL_MINUTES = 30
    val INTERVAL_OPTIONS_MINUTES = intArrayOf(15, 30, 60)

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private val resultLock = Any()
    @Volatile private var runToken = 0L

    /** Motivo del último fallo de captura (null si la última fue correcta). */
    @Volatile
    var lastError: String? = null
        private set

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, 0)

    fun cacheFile(context: Context): File = File(context.filesDir, CACHE_NAME)

    private fun temporaryImageFile(context: Context, attemptToken: Long): File =
        File(File(context.cacheDir, TEMP_DIR_NAME), "$CACHE_NAME.$attemptToken.part")

    // ---------- Selección de vista (recordada) ----------

    /** true si el usuario ya eligió una vista de Zoom Earth. */
    fun hasSelection(context: Context): Boolean = prefs(context).contains(KEY_VIEW_URL)

    /** URL de Zoom Earth elegida por el usuario (o la de por defecto). */
    fun viewUrl(context: Context): String =
        prefs(context).getString(KEY_VIEW_URL, null) ?: ZoomEarthCapture.PAGE_URL

    /** Guarda la vista elegida. Si cambia, invalida la imagen en caché y el encuadre. */
    fun saveViewUrl(context: Context, url: String) {
        val p = prefs(context)
        val changed = p.getString(KEY_VIEW_URL, null) != url
        val e = p.edit().putString(KEY_VIEW_URL, url)
        if (changed) {
            e.remove(KEY_UPDATED_AT).remove(KEY_ZOOM).remove(KEY_OFFSET_X).remove(KEY_OFFSET_Y)
            runCatching { cacheFile(context).delete() }
        }
        e.apply()
    }

    fun lockEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_LOCK_STATIC, true)

    fun setLockEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_LOCK_STATIC, value).apply()
    }

    fun refreshIntervalMinutes(context: Context): Int {
        val value = prefs(context).getInt(KEY_REFRESH_INTERVAL_MINUTES, DEFAULT_INTERVAL_MINUTES)
        return value.takeIf { it in INTERVAL_OPTIONS_MINUTES } ?: DEFAULT_INTERVAL_MINUTES
    }

    fun setRefreshIntervalMinutes(context: Context, minutes: Int) {
        require(minutes in INTERVAL_OPTIONS_MINUTES) { "El intervalo debe ser 15, 30 o 60 minutos" }
        prefs(context).edit().putInt(KEY_REFRESH_INTERVAL_MINUTES, minutes).apply()
    }

    fun refreshIntervalMs(context: Context): Long =
        refreshIntervalMinutes(context) * 60_000L

    // ---------- Caché / encuadre ----------

    fun updatedAt(context: Context): Long {
        val v = prefs(context).getLong(KEY_UPDATED_AT, 0L)
        return if (v > 0) v else cacheFile(context).lastModified()
    }

    private fun lastAttemptAt(context: Context): Long =
        prefs(context).getLong(KEY_LAST_ATTEMPT_AT, updatedAt(context))

    fun loadCached(context: Context): Bitmap? {
        val f = cacheFile(context).takeIf { it.exists() } ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeFile(f.absolutePath, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            runCatching { f.delete() }
            return null
        }
        val maxEdge = ScreenSize.capture(context).maxDimension
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxEdge) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = runCatching { BitmapFactory.decodeFile(f.absolutePath, options) }.getOrNull()
            ?.takeIf { it.width > 0 && it.height > 0 }
        if (bmp != null) return bmp
        // Solo se borra si el archivo está realmente corrupto. Un fallo de decodificación por
        // memoria u otra causa transitoria NO debe destruir la última imagen buena.
        return null
    }

    /** true si el sistema indica una red con acceso a Internet validado. */
    fun isOnline(context: Context): Boolean = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        caps != null &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    } catch (t: Throwable) {
        true // si no se puede comprobar, se intenta igualmente
    }

    fun loadState(context: Context): CropState {
        val p = prefs(context)
        return CropState(
            p.getFloat(KEY_ZOOM, 1f),
            p.getFloat(KEY_OFFSET_X, 0f),
            p.getFloat(KEY_OFFSET_Y, 0f)
        ).clamped()
    }

    fun saveState(context: Context, state: CropState) {
        val s = state.clamped()
        prefs(context).edit()
            .putFloat(KEY_ZOOM, s.zoom)
            .putFloat(KEY_OFFSET_X, s.offsetX)
            .putFloat(KEY_OFFSET_Y, s.offsetY)
            .apply()
    }

    // ---------- Registro de intentos (diagnóstico) ----------

    @Synchronized
    fun note(context: Context, source: String, text: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val old = prefs(context).getString(KEY_LOG, "")!!.split("\n").filter { it.isNotBlank() }
        val log = (old + "$time · $source · $text").takeLast(10).joinToString("\n")
        prefs(context).edit().putString(KEY_LOG, log).apply()
        Log.i(TAG, "$source: $text")
    }

    /** Últimos intentos de descarga (más reciente al final). */
    fun attemptLog(context: Context): String = prefs(context).getString(KEY_LOG, "")!!

    // ---------- Descarga ----------

    /**
     * Captura la vista elegida de Zoom Earth y actualiza la caché (y la pantalla de bloqueo).
     * Misma estructura que la APK original: refresh() hace el trabajo, al terminar avisa con el
     * broadcast CACHE_UPDATED y el fondo animado vuelve a pintarse.
     * force = false: no hace nada si la caché tiene menos de [minAgeMs].
     * host != null: captura con el WebView visible dentro de ese contenedor (modo fiable).
     * host == null: captura en segundo plano (cascada de estrategias).
     * callback(true) solo si se generó una imagen nueva; se invoca en el hilo principal.
     */
    fun refresh(
        context: Context,
        force: Boolean = false,
        host: ViewGroup? = null,
        minAgeMs: Long = refreshIntervalMs(context),
        source: String = "manual",
        callback: (Boolean) -> Unit = {}
    ) {
        val app = context.applicationContext
        main.post {
            val lastAttempt = lastAttemptAt(app)
            val ageMs = if (lastAttempt > 0L) {
                (System.currentTimeMillis() - lastAttempt).coerceAtLeast(0L)
            } else {
                Long.MAX_VALUE
            }
            if (!force && lastAttempt > 0L && ageMs < minAgeMs) {
                note(app, source, "omitido: último intento hace ${ageMs / 60_000} min (mínimo ${minAgeMs / 60_000} min)")
                callback(false); return@post
            }
            if (!isOnline(app)) {
                note(app, source, "omitido: sin conexión; se mantiene la imagen actual")
                callback(false); return@post
            }
            if (!running.compareAndSet(false, true)) {
                note(app, source, "omitido: ya hay una descarga en curso")
                callback(false); return@post
            }
            val token = System.nanoTime()
            runToken = token
            prefs(app).edit().putLong(KEY_LAST_ATTEMPT_AT, System.currentTimeMillis()).apply()
            val startedAt = SystemClock.elapsedRealtime()
            note(
                app, source,
                "iniciando captura (${if (host != null) "WebView visible" else "segundo plano"}; intervalo ${refreshIntervalMinutes(app)} min)"
            )

            // Red de seguridad: si la captura no termina nunca, se libera el bloqueo.
            main.postDelayed({
                val expired = synchronized(resultLock) {
                    if (runToken == token) {
                        runToken = 0L
                        running.set(false)
                        true
                    } else {
                        false
                    }
                }
                if (expired) {
                    note(app, source, "FALLO: la captura no terminó en ${WATCHDOG_MS / 1000} s; se libera el bloqueo")
                    callback(false)
                }
            }, WATCHDOG_MS)

            val tmp = temporaryImageFile(app, token)
            runCatching {
                tmp.parentFile?.mkdirs()
                tmp.delete()
            } // cada intento empieza limpio dentro de la carpeta temporal
            val done: (String?) -> Unit = { captureError ->
                executor.execute {
                    var error: String? = null
                    var ok = false
                    val accepted = synchronized(resultLock) {
                        if (runToken != token) {
                            false
                        } else {
                            // Si la red se perdió durante la captura, la imagen puede estar incompleta: se descarta.
                            error = captureError
                                ?: if (!isOnline(app)) "se perdió la conexión durante la captura" else null
                            ok = error == null && commit(app, tmp)
                            if (!ok) runCatching { tmp.delete() }
                            runToken = 0L
                            running.set(false)
                            true
                        }
                    }
                    if (!accepted) {
                        runCatching { tmp.delete() }
                        return@execute
                    }
                    main.post {
                        lastError = if (ok) null else (error ?: "la imagen capturada no es válida")
                        note(
                            app, source,
                            if (ok) "OK, imagen actualizada en ${(SystemClock.elapsedRealtime() - startedAt) / 1000} s [${ZoomEarthCapture.lastMode}]"
                            else "FALLO: $lastError (se mantiene la imagen anterior)"
                        )
                        callback(ok)
                        if (ok) {
                            // Primero se repinta el fondo animado (como la APK original); la pantalla de bloqueo estática después.
                            app.sendBroadcast(
                                Intent(SatelliteWallpaperService.ACTION_CACHE_UPDATED)
                                    .setPackage(app.packageName)
                            )
                            if (lockEnabled(app)) executor.execute { applyLockScreen(app) }
                        }
                    }
                }
            }
            try {
                if (host != null) ZoomEarthCapture.capture(app, tmp, host, viewUrl(app)) { done(it) }
                else ZoomEarthCapture.captureBackground(app, tmp, viewUrl(app)) { done(it) }
            } catch (t: Throwable) {
                done("excepción al iniciar la captura: ${t.javaClass.simpleName} ${t.message}")
            }
        }
    }

    /**
     * Pinta la imagen encuadrada (con fecha y hora) como fondo ESTÁTICO de la pantalla de bloqueo.
     * Garantiza que el bloqueo se actualice aunque el launcher no muestre el fondo animado ahí.
     */
    fun applyLockScreen(context: Context) {
        if (Build.VERSION.SDK_INT < 24) return
        val app = context.applicationContext
        val src = loadCached(app) ?: return
        val captureSize = ScreenSize.capture(app)
        val sw = captureSize.width
        val sh = captureSize.height
        val out = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        try {
            val c = Canvas(out)
            c.drawColor(0xFF10212B.toInt())
            CropMath.draw(c, src, out.width, out.height, loadState(app))
            CropMath.drawUpdatedAt(c, updatedAt(app), out.width, out.height)
            WallpaperManager.getInstance(app)
                .setBitmap(out, null, true, WallpaperManager.FLAG_LOCK)
        } catch (t: Throwable) {
            Log.w(TAG, "applyLockScreen", t)
        } finally {
            src.recycle()
            out.recycle()
        }
    }

    fun applyLockScreenAsync(context: Context) {
        val app = context.applicationContext
        executor.execute { applyLockScreen(app) }
    }

    private fun commit(app: Context, tmp: File): Boolean = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(tmp.absolutePath, o)
        check(o.outWidth > 0 && o.outHeight > 0) { "La imagen capturada no tiene un tamaño válido" }
        val dest = cacheFile(app)
        check(tmp.isFile && tmp.length() > 0L) { "La descarga temporal está vacía" }
        // Ambos directorios están en el almacenamiento privado de la app; rename reemplaza la
        // imagen anterior de forma atómica, sin dejar una caché a medio copiar.
        Os.rename(tmp.absolutePath, dest.absolutePath)
        prefs(app).edit().putLong(KEY_UPDATED_AT, System.currentTimeMillis()).apply()
        true
    } catch (t: Throwable) {
        runCatching { tmp.delete() }
        false
    }
}
