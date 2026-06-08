package com.overdrive.app.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.overdrive.app.R
import com.overdrive.app.receiver.ProcessRevivalReceiver
import com.overdrive.app.receiver.ScreenOffReceiver
import com.overdrive.app.ui.MainActivity
import com.overdrive.app.ui.daemon.DaemonStartupManager

/**
 * Foreground service that keeps the app alive and monitors SCREEN_OFF events.
 * 
 * Features:
 * - START_STICKY to restart if killed
 * - PARTIAL_WAKE_LOCK to prevent CPU sleep
 * - Registers ScreenOffReceiver for daemon survival
 * - Starts daemons on service start
 */
class DaemonKeepaliveService : Service() {
    
    companion object {
        private const val TAG = "DaemonKeepalive"
        private const val NOTIFICATION_ID = 19876
        private const val CHANNEL_ID = "daemon_keepalive_channel"

        // Shared notification grouping. The three foreground services that
        // Overdrive runs (this one + LocationSidecarService + StatusOverlay
        // Service) each post their own ongoing notification — Android needs
        // the FGS notification to remain visible per service. Tagging them
        // all with the same group key, plus a 4th `setGroupSummary(true)`
        // notification posted by this service, collapses the four entries
        // into a single expandable shade row. The user sees one "Overdrive
        // Active" tile with the per-service lines available on tap-to-expand.
        //
        // The summary notification is *not* a foreground-service notification
        // — it's a plain ongoing notification posted via NotificationManager
        // .notify(). FGS notifs can't be the group summary on every Android
        // version; a separate summary side-steps that and survives any of
        // the three children temporarily disappearing (e.g. permission flap
        // killing LocationSidecarService).
        const val NOTIFICATION_GROUP_KEY = "com.overdrive.app.STATUS"
        private const val SUMMARY_NOTIFICATION_ID = 19875
        private const val SUMMARY_CHANNEL_ID = "overdrive_status_summary"

        fun start(context: Context) {
            val intent = Intent(context, DaemonKeepaliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DaemonKeepaliveService::class.java))
        }
    }
    
    private var wakeLock: PowerManager.WakeLock? = null
    private var screenOffReceiver: ScreenOffReceiver? = null
    
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service onCreate")

        createNotificationChannel()
        createSummaryChannel()
        startForegroundWithNotification()
        postOrUpdateSummary()
        acquireWakeLock()
        registerScreenOffReceiver()

        // Seed out-of-process revival watchdog. If this service was started
        // by the watchdog itself, this just re-arms the next alarm.
        try {
            ProcessRevivalReceiver.schedule(applicationContext)
        } catch (e: Exception) {
            Log.w(TAG, "ProcessRevivalReceiver.schedule failed: ${e.message}")
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "Service onStartCommand")

        // Skip daemon startup when a post-update launch is in progress.
        // MainActivity is the sole orchestrator after an install: it runs
        // UpdateLifecycle.hardResetDaemons (kills zombie daemons + watchdogs +
        // wipes lock files) and THEN calls DaemonStartupManager.initializeOnAppLaunch.
        // If we also fired startOnBoot here, two things would race:
        //   1. The bootStarted flag would block initializeOnAppLaunch's view of
        //      the singleton (different instance — but both schedule 45s tasks),
        //      and we'd end up doing work in the still-zombie environment.
        //   2. Daemons launched here would be killed seconds later by the
        //      hardReset sweep, then restarted again — pointless thrash and
        //      a real risk of overlapping camera handles on the AVMCamera HAL.
        val postUpdate = com.overdrive.app.updater.UpdateLifecycle
            .isPostUpdateLaunch(applicationContext, null)
        if (postUpdate) {
            Log.i(TAG, "Post-update launch — deferring daemon startup to MainActivity")
        } else {
            try {
                DaemonStartupManager.startOnBoot(applicationContext)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start daemons: ${e.message}")
            }
        }
        
        // Bring the status pill back if the process was restarted without the
        // Activity running (e.g. system killed the process, then Android
        // respawned this keepalive service via START_STICKY).
        try {
            com.overdrive.app.overlay.StatusOverlayService.startIfPermitted(applicationContext)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to kick status overlay: ${e.message}")
        }

        // Same for the RoadSense overlay: it must be visible whenever the feature is
        // enabled, NOT only after the user opens MainActivity (its sole other launch
        // path, onResume). The app process is killed/respawned across ACC cycles, so
        // without this an ACC-on with RoadSense already enabled would leave the overlay
        // absent until the user manually opened the app. Gated on the enabled flag
        // (forceReload — the daemon/web UI may have just toggled it cross-UID) so a
        // disabled feature stays silent. The overlay itself only renders daemon-
        // published state, so showing it early just yields the idle/scanning pill.
        try {
            if (com.overdrive.app.roadsense.config.RoadSenseConfig.snapshot(forceReload = true).enabled) {
                com.overdrive.app.roadsense.overlay.RoadSenseOverlayService.startIfPermitted(applicationContext)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to kick RoadSense overlay: ${e.message}")
        }
        
        // START_STICKY ensures service restarts if killed
        return START_STICKY
    }
    
    override fun onDestroy() {
        Log.i(TAG, "Service onDestroy")

        // Drop the group-summary so the user doesn't see a stale "Overdrive"
        // row after the keepalive service stops. The per-service FGS notifs
        // are auto-removed by the framework when their service stops; only
        // the summary (posted via NotificationManager.notify) needs an
        // explicit cancel.
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager?.cancel(SUMMARY_NOTIFICATION_ID)
        } catch (e: Exception) {
            Log.w(TAG, "cancel summary failed: ${e.message}")
        }

        unregisterScreenOffReceiver()
        releaseWakeLock()

        super.onDestroy()
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Daemon Keepalive",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps daemons running in background"
                setShowBadge(false)
            }
            
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
    
    private fun startForegroundWithNotification() {
        val notification = buildNotification()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10-13
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        
        Log.i(TAG, "Foreground service started")
    }
    
    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val title = getString(R.string.daemon_keepalive_notif_title)
        val text  = getString(R.string.daemon_keepalive_notif_text)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_sentry)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setGroup(NOTIFICATION_GROUP_KEY)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_sentry)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setGroup(NOTIFICATION_GROUP_KEY)
                .build()
        }
    }

    /**
     * Dedicated channel for the group-summary notification. Kept separate
     * from the daemon-keepalive channel so the user can mute the summary
     * row without losing the per-service FGS notifications underneath
     * (Android requires the FGS channel to stay user-visible). Importance
     * mirrors the children — LOW means silent, no badge, but sticky.
     */
    private fun createSummaryChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                SUMMARY_CHANNEL_ID,
                "Overdrive Status",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Combined status row for Overdrive's background services"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * Post (or refresh) the group-summary notification. This is what the
     * user actually sees collapsed in the shade. The three FGS children
     * (this service, LocationSidecarService, StatusOverlayService) all
     * carry the same `setGroup(NOTIFICATION_GROUP_KEY)` so Android pairs
     * them under this summary on API 20+ (every supported version of this
     * app — minSdk is 28).
     *
     * The summary is intentionally generic. We don't enumerate the three
     * children inline because the keepalive service can outlive the other
     * two:
     *   - On first launch, this summary is posted from Application.onCreate
     *     before MainActivity has had a chance to start LocationSidecar /
     *     StatusOverlay — naming them here would lie for a few hundred ms.
     *   - After an OOM kill with no Activity, START_STICKY only respawns
     *     this service; LocationSidecar (kicked from MainActivity) stays
     *     dead until the next app launch. A static "Location: GPS tracking"
     *     line would lie for the entire respawn window.
     * Tap-to-expand still shows whichever per-service FGS notifications
     * are actually live, so the user sees the truth on demand.
     *
     * Idempotent — calling it again replaces the existing summary.
     */
    private fun postOrUpdateSummary() {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, SUMMARY_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val notification = builder
            .setContentTitle(getString(R.string.overdrive_status_summary_title))
            .setContentText(getString(R.string.overdrive_status_summary_text))
            .setSmallIcon(R.drawable.ic_sentry)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setGroup(NOTIFICATION_GROUP_KEY)
            .setGroupSummary(true)
            .build()
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager?.notify(SUMMARY_NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.w(TAG, "postOrUpdateSummary failed: ${e.message}")
        }
    }
    
    private fun acquireWakeLock() {
        if (wakeLock != null) return
        
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "Overdrive:DaemonKeepalive"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.i(TAG, "WakeLock acquired")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire WakeLock: ${e.message}")
        }
    }
    
    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.i(TAG, "WakeLock released")
            }
        }
        wakeLock = null
    }
    
    private fun registerScreenOffReceiver() {
        if (screenOffReceiver != null) return
        
        screenOffReceiver = ScreenOffReceiver.register(this)
        Log.i(TAG, "ScreenOffReceiver registered in service")
    }
    
    private fun unregisterScreenOffReceiver() {
        screenOffReceiver?.let {
            ScreenOffReceiver.unregister(this, it)
            screenOffReceiver = null
        }
    }
}
