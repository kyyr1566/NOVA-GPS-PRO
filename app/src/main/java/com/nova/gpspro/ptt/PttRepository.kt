package com.nova.gpspro.ptt

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

enum class PttPhase {
    DISCONNECTED,
    SCANNING,
    CONNECTING,
    WAITING_APPROVAL,
    IN_SESSION
}

enum class PttErrorCode {
    NONE,
    AUTH_FAILED,
    REJECTED_BY_HOST,
    CONNECT_TIMEOUT,
    P2P_UNSUPPORTED,
    WIFI_DISABLED,
    LOCATION_SERVICE_OFF,
    PERMISSION_MISSING
}

data class PendingJoinApproval(
    val requestId: String,
    val participantId: String,
    val displayName: String,
    val deviceFingerprint: String
)

data class LocalHotspotCredentials(
    val ssid: String,
    val passphrase: String
)

data class PttUiState(
    val phase: PttPhase = PttPhase.DISCONNECTED,
    val isHost: Boolean = false,
    val displayName: String = "",
    val channelCode: String = "",
    val talkMode: TalkMode = TalkMode.HOLD_TO_TALK,
    val rogerBeepEnabled: Boolean = true,
    val buttonState: PttButtonVisualState = PttButtonVisualState.IDLE_BLUE,
    val activeSpeakerId: String? = null,
    val activeSpeakerName: String? = null,
    val negotiatedCodec: AudioCodecType = AudioCodecType.ADPCM_16K,
    val localAudioMuted: Boolean = false,
    val discoveredSessions: List<DiscoveredPttSession> = emptyList(),
    val pendingApprovals: List<PendingJoinApproval> = emptyList(),
    val participants: List<PttParticipant> = emptyList(),
    val connectedCount: Int = 0,
    val hotspotInfo: LocalHotspotCredentials? = null,
    val showSessionEndedBanner: Boolean = false,
    val errorCode: PttErrorCode = PttErrorCode.NONE
) {
    val isActiveSessionOrConnecting: Boolean
        get() = phase == PttPhase.CONNECTING || phase == PttPhase.WAITING_APPROVAL || phase == PttPhase.IN_SESSION
}

interface PttServiceBridge {
    fun startOrUpdateService()
    fun stopService()
}

/**
 * Application-scoped repository owning the entire Walkie-Talkie lifecycle via thread-safe [StateFlow].
 * Survives Activity recreation across Arabic/English language switches and Light/Dark theme toggles
 * without dropping active Wi-Fi Direct links or audio streams.
 */
