package com.nova.gpspro.connect

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.SystemClock
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Low-latency, battery-efficient group voice engine for RMG CONNECT.
 *  • Microphone is OFF/MUTED by default; AudioRecord opens ONLY while unmuted in Open-Mic mode or
 *    while the PTT button is actively held.
 *  • Hardware AEC (Echo Canceler), NS (Noise Suppressor), and AGC + software VAD noise gate.
 *  • Multi-speaker real-time mixer over Wi-Fi Direct UDP with PTT priority ducking.
 *  • Safe Bluetooth audio routing ONLY when a supported & stable BT headset/link is present.
 */
class RmgVoiceEngine(
    private val ctx: Context,
    private val localSenderHash: Long,
    private val wifiTargetsProvider: () -> List<InetAddress>,
    private val btFallbackSender: ((ByteArray) -> Boolean)?,
    private val onLocalSpeakingChanged: (speaking: Boolean, pttPriority: Boolean) -> Unit,
    private val onRemoteSpeakingUpdate: (senderHash: Long, speaking: Boolean, pttPriority: Boolean, packetLossPct: Int) -> Unit,
    private val onBluetoothAudioStateChanged: (active: Boolean, available: Boolean) -> Unit
) {

    private val audioManager: AudioManager? = ctx.getSystemService(AudioManager::class.java)

    private val sessionRunning = AtomicBoolean(false)
    private val captureActive = AtomicBoolean(false)
    private val micMuted = AtomicBoolean(RmgDefaults.DEFAULT_MIC_MUTED)
    private val pttPressed = AtomicBoolean(false)
    @Volatile private var voiceMode: VoiceMode = RmgDefaults.DEFAULT_VOICE_MODE
    @Volatile private var btAudioActive = false

    private var udpSocket: DatagramSocket? = null
    private var rxThread: Thread? = null
    private var playThread: Thread? = null
    private var captureThread: Thread? = null

    private val txSeq = AtomicInteger(0)

    private class JitterStream(val senderHash: Long) {
        private val ring = arrayOfNulls<ShortArray>(8)
        private val prio = BooleanArray(8)
        private var writeIdx = 0
        private var readIdx = 0
        private var count = 0
        var lastPacketElapsedMs = 0L
        var lastPriority = false
        var expectedSeq = -1
        var receivedCount = 0
        var lostCount = 0

        @Synchronized
        fun push(seq: Int, samples: ShortArray, isPriority: Boolean, nowMs: Long) {
            if (expectedSeq != -1) {
                val gap = ((seq - expectedSeq) and 0xFFFF)
                if (gap in 1..64) lostCount += gap
            }
            expectedSeq = (seq + 1) and 0xFFFF
            receivedCount++
            lastPacketElapsedMs = nowMs
            lastPriority = isPriority

            ring[writeIdx] = samples
            prio[writeIdx] = isPriority
            writeIdx = (writeIdx + 1) and 7
            if (count < 8) {
                count++
            } else {
                // Overflow: drop oldest frame to keep latency crisp (<120ms)
                readIdx = (readIdx + 1) and 7
            }
        }

        @Synchronized
        fun pop(): Pair<ShortArray, Boolean>? {
            if (count == 0) return null
            val s = ring[readIdx] ?: return null
            val p = prio[readIdx]
            ring[readIdx] = null
            readIdx = (readIdx + 1) and 7
            count--
            return s to p
        }

        @Synchronized
        fun lossPercent(): Int {
            val total = receivedCount + lostCount
            if (total < 10) return 0
            val pct = (lostCount * 100) / total
            if (total > 200) {
                receivedCount /= 2
                lostCount /= 2
            }
            return pct.coerceIn(0, 100)
        }
    }

    private val remoteStreams = ConcurrentHashMap<Long, JitterStream>()
    private var scoReceiverRegistered = false

    private val audioDeviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED -> {
                    val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_DISCONNECTED)
                    if (state == AudioManager.SCO_AUDIO_STATE_DISCONNECTED || state == AudioManager.SCO_AUDIO_STATE_ERROR) {
                        if (btAudioActive && Build.VERSION.SDK_INT < 31) {
                            disableBluetoothAudioInternal()
                        }
                    }
                    notifyBtState()
                }
                AudioManager.ACTION_AUDIO_BECOMING_NOISY -> {
                    if (btAudioActive) disableBluetoothAudioInternal()
                    notifyBtState()
                }
            }
        }
    }

    fun startSession() {
        if (!sessionRunning.compareAndSet(false, true)) return
        registerAudioReceiver()
        try {
            val sock = DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = false
                trafficClass = 0xB8 // Expedited Forwarding (VoIP QoS DSCP)
                bind(java.net.InetSocketAddress(RmgDefaults.UDP_VOICE_PORT))
            }
            udpSocket = sock
            startReceiverLoop(sock)
        } catch (_: Exception) {
            // Port busy or network restricted — fallback handled safely
        }
        startPlaybackMixerLoop()
        notifyBtState()
        syncCaptureState()
    }

    fun stopSession() {
        if (!sessionRunning.compareAndSet(true, false)) return
        pttPressed.set(false)
        micMuted.set(true)
        stopCaptureLoop()
        disableBluetoothAudioInternal()
        unregisterAudioReceiver()
        try { udpSocket?.close() } catch (_: Exception) {}
        udpSocket = null
        rxThread?.interrupt(); rxThread = null
        playThread?.interrupt(); playThread = null
        remoteStreams.clear()
        try {
            audioManager?.mode = AudioManager.MODE_NORMAL
        } catch (_: Exception) {}
    }

    fun setVoiceMode(mode: VoiceMode) {
        voiceMode = mode
        if (mode == VoiceMode.PTT) {
            pttPressed.set(false)
            micMuted.set(true)
        }
        syncCaptureState()
    }

    fun setMicMuted(muted: Boolean) {
        micMuted.set(muted)
        if (muted) pttPressed.set(false)
        syncCaptureState()
    }

    fun setPttPressed(pressed: Boolean): Boolean {
        if (!RmgPermissions.hasRecordAudioPermission(ctx)) {
            pttPressed.set(false)
            syncCaptureState()
            return false
        }
        if (voiceMode != VoiceMode.PTT) voiceMode = VoiceMode.PTT
        pttPressed.set(pressed)
        micMuted.set(!pressed)
        syncCaptureState()
        return true
    }

    fun isMicMuted(): Boolean = micMuted.get()
    fun isPttPressed(): Boolean = pttPressed.get()
    fun currentMode(): VoiceMode = voiceMode

    /** Feeds an incoming compact voice frame received over a stable 1-to-1 Bluetooth RFCOMM link. */
    fun onIncomingBtVoiceFrame(raw: ByteArray) {
        if (!sessionRunning.get()) return
        val decoded = RmgAudioCodec.decodeFrame(raw, raw.size) ?: return
        if (decoded.senderHash == localSenderHash) return
        val now = SystemClock.elapsedRealtime()
        val stream = remoteStreams.getOrPut(decoded.senderHash) { JitterStream(decoded.senderHash) }
        stream.push(decoded.seq, decoded.samples16k, decoded.pttPriority, now)
    }

    // ---------------- Bluetooth Audio Routing (only when supported & stable) ----------------

    fun isBluetoothAudioSupportedAndConnected(): Boolean {
        val am = audioManager ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= 31) {
                am.availableCommunicationDevices.any {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                }
            } else {
                am.isBluetoothScoAvailableOffCall && am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    fun setBluetoothAudioEnabled(enable: Boolean): Boolean {
        val am = audioManager ?: return false
        if (!enable) {
            disableBluetoothAudioInternal()
            notifyBtState()
            return true
        }
        if (!isBluetoothAudioSupportedAndConnected()) {
            btAudioActive = false
            notifyBtState()
            return false
        }
        return try {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= 31) {
                val dev = am.availableCommunicationDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                } ?: return false
                val ok = am.setCommunicationDevice(dev)
                btAudioActive = ok
                notifyBtState()
                ok
            } else {
                @Suppress("DEPRECATION")
                am.startBluetoothSco()
                @Suppress("DEPRECATION")
                am.isBluetoothScoOn = true
                btAudioActive = true
                notifyBtState()
                true
            }
        } catch (_: Exception) {
            btAudioActive = false
            notifyBtState()
            false
        }
    }

    private fun disableBluetoothAudioInternal() {
        val am = audioManager ?: return
        btAudioActive = false
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                am.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                am.isBluetoothScoOn = false
                @Suppress("DEPRECATION")
                am.stopBluetoothSco()
            }
        } catch (_: Exception) {}
    }

    private fun notifyBtState() {
        val avail = isBluetoothAudioSupportedAndConnected()
        if (!avail && btAudioActive) btAudioActive = false
        onBluetoothAudioStateChanged(btAudioActive, avail)
    }

    private fun registerAudioReceiver() {
        if (scoReceiverRegistered) return
        try {
            val f = IntentFilter().apply {
                addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
                addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            }
            ctx.registerReceiver(audioDeviceReceiver, f, Context.RECEIVER_NOT_EXPORTED)
            scoReceiverRegistered = true
        } catch (_: Exception) {}
    }

    private fun unregisterAudioReceiver() {
        if (!scoReceiverRegistered) return
        try { ctx.unregisterReceiver(audioDeviceReceiver) } catch (_: Exception) {}
        scoReceiverRegistered = false
    }

    // ---------------- Capture + Hardware AEC / NS / AGC ----------------

    private fun shouldCaptureNow(): Boolean {
        if (!sessionRunning.get()) return false
        if (!RmgPermissions.hasRecordAudioPermission(ctx)) return false
        return when (voiceMode) {
            VoiceMode.PTT -> pttPressed.get() && !micMuted.get()
            VoiceMode.OPEN_MIC -> !micMuted.get()
        }
    }

    private fun syncCaptureState() {
        if (shouldCaptureNow()) startCaptureLoop() else stopCaptureLoop()
    }

    @SuppressLint("MissingPermission")
    private fun startCaptureLoop() {
        if (!captureActive.compareAndSet(false, true)) return
        val thread = Thread({
            var recorder: AudioRecord? = null
            var aec: AcousticEchoCanceler? = null
            var ns: NoiseSuppressor? = null
            var agc: AutomaticGainControl? = null
            var announcedSpeaking = false

            try {
                audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
                val minBuf = AudioRecord.getMinBufferSize(
                    RmgAudioCodec.SAMPLE_RATE_HZ,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(RmgAudioCodec.FRAME_SAMPLES * 4)

                recorder = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    RmgAudioCodec.SAMPLE_RATE_HZ,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf * 2
                )
                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    captureActive.set(false)
                    return@Thread
                }

                val sessionId = recorder.audioSessionId
                if (AcousticEchoCanceler.isAvailable()) {
                    aec = try { AcousticEchoCanceler.create(sessionId)?.apply { enabled = true } } catch (_: Exception) { null }
                }
                if (NoiseSuppressor.isAvailable()) {
                    ns = try { NoiseSuppressor.create(sessionId)?.apply { enabled = true } } catch (_: Exception) { null }
                }
                if (AutomaticGainControl.isAvailable()) {
                    agc = try { AutomaticGainControl.create(sessionId)?.apply { enabled = true } } catch (_: Exception) { null }
                }

                recorder.startRecording()
                val pcm = ShortArray(RmgAudioCodec.FRAME_SAMPLES)
                var hangoverFrames = 0

                while (captureActive.get() && sessionRunning.get() && !Thread.currentThread().isInterrupted) {
                    val read = recorder.read(pcm, 0, pcm.size)
                    if (read <= 0) continue

                    val isPtt = voiceMode == VoiceMode.PTT && pttPressed.get()
                    val voiceDetected = RmgAudioCodec.processCaptureFrame(pcm, pttMode = isPtt)
                    if (voiceDetected || isPtt) {
                        hangoverFrames = if (isPtt) 1 else 6 // 120ms smooth tail in Open-Mic mode
                    } else if (hangoverFrames > 0) {
                        hangoverFrames--
                    }

                    val transmitNow = isPtt || hangoverFrames > 0
                    if (transmitNow != announcedSpeaking) {
                        announcedSpeaking = transmitNow
                        onLocalSpeakingChanged(transmitNow, isPtt)
                    }

                    if (!transmitNow) continue

                    val seq = txSeq.getAndIncrement() and 0xFFFF
                    val wifiTargets = wifiTargetsProvider()
                    val sock = udpSocket
                    if (wifiTargets.isNotEmpty() && sock != null && !sock.isClosed) {
                        val payload = RmgAudioCodec.encodePcmFrame(localSenderHash, seq, isPtt, pcm, read)
                        for (addr in wifiTargets) {
                            try {
                                sock.send(DatagramPacket(payload, payload.size, addr, RmgDefaults.UDP_VOICE_PORT))
                            } catch (_: Exception) {}
                        }
                    } else if (isPtt && btFallbackSender != null) {
                        // Safe 1-to-1 Bluetooth audio fallback ONLY when Wi-Fi Direct is absent and link is stable
                        val compact = RmgAudioCodec.encodeCompactBtFrame(localSenderHash, seq, true, pcm)
                        btFallbackSender.invoke(compact)
                    }
                }
            } catch (_: Exception) {
            } finally {
                if (announcedSpeaking) onLocalSpeakingChanged(false, false)
                try { aec?.release() } catch (_: Exception) {}
                try { ns?.release() } catch (_: Exception) {}
                try { agc?.release() } catch (_: Exception) {}
                try { recorder?.stop() } catch (_: Exception) {}
                try { recorder?.release() } catch (_: Exception) {}
                captureActive.set(false)
            }
        }, "rmg-voice-tx").apply {
            priority = Thread.MAX_PRIORITY - 1
            isDaemon = true
        }
        captureThread = thread
        thread.start()
    }

    private fun stopCaptureLoop() {
        captureActive.set(false)
        captureThread?.interrupt()
        captureThread = null
    }

    // ---------------- Multi-Speaker UDP Receiver & Playback Mixer ----------------

    private fun startReceiverLoop(sock: DatagramSocket) {
        val thread = Thread({
            val buf = ByteArray(1024)
            val pkt = DatagramPacket(buf, buf.size)
            while (sessionRunning.get() && !sock.isClosed && !Thread.currentThread().isInterrupted) {
                try {
                    sock.receive(pkt)
                    val decoded = RmgAudioCodec.decodeFrame(pkt.data, pkt.length) ?: continue
                    if (decoded.senderHash == localSenderHash) continue
                    val now = SystemClock.elapsedRealtime()
                    val stream = remoteStreams.getOrPut(decoded.senderHash) { JitterStream(decoded.senderHash) }
                    stream.push(decoded.seq, decoded.samples16k, decoded.pttPriority, now)
                } catch (_: Exception) {
                    if (!sessionRunning.get() || sock.isClosed) break
                }
            }
        }, "rmg-voice-rx").apply {
            priority = Thread.MAX_PRIORITY - 1
            isDaemon = true
        }
        rxThread = thread
        thread.start()
    }

    private fun startPlaybackMixerLoop() {
        val thread = Thread({
            var track: AudioTrack? = null
            val mixedOut = ShortArray(RmgAudioCodec.FRAME_SAMPLES)
            val activeFrames = ArrayList<Pair<ShortArray, Boolean>>(6)
            val speakingNotified = HashMap<Long, Boolean>()

            try {
                val minOutBuf = AudioTrack.getMinBufferSize(
                    RmgAudioCodec.SAMPLE_RATE_HZ,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(RmgAudioCodec.FRAME_SAMPLES * 4)

                track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(RmgAudioCodec.SAMPLE_RATE_HZ)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(minOutBuf * 2)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build()

                var trackPlaying = false

                while (sessionRunning.get() && !Thread.currentThread().isInterrupted) {
                    val loopStart = SystemClock.elapsedRealtime()
                    activeFrames.clear()

                    val iter = remoteStreams.entries.iterator()
                    while (iter.hasNext()) {
                        val (hash, js) = iter.next()
                        val popped = js.pop()
                        val alive = (loopStart - js.lastPacketElapsedMs) < 320L
                        if (popped != null) {
                            activeFrames.add(popped)
                        }
                        val wasSpeaking = speakingNotified[hash] == true
                        if (alive != wasSpeaking) {
                            speakingNotified[hash] = alive
                            onRemoteSpeakingUpdate(hash, alive, js.lastPriority, js.lossPercent())
                        }
                        if (loopStart - js.lastPacketElapsedMs > 15_000L) {
                            iter.remove()
                            speakingNotified.remove(hash)
                        }
                    }

                    if (activeFrames.isNotEmpty()) {
                        if (!trackPlaying && track.state == AudioTrack.STATE_INITIALIZED) {
                            if (!btAudioActive) {
                                try {
                                    audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
                                    @Suppress("DEPRECATION")
                                    audioManager?.isSpeakerphoneOn = true
                                } catch (_: Exception) {}
                            }
                            track.play()
                            trackPlaying = true
                        }
                        RmgAudioCodec.mixStreams(activeFrames, mixedOut)
                        track.write(mixedOut, 0, mixedOut.size)
                    } else {
                        if (trackPlaying) {
                            try { track.pause(); track.flush() } catch (_: Exception) {}
                            trackPlaying = false
                        }
                        Thread.sleep(20L)
                    }
                }
            } catch (_: InterruptedException) {
            } catch (_: Exception) {
            } finally {
                try { track?.stop() } catch (_: Exception) {}
                try { track?.release() } catch (_: Exception) {}
            }
        }, "rmg-voice-mix").apply {
            priority = Thread.MAX_PRIORITY - 1
            isDaemon = true
        }
        playThread = thread
        thread.start()
    }
}
