# 0001 — Phone-side Android Auto replacement: architecture

## Goal

Build an Android app that replaces Google's Android Auto phone app entirely —
talking directly to existing, unmodified Android-Auto-enabled head units over
the same wire protocol, with no dependency on Google's app being installed or
running. This is the inverse of the (rejected) alternative: using the
`androidx.car.app` Car App Library, which requires Google's real AA app
present as host and only supplies content *to* it.

Current focus: **navigation, media, and messaging** content channels.

## Modules

- `protocol` — Android library. Transport abstraction, handshake, frame
  multiplexing, and per-channel logic. No UI, no `Activity`.
- `phone` — Android application. Thin shell: receives the
  `USB_ACCESSORY_ATTACHED` intent, opens the transport, and will eventually
  own the app-level lifecycle (foreground service, wireless discovery UI,
  settings).
- `mobile` / `automotive` / `shared` — pre-existing Car App Library scaffold
  from the original Android Studio template. Left untouched; unrelated to
  this effort (see chat history — these build a *hosted* app, not a
  replacement host).

## Layering

```
Content sources (nav engine / MediaBrowserService client / NotificationListenerService)
        │
per-channel logic (NavigationChannel, MediaChannel, MessagingChannel, ...)
        │
ChannelMultiplexer  (frame + channel-id routing, fragment reassembly)
        │
AaHandshake         (SSL-style handshake, encryption)
        │
Transport           (UsbAccessoryTransport | future Wi-Fi transport)
```

## What is and isn't verified

The transport layer (`UsbAccessoryTransport`) relies only on documented,
stable Android platform APIs (`UsbManager`/`UsbAccessory`) and is safe to
trust as written.

Everything below `ChannelMultiplexer` and `AaHandshake` — exact frame byte
layout, channel-id numbering, handshake message sequence — is a **structural
placeholder**, not a verified protocol implementation. It was deliberately
*not* hand-derived from memory, because getting these details wrong silently
produces code that looks complete but will never actually interoperate with
a real head unit. Before milestone M1 can talk to real hardware, every TODO
in `framing/` and `handshake/` needs to be ported from an authoritative
open-source reference:

- **aasdk** (https://github.com/f1xpl/aasdk) — the SSL/framing layer used by
  OpenAuto. Closest to a canonical reference for the wire format.
- **OpenAuto** (https://github.com/f1xpl/openauto) — full head-unit-side
  implementation built on aasdk.
- **aa-proxy-rs** — implements the *phone-facing* side of the protocol
  (structurally the same role this project's `protocol` module plays),
  making it the more directly analogous reference of the three.

## Test harness

**Updated**: testing now happens directly against the real head unit
installed in the vehicle over its wired USB port, rather than a PC-based
OpenAuto stand-in. This is a knowing trade-off — debugging is harder against
opaque OEM firmware than it would be against a sourceable implementation like
OpenAuto — mitigated by validating each protocol stage independently
(accessory attach → permission grant → handshake → first channel) rather than
testing the full stack at once. See `docs/ROADMAP.md`'s M1 section for the
in-vehicle safety notes this implies (parked/parking-brake, knowing how to
power-cycle the head unit, scope staying at the AA layer and not the vehicle's
CAN bus/ECUs).

## See also

- `docs/ROADMAP.md` for milestone sequencing.
