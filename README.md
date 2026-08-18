# MeshVoice

A minimal, working Android app for offline group voice over a local mesh —
inspired by RideMesh's architecture, scoped down to a v1 I could write
correctly and completely in one pass.

## What's actually in this build

- **Create/join a ride by a short code**, entered on one screen (`MainActivity`).
- **Local mesh group voice** over Nearby Connections (`mesh/MeshNode.kt`), with:
  - TTL + packet-ID dedup so audio relays across multiple hops without looping
  - a bounded LRU "seen packets" cache so memory doesn't grow unbounded
  - ride-code filtering on both advertise and discover, so devices using a
    different code don't connect to each other
- **Real-time audio** (`audio/AudioEngine.kt`):
  - `VOICE_COMMUNICATION` capture with hardware AEC / NS / AGC when the device supports them
  - a simple adaptive-noise-floor VAD gate, so silence isn't relayed to peers
  - a bounded playback queue (`DiscardOldestPolicy`) so a slow peer causes
    dropped frames, never growing delay
- **Foreground service** (`service/RideService.kt`) so the mic + connections
  survive the screen turning off, with a fallback path for OEMs that enforce
  stricter foreground-service-type rules.
- **A unit test** (`MeshPacketTest.kt`) that locks down the wire format's byte
  accounting — this exists because the RideMesh reference app I audited had a
  real bug of exactly this kind (declared header size didn't match bytes
  actually written, corrupting every audio frame by 4 bytes). Encode/decode
  round-trips are asserted for empty audio, a typical 20ms frame, and
  malformed input.

## What's deliberately NOT in this build, and why

- **No Internet/long-range path.** RideMesh's Internet path in the reference
  repo relayed through a shared public MQTT broker with no authentication —
  I flagged that as the top security finding in the audit. Building it
  properly means standing up and authenticating against a server I can't
  host for you. `mesh/MeshNode.kt` only knows about opaque packet bytes, so
  when you're ready, you can add an `InternetTransport` next to it that
  implements the same "encode packet → send bytes / receive bytes → decode
  packet" shape without touching the mesh code.
- **No end-to-end encryption.** Ride-code matching in `MeshNode` is a
  convenience filter (like a Wi-Fi SSID), not authentication — anyone who
  knows or guesses the code can join. Fine for a personal/local project;
  add authenticated key exchange before trusting this with anything sensitive.
- **No QR invites / rich UI / settings screens.** Straightforward to layer on
  top once the core mesh+audio loop is working for you.

## Getting an APK onto your phone

**Option A — GitHub Actions builds it for you, no Android Studio needed**

1. Create a new (can be private) GitHub repo and push this folder to it.
2. GitHub Actions will automatically run `.github/workflows/build.yml` on
   push (or trigger it manually from the Actions tab → "Build debug APK" →
   Run workflow).
3. When it finishes, open that workflow run → **Artifacts** →
   download `MeshVoice-debug-apk`. Unzip it to get `app-debug.apk`.
4. Copy the APK to your phone (email it to yourself, Google Drive, USB,
   whatever's easiest) and tap it to install. You'll need to allow
   "install unknown apps" for whichever app you used to open the file —
   Android will prompt you for this the first time.
5. Repeat on a second phone — you need two devices to test the mesh at all,
   since Nearby Connections requires real Bluetooth/Wi-Fi radios.

**Option B — Android Studio, if you already have it installed**

1. Open the `MeshVoice/` folder in Android Studio (Koala or newer). It will
   regenerate the Gradle wrapper jar automatically on first sync — I didn't
   ship that binary file since it's not source code.
2. Let Gradle sync, then Run on two physical devices (again: not emulators,
   Nearby Connections needs real radios).
3. On both devices: same ride code, tap START RIDE, grant the permission
   prompts. They should find each other and both should hear the other's mic.

## Where to go next, in order

1. Test with 3 devices to see the multi-hop relay (`MAX_TTL = 4` in
   `MeshNode`) actually forward audio between two phones that are out of
   direct range of each other but both in range of a third.
2. Add a `LobbyNode`-style short discovery scan if you want "find nearby
   riders and invite them" as a separate flow from the always-on mesh.
3. Add the Internet path once you have a server, using a real auth scheme
   (not a public shared broker) — see the audit's P0 #1 for what to avoid.
4. Swap PCM16 for Opus if you want dramatically lower bandwidth per hop.
