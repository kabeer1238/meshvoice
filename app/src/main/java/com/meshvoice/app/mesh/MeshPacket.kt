package com.meshvoice.app.mesh

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Wire format for one audio packet on the local mesh transport.
 *
 * Layout (big-endian):
 *   4 bytes  magic
 *   1 byte   version
 *   1 byte   ttl
 *   16 bytes origin node id (UUID)
 *   16 bytes packet id (UUID)     <- used for relay dedup; receiver-independent
 *   4 bytes  sequence
 *   8 bytes  timestampMs
 *   N bytes  audio (PCM16 mono frame)
 *
 * HEADER_BYTES below is intentionally computed from the field widths rather
 * than hard-coded, and MeshPacketTest asserts encode()/decode() round-trip
 * exactly for a range of payload sizes including empty audio. This guards
 * against the classic "declared header size != bytes actually written" bug
 * (silently appends/truncates N trailing bytes of every audio frame).
 */
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
        check(!buffer.hasRemaining()) { "MeshPacket.encode() header size mismatch" }
        return buffer.array()
    }

    fun nextHop(): MeshPacket = copy(ttl = (ttl - 1).coerceAtLeast(0))

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MeshPacket) return false
        return ttl == other.ttl && origin == other.origin && packetId == other.packetId &&
            sequence == other.sequence && timestampMs == other.timestampMs &&
            audio.contentEquals(other.audio)
    }

    override fun hashCode(): Int {
        var result = ttl
        result = 31 * result + origin.hashCode()
        result = 31 * result + packetId.hashCode()
        result = 31 * result + sequence
        result = 31 * result + timestampMs.hashCode()
        result = 31 * result + audio.contentHashCode()
        return result
    }

    companion object {
        private const val MAGIC = 0x4D565031 // "MVP1"
        private const val VERSION: Byte = 1

        // 4 (magic) + 1 (version) + 1 (ttl) + 16 (origin) + 16 (packetId) + 4 (sequence) + 8 (timestamp)
        const val HEADER_BYTES = 4 + 1 + 1 + 16 + 16 + 4 + 8 // = 50

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
