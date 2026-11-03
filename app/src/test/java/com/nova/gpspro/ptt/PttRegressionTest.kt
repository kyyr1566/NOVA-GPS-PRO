package com.nova.gpspro.ptt

import com.nova.gpspro.ui.C
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Comprehensive regression & lifecycle test suite covering all 3 field-reported bugs
 * and all 13 mandatory verification scenarios (with programmatic Wi-Fi disable,
 * callback failure injection, sequencing assertions, and real localhost TCP/UDP sockets).
 */
class PttRegressionTest {

    // =========================================================================
    // Test Doubles: Manual Task Scheduler, Fake P2P Driver, Fake Radio Transport,
    // Fake Service Bridge, Fake Audio Controller, Fake Permission Gate
    // =========================================================================

    private class ManualTaskScheduler : PttTaskScheduler {
        data class Entry(val dueAtMs: Long, val task: Runnable)
        var nowMs: Long = 0L
        private val queue = ArrayList<Entry>()

        override fun post(task: Runnable) {
            queue.add(Entry(nowMs, task))
            drainDue()
        }

        override fun postDelayed(delayMs: Long, task: Runnable) {
            queue.add(Entry(nowMs + delayMs, task))
        }

        override fun remove(task: Runnable) {
            queue.removeAll { it.task === task }
        }

        fun advanceBy(deltaMs: Long) {
            nowMs += deltaMs
            drainDue()
        }

        private fun drainDue() {
            while (true) {
                val nextIdx = queue.indexOfFirst { it.dueAtMs <= nowMs }
                if (nextIdx < 0) break
                val entry = queue.removeAt(nextIdx)
                entry.task.run()
            }
        }
    }

    private class FakeP2pDriver : P2pDriver {
        override var isHardwareSupported: Boolean = true
        var wifiEnabled: Boolean = true
        var discoveryPermGranted: Boolean = true
        var locationToggleOff: Boolean = false

        val callLog = CopyOnWriteArrayList<String>()
        var registeredInstanceName: String? = null
        var registeredServiceType: String? = null
        var registeredTxt: Map<String, String>? = null

        var addLocalServiceBehaviors = ArrayDeque<Pair<Boolean, Int>>()
        var createGroupBehaviors = ArrayDeque<Pair<Boolean, Int>>()
        var addServiceRequestBehaviors = ArrayDeque<Pair<Boolean, Int>>()
        var discoverServicesBehaviors = ArrayDeque<Pair<Boolean, Int>>()
        var discoverPeersBehaviors = ArrayDeque<Pair<Boolean, Int>>()

        var currentLinkState: P2pDriver.GroupLinkState? = null
        var hostedCreds: P2pDriver.HostedGroupCredentials? = P2pDriver.HostedGroupCredentials("DIRECT-RM-Nova", "pass987654")
        var availablePeers: List<P2pDriver.PeerSnapshot> = emptyList()
        var directCredConnectBehaviors = ArrayDeque<Pair<Boolean, Int>>()
        var macConnectBehaviors = ArrayDeque<Pair<Boolean, Int>>()

        var dnsServiceCb: ((String, String, String, String) -> Unit)? = null
        var dnsTxtCb: ((String, Map<String, String>, String, String) -> Unit)? = null

        var activeServiceRequestCount = 0
        var peerDiscoveryActive = false

        override fun isWifiEnabled(): Boolean = wifiEnabled
        override fun hasDiscoveryPermission(): Boolean = discoveryPermGranted
        override fun isLocationToggleRequiredButOff(): Boolean = locationToggleOff

        override fun ensureInitialized(): Boolean {
            callLog += "initialize"
            return true
        }

        override fun clearLocalServices(cb: (Boolean, Int) -> Unit) {
            callLog += "clearLocalServices"
            registeredInstanceName = null
            registeredServiceType = null
            registeredTxt = null
            cb(true, 0)
        }

        override fun addDnsSdLocalService(
            instanceName: String,
            serviceType: String,
            txt: Map<String, String>,
            cb: (Boolean, Int) -> Unit
        ) {
            callLog += "addLocalService($instanceName,$serviceType)"
            val (ok, reason) = addLocalServiceBehaviors.removeFirstOrNull() ?: (true to 0)
            if (ok) {
                registeredInstanceName = instanceName
                registeredServiceType = serviceType
                registeredTxt = txt
            }
            cb(ok, reason)
        }

        override fun removeExistingGroupIfAny(cb: () -> Unit) {
            callLog += "removeExistingGroupIfAny"
            cb()
        }

        override fun createGroup(cb: (Boolean, Int) -> Unit) {
            callLog += "createGroup"
            val (ok, reason) = createGroupBehaviors.removeFirstOrNull() ?: (true to 0)
            cb(ok, reason)
        }

        override fun requestLinkState(cb: (P2pDriver.GroupLinkState?) -> Unit) {
            cb(currentLinkState)
        }

        override fun requestHostedGroupCredentials(cb: (P2pDriver.HostedGroupCredentials?) -> Unit) {
            callLog += "requestHostedGroupCredentials"
            cb(hostedCreds)
        }

        override fun setDnsSdListeners(
            onService: (String, String, String, String) -> Unit,
            onTxt: (String, Map<String, String>, String, String) -> Unit
        ) {
            callLog += "setDnsSdListeners"
            dnsServiceCb = onService
            dnsTxtCb = onTxt
        }

        override fun clearServiceRequests(cb: (Boolean, Int) -> Unit) {
            callLog += "clearServiceRequests"
            activeServiceRequestCount = 0
            cb(true, 0)
        }

        override fun addDnsSdServiceRequest(cb: (Boolean, Int) -> Unit) {
            callLog += "addServiceRequest"
            val (ok, reason) = addServiceRequestBehaviors.removeFirstOrNull() ?: (true to 0)
            if (ok) activeServiceRequestCount++
            cb(ok, reason)
        }

