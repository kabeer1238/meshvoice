package com.bikemesh.ridemesh.transport

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.random.Random

/**
 * Experimental Internet transport for field testing.
 *
 * Implements the small MQTT 3.1.1 subset RideMesh needs (CONNECT, SUBSCRIBE,
 * QoS-0 PUBLISH and PING) without a large networking dependency. Audio and a
 * tiny presence heartbeat share the same ride topic tree. The public broker
 * remains TEST-ONLY infrastructure.
 */
class InternetNode(private val listener: Listener) {
    interface Listener {
        fun onInternetState(connected: Boolean, message: String)
        fun onInternetAudio(audio: ByteArray)
        fun onInternetPeerCount(count: Int)
    }

    private val nodeId = UUID.randomUUID()
    private val sequence = AtomicInteger(0)
    private val connected = AtomicBoolean(false)
    private val running = AtomicBoolean(false)
    private val outputLock = Any()
    private val peers = ConcurrentHashMap<UUID, Long>()
    private val reportedPeerCount = AtomicInteger(-1)

    @Volatile private var baseTopic: String = ""
    @Volatile private var audioTopic: String = ""
    @Volatile private var presenceTopic: String = ""
    @Volatile private var subscriptionTopic: String = ""
    @Volatile private var socket: SSLSocket? = null
    @Volatile private var output: BufferedOutputStream? = null
    @Volatile private var worker: Thread? = null

    fun start(rideCode: String) {
        stop()
        val safeRide = rideCode.trim().uppercase().ifBlank { "RIDE01" }
            .replace(Regex("[^A-Z0-9_-]"), "_")
            .take(32)
        baseTopic = "ridemesh/test/v2/$safeRide"
        audioTopic = "$baseTopic/audio"
        presenceTopic = "$baseTopic/presence"
        subscriptionTopic = "$baseTopic/#"
        running.set(true)
        listener.onInternetState(false, "Internet relay connecting…")

        worker = Thread({ connectionLoop() }, "RideMesh-Internet").apply {
            isDaemon = true
            start()
        }
    }

    fun isConnected(): Boolean = connected.get()

    fun remotePeerCount(): Int = peers.size

    fun sendLocalAudio(audio: ByteArray): Boolean {
        if (audio.isEmpty() || !connected.get()) return false
        val packet = encode(
            InternetPacket(
                origin = nodeId,
                sequence = sequence.incrementAndGet(),
                timestampMs = System.currentTimeMillis(),
                audio = audio,
            )
        )
        return try {
            sendMqttPublish(audioTopic, packet)
            true
        } catch (_: Throwable) {
            markDisconnected("Internet send failed • switching to local mesh")
            closeSocket()
            false
        }
    }

    fun stop() {
        running.set(false)
        connected.set(false)
        clearPeers()
        closeSocket()
        worker?.interrupt()
        worker = null
    }

    private fun connectionLoop() {
        var backoffMs = RECONNECT_DELAY_MS
        while (running.get()) {
            try {
                connectAndRead()
                // Reached only after a session that actually connected and
                // then ended, so the next retry starts from the floor again.
                backoffMs = RECONNECT_DELAY_MS
            } catch (_: InterruptedException) {
                break
            } catch (_: Throwable) {
                if (running.get()) markDisconnected("Internet relay unavailable • local mesh fallback")
            } finally {
                closeSocket()
            }

            if (running.get()) {
                // Jitter spreads a group's reconnect attempts out instead of
                // having every rider retry on the same tick.
                val jitter = Random.nextLong(0L, backoffMs / 2 + 1L)
                try {
                    Thread.sleep(backoffMs + jitter)
                } catch (_: InterruptedException) {
                    break
                }
                backoffMs = (backoffMs * 2).coerceAtMost(RECONNECT_MAX_DELAY_MS)
                listener.onInternetState(false, "Internet reconnecting… • local mesh available")
            }
        }
    }

