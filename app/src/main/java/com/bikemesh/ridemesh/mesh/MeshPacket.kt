package com.bikemesh.ridemesh.mesh

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

data class MeshPacket(
    val ttl: Int,
    val origin: UUID,
    val packetId: UUID,
    val sequence: Int,
    val timestampMs: Long,
    val audio: ByteArray,
) {
    fun encode(): ByteArray {
        val buffer = ByteBuffer.allocate(HEADER_BYTES + audio.size).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(MAGIC)
        buffer.put(VERSION)
        buffer.put(ttl.coerceIn(0, 255).toByte())
        buffer.putLong(origin.mostSignificantBits)
        buffer.putLong(origin.leastSignificantBits)
        buffer.putLong(packetId.mostSignificantBits)
        buffer.putLong(packetId.leastSignificantBits)
        buffer.putInt(sequence)
        buffer.putLong(timestampMs)
        buffer.put(audio)
        return buffer.array()
    }

    fun nextHop(): MeshPacket = copy(ttl = (ttl - 1).coerceAtLeast(0))

    companion object {
        private const val MAGIC = 0x524D5631 // "RMV1"
        private const val VERSION: Byte = 1
        private const val HEADER_BYTES = 50

        fun decode(bytes: ByteArray): MeshPacket? {
            if (bytes.size < HEADER_BYTES) return null
            return try {
                val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
                if (buffer.int != MAGIC) return null
                if (buffer.get() != VERSION) return null
                val ttl = buffer.get().toInt() and 0xFF
                val origin = UUID(buffer.long, buffer.long)
                val packetId = UUID(buffer.long, buffer.long)
                val sequence = buffer.int
                val timestamp = buffer.long
                val audio = ByteArray(buffer.remaining())
                buffer.get(audio)
                MeshPacket(ttl, origin, packetId, sequence, timestamp, audio)
            } catch (_: Throwable) {
                null
            }
        }
    }
}