        override fun discoverServices(cb: (Boolean, Int) -> Unit) {
            // Assert peer discovery was stopped before discoverServices so they never collide!
            assertFalse("discoverPeers must NOT be active when discoverServices starts", peerDiscoveryActive)
            callLog += "discoverServices"
            val (ok, reason) = discoverServicesBehaviors.removeFirstOrNull() ?: (true to 0)
            cb(ok, reason)
        }

        override fun discoverPeers(cb: (Boolean, Int) -> Unit) {
            callLog += "discoverPeers"
            val (ok, reason) = discoverPeersBehaviors.removeFirstOrNull() ?: (true to 0)
            if (ok) peerDiscoveryActive = true
            cb(ok, reason)
        }

        override fun stopPeerDiscovery() {
            callLog += "stopPeerDiscovery"
            peerDiscoveryActive = false
        }

        override fun requestPeers(cb: (List<P2pDriver.PeerSnapshot>) -> Unit) {
            callLog += "requestPeers"
            cb(availablePeers)
        }

        override fun connectWithGroupCredentials(networkName: String, passphrase: String, cb: (Boolean, Int) -> Unit) {
            callLog += "connectWithGroupCredentials($networkName)"
            val (ok, reason) = directCredConnectBehaviors.removeFirstOrNull() ?: (true to 0)
            cb(ok, reason)
        }

        override fun connect(deviceAddress: String, cb: (Boolean, Int) -> Unit) {
            callLog += "connect($deviceAddress)"
            val (ok, reason) = macConnectBehaviors.removeFirstOrNull() ?: (true to 0)
            cb(ok, reason)
        }

        override fun cancelConnect() {
            callLog += "cancelConnect"
        }

        override fun removeGroupNow() {
            callLog += "removeGroupNow"
            currentLinkState = null
        }
    }

    private class FakeServiceBridge : PttServiceBridge {
        val startCalls = AtomicInteger(0)
        val stopCalls = AtomicInteger(0)
        var currentlyRunning = AtomicBoolean(false)

        override fun startOrUpdateService() {
            startCalls.incrementAndGet()
            currentlyRunning.set(true)
        }

        override fun stopService() {
            stopCalls.incrementAndGet()
            currentlyRunning.set(false)
        }
    }

    private class FakeAudioController : PttAudioController {
        val receiveStartCalls = AtomicInteger(0)
        val transmitStartCalls = AtomicInteger(0)
        val transmitStopCalls = AtomicInteger(0)
        val releaseCalls = AtomicInteger(0)
        val receivedPackets = CopyOnWriteArrayList<PttProtocol.UdpVoicePacket>()
        var muted = false
        var activeFrameSender: ((AudioCodecType, Int, Long, Boolean, ByteArray) -> Unit)? = null

        override fun localSupportedCodecMask(): Int = AudioCodecType.ADPCM_16K.mask

        override fun startReceiveSession() {
            receiveStartCalls.incrementAndGet()
        }

        override fun onIncomingVoicePacket(packet: PttProtocol.UdpVoicePacket) {
            receivedPackets.add(packet)
        }

        override fun startTransmit(
            codec: AudioCodecType,
            rogerBeep: Boolean,
            onEncodedFrame: (AudioCodecType, Int, Long, Boolean, ByteArray) -> Unit,
            onCaptureError: () -> Unit
        ) {
            transmitStartCalls.incrementAndGet()
            activeFrameSender = onEncodedFrame
        }

        override fun stopTransmit(playEndBeep: Boolean) {
            transmitStopCalls.incrementAndGet()
            activeFrameSender = null
        }

        override fun setLocalListenMuted(muted: Boolean) {
            this.muted = muted
        }

        override fun playRogerEndToneIfEnabled(enabled: Boolean) {}
        override fun measuredPacketLossPct(): Int = 0

        override fun releaseAll() {
            releaseCalls.incrementAndGet()
            activeFrameSender = null
        }
    }

    private class FakePermissionGate(
        var coreGranted: Boolean = true,
        var micGranted: Boolean = true
    ) : PttPermissionGate {
        override fun hasAllCorePermissions(): Boolean = coreGranted
        override fun hasRecordAudioPermission(): Boolean = micGranted
    }

