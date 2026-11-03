package com.nova.gpspro.ptt

import android.Manifest
import android.content.pm.ServiceInfo
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class PttWalkieTalkieTest {

    companion object {
        /** Deterministic stand-in for a host Keystore SPKI (tests never touch AndroidKeyStore). */
        val TEST_HOST_SPKI: ByteArray = ByteArray(91) { (it * 3 + 11).toByte() }
    }

    private fun findRepoFile(relativePath: String): File? {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val direct = File(dir, relativePath)
            if (direct.exists()) return direct
            val nested = File(dir, "app/$relativePath")
            if (nested.exists()) return nested
            dir = dir.parentFile
        }
        return null
    }

    // =========================================================================
    // 1. Host-certificate identity + approval-bound challenge-response authentication
    //    (channel codes are gone; identity now comes from the host certificate pin)
    // =========================================================================

    private fun hostKeyPair(): Pair<java.security.KeyPair, String> {
        val kpg = java.security.KeyPairGenerator.getInstance("EC")
        kpg.initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
        val kp = kpg.generateKeyPair()
        return kp to PttSecurity.computeCertificatePinIssuerFromEncoded(kp.public.encoded)
    }

    @Test
    fun certificatePinIsStableMatchesTheKeyAndRejectsAnyOtherIdentity() {
        val (kp, pin) = hostKeyPair()
        assertEquals(pin, PttSecurity.computeCertificatePinIssuerFromEncoded(kp.public.encoded))
        assertEquals(pin, PttSecurity.computeCertificatePinIssuer(kp.public))
        assertEquals(64, pin.length)
        assertTrue(pin.all { it in "0123456789abcdef" })

        val (otherKp, otherPin) = hostKeyPair()
        assertNotEquals(pin, otherPin)
        assertNotEquals(pin, PttSecurity.computeCertificatePinIssuerFromEncoded(otherKp.public.encoded))
    }

    @Test
    fun sessionKeyIsDerivedFromTheVerifiedHostKeyAndSessionOnly() {
        val (kp, _) = hostKeyPair()
        val (otherKp, _) = hostKeyPair()
        val sessionId = PttSecurity.randomHex(12)
        val saltHex = PttSecurity.randomHex(16)

        val hostSide = PttSecurity.deriveSessionKeyFromCertificate(kp.public.encoded, sessionId, saltHex)
        val guestSide = PttSecurity.deriveSessionKeyFromCertificate(kp.public.encoded, sessionId, saltHex)
        assertArrayEquals("Both sides must derive the same key from the same SPKI", hostSide, guestSide)

        val otherHost = PttSecurity.deriveSessionKeyFromCertificate(otherKp.public.encoded, sessionId, saltHex)
        assertFalse("A different host identity must yield a different key", hostSide.contentEquals(otherHost))

        val otherSession = PttSecurity.deriveSessionKeyFromCertificate(kp.public.encoded, PttSecurity.randomHex(12), saltHex)
        assertFalse("A different session id must yield a different key", hostSide.contentEquals(otherSession))

        val otherSalt = PttSecurity.deriveSessionKeyFromCertificate(kp.public.encoded, sessionId, PttSecurity.randomHex(16))
        assertFalse("A different per-session salt must yield a different key", hostSide.contentEquals(otherSalt))
    }

    @Test
    fun joinProofBindsTheVerifiedHostAndRejectsForeignOrReplayedProofs() {
        val (hostKp, _) = hostKeyPair()
        val (foreignKp, _) = hostKeyPair()
        val sessionId = PttSecurity.randomHex(8)
        val saltHex = PttSecurity.randomHex(16)
        val serverNonce = PttSecurity.randomHex(16)
        val clientNonce = PttSecurity.randomHex(16)

        val hostKey = PttSecurity.deriveSessionKeyFromCertificate(hostKp.public.encoded, sessionId, saltHex)
        val guestKey = PttSecurity.deriveSessionKeyFromCertificate(hostKp.public.encoded, sessionId, saltHex)
        val foreignKey = PttSecurity.deriveSessionKeyFromCertificate(foreignKp.public.encoded, sessionId, saltHex)

        val validProof = PttSecurity.computeClientJoinProof(
            sessionKey = guestKey,
            sessionId = sessionId,
            serverNonce = serverNonce,
            clientNonce = clientNonce,
            deviceId = "DEV_GUEST_1",
            displayName = "Ali"
        )
        assertTrue(
            "A guest that verified the host pin must pass verification",
            PttSecurity.verifyClientJoinProof(hostKey, sessionId, serverNonce, clientNonce, "DEV_GUEST_1", "Ali", validProof)
        )

        val foreignProof = PttSecurity.computeClientJoinProof(
            sessionKey = foreignKey,
            sessionId = sessionId,
            serverNonce = serverNonce,
            clientNonce = clientNonce,
            deviceId = "DEV_GUEST_1",
            displayName = "Ali"
        )
        assertFalse(
            "A guest that never verified the host certificate must be rejected",
            PttSecurity.verifyClientJoinProof(hostKey, sessionId, serverNonce, clientNonce, "DEV_GUEST_1", "Ali", foreignProof)
        )

        // Replay / nonce tampering must fail
        assertFalse(
            "Replayed proof with a different server nonce must fail",
            PttSecurity.verifyClientJoinProof(
                hostKey, sessionId, PttSecurity.randomHex(16), clientNonce, "DEV_GUEST_1", "Ali", validProof
            )
        )

        // Mutual authentication: the host proves the same verified identity back to the guest
        val assignedToken = PttSecurity.randomHex(16)
        val hostAcceptProof = PttSecurity.computeHostAcceptProof(hostKey, sessionId, clientNonce, serverNonce, assignedToken)
        assertTrue(
            PttSecurity.verifyHostAcceptProof(guestKey, sessionId, clientNonce, serverNonce, assignedToken, hostAcceptProof)
        )
        assertFalse(
            PttSecurity.verifyHostAcceptProof(foreignKey, sessionId, clientNonce, serverNonce, assignedToken, hostAcceptProof)
        )
    }

    @Test
    fun approvalNonceBindsTheFinalSessionKeyAndNeverLeaksItBeforeApproval() {
        val (hostKp, _) = hostKeyPair()
        val sessionId = PttSecurity.randomHex(12)
        val baseKey = PttSecurity.deriveSessionKeyFromCertificate(hostKp.public.encoded, sessionId, PttSecurity.randomHex(16))

        val approvalNonce = PttSecurity.randomHex(16)
        val hostApproved = PttProtocol.deriveApprovalKey(baseKey, approvalNonce)
        val guestApproved = PttProtocol.deriveApprovalKey(baseKey, approvalNonce)
        assertArrayEquals("Approval must produce the same key on both sides", hostApproved, guestApproved)

        assertFalse("The approved key must differ from the un-approved base key", hostApproved.contentEquals(baseKey))
        assertFalse(
            "A different approval nonce must produce a different key",
            hostApproved.contentEquals(PttProtocol.deriveApprovalKey(baseKey, PttSecurity.randomHex(16)))
        )

        // Pending (unapproved) requests never own a usable approved key.
        assertFalse(PttSecurity.verifyHostAcceptProof(
            baseKey, sessionId, "cn", "sn", "tok",
            PttSecurity.computeHostAcceptProof(hostApproved, sessionId, "cn", "sn", "tok")
        ))

        // Media keys are derived from the approved key, so media is approval-bound too.
        assertFalse(
            PttSecurity.deriveMediaKey(baseKey, sessionId)
                .contentEquals(PttSecurity.deriveMediaKey(hostApproved, sessionId))
        )
    }

    // =========================================================================
    // 2. Protocol Version Compatibility, Codec Negotiation & Signed TCP Control
    // =========================================================================

    @Test
    fun protocolVersionCompatibilityAndCodecNegotiation() {
        assertEquals(2, PttProtocol.PROTOCOL_VERSION)

        // Both support Opus + ADPCM -> Opus 16kHz selected
        val bothMask = AudioCodecType.OPUS_16K.mask or AudioCodecType.ADPCM_16K.mask
        assertEquals(AudioCodecType.OPUS_16K, AudioCodecType.negotiate(bothMask, bothMask))

        // One side lacks Opus -> falls back cleanly to compressed low-latency IMA-ADPCM 16kHz
        val adpcmOnlyMask = AudioCodecType.ADPCM_16K.mask
        assertEquals(AudioCodecType.ADPCM_16K, AudioCodecType.negotiate(bothMask, adpcmOnlyMask))
        assertEquals(AudioCodecType.ADPCM_16K, AudioCodecType.negotiate(adpcmOnlyMask, bothMask))

        // Control frame round-trip + HMAC integrity verification
        val key = PttSecurity.deriveSessionKeyFromCertificate(
            TEST_HOST_SPKI,
            PttSecurity.randomHex(12),
            PttSecurity.randomHex(16)
        )
        val framesToTest = listOf(
            PttProtocol.ControlFrame.HelloChallenge(
                protocolVersion = PttProtocol.PROTOCOL_VERSION,
                sessionId = "sess1",
                sessionSaltHex = "aabbccdd",
                hostPublicKeyHex = "30593013",
                serverNonce = "11223344",
                hostId = "H1",
                hostName = "Captain",
                supportedCodecMask = bothMask
            ),
            PttProtocol.ControlFrame.JoinRejected(PttRejectReason.PROTOCOL_MISMATCH),
            PttProtocol.ControlFrame.FloorGranted("P2", "Omar", leaseId = 7, maxDurationMs = 45_000L),
            PttProtocol.ControlFrame.RosterSync(
                negotiatedCodecId = AudioCodecType.OPUS_16K.id,
                roster = listOf(
                    WireParticipant("H1", "Captain", isHost = true, rttMs = 0, lossPct = 0),
                    WireParticipant("P2", "Omar", isHost = false, rttMs = 24, lossPct = 1)
                )
            )
        )

        for (original in framesToTest) {
            val out = ByteArrayOutputStream()
            PttProtocol.writeControlFrame(out, original, macKey = key)
            val wireBytes = out.toByteArray()
            val decoded = PttProtocol.readControlFrame(ByteArrayInputStream(wireBytes), macKey = key, requireMac = true)
            assertEquals(original, decoded)

            // Tamper with a payload byte -> MAC verification must throw IOException
            val tampered = wireBytes.copyOf()
            tampered[tampered.lastIndex] = (tampered.last().toInt() xor 0xFF).toByte()
            assertThrows(IOException::class.java) {
                PttProtocol.readControlFrame(ByteArrayInputStream(tampered), macKey = key, requireMac = true)
            }
        }
    }

    // =========================================================================
    // 3. Half-Duplex Floor Arbitrator & Three Button Visual States (#2457D6 / #14866D / #D99A32)
    // =========================================================================

    @Test
    fun halfDuplexPreventsSimultaneousSpeechAndTransitionsThreeButtonColors() {
        // Verify exact mandatory hex colors
        assertEquals(0xFF2457D6.toInt(), PttButtonVisualState.IDLE_BLUE.hexColor)
        assertEquals(0xFF14866D.toInt(), PttButtonVisualState.SELF_SPEAKING_GREEN.hexColor)
        assertEquals(0xFFD99A32.toInt(), PttButtonVisualState.REMOTE_SPEAKING_AMBER.hexColor)

        val hostMgr = PttSessionManager(localParticipantId = "HOST_1", initialLocalName = "Host", isHostRole = true)
        hostMgr.registerAuthenticatedParticipant("PEER_A", "Ahmed", isHost = false, authToken = "tokA", nowMs = 1_000L)
        hostMgr.registerAuthenticatedParticipant("PEER_B", "Zaid", isHost = false, authToken = "tokB", nowMs = 1_000L)

        // State 1: Idle -> Royal Blue #2457D6
        assertEquals(PttButtonVisualState.IDLE_BLUE, hostMgr.currentVisualState())

        // State 2: Local user acquires floor -> Emerald Green #14866D
        val hostGrant = hostMgr.arbitrateFloorRequest("HOST_1", nowMs = 1_100L)
        assertTrue(hostGrant is FloorDecision.Granted)
        assertEquals(PttButtonVisualState.SELF_SPEAKING_GREEN, hostMgr.currentVisualState())

        // Simultaneous request by Ahmed while Host speaks -> strictly DENIED
        val deniedWhileHostSpeaking = hostMgr.arbitrateFloorRequest("PEER_A", nowMs = 1_150L)
        assertEquals(FloorDecision.Denied("HOST_1", "Host"), deniedWhileHostSpeaking)

        // Host releases floor -> returns immediately to Idle Royal Blue #2457D6
        val lease1 = (hostGrant as FloorDecision.Granted).leaseId
        assertEquals(lease1, hostMgr.releaseFloor("HOST_1", lease1))
        assertEquals(PttButtonVisualState.IDLE_BLUE, hostMgr.currentVisualState())

        // State 3: Remote participant Ahmed acquires floor -> Amber #D99A32 on Host
        val peerGrant = hostMgr.arbitrateFloorRequest("PEER_A", nowMs = 2_000L)
        assertTrue(peerGrant is FloorDecision.Granted)
        assertEquals(PttButtonVisualState.REMOTE_SPEAKING_AMBER, hostMgr.currentVisualState())
        assertEquals("Ahmed", hostMgr.currentSpeakerName())

        // Simultaneous attempt by Zaid OR Host while Ahmed speaks -> DENIED
        assertEquals(FloorDecision.Denied("PEER_A", "Ahmed"), hostMgr.arbitrateFloorRequest("PEER_B", nowMs = 2_050L))
        assertEquals(FloorDecision.Denied("PEER_A", "Ahmed"), hostMgr.arbitrateFloorRequest("HOST_1", nowMs = 2_060L))

        // Unregistered intruder cannot request floor
        assertEquals(FloorDecision.Unauthorized, hostMgr.arbitrateFloorRequest("INTRUDER", nowMs = 2_070L))

        // Ahmed finishes speaking -> automatically returns to Idle Royal Blue #2457D6
        val lease2 = (peerGrant as FloorDecision.Granted).leaseId
        assertEquals(lease2, hostMgr.releaseFloor("PEER_A", lease2))
        assertEquals(PttButtonVisualState.IDLE_BLUE, hostMgr.currentVisualState())
    }

    @Test
    fun maxFloorLeasePreventsInfiniteChannelLock() {
        val mgr = PttSessionManager("HOST_1", "Host", isHostRole = true)
        val grant = mgr.arbitrateFloorRequest("HOST_1", nowMs = 10_000L) as FloorDecision.Granted
        assertEquals(PttButtonVisualState.SELF_SPEAKING_GREEN, mgr.currentVisualState())

        // Before 45s max lease -> still active
        val earlyCheck = mgr.checkTimeouts(nowMs = 10_000L + 30_000L)
        assertNull(earlyCheck.floorExpiredLeaseId)
        assertEquals(PttButtonVisualState.SELF_SPEAKING_GREEN, mgr.currentVisualState())

        // At 45s max lease -> floor automatically reclaimed to IDLE_BLUE
        val expiredCheck = mgr.checkTimeouts(nowMs = 10_000L + PttSessionManager.MAX_FLOOR_LEASE_MS)
        assertEquals(grant.leaseId, expiredCheck.floorExpiredLeaseId)
        assertEquals(PttButtonVisualState.IDLE_BLUE, mgr.currentVisualState())
    }

    // =========================================================================
    // 4. UDP Voice Framing, IMA-ADPCM 16kHz Codec, Jitter Buffer Reorder/Dup/PLC
    // =========================================================================

    @Test
    fun udpVoicePacketFramingAndAuthentication() {
        val sessionKey = PttSecurity.deriveSessionKeyFromCertificate(TEST_HOST_SPKI, "SESS_99", "0011223344556677")
        val mediaKey = PttSecurity.deriveMediaKey(sessionKey, "SESS_99")
        val wrongMediaKey = PttSecurity.deriveMediaKey(sessionKey, "OTHER_SESS")

        val samplePayload = ByteArray(164) { i -> (i * 7).toByte() }
        val senderHash = PttSecurity.participantShortHash("PEER_A")
        val datagram = PttProtocol.encodeUdpVoicePacket(
            mediaKey = mediaKey,
            codec = AudioCodecType.ADPCM_16K,
            endOfBurst = true,
            leaseId = 4,
            sequence = 128,
            timestampMs = 98_765L,
            senderShortHash = senderHash,
            payload = samplePayload
        )

        val unpacked = PttProtocol.decodeUdpVoicePacket(mediaKey, datagram)
        assertNotNull(unpacked)
        assertEquals(AudioCodecType.ADPCM_16K, unpacked!!.codec)
        assertTrue(unpacked.endOfBurst)
        assertEquals(4, unpacked.leaseId)
        assertEquals(128, unpacked.sequence)
        assertEquals(98_765L, unpacked.timestampMs)
        assertEquals(senderHash, unpacked.senderShortHash)
        assertArrayEquals(samplePayload, unpacked.payload)

        // Forged key or corrupted byte must be rejected (null)
        assertNull(PttProtocol.decodeUdpVoicePacket(wrongMediaKey, datagram))
        val corrupted = datagram.copyOf()
        corrupted[PttProtocol.UDP_HEADER_BYTES + 5] = (corrupted[PttProtocol.UDP_HEADER_BYTES + 5].toInt() xor 0x55).toByte()
        assertNull(PttProtocol.decodeUdpVoicePacket(mediaKey, corrupted))
    }

    @Test
    fun adpcm16kCompressionAndJitterBufferOutOfOrderDuplicateAndMissingPackets() {
        // 1. Verify IMA-ADPCM 20ms frame (320 samples -> 164 bytes) fidelity
        val originalWave = ShortArray(320) { n ->
            (sin(2.0 * PI * 440.0 * n / 16_000.0) * 14_000).toInt().toShort()
        }
        val encoded = ImaAdpcmCodec.encodeFrame(originalWave)
        assertEquals(164, encoded.size)
        val decoded = ImaAdpcmCodec.decodeFrame(encoded, 320)
        assertEquals(320, decoded.size)
        val meanAbsErr = originalWave.indices.sumOf { abs(originalWave[it].toInt() - decoded[it].toInt()) } / 320.0
        assertTrue("ADPCM reconstruction error too high: $meanAbsErr", meanAbsErr < 650.0)

        // 2. Verify AdaptiveJitterBuffer out-of-order sorting, duplicate rejection, and missing packet PLC
        val jb = AdaptiveJitterBuffer(frameSamples = 4, frameDurationMs = 20)
        val f10 = shortArrayOf(1000, 1000, 1000, 1000)
        val f11 = shortArrayOf(2000, 2000, 2000, 2000)
        val f12 = shortArrayOf(3000, 3000, 3000, 3000)
        val f14 = shortArrayOf(5000, 5000, 5000, 5000) // Seq 13 intentionally lost!

        // Push out-of-order: 10, 12, duplicate 12, then 11, then 14 (skipping 13)
        jb.push(10, 200L, 200L, f10)
        jb.push(12, 240L, 242L, f12)
        jb.push(12, 240L, 243L, f12) // duplicate while buffered
        jb.push(11, 220L, 244L, f11) // out-of-order arrival
        jb.push(14, 280L, 280L, f14) // seq 13 missing

        assertEquals(1, jb.duplicateOrLateDropsCount())

        // Playout must strictly yield 10 -> 11 -> 12 -> concealed(13) -> 14
        assertArrayEquals(f10, jb.pollNextFrame())
        // Late duplicate of already-played packet 10 must also be dropped
        jb.push(10, 200L, 290L, f10)
        assertEquals(2, jb.duplicateOrLateDropsCount())

        assertArrayEquals(f11, jb.pollNextFrame())
        assertArrayEquals(f12, jb.pollNextFrame())

        val plcFor13 = jb.pollNextFrame()
        assertNotNull("PLC must synthesize smooth replacement for missing seq 13", plcFor13)
        // Attenuated from f12 (3000 * 0.68 = 2040)
        assertTrue(plcFor13!![0] in 1900..2150)

        assertArrayEquals(f14, jb.pollNextFrame())
        assertTrue("Measured packet loss must reflect the 1-in-5 missing packet", jb.measuredLossPercent() in 15..25)
    }

    // =========================================================================
    // 5. Heartbeat Timeout (~10s) & Link Quality Evaluation + Per-Peer Local Mute
    // =========================================================================

    @Test
    fun heartbeatTimeoutToleratesBriefFadeAndDetectsTenSecondDisconnect() {
        val mgr = PttSessionManager("HOST_1", "Host", isHostRole = true)
        mgr.registerAuthenticatedParticipant("PEER_1", "Hassan", isHost = false, authToken = "t1", nowMs = 1_000L)
        mgr.registerAuthenticatedParticipant("PEER_2", "Kareem", isHost = false, authToken = "t2", nowMs = 1_000L)
        assertEquals(3, mgr.connectedCount())

        // At t = 9,500ms (8.5s elapsed < 10s): brief wireless fade must NOT falsely drop peers
        mgr.recordHeartbeat("PEER_1", rttMs = 22, lossPct = 0, nowMs = 6_000L)
        val checkBefore10s = mgr.checkTimeouts(nowMs = 10_500L)
        assertTrue(checkBefore10s.timedOutParticipantIds.isEmpty())
        assertEquals(3, mgr.connectedCount())

        // At t = 11,001ms: PEER_2 has been silent for 10,001ms (>= 10s), while PEER_1 was seen at 6,000ms (5s ago)
        val checkAfter10s = mgr.checkTimeouts(nowMs = 11_001L)
        assertEquals(listOf("PEER_2"), checkAfter10s.timedOutParticipantIds)
        assertEquals(2, mgr.connectedCount())

        // Verify link quality classification and local per-peer mute
        assertEquals(LinkQualityLevel.EXCELLENT, LinkQualityLevel.evaluate(rttMs = 20, packetLossPct = 0))
        assertEquals(LinkQualityLevel.GOOD, LinkQualityLevel.evaluate(rttMs = 60, packetLossPct = 2))
        assertEquals(LinkQualityLevel.FAIR, LinkQualityLevel.evaluate(rttMs = 140, packetLossPct = 8))
        assertEquals(LinkQualityLevel.WEAK, LinkQualityLevel.evaluate(rttMs = 260, packetLossPct = 20))
        assertEquals(LinkQualityLevel.MEASURING, LinkQualityLevel.evaluate(rttMs = null, packetLossPct = 0))

        assertTrue(mgr.togglePeerLocallyMuted("PEER_1"))
        assertTrue(mgr.isPeerMutedByShortHash(PttSecurity.participantShortHash("PEER_1")))
        assertFalse(mgr.togglePeerLocallyMuted("PEER_1"))
        assertFalse(mgr.isPeerMutedByShortHash(PttSecurity.participantShortHash("PEER_1")))
    }

    // =========================================================================
    // 6. Android 11–16 Permission Matrix, FGS Type Policy & Zero-Bluetooth / Zero-GPS Guard
    // =========================================================================

    @Test
    fun permissionAndForegroundServiceMatrixAcrossApi30To36AndZeroBluetooth() {
        // Android 11 & 12 (API 30–32)
        for (sdk in listOf(30, 31, 32)) {
            val core = PttPermissions.requiredCorePermissions(sdk)
            assertEquals(
                listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION),
                core
            )
            assertFalse(PttPermissions.allPromptPermissions(sdk).contains(Manifest.permission.POST_NOTIFICATIONS))
        }

        // Android 13, 14, 15, 16 (API 33–36)
        for (sdk in listOf(33, 34, 35, 36)) {
            val core = PttPermissions.requiredCorePermissions(sdk)
            assertEquals(
                listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.NEARBY_WIFI_DEVICES),
                core
            )
            assertTrue(PttPermissions.allPromptPermissions(sdk).contains(Manifest.permission.POST_NOTIFICATIONS))
        }

        // ForegroundService type resolution (Android 14–16 microphone FGS safety)
        val connectedOnly = PttPermissions.resolveForegroundServiceType(
            sdkInt = 35,
            hasRecordAudioPerm = false,
            isAppForegroundOrAlreadyMicEligible = true
        )
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE, connectedOnly)

        val bgWithoutPriorMic = PttPermissions.resolveForegroundServiceType(
            sdkInt = 35,
            hasRecordAudioPerm = true,
            isAppForegroundOrAlreadyMicEligible = false
        )
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE, bgWithoutPriorMic)

        val fgWithMic = PttPermissions.resolveForegroundServiceType(
            sdkInt = 35,
            hasRecordAudioPerm = true,
            isAppForegroundOrAlreadyMicEligible = true
        )
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            fgWithMic
        )

        // Static guard: Manifest must have ZERO Bluetooth permissions & ptt package must never touch GPS coordinates
        val manifestFile = findRepoFile("src/main/AndroidManifest.xml")
        assumeTrue("AndroidManifest.xml reachable", manifestFile != null)
        val manifestText = manifestFile!!.readText()
        assertFalse("Bluetooth permissions are strictly forbidden", manifestText.contains("BLUETOOTH", ignoreCase = true))
        assertTrue("PttService must declare connectedDevice|microphone", manifestText.contains("connectedDevice|microphone"))

        val pttDir = findRepoFile("src/main/java/com/nova/gpspro/ptt")!!
        for (f in pttDir.walkTopDown().filter { it.isFile && it.extension == "kt" }) {
            val src = f.readText()
            assertFalse("${f.name} must not use Bluetooth", src.contains("android.bluetooth"))
            assertFalse("${f.name} must not read GPS coordinates", src.contains("getLastKnownLocation") || src.contains("requestLocationUpdates"))
            assertFalse("${f.name} must not reference RadarView", src.contains("RadarView") || src.contains("RadarPage"))
        }
    }

    // =========================================================================
    // 7. Arabic / English Resource Keys 1:1 Parity & Exact 5th Portal Card Texts
    // =========================================================================

    @Test
    fun arabicAndEnglishStringResourcesMatchOneToOne() {
        val enFile = findRepoFile("src/main/res/values/strings.xml")
        val arFile = findRepoFile("src/main/res/values-ar/strings.xml")
        assumeTrue("strings.xml files reachable", enFile != null && arFile != null)

        fun parseStringMap(file: File, skipNonTranslatable: Boolean): Map<String, String> {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val nodes = doc.getElementsByTagName("string")
            val map = LinkedHashMap<String, String>()
            for (i in 0 until nodes.length) {
                val el = nodes.item(i)
                val translatableAttr = el.attributes?.getNamedItem("translatable")?.nodeValue
                if (skipNonTranslatable && translatableAttr == "false") continue
                val name = el.attributes?.getNamedItem("name")?.nodeValue ?: continue
                map[name] = el.textContent ?: ""
            }
            return map
        }

        val enMap = parseStringMap(enFile!!, skipNonTranslatable = true)
        val arMap = parseStringMap(arFile!!, skipNonTranslatable = true)

        val missingInAr = enMap.keys - arMap.keys
        val missingInEn = arMap.keys - enMap.keys
        assertTrue("Keys missing in values-ar/strings.xml: $missingInAr", missingInAr.isEmpty())
        assertTrue("Keys missing in values/strings.xml: $missingInEn", missingInEn.isEmpty())

        val pttKeys = enMap.keys.filter { it.startsWith("ptt_") }
        assertTrue("Expected comprehensive ptt_* string keys", pttKeys.size >= 45)
        for (k in pttKeys) {
            assertTrue("Empty English value for $k", enMap[k]!!.isNotBlank())
            assertTrue("Empty Arabic value for $k", arMap[k]!!.isNotBlank())
        }

        // Exact mandatory Portal 5th card texts & Wi-Fi / Discovery texts
        assertEquals("Walkie-Talkie", enMap["ptt_portal_title"])
        assertEquals("Talk to nearby devices without internet.", enMap["ptt_portal_desc"])
        assertEquals("اللاسلكي", arMap["ptt_portal_title"])
        assertEquals("تحدث مع الأجهزة القريبة مباشرة دون إنترنت", arMap["ptt_portal_desc"])
        assertEquals("Wi-Fi مغلق. يرجى تشغيل Wi-Fi للمتابعة", arMap["ptt_err_wifi_disabled"])
        assertEquals("فتح إعدادات Wi-Fi", arMap["ptt_open_wifi_settings"])
        assertEquals("جارٍ البحث", arMap["ptt_discovery_searching"])
        assertEquals("تم العثور على جلسة", arMap["ptt_discovery_found"])
        assertEquals("فشل الاكتشاف", arMap["ptt_discovery_failed"])

        // Verify corrected AOSP DNS-SD instance & service type constants
        assertEquals("rmgptt", PttProtocol.DNS_SERVICE_INSTANCE)
        assertEquals("_rmgptt._tcp", PttProtocol.DNS_SERVICE_TYPE)
        assertTrue(PttProtocol.matchesDnsSdService("rmgptt._rmgptt._tcp.local."))
        assertFalse(PttProtocol.matchesDnsSdService("printer._ipp._tcp.local."))
    }
}