    private fun connectAndRead() {
        val tls = (SSLSocketFactory.getDefault()
            .createSocket(PUBLIC_BROKER, PUBLIC_BROKER_TLS_PORT) as SSLSocket).apply {
            soTimeout = SOCKET_TIMEOUT_MS
            startHandshake()
        }
        socket = tls
        val input = BufferedInputStream(tls.inputStream)
        output = BufferedOutputStream(tls.outputStream)

        sendRaw(connectPacket())
        val connAck = readPacket(input)
        if (connAck.type != 2 || connAck.body.size < 2 || connAck.body[1].toInt() != 0) {
            throw IllegalStateException("MQTT broker rejected connection")
        }

        sendRaw(subscribePacket(subscriptionTopic))
        connected.set(true)
        clearPeers()
        listener.onInternetState(true, "Internet voice connected")
        publishPresence()

        var lastPing = System.currentTimeMillis()
        var lastPresence = System.currentTimeMillis()

        while (running.get() && !tls.isClosed) {
            try {
                val mqtt = readPacket(input)
                if (mqtt.type == 3) handlePublish(mqtt.body)
            } catch (_: java.net.SocketTimeoutException) {
                // Used as a periodic wake-up for keepalive and presence cleanup.
            }

            val now = System.currentTimeMillis()
            if (now - lastPresence >= PRESENCE_INTERVAL_MS) {
                publishPresence()
                prunePeers(now)
                lastPresence = now
            }
            if (now - lastPing >= PING_INTERVAL_MS) {
                sendRaw(byteArrayOf(0xC0.toByte(), 0x00))
                lastPing = now
            }
        }
    }

    private fun handlePublish(body: ByteArray) {
        if (body.size < 2) return
        val topicLen = ((body[0].toInt() and 0xff) shl 8) or (body[1].toInt() and 0xff)
        if (topicLen <= 0 || body.size < 2 + topicLen) return
        val receivedTopic = body.copyOfRange(2, 2 + topicLen).toString(Charsets.UTF_8)
        val payload = body.copyOfRange(2 + topicLen, body.size)

        when (receivedTopic) {
            audioTopic -> {
                val packet = decode(payload) ?: return
                if (packet.origin == nodeId) return
                markPeer(packet.origin)
                listener.onInternetAudio(packet.audio)
            }
            presenceTopic -> handlePresence(payload)
        }
    }

