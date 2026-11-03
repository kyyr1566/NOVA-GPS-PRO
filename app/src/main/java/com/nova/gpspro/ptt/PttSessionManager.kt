package com.nova.gpspro.ptt

/**
 * Pure, thread-safe Walkie-Talkie session state machine & Half-Duplex floor arbitrator.
 * Zero Android UI dependencies so every rule (blue/green/amber states, simultaneous talk prevention,
 * touch cancel release, 10s heartbeat timeout, max floor lease, local per-peer mute) is JVM-testable.
 */

enum class PttButtonVisualState(val hexColor: Int) {
    /** Royal Blue #2457D6 — channel idle, ready to push-to-talk. */
    IDLE_BLUE(0xFF2457D6.toInt()),

    /** Emerald Green #14866D — local user holds the floor and is transmitting. */
    SELF_SPEAKING_GREEN(0xFF14866D.toInt()),

    /** Amber #D99A32 — another participant holds the floor; local transmit is locked out. */
    REMOTE_SPEAKING_AMBER(0xFFD99A32.toInt())
}

enum class ParticipantStatus {
    CONNECTED,
    SPEAKING,
    DISCONNECTED
}

enum class LinkQualityLevel(val bars: Int) {
    EXCELLENT(4),
    GOOD(3),
    FAIR(2),
    WEAK(1),
    MEASURING(0);

    companion object {
        fun evaluate(rttMs: Int?, packetLossPct: Int): LinkQualityLevel {
            if (rttMs == null || rttMs < 0) return MEASURING
            return when {
                rttMs <= 35 && packetLossPct <= 1 -> EXCELLENT
                rttMs <= 85 && packetLossPct <= 4 -> GOOD
                rttMs <= 180 && packetLossPct <= 12 -> FAIR
                else -> WEAK
            }
        }
    }
}

data class PttParticipant(
    val id: String,
    val displayName: String,
    val isHost: Boolean,
    val isSelf: Boolean,
    val status: ParticipantStatus = ParticipantStatus.CONNECTED,
    val rttMs: Int? = null,
    val packetLossPct: Int = 0,
    val locallyMuted: Boolean = false
) {
    val quality: LinkQualityLevel
        get() = if (isSelf) LinkQualityLevel.EXCELLENT else LinkQualityLevel.evaluate(rttMs, packetLossPct)
}

sealed class FloorDecision {
    data class Granted(val speakerId: String, val speakerName: String, val leaseId: Int, val maxDurationMs: Long) : FloorDecision()
    data class Denied(val currentSpeakerId: String, val currentSpeakerName: String) : FloorDecision()
    data object Unauthorized : FloorDecision()
}

