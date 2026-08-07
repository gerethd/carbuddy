# Roadmap — phone-side Android Auto replacement

See `docs/design/0001-phone-side-architecture.md` for the architecture this
sequencing assumes, `docs/design/0002-m0-scaffold-summary.md` for the
detailed record of what M0 actually produced, and
`docs/design/0003-aasdk-protocol-notes.md` for sourced protocol facts (frame
layout, channel ids, message sequence) that replace earlier guesses —
including a correction to M2/M4 below.

For the current milestone specifically:
`docs/design/0005-post-handshake-protocol-notes.md` carries the sourced facts
for everything *after* `AUTH_COMPLETE` (service discovery, channel open, the
video channel, and the navigation status channel's message ids — the last of
these recovered from the Gearhead APK, since aasdk does not implement that
channel at all), and
`docs/design/0006-navigation-overlay-plan.md` is the sequenced plan for
getting navigation content onto the head unit, which is what M1's remaining
tasks (#2, #4, #5, #6) now follow.

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

- [~] **#1** Validate protocol work against the real in-vehicle head unit,
      stage by stage (see above). **Handshake stage now validated against
      the Desktop Head Unit (DHU) emulator** — full mutual TLS + real
      certificate identity + `AUTH_COMPLETE(OK)`, confirmed working
      end-to-end (see 0003). Still not yet tried against the actual
      in-vehicle head unit — DHU and real OEM firmware aren't guaranteed to
      enforce identical certificate checks.
- [ ] **#2** Port `FrameHeader` byte layout and channel-id numbering from
      aasdk — now sourced, see 0003. Remaining work: fix `ChannelId.java` to
      only hardcode `CONTROL = 0` and read every other channel's real id
      from the head unit's `ServiceDiscoveryResponse` at connection time.
- [x] **#3** Port `AaHandshake`'s SSL handshake sequence from aasdk —
      **implemented and validated end-to-end** (version exchange, mutual
      TLS handshake via `SSLEngine` in server mode, real `AUTH_COMPLETE(OK)`
      receipt against the DHU), backed by `FrameCodec`/`AaServerIdentity`
      (now presenting a real, Google-issued cert/key — see 0003) and
      covered by both a byte-capture regression test
      (`AaHandshakeTest`) and a full real-`SSLEngine` success-path test
      (`AaHandshakeSuccessPathTest`).
- [ ] **#11** Fix the frame read path before anything multiplexed runs over it
      (0006 stage A). `FrameCodec` currently drops every frame after the
      first when one USB transfer carries several, reassembles fragments
      without regard to channel interleaving, cannot write payloads over
      65535 bytes, and applies a 5-second per-frame deadline that suits a
      handshake but not an idle session. None of this bit us during the
      handshake; all of it bites as soon as more than one channel is open.
- [ ] **#4** Implement `ChannelMultiplexer.sendMessage`/`pumpOnce` for real
      (0006 stage B) — can now reuse `FrameCodec`; also needs to use the
      handshake's established `SSLEngine` to encrypt/decrypt traffic once
      `AUTH_COMPLETE` is received, with `wrap`/`unwrap` each serialised since
      `SSLEngine` is not thread-safe. Control-channel dispatch (ping,
      shutdown, focus) lands here too.
- [ ] **#12** Add protobuf (`protobuf-javalite` + the Gradle plugin) and
      implement service discovery (0006 stage C). Hand-rolled bytes stop
      being viable at `ServiceDiscoveryResponse`. Write our own `.proto`
      files from 0005's field tables rather than copying aasdk's — same
      GPLv3 reasoning as 0003. Logging the full response is the first real
      look at what our head unit actually offers.
- [ ] **#13** Channel-open plumbing (0006 stage D), then **run the stage E
      experiment**: find out whether the head unit will accept navigation
      channel traffic with no video stream running. Neither aasdk nor the
      Gearhead decompile answers this, and the answer decides whether #5 is
      required at all. Record the outcome in 0005.
- [ ] **#5** Minimal `VideoChannel` — a single pre-encoded H.264 keyframe
      resent on a timer is enough (0006 stage F). **Now conditional on the
      #13 experiment** rather than assumed: it was listed here on the belief
      that head units require *some* video stream before other channels are
      usable, which is plausible but unverified. `AudioChannel` is dropped
      from this checkpoint — nothing in the navigation path needs it.
- [ ] **#6** Get the mock `NavigationChannel` content (a real channel type,
      see 0003) actually rendering on the real head unit (0006 stage G). This
      is the milestone that proves the wire protocol works at all, before any
      investment in real content integration. Message ids are now recovered
      (0005); remaining work is decoding the `0x8003`/`0x8004` bodies,
      navigation focus, and growing `NavigationEvent` to match the real wire
      fields — its current three fields don't.
      `MediaChannel`/`MessagingChannel` are dropped from this checkpoint —
      see the M2/M4 correction below.
- [x] **#10** Confirm the remaining unconfirmed protocol details — done, and
      then some: the TLS cert/key mystery is fully resolved (not just
      "what aasdk's `Cryptor` uses," but what a real head unit/DHU actually
      requires and why — see 0003, "TLS trust model and certificate
      identity"). Frame-size byte widths, version request layout, and
      `AUTH_COMPLETE` plaintext status were already confirmed earlier.

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

- **The recovered certificate expires 2026-10-14** — roughly nine weeks out as
  of 2026-08-07. Once it lapses the handshake fails and every task above is
  blocked behind it, with no bypass: the DHU does strict PKIX validation. Two
  implications: front-load work that needs a live handshake, and separately
  investigate renewal (see 0003 on the Phenotype-flag-driven `Liyn` path real
  installs use). Tracked as **#14**.
- No official certification path; this only ever achieves protocol
  compatibility, not "Android Auto Certified" branding.
- Google can change protocol details across AA app versions with no
  compatibility guarantee for unofficial implementations.
- This is reverse-engineering-for-interoperability; review ToS/legal posture
  before any public distribution, not just personal use.
