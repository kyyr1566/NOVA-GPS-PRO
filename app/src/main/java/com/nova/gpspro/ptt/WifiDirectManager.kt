package com.nova.gpspro.ptt

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
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

data class DiscoveredPttSession(
    val deviceAddress: String,
    val sessionId: String,
    val hostName: String,
    val deviceName: String,
    val tcpPort: Int,
    val protocolVersion: Int,
    val lastSeenMs: Long
)

enum class WifiEnvironmentIssue {
    NONE,
    P2P_UNSUPPORTED,
    WIFI_DISABLED,
    LOCATION_TOGGLE_OFF,
    MISSING_PERMISSION
}

/**
 * Dedicated offline Wi-Fi Direct + Local-Only Hotspot transport controller.
 * Strictly zero Bluetooth and zero internet / cloud calls.
 */
class WifiDirectManager(private val appCtx: Context) {

    interface Listener {
        fun onSessionsDiscovered(sessions: List<DiscoveredPttSession>)
        fun onP2pLinkEstablished(isGroupOwner: Boolean, groupOwnerIp: String)
        fun onP2pLinkDisconnected()
        fun onLocalHotspotStarted(ssid: String, passphrase: String)
        fun onTransportFailure(issue: WifiEnvironmentIssue)
    }

    var listener: Listener? = null

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
    private val connectivityManager: ConnectivityManager? = try {
        appCtx.getSystemService(ConnectivityManager::class.java)
    } catch (_: Throwable) {
        null
    }

    private var p2pChannel: WifiP2pManager.Channel? = null
    private var localServiceInfo: WifiP2pDnsSdServiceInfo? = null
    private var serviceRequest: WifiP2pDnsSdServiceRequest? = null
    private var hotspotReservation: WifiManager.LocalOnlyHotspotReservation? = null

    private val receiverRegistered = AtomicBoolean(false)
    private val scanning = AtomicBoolean(false)
    private val ownsGroup = AtomicBoolean(false)
    private val connectInProgress = AtomicBoolean(false)

    private val discoveredByAddress = ConcurrentHashMap<String, DiscoveredPttSession>()

