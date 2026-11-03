package com.nova.gpspro.ptt

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
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
import java.net.Inet4Address
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
    val discoveredViaFallback: Boolean = false
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
    CONNECT_FAILED
}

sealed class LocalHotspotGatewayCheck {
    data class Ready(val gatewayIp: String) : LocalHotspotGatewayCheck()
    data object WifiTurnedOff : LocalHotspotGatewayCheck()
    data object NotConnectedToHotspot : LocalHotspotGatewayCheck()
}

/**
 * Transport abstraction allowing deterministic unit & regression testing of [PttRepository]
 * (including simulated Wi-Fi off, callback failures, discovery timeouts, and link drops).
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

    fun teardownRadio()
}

/**
 * Low-level P2P driver interface so the exact sequencing logic of [WifiDirectManager]
 * (clearLocalServices -> addLocalService -> createGroup -> groupFormed, and DNS-SD -> Peer fallback)
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
        val groupOwnerIp: String?
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
        private const val GROUP_FORM_TIMEOUT_MS = 8_500L
        private const val HOST_BEACON_REFRESH_MS = 14_000L
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
    private val operationGen = AtomicInteger(0)

    private val discoveredByAddress = ConcurrentHashMap<String, DiscoveredPttSession>()
    private val cachedTxtByAddress = ConcurrentHashMap<String, Map<String, String>>()
    private val cachedDeviceNameByAddress = ConcurrentHashMap<String, String>()

    // Scheduled runnables tracked for clean cancellation
    private var discoveryStepRunnable: Runnable? = null
    private var groupFormPollRunnable: Runnable? = null
    private var connectPollRunnable: Runnable? = null
    private val hostBeaconRunnable = object : Runnable {
        override fun run() {
            if (!hostingActive.get() || connectInProgress.get()) return
            driver.discoverPeers { ok, reason ->
                if (!ok) {
                    PttDiagnostics.warn("HostBeacon", "discoverPeers refresh returned ${PttDiagnostics.p2pReasonName(reason)}")
                }
            }
            if (hostingActive.get()) {
                scheduler.postDelayed(HOST_BEACON_REFRESH_MS, this)
            }
        }
    }

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
                        if (!goIp.isNullOrBlank()) {
                            onGroupLinkObserved(p2pInfo.isGroupOwner, goIp)
                        } else {
                            driver.requestLinkState { st ->
                                if (st != null && st.groupFormed && !st.groupOwnerIp.isNullOrBlank()) {
                                    onGroupLinkObserved(st.isGroupOwner, st.groupOwnerIp)
                                }
                            }
                        }
                    } else {
                        if (!connectInProgress.get() && !hostingActive.get()) {
                            listener?.onP2pLinkDisconnected()
                        } else if (!connectInProgress.get() && hostingActive.get() && pendingHostReadyCallback == null) {
                            // Host's own group collapsed externally (e.g. user toggled Wi-Fi off)
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
     * 1. Environment & permission verification
     * 2. Channel initialization & receiver registration
     * 3. Stop conflicting scans + remove any stale P2P group (await callback)
     * 4. clearLocalServices -> addLocalService ("rmgptt", "_rmgptt._tcp") with BUSY retry
     * 5. createGroup with BUSY retry
     * 6. Verify actual group formation (groupFormed == true & valid Group Owner IP) BEFORE invoking [onReady]!
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
        hostingActive.set(false)
        pendingHostReadyCallback = null

        val env = diagnoseEnvironment()
        if (env != WifiEnvironmentIssue.NONE) {
            onError(env, "Pre-flight check failed: $env")
            return
        }
        if (!driver.ensureInitialized()) {
            PttDiagnostics.error("HostStart", "WifiP2pManager.initialize returned null channel")
            onError(WifiEnvironmentIssue.P2P_UNSUPPORTED, "Channel initialization failed")
            return
        }
        ensureReceiverRegistered()

        val txtRecord = linkedMapOf(
            "v" to PttProtocol.PROTOCOL_VERSION.toString(),
            "sid" to sessionId.take(24),
            "host" to hostDisplayName.take(28),
            "port" to tcpPort.toString()
        )

        PttDiagnostics.info("HostStart", "Preparing P2P host group for sid=${sessionId.take(8)} port=$tcpPort")
        driver.stopPeerDiscovery()
        driver.clearServiceRequests { _, _ -> }

        driver.removeExistingGroupIfAny {
            if (operationGen.get() != gen) return@removeExistingGroupIfAny
            if (!driver.isWifiEnabled()) {
                onError(WifiEnvironmentIssue.WIFI_DISABLED, "Wi-Fi turned off during group cleanup")
                return@removeExistingGroupIfAny
            }

            driver.clearLocalServices { _, _ ->
                if (operationGen.get() != gen) return@clearLocalServices
                registerLocalServiceWithRetry(gen, txtRecord, attempt = 1, onError = onError) {
                    createGroupWithRetry(gen, attempt = 1, onReady = onReady, onError = onError)
                }
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
        onReady: (String) -> Unit,
        onError: (WifiEnvironmentIssue, String) -> Unit
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
                    createGroupWithRetry(gen, attempt + 1, onReady, onError)
                })
            } else {
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
                // Kick off periodic peer listen beacon after 700ms so OEM P2P radios answer DNS-SD probes
                scheduler.remove(hostBeaconRunnable)
                scheduler.postDelayed(700L, hostBeaconRunnable)
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
                    if (st != null && st.groupFormed && !st.groupOwnerIp.isNullOrBlank()) {
                        finishReady(st.groupOwnerIp)
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
                        scheduler.postDelayed(350L, this)
                    }
                }
            }
        }
        groupFormPollRunnable = poll
        scheduler.postDelayed(180L, poll)
    }

    /**
     * Considered fallback when Wi-Fi Direct group creation is blocked by OEM policy:
     * starts an offline Android Local-Only Hotspot (zero internet routing).
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
            PttDiagnostics.error("Hotspot", "SecurityException starting LocalOnlyHotspot", se)
            onError(WifiEnvironmentIssue.MISSING_PERMISSION, se.message ?: "SecurityException")
        } catch (ise: IllegalStateException) {
            PttDiagnostics.error("Hotspot", "IllegalStateException starting LocalOnlyHotspot", ise)
            onError(WifiEnvironmentIssue.HOTSPOT_START_FAILED, ise.message ?: "Hotspot request already active")
        } catch (t: Throwable) {
            PttDiagnostics.error("Hotspot", "Unexpected error starting LocalOnlyHotspot", t)
            onError(WifiEnvironmentIssue.HOTSPOT_START_FAILED, t.message ?: "Unexpected hotspot error")
        }
    }

    /**
     * Resolves IPv4 gateway ONLY when the device is actively associated with a Wi-Fi network.
     * Never misreports cellular data or disconnected Wi-Fi as a disabled Wi-Fi adapter.
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

    /**
     * Sequenced, non-conflicting session discovery:
     *  • Pre-flight checks (hardware, permissions, Wi-Fi ON, Location toggle on API 30–32)
     *  • Removes any leftover P2P group from an older session so scanning isn't pinned to a single channel
     *  • Runs up to [MAX_DISCOVERY_ROUNDS] bounded rounds:
     *      Phase A (5s): DNS-SD Service Discovery ONLY (clearServiceRequests -> addServiceRequest -> discoverServices)
     *      Phase B (3s, if DNS-SD hasn't yielded a session yet): Peer Discovery fallback (discoverPeers -> requestPeers)
     *  • Stops cleanly on timeout and reports empty/failure if no sessions exist.
     */
    override fun startSessionDiscovery(
        onStarted: () -> Unit,
        onError: (WifiEnvironmentIssue, String) -> Unit
    ): Boolean {
        val gen = operationGen.incrementAndGet()
        cancelScheduledTasks()

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

        // Ensure no stale P2P group blocks multi-channel scanning before Round 1
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
        // Stop raw peer discovery first so it never cancels P2P_SERV_DISC_REQ on Qualcomm/MTK drivers
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
                    // Wait DNS_PHASE_DURATION_MS for DNS-SD responses without interfering calls
                    scheduleDiscoveryStep(DNS_PHASE_DURATION_MS) {
                        if (operationGen.get() != gen || !scanning.get()) return@scheduleDiscoveryStep
                        val hasDnsResult = discoveredByAddress.values.any { !it.discoveredViaFallback }
                        if (hasDnsResult) {
                            // Session already found via DNS-SD! Keep refreshing gently or conclude after final round
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
                    // Sample peer list midway and at end of fallback window
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

    /**
     * Controlled fallback when OEM firmware suppresses Wi-Fi Direct DNS-SD frames:
     * surfaces real nearby Wi-Fi Direct Group Owners (or active P2P peers) so the user can initiate
     * connection; [PttRepository] strictly verifies the RMG-GPS-Pro protocol header & version
     * and cryptographic channel code before admitting the session.
     */
    private fun inspectAvailablePeersForFallback() {
        driver.requestPeers { peers ->
            if (!scanning.get()) return@requestPeers
            var changed = false
            val now = System.currentTimeMillis()
            // Prefer explicit P2P Group Owners first; if none flagged GO, include available/invited peers
            val candidates = peers.filter {
                it.deviceAddress.isNotBlank() &&
                    (it.isGroupOwner || it.status == WifiP2pDevice.AVAILABLE || it.status == WifiP2pDevice.INVITED || it.status == WifiP2pDevice.CONNECTED)
            }
            for (peer in candidates) {
                val existing = discoveredByAddress[peer.deviceAddress]
                if (existing != null && !existing.discoveredViaFallback) {
                    // Already discovered with full DNS-SD metadata; never overwrite with fallback
                    continue
                }
                // Only add non-GO peers if at least round 1 DNS-SD already ran and no GO peer exists
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

    /**
     * Connects to the selected host device over Wi-Fi Direct.
     * If already associated with that P2P group, immediately delivers the Group Owner IP.
     */
    override fun connectToHostDevice(
        deviceAddress: String,
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
        ensureReceiverRegistered()
        stopSessionDiscovery()

        val gen = operationGen.incrementAndGet()
        connectInProgress.set(true)

        // Check if already connected to an active P2P group first
        driver.requestLinkState { existingLink ->
            if (operationGen.get() != gen) return@requestLinkState
            if (existingLink != null && existingLink.groupFormed && !existingLink.groupOwnerIp.isNullOrBlank() && !existingLink.isGroupOwner) {
                PttDiagnostics.info("Connect", "Already attached to P2P group GO=${existingLink.groupOwnerIp}")
                connectInProgress.set(false)
                onInitiated()
                listener?.onP2pLinkEstablished(false, existingLink.groupOwnerIp)
                return@requestLinkState
            }

            initiateP2pConnectWithRetry(gen, deviceAddress, attempt = 1, onInitiated = onInitiated, onFailed = onFailed)
        }
    }

    private fun initiateP2pConnectWithRetry(
        gen: Int,
        deviceAddress: String,
        attempt: Int,
        onInitiated: () -> Unit,
        onFailed: (WifiEnvironmentIssue, String) -> Unit
    ) {
        driver.connect(deviceAddress) { ok, reason ->
            if (operationGen.get() != gen) return@connect
            if (ok) {
                PttDiagnostics.info("Connect", "WifiP2pManager.connect initiated to $deviceAddress")
                onInitiated()
                startConnectLinkPolling(gen)
            } else if (reason == REASON_BUSY && attempt < 2) {
                PttDiagnostics.warn("Connect", "connect BUSY; retrying in 500ms")
                scheduler.postDelayed(500L, Runnable {
                    initiateP2pConnectWithRetry(gen, deviceAddress, attempt + 1, onInitiated, onFailed)
                })
            } else {
                connectInProgress.set(false)
                val issue = classifyP2pFailure(reason, WifiEnvironmentIssue.CONNECT_FAILED)
                val diag = "WifiP2pManager.connect failed: ${PttDiagnostics.p2pReasonName(reason)}"
                PttDiagnostics.error("Connect", diag)
                onFailed(issue, diag)
            }
        }
    }

    private fun startConnectLinkPolling(gen: Int) {
        connectPollRunnable?.let { scheduler.remove(it) }
        val poll = object : Runnable {
            override fun run() {
                if (operationGen.get() != gen || !connectInProgress.get()) return
                driver.requestLinkState { st ->
                    if (operationGen.get() != gen || !connectInProgress.get()) return@requestLinkState
                    if (st != null && st.groupFormed && !st.groupOwnerIp.isNullOrBlank()) {
                        onGroupLinkObserved(st.isGroupOwner, st.groupOwnerIp)
                    } else {
                        scheduler.postDelayed(600L, this)
                    }
                }
            }
        }
        connectPollRunnable = poll
        scheduler.postDelayed(600L, poll)
    }

    private fun onGroupLinkObserved(isGroupOwner: Boolean, goIp: String) {
        connectInProgress.set(false)
        connectPollRunnable?.let { scheduler.remove(it) }
        connectPollRunnable = null

        val hostWaiter = pendingHostReadyCallback
        if (hostWaiter != null && isGroupOwner) {
            hostWaiter.invoke(goIp)
            return
        }
        listener?.onP2pLinkEstablished(isGroupOwner, goIp)
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

        discoveredByAddress[deviceAddress] = DiscoveredPttSession(
            deviceAddress = deviceAddress,
            sessionId = sid,
            hostName = hostName,
            deviceName = effectiveDevName,
            tcpPort = port,
            protocolVersion = ver ?: PttProtocol.PROTOCOL_VERSION,
            lastSeenMs = System.currentTimeMillis(),
            discoveredViaFallback = false
        )
        PttDiagnostics.info("DnsSd", "Discovered verified DNS-SD host '$hostName' ($deviceAddress:$port)")
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
        scheduler.remove(hostBeaconRunnable)
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
        scanning.set(false)
        hostingActive.set(false)
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
                                    }, 200L)
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
                    } else {
                        cb(
                            P2pDriver.GroupLinkState(
                                groupFormed = info.groupFormed,
                                isGroupOwner = info.isGroupOwner,
                                groupOwnerIp = info.groupOwnerAddress?.hostAddress
                            )
                        )
                    }
                }
            } catch (_: Throwable) {
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
