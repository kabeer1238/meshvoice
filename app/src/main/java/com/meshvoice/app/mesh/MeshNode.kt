package com.meshvoice.app.mesh

import android.content.Context
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import java.util.Collections
import java.util.LinkedHashMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Local, offline group-voice transport built on Nearby Connections.
 *
 * Design notes (see README for the bigger picture):
 *  - This node only ever moves opaque MeshPacket bytes. It knows nothing about
 *    audio codecs or UI state; MainActivity wires it up.
 *  - Every advertised endpoint name embeds a ride code. A peer is only
 *    accepted if that code matches ours. This is NOT cryptographic
 *    authentication -- it is a convenience filter, same as a Wi-Fi SSID.
 *    Do not treat matching ride codes as proof of membership; if you add
 *    anything sensitive, authenticate above this layer (see MeshPacket doc).
 *  - Multi-hop relay: every packet carries a TTL and a random packetId.
 *    Each node remembers packetIds it has already seen (bounded LRU) and
 *    only relays packets it hasn't seen and whose TTL is still > 0, and never
 *    echoes a packet back to the peer it just received it from. This is what
 *    prevents relay loops on a mesh topology.
 */
class MeshNode(
    context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onLog(message: String)
        fun onPeerCountChanged(count: Int)
        fun onAudioReceived(audio: ByteArray)
    }

    private val client: ConnectionsClient = Nearby.getConnectionsClient(context)
    private val nodeId: UUID = UUID.randomUUID()
    private val sequence = AtomicInteger(0)

    private val connectedEndpoints = ConcurrentHashMap.newKeySet<String>()
    private val pendingRequests = ConcurrentHashMap.newKeySet<String>()
    private val endpointNames = ConcurrentHashMap<String, String>()

    private var riderName: String = "Rider"
    private var rideCode: String = ""
    @Volatile private var running = false

    // Bounded LRU of packet IDs we've already relayed, so re-broadcasts on a
    // mesh don't loop forever. Oldest entries evicted once we exceed the cap.
    private val seenPacketIds: MutableMap<UUID, Boolean> = Collections.synchronizedMap(
        object : LinkedHashMap<UUID, Boolean>(SEEN_CACHE_INITIAL, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<UUID, Boolean>?): Boolean {
                return size > SEEN_CACHE_MAX
            }
        }
    )

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type != Payload.Type.BYTES) return
            val raw = payload.asBytes() ?: return
            val packet = MeshPacket.decode(raw) ?: return

            val alreadySeen = synchronized(seenPacketIds) {
                val seen = seenPacketIds.containsKey(packet.packetId)
                seenPacketIds[packet.packetId] = true
                seen
            }
            if (alreadySeen) return

            if (packet.origin != nodeId && packet.audio.isNotEmpty()) {
                listener.onAudioReceived(packet.audio)
            }

            if (packet.ttl > 0) {
                relay(packet.nextHop(), excludeEndpoint = endpointId)
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
    }

    private val lifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            endpointNames[endpointId] = info.endpointName
            if (parseRideCode(info.endpointName) == rideCode) {
                runCatching { client.acceptConnection(endpointId, payloadCallback) }
                listener.onLog("Pairing with ${displayName(info.endpointName)}")
            } else {
                runCatching { client.rejectConnection(endpointId) }
            }
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            pendingRequests.remove(endpointId)
            if (resolution.status.isSuccess) {
                connectedEndpoints.add(endpointId)
                listener.onLog("Connected: ${displayName(endpointNames[endpointId] ?: endpointId)}")
            } else {
                listener.onLog("Connection failed (${resolution.status.statusCode})")
            }
            listener.onPeerCountChanged(connectedEndpoints.size)
        }

        override fun onDisconnected(endpointId: String) {
            connectedEndpoints.remove(endpointId)
            pendingRequests.remove(endpointId)
            listener.onLog("Peer disconnected: ${displayName(endpointNames[endpointId] ?: endpointId)}")
            listener.onPeerCountChanged(connectedEndpoints.size)
        }
    }

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            endpointNames[endpointId] = info.endpointName
            if (!running || parseRideCode(info.endpointName) != rideCode) return
            if (connectedEndpoints.contains(endpointId) || !pendingRequests.add(endpointId)) return

            client.requestConnection(advertisedName(), endpointId, lifecycleCallback)
                .addOnFailureListener {
                    pendingRequests.remove(endpointId)
                    listener.onLog("Could not reach ${displayName(info.endpointName)}")
                }
        }

        override fun onEndpointLost(endpointId: String) {
            pendingRequests.remove(endpointId)
        }
    }

    /** Starts advertising + discovering for [rideCode]. Idempotent restart. */
    fun start(riderName: String, rideCode: String) {
        stop()
        this.riderName = riderName.trim().ifBlank { "Rider" }.take(MAX_NAME_LEN)
        this.rideCode = normalizeCode(rideCode)
        running = true
        listener.onLog("Local mesh starting for ride ${this.rideCode}")

        val advertising = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        val discovery = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()

        try {
            client.startAdvertising(advertisedName(), SERVICE_ID, lifecycleCallback, advertising)
                .addOnFailureListener { listener.onLog("Advertising error: ${it.message}") }
            client.startDiscovery(SERVICE_ID, discoveryCallback, discovery)
                .addOnFailureListener { listener.onLog("Discovery error: ${it.message}") }
        } catch (t: Throwable) {
            running = false
            listener.onLog("Mesh start error: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    fun stop() {
        running = false
        runCatching { client.stopAdvertising() }
        runCatching { client.stopDiscovery() }
        runCatching { client.stopAllEndpoints() }
        connectedEndpoints.clear()
        pendingRequests.clear()
        endpointNames.clear()
        listener.onPeerCountChanged(0)
    }

    fun peerCount(): Int = connectedEndpoints.size

    /** Sends a locally captured audio frame into the mesh. */
    fun sendAudio(audio: ByteArray) {
        if (!running || audio.isEmpty() || connectedEndpoints.isEmpty()) return
        val packet = MeshPacket(
            ttl = MAX_TTL,
            origin = nodeId,
            packetId = UUID.randomUUID(),
            sequence = sequence.incrementAndGet(),
            timestampMs = System.currentTimeMillis(),
            audio = audio,
        )
        synchronized(seenPacketIds) { seenPacketIds[packet.packetId] = true }
        relay(packet, excludeEndpoint = null)
    }

    private fun relay(packet: MeshPacket, excludeEndpoint: String?) {
        val bytes = packet.encode()
        for (endpoint in connectedEndpoints) {
            if (endpoint == excludeEndpoint) continue
            runCatching { client.sendPayload(endpoint, Payload.fromBytes(bytes)) }
        }
    }

    private fun advertisedName(): String = "$rideCode|$riderName|${nodeId.toString().take(8)}"

    private fun parseRideCode(endpointName: String): String = endpointName.substringBefore('|')

    private fun displayName(endpointName: String): String =
        endpointName.split('|').getOrNull(1) ?: endpointName

    private fun normalizeCode(code: String): String =
        code.trim().uppercase().ifBlank { "RIDE01" }.take(12)

    companion object {
        private const val SERVICE_ID = "com.meshvoice.app.voice"
        private val STRATEGY = Strategy.P2P_CLUSTER
        private const val MAX_TTL = 4
        private const val MAX_NAME_LEN = 18
        private const val SEEN_CACHE_INITIAL = 1024
        private const val SEEN_CACHE_MAX = 4096
    }
}