    val isWifiDirectHardwareSupported: Boolean =
        appCtx.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT) && p2pManager != null

    private val autoStopScanRunnable = Runnable {
        stopSessionDiscovery()
    }

    private val p2pReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    if (state == WifiP2pManager.WIFI_P2P_STATE_DISABLED) {
                        listener?.onTransportFailure(WifiEnvironmentIssue.WIFI_DISABLED)
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
                        connectInProgress.set(false)
                        val goIp = p2pInfo.groupOwnerAddress?.hostAddress
                        if (!goIp.isNullOrBlank()) {
                            listener?.onP2pLinkEstablished(p2pInfo.isGroupOwner, goIp)
                        } else {
                            requestCurrentConnectionInfo()
                        }
                    } else {
                        if (!connectInProgress.get()) {
                            listener?.onP2pLinkDisconnected()
                        }
                    }
                }
            }
        }
    }

    fun diagnoseEnvironment(): WifiEnvironmentIssue {
        if (!isWifiDirectHardwareSupported) return WifiEnvironmentIssue.P2P_UNSUPPORTED
        if (!PttPermissions.hasWifiDirectDiscoveryPermission(appCtx)) return WifiEnvironmentIssue.MISSING_PERMISSION
        if (wifiManager != null && !wifiManager.isWifiEnabled) return WifiEnvironmentIssue.WIFI_DISABLED
        if (PttPermissions.isLocationToggleRequiredButOff(appCtx)) return WifiEnvironmentIssue.LOCATION_TOGGLE_OFF
        return WifiEnvironmentIssue.NONE
    }

    /**
     * Host flow: creates a Wi-Fi Direct group (Group Owner) and advertises the Walkie-Talkie
     * DNS-SD service record with protocol version verification metadata.
     */
    @SuppressLint("MissingPermission")
    fun startHostingGroup(
        sessionId: String,
        hostDisplayName: String,
        tcpPort: Int,
        onReady: () -> Unit,
        onError: (WifiEnvironmentIssue) -> Unit
    ) {
        val env = diagnoseEnvironment()
        if (env != WifiEnvironmentIssue.NONE) {
            onError(env)
            return
        }
        val mgr = p2pManager ?: run { onError(WifiEnvironmentIssue.P2P_UNSUPPORTED); return }
        val ch = ensureChannel() ?: run { onError(WifiEnvironmentIssue.P2P_UNSUPPORTED); return }
        ensureReceiverRegistered()

        val txtRecord = mapOf(
            "v" to PttProtocol.PROTOCOL_VERSION.toString(),
            "sid" to sessionId.take(24),
            "host" to hostDisplayName.take(28),
            "port" to tcpPort.toString()
        )
        val svcInfo = WifiP2pDnsSdServiceInfo.newInstance(
            PttProtocol.DNS_SERVICE_INSTANCE,
            PttProtocol.DNS_SERVICE_TYPE,
            txtRecord
        )
        localServiceInfo = svcInfo

        try {
            mgr.clearLocalServices(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    mgr.addLocalService(ch, svcInfo, null)
                }
                override fun onFailure(reason: Int) {
                    mgr.addLocalService(ch, svcInfo, null)
                }
            })

            // Clean any leftover group first so this phone reliably becomes Group Owner
            mgr.requestGroupInfo(ch) { existingGroup ->
                val createBlock = {
                    mgr.createGroup(ch, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() {
                            ownsGroup.set(true)
                            // Keep peer discovery active so nearby scanners see our DNS-SD beacon promptly
                            mgr.discoverPeers(ch, null)
                            requestCurrentConnectionInfo()
                            onReady()
                        }
                        override fun onFailure(reason: Int) {
                            onError(
                                if (wifiManager?.isWifiEnabled == false) WifiEnvironmentIssue.WIFI_DISABLED
                                else WifiEnvironmentIssue.P2P_UNSUPPORTED
                            )
                        }
                    })
                }
                if (existingGroup != null) {
                    mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() = createBlock()
                        override fun onFailure(reason: Int) = createBlock()
                    })
                } else {
                    createBlock()
                }
            }
        } catch (_: SecurityException) {
            onError(WifiEnvironmentIssue.MISSING_PERMISSION)
        } catch (_: Throwable) {
            onError(WifiEnvironmentIssue.P2P_UNSUPPORTED)
        }
    }

    /**
     * Considered fallback when Wi-Fi Direct group creation is blocked by OEM policy:
     * starts an offline Android Local-Only Hotspot (zero internet routing).
     */
    @SuppressLint("MissingPermission")
    fun startLocalOnlyHotspotFallback(
        onError: (WifiEnvironmentIssue) -> Unit
    ) {
        val wm = wifiManager ?: run { onError(WifiEnvironmentIssue.P2P_UNSUPPORTED); return }
        if (!PttPermissions.hasWifiDirectDiscoveryPermission(appCtx)) {
            onError(WifiEnvironmentIssue.MISSING_PERMISSION)
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
                    listener?.onLocalHotspotStarted(ssid, pass)
                }

                override fun onFailed(reason: Int) {
                    onError(WifiEnvironmentIssue.WIFI_DISABLED)
                }
            }, mainHandler)
        } catch (_: SecurityException) {
            onError(WifiEnvironmentIssue.MISSING_PERMISSION)
        } catch (_: Throwable) {
            onError(WifiEnvironmentIssue.P2P_UNSUPPORTED)
        }
    }

    /** Resolves the IPv4 gateway of a currently joined Local-Only Hotspot Wi-Fi network without internet. */
    fun resolveLocalWifiGatewayIp(): String? {
        try {
            val active = connectivityManager?.activeNetwork
            val props = if (active != null) connectivityManager.getLinkProperties(active) else null
            if (props != null) {
                for (route in props.routes) {
                    val gw = route.gateway
                    if (gw is Inet4Address && !gw.isLoopbackAddress && gw.hostAddress != "0.0.0.0") {
                        return gw.hostAddress
                    }
                }
            }
        } catch (_: Throwable) {}

        try {
            @Suppress("DEPRECATION")
            val dhcp = wifiManager?.dhcpInfo
            if (dhcp != null && dhcp.gateway != 0) {
                val g = dhcp.gateway
                return "${g and 0xFF}.${(g shr 8) and 0xFF}.${(g shr 16) and 0xFF}.${(g shr 24) and 0xFF}"
            }
        } catch (_: Throwable) {}
        return null
    }

    /**
     * Client flow: discovers nearby Walkie-Talkie sessions via Wi-Fi Direct DNS-SD service records,
     * filtering strictly for compatible application protocol versions.
     */
    @SuppressLint("MissingPermission")
    fun startSessionDiscovery(onError: (WifiEnvironmentIssue) -> Unit): Boolean {
        val env = diagnoseEnvironment()
        if (env != WifiEnvironmentIssue.NONE) {
            onError(env)
            return false
        }
        val mgr = p2pManager ?: run { onError(WifiEnvironmentIssue.P2P_UNSUPPORTED); return false }
        val ch = ensureChannel() ?: run { onError(WifiEnvironmentIssue.P2P_UNSUPPORTED); return false }
        ensureReceiverRegistered()

        discoveredByAddress.clear()
        listener?.onSessionsDiscovered(emptyList())
        scanning.set(true)

        try {
            mgr.setDnsSdResponseListeners(
                ch,
                { instanceName, _, srcDevice ->
                    if (instanceName.equals(PttProtocol.DNS_SERVICE_INSTANCE, ignoreCase = true)) {
                        // TXT listener populates full session details; keep device name updated if already known
                        val cur = discoveredByAddress[srcDevice.deviceAddress]
                        if (cur != null) {
                            discoveredByAddress[srcDevice.deviceAddress] = cur.copy(
                                deviceName = srcDevice.deviceName.orEmpty().ifBlank { cur.deviceName },
                                lastSeenMs = System.currentTimeMillis()
                            )
                            publishDiscoveredList()
                        }
                    }
                },
                { fullDomain, record, srcDevice ->
                    if (fullDomain.contains(PttProtocol.DNS_SERVICE_INSTANCE, ignoreCase = true)) {
                        handleDiscoveredTxtRecord(record, srcDevice)
                    }
                }
            )

            val req = WifiP2pDnsSdServiceRequest.newInstance()
            serviceRequest = req
            mgr.clearServiceRequests(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    mgr.addServiceRequest(ch, req, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() {
                            mgr.discoverServices(ch, object : WifiP2pManager.ActionListener {
                                override fun onSuccess() {}
                                override fun onFailure(reason: Int) {
                                    scanning.set(false)
                                    onError(
                                        if (wifiManager?.isWifiEnabled == false) WifiEnvironmentIssue.WIFI_DISABLED
                                        else WifiEnvironmentIssue.P2P_UNSUPPORTED
                                    )
                                }
                            })
                            mgr.discoverPeers(ch, null)
                        }
                        override fun onFailure(reason: Int) {
                            scanning.set(false)
                        }
                    })
                }
                override fun onFailure(reason: Int) {
                    mgr.addServiceRequest(ch, req, null)
                    mgr.discoverServices(ch, null)
                }
            })

            mainHandler.removeCallbacks(autoStopScanRunnable)
            mainHandler.postDelayed(autoStopScanRunnable, 30_000L)
            return true
        } catch (_: SecurityException) {
            scanning.set(false)
            onError(WifiEnvironmentIssue.MISSING_PERMISSION)
            return false
        } catch (_: Throwable) {
            scanning.set(false)
            onError(WifiEnvironmentIssue.P2P_UNSUPPORTED)
            return false
        }
    }

    fun stopSessionDiscovery() {
        mainHandler.removeCallbacks(autoStopScanRunnable)
        if (!scanning.getAndSet(false)) return
        val mgr = p2pManager ?: return
        val ch = p2pChannel ?: return
        try { mgr.clearServiceRequests(ch, null) } catch (_: Throwable) {}
        try { mgr.stopPeerDiscovery(ch, null) } catch (_: Throwable) {}
    }

    fun isScanning(): Boolean = scanning.get()

    /**
     * Initiates a Wi-Fi Direct connection to the selected host session.
     * Respects Android's system confirmation dialog (Push Button Configuration) without bypassing user consent.
     */
    @SuppressLint("MissingPermission")
    fun connectToHostDevice(
        deviceAddress: String,
        onInitiated: () -> Unit,
        onFailed: () -> Unit
    ) {
        val mgr = p2pManager ?: run { onFailed(); return }
        val ch = ensureChannel() ?: run { onFailed(); return }
        ensureReceiverRegistered()
        stopSessionDiscovery()

        val config = WifiP2pConfig().apply {
            this.deviceAddress = deviceAddress
            wps.setup = WpsInfo.PBC
            groupOwnerIntent = 0 // Prefer joining the existing host's Group Owner
        }
        connectInProgress.set(true)
        try {
            mgr.connect(ch, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    onInitiated()
                    requestCurrentConnectionInfo()
                }
                override fun onFailure(reason: Int) {
                    connectInProgress.set(false)
                    onFailed()
                }
            })
        } catch (_: Throwable) {
            connectInProgress.set(false)
            onFailed()
        }
    }

    private fun handleDiscoveredTxtRecord(record: Map<String, String>, srcDevice: WifiP2pDevice) {
        val ver = record["v"]?.toIntOrNull() ?: return
        if (ver != PttProtocol.PROTOCOL_VERSION) return // Incompatible protocol filtered safely
        val sid = record["sid"].orEmpty().trim()
        if (sid.isEmpty()) return
        val hostName = record["host"].orEmpty().trim().ifBlank {
            srcDevice.deviceName.orEmpty().ifBlank { "Host" }
        }
        val port = record["port"]?.toIntOrNull() ?: PttProtocol.DEFAULT_TCP_PORT

        discoveredByAddress[srcDevice.deviceAddress] = DiscoveredPttSession(
            deviceAddress = srcDevice.deviceAddress,
            sessionId = sid,
            hostName = hostName,
            deviceName = srcDevice.deviceName.orEmpty(),
            tcpPort = port,
            protocolVersion = ver,
            lastSeenMs = System.currentTimeMillis()
        )
        publishDiscoveredList()
    }

    private fun publishDiscoveredList() {
        val sorted = discoveredByAddress.values.sortedByDescending { it.lastSeenMs }
        mainHandler.post {
            listener?.onSessionsDiscovered(sorted)
        }
    }

    private fun requestCurrentConnectionInfo() {
        val mgr = p2pManager ?: return
        val ch = p2pChannel ?: return
        try {
            mgr.requestConnectionInfo(ch) { info ->
                if (info != null && info.groupFormed) {
                    val ip = info.groupOwnerAddress?.hostAddress
                    if (!ip.isNullOrBlank()) {
                        listener?.onP2pLinkEstablished(info.isGroupOwner, ip)
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    /**
     * Unified, idempotent cleanup of all Wi-Fi Direct discovery requests, local services,
     * pending connection attempts, owned P2P groups, and Local-Only Hotspot reservations.
     */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun teardownRadio() {
        mainHandler.removeCallbacks(autoStopScanRunnable)
        scanning.set(false)
        discoveredByAddress.clear()

        val mgr = p2pManager
        val ch = p2pChannel
        if (mgr != null && ch != null) {
            try { mgr.stopPeerDiscovery(ch, null) } catch (_: Throwable) {}
            try { mgr.clearServiceRequests(ch, null) } catch (_: Throwable) {}
            if (localServiceInfo != null) {
                try { mgr.clearLocalServices(ch, null) } catch (_: Throwable) {}
                localServiceInfo = null
            }
            if (connectInProgress.getAndSet(false)) {
                try { mgr.cancelConnect(ch, null) } catch (_: Throwable) {}
            }
            // Remove Wi-Fi Direct group if we created it or are still attached to a session group
            try {
                mgr.requestGroupInfo(ch) { group ->
                    if (group != null && (ownsGroup.get() || group.isGroupOwner)) {
                        try { mgr.removeGroup(ch, null) } catch (_: Throwable) {}
                    } else if (group != null) {
                        // Client leaving group cleanly
                        try { mgr.removeGroup(ch, null) } catch (_: Throwable) {}
                    }
                    ownsGroup.set(false)
                }
            } catch (_: Throwable) {
                if (ownsGroup.getAndSet(false)) {
                    try { mgr.removeGroup(ch, null) } catch (_: Throwable) {}
                }
            }
        }
        serviceRequest = null
        closeHotspotReservation()
        unregisterReceiverQuietly()
    }

    private fun closeHotspotReservation() {
        try { hotspotReservation?.close() } catch (_: Throwable) {}
        hotspotReservation = null
    }

    private fun ensureChannel(): WifiP2pManager.Channel? {
        p2pChannel?.let { return it }
        val mgr = p2pManager ?: return null
        return try {
            mgr.initialize(appCtx, Looper.getMainLooper(), null).also { p2pChannel = it }
        } catch (_: Throwable) {
            null
        }
    }

    private fun ensureReceiverRegistered() {
        if (receiverRegistered.compareAndSet(false, true)) {
            val filter = IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            }
            try {
                if (Build.VERSION.SDK_INT >= 33) {
                    appCtx.registerReceiver(p2pReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    appCtx.registerReceiver(p2pReceiver, filter)
                }
            } catch (_: Throwable) {
                receiverRegistered.set(false)
            }
        }
    }

    private fun unregisterReceiverQuietly() {
        if (receiverRegistered.compareAndSet(true, false)) {
            try { appCtx.unregisterReceiver(p2pReceiver) } catch (_: Throwable) {}
        }
    }
}