    private fun publishPresence() {
        if (!connected.get()) return
        val payload = ByteBuffer.allocate(PRESENCE_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .putLong(nodeId.mostSignificantBits)
            .putLong(nodeId.leastSignificantBits)
            .putLong(System.currentTimeMillis())
            .array()
        sendMqttPublish(presenceTopic, payload)
    }

    private fun handlePresence(payload: ByteArray) {
        if (payload.size < PRESENCE_BYTES) return
        try {
            val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
            val origin = UUID(buffer.long, buffer.long)
            buffer.long // sender timestamp; local receive time is used for expiry
            if (origin == nodeId) return
            markPeer(origin)
        } catch (_: Throwable) {
        }
    }

    private fun markPeer(id: UUID) {
        peers[id] = System.currentTimeMillis()
        notifyPeerCount()
    }

    private fun prunePeers(now: Long) {
        peers.entries.removeIf { now - it.value > PRESENCE_TIMEOUT_MS }
        notifyPeerCount()
    }

    private fun clearPeers() {
        peers.clear()
        notifyPeerCount(force = true)
    }

    private fun notifyPeerCount(force: Boolean = false) {
        val count = peers.size
        val previous = reportedPeerCount.getAndSet(count)
        if (force || previous != count) listener.onInternetPeerCount(count)
    }

    private fun sendMqttPublish(topic: String, payload: ByteArray) {
        val topicBytes = topic.toByteArray(Charsets.UTF_8)
        val variable = ByteArrayOutputStream().apply {
            writeUtf8(topicBytes)
            write(payload)
        }.toByteArray()
        sendRaw(fixedPacket(0x30, variable)) // PUBLISH, QoS 0
    }

    private fun connectPacket(): ByteArray {
        val clientId = "rm-${nodeId.toString().replace("-", "").take(20)}".toByteArray(Charsets.UTF_8)
        val body = ByteArrayOutputStream().apply {
            writeUtf8("MQTT".toByteArray(Charsets.UTF_8))
            write(0x04) // MQTT 3.1.1
            write(0x02) // clean session
            write((KEEP_ALIVE_SECONDS shr 8) and 0xff)
            write(KEEP_ALIVE_SECONDS and 0xff)
            writeUtf8(clientId)
        }.toByteArray()
        return fixedPacket(0x10, body)
    }

    private fun subscribePacket(topic: String): ByteArray {
        val topicBytes = topic.toByteArray(Charsets.UTF_8)
        val body = ByteArrayOutputStream().apply {
            write(0x00)
            write(0x01) // packet id 1
            writeUtf8(topicBytes)
            write(0x00) // QoS 0
        }.toByteArray()
        return fixedPacket(0x82, body)
    }

    private fun sendRaw(packet: ByteArray) {
        val out = output ?: throw IllegalStateException("Internet relay not connected")
        synchronized(outputLock) {
            out.write(packet)
            out.flush()
        }
    }

    private data class MqttPacket(val type: Int, val body: ByteArray)

    private fun readPacket(input: BufferedInputStream): MqttPacket {
        val first = input.read()
        if (first < 0) throw EOFException()
        val remaining = readRemainingLength(input)
        val body = ByteArray(remaining)
        DataInputStream(input).readFully(body)
        return MqttPacket((first ushr 4) and 0x0f, body)
    }

    private fun readRemainingLength(input: BufferedInputStream): Int {
        var multiplier = 1
        var value = 0
        var loops = 0
        while (true) {
            val digit = input.read()
            if (digit < 0) throw EOFException()
            value += (digit and 127) * multiplier
            if ((digit and 128) == 0) return value
            multiplier *= 128
            loops++
            if (loops >= 4) throw IllegalStateException("Malformed MQTT remaining length")
        }
    }

    private fun fixedPacket(header: Int, body: ByteArray): ByteArray {
        val remaining = encodeRemainingLength(body.size)
        return ByteArray(1 + remaining.size + body.size).also { packet ->
            packet[0] = header.toByte()
            remaining.copyInto(packet, 1)
            body.copyInto(packet, 1 + remaining.size)
        }
    }

    private fun encodeRemainingLength(length: Int): ByteArray {
        var x = length
        val out = ByteArrayOutputStream(4)
        do {
            var digit = x % 128
            x /= 128
            if (x > 0) digit = digit or 0x80
            out.write(digit)
        } while (x > 0)
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.writeUtf8(bytes: ByteArray) {
        write((bytes.size shr 8) and 0xff)
        write(bytes.size and 0xff)
        write(bytes)
    }

    private fun markDisconnected(message: String) {
        val wasConnected = connected.getAndSet(false)
        clearPeers()
        if (wasConnected || running.get()) listener.onInternetState(false, message)
    }

    private fun closeSocket() {
        connected.set(false)
        synchronized(outputLock) {
            try {
                output?.close()
            } catch (_: Throwable) {
            }
            output = null
            try {
                socket?.close()
            } catch (_: Throwable) {
            }
            socket = null
        }
    }

    internal data class InternetPacket(
        val origin: UUID,
        val sequence: Int,
        val timestampMs: Long,
        val audio: ByteArray,
    )

    internal fun encode(packet: InternetPacket): ByteArray {
        val buffer = ByteBuffer.allocate(HEADER_BYTES + packet.audio.size).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(MAGIC)
        buffer.put(VERSION)
        buffer.putLong(packet.origin.mostSignificantBits)
        buffer.putLong(packet.origin.leastSignificantBits)
        buffer.putInt(packet.sequence)
        buffer.putLong(packet.timestampMs)
        buffer.put(packet.audio)
        // Guard against HEADER_BYTES drifting out of sync with the fields
        // actually written above. Before v1.1 this constant was 37 while only
        // 33 bytes were written, so every frame carried 4 stray zero bytes
        // that the receiver decoded as trailing audio samples.
        check(!buffer.hasRemaining()) { "InternetNode header size mismatch" }
        return buffer.array()
    }

    internal fun decode(bytes: ByteArray): InternetPacket? {
        if (bytes.size < HEADER_BYTES) return null
        return try {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            if (buffer.int != MAGIC) return null
            if (buffer.get() != VERSION) return null
            val origin = UUID(buffer.long, buffer.long)
            val sequence = buffer.int
            val timestamp = buffer.long
            val audio = ByteArray(buffer.remaining())
            buffer.get(audio)
            InternetPacket(origin, sequence, timestamp, audio)
        } catch (_: Throwable) {
            null
        }
    }

    companion object {
        private const val PUBLIC_BROKER = "broker.hivemq.com"
        private const val PUBLIC_BROKER_TLS_PORT = 8883
        private const val KEEP_ALIVE_SECONDS = 30
        private const val SOCKET_TIMEOUT_MS = 7_000
        private const val PING_INTERVAL_MS = 15_000L
        private const val PRESENCE_INTERVAL_MS = 10_000L
        private const val PRESENCE_TIMEOUT_MS = 32_000L
        // Reconnect backoff: starts at RECONNECT_DELAY_MS and doubles up to
        // RECONNECT_MAX_DELAY_MS, with jitter. A flat 2s retry meant every
        // rider in a group hammered the broker in lockstep after a tunnel or
        // coverage drop, which is exactly when reconnection matters most.
        private const val RECONNECT_DELAY_MS = 2_000L
        private const val RECONNECT_MAX_DELAY_MS = 30_000L
        private const val PRESENCE_BYTES = 24
        private const val MAGIC = 0x524D4931 // RMI1
        private const val VERSION: Byte = 1

        // 4 (magic) + 1 (version) + 16 (origin UUID) + 4 (sequence) + 8 (timestamp)
        private const val HEADER_BYTES = 4 + 1 + 16 + 4 + 8 // = 33
    }
}
