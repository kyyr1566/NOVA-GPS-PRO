package com.nova.gpspro.ptt

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.TreeMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Self-contained packet-independent IMA-ADPCM 16kHz codec (4:1 compression -> 64 kbps, 164 bytes / 20ms frame).
 * Each packet embeds its initial predictor & step index so packet loss never corrupts subsequent frames.
 */
object ImaAdpcmCodec {
    private val INDEX_TABLE = intArrayOf(
        -1, -1, -1, -1, 2, 4, 6, 8,
        -1, -1, -1, -1, 2, 4, 6, 8
    )

    private val STEP_TABLE = intArrayOf(
        7, 8, 9, 10, 11, 12, 13, 14, 16, 17,
        19, 21, 23, 25, 28, 31, 34, 37, 41, 45,
        50, 55, 60, 66, 73, 80, 88, 97, 107, 118,
        130, 143, 157, 173, 190, 209, 230, 253, 279, 307,
        337, 371, 408, 449, 494, 544, 598, 658, 724, 796,
        876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066,
        2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358,
        5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899,
        15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767
    )

    class State(var predictor: Int = 0, var stepIndex: Int = 0)

    fun encodeFrame(pcm: ShortArray, state: State = State()): ByteArray {
        if (pcm.isEmpty()) return ByteArray(0)
        var pred = pcm[0].toInt()
        var idx = state.stepIndex.coerceIn(0, 88)
        val packedBytes = (pcm.size + 1) / 2
        val out = ByteArray(4 + packedBytes)
        out[0] = ((pred shr 8) and 0xFF).toByte()
        out[1] = (pred and 0xFF).toByte()
        out[2] = idx.toByte()
        out[3] = ((pcm.size shr 4) and 0xFF).toByte()

        var outPos = 4
        var highNibble = true
        var currentByte = 0

        for (i in pcm.indices) {
            val sample = pcm[i].toInt()
            val step = STEP_TABLE[idx]
            var diff = sample - pred
            var nibble = 0
            if (diff < 0) {
                nibble = 8
                diff = -diff
            }
            var vpdiff = step shr 3
            if (diff >= step) {
                nibble = nibble or 4
                diff -= step
                vpdiff += step
            }
            if (diff >= (step shr 1)) {
                nibble = nibble or 2
                diff -= (step shr 1)
                vpdiff += (step shr 1)
            }
            if (diff >= (step shr 2)) {
                nibble = nibble or 1
                vpdiff += (step shr 2)
            }
            pred = if ((nibble and 8) != 0) pred - vpdiff else pred + vpdiff
            pred = pred.coerceIn(-32768, 32767)
            idx = (idx + INDEX_TABLE[nibble]).coerceIn(0, 88)

            if (highNibble) {
                currentByte = (nibble and 0x0F) shl 4
                highNibble = false
            } else {
                currentByte = currentByte or (nibble and 0x0F)
                out[outPos++] = currentByte.toByte()
                highNibble = true
            }
        }
        if (!highNibble && outPos < out.size) {
            out[outPos] = currentByte.toByte()
        }
        state.predictor = pred
        state.stepIndex = idx
        return out
    }

    fun decodeFrame(encoded: ByteArray, expectedSamples: Int = AudioEngine.FRAME_SAMPLES): ShortArray {
        if (encoded.size < 4) return ShortArray(0)
        var pred = ((encoded[0].toInt() shl 8) or (encoded[1].toInt() and 0xFF)).toShort().toInt()
        var idx = (encoded[2].toInt() and 0xFF).coerceIn(0, 88)
        val maxPossible = (encoded.size - 4) * 2
        val sampleCount = expectedSamples.coerceAtMost(maxPossible)
        val out = ShortArray(sampleCount)

        var inPos = 4
        var highNibble = true
        var packed = 0

        for (i in 0 until sampleCount) {
            val nibble = if (highNibble) {
                packed = encoded[inPos++].toInt() and 0xFF
                highNibble = false
                (packed ushr 4) and 0x0F
            } else {
                highNibble = true
                packed and 0x0F
            }
            val step = STEP_TABLE[idx]
            var vpdiff = step shr 3
            if ((nibble and 4) != 0) vpdiff += step
            if ((nibble and 2) != 0) vpdiff += (step shr 1)
            if ((nibble and 1) != 0) vpdiff += (step shr 2)
            pred = if ((nibble and 8) != 0) pred - vpdiff else pred + vpdiff
            pred = pred.coerceIn(-32768, 32767)
            idx = (idx + INDEX_TABLE[nibble]).coerceIn(0, 88)
            out[i] = pred.toShort()
        }
        return out
    }
}

