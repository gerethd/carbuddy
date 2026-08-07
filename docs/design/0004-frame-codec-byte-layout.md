# 0004 — FrameCodec byte layout reference

Companion reference to `0003-aasdk-protocol-notes.md`. That doc explains
*where the facts below came from* (aasdk source) and the open questions;
this one is just the byte-level cheat sheet, kept in sync with `FrameCodec`.

## Physical frame layout

| Byte offset | Field | Size | Notes |
|---|---|---|---|
| 0 | `channel_id` | 1 byte | Only `0x00` (`CONTROL`) is protocol-fixed; every other value is negotiated per-connection via `ServiceDiscoveryResponse` |
| 1 | flags | 1 byte | bit0=`FIRST`, bit1=`LAST`, bit2=`MessageType::CONTROL` (unresolved discrepancy — empirically unset even on real control messages), bit3=`ENCRYPTED` |
| 2–3 | frame size (`SHORT`) | 2 bytes, big-endian `uint16` | Used whenever flags ≠ exactly `0x01` (i.e. `BULK`/`0x03` or `LAST`-only/`0x02`) |
| 2–7 | frame size + total size (`EXTENDED`) | 6 bytes (2 + 4) | Used only when flags is *exactly* `FIRST`-without-`LAST` (`0x01`) — 2-byte this-fragment size + 4-byte big-endian `uint32` total message size |
| after size field | payload | `size` bytes (from the field above) | Contents depend on channel + message type |

**Critical read-path bug found 2026-08-06** (fix pending, see ROADMAP): each
physical frame must be read with **one single `transport.read()` call sized
to cover the whole frame** — header + size field + payload together. The USB
accessory descriptor is transfer-oriented: a short read consumes and
discards the remainder of that transfer rather than leaving it queued for a
follow-up read. `FrameCodec`'s current `readFully()`-based sequential
small reads (2 bytes header, then 2/6 bytes size, then N bytes payload) is
wrong for this transport and needs to become one large read per physical
frame, parsed from an in-memory buffer afterward.

## Payload contents (unencrypted `CONTROL`-channel messages — the handshake phase)

| Payload offset | Field | Size | Notes |
|---|---|---|---|
| 0–1 | `MessageId` | 2 bytes, big-endian `uint16` | e.g. `0x0001` = `VERSION_REQUEST` |
| 2+ | body | remaining bytes | Format depends on `MessageId` |

## Concrete example: `VERSION_REQUEST` (real capture, 10 bytes total)

| Byte(s) | Value | Meaning |
|---|---|---|
| 0 | `0x00` | `channel_id` = `CONTROL` |
| 1 | `0x03` | flags = `FIRST\|LAST` (`BULK`) |
| 2–3 | `0x00 0x06` | frame size = 6 (`SHORT`) |
| 4–5 | `0x00 0x01` | `MessageId` = `VERSION_REQUEST` |
| 6–7 | `0x00 0x01` | `major` = 1 |
| 8–9 | `0x00 0x07` | `minor` = 7 |

## Concrete example: `VERSION_RESPONSE` (our reply, 12 bytes total)

| Byte(s) | Value | Meaning |
|---|---|---|
| 0 | `0x00` | `channel_id` = `CONTROL` |
| 1 | `0x03` | flags = `BULK` |
| 2–3 | `0x00 0x08` | frame size = 8 |
| 4–5 | `0x00 0x02` | `MessageId` = `VERSION_RESPONSE` |
| 6–7 | major | mirrored from the request |
| 8–9 | minor | mirrored from the request |
| 10–11 | `0x00 0x00` | status = `MATCH` |

## `EXTENDED` layout (true multi-fragment `FIRST` frame only, flags = `0x01`)

| Byte offset | Field | Size |
|---|---|---|
| 0 | `channel_id` | 1 |
| 1 | flags (`0x01`, `FIRST` only) | 1 |
| 2–3 | this fragment's size | 2 |
| 4–7 | total logical message size | 4 |
| 8+ | this fragment's payload | `size` bytes from offset 2–3 |

## Accessory-mode identification (separate layer, below all of this)

Not part of the frame format itself, but the gate before any of it flows —
`accessory_filter.xml` must match on `manufacturer="Android"` and one of
three `model` values, extracted directly from Google's own Android Auto
APK (not guessed): `"Android Auto"` (real vehicle head units),
`"Android Open Automotive Protocol"` (Google's Desktop Head Unit), and
`"Android"` (a third variant their own filter also matches).
