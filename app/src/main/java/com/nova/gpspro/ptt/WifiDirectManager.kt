package com.nova.gpspro.ptt

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class DiscoveredPttSession(
    val deviceAddress: String,
    val sessionId: String,
    val hostName: String,
    val deviceName: String,
    val tcpPort: Int,
    val protocolVersion: Int,
    val lastSeenMs: Long,
    val discoveredViaFallback: Boolean = false,
    val groupOwnerIpHint: String = "",
    val groupNetworkName: String = "",
    val groupPassphrase: String = ""
)

enum class WifiEnvironmentIssue {
    NONE,
    P2P_UNSUPPORTED,
    WIFI_DISABLED,
    LOCATION_TOGGLE_OFF,
    MISSING_PERMISSION,
    HOSTING_GROUP_FAILED,
    DISCOVERY_FAILED,
    HOTSPOT_START_FAILED,
    P2P_LINK_FAILED,
    CONNECT_FAILED
}

sealed class LocalHotspotGatewayCheck {
    data class Ready(val gatewayIp: String) : LocalHotspotGatewayCheck()
    data object WifiTurnedOff : LocalHotspotGatewayCheck()
    data object NotConnectedToHotspot : LocalHotspotGatewayCheck()
}

/**
 * Transport abstraction allowing deterministic unit & regression testing of [PttRepository]
 * (including simulated Wi-Fi off, callback failures, discovery timeouts, and P2P link drops).
 */
interface PttRadioTransport {
    interface Listener {
        fun onSessionsDiscovered(sessions: List<DiscoveredPttSession>)
        fun onDiscoveryFinishedEmpty()
        fun onP2pLinkEstablished(isGroupOwner: Boolean, groupOwnerIp: String)
        fun onP2pLinkDisconnected()
        fun onLocalHotspotStarted(ssid: String, passphrase: String)
        fun onTransportFailure(issue: WifiEnvironmentIssue, diagnostic: String = "")
    }

    var listener: Listener?

    fun isWifiEnabled(): Boolean
    fun diagnoseEnvironment(): WifiEnvironmentIssue
    fun checkLocalHotspotGateway(): LocalHotspotGatewayCheck

    fun startHostingGroup(
        sessionId: String,
        hostDisplayName: String,
        tcpPort: Int,
        onReady: (groupOwnerIp: String) -> Unit,
        onError: (WifiEnvironmentIssue, String) -> Unit
    )

    fun startLocalOnlyHotspotFallback(
        onReady: (ssid: String, passphrase: String) -> Unit,
        onError: (WifiEnvironmentIssue, String) -> Unit
    )

    fun startSessionDiscovery(
        onStarted: () -> Unit = {},
        onError: (WifiEnvironmentIssue, String) -> Unit
    ): Boolean

    fun stopSessionDiscovery()
    fun isScanning(): Boolean

    fun connectToHostDevice(
        deviceAddress: String,
        onInitiated: () -> Unit,
        onFailed: (WifiEnvironmentIssue, String) -> Unit
    )

    fun connectToSession(
        session: DiscoveredPttSession,
        onInitiated: () -> Unit,
        onFailed: (WifiEnvironmentIssue, String) -> Unit
    ) {
        connectToHostDevice(session.deviceAddress, onInitiated, onFailed)
    }

    /**
     * Binds a TCP [Socket] to the active local Wi-Fi / P2P network so Android's fwmark router
     * never misroutes offline packets over mobile cellular data (4G/5G).
     */
    fun bindSocketToRadioNetwork(socket: Socket, remoteIp: String, viaLocalHotspot: Boolean): Boolean = false

    /**
     * Binds a UDP [DatagramSocket] to the active local Wi-Fi network when operating over Local Hotspot.
     */
    fun bindDatagramToRadioNetwork(socket: DatagramSocket, remoteIp: String, viaLocalHotspot: Boolean): Boolean = false

    fun teardownRadio()
}

/**
 * Low-level P2P driver interface so the exact sequencing logic of [WifiDirectManager]
 * (createGroup -> requestGroupDetails -> addLocalService, and Direct-SSID connect -> MAC fallback)
 * can be verified in JVM unit tests with injected callback failures.
 */
interface P2pDriver {
    data class PeerSnapshot(
        val deviceAddress: String,
        val deviceName: String,
        val isGroupOwner: Boolean,
        val status: Int
    )

    data class GroupLinkState(
        val groupFormed: Boolean,
        val isGroupOwner: Boolean,
        val groupOwnerIp: String?,
        val networkName: String = ""
    )

    data class HostedGroupCredentials(
        val networkName: String,
        val passphrase: String
    )

    val isHardwareSupported: Boolean
    fun isWifiEnabled(): Boolean
    fun hasDiscoveryPermission(): Boolean
    fun isLocationToggleRequiredButOff(): Boolean

    fun ensureInitialized(): Boolean
    fun clearLocalServices(cb: (Boolean, Int) -> Unit)
    fun addDnsSdLocalService(instanceName: String, serviceType: String, txt: Map<String, String>, cb: (Boolean, Int) -> Unit)
    fun removeExistingGroupIfAny(cb: () -> Unit)
    fun createGroup(cb: (Boolean, Int) -> Unit)
    fun requestLinkState(cb: (GroupLinkState?) -> Unit)
    fun requestHostedGroupCredentials(cb: (HostedGroupCredentials?) -> Unit) {
        cb(null)
    }

    fun setDnsSdListeners(
        onService: (instanceName: String, regType: String, deviceAddress: String, deviceName: String) -> Unit,
        onTxt: (fullDomain: String, txt: Map<String, String>, deviceAddress: String, deviceName: String) -> Unit
    )
    fun clearServiceRequests(cb: (Boolean, Int) -> Unit)
    fun addDnsSdServiceRequest(cb: (Boolean, Int) -> Unit)
    fun discoverServices(cb: (Boolean, Int) -> Unit)
    fun discoverPeers(cb: (Boolean, Int) -> Unit)
    fun stopPeerDiscovery()
    fun requestPeers(cb: (List<PeerSnapshot>) -> Unit)

    fun connectWithGroupCredentials(networkName: String, passphrase: String, cb: (Boolean, Int) -> Unit) {
        cb(false, WifiDirectManager.REASON_P2P_UNSUPPORTED)
    }
    fun connect(deviceAddress: String, cb: (Boolean, Int) -> Unit)
    fun cancelConnect()
    fun removeGroupNow()
}

/**
 * Simple scheduler abstraction so sequencing state machines run on Android Main Looper in production
 * and deterministically in JVM unit tests.
 */
interface PttTaskScheduler {
    fun post(task: Runnable)
    fun postDelayed(delayMs: Long, task: Runnable)
    fun remove(task: Runnable)
}

class MainLooperTaskScheduler : PttTaskScheduler {
    private val handler = Handler(Looper.getMainLooper())
    override fun post(task: Runnable) { handler.post(task) }
    override fun postDelayed(delayMs: Long, task: Runnable) { handler.postDelayed(task, delayMs) }
    override fun remove(task: Runnable) { handler.removeCallbacks(task) }
}

/**
 * Production Android Wi-Fi Direct + Local-Only Hotspot transport controller.
 * Strictly zero Bluetooth and zero internet / cloud calls.
 */