/**
 * Pure Kotlin adaptive jitter buffer with RFC-3550 inter-arrival jitter estimation,
 * sequence reordering, sliding-window loss measurement, and soft Packet Loss Concealment (PLC).
 */
class AdaptiveJitterBuffer(
    private val frameSamples: Int = AudioEngine.FRAME_SAMPLES,
    private val frameDurationMs: Int = 20
) {
    private val lock = Any()
    private val frames = TreeMap<Int, ShortArray>()
    private var primed = false
    private var nextExpectedSeq = 0
    private var targetDepthFrames = 2

    private var lastArrivalMs = -1L
    private var lastPacketTsMs = -1L
    private var jitterEstimateMs = 10.0

    private var lastGoodFrame: ShortArray? = null
    private var consecutiveConcealed = 0
    private var duplicateOrLateCount = 0

    // Sliding window of 50 sequence slots for real packet loss %
    private val windowHistory = BooleanArray(50) { true }
    private var windowCursor = 0
    private var windowSamplesCount = 0

    fun reset() = synchronized(lock) {
        frames.clear()
        primed = false
        nextExpectedSeq = 0
        targetDepthFrames = 2
        lastArrivalMs = -1L
        lastPacketTsMs = -1L
        jitterEstimateMs = 10.0
        lastGoodFrame = null
        consecutiveConcealed = 0
        duplicateOrLateCount = 0
        windowHistory.fill(true)
        windowCursor = 0
        windowSamplesCount = 0
    }

    fun push(sequence: Int, packetTimestampMs: Long, arrivalElapsedMs: Long, pcm: ShortArray) = synchronized(lock) {
        if (pcm.isEmpty()) return
        if (primed && sequence < nextExpectedSeq) {
            duplicateOrLateCount++
            return
        }
        if (frames.containsKey(sequence)) {
            duplicateOrLateCount++
            return
        }
        if (lastArrivalMs >= 0L && lastPacketTsMs >= 0L) {
            val transitDelta = (arrivalElapsedMs - lastArrivalMs) - (packetTimestampMs - lastPacketTsMs)
            val d = abs(transitDelta.toDouble())
            jitterEstimateMs += (d - jitterEstimateMs) / 16.0
            targetDepthFrames = when {
                jitterEstimateMs < 18.0 -> 2
                jitterEstimateMs < 38.0 -> 3
                jitterEstimateMs < 65.0 -> 4
                else -> 6
            }
        }
        lastArrivalMs = arrivalElapsedMs
        lastPacketTsMs = packetTimestampMs

        if (!primed) {
            frames[sequence] = pcm
            if (frames.size >= targetDepthFrames) {
                primed = true
                nextExpectedSeq = frames.firstKey()
            }
            return
        }

        frames[sequence] = pcm
        // Bound latency if queue grows excessively during CPU spike
        while (frames.size > 12) {
            val dropped = frames.pollFirstEntry()
            if (dropped != null && dropped.key >= nextExpectedSeq) {
                nextExpectedSeq = dropped.key + 1
            }
        }
    }

    fun duplicateOrLateDropsCount(): Int = synchronized(lock) { duplicateOrLateCount }

    /** Pulls next 20ms frame for AudioTrack playout, applying smooth PLC on single/double packet loss. */
    fun pollNextFrame(): ShortArray? = synchronized(lock) {
        if (!primed) {
            if (frames.isNotEmpty() && frames.size >= targetDepthFrames) {
                primed = true
                nextExpectedSeq = frames.firstKey()
            } else {
                return null
            }
        }

        val exact = frames.remove(nextExpectedSeq)
        if (exact != null) {
            nextExpectedSeq++
            consecutiveConcealed = 0
            lastGoodFrame = exact
            recordOutcomeLocked(received = true)
            return exact
        }

        if (frames.isNotEmpty()) {
            // Packet at nextExpectedSeq was lost while newer packets arrived -> conceal smoothly
            nextExpectedSeq++
            recordOutcomeLocked(received = false)
            return concealFrameLocked()
        }

        // Queue temporarily drained -> allow up to 2 soft-concealed tail frames, then re-prime cleanly
        if (consecutiveConcealed < 2 && lastGoodFrame != null) {
            return concealFrameLocked()
        }
        primed = false
        lastGoodFrame = null
        consecutiveConcealed = 0
        return null
    }

    fun measuredLossPercent(): Int = synchronized(lock) {
        if (windowSamplesCount < 5) return 0
        val count = windowSamplesCount.coerceAtMost(windowHistory.size)
        var lost = 0
        for (i in 0 until count) {
            if (!windowHistory[i]) lost++
        }
        ((lost * 100.0) / count).roundToInt().coerceIn(0, 100)
    }

    fun currentTargetDepthFrames(): Int = synchronized(lock) { targetDepthFrames }

    private fun recordOutcomeLocked(received: Boolean) {
        windowHistory[windowCursor] = received
        windowCursor = (windowCursor + 1) % windowHistory.size
        if (windowSamplesCount < windowHistory.size) windowSamplesCount++
    }

    private fun concealFrameLocked(): ShortArray {
        consecutiveConcealed++
        val prev = lastGoodFrame ?: return ShortArray(frameSamples)
        val atten = if (consecutiveConcealed == 1) 0.68f else 0.35f
        val concealed = ShortArray(prev.size) { i ->
            (prev[i] * atten).toInt().toShort()
        }
        lastGoodFrame = concealed
        return concealed
    }
}

