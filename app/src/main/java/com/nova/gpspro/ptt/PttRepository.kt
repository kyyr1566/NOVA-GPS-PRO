package com.nova.gpspro.ptt

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable
import java.io.IOException
import java.net.ConnectException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Explicit, strictly ordered Walkie-Talkie lifecycle phases:
 * DISCONNECTED -> CHECKING -> HOSTING / SCANNING -> CONNECTING -> WAITING_APPROVAL -> IN_SESSION
 */
enum class PttPhase {
    DISCONNECTED,
    CHECKING,
    HOSTING,
    SCANNING,
    CONNECTING,
    WAITING_APPROVAL,
    IN_SESSION
}

enum class PttDiscoveryState {
    IDLE,
    SEARCHING,
    FOUND,
    FAILED
}

enum class PttErrorCode {
    NONE,
    WIFI_DISABLED,
    LOCATION_SERVICE_OFF,
    PERMISSION_MISSING,
    P2P_UNSUPPORTED,
    HOSTING_FAILED,
    DISCOVERY_FAILED,
    HOTSPOT_START_FAILED,
    HOTSPOT_NOT_CONNECTED,
    P2P_LINK_FAILED,
    PROTOCOL_INCOMPATIBLE,
    AUTH_FAILED,
    REJECTED_BY_HOST,
    APPROVAL_TIMEOUT,
    CONNECT_TIMEOUT,
    CONNECTION_REFUSED
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
    val discoveryState: PttDiscoveryState = PttDiscoveryState.IDLE,
    val connectingRadioLinked: Boolean = false,
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
    val errorCode: PttErrorCode = PttErrorCode.NONE,
    val diagnosticMessage: String = ""
) {
    val isActiveSessionOrConnecting: Boolean
        get() = phase == PttPhase.HOSTING ||
            phase == PttPhase.CONNECTING ||
            phase == PttPhase.WAITING_APPROVAL ||
            phase == PttPhase.IN_SESSION
}

interface PttServiceBridge {
    fun startOrUpdateService()
    fun stopService()
}

interface PttPermissionGate {
    fun hasAllCorePermissions(): Boolean
    fun hasRecordAudioPermission(): Boolean
}

interface PttAudioController {
    fun localSupportedCodecMask(): Int
    fun startReceiveSession()
    fun onIncomingVoicePacket(packet: PttProtocol.UdpVoicePacket)
    fun startTransmit(
        codec: AudioCodecType,
        rogerBeep: Boolean,
        onEncodedFrame: (AudioCodecType, Int, Long, Boolean, ByteArray) -> Unit,
        onCaptureError: () -> Unit
    )
    fun stopTransmit(playEndBeep: Boolean)
    fun setLocalListenMuted(muted: Boolean)
    fun playRogerEndToneIfEnabled(enabled: Boolean)
    fun measuredPacketLossPct(): Int
    fun releaseAll()
}

private class AndroidPermissionGate(private val ctx: Context) : PttPermissionGate {
    override fun hasAllCorePermissions(): Boolean = PttPermissions.missingCorePermissions(ctx).isEmpty()
    override fun hasRecordAudioPermission(): Boolean = PttPermissions.hasRecordAudio(ctx)
}

private class AndroidAudioControllerAdapter(ctx: Context) : PttAudioController {
    private val engine = AudioEngine(ctx)
    override fun localSupportedCodecMask(): Int = engine.localSupportedCodecMask()
    override fun startReceiveSession() = engine.startReceiveSession()
    override fun onIncomingVoicePacket(packet: PttProtocol.UdpVoicePacket) = engine.onIncomingVoicePacket(packet)
    override fun startTransmit(
        codec: AudioCodecType,
        rogerBeep: Boolean,
        onEncodedFrame: (AudioCodecType, Int, Long, Boolean, ByteArray) -> Unit,
        onCaptureError: () -> Unit
    ) = engine.startTransmit(codec, rogerBeep, onEncodedFrame, onCaptureError)
    override fun stopTransmit(playEndBeep: Boolean) = engine.stopTransmit(playEndBeep)
    override fun setLocalListenMuted(muted: Boolean) = engine.setLocalListenMuted(muted)
    override fun playRogerEndToneIfEnabled(enabled: Boolean) = engine.playRogerEndToneIfEnabled(enabled)
    override fun measuredPacketLossPct(): Int = engine.measuredPacketLossPct()
    override fun releaseAll() = engine.releaseAll()
}

/**
 * Application-scoped repository owning the entire Walkie-Talkie lifecycle via thread-safe [StateFlow].
 *
 * Key architectural guarantees:
 *  1. Pre-flight verification (CHECKING) runs BEFORE binding sockets, starting AudioEngine, or starting PttService.
 *  2. Hosting enters HOSTING first and transitions to IN_SESSION ("Session ready") ONLY after the Wi-Fi Direct
 *     group & DNS-SD service (or Local-Only Hotspot) have genuinely succeeded.
 *  3. ForegroundService is NEVER started until phase == IN_SESSION, eliminating ForegroundServiceDidNotStartInTimeException.
 *  4. Local-Only Hotspot join strictly distinguishes real Wi-Fi OFF from "not connected to hotspot gateway".
 */
