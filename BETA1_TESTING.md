# RideMesh Beta 1.1 Tester Guide

Version: **1.1.0-beta1.1**

RideMesh Beta 1 is an Android field-test build for motorcycle group voice. It combines Internet group voice with automatic Nearby local fallback and supports phone audio or compatible Bluetooth helmet/headset audio.

## Included in Beta 1

- Hands-free group voice.
- Internet voice for riders in different locations using the same Ride Code.
- Automatic Internet reconnect after temporary 4G/5G loss while the ride session remains active.
- Nearby local voice fallback when Internet is unavailable and riders have a usable local radio path.
- Local multi-hop relay prototype.
- Automatic local rediscovery after complete connectivity loss.
- Battery Smart handover behavior.
- Android VOICE_COMMUNICATION capture path with platform Acoustic Echo Canceler, Noise Suppressor and Automatic Gain Control when supported by the phone.
- Adaptive voice activity detection / silence suppression.
- Software wind/road-rumble high-pass filtering before speech detection.
- Bounded playback queue so stale audio is discarded instead of creating seconds of delay.
- Create / join rides by code.
- Show and share a RideMesh QR invite while a conversation is active.
- Find nearby RideMesh riders while an active Internet conversation continues.
- WhatsApp bug-report group: https://chat.whatsapp.com/CGToJCBDG6XFGUpeTp7uKW
- Direct support fallback: +91 9188664823
- RideMesh community: https://chat.whatsapp.com/CGToJCBDG6XFGUpeTp7uKW

## Important live-invite behavior

Beta 1 does not start a second nearby invite scan while a local-only mesh call is carrying voice, because that can compete for the same radio resources. In that situation use **Show QR** or **Share QR**. When Internet voice is healthy, RideMesh can run the nearby invite channel while the existing conversation continues over Internet.

## Recommended tests

1. **Two riders, different locations** — both use Internet, same Ride Code, verify hands-free voice and reconnection.
2. **Three riders over Internet** — test normal speech, short overlaps, latency, clipping, echo and noise.
3. **Internet loss and recovery** — remove Internet; if riders are nearby verify local fallback, otherwise verify reconnecting; restore 4G/5G and verify automatic Internet reconnection without rejoining.
4. **Local reconnect** — with Internet off, move riders out of local range and back into range; verify automatic discovery/reconnect.
5. **Add riders during a call** — during Internet voice, tap INVITE and test Show QR, Share QR and Find Nearby Riders; current riders should keep communicating.
6. **Helmet compatibility** — repeat tests with different Bluetooth helmet/intercom brands and record exact models.
7. **Battery and screen-off** — use Battery Smart, lock the screen for 15–30 minutes, then record voice/reconnect behavior and battery change.

## What testers should report

Please include phone model, Android version, helmet/intercom brand/model if used, number of riders, current path (Internet / Local Mesh / Reconnecting), approximate delay, whether the screen was locked, battery level if relevant, and steps that reproduce the issue.

## Known Beta 1 limitations

- This is a test build, not a production safety system.
- Internet voice currently uses experimental public test relay infrastructure and is not end-to-end encrypted. Do not use Beta 1 for sensitive conversations.
- Local range depends on phone radios, rider spacing, obstacles and RF conditions.
- Nearby invite scanning is deliberately restricted during a local-only voice call; QR sharing remains available.
- Media is still PCM + VAD rather than the planned Opus transport, so larger groups and overlapping speech are important Beta 1 test areas.
- Screen-off/process-restart behavior can vary with Android/OEM battery management and must be field-tested.

## Rider safety

Do not operate the phone while the motorcycle is moving. Configure the ride, audio route and invitations while stopped. Normal RideMesh audio is hands-free.
