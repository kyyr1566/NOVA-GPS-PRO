package com.nova.gpspro

import android.Manifest
import android.content.pm.ServiceInfo
import com.nova.gpspro.connect.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs

class RmgConnectTest {

    private val appRoot: File? by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "src/main/AndroidManifest.xml").isFile) return@lazy dir
            if (File(dir, "app/src/main/AndroidManifest.xml").isFile) return@lazy File(dir, "app")
            dir = dir.parentFile
        }
        null
    }

    @Test
    fun defaultsHaveMicMutedAndLocationSharingOff() {
        assertTrue("Mic must be muted by default", RmgDefaults.DEFAULT_MIC_MUTED)
        assertFalse("GPS location sharing must be OFF by default", RmgDefaults.DEFAULT_LOCATION_SHARING)
        assertEquals(VoiceMode.PTT, RmgDefaults.DEFAULT_VOICE_MODE)

        val snap = RmgSessionSnapshot(localNodeId = "node1", localDisplayName = "Device A")
        assertTrue(snap.isMicMuted)
        assertFalse(snap.isSharingLocation)
        assertFalse(snap.isPttPressed)
        assertFalse(snap.hasActiveSession)
    }

    @Test
    fun gpsBackgroundLifecycleFixesOnStopOnlyDuringActiveLocationSharing() {
        // Outside session: MainActivity.onStop() MUST stop GPS normally
        assertFalse(GpsLifecyclePolicy.shouldKeepGpsOnActivityStop(hasActiveSession = false, isSharingLocation = false))
        assertFalse(GpsLifecyclePolicy.shouldKeepGpsOnActivityStop(hasActiveSession = false, isSharingLocation = true))

        // Inside session with location sharing OFF: MainActivity.onStop() still stops GPS to save battery
        assertFalse(GpsLifecyclePolicy.shouldKeepGpsOnActivityStop(hasActiveSession = true, isSharingLocation = false))

        // Inside session with location sharing ON: MainActivity.onStop() keeps GPS alive in background
        assertTrue(GpsLifecyclePolicy.shouldKeepGpsOnActivityStop(hasActiveSession = true, isSharingLocation = true))
    }

    @Test
    fun android14And15ForegroundServiceTypeMaskFollowsGrantedPermissions() {
        // Base connected-device session with mic muted & GPS sharing off
        val baseMask = RmgPermissions.resolveForegroundServiceType(
            sdkInt = 35,
            hasLocationPerm = true,
            isSharingLocation = false,
            hasRecordAudioPerm = true,
            includeMicrophoneType = false
        )
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE, baseMask)

        // Location sharing enabled WITH location permission adds FOREGROUND_SERVICE_TYPE_LOCATION
        val locMask = RmgPermissions.resolveForegroundServiceType(
            sdkInt = 35,
            hasLocationPerm = true,
            isSharingLocation = true,
            hasRecordAudioPerm = false,
            includeMicrophoneType = true
        )
        assertTrue((locMask and ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE) != 0)
        assertTrue((locMask and ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) != 0)
        assertEquals(0, locMask and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)

        // Location sharing enabled WITHOUT permission must NOT include LOCATION type (prevents SecurityException on API 34/35)
        val noPermLocMask = RmgPermissions.resolveForegroundServiceType(
            sdkInt = 34,
            hasLocationPerm = false,
            isSharingLocation = true,
            hasRecordAudioPerm = false,
            includeMicrophoneType = false
        )
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE, noPermLocMask)

        // Full voice + GPS session with permissions includes all three FGS types
        val fullMask = RmgPermissions.resolveForegroundServiceType(
            sdkInt = 35,
            hasLocationPerm = true,
            isSharingLocation = true,
            hasRecordAudioPerm = true,
            includeMicrophoneType = true
        )
        val expected = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        assertEquals(expected, fullMask)
    }

    @Test
    fun runtimeNearbyPermissionsAdaptAcrossApi30To35() {
        val api30 = RmgPermissions.nearbyPermissionsForSdk(30)
        assertTrue(Manifest.permission.ACCESS_FINE_LOCATION in api30)

        val api31 = RmgPermissions.nearbyPermissionsForSdk(31)
        assertTrue(Manifest.permission.ACCESS_FINE_LOCATION in api31)
        assertTrue(Manifest.permission.BLUETOOTH_SCAN in api31)
        assertTrue(Manifest.permission.BLUETOOTH_CONNECT in api31)
        assertTrue(Manifest.permission.BLUETOOTH_ADVERTISE in api31)

        val api34 = RmgPermissions.nearbyPermissionsForSdk(34)
        assertTrue(Manifest.permission.NEARBY_WIFI_DEVICES in api34)
        assertFalse("API 33+ Wi-Fi Direct discovery uses NEARBY_WIFI_DEVICES without forcing location", Manifest.permission.ACCESS_FINE_LOCATION in api34)
        assertTrue(Manifest.permission.BLUETOOTH_SCAN in api34)
        assertTrue(Manifest.permission.BLUETOOTH_CONNECT in api34)
    }

    @Test
    fun wireProtocolRoundTripsAllControlChatRosterAndLocationPackets() {
        val packets = listOf(
            RmgWireProtocol.Packet.ConnRequest("node-a", "Phone Alpha", RmgDefaults.PROTOCOL_VERSION),
            RmgWireProtocol.Packet.ConnAccept("node-b", "Phone Beta", RmgDefaults.UDP_VOICE_PORT),
            RmgWireProtocol.Packet.ConnReject("node-b"),
            RmgWireProtocol.Packet.ConnBlock("node-b"),
            RmgWireProtocol.Packet.PeerRoster(
                listOf(
                    RmgWireProtocol.Packet.RosterEntry("n1", "Peer 1", "192.168.49.2"),
                    RmgWireProtocol.Packet.RosterEntry("n2", "الجهاز الثاني", "192.168.49.3")
                )
            ),
            RmgWireProtocol.Packet.Chat("msg-101", "node-a", "أحمد", "مرحباً يا شباب — بدون إنترنت!", 1762160000000L),
            RmgWireProtocol.Packet.ChatAck("msg-101", "node-b"),
            RmgWireProtocol.Packet.LocationUpdate("node-a", "أحمد", 32.123456, 44.654321, 4.5f, 3.2f, 1762160005000L),
            RmgWireProtocol.Packet.LocationStop("node-a"),
            RmgWireProtocol.Packet.Ping(7, 99887766L, -52),
            RmgWireProtocol.Packet.Pong(7, 99887766L),
            RmgWireProtocol.Packet.VoiceState("node-a", speaking = true, pttPriority = true),
            RmgWireProtocol.Packet.BtVoiceFrame(byteArrayOf(1, 2, 3, 4, 5)),
            RmgWireProtocol.Packet.Bye("node-a")
        )

        val out = ByteArrayOutputStream()
        for (pkt in packets) RmgWireProtocol.writePacket(out, pkt)

        val inp = ByteArrayInputStream(out.toByteArray())
        for (expected in packets) {
            val decoded = RmgWireProtocol.readPacket(inp)
            when (expected) {
                is RmgWireProtocol.Packet.BtVoiceFrame -> {
                    assertTrue(decoded is RmgWireProtocol.Packet.BtVoiceFrame)
                    assertArrayEquals(expected.rawFrame, (decoded as RmgWireProtocol.Packet.BtVoiceFrame).rawFrame)
                }
                is RmgWireProtocol.Packet.LocationUpdate -> {
                    val d = decoded as RmgWireProtocol.Packet.LocationUpdate
                    assertEquals(expected.senderId, d.senderId)
                    assertEquals(expected.latitude, d.latitude, 1e-6)
                    assertEquals(expected.longitude, d.longitude, 1e-6)
                    assertEquals(expected.accuracyM, d.accuracyM, 1e-3f)
                }
                else -> assertEquals(expected, decoded)
            }
        }
    }

    @Test
    fun audioCodecSupportsPcm16CompactBtAndMultiSpeakerMixingWithPttPriority() {
        val original = ShortArray(RmgAudioCodec.FRAME_SAMPLES) { idx ->
            ((idx % 32 - 16) * 900).toShort()
        }
        val senderHash = RmgAudioCodec.senderHash("node-alpha")

        // PCM16 lossless Wi-Fi Direct voice packet
        val pcmBytes = RmgAudioCodec.encodePcmFrame(
            senderHash = senderHash,
            seq = 42,
            pttPriority = true,
            samples = original
        )
        val decodedPcm = RmgAudioCodec.decodeFrame(pcmBytes)
        assertNotNull(decodedPcm)
        assertEquals(senderHash, decodedPcm!!.senderHash)
        assertEquals(42, decodedPcm.seq)
        assertTrue(decodedPcm.pttPriority)
        assertArrayEquals(original, decodedPcm.samples16k)

        // Compact Bluetooth fallback voice packet (nearly 4x smaller than PCM16)
        val btBytes = RmgAudioCodec.encodeCompactBtFrame(
            senderHash = senderHash,
            seq = 43,
            pttPriority = false,
            samples16k = original
        )
        assertTrue("Compact BT voice frame must be much smaller than PCM16 packet", btBytes.size < pcmBytes.size / 2)
        val decodedBt = RmgAudioCodec.decodeFrame(btBytes)
        assertNotNull(decodedBt)
        assertFalse(decodedBt!!.pttPriority)
        assertEquals(RmgAudioCodec.FRAME_SAMPLES, decodedBt.samples16k.size)
        // Verify non-zero reconstructed waveform
        assertTrue(decodedBt.samples16k.any { abs(it.toInt()) > 500 })

        // Multi-speaker mixer: PTT speaker ducks simultaneous Open-Mic speaker to 30% without clipping
        val pttSamples = ShortArray(RmgAudioCodec.FRAME_SAMPLES) { 10000 }
        val openMicSamples = ShortArray(RmgAudioCodec.FRAME_SAMPLES) { 10000 }

        val mixedEqualOpen = ShortArray(RmgAudioCodec.FRAME_SAMPLES)
        RmgAudioCodec.mixStreams(listOf(openMicSamples to false, openMicSamples to false), mixedEqualOpen)

        val mixedWithPttPriority = ShortArray(RmgAudioCodec.FRAME_SAMPLES)
        RmgAudioCodec.mixStreams(listOf(pttSamples to true, openMicSamples to false), mixedWithPttPriority)

        assertEquals(20000, mixedEqualOpen[0].toInt())
        assertEquals(13000, mixedWithPttPriority[0].toInt())

        // Noise gate attenuates quiet background hiss in Open-Mic mode
        val quietHiss = ShortArray(RmgAudioCodec.FRAME_SAMPLES) { 45 }
        assertFalse(RmgAudioCodec.processCaptureFrame(quietHiss, pttMode = false))
        assertTrue(abs(quietHiss[0].toInt()) < 45)
    }

    @Test
    fun signalQualityAndReconnectBackoffBehaveDeterministically() {
        assertEquals(SignalQuality.EXCELLENT, SignalQuality.evaluate(rssiDbm = -48, rttMs = 15))
        assertEquals(SignalQuality.GOOD, SignalQuality.evaluate(rssiDbm = -63, rttMs = 45))
        assertEquals(SignalQuality.FAIR, SignalQuality.evaluate(rssiDbm = -76, rttMs = 140))
        assertEquals(SignalQuality.WEAK, SignalQuality.evaluate(rssiDbm = -88, rttMs = 350))
        assertEquals(SignalQuality.UNKNOWN, SignalQuality.evaluate(rssiDbm = null, rttMs = null))

        assertTrue(ReconnectBackoff.shouldRetry(attempt = 0, explicitBye = false, blocked = false))
        assertFalse(ReconnectBackoff.shouldRetry(attempt = 0, explicitBye = true, blocked = false))
        assertFalse(ReconnectBackoff.shouldRetry(attempt = 0, explicitBye = false, blocked = true))

        val d0 = ReconnectBackoff.delayMs(0)
        val d1 = ReconnectBackoff.delayMs(1)
        val d2 = ReconnectBackoff.delayMs(2)
        val dMax = ReconnectBackoff.delayMs(12)
        assertTrue(d1 > d0 && d2 > d1)
        assertEquals(20_000L, dMax)

        assertEquals(0, CompassSector.indexFromBearing(0f))
        assertEquals(2, CompassSector.indexFromBearing(90f))
        assertEquals(4, CompassSector.indexFromBearing(180f))
        assertEquals(6, CompassSector.indexFromBearing(270f))
    }

    @Test
    fun manifestAndBilingualStringsContainAllRmgConnectDeclarationsAndPreserveSupabaseLicense() {
        val root = appRoot
        assumeTrue("app root reachable", root != null)

        val manifest = File(root!!, "src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android.permission.NEARBY_WIFI_DEVICES"))
        assertTrue(manifest.contains("android.permission.BLUETOOTH_CONNECT"))
        assertTrue(manifest.contains("android.permission.RECORD_AUDIO"))
        assertTrue(manifest.contains("android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE"))
        assertTrue(manifest.contains("android.permission.FOREGROUND_SERVICE_LOCATION"))
        assertTrue(manifest.contains("android.permission.FOREGROUND_SERVICE_MICROPHONE"))
        assertTrue(manifest.contains(".connect.RmgConnectService"))
        assertTrue(manifest.contains("connectedDevice|location|microphone"))

        val enStrings = File(root, "src/main/res/values/strings.xml").readText()
        val arStrings = File(root, "src/main/res/values-ar/strings.xml").readText()
        val keyRegex = Regex("""<string name="(rmg_connect_[^"]+)">""")
        val enKeys = keyRegex.findAll(enStrings).map { it.groupValues[1] }.toSet()
        val arKeys = keyRegex.findAll(arStrings).map { it.groupValues[1] }.toSet()
        assertTrue("Expected RMG CONNECT string keys", enKeys.size >= 80)
        assertEquals("English and Arabic RMG CONNECT string keys must match 1:1", enKeys, arKeys)

        // Verify Supabase licensing files remain intact
        assertTrue(File(root, "src/main/java/com/nova/gpspro/license/LicenseManager.kt").isFile)
        assertTrue(File(root, "src/main/java/com/nova/gpspro/license/ActivationView.kt").isFile)
    }
}