class PttRepository(
    private val appCtx: Context?,
    val settings: PttSettingsStore = PttSettings(appCtx!!),
    private val serviceBridge: PttServiceBridge = DefaultPttServiceBridge(appCtx!!),
    val wifiDirect: PttRadioTransport = WifiDirectManager(appCtx!!),
    private val permissionGate: PttPermissionGate = AndroidPermissionGate(appCtx!!),
    private val audioEngine: PttAudioController = AndroidAudioControllerAdapter(appCtx!!),
    private val uiPoster: (Runnable) -> Unit = { r -> Handler(Looper.getMainLooper()).post(r) },
    private val clockMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val preferredHostTcpPort: Int = PttProtocol.DEFAULT_TCP_PORT,
    private val preferredHostUdpPort: Int = PttProtocol.DEFAULT_UDP_PORT
) {
    companion object {
        private const val HEARTBEAT_INTERVAL_MS = 2_500L
        private const val HOSTING_SETUP_TIMEOUT_MS = 14_000L
        private const val P2P_RADIO_LINK_TIMEOUT_MS = 22_000L
        private const val TCP_HANDSHAKE_TIMEOUT_MS = 18_000L
        private const val APPROVAL_TIMEOUT_MS = 38_000L
    }

    private class DefaultPttServiceBridge(private val ctx: Context) : PttServiceBridge {
        override fun startOrUpdateService() = PttService.startOrUpdate(ctx)
        override fun stopService() = PttService.stop(ctx)
    }

    private val ioExecutor = Executors.newCachedThreadPool { r ->
        Thread(r, "ptt-net-io").apply { isDaemon = true }
    }
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "ptt-watchdog").apply { isDaemon = true }
    }

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
    private val sessionOperationGen = AtomicInteger(0)
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
    private var pendingJoinViaHotspot: Boolean = false
    private val tcpDialInProgress = AtomicBoolean(false)

    // Shared UDP voice socket
    private var udpSocket: DatagramSocket? = null
    private val udpRunning = AtomicBoolean(false)

    // Timers & foreground service tracking
    private var maintenanceFuture: ScheduledFuture<*>? = null
    private var connectTimeoutFuture: ScheduledFuture<*>? = null
    private val heartbeatSeq = AtomicInteger(1)
    private val tearingDown = AtomicBoolean(false)
    private val serviceStartedForCurrentSession = AtomicBoolean(false)

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
        wifiDirect.listener = object : PttRadioTransport.Listener {
            override fun onSessionsDiscovered(sessions: List<DiscoveredPttSession>) {
                updateState { cur ->
                    if (cur.phase == PttPhase.SCANNING) {
                        cur.copy(
                            discoveredSessions = sessions,
                            discoveryState = if (sessions.isNotEmpty()) PttDiscoveryState.FOUND else PttDiscoveryState.SEARCHING,
                            errorCode = if (sessions.isNotEmpty()) PttErrorCode.NONE else cur.errorCode
                        )
                    } else cur
                }
            }

            override fun onDiscoveryFinishedEmpty() {
                updateState { cur ->
                    if (cur.phase == PttPhase.SCANNING && cur.discoveredSessions.isEmpty()) {
                        cur.copy(
                            phase = PttPhase.DISCONNECTED,
                            discoveryState = PttDiscoveryState.FAILED,
                            errorCode = PttErrorCode.DISCOVERY_FAILED,
                            diagnosticMessage = PttDiagnostics.lastSummary
                        )
                    } else if (cur.phase == PttPhase.SCANNING) {
                        // Sessions were found earlier; stop active spinner while keeping found sessions visible
                        cur.copy(
                            phase = PttPhase.DISCONNECTED,
                            discoveryState = PttDiscoveryState.FOUND
                        )
                    } else cur
                }
            }

            override fun onP2pLinkEstablished(isGroupOwner: Boolean, groupOwnerIp: String) {
                val cur = _state.value
                if (cur.phase == PttPhase.CONNECTING && !cur.isHost) {
                    val port = pendingTargetSession?.tcpPort ?: PttProtocol.DEFAULT_TCP_PORT
                    PttDiagnostics.info("ClientJoin", "P2P link ready -> dialing TCP control $groupOwnerIp:$port")
                    updateState { it.copy(connectingRadioLinked = true) }
                    armConnectTimeout(TCP_HANDSHAKE_TIMEOUT_MS, timeoutError = PttErrorCode.CONNECT_TIMEOUT)
                    dialHostControlChannel(groupOwnerIp, port)
                }
            }

            override fun onP2pLinkDisconnected() {
                val cur = _state.value
                if (!cur.isHost && cur.isActiveSessionOrConnecting) {
                    PttDiagnostics.warn("ClientJoin", "Established P2P link disconnected; tearing down client session")
                    val wasEstablished = cur.phase == PttPhase.IN_SESSION
                    val err = when {
                        wasEstablished -> PttErrorCode.NONE
                        cur.connectingRadioLinked -> PttErrorCode.CONNECT_TIMEOUT
                        else -> PttErrorCode.P2P_LINK_FAILED
                    }
                    teardownSession(
                        showEndedBanner = wasEstablished,
                        sendGoodbye = false,
                        postError = err,
                        diagnostic = "[P2P-Link] Radio link disconnected"
                    )
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

            override fun onTransportFailure(issue: WifiEnvironmentIssue, diagnostic: String) {
                val err = mapWifiIssue(issue)
                val cur = _state.value
                if (cur.phase != PttPhase.DISCONNECTED) {
                    val wasInLiveSession = cur.phase == PttPhase.IN_SESSION
                    teardownSession(
                        showEndedBanner = wasInLiveSession,
                        sendGoodbye = false,
                        postError = err,
                        diagnostic = diagnostic
                    )
                } else {
                    updateState {
                        it.copy(
                            errorCode = err,
                            diagnosticMessage = diagnostic.ifBlank { PttDiagnostics.lastSummary }
                        )
                    }
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
        updateState {
            it.copy(
                errorCode = PttErrorCode.NONE,
                showSessionEndedBanner = false,
                diagnosticMessage = ""
            )
        }
    }

    // ==================== 1. Host Session Flow (CHECKING -> HOSTING -> IN_SESSION) ====================

    /**
     * Strictly sequenced Host session creation:
     *  Step 1 (CHECKING): Validates name, channel code, permissions, Wi-Fi state, and P2P hardware
     *                     BEFORE opening any sockets, starting AudioEngine, or touching ForegroundService.
     *  Step 2 (HOSTING):  Binds TCP/UDP sockets and requests Wi-Fi Direct group + DNS-SD advertisement
     *                     (or Local-Only Hotspot). UI shows "Initializing hosting..." — NOT "Session ready".
     *  Step 3 (IN_SESSION): Triggered ONLY after groupFormed & service advertisement succeed. Starts
     *                       AudioEngine receive loop, heartbeat watchdog, and PttService notification.
     */
    fun createHostSession(rawChannelCode: String, useLocalHotspotFallback: Boolean = false): Boolean {
        val name = settings.displayName.trim()
        if (name.isEmpty()) return false
        if (!PttSettings.isValidChannelCode(rawChannelCode)) {
            updateState { it.copy(phase = PttPhase.DISCONNECTED, errorCode = PttErrorCode.AUTH_FAILED) }
            return false
        }

        // Clean any leftover session state before pre-flight checks
        teardownSession(showEndedBanner = false, sendGoodbye = false)

        val code = PttSettings.normalizeChannelCode(rawChannelCode)
        settings.lastChannelCode = code

        // ---- Phase 1: CHECKING (zero sockets / audio / service started yet!) ----
        updateState {
            it.copy(
                phase = PttPhase.CHECKING,
                discoveryState = PttDiscoveryState.IDLE,
                isHost = true,
                displayName = name,
                channelCode = code,
                discoveredSessions = emptyList(),
                showSessionEndedBanner = false,
                errorCode = PttErrorCode.NONE,
                diagnosticMessage = ""
            )
        }

        if (!permissionGate.hasAllCorePermissions()) {
            PttDiagnostics.warn("HostCreate", "Aborted in CHECKING: missing core permissions")
            updateState {
                it.copy(
                    phase = PttPhase.DISCONNECTED,
                    isHost = false,
                    errorCode = PttErrorCode.PERMISSION_MISSING,
                    diagnosticMessage = PttDiagnostics.lastSummary
                )
            }
            return false
        }

        // Check Wi-Fi enabled FIRST even for LocalHotspot so Wi-Fi OFF is handled cleanly without crash
        if (!wifiDirect.isWifiEnabled()) {
            PttDiagnostics.warn("HostCreate", "Aborted in CHECKING: Wi-Fi is turned off")
            updateState {
                it.copy(
                    phase = PttPhase.DISCONNECTED,
                    isHost = false,
                    errorCode = PttErrorCode.WIFI_DISABLED,
                    diagnosticMessage = "Wi-Fi is disabled"
                )
            }
            return false
        }

        if (!useLocalHotspotFallback) {
            val envIssue = wifiDirect.diagnoseEnvironment()
            if (envIssue != WifiEnvironmentIssue.NONE) {
                val mapped = mapWifiIssue(envIssue)
                PttDiagnostics.warn("HostCreate", "Aborted in CHECKING: envIssue=$envIssue")
                updateState {
                    it.copy(
                        phase = PttPhase.DISCONNECTED,
                        isHost = false,
                        errorCode = mapped,
                        diagnosticMessage = PttDiagnostics.lastSummary
                    )
                }
                return false
            }
        }

        // ---- Phase 2: HOSTING (bind sockets & bring up P2P group + DNS-SD) ----
        val opGen = sessionOperationGen.incrementAndGet()
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
            micForegroundEligibleInSession = isAppInForeground && permissionGate.hasRecordAudioPermission()
        }

        updateState {
            it.copy(
                phase = PttPhase.HOSTING,
                isHost = true,
                displayName = name,
                channelCode = code,
                negotiatedCodec = initialCodec,
                errorCode = PttErrorCode.NONE
            )
        }

        if (!startHostSockets()) {
            PttDiagnostics.error("HostCreate", "Failed to bind host TCP/UDP sockets")
            teardownSession(
                showEndedBanner = false,
                sendGoodbye = false,
                postError = PttErrorCode.HOSTING_FAILED,
                diagnostic = "Failed to bind local sockets"
            )
            return false
        }

        armConnectTimeout(HOSTING_SETUP_TIMEOUT_MS, timeoutError = PttErrorCode.HOSTING_FAILED)
        val actualTcpPort = tcpServer?.localPort ?: preferredHostTcpPort

        val onHostingReady = { hotspotCreds: LocalHotspotCredentials? ->
            if (sessionOperationGen.get() == opGen && _state.value.phase == PttPhase.HOSTING) {
                cancelConnectTimeout()
                // ---- Phase 3: IN_SESSION (NOW and ONLY NOW start audio, watchdog, and ForegroundService!) ----
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
                        hotspotInfo = hotspotCreds ?: it.hotspotInfo,
                        showSessionEndedBanner = false,
                        errorCode = PttErrorCode.NONE,
                        diagnosticMessage = ""
                    )
                }
                startOrRefreshForegroundServiceSafely()
            }
        }

        if (useLocalHotspotFallback) {
            wifiDirect.startLocalOnlyHotspotFallback(
                onReady = { ssid, pass ->
                    onHostingReady(LocalHotspotCredentials(ssid, pass))
                },
                onError = { issue, diag ->
                    if (sessionOperationGen.get() == opGen) {
                        teardownSession(
                            showEndedBanner = false,
                            sendGoodbye = false,
                            postError = mapWifiIssue(issue),
                            diagnostic = diag
                        )
                    }
                }
            )
        } else {
            wifiDirect.startHostingGroup(
                sessionId = sid,
                hostDisplayName = name,
                tcpPort = actualTcpPort,
                onReady = { _ ->
                    onHostingReady(null)
                },
                onError = { issue, diag ->
                    if (sessionOperationGen.get() == opGen) {
                        teardownSession(
                            showEndedBanner = false,
                            sendGoodbye = false,
                            postError = mapWifiIssue(issue),
                            diagnostic = diag
                        )
                    }
                }
            )
        }
        return true
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
                    if (negotiated == AudioCodecType.ADPCM_16K || (hostClients.isNotEmpty() && negotiatedCodec == AudioCodecType.ADPCM_16K)) {
                        negotiatedCodec = AudioCodecType.ADPCM_16K
                    } else {
                        negotiatedCodec = negotiated
                    }
                }
                val now = clockMs()
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
                val localUdpPort = udpSocket?.localPort ?: preferredHostUdpPort

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
                pending.socket.soTimeout = 0

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

                val curSpk = sm.currentSpeakerId()
                val curName = sm.currentSpeakerName()
                if (curSpk != null && curName != null) {
                    clientConn.sendAsync(PttProtocol.ControlFrame.FloorTaken(curSpk, curName, sm.currentLeaseId()))
                }

                syncUiFromSessionManager()
                startOrRefreshForegroundServiceSafely()
                runHostClientReadLoop(clientConn)
            } catch (t: Throwable) {
                PttDiagnostics.warn("HostApprove", "Failed completing client join approval", t)
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

        teardownSession(showEndedBanner = false, sendGoodbye = false)

        updateState {
            it.copy(
                phase = PttPhase.CHECKING,
                discoveryState = PttDiscoveryState.IDLE,
                isHost = false,
                discoveredSessions = emptyList(),
                showSessionEndedBanner = false,
                errorCode = PttErrorCode.NONE,
                diagnosticMessage = ""
            )
        }

        if (!permissionGate.hasAllCorePermissions()) {
            updateState {
                it.copy(
                    phase = PttPhase.DISCONNECTED,
                    discoveryState = PttDiscoveryState.FAILED,
                    errorCode = PttErrorCode.PERMISSION_MISSING
                )
            }
            return false
        }

        val env = wifiDirect.diagnoseEnvironment()
        if (env != WifiEnvironmentIssue.NONE) {
            updateState {
                it.copy(
                    phase = PttPhase.DISCONNECTED,
                    discoveryState = PttDiscoveryState.FAILED,
                    errorCode = mapWifiIssue(env),
                    diagnosticMessage = PttDiagnostics.lastSummary
                )
            }
            return false
        }

        updateState {
            it.copy(
                phase = PttPhase.SCANNING,
                discoveryState = PttDiscoveryState.SEARCHING,
                isHost = false,
                discoveredSessions = emptyList(),
                showSessionEndedBanner = false,
                errorCode = PttErrorCode.NONE,
                diagnosticMessage = ""
            )
        }

        return wifiDirect.startSessionDiscovery(
            onStarted = {
                updateState { cur ->
                    if (cur.phase == PttPhase.SCANNING) cur.copy(discoveryState = PttDiscoveryState.SEARCHING) else cur
                }
            },
            onError = { issue, diag ->
                updateState {
                    it.copy(
                        phase = PttPhase.DISCONNECTED,
                        discoveryState = PttDiscoveryState.FAILED,
                        errorCode = mapWifiIssue(issue),
                        diagnosticMessage = diag.ifBlank { PttDiagnostics.lastSummary }
                    )
                }
            }
        )
    }

    fun stopScanningSessions() {
        wifiDirect.stopSessionDiscovery()
        updateState { cur ->
            if (cur.phase == PttPhase.SCANNING) {
                cur.copy(
                    phase = PttPhase.DISCONNECTED,
                    discoveryState = if (cur.discoveredSessions.isNotEmpty()) PttDiscoveryState.FOUND else PttDiscoveryState.IDLE
                )
            } else cur
        }
    }

    fun requestJoinDiscoveredSession(session: DiscoveredPttSession, rawChannelCode: String): Boolean {
        val name = settings.displayName.trim()
        if (name.isEmpty()) return false
        if (!PttSettings.isValidChannelCode(rawChannelCode)) {
            updateState { it.copy(errorCode = PttErrorCode.AUTH_FAILED) }
            return false
        }
        if (!permissionGate.hasAllCorePermissions()) {
            updateState { it.copy(errorCode = PttErrorCode.PERMISSION_MISSING) }
            return false
        }
        if (!wifiDirect.isWifiEnabled()) {
            updateState { it.copy(phase = PttPhase.DISCONNECTED, errorCode = PttErrorCode.WIFI_DISABLED) }
            return false
        }

        wifiDirect.stopSessionDiscovery()
        val code = PttSettings.normalizeChannelCode(rawChannelCode)
        settings.lastChannelCode = code
        sessionOperationGen.incrementAndGet()
        pendingTargetSession = session
        pendingJoinChannelCode = code
        pendingJoinViaHotspot = false
        tcpDialInProgress.set(false)
        micForegroundEligibleInSession = isAppInForeground && permissionGate.hasRecordAudioPermission()

        updateState {
            it.copy(
                phase = PttPhase.CONNECTING,
                connectingRadioLinked = false,
                isHost = false,
                channelCode = code,
                showSessionEndedBanner = false,
                errorCode = PttErrorCode.NONE,
                diagnosticMessage = ""
            )
        }
        armConnectTimeout(P2P_RADIO_LINK_TIMEOUT_MS, timeoutError = PttErrorCode.P2P_LINK_FAILED)

        wifiDirect.connectToSession(
            session = session,
            onInitiated = { /* P2P connection broadcast / link poll will invoke dialHostControlChannel */ },
            onFailed = { issue, diag ->
                val mapped = mapWifiIssue(issue)
                teardownSession(showEndedBanner = false, sendGoodbye = false, postError = mapped, diagnostic = diag)
            }
        )
        return true
    }

    /**
     * Separate, explicit join flow for Local-Only Hotspot fallback ONLY.
     * Clearly distinguishes:
     *  • Real Wi-Fi adapter OFF -> [PttErrorCode.WIFI_DISABLED]
     *  • Wi-Fi ON, but phone not connected to the Host's Local Hotspot -> [PttErrorCode.HOTSPOT_NOT_CONNECTED]
     */
    fun requestJoinLocalHotspotGateway(rawChannelCode: String, explicitPort: Int = preferredHostTcpPort): Boolean {
        val name = settings.displayName.trim()
        if (name.isEmpty()) return false
        if (!PttSettings.isValidChannelCode(rawChannelCode)) {
            updateState { it.copy(errorCode = PttErrorCode.AUTH_FAILED) }
            return false
        }
        if (!permissionGate.hasAllCorePermissions()) {
            updateState { it.copy(errorCode = PttErrorCode.PERMISSION_MISSING) }
            return false
        }

        val gwIp = when (val gwCheck = wifiDirect.checkLocalHotspotGateway()) {
            is LocalHotspotGatewayCheck.WifiTurnedOff -> {
                updateState {
                    it.copy(
                        phase = PttPhase.DISCONNECTED,
                        errorCode = PttErrorCode.WIFI_DISABLED,
                        diagnosticMessage = "Wi-Fi is turned off"
                    )
                }
                return false
            }
            is LocalHotspotGatewayCheck.NotConnectedToHotspot -> {
                updateState {
                    it.copy(
                        phase = PttPhase.DISCONNECTED,
                        errorCode = PttErrorCode.HOTSPOT_NOT_CONNECTED,
                        diagnosticMessage = "No active Wi-Fi Local Hotspot gateway detected"
                    )
                }
                return false
            }
            is LocalHotspotGatewayCheck.Ready -> gwCheck.gatewayIp
        }

        wifiDirect.stopSessionDiscovery()
        val code = PttSettings.normalizeChannelCode(rawChannelCode)
        settings.lastChannelCode = code
        sessionOperationGen.incrementAndGet()
        pendingTargetSession = null
        pendingJoinChannelCode = code
        pendingJoinViaHotspot = true
        tcpDialInProgress.set(false)
        micForegroundEligibleInSession = isAppInForeground && permissionGate.hasRecordAudioPermission()

        updateState {
            it.copy(
                phase = PttPhase.CONNECTING,
                connectingRadioLinked = true,
                isHost = false,
                channelCode = code,
                showSessionEndedBanner = false,
                errorCode = PttErrorCode.NONE,
                diagnosticMessage = ""
            )
        }
        armConnectTimeout(TCP_HANDSHAKE_TIMEOUT_MS, timeoutError = PttErrorCode.CONNECT_TIMEOUT)
        dialHostControlChannel(gwIp, explicitPort)
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
                startOrRefreshForegroundServiceSafely()
            }
        } else {
            sm.releaseFloor(myId, activeLease)
            syncUiFromSessionManager()
            startOrRefreshForegroundServiceSafely()
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
            startOrRefreshForegroundServiceSafely()
        }
    }

    fun toggleLocalAudioMutedFromNotification() {
        setLocalAudioMuted(!_state.value.localAudioMuted)
    }

    private fun requestTransmitFloor() {
        val sm = sessionManager ?: return
        if (!permissionGate.hasRecordAudioPermission()) {
            updateState { it.copy(errorCode = PttErrorCode.PERMISSION_MISSING) }
            return
        }
        if (isAppInForeground) {
            micForegroundEligibleInSession = true
        }
        val myId = sm.localParticipantId
        if (_state.value.isHost) {
            when (val dec = sm.arbitrateFloorRequest(myId, clockMs())) {
                is FloorDecision.Granted -> {
                    broadcastToAllClients(PttProtocol.ControlFrame.FloorTaken(dec.speakerId, dec.speakerName, dec.leaseId))
                    syncUiFromSessionManager()
                    startOrRefreshForegroundServiceSafely()
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
                uiPoster { onTalkReleasedOrCancelled() }
            }
        )
    }

    // ==================== 4. Unified Clean Teardown (Idempotent & Safe) ====================

    fun endSessionByUser() {
        teardownSession(showEndedBanner = false, sendGoodbye = true)
    }

    fun onSystemServiceForcedTimeout() {
        teardownSession(showEndedBanner = true, sendGoodbye = true)
    }

    fun teardownSession(
        showEndedBanner: Boolean,
        sendGoodbye: Boolean,
        postError: PttErrorCode = PttErrorCode.NONE,
        diagnostic: String = ""
    ) {
        if (!tearingDown.compareAndSet(false, true)) return
        try {
            sessionOperationGen.incrementAndGet()

            // 1. Stop transmit immediately
            audioEngine.stopTransmit(playEndBeep = false)

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
            pendingJoinViaHotspot = false

            // 2. Cancel discovery, connection waits, and periodic timers
            cancelConnectTimeout()
            maintenanceFuture?.cancel(true)
            maintenanceFuture = null

            // 3. Send polite session termination frame (bounded 200ms) & close all sockets
            if (sendGoodbye && (clientsSnapshot.isNotEmpty() || clientConnSnapshot != null)) {
                val goodbyeLatch = CountDownLatch(1)
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

            // 4. Release AudioRecord, AudioTrack, MediaCodec, and buffers
            audioEngine.releaseAll()

            // 5. Remove owned Wi-Fi Direct group / stop discovery / close Local Hotspot / unregister receiver
            wifiDirect.teardownRadio()

            // 6. Stop Foreground Service ONLY if it was actually started (plus defensive cancel)
            if (serviceStartedForCurrentSession.getAndSet(false)) {
                serviceBridge.stopService()
            }

            // 7. Wipe internal session keys & participant state
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

            // 8. Reset UI StateFlow cleanly to DISCONNECTED
            _state.value = PttUiState(
                phase = PttPhase.DISCONNECTED,
                discoveryState = if (postError == PttErrorCode.DISCOVERY_FAILED) PttDiscoveryState.FAILED else PttDiscoveryState.IDLE,
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
                errorCode = postError,
                diagnosticMessage = diagnostic.ifBlank { if (postError != PttErrorCode.NONE) PttDiagnostics.lastSummary else "" }
            )
        } finally {
            tearingDown.set(false)
        }
    }

    private fun startOrRefreshForegroundServiceSafely() {
        if (_state.value.phase != PttPhase.IN_SESSION) return
        serviceStartedForCurrentSession.set(true)
        serviceBridge.startOrUpdateService()
    }

    // ==================== Internal Networking & Protocol Loops ====================

    private fun startHostSockets(): Boolean {
        return try {
            val srv = bindServerSocketWithRetry(preferredHostTcpPort)
            tcpServer = srv

            val udp = bindDatagramSocketWithRetry(preferredHostUdpPort)
            udpSocket = udp
            udpRunning.set(true)

            ioExecutor.execute { acceptHostClientsLoop(srv) }
            ioExecutor.execute { receiveUdpLoop(udp, isHostMode = true) }
            true
        } catch (t: Throwable) {
            PttDiagnostics.error("Sockets", "Failed binding host sockets", t)
            try { tcpServer?.close() } catch (_: Throwable) {}
            try { udpSocket?.close() } catch (_: Throwable) {}
            tcpServer = null
            udpSocket = null
            udpRunning.set(false)
            false
        }
    }

    private fun bindServerSocketWithRetry(targetPort: Int): ServerSocket {
        var lastErr: Throwable? = null
        for (attempt in 1..3) {
            try {
                val s = ServerSocket()
                s.reuseAddress = true
                s.bind(InetSocketAddress(targetPort))
                return s
            } catch (t: Throwable) {
                lastErr = t
                if (targetPort == 0) break
                try { Thread.sleep(70L) } catch (_: InterruptedException) {}
            }
        }
        // Fallback to any ephemeral free port (advertised in DNS-SD TXT "port") if preferred port is held
        val fallback = ServerSocket()
        fallback.reuseAddress = true
        try {
            fallback.bind(InetSocketAddress(0))
            return fallback
        } catch (t: Throwable) {
            try { fallback.close() } catch (_: Throwable) {}
            throw (lastErr ?: t)
        }
    }

    private fun bindDatagramSocketWithRetry(targetPort: Int): DatagramSocket {
        for (attempt in 1..3) {
            try {
                return DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(targetPort))
                }
            } catch (_: Throwable) {
                if (targetPort == 0) break
                try { Thread.sleep(70L) } catch (_: InterruptedException) {}
            }
        }
        return DatagramSocket(0)
    }

    private fun acceptHostClientsLoop(srv: ServerSocket) {
        while (!srv.isClosed) {
            val ph = _state.value.phase
            if ((ph != PttPhase.HOSTING && ph != PttPhase.IN_SESSION) || !_state.value.isHost) break
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

            // If client dials milliseconds before host transitions from HOSTING -> IN_SESSION, wait briefly
            var waitSlices = 0
            while (_state.value.phase == PttPhase.HOSTING && waitSlices < 15) {
                Thread.sleep(100L)
                waitSlices++
            }

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
        } catch (t: Throwable) {
            PttDiagnostics.warn("Handshake", "Incoming join handshake failed", t)
            try { socket.close() } catch (_: Throwable) {}
        }
    }

    private fun runHostClientReadLoop(conn: HostClientConn) {
        try {
            while (conn.alive.get() && _state.value.phase == PttPhase.IN_SESSION) {
                val frame = PttProtocol.readControlFrame(conn.socket.getInputStream(), macKey = sessionKey, requireMac = true)
                val sm = sessionManager ?: break
                val now = clockMs()
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
                                startOrRefreshForegroundServiceSafely()
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
                            startOrRefreshForegroundServiceSafely()
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
            startOrRefreshForegroundServiceSafely()
        }
    }

    private fun dialHostControlChannel(hostIp: String, tcpPort: Int) {
        if (!tcpDialInProgress.compareAndSet(false, true)) return
        ioExecutor.execute {
            var sock: Socket? = null
            try {
                val ipCandidates = LinkedHashSet<String>().apply {
                    if (hostIp.isNotBlank() && hostIp != "0.0.0.0") add(hostIp)
                    val hint = pendingTargetSession?.groupOwnerIpHint.orEmpty()
                    if (hint.isNotBlank() && hint != "0.0.0.0") add(hint)
                    if (!pendingJoinViaHotspot && hostIp != "127.0.0.1" && hostIp != "localhost") {
                        add("192.168.49.1")
                    }
                }.toList()

                // Wait briefly (up to 3.0s) for DHCP on p2p-wlan0-* / wlan0 to assign a local subnet IPv4 address
                if (ipCandidates.none { it == "127.0.0.1" || it == "localhost" }) {
                    var waitedMs = 0L
                    while (waitedMs < 3_000L && _state.value.phase == PttPhase.CONNECTING) {
                        if (ipCandidates.any { findLocalInterfaceAddressForTarget(it) != null }) break
                        Thread.sleep(200L)
                        waitedMs += 200L
                    }
                }

                var connected: Socket? = null
                var connectedTargetIp: String = hostIp
                var lastConnectErr: Throwable? = null

                outer@ for (attempt in 1..7) {
                    if (_state.value.phase != PttPhase.CONNECTING) return@execute
                    if (!wifiDirect.isWifiEnabled()) {
                        teardownSession(showEndedBanner = false, sendGoodbye = false, postError = PttErrorCode.WIFI_DISABLED)
                        return@execute
                    }
                    for (candidateIp in ipCandidates) {
                        if (_state.value.phase != PttPhase.CONNECTING) return@execute
                        try {
                            val candidate = Socket()
                            candidate.tcpNoDelay = true
                            val boundViaNet = wifiDirect.bindSocketToRadioNetwork(
                                socket = candidate,
                                remoteIp = candidateIp,
                                viaLocalHotspot = pendingJoinViaHotspot
                            )
                            if (!boundViaNet) {
                                bindSocketToMatchingP2pSubnetIfPresent(candidate, candidateIp)
                            }
                            PttDiagnostics.info("ClientDial", "Attempt $attempt -> TCP $candidateIp:$tcpPort (netBound=$boundViaNet)")
                            candidate.connect(InetSocketAddress(candidateIp, tcpPort), 2_100)
                            connected = candidate
                            connectedTargetIp = candidateIp
                            break@outer
                        } catch (t: Throwable) {
                            lastConnectErr = t
                            PttDiagnostics.warn("ClientDial", "Attempt $attempt to $candidateIp:$tcpPort failed: ${t.javaClass.simpleName}: ${t.message}")
                        }
                    }
                    Thread.sleep(450L)
                }

                sock = connected ?: run {
                    val errCode = when {
                        !wifiDirect.isWifiEnabled() -> PttErrorCode.WIFI_DISABLED
                        lastConnectErr is ConnectException && pendingTargetSession?.discoveredViaFallback == true ->
                            PttErrorCode.PROTOCOL_INCOMPATIBLE
                        lastConnectErr is ConnectException -> PttErrorCode.CONNECTION_REFUSED
                        else -> PttErrorCode.CONNECT_TIMEOUT
                    }
                    val diag = "[TCP-Dial $hostIp:$tcpPort] ${lastConnectErr?.javaClass?.simpleName ?: "Timeout"}: ${lastConnectErr?.message.orEmpty()}"
                    PttDiagnostics.error("ClientDial", diag, lastConnectErr)
                    teardownSession(
                        showEndedBanner = false,
                        sendGoodbye = false,
                        postError = errCode,
                        diagnostic = diag
                    )
                    return@execute
                }
                sock.soTimeout = 10_000
                PttDiagnostics.info("ClientDial", "TCP connected to $connectedTargetIp:$tcpPort; awaiting HelloChallenge")

                val localUdpBindAddr = findLocalInterfaceAddressForTarget(connectedTargetIp)
                val udp = if (localUdpBindAddr != null && connectedTargetIp != "127.0.0.1" && connectedTargetIp != "localhost") {
                    try {
                        DatagramSocket(InetSocketAddress(localUdpBindAddr, 0))
                    } catch (_: Throwable) {
                        DatagramSocket(0)
                    }
                } else {
                    DatagramSocket(0)
                }
                wifiDirect.bindDatagramToRadioNetwork(udp, connectedTargetIp, pendingJoinViaHotspot)
                udpSocket = udp
                udpRunning.set(true)

                val challenge = try {
                    PttProtocol.readControlFrame(sock.getInputStream(), macKey = null, requireMac = false)
                } catch (ioe: IOException) {
                    val diag = "[TCP-Challenge] Invalid protocol header from $connectedTargetIp:$tcpPort: ${ioe.message}"
                    PttDiagnostics.error("ClientDial", diag, ioe)
                    teardownSession(
                        showEndedBanner = false,
                        sendGoodbye = false,
                        postError = PttErrorCode.PROTOCOL_INCOMPATIBLE,
                        diagnostic = diag
                    )
                    return@execute
                }

                if (challenge !is PttProtocol.ControlFrame.HelloChallenge || challenge.protocolVersion != PttProtocol.PROTOCOL_VERSION) {
                    teardownSession(
                        showEndedBanner = false,
                        sendGoodbye = false,
                        postError = PttErrorCode.PROTOCOL_INCOMPATIBLE,
                        diagnostic = "[Protocol] Version mismatch"
                    )
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
                PttDiagnostics.info("ClientDial", "JoinRequest sent; awaiting channel auth & host approval")

                var nextFrame = PttProtocol.readControlFrame(sock.getInputStream(), macKey = derivedSessionKey, requireMac = false)
                if (nextFrame is PttProtocol.ControlFrame.JoinPendingApproval) {
                    PttDiagnostics.info("ClientDial", "Channel code verified! Waiting for manual Host approval")
                    updateState { it.copy(phase = PttPhase.WAITING_APPROVAL) }
                    armConnectTimeout(APPROVAL_TIMEOUT_MS, timeoutError = PttErrorCode.APPROVAL_TIMEOUT)
                    sock.soTimeout = APPROVAL_TIMEOUT_MS.toInt()
                    nextFrame = try {
                        PttProtocol.readControlFrame(sock.getInputStream(), macKey = derivedSessionKey, requireMac = false)
                    } catch (ste: java.net.SocketTimeoutException) {
                        teardownSession(
                            showEndedBanner = false,
                            sendGoodbye = false,
                            postError = PttErrorCode.APPROVAL_TIMEOUT,
                            diagnostic = "[Approval] Host did not respond within timeout"
                        )
                        return@execute
                    }
                }

                when (nextFrame) {
                    is PttProtocol.ControlFrame.JoinRejected -> {
                        val err = when (nextFrame.reasonCode) {
                            PttRejectReason.AUTH_FAILED -> PttErrorCode.AUTH_FAILED
                            PttRejectReason.PROTOCOL_MISMATCH -> PttErrorCode.PROTOCOL_INCOMPATIBLE
                            else -> PttErrorCode.REJECTED_BY_HOST
                        }
                        PttDiagnostics.warn("ClientDial", "JoinRejected by host: reason=${nextFrame.reasonCode} -> $err")
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
                        sm.applyAuthoritativeRoster(nextFrame.roster, clockMs())

                        val hostAddr = InetAddress.getByName(connectedTargetIp)
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

                        sendClientUdpPunchPacket(ctrlConn)

                        updateState {
                            it.copy(
                                phase = PttPhase.IN_SESSION,
                                connectingRadioLinked = true,
                                isHost = false,
                                negotiatedCodec = codec,
                                buttonState = sm.currentVisualState(),
                                participants = sm.snapshotParticipants(),
                                connectedCount = sm.connectedCount(),
                                showSessionEndedBanner = false,
                                errorCode = PttErrorCode.NONE,
                                diagnosticMessage = ""
                            )
                        }
                        PttDiagnostics.info("ClientDial", "Client entered IN_SESSION successfully (connectedCount=${sm.connectedCount()})")
                        startOrRefreshForegroundServiceSafely()
                        runClientControlReadLoop(ctrlConn)
                    }

                    else -> {
                        teardownSession(showEndedBanner = false, sendGoodbye = false, postError = PttErrorCode.CONNECT_TIMEOUT)
                    }
                }
            } catch (t: Throwable) {
                PttDiagnostics.warn("ClientDial", "Exception during join handshake", t)
                if (!tearingDown.get() && _state.value.isActiveSessionOrConnecting) {
                    try { sock?.close() } catch (_: Throwable) {}
                    val curPhase = _state.value.phase
                    val err = when {
                        !wifiDirect.isWifiEnabled() -> PttErrorCode.WIFI_DISABLED
                        curPhase == PttPhase.WAITING_APPROVAL -> PttErrorCode.APPROVAL_TIMEOUT
                        else -> PttErrorCode.CONNECT_TIMEOUT
                    }
                    teardownSession(showEndedBanner = false, sendGoodbye = false, postError = err, diagnostic = t.message.orEmpty())
                }
            }
        }
    }

    private fun findLocalInterfaceAddressForTarget(remoteIp: String): Inet4Address? {
        if (remoteIp == "127.0.0.1" || remoteIp == "localhost") return null
        val parts = remoteIp.split('.')
        if (parts.size != 4) return null
        val prefix = "${parts[0]}.${parts[1]}.${parts[2]}."
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return null
            while (ifaces.hasMoreElements()) {
                val nif = ifaces.nextElement()
                if (!nif.isUp || nif.isLoopback) continue
                val addrs = nif.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    val hostAddr = addr.hostAddress
                    if (addr is Inet4Address && !hostAddr.isNullOrBlank() && hostAddr.startsWith(prefix)) {
                        return addr
                    }
                }
            }
        } catch (_: Throwable) {}
        return null
    }

    /**
     * When mobile cellular data is active without internet, Android's default routing table on some
     * OEM devices misroutes 192.168.49.1 packets away from `p2p-wlan0-*`. Binding the client socket
     * to the local IPv4 address sharing the target's /24 subnet guarantees direct P2P routing.
     */
    private fun bindSocketToMatchingP2pSubnetIfPresent(socket: Socket, remoteIp: String) {
        val localAddr = findLocalInterfaceAddressForTarget(remoteIp) ?: return
        try {
            socket.bind(InetSocketAddress(localAddr, 0))
        } catch (_: Throwable) {
            // Safe best-effort bind; default OS routing proceeds if unavailable
        }
    }

    private fun runClientControlReadLoop(conn: ClientControlConn) {
        try {
            while (conn.alive.get() && _state.value.phase == PttPhase.IN_SESSION) {
                val frame = PttProtocol.readControlFrame(conn.socket.getInputStream(), macKey = sessionKey, requireMac = true)
                val sm = sessionManager ?: break
                val now = clockMs()
                sm.snapshotParticipants().firstOrNull { it.isHost }?.let { hostPeer ->
                    sm.recordHeartbeat(hostPeer.id, null, null, now)
                }

                when (frame) {
                    is PttProtocol.ControlFrame.RosterSync -> {
                        negotiatedCodec = AudioCodecType.fromId(frame.negotiatedCodecId)
                        sm.applyAuthoritativeRoster(frame.roster, now)
                        syncUiFromSessionManager()
                        startOrRefreshForegroundServiceSafely()
                    }

                    is PttProtocol.ControlFrame.FloorGranted -> {
                        sm.applyFloorTaken(frame.speakerId, frame.speakerName, frame.leaseId, now)
                        syncUiFromSessionManager()
                        startOrRefreshForegroundServiceSafely()
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
                        startOrRefreshForegroundServiceSafely()
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
                        startOrRefreshForegroundServiceSafely()
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
                    hostClients[senderId]?.let { c ->
                        c.remoteIp = dp.address
                        if (dp.port > 0) c.remoteUdpPort = dp.port
                    }
                }

                if (decoded.payload.isEmpty()) continue

                if (!sm.noteSpeakerAudioPacket(decoded.leaseId, clockMs())) {
                    continue
                }
                if (senderId == sm.localParticipantId) continue

                if (!sm.isPeerLocallyMuted(senderId)) {
                    audioEngine.onIncomingVoicePacket(decoded)
                }

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
                    timestampMs = clockMs(),
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

    internal fun runPeriodicHeartbeatAndTimeoutCheck() {
        if (_state.value.phase != PttPhase.IN_SESSION) return
        val sm = sessionManager ?: return
        val now = clockMs()

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
                for (deadId in check.timedOutParticipantIds) {
                    hostClients.remove(deadId)?.close()
                }
                broadcastHostRoster()
                syncUiFromSessionManager()
                startOrRefreshForegroundServiceSafely()
            } else {
                teardownSession(showEndedBanner = true, sendGoodbye = false)
                return
            }
        } else if (check.floorExpiredLeaseId != null) {
            syncUiFromSessionManager()
            startOrRefreshForegroundServiceSafely()
        }

        val seq = heartbeatSeq.getAndIncrement()
        val myLoss = audioEngine.measuredPacketLossPct()
        val ping = PttProtocol.ControlFrame.HeartbeatPing(sm.localParticipantId, seq, now, myLoss)
        if (_state.value.isHost) {
            broadcastToAllClients(ping)
            if (seq % 2 == 0 && hostClients.isNotEmpty()) {
                broadcastHostRoster()
            }
        } else {
            clientControlConn?.sendAsync(ping)
        }
    }

    private fun armConnectTimeout(timeoutMs: Long, timeoutError: PttErrorCode) {
        cancelConnectTimeout()
        connectTimeoutFuture = scheduler.schedule({
            val ph = _state.value.phase
            if (ph == PttPhase.HOSTING || ph == PttPhase.CONNECTING || ph == PttPhase.WAITING_APPROVAL) {
                teardownSession(showEndedBanner = false, sendGoodbye = false, postError = timeoutError)
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
        WifiEnvironmentIssue.HOSTING_GROUP_FAILED -> PttErrorCode.HOSTING_FAILED
        WifiEnvironmentIssue.DISCOVERY_FAILED -> PttErrorCode.DISCOVERY_FAILED
        WifiEnvironmentIssue.HOTSPOT_START_FAILED -> PttErrorCode.HOTSPOT_START_FAILED
        WifiEnvironmentIssue.P2P_LINK_FAILED -> PttErrorCode.P2P_LINK_FAILED
        WifiEnvironmentIssue.CONNECT_FAILED -> PttErrorCode.CONNECT_TIMEOUT
    }
}
