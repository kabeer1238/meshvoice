package com.bikemesh.ridemesh.mesh

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.util.UUID

class MeshPacketTest {
    @Test
    fun packetRoundTripPreservesAudioAndRoutingFields() {
        val origin = UUID.randomUUID()
        val id = UUID.randomUUID()
        val audio = ByteArray(640) { (it % 127).toByte() }
        val original = MeshPacket(4, origin, id, 42, 123456789L, audio)

        val decoded = MeshPacket.decode(original.encode())
        assertNotNull(decoded)
        decoded!!
        assertEquals(4, decoded.ttl)
        assertEquals(origin, decoded.origin)
        assertEquals(id, decoded.packetId)
        assertEquals(42, decoded.sequence)
        assertEquals(123456789L, decoded.timestampMs)
        assertArrayEquals(audio, decoded.audio)
    }

    @Test
    fun nextHopDecrementsTtlOnly() {
        val p = MeshPacket(2, UUID.randomUUID(), UUID.randomUUID(), 1, 1L, byteArrayOf(1, 2))
        assertEquals(1, p.nextHop().ttl)
        assertEquals(p.packetId, p.nextHop().packetId)
    }
}
