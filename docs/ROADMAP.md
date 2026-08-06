# Roadmap — phone-side Android Auto replacement

See `docs/design/0001-phone-side-architecture.md` for the architecture this
sequencing assumes, `docs/design/0002-m0-scaffold-summary.md` for the
detailed record of what M0 actually produced, and
`docs/design/0003-aasdk-protocol-notes.md` for sourced protocol facts (frame
layout, channel ids, message sequence) that replace earlier guesses —
including a correction to M2/M4 below.

Task IDs (`#1`–`#10`) refer to the tracked task list; check there for
up-to-date status rather than trusting this doc's checkboxes to stay current
on their own.

## M0 — Project scaffold (done)

- [x] `protocol` module: `Transport`/`UsbAccessoryTransport`, placeholder
      `FrameHeader`/`ChannelMultiplexer`/`AaHandshake`, all channel classes
      with mock content sources for navigation/media/messaging.
- [x] `phone` module: minimal `Activity` that opens the USB accessory on
      `USB_ACCESSORY_ATTACHED` and reports connection status.
- [x] Unit tests for the mock content sources (3/3 passing).
- [x] Build verified: `:protocol:compileDebugKotlin`, `:phone:compileDebugKotlin`,
      `:protocol:testDebugUnitTest` all green.

## M1 — Real handshake + framing against a real head unit

Test target changed from a PC-based OpenAuto stand-in to the **real head unit
installed in the vehicle**, over its wired USB port. Trade-off accepted
knowingly: faster path to real signal, but debugging is harder against opaque
OEM firmware (no logs/source on the other end) than it would be against
OpenAuto. Mitigate by validating in the smallest possible increments —
confirm each stage (accessory attach → permission grant → handshake → first
channel) before moving to the next, rather than wiring the full stack at once.

**Before testing in the vehicle:**
- Vehicle parked, parking brake set. Never test while driving, and never as
  a distraction to whoever's driving.
- Know how to power-cycle the head unit (ignition cycle, or the
  infotainment circuit's fuse) in case malformed protocol data hangs it —
  a real, if modest, risk while `FrameHeader`/`AaHandshake` are unverified.
- Scope stays at the AA/infotainment USB layer. Do not extend this project
  into the vehicle's CAN bus/OBD-II or other ECUs.

- [ ] **#1** Validate protocol work against the real in-vehicle head unit,
      stage by stage (see above).
- [ ] **#2** Port `FrameHeader` byte layout and channel-id numbering from
      aasdk — now sourced, see 0003. Remaining work: fix `ChannelId.kt` to
      only hardcode `CONTROL = 0` and read every other channel's real id
      from the head unit's `ServiceDiscoveryResponse` at connection time.
- [ ] **#3** Port `AaHandshake`'s SSL handshake sequence from aasdk.
- [ ] **#4** Implement `ChannelMultiplexer.sendMessage`/`pumpOnce` for real.
- [ ] **#5** Minimal `VideoChannel`/`AudioChannel` — a single static frame and
      silence are enough; head units require *some* video stream to
      negotiate before other channels are usable.
- [ ] **#6** Get the mock `NavigationChannel` content (a real channel type,
      see 0003) actually rendering on the real head unit. This is the
      milestone that proves the wire protocol works at all, before any
      investment in real content integration. `MediaChannel`/`MessagingChannel`
      are dropped from this checkpoint — see the M2/M4 correction below.
- [ ] **#10** Confirm the remaining unconfirmed protocol details (frame-size
      byte widths, version request byte layout, whether `AUTH_COMPLETE` is
      encrypted, the actual TLS cert/key `Cryptor` uses) from aasdk's `.cpp`
      sources — blocks #3/#4 from being real rather than best-effort.

## M2 — Real media integration (**corrected** — was a wire channel, isn't one)

`docs/design/0003-aasdk-protocol-notes.md` found there's no dedicated
media-metadata channel in the real protocol — `MEDIA` is just an `AudioType`
label on the ordinary audio channel. Real AA renders the now-playing
bar/controls as pixels into the video stream and gets taps back over input.

- [ ] **#7** Keep `MediaBrowserServiceCompat` as the real content source
      (discover installed media apps, browse, relay playback controls), but
      deliver it by rendering into `VideoChannel`'s output and interpreting
      `InputChannel` taps against that layout — not a `MediaChannel` wire
      message. `MediaChannel.kt` needs redesigning, not just a real
      `MediaSource`.

## M3 — Real navigation integration

- [ ] **#8** Replace `MockNavigationSource` with a real turn-by-turn source.
      Unlike Google's real AA app, we can't lean on a third-party nav app via
      the Car App Library here — options are hosting a routing engine
      in-process, or driving a maps SDK directly. Needs its own design doc
      before implementation. Unlike media/messaging, this one *is* a real
      wire channel (`navigation_channel` in `ServiceDiscoveryResponse`), so
      `NavigationChannel.kt`'s shape stays valid — only the source changes.

## M4 — Real messaging integration (**corrected** — was a wire channel, isn't one)

Same finding as M2: no messaging channel type exists in the protocol either.

- [ ] **#9** Keep `NotificationListenerService` as the real content source
      (observe `MessagingStyle` notifications, use their `RemoteInput`/reply
      `PendingIntent`), but deliver it by rendering into `VideoChannel`'s
      output and interpreting `InputChannel` taps — not a `MessagingChannel`
      wire message. `MessagingChannel.kt` needs redesigning, not just a real
      `MessagingSource`.

## M5 — Wireless transport

- [ ] mDNS discovery + Wi-Fi Direct/AP setup as an alternative to
      `UsbAccessoryTransport`, sharing the same `Transport` interface. Not
      yet broken into tracked tasks — do that once M1–M4 land, since the
      channel/content layer above the transport is transport-agnostic and
      shouldn't need rework.

## Known risks (carried from earlier design discussion, not re-litigated here)

- No official certification path; this only ever achieves protocol
  compatibility, not "Android Auto Certified" branding.
- Google can change protocol details across AA app versions with no
  compatibility guarantee for unofficial implementations.
- This is reverse-engineering-for-interoperability; review ToS/legal posture
  before any public distribution, not just personal use.
