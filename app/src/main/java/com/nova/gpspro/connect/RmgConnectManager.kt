package com.nova.gpspro.connect

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.net.NetworkInfo
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.nova.gpspro.location.GpsState
import com.nova.gpspro.location.GpsStatus
import com.nova.gpspro.location.LocationEngine
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Central offline session coordinator for RMG CONNECT.
 * Orchestrates Wi-Fi Direct + Bluetooth discovery, connection request/accept/reject/block,
 * direct multi-peer messaging & auto-reconnect, optional live GPS sharing, link quality telemetry,
 * real-time voice engine, and Android 14–15 foreground service synchronization.
 */
class RmgConnectManager(
    private val appCtx: Context,
    private val gpsEngine: LocationEngine
) {

    fun interface Listener {
        fun onSnapshot(snapshot: RmgSessionSnapshot)
    }

    interface UiEvents {
        fun onToastInfo(resId: Int)
    }

    private val prefs = appCtx.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioPool = Executors.newCachedThreadPool { r ->
        Thread(r, "rmg-io").apply { isDaemon = true }
    }
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "rmg-sched").apply { isDaemon = true }
    }

    val localNodeId: String = prefs.getString(KEY_NODE_ID, null) ?: UUID.randomUUID().toString().also {
        prefs.edit().putString(KEY_NODE_ID, it).apply()
    }
    private val localSenderHash: Long = RmgAudioCodec.senderHash(localNodeId)

    @Volatile
    var localDisplayName: String = prefs.getString(KEY_DISPLAY_NAME, null)
        ?.takeIf { it.isNotBlank() }
        ?: defaultDeviceName()
        private set

    @Volatile
    var isMainActivityStarted: Boolean = false

    var uiEvents: UiEvents? = null

    private val listeners = CopyOnWriteArraySet<Listener>()

    // Hardware managers (resolved safely — devices without BT or Wi-Fi Direct never crash)
    private val wifiManager: WifiManager? = try { appCtx.applicationContext.getSystemService(WifiManager::class.java) } catch (_: Exception) { null }
    private val p2pManager: WifiP2pManager? = try { appCtx.getSystemService(WifiP2pManager::class.java) } catch (_: Exception) { null }
    private var p2pChannel: WifiP2pManager.Channel? = null
    private val btManager: BluetoothManager? = try { appCtx.getSystemService(BluetoothManager::class.java) } catch (_: Exception) { null }
    private val btAdapter: BluetoothAdapter? get() = try { btManager?.adapter } catch (_: Exception) { null }

    val wifiDirectSupported: Boolean =
        appCtx.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_WIFI_DIRECT) && p2pManager != null
    val bluetoothSupported: Boolean =
        appCtx.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_BLUETOOTH) && btAdapter != null

    // Runtime state
    private val discovering = AtomicBoolean(false)
    private val hostingGroup = AtomicBoolean(false)
    private val sharingLocation = AtomicBoolean(RmgDefaults.DEFAULT_LOCATION_SHARING)
    private val voiceEverUnmutedInSession = AtomicBoolean(false)

    private val discoveredMap = ConcurrentHashMap<String, DiscoveredPeer>()
    private val connectedMap = ConcurrentHashMap<String, ConnectedPeer>()
    private val activeConnections = ConcurrentHashMap<String, PeerChannel>()
    private val acceptedSessionNodes = ConcurrentHashMap<String, ReconnectTarget>()
    private val pendingInvites = ConcurrentHashMap<String, PendingInviteHolder>()
    private val outboxByPeer = ConcurrentHashMap<String, MutableList<RmgWireProtocol.Packet.Chat>>()
    private val seenChatIds = Collections.synchronizedSet(LinkedHashSet<String>())
    private val chatHistory = Collections.synchronizedList(ArrayList<ChatMessage>())
    private val blockedMap = ConcurrentHashMap<String, BlockedPeerEntry>()

    private var tcpServer: ServerSocket? = null
    private var btServer: BluetoothServerSocket? = null
    private var receiversRegistered = false
    private var pendingWifiConnectTargetName: String? = null
    private var pendingWifiAutoRequest = AtomicBoolean(false)
    private var lastLocationSentAtMs = 0L
    private var lastSentLat = Double.NaN
    private var lastSentLon = Double.NaN
    private val pingSeq = AtomicInteger(1)

    @Volatile private var btAudioActive = false
    @Volatile private var btAudioAvailable = false

    private data class ReconnectTarget(
        val nodeId: String,
        val name: String,
        val transport: TransportType,
        val hostIp: String?,
        val btAddress: String?
    )

    private class PendingInviteHolder(
        val invite: IncomingInvite,
        val channel: RawEndpoint,
        val output: OutputStream
    )

    private interface RawEndpoint : Closeable {
        val remoteAddress: String
        val hostIp: String?
        val transport: TransportType
        val input: InputStream
        val output: OutputStream
    }

    private class TcpEndpoint(private val socket: Socket) : RawEndpoint {
        override val remoteAddress: String = socket.inetAddress?.hostAddress ?: "tcp"
        override val hostIp: String? = socket.inetAddress?.hostAddress
        override val transport: TransportType = TransportType.WIFI_DIRECT
        override val input: InputStream = socket.getInputStream()
        override val output: OutputStream = socket.getOutputStream()
        override fun close() { try { socket.close() } catch (_: Exception) {} }
    }

    private class BtEndpoint(private val socket: BluetoothSocket, addrFallback: String) : RawEndpoint {
        override val remoteAddress: String = try { socket.remoteDevice?.address ?: addrFallback } catch (_: Exception) { addrFallback }
        override val hostIp: String? = null
        override val transport: TransportType = TransportType.BLUETOOTH
        override val input: InputStream = socket.inputStream
        override val output: OutputStream = socket.outputStream
        override fun close() { try { socket.close() } catch (_: Exception) {} }
    }

    private inner class PeerChannel(
        val nodeId: String,
        var peerName: String,
        val endpoint: RawEndpoint
    ) {
        val alive = AtomicBoolean(true)
        private val writeLock = Any()

        fun sendAsync(packet: RmgWireProtocol.Packet) {
            if (!alive.get()) return
            ioPool.execute {
                sendBlocking(packet)
            }
        }

        fun sendBlocking(packet: RmgWireProtocol.Packet): Boolean {
            if (!alive.get()) return false
            return try {
                synchronized(writeLock) {
                    RmgWireProtocol.writePacket(endpoint.output, packet)
                }
                true
            } catch (_: Exception) {
                handleUnexpectedDrop(this)
                false
            }
        }

        fun closeQuietly() {
            alive.set(false)
            endpoint.close()
        }
    }

    private val voiceEngine = RmgVoiceEngine(
        ctx = appCtx,
        localSenderHash = localSenderHash,
        wifiTargetsProvider = { resolveActiveWifiVoiceTargets() },
        btFallbackSender = { compactFrame -> sendCompactVoiceOverStableBluetooth(compactFrame) },
        onLocalSpeakingChanged = { speaking, ptt ->
            broadcastPacket(RmgWireProtocol.Packet.VoiceState(localNodeId, speaking, ptt))
            publishSnapshot()
        },
        onRemoteSpeakingUpdate = { senderHash, speaking, ptt, lossPct ->
            val match = connectedMap.values.firstOrNull { RmgAudioCodec.senderHash(it.nodeId) == senderHash }
            if (match != null) {
                connectedMap[match.nodeId] = match.copy(
                    isSpeaking = speaking,
                    isPttPriority = speaking && ptt,
                    packetLossPct = lossPct
                )
                publishSnapshot()
            }
        },
        onBluetoothAudioStateChanged = { active, available ->
            btAudioActive = active
            btAudioAvailable = available
            publishSnapshot()
        }
    )

    private val gpsListener = LocationEngine.Listener { state ->
        onLocalGpsUpdate(state)
    }

    @Volatile
    var snapshot: RmgSessionSnapshot = buildSnapshot()
        private set

    init {
        loadBlockedPeers()
        scheduler.scheduleWithFixedDelay({ runPeriodicMaintenance() }, 3_500L, 3_500L, TimeUnit.MILLISECONDS)
    }

    fun addListener(l: Listener) {
        listeners.add(l)
        l.onSnapshot(snapshot)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    fun setDisplayName(newName: String) {
        val clean = newName.trim().take(36)
        if (clean.isEmpty()) return
        localDisplayName = clean
        prefs.edit().putString(KEY_DISPLAY_NAME, clean).apply()
        publishSnapshot()
    }

    /**
     * Used by MainActivity.onStop(): keeps GPS alive ONLY while an active RMG CONNECT session
     * has location sharing explicitly enabled by the user.
     */
    fun shouldKeepGpsRunningInBackground(): Boolean =
        GpsLifecyclePolicy.shouldKeepGpsOnActivityStop(
            hasActiveSession = snapshot.hasActiveSession,
            isSharingLocation = sharingLocation.get()
        )

    fun hasMicrophoneForegroundEligibility(): Boolean =
        voiceEverUnmutedInSession.get() && RmgPermissions.hasRecordAudioPermission(appCtx)

    // ================================================================ Discovery & Hosting

    @SuppressLint("MissingPermission")
    fun startDiscovery() {
        ensureTransportInfrastructure()
        discovering.set(true)
        var anyStarted = false

        // 1) Wi-Fi Direct peer & service discovery
        if (wifiDirectSupported && RmgPermissions.hasWifiDirectPermission(appCtx)) {
            val mgr = p2pManager
            val ch = ensureP2pChannel()
            if (mgr != null && ch != null) {
                try {
                    val txt = mapOf("node" to localNodeId.take(16), "name" to localDisplayName)
                    val svc = WifiP2pDnsSdServiceInfo.newInstance("_rmgconnect", "_tcp", txt)
                    mgr.clearLocalServices(ch, null)
                    mgr.addLocalService(ch, svc, null)
                    mgr.setDnsSdResponseListeners(
                        ch,
                        { _, _, srcDevice -> upsertWifiDevice(srcDevice, null) },
                        { _, record, srcDevice -> upsertWifiDevice(srcDevice, record["name"]) }
                    )
                    mgr.clearServiceRequests(ch, null)
                    mgr.addServiceRequest(ch, WifiP2pDnsSdServiceRequest.newInstance(), null)
                    mgr.discoverServices(ch, null)
                    mgr.discoverPeers(ch, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() {}
                        override fun onFailure(reason: Int) {}
                    })
                    anyStarted = true
                } catch (_: Exception) {}
            }
        }

        // 2) Bluetooth bonded + active inquiry scan (reads real RSSI dBm)
        val adapter = btAdapter
        if (bluetoothSupported && adapter != null && adapter.isEnabled) {
            if (RmgPermissions.hasBluetoothConnectPermission(appCtx)) {
                try {
                    adapter.bondedDevices?.forEach { dev ->
                        upsertBluetoothDevice(dev, rssi = null)
                    }
                    anyStarted = true
                } catch (_: SecurityException) {}
            }
            if (RmgPermissions.hasBluetoothScanPermission(appCtx)) {
                try {
                    if (adapter.isDiscovering) adapter.cancelDiscovery()
                    if (adapter.startDiscovery()) anyStarted = true
                } catch (_: SecurityException) {}
            }
        }

        if (!anyStarted) {
            discovering.set(false)
            uiEvents?.onToastInfo(com.nova.gpspro.R.string.rmg_connect_hw_unavailable)
        } else {
            mainHandler.removeCallbacks(autoStopDiscoveryRunnable)
            mainHandler.postDelayed(autoStopDiscoveryRunnable, DISCOVERY_WINDOW_MS)
        }
        publishSnapshot()
    }

    @SuppressLint("MissingPermission")
    fun stopDiscovery() {
        mainHandler.removeCallbacks(autoStopDiscoveryRunnable)
        if (!discovering.getAndSet(false)) return
        try {
            val ch = p2pChannel
            if (ch != null) p2pManager?.stopPeerDiscovery(ch, null)
        } catch (_: Exception) {}
        try {
            if (RmgPermissions.hasBluetoothScanPermission(appCtx)) {
                btAdapter?.takeIf { it.isDiscovering }?.cancelDiscovery()
            }
        } catch (_: Exception) {}
        publishSnapshot()
    }

    private val autoStopDiscoveryRunnable = Runnable { stopDiscovery() }

    @SuppressLint("MissingPermission")
    fun startDirectGroupHost() {
        ensureTransportInfrastructure()
        hostingGroup.set(true)
        voiceEngine.startSession()

        if (wifiDirectSupported && RmgPermissions.hasWifiDirectPermission(appCtx)) {
            val ch = ensureP2pChannel()
            if (ch != null) {
                try {
                    p2pManager?.createGroup(ch, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() { publishSnapshot() }
                        override fun onFailure(reason: Int) { publishSnapshot() }
                    })
                } catch (_: Exception) {}
            }
        }
        syncForegroundService()
        publishSnapshot()
    }

    // ================================================================ Outgoing Connect Request

    @SuppressLint("MissingPermission")
    fun connectToDiscovered(peer: DiscoveredPeer) {
        if (isBlocked(peer.key, peer.address)) return
        ensureTransportInfrastructure()
        voiceEngine.startSession()
        discoveredMap[peer.key] = peer.copy(isPending = true)
        publishSnapshot()

        when (peer.transport) {
            TransportType.BLUETOOTH -> {
                ioPool.execute {
                    connectViaBluetooth(peer.address, peer.name, peer.key)
                }
            }
            TransportType.WIFI_DIRECT -> {
                if (!RmgPermissions.hasWifiDirectPermission(appCtx)) {
                    discoveredMap[peer.key] = peer.copy(isPending = false)
                    publishSnapshot()
                    return
                }
                val ch = ensureP2pChannel() ?: return
                pendingWifiConnectTargetName = peer.name
                pendingWifiAutoRequest.set(true)
                val cfg = WifiP2pConfig().apply {
                    deviceAddress = peer.address
                    wps.setup = android.net.wifi.WpsInfo.PBC
                }
                try {
                    p2pManager?.connect(ch, cfg, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() {
                            syncForegroundService()
                            publishSnapshot()
                        }
                        override fun onFailure(reason: Int) {
                            pendingWifiAutoRequest.set(false)
                            discoveredMap[peer.key]?.let { discoveredMap[peer.key] = it.copy(isPending = false) }
                            publishSnapshot()
                        }
                    })
                } catch (_: Exception) {
                    pendingWifiAutoRequest.set(false)
                    discoveredMap[peer.key]?.let { discoveredMap[peer.key] = it.copy(isPending = false) }
                    publishSnapshot()
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectViaBluetooth(address: String, fallbackName: String, discoveredKey: String?) {
        val adapter = btAdapter ?: return
        if (!RmgPermissions.hasBluetoothConnectPermission(appCtx)) return
        try {
            if (RmgPermissions.hasBluetoothScanPermission(appCtx) && adapter.isDiscovering) {
                adapter.cancelDiscovery()
            }
            val device = adapter.getRemoteDevice(address)
            val sock = device.createInsecureRfcommSocketToServiceRecord(RMG_BT_UUID)
            sock.connect()
            initiateOutgoingHandshake(BtEndpoint(sock, address), fallbackName, discoveredKey)
        } catch (_: Exception) {
            if (discoveredKey != null) {
                discoveredMap[discoveredKey]?.let { discoveredMap[discoveredKey] = it.copy(isPending = false) }
            }
            publishSnapshot()
        }
    }

    private fun connectViaTcp(hostIp: String, fallbackName: String) {
        ioPool.execute {
            try {
                val sock = Socket()
                sock.tcpNoDelay = true
                sock.connect(InetSocketAddress(hostIp, RmgDefaults.TCP_CONTROL_PORT), 6_000)
                initiateOutgoingHandshake(TcpEndpoint(sock), fallbackName, null)
            } catch (_: Exception) {
                publishSnapshot()
            }
        }
    }

    private fun initiateOutgoingHandshake(endpoint: RawEndpoint, fallbackName: String, discoveredKey: String?) {
        try {
            RmgWireProtocol.writePacket(
                endpoint.output,
                RmgWireProtocol.Packet.ConnRequest(localNodeId, localDisplayName, RmgDefaults.PROTOCOL_VERSION)
            )
            when (val reply = RmgWireProtocol.readPacket(endpoint.input)) {
                is RmgWireProtocol.Packet.ConnAccept -> {
                    val remoteId = reply.nodeId
                    val remoteName = reply.displayName.ifBlank { fallbackName }
                    if (discoveredKey != null) {
                        discoveredMap[discoveredKey]?.let {
                            discoveredMap[discoveredKey] = it.copy(isPending = false, isConnected = true)
                        }
                    }
                    promoteAcceptedConnection(remoteId, remoteName, endpoint)
                }
                is RmgWireProtocol.Packet.ConnReject -> {
                    endpoint.close()
                    if (discoveredKey != null) {
                        discoveredMap[discoveredKey]?.let { discoveredMap[discoveredKey] = it.copy(isPending = false) }
                    }
                    mainHandler.post { uiEvents?.onToastInfo(com.nova.gpspro.R.string.rmg_connect_rejected_msg) }
                    publishSnapshot()
                }
                is RmgWireProtocol.Packet.ConnBlock -> {
                    endpoint.close()
                    if (discoveredKey != null) {
                        discoveredMap[discoveredKey]?.let { discoveredMap[discoveredKey] = it.copy(isPending = false) }
                    }
                    publishSnapshot()
                }
                else -> {
                    endpoint.close()
                    publishSnapshot()
                }
            }
        } catch (_: Exception) {
            endpoint.close()
            if (discoveredKey != null) {
                discoveredMap[discoveredKey]?.let { discoveredMap[discoveredKey] = it.copy(isPending = false) }
            }
            publishSnapshot()
        }
    }

    // ================================================================ Incoming Accept / Reject / Block

    fun acceptInvite(inviteId: String) {
        val holder = pendingInvites.remove(inviteId) ?: return
        ioPool.execute {
            try {
                RmgWireProtocol.writePacket(
                    holder.output,
                    RmgWireProtocol.Packet.ConnAccept(localNodeId, localDisplayName, RmgDefaults.UDP_VOICE_PORT)
                )
                promoteAcceptedConnection(holder.invite.nodeId, holder.invite.deviceName, holder.channel)
            } catch (_: Exception) {
                holder.channel.close()
                publishSnapshot()
            }
        }
    }

    fun rejectInvite(inviteId: String) {
        val holder = pendingInvites.remove(inviteId) ?: return
        ioPool.execute {
            try {
                RmgWireProtocol.writePacket(holder.output, RmgWireProtocol.Packet.ConnReject(localNodeId))
            } catch (_: Exception) {}
            holder.channel.close()
            publishSnapshot()
            syncForegroundService()
        }
    }

    fun blockInvite(inviteId: String) {
        val holder = pendingInvites.remove(inviteId) ?: return
        blockPeerInternal(holder.invite.nodeId, holder.invite.address, holder.invite.deviceName)
        ioPool.execute {
            try {
                RmgWireProtocol.writePacket(holder.output, RmgWireProtocol.Packet.ConnBlock(localNodeId))
            } catch (_: Exception) {}
            holder.channel.close()
            mainHandler.post { uiEvents?.onToastInfo(com.nova.gpspro.R.string.rmg_connect_blocked_msg) }
            publishSnapshot()
            syncForegroundService()
        }
    }

    fun blockConnectedPeer(nodeId: String) {
        val peer = connectedMap[nodeId] ?: return
        blockPeerInternal(nodeId, peer.address, peer.name)
        disconnectPeer(nodeId, sendBye = false, sendBlock = true)
    }

    fun unblockPeer(key: String) {
        blockedMap.remove(key)
        saveBlockedPeers()
        publishSnapshot()
    }

    private fun blockPeerInternal(nodeId: String, address: String, name: String) {
        val now = System.currentTimeMillis()
        if (nodeId.isNotBlank()) {
            blockedMap[nodeId] = BlockedPeerEntry(nodeId, name, now)
        }
        if (address.isNotBlank() && address != "tcp") {
            blockedMap[address] = BlockedPeerEntry(address, name, now)
        }
        acceptedSessionNodes.remove(nodeId)
        discoveredMap.entries.removeIf { (_, v) -> v.address == address || v.key == nodeId }
        saveBlockedPeers()
    }

    private fun isBlocked(nodeId: String?, address: String?): Boolean {
        if (!nodeId.isNullOrBlank() && blockedMap.containsKey(nodeId)) return true
        if (!address.isNullOrBlank() && blockedMap.containsKey(address)) return true
        return false
    }

    // ================================================================ Session Promotion & Direct Multi-Peer

    private fun promoteAcceptedConnection(remoteId: String, remoteName: String, endpoint: RawEndpoint) {
        voiceEngine.startSession()

        // Close any stale socket for the same logical node
        activeConnections.remove(remoteId)?.closeQuietly()

        val channel = PeerChannel(remoteId, remoteName, endpoint)
        activeConnections[remoteId] = channel

        val prev = connectedMap[remoteId]
        val now = System.currentTimeMillis()
        connectedMap[remoteId] = ConnectedPeer(
            nodeId = remoteId,
            name = remoteName,
            address = endpoint.remoteAddress,
            hostIp = endpoint.hostIp ?: prev?.hostIp,
            transport = endpoint.transport,
            state = PeerLinkState.CONNECTED,
            rssiDbm = prev?.rssiDbm ?: lookupDiscoveredRssi(endpoint.remoteAddress),
            rttMs = prev?.rttMs,
            isSharingLocation = prev?.isSharingLocation ?: false,
            lastKnownLocation = prev?.lastKnownLocation,
            reconnectAttempt = 0,
            lastSeenMs = now
        )

        acceptedSessionNodes[remoteId] = ReconnectTarget(
            nodeId = remoteId,
            name = remoteName,
            transport = endpoint.transport,
            hostIp = endpoint.hostIp,
            btAddress = if (endpoint.transport == TransportType.BLUETOOTH) endpoint.remoteAddress else null
        )

        syncForegroundService()
        publishSnapshot()

        // Share accepted Wi-Fi Direct roster so group members establish direct peer-to-peer links
        // inside the same Wi-Fi Direct group (no channels, no multi-hop mesh).
        broadcastWifiRoster()

        // Flush any queued chat messages waiting during brief reconnect
        outboxByPeer.remove(remoteId)?.let { queued ->
            for (pkt in queued) channel.sendAsync(pkt)
        }

        // Immediately send current GPS snapshot if location sharing is active
        if (sharingLocation.get()) {
            sendCurrentGpsTo(channel)
        }

        // Start dedicated packet reader loop for this peer
        ioPool.execute { readLoop(channel) }
    }

    private fun broadcastWifiRoster() {
        val wifiPeers = connectedMap.values.filter {
            it.state == PeerLinkState.CONNECTED && it.transport == TransportType.WIFI_DIRECT && !it.hostIp.isNullOrBlank()
        }
        if (wifiPeers.size < 2) return
        val entries = wifiPeers.map { RmgWireProtocol.Packet.RosterEntry(it.nodeId, it.name, it.hostIp!!) }
        val rosterPkt = RmgWireProtocol.Packet.PeerRoster(entries)
        for (p in wifiPeers) {
            activeConnections[p.nodeId]?.sendAsync(rosterPkt)
        }
    }

    private fun readLoop(channel: PeerChannel) {
        try {
            while (channel.alive.get()) {
                val pkt = RmgWireProtocol.readPacket(channel.endpoint.input)
                handleIncomingPacket(channel, pkt)
            }
        } catch (_: Exception) {
            handleUnexpectedDrop(channel)
        }
    }

    private fun handleIncomingPacket(from: PeerChannel, pkt: RmgWireProtocol.Packet) {
        val nowWall = System.currentTimeMillis()
        connectedMap[from.nodeId]?.let { cur ->
            connectedMap[from.nodeId] = cur.copy(lastSeenMs = nowWall, state = PeerLinkState.CONNECTED)
        }

        when (pkt) {
            is RmgWireProtocol.Packet.Chat -> {
                from.sendAsync(RmgWireProtocol.Packet.ChatAck(pkt.msgId, localNodeId))
                if (seenChatIds.add(pkt.msgId)) {
                    synchronized(seenChatIds) {
                        while (seenChatIds.size > 400) {
                            val first = seenChatIds.iterator().next()
                            seenChatIds.remove(first)
                        }
                    }
                    chatHistory.add(
                        ChatMessage(
                            id = pkt.msgId,
                            senderId = pkt.senderId,
                            senderName = pkt.senderName.ifBlank { from.peerName },
                            text = pkt.text,
                            timestampMs = nowWall,
                            outgoing = false,
                            delivery = ChatDelivery.DELIVERED
                        )
                    )
                    trimChatHistory()
                    // If we are the Wi-Fi Direct group host bridging direct group peers that haven't linked yet,
                    // forward within the immediate direct group only (single-star group, NOT multi-hop mesh).
                    if (hostingGroup.get()) {
                        for ((id, ch) in activeConnections) {
                            if (id != from.nodeId && id != pkt.senderId) ch.sendAsync(pkt)
                        }
                    }
                    publishSnapshot()
                }
            }

            is RmgWireProtocol.Packet.ChatAck -> {
                synchronized(chatHistory) {
                    val idx = chatHistory.indexOfLast { it.id == pkt.msgId && it.outgoing }
                    if (idx >= 0 && chatHistory[idx].delivery != ChatDelivery.DELIVERED) {
                        chatHistory[idx] = chatHistory[idx].copy(delivery = ChatDelivery.DELIVERED)
                    }
                }
                publishSnapshot()
            }

            is RmgWireProtocol.Packet.LocationUpdate -> {
                val targetId = pkt.senderId.ifBlank { from.nodeId }
                val loc = SharedLocation(
                    latitude = pkt.latitude,
                    longitude = pkt.longitude,
                    accuracyM = pkt.accuracyM,
                    speedMps = pkt.speedMps,
                    timestampMs = nowWall
                )
                connectedMap[targetId]?.let { cur ->
                    connectedMap[targetId] = cur.copy(
                        isSharingLocation = true,
                        lastKnownLocation = loc,
                        lastSeenMs = nowWall
                    )
                }
                if (hostingGroup.get()) {
                    for ((id, ch) in activeConnections) {
                        if (id != from.nodeId && id != targetId) ch.sendAsync(pkt)
                    }
                }
                publishSnapshot()
            }

            is RmgWireProtocol.Packet.LocationStop -> {
                val targetId = pkt.senderId.ifBlank { from.nodeId }
                connectedMap[targetId]?.let { cur ->
                    // Keep lastKnownLocation intact, only flip live flag off!
                    connectedMap[targetId] = cur.copy(isSharingLocation = false)
                }
                if (hostingGroup.get()) {
                    for ((id, ch) in activeConnections) {
                        if (id != from.nodeId && id != targetId) ch.sendAsync(pkt)
                    }
                }
                publishSnapshot()
            }

            is RmgWireProtocol.Packet.Ping -> {
                from.sendAsync(RmgWireProtocol.Packet.Pong(pkt.seq, pkt.sentAtElapsedMs))
                if (pkt.rssiHintDbm in -120..-15) {
                    connectedMap[from.nodeId]?.let { cur ->
                        connectedMap[from.nodeId] = cur.copy(rssiDbm = pkt.rssiHintDbm)
                    }
                }
            }

            is RmgWireProtocol.Packet.Pong -> {
                val rtt = (SystemClock.elapsedRealtime() - pkt.echoElapsedMs).toInt().coerceAtLeast(1)
                val wifiRssi = readCurrentWifiRssiDbm()
                val wifiSpeed = readCurrentWifiLinkSpeedMbps()
                connectedMap[from.nodeId]?.let { cur ->
                    connectedMap[from.nodeId] = cur.copy(
                        rttMs = rtt,
                        rssiDbm = if (cur.transport == TransportType.WIFI_DIRECT && wifiRssi != null) wifiRssi else cur.rssiDbm,
                        linkSpeedMbps = if (cur.transport == TransportType.WIFI_DIRECT) wifiSpeed else null
                    )
                }
                publishSnapshot()
            }

            is RmgWireProtocol.Packet.PeerRoster -> {
                // Establish direct links to other accepted peers in the same Wi-Fi Direct subnet
                for (entry in pkt.members) {
                    if (entry.nodeId == localNodeId || isBlocked(entry.nodeId, entry.hostIp)) continue
                    if (!activeConnections.containsKey(entry.nodeId) && localNodeId < entry.nodeId) {
                        acceptedSessionNodes[entry.nodeId] = ReconnectTarget(
                            nodeId = entry.nodeId,
                            name = entry.name,
                            transport = TransportType.WIFI_DIRECT,
                            hostIp = entry.hostIp,
                            btAddress = null
                        )
                        connectViaTcp(entry.hostIp, entry.name)
                    }
                }
            }

            is RmgWireProtocol.Packet.VoiceState -> {
                connectedMap[from.nodeId]?.let { cur ->
                    connectedMap[from.nodeId] = cur.copy(
                        isSpeaking = pkt.speaking,
                        isPttPriority = pkt.speaking && pkt.pttPriority
                    )
                }
                publishSnapshot()
            }

            is RmgWireProtocol.Packet.BtVoiceFrame -> {
                voiceEngine.onIncomingBtVoiceFrame(pkt.rawFrame)
            }

            is RmgWireProtocol.Packet.Bye -> {
                acceptedSessionNodes.remove(from.nodeId)
                from.closeQuietly()
                activeConnections.remove(from.nodeId, from)
                connectedMap[from.nodeId]?.let { cur ->
                    if (cur.lastKnownLocation != null) {
                        // Preserve last known location entry marked disconnected
                        connectedMap[from.nodeId] = cur.copy(
                            state = PeerLinkState.DISCONNECTED,
                            isSharingLocation = false,
                            isSpeaking = false
                        )
                    } else {
                        connectedMap.remove(from.nodeId)
                    }
                }
                syncSessionLifecycleAfterChange()
                publishSnapshot()
            }

            else -> Unit
        }
    }

    private fun handleUnexpectedDrop(channel: PeerChannel) {
        if (!activeConnections.remove(channel.nodeId, channel)) {
            channel.closeQuietly()
            return
        }
        channel.closeQuietly()
        val target = acceptedSessionNodes[channel.nodeId]
        val blocked = isBlocked(channel.nodeId, channel.endpoint.remoteAddress)
        if (target == null || !ReconnectBackoff.shouldRetry(0, explicitBye = false, blocked = blocked)) {
            connectedMap.remove(channel.nodeId)
            publishSnapshot()
            syncSessionLifecycleAfterChange()
            return
        }
        connectedMap[channel.nodeId]?.let { cur ->
            connectedMap[channel.nodeId] = cur.copy(
                state = PeerLinkState.RECONNECTING,
                isSpeaking = false,
                reconnectAttempt = 1
            )
        }
        publishSnapshot()
        scheduleReconnectAttempt(target, 0)
    }

    private fun scheduleReconnectAttempt(target: ReconnectTarget, attemptIndex: Int) {
        if (!ReconnectBackoff.shouldRetry(attemptIndex, explicitBye = false, blocked = isBlocked(target.nodeId, target.btAddress ?: target.hostIp))) {
            acceptedSessionNodes.remove(target.nodeId)
            connectedMap[target.nodeId]?.let { cur ->
                if (cur.lastKnownLocation != null) {
                    connectedMap[target.nodeId] = cur.copy(state = PeerLinkState.DISCONNECTED, isSharingLocation = false)
                } else {
                    connectedMap.remove(target.nodeId)
                }
            }
            publishSnapshot()
            syncSessionLifecycleAfterChange()
            return
        }
        val delay = ReconnectBackoff.delayMs(attemptIndex)
        scheduler.schedule({
            if (activeConnections.containsKey(target.nodeId) || !acceptedSessionNodes.containsKey(target.nodeId)) {
                return@schedule
            }
            val ok = tryReconnectOnce(target)
            if (!ok && !activeConnections.containsKey(target.nodeId)) {
                connectedMap[target.nodeId]?.let { cur ->
                    connectedMap[target.nodeId] = cur.copy(
                        state = PeerLinkState.RECONNECTING,
                        reconnectAttempt = attemptIndex + 2
                    )
                }
                publishSnapshot()
                scheduleReconnectAttempt(target, attemptIndex + 1)
            }
        }, delay, TimeUnit.MILLISECONDS)
    }

    @SuppressLint("MissingPermission")
    private fun tryReconnectOnce(target: ReconnectTarget): Boolean {
        return try {
            when (target.transport) {
                TransportType.WIFI_DIRECT -> {
                    val ip = target.hostIp ?: return false
                    val sock = Socket()
                    sock.tcpNoDelay = true
                    sock.connect(InetSocketAddress(ip, RmgDefaults.TCP_CONTROL_PORT), 4_000)
                    initiateOutgoingHandshake(TcpEndpoint(sock), target.name, null)
                    activeConnections.containsKey(target.nodeId)
                }
                TransportType.BLUETOOTH -> {
                    val addr = target.btAddress ?: return false
                    if (!RmgPermissions.hasBluetoothConnectPermission(appCtx)) return false
                    val dev = btAdapter?.getRemoteDevice(addr) ?: return false
                    val sock = dev.createInsecureRfcommSocketToServiceRecord(RMG_BT_UUID)
                    sock.connect()
                    initiateOutgoingHandshake(BtEndpoint(sock, addr), target.name, null)
                    activeConnections.containsKey(target.nodeId)
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    // ================================================================ Chat Messaging

    fun sendChatMessage(rawText: String): Boolean {
        val text = rawText.trim()
        if (text.isEmpty()) return false
        val msgId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        seenChatIds.add(msgId)

        val pkt = RmgWireProtocol.Packet.Chat(msgId, localNodeId, localDisplayName, text, now)
        var sentImmediate = 0
        var queuedForReconnect = 0

        for ((nodeId, peer) in connectedMap) {
            val ch = activeConnections[nodeId]
            if (ch != null && ch.alive.get()) {
                ch.sendAsync(pkt)
                sentImmediate++
            } else if (peer.state == PeerLinkState.RECONNECTING) {
                val q = outboxByPeer.getOrPut(nodeId) { Collections.synchronizedList(ArrayList()) }
                if (q.size < 50) q.add(pkt)
                queuedForReconnect++
            }
        }

        val initialDelivery = when {
            sentImmediate > 0 -> ChatDelivery.SENT
            queuedForReconnect > 0 -> ChatDelivery.QUEUED
            else -> ChatDelivery.SENT
        }

        chatHistory.add(
            ChatMessage(
                id = msgId,
                senderId = localNodeId,
                senderName = localDisplayName,
                text = text,
                timestampMs = now,
                outgoing = true,
                delivery = initialDelivery
            )
        )
        trimChatHistory()
        publishSnapshot()
        return true
    }

    private fun trimChatHistory() {
        synchronized(chatHistory) {
            while (chatHistory.size > 200) {
                chatHistory.removeAt(0)
            }
        }
    }

    // ================================================================ Optional GPS Sharing

    fun setLocationSharingEnabled(enabled: Boolean): Boolean {
        if (enabled && !RmgPermissions.hasLocationPermission(appCtx)) {
            sharingLocation.set(false)
            publishSnapshot()
            return false
        }
        val prev = sharingLocation.getAndSet(enabled)
        if (prev == enabled) return true

        if (enabled) {
            mainHandler.post {
                gpsEngine.start()
                gpsEngine.addListener(gpsListener)
            }
            lastLocationSentAtMs = 0L
        } else {
            mainHandler.post {
                gpsEngine.removeListener(gpsListener)
                if (!isMainActivityStarted) {
                    gpsEngine.stop()
                }
            }
            broadcastPacket(RmgWireProtocol.Packet.LocationStop(localNodeId))
        }
        syncForegroundService()
        publishSnapshot()
        return true
    }

    private fun onLocalGpsUpdate(state: GpsState) {
        if (!sharingLocation.get()) return
        val hasValidFix = state.location != null &&
            (state.gpsStatus == GpsStatus.GPS_CONNECTED || state.gpsStatus == GpsStatus.WEAK_ACCURACY)
        if (!hasValidFix) return

        val now = SystemClock.elapsedRealtime()
        val movedEnough = lastSentLat.isNaN() ||
            Math.abs(state.latitude - lastSentLat) > 0.000025 ||
            Math.abs(state.longitude - lastSentLon) > 0.000025
        if (now - lastLocationSentAtMs < 2_000L && !movedEnough) return

        lastLocationSentAtMs = now
        lastSentLat = state.latitude
        lastSentLon = state.longitude

        val pkt = RmgWireProtocol.Packet.LocationUpdate(
            senderId = localNodeId,
            senderName = localDisplayName,
            latitude = state.latitude,
            longitude = state.longitude,
            accuracyM = state.accuracy,
            speedMps = state.speed,
            timestampMs = System.currentTimeMillis()
        )
        broadcastPacket(pkt)
    }

    private fun sendCurrentGpsTo(channel: PeerChannel) {
        val s = gpsEngine.state
        if (s.location != null && (s.gpsStatus == GpsStatus.GPS_CONNECTED || s.gpsStatus == GpsStatus.WEAK_ACCURACY)) {
            channel.sendAsync(
                RmgWireProtocol.Packet.LocationUpdate(
                    senderId = localNodeId,
                    senderName = localDisplayName,
                    latitude = s.latitude,
                    longitude = s.longitude,
                    accuracyM = s.accuracy,
                    speedMps = s.speed,
                    timestampMs = System.currentTimeMillis()
                )
            )
        }
    }

    // ================================================================ Voice Controls

    fun setVoiceMode(mode: VoiceMode) {
        voiceEngine.setVoiceMode(mode)
        publishSnapshot()
    }

    fun setMicMuted(muted: Boolean): Boolean {
        if (!muted && !RmgPermissions.hasRecordAudioPermission(appCtx)) {
            voiceEngine.setMicMuted(true)
            publishSnapshot()
            return false
        }
        if (!muted) {
            voiceEverUnmutedInSession.set(true)
            voiceEngine.startSession()
        }
        voiceEngine.setMicMuted(muted)
        syncForegroundService()
        publishSnapshot()
        return true
    }

    fun toggleMicMuteFromNotification() {
        setMicMuted(!voiceEngine.isMicMuted())
    }

    fun setPttPressed(pressed: Boolean): Boolean {
        if (pressed) {
            if (!RmgPermissions.hasRecordAudioPermission(appCtx)) return false
            // If connected ONLY via unstable Bluetooth (high RTT or multiple BT peers), warn cleanly
            val wifiTargets = resolveActiveWifiVoiceTargets()
            if (wifiTargets.isEmpty()) {
                val btPeers = connectedMap.values.filter { it.state == PeerLinkState.CONNECTED && it.transport == TransportType.BLUETOOTH }
                if (btPeers.size > 1 || (btPeers.size == 1 && (btPeers[0].rttMs ?: 50) > 130)) {
                    uiEvents?.onToastInfo(com.nova.gpspro.R.string.rmg_connect_bt_voice_unstable)
                    return false
                }
            }
            voiceEverUnmutedInSession.set(true)
            voiceEngine.startSession()
        }
        val ok = voiceEngine.setPttPressed(pressed)
        syncForegroundService()
        publishSnapshot()
        return ok
    }

    fun setBluetoothAudioEnabled(enabled: Boolean): Boolean {
        val ok = voiceEngine.setBluetoothAudioEnabled(enabled)
        if (!ok && enabled) {
            uiEvents?.onToastInfo(com.nova.gpspro.R.string.rmg_connect_bt_audio_unavailable)
        }
        publishSnapshot()
        return ok
    }

    private fun resolveActiveWifiVoiceTargets(): List<InetAddress> {
        val out = ArrayList<InetAddress>()
        for (peer in connectedMap.values) {
            if (peer.state == PeerLinkState.CONNECTED && peer.transport == TransportType.WIFI_DIRECT && !peer.hostIp.isNullOrBlank()) {
                try {
                    out.add(InetAddress.getByName(peer.hostIp))
                } catch (_: Exception) {}
            }
        }
        return out
    }

    private fun sendCompactVoiceOverStableBluetooth(compactFrame: ByteArray): Boolean {
        val btPeers = connectedMap.values.filter {
            it.state == PeerLinkState.CONNECTED && it.transport == TransportType.BLUETOOTH
        }
        if (btPeers.size != 1) return false
        val single = btPeers[0]
        if ((single.rttMs ?: 60) > 130) return false
        val ch = activeConnections[single.nodeId] ?: return false
        ch.sendAsync(RmgWireProtocol.Packet.BtVoiceFrame(compactFrame))
        return true
    }

    // ================================================================ Disconnect & Teardown

    fun disconnectPeer(nodeId: String, sendBye: Boolean = true, sendBlock: Boolean = false) {
        acceptedSessionNodes.remove(nodeId)
        outboxByPeer.remove(nodeId)
        val ch = activeConnections.remove(nodeId)
        if (ch != null) {
            ioPool.execute {
                if (sendBlock) ch.sendBlocking(RmgWireProtocol.Packet.ConnBlock(localNodeId))
                else if (sendBye) ch.sendBlocking(RmgWireProtocol.Packet.Bye(localNodeId))
                ch.closeQuietly()
            }
        }
        connectedMap.remove(nodeId)
        syncSessionLifecycleAfterChange()
        publishSnapshot()
    }

    @SuppressLint("MissingPermission")
    fun disconnectAllAndEndSession() {
        stopDiscovery()
        hostingGroup.set(false)
        voiceEverUnmutedInSession.set(false)
        acceptedSessionNodes.clear()
        outboxByPeer.clear()

        // Turn off GPS sharing & stop GPS if Activity is already in the background
        if (sharingLocation.getAndSet(false)) {
            mainHandler.post {
                gpsEngine.removeListener(gpsListener)
                if (!isMainActivityStarted) gpsEngine.stop()
            }
        } else if (!isMainActivityStarted) {
            mainHandler.post { gpsEngine.stop() }
        }

        // Notify and close all active channels
        val channels = activeConnections.values.toList()
        activeConnections.clear()
        connectedMap.clear()
        ioPool.execute {
            for (ch in channels) {
                ch.sendBlocking(RmgWireProtocol.Packet.Bye(localNodeId))
                ch.closeQuietly()
            }
        }

        // Reject any pending invites cleanly
        val invites = pendingInvites.values.toList()
        pendingInvites.clear()
        ioPool.execute {
            for (inv in invites) inv.channel.close()
        }

        // Release Wi-Fi Direct group if formed
        try {
            val ch = p2pChannel
            if (ch != null) p2pManager?.removeGroup(ch, null)
        } catch (_: Exception) {}

        voiceEngine.stopSession()
        RmgConnectService.stop(appCtx)
        publishSnapshot()
    }

    private fun syncSessionLifecycleAfterChange() {
        val snap = buildSnapshot()
        if (!snap.hasActiveSession) {
            voiceEngine.stopSession()
            if (sharingLocation.getAndSet(false)) {
                mainHandler.post {
                    gpsEngine.removeListener(gpsListener)
                    if (!isMainActivityStarted) gpsEngine.stop()
                }
            }
            RmgConnectService.stop(appCtx)
        } else {
            syncForegroundService()
        }
    }

    private fun syncForegroundService() {
        val snap = buildSnapshot()
        if (snap.hasActiveSession) {
            RmgConnectService.startOrUpdate(appCtx)
        } else {
            RmgConnectService.stop(appCtx)
        }
    }

    private fun broadcastPacket(pkt: RmgWireProtocol.Packet) {
        for (ch in activeConnections.values) {
            ch.sendAsync(pkt)
        }
    }

    // ================================================================ Server Listeners & System Broadcasts

    private fun ensureTransportInfrastructure() {
        registerSystemReceivers()
        startTcpServerIfNeeded()
        startBluetoothServerIfNeeded()
    }

    private fun ensureP2pChannel(): WifiP2pManager.Channel? {
        if (p2pChannel != null) return p2pChannel
        val mgr = p2pManager ?: return null
        p2pChannel = try { mgr.initialize(appCtx, Looper.getMainLooper(), null) } catch (_: Exception) { null }
        return p2pChannel
    }

    private fun startTcpServerIfNeeded() {
        if (tcpServer != null && tcpServer?.isClosed == false) return
        ioPool.execute {
            try {
                val srv = ServerSocket()
                srv.reuseAddress = true
                srv.bind(InetSocketAddress(RmgDefaults.TCP_CONTROL_PORT))
                tcpServer = srv
                while (!srv.isClosed) {
                    val client = srv.accept()
                    client.tcpNoDelay = true
                    ioPool.execute { handleIncomingRawEndpoint(TcpEndpoint(client)) }
                }
            } catch (_: Exception) {}
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBluetoothServerIfNeeded() {
        if (!bluetoothSupported || !RmgPermissions.hasBluetoothConnectPermission(appCtx)) return
        if (btServer != null) return
        val adapter = btAdapter?.takeIf { it.isEnabled } ?: return
        ioPool.execute {
            try {
                val srv = adapter.listenUsingInsecureRfcommWithServiceRecord("RMG_CONNECT", RMG_BT_UUID)
                btServer = srv
                while (true) {
                    val sock = srv.accept() ?: continue
                    val addr = try { sock.remoteDevice?.address ?: "bt" } catch (_: Exception) { "bt" }
                    ioPool.execute { handleIncomingRawEndpoint(BtEndpoint(sock, addr)) }
                }
            } catch (_: Exception) {
                btServer = null
            }
        }
    }

    private fun handleIncomingRawEndpoint(endpoint: RawEndpoint) {
        try {
            val first = RmgWireProtocol.readPacket(endpoint.input)
            if (first !is RmgWireProtocol.Packet.ConnRequest) {
                endpoint.close()
                return
            }
            val remoteId = first.nodeId
            val remoteName = first.displayName.ifBlank { endpoint.remoteAddress }

            if (isBlocked(remoteId, endpoint.remoteAddress)) {
                try { RmgWireProtocol.writePacket(endpoint.output, RmgWireProtocol.Packet.ConnBlock(localNodeId)) } catch (_: Exception) {}
                endpoint.close()
                return
            }

            // Seamless auto-reconnect: if this exact nodeId was already accepted in the active session!
            if (acceptedSessionNodes.containsKey(remoteId)) {
                RmgWireProtocol.writePacket(
                    endpoint.output,
                    RmgWireProtocol.Packet.ConnAccept(localNodeId, localDisplayName, RmgDefaults.UDP_VOICE_PORT)
                )
                promoteAcceptedConnection(remoteId, remoteName, endpoint)
                return
            }

            val inviteId = UUID.randomUUID().toString()
            val inv = IncomingInvite(
                inviteId = inviteId,
                nodeId = remoteId,
                deviceName = remoteName,
                address = endpoint.remoteAddress,
                transport = endpoint.transport,
                receivedAtMs = System.currentTimeMillis()
            )
            pendingInvites[inviteId] = PendingInviteHolder(inv, endpoint, endpoint.output)
            syncForegroundService()
            publishSnapshot()

            // Auto-expire unanswered invite after 45 seconds so sockets never leak
            scheduler.schedule({
                val expired = pendingInvites.remove(inviteId)
                if (expired != null) {
                    expired.channel.close()
                    publishSnapshot()
                    syncSessionLifecycleAfterChange()
                }
            }, 45L, TimeUnit.SECONDS)
        } catch (_: Exception) {
            endpoint.close()
        }
    }

    private val systemReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    val ch = p2pChannel ?: return
                    if (RmgPermissions.hasWifiDirectPermission(appCtx)) {
                        p2pManager?.requestPeers(ch) { peerList ->
                            peerList?.deviceList?.forEach { dev -> upsertWifiDevice(dev, null) }
                            publishSnapshot()
                        }
                    }
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val ch = p2pChannel ?: return
                    @Suppress("DEPRECATION")
                    val netInfo = intent.getParcelableExtra<NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                    if (netInfo?.isConnected == true) {
                        p2pManager?.requestConnectionInfo(ch) { info: WifiP2pInfo? ->
                            if (info != null && info.groupFormed) {
                                hostingGroup.set(info.isGroupOwner)
                                if (!info.isGroupOwner && info.groupOwnerAddress != null && pendingWifiAutoRequest.getAndSet(false)) {
                                    val hostIp = info.groupOwnerAddress.hostAddress
                                    if (!hostIp.isNullOrBlank()) {
                                        connectViaTcp(hostIp, pendingWifiConnectTargetName ?: "Wi-Fi Direct Peer")
                                    }
                                }
                                publishSnapshot()
                            }
                        }
                    }
                }
                BluetoothDevice.ACTION_FOUND -> {
                    val dev: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    val rssiShort = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE)
                    val rssi = if (rssiShort != Short.MIN_VALUE) rssiShort.toInt() else null
                    if (dev != null) {
                        upsertBluetoothDevice(dev, rssi)
                        publishSnapshot()
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    publishSnapshot()
                }
            }
        }
    }

    private fun registerSystemReceivers() {
        if (receiversRegistered) return
        try {
            val f = IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            }
            appCtx.registerReceiver(systemReceiver, f, Context.RECEIVER_NOT_EXPORTED)
            receiversRegistered = true
        } catch (_: Exception) {}
    }

    private fun upsertWifiDevice(dev: WifiP2pDevice, serviceName: String?) {
        val addr = dev.deviceAddress ?: return
        if (isBlocked(null, addr)) return
        val key = "wifi:$addr"
        val cleanName = serviceName?.takeIf { it.isNotBlank() }
            ?: dev.deviceName?.takeIf { it.isNotBlank() }
            ?: addr
        val prev = discoveredMap[key]
        discoveredMap[key] = DiscoveredPeer(
            key = key,
            name = cleanName,
            address = addr,
            transport = TransportType.WIFI_DIRECT,
            rssiDbm = prev?.rssiDbm,
            isPending = prev?.isPending ?: false,
            isConnected = dev.status == WifiP2pDevice.CONNECTED || (prev?.isConnected == true),
            lastSeenMs = System.currentTimeMillis()
        )
    }

    @SuppressLint("MissingPermission")
    private fun upsertBluetoothDevice(dev: BluetoothDevice, rssi: Int?) {
        val addr = try { dev.address } catch (_: Exception) { null } ?: return
        if (isBlocked(null, addr)) return
        val name = try {
            if (RmgPermissions.hasBluetoothConnectPermission(appCtx)) dev.name else null
        } catch (_: SecurityException) { null }
        if (name.isNullOrBlank() && rssi == null) return
        val key = "bt:$addr"
        val prev = discoveredMap[key]
        discoveredMap[key] = DiscoveredPeer(
            key = key,
            name = name?.takeIf { it.isNotBlank() } ?: prev?.name ?: addr,
            address = addr,
            transport = TransportType.BLUETOOTH,
            rssiDbm = rssi ?: prev?.rssiDbm,
            isPending = prev?.isPending ?: false,
            isConnected = prev?.isConnected ?: false,
            lastSeenMs = System.currentTimeMillis()
        )
    }

    private fun lookupDiscoveredRssi(address: String): Int? =
        discoveredMap.values.firstOrNull { it.address == address }?.rssiDbm

    // ================================================================ Heartbeat & Signal Telemetry

    private fun runPeriodicMaintenance() {
        if (activeConnections.isEmpty()) return
        val seq = pingSeq.getAndIncrement()
        val nowElapsed = SystemClock.elapsedRealtime()
        val localWifiRssi = readCurrentWifiRssiDbm() ?: -999
        val ping = RmgWireProtocol.Packet.Ping(seq, nowElapsed, localWifiRssi)
        for (ch in activeConnections.values) {
            ch.sendAsync(ping)
        }
    }

    @Suppress("DEPRECATION")
    private fun readCurrentWifiRssiDbm(): Int? = try {
        val info = wifiManager?.connectionInfo
        val rssi = info?.rssi
        if (rssi != null && rssi in -115..-15) rssi else null
    } catch (_: Exception) { null }

    @Suppress("DEPRECATION")
    private fun readCurrentWifiLinkSpeedMbps(): Int? = try {
        val spd = wifiManager?.connectionInfo?.linkSpeed
        if (spd != null && spd > 0) spd else null
    } catch (_: Exception) { null }

    // ================================================================ Blocked Persistence & Snapshot

    private fun loadBlockedPeers() {
        val raw = prefs.getStringSet(KEY_BLOCKED_SET, emptySet()) ?: emptySet()
        for (entry in raw) {
            val parts = entry.split('\t')
            if (parts.isNotEmpty() && parts[0].isNotBlank()) {
                val key = parts[0]
                val name = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: key
                val ts = parts.getOrNull(2)?.toLongOrNull() ?: 0L
                blockedMap[key] = BlockedPeerEntry(key, name, ts)
            }
        }
    }

    private fun saveBlockedPeers() {
        val encoded = blockedMap.values.map { "${it.key}\t${it.name.replace('\t', ' ')}\t${it.blockedAtMs}" }.toSet()
        prefs.edit().putStringSet(KEY_BLOCKED_SET, encoded).apply()
    }

    private fun buildSnapshot(): RmgSessionSnapshot {
        val invites = pendingInvites.values.map { it.invite }.sortedByDescending { it.receivedAtMs }
        val connected = connectedMap.values.sortedWith(
            compareBy<ConnectedPeer> { it.state.ordinal }.thenBy { it.name.lowercase() }
        )
        val connectedAddrs = connected.filter { it.state != PeerLinkState.DISCONNECTED }.map { it.address }.toHashSet()
        val discovered = discoveredMap.values
            .filter { !isBlocked(it.key, it.address) && it.address !in connectedAddrs }
            .sortedWith(compareByDescending<DiscoveredPeer> { it.rssiDbm ?: -120 }.thenBy { it.name.lowercase() })

        val msgs = synchronized(chatHistory) { ArrayList(chatHistory) }
        val blocked = blockedMap.values
            .distinctBy { it.name + "|" + it.blockedAtMs }
            .sortedByDescending { it.blockedAtMs }

        return RmgSessionSnapshot(
            localNodeId = localNodeId,
            localDisplayName = localDisplayName,
            isDiscovering = discovering.get(),
            isGroupHost = hostingGroup.get(),
            wifiDirectSupported = wifiDirectSupported,
            bluetoothSupported = bluetoothSupported,
            voiceMode = voiceEngine.currentMode(),
            isMicMuted = voiceEngine.isMicMuted(),
            isPttPressed = voiceEngine.isPttPressed(),
            isBluetoothAudioActive = btAudioActive,
            isBluetoothAudioAvailable = btAudioAvailable || voiceEngine.isBluetoothAudioSupportedAndConnected(),
            isSharingLocation = sharingLocation.get(),
            discoveredPeers = discovered,
            incomingInvites = invites,
            connectedPeers = connected,
            messages = msgs,
            blockedPeers = blocked
        )
    }

    private fun publishSnapshot() {
        val next = buildSnapshot()
        snapshot = next
        mainHandler.post {
            for (l in listeners) l.onSnapshot(next)
        }
    }

    private fun defaultDeviceName(): String {
        val model = Build.MODEL?.trim().orEmpty()
        return if (model.isNotEmpty()) model.take(28) else "RMG Device"
    }

    companion object {
        private const val PREFS_FILE = "rmg_connect_prefs"
        private const val KEY_NODE_ID = "node_id"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_BLOCKED_SET = "blocked_peers"
        private const val DISCOVERY_WINDOW_MS = 25_000L
        val RMG_BT_UUID: UUID = UUID.fromString("8e400001-b5a3-f393-e0a9-e50e24dcca9e")
    }
}