/** Synthesizes crisp, pleasant Walkie-Talkie start & Roger Beep tones with anti-click envelopes. */
object RogerBeepSynthesizer {
    fun generateStartChirp(sampleRateHz: Int = AudioEngine.SAMPLE_RATE_HZ): ShortArray =
        dualTone(sampleRateHz, freq1Hz = 980.0, ms1 = 30, freq2Hz = 1260.0, ms2 = 38, amplitude = 0.22)

    fun generateRogerBeep(sampleRateHz: Int = AudioEngine.SAMPLE_RATE_HZ): ShortArray =
        dualTone(sampleRateHz, freq1Hz = 1280.0, ms1 = 38, freq2Hz = 960.0, ms2 = 46, amplitude = 0.24)

    private fun dualTone(
        sampleRateHz: Int,
        freq1Hz: Double,
        ms1: Int,
        freq2Hz: Double,
        ms2: Int,
        amplitude: Double
    ): ShortArray {
        val n1 = (sampleRateHz * ms1) / 1000
        val n2 = (sampleRateHz * ms2) / 1000
        val total = n1 + n2
        val out = ShortArray(total)
        val fadeSamples = ((sampleRateHz * 6) / 1000).coerceAtLeast(1)
        var phase = 0.0
        for (i in 0 until total) {
            val freq = if (i < n1) freq1Hz else freq2Hz
            phase += (2.0 * PI * freq) / sampleRateHz
            val env = when {
                i < fadeSamples -> i.toDouble() / fadeSamples
                i > total - fadeSamples -> (total - i).toDouble() / fadeSamples
                else -> 1.0
            }
            out[i] = (sin(phase) * amplitude * env * 32767.0).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }
}

/**
 * Hardware-accelerated Opus wrapper via Android's native [MediaCodec] ("audio/opus") when supported
 * by both endpoints, with automatic capability probing on Android 11–16.
 */
object OpusMediaCodecHelper {
    private const val MIME_OPUS = MediaFormat.MIMETYPE_AUDIO_OPUS

