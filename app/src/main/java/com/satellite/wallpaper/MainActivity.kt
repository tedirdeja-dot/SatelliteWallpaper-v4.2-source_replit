package com.satellite.wallpaper

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.CheckBox
import android.widget.ProgressBar
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var preview: CropPreviewView
    private lateinit var button: Button
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var changeView: Button
    private lateinit var grantOverlay: Button
    private lateinit var lockStatic: CheckBox
    private var leavingActivity = false

    private val cacheReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != SatelliteWallpaperService.ACTION_CACHE_UPDATED || !isActivityUsable()) return
            runCatching { WallpaperRepository.loadCached(this@MainActivity) }
                .getOrNull()
                ?.let { showImage(it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applySafeBottomInset()

        preview = findViewById(R.id.crop_preview)
        button = findViewById(R.id.set_wallpaper)
        progress = findViewById(R.id.progress)
        status = findViewById(R.id.status)
        preview.onStateChanged = { WallpaperRepository.saveState(this, it) }
        button.setOnClickListener { openConfirm() }

        changeView = findViewById(R.id.change_view)
        grantOverlay = findViewById(R.id.grant_overlay)
        lockStatic = findViewById(R.id.lock_static)

        changeView.setOnClickListener { openPicker() }
        grantOverlay.setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
        findViewById<Button>(R.id.grant_battery).setOnClickListener {
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                )
            }
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 2001)
        }
        UnlockService.start(this) // mantiene la app viva y escucha el desbloqueo aunque esté cerrada
        lockStatic.isChecked = WallpaperRepository.lockEnabled(this)
        lockStatic.setOnCheckedChangeListener { _, checked ->
            WallpaperRepository.setLockEnabled(this, checked)
            if (checked) WallpaperRepository.applyLockScreenAsync(this)
        }

        promptIntervalThenContinue()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(SatelliteWallpaperService.ACTION_CACHE_UPDATED)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(cacheReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(cacheReceiver, filter)
        }
    }

    override fun onStop() {
        runCatching { unregisterReceiver(cacheReceiver) }
        super.onStop()
    }

    private fun versionLabel(): String = runCatching {
        val pi = packageManager.getPackageInfo(packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
        "v${pi.versionName} (build $code)"
    }.getOrDefault("v?")

    override fun onResume() {
        super.onResume()
        updateVersionInfo()
        grantOverlay.visibility =
            if (Settings.canDrawOverlays(this)) View.GONE else View.VISIBLE
        val ignoring = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        findViewById<View>(R.id.grant_battery).visibility = if (ignoring) View.GONE else View.VISIBLE
        val log = WallpaperRepository.attemptLog(this)
        findViewById<TextView>(R.id.attempt_log).text =
            if (log.isBlank()) "${versionLabel()} · sin intentos en segundo plano todavía." else "${versionLabel()} · últimos intentos:\n$log"
    }

    private fun openPicker() {
        startActivityForResult(Intent(this, PickViewActivity::class.java), REQ_PICK)
    }

    private fun updateVersionInfo() {
        findViewById<TextView>(R.id.version).text =
            "Versión ${versionLabel()} · actualización al desbloquear: cada ${WallpaperRepository.refreshIntervalMinutes(this)} min"
    }

    private fun promptIntervalThenContinue() {
        val values = WallpaperRepository.INTERVAL_OPTIONS_MINUTES
        val selectedMinutes = WallpaperRepository.refreshIntervalMinutes(this)
        val items = values.map { getString(R.string.interval_option, it) }.toTypedArray()
        var selectedIndex = values.indexOf(selectedMinutes).coerceAtLeast(0)

        fun continueSetup() {
            if (!WallpaperRepository.hasSelection(this)) {
                openPicker()
            } else {
                showCachedImage()
            }
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.interval_title)
            .setMessage(R.string.interval_message)
            .setSingleChoiceItems(items, selectedIndex) { _, which -> selectedIndex = which }
            .setPositiveButton(R.string.interval_save) { _, _ ->
                WallpaperRepository.setRefreshIntervalMinutes(this, values[selectedIndex])
                updateVersionInfo()
                continueSetup()
            }
            .setNegativeButton(R.string.interval_keep) { _, _ -> continueSetup() }
            .setOnCancelListener { continueSetup() }
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (!isActivityUsable()) return
        if (requestCode == REQ_CONFIRM) {
            if (resultCode == RESULT_OK) saveAndOpenWallpaperPicker()
            return
        }
        if (requestCode != REQ_PICK) return
        showCachedImage()
    }

    private fun showCachedImage() {
        val cached = runCatching { WallpaperRepository.loadCached(this) }.getOrNull()
        if (cached != null) {
            showImage(cached)
        } else {
            showWaitingForUnlock()
        }
    }

    override fun onDestroy() {
        leavingActivity = true
        super.onDestroy()
    }

    private fun applySafeBottomInset() {
        val root = findViewById<View>(R.id.root_container)
        val basePadding = root.paddingBottom
        val extra = (24 * resources.displayMetrics.density).toInt()
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bottom = if (Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(WindowInsets.Type.systemBars()).bottom
            } else {
                @Suppress("DEPRECATION") insets.systemWindowInsetBottom
            }
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, basePadding + bottom + extra)
            insets
        }
        root.requestApplyInsets()
    }

    private fun showWaitingForUnlock() {
        progress.visibility = View.GONE
        status.visibility = View.VISIBLE
        status.text = getString(
            R.string.waiting_for_unlock,
            WallpaperRepository.refreshIntervalMinutes(this)
        )
        button.isEnabled = false
    }

    /** Muestra la imagen a pantalla completa con Aceptar / Cancelar antes de aplicar el fondo. */
    private fun openConfirm() {
        WallpaperRepository.saveState(this, preview.state)
        startActivityForResult(Intent(this, ConfirmWallpaperActivity::class.java), REQ_CONFIRM)
    }

    private fun showImage(bitmap: Bitmap) {
        progress.visibility = View.GONE
        status.visibility = View.GONE
        preview.setBitmap(
            bitmap,
            WallpaperRepository.loadState(this),
            WallpaperRepository.updatedAt(this)
        )
        button.isEnabled = true
    }

    private fun isActivityUsable(): Boolean = !(leavingActivity || isFinishing || isDestroyed)

    private fun saveAndOpenWallpaperPicker() {
        WallpaperRepository.saveState(this, preview.state)
        if (WallpaperRepository.lockEnabled(this)) WallpaperRepository.applyLockScreenAsync(this)
        val component = ComponentName(this, SatelliteWallpaperService::class.java)
        val intent = Intent("android.service.wallpaper.CHANGE_LIVE_WALLPAPER").apply {
            putExtra("android.service.wallpaper.extra.LIVE_WALLPAPER_COMPONENT", component)
        }
        try {
            startActivity(intent)
        } catch (t: Throwable) {
            startActivity(Intent("android.service.wallpaper.LIVE_WALLPAPER_CHOOSER"))
        }
    }

    companion object {
        private const val REQ_PICK = 1001
        private const val REQ_CONFIRM = 1002
    }
}
