package com.bikemesh.ridemesh.transport

/**
 * Platform-client transport boundary.
 *
 * RideMesh protocol packets must be created above this layer. A transport only
 * discovers/reaches peers and moves opaque RideMesh envelope bytes.
 */
interface RideMeshTransport {
    val kind: TransportKind
    val capabilities: TransportCapabilities
    val state: TransportState

    fun start()
    fun stop()

    /**
     * Sends one already-encoded RideMesh protocol envelope.
     * A null destination means the transport may deliver to all currently
     * appropriate ride peers; final routing/deduplication still belongs above
     * the transport layer.
     */
    fun send(envelope: ByteArray, destinationNodeId: String? = null): Boolean

    fun setListener(listener: Listener?)

    interface Listener {
        fun onEnvelopeReceived(
            transport: RideMeshTransport,
            fromPeerId: String?,
            envelope: ByteArray,
        )

        fun onStateChanged(transport: RideMeshTransport, state: TransportState)

        fun onMetricsChanged(transport: RideMeshTransport, metrics: TransportMetrics)
    }
}

enum class TransportKind {
    NEARBY,
    WIFI_AWARE,
    WIFI_DIRECT,
    LOCAL_NETWORK,
    INTERNET,
}

enum class TransportState {
    STOPPED,
    STARTING,
    AVAILABLE,
    DEGRADED,
    UNAVAILABLE,
}

data class TransportCapabilities(
    val supportsDiscovery: Boolean,
    val supportsMultiplePeers: Boolean,
    val supportsInternetReach: Boolean,
    val supportsBackgroundUse: Boolean,
)

data class TransportMetrics(
    val reachablePeers: Int = 0,
    val estimatedRttMs: Int? = null,
    val jitterMs: Int? = null,
    val packetLoss: Float? = null,
    val estimatedHopCount: Int? = null,
    val metered: Boolean = false,
)