class WifiDirectManager(
    private val appCtx: Context?,
    private val customDriver: P2pDriver? = null,
    private val scheduler: PttTaskScheduler = MainLooperTaskScheduler()
) : PttRadioTransport {

    companion object {
        const val REASON_ERROR = 0
        const val REASON_P2P_UNSUPPORTED = 1
        const val REASON_BUSY = 2
        const val REASON_NO_SERVICE_REQUESTS = 3

        private const val MAX_DISCOVERY_ROUNDS = 3
        private const val DNS_PHASE_DURATION_MS = 5_000L
        private const val PEER_FALLBACK_DURATION_MS = 3_000L
        private const val GROUP_FORM_TIMEOUT_MS = 9_000L

        // Client P2P radio association windows (Direct SSID/PSK fast path + MAC PBC fallback)
        private const val DIRECT_CRED_LINK_TIMEOUT_MS = 8_000L
        private const val MAC_FALLBACK_LINK_TIMEOUT_MS = 10_000L

        fun isValidDirectGroupSsid(ssid: String): Boolean =
            ssid.startsWith("DIRECT-") && ssid.length in 9..32

        fun isValidDirectGroupPassphrase(psk: String): Boolean =
            psk.length in 8..63
    }

    override var listener: PttRadioTransport.Listener? = null

    private val wifiManager: WifiManager? = try {
        appCtx?.applicationContext?.getSystemService(WifiManager::class.java)
    } catch (_: Throwable) {
        null
    }
    private val connectivityManager: ConnectivityManager? = try {
        appCtx?.getSystemService(ConnectivityManager::class.java)
    } catch (_: Throwable) {
        null
    }

    private val driver: P2pDriver = customDriver ?: AndroidP2pDriver(appCtx!!)

    private var hotspotReservation: WifiManager.LocalOnlyHotspotReservation? = null
    private val receiverRegistered = AtomicBoolean(false)
    private val scanning = AtomicBoolean(false)
    private val hostingActive = AtomicBoolean(false)
    private val connectInProgress = AtomicBoolean(false)
    private val clientLinkEstablished = AtomicBoolean(false)
    private val operationGen = AtomicInteger(0)

    @Volatile
    private var activeTargetIpHint: String = ""

    private val discoveredByAddress = ConcurrentHashMap<String, DiscoveredPttSession>()
    private val cachedTxtByAddress = ConcurrentHashMap<String, Map<String, String>>()
    private val cachedDeviceNameByAddress = ConcurrentHashMap<String, String>()

    // Scheduled runnables tracked for clean cancellation
    private var discoveryStepRunnable: Runnable? = null
    private var groupFormPollRunnable: Runnable? = null
    private var connectPollRunnable: Runnable? = null
    private var connectStageTimeoutRunnable: Runnable? = null

    // Active hosting wait callback (completed once groupFormed == true)
    @Volatile
    private var pendingHostReadyCallback: ((String) -> Unit)? = null

    private val p2pReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    if (state == WifiP2pManager.WIFI_P2P_STATE_DISABLED) {
                        PttDiagnostics.warn("Broadcast", "WIFI_P2P_STATE_DISABLED received")
                        handlePlatformWifiDisabled()
                    }
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    if (scanning.get()) {
                        inspectAvailablePeersForFallback()
                    }
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    @Suppress("DEPRECATION")
                    val p2pInfo = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO, WifiP2pInfo::class.java)
                    } else {
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO)
                    }
                    if (p2pInfo != null && p2pInfo.groupFormed) {
                        val goIp = p2pInfo.groupOwnerAddress?.hostAddress
                            ?.takeIf { it.isNotBlank() && it != "0.0.0.0" }
                            ?: activeTargetIpHint.takeIf { it.isNotBlank() && !p2pInfo.isGroupOwner }
                        if (!goIp.isNullOrBlank()) {
                            onGroupLinkObserved(p2pInfo.isGroupOwner, goIp)
                        } else {
                            driver.requestLinkState { st ->
                                val resolvedIp = st?.groupOwnerIp?.takeIf { it.isNotBlank() && it != "0.0.0.0" }
                                    ?: resolveLocalP2pInterfaceIp().takeIf { p2pInfo.isGroupOwner }
                                    ?: activeTargetIpHint.takeIf { it.isNotBlank() }
                                if (st != null && st.groupFormed && !resolvedIp.isNullOrBlank()) {
                                    onGroupLinkObserved(st.isGroupOwner, resolvedIp)
                                }
                            }
                        }
                    } else {
                        // CRITICAL: Ignore sticky/transient groupFormed=false broadcasts while connecting!
                        // Only fire onP2pLinkDisconnected if a client P2P link had ALREADY been established.
                        if (!connectInProgress.get() && !hostingActive.get()) {
                            if (clientLinkEstablished.compareAndSet(true, false)) {
                                PttDiagnostics.warn("Broadcast", "Established client P2P link dropped")
                                listener?.onP2pLinkDisconnected()
                            }
                        } else if (!connectInProgress.get() && hostingActive.get() && pendingHostReadyCallback == null) {
                            if (!isWifiEnabled()) {
                                handlePlatformWifiDisabled()
                            }
                        }
                    }
                }
            }
        }
    }

    override fun isWifiEnabled(): Boolean = driver.isWifiEnabled()

    override fun diagnoseEnvironment(): WifiEnvironmentIssue {
        if (!driver.isHardwareSupported) {
            PttDiagnostics.warn("EnvCheck", "Wi-Fi Direct hardware/service unsupported")
            return WifiEnvironmentIssue.P2P_UNSUPPORTED
        }
        if (!driver.hasDiscoveryPermission()) {
            PttDiagnostics.warn("EnvCheck", "Required Walkie-Talkie runtime permission missing")
            return WifiEnvironmentIssue.MISSING_PERMISSION
        }
        if (!driver.isWifiEnabled()) {
            PttDiagnostics.warn("EnvCheck", "Wi-Fi adapter is currently disabled")
            return WifiEnvironmentIssue.WIFI_DISABLED
        }
        if (driver.isLocationToggleRequiredButOff()) {
            PttDiagnostics.warn("EnvCheck", "System Location toggle is off on Android 11/12")
            return WifiEnvironmentIssue.LOCATION_TOGGLE_OFF
        }
        return WifiEnvironmentIssue.NONE
    }

    override fun checkLocalHotspotGateway(): LocalHotspotGatewayCheck {
        if (!driver.isWifiEnabled()) {
            return LocalHotspotGatewayCheck.WifiTurnedOff
        }
        val gw = resolveConnectedWifiGatewayIp()
        return if (!gw.isNullOrBlank()) {
            LocalHotspotGatewayCheck.Ready(gw)
        } else {
            LocalHotspotGatewayCheck.NotConnectedToHotspot
        }
    }

    /**
     * Sequenced Host startup:
     * 1. Pre-flight verification (permissions, Wi-Fi ON, Location toggle on API 30–32)
     * 2. Channel initialization & receiver registration
     * 3. Stop conflicting scans + remove any stale P2P group (await callback)
     * 4. Register initial DNS-SD service + create Autonomous P2P Group (`createGroup`)
     * 5. Wait for `groupFormed == true` & valid Group Owner IP
     * 6. Query actual `DIRECT-...` SSID & passphrase via `requestGroupInfo` and update DNS-SD TXT
     *    (`gn`, `gp`, `goip`, `port`) so Android 11–16 clients can join directly via `WifiP2pConfig.Builder`
     *    without WPS PBC timeouts, MAC randomization mismatches, or hidden OS invitation dialogs!
     */
    override fun startHostingGroup(
        sessionId: String,
        hostDisplayName: String,
        tcpPort: Int,
        onReady: (groupOwnerIp: String) -> Unit,
        onError: (WifiEnvironmentIssue, String) -> Unit
    ) {
        val gen = operationGen.incrementAndGet()
        cancelScheduledTasks()
        scanning.set(false)
        connectInProgress.set(false)
        clientLinkEstablished.set(false)
        hostingActive.set(true)
        pendingHostReadyCallback = null

        val env = diagnoseEnvironment()
        if (env != WifiEnvironmentIssue.NONE) {
            hostingActive.set(false)
            onError(env, "Pre-flight check failed: $env")
            return
        }
        if (!driver.ensureInitialized()) {
            hostingActive.set(false)
            PttDiagnostics.error("HostStart", "WifiP2pManager.initialize returned null channel")
            onError(WifiEnvironmentIssue.P2P_UNSUPPORTED, "Channel initialization failed")
            return
        }
        ensureReceiverRegistered()

        val baseTxtRecord = linkedMapOf(
            "v" to PttProtocol.PROTOCOL_VERSION.toString(),
            "sid" to sessionId.take(20),
            "host" to hostDisplayName.take(24),
            "port" to tcpPort.toString()
        )

        PttDiagnostics.info("HostStart", "Preparing P2P host group for sid=${sessionId.take(8)} port=$tcpPort")
        driver.stopPeerDiscovery()
        driver.clearServiceRequests { _, _ -> }

        driver.removeExistingGroupIfAny {
            if (operationGen.get() != gen) return@removeExistingGroupIfAny
            if (!driver.isWifiEnabled()) {
                hostingActive.set(false)
                onError(WifiEnvironmentIssue.WIFI_DISABLED, "Wi-Fi turned off during group cleanup")
                return@removeExistingGroupIfAny
            }

            driver.clearLocalServices { _, _ ->
                if (operationGen.get() != gen) return@clearLocalServices
                registerLocalServiceWithRetry(gen, baseTxtRecord, attempt = 1, onError = onError) {
                    createGroupWithRetry(gen, attempt = 1, onError = onError) { formedGoIp ->
                        publishGroupCredentialsIntoDnsSd(gen, baseTxtRecord, formedGoIp) {
                            onReady(formedGoIp)
                        }
                    }
                }
            }
        }
    }

    private fun publishGroupCredentialsIntoDnsSd(
        gen: Int,
        baseTxt: Map<String, String>,
        goIp: String,
        onComplete: () -> Unit
    ) {
        if (operationGen.get() != gen) return
        driver.requestHostedGroupCredentials { creds ->
            if (operationGen.get() != gen) return@requestHostedGroupCredentials
            val enriched = LinkedHashMap<String, String>(baseTxt)
            if (goIp.isNotBlank() && goIp != "0.0.0.0") {
                enriched["goip"] = goIp
            }
            if (creds != null &&
                isValidDirectGroupSsid(creds.networkName) &&
                isValidDirectGroupPassphrase(creds.passphrase)
            ) {
                enriched["gn"] = creds.networkName
                enriched["gp"] = creds.passphrase
                PttDiagnostics.info("HostStart", "Publishing P2P group credentials in DNS-SD forSSID=${creds.networkName} goIp=$goIp")
                driver.clearLocalServices { _, _ ->
                    if (operationGen.get() != gen) return@clearLocalServices
                    driver.addDnsSdLocalService(
                        PttProtocol.DNS_SERVICE_INSTANCE,
                        PttProtocol.DNS_SERVICE_TYPE,
                        enriched
                    ) { ok, reason ->
                        if (!ok) {
                            PttDiagnostics.warn("HostStart", "Enriched addLocalService warning: ${PttDiagnostics.p2pReasonName(reason)}")
                        }
                        // Put local P2P radio in listen state once so wpa_supplicant answers DNS-SD queries immediately
                        driver.discoverPeers { _, _ -> }
                        onComplete()
                    }
                }
            } else {
                driver.discoverPeers { _, _ -> }
                onComplete()
            }
        }
    }

    private fun registerLocalServiceWithRetry(
        gen: Int,
        txtRecord: Map<String, String>,
        attempt: Int,
        onError: (WifiEnvironmentIssue, String) -> Unit,
        onRegistered: () -> Unit
    ) {
        if (operationGen.get() != gen) return
        driver.addDnsSdLocalService(
            PttProtocol.DNS_SERVICE_INSTANCE,
            PttProtocol.DNS_SERVICE_TYPE,
            txtRecord
        ) { ok, reason ->
            if (operationGen.get() != gen) return@addDnsSdLocalService
            if (ok) {
                PttDiagnostics.info("HostStart", "DNS-SD local service registered (${PttProtocol.DNS_SERVICE_INSTANCE}.${PttProtocol.DNS_SERVICE_TYPE})")
                onRegistered()
            } else if (reason == REASON_BUSY && attempt < 2) {
                PttDiagnostics.warn("HostStart", "addLocalService BUSY; retrying once in 450ms")
                scheduler.postDelayed(450L, Runnable {
                    registerLocalServiceWithRetry(gen, txtRecord, attempt + 1, onError, onRegistered)
                })
            } else {
                hostingActive.set(false)
                val issue = classifyP2pFailure(reason, WifiEnvironmentIssue.HOSTING_GROUP_FAILED)
                val diag = "addLocalService failed: ${PttDiagnostics.p2pReasonName(reason)}"
                PttDiagnostics.error("HostStart", diag)
                onError(issue, diag)
            }
        }
    }

    private fun createGroupWithRetry(
        gen: Int,
        attempt: Int,
        onError: (WifiEnvironmentIssue, String) -> Unit,
        onReady: (String) -> Unit
    ) {
        if (operationGen.get() != gen) return
        driver.createGroup { ok, reason ->
            if (operationGen.get() != gen) return@createGroup
            if (ok) {
                PttDiagnostics.info("HostStart", "createGroup accepted; awaiting groupFormed & GO IP")
                hostingActive.set(true)
                awaitHostGroupFormation(gen, onReady, onError)
            } else if (reason == REASON_BUSY && attempt < 2) {
                PttDiagnostics.warn("HostStart", "createGroup BUSY; retrying once in 550ms")
                scheduler.postDelayed(550L, Runnable {
                    createGroupWithRetry(gen, attempt + 1, onError, onReady)
                })
            } else {
                hostingActive.set(false)
                driver.clearLocalServices { _, _ -> }
                val issue = classifyP2pFailure(reason, WifiEnvironmentIssue.HOSTING_GROUP_FAILED)
                val diag = "createGroup failed: ${PttDiagnostics.p2pReasonName(reason)}"
                PttDiagnostics.error("HostStart", diag)
                onError(issue, diag)
            }
        }
    }

    private fun awaitHostGroupFormation(
        gen: Int,
        onReady: (String) -> Unit,
        onError: (WifiEnvironmentIssue, String) -> Unit
    ) {
        val completed = AtomicBoolean(false)
        val startedMs = System.currentTimeMillis()

        val finishReady: (String) -> Unit = { ip ->
            if (completed.compareAndSet(false, true) && operationGen.get() == gen) {
                pendingHostReadyCallback = null
                groupFormPollRunnable?.let { scheduler.remove(it) }
                groupFormPollRunnable = null
                PttDiagnostics.info("HostStart", "Host group ready at $ip")
                onReady(ip)
            }
        }
        pendingHostReadyCallback = finishReady

        val poll = object : Runnable {
            override fun run() {
                if (completed.get() || operationGen.get() != gen) return
                if (!driver.isWifiEnabled()) {
                    if (completed.compareAndSet(false, true)) {
                        pendingHostReadyCallback = null
                        hostingActive.set(false)
                        onError(WifiEnvironmentIssue.WIFI_DISABLED, "Wi-Fi disabled before group formed")
                    }
                    return
                }
                driver.requestLinkState { st ->
                    if (completed.get() || operationGen.get() != gen) return@requestLinkState
                    val goIp = st?.groupOwnerIp?.takeIf { it.isNotBlank() && it != "0.0.0.0" }
                        ?: if (st?.groupFormed == true) resolveLocalP2pInterfaceIp() ?: "192.168.49.1" else null
                    if (st != null && st.groupFormed && !goIp.isNullOrBlank()) {
                        finishReady(goIp)
                    } else if (System.currentTimeMillis() - startedMs >= GROUP_FORM_TIMEOUT_MS) {
                        if (completed.compareAndSet(false, true)) {
                            pendingHostReadyCallback = null
                            hostingActive.set(false)
                            driver.removeGroupNow()
                            driver.clearLocalServices { _, _ -> }
                            val diag = "Timed out waiting for Wi-Fi Direct groupFormed"
                            PttDiagnostics.error("HostStart", diag)
                            onError(WifiEnvironmentIssue.HOSTING_GROUP_FAILED, diag)
                        }
                    } else {
                        scheduler.postDelayed(320L, this)
                    }
                }
            }
        }
        groupFormPollRunnable = poll
        scheduler.postDelayed(180L, poll)
    }

    /**
     * Explicit offline Android Local-Only Hotspot mode (zero internet routing, zero Bluetooth).
     */
    @SuppressLint("MissingPermission")
    override fun startLocalOnlyHotspotFallback(
        onReady: (ssid: String, passphrase: String) -> Unit,
        onError: (WifiEnvironmentIssue, String) -> Unit
    ) {
        val ctx = appCtx
        val wm = wifiManager
        if (ctx == null || wm == null) {
            onError(WifiEnvironmentIssue.HOTSPOT_START_FAILED, "WifiManager unavailable")
            return
        }
        if (!PttPermissions.hasWifiDirectDiscoveryPermission(ctx)) {
            onError(WifiEnvironmentIssue.MISSING_PERMISSION, "Missing nearby/location permission for LocalHotspot")
            return
        }
        if (!driver.isWifiEnabled()) {
            onError(WifiEnvironmentIssue.WIFI_DISABLED, "Wi-Fi disabled before starting LocalHotspot")
            return
        }
        if (PttPermissions.isLocationToggleRequiredButOff(ctx)) {
            onError(WifiEnvironmentIssue.LOCATION_TOGGLE_OFF, "Location toggle off on Android 11/12")
            return
        }
        try {
            closeHotspotReservation()
            wm.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation?) {
                    hotspotReservation = reservation
                    @Suppress("DEPRECATION")
                    val ssid = reservation?.softApConfiguration?.ssid
                        ?: reservation?.wifiConfiguration?.SSID?.trim('"')
                        ?: "RMG-PTT"
                    @Suppress("DEPRECATION")
                    val pass = reservation?.softApConfiguration?.passphrase
                        ?: reservation?.wifiConfiguration?.preSharedKey?.trim('"')
                        ?: ""
                    PttDiagnostics.info("Hotspot", "LocalOnlyHotspot started: SSID=$ssid")
                    listener?.onLocalHotspotStarted(ssid, pass)
                    onReady(ssid, pass)
                }

                override fun onFailed(reason: Int) {
                    val issue = if (!driver.isWifiEnabled()) {
                        WifiEnvironmentIssue.WIFI_DISABLED
                    } else {
                        WifiEnvironmentIssue.HOTSPOT_START_FAILED
                    }
                    val diag = "LocalOnlyHotspot failed with reason=$reason"
                    PttDiagnostics.error("Hotspot", diag)
                    onError(issue, diag)
                }
            }, Handler(Looper.getMainLooper()))
        } catch (se: SecurityException) {
            PttDiagnostics.error("Hotspot", "SecurityException starting LocalHotspot", se)
            onError(WifiEnvironmentIssue.MISSING_PERMISSION, se.message ?: "SecurityException")
        } catch (ise: IllegalStateException) {
            PttDiagnostics.error("Hotspot", "IllegalStateException starting LocalHotspot", ise)
            onError(WifiEnvironmentIssue.HOTSPOT_START_FAILED, ise.message ?: "Hotspot request already active")
        } catch (t: Throwable) {
            PttDiagnostics.error("Hotspot", "Unexpected error starting LocalHotspot", t)
            onError(WifiEnvironmentIssue.HOTSPOT_START_FAILED, t.message ?: "Unexpected hotspot error")
        }
    }

    /**
     * Resolves IPv4 gateway ONLY when the device is actively associated with a Wi-Fi network.
     * Supports isolated Local-Only Hotspots (which have TRANSPORT_WIFI without NET_CAPABILITY_INTERNET).
     */
    private fun resolveConnectedWifiGatewayIp(): String? {
        try {
            val cm = connectivityManager
            if (cm != null) {
                @Suppress("DEPRECATION")
                val allNets = cm.allNetworks
                for (net in allNets) {
                    val caps = cm.getNetworkCapabilities(net) ?: continue
                    if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
                    val props = cm.getLinkProperties(net) ?: continue
                    for (route in props.routes) {
                        val gw = route.gateway
                        if (gw is Inet4Address && !gw.isLoopbackAddress && gw.hostAddress != "0.0.0.0") {
                            return gw.hostAddress
                        }
                    }
                    // Fallback: derive .1 gateway from assigned /24 link address on isolated SoftAP
                    for (la in props.linkAddresses) {
                        val addr = la.address
                        val hostAddr = addr.hostAddress
                        if (addr is Inet4Address && !addr.isLoopbackAddress && !hostAddr.isNullOrBlank()) {
                            val parts = hostAddr.split('.')
                            if (parts.size == 4 && parts[3] != "1") {
                                return "${parts[0]}.${parts[1]}.${parts[2]}.1"
                            }
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            PttDiagnostics.warn("HotspotGw", "ConnectivityManager Wi-Fi gateway lookup warning", t)
        }

        try {
            @Suppress("DEPRECATION")
            val dhcp = wifiManager?.dhcpInfo
            if (dhcp != null && dhcp.gateway != 0 && dhcp.ipAddress != 0) {
                val g = dhcp.gateway
                val ip = "${g and 0xFF}.${(g shr 8) and 0xFF}.${(g shr 16) and 0xFF}.${(g shr 24) and 0xFF}"
                if (ip != "0.0.0.0") return ip
            }
        } catch (_: Throwable) {}
        return null
    }

    private fun findMatchingRadioNetwork(remoteIp: String, viaLocalHotspot: Boolean): Network? {
        val cm = connectivityManager ?: return null
        val parts = remoteIp.split('.')
        val subnetPrefix = if (parts.size == 4) "${parts[0]}.${parts[1]}.${parts[2]}." else null
        try {
            @Suppress("DEPRECATION")
            val allNets = cm.allNetworks
            var anyWifiCandidate: Network? = null
            for (net in allNets) {
                val caps = cm.getNetworkCapabilities(net) ?: continue
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_WIFI_P2P)
                ) {
                    continue
                }
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    anyWifiCandidate = net
                }
                val props = cm.getLinkProperties(net) ?: continue
                if (subnetPrefix != null) {
                    val matchesAddr = props.linkAddresses.any {
                        val ha = it.address?.hostAddress
                        !ha.isNullOrBlank() && ha.startsWith(subnetPrefix)
                    }
                    val matchesGw = props.routes.any {
                        val gw = it.gateway?.hostAddress
                        !gw.isNullOrBlank() && (gw == remoteIp || gw.startsWith(subnetPrefix))
                    }
                    if (matchesAddr || matchesGw) return net
                }
            }
            if (viaLocalHotspot && anyWifiCandidate != null) return anyWifiCandidate
        } catch (t: Throwable) {
            PttDiagnostics.warn("NetBind", "Lookup matching Network warning", t)
        }
        return null
    }

    override fun bindSocketToRadioNetwork(socket: Socket, remoteIp: String, viaLocalHotspot: Boolean): Boolean {
        val net = findMatchingRadioNetwork(remoteIp, viaLocalHotspot) ?: return false
        return try {
            net.bindSocket(socket)
            PttDiagnostics.info("NetBind", "Bound TCP socket to explicit Android Network for $remoteIp")
            true
        } catch (t: Throwable) {
            PttDiagnostics.warn("NetBind", "Network.bindSocket(TCP) failed", t)
            false
        }
    }

    override fun bindDatagramToRadioNetwork(socket: DatagramSocket, remoteIp: String, viaLocalHotspot: Boolean): Boolean {
        val net = findMatchingRadioNetwork(remoteIp, viaLocalHotspot) ?: return false
        return try {
            net.bindSocket(socket)
            PttDiagnostics.info("NetBind", "Bound UDP socket to explicit Android Network for $remoteIp")
            true
        } catch (t: Throwable) {
            PttDiagnostics.warn("NetBind", "Network.bindSocket(UDP) failed", t)
            false
        }
    }

    private fun resolveLocalP2pInterfaceIp(): String? {
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return null
            while (ifaces.hasMoreElements()) {
                val nif = ifaces.nextElement()
                val name = nif.name?.lowercase().orEmpty()
                if (!nif.isUp || nif.isLoopback) continue
                if (!name.contains("p2p") && !name.contains("direct")) continue
                val addrs = nif.inetAddresses
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (a is Inet4Address && !a.isLoopbackAddress) {
                        return a.hostAddress
                    }
                }
            }
        } catch (_: Throwable) {}
        return null
    }

    /**
     * Sequenced, non-conflicting session discovery.
     */
    override fun startSessionDiscovery(
        onStarted: () -> Unit,
        onError: (WifiEnvironmentIssue, String) -> Unit
    ): Boolean {
        val gen = operationGen.incrementAndGet()
        cancelScheduledTasks()
        connectInProgress.set(false)
        clientLinkEstablished.set(false)

        val env = diagnoseEnvironment()
        if (env != WifiEnvironmentIssue.NONE) {
            scanning.set(false)
            onError(env, "Discovery pre-flight failed: $env")
            return false
        }
        if (!driver.ensureInitialized()) {
            scanning.set(false)
            onError(WifiEnvironmentIssue.P2P_UNSUPPORTED, "Channel initialization failed")
            return false
        }
        ensureReceiverRegistered()

        discoveredByAddress.clear()
        cachedTxtByAddress.clear()
        cachedDeviceNameByAddress.clear()
        scanning.set(true)
        listener?.onSessionsDiscovered(emptyList())
        onStarted()

        driver.setDnsSdListeners(
            onService = { instanceName, regType, addr, devName ->
                if (addr.isNotBlank()) {
                    if (devName.isNotBlank()) cachedDeviceNameByAddress[addr] = devName
                    if (PttProtocol.matchesDnsSdService(instanceName, regType)) {
                        PttDiagnostics.info("DnsSd", "Service response from $addr ($devName) inst=$instanceName type=$regType")
                        val cachedTxt = cachedTxtByAddress[addr]
                        if (cachedTxt != null) {
                            processDiscoveredDnsRecord(cachedTxt, addr, devName)
                        } else {
                            val existing = discoveredByAddress[addr]
                            if (existing != null && devName.isNotBlank()) {
                                discoveredByAddress[addr] = existing.copy(deviceName = devName, lastSeenMs = System.currentTimeMillis())
                                publishDiscoveredList()
                            }
                        }
                    }
                }
            },
            onTxt = { fullDomain, record, addr, devName ->
                if (addr.isNotBlank()) {
                    if (devName.isNotBlank()) cachedDeviceNameByAddress[addr] = devName
                    if (PttProtocol.matchesDnsSdService(fullDomain, null) || record.containsKey("sid")) {
                        cachedTxtByAddress[addr] = record
                        processDiscoveredDnsRecord(record, addr, devName)
                    }
                }
            }
        )

        driver.removeExistingGroupIfAny {
            if (operationGen.get() != gen || !scanning.get()) return@removeExistingGroupIfAny
            runDiscoveryRoundDnsPhase(gen, round = 1, onError = onError)
        }
        return true
    }

    private fun runDiscoveryRoundDnsPhase(
        gen: Int,
        round: Int,
        onError: (WifiEnvironmentIssue, String) -> Unit
    ) {
        if (operationGen.get() != gen || !scanning.get()) return
        if (!driver.isWifiEnabled()) {
            stopSessionDiscovery()
            onError(WifiEnvironmentIssue.WIFI_DISABLED, "Wi-Fi disabled during discovery")
            return
        }

        PttDiagnostics.info("Discovery", "Round $round/$MAX_DISCOVERY_ROUNDS — Phase A (DNS-SD)")
        driver.stopPeerDiscovery()

        driver.clearServiceRequests { _, _ ->
            if (operationGen.get() != gen || !scanning.get()) return@clearServiceRequests
            driver.addDnsSdServiceRequest { addOk, addReason ->
                if (operationGen.get() != gen || !scanning.get()) return@addDnsSdServiceRequest
                if (!addOk) {
                    PttDiagnostics.warn("Discovery", "addServiceRequest failed: ${PttDiagnostics.p2pReasonName(addReason)}; falling back to Peer scan")
                    runDiscoveryRoundPeerFallbackPhase(gen, round, dnsFailed = true, onError = onError)
                    return@addDnsSdServiceRequest
                }
                startDiscoverServicesWithBusyRetry(gen, attempt = 1) { svcOk, svcReason ->
                    if (operationGen.get() != gen || !scanning.get()) return@startDiscoverServicesWithBusyRetry
                    if (!svcOk) {
                        PttDiagnostics.warn("Discovery", "discoverServices failed: ${PttDiagnostics.p2pReasonName(svcReason)}; falling back to Peer scan")
                        runDiscoveryRoundPeerFallbackPhase(gen, round, dnsFailed = true, onError = onError)
                        return@startDiscoverServicesWithBusyRetry
                    }
                    scheduleDiscoveryStep(DNS_PHASE_DURATION_MS) {
                        if (operationGen.get() != gen || !scanning.get()) return@scheduleDiscoveryStep
                        val hasDnsResult = discoveredByAddress.values.any { !it.discoveredViaFallback }
                        if (hasDnsResult) {
                            advanceOrConcludeDiscovery(gen, round, onError)
                        } else {
                            runDiscoveryRoundPeerFallbackPhase(gen, round, dnsFailed = false, onError = onError)
                        }
                    }
                }
            }
        }
    }

    private fun startDiscoverServicesWithBusyRetry(
        gen: Int,
        attempt: Int,
        cb: (Boolean, Int) -> Unit
    ) {
        driver.discoverServices { ok, reason ->
            if (operationGen.get() != gen || !scanning.get()) return@discoverServices
            if (!ok && reason == REASON_BUSY && attempt < 2) {
                PttDiagnostics.warn("Discovery", "discoverServices BUSY; retrying in 600ms")
                scheduleDiscoveryStep(600L) {
                    startDiscoverServicesWithBusyRetry(gen, attempt + 1, cb)
                }
            } else {
                cb(ok, reason)
            }
        }
    }

    private fun runDiscoveryRoundPeerFallbackPhase(
        gen: Int,
        round: Int,
        dnsFailed: Boolean,
        onError: (WifiEnvironmentIssue, String) -> Unit
    ) {
        if (operationGen.get() != gen || !scanning.get()) return
        PttDiagnostics.info("Discovery", "Round $round/$MAX_DISCOVERY_ROUNDS — Phase B (Peer Fallback, dnsFailed=$dnsFailed)")
        driver.clearServiceRequests { _, _ ->
            if (operationGen.get() != gen || !scanning.get()) return@clearServiceRequests
            driver.discoverPeers { peerOk, peerReason ->
                if (operationGen.get() != gen || !scanning.get()) return@discoverPeers
                if (!peerOk) {
                    PttDiagnostics.warn("Discovery", "discoverPeers failed: ${PttDiagnostics.p2pReasonName(peerReason)}")
                    if (dnsFailed && round >= MAX_DISCOVERY_ROUNDS && discoveredByAddress.isEmpty()) {
                        stopSessionDiscovery()
                        val issue = classifyP2pFailure(peerReason, WifiEnvironmentIssue.DISCOVERY_FAILED)
                        onError(issue, "Both DNS-SD and Peer discovery failed (${PttDiagnostics.p2pReasonName(peerReason)})")
                        return@discoverPeers
                    }
                } else {
                    scheduleDiscoveryStep(1_300L) {
                        if (operationGen.get() == gen && scanning.get()) {
                            inspectAvailablePeersForFallback()
                            scheduleDiscoveryStep(PEER_FALLBACK_DURATION_MS - 1_300L) {
                                if (operationGen.get() == gen && scanning.get()) {
                                    inspectAvailablePeersForFallback()
                                    advanceOrConcludeDiscovery(gen, round, onError)
                                }
                            }
                        }
                    }
                    return@discoverPeers
                }
                scheduleDiscoveryStep(1_200L) {
                    advanceOrConcludeDiscovery(gen, round, onError)
                }
            }
        }
    }

    private fun advanceOrConcludeDiscovery(
        gen: Int,
        completedRound: Int,
        onError: (WifiEnvironmentIssue, String) -> Unit
    ) {
        if (operationGen.get() != gen || !scanning.get()) return
        if (completedRound < MAX_DISCOVERY_ROUNDS) {
            runDiscoveryRoundDnsPhase(gen, completedRound + 1, onError)
        } else {
            val foundAny = discoveredByAddress.isNotEmpty()
            PttDiagnostics.info("Discovery", "Completed $MAX_DISCOVERY_ROUNDS rounds (foundAny=$foundAny)")
            stopSessionDiscovery()
            if (!foundAny) {
                listener?.onDiscoveryFinishedEmpty()
            }
        }
    }

    private fun inspectAvailablePeersForFallback() {
        driver.requestPeers { peers ->
            if (!scanning.get()) return@requestPeers
            var changed = false
            val now = System.currentTimeMillis()
            val candidates = peers.filter {
                it.deviceAddress.isNotBlank() &&
                    (it.isGroupOwner || it.status == WifiP2pDevice.AVAILABLE || it.status == WifiP2pDevice.INVITED || it.status == WifiP2pDevice.CONNECTED)
            }
            for (peer in candidates) {
                val existing = discoveredByAddress[peer.deviceAddress]
                if (existing != null && !existing.discoveredViaFallback) {
                    continue
                }
                if (!peer.isGroupOwner && candidates.any { it.isGroupOwner }) continue

                val cleanName = peer.deviceName.trim().ifBlank { peer.deviceAddress }
                discoveredByAddress[peer.deviceAddress] = DiscoveredPttSession(
                    deviceAddress = peer.deviceAddress,
                    sessionId = "peer_${peer.deviceAddress.replace(":", "").lowercase()}",
                    hostName = cleanName,
                    deviceName = cleanName,
                    tcpPort = PttProtocol.DEFAULT_TCP_PORT,
                    protocolVersion = PttProtocol.PROTOCOL_VERSION,
                    lastSeenMs = now,
                    discoveredViaFallback = true
                )
                changed = true
            }
            if (changed) {
                PttDiagnostics.info("Discovery", "Fallback peer list updated (${discoveredByAddress.size} candidate(s))")
                publishDiscoveredList()
            }
        }
    }

    override fun stopSessionDiscovery() {
        discoveryStepRunnable?.let { scheduler.remove(it) }
        discoveryStepRunnable = null
        if (!scanning.getAndSet(false)) return
        driver.clearServiceRequests { _, _ -> }
        driver.stopPeerDiscovery()
    }

    override fun isScanning(): Boolean = scanning.get()

    override fun connectToHostDevice(
        deviceAddress: String,
        onInitiated: () -> Unit,
        onFailed: (WifiEnvironmentIssue, String) -> Unit
    ) {
        val cached = discoveredByAddress[deviceAddress] ?: DiscoveredPttSession(
            deviceAddress = deviceAddress,
            sessionId = "",
            hostName = "",
            deviceName = "",
            tcpPort = PttProtocol.DEFAULT_TCP_PORT,
            protocolVersion = PttProtocol.PROTOCOL_VERSION,
            lastSeenMs = System.currentTimeMillis()
        )
        connectToSession(cached, onInitiated, onFailed)
    }

    /**
     * Two-stage smart Wi-Fi Direct client connection:
     *  • Sets [connectInProgress] BEFORE registering BroadcastReceiver so sticky `groupFormed=false`
     *    intents NEVER abort the connection prematurely!
     *  • Stage 1 (Fast Direct Group Join, API 30+): If DNS-SD provided `DIRECT-...` SSID & passphrase,
     *    joins the Autonomous GO group directly via `WifiP2pConfig.Builder` — bypassing WPS PBC,
     *    MAC randomization mismatches, and hidden OS invitation popups.
     *  • Stage 2 (Fallback MAC Connect): If Stage 1 is unavailable or does not form a link within 8s,
     *    refreshes the P2P peer cache briefly and connects via `deviceAddress` + `WpsInfo.PBC`.
     *  • Reports [WifiEnvironmentIssue.P2P_LINK_FAILED] if neither radio stage forms a P2P group.
     */
    override fun connectToSession(
        session: DiscoveredPttSession,
        onInitiated: () -> Unit,
        onFailed: (WifiEnvironmentIssue, String) -> Unit
    ) {
        val env = diagnoseEnvironment()
        if (env != WifiEnvironmentIssue.NONE) {
            onFailed(env, "Connect pre-flight failed: $env")
            return
        }
        if (!driver.ensureInitialized()) {
            onFailed(WifiEnvironmentIssue.P2P_UNSUPPORTED, "Channel initialization failed")
            return
        }

        stopSessionDiscovery()
        val gen = operationGen.incrementAndGet()
        activeTargetIpHint = session.groupOwnerIpHint
        clientLinkEstablished.set(false)
        // Must be true BEFORE ensureReceiverRegistered() so sticky broadcast never triggers disconnect!
        connectInProgress.set(true)
        ensureReceiverRegistered()

        driver.requestLinkState { existingLink ->
            if (operationGen.get() != gen) return@requestLinkState
            if (existingLink != null && existingLink.groupFormed) {
                val sameTargetGroup = session.groupNetworkName.isBlank() ||
                    existingLink.networkName.isBlank() ||
                    existingLink.networkName == session.groupNetworkName
                val resolvedGoIp = existingLink.groupOwnerIp?.takeIf { it.isNotBlank() && it != "0.0.0.0" }
                    ?: session.groupOwnerIpHint.takeIf { it.isNotBlank() }
                if (!existingLink.isGroupOwner && sameTargetGroup && !resolvedGoIp.isNullOrBlank()) {
                    PttDiagnostics.info("Connect", "Reusing active P2P client link GO=$resolvedGoIp")
                    connectInProgress.set(false)
                    clientLinkEstablished.set(true)
                    onInitiated()
                    listener?.onP2pLinkEstablished(false, resolvedGoIp)
                    return@requestLinkState
                }
                // Stale or mismatched group (e.g. phone was previously Host): clean it before joining!
                PttDiagnostics.info("Connect", "Removing stale P2P group before joining target host")
                driver.removeExistingGroupIfAny {
                    if (operationGen.get() == gen) {
                        beginClientAssociationStages(gen, session, onInitiated, onFailed)
                    }
                }
                return@requestLinkState
            }

            beginClientAssociationStages(gen, session, onInitiated, onFailed)
        }
    }

    private fun beginClientAssociationStages(
        gen: Int,
        session: DiscoveredPttSession,
        onInitiated: () -> Unit,
        onFailed: (WifiEnvironmentIssue, String) -> Unit
    ) {
        val notifiedInitiated = AtomicBoolean(false)
        val notifyOnce = {
            if (notifiedInitiated.compareAndSet(false, true)) {
                onInitiated()
            }
        }

        val hasDirectCreds = isValidDirectGroupSsid(session.groupNetworkName) &&
            isValidDirectGroupPassphrase(session.groupPassphrase)

        if (hasDirectCreds) {
            PttDiagnostics.info(
                "Connect",
                "Stage 1: Direct group join via SSID=${session.groupNetworkName} (target=${session.deviceAddress})"
            )
            driver.connectWithGroupCredentials(session.groupNetworkName, session.groupPassphrase) { ok, reason ->
                if (operationGen.get() != gen || !connectInProgress.get()) return@connectWithGroupCredentials
                if (ok) {
                    notifyOnce()
                    startConnectLinkPolling(gen)
                    armConnectStageTimeout(DIRECT_CRED_LINK_TIMEOUT_MS) {
                        if (operationGen.get() == gen && connectInProgress.get()) {
                            PttDiagnostics.warn("Connect", "Stage 1 Direct SSID join timed out; falling back to Stage 2 MAC connect")
                            try { driver.cancelConnect() } catch (_: Throwable) {}
                            scheduler.postDelayed(250L, Runnable {
                                if (operationGen.get() == gen && connectInProgress.get()) {
                                    runStage2MacConnect(gen, session.deviceAddress, notifyOnce, onFailed)
                                }
                            })
                        }
                    }
                } else {
                    PttDiagnostics.warn(
                        "Connect",
                        "Stage 1 Direct SSID connect rejected (${PttDiagnostics.p2pReasonName(reason)}); switching immediately to Stage 2 MAC connect"
                    )
                    runStage2MacConnect(gen, session.deviceAddress, notifyOnce, onFailed)
                }
            }
        } else {
            runStage2MacConnect(gen, session.deviceAddress, notifyOnce, onFailed)
        }
    }

    private fun runStage2MacConnect(
        gen: Int,
        deviceAddress: String,
        notifyInitiatedOnce: () -> Unit,
        onFailed: (WifiEnvironmentIssue, String) -> Unit
    ) {
        if (operationGen.get() != gen || !connectInProgress.get()) return
        if (deviceAddress.isBlank()) {
            connectInProgress.set(false)
            onFailed(WifiEnvironmentIssue.P2P_LINK_FAILED, "Target P2P device address is blank")
            return
        }
        PttDiagnostics.info("Connect", "Stage 2: P2P MAC connect to $deviceAddress")
        initiateMacConnectWithPeerRefreshRetry(gen, deviceAddress, attempt = 1, notifyInitiatedOnce = notifyInitiatedOnce, onFailed = onFailed)
    }

    private fun initiateMacConnectWithPeerRefreshRetry(
        gen: Int,
        deviceAddress: String,
        attempt: Int,
        notifyInitiatedOnce: () -> Unit,
        onFailed: (WifiEnvironmentIssue, String) -> Unit
    ) {
        driver.connect(deviceAddress) { ok, reason ->
            if (operationGen.get() != gen || !connectInProgress.get()) return@connect
            if (ok) {
                PttDiagnostics.info("Connect", "WifiP2pManager.connect(MAC) accepted for $deviceAddress (attempt=$attempt)")
                notifyInitiatedOnce()
                startConnectLinkPolling(gen)
                armConnectStageTimeout(MAC_FALLBACK_LINK_TIMEOUT_MS) {
                    if (operationGen.get() == gen && connectInProgress.get()) {
                        connectInProgress.set(false)
                        connectPollRunnable?.let { scheduler.remove(it) }
                        connectPollRunnable = null
                        try { driver.cancelConnect() } catch (_: Throwable) {}
                        val issue = if (!driver.isWifiEnabled()) WifiEnvironmentIssue.WIFI_DISABLED else WifiEnvironmentIssue.P2P_LINK_FAILED
                        val diag = "[P2P-Link] Timed out waiting for groupFormed with $deviceAddress"
                        PttDiagnostics.error("Connect", diag)
                        onFailed(issue, diag)
                    }
                }
            } else if (attempt < 2 && (reason == REASON_BUSY || reason == REASON_ERROR)) {
                // Refresh wpa_supplicant peer table briefly (900ms) then retry MAC connect once
                PttDiagnostics.warn(
                    "Connect",
                    "MAC connect returned ${PttDiagnostics.p2pReasonName(reason)}; refreshing peers for 900ms before retry"
                )
                driver.discoverPeers { _, _ -> }
                scheduler.postDelayed(900L, Runnable {
                    if (operationGen.get() == gen && connectInProgress.get()) {
                        driver.stopPeerDiscovery()
                        initiateMacConnectWithPeerRefreshRetry(gen, deviceAddress, attempt + 1, notifyInitiatedOnce, onFailed)
                    }
                })
            } else {
                connectInProgress.set(false)
                val issue = classifyP2pFailure(reason, WifiEnvironmentIssue.P2P_LINK_FAILED)
                val diag = "[P2P-Link] WifiP2pManager.connect failed: ${PttDiagnostics.p2pReasonName(reason)}"
                PttDiagnostics.error("Connect", diag)
                onFailed(issue, diag)
            }
        }
    }

    private fun armConnectStageTimeout(timeoutMs: Long, onTimeout: () -> Unit) {
        connectStageTimeoutRunnable?.let { scheduler.remove(it) }
        val r = Runnable { onTimeout() }
        connectStageTimeoutRunnable = r
        scheduler.postDelayed(timeoutMs, r)
    }

    private fun startConnectLinkPolling(gen: Int) {
        connectPollRunnable?.let { scheduler.remove(it) }
        val poll = object : Runnable {
            override fun run() {
                if (operationGen.get() != gen || !connectInProgress.get()) return
                driver.requestLinkState { st ->
                    if (operationGen.get() != gen || !connectInProgress.get()) return@requestLinkState
                    val goIp = st?.groupOwnerIp?.takeIf { it.isNotBlank() && it != "0.0.0.0" }
                        ?: if (st?.groupFormed == true && !st.isGroupOwner) activeTargetIpHint.takeIf { it.isNotBlank() } else null
                    if (st != null && st.groupFormed && !goIp.isNullOrBlank()) {
                        onGroupLinkObserved(st.isGroupOwner, goIp)
                    } else {
                        scheduler.postDelayed(400L, this)
                    }
                }
            }
        }
        connectPollRunnable = poll
        scheduler.postDelayed(350L, poll)
    }

    private fun onGroupLinkObserved(isGroupOwner: Boolean, goIp: String) {
        connectStageTimeoutRunnable?.let { scheduler.remove(it) }
        connectStageTimeoutRunnable = null
        connectPollRunnable?.let { scheduler.remove(it) }
        connectPollRunnable = null

        val hostWaiter = pendingHostReadyCallback
        if (hostWaiter != null && isGroupOwner) {
            hostWaiter.invoke(goIp)
            return
        }

        if (connectInProgress.getAndSet(false) || (!hostingActive.get() && !isGroupOwner && !clientLinkEstablished.get())) {
            clientLinkEstablished.set(true)
            PttDiagnostics.info("Connect", "P2P group link established (isGO=$isGroupOwner, goIp=$goIp)")
            listener?.onP2pLinkEstablished(isGroupOwner, goIp)
        }
    }

    private fun processDiscoveredDnsRecord(record: Map<String, String>, deviceAddress: String, rawDeviceName: String) {
        val ver = record["v"]?.toIntOrNull()
        if (ver != null && ver != PttProtocol.PROTOCOL_VERSION) {
            PttDiagnostics.warn("DnsSd", "Filtered incompatible session v=$ver from $deviceAddress")
            return
        }
        val sid = record["sid"].orEmpty().trim()
        if (sid.isEmpty()) return
        val effectiveDevName = rawDeviceName.ifBlank { cachedDeviceNameByAddress[deviceAddress].orEmpty() }
        val hostName = record["host"].orEmpty().trim().ifBlank {
            effectiveDevName.ifBlank { deviceAddress }
        }
        val port = record["port"]?.toIntOrNull() ?: PttProtocol.DEFAULT_TCP_PORT
        val goIpHint = record["goip"].orEmpty().trim()
        val groupSsid = record["gn"].orEmpty().trim()
        val groupPass = record["gp"].orEmpty().trim()

        discoveredByAddress[deviceAddress] = DiscoveredPttSession(
            deviceAddress = deviceAddress,
            sessionId = sid,
            hostName = hostName,
            deviceName = effectiveDevName,
            tcpPort = port,
            protocolVersion = ver ?: PttProtocol.PROTOCOL_VERSION,
            lastSeenMs = System.currentTimeMillis(),
            discoveredViaFallback = false,
            groupOwnerIpHint = goIpHint,
            groupNetworkName = groupSsid,
            groupPassphrase = groupPass
        )
        PttDiagnostics.info(
            "DnsSd",
            "Discovered verified DNS-SD host '$hostName' ($deviceAddress:$port, directSsid=${groupSsid.isNotBlank()}, goIp=$goIpHint)"
        )
        publishDiscoveredList()
    }

    private fun publishDiscoveredList() {
        val sorted = discoveredByAddress.values.sortedWith(
            compareBy<DiscoveredPttSession> { it.discoveredViaFallback }
                .thenByDescending { it.lastSeenMs }
        )
        scheduler.post(Runnable {
            listener?.onSessionsDiscovered(sorted)
        })
    }

    private fun handlePlatformWifiDisabled() {
        cancelScheduledTasks()
        scanning.set(false)
        hostingActive.set(false)
        connectInProgress.set(false)
        clientLinkEstablished.set(false)
        pendingHostReadyCallback = null
        listener?.onTransportFailure(WifiEnvironmentIssue.WIFI_DISABLED, "Wi-Fi disabled on device")
    }

    private fun classifyP2pFailure(reason: Int, defaultIssue: WifiEnvironmentIssue): WifiEnvironmentIssue = when {
        !driver.isWifiEnabled() -> WifiEnvironmentIssue.WIFI_DISABLED
        reason == REASON_P2P_UNSUPPORTED -> WifiEnvironmentIssue.P2P_UNSUPPORTED
        else -> defaultIssue
    }

    private fun scheduleDiscoveryStep(delayMs: Long, block: () -> Unit) {
        discoveryStepRunnable?.let { scheduler.remove(it) }
        val r = Runnable { block() }
        discoveryStepRunnable = r
        scheduler.postDelayed(delayMs, r)
    }

    private fun cancelScheduledTasks() {
        discoveryStepRunnable?.let { scheduler.remove(it) }
        discoveryStepRunnable = null
        groupFormPollRunnable?.let { scheduler.remove(it) }
        groupFormPollRunnable = null
        connectPollRunnable?.let { scheduler.remove(it) }
        connectPollRunnable = null
        connectStageTimeoutRunnable?.let { scheduler.remove(it) }
        connectStageTimeoutRunnable = null
    }

    /**
     * Unified, idempotent cleanup of all Wi-Fi Direct discovery requests, local services,
     * pending connection attempts, P2P groups, broadcast receivers, and Local-Only Hotspot reservations.
     */
    @Synchronized
    override fun teardownRadio() {
        operationGen.incrementAndGet()
        cancelScheduledTasks()
        pendingHostReadyCallback = null
        activeTargetIpHint = ""
        scanning.set(false)
        hostingActive.set(false)
        clientLinkEstablished.set(false)
        discoveredByAddress.clear()
        cachedTxtByAddress.clear()
        cachedDeviceNameByAddress.clear()

        try { driver.stopPeerDiscovery() } catch (_: Throwable) {}
        try { driver.clearServiceRequests { _, _ -> } } catch (_: Throwable) {}
        try { driver.clearLocalServices { _, _ -> } } catch (_: Throwable) {}
        if (connectInProgress.getAndSet(false)) {
            try { driver.cancelConnect() } catch (_: Throwable) {}
        }
        try { driver.removeGroupNow() } catch (_: Throwable) {}

        closeHotspotReservation()
        unregisterReceiverQuietly()
    }

    private fun closeHotspotReservation() {
        try { hotspotReservation?.close() } catch (_: Throwable) {}
        hotspotReservation = null
    }

    private fun ensureReceiverRegistered() {
        val ctx = appCtx ?: return
        if (receiverRegistered.compareAndSet(false, true)) {
            val filter = IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
            }
            try {
                if (Build.VERSION.SDK_INT >= 33) {
                    ctx.registerReceiver(p2pReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    ctx.registerReceiver(p2pReceiver, filter)
                }
            } catch (t: Throwable) {
                receiverRegistered.set(false)
                PttDiagnostics.warn("Receiver", "Failed to register P2P BroadcastReceiver", t)
            }
        }
    }

    private fun unregisterReceiverQuietly() {
        val ctx = appCtx ?: return
        if (receiverRegistered.compareAndSet(true, false)) {
            try { ctx.unregisterReceiver(p2pReceiver) } catch (_: Throwable) {}
        }
    }

    // ==================== Real Android Framework P2P Driver ====================

    @SuppressLint("MissingPermission")
    private class AndroidP2pDriver(private val appCtx: Context) : P2pDriver {
        private val mainHandler = Handler(Looper.getMainLooper())
        private val wifiManager: WifiManager? = try {
            appCtx.applicationContext.getSystemService(WifiManager::class.java)
        } catch (_: Throwable) {
            null
        }
        private val p2pManager: WifiP2pManager? = try {
            appCtx.getSystemService(WifiP2pManager::class.java)
        } catch (_: Throwable) {
            null
        }

        @Volatile
        private var p2pChannel: WifiP2pManager.Channel? = null

        override val isHardwareSupported: Boolean =
            appCtx.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT) && p2pManager != null

        override fun isWifiEnabled(): Boolean = try {
            wifiManager?.isWifiEnabled == true
        } catch (_: Throwable) {
            false
        }

        override fun hasDiscoveryPermission(): Boolean =
            PttPermissions.hasWifiDirectDiscoveryPermission(appCtx)

        override fun isLocationToggleRequiredButOff(): Boolean =
            PttPermissions.isLocationToggleRequiredButOff(appCtx)

        override fun ensureInitialized(): Boolean {
            if (p2pChannel != null) return true
            val mgr = p2pManager ?: return false
            return try {
                val ch = mgr.initialize(appCtx, Looper.getMainLooper()) {
                    PttDiagnostics.warn("P2pChannel", "WifiP2pManager channel disconnected; resetting reference")
                    p2pChannel = null
                }
                p2pChannel = ch
                ch != null
            } catch (t: Throwable) {
                PttDiagnostics.error("P2pChannel", "Exception in WifiP2pManager.initialize", t)
                false
            }
        }

        override fun clearLocalServices(cb: (Boolean, Int) -> Unit) {
            val mgr = p2pManager
            val ch = p2pChannel
            if (mgr == null || ch == null) { cb(false, REASON_P2P_UNSUPPORTED); return }
            val done = AtomicBoolean(false)
            mainHandler.postDelayed({ if (done.compareAndSet(false, true)) cb(true, 0) }, 900L)
            try {
                mgr.clearLocalServices(ch, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() { if (done.compareAndSet(false, true)) cb(true, 0) }
                    override fun onFailure(reason: Int) { if (done.compareAndSet(false, true)) cb(false, reason) }
                })
            } catch (t: Throwable) {
                PttDiagnostics.warn("P2pDriver", "clearLocalServices exception", t)
                if (done.compareAndSet(false, true)) cb(false, REASON_ERROR)
            }
        }

        override fun addDnsSdLocalService(
            instanceName: String,
            serviceType: String,
            txt: Map<String, String>,
            cb: (Boolean, Int) -> Unit
        ) {
            val mgr = p2pManager
            val ch = p2pChannel
            if (mgr == null || ch == null) { cb(false, REASON_P2P_UNSUPPORTED); return }
            try {
                val svcInfo = WifiP2pDnsSdServiceInfo.newInstance(instanceName, serviceType, txt)
                mgr.addLocalService(ch, svcInfo, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() = cb(true, 0)
                    override fun onFailure(reason: Int) = cb(false, reason)
                })
            } catch (t: Throwable) {
                PttDiagnostics.error("P2pDriver", "addLocalService exception", t)
                cb(false, REASON_ERROR)
            }
        }

        override fun removeExistingGroupIfAny(cb: () -> Unit) {
            val mgr = p2pManager
            val ch = p2pChannel
            if (mgr == null || ch == null) { cb(); return }
            val done = AtomicBoolean(false)
            mainHandler.postDelayed({ if (done.compareAndSet(false, true)) cb() }, 1_300L)
            try {
                mgr.requestGroupInfo(ch) { existingGroup ->
                    if (existingGroup != null) {
                        PttDiagnostics.info("P2pDriver", "Removing stale P2P group before new operation")
                        try {
                            mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
                                override fun onSuccess() {
                                    mainHandler.postDelayed({
                                        if (done.compareAndSet(false, true)) cb()
                                    }, 220L)
                                }
                                override fun onFailure(reason: Int) {
                                    if (done.compareAndSet(false, true)) cb()
                                }
                            })
                        } catch (_: Throwable) {
                            if (done.compareAndSet(false, true)) cb()
                        }
                    } else {
                        if (done.compareAndSet(false, true)) cb()
                    }
                }
            } catch (_: Throwable) {
                if (done.compareAndSet(false, true)) cb()
            }
        }

        override fun createGroup(cb: (Boolean, Int) -> Unit) {
            val mgr = p2pManager
            val ch = p2pChannel
            if (mgr == null || ch == null) { cb(false, REASON_P2P_UNSUPPORTED); return }
            try {
                mgr.createGroup(ch, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() = cb(true, 0)
                    override fun onFailure(reason: Int) = cb(false, reason)
                })
            } catch (t: Throwable) {
                PttDiagnostics.error("P2pDriver", "createGroup exception", t)
                cb(false, REASON_ERROR)
            }
        }

        override fun requestLinkState(cb: (P2pDriver.GroupLinkState?) -> Unit) {
            val mgr = p2pManager
            val ch = p2pChannel
            if (mgr == null || ch == null) { cb(null); return }
            try {
                mgr.requestConnectionInfo(ch) { info ->
                    if (info == null) {
                        cb(null)
                    } else if (!info.groupFormed) {
                        cb(
                            P2pDriver.GroupLinkState(
                                groupFormed = false,
                                isGroupOwner = info.isGroupOwner,
                                groupOwnerIp = info.groupOwnerAddress?.hostAddress
                            )
                        )
                    } else {
                        mgr.requestGroupInfo(ch) { grp ->
                            cb(
                                P2pDriver.GroupLinkState(
                                    groupFormed = true,
                                    isGroupOwner = info.isGroupOwner,
                                    groupOwnerIp = info.groupOwnerAddress?.hostAddress,
                                    networkName = grp?.networkName.orEmpty()
                                )
                            )
                        }
                    }
                }
            } catch (_: Throwable) {
                cb(null)
            }
        }

        override fun requestHostedGroupCredentials(cb: (P2pDriver.HostedGroupCredentials?) -> Unit) {
            val mgr = p2pManager
            val ch = p2pChannel
            if (mgr == null || ch == null) { cb(null); return }
            try {
                mgr.requestGroupInfo(ch) { grp ->
                    val ssid = grp?.networkName.orEmpty()
                    val pass = grp?.passphrase.orEmpty()
                    if (ssid.isNotBlank() && pass.isNotBlank()) {
                        cb(P2pDriver.HostedGroupCredentials(ssid, pass))
                    } else {
                        cb(null)
                    }
                }
            } catch (t: Throwable) {
                PttDiagnostics.warn("P2pDriver", "requestGroupInfo warning", t)
                cb(null)
            }
        }

        override fun setDnsSdListeners(
            onService: (instanceName: String, regType: String, deviceAddress: String, deviceName: String) -> Unit,
            onTxt: (fullDomain: String, txt: Map<String, String>, deviceAddress: String, deviceName: String) -> Unit
        ) {
            val mgr = p2pManager ?: return
            val ch = p2pChannel ?: return
            try {
                mgr.setDnsSdResponseListeners(
                    ch,
                    { instanceName, registrationType, srcDevice ->
                        onService(
                            instanceName.orEmpty(),
                            registrationType.orEmpty(),
                            srcDevice?.deviceAddress.orEmpty(),
                            srcDevice?.deviceName.orEmpty()
                        )
                    },
                    { fullDomainName, txtRecordMap, srcDevice ->
                        onTxt(
                            fullDomainName.orEmpty(),
                            txtRecordMap ?: emptyMap(),
                            srcDevice?.deviceAddress.orEmpty(),
                            srcDevice?.deviceName.orEmpty()
                        )
                    }
                )
            } catch (t: Throwable) {
                PttDiagnostics.error("P2pDriver", "setDnsSdResponseListeners exception", t)
            }
        }

        override fun clearServiceRequests(cb: (Boolean, Int) -> Unit) {
            val mgr = p2pManager
            val ch = p2pChannel
            if (mgr == null || ch == null) { cb(false, REASON_P2P_UNSUPPORTED); return }
            val done = AtomicBoolean(false)
            mainHandler.postDelayed({ if (done.compareAndSet(false, true)) cb(true, 0) }, 800L)
            try {
                mgr.clearServiceRequests(ch, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() { if (done.compareAndSet(false, true)) cb(true, 0) }
                    override fun onFailure(reason: Int) { if (done.compareAndSet(false, true)) cb(false, reason) }
                })
            } catch (_: Throwable) {
                if (done.compareAndSet(false, true)) cb(false, REASON_ERROR)
            }
        }

        override fun addDnsSdServiceRequest(cb: (Boolean, Int) -> Unit) {
            val mgr = p2pManager
            val ch = p2pChannel
            if (mgr == null || ch == null) { cb(false, REASON_P2P_UNSUPPORTED); return }
            try {
                val req = WifiP2pDnsSdServiceRequest.newInstance()
                mgr.addServiceRequest(ch, req, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() = cb(true, 0)
                    override fun onFailure(reason: Int) = cb(false, reason)
                })
            } catch (t: Throwable) {
                PttDiagnostics.error("P2pDriver", "addServiceRequest exception", t)
                cb(false, REASON_ERROR)
            }
        }

        override fun discoverServices(cb: (Boolean, Int) -> Unit) {
            val mgr = p2pManager
            val ch = p2pChannel
            if (mgr == null || ch == null) { cb(false, REASON_P2P_UNSUPPORTED); return }
            try {
                mgr.discoverServices(ch, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() = cb(true, 0)
                    override fun onFailure(reason: Int) = cb(false, reason)
                })
            } catch (t: Throwable) {
                PttDiagnostics.error("P2pDriver", "discoverServices exception", t)
                cb(false, REASON_ERROR)
            }
        }

        override fun discoverPeers(cb: (Boolean, Int) -> Unit) {
            val mgr = p2pManager
            val ch = p2pChannel
            if (mgr == null || ch == null) { cb(false, REASON_P2P_UNSUPPORTED); return }
            try {
                mgr.discoverPeers(ch, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() = cb(true, 0)
                    override fun onFailure(reason: Int) = cb(false, reason)
                })
            } catch (t: Throwable) {
                PttDiagnostics.error("P2pDriver", "discoverPeers exception", t)
                cb(false, REASON_ERROR)
            }
        }

        override fun stopPeerDiscovery() {
            val mgr = p2pManager ?: return
            val ch = p2pChannel ?: return
            try { mgr.stopPeerDiscovery(ch, null) } catch (_: Throwable) {}
        }

        override fun requestPeers(cb: (List<P2pDriver.PeerSnapshot>) -> Unit) {
            val mgr = p2pManager
            val ch = p2pChannel
            if (mgr == null || ch == null) { cb(emptyList()); return }
            try {
                mgr.requestPeers(ch) { peerList ->
                    val mapped = peerList?.deviceList?.map { d ->
                        P2pDriver.PeerSnapshot(
                            deviceAddress = d.deviceAddress.orEmpty(),
                            deviceName = d.deviceName.orEmpty(),
                            isGroupOwner = d.isGroupOwner,
                            status = d.status
                        )
                    } ?: emptyList()
                    cb(mapped)
                }
            } catch (_: Throwable) {
                cb(emptyList())
            }
        }

        override fun connectWithGroupCredentials(
            networkName: String,
            passphrase: String,
            cb: (Boolean, Int) -> Unit
        ) {
            val mgr = p2pManager
            val ch = p2pChannel
            if (mgr == null || ch == null) { cb(false, REASON_P2P_UNSUPPORTED); return }
            try {
                val cfg = WifiP2pConfig.Builder()
                    .setNetworkName(networkName)
                    .setPassphrase(passphrase)
                    .build()
                mgr.connect(ch, cfg, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() = cb(true, 0)
                    override fun onFailure(reason: Int) = cb(false, reason)
                })
            } catch (t: Throwable) {
                PttDiagnostics.warn("P2pDriver", "connectWithGroupCredentials exception", t)
                cb(false, REASON_ERROR)
            }
        }

        override fun connect(deviceAddress: String, cb: (Boolean, Int) -> Unit) {
            val mgr = p2pManager
            val ch = p2pChannel
            if (mgr == null || ch == null) { cb(false, REASON_P2P_UNSUPPORTED); return }
            val cfg = WifiP2pConfig().apply {
                this.deviceAddress = deviceAddress
                wps.setup = WpsInfo.PBC
                groupOwnerIntent = 0
            }
            try {
                mgr.connect(ch, cfg, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() = cb(true, 0)
                    override fun onFailure(reason: Int) = cb(false, reason)
                })
            } catch (t: Throwable) {
                PttDiagnostics.error("P2pDriver", "connect exception", t)
                cb(false, REASON_ERROR)
            }
        }

        override fun cancelConnect() {
            val mgr = p2pManager ?: return
            val ch = p2pChannel ?: return
            try { mgr.cancelConnect(ch, null) } catch (_: Throwable) {}
        }

        override fun removeGroupNow() {
            val mgr = p2pManager ?: return
            val ch = p2pChannel ?: return
            try {
                mgr.requestGroupInfo(ch) { group ->
                    if (group != null) {
                        try { mgr.removeGroup(ch, null) } catch (_: Throwable) {}
                    }
                }
            } catch (_: Throwable) {
                try { mgr.removeGroup(ch, null) } catch (_: Throwable) {}
            }
        }
    }
}
