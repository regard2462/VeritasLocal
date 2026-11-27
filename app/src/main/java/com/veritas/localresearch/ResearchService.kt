package com.veritas.localresearch

import android.Manifest
import com.veritas.localresearch.i18n.tr
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Vordergrunddienst fuer laufende Recherchen: haelt Prozess und CPU wach, zeigt die aktuelle Stufe in einer
 * Benachrichtigung mit Stopp-Knopf und meldet das Ergebnis, wenn die App nicht offen ist.
 */
class ResearchService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(EXTRA_TITLE).orEmpty()
        val text = intent?.getStringExtra(EXTRA_TEXT).orEmpty()
        when (intent?.action) {
            ACTION_START -> {
                ensureChannels(this)
                val n = progressNotification(title, text)
                ServiceCompat.startForeground(this, ID_PROGRESS, n,
                    if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
                running = true
                if (wakeLock == null) {
                    wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VeritasLocal:research")
                        .apply { setReferenceCounted(false); acquire(3 * 60 * 60 * 1000L) }
                }
            }
            ACTION_PROGRESS -> if (running && canNotify(this)) {
                getSystemService(NotificationManager::class.java).notify(ID_PROGRESS, progressNotification(title, text))
            }
            ACTION_STOP -> (application as VeritasApp).researchViewModel.stopResearch()
            ACTION_FINISH -> {
                notifyDone(this, title, text)
                // Ein neuer Lauf hat schon begonnen: Dienst bleibt
                if (intent.getIntExtra(EXTRA_GEN, -1) == (application as VeritasApp).generation.get()) shutdown()
            }
            else -> if (!running) shutdown(startId)
        }
        return START_NOT_STICKY
    }

    /** Mit [startId] endet der Dienst nur, wenn danach kein weiterer Startbefehl eingegangen ist. */
    private fun shutdown(startId: Int? = null) {
        if (startId != null && !stopSelfResult(startId)) return
        running = false
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (startId == null) stopSelf()
    }

    /** Ab Android 15 endet dataSync nach 6 Stunden je Tag: Lauf stoppen und Dienst sofort beenden. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        (application as VeritasApp).researchViewModel.stopResearch()
        shutdown()
    }

    override fun onDestroy() {
        running = false
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    private fun progressNotification(title: String, text: String): Notification {
        val stop = PendingIntent.getService(this, 1,
            Intent(this, ResearchService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_RUN)
            .setSmallIcon(R.drawable.ic_stat_veritas)
            .setContentTitle(title.ifBlank { tr("notif_running") })
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setProgress(0, 0, true)
            .setContentIntent(openApp(this))
            .addAction(0, tr("stop"), stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        const val ACTION_START = "veritas.START"
        const val ACTION_PROGRESS = "veritas.PROGRESS"
        const val ACTION_FINISH = "veritas.FINISH"
        const val ACTION_STOP = "veritas.STOP"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
        const val EXTRA_GEN = "gen"
        private const val CHANNEL_RUN = "research_running"
        private const val CHANNEL_DONE = "research_done"
        private const val ID_PROGRESS = 1
        private const val ID_DONE = 2

        @Volatile var running = false

        fun canNotify(c: Context) = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

        private fun openApp(c: Context): PendingIntent = PendingIntent.getActivity(c, 0,
            Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE)

        fun ensureChannels(c: Context) {
            val nm = c.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL_RUN, tr("ch_running"), NotificationManager.IMPORTANCE_LOW)
                .apply { setSound(null, null); enableVibration(false) })
            nm.createNotificationChannel(NotificationChannel(CHANNEL_DONE, tr("ch_done"), NotificationManager.IMPORTANCE_DEFAULT))
        }

        /** Ergebnis melden; im Vordergrund sieht man es ohnehin, die Meldung stoert dort aber nicht. */
        fun notifyDone(c: Context, title: String, text: String) {
            if (title.isBlank() || !canNotify(c)) return
            ensureChannels(c)
            val n = NotificationCompat.Builder(c, CHANNEL_DONE)
                .setSmallIcon(R.drawable.ic_stat_veritas)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(openApp(c))
                .build()
            c.getSystemService(NotificationManager::class.java).notify(ID_DONE, n)
        }
    }
}
