package com.nova.gpspro.ptt

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

enum class AudioCodecType(val id: Int, val mask: Int) {
    OPUS_16K(1, 0x01),
    ADPCM_16K(2, 0x02);

    companion object {
        fun fromId(id: Int): AudioCodecType = entries.firstOrNull { it.id == id } ?: ADPCM_16K

        /** Negotiates the best mutually supported low-latency codec (Opus preferred when both support it). */
        fun negotiate(hostMask: Int, clientMask: Int): AudioCodecType {
            val common = hostMask and clientMask
            return if ((common and OPUS_16K.mask) != 0) OPUS_16K else ADPCM_16K
        }
    }
}

object PttRejectReason {
    const val PROTOCOL_MISMATCH = 1
    const val AUTH_FAILED = 2
    const val DECLINED_BY_HOST = 3
    const val SESSION_CLOSED = 4
}

data class WireParticipant(
    val participantId: String,
    val displayName: String,
    val isHost: Boolean,
    val rttMs: Int,
    val lossPct: Int
)

object PttSecurity {
    private val rng = SecureRandom()

    fun randomHex(bytes: Int = 16): String {
        val buf = ByteArray(bytes)
        rng.nextBytes(buf)
        return toHex(buf)
    }

    fun shortDeviceFingerprint(deviceId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(deviceId.toByteArray(StandardCharsets.UTF_8))
        return toHex(digest).take(6).uppercase()
    }

    fun participantShortHash(participantId: String): Int {
        var h = -2128831035
        for (ch in participantId) {
            h = h xor ch.code
            h *= 16777619
        }
        return h
    }

    /** Derives a 256-bit session key from the normalized channel code and per-session random salt. */
    fun deriveSessionKey(channelCode: String, sessionSaltHex: String): ByteArray {
        val normalized = PttSettings.normalizeChannelCode(channelCode)
        val salt = ("RMG-PTT-V1|" + sessionSaltHex.lowercase()).toByteArray(StandardCharsets.UTF_8)
        val spec = PBEKeySpec(normalized.toCharArray(), salt, 4096, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    fun deriveMediaKey(sessionKey: ByteArray, sessionId: String): ByteArray =
        hmacSha256(sessionKey, ("MEDIA|$sessionId").toByteArray(StandardCharsets.UTF_8))

    fun computeClientJoinProof(
        sessionKey: ByteArray,
        sessionId: String,
        serverNonce: String,
        clientNonce: String,
        deviceId: String,
        displayName: String
    ): String {
        val msg = "JOIN|$sessionId|$serverNonce|$clientNonce|$deviceId|$displayName"
        return toHex(hmacSha256(sessionKey, msg.toByteArray(StandardCharsets.UTF_8)))
    }

    fun verifyClientJoinProof(
        sessionKey: ByteArray,
        sessionId: String,
        serverNonce: String,
        clientNonce: String,
        deviceId: String,
        displayName: String,
        candidateHex: String
    ): Boolean {
        val expected = fromHex(computeClientJoinProof(sessionKey, sessionId, serverNonce, clientNonce, deviceId, displayName))
        val actual = fromHex(candidateHex)
        return expected.isNotEmpty() && MessageDigest.isEqual(expected, actual)
    }

    fun computeHostAcceptProof(
        sessionKey: ByteArray,
        sessionId: String,
        clientNonce: String,
        serverNonce: String,
        participantToken: String
    ): String {
        val msg = "ACCEPT|$sessionId|$clientNonce|$serverNonce|$participantToken"
        return toHex(hmacSha256(sessionKey, msg.toByteArray(StandardCharsets.UTF_8)))
    }

    fun verifyHostAcceptProof(
        sessionKey: ByteArray,
        sessionId: String,
        clientNonce: String,
        serverNonce: String,
        participantToken: String,
        candidateHex: String
    ): Boolean {
        val expected = fromHex(computeHostAcceptProof(sessionKey, sessionId, clientNonce, serverNonce, participantToken))
        val actual = fromHex(candidateHex)
        return expected.isNotEmpty() && MessageDigest.isEqual(expected, actual)
    }

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    fun computeTag64(mediaKey: ByteArray, header24: ByteArray, payload: ByteArray, payloadOffset: Int, payloadLen: Int): Long {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(mediaKey, "HmacSHA256"))
        mac.update(header24, 0, 24)
        mac.update(payload, payloadOffset, payloadLen)
        val full = mac.doFinal()
        return ByteBuffer.wrap(full, 0, 8).long
    }

    fun toHex(bytes: ByteArray): String {
        val chars = CharArray(bytes.size * 2)
        val hex = "0123456789abcdef"
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            chars[i * 2] = hex[v ushr 4]
            chars[i * 2 + 1] = hex[v and 0x0F]
        }
        return String(chars)
    }

