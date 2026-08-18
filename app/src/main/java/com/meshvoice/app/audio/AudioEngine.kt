package com.meshvoice.app.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sqrt

enum class AudioRoute {
    AUTO,
    PHONE,
    HELMET,
}

class AudioEngine(
    context: Context,
    private val onCapturedFrame: (ByteArray) -> Unit,
    private val onStatus: (String) -> Unit,
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val capturing = AtomicBoolean(false)

    // Previously all incoming audio -- regardless of which rider sent it --
    // was pushed into ONE shared queue and written to the AudioTrack in
    // arrival order. With a single remote rider that's indistinguishable
    // from correct playback, but with two or more simultaneous senders their
    // packets interleaved chunk-by-chunk instead of being mixed, which is
    // what produced "jittery and delayed" audio once a third phone joined.
    //
    // Fixed by giving each sender (origin) its own small bounded queue and
    // running a fixed-cadence mixer thread that sums whatever frame each
    // active origin has ready every 20ms into a single mixed frame, so N
    // simultaneous speakers are actually mixed rather than serialized.
    private val originQueues = ConcurrentHashMap<String, ArrayDeque<ByteArray>>()
    private val originQueuesLock = Any()
    private val mixerRunning = AtomicBoolean(false)
    @Volatile private var mixerThread: Thread? = null

    // Updated every time the mixer actually writes real (non-silent) audio to
    // the track. The mic capture loop uses recency of this to infer "remote
    // audio is probably still coming out of this device's speaker right now"
    // and raises the local speech threshold briefly -- a partial mitigation
    // for echo on devices where hardware AEC is weak or absent, since it
    // makes the device less likely to re-transmit what its own mic just
    // picked back up off its own speaker. It does not replace real AEC and
    // will not fully eliminate echo caused by a WEAK echo canceler on the
    // *other* party's device -- see AEC discussion in code comments below.
    @Volatile private var lastMixedWriteMs = 0L

    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var audioTrack: AudioTrack? = null
    @Volatile private var route: AudioRoute = AudioRoute.AUTO

    // True once startTransmit() has been called and stopTransmit() hasn't --
    // i.e. "the ride wants the mic on", independent of whether a phone call
    // has temporarily taken the mic away. Used to decide whether to
    // auto-resume capture when audio focus comes back after a call ends.
    @Volatile private var desiredCapture = false

    // True while a real phone call (or anything else with higher audio
    // priority) holds the device's mic/speaker instead of us. No
    // READ_PHONE_STATE permission needed -- AudioManager's focus callback is
    // the platform-recommended way apps detect this, and it fires for calls
    // specifically because the telephony stack requests focus for them.
    @Volatile private var focusPaused = false
    private var focusRequest: AudioFocusRequest? = null

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                focusPaused = true
                capturing.set(false) // mic thread exits and releases AudioRecord in its own finally block
                audioTrack?.let {
                    try { it.stop() } catch (_: Throwable) {}
                    try { it.release() } catch (_: Throwable) {}
                }
                audioTrack = null
                onStatus("Voice paused — phone call")
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                focusPaused = false
                onStatus("Voice resumed")
                if (desiredCapture) beginCapture()
            }
        }
    }

    private fun requestCallAudioFocus() {
        if (focusRequest != null) return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener(focusListener)
            .build()
        focusRequest = request
        val result = audioManager.requestAudioFocus(request)
        // Losing the request outright (e.g. a call is already active when the
        // ride starts) is handled the same way as losing it mid-ride.
        if (result == AudioManager.AUDIOFOCUS_REQUEST_FAILED) {
            focusPaused = true
        }
    }

    private fun abandonCallAudioFocus() {
        focusRequest?.let { runCatching { audioManager.abandonAudioFocusRequest(it) } }
        focusRequest = null
    }

    fun setRoute(newRoute: AudioRoute) {
        route = newRoute
    }

    @SuppressLint("MissingPermission")
    fun selectCommunicationDevice(): String {
        return try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val available = audioManager.availableCommunicationDevices
                val helmet = available.firstOrNull { it.isHelmetCandidate() }
                val speaker = available.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }

                val chosen = when (route) {
                    AudioRoute.HELMET -> helmet
                    AudioRoute.PHONE -> speaker
                    AudioRoute.AUTO -> helmet ?: speaker
                }

                if (chosen == null) {
                    val text = when (route) {
                        AudioRoute.HELMET -> "Audio: no call-capable Bluetooth headset found"
                        AudioRoute.PHONE -> "Audio: phone speaker unavailable"
                        AudioRoute.AUTO -> "Audio: no communication device available"
                    }
                    onStatus(text)
                    text
                } else {
                    val ok = audioManager.setCommunicationDevice(chosen)
                    val label = chosen.routeLabel()
                    val text = if (ok) "Audio: $label" else "Audio routing failed: $label"
                    onStatus(text)
                    text
                }
            } else {
                val hasBluetoothSco = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    .any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }

                val useBluetooth = when (route) {
                    AudioRoute.HELMET -> true
                    AudioRoute.PHONE -> false
                    AudioRoute.AUTO -> hasBluetoothSco
                }

                @Suppress("DEPRECATION")
                if (useBluetooth) {
                    audioManager.isSpeakerphoneOn = false
                    audioManager.startBluetoothSco()
                    audioManager.isBluetoothScoOn = true
                    "Audio: Bluetooth headset"
                } else {
                    audioManager.stopBluetoothSco()
                    audioManager.isBluetoothScoOn = false
                    audioManager.isSpeakerphoneOn = true
                    "Audio: phone speaker + microphone"
                }.also(onStatus)
            }
        } catch (t: Throwable) {
            val text = "Audio routing error: ${t.javaClass.simpleName}: ${t.message ?: "unknown"}"
            onStatus(text)
            text
        }
    }

    @SuppressLint("MissingPermission")
    fun startTransmit() {
        desiredCapture = true
        requestCallAudioFocus()
        if (focusPaused) {
            // A call is already active; beginCapture() runs automatically
            // once AUDIOFOCUS_GAIN comes back, same as a mid-ride interruption.
            onStatus("Voice paused — phone call in progress")
            return
        }
        beginCapture()
    }

    @SuppressLint("MissingPermission")
    private fun beginCapture() {
        if (!capturing.compareAndSet(false, true)) return

        var recorder: AudioRecord? = null
        try {
            selectCommunicationDevice()

            val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING)
            if (min <= 0) {
                capturing.set(false)
                onStatus("Microphone buffer unavailable")
                return
            }
            val recordBuffer = max(min, FRAME_BYTES * 4)

            recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                CHANNEL_IN,
                ENCODING,
                recordBuffer,
            )

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                capturing.set(false)
                recorder.release()
                onStatus("Microphone could not start")
                return
            }

            // VOICE_COMMUNICATION requests the platform's VoIP capture tuning. We also
            // explicitly attach the available preprocessors to this AudioRecord session.
            val aec = createAec(recorder.audioSessionId)
            val ns = createNs(recorder.audioSessionId)
            val agc = createAgc(recorder.audioSessionId)

            audioRecord = recorder
            recorder.startRecording()
            onStatus("HANDS-FREE • NOISE REDUCTION • VAD • ${effectsLabel(aec != null, ns != null, agc != null)}")

            val activeRecorder = recorder
            Thread({
                val frame = ByteArray(FRAME_BYTES)
                val preRoll = ArrayDeque<ByteArray>(VAD_PREROLL_FRAMES)
                val windFilter = WindRumbleFilter(SAMPLE_RATE, WIND_FILTER_CUTOFF_HZ)
                var hangover = 0
                var wasSending = false
                var noiseFloor = VAD_INITIAL_NOISE_FLOOR

                try {
                    while (capturing.get()) {
                        val read = activeRecorder.read(frame, 0, frame.size)
                        if (read <= 0) continue

                        val raw = if (read == frame.size) frame.copyOf() else frame.copyOf(read)
                        // Motorcycle wind noise is dominated by low-frequency rumble. Remove
                        // that energy before VAD so wind is less likely to hold the mic open.
                        val current = windFilter.process(raw)
                        val rms = pcmRms(current)

                        // Partial echo mitigation: while this device's speaker is
                        // actively rendering another rider's voice, its own mic is
                        // the thing most likely to be picking that playback back up
                        // (weak/absent hardware AEC on some phones, or acoustic
                        // coupling that simply overwhelms AEC at speakerphone
                        // volume outdoors). Raising the bar during that window makes
                        // re-transmitting that pickup less likely without blocking a
                        // genuine loud interruption, which still clears the higher
                        // threshold. This is a heuristic, not real echo cancellation
                        // -- it cannot fix a *weak AEC on the other rider's phone*,
                        // which is the more likely cause if the echo is the other
                        // person hearing themselves rather than you hearing yourself.
                        val playbackActive = (System.currentTimeMillis() - lastMixedWriteMs) < ECHO_GUARD_WINDOW_MS
                        val duckMultiplier = if (playbackActive) ECHO_GUARD_MULTIPLIER else 1.0

                        // Slowly learn the background level only when the frame does not look like speech.
                        val speechThreshold = max(VAD_MIN_RMS, noiseFloor * VAD_NOISE_MULTIPLIER) * duckMultiplier
                        val speech = rms >= speechThreshold
                        if (!speech) {
                            noiseFloor = (noiseFloor * 0.985) + (rms * 0.015)
                        }

                        if (speech) hangover = VAD_HANGOVER_FRAMES
                        else if (hangover > 0) hangover--

                        val sending = speech || hangover > 0
                        if (sending) {
                            if (!wasSending) {
                                // Preserve the beginning of the first word instead of clipping it.
                                while (preRoll.isNotEmpty()) onCapturedFrame(preRoll.removeFirst())
                            }
                            onCapturedFrame(current)
                        } else {
                            if (preRoll.size >= VAD_PREROLL_FRAMES) preRoll.removeFirst()
                            preRoll.addLast(current)
                        }
                        wasSending = sending
                    }
                } catch (t: Throwable) {
                    onStatus("Microphone stream error: ${t.javaClass.simpleName}: ${t.message ?: "unknown"}")
                } finally {
                    try { activeRecorder.stop() } catch (_: Throwable) {}
                    aec?.release()
                    ns?.release()
                    agc?.release()
                    activeRecorder.release()
                    if (audioRecord === activeRecorder) audioRecord = null
                    selectCommunicationDevice()
                }
            }, "MeshVoice-Mic").start()
        } catch (t: Throwable) {
            capturing.set(false)
            try { recorder?.release() } catch (_: Throwable) {}
            if (audioRecord === recorder) audioRecord = null
            onStatus("Microphone error: ${t.javaClass.simpleName}: ${t.message ?: "unknown"}")
        }
    }

    fun stopTransmit() {
        desiredCapture = false
        capturing.set(false)
    }

    fun playIncoming(origin: String, audio: ByteArray) {
        if (audio.isEmpty()) return
        synchronized(originQueuesLock) {
            val queue = originQueues.getOrPut(origin) { ArrayDeque() }
            // Bounded per sender: a backlogged rider's audio gets dropped
            // rather than delaying everyone else's, same discard-oldest
            // policy the old single queue used, just scoped per origin now.
            if (queue.size >= PER_ORIGIN_QUEUE_FRAMES) queue.removeFirst()
            queue.addLast(audio)
        }
        ensureMixerRunning()
    }

    private fun ensureMixerRunning() {
        if (!mixerRunning.compareAndSet(false, true)) return
        val thread = Thread({ mixerLoop() }, "MeshVoice-Mixer").apply { isDaemon = true }
        mixerThread = thread
        thread.start()
    }

    /**
     * Runs at a fixed ~20ms cadence for as long as the mixer stays "hot"
     * (any origin has pending audio, or did recently). Each tick pulls at
     * most one frame from every origin that has one ready and sums them
     * into a single mixed frame -- real mixing, not queue interleaving --
     * so two or more riders talking at once are actually audible together
     * rather than chopped into alternating fragments.
     */
    private fun mixerLoop() {
        var idleTicks = 0
        try {
            while (mixerRunning.get()) {
                val tickStart = System.nanoTime()

                val pending: List<ByteArray> = synchronized(originQueuesLock) {
                    originQueues.values.mapNotNull { q -> if (q.isNotEmpty()) q.removeFirst() else null }
                }

                if (pending.isNotEmpty()) {
                    idleTicks = 0
                    // Drain queues even while paused for a call, so audio
                    // doesn't burst out all at once the moment focus returns.
                    if (!focusPaused) {
                        val mixed = if (pending.size == 1) pending[0] else mixFrames(pending)
                        val track = ensureTrack()
                        if (track != null) {
                            try {
                                track.write(mixed, 0, mixed.size, AudioTrack.WRITE_BLOCKING)
                                lastMixedWriteMs = System.currentTimeMillis()
                            } catch (_: Throwable) {
                            }
                        }
                    }
                } else {
                    idleTicks++
                    // Nothing to mix for a while -- stop the thread instead of
                    // spinning forever on a silent ride; playIncoming() will
                    // restart it the moment new audio arrives.
                    if (idleTicks > MIXER_IDLE_STOP_TICKS) {
                        mixerRunning.set(false)
                        break
                    }
                }

                val elapsedMs = (System.nanoTime() - tickStart) / 1_000_000L
                val sleepMs = FRAME_MS - elapsedMs
                if (sleepMs > 0) Thread.sleep(sleepMs)
            }
        } catch (_: InterruptedException) {
        } finally {
            mixerRunning.set(false)
        }
    }

    /** Sums PCM16 samples from every provided frame and clamps to avoid wraparound clipping artifacts. */
    private fun mixFrames(frames: List<ByteArray>): ByteArray {
        val length = frames.maxOf { it.size }
        val out = ByteArray(length)
        var i = 0
        while (i + 1 < length) {
            var sum = 0
            for (f in frames) {
                if (i + 1 >= f.size) continue
                val lo = f[i].toInt() and 0xff
                val hi = f[i + 1].toInt()
                sum += ((hi shl 8) or lo).toShort().toInt()
            }
            val clamped = sum.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            out[i] = (clamped and 0xff).toByte()
            out[i + 1] = ((clamped shr 8) and 0xff).toByte()
            i += 2
        }
        return out
    }

    fun release() {
        stopTransmit()
        abandonCallAudioFocus()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try { audioManager.clearCommunicationDevice() } catch (_: Throwable) {}
        } else {
            @Suppress("DEPRECATION")
            try {
                audioManager.stopBluetoothSco()
                audioManager.isBluetoothScoOn = false
                audioManager.isSpeakerphoneOn = false
            } catch (_: Throwable) {}
        }
        audioTrack?.let {
            try { it.stop() } catch (_: Throwable) {}
            it.release()
        }
        audioTrack = null
        try { audioManager.mode = AudioManager.MODE_NORMAL } catch (_: Throwable) {}
        mixerRunning.set(false)
        mixerThread?.interrupt()
        synchronized(originQueuesLock) { originQueues.clear() }
    }

    private fun ensureTrack(): AudioTrack? {
        audioTrack?.let { return it }

        return try {
            val min = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, ENCODING)
            if (min <= 0) return null

            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(ENCODING)
                        .setChannelMask(CHANNEL_OUT)
                        .build()
                )
                .setBufferSizeInBytes(max(min, FRAME_BYTES * 6))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            if (track.state != AudioTrack.STATE_INITIALIZED) {
                track.release()
                null
            } else {
                track.play()
                audioTrack = track
                track
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun pcmRms(bytes: ByteArray): Double {
        if (bytes.size < 2) return 0.0
        var sum = 0.0
        var samples = 0
        var i = 0
        while (i + 1 < bytes.size) {
            val lo = bytes[i].toInt() and 0xff
            val hi = bytes[i + 1].toInt()
            val sample = ((hi shl 8) or lo).toShort().toInt()
            sum += sample.toDouble() * sample.toDouble()
            samples++
            i += 2
        }
        return if (samples == 0) 0.0 else sqrt(sum / samples)
    }

    private fun createAec(sessionId: Int): AcousticEchoCanceler? = try {
        if (AcousticEchoCanceler.isAvailable()) {
            AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
        } else null
    } catch (_: Throwable) { null }

    private fun createNs(sessionId: Int): NoiseSuppressor? = try {
        if (NoiseSuppressor.isAvailable()) {
            NoiseSuppressor.create(sessionId)?.apply { enabled = true }
        } else null
    } catch (_: Throwable) { null }

    private fun createAgc(sessionId: Int): AutomaticGainControl? = try {
        if (AutomaticGainControl.isAvailable()) {
            AutomaticGainControl.create(sessionId)?.apply { enabled = true }
        } else null
    } catch (_: Throwable) { null }

    private fun effectsLabel(aec: Boolean, ns: Boolean, agc: Boolean): String {
        val enabled = buildList {
            if (aec) add("AEC")
            if (ns) add("NS")
            if (agc) add("AGC")
        }
        return if (enabled.isEmpty()) "software wind filter" else enabled.joinToString("+") + "+WIND"
    }

    /** Very small first-order high-pass filter to reduce motorcycle wind/road rumble. */
    private class WindRumbleFilter(sampleRate: Int, cutoffHz: Double) {
        private val alpha: Double
        private var previousInput = 0.0
        private var previousOutput = 0.0

        init {
            val dt = 1.0 / sampleRate.toDouble()
            val rc = 1.0 / (2.0 * PI * cutoffHz)
            alpha = rc / (rc + dt)
        }

        fun process(bytes: ByteArray): ByteArray {
            if (bytes.size < 2) return bytes
            val out = bytes.copyOf()
            var i = 0
            while (i + 1 < bytes.size) {
                val lo = bytes[i].toInt() and 0xff
                val hi = bytes[i + 1].toInt()
                val input = ((hi shl 8) or lo).toShort().toDouble()
                val filtered = alpha * (previousOutput + input - previousInput)
                previousInput = input
                previousOutput = filtered
                val sample = filtered.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                out[i] = (sample and 0xff).toByte()
                out[i + 1] = ((sample shr 8) and 0xff).toByte()
                i += 2
            }
            return out
        }
    }

    private fun AudioDeviceInfo.isHelmetCandidate(): Boolean {
        return when (type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_HEARING_AID -> true
            else -> false
        }
    }

    private fun AudioDeviceInfo.routeLabel(): String {
        return when {
            isHelmetCandidate() -> productName?.toString()?.takeIf { it.isNotBlank() }
                ?: "Bluetooth helmet/headset"
            type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "phone speaker + microphone"
            else -> productName?.toString()?.takeIf { it.isNotBlank() } ?: "communication device"
        }
    }

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        private const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
        private const val FRAME_MS = 20
        private const val SAMPLES_PER_FRAME = SAMPLE_RATE * FRAME_MS / 1000
        private const val FRAME_BYTES = SAMPLES_PER_FRAME * 2

        private const val PER_ORIGIN_QUEUE_FRAMES = 4 // ~80 ms max pending audio per sender
        private const val MIXER_IDLE_STOP_TICKS = 100 // ~2s of silence before the mixer thread parks itself

        private const val ECHO_GUARD_WINDOW_MS = 300L
        private const val ECHO_GUARD_MULTIPLIER = 1.8

        private const val VAD_PREROLL_FRAMES = 3    // 60 ms of audio before speech trigger
        private const val VAD_HANGOVER_FRAMES = 12 // 240 ms after speech falls below threshold
        private const val VAD_INITIAL_NOISE_FLOOR = 250.0
        private const val VAD_MIN_RMS = 520.0
        private const val VAD_NOISE_MULTIPLIER = 2.2
        private const val WIND_FILTER_CUTOFF_HZ = 110.0
    }
}
