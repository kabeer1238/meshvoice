# RideMesh Beta 1.1 — Changes

Version: **1.1.0-beta1.1** (versionCode 11)
Previous: 0.4.0-beta1 (versionCode 10)

## Branding

- **Launcher icon.** Real `mipmap-*` assets at mdpi→xxxhdpi, plus an
  Android 8+ adaptive icon (`mipmap-anydpi-v26/ic_launcher.xml`) with a
  monochrome layer for themed icons. Generated from the supplied RideMesh
  logo, cropped to the helmet + mesh emblem so it stays legible at launcher
  size — the full lockup with the wordmark is unreadable below ~96px.
- **Splash screen.** AndroidX `core-splashscreen`; the emblem is shown by the
  system from the instant the icon is tapped, then fades/scales out via
  `setOnExitAnimationListener`. Beta 1 showed a plain black window here.
- **Build badge.** The header now shows `BETA 1.1 • v<versionName>`, read from
  the package manager at runtime so it cannot drift from `build.gradle.kts`.
  Testers quoting this in a bug report will always quote the real build.

## Fixed: Internet audio corrupted on every frame

`InternetNode` declared `HEADER_BYTES = 37` but `encode()` only wrote 33 bytes
(4 magic + 1 version + 16 origin + 4 sequence + 8 timestamp). `ByteBuffer`
zero-fills, so `array()` returned four zero bytes past the end of the audio
and the receiver decoded them as trailing PCM samples — on **every single
Internet frame**. Now computed from the field widths, with a `check()` that
fails loudly if the two ever diverge, plus `InternetNodeTest` asserting exact
encoded sizes and byte-for-byte round-trips.

## Fixed: local scan could knock riders off mobile data

`MeshNode` already used `ConnectionType.NON_DISRUPTIVE`, but `LobbyNode` —
the "Find Nearby Riders" scan — still used the default. Tapping that button
could tear down the phone's existing Wi-Fi/Internet connection at exactly the
moment the Internet transport was trying to hold the ride together. Both
paths are now `NON_DISRUPTIVE`.

## Fixed: build was broken outside CI

The manifest referenced `@drawable/ridemesh_icon`, which did not exist in the
source tree — it was materialised at build time by `base64 -d` inside the CI
workflow. Anyone cloning the repo and building in Android Studio hit a missing
resource. Icons are now checked-in assets and the CI decode step is gone.

## Reliability

- **Reconnect backoff.** The Internet transport retried every 2s flat, so an
  entire group hammered the broker in lockstep after a tunnel or coverage
  drop. Now exponential to a 30s ceiling with jitter, reset after any session
  that successfully connected.
- **Ride code entropy.** Was `Random.nextInt(1000, 9999)` — ~9,000 codes from
  a non-cryptographic RNG, cheap to enumerate exhaustively. Now `SecureRandom`
  over a 32-character ambiguity-free alphabet (no `0`/`O`, no `1`/`I`) at
  length 6. See the limitation below before reading this as a security fix.
- **Backup hardening.** `allowBackup=false` plus explicit
  `data_extraction_rules.xml` excluding cloud backup and device transfer.

## Known limitations — unchanged in 1.1

- **The Internet path is not authenticated.** Ride codes are now expensive to
  guess, but they are still the *only* thing separating one group's audio from
  another on the shared public test broker, and anyone who learns a code can
  join a ride and both hear and inject audio. Larger codes raise the cost of
  guessing; they are not access control. Real membership authentication needs
  server-side work and remains a pre-launch blocker.
- **The Internet path is not end-to-end encrypted.** Audio reaches a public
  test broker as plaintext PCM. Treat every Beta ride as public.
- **`MeshPacket` has no integrity or replay protection.** Dedup is keyed on a
  sender-chosen `packetId`, so it protects against accidental relay loops, not
  a malicious peer. The `protocol/ridemesh.proto` `Envelope` already reserves
  `encryption`, `key_epoch` and `nonce` for this migration.
- **Radio range is physics.** Out of local radio range with no mobile data,
  riders cannot communicate. Nothing in software changes this.
