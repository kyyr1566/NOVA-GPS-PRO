package com.nova.gpspro.ptt

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.nova.gpspro.MainActivity
import com.nova.gpspro.NovaApp
import com.nova.gpspro.R
import com.nova.gpspro.settings.SettingsRepository
import java.util.Locale

/**
 * Android 11–16 compliant ForegroundService keeping the Walkie-Talkie session alive across
 * Home button press, app switching, and screen lock, with immediate clean shutdown on session end.
 */
class PttService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var lastAppliedFgsType: Int = 0
    @Volatile private var intentionalStop: Boolean = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        acquireLocks()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as? NovaApp
        if (app == null) {
            intentionalStop = true
            satisfyForegroundContractBeforeStop()
            dismissAndStopSelf()
            return START_NOT_STICKY
        }

        val repo = app.ptt
        when (intent?.action) {
            ACTION_TOGGLE_MUTE -> {
                repo.toggleLocalAudioMutedFromNotification()
            }
            ACTION_END_SESSION -> {
                intentionalStop = true
                satisfyForegroundContractBeforeStop()
                repo.endSessionByUser()
                dismissAndStopSelf()
                return START_NOT_STICKY
            }
        }

        val uiState = repo.state.value
        if (uiState.phase != PttPhase.IN_SESSION) {
            intentionalStop = true
            satisfyForegroundContractBeforeStop()
            dismissAndStopSelf()
            return START_NOT_STICKY
        }

        promoteOrUpdateForeground(repo, uiState)
        return START_NOT_STICKY
    }

    /**
     * Guarantees Android 8–16's startForegroundService() -> startForeground() contract is always
     * fulfilled even if the session was cancelled milliseconds before onStartCommand executed,
     * preventing ForegroundServiceDidNotStartInTimeException crashes.
     */
    private fun satisfyForegroundContractBeforeStop() {
        try {
            val locCtx = localizedContext()
            ensureChannel(locCtx)
            val minimal = Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_ptt_radio)
                .setContentTitle(locCtx.getString(R.string.ptt_notif_title))
                .setOngoing(false)
                .build()
            startForeground(NOTIFICATION_ID, minimal, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (t: Throwable) {
            try {
                val locCtx = localizedContext()
                ensureChannel(locCtx)
                val minimal = Notification.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_ptt_radio)
                    .setContentTitle(locCtx.getString(R.string.ptt_notif_title))
                    .build()
                startForeground(NOTIFICATION_ID, minimal)
            } catch (inner: Throwable) {
                PttDiagnostics.warn("PttService", "satisfyForegroundContract fallback", inner)
            }
        }
    }

    /** Android 15+ (SDK 35) FGS timeout hook: prevents crash and tears down session cleanly so no ghost state remains. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        intentionalStop = true
        (application as? NovaApp)?.ptt?.onSystemServiceForcedTimeout()
        dismissAndStopSelf()
    }

    override fun onDestroy() {
        val app = application as? NovaApp
        // If Android forcefully killed the service while session was active, clean up session so no fake connected state remains
        if (!intentionalStop && app?.ptt?.state?.value?.phase == PttPhase.IN_SESSION) {
            app.ptt.onSystemServiceForcedTimeout()
        }
        releaseLocks()
        cancelNotificationExplicitly()
        super.onDestroy()
    }

    private fun promoteOrUpdateForeground(repo: PttRepository, state: PttUiState) {
        val locCtx = localizedContext()
        ensureChannel(locCtx)
        val notif = buildOngoingNotification(locCtx, state)

        val micAlreadyRunning = (lastAppliedFgsType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) != 0
        val micEligible = repo.isAppInForeground || repo.micForegroundEligibleInSession || micAlreadyRunning
        val fgsMask = PttPermissions.resolveForegroundServiceType(
            sdkInt = Build.VERSION.SDK_INT,
            hasRecordAudioPerm = PttPermissions.hasRecordAudio(this),
            isAppForegroundOrAlreadyMicEligible = micEligible
        )

        try {
            startForeground(NOTIFICATION_ID, notif, fgsMask)
            lastAppliedFgsType = fgsMask
        } catch (_: SecurityException) {
            // Strict fallback to connectedDevice if OEM restricts microphone FGS promotion
            try {
                startForeground(NOTIFICATION_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
                lastAppliedFgsType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } catch (_: Throwable) {
                getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notif)
            }
        } catch (_: Throwable) {}
    }

    private fun buildOngoingNotification(locCtx: Context, state: PttUiState): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_PTT, true)
        }
        val contentPi = PendingIntent.getActivity(
            this,
            201,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val currentStatusText = when {
            state.localAudioMuted -> locCtx.getString(R.string.ptt_notif_state_muted)
            state.buttonState == PttButtonVisualState.SELF_SPEAKING_GREEN -> locCtx.getString(R.string.ptt_btn_you_speaking)
            state.buttonState == PttButtonVisualState.REMOTE_SPEAKING_AMBER && !state.activeSpeakerName.isNullOrBlank() ->
                locCtx.getString(R.string.ptt_btn_other_speaking_fmt, state.activeSpeakerName)
            state.isHost && state.connectedCount <= 1 -> locCtx.getString(R.string.ptt_session_ready_waiting)
            else -> locCtx.getString(R.string.ptt_notif_state_ready)
        }

        val bodyText = locCtx.getString(
            R.string.ptt_notif_body_fmt,
            state.channelCode.ifBlank { "—" },
            state.connectedCount,
            currentStatusText
        )

        val muteBtnLabel = if (state.localAudioMuted) {
            locCtx.getString(R.string.ptt_unmute_all_audio)
        } else {
            locCtx.getString(R.string.ptt_mute_all_audio)
        }

        val muteAction = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_ptt_radio),
            muteBtnLabel,
            actionPendingIntent(ACTION_TOGGLE_MUTE, 202)
        ).build()

        val endAction = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_ptt_radio),
            locCtx.getString(R.string.ptt_end_session),
            actionPendingIntent(ACTION_END_SESSION, 203)
        ).build()

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ptt_radio)
            .setContentTitle(locCtx.getString(R.string.ptt_notif_title))
            .setContentText(bodyText)
            .setStyle(Notification.BigTextStyle().bigText(bodyText))
            .setContentIntent(contentPi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_CALL)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(muteAction)
            .addAction(endAction)

        if (Build.VERSION.SDK_INT >= 31) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    private fun actionPendingIntent(action: String, reqCode: Int): PendingIntent {
        val intent = Intent(this, PttService::class.java).apply { this.action = action }
        return PendingIntent.getService(
            this,
            reqCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun ensureChannel(locCtx: Context) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val ch = NotificationChannel(
            CHANNEL_ID,
            locCtx.getString(R.string.ptt_notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        nm.createNotificationChannel(ch)
    }

    /** Resolves resources in the app's currently selected language (Arabic / English) immediately. */
    private fun localizedContext(): Context {
        val lang = SettingsRepository.peekLanguage(this)
        val locale = Locale.forLanguageTag(lang)
        val cfg = Configuration(resources.configuration)
        cfg.setLocale(locale)
        cfg.setLayoutDirection(locale)
        return createConfigurationContext(cfg)
    }

    private fun dismissAndStopSelf() {
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Throwable) {}
        cancelNotificationExplicitly()
        stopSelf()
    }

    private fun cancelNotificationExplicitly() {
        try {
            getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        } catch (_: Throwable) {}
    }

    private fun acquireLocks() {
        try {
            val pm = getSystemService(PowerManager::class.java)
            if (wakeLock == null && pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RMGGPSPro::PttSessionLock").apply {
                    setReferenceCounted(false)
                    acquire(3 * 60 * 60 * 1000L)
                }
            }
        } catch (_: Throwable) {}

        try {
            val wm = applicationContext.getSystemService(WifiManager::class.java)
            if (wifiLock == null && wm != null) {
                @Suppress("DEPRECATION")
                val mode = if (Build.VERSION.SDK_INT >= 29) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wm.createWifiLock(mode, "RMGGPSPro::PttWifiLowLatency").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (_: Throwable) {}
    }

    private fun releaseLocks() {
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Throwable) {}
        wakeLock = null
        try { wifiLock?.let { if (it.isHeld) it.release() } } catch (_: Throwable) {}
        wifiLock = null
    }

    companion object {
        const val CHANNEL_ID = "rmg_ptt_active_channel"
        const val NOTIFICATION_ID = 4932
        const val EXTRA_OPEN_PTT = "open_walkie_talkie"

        const val ACTION_START_OR_UPDATE = "com.nova.gpspro.ptt.START_OR_UPDATE"
        const val ACTION_TOGGLE_MUTE = "com.nova.gpspro.ptt.TOGGLE_MUTE"
        const val ACTION_END_SESSION = "com.nova.gpspro.ptt.END_SESSION"

        fun startOrUpdate(ctx: Context) {
            val app = ctx.applicationContext as? NovaApp
            if (app != null && app.ptt.state.value.phase != PttPhase.IN_SESSION) {
                PttDiagnostics.info("PttService", "Skipping startOrUpdate because phase != IN_SESSION")
                return
            }
            val i = Intent(ctx, PttService::class.java).apply {
                action = ACTION_START_OR_UPDATE
            }
            try {
                ctx.startForegroundService(i)
            } catch (se: SecurityException) {
                PttDiagnostics.error("PttService", "SecurityException starting foreground service", se)
            } catch (ise: IllegalStateException) {
                PttDiagnostics.warn("PttService", "IllegalStateException starting foreground service (app backgrounded)", ise)
            } catch (t: Throwable) {
                PttDiagnostics.error("PttService", "Unexpected error starting foreground service", t)
            }
        }

        fun syncWithState(ctx: Context, state: PttUiState) {
            if (state.phase == PttPhase.IN_SESSION) startOrUpdate(ctx) else stop(ctx)
        }

        fun stop(ctx: Context) {
            try {
                ctx.stopService(Intent(ctx, PttService::class.java))
            } catch (_: Throwable) {}
            try {
                ctx.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
            } catch (_: Throwable) {}
        }
    }
}