    fun isOpusCodecPairSupported(): Boolean = try {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        var hasEnc = false
        var hasDec = false
        for (info in list) {
            if (!info.types.any { it.equals(MIME_OPUS, ignoreCase = true) }) continue
            if (info.isEncoder) hasEnc = true else hasDec = true
        }
        hasEnc && hasDec
    } catch (_: Throwable) {
        false
    }

    /** RFC 7845 19-byte OpusHead CSD-0 header for 16kHz mono 0-preskip low-latency stream. */
    fun buildOpusHeadCsd(): ByteBuffer {
        val buf = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("OpusHead".toByteArray(Charsets.US_ASCII))
        buf.put(1.toByte())           // version = 1
        buf.put(1.toByte())           // channels = 1 (mono)
        buf.putShort(80.toShort())    // pre-skip (samples @ 48kHz)
        buf.putInt(AudioEngine.SAMPLE_RATE_HZ)
        buf.putShort(0.toShort())     // output gain = 0
        buf.put(0.toByte())           // channel mapping family = 0
        buf.flip()
        return buf
    }
}

/**
 * Real-time Walkie-Talkie AudioEngine:
 *  • Opens [AudioRecord] ONLY while the local user actively holds the transmit floor, and releases it
 *    immediately when speech stops (battery-optimal, strict privacy).
 *  • Plays incoming speech via [AudioTrack] fed by [AdaptiveJitterBuffer] + negotiated codec (Opus / ADPCM).
 *  • Supports local global listen mute and optional Roger Beep tones.
 */
class AudioEngine(private val ctx: Context) {

    companion object {
        const val SAMPLE_RATE_HZ: Int = 16_000
        const val FRAME_SAMPLES: Int = 320 // 20ms @ 16kHz
    }

    private val audioManager: AudioManager? = try { ctx.getSystemService(AudioManager::class.java) } catch (_: Exception) { null }
    private val opusAvailable: Boolean by lazy { OpusMediaCodecHelper.isOpusCodecPairSupported() }

    private val playbackRunning = AtomicBoolean(false)
    private val captureRunning = AtomicBoolean(false)
    private val localListenMuted = AtomicBoolean(false)
    private val txSeq = AtomicInteger(1)

    private val jitterBuffer = AdaptiveJitterBuffer(FRAME_SAMPLES, 20)

    private var playbackThread: Thread? = null
    private var captureThread: Thread? = null

    @Volatile private var activeTrack: AudioTrack? = null
    @Volatile private var opusDecoder: MediaCodec? = null
    private val decoderLock = Any()

    fun localSupportedCodecMask(): Int {
        var mask = AudioCodecType.ADPCM_16K.mask
        if (opusAvailable) {
            mask = mask or AudioCodecType.OPUS_16K.mask
        }
        return mask
    }

    fun setLocalListenMuted(muted: Boolean) {
        localListenMuted.set(muted)
        if (muted) {
            jitterBuffer.reset()
        }
    }

    fun isLocalListenMuted(): Boolean = localListenMuted.get()

    fun measuredPacketLossPct(): Int = jitterBuffer.measuredLossPercent()

    /** Starts the session receive playback loop (AudioTrack stays idle until audio frames arrive). */
    @Synchronized
    fun startReceiveSession() {
        if (playbackRunning.getAndSet(true)) return
        jitterBuffer.reset()
        val t = Thread({ runPlaybackLoop() }, "ptt-audio-play").apply { isDaemon = true }
        playbackThread = t
        t.start()
    }

    /** Feeds an authenticated incoming UDP voice packet into the decoder and adaptive jitter buffer. */
    fun onIncomingVoicePacket(packet: PttProtocol.UdpVoicePacket) {
        if (!playbackRunning.get() || localListenMuted.get()) return
        val pcm = when (packet.codec) {
            AudioCodecType.OPUS_16K -> decodeOpusOrFallback(packet.payload)
            AudioCodecType.ADPCM_16K -> ImaAdpcmCodec.decodeFrame(packet.payload, FRAME_SAMPLES)
        }
        if (pcm.isNotEmpty()) {
            jitterBuffer.push(
                sequence = packet.sequence,
                packetTimestampMs = packet.timestampMs,
                arrivalElapsedMs = SystemClock.elapsedRealtime(),
                pcm = pcm
            )
        }
    }

