package com.satellite.wallpaper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Servicio en primer plano (notificación mínima) que mantiene vivo el proceso con la app cerrada
 * y escucha el desbloqueo (USER_PRESENT), que no puede declararse en el manifiesto desde Android 8.
 */
class UnlockService : Service() {

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_USER_PRESENT) return
            WallpaperRepository.refresh(
                applicationContext,
                minAgeMs = WallpaperRepository.refreshIntervalMs(applicationContext),
                source = "desbloqueo"
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_ID, n)
            }
            WallpaperRepository.note(this, "servicio", "activo (primer plano)")
        } catch (t: Throwable) {
            WallpaperRepository.note(this, "servicio", "no pudo pasar a primer plano: ${t.javaClass.simpleName}")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Fondo de satélite", NotificationManager.IMPORTANCE_MIN)
            )
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this).setPriority(Notification.PRIORITY_MIN)
        }
        return builder
            .setSmallIcon(android.R.drawable.ic_menu_mapmode)
            .setContentTitle("Fondo de satélite activo")
            .setContentText("Se actualiza al desbloquear el teléfono")
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "satellite_unlock"
        private const val NOTIF_ID = 42

        /** Arranca el servicio; si el sistema no lo permite (segundo plano), se ignora. */
        fun start(context: Context) {
            val app = context.applicationContext
            val i = Intent(app, UnlockService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i) else app.startService(i)
            } catch (t: Throwable) {
                WallpaperRepository.note(app, "servicio", "no se pudo iniciar: ${t.javaClass.simpleName}")
            }
        }
    }
}
