package com.meshvoice.app.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Captures 16kHz mono PCM16 frames, gates them with a lightweight
 * voice-activity detector so silence isn't relayed across the mesh, and
 * plays back incoming frames through a bounded queue so a slow/lossy peer
 * causes dropped frames instead of growing audio delay.
 *
 * This intentionally mirrors the real-time discipline in the RideMesh
 * reference app's AudioEngine (VOICE_COMMUNICATION source + platform AEC/NS/AGC
 * + DiscardOldestPolicy playback queue) since that part of the design is sound.
 */
class AudioEngine(
    context: Context,
    private val onCapturedFrame: (ByteArray) -> Unit,
    private val onStatus: (String) -> Unit,
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val capturing = AtomicBoolean(false)

    private val playbackExecutor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(PLAYBACK_QUEUE_FRAMES),
        ThreadPoolExecutor.DiscardOldestPolicy(),
    )

    @Volatile private var audioTrack: AudioTrack? = null

    @SuppressLint("MissingPermission")
    fun startTransmit() {
        if (!capturing.compareAndSet(false, true)) return

        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = true

            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING)
            if (minBuf <= 0) {
                capturing.set(false)
                onStatus("Microphone unavailable on this device")
                return
            }

            val recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE, CHANNEL_IN, ENCODING, max(minBuf, FRAME_BYTES * 4),
            )
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                capturing.set(false)
                recorder.release()
                onStatus("Microphone failed to initialize")
                return
            }

            val aec = createEffect(AcousticEchoCanceler::isAvailable) { AcousticEchoCanceler.create(recorder.audioSessionId) }
            val ns = createEffect(NoiseSuppressor::isAvailable) { NoiseSuppressor.create(recorder.audioSessionId) }
            val agc = createEffect(AutomaticGainControl::isAvailable) { AutomaticGainControl.create(recorder.audioSessionId) }

            recorder.startRecording()
            onStatus("Mic live" + effectsSuffix(aec != null, ns != null, agc != null))

            Thread({
                val frame = ByteArray(FRAME_BYTES)
                var hangover = 0
                var noiseFloor = VAD_INITIAL_NOISE_FLOOR
                try {
                    while (capturing.get()) {
                        val read = recorder.read(frame, 0, frame.size)
                        if (read <= 0) continue
                        val current = if (read == frame.size) frame.copyOf() else frame.copyOf(read)

                        val rms = pcmRms(current)
                        val threshold = max(VAD_MIN_RMS, noiseFloor * VAD_NOISE_MULTIPLIER)
                        val isSpeech = rms >= threshold
                        if (!isSpeech) noiseFloor = (noiseFloor * 0.985) + (rms * 0.015)

                        if (isSpeech) hangover = VAD_HANGOVER_FRAMES else if (hangover > 0) hangover--
                        if (isSpeech || hangover > 0) onCapturedFrame(current)
                    }
                } catch (t: Throwable) {
                    onStatus("Mic stream error: ${t.javaClass.simpleName}")
                } finally {
                    runCatching { recorder.stop() }
                    aec?.release(); ns?.release(); agc?.release()
                    recorder.release()
                }
            }, "MeshVoice-Mic").start()
        } catch (t: Throwable) {
            capturing.set(false)
            onStatus("Mic error: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    fun stopTransmit() {
        capturing.set(false)
    }

    fun playIncoming(audio: ByteArray) {
        if (audio.isEmpty()) return
        playbackExecutor.execute {
            val track = ensureTrack() ?: return@execute
            runCatching { track.write(audio, 0, audio.size, AudioTrack.WRITE_BLOCKING) }
        }
    }

    fun release() {
        stopTransmit()
        runCatching { audioManager.mode = AudioManager.MODE_NORMAL }
        audioTrack?.let { runCatching { it.stop() }; it.release() }
        audioTrack = null
        playbackExecutor.shutdownNow()
    }

    private fun ensureTrack(): AudioTrack? {
        audioTrack?.let { return it }
        return try {
            val minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, ENCODING)
            if (minBuf <= 0) return null
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
                .setBufferSizeInBytes(max(minBuf, FRAME_BYTES * 6))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                track.release(); null
            } else {
                track.play(); audioTrack = track; track
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

    private inline fun <T> createEffect(available: () -> Boolean, create: () -> T?): T? =
        try { if (available()) create() else null } catch (_: Throwable) { null }

    private fun effectsSuffix(aec: Boolean, ns: Boolean, agc: Boolean): String {
        val on = buildList { if (aec) add("AEC"); if (ns) add("NS"); if (agc) add("AGC") }
        return if (on.isEmpty()) "" else " • " + on.joinToString("+")
    }

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        private const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
        private const val FRAME_MS = 20
        private const val FRAME_BYTES = (SAMPLE_RATE * FRAME_MS / 1000) * 2

        private const val PLAYBACK_QUEUE_FRAMES = 8 // ~160ms max pending audio

        private const val VAD_HANGOVER_FRAMES = 12
        private const val VAD_INITIAL_NOISE_FLOOR = 250.0
        private const val VAD_MIN_RMS = 520.0
        private const val VAD_NOISE_MULTIPLIER = 2.2
    }
}
