# MeshVoice rebrand

## App identity

- Display name, wordmark, and every user-facing string (dialogs, share
  text, notification text, log lines) changed from "RideMesh" to
  "MeshVoice". Internal type/interface names (`RideMeshTransport`, method
  names like `openRideMeshCommunity()`) were left alone — renaming those
  is a larger, purely internal refactor with no user-visible benefit and
  more chance of breaking something for no reason.
- **Full package rename**: `com.bikemesh.ridemesh` → `com.meshvoice.app`,
  matching both the repo name and the app name. This touched:
  - every `package`/`import` line across all 11 Kotlin files
  - `namespace` / `applicationId` in `app/build.gradle.kts`
  - `rootProject.name` in `settings.gradle.kts`
  - the Nearby Connections `SERVICE_ID` strings in `MeshNode` and
    `LobbyNode` (arbitrary identifiers, updated for consistency —
    this does *not* need to match the Android package, it's just a
    string both advertiser and discoverer must agree on)
  - `.fileprovider` authority updates automatically since it's derived
    from `${applicationId}`, no manual change needed
- All 11 `.kt` files verified brace/paren-balanced and all XML
  re-validated after the mass rename before committing.

## Theme: white + orange on very dark green

New `colors.xml` palette:

| Token | Old | New |
|---|---|---|
| accent (primary/buttons/highlights) | `#00E5D4` teal | `#FF7A29` orange |
| surface/background | `#0A0D0D` near-black | `#071811` very dark green |
| panel | `#111716` | `#0D2418` |
| panel2 | `#161D1C` | `#122C1E` |
| border | `#1F2928` | `#1C3A28` |
| white (text) | `#F2F4F3` | `#F7F5F0` |
| muted | `#8A9694` | `#9DB3A3` (green-tinted for harmony) |
| faint | `#4F5957` | `#5B7266` |

Status colours (`green`/`amber`/`danger` — connected/reconnecting/no-path)
were deliberately **not** shifted onto the orange/green theme axis: they
need to stay visually distinct from both the background and the accent so
connection state is still readable at a glance. Only `green` shifted
slightly (`#3DDC84` → `#4CD964`) to stay legible against the new darker,
greener background.

The window background was previously hardcoded to `@color/black` in two
places (`themes.xml` and the root `FrameLayout` in `activity_main.xml`)
instead of using a token — fixed both to `@color/surface` so the whole app
repaints from one place going forward.

Launcher icon backgrounds (adaptive icon layer + legacy square/round PNGs)
regenerated to match `@color/surface` instead of the old pure black, so the
icon blends with the rest of the theme instead of sitting in a black box.

The emblem itself (helmet + mesh graph) keeps its original white/cyan
colouring from the supplied logo artwork — recoloring someone's actual
provided logo felt like a bigger liberty than a UI theme change calls for.
Say the word if you want the emblem itself shifted to orange too.