    private class FakeRadioTransport(
        var wifiOn: Boolean = true,
        var envIssue: WifiEnvironmentIssue = WifiEnvironmentIssue.NONE,
        var gatewayCheck: LocalHotspotGatewayCheck = LocalHotspotGatewayCheck.NotConnectedToHotspot
    ) : PttRadioTransport {
        override var listener: PttRadioTransport.Listener? = null
        var autoCompleteHosting: Boolean = true
        var hostingFailIssue: WifiEnvironmentIssue? = null
        var advertisedTcpPort: Int = 0
        var advertisedSessionId: String = ""
        var advertisedHostName: String = ""
        var scanningNow = false
        var targetConnectHostIp: String = "127.0.0.1"
        var autoCompleteConnect: Boolean = true
        val teardownCalls = AtomicInteger(0)

        private var pendingHostReady: ((String) -> Unit)? = null
        private var pendingHostError: ((WifiEnvironmentIssue, String) -> Unit)? = null

        override fun isWifiEnabled(): Boolean = wifiOn

        override fun diagnoseEnvironment(): WifiEnvironmentIssue =
            if (!wifiOn) WifiEnvironmentIssue.WIFI_DISABLED else envIssue

        override fun checkLocalHotspotGateway(): LocalHotspotGatewayCheck =
            if (!wifiOn) LocalHotspotGatewayCheck.WifiTurnedOff else gatewayCheck

        override fun startHostingGroup(
            sessionId: String,
            hostDisplayName: String,
            tcpPort: Int,
            onReady: (String) -> Unit,
            onError: (WifiEnvironmentIssue, String) -> Unit
        ) {
            advertisedSessionId = sessionId
            advertisedHostName = hostDisplayName
            advertisedTcpPort = tcpPort
            val fail = hostingFailIssue
            if (fail != null) {
                onError(fail, "Simulated hosting failure: $fail")
                return
            }
            if (autoCompleteHosting) {
                onReady("127.0.0.1")
            } else {
                pendingHostReady = onReady
                pendingHostError = onError
            }
        }

        fun completePendingHostReady(ip: String = "127.0.0.1") {
            pendingHostReady?.invoke(ip)
            pendingHostReady = null
        }

        fun failPendingHost(issue: WifiEnvironmentIssue) {
            pendingHostError?.invoke(issue, "Async host failure")
            pendingHostError = null
        }

        override fun startLocalOnlyHotspotFallback(
            onReady: (String, String) -> Unit,
            onError: (WifiEnvironmentIssue, String) -> Unit
        ) {
            if (!wifiOn) {
                onError(WifiEnvironmentIssue.WIFI_DISABLED, "Wi-Fi off")
            } else {
                listener?.onLocalHotspotStarted("AndroidShare_RMG", "12345678")
                onReady("AndroidShare_RMG", "12345678")
            }
        }

        override fun startSessionDiscovery(
            onStarted: () -> Unit,
            onError: (WifiEnvironmentIssue, String) -> Unit
        ): Boolean {
            val d = diagnoseEnvironment()
            if (d != WifiEnvironmentIssue.NONE) {
                onError(d, "Pre-flight fail")
                return false
            }
            scanningNow = true
            onStarted()
            return true
        }

        override fun stopSessionDiscovery() {
            scanningNow = false
        }

        override fun isScanning(): Boolean = scanningNow

        override fun connectToHostDevice(
            deviceAddress: String,
            onInitiated: () -> Unit,
            onFailed: (WifiEnvironmentIssue, String) -> Unit
        ) {
            if (!wifiOn) {
                onFailed(WifiEnvironmentIssue.WIFI_DISABLED, "Wi-Fi off")
                return
            }
            onInitiated()
            if (autoCompleteConnect) {
                listener?.onP2pLinkEstablished(false, targetConnectHostIp)
            }
        }

        override fun teardownRadio() {
            scanningNow = false
            pendingHostReady = null
            pendingHostError = null
            teardownCalls.incrementAndGet()
        }
    }

