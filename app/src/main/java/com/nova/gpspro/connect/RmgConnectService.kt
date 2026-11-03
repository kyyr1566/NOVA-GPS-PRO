package com.nova.gpspro.connect

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.nova.gpspro.MainActivity
import com.nova.gpspro.NovaApp
import com.nova.gpspro.R

/**
 * Android 14–15 compliant Foreground Service keeping RMG CONNECT direct links, voice mixer,
 * and optional GPS sharing alive while the screen is locked or the app is in the background.
 *
 * Exposes an ongoing notification with 3 instant actions:
 *  1) Mute / Unmute Microphone
 *  2) Stop GPS Sharing
 *  3) End Session (Disconnect All)
 */
class RmgConnectService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var snapshotListener: RmgConnectManager.Listener? = null
    private var lastAppliedFgsType: Int = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        acquireSessionLocks()

        val app = application as? NovaApp ?: return
        val listener = RmgConnectManager.Listener { snap ->
            if (!snap.hasActiveSession) {
                stopForegroundCompat()
                stopSelf()
            } else {
                promoteOrUpdateForeground(app, snap)
            }
        }
        snapshotListener = listener
        app.rmgConnect.addListener(listener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as? NovaApp
        if (app == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_TOGGLE_MUTE -> {
                app.rmgConnect.toggleMicMuteFromNotification()
            }
            ACTION_STOP_LOCATION -> {
                app.rmgConnect.setLocationSharingEnabled(false)
            }
            ACTION_END_SESSION -> {
                app.rmgConnect.disconnectAllAndEndSession()
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
        }

        val snap = app.rmgConnect.snapshot
        if (!snap.hasActiveSession && intent?.action != ACTION_START_OR_UPDATE) {
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }

        promoteOrUpdateForeground(app, snap)
        return START_STICKY
    }

    /** Android 15 (API 35) foreground service timeout hook — clean shutdown instead of crash. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        val app = application as? NovaApp
        app?.rmgConnect?.disconnectAllAndEndSession()
        stopForegroundCompat()
        stopSelf(startId)
    }

    override fun onDestroy() {
        val app = application as? NovaApp
        snapshotListener?.let { app?.rmgConnect?.removeListener(it) }
        snapshotListener = null
        releaseSessionLocks()
        super.onDestroy()
    }

    private fun promoteOrUpdateForeground(app: NovaApp, snap: RmgSessionSnapshot) {
        val notif = buildOngoingNotification(snap)
        val canIncludeMic = app.rmgConnect.hasMicrophoneForegroundEligibility() &&
            (app.rmgConnect.isMainActivityStarted || (lastAppliedFgsType and android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) != 0)

        val desiredType = RmgPermissions.resolveForegroundServiceType(
            sdkInt = Build.VERSION.SDK_INT,
            hasLocationPerm = RmgPermissions.hasLocationPermission(this),
            isSharingLocation = snap.isSharingLocation,
            hasRecordAudioPerm = RmgPermissions.hasRecordAudioPermission(this),
            includeMicrophoneType = canIncludeMic
        )

        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notif, desiredType)
                lastAppliedFgsType = desiredType
            } else {
                startForeground(NOTIFICATION_ID, notif)
                lastAppliedFgsType = 0
            }
        } catch (_: SecurityException) {
            // Safe fallback on strict Android 14/15 OEM policies: fall back to connectedDevice only
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    val baseOnly = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                    startForeground(NOTIFICATION_ID, notif, baseOnly)
                    lastAppliedFgsType = baseOnly
                }
            } catch (_: Exception) {
                val nm = getSystemService(NotificationManager::class.java)
                nm?.notify(NOTIFICATION_ID, notif)
            }
        } catch (_: Exception) {}
    }

    private fun buildOngoingNotification(snap: RmgSessionSnapshot): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_RMG_CONNECT, true)
        }
        val contentPi = PendingIntent.getActivity(
            this, 100, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val count = snap.activeConnectedCount
        val title = if (count > 0) getString(R.string.rmg_connect_notif_title_fmt, count)
        else getString(R.string.rmg_connect_notif_title_standby)

        val micStatus = if (snap.isMicMuted) getString(R.string.rmg_connect_mic_muted)
        else getString(R.string.rmg_connect_mic_live)
        val gpsStatus = if (snap.isSharingLocation) getString(R.string.rmg_connect_gps_live)
        else getString(R.string.rmg_connect_gps_off)
        val body = getString(R.string.rmg_connect_notif_body_fmt, micStatus, gpsStatus)

        val muteLabel = if (snap.isMicMuted) getString(R.string.rmg_connect_unmute_now)
        else getString(R.string.rmg_connect_mute_now)

        val muteAction = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_rmg_connect),
            muteLabel,
            serviceActionPi(ACTION_TOGGLE_MUTE, 101)
        ).build()

        val stopGpsAction = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_rmg_connect),
            getString(R.string.rmg_connect_notif_stop_gps),
            serviceActionPi(ACTION_STOP_LOCATION, 102)
        ).build()

        val endAction = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_rmg_connect),
            getString(R.string.rmg_connect_notif_end),
            serviceActionPi(ACTION_END_SESSION, 103)
        ).build()

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_rmg_connect)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(contentPi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_CALL)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(muteAction)
            .addAction(stopGpsAction)
            .addAction(endAction)

        if (Build.VERSION.SDK_INT >= 31) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    private fun serviceActionPi(action: String, reqCode: Int): PendingIntent {
        val i = Intent(this, RmgConnectService::class.java).apply { this.action = action }
        return PendingIntent.getService(
            this, reqCode, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun ensureNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val ch = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.rmg_connect_notif_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        nm.createNotificationChannel(ch)
    }

    private fun acquireSessionLocks() {
        try {
            val pm = getSystemService(PowerManager::class.java)
            if (wakeLock == null && pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RMGGPSPro::ConnectSession").apply {
                    setReferenceCounted(false)
                    acquire(4 * 60 * 60 * 1000L) // bounded 4h safety cap
                }
            }
        } catch (_: Exception) {}

        try {
            val wm = applicationContext.getSystemService(WifiManager::class.java)
            if (wifiLock == null && wm != null) {
                val mode = WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                wifiLock = wm.createWifiLock(mode, "RMGGPSPro::WifiLowLatency").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (_: Exception) {}
    }

    private fun releaseSessionLocks() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {}
        wakeLock = null

        try {
            wifiLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {}
        wifiLock = null
    }

    private fun stopForegroundCompat() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {}
    }

    companion object {
        const val CHANNEL_ID = "rmg_connect_session_ch"
        const val NOTIFICATION_ID = 4921
        const val EXTRA_OPEN_RMG_CONNECT = "open_rmg_connect"

        const val ACTION_START_OR_UPDATE = "com.nova.gpspro.connect.START_OR_UPDATE"
        const val ACTION_TOGGLE_MUTE = "com.nova.gpspro.connect.TOGGLE_MUTE"
        const val ACTION_STOP_LOCATION = "com.nova.gpspro.connect.STOP_LOCATION"
        const val ACTION_END_SESSION = "com.nova.gpspro.connect.END_SESSION"

        fun startOrUpdate(ctx: Context) {
            val i = Intent(ctx, RmgConnectService::class.java).apply {
                action = ACTION_START_OR_UPDATE
            }
            try {
                ctx.startForegroundService(i)
            } catch (_: Exception) {
                // Android 12+ BackgroundServiceStartNotAllowedException fallback if already running
                try { ctx.startService(i) } catch (_: Exception) {}
            }
        }

        fun stop(ctx: Context) {
            try {
                ctx.stopService(Intent(ctx, RmgConnectService::class.java))
            } catch (_: Exception) {}
        }
    }
}