    /**
     * Opens [AudioRecord] and transmits encoded 20ms frames ONLY while holding the floor.
     * Safe to call repeatedly; any previous capture session is cleanly closed first.
     */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun startTransmit(
        codec: AudioCodecType,
        rogerBeep: Boolean,
        onEncodedFrame: (actualCodec: AudioCodecType, sequence: Int, timestampMs: Long, endOfBurst: Boolean, encodedBytes: ByteArray) -> Unit,
        onCaptureError: () -> Unit
    ) {
        stopTransmit(playEndBeep = false)
        if (!PttPermissions.hasRecordAudio(ctx)) {
            onCaptureError()
            return
        }
        if (rogerBeep) {
            playToneAsync(RogerBeepSynthesizer.generateStartChirp())
        }
        captureRunning.set(true)
        val thread = Thread({
            runCaptureLoop(codec, onEncodedFrame, onCaptureError)
        }, "ptt-audio-capture").apply { isDaemon = true }
        captureThread = thread
        thread.start()
    }

    /** Stops microphone capture immediately and releases [AudioRecord] hardware within milliseconds. */
    @Synchronized
    fun stopTransmit(playEndBeep: Boolean) {
        val wasRunning = captureRunning.getAndSet(false)
        val th = captureThread
        captureThread = null
        if (th != null && th !== Thread.currentThread()) {
            th.interrupt()
            try { th.join(180) } catch (_: InterruptedException) {}
        }
        if (wasRunning && playEndBeep && !localListenMuted.get()) {
            playToneAsync(RogerBeepSynthesizer.generateRogerBeep())
        }
    }

    /** Idempotent full teardown of capture, playback, codecs, and buffers. */
    @Synchronized
    fun releaseAll() {
        stopTransmit(playEndBeep = false)
        playbackRunning.set(false)
        val pt = playbackThread
        playbackThread = null
        pt?.interrupt()
        if (pt != null && pt !== Thread.currentThread()) {
            try { pt.join(180) } catch (_: InterruptedException) {}
        }
        synchronized(decoderLock) {
            try { opusDecoder?.stop() } catch (_: Throwable) {}
            try { opusDecoder?.release() } catch (_: Throwable) {}
            opusDecoder = null
        }
        try {
            activeTrack?.pause()
            activeTrack?.flush()
            activeTrack?.release()
        } catch (_: Throwable) {}
        activeTrack = null
        jitterBuffer.reset()
        localListenMuted.set(false)
        try {
            if (audioManager?.mode == AudioManager.MODE_IN_COMMUNICATION) {
                audioManager.mode = AudioManager.MODE_NORMAL
            }
        } catch (_: Throwable) {}
    }

    fun playRogerEndToneIfEnabled(enabled: Boolean) {
        if (enabled && !localListenMuted.get()) {
            playToneAsync(RogerBeepSynthesizer.generateRogerBeep())
        }
    }

    @SuppressLint("MissingPermission")
    private fun runCaptureLoop(
        preferredCodec: AudioCodecType,
        onEncodedFrame: (AudioCodecType, Int, Long, Boolean, ByteArray) -> Unit,
        onCaptureError: () -> Unit
    ) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            captureRunning.set(false)
            onCaptureError()
            return
        }

        var record: AudioRecord? = null
        var aec: AcousticEchoCanceler? = null
        var ns: NoiseSuppressor? = null
        var agc: AutomaticGainControl? = null
        var opusEnc: MediaCodec? = null
        var activeCodec = preferredCodec
        val adpcmState = ImaAdpcmCodec.State()

        try {
            val bufBytes = maxOf(minBuf * 2, FRAME_SAMPLES * 2 * 4)
            record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufBytes
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                captureRunning.set(false)
                onCaptureError()
                return
            }

