package com.bikemesh.ridemesh.mesh

import android.content.Context
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionOptions
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionType
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Short-lived nearby discovery/invitation channel.
 *
 * It is also used while an Internet ride is already active. In that case the
 * voice call stays on Internet while this lobby runs for a short scan window.
 */
class LobbyNode(
    context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onLobbyLog(message: String)
        fun onNearbyRiderFound(endpointId: String, riderName: String, rideCode: String)
        fun onNearbyRiderLost(endpointId: String)
        fun onRideInviteReceived(inviterName: String, rideCode: String)
    }

    private data class PendingInvite(val rideCode: String, val inviterName: String)

    private val client = Nearby.getConnectionsClient(context)
    private val nodeId = UUID.randomUUID().toString().take(8)
    private val endpointNames = ConcurrentHashMap<String, String>()
    private val pendingInvites = ConcurrentHashMap<String, PendingInvite>()
    private val connected = ConcurrentHashMap.newKeySet<String>()

    @Volatile private var running = false
    private var riderName = "Rider"
    private var rideCode = "RIDE01"

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type != Payload.Type.BYTES) return
            val raw = payload.asBytes()?.toString(Charsets.UTF_8) ?: return
            val parts = raw.split('|', limit = 3)
            if (parts.size == 3 && parts[0] == INVITE_PREFIX) {
                val code = parts[1].trim().uppercase().take(12)
                val inviter = parts[2].trim().ifBlank { "Nearby rider" }.take(18)
                listener.onRideInviteReceived(inviter, code)
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
    }

    private val lifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            endpointNames[endpointId] = info.endpointName
            client.acceptConnection(endpointId, payloadCallback)
                .addOnFailureListener {
                    listener.onLobbyLog("Could not accept invite channel: ${it.message ?: "error"}")
                }
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            if (!resolution.status.isSuccess) {
                pendingInvites.remove(endpointId)
                listener.onLobbyLog("Invite connection failed: ${resolution.status.statusCode}")
                return
            }

            connected.add(endpointId)
            val invite = pendingInvites.remove(endpointId) ?: return
            val wire = "$INVITE_PREFIX|${invite.rideCode}|${invite.inviterName}".toByteArray(Charsets.UTF_8)
            client.sendPayload(endpointId, Payload.fromBytes(wire))
                .addOnSuccessListener {
                    listener.onLobbyLog("Invite sent to ${displayName(endpointNames[endpointId] ?: endpointId)}")
                }
                .addOnFailureListener {
                    listener.onLobbyLog("Could not send invite: ${it.message ?: "error"}")
                }
        }

        override fun onDisconnected(endpointId: String) {
            connected.remove(endpointId)
        }
    }

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (!running || !info.endpointName.startsWith("RM|")) return
            endpointNames[endpointId] = info.endpointName
            val parts = info.endpointName.split('|')
            val name = parts.getOrNull(1)?.ifBlank { "Rider" } ?: "Rider"
            val code = parts.getOrNull(2)?.ifBlank { "RIDE01" } ?: "RIDE01"
            listener.onNearbyRiderFound(endpointId, name.take(18), code.take(12))
        }

        override fun onEndpointLost(endpointId: String) {
            endpointNames.remove(endpointId)
            pendingInvites.remove(endpointId)
            listener.onNearbyRiderLost(endpointId)
        }
    }

    fun start(riderName: String, rideCode: String) {
        stop()
        this.riderName = riderName.trim().ifBlank { "Rider" }.take(18)
        this.rideCode = rideCode.trim().uppercase().ifBlank { "RIDE01" }.take(12)
        running = true

        // NON_DISRUPTIVE keeps Nearby from tearing down the phone's existing
        // Wi-Fi/Internet connection while scanning. Without this the lobby
        // scan could knock a rider off mobile data at the exact moment the
        // Internet transport was trying to keep the ride alive.
        val advertising = AdvertisingOptions.Builder()
            .setStrategy(STRATEGY)
            .setConnectionType(ConnectionType.NON_DISRUPTIVE)
            .build()
        val discovery = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()

        try {
            client.startAdvertising(advertisedName(), SERVICE_ID, lifecycleCallback, advertising)
                .addOnSuccessListener { listener.onLobbyLog("Nearby rider visibility ON") }
                .addOnFailureListener { listener.onLobbyLog("Nearby rider advertising error: ${it.message ?: "unknown"}") }

            client.startDiscovery(SERVICE_ID, discoveryCallback, discovery)
                .addOnSuccessListener { listener.onLobbyLog("Searching for RideMesh riders nearby") }
                .addOnFailureListener { listener.onLobbyLog("Nearby rider discovery error: ${it.message ?: "unknown"}") }
        } catch (t: Throwable) {
            running = false
            listener.onLobbyLog("Nearby rider search could not start: ${t.javaClass.simpleName}: ${t.message ?: "unknown"}")
        }
    }

    fun invite(endpointId: String, rideCode: String, inviterName: String) {
        val name = endpointNames[endpointId]
        if (!running || name == null) {
            listener.onLobbyLog("That rider is no longer visible")
            return
        }

        pendingInvites[endpointId] = PendingInvite(
            rideCode = rideCode.trim().uppercase().ifBlank { "RIDE01" }.take(12),
            inviterName = inviterName.trim().ifBlank { "Rider" }.take(18),
        )

        if (connected.contains(endpointId)) {
            val invite = pendingInvites.remove(endpointId) ?: return
            val wire = "$INVITE_PREFIX|${invite.rideCode}|${invite.inviterName}".toByteArray(Charsets.UTF_8)
            client.sendPayload(endpointId, Payload.fromBytes(wire))
            return
        }

        val options = ConnectionOptions.Builder()
            .setConnectionType(ConnectionType.NON_DISRUPTIVE)
            .build()

        client.requestConnection(advertisedName(), endpointId, lifecycleCallback, options)
            .addOnFailureListener {
                pendingInvites.remove(endpointId)
                listener.onLobbyLog("Could not invite ${displayName(name)}: ${it.message ?: "error"}")
            }
    }

    fun stop() {
        running = false
        runCatching { client.stopAdvertising() }
        runCatching { client.stopDiscovery() }
        // Only disconnect the short-lived lobby endpoints we own. Do not use
        // stopAllEndpoints() here because an active RideMesh voice path may exist.
        connected.toList().forEach { endpoint ->
            runCatching { client.disconnectFromEndpoint(endpoint) }
        }
        endpointNames.keys.forEach { listener.onNearbyRiderLost(it) }
        endpointNames.clear()
        pendingInvites.clear()
        connected.clear()
    }

    private fun advertisedName(): String = "RM|$riderName|$rideCode|$nodeId"

    private fun displayName(endpointName: String): String {
        val parts = endpointName.split('|')
        return parts.getOrNull(1)?.ifBlank { endpointName } ?: endpointName
    }

    companion object {
        private const val SERVICE_ID = "com.bikemesh.ridemesh.lobby"
        private const val INVITE_PREFIX = "RIDEMESH_INVITE"
        private val STRATEGY = Strategy.P2P_CLUSTER
    }
}
