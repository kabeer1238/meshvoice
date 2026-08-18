package com.bikemesh.ridemesh.audio

import android.annotation.SuppressLint
import android.content.Context
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
import java.util.ArrayDeque
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
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

    // Never allow seconds of old voice to queue up. If the radio/network is slower than
    // real time we discard the oldest pending frame rather than making riders hear stale audio.
    private val playbackExecutor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(PLAYBACK_QUEUE_FRAMES),
        ThreadPoolExecutor.DiscardOldestPolicy(),
    )

    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var audioTrack: AudioTrack? = null
    @Volatile private var route: AudioRoute = AudioRoute.AUTO

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

                        // Slowly learn the background level only when the frame does not look like speech.
                        val speechThreshold = max(VAD_MIN_RMS, noiseFloor * VAD_NOISE_MULTIPLIER)
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
            }, "RideMesh-Mic").start()
        } catch (t: Throwable) {
            capturing.set(false)
            try { recorder?.release() } catch (_: Throwable) {}
            if (audioRecord === recorder) audioRecord = null
            onStatus("Microphone error: ${t.javaClass.simpleName}: ${t.message ?: "unknown"}")
        }
    }

    fun stopTransmit() {
        capturing.set(false)
    }

    fun playIncoming(audio: ByteArray) {
        if (audio.isEmpty()) return
        playbackExecutor.execute {
            val track = ensureTrack() ?: return@execute
            try {
                track.write(audio, 0, audio.size, AudioTrack.WRITE_BLOCKING)
            } catch (_: Throwable) {}
        }
    }

    fun release() {
        stopTransmit()
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
        playbackExecutor.shutdownNow()
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

        private const val PLAYBACK_QUEUE_FRAMES = 8 // ~160 ms maximum pending audio

        private const val VAD_PREROLL_FRAMES = 3    // 60 ms of audio before speech trigger
        private const val VAD_HANGOVER_FRAMES = 12 // 240 ms after speech falls below threshold
        private const val VAD_INITIAL_NOISE_FLOOR = 250.0
        private const val VAD_MIN_RMS = 520.0
        private const val VAD_NOISE_MULTIPLIER = 2.2
        private const val WIND_FILTER_CUTOFF_HZ = 110.0
    }
}