            val sid = record.audioSessionId
            if (AcousticEchoCanceler.isAvailable()) {
                aec = runCatching { AcousticEchoCanceler.create(sid)?.apply { enabled = true } }.getOrNull()
            }
            if (NoiseSuppressor.isAvailable()) {
                ns = runCatching { NoiseSuppressor.create(sid)?.apply { enabled = true } }.getOrNull()
            }
            if (AutomaticGainControl.isAvailable()) {
                agc = runCatching { AutomaticGainControl.create(sid)?.apply { enabled = true } }.getOrNull()
            }

            if (activeCodec == AudioCodecType.OPUS_16K) {
                opusEnc = createOpusEncoderOrNull()
                if (opusEnc == null) {
                    activeCodec = AudioCodecType.ADPCM_16K
                }
            }

            record.startRecording()
            val pcmBuf = ShortArray(FRAME_SAMPLES)
            val codecInfo = MediaCodec.BufferInfo()

            while (captureRunning.get() && !Thread.currentThread().isInterrupted) {
                var readTotal = 0
                while (readTotal < FRAME_SAMPLES && captureRunning.get()) {
                    val r = record.read(pcmBuf, readTotal, FRAME_SAMPLES - readTotal)
                    if (r <= 0) break
                    readTotal += r
                }
                if (readTotal < FRAME_SAMPLES || !captureRunning.get()) break

                val nowMs = SystemClock.elapsedRealtime()
                val enc = opusEnc
                if (activeCodec == AudioCodecType.OPUS_16K && enc != null) {
                    val encodedOpus = encodeOpusFrame(enc, pcmBuf, codecInfo)
                    if (encodedOpus != null && encodedOpus.isNotEmpty()) {
                        onEncodedFrame(AudioCodecType.OPUS_16K, txSeq.getAndIncrement(), nowMs, false, encodedOpus)
                        continue
                    }
                }
                val adpcmBytes = ImaAdpcmCodec.encodeFrame(pcmBuf, adpcmState)
                onEncodedFrame(AudioCodecType.ADPCM_16K, txSeq.getAndIncrement(), nowMs, false, adpcmBytes)
            }
        } catch (_: Throwable) {
            if (captureRunning.getAndSet(false)) {
                onCaptureError()
            }
        } finally {
            try { opusEnc?.stop() } catch (_: Throwable) {}
            try { opusEnc?.release() } catch (_: Throwable) {}
            try { aec?.release() } catch (_: Throwable) {}
            try { ns?.release() } catch (_: Throwable) {}
            try { agc?.release() } catch (_: Throwable) {}
            try {
                if (record?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    record.stop()
                }
            } catch (_: Throwable) {}
            try { record?.release() } catch (_: Throwable) {}
        }
    }

    private fun runPlaybackLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val minTrackBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(FRAME_SAMPLES * 2 * 4)

        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE_HZ)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minTrackBuf)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (_: Throwable) {
            return
        }

        activeTrack = track
        var playing = false
        try {
            while (playbackRunning.get() && !Thread.currentThread().isInterrupted) {
                val frame = if (!localListenMuted.get()) jitterBuffer.pollNextFrame() else null
                if (frame != null) {
                    if (!playing) {
                        track.play()
                        playing = true
                    }
                    track.write(frame, 0, frame.size)
                } else {
                    if (playing) {
                        try { track.pause(); track.flush() } catch (_: Throwable) {}
                        playing = false
                    }
                    Thread.sleep(10)
                }
            }
        } catch (_: InterruptedException) {
            // Clean exit
        } catch (_: Throwable) {
            // Safe exit
        } finally {
            try {
                if (playing) track.stop()
            } catch (_: Throwable) {}
            try { track.release() } catch (_: Throwable) {}
            if (activeTrack === track) activeTrack = null
        }
    }

    private fun createOpusEncoderOrNull(): MediaCodec? = try {
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, SAMPLE_RATE_HZ, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 20_000)
            setInteger(MediaFormat.KEY_COMPLEXITY, 4)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FRAME_SAMPLES * 2 * 2)
        }
        MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS).apply {
            configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
    } catch (_: Throwable) {
        null
    }

    private fun encodeOpusFrame(encoder: MediaCodec, pcm: ShortArray, info: MediaCodec.BufferInfo): ByteArray? {
        val inIdx = encoder.dequeueInputBuffer(5_000)
        if (inIdx >= 0) {
            val inBuf = encoder.getInputBuffer(inIdx) ?: return null
            inBuf.clear()
            val bb = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.nativeOrder())
            bb.asShortBuffer().put(pcm)
            inBuf.put(bb.array(), 0, pcm.size * 2)
            val ptsUs = SystemClock.elapsedRealtimeNanos() / 1000L
            encoder.queueInputBuffer(inIdx, 0, pcm.size * 2, ptsUs, 0)
        }
        var result: ByteArray? = null
        while (true) {
            val outIdx = encoder.dequeueOutputBuffer(info, 4_000)
            if (outIdx >= 0) {
                val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                if (!isConfig && info.size > 0) {
                    val outBuf = encoder.getOutputBuffer(outIdx)
                    if (outBuf != null) {
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)
                        val bytes = ByteArray(info.size)
                        outBuf.get(bytes)
                        result = bytes
                    }
                }
                encoder.releaseOutputBuffer(outIdx, false)
            } else {
                break
            }
        }
        return result
    }

    private fun decodeOpusOrFallback(opusBytes: ByteArray): ShortArray = synchronized(decoderLock) {
        if (opusBytes.isEmpty()) return ShortArray(0)
        try {
            var dec = opusDecoder
            if (dec == null) {
                val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, SAMPLE_RATE_HZ, 1).apply {
                    setByteBuffer("csd-0", OpusMediaCodecHelper.buildOpusHeadCsd())
                    val zero8 = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).apply { putLong(0L); flip() }
                    val preSkip8 = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).apply { putLong(80_000_000L); flip() }
                    setByteBuffer("csd-1", zero8)
                    setByteBuffer("csd-2", preSkip8)
                }
                dec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS).apply {
                    configure(fmt, null, null, 0)
                    start()
                }
                opusDecoder = dec
            }
            val inIdx = dec.dequeueInputBuffer(5_000)
            if (inIdx >= 0) {
                val inBuf = dec.getInputBuffer(inIdx)
                if (inBuf != null) {
                    inBuf.clear()
                    inBuf.put(opusBytes)
                    dec.queueInputBuffer(inIdx, 0, opusBytes.size, SystemClock.elapsedRealtimeNanos() / 1000L, 0)
                }
            }
            val info = MediaCodec.BufferInfo()
            var outShorts: ShortArray? = null
            while (true) {
                val outIdx = dec.dequeueOutputBuffer(info, 4_000)
                if (outIdx >= 0) {
                    if (info.size > 0) {
                        val outBuf = dec.getOutputBuffer(outIdx)
                        if (outBuf != null) {
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            val raw = ByteArray(info.size)
                            outBuf.get(raw)
                            val shorts = ShortArray(raw.size / 2)
                            ByteBuffer.wrap(raw).order(ByteOrder.nativeOrder()).asShortBuffer().get(shorts)
                            outShorts = if (shorts.size == FRAME_SAMPLES) shorts else shorts.copyOf(FRAME_SAMPLES)
                        }
                    }
                    dec.releaseOutputBuffer(outIdx, false)
                } else {
                    break
                }
            }
            outShorts ?: ShortArray(0)
        } catch (_: Throwable) {
            ShortArray(0)
        }
    }

    private fun playToneAsync(samples: ShortArray) {
        if (samples.isEmpty() || localListenMuted.get()) return
        Thread({
            var t: AudioTrack? = null
            try {
                t = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE_HZ)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(samples.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                t.write(samples, 0, samples.size)
                t.play()
                val ms = (samples.size * 1000L) / SAMPLE_RATE_HZ + 25L
                Thread.sleep(ms)
            } catch (_: Throwable) {
            } finally {
                try { t?.stop() } catch (_: Throwable) {}
                try { t?.release() } catch (_: Throwable) {}
            }
        }, "ptt-tone").apply { isDaemon = true }.start()
    }
}
