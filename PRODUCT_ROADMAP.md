# RideMesh Product Roadmap

## Product goal
Universal motorcycle group voice that does not care which helmet intercom brand a rider owns, and keeps communicating across Internet, direct peer-to-peer, and multi-hop relay paths.

## Architecture rule
RideMesh is a cross-platform protocol first. Android is the first client. Rider identity, ride membership, packet format, encryption, audio framing, relay rules, and routing behavior must not depend on Google Nearby or any other single transport API.

## V0.2 — bench proof
- Phone-only PTT
- Nearby P2P_CLUSTER
- App-level multi-hop relay
- Phone / Auto / Helmet audio routing
- Hardware volume-button PTT
- Basic Android voice processing
- Forced LAB A -> B -> C topology

## V0.3 — protocol + build foundation
- Canonical cross-platform `protocol/ridemesh.proto`
- Transport abstraction in Android
- Stable node / ride identity model
- Packet types for audio, presence, control, ACK, route, SOS, and keys
- Global packet IDs, sequence, TTL, hop count, priority
- Transport-independent encryption metadata
- Fix/reliably automate APK CI builds
- Keep V0.2 bench packet compatible until A-B-C proof is complete

## V0.4 — hybrid routing
- Android Nearby adapter behind the transport interface
- Internet transport using the same RideMesh envelopes
- Keep local and Internet paths warm when practical
- Route scoring using reachability, latency, jitter, packet loss, hops, battery, and metered-data preference
- Automatic Internet <-> direct <-> multi-hop handoff
- Gateway bridge: a rider with Internet can relay an offline local cluster to the Internet service
- Heartbeats, rider presence, reconnect/backoff, topology diagnostics

## V0.5 — efficient voice
- Opus encode/decode
- 20 ms frames
- Adaptive bitrate
- Sequence jitter buffer
- Packet-loss metrics and concealment strategy
- VAD / silence suppression
- Wind-noise-aware gate

## V0.6 — ride-ready controls
- Screen-off reliability
- Bluetooth/handlebar PTT input mapping
- Spoken connection status prompts
- Automatic route switching phone ↔ helmet
- Battery/thermal mode
- User-selectable data-saving / local-first policy

## V0.7 — iOS client foundation
- Swift client generated/implemented against the same RideMesh protocol
- Same Internet transport and ride membership semantics as Android
- Network Framework + Wi-Fi Aware local adapter on supported Apple devices
- Same-LAN local path where useful
- Physical Android <-> iOS Wi-Fi Aware interoperability testing
- Do not make deprecated Multipeer Connectivity the protocol foundation

## V0.8 — open intercom
- Full duplex
- Echo management
- Active-speaker limits/mixing
- Internet SFU-style media path
- Per-rider mute
- Priority leader/SOS audio
- Local/offline fallback remains available

## V1.0 — rider product
- QR / ride-code joining
- End-to-end encrypted private groups
- Rider roster + topology / path status
- Optional live group radar
- SOS broadcast over multiple available paths
- Voice history controls
- Diagnostics export
- Helmet compatibility matrix
- Field-tested reconnection, battery, thermal, latency, and cross-platform profiles