class PttSessionManager(
    val localParticipantId: String,
    initialLocalName: String,
    val isHostRole: Boolean
) {
    companion object {
        /** ~10s heartbeat verification window to tolerate brief RF fade before confirming disconnect. */
        const val HEARTBEAT_TIMEOUT_MS: Long = 10_000L
        /** Maximum continuous PTT transmission lease so no device can lock the channel forever. */
        const val MAX_FLOOR_LEASE_MS: Long = 45_000L
        /** Safety release if an active remote speaker stops sending audio without a release packet. */
        const val SPEAKER_AUDIO_WATCHDOG_MS: Long = 3_500L
    }

    private val lock = Any()

    private var localName: String = initialLocalName
    private var nextLeaseSeq: Int = 1

    private var activeSpeakerId: String? = null
    private var activeSpeakerName: String? = null
    private var activeLeaseId: Int = 0
    private var leaseStartedAtMs: Long = 0L
    private var lastSpeakerAudioAtMs: Long = 0L

    private data class MemberRecord(
        val id: String,
        var name: String,
        val isHost: Boolean,
        val isSelf: Boolean,
        val authToken: String,
        var connected: Boolean,
        var lastSeenMs: Long,
        var rttMs: Int? = null,
        var lossPct: Int = 0
    )

    private val members = LinkedHashMap<String, MemberRecord>()
    private val locallyMutedPeerIds = HashSet<String>()

    init {
        val now = System.currentTimeMillis()
        members[localParticipantId] = MemberRecord(
            id = localParticipantId,
            name = initialLocalName,
            isHost = isHostRole,
            isSelf = true,
            authToken = "LOCAL",
            connected = true,
            lastSeenMs = now,
            rttMs = 0,
            lossPct = 0
        )
    }

    fun updateLocalName(name: String) = synchronized(lock) {
        localName = name
        members[localParticipantId]?.name = name
        if (activeSpeakerId == localParticipantId) {
            activeSpeakerName = name
        }
    }

    /** Host registers a newly authenticated & approved participant with its unique session token. */
    fun registerAuthenticatedParticipant(
        participantId: String,
        displayName: String,
        isHost: Boolean,
        authToken: String,
        nowMs: Long
    ) = synchronized(lock) {
        members[participantId] = MemberRecord(
            id = participantId,
            name = displayName,
            isHost = isHost,
            isSelf = participantId == localParticipantId,
            authToken = authToken,
            connected = true,
            lastSeenMs = nowMs
        )
    }

    fun verifyParticipantToken(participantId: String, candidateToken: String): Boolean = synchronized(lock) {
        val rec = members[participantId] ?: return false
        rec.connected && rec.authToken.isNotEmpty() && rec.authToken == candidateToken
    }

    /** Client synchronizes its roster view from the authoritative host RosterSync frame. */
    fun applyAuthoritativeRoster(wireRoster: List<WireParticipant>, nowMs: Long) = synchronized(lock) {
        val incomingIds = wireRoster.map { it.participantId }.toSet()
        val iter = members.entries.iterator()
        while (iter.hasNext()) {
            val e = iter.next()
            if (!e.value.isSelf && e.key !in incomingIds) {
                if (activeSpeakerId == e.key) clearFloorLocked()
                iter.remove()
            }
        }
        for (wp in wireRoster) {
            val existing = members[wp.participantId]
            if (existing != null) {
                existing.name = wp.displayName
                existing.connected = true
                existing.lastSeenMs = nowMs
                if (!existing.isSelf && wp.rttMs >= 0) existing.rttMs = wp.rttMs
                if (!existing.isSelf) existing.lossPct = wp.lossPct
            } else {
                members[wp.participantId] = MemberRecord(
                    id = wp.participantId,
                    name = wp.displayName,
                    isHost = wp.isHost,
                    isSelf = wp.participantId == localParticipantId,
                    authToken = "",
                    connected = true,
                    lastSeenMs = nowMs,
                    rttMs = wp.rttMs.takeIf { it >= 0 },
                    lossPct = wp.lossPct
                )
            }
        }
    }

    fun exportWireRoster(): List<WireParticipant> = synchronized(lock) {
        members.values.filter { it.connected }.map {
            WireParticipant(
                participantId = it.id,
                displayName = it.name,
                isHost = it.isHost,
                rttMs = it.rttMs ?: -1,
                lossPct = it.lossPct
            )
        }
    }

    /**
     * Host floor arbitration (Half-Duplex): strictly one speaker at a time.
     * Simultaneous requests are serialized under [lock]; first wins, second receives [FloorDecision.Denied].
     */
    fun arbitrateFloorRequest(requesterId: String, nowMs: Long): FloorDecision = synchronized(lock) {
        expireStaleFloorIfNeededLocked(nowMs)
        val member = members[requesterId]
        if (member == null || !member.connected) {
            return FloorDecision.Unauthorized
        }
        val curSpeaker = activeSpeakerId
        if (curSpeaker != null && curSpeaker != requesterId) {
            return FloorDecision.Denied(curSpeaker, activeSpeakerName ?: "")
        }
        if (curSpeaker == requesterId) {
            return FloorDecision.Granted(requesterId, member.name, activeLeaseId, MAX_FLOOR_LEASE_MS)
        }
        val lease = nextLeaseSeq++
        activeSpeakerId = requesterId
        activeSpeakerName = member.name
        activeLeaseId = lease
        leaseStartedAtMs = nowMs
        lastSpeakerAudioAtMs = nowMs
        FloorDecision.Granted(requesterId, member.name, lease, MAX_FLOOR_LEASE_MS)
    }

    /** Client applies a FloorGranted / FloorTaken event announced by the Host. */
    fun applyFloorTaken(speakerId: String, speakerName: String, leaseId: Int, nowMs: Long) = synchronized(lock) {
        activeSpeakerId = speakerId
        activeSpeakerName = speakerName.ifBlank { members[speakerId]?.name ?: speakerId }
        activeLeaseId = leaseId
        leaseStartedAtMs = nowMs
        lastSpeakerAudioAtMs = nowMs
    }

    /** Releases floor if held by [speakerId] (matching [leaseId] when > 0). Returns released leaseId or null. */
    fun releaseFloor(speakerId: String, leaseId: Int = 0): Int? = synchronized(lock) {
        if (activeSpeakerId != speakerId) return null
        if (leaseId > 0 && activeLeaseId != leaseId) return null
        val prev = activeLeaseId
        clearFloorLocked()
        prev
    }

    fun applyFloorIdle(leaseId: Int = 0) = synchronized(lock) {
        if (leaseId == 0 || activeLeaseId == leaseId || activeLeaseId == 0) {
            clearFloorLocked()
        }
    }

    fun noteSpeakerAudioPacket(leaseId: Int, nowMs: Long): Boolean = synchronized(lock) {
        if (activeSpeakerId == null || activeLeaseId != leaseId) return false
        lastSpeakerAudioAtMs = nowMs
        true
    }

    fun recordHeartbeat(participantId: String, rttMs: Int?, lossPct: Int?, nowMs: Long) = synchronized(lock) {
        val m = members[participantId] ?: return
        m.lastSeenMs = nowMs
        if (rttMs != null && rttMs >= 0) {
            m.rttMs = if (m.rttMs == null) rttMs else ((m.rttMs!! * 3 + rttMs) / 4)
        }
        if (lossPct != null && lossPct >= 0) {
            m.lossPct = lossPct.coerceIn(0, 100)
        }
    }

    fun removeParticipant(participantId: String): Boolean = synchronized(lock) {
        if (participantId == localParticipantId) return false
        val removed = members.remove(participantId) != null
        locallyMutedPeerIds.remove(participantId)
        if (activeSpeakerId == participantId) {
            clearFloorLocked()
        }
        removed
    }

    data class TimeoutCheckResult(
        val timedOutParticipantIds: List<String>,
        val floorExpiredLeaseId: Int?
    )

    /** Periodic maintenance: checks floor lease cap, silent speaker watchdog, and 10s heartbeat timeouts. */
    fun checkTimeouts(nowMs: Long): TimeoutCheckResult = synchronized(lock) {
        val floorExpired = expireStaleFloorIfNeededLocked(nowMs)
        val timedOut = ArrayList<String>()
        for (m in members.values) {
            if (!m.isSelf && m.connected && nowMs - m.lastSeenMs >= HEARTBEAT_TIMEOUT_MS) {
                timedOut.add(m.id)
            }
        }
        for (id in timedOut) {
            members.remove(id)
            locallyMutedPeerIds.remove(id)
            if (activeSpeakerId == id) clearFloorLocked()
        }
        TimeoutCheckResult(timedOut, floorExpired)
    }

    fun setPeerLocallyMuted(participantId: String, muted: Boolean) = synchronized(lock) {
        if (participantId == localParticipantId) return
        if (muted) locallyMutedPeerIds.add(participantId) else locallyMutedPeerIds.remove(participantId)
    }

    fun togglePeerLocallyMuted(participantId: String): Boolean = synchronized(lock) {
        if (participantId == localParticipantId) return false
        val nowMuted = !locallyMutedPeerIds.contains(participantId)
        if (nowMuted) locallyMutedPeerIds.add(participantId) else locallyMutedPeerIds.remove(participantId)
        nowMuted
    }

    fun isPeerLocallyMuted(participantId: String): Boolean = synchronized(lock) {
        locallyMutedPeerIds.contains(participantId)
    }

    fun isPeerMutedByShortHash(shortHash: Int): Boolean = synchronized(lock) {
        locallyMutedPeerIds.any { PttSecurity.participantShortHash(it) == shortHash }
    }

    fun resolveParticipantIdByShortHash(shortHash: Int): String? = synchronized(lock) {
        members.keys.firstOrNull { PttSecurity.participantShortHash(it) == shortHash }
    }

    fun currentVisualState(): PttButtonVisualState = synchronized(lock) {
        when (activeSpeakerId) {
            null -> PttButtonVisualState.IDLE_BLUE
            localParticipantId -> PttButtonVisualState.SELF_SPEAKING_GREEN
            else -> PttButtonVisualState.REMOTE_SPEAKING_AMBER
        }
    }

    fun currentSpeakerId(): String? = synchronized(lock) { activeSpeakerId }
    fun currentSpeakerName(): String? = synchronized(lock) { activeSpeakerName }
    fun currentLeaseId(): Int = synchronized(lock) { activeLeaseId }

    fun snapshotParticipants(): List<PttParticipant> = synchronized(lock) {
        members.values.map { rec ->
            val st = when {
                !rec.connected -> ParticipantStatus.DISCONNECTED
                activeSpeakerId == rec.id -> ParticipantStatus.SPEAKING
                else -> ParticipantStatus.CONNECTED
            }
            PttParticipant(
                id = rec.id,
                displayName = rec.name,
                isHost = rec.isHost,
                isSelf = rec.isSelf,
                status = st,
                rttMs = rec.rttMs,
                packetLossPct = rec.lossPct,
                locallyMuted = locallyMutedPeerIds.contains(rec.id)
            )
        }
    }

    fun connectedCount(): Int = synchronized(lock) {
        members.values.count { it.connected }
    }

    fun resetAll() = synchronized(lock) {
        clearFloorLocked()
        locallyMutedPeerIds.clear()
        val self = members[localParticipantId]
        members.clear()
        if (self != null) {
            self.lastSeenMs = System.currentTimeMillis()
            members[localParticipantId] = self
        }
    }

    private fun expireStaleFloorIfNeededLocked(nowMs: Long): Int? {
        val spk = activeSpeakerId ?: return null
        val leaseExceeded = nowMs - leaseStartedAtMs >= MAX_FLOOR_LEASE_MS
        val remoteAudioSilent = spk != localParticipantId && (nowMs - lastSpeakerAudioAtMs >= SPEAKER_AUDIO_WATCHDOG_MS)
        return if (leaseExceeded || remoteAudioSilent) {
            val prev = activeLeaseId
            clearFloorLocked()
            prev
        } else null
    }

    private fun clearFloorLocked() {
        activeSpeakerId = null
        activeSpeakerName = null
        activeLeaseId = 0
        leaseStartedAtMs = 0L
        lastSpeakerAudioAtMs = 0L
    }
}
