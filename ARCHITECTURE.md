# RideMesh Architecture

## Product definition

RideMesh is a cross-platform motorcycle group voice protocol and routing system. Android is the first client, not the protocol itself.

The application must keep rider identity, ride membership, packet format, encryption, audio framing, acknowledgements, relay rules, and routing policy independent from any single radio/API.

## Layer model

```text
UI / Ride controls
        |
Audio engine (PCM now, Opus target)
        |
RideMesh protocol
- rider / node identity
- ride membership
- packet IDs + sequencing
- TTL / hop count
- presence / ACK / SOS
- encryption metadata
        |
Hybrid router
- path scoring
- duplicate suppression
- handoff
- gateway relay
        |
+----------------+----------------+----------------+
| Local Android  | Internet       | Local iOS      |
| Nearby /       | relay / SFU    | Network +      |
| Wi-Fi Aware    | transport      | Wi-Fi Aware    |
+----------------+----------------+----------------+
```

## Routing modes

### 1. Internet path
Use mobile data/Wi-Fi when available for long-range group communication. For early PTT this can be a lightweight relay. For open full-duplex intercom, an SFU/WebRTC-class media service is the likely production path.

### 2. Direct local path
Two nearby riders communicate device-to-device with no Internet.

### 3. Local multi-hop path
A packet may be relayed A -> B -> C when A and C have no direct link. Packet ID deduplication and TTL/hop limits prevent loops.

### 4. Gateway bridge
A rider with Internet can bridge an offline local cluster to the Internet service at the RideMesh application layer. No tethering is required: the gateway app receives an encrypted RideMesh packet locally and forwards the same logical packet over its Internet transport, and vice versa.

## Hybrid route policy

The router should keep more than one transport warm when practical instead of waiting for a hard failure.

Each path is scored using:
- reachability
- latency / jitter
- packet loss
- hop count
- bandwidth
- battery cost
- mobile-data cost preference

During handoff, the router may briefly duplicate high-priority packets over two paths. Global packet IDs make duplicate reception safe.

Default user policy:
- local direct path preferred for nearby riders when healthy
- Internet used for riders outside the local cluster
- local mesh automatically carries traffic when Internet disappears
- Internet resumes when it becomes the better/reachable path
- SOS/control packets may use multiple available paths

## Protocol boundary

The canonical wire contract lives in `protocol/ridemesh.proto`.

Android-specific classes such as Google Nearby Connections must never define the RideMesh wire format. They are adapters that move RideMesh envelopes.

The current `MeshPacket` / `MeshNode` implementation remains the V0.2 bench format so we can finish the A-B-C proof without destabilizing it. New work should migrate packet semantics toward the shared protocol instead of adding more Android-only fields to `MeshPacket`.

## Security model

Transport encryption is useful but not sufficient because packets may cross multiple transports and relay devices.

Production target:
- end-to-end group encryption above the transport layer
- authenticated ride membership
- key epochs for membership changes
- packet replay protection
- relay phones forward ciphertext and do not need plaintext access unless they are legitimate ride participants

## Android transport plan

1. Existing Google Nearby Connections P2P_CLUSTER adapter for broad device compatibility and V0.2 testing.
2. Android Wi-Fi Aware adapter where hardware supports it.
3. Internet adapter using the same RideMesh envelope.
4. Later: evaluate Wi-Fi Direct only where it improves range/topology without making handoff brittle.

## iOS transport plan

1. Same Internet RideMesh protocol as Android.
2. Network Framework + Wi-Fi Aware on supported Apple hardware for direct nearby networking.
3. Same-LAN networking as an additional local path where useful.
4. Do not make Apple Multipeer Connectivity the cross-platform foundation; it is deprecated and Apple peer-to-peer Wi-Fi is not a documented cross-platform wire protocol.

Cross-platform Android <-> iOS Wi-Fi Aware must be verified on physical devices before we promise it as a supported offline path.

## Server evolution

### PTT MVP
A simple authenticated packet relay can route encrypted RideMesh control/audio frames between ride members.

### Open intercom
Move Internet media to an SFU-style architecture so each rider does not need to upload a separate stream to every other rider. The local/offline protocol remains available as a fallback path.

## Design rule

A feature is protocol-level if iOS and Android should behave the same.
A feature is transport-level only if it describes how bytes move on one platform/network.
