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

## Frame size (partially confirmed)

`FrameSizeType` has two variants, `SHORT` and `EXTENDED` — confirmed to
exist, but their exact byte widths weren't in the header we read (only in
the `.cpp`, not yet fetched). Public convention for this protocol style is
2 bytes for `SHORT` (this frame's payload length) and a longer form for
`EXTENDED` that also carries the total reassembled message length when the
`FIRST` flag is set — **treat this specific byte width as still unconfirmed**
until the `.cpp` is read, unlike the frame header/flags above.

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

1. **Version request/response** (`VERSION_REQUEST`/`VERSION_RESPONSE`, both `CONTROL`, unencrypted). No `.proto` exists for these — the payload is raw bytes, not protobuf. Exact byte layout (which half is major/minor, endianness) is **not confirmed** from what we've read yet; only that it isn't protobuf-encoded. `VersionResponseStatusEnum.proto` gives the outcome values: `MATCH = 0`, `MISMATCH = 0xFFFF`.
2. **SSL handshake** (`SSL_HANDSHAKE`, `CONTROL`, unencrypted framing carrying encrypted-handshake bytes) — both sides exchange these until the TLS session establishes. Confirmed from `Cryptor.hpp`: this is backed by OpenSSL memory `BIO`s (`doHandshake()`, `readHandshakeBuffer()`/`writeHandshakeBuffer()`) rather than a plain `SSLSocket` — matches what we suspected, now confirmed. `Cryptor` also references a hardcoded certificate/key pair (`cCertificate`/`cPrivateKey` constants) — **values live in the `.cpp`, not yet fetched**; needed before `AaHandshake` can actually complete a handshake.
3. **Auth complete** (`AUTH_COMPLETE`) — `AuthCompleteIndication{status: Status.Enum}` (`OK = 0` / `FAIL = 1`). Whether this specific message is sent plain or already inside the encrypted session is **not confirmed** yet.
4. **Service discovery** — we (phone) send `ServiceDiscoveryRequest{device_name (field 4), device_brand (field 5)}`. Note the gap: fields 1–3 don't exist in this message — likely deprecated across protocol versions, not a transcription error.
5. Head unit responds `ServiceDiscoveryResponse` with: `channels` (repeated `ChannelDescriptor`), `head_unit_name`, `car_model`/`car_year`/`car_serial`, `left_hand_drive_vehicle`, `headunit_manufacturer`/`headunit_model`, `sw_build`/`sw_version`, `can_play_native_media_during_vr`, `hide_clock`. Each `ChannelDescriptor` has `channel_id` (the *real* negotiated id) plus exactly one of `sensor_channel`/`av_channel`/`input_channel`/`av_input_channel`/`bluetooth_channel`/`navigation_channel`/`vendor_extension_channel` describing that channel's config.
6. For each channel we want to use: `CHANNEL_OPEN_REQUEST{priority, channel_id}` → `CHANNEL_OPEN_RESPONSE{status: Status.Enum}`.

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

## Still unconfirmed / needs the `.cpp` sources, not just headers/protos

- Exact `FrameSize` byte widths for `SHORT` vs `EXTENDED`.
- Version request/response raw byte layout.
- Whether `AUTH_COMPLETE` is sent plain or encrypted.
- The actual certificate/private key `Cryptor` uses (`cCertificate`/`cPrivateKey` values).
- Exact accessory-mode string values beyond the manufacturer/model pair we already validated empirically.
