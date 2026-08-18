package com.bikemesh.ridemesh.mesh

import android.content.Context
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionOptions
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionType
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
 * Local RideMesh transport.
 *
 * Nearby Connections P2P_CLUSTER supplies multiple direct nearby links.
 * RideMesh adds packet IDs + TTL broadcast forwarding for multi-hop relay.
 * NON_DISRUPTIVE connection mode is intentional: local mesh should coexist
 * with mobile/Wi-Fi Internet so the hybrid router can hand over cleanly.
 */
class MeshNode(
    context: Context,
    private val listener: Listener,
) {
    enum class LabRole { NORMAL, A, B, C }

    interface Listener {
        fun onLog(message: String)
        fun onDirectPeerCount(count: Int)
        fun onAudioPacket(audio: ByteArray)
    }

    private val client: ConnectionsClient = Nearby.getConnectionsClient(context)
    private val nodeId = UUID.randomUUID()
    private val sequence = AtomicInteger(0)
    private val connected = ConcurrentHashMap.newKeySet<String>()
    private val requested = ConcurrentHashMap.newKeySet<String>()
    private val endpointNames = ConcurrentHashMap<String, String>()

    private var riderName: String = "Rider"
    private var rideCode: String = "RIDE01"
    private var labRole: LabRole = LabRole.NORMAL
    @Volatile private var running = false

    private val seenPackets: MutableMap<UUID, Boolean> = Collections.synchronizedMap(
        object : LinkedHashMap<UUID, Boolean>(1024, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<UUID, Boolean>?): Boolean {
                return size > 4096
            }
        }
    )

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            try {
                if (payload.type != Payload.Type.BYTES) return
                val raw = payload.asBytes() ?: return
                val packet = MeshPacket.decode(raw) ?: return

                synchronized(seenPackets) {
                    if (seenPackets.containsKey(packet.packetId)) return
                    seenPackets[packet.packetId] = true
                }

                if (packet.origin != nodeId && packet.audio.isNotEmpty()) {
                    listener.onAudioPacket(packet.audio)
                }

                if (packet.ttl > 0) {
                    relay(packet.nextHop(), excludeEndpoint = endpointId)
                }
            } catch (t: Throwable) {
                listener.onLog("Payload error: ${t.javaClass.simpleName}: ${t.message ?: "unknown"}")
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
    }

    private val lifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            endpointNames[endpointId] = info.endpointName
            val remoteCode = parseRideCode(info.endpointName)
            val remoteRole = parseLabRole(info.endpointName)
            try {
                if (remoteCode == rideCode && isAllowedPeer(remoteRole)) {
                    client.acceptConnection(endpointId, payloadCallback)
                    listener.onLog("Pairing with ${displayName(info.endpointName)}")
                } else {
                    client.rejectConnection(endpointId)
                }
            } catch (t: Throwable) {
                listener.onLog("Pairing error: ${t.javaClass.simpleName}: ${t.message ?: "unknown"}")
            }
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            requested.remove(endpointId)
            if (resolution.status.isSuccess) {
                connected.add(endpointId)
                listener.onLog("Connected: ${displayName(endpointNames[endpointId] ?: endpointId)}")
            } else {
                listener.onLog("Connection failed: ${resolution.status.statusCode}")
            }
            listener.onDirectPeerCount(connected.size)
        }

        override fun onDisconnected(endpointId: String) {
            connected.remove(endpointId)
            requested.remove(endpointId)
            listener.onLog("Peer disconnected: ${displayName(endpointNames[endpointId] ?: endpointId)}")
            listener.onDirectPeerCount(connected.size)
        }
    }

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            endpointNames[endpointId] = info.endpointName
            if (!running || parseRideCode(info.endpointName) != rideCode) return
            if (!isAllowedPeer(parseLabRole(info.endpointName))) return
            if (connected.contains(endpointId) || !requested.add(endpointId)) return

            listener.onLog("Found ${displayName(info.endpointName)} — connecting")
            try {
                client.requestConnection(
                    advertisedName(),
                    endpointId,
                    lifecycleCallback,
                    ConnectionOptions.Builder()
                        .setConnectionType(ConnectionType.NON_DISRUPTIVE)
                        .build(),
                ).addOnFailureListener {
                    requested.remove(endpointId)
                    listener.onLog("Could not connect to ${displayName(info.endpointName)}: ${it.message ?: "error"}")
                }
            } catch (t: Throwable) {
                requested.remove(endpointId)
                listener.onLog("Connection request error: ${t.javaClass.simpleName}: ${t.message ?: "unknown"}")
            }
        }

        override fun onEndpointLost(endpointId: String) {
            requested.remove(endpointId)
        }
    }

    fun start(riderName: String, rideCode: String, labRole: LabRole = LabRole.NORMAL) {
        stop()
        this.riderName = riderName.trim().ifBlank { "Rider" }.take(18)
        this.rideCode = rideCode.trim().uppercase().ifBlank { "RIDE01" }.take(12)
        this.labRole = labRole
        running = true
        listener.onLog("Starting local mesh for ride ${this.rideCode}")

        val advertising = AdvertisingOptions.Builder()
            .setStrategy(STRATEGY)
            .setConnectionType(ConnectionType.NON_DISRUPTIVE)
            .build()
        val discovery = DiscoveryOptions.Builder()
            .setStrategy(STRATEGY)
            .build()

        try {
            client.startAdvertising(advertisedName(), SERVICE_ID, lifecycleCallback, advertising)
                .addOnSuccessListener { listener.onLog("Local mesh visible as ${this.riderName}") }
                .addOnFailureListener { listener.onLog("Advertising error: ${it.javaClass.simpleName}: ${it.message ?: "unknown"}") }

            client.startDiscovery(SERVICE_ID, discoveryCallback, discovery)
                .addOnSuccessListener { listener.onLog("Searching for nearby RideMesh riders") }
                .addOnFailureListener { listener.onLog("Discovery error: ${it.javaClass.simpleName}: ${it.message ?: "unknown"}") }
        } catch (t: Throwable) {
            running = false
            listener.onLog("Nearby start error: ${t.javaClass.simpleName}: ${t.message ?: "unknown"}")
        }
    }

    fun stop() {
        running = false
        runCatching { client.stopAdvertising() }
        runCatching { client.stopDiscovery() }
        runCatching { client.stopAllEndpoints() }
        connected.clear()
        requested.clear()
        endpointNames.clear()
        listener.onDirectPeerCount(0)
    }

    fun sendLocalAudio(audio: ByteArray) {
        if (!running || audio.isEmpty() || connected.isEmpty()) return
        val packet = MeshPacket(
            ttl = MAX_TTL,
            origin = nodeId,
            packetId = UUID.randomUUID(),
            sequence = sequence.incrementAndGet(),
            timestampMs = System.currentTimeMillis(),
            audio = audio,
        )
        synchronized(seenPackets) { seenPackets[packet.packetId] = true }
        relay(packet, excludeEndpoint = null)
    }

    private fun relay(packet: MeshPacket, excludeEndpoint: String?) {
        val bytes = packet.encode()
        for (endpoint in connected) {
            if (endpoint == excludeEndpoint) continue
            try {
                client.sendPayload(endpoint, Payload.fromBytes(bytes))
                    .addOnFailureListener {
                        listener.onLog("Send error: ${it.javaClass.simpleName}: ${it.message ?: "unknown"}")
                    }
            } catch (t: Throwable) {
                listener.onLog("Send error: ${t.javaClass.simpleName}: ${t.message ?: "unknown"}")
            }
        }
    }

    private fun advertisedName(): String = "$rideCode|$riderName|${labRole.name}|${nodeId.toString().take(8)}"

    private fun parseRideCode(endpointName: String): String = endpointName.substringBefore('|').uppercase()

    private fun parseLabRole(endpointName: String): LabRole {
        val parts = endpointName.split('|')
        return if (parts.size >= 3) {
            runCatching { LabRole.valueOf(parts[2].uppercase()) }.getOrDefault(LabRole.NORMAL)
        } else LabRole.NORMAL
    }

    private fun isAllowedPeer(remote: LabRole): Boolean {
        if (labRole == LabRole.NORMAL) return true
        return when (labRole) {
            LabRole.A -> remote == LabRole.B
            LabRole.B -> remote == LabRole.A || remote == LabRole.C
            LabRole.C -> remote == LabRole.B
            LabRole.NORMAL -> true
        }
    }

    private fun displayName(endpointName: String): String {
        val parts = endpointName.split('|')
        val name = if (parts.size >= 2) parts[1] else endpointName
        val role = parseLabRole(endpointName)
        return if (role == LabRole.NORMAL) name else "$name [${role.name}]"
    }

    companion object {
        private const val SERVICE_ID = "com.bikemesh.ridemesh.voice"
        private val STRATEGY = Strategy.P2P_CLUSTER
        private const val MAX_TTL = 4
    }
}