package com.meshvoice.app.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.util.UUID

/**
 * Regression tests for the Internet transport wire format.
 *
 * Beta 1 declared HEADER_BYTES = 37 while encode() only wrote 33 bytes, so
 * ByteBuffer.array() returned four zero bytes past the end of the audio and
 * the receiver decoded them as trailing PCM samples on every single frame.
 * The size assertions below fail loudly if that ever drifts again.
 */
class InternetNodeTest {

    private val silentListener = object : InternetNode.Listener {
        override fun onInternetState(connected: Boolean, message: String) = Unit
        override fun onInternetAudio(origin: String, audio: ByteArray) = Unit
        override fun onInternetPeerCount(count: Int) = Unit
        override fun onInternetPeerListChanged(peers: List<InternetNode.InternetPeer>) = Unit
    }

    private fun node() = InternetNode(silentListener)

    @Test
    fun encodedSizeIsExactlyHeaderPlusAudio() {
        val n = node()
        // 16 kHz mono PCM16, 20 ms frame.
        val audio = ByteArray(640) { (it % 127).toByte() }
        val encoded = n.encode(
            InternetNode.InternetPacket(UUID.randomUUID(), 1, 1L, audio)
        )
        // No stray padding: total must be the audio plus the real header width.
        assertEquals(33 + audio.size, encoded.size)
    }

    @Test
    fun roundTripPreservesAudioExactly() {
        val n = node()
        val origin = UUID.randomUUID()
        val audio = ByteArray(640) { (it % 127).toByte() }

        val decoded = n.decode(n.encode(InternetNode.InternetPacket(origin, 42, 123456789L, audio)))

        assertNotNull(decoded)
        decoded!!
        assertEquals(origin, decoded.origin)
        assertEquals(42, decoded.sequence)
        assertEquals(123456789L, decoded.timestampMs)
        // The core of the bug: audio came back longer than it went in.
        assertEquals(audio.size, decoded.audio.size)
        assertArrayEquals(audio, decoded.audio)
    }

    @Test
    fun emptyAudioRoundTripsWithoutPadding() {
        val n = node()
        val encoded = n.encode(InternetNode.InternetPacket(UUID.randomUUID(), 0, 0L, ByteArray(0)))
        assertEquals(33, encoded.size)

        val decoded = n.decode(encoded)
        assertNotNull(decoded)
        assertEquals(0, decoded!!.audio.size)
    }

    @Test
    fun presenceRoundTripCarriesRiderName() {
        val n = node()
        val origin = UUID.randomUUID()
        val encoded = n.encodePresence(origin, "Kabeer", 555L)

        val decoded = n.decodePresence(encoded)
        assertNotNull(decoded)
        assertEquals(origin, decoded!!.origin)
        assertEquals("Kabeer", decoded.name)
    }

    @Test
    fun presenceDecodeOfOlderNameLessPacketDefaultsToRider() {
        val n = node()
        val origin = UUID.randomUUID()
        // Pre-v1.1 presence packets were exactly PRESENCE_BYTES with no name
        // suffix at all -- must not be rejected just because it's shorter.
        val legacy = ByteBuffer.allocate(24)
            .putLong(origin.mostSignificantBits)
            .putLong(origin.leastSignificantBits)
            .putLong(999L)
            .array()

        val decoded = n.decodePresence(legacy)
        assertNotNull(decoded)
        assertEquals(origin, decoded!!.origin)
        assertEquals("Rider", decoded.name)
    }

    @Test
    fun presenceNameIsTruncatedNotOverflowed() {
        val n = node()
        val longName = "A".repeat(200)
        val encoded = n.encodePresence(UUID.randomUUID(), longName, 1L)
        val decoded = n.decodePresence(encoded)
        assertNotNull(decoded)
        // Must be capped, not the full 200 chars -- otherwise a malicious or
        // buggy peer could inflate every heartbeat packet arbitrarily.
        assert(decoded!!.name.length <= 20) { "name not capped: ${decoded.name.length} chars" }
    }

    @Test
    fun decodeRejectsShortAndCorruptBuffers() {
        val n = node()
        assertNull(n.decode(ByteArray(0)))
        assertNull(n.decode(ByteArray(32)))

        val encoded = n.encode(InternetNode.InternetPacket(UUID.randomUUID(), 1, 1L, ByteArray(8)))
        encoded[0] = (encoded[0] + 1).toByte() // corrupt the magic
        assertNull(n.decode(encoded))
    }
}