    fun fromHex(hexStr: String): ByteArray {
        val clean = hexStr.trim().lowercase()
        if (clean.length % 2 != 0) return ByteArray(0)
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(clean[i * 2], 16)
            val lo = Character.digit(clean[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return ByteArray(0)
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}

object PttDiagnostics {
    const val TAG = "RMG_PTT"
    private val ring = ArrayDeque<String>(32)

    @Volatile
    var lastSummary: String = ""
        private set

    fun info(stage: String, detail: String) = record("I", stage, detail, null)

    fun warn(stage: String, detail: String, error: Throwable? = null) = record("W", stage, detail, error)

    fun error(stage: String, detail: String, error: Throwable? = null) = record("E", stage, detail, error)

    fun p2pReasonName(reason: Int): String = when (reason) {
        0 -> "P2P_INTERNAL_ERROR(0)"
        1 -> "P2P_UNSUPPORTED(1)"
        2 -> "P2P_BUSY(2)"
        3 -> "NO_SERVICE_REQUESTS(3)"
        else -> "CODE($reason)"
    }

    @Synchronized
    fun snapshot(): List<String> = ring.toList()

    @Synchronized
    fun clear() {
        ring.clear()
        lastSummary = ""
    }

    @Synchronized
    private fun record(level: String, stage: String, detail: String, err: Throwable?) {
        val errSuffix = if (err != null) " [${err.javaClass.simpleName}: ${err.message.orEmpty()}]" else ""
        val entry = "[$level/$stage] $detail$errSuffix"
        if (ring.size >= 32) ring.removeFirst()
        ring.addLast(entry)
        lastSummary = "$stage: $detail$errSuffix"
        // Safe Logcat emission on real Android devices without breaking pure JVM unit tests
        try {
            val logCls = Class.forName("android.util.Log")
            val methodName = when (level) {
                "E" -> "e"
                "W" -> "w"
                else -> "i"
            }
            if (err != null) {
                logCls.getMethod(methodName, String::class.java, String::class.java, Throwable::class.java)
                    .invoke(null, TAG, "[$stage] $detail", err)
            } else {
                logCls.getMethod(methodName, String::class.java, String::class.java)
                    .invoke(null, TAG, "[$stage] $detail")
            }
        } catch (_: Throwable) {
            // Running inside pure JVM unit test environment
        }
    }
}

object PttProtocol {
    const val PROTOCOL_VERSION: Int = 1
    const val DEFAULT_TCP_PORT: Int = 49320
    const val DEFAULT_UDP_PORT: Int = 49321
    /** RFC 6763 / Android WifiP2pDnsSdServiceInfo compliant instance & service type -> rmgptt._rmgptt._tcp.local. */
    const val DNS_SERVICE_INSTANCE: String = "rmgptt"
    const val DNS_SERVICE_TYPE: String = "_rmgptt._tcp"

    fun matchesDnsSdService(instanceOrDomain: String?, regType: String? = null): Boolean {
        val a = instanceOrDomain.orEmpty()
        val b = regType.orEmpty()
        return a.contains("rmgptt", ignoreCase = true) || b.contains("_rmgptt", ignoreCase = true)
    }

    private const val CTRL_MAGIC_0: Byte = 0x57 // 'W'
    private const val CTRL_MAGIC_1: Byte = 0x54 // 'T'
    private const val MAX_CTRL_BYTES: Int = 32_768

    private const val UDP_MAGIC_0: Byte = 0x57 // 'W'
    private const val UDP_MAGIC_1: Byte = 0x50 // 'P'
    const val UDP_HEADER_BYTES: Int = 32

    private const val T_HELLO_CHALLENGE = 1
    private const val T_JOIN_REQUEST = 2
    private const val T_JOIN_PENDING = 3
    private const val T_JOIN_ACCEPTED = 4
    private const val T_JOIN_REJECTED = 5
    private const val T_ROSTER_SYNC = 6
    private const val T_FLOOR_REQUEST = 7
    private const val T_FLOOR_GRANTED = 8
    private const val T_FLOOR_DENIED = 9
    private const val T_FLOOR_TAKEN = 10
    private const val T_FLOOR_RELEASE = 11
    private const val T_FLOOR_IDLE = 12
    private const val T_HEARTBEAT_PING = 13
    private const val T_HEARTBEAT_PONG = 14
    private const val T_SESSION_END = 15
    private const val T_PARTICIPANT_LEAVE = 16

    sealed class ControlFrame {
        data class HelloChallenge(
            val protocolVersion: Int,
            val sessionId: String,
            val sessionSaltHex: String,
            val serverNonce: String,
            val hostId: String,
            val hostName: String,
            val supportedCodecMask: Int
        ) : ControlFrame()

        data class JoinRequest(
            val protocolVersion: Int,
            val sessionId: String,
            val clientNonce: String,
            val deviceId: String,
            val displayName: String,
            val supportedCodecMask: Int,
            val udpListenPort: Int,
            val clientProofHex: String
        ) : ControlFrame()

        data class JoinPendingApproval(val sessionId: String) : ControlFrame()

        data class JoinAccepted(
            val sessionId: String,
            val participantId: String,
            val participantToken: String,
            val negotiatedCodecId: Int,
            val hostUdpPort: Int,
            val hostProofHex: String,
            val roster: List<WireParticipant>
        ) : ControlFrame()

        data class JoinRejected(val reasonCode: Int) : ControlFrame()

        data class RosterSync(
            val negotiatedCodecId: Int,
            val roster: List<WireParticipant>
        ) : ControlFrame()

        data class FloorRequest(val requesterId: String, val participantToken: String) : ControlFrame()

        data class FloorGranted(
            val speakerId: String,
            val speakerName: String,
            val leaseId: Int,
            val maxDurationMs: Long
        ) : ControlFrame()

        data class FloorDenied(val currentSpeakerId: String, val currentSpeakerName: String) : ControlFrame()

        data class FloorTaken(val speakerId: String, val speakerName: String, val leaseId: Int) : ControlFrame()

        data class FloorRelease(val speakerId: String, val leaseId: Int, val participantToken: String) : ControlFrame()

        data class FloorIdle(val previousLeaseId: Int) : ControlFrame()

        data class HeartbeatPing(val senderId: String, val seq: Int, val sentElapsedMs: Long, val lossPct: Int) : ControlFrame()

        data class HeartbeatPong(val senderId: String, val seq: Int, val echoElapsedMs: Long, val lossPct: Int) : ControlFrame()

        data class SessionEnd(val reason: String) : ControlFrame()

        data class ParticipantLeave(val participantId: String, val participantToken: String) : ControlFrame()
    }

    @Throws(IOException::class)
    fun writeControlFrame(out: OutputStream, frame: ControlFrame, macKey: ByteArray? = null) {
        val bodyBuf = ByteArrayOutputStream(128)
        val d = DataOutputStream(bodyBuf)
        val type = when (frame) {
            is ControlFrame.HelloChallenge -> {
                d.writeInt(frame.protocolVersion)
                d.writeUTF(frame.sessionId.take(64))
                d.writeUTF(frame.sessionSaltHex.take(64))
                d.writeUTF(frame.serverNonce.take(64))
                d.writeUTF(frame.hostId.take(64))
                d.writeUTF(frame.hostName.take(40))
                d.writeInt(frame.supportedCodecMask)
                T_HELLO_CHALLENGE
            }
            is ControlFrame.JoinRequest -> {
                d.writeInt(frame.protocolVersion)
                d.writeUTF(frame.sessionId.take(64))
                d.writeUTF(frame.clientNonce.take(64))
                d.writeUTF(frame.deviceId.take(64))
                d.writeUTF(frame.displayName.take(40))
                d.writeInt(frame.supportedCodecMask)
                d.writeInt(frame.udpListenPort)
                d.writeUTF(frame.clientProofHex.take(128))
                T_JOIN_REQUEST
            }
            is ControlFrame.JoinPendingApproval -> {
                d.writeUTF(frame.sessionId.take(64))
                T_JOIN_PENDING
            }
            is ControlFrame.JoinAccepted -> {
                d.writeUTF(frame.sessionId.take(64))
                d.writeUTF(frame.participantId.take(64))
                d.writeUTF(frame.participantToken.take(64))
                d.writeInt(frame.negotiatedCodecId)
                d.writeInt(frame.hostUdpPort)
                d.writeUTF(frame.hostProofHex.take(128))
                writeRoster(d, frame.roster)
                T_JOIN_ACCEPTED
            }
            is ControlFrame.JoinRejected -> {
                d.writeInt(frame.reasonCode)
                T_JOIN_REJECTED
            }
            is ControlFrame.RosterSync -> {
                d.writeInt(frame.negotiatedCodecId)
                writeRoster(d, frame.roster)
                T_ROSTER_SYNC
            }
            is ControlFrame.FloorRequest -> {
                d.writeUTF(frame.requesterId.take(64))
                d.writeUTF(frame.participantToken.take(64))
                T_FLOOR_REQUEST
            }
            is ControlFrame.FloorGranted -> {
                d.writeUTF(frame.speakerId.take(64))
                d.writeUTF(frame.speakerName.take(40))
                d.writeInt(frame.leaseId)
                d.writeLong(frame.maxDurationMs)
                T_FLOOR_GRANTED
            }
            is ControlFrame.FloorDenied -> {
                d.writeUTF(frame.currentSpeakerId.take(64))
                d.writeUTF(frame.currentSpeakerName.take(40))
                T_FLOOR_DENIED
            }
            is ControlFrame.FloorTaken -> {
                d.writeUTF(frame.speakerId.take(64))
                d.writeUTF(frame.speakerName.take(40))
                d.writeInt(frame.leaseId)
                T_FLOOR_TAKEN
            }
            is ControlFrame.FloorRelease -> {
                d.writeUTF(frame.speakerId.take(64))
                d.writeInt(frame.leaseId)
                d.writeUTF(frame.participantToken.take(64))
                T_FLOOR_RELEASE
            }
            is ControlFrame.FloorIdle -> {
                d.writeInt(frame.previousLeaseId)
                T_FLOOR_IDLE
            }
            is ControlFrame.HeartbeatPing -> {
                d.writeUTF(frame.senderId.take(64))
                d.writeInt(frame.seq)
                d.writeLong(frame.sentElapsedMs)
                d.writeInt(frame.lossPct)
                T_HEARTBEAT_PING
            }
            is ControlFrame.HeartbeatPong -> {
                d.writeUTF(frame.senderId.take(64))
                d.writeInt(frame.seq)
                d.writeLong(frame.echoElapsedMs)
                d.writeInt(frame.lossPct)
                T_HEARTBEAT_PONG
            }
            is ControlFrame.SessionEnd -> {
                d.writeUTF(frame.reason.take(64))
                T_SESSION_END
            }
            is ControlFrame.ParticipantLeave -> {
                d.writeUTF(frame.participantId.take(64))
                d.writeUTF(frame.participantToken.take(64))
                T_PARTICIPANT_LEAVE
            }
        }
        d.flush()
        val payload = bodyBuf.toByteArray()
        if (payload.size > MAX_CTRL_BYTES) throw IOException("Control payload too large: ${payload.size}")

        val tag = if (macKey != null) {
            PttSecurity.hmacSha256(macKey, byteArrayOf(type.toByte()) + payload).copyOf(16)
        } else {
            ByteArray(0)
        }

        val wire = DataOutputStream(out)
        synchronized(out) {
            wire.writeByte(CTRL_MAGIC_0.toInt())
            wire.writeByte(CTRL_MAGIC_1.toInt())
            wire.writeByte(type)
            wire.writeByte(tag.size)
            wire.writeInt(payload.size)
            if (tag.isNotEmpty()) wire.write(tag)
            wire.write(payload)
            wire.flush()
        }
    }

    @Throws(IOException::class)
    fun readControlFrame(inp: InputStream, macKey: ByteArray? = null, requireMac: Boolean = false): ControlFrame {
        val din = DataInputStream(inp)
        val m0 = din.readByte()
        val m1 = din.readByte()
        if (m0 != CTRL_MAGIC_0 || m1 != CTRL_MAGIC_1) {
            throw IOException("Invalid PTT control frame magic")
        }
        val type = din.readUnsignedByte()
        val tagLen = din.readUnsignedByte()
        val payloadLen = din.readInt()
        if (tagLen !in setOf(0, 16) || payloadLen < 0 || payloadLen > MAX_CTRL_BYTES) {
            throw IOException("Malformed PTT control header")
        }
        val tag = if (tagLen > 0) ByteArray(tagLen).also { din.readFully(it) } else ByteArray(0)
        val payload = ByteArray(payloadLen).also { din.readFully(it) }

        if (requireMac) {
            if (macKey == null || tag.size != 16) throw IOException("Missing required control MAC")
            val expected = PttSecurity.hmacSha256(macKey, byteArrayOf(type.toByte()) + payload).copyOf(16)
            if (!MessageDigest.isEqual(expected, tag)) throw IOException("Control MAC verification failed")
        } else if (macKey != null && tag.size == 16) {
            val expected = PttSecurity.hmacSha256(macKey, byteArrayOf(type.toByte()) + payload).copyOf(16)
            if (!MessageDigest.isEqual(expected, tag)) throw IOException("Control MAC mismatch")
        }

        val p = DataInputStream(ByteArrayInputStream(payload))
        return when (type) {
            T_HELLO_CHALLENGE -> ControlFrame.HelloChallenge(
                p.readInt(), p.readUTF(), p.readUTF(), p.readUTF(), p.readUTF(), p.readUTF(), p.readInt()
            )
            T_JOIN_REQUEST -> ControlFrame.JoinRequest(
                p.readInt(), p.readUTF(), p.readUTF(), p.readUTF(), p.readUTF(), p.readInt(), p.readInt(), p.readUTF()
            )
            T_JOIN_PENDING -> ControlFrame.JoinPendingApproval(p.readUTF())
            T_JOIN_ACCEPTED -> ControlFrame.JoinAccepted(
                p.readUTF(), p.readUTF(), p.readUTF(), p.readInt(), p.readInt(), p.readUTF(), readRoster(p)
            )
            T_JOIN_REJECTED -> ControlFrame.JoinRejected(p.readInt())
            T_ROSTER_SYNC -> ControlFrame.RosterSync(p.readInt(), readRoster(p))
            T_FLOOR_REQUEST -> ControlFrame.FloorRequest(p.readUTF(), p.readUTF())
            T_FLOOR_GRANTED -> ControlFrame.FloorGranted(p.readUTF(), p.readUTF(), p.readInt(), p.readLong())
            T_FLOOR_DENIED -> ControlFrame.FloorDenied(p.readUTF(), p.readUTF())
            T_FLOOR_TAKEN -> ControlFrame.FloorTaken(p.readUTF(), p.readUTF(), p.readInt())
            T_FLOOR_RELEASE -> ControlFrame.FloorRelease(p.readUTF(), p.readInt(), p.readUTF())
            T_FLOOR_IDLE -> ControlFrame.FloorIdle(p.readInt())
            T_HEARTBEAT_PING -> ControlFrame.HeartbeatPing(p.readUTF(), p.readInt(), p.readLong(), p.readInt())
            T_HEARTBEAT_PONG -> ControlFrame.HeartbeatPong(p.readUTF(), p.readInt(), p.readLong(), p.readInt())
            T_SESSION_END -> ControlFrame.SessionEnd(p.readUTF())
            T_PARTICIPANT_LEAVE -> ControlFrame.ParticipantLeave(p.readUTF(), p.readUTF())
            else -> throw IOException("Unknown PTT control frame type: $type")
        }
    }

    private fun writeRoster(d: DataOutputStream, roster: List<WireParticipant>) {
        val capped = roster.take(16)
        d.writeInt(capped.size)
        for (item in capped) {
            d.writeUTF(item.participantId.take(64))
            d.writeUTF(item.displayName.take(40))
            d.writeBoolean(item.isHost)
            d.writeInt(item.rttMs)
            d.writeInt(item.lossPct)
        }
    }

    private fun readRoster(p: DataInputStream): List<WireParticipant> {
        val n = p.readInt().coerceIn(0, 16)
        val list = ArrayList<WireParticipant>(n)
        repeat(n) {
            list.add(WireParticipant(p.readUTF(), p.readUTF(), p.readBoolean(), p.readInt(), p.readInt()))
        }
        return list
    }

    // ==================== Authenticated UDP Voice Packet ====================

    data class UdpVoicePacket(
        val codec: AudioCodecType,
        val endOfBurst: Boolean,
        val leaseId: Int,
        val sequence: Int,
        val timestampMs: Long,
        val senderShortHash: Int,
        val payload: ByteArray
    )

    fun encodeUdpVoicePacket(
        mediaKey: ByteArray,
        codec: AudioCodecType,
        endOfBurst: Boolean,
        leaseId: Int,
        sequence: Int,
        timestampMs: Long,
        senderShortHash: Int,
        payload: ByteArray,
        payloadLen: Int = payload.size
    ): ByteArray {
        val safeLen = payloadLen.coerceIn(0, 1400)
        val out = ByteArray(UDP_HEADER_BYTES + safeLen)
        val bb = ByteBuffer.wrap(out)
        bb.put(UDP_MAGIC_0)
        bb.put(UDP_MAGIC_1)
        bb.put(codec.id.toByte())
        bb.put((if (endOfBurst) 0x01 else 0x00).toByte())
        bb.putInt(leaseId)
        bb.putInt(sequence)
        bb.putLong(timestampMs)
        bb.putInt(senderShortHash)
        val tag64 = PttSecurity.computeUdpTag64Compat(mediaKey, out, payload, safeLen)
        bb.putLong(tag64)
        System.arraycopy(payload, 0, out, UDP_HEADER_BYTES, safeLen)
        return out
    }

    fun decodeUdpVoicePacket(
        mediaKey: ByteArray,
        datagram: ByteArray,
        length: Int = datagram.size
    ): UdpVoicePacket? {
        if (length < UDP_HEADER_BYTES || length > 1500) return null
        if (datagram[0] != UDP_MAGIC_0 || datagram[1] != UDP_MAGIC_1) return null
        val bb = ByteBuffer.wrap(datagram, 0, length)
        bb.get()
        bb.get()
        val codec = AudioCodecType.fromId(bb.get().toInt() and 0xFF)
        val flags = bb.get().toInt() and 0xFF
        val leaseId = bb.int
        val seq = bb.int
        val ts = bb.long
        val senderHash = bb.int
        val receivedTag = bb.long

        val payloadLen = length - UDP_HEADER_BYTES
        val expectedTag = PttSecurity.computeTag64(mediaKey, datagram, datagram, UDP_HEADER_BYTES, payloadLen)
        if (receivedTag != expectedTag) return null

        val payload = ByteArray(payloadLen)
        System.arraycopy(datagram, UDP_HEADER_BYTES, payload, 0, payloadLen)
        return UdpVoicePacket(
            codec = codec,
            endOfBurst = (flags and 0x01) != 0,
            leaseId = leaseId,
            sequence = seq,
            timestampMs = ts,
            senderShortHash = senderHash,
            payload = payload
        )
    }

    private fun PttSecurity.computeUdpTag64Compat(
        mediaKey: ByteArray,
        headerBuf: ByteArray,
        payload: ByteArray,
        payloadLen: Int
    ): Long = computeTag64(mediaKey, headerBuf, payload, 0, payloadLen)
}