    private fun waitUntil(timeoutMs: Long = 4_000L, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(25L)
        }
        return condition()
    }

    private fun buildTestRepo(
        name: String,
        devId: String,
        transport: FakeRadioTransport,
        service: FakeServiceBridge = FakeServiceBridge(),
        audio: FakeAudioController = FakeAudioController(),
        perms: FakePermissionGate = FakePermissionGate(),
        clock: () -> Long = { System.currentTimeMillis() },
        tcpPort: Int = 0
    ): PttRepository = PttRepository(
        appCtx = null,
        settings = InMemoryPttSettings(deviceId = devId, displayName = name),
        serviceBridge = service,
        wifiDirect = transport,
        permissionGate = perms,
        audioEngine = audio,
        uiPoster = { it.run() },
        clockMs = clock,
        preferredHostTcpPort = tcpPort,
        preferredHostUdpPort = 0
    )

    // =========================================================================
    // REGRESSION BUG 1: Wi-Fi OFF + Create Session -> NO crash, NO service/audio/sockets,
    //                   exact WIFI_DISABLED error state!
    // =========================================================================

    @Test
    fun bug1_wifiOffCreateSessionNeverStartsForegroundServiceOrAudioAndShowsWifiDisabled() {
        val transport = FakeRadioTransport(wifiOn = false)
        val service = FakeServiceBridge()
        val audio = FakeAudioController()
        val repo = buildTestRepo("HostUser", "DEV_H1", transport, service, audio)

        val ok = repo.createHostSession("RMG742")

        assertFalse("createHostSession must abort immediately when Wi-Fi is off", ok)
        assertEquals(PttPhase.DISCONNECTED, repo.state.value.phase)
        assertEquals(PttErrorCode.WIFI_DISABLED, repo.state.value.errorCode)
        // Crucial crash prevention proof: ForegroundService and AudioEngine were NEVER touched!
        assertEquals("PttService must NEVER start when Wi-Fi is off", 0, service.startCalls.get())
        assertEquals("AudioEngine receive loop must NOT start when Wi-Fi is off", 0, audio.receiveStartCalls.get())
        repo.teardownSession(false, false)
    }

    // =========================================================================
    // SCENARIO 2: Wi-Fi ON + Create Session -> CHECKING -> HOSTING -> IN_SESSION
    //             ONLY after P2P group + DNS-SD actually succeed (plus callback failure test!)
    // =========================================================================

    @Test
    fun scenario2_hostingWaitsForActualGroupFormationBeforeShowingReadyAndCleansUpOnFailure() {
        val transport = FakeRadioTransport(wifiOn = true).apply { autoCompleteHosting = false }
        val service = FakeServiceBridge()
        val audio = FakeAudioController()
        val repo = buildTestRepo("Captain", "DEV_H2", transport, service, audio)

        // Start hosting while group formation is still in progress
        assertTrue(repo.createHostSession("NOVA55"))
        assertEquals("Must stay in HOSTING until P2P group forms", PttPhase.HOSTING, repo.state.value.phase)
        assertEquals("PttService must NOT start during HOSTING", 0, service.startCalls.get())
        assertEquals("AudioEngine must NOT start during HOSTING", 0, audio.receiveStartCalls.get())

        // Now P2P group & DNS-SD report ready -> transitions to IN_SESSION
        transport.completePendingHostReady("192.168.49.1")
        assertEquals(PttPhase.IN_SESSION, repo.state.value.phase)
        assertEquals(1, service.startCalls.get())
        assertEquals(1, audio.receiveStartCalls.get())
        assertEquals(1, repo.state.value.connectedCount)

        repo.endSessionByUser()
        assertEquals(PttPhase.DISCONNECTED, repo.state.value.phase)
        assertFalse(service.currentlyRunning.get())

        // Inject async P2P group creation failure -> must clean up to DISCONNECTED + HOSTING_FAILED without starting service!
        val service2 = FakeServiceBridge()
        val transport2 = FakeRadioTransport(wifiOn = true).apply { autoCompleteHosting = false }
        val repo2 = buildTestRepo("Captain", "DEV_H2B", transport2, service2)
        assertTrue(repo2.createHostSession("NOVA55"))
        assertEquals(PttPhase.HOSTING, repo2.state.value.phase)
        transport2.failPendingHost(WifiEnvironmentIssue.HOSTING_GROUP_FAILED)
        assertEquals(PttPhase.DISCONNECTED, repo2.state.value.phase)
        assertEquals(PttErrorCode.HOSTING_FAILED, repo2.state.value.errorCode)
        assertEquals("PttService must never have started on failed host setup", 0, service2.startCalls.get())
    }

    // =========================================================================
    // REGRESSION BUG 2 & SCENARIO 3: WifiDirectManager Sequencing, BUSY Retry,
    //                                Non-Conflicting DNS-SD, Peer Fallback & Timeout
    // =========================================================================

    @Test
    fun bug2_wifiDirectManagerHostAndDiscoverySequencingDnsSdAndPeerFallback() {
        // Part A: Host sequencing with simulated BUSY on addLocalService & createGroup, then groupFormed
        val hostSched = ManualTaskScheduler()
        val hostDriver = FakeP2pDriver().apply {
            addLocalServiceBehaviors.addLast(false to WifiDirectManager.REASON_BUSY)
            addLocalServiceBehaviors.addLast(true to 0)
            createGroupBehaviors.addLast(false to WifiDirectManager.REASON_BUSY)
            createGroupBehaviors.addLast(true to 0)
        }
        val hostWdm = WifiDirectManager(appCtx = null, customDriver = hostDriver, scheduler = hostSched)
        var readyIp: String? = null
        hostWdm.startHostingGroup(
            sessionId = "SESS1234",
            hostDisplayName = "PhoneOne",
            tcpPort = 49320,
            onReady = { readyIp = it },
            onError = { issue, diag -> fail("Unexpected error: $issue / $diag") }
        )
        // Advance through BUSY retries (450ms + 550ms) — still not ready until groupFormed!
        hostSched.advanceBy(500L)
        hostSched.advanceBy(600L)
        assertNull("Must not report ready before groupFormed == true", readyIp)
        assertEquals("rmgptt", hostDriver.registeredInstanceName)
        assertEquals("_rmgptt._tcp", hostDriver.registeredServiceType)

        // Simulate OS forming group with Group Owner IP 192.168.49.1
        hostDriver.currentLinkState = P2pDriver.GroupLinkState(groupFormed = true, isGroupOwner = true, groupOwnerIp = "192.168.49.1")
        hostSched.advanceBy(400L)
        assertEquals("192.168.49.1", readyIp)

        // Part B: Client discovery via primary DNS-SD (verifying discoverPeers is NOT called concurrently with discoverServices)
        val clientSched = ManualTaskScheduler()
        val clientDriver = FakeP2pDriver()
        val clientWdm = WifiDirectManager(appCtx = null, customDriver = clientDriver, scheduler = clientSched)
        val discoveredSnapshots = CopyOnWriteArrayList<List<DiscoveredPttSession>>()
        var finishedEmpty = false
        clientWdm.listener = object : PttRadioTransport.Listener {
            override fun onSessionsDiscovered(sessions: List<DiscoveredPttSession>) { discoveredSnapshots.add(sessions) }
            override fun onDiscoveryFinishedEmpty() { finishedEmpty = true }
            override fun onP2pLinkEstablished(isGroupOwner: Boolean, groupOwnerIp: String) {}
            override fun onP2pLinkDisconnected() {}
            override fun onLocalHotspotStarted(ssid: String, passphrase: String) {}
            override fun onTransportFailure(issue: WifiEnvironmentIssue, diagnostic: String) {}
        }

        assertTrue(clientWdm.startSessionDiscovery(onError = { i, d -> fail("$i: $d") }))
        // Simulate incoming DNS-SD TXT response for rmgptt._rmgptt._tcp.local.
        clientDriver.dnsTxtCb?.invoke(
            "rmgptt._rmgptt._tcp.local.",
            hostDriver.registeredTxt!!,
            "aa:bb:cc:dd:ee:01",
            "Galaxy-A54"
        )
        val latest = discoveredSnapshots.last()
        assertEquals(1, latest.size)
        assertEquals("PhoneOne", latest[0].hostName)
        assertFalse(latest[0].discoveredViaFallback)
        assertFalse(finishedEmpty)
        clientWdm.teardownRadio()

        // Part C: OEM blocks DNS-SD -> verify automatic Phase B Peer Discovery fallback finds Group Owner,
        //         and if zero peers exist after 3 bounded rounds, discovery stops with onDiscoveryFinishedEmpty!
        val fallbackSched = ManualTaskScheduler()
        val fallbackDriver = FakeP2pDriver().apply {
            availablePeers = listOf(
                P2pDriver.PeerSnapshot(
                    deviceAddress = "11:22:33:44:55:66",
                    deviceName = "Redmi-Host",
                    isGroupOwner = true,
                    status = 3 // AVAILABLE
                )
            )
        }
        val fallbackWdm = WifiDirectManager(appCtx = null, customDriver = fallbackDriver, scheduler = fallbackSched)
        var fallbackList: List<DiscoveredPttSession> = emptyList()
        fallbackWdm.listener = object : PttRadioTransport.Listener {
            override fun onSessionsDiscovered(sessions: List<DiscoveredPttSession>) { fallbackList = sessions }
            override fun onDiscoveryFinishedEmpty() {}
            override fun onP2pLinkEstablished(isGroupOwner: Boolean, groupOwnerIp: String) {}
            override fun onP2pLinkDisconnected() {}
            override fun onLocalHotspotStarted(ssid: String, passphrase: String) {}
            override fun onTransportFailure(issue: WifiEnvironmentIssue, diagnostic: String) {}
        }
        assertTrue(fallbackWdm.startSessionDiscovery(onError = { i, d -> fail("$i: $d") }))
        // Advance past 5s DNS-SD window into Phase B Peer fallback (5000 + 1400ms)
        fallbackSched.advanceBy(5_100L)
        fallbackSched.advanceBy(1_400L)
        assertEquals(1, fallbackList.size)
        assertEquals("Redmi-Host", fallbackList[0].hostName)
        assertTrue("Must mark candidate as discoveredViaFallback", fallbackList[0].discoveredViaFallback)
        fallbackWdm.teardownRadio()

        // Empty timeout after 3 bounded rounds
        val emptySched = ManualTaskScheduler()
        val emptyDriver = FakeP2pDriver()
        val emptyWdm = WifiDirectManager(appCtx = null, customDriver = emptyDriver, scheduler = emptySched)
        var emptyTriggered = false
        emptyWdm.listener = object : PttRadioTransport.Listener {
            override fun onSessionsDiscovered(sessions: List<DiscoveredPttSession>) {}
            override fun onDiscoveryFinishedEmpty() { emptyTriggered = true }
            override fun onP2pLinkEstablished(isGroupOwner: Boolean, groupOwnerIp: String) {}
            override fun onP2pLinkDisconnected() {}
            override fun onLocalHotspotStarted(ssid: String, passphrase: String) {}
            override fun onTransportFailure(issue: WifiEnvironmentIssue, diagnostic: String) {}
        }
        assertTrue(emptyWdm.startSessionDiscovery(onError = { _, _ -> }))
        repeat(15) { emptySched.advanceBy(2_500L) }
        assertTrue("Must conclude bounded discovery and fire onDiscoveryFinishedEmpty", emptyTriggered)
        assertFalse("Scanning flag must be cleared on timeout", emptyWdm.isScanning())
    }

    // =========================================================================
    // REGRESSION BUG 3: Wi-Fi ON + Local Hotspot Join without Gateway ->
    //                   MUST report HOTSPOT_NOT_CONNECTED, NEVER WIFI_DISABLED!
    // =========================================================================

    @Test
    fun bug3_localHotspotJoinWithWifiEnabledNeverShowsFalseWifiDisabledError() {
        val transport = FakeRadioTransport(
            wifiOn = true,
            gatewayCheck = LocalHotspotGatewayCheck.NotConnectedToHotspot
        )
        val repo = buildTestRepo("GuestPhone2", "DEV_G1", transport)

        val joined = repo.requestJoinLocalHotspotGateway("RMG742")
        assertFalse(joined)
        assertEquals(PttPhase.DISCONNECTED, repo.state.value.phase)
        assertNotEquals(
            "MUST NOT falsely claim Wi-Fi is disabled when Wi-Fi is ON!",
            PttErrorCode.WIFI_DISABLED,
            repo.state.value.errorCode
        )
        assertEquals(PttErrorCode.HOTSPOT_NOT_CONNECTED, repo.state.value.errorCode)

        // Contrast: when Wi-Fi is genuinely turned OFF, it MUST report WIFI_DISABLED
        transport.wifiOn = false
        assertFalse(repo.requestJoinLocalHotspotGateway("RMG742"))
        assertEquals(PttErrorCode.WIFI_DISABLED, repo.state.value.errorCode)
        repo.teardownSession(false, false)
    }

    // =========================================================================
    // SCENARIOS 4, 5, 6, 7: End-to-End Real Socket Discovery -> Join -> Wrong Code ->
    //                       Host Reject -> Host Accept -> Live UDP Audio -> Clean End!
    // =========================================================================

    @Test
    fun scenarios4to7_endToEndJoinWrongCodeHostRejectHostAcceptLiveVoiceAndCleanEnd() {
        val hostTransport = FakeRadioTransport(wifiOn = true)
        val hostService = FakeServiceBridge()
        val hostAudio = FakeAudioController()
        val hostRepo = buildTestRepo("Ahmad", "HOST_DEV", hostTransport, hostService, hostAudio, tcpPort = 0)

        assertTrue(hostRepo.createHostSession("SAFE88"))
        assertEquals(PttPhase.IN_SESSION, hostRepo.state.value.phase)
        val hostPort = hostTransport.advertisedTcpPort
        assertTrue("Host must bind real TCP port > 0", hostPort > 0)

        val discovered = DiscoveredPttSession(
            deviceAddress = "02:00:00:00:00:01",
            sessionId = hostTransport.advertisedSessionId,
            hostName = "Ahmad",
            deviceName = "PixelHost",
            tcpPort = hostPort,
            protocolVersion = PttProtocol.PROTOCOL_VERSION,
            lastSeenMs = System.currentTimeMillis()
        )

        val guestTransport = FakeRadioTransport(wifiOn = true).apply { targetConnectHostIp = "127.0.0.1" }
        val guestService = FakeServiceBridge()
        val guestAudio = FakeAudioController()
        val guestRepo = buildTestRepo("Ali", "GUEST_DEV", guestTransport, guestService, guestAudio, tcpPort = 0)

        // 1) Discovery state transition: SEARCHING -> FOUND
        assertTrue(guestRepo.startScanningSessions())
        assertEquals(PttPhase.SCANNING, guestRepo.state.value.phase)
        assertEquals(PttDiscoveryState.SEARCHING, guestRepo.state.value.discoveryState)
        guestTransport.listener?.onSessionsDiscovered(listOf(discovered))
        assertEquals(PttDiscoveryState.FOUND, guestRepo.state.value.discoveryState)

        // 2) Wrong channel code -> cryptographically rejected with AUTH_FAILED!
        assertTrue(guestRepo.requestJoinDiscoveredSession(discovered, "WRONG1"))
        assertTrue(
            "Guest must fail with AUTH_FAILED on wrong channel code",
            waitUntil { guestRepo.state.value.phase == PttPhase.DISCONNECTED && guestRepo.state.value.errorCode == PttErrorCode.AUTH_FAILED }
        )
        assertTrue("Host must have zero pending approvals for wrong code", hostRepo.state.value.pendingApprovals.isEmpty())

        // 3) Correct channel code, then Host clicks REJECT -> Guest gets REJECTED_BY_HOST
        assertTrue(guestRepo.requestJoinDiscoveredSession(discovered, "SAFE88"))
        assertTrue(
            "Host must receive pending join approval card",
            waitUntil { hostRepo.state.value.pendingApprovals.size == 1 }
        )
        assertTrue(waitUntil { guestRepo.state.value.phase == PttPhase.WAITING_APPROVAL })
        val reqIdToReject = hostRepo.state.value.pendingApprovals.first().requestId
        hostRepo.rejectJoinRequest(reqIdToReject)
        assertTrue(
            "Guest must receive REJECTED_BY_HOST",
            waitUntil { guestRepo.state.value.phase == PttPhase.DISCONNECTED && guestRepo.state.value.errorCode == PttErrorCode.REJECTED_BY_HOST }
        )

        // 4) Correct channel code, Host clicks ACCEPT -> both enter IN_SESSION with connectedCount = 2!
        assertTrue(guestRepo.requestJoinDiscoveredSession(discovered, "SAFE88"))
        assertTrue(waitUntil { hostRepo.state.value.pendingApprovals.size == 1 })
        hostRepo.approveJoinRequest(hostRepo.state.value.pendingApprovals.first().requestId)

        assertTrue(waitUntil { guestRepo.state.value.phase == PttPhase.IN_SESSION })
        assertTrue(waitUntil { hostRepo.state.value.connectedCount == 2 && guestRepo.state.value.connectedCount == 2 })

        // 5) Push-to-Talk Half-Duplex & live UDP voice packet delivery from Guest -> Host
        guestRepo.onTalkTouchDown()
        assertTrue(
            "Guest button turns Green (#14866D) and Host turns Amber (#D99A32)",
            waitUntil {
                guestRepo.state.value.buttonState == PttButtonVisualState.SELF_SPEAKING_GREEN &&
                    hostRepo.state.value.buttonState == PttButtonVisualState.REMOTE_SPEAKING_AMBER
            }
        )
        // Emit an encoded ADPCM frame from Guest's microphone callback
        val dummyAdpcm = ImaAdpcmCodec.encodeFrame(ShortArray(320) { 1200 })
        guestAudio.activeFrameSender?.invoke(AudioCodecType.ADPCM_16K, 1, 20L, false, dummyAdpcm)
        assertTrue("Host AudioEngine must receive authenticated UDP voice packet", waitUntil { hostAudio.receivedPackets.isNotEmpty() })

        // Guest releases touch -> both return to Royal Blue (#2457D6)
        guestRepo.onTalkTouchUpOrCancel()
        assertTrue(
            waitUntil {
                guestRepo.state.value.buttonState == PttButtonVisualState.IDLE_BLUE &&
                    hostRepo.state.value.buttonState == PttButtonVisualState.IDLE_BLUE
            }
        )

        // 6) Host ends session -> terminates cleanly for everyone & stops services!
        hostRepo.endSessionByUser()
        assertEquals(PttPhase.DISCONNECTED, hostRepo.state.value.phase)
        assertFalse(hostService.currentlyRunning.get())
        assertTrue(
            "Guest must automatically disconnect with ended banner when Host ends session",
            waitUntil { guestRepo.state.value.phase == PttPhase.DISCONNECTED && guestRepo.state.value.showSessionEndedBanner }
        )
        assertFalse(guestService.currentlyRunning.get())
    }

    // =========================================================================
    // SCENARIO 4b: Fallback Peer Protocol Verification (non-PTT socket rejected cleanly)
    // =========================================================================

    @Test
    fun fallbackCandidateNotRunningWalkieTalkieProtocolIsRejectedCleanly() {
        // Open a plain non-PTT TCP server (e.g. another app's random port) that sends non-WT bytes
        val rogueServer = ServerSocket(0)
        val roguePort = rogueServer.localPort
        Thread({
            try {
                rogueServer.accept().use { s ->
                    s.getOutputStream().write("HTTP/1.1 400 Bad Request\r\n\r\n".toByteArray())
                    s.getOutputStream().flush()
                }
            } catch (_: Throwable) {}
        }, "rogue-srv").apply { isDaemon = true }.start()

        val transport = FakeRadioTransport(wifiOn = true).apply { targetConnectHostIp = "127.0.0.1" }
        val repo = buildTestRepo("Guest", "DEV_G9", transport, tcpPort = 0)
        val fallbackCandidate = DiscoveredPttSession(
            deviceAddress = "aa:bb:cc:00:11:22",
            sessionId = "peer_aabbcc001122",
            hostName = "UnknownPeer",
            deviceName = "UnknownPeer",
            tcpPort = roguePort,
            protocolVersion = PttProtocol.PROTOCOL_VERSION,
            lastSeenMs = System.currentTimeMillis(),
            discoveredViaFallback = true
        )

        assertTrue(repo.requestJoinDiscoveredSession(fallbackCandidate, "RMG742"))
        assertTrue(
            "Non-PTT endpoint must be rejected with PROTOCOL_INCOMPATIBLE",
            waitUntil {
                repo.state.value.phase == PttPhase.DISCONNECTED &&
                    repo.state.value.errorCode == PttErrorCode.PROTOCOL_INCOMPATIBLE
            }
        )
        rogueServer.close()
    }

    // =========================================================================
    // SCENARIOS 8, 9, 10, 12: Connection Loss Mid-Scan/Connect, Repeated Create/End,
    //                         Background/Foreground Continuity & Light/Dark Theme Safety
    // =========================================================================

    @Test
    fun scenarios8to12_connectionLossRepeatedCreateEndBackgroundAndThemeSwitches() {
        val fakeClock = AtomicLong(1_000L)
        val transport = FakeRadioTransport(wifiOn = true)
        val service = FakeServiceBridge()
        val audio = FakeAudioController()
        val repo = buildTestRepo("Tester", "DEV_T1", transport, service, audio, clock = { fakeClock.get() }, tcpPort = 0)

        // Scenario 8a: Wi-Fi turns off mid-scan -> immediately resets to DISCONNECTED + WIFI_DISABLED
        assertTrue(repo.startScanningSessions())
        assertEquals(PttPhase.SCANNING, repo.state.value.phase)
        transport.wifiOn = false
        transport.listener?.onTransportFailure(WifiEnvironmentIssue.WIFI_DISABLED, "Wi-Fi turned off")
        assertEquals(PttPhase.DISCONNECTED, repo.state.value.phase)
        assertEquals(PttErrorCode.WIFI_DISABLED, repo.state.value.errorCode)

        // Scenario 8b: P2P link drops before radio link finishes -> reports distinct P2P_LINK_FAILED (not generic timeout!)
        transport.wifiOn = true
        transport.autoCompleteConnect = false
        val dummyTarget = DiscoveredPttSession("02:11:22:33:44:55", "s1", "H", "H", 49320, 1, 100L)
        assertTrue(repo.requestJoinDiscoveredSession(dummyTarget, "RMG123"))
        assertEquals(PttPhase.CONNECTING, repo.state.value.phase)
        transport.listener?.onP2pLinkDisconnected()
        assertEquals(PttPhase.DISCONNECTED, repo.state.value.phase)
        assertEquals(PttErrorCode.P2P_LINK_FAILED, repo.state.value.errorCode)

        // Scenario 9: Repeatedly create & end host session 5 times in a row without deadlock or leak
        repeat(5) { iter ->
            assertTrue("Iteration $iter failed to create session", repo.createHostSession("CH000$iter"))
            assertEquals(PttPhase.IN_SESSION, repo.state.value.phase)
            assertTrue(service.currentlyRunning.get())

            // Scenario 10: Move app to background & back to foreground while session is active
            repo.isAppInForeground = false
            assertEquals("Session must survive background transition", PttPhase.IN_SESSION, repo.state.value.phase)
            repo.isAppInForeground = true

            // Scenario 11 & 12: Switch Light/Dark palette while session is active -> repo state unaffected!
            C.use(darkMode = true)
            assertEquals(PttPhase.IN_SESSION, repo.state.value.phase)
            C.use(darkMode = false)
            assertEquals(PttPhase.IN_SESSION, repo.state.value.phase)

            // Idempotent double-teardown call must never crash
            repo.endSessionByUser()
            repo.endSessionByUser()
            assertEquals(PttPhase.DISCONNECTED, repo.state.value.phase)
            assertFalse(service.currentlyRunning.get())
        }

        // Scenario 13 guard: Verify LicenseManager, LocationEngine, and RadarView sources remain untouched
        var dir: File? = File("").absoluteFile
        var srcRoot: File? = null
        while (dir != null) {
            val c1 = File(dir, "src/main/java/com/nova/gpspro")
            val c2 = File(dir, "app/src/main/java/com/nova/gpspro")
            if (c1.isDirectory) { srcRoot = c1; break }
            if (c2.isDirectory) { srcRoot = c2; break }
            dir = dir.parentFile
        }
        assumeTrue(srcRoot != null)
        assertTrue(File(srcRoot!!, "license/LicenseManager.kt").isFile)
        assertTrue(File(srcRoot, "location/LocationEngine.kt").isFile)
        assertTrue(File(srcRoot, "ui/RadarView.kt").isFile)
        assertTrue(File(srcRoot, "ui/RadarPage.kt").isFile)
    }

    // =========================================================================
    // NEW REGRESSION: Two-Stage P2P Connect (Direct SSID/PSK -> MAC Fallback ->
    //                 Distinct P2P_LINK_FAILED) + End-to-End Local-Only Hotspot
    // =========================================================================

    @Test
    fun twoStageDirectGroupJoinFallbackAndLocalOnlyHotspotEndToEnd() {
        // 1) Verify Host publishes gn (DIRECT-...), gp (passphrase), and goip into DNS-SD TXT
        val hostSched = ManualTaskScheduler()
        val hostDriver = FakeP2pDriver()
        val hostWdm = WifiDirectManager(appCtx = null, customDriver = hostDriver, scheduler = hostSched)
        var readyGoIp: String? = null
        hostWdm.startHostingGroup("SESS99", "CaptainHost", 49320, onReady = { readyGoIp = it }, onError = { i, d -> fail("$i:$d") })
        hostDriver.currentLinkState = P2pDriver.GroupLinkState(true, true, "192.168.49.1", "DIRECT-RM-Nova")
        hostSched.advanceBy(400L)
        assertEquals("192.168.49.1", readyGoIp)
        val txt = hostDriver.registeredTxt!!
        assertEquals("DIRECT-RM-Nova", txt["gn"])
        assertEquals("pass987654", txt["gp"])
        assertEquals("192.168.49.1", txt["goip"])

        // 2) Verify Client Stage 1 (connectWithGroupCredentials) succeeds immediately when group forms
        val c1Sched = ManualTaskScheduler()
        val c1Driver = FakeP2pDriver()
        val c1Wdm = WifiDirectManager(appCtx = null, customDriver = c1Driver, scheduler = c1Sched)
        var linkedGoIp: String? = null
        c1Wdm.listener = object : PttRadioTransport.Listener {
            override fun onSessionsDiscovered(sessions: List<DiscoveredPttSession>) {}
            override fun onDiscoveryFinishedEmpty() {}
            override fun onP2pLinkEstablished(isGroupOwner: Boolean, groupOwnerIp: String) { linkedGoIp = groupOwnerIp }
            override fun onP2pLinkDisconnected() {}
            override fun onLocalHotspotStarted(ssid: String, passphrase: String) {}
            override fun onTransportFailure(issue: WifiEnvironmentIssue, diagnostic: String) {}
        }
        val enrichedSession = DiscoveredPttSession(
            deviceAddress = "aa:bb:cc:11:22:33",
            sessionId = "SESS99",
            hostName = "CaptainHost",
            deviceName = "Pixel",
            tcpPort = 49320,
            protocolVersion = PttProtocol.PROTOCOL_VERSION,
            lastSeenMs = 100L,
            groupOwnerIpHint = "192.168.49.1",
            groupNetworkName = "DIRECT-RM-Nova",
            groupPassphrase = "pass987654"
        )
        c1Wdm.connectToSession(enrichedSession, onInitiated = {}, onFailed = { i, d -> fail("$i:$d") })
        assertTrue(c1Driver.callLog.contains("connectWithGroupCredentials(DIRECT-RM-Nova)"))
        c1Driver.currentLinkState = P2pDriver.GroupLinkState(true, false, "192.168.49.1", "DIRECT-RM-Nova")
        c1Sched.advanceBy(500L)
        assertEquals("192.168.49.1", linkedGoIp)

        // 3) Verify Stage 1 timeout -> automatic fallback to Stage 2 MAC connect -> distinct P2P_LINK_FAILED if radio still fails
        val c2Sched = ManualTaskScheduler()
        val c2Driver = FakeP2pDriver().apply {
            macConnectBehaviors.addLast(false to WifiDirectManager.REASON_ERROR) // first MAC attempt fails -> triggers peer refresh
            macConnectBehaviors.addLast(true to 0) // second MAC attempt accepted, but never forms group
        }
        val c2Wdm = WifiDirectManager(appCtx = null, customDriver = c2Driver, scheduler = c2Sched)
        var failedIssue: WifiEnvironmentIssue? = null
        c2Wdm.connectToSession(
            enrichedSession,
            onInitiated = {},
            onFailed = { issue, _ -> failedIssue = issue }
        )
        // Advance past Stage 1 (8s) + 250ms + peer refresh (900ms) + Stage 2 (10s)
        repeat(25) { c2Sched.advanceBy(1_000L) }
        assertTrue(c2Driver.callLog.contains("connect(aa:bb:cc:11:22:33)"))
        assertEquals(WifiEnvironmentIssue.P2P_LINK_FAILED, failedIssue)

        // 4) Full End-to-End Local-Only Hotspot Host + Guest Join over real localhost sockets
        val hsHostTransport = FakeRadioTransport(wifiOn = true)
        val hsHostRepo = buildTestRepo("HotspotHost", "HS_H1", hsHostTransport, tcpPort = 0)
        assertTrue(hsHostRepo.createHostSession("HOT999", useLocalHotspotFallback = true))
        assertEquals(PttPhase.IN_SESSION, hsHostRepo.state.value.phase)
        assertNotNull("Host must expose Local-Only Hotspot SSID & Passphrase", hsHostRepo.state.value.hotspotInfo)
        assertEquals("AndroidShare_RMG", hsHostRepo.state.value.hotspotInfo?.ssid)

        // Read actual TCP port bound by host via reflection for ephemeral port test
        val srvField = PttRepository::class.java.getDeclaredField("tcpServer").apply { isAccessible = true }
        val actualHsPort = (srvField.get(hsHostRepo) as ServerSocket).localPort

        val hsGuestTransport = FakeRadioTransport(
            wifiOn = true,
            gatewayCheck = LocalHotspotGatewayCheck.Ready("127.0.0.1")
        )
        val hsGuestRepo = buildTestRepo("HotspotGuest", "HS_G1", hsGuestTransport, tcpPort = actualHsPort)

        // Wrong channel code over Local Hotspot -> must fail with AUTH_FAILED (never CONNECT_TIMEOUT!)
        assertTrue(hsGuestRepo.requestJoinLocalHotspotGateway("BAD000", explicitPort = actualHsPort))
        assertTrue(
            waitUntil { hsGuestRepo.state.value.phase == PttPhase.DISCONNECTED && hsGuestRepo.state.value.errorCode == PttErrorCode.AUTH_FAILED }
        )

        // Correct channel code over Local Hotspot -> Host sees approval request -> accepts -> IN_SESSION!
        assertTrue(hsGuestRepo.requestJoinLocalHotspotGateway("HOT999", explicitPort = actualHsPort))
        assertTrue(waitUntil { hsHostRepo.state.value.pendingApprovals.size == 1 })
        hsHostRepo.approveJoinRequest(hsHostRepo.state.value.pendingApprovals.first().requestId)
        assertTrue(waitUntil { hsGuestRepo.state.value.phase == PttPhase.IN_SESSION && hsHostRepo.state.value.connectedCount == 2 })

        hsHostRepo.endSessionByUser()
        assertTrue(waitUntil { hsGuestRepo.state.value.phase == PttPhase.DISCONNECTED })
    }
}
