package com.meshvoice.app.audio

import org.concentus.OpusApplication
import org.concentus.OpusDecoder
import org.concentus.OpusEncoder

/**
 * Wraps Concentus (a pure-Java Opus port, vendored directly under
 * app/src/main/java/org/concentus -- see VENDORED_FROM.md in that
 * directory for why it's source rather than a Maven dependency) so every
 * call into that code lives in one small, easily-fixed file rather than
 * scattered through AudioEngine.
 *
 * Compresses each 20ms/16kHz mono voice frame from ~640 bytes of raw PCM16
 * down to roughly 40-80 bytes at typical speech bitrates -- an 8-16x
 * reduction that matters twice over here: less load on the shared public
 * MQTT broker on the Internet path, and less airtime per hop on the local
 * mesh, where the same payload gets retransmitted at every relay.
 *
 * One encoder per AudioEngine instance (this device's own outgoing voice).
 * One decoder PER REMOTE ORIGIN, not shared -- Opus is a stateful codec and
 * packets from a given sender must be decoded serially, in order, by a
 * decoder that has only ever seen that sender's packets. Mixing two
 * senders' packets through one decoder would corrupt both streams.
 */
object OpusCodec {
    private const val SAMPLE_RATE = 16_000
    private const val CHANNELS = 1
    private const val BITRATE_BPS = 24_000
    private const val MAX_ENCODED_BYTES = 512

    fun newEncoder(): OpusEncoder =
        OpusEncoder(SAMPLE_RATE, CHANNELS, OpusApplication.OPUS_APPLICATION_VOIP).apply {
            bitrate = BITRATE_BPS
        }

    fun newDecoder(): OpusDecoder = OpusDecoder(SAMPLE_RATE, CHANNELS)

    /** Encodes one PCM16 little-endian frame. Returns null (caller should drop the frame) on any codec error. */
    fun encode(encoder: OpusEncoder, pcm: ByteArray): ByteArray? {
        return try {
            val samples = bytesToShorts(pcm)
            val out = ByteArray(MAX_ENCODED_BYTES)
            val written = encoder.encode(samples, 0, samples.size, out, 0, out.size)
            if (written <= 0) null else out.copyOf(written)
        } catch (_: Throwable) {
            null
        }
    }

    /** Decodes one Opus packet back to PCM16 little-endian. Returns null on any codec error. */
    fun decode(decoder: OpusDecoder, opusData: ByteArray, frameSizeSamples: Int): ByteArray? {
        return try {
            val samples = ShortArray(frameSizeSamples)
            val decoded = decoder.decode(opusData, 0, opusData.size, samples, 0, frameSizeSamples, false)
            if (decoded <= 0) null else shortsToBytes(samples, decoded)
        } catch (_: Throwable) {
            null
        }
    }

    private fun bytesToShorts(bytes: ByteArray): ShortArray {
        val out = ShortArray(bytes.size / 2)
        var i = 0
        var s = 0
        while (i + 1 < bytes.size) {
            val lo = bytes[i].toInt() and 0xff
            val hi = bytes[i + 1].toInt()
            out[s] = ((hi shl 8) or lo).toShort()
            i += 2
            s++
        }
        return out
    }

    private fun shortsToBytes(shorts: ShortArray, count: Int): ByteArray {
        val out = ByteArray(count * 2)
        for (s in 0 until count) {
            val v = shorts[s].toInt()
            out[s * 2] = (v and 0xff).toByte()
            out[s * 2 + 1] = ((v shr 8) and 0xff).toByte()
        }
        return out
    }
}
