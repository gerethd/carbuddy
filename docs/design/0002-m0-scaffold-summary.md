# 0002 — M0 scaffold: what was built and why

Companion record to `0001-phone-side-architecture.md`. That doc explains the
architecture; this one is the session record — what exists, file by file,
what's real vs. placeholder, and what was verified before being called done.

## Decision history

1. **Goal established**: replace Google's Android Auto phone app entirely,
   talking directly to unmodified, existing AA-enabled head units — not
   building a head unit, and not building a third-party app hosted *by*
   Google's app.
2. **Wrong-template catch**: the pre-existing `MyApplication` project
   (`mobile`/`automotive`/`shared` modules) turned out to be Android
   Studio's stock **Car App Library** template — `MyCarAppService` /
   `Session` / `HostValidator.ALLOW_ALL_HOSTS_VALIDATOR` on `androidx.car.app`.
   That template builds the *opposite* thing: an app hosted by Google's real
   AA app, which still does all the head-unit protocol work itself. Flagged
   to the user before building on it.
3. **User confirmed**: keep the full-replacement goal. The Car App Library
   modules were left in place, untouched, but the actual work happens in two
   new modules (below) rather than reusing `mobile`/`shared`.
4. **Scope for first milestone**: navigation, media, and messaging content
   channels, with mock content first (per earlier discussion) to de-risk the
   wire protocol before investing in real app-ecosystem integration.

## What was created

### `protocol` module (Android library, no UI)

| File | Purpose | Status |
|---|---|---|
| `build.gradle` | Library module config, mirrors `shared`/`automotive`'s existing plugin/SDK conventions | done |
| `transport/Transport.kt` | Duplex byte-stream interface — the seam between USB/Wi-Fi and everything above | done |
| `transport/UsbAccessoryTransport.kt` | Wired transport over `UsbManager.openAccessory()` | done — built on stable, documented platform APIs |
| `framing/FrameHeader.kt` | Frame header data class (channel id, flags, length, fragment info) | **placeholder** — byte layout not verified, see caveats in the file |
| `framing/ChannelMultiplexer.kt` | Frame read/write + channel dispatch + fragment reassembly | **stub** — `sendMessage`/`pumpOnce` are `TODO()` |
| `handshake/AaHandshake.kt` | SSL-style handshake | **stub** — `performHandshake()` is `TODO()` |
| `channel/Channel.kt` | Common channel interface (`onOpen`/`onMessage`/`onClose`) | done |
| `channel/ChannelId.kt` | Channel-id constants | **placeholder** — numbers unverified |
| `channel/NavigationChannel.kt` + `MockNavigationSource` | Nav channel with mock turn-by-turn events | mock content done; channel wiring is `TODO()` pending M1 |
| `channel/MediaChannel.kt` + `MockMediaSource` | Media channel with mock now-playing + playback control | mock content done; channel wiring is `TODO()` pending M1 |
| `channel/MessagingChannel.kt` + `MockMessagingSource` | Messaging channel with mock incoming message | mock content done; channel wiring is `TODO()` pending M1 |
| `channel/VideoChannel.kt`, `AudioChannel.kt`, `InputChannel.kt`, `SensorChannel.kt` | Required-by-protocol channels outside this milestone's content focus | stubs, `TODO()` |
| `src/test/.../MockSourcesTest.kt` | Unit tests for the three mock sources | done — 3/3 passing |

### `phone` module (Android application)

| File | Purpose | Status |
|---|---|---|
| `build.gradle` | App module config, depends on `:protocol` | done |
| `AndroidManifest.xml` | Launcher activity + `USB_ACCESSORY_ATTACHED` intent filter | done |
| `res/xml/accessory_filter.xml` | Manufacturer/model match for the AOA intent (`Android` / `Android Auto`, matching known OSS head-unit implementations — flagged as unverified against real head unit firmware) | done, flagged |
| `res/values/strings.xml` | App name | done |
| `MainActivity.kt` | Opens the USB accessory on attach, reports connection status; does not yet drive the handshake/multiplexer | done for its current scope |

### `docs/`

| File | Purpose |
|---|---|
| `design/0001-phone-side-architecture.md` | Layered architecture, module boundaries, what's verified vs. not, test-harness plan |
| `design/0002-m0-scaffold-summary.md` | This file |
| `ROADMAP.md` | Milestone sequencing, now cross-referenced with task tracker IDs (see below) |

## Verification performed (not just "should compile")

```
./gradlew :protocol:compileDebugKotlin :phone:compileDebugKotlin :protocol:testDebugUnitTest
```

- Both modules compiled clean.
- One real bug caught and fixed in this pass: `accessory_filter.xml` had a
  literal `--` inside an XML comment, which is illegal in XML and failed
  `phone:parseDebugLocalResources`. Fixed before reporting success.
- `protocol:testDebugUnitTest` → **3 tests, 0 failures, 0 errors** (the mock
  navigation/media/messaging source tests — the only non-stub logic in the
  module at this point).

## Explicitly not done yet (see ROADMAP.md)

Everything under `framing/` and `handshake/`, plus the four content/media
channels' actual wire wiring, is scaffolding only. No code in this module has
been exercised against a real or emulated head unit — that starts at M1.