class PttRepository(
    private val appCtx: Context,
    val settings: PttSettingsStore = PttSettings(appCtx),
    private val serviceBridge: PttServiceBridge = DefaultPttServiceBridge(appCtx)
) {
    companion object {
        private const val HEARTBEAT_INTERVAL_MS = 2_500L
        private const val CONNECTING_TIMEOUT_MS = 24_000L
        private const val APPROVAL_TIMEOUT_MS = 35_000L
    }

    private class DefaultPttServiceBridge(private val ctx: Context) : PttServiceBridge {
        override fun startOrUpdateService() = PttService.startOrUpdate(ctx)
        override fun stopService() = PttService.stop(ctx)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newCachedThreadPool { r ->
        Thread(r, "ptt-net-io").apply { isDaemon = true }
    }
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "ptt-watchdog").apply { isDaemon = true }
    }

    val wifiDirect = WifiDirectManager(appCtx)
    private val audioEngine = AudioEngine(appCtx)

    @Volatile
    var isAppInForeground: Boolean = false

    @Volatile
    var micForegroundEligibleInSession: Boolean = false
        private set

    private val _state = MutableStateFlow(
        PttUiState(
            displayName = settings.displayName,
            channelCode = settings.lastChannelCode,
            talkMode = settings.talkMode,
            rogerBeepEnabled = settings.rogerBeepEnabled
        )
    )
    val state: StateFlow<PttUiState> = _state.asStateFlow()

    // Active session runtime state
    private val stateLock = Any()
    private var sessionManager: PttSessionManager? = null
    private var currentSessionId: String = ""
    private var currentSaltHex: String = ""
    private var sessionKey: ByteArray? = null
    private var mediaKey: ByteArray? = null
    private var negotiatedCodec: AudioCodecType = AudioCodecType.ADPCM_16K

    // Host networking state
    private var tcpServer: ServerSocket? = null
    private val hostClients = ConcurrentHashMap<String, HostClientConn>()
    private val pendingJoinMap = ConcurrentHashMap<String, PendingJoinConn>()

    // Client networking state
    private var clientControlConn: ClientControlConn? = null
    private var pendingTargetSession: DiscoveredPttSession? = null
    private var pendingJoinChannelCode: String = ""
    private val tcpDialInProgress = AtomicBoolean(false)

    // Shared UDP voice socket
    private var udpSocket: DatagramSocket? = null
    private val udpRunning = AtomicBoolean(false)

    // Timers
    private var maintenanceFuture: ScheduledFuture<*>? = null
    private var connectTimeoutFuture: ScheduledFuture<*>? = null
    private val heartbeatSeq = AtomicInteger(1)
    private val tearingDown = AtomicBoolean(false)

    private inner class PendingJoinConn(
        val approval: PendingJoinApproval,
        val socket: Socket,
        val clientNonce: String,
        val serverNonce: String,
        val clientCodecMask: Int,
        val clientUdpPort: Int
    ) : Closeable {
        override fun close() {
            try { socket.close() } catch (_: Throwable) {}
        }
    }

    private inner class HostClientConn(
        val participantId: String,
        val displayName: String,
        val token: String,
        val socket: Socket,
        @Volatile var remoteIp: InetAddress,
        @Volatile var remoteUdpPort: Int
    ) : Closeable {
        val alive = AtomicBoolean(true)
        private val writeLock = Any()

        fun sendAsync(frame: PttProtocol.ControlFrame) {
            if (!alive.get()) return
            ioExecutor.execute { sendBlocking(frame) }
        }

        fun sendBlocking(frame: PttProtocol.ControlFrame): Boolean {
            if (!alive.get()) return false
            return try {
                synchronized(writeLock) {
                    PttProtocol.writeControlFrame(socket.getOutputStream(), frame, sessionKey)
                }
                true
            } catch (_: Throwable) {
                handleHostClientDropped(participantId)
                false
            }
        }

        override fun close() {
            alive.set(false)
            try { socket.close() } catch (_: Throwable) {}
        }
    }

    private inner class ClientControlConn(
        val participantId: String,
        val participantToken: String,
        val socket: Socket,
        val hostAddress: InetAddress,
        val hostUdpPort: Int
    ) : Closeable {
        val alive = AtomicBoolean(true)
        private val writeLock = Any()

        fun sendAsync(frame: PttProtocol.ControlFrame) {
            if (!alive.get()) return
            ioExecutor.execute { sendBlocking(frame) }
        }

        fun sendBlocking(frame: PttProtocol.ControlFrame): Boolean {
            if (!alive.get()) return false
            return try {
                synchronized(writeLock) {
                    PttProtocol.writeControlFrame(socket.getOutputStream(), frame, sessionKey)
                }
                true
            } catch (_: Throwable) {
                false
            }
        }

        override fun close() {
            alive.set(false)
            try { socket.close() } catch (_: Throwable) {}
        }
    }

    init {
        wifiDirect.listener = object : WifiDirectManager.Listener {
            override fun onSessionsDiscovered(sessions: List<DiscoveredPttSession>) {
                updateState {
                    if (it.phase == PttPhase.SCANNING) it.copy(discoveredSessions = sessions) else it
                }
            }

            override fun onP2pLinkEstablished(isGroupOwner: Boolean, groupOwnerIp: String) {
                val curPhase = _state.value.phase
                if (curPhase == PttPhase.CONNECTING && !_state.value.isHost) {
                    val port = pendingTargetSession?.tcpPort ?: PttProtocol.DEFAULT_TCP_PORT
                    dialHostControlChannel(groupOwnerIp, port)
                }
            }

            override fun onP2pLinkDisconnected() {
                val cur = _state.value
                if (!cur.isHost && cur.isActiveSessionOrConnecting) {
                    teardownSession(showEndedBanner = true, sendGoodbye = false)
                }
            }

            override fun onLocalHotspotStarted(ssid: String, passphrase: String) {
                updateState {
                    it.copy(
                        hotspotInfo = LocalHotspotCredentials(ssid, passphrase),
                        errorCode = PttErrorCode.NONE
                    )
                }
            }

            override fun onTransportFailure(issue: WifiEnvironmentIssue) {
                val err = mapWifiIssue(issue)
                if (_state.value.phase == PttPhase.CONNECTING || _state.value.phase == PttPhase.SCANNING) {
                    teardownSession(showEndedBanner = false, sendGoodbye = false, postError = err)
                } else {
                    updateState { it.copy(errorCode = err) }
                }
            }
        }
    }

    // ==================== Settings & Identity ====================

    fun saveDisplayName(name: String): Boolean {
        val trimmed = name.trim().take(28)
        if (trimmed.isEmpty()) return false
        settings.displayName = trimmed
        sessionManager?.updateLocalName(trimmed)
        updateState { it.copy(displayName = trimmed, errorCode = PttErrorCode.NONE) }
        if (_state.value.isHost && _state.value.phase == PttPhase.IN_SESSION) {
            broadcastHostRoster()
        }
        syncUiFromSessionManager()
        return true
    }

    fun setTalkMode(mode: TalkMode) {
        settings.talkMode = mode
        if (_state.value.buttonState == PttButtonVisualState.SELF_SPEAKING_GREEN && mode == TalkMode.HOLD_TO_TALK) {
            onTalkReleasedOrCancelled()
        }
        updateState { it.copy(talkMode = mode) }
    }

    fun setRogerBeepEnabled(enabled: Boolean) {
        settings.rogerBeepEnabled = enabled
        updateState { it.copy(rogerBeepEnabled = enabled) }
    }

    fun clearTransientMessages() {
        updateState { it.copy(errorCode = PttErrorCode.NONE, showSessionEndedBanner = false) }
    }

    // ==================== 1. Host Session Flow ====================

    fun createHostSession(rawChannelCode: String, useLocalHotspotFallback: Boolean = false): Boolean {
        val name = settings.displayName.trim()
        if (name.isEmpty()) return false
        if (!PttSettings.isValidChannelCode(rawChannelCode)) {
            updateState { it.copy(errorCode = PttErrorCode.AUTH_FAILED) }
            return false
        }
        if (PttPermissions.missingCorePermissions(appCtx).isNotEmpty()) {
            updateState { it.copy(errorCode = PttErrorCode.PERMISSION_MISSING) }
            return false
        }

        // Clean any prior state first
        teardownSession(showEndedBanner = false, sendGoodbye = false)

        val code = PttSettings.normalizeChannelCode(rawChannelCode)
        settings.lastChannelCode = code

        val sid = PttSecurity.randomHex(12)
        val salt = PttSecurity.randomHex(16)
        val derivedSessionKey = PttSecurity.deriveSessionKey(code, salt)
        val derivedMediaKey = PttSecurity.deriveMediaKey(derivedSessionKey, sid)
        val localHostId = "H_${settings.deviceId}"
        val sm = PttSessionManager(localParticipantId = localHostId, initialLocalName = name, isHostRole = true)
        val initialCodec = if ((audioEngine.localSupportedCodecMask() and AudioCodecType.OPUS_16K.mask) != 0) {
            AudioCodecType.OPUS_16K
        } else {
            AudioCodecType.ADPCM_16K
        }

        synchronized(stateLock) {
            currentSessionId = sid
            currentSaltHex = salt
            sessionKey = derivedSessionKey
            mediaKey = derivedMediaKey
            sessionManager = sm
            negotiatedCodec = initialCodec
            micForegroundEligibleInSession = isAppInForeground && PttPermissions.hasRecordAudio(appCtx)
        }

        if (!startHostSockets()) {
            teardownSession(showEndedBanner = false, sendGoodbye = false, postError = PttErrorCode.P2P_UNSUPPORTED)
            return false
        }

        audioEngine.startReceiveSession()
        startMaintenanceLoop()

        updateState {
            it.copy(
                phase = PttPhase.IN_SESSION,
                isHost = true,
                displayName = name,
                channelCode = code,
                buttonState = PttButtonVisualState.IDLE_BLUE,
                activeSpeakerId = null,
                activeSpeakerName = null,
                negotiatedCodec = initialCodec,
                localAudioMuted = false,
                pendingApprovals = emptyList(),
                participants = sm.snapshotParticipants(),
                connectedCount = sm.connectedCount(),
                hotspotInfo = null,
                showSessionEndedBanner = false,
                errorCode = PttErrorCode.NONE
            )
        }
        serviceBridge.startOrUpdateService()

        val actualTcpPort = tcpServer?.localPort ?: PttProtocol.DEFAULT_TCP_PORT
        if (useLocalHotspotFallback) {
            wifiDirect.startLocalOnlyHotspotFallback { issue ->
                teardownSession(showEndedBanner = false, sendGoodbye = false, postError = mapWifiIssue(issue))
            }
        } else {
            wifiDirect.startHostingGroup(
                sessionId = sid,
                hostDisplayName = name,
                tcpPort = actualTcpPort,
                onReady = { /* group advertised & ready */ },
                onError = { issue ->
                    teardownSession(showEndedBanner = false, sendGoodbye = false, postError = mapWifiIssue(issue))
                }
            )
        }
        return true
    }

    fun switchActiveHostToLocalHotspot() {
        if (_state.value.phase == PttPhase.IN_SESSION && _state.value.isHost) {
            wifiDirect.startLocalOnlyHotspotFallback { issue ->
                updateState { it.copy(errorCode = mapWifiIssue(issue)) }
            }
        }
    }

    fun approveJoinRequest(requestId: String) {
        val pending = pendingJoinMap.remove(requestId) ?: return
        refreshPendingApprovalsState()
        ioExecutor.execute {
            val sm = sessionManager
            val sKey = sessionKey
            if (sm == null || sKey == null || _state.value.phase != PttPhase.IN_SESSION) {
                pending.close()
                return@execute
            }
            try {
                val token = PttSecurity.randomHex(16)
                val negotiated = AudioCodecType.negotiate(audioEngine.localSupportedCodecMask(), pending.clientCodecMask)
                synchronized(stateLock) {
                    // Ensure all participants in a multi-peer group use a codec everyone supports
                    if (negotiated == AudioCodecType.ADPCM_16K || hostClients.isNotEmpty() && negotiatedCodec == AudioCodecType.ADPCM_16K) {
                        negotiatedCodec = AudioCodecType.ADPCM_16K
                    } else {
                        negotiatedCodec = negotiated
                    }
                }
                val now = SystemClock.elapsedRealtime()
                sm.registerAuthenticatedParticipant(
                    participantId = pending.approval.participantId,
                    displayName = pending.approval.displayName,
                    isHost = false,
                    authToken = token,
                    nowMs = now
                )
                val hostProof = PttSecurity.computeHostAcceptProof(
                    sessionKey = sKey,
                    sessionId = currentSessionId,
                    clientNonce = pending.clientNonce,
                    serverNonce = pending.serverNonce,
                    participantToken = token
                )
                val roster = sm.exportWireRoster()
                val localUdpPort = udpSocket?.localPort ?: PttProtocol.DEFAULT_UDP_PORT

                val acceptedFrame = PttProtocol.ControlFrame.JoinAccepted(
                    sessionId = currentSessionId,
                    participantId = pending.approval.participantId,
                    participantToken = token,
                    negotiatedCodecId = negotiatedCodec.id,
                    hostUdpPort = localUdpPort,
                    hostProofHex = hostProof,
                    roster = roster
                )
                PttProtocol.writeControlFrame(pending.socket.getOutputStream(), acceptedFrame, sKey)
                pending.socket.soTimeout = 0 // Keep persistent control channel open

                val clientConn = HostClientConn(
                    participantId = pending.approval.participantId,
                    displayName = pending.approval.displayName,
                    token = token,
                    socket = pending.socket,
                    remoteIp = pending.socket.inetAddress,
                    remoteUdpPort = pending.clientUdpPort
                )
                hostClients.put(clientConn.participantId, clientConn)?.close()
                broadcastHostRoster()

                // Inform newcomer if someone is currently speaking
                val curSpk = sm.currentSpeakerId()
                val curName = sm.currentSpeakerName()
                if (curSpk != null && curName != null) {
                    clientConn.sendAsync(PttProtocol.ControlFrame.FloorTaken(curSpk, curName, sm.currentLeaseId()))
                }

                syncUiFromSessionManager()
                serviceBridge.startOrUpdateService()
                runHostClientReadLoop(clientConn)
            } catch (_: Throwable) {
                sm.removeParticipant(pending.approval.participantId)
                pending.close()
                syncUiFromSessionManager()
            }
        }
    }

    fun rejectJoinRequest(requestId: String) {
        val pending = pendingJoinMap.remove(requestId) ?: return
        refreshPendingApprovalsState()
        ioExecutor.execute {
            try {
                PttProtocol.writeControlFrame(
                    pending.socket.getOutputStream(),
                    PttProtocol.ControlFrame.JoinRejected(PttRejectReason.DECLINED_BY_HOST),
                    sessionKey
                )
            } catch (_: Throwable) {}
            pending.close()
        }
    }

    // ==================== 2. Client Discovery & Join Flow ====================

    fun startScanningSessions(): Boolean {
        val name = settings.displayName.trim()
        if (name.isEmpty()) return false
        if (PttPermissions.missingCorePermissions(appCtx).isNotEmpty()) {
            updateState { it.copy(errorCode = PttErrorCode.PERMISSION_MISSING) }
            return false
        }
        teardownSession(showEndedBanner = false, sendGoodbye = false)
        updateState {
            it.copy(
                phase = PttPhase.SCANNING,
                isHost = false,
                discoveredSessions = emptyList(),
                showSessionEndedBanner = false,
                errorCode = PttErrorCode.NONE
            )
        }
        return wifiDirect.startSessionDiscovery { issue ->
            updateState {
                it.copy(
                    phase = PttPhase.DISCONNECTED,
                    errorCode = mapWifiIssue(issue)
                )
            }
        }
    }

    fun stopScanningSessions() {
        wifiDirect.stopSessionDiscovery()
        if (_state.value.phase == PttPhase.SCANNING) {
            updateState { it.copy(phase = PttPhase.DISCONNECTED, discoveredSessions = emptyList()) }
        }
    }

    fun requestJoinDiscoveredSession(session: DiscoveredPttSession, rawChannelCode: String): Boolean {
        val name = settings.displayName.trim()
        if (name.isEmpty()) return false
        if (!PttSettings.isValidChannelCode(rawChannelCode)) {
            updateState { it.copy(errorCode = PttErrorCode.AUTH_FAILED) }
            return false
        }
        if (PttPermissions.missingCorePermissions(appCtx).isNotEmpty()) {
            updateState { it.copy(errorCode = PttErrorCode.PERMISSION_MISSING) }
            return false
        }

        wifiDirect.stopSessionDiscovery()
        val code = PttSettings.normalizeChannelCode(rawChannelCode)
        settings.lastChannelCode = code
        pendingTargetSession = session
        pendingJoinChannelCode = code
        tcpDialInProgress.set(false)
        micForegroundEligibleInSession = isAppInForeground && PttPermissions.hasRecordAudio(appCtx)

        updateState {
            it.copy(
                phase = PttPhase.CONNECTING,
                isHost = false,
                channelCode = code,
                showSessionEndedBanner = false,
                errorCode = PttErrorCode.NONE
            )
        }
        armConnectTimeout(CONNECTING_TIMEOUT_MS)

        wifiDirect.connectToHostDevice(
            deviceAddress = session.deviceAddress,
            onInitiated = { /* P2P connection broadcast will trigger dialHostControlChannel */ },
            onFailed = {
                teardownSession(showEndedBanner = false, sendGoodbye = false, postError = PttErrorCode.CONNECT_TIMEOUT)
            }
        )
        return true
    }

    /** Joins a host running Local-Only Hotspot fallback on the currently joined Wi-Fi gateway. */
    fun requestJoinLocalHotspotGateway(rawChannelCode: String): Boolean {
        val name = settings.displayName.trim()
        if (name.isEmpty()) return false
        if (!PttSettings.isValidChannelCode(rawChannelCode)) {
            updateState { it.copy(errorCode = PttErrorCode.AUTH_FAILED) }
            return false
        }
        val gwIp = wifiDirect.resolveLocalWifiGatewayIp()
        if (gwIp.isNullOrBlank()) {
            updateState { it.copy(errorCode = PttErrorCode.WIFI_DISABLED) }
            return false
        }
        wifiDirect.stopSessionDiscovery()
        val code = PttSettings.normalizeChannelCode(rawChannelCode)
        settings.lastChannelCode = code
        pendingTargetSession = null
        pendingJoinChannelCode = code
        tcpDialInProgress.set(false)
        micForegroundEligibleInSession = isAppInForeground && PttPermissions.hasRecordAudio(appCtx)

        updateState {
            it.copy(
                phase = PttPhase.CONNECTING,
                isHost = false,
                channelCode = code,
                showSessionEndedBanner = false,
                errorCode = PttErrorCode.NONE
            )
        }
        armConnectTimeout(CONNECTING_TIMEOUT_MS)
        dialHostControlChannel(gwIp, PttProtocol.DEFAULT_TCP_PORT)
        return true
    }

    // ==================== 3. Push-to-Talk Floor & Audio Controls ====================

    fun onTalkTouchDown() {
        if (_state.value.phase != PttPhase.IN_SESSION) return
        if (_state.value.talkMode == TalkMode.TAP_TO_TOGGLE) {
            if (_state.value.buttonState == PttButtonVisualState.SELF_SPEAKING_GREEN) {
                onTalkReleasedOrCancelled()
            } else if (_state.value.buttonState == PttButtonVisualState.IDLE_BLUE) {
                requestTransmitFloor()
            }
        } else {
            if (_state.value.buttonState == PttButtonVisualState.IDLE_BLUE) {
                requestTransmitFloor()
            }
        }
    }

    fun onTalkTouchUpOrCancel() {
        if (_state.value.talkMode == TalkMode.HOLD_TO_TALK) {
            onTalkReleasedOrCancelled()
        }
    }

    /** Safe immediate stop used on touch UP, touch CANCEL, UI detach, or mode switch. */
    fun onTalkReleasedOrCancelled() {
        val sm = sessionManager ?: run {
            audioEngine.stopTransmit(playEndBeep = false)
            return
        }
        val myId = sm.localParticipantId
        val activeLease = sm.currentLeaseId()
        val wasSpeaking = sm.currentSpeakerId() == myId
        audioEngine.stopTransmit(playEndBeep = wasSpeaking && settings.rogerBeepEnabled)

        if (!wasSpeaking) return

        if (_state.value.isHost) {
            val released = sm.releaseFloor(myId, activeLease)
            if (released != null) {
                broadcastToAllClients(PttProtocol.ControlFrame.FloorIdle(released))
                syncUiFromSessionManager()
                serviceBridge.startOrUpdateService()
            }
        } else {
            sm.releaseFloor(myId, activeLease)
            syncUiFromSessionManager()
            serviceBridge.startOrUpdateService()
            val conn = clientControlConn
            if (conn != null) {
                conn.sendAsync(PttProtocol.ControlFrame.FloorRelease(myId, activeLease, conn.participantToken))
            }
        }
    }

    fun togglePeerLocalMute(participantId: String) {
        val sm = sessionManager ?: return
        sm.togglePeerLocallyMuted(participantId)
        syncUiFromSessionManager()
    }

    fun setLocalAudioMuted(muted: Boolean) {
        audioEngine.setLocalListenMuted(muted)
        updateState { it.copy(localAudioMuted = muted) }
        if (_state.value.phase == PttPhase.IN_SESSION) {
            serviceBridge.startOrUpdateService()
        }
    }

    fun toggleLocalAudioMutedFromNotification() {
        setLocalAudioMuted(!_state.value.localAudioMuted)
    }

    private fun requestTransmitFloor() {
        val sm = sessionManager ?: return
        if (!PttPermissions.hasRecordAudio(appCtx)) {
            updateState { it.copy(errorCode = PttErrorCode.PERMISSION_MISSING) }
            return
        }
        if (isAppInForeground) {
            micForegroundEligibleInSession = true
        }
        val myId = sm.localParticipantId
        if (_state.value.isHost) {
            when (val dec = sm.arbitrateFloorRequest(myId, SystemClock.elapsedRealtime())) {
                is FloorDecision.Granted -> {
                    broadcastToAllClients(PttProtocol.ControlFrame.FloorTaken(dec.speakerId, dec.speakerName, dec.leaseId))
                    syncUiFromSessionManager()
                    serviceBridge.startOrUpdateService()
                    beginMicrophoneCapture(dec.leaseId, myId)
                }
                is FloorDecision.Denied -> {
                    syncUiFromSessionManager()
                }
                FloorDecision.Unauthorized -> Unit
            }
        } else {
            val conn = clientControlConn ?: return
            conn.sendAsync(PttProtocol.ControlFrame.FloorRequest(myId, conn.participantToken))
        }
    }

    private fun beginMicrophoneCapture(leaseId: Int, speakerId: String) {
        val senderHash = PttSecurity.participantShortHash(speakerId)
        audioEngine.startTransmit(
            codec = negotiatedCodec,
            rogerBeep = settings.rogerBeepEnabled,
            onEncodedFrame = { actualCodec, seq, tsMs, endOfBurst, payload ->
                val mKey = mediaKey ?: return@startTransmit
                val sm = sessionManager ?: return@startTransmit
                if (sm.currentSpeakerId() != speakerId || sm.currentLeaseId() != leaseId) {
                    return@startTransmit
                }
                val datagram = PttProtocol.encodeUdpVoicePacket(
                    mediaKey = mKey,
                    codec = actualCodec,
                    endOfBurst = endOfBurst,
                    leaseId = leaseId,
                    sequence = seq,
                    timestampMs = tsMs,
                    senderShortHash = senderHash,
                    payload = payload
                )
                dispatchOutgoingVoiceDatagram(datagram)
            },
            onCaptureError = {
                mainHandler.post { onTalkReleasedOrCancelled() }
            }
        )
    }

    // ==================== 4. Unified Clean Teardown (10-Step Guarantee) ====================

    /**
     * Unified, idempotent teardown executing all 10 required cleanup steps safely without deadlocks,
     * lingering sockets, stuck "Connecting..." states, or orphaned foreground notifications.
     */
    fun endSessionByUser() {
        teardownSession(showEndedBanner = false, sendGoodbye = true)
    }

    fun onSystemServiceForcedTimeout() {
        teardownSession(showEndedBanner = true, sendGoodbye = true)
    }

    fun teardownSession(
        showEndedBanner: Boolean,
        sendGoodbye: Boolean,
        postError: PttErrorCode = PttErrorCode.NONE
    ) {
        if (!tearingDown.compareAndSet(false, true)) return
        try {
            // 1 & 2. Stop transmit & incoming playback immediately
            audioEngine.stopTransmit(playEndBeep = false)

            // Snapshot active sockets before clearing state
            val wasHost = _state.value.isHost
            val clientsSnapshot = hostClients.values.toList()
            val pendingSnapshot = pendingJoinMap.values.toList()
            val clientConnSnapshot = clientControlConn
            val srvSnapshot = tcpServer
            val udpSnapshot = udpSocket

            hostClients.clear()
            pendingJoinMap.clear()
            clientControlConn = null
            tcpServer = null
            udpSocket = null
            udpRunning.set(false)
            tcpDialInProgress.set(false)
            pendingTargetSession = null

            // 5. Cancel discovery, connection waits, and periodic timers
            cancelConnectTimeout()
            maintenanceFuture?.cancel(true)
            maintenanceFuture = null

            // 3 & 4. Send polite session termination frame (bounded non-blocking) & close all sockets
            if (sendGoodbye && (clientsSnapshot.isNotEmpty() || clientConnSnapshot != null)) {
                val goodbyeLatch = java.util.concurrent.CountDownLatch(1)
                ioExecutor.execute {
                    try {
                        if (wasHost) {
                            val endFrame = PttProtocol.ControlFrame.SessionEnd("host_ended")
                            for (c in clientsSnapshot) c.sendBlocking(endFrame)
                        } else if (clientConnSnapshot != null) {
                            val leave = PttProtocol.ControlFrame.ParticipantLeave(
                                clientConnSnapshot.participantId,
                                clientConnSnapshot.participantToken
                            )
                            clientConnSnapshot.sendBlocking(leave)
                        }
                    } catch (_: Throwable) {
                    } finally {
                        goodbyeLatch.countDown()
                    }
                }
                try { goodbyeLatch.await(200, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {}
            }

            for (c in clientsSnapshot) c.close()
            for (p in pendingSnapshot) p.close()
            clientConnSnapshot?.close()
            try { srvSnapshot?.close() } catch (_: Throwable) {}
            try { udpSnapshot?.close() } catch (_: Throwable) {}

            // 6. Release AudioRecord, AudioTrack, MediaCodec, and buffers
            audioEngine.releaseAll()

            // 7. Remove owned Wi-Fi Direct group / stop discovery / close Local Hotspot
            wifiDirect.teardownRadio()

            // 8. Stop Foreground Service and remove its ongoing notification
            serviceBridge.stopService()

            // 9. Wipe internal session keys & participant state
            synchronized(stateLock) {
                sessionManager?.resetAll()
                sessionManager = null
                currentSessionId = ""
                currentSaltHex = ""
                sessionKey?.fill(0)
                mediaKey?.fill(0)
                sessionKey = null
                mediaKey = null
                micForegroundEligibleInSession = false
            }

            // 10. Reset UI StateFlow cleanly to DISCONNECTED
            _state.value = PttUiState(
                phase = PttPhase.DISCONNECTED,
                isHost = false,
                displayName = settings.displayName,
                channelCode = settings.lastChannelCode,
                talkMode = settings.talkMode,
                rogerBeepEnabled = settings.rogerBeepEnabled,
                buttonState = PttButtonVisualState.IDLE_BLUE,
                activeSpeakerId = null,
                activeSpeakerName = null,
                negotiatedCodec = AudioCodecType.ADPCM_16K,
                localAudioMuted = false,
                discoveredSessions = emptyList(),
                pendingApprovals = emptyList(),
                participants = emptyList(),
                connectedCount = 0,
                hotspotInfo = null,
                showSessionEndedBanner = showEndedBanner,
                errorCode = postError
            )
        } finally {
            tearingDown.set(false)
        }
    }

    // ==================== Internal Networking & Protocol Loops ====================

    private fun startHostSockets(): Boolean {
        return try {
            val srv = ServerSocket()
            srv.reuseAddress = true
            srv.bind(InetSocketAddress(PttProtocol.DEFAULT_TCP_PORT))
            tcpServer = srv

            val udp = DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(PttProtocol.DEFAULT_UDP_PORT))
            }
            udpSocket = udp
            udpRunning.set(true)

            ioExecutor.execute { acceptHostClientsLoop(srv) }
            ioExecutor.execute { receiveUdpLoop(udp, isHostMode = true) }
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun acceptHostClientsLoop(srv: ServerSocket) {
        while (!srv.isClosed && _state.value.phase == PttPhase.IN_SESSION && _state.value.isHost) {
            val rawSocket = try {
                srv.accept()
            } catch (_: Throwable) {
                break
            }
            ioExecutor.execute { handleIncomingJoinHandshake(rawSocket) }
        }
    }

    private fun handleIncomingJoinHandshake(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = 10_000
            val sm = sessionManager
            val sKey = sessionKey
            if (sm == null || sKey == null || _state.value.phase != PttPhase.IN_SESSION) {
                socket.close()
                return
            }

            val serverNonce = PttSecurity.randomHex(16)
            val challenge = PttProtocol.ControlFrame.HelloChallenge(
                protocolVersion = PttProtocol.PROTOCOL_VERSION,
                sessionId = currentSessionId,
                sessionSaltHex = currentSaltHex,
                serverNonce = serverNonce,
                hostId = sm.localParticipantId,
                hostName = settings.displayName,
                supportedCodecMask = audioEngine.localSupportedCodecMask()
            )
            PttProtocol.writeControlFrame(socket.getOutputStream(), challenge, macKey = null)

            val response = PttProtocol.readControlFrame(socket.getInputStream(), macKey = null, requireMac = false)
            if (response !is PttProtocol.ControlFrame.JoinRequest) {
                socket.close()
                return
            }
            if (response.protocolVersion != PttProtocol.PROTOCOL_VERSION || response.sessionId != currentSessionId) {
                PttProtocol.writeControlFrame(
                    socket.getOutputStream(),
                    PttProtocol.ControlFrame.JoinRejected(PttRejectReason.PROTOCOL_MISMATCH)
                )
                socket.close()
                return
            }

            val authOk = PttSecurity.verifyClientJoinProof(
                sessionKey = sKey,
                sessionId = currentSessionId,
                serverNonce = serverNonce,
                clientNonce = response.clientNonce,
                deviceId = response.deviceId,
                displayName = response.displayName,
                candidateHex = response.clientProofHex
            )
            if (!authOk) {
                PttProtocol.writeControlFrame(
                    socket.getOutputStream(),
                    PttProtocol.ControlFrame.JoinRejected(PttRejectReason.AUTH_FAILED)
                )
                socket.close()
                return
            }

            // Authenticated! Notify client it is awaiting host user's Accept / Reject tap
            PttProtocol.writeControlFrame(
                socket.getOutputStream(),
                PttProtocol.ControlFrame.JoinPendingApproval(currentSessionId),
                macKey = sKey
            )
            socket.soTimeout = APPROVAL_TIMEOUT_MS.toInt()

            val reqId = UUID.randomUUID().toString()
            val participantId = "C_${response.deviceId.take(16)}"
            val approval = PendingJoinApproval(
                requestId = reqId,
                participantId = participantId,
                displayName = response.displayName.trim().ifBlank { "Guest" },
                deviceFingerprint = PttSecurity.shortDeviceFingerprint(response.deviceId)
            )
            pendingJoinMap[reqId] = PendingJoinConn(
                approval = approval,
                socket = socket,
                clientNonce = response.clientNonce,
                serverNonce = serverNonce,
                clientCodecMask = response.supportedCodecMask,
                clientUdpPort = response.udpListenPort
            )
            refreshPendingApprovalsState()
        } catch (_: Throwable) {
            try { socket.close() } catch (_: Throwable) {}
        }
    }

    private fun runHostClientReadLoop(conn: HostClientConn) {
        try {
            while (conn.alive.get() && _state.value.phase == PttPhase.IN_SESSION) {
                val frame = PttProtocol.readControlFrame(conn.socket.getInputStream(), macKey = sessionKey, requireMac = true)
                val sm = sessionManager ?: break
                val now = SystemClock.elapsedRealtime()
                sm.recordHeartbeat(conn.participantId, null, null, now)

                when (frame) {
                    is PttProtocol.ControlFrame.FloorRequest -> {
                        if (!sm.verifyParticipantToken(conn.participantId, frame.participantToken)) continue
                        when (val dec = sm.arbitrateFloorRequest(conn.participantId, now)) {
                            is FloorDecision.Granted -> {
                                conn.sendAsync(
                                    PttProtocol.ControlFrame.FloorGranted(
                                        dec.speakerId,
                                        dec.speakerName,
                                        dec.leaseId,
                                        dec.maxDurationMs
                                    )
                                )
                                for ((otherId, otherConn) in hostClients) {
                                    if (otherId != conn.participantId) {
                                        otherConn.sendAsync(
                                            PttProtocol.ControlFrame.FloorTaken(dec.speakerId, dec.speakerName, dec.leaseId)
                                        )
                                    }
                                }
                                syncUiFromSessionManager()
                                serviceBridge.startOrUpdateService()
                            }
                            is FloorDecision.Denied -> {
                                conn.sendAsync(PttProtocol.ControlFrame.FloorDenied(dec.currentSpeakerId, dec.currentSpeakerName))
                            }
                            FloorDecision.Unauthorized -> Unit
                        }
                    }

                    is PttProtocol.ControlFrame.FloorRelease -> {
                        if (!sm.verifyParticipantToken(conn.participantId, frame.participantToken)) continue
                        val released = sm.releaseFloor(conn.participantId, frame.leaseId)
                        if (released != null) {
                            audioEngine.playRogerEndToneIfEnabled(settings.rogerBeepEnabled)
                            broadcastToAllClients(PttProtocol.ControlFrame.FloorIdle(released))
                            syncUiFromSessionManager()
                            serviceBridge.startOrUpdateService()
                        }
                    }

                    is PttProtocol.ControlFrame.HeartbeatPing -> {
                        sm.recordHeartbeat(conn.participantId, null, frame.lossPct, now)
                        conn.sendAsync(
                            PttProtocol.ControlFrame.HeartbeatPong(
                                senderId = sm.localParticipantId,
                                seq = frame.seq,
                                echoElapsedMs = frame.sentElapsedMs,
                                lossPct = audioEngine.measuredPacketLossPct()
                            )
                        )
                    }

                    is PttProtocol.ControlFrame.HeartbeatPong -> {
                        val rtt = (now - frame.echoElapsedMs).toInt().coerceAtLeast(1)
                        sm.recordHeartbeat(conn.participantId, rtt, frame.lossPct, now)
                        syncUiFromSessionManager()
                    }

                    is PttProtocol.ControlFrame.ParticipantLeave -> {
                        if (sm.verifyParticipantToken(conn.participantId, frame.participantToken)) {
                            handleHostClientDropped(conn.participantId)
                            break
                        }
                    }

                    else -> Unit
                }
            }
        } catch (_: Throwable) {
            handleHostClientDropped(conn.participantId)
        }
    }

    private fun handleHostClientDropped(participantId: String) {
        val removedConn = hostClients.remove(participantId)
        removedConn?.close()
        val sm = sessionManager ?: return
        val wasSpeaking = sm.currentSpeakerId() == participantId
        val prevLease = sm.currentLeaseId()
        if (sm.removeParticipant(participantId)) {
            if (wasSpeaking) {
                broadcastToAllClients(PttProtocol.ControlFrame.FloorIdle(prevLease))
            }
            broadcastHostRoster()
            syncUiFromSessionManager()
            serviceBridge.startOrUpdateService()
        }
    }

    private fun dialHostControlChannel(hostIp: String, tcpPort: Int) {
        if (!tcpDialInProgress.compareAndSet(false, true)) return
        ioExecutor.execute {
            var sock: Socket? = null
            try {
                // Retry briefly while P2P interface brings up IP routing
                var connected: Socket? = null
                for (attempt in 1..6) {
                    if (_state.value.phase != PttPhase.CONNECTING) return@execute
                    try {
                        val candidate = Socket()
                        candidate.tcpNoDelay = true
                        candidate.connect(InetSocketAddress(hostIp, tcpPort), 2_800)
                        connected = candidate
                        break
                    } catch (_: Throwable) {
                        Thread.sleep(650L)
                    }
                }
                sock = connected ?: run {
                    teardownSession(showEndedBanner = false, sendGoodbye = false, postError = PttErrorCode.CONNECT_TIMEOUT)
                    return@execute
                }
                sock.soTimeout = 10_000

                val udp = DatagramSocket(0)
                udpSocket = udp
                udpRunning.set(true)

                val challenge = PttProtocol.readControlFrame(sock.getInputStream(), macKey = null, requireMac = false)
                if (challenge !is PttProtocol.ControlFrame.HelloChallenge || challenge.protocolVersion != PttProtocol.PROTOCOL_VERSION) {
                    teardownSession(showEndedBanner = false, sendGoodbye = false, postError = PttErrorCode.AUTH_FAILED)
                    return@execute
                }

                val derivedSessionKey = PttSecurity.deriveSessionKey(pendingJoinChannelCode, challenge.sessionSaltHex)
                val derivedMediaKey = PttSecurity.deriveMediaKey(derivedSessionKey, challenge.sessionId)
                val clientNonce = PttSecurity.randomHex(16)
                val myName = settings.displayName.trim()
                val proof = PttSecurity.computeClientJoinProof(
                    sessionKey = derivedSessionKey,
                    sessionId = challenge.sessionId,
                    serverNonce = challenge.serverNonce,
                    clientNonce = clientNonce,
                    deviceId = settings.deviceId,
                    displayName = myName
                )

                val joinReq = PttProtocol.ControlFrame.JoinRequest(
                    protocolVersion = PttProtocol.PROTOCOL_VERSION,
                    sessionId = challenge.sessionId,
                    clientNonce = clientNonce,
                    deviceId = settings.deviceId,
                    displayName = myName,
                    supportedCodecMask = audioEngine.localSupportedCodecMask(),
                    udpListenPort = udp.localPort,
                    clientProofHex = proof
                )
                PttProtocol.writeControlFrame(sock.getOutputStream(), joinReq, macKey = null)

                var nextFrame = PttProtocol.readControlFrame(sock.getInputStream(), macKey = derivedSessionKey, requireMac = false)
                if (nextFrame is PttProtocol.ControlFrame.JoinPendingApproval) {
                    updateState { it.copy(phase = PttPhase.WAITING_APPROVAL) }
                    armConnectTimeout(APPROVAL_TIMEOUT_MS)
                    sock.soTimeout = APPROVAL_TIMEOUT_MS.toInt()
                    nextFrame = PttProtocol.readControlFrame(sock.getInputStream(), macKey = derivedSessionKey, requireMac = false)
                }

                when (nextFrame) {
                    is PttProtocol.ControlFrame.JoinRejected -> {
                        val err = if (nextFrame.reasonCode == PttRejectReason.AUTH_FAILED) {
                            PttErrorCode.AUTH_FAILED
                        } else {
                            PttErrorCode.REJECTED_BY_HOST
                        }
                        teardownSession(showEndedBanner = false, sendGoodbye = false, postError = err)
                        return@execute
                    }

                    is PttProtocol.ControlFrame.JoinAccepted -> {
                        val validHost = PttSecurity.verifyHostAcceptProof(
                            sessionKey = derivedSessionKey,
                            sessionId = challenge.sessionId,
                            clientNonce = clientNonce,
                            serverNonce = challenge.serverNonce,
                            participantToken = nextFrame.participantToken,
                            candidateHex = nextFrame.hostProofHex
                        )
                        if (!validHost) {
                            teardownSession(showEndedBanner = false, sendGoodbye = false, postError = PttErrorCode.AUTH_FAILED)
                            return@execute
                        }

                        cancelConnectTimeout()
                        sock.soTimeout = 0
                        val codec = AudioCodecType.fromId(nextFrame.negotiatedCodecId)
                        val sm = PttSessionManager(
                            localParticipantId = nextFrame.participantId,
                            initialLocalName = myName,
                            isHostRole = false
                        )
                        sm.applyAuthoritativeRoster(nextFrame.roster, SystemClock.elapsedRealtime())

                        val hostAddr = InetAddress.getByName(hostIp)
                        val ctrlConn = ClientControlConn(
                            participantId = nextFrame.participantId,
                            participantToken = nextFrame.participantToken,
                            socket = sock,
                            hostAddress = hostAddr,
                            hostUdpPort = nextFrame.hostUdpPort
                        )

                        synchronized(stateLock) {
                            currentSessionId = challenge.sessionId
                            currentSaltHex = challenge.sessionSaltHex
                            sessionKey = derivedSessionKey
                            mediaKey = derivedMediaKey
                            negotiatedCodec = codec
                            sessionManager = sm
                            clientControlConn = ctrlConn
                        }

                        audioEngine.startReceiveSession()
                        ioExecutor.execute { receiveUdpLoop(udp, isHostMode = false) }
                        startMaintenanceLoop()

                        // Send an initial empty UDP punch packet so the Host immediately learns our NAT/source UDP port
                        sendClientUdpPunchPacket(ctrlConn)

                        updateState {
                            it.copy(
                                phase = PttPhase.IN_SESSION,
                                isHost = false,
                                negotiatedCodec = codec,
                                buttonState = sm.currentVisualState(),
                                participants = sm.snapshotParticipants(),
                                connectedCount = sm.connectedCount(),
                                showSessionEndedBanner = false,
                                errorCode = PttErrorCode.NONE
                            )
                        }
                        serviceBridge.startOrUpdateService()
                        runClientControlReadLoop(ctrlConn)
                    }

                    else -> {
                        teardownSession(showEndedBanner = false, sendGoodbye = false, postError = PttErrorCode.CONNECT_TIMEOUT)
                    }
                }
            } catch (_: Throwable) {
                if (!tearingDown.get() && _state.value.isActiveSessionOrConnecting) {
                    try { sock?.close() } catch (_: Throwable) {}
                    teardownSession(showEndedBanner = false, sendGoodbye = false, postError = PttErrorCode.CONNECT_TIMEOUT)
                }
            }
        }
    }

    private fun runClientControlReadLoop(conn: ClientControlConn) {
        try {
            while (conn.alive.get() && _state.value.phase == PttPhase.IN_SESSION) {
                val frame = PttProtocol.readControlFrame(conn.socket.getInputStream(), macKey = sessionKey, requireMac = true)
                val sm = sessionManager ?: break
                val now = SystemClock.elapsedRealtime()
                // Record host heartbeat activity on every valid frame
                sm.snapshotParticipants().firstOrNull { it.isHost }?.let { hostPeer ->
                    sm.recordHeartbeat(hostPeer.id, null, null, now)
                }

                when (frame) {
                    is PttProtocol.ControlFrame.RosterSync -> {
                        negotiatedCodec = AudioCodecType.fromId(frame.negotiatedCodecId)
                        sm.applyAuthoritativeRoster(frame.roster, now)
                        syncUiFromSessionManager()
                        serviceBridge.startOrUpdateService()
                    }

                    is PttProtocol.ControlFrame.FloorGranted -> {
                        sm.applyFloorTaken(frame.speakerId, frame.speakerName, frame.leaseId, now)
                        syncUiFromSessionManager()
                        serviceBridge.startOrUpdateService()
                        if (frame.speakerId == sm.localParticipantId) {
                            beginMicrophoneCapture(frame.leaseId, sm.localParticipantId)
                        }
                    }

                    is PttProtocol.ControlFrame.FloorDenied -> {
                        sm.applyFloorTaken(frame.currentSpeakerId, frame.currentSpeakerName, sm.currentLeaseId(), now)
                        syncUiFromSessionManager()
                    }

                    is PttProtocol.ControlFrame.FloorTaken -> {
                        if (sm.currentSpeakerId() == sm.localParticipantId) {
                            audioEngine.stopTransmit(playEndBeep = false)
                        }
                        sm.applyFloorTaken(frame.speakerId, frame.speakerName, frame.leaseId, now)
                        syncUiFromSessionManager()
                        serviceBridge.startOrUpdateService()
                    }

                    is PttProtocol.ControlFrame.FloorIdle -> {
                        val wasRemoteSpeaking = sm.currentSpeakerId() != null && sm.currentSpeakerId() != sm.localParticipantId
                        if (sm.currentSpeakerId() == sm.localParticipantId) {
                            audioEngine.stopTransmit(playEndBeep = false)
                        }
                        sm.applyFloorIdle(frame.previousLeaseId)
                        if (wasRemoteSpeaking) {
                            audioEngine.playRogerEndToneIfEnabled(settings.rogerBeepEnabled)
                        }
                        syncUiFromSessionManager()
                        serviceBridge.startOrUpdateService()
                    }

                    is PttProtocol.ControlFrame.HeartbeatPing -> {
                        sm.recordHeartbeat(frame.senderId, null, frame.lossPct, now)
                        conn.sendAsync(
                            PttProtocol.ControlFrame.HeartbeatPong(
                                senderId = sm.localParticipantId,
                                seq = frame.seq,
                                echoElapsedMs = frame.sentElapsedMs,
                                lossPct = audioEngine.measuredPacketLossPct()
                            )
                        )
                    }

                    is PttProtocol.ControlFrame.HeartbeatPong -> {
                        val rtt = (now - frame.echoElapsedMs).toInt().coerceAtLeast(1)
                        sm.recordHeartbeat(frame.senderId, rtt, frame.lossPct, now)
                        syncUiFromSessionManager()
                    }

                    is PttProtocol.ControlFrame.SessionEnd -> {
                        teardownSession(showEndedBanner = true, sendGoodbye = false)
                        break
                    }

                    else -> Unit
                }
            }
        } catch (_: Throwable) {
            if (!tearingDown.get() && _state.value.phase == PttPhase.IN_SESSION) {
                teardownSession(showEndedBanner = true, sendGoodbye = false)
            }
        }
    }

    private fun receiveUdpLoop(sock: DatagramSocket, isHostMode: Boolean) {
        val buf = ByteArray(1500)
        val dp = DatagramPacket(buf, buf.size)
        while (udpRunning.get() && !sock.isClosed) {
            try {
                dp.length = buf.size
                sock.receive(dp)
                val mKey = mediaKey ?: continue
                val sm = sessionManager ?: continue
                val decoded = PttProtocol.decodeUdpVoicePacket(mKey, dp.data, dp.length) ?: continue
                val senderId = sm.resolveParticipantIdByShortHash(decoded.senderShortHash) ?: continue

                if (isHostMode) {
                    // Refresh client's dynamic source UDP endpoint
                    hostClients[senderId]?.let { c ->
                        c.remoteIp = dp.address
                        if (dp.port > 0) c.remoteUdpPort = dp.port
                    }
                }

                // Ignore empty NAT punch packets
                if (decoded.payload.isEmpty()) continue

                // Enforce strict Half-Duplex: only play & relay packets matching the active floor lease!
                if (!sm.noteSpeakerAudioPacket(decoded.leaseId, SystemClock.elapsedRealtime())) {
                    continue
                }
                if (senderId == sm.localParticipantId) continue

                if (!sm.isPeerLocallyMuted(senderId)) {
                    audioEngine.onIncomingVoicePacket(decoded)
                }

                // Group Owner relays active guest speech to all other guests in multi-phone sessions
                if (isHostMode) {
                    val rawCopy = dp.data.copyOfRange(0, dp.length)
                    for ((peerId, clientConn) in hostClients) {
                        if (peerId != senderId && clientConn.alive.get()) {
                            try {
                                sock.send(DatagramPacket(rawCopy, rawCopy.size, clientConn.remoteIp, clientConn.remoteUdpPort))
                            } catch (_: Throwable) {}
                        }
                    }
                }
            } catch (_: Throwable) {
                if (!udpRunning.get() || sock.isClosed) break
            }
        }
    }

    private fun dispatchOutgoingVoiceDatagram(datagram: ByteArray) {
        val sock = udpSocket ?: return
        if (sock.isClosed) return
        if (_state.value.isHost) {
            for (c in hostClients.values) {
                if (!c.alive.get()) continue
                try {
                    sock.send(DatagramPacket(datagram, datagram.size, c.remoteIp, c.remoteUdpPort))
                } catch (_: Throwable) {}
            }
        } else {
            val conn = clientControlConn ?: return
            try {
                sock.send(DatagramPacket(datagram, datagram.size, conn.hostAddress, conn.hostUdpPort))
            } catch (_: Throwable) {}
        }
    }

    private fun sendClientUdpPunchPacket(conn: ClientControlConn) {
        val mKey = mediaKey ?: return
        val sock = udpSocket ?: return
        ioExecutor.execute {
            try {
                val punch = PttProtocol.encodeUdpVoicePacket(
                    mediaKey = mKey,
                    codec = negotiatedCodec,
                    endOfBurst = false,
                    leaseId = 0,
                    sequence = 0,
                    timestampMs = SystemClock.elapsedRealtime(),
                    senderShortHash = PttSecurity.participantShortHash(conn.participantId),
                    payload = ByteArray(0)
                )
                sock.send(DatagramPacket(punch, punch.size, conn.hostAddress, conn.hostUdpPort))
            } catch (_: Throwable) {}
        }
    }

    // ==================== 5. Heartbeat & Watchdog Timers ====================

    private fun startMaintenanceLoop() {
        maintenanceFuture?.cancel(false)
        maintenanceFuture = scheduler.scheduleWithFixedDelay({
            try {
                runPeriodicHeartbeatAndTimeoutCheck()
            } catch (_: Throwable) {}
        }, HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS)
    }

    private fun runPeriodicHeartbeatAndTimeoutCheck() {
        if (_state.value.phase != PttPhase.IN_SESSION) return
        val sm = sessionManager ?: return
        val now = SystemClock.elapsedRealtime()

        // 1. Check 10-second heartbeat timeout & max floor lease watchdog
        val check = sm.checkTimeouts(now)
        if (check.floorExpiredLeaseId != null) {
            if (_state.value.buttonState == PttButtonVisualState.SELF_SPEAKING_GREEN) {
                audioEngine.stopTransmit(playEndBeep = settings.rogerBeepEnabled)
            }
            if (_state.value.isHost) {
                broadcastToAllClients(PttProtocol.ControlFrame.FloorIdle(check.floorExpiredLeaseId))
            }
        }

        if (check.timedOutParticipantIds.isNotEmpty()) {
            if (_state.value.isHost) {
                // Remove only the timed-out guest(s); remaining guests & host stay active!
                for (deadId in check.timedOutParticipantIds) {
                    hostClients.remove(deadId)?.close()
                }
                broadcastHostRoster()
                syncUiFromSessionManager()
                serviceBridge.startOrUpdateService()
            } else {
                // Client lost heartbeat from Host for >= 10s -> terminate client session cleanly!
                teardownSession(showEndedBanner = true, sendGoodbye = false)
                return
            }
        } else if (check.floorExpiredLeaseId != null) {
            syncUiFromSessionManager()
            serviceBridge.startOrUpdateService()
        }

        // 2. Send fresh HeartbeatPing with real packet loss measurement
        val seq = heartbeatSeq.getAndIncrement()
        val myLoss = audioEngine.measuredPacketLossPct()
        val ping = PttProtocol.ControlFrame.HeartbeatPing(sm.localParticipantId, seq, now, myLoss)
        if (_state.value.isHost) {
            broadcastToAllClients(ping)
            // Periodically sync RTT/loss metrics across multi-guest roster
            if (seq % 2 == 0 && hostClients.isNotEmpty()) {
                broadcastHostRoster()
            }
        } else {
            clientControlConn?.sendAsync(ping)
        }
    }

    private fun armConnectTimeout(timeoutMs: Long) {
        cancelConnectTimeout()
        connectTimeoutFuture = scheduler.schedule({
            val ph = _state.value.phase
            if (ph == PttPhase.CONNECTING || ph == PttPhase.WAITING_APPROVAL) {
                teardownSession(showEndedBanner = false, sendGoodbye = false, postError = PttErrorCode.CONNECT_TIMEOUT)
            }
        }, timeoutMs, TimeUnit.MILLISECONDS)
    }

    private fun cancelConnectTimeout() {
        connectTimeoutFuture?.cancel(false)
        connectTimeoutFuture = null
    }

    private fun broadcastHostRoster() {
        val sm = sessionManager ?: return
        val frame = PttProtocol.ControlFrame.RosterSync(negotiatedCodec.id, sm.exportWireRoster())
        broadcastToAllClients(frame)
    }

    private fun broadcastToAllClients(frame: PttProtocol.ControlFrame) {
        for (c in hostClients.values) {
            c.sendAsync(frame)
        }
    }

    private fun refreshPendingApprovalsState() {
        val list = pendingJoinMap.values.map { it.approval }
        updateState { it.copy(pendingApprovals = list) }
    }

    private fun syncUiFromSessionManager() {
        val sm = sessionManager ?: return
        val parts = sm.snapshotParticipants()
        val btn = sm.currentVisualState()
        val spkId = sm.currentSpeakerId()
        val spkName = sm.currentSpeakerName()
        val cnt = sm.connectedCount()
        val codec = negotiatedCodec
        updateState {
            it.copy(
                buttonState = btn,
                activeSpeakerId = spkId,
                activeSpeakerName = spkName,
                negotiatedCodec = codec,
                participants = parts,
                connectedCount = cnt
            )
        }
    }

    private inline fun updateState(crossinline transform: (PttUiState) -> PttUiState) {
        synchronized(stateLock) {
            _state.value = transform(_state.value)
        }
    }

    private fun mapWifiIssue(issue: WifiEnvironmentIssue): PttErrorCode = when (issue) {
        WifiEnvironmentIssue.NONE -> PttErrorCode.NONE
        WifiEnvironmentIssue.P2P_UNSUPPORTED -> PttErrorCode.P2P_UNSUPPORTED
        WifiEnvironmentIssue.WIFI_DISABLED -> PttErrorCode.WIFI_DISABLED
        WifiEnvironmentIssue.LOCATION_TOGGLE_OFF -> PttErrorCode.LOCATION_SERVICE_OFF
        WifiEnvironmentIssue.MISSING_PERMISSION -> PttErrorCode.PERMISSION_MISSING
    }
}
