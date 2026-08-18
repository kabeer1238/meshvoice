package com.meshvoice.app.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

class MeshPacketTest {

    private fun samplePacket(audio: ByteArray) = MeshPacket(
        ttl = 4,
        origin = UUID.randomUUID(),
        packetId = UUID.randomUUID(),
        sequence = 7,
        timestampMs = System.currentTimeMillis(),
        audio = audio,
    )

    @Test
    fun `round trip with empty audio`() {
        val packet = samplePacket(ByteArray(0))
        val encoded = packet.encode()
        assertEquals(MeshPacket.HEADER_BYTES, encoded.size)
        assertEquals(packet, MeshPacket.decode(encoded))
    }

    @Test
    fun `round trip with typical 20ms PCM16 frame`() {
        // 16kHz mono, 20ms frame = 320 samples * 2 bytes
        val audio = ByteArray(640) { it.toByte() }
        val packet = samplePacket(audio)
        val encoded = packet.encode()
        assertEquals(MeshPacket.HEADER_BYTES + audio.size, encoded.size)

        val decoded = MeshPacket.decode(encoded)
        assertEquals(packet, decoded)
        assertEquals(audio.size, decoded!!.audio.size)
    }

    @Test
    fun `decode rejects too-short buffers`() {
        assertNull(MeshPacket.decode(ByteArray(MeshPacket.HEADER_BYTES - 1)))
        assertNull(MeshPacket.decode(ByteArray(0)))
    }

    @Test
    fun `decode rejects wrong magic`() {
        val encoded = samplePacket(ByteArray(10)).encode()
        encoded[0] = encoded[0].inc() // corrupt magic
        assertNull(MeshPacket.decode(encoded))
    }

    @Test
    fun `ttl decrements and floors at zero`() {
        val packet = samplePacket(ByteArray(0)).copy(ttl = 1)
        assertEquals(0, packet.nextHop().ttl)
        assertEquals(0, packet.nextHop().nextHop().ttl)
    }
}
