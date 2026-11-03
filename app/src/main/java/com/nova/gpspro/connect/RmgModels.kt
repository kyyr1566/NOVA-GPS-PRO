package com.nova.gpspro.connect

/**
 * Core domain models & deterministic state policies for RMG CONNECT.
 * Pure Kotlin — zero Android UI dependencies so every rule is 100% unit-testable on the JVM.
 */

enum class TransportType { WIFI_DIRECT, BLUETOOTH }

enum class SignalQuality(val bars: Int) {
    EXCELLENT(4),
    GOOD(3),
    FAIR(2),
    WEAK(1),
    UNKNOWN(0);

    companion object {
        /**
         * Derives link quality from real radio RSSI (dBm) and/or measured round-trip latency (ms)
         * and audio packet loss percentage. Never invents synthetic numbers when unavailable.
         */
        fun evaluate(rssiDbm: Int?, rttMs: Int?, packetLossPct: Int = 0): SignalQuality {
            if (rssiDbm == null && rttMs == null) return UNKNOWN
            val rssiScore = when {
                rssiDbm == null -> null
                rssiDbm >= -58 -> 4
                rssiDbm >= -70 -> 3
                rssiDbm >= -82 -> 2
                else -> 1
            }
            val rttScore = when {
                rttMs == null -> null
                rttMs <= 28 -> 4
                rttMs <= 70 -> 3
                rttMs <= 160 -> 2
                else -> 1
            }
            val combined = when {
                rssiScore != null && rttScore != null -> minOf(rssiScore, rttScore)
                rssiScore != null -> rssiScore
                else -> rttScore ?: 0
            }
            val penalized = when {
                packetLossPct >= 20 -> minOf(combined, 1)
                packetLossPct >= 8 -> (combined - 1).coerceAtLeast(1)
                else -> combined
            }
            return when (penalized) {
                4 -> EXCELLENT
                3 -> GOOD
                2 -> FAIR
                1 -> WEAK
                else -> UNKNOWN
            }
        }
    }
}

enum class PeerLinkState { CONNECTED, RECONNECTING, DISCONNECTED }

data class DiscoveredPeer(
    val key: String,
    val name: String,
    val address: String,
    val transport: TransportType,
    val rssiDbm: Int? = null,
    val isPending: Boolean = false,
    val isConnected: Boolean = false,
    val lastSeenMs: Long = 0L
)

data class IncomingInvite(
    val inviteId: String,
    val nodeId: String,
    val deviceName: String,
    val address: String,
    val transport: TransportType,
    val receivedAtMs: Long
)

data class SharedLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracyM: Float,
    val speedMps: Float,
    val timestampMs: Long
)

data class ConnectedPeer(
    val nodeId: String,
    val name: String,
    val address: String,
    val hostIp: String? = null,
    val transport: TransportType,
    val state: PeerLinkState = PeerLinkState.CONNECTED,
    val rssiDbm: Int? = null,
    val rttMs: Int? = null,
    val linkSpeedMbps: Int? = null,
    val packetLossPct: Int = 0,
    val isSharingLocation: Boolean = false,
    val lastKnownLocation: SharedLocation? = null,
    val isSpeaking: Boolean = false,
    val isPttPriority: Boolean = false,
    val reconnectAttempt: Int = 0,
    val lastSeenMs: Long = 0L
) {
    val signalQuality: SignalQuality
        get() = SignalQuality.evaluate(rssiDbm, rttMs, packetLossPct)
}

enum class ChatDelivery { SENT, DELIVERED, QUEUED }

data class ChatMessage(
    val id: String,
    val senderId: String,
    val senderName: String,
    val text: String,
    val timestampMs: Long,
    val outgoing: Boolean,
    val delivery: ChatDelivery = ChatDelivery.SENT
)

enum class VoiceMode { PTT, OPEN_MIC }

data class BlockedPeerEntry(
    val key: String,
    val name: String,
    val blockedAtMs: Long
)

data class RmgSessionSnapshot(
    val localNodeId: String = "",
    val localDisplayName: String = "",
    val isDiscovering: Boolean = false,
    val isGroupHost: Boolean = false,
    val wifiDirectSupported: Boolean = true,
    val bluetoothSupported: Boolean = true,
    val voiceMode: VoiceMode = RmgDefaults.DEFAULT_VOICE_MODE,
    val isMicMuted: Boolean = RmgDefaults.DEFAULT_MIC_MUTED,
    val isPttPressed: Boolean = false,
    val isBluetoothAudioActive: Boolean = false,
    val isBluetoothAudioAvailable: Boolean = false,
    val isSharingLocation: Boolean = RmgDefaults.DEFAULT_LOCATION_SHARING,
    val discoveredPeers: List<DiscoveredPeer> = emptyList(),
    val incomingInvites: List<IncomingInvite> = emptyList(),
    val connectedPeers: List<ConnectedPeer> = emptyList(),
    val messages: List<ChatMessage> = emptyList(),
    val blockedPeers: List<BlockedPeerEntry> = emptyList()
) {
    val activeConnectedCount: Int
        get() = connectedPeers.count { it.state == PeerLinkState.CONNECTED || it.state == PeerLinkState.RECONNECTING }

    val hasActiveSession: Boolean
        get() = isGroupHost || activeConnectedCount > 0 || incomingInvites.isNotEmpty()
}

object RmgDefaults {
    /** Mandatory privacy defaults: microphone and GPS sharing are ALWAYS OFF initially. */
    const val DEFAULT_MIC_MUTED: Boolean = true
    const val DEFAULT_LOCATION_SHARING: Boolean = false
    val DEFAULT_VOICE_MODE: VoiceMode = VoiceMode.PTT

    const val TCP_CONTROL_PORT: Int = 49210
    const val UDP_VOICE_PORT: Int = 49211
    const val PROTOCOL_VERSION: Int = 1
}

/** Battery-aware bounded exponential backoff for automatic peer reconnection. */
object ReconnectBackoff {
    const val MAX_ATTEMPTS = 6
    private val DELAYS_MS = longArrayOf(1_500L, 3_000L, 5_500L, 9_000L, 14_000L, 20_000L)

    fun shouldRetry(attempt: Int, explicitBye: Boolean, blocked: Boolean): Boolean =
        !explicitBye && !blocked && attempt in 0 until MAX_ATTEMPTS

    fun delayMs(attempt: Int): Long {
        if (attempt <= 0) return DELAYS_MS[0]
        return DELAYS_MS[attempt.coerceAtMost(DELAYS_MS.lastIndex)]
    }
}

/**
 * Policy governing whether MainActivity.onStop() stops LocationEngine:
 *  • Outside an active RMG CONNECT session, or when GPS sharing is OFF -> stop GPS normally (preserves exact existing app behaviour).
 *  • During an active RMG CONNECT session with GPS sharing explicitly ON -> keep GPS alive for background location sharing.
 */
object GpsLifecyclePolicy {
    fun shouldKeepGpsOnActivityStop(
        hasActiveSession: Boolean,
        isSharingLocation: Boolean
    ): Boolean = hasActiveSession && isSharingLocation
}

/** 8-wind compass sector index (0=N, 1=NE, 2=E, 3=SE, 4=S, 5=SW, 6=W, 7=NW). */
object CompassSector {
    fun indexFromBearing(bearingDeg: Float): Int {
        var norm = bearingDeg % 360f
        if (norm < 0f) norm += 360f
        return (((norm + 22.5f) / 45f).toInt()) and 7
    }
}
