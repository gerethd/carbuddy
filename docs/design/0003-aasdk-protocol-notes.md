# 0003 — Protocol notes, sourced from aasdk

Reference notes distilled from reading `f1xpl/aasdk` (https://github.com/f1xpl/aasdk)
directly — not reconstructed from memory. Facts below are written in our own
words/tables rather than pasted source, but every field name/number/enum
value is transcribed as-is from the source, since those are the actual wire
contract, not something safe to paraphrase loosely.

**License note**: aasdk is **GPLv3**. Reading it for facts (message shapes,
field numbers, framing) to write our own independent implementation is fine.
*Porting its actual code/logic verbatim* is a different matter — if enough of
its expression (not just facts) ends up in our code, our project likely
inherits GPL's copyleft obligations. Keep implementations here written from
these notes, not transcribed from aasdk's `.cpp` files, if the app is meant
to have different licensing terms.

## Frame header (confirmed)

`FrameHeader.getSizeOf() == 2` — two bytes:

| Byte | Contents |
|---|---|
| 0 | `channel_id` (see table below) |
| 1 | flags byte, bitwise OR of: |

Flags byte bit assignment (confirmed from `FrameType.hpp`/`EncryptionType.hpp`/`MessageType.hpp`):

| Bit | Source enum | Meaning when set |
|---|---|---|
| 0 (`0x01`) | `FrameType::FIRST` | first fragment of a (possibly) multi-frame message |
| 1 (`0x02`) | `FrameType::LAST` | last fragment — `FIRST\|LAST` (`0x03`, aliased `BULK`) means single-frame message |
| 2 (`0x04`) | `MessageType::CONTROL` | control-channel message (unset = `SPECIFIC`, i.e. a channel-specific message) |
| 3 (`0x08`) | `EncryptionType::ENCRYPTED` | payload is TLS-wrapped (unset = `PLAIN`) |

This matches the general shape flagged as unverified in our original
`FrameHeader.kt` — now confirmed rather than guessed.

## Frame size (now fully confirmed, from `FrameSize.cpp`/`MessageInStream.cpp`)

- `SHORT` = 2 bytes: `frameSize` as big-endian `uint16`.
- `EXTENDED` = 6 bytes: `frameSize` (big-endian `uint16`) + `totalSize`
  (big-endian `uint32`).
- Which one is used is decided by `MessageInStream`:
  `FrameSize::getSizeOf(frameHeader.getType() == FrameType::FIRST ? EXTENDED : SHORT)`.
  Critically, this is a check against `FrameType::FIRST` **exactly** (value
  `1`), not "is the FIRST bit set" — so `BULK` (`FIRST|LAST` = `3`, a
  complete single-frame message) does **not** get the extended form, only a
  true "more fragments coming" first-of-many frame does. This exactly
  explains our own empirical capture below: the `VERSION_REQUEST` frame had
  flags `0x03` (`BULK`) and used the 2-byte `SHORT` form, which is why
  header(2) + size(2) + payload(6) added up to exactly 10 bytes with nothing
  left over.
- Fragment reassembly: `MessageInStream` loops — read header, read
  size (`SHORT` or `EXTENDED` per the rule above), read that many payload
  bytes, decrypt if needed, append to the accumulating message — until
  `recentFrameType_` is `BULK` or `LAST`, at which point the message is
  complete and dispatched.

## Message ID

`MessageId.getSizeOf() == 2` — a 2-byte id, immediately following frame size,
identifying which message this payload is. For `CONTROL` frames, values come
from `ControlMessageIdsEnum` (below); for `SPECIFIC` (channel) frames, from
that channel's own message-id enum (e.g. `InputChannelMessageIdsEnum`).

## Channel IDs — **important correction to our earlier placeholder**

`Messenger/ChannelId.hpp` hardcodes:

| Name | Value |
|---|---|
| `CONTROL` | 0 |
| `INPUT` | 1 |
| `SENSOR` | 2 |
| `VIDEO` | 3 |
| `MEDIA_AUDIO` | 4 |
| `SPEECH_AUDIO` | 5 |
| `SYSTEM_AUDIO` | 6 |
| `AV_INPUT` | 7 |
| `BLUETOOTH` | 8 |
| `NONE` | 255 |

**Only `CONTROL = 0` is a protocol-fixed constant.** Every other channel id
here is just aasdk's own choice *when it acts as the head unit* — real
channel ids are negotiated per-connection via `ServiceDiscoveryResponse`
(below), where the head unit tells us which numeric id it's using for each
channel it supports. Our `ChannelId.kt` currently hardcodes all of these as
constants — that's wrong for anything but `CONTROL`, and needs to read the
real head unit's service-discovery response instead. Tracked as a correction
to task #2.

**There is no `NAVIGATION`, `MEDIA_STATUS`, or `MESSAGING` entry in this
enum at all** — see the "media/messaging" finding below; that's not an
oversight in transcription, it reflects the actual protocol surface aasdk
implements.

## Control messages (confirmed)

`ControlMessageIdsEnum.proto`:

| Name | Value |
|---|---|
| `VERSION_REQUEST` | `0x0001` |
| `VERSION_RESPONSE` | `0x0002` |
| `SSL_HANDSHAKE` | `0x0003` |
| `AUTH_COMPLETE` | `0x0004` |
| `SERVICE_DISCOVERY_REQUEST` | `0x0005` |
| `SERVICE_DISCOVERY_RESPONSE` | `0x0006` |
| `CHANNEL_OPEN_REQUEST` | `0x0007` |
| `CHANNEL_OPEN_RESPONSE` | `0x0008` |
| `PING_REQUEST` | `0x000b` |
| `PING_RESPONSE` | `0x000c` |
| `NAVIGATION_FOCUS_REQUEST` | `0x000d` |
| `NAVIGATION_FOCUS_RESPONSE` | `0x000e` |
| `SHUTDOWN_REQUEST` | `0x000f` |
| `SHUTDOWN_RESPONSE` | `0x0010` |
| `VOICE_SESSION_REQUEST` | `0x0011` |
| `AUDIO_FOCUS_REQUEST` | `0x0012` |
| `AUDIO_FOCUS_RESPONSE` | `0x0013` |

(`0x0009`/`0x000a` are gaps in this list — not a transcription error, the
source skips them, presumably deprecated/reserved.)

## Handshake sequence, tied to the above

Message **direction** below is confirmed from which side of aasdk's own
code each message lives in — `ControlServiceChannel.cpp` is aasdk's
head-unit-role implementation, so a method that *sends* a message there
means the real head unit sends it to the phone, and vice versa.

1. **Version request/response** (`VERSION_REQUEST`/`VERSION_RESPONSE`, both `CONTROL`, unencrypted). No `.proto` exists for these — confirmed raw bytes, not protobuf, from `ControlServiceChannel.cpp`:
   - **Head unit → phone**: `VersionRequest` body, 4 bytes — `major` (big-endian `uint16`), `minor` (big-endian `uint16`). Matches our own empirical capture exactly (major=1, minor=7) — that was a correct read, not a lucky guess.
   - **Phone → head unit**: `VersionResponse` body, 6 bytes — `major`, `minor` (same as above), then `status` (big-endian `uint16`, values from `VersionResponseStatusEnum`: `MATCH = 0`, `MISMATCH = 0xFFFF`). We mirror the head unit's own version back with `MATCH`, having no independent basis to claim otherwise.
2. **SSL handshake** (`SSL_HANDSHAKE`, `CONTROL`, unencrypted *framing* carrying the TLS bytes themselves — the framing isn't encrypted, the payload already is by virtue of being raw TLS record bytes) — both sides send/receive these as the handshake progresses. Confirmed from `Cryptor.cpp`:
   - **aasdk (head unit) is the TLS *client*** (`sslWrapper_->setConnectState(ssl_)`) — meaning **the real head unit is the TLS client, and the phone (us) is the TLS *server***. Backed by OpenSSL memory `BIO`s (`doHandshake()`/`readHandshakeBuffer()`/`writeHandshakeBuffer()`), not a plain socket — confirmed, matches what we suspected.
   - aasdk's certificate/key (`cCertificate`/`cPrivateKey`, CN "Google Automotive Linux") is the **head unit's own identity** for its client role — not something to copy for our server role. We generated our own, unrelated self-signed identity instead (see `AaServerIdentity`).
3. **Auth complete** (`AUTH_COMPLETE`) — **head unit → phone** (`sendAuthComplete` lives in aasdk's head-unit code), `AuthCompleteIndication{status: Status.Enum}` (`OK = 0` / `FAIL = 1`), confirmed sent `PLAIN` (unencrypted) — i.e. this is the last plain-framed message before the established session is used for everything after.
4. **Service discovery** — phone → head unit: `ServiceDiscoveryRequest{device_name (field 4), device_brand (field 5)}`. Note the gap: fields 1–3 don't exist in this message — likely deprecated across protocol versions, not a transcription error.
5. Head unit responds `ServiceDiscoveryResponse` with: `channels` (repeated `ChannelDescriptor`), `head_unit_name`, `car_model`/`car_year`/`car_serial`, `left_hand_drive_vehicle`, `headunit_manufacturer`/`headunit_model`, `sw_build`/`sw_version`, `can_play_native_media_during_vr`, `hide_clock`. Each `ChannelDescriptor` has `channel_id` (the *real* negotiated id) plus exactly one of `sensor_channel`/`av_channel`/`input_channel`/`av_input_channel`/`bluetooth_channel`/`navigation_channel`/`vendor_extension_channel` describing that channel's config.
6. For each channel we want to use: `CHANNEL_OPEN_REQUEST{priority, channel_id}` → `CHANNEL_OPEN_RESPONSE{status: Status.Enum}`.

## Implementation (task #3)

`AaHandshake` implements steps 1–3 above:

- `FrameCodec` (new, shared with future `ChannelMultiplexer` work) encodes/decodes frames per the confirmed layout, including real FIRST/MIDDLE/LAST reassembly on read.
- `AaServerIdentity` builds the phone's TLS server `SSLContext` from a freshly-generated, project-specific self-signed cert/key (not aasdk's).
- The TLS handshake itself is driven with `javax.net.ssl.SSLEngine` in server mode (`setUseClientMode(false)`) — the standard Java API for exactly this "drive TLS over arbitrary framing, not a real socket" case.
- **Android-specific gotcha found while wiring this up**: Android's `SSLEngineResult.HandshakeStatus` has no `NEED_UNWRAP_AGAIN` (a newer-JDK-only addition) — the retry-with-already-buffered-bytes case has to be handled by simply not advancing the loop's status variable on `BUFFER_UNDERFLOW`, rather than relying on that status value.
- Not yet exercised against a real head unit end-to-end — `FrameCodecTest` covers the framing logic (including a direct regression test against the real captured `VERSION_REQUEST` bytes) with a fake in-memory transport, but the TLS handshake itself has only been validated by compiling, not by a real handshake completing.

## USB accessory mode query sequence (already empirically validated in the vehicle)

`AccessoryModeQueryType` enumerates the AOA control-transfer sequence:
`PROTOCOL_VERSION → SEND_MANUFACTURER → SEND_MODEL → SEND_DESCRIPTION →
SEND_VERSION → SEND_URI → SEND_SERIAL → START`. We already got a real
permission dialog in the vehicle, which confirms our `accessory_filter.xml`
guess (`manufacturer="Android"`, `model="Android Auto"`) matches what the
head unit actually sends for those two queries — the other four string
values (description/version/uri/serial) aren't things our app needs to
supply, since Android's own OS drives this exchange, not our app code.

## The important finding: navigation is a real channel, media/messaging are not

`ChannelDescriptorData.proto`'s channel-type union is: `sensor_channel`,
`av_channel`, `input_channel`, `av_input_channel`, `bluetooth_channel`,
`navigation_channel`, `vendor_extension_channel`. **There is no
media-metadata channel and no messaging channel.** Cross-checked against
`AudioTypeEnum.proto` (`NONE`/`SPEECH`/`SYSTEM`/`MEDIA`/`ALARM`) — `MEDIA` is
just a *label on an audio stream* carried over the ordinary `MEDIA_AUDIO`
channel, not a structured "now playing" metadata channel.

**Implication**: in real Android Auto, the "now playing" bar and messaging
popups are not delivered as structured protocol messages at all — Google's
app renders them as pixels into the `VIDEO` channel it's already streaming,
and taps on them arrive back over the ordinary `INPUT` channel like any
other touch. There's no dedicated wire-level channel to reverse-engineer for
those two.

**This changes M2 and M4** (see `docs/ROADMAP.md`, updated): `MediaChannel`
and `MessagingChannel` as scaffolded — dedicated channel ids carrying
structured `NowPlaying`/`IncomingMessage` messages — don't correspond to
anything in the real wire protocol. Real work here is UI rendered into our
own `VideoChannel` output plus interpreting `InputChannel` taps against that
UI's layout, not a channel-level integration. `NavigationChannel` is
unaffected — `navigation_channel` is confirmed as a first-class channel type
with its own focus-request/response messages (`NavigationFocusRequestMessage.proto`/`NavigationFocusResponseMessage.proto`, control message ids `0x000d`/`0x000e`).

## Still unconfirmed

- Exact accessory-mode string values beyond the manufacturer/model pair we already validated empirically (in `AccessoryModeQueryFactory.cpp`/`AccessoryModeSendStringQuery.cpp`) — not needed for our current milestone since Android's OS drives that exchange, not our app code.
- The still-unresolved `MessageType::CONTROL` flag discrepancy noted above (empirically unset on a real `VERSION_REQUEST`).
- Whether our self-signed TLS server identity (see Implementation, above) is actually acceptable to a real head unit — no CA chain validation is confirmed, but whether the head unit inspects certificate contents any further than that is untested.

Everything else previously listed here — `FrameSize` byte widths, version
request/response byte layout, whether `AUTH_COMPLETE` is plain or encrypted,
and the head unit's certificate/key — is now confirmed above, straight from
`FrameSize.cpp`, `MessageInStream.cpp`, `ControlServiceChannel.cpp`, and
`Cryptor.cpp` (the last one turned out not to be reusable for our side
anyway — see the SSL handshake entry above).

## Empirical observation from the real head unit

First bytes read off the transport on accessory attach (corrected capture —
an earlier pass missed 2 bytes): `[0, 3, 0, 6, 0, 1, 0, 1, 0, 7]`.

Parse against the confirmed `FrameHeader` layout — this one is a **complete**
message, not a partial read:

| Bytes | Field | Value |
|---|---|---|
| `0` | `channel_id` | `0` = `CONTROL` |
| `3` | flags | `0x03` = FIRST\|LAST (single-frame). Bit2 (`MessageType::CONTROL`) and bit3 (`ENCRYPTED`) both unset. |
| `0, 6` | frame size, assumed `SHORT` (2 bytes) | `6` — payload length |
| `0, 1` | payload bytes 1–2, assumed `MessageId` | `0x0001` = **`VERSION_REQUEST`** |
| `0, 1, 0, 7` | payload bytes 3–6, assumed version body | major=`1`, minor=`7` (major-then-minor order is still an assumption) |

Header(2) + size(2) + exactly 6 payload bytes = 10 bytes total, matching the
declared size exactly — confirms the `FrameSize` = `SHORT`/2-byte-big-endian
assumption too, at least for this message. The `MessageId` match is a strong
signal: this is genuinely the head unit's opening `VERSION_REQUEST`, right
where the protocol notes predicted, requesting protocol version 1.7.

**Open discrepancy, still unresolved (independent of the above)**: this
message is unambiguously a control/administrative message, but flags bit2
(`MessageType::CONTROL`, expected set for control-channel administrative
traffic per the header names) is *not* set — bit pattern reads `SPECIFIC`.
Either the CONTROL/SPECIFIC distinction doesn't mean what the enum names
suggest, or something else is going on. Needs the `.cpp`
(`ControlServiceChannel.cpp`/`Messenger.cpp`), not more header-reading, to
resolve — recorded here rather than papered over with a guess.
