#!/usr/bin/env bash
#
# Decode a raw byte dump captured off the AA (Android Auto) transport into a
# human-readable breakdown of each frame.
#
# Frame layout (confirmed against aasdk -- see
# docs/design/0003-aasdk-protocol-notes.md for sourcing and open questions):
#   byte 0        channel_id
#   byte 1        flags: bit0=FIRST, bit1=LAST, bit2=MessageType::CONTROL,
#                 bit3=ENCRYPTED
#   size field    confirmed from FrameSize.cpp/MessageInStream.cpp: SHORT
#                 (2 bytes, big-endian uint16 frame size) unless flags is
#                 EXACTLY FIRST-without-LAST (0x01, a true "more fragments
#                 coming" frame, as opposed to BULK=FIRST|LAST=0x03 which is
#                 a complete single-frame message) -- in that one case it's
#                 EXTENDED (6 bytes: 2-byte frame size + 4-byte big-endian
#                 uint32 total message size).
#   payload       If channel_id == 0 (CONTROL) and unencrypted, the first 2
#                 payload bytes are treated as a MessageId (big-endian
#                 uint16) and looked up against ControlMessageIdsEnum. For
#                 VERSION_REQUEST (4-byte body) and VERSION_RESPONSE (6-byte
#                 body), the rest is further decoded as major/minor (and, for
#                 VERSION_RESPONSE, a status enum) uint16s -- confirmed from
#                 ControlServiceChannel.cpp. Anything else is hex-dumped.
#
# NOTE: only channel_id == 0 (CONTROL) is protocol-fixed. Any other channel
# id is unlabeled here beyond aasdk's own head-unit-emulator convention --
# the real id for anything else is only known once you've read the head
# unit's ServiceDiscoveryResponse, which this script doesn't have.
#
# Usage:
#   ./decode-frame.sh 0 3 0 6 0 1 0 1 0 7
#   echo "0 3 0 6 0 1 0 1 0 7" | ./decode-frame.sh
#   ./decode-frame.sh 0x00 0x03 0x00 0x06 0x00 0x01 0x00 0x01 0x00 0x07
#   ./decode-frame.sh "[0, 3, 0, 6, 0, 1, 0, 1, 0, 7]"   # brackets/commas are stripped
#
# Multiple concatenated frames in one input are decoded in sequence.

set -euo pipefail

control_message_name() {
    case "$1" in
        1) echo "VERSION_REQUEST" ;;
        2) echo "VERSION_RESPONSE" ;;
        3) echo "SSL_HANDSHAKE" ;;
        4) echo "AUTH_COMPLETE" ;;
        5) echo "SERVICE_DISCOVERY_REQUEST" ;;
        6) echo "SERVICE_DISCOVERY_RESPONSE" ;;
        7) echo "CHANNEL_OPEN_REQUEST" ;;
        8) echo "CHANNEL_OPEN_RESPONSE" ;;
        11) echo "PING_REQUEST" ;;
        12) echo "PING_RESPONSE" ;;
        13) echo "NAVIGATION_FOCUS_REQUEST" ;;
        14) echo "NAVIGATION_FOCUS_RESPONSE" ;;
        15) echo "SHUTDOWN_REQUEST" ;;
        16) echo "SHUTDOWN_RESPONSE" ;;
        17) echo "VOICE_SESSION_REQUEST" ;;
        18) echo "AUDIO_FOCUS_REQUEST" ;;
        19) echo "AUDIO_FOCUS_RESPONSE" ;;
        *) printf "unknown (0x%04x)" "$1" ;;
    esac
}

# aasdk's own channel-id convention when it acts as a head unit -- NOT
# protocol-fixed except CONTROL. See docs/design/0003-aasdk-protocol-notes.md.
aasdk_channel_hint() {
    case "$1" in
        0) echo "CONTROL -- the only value that's actually protocol-fixed" ;;
        1) echo "aasdk convention: INPUT (unconfirmed against a real head unit)" ;;
        2) echo "aasdk convention: SENSOR (unconfirmed against a real head unit)" ;;
        3) echo "aasdk convention: VIDEO (unconfirmed against a real head unit)" ;;
        4) echo "aasdk convention: MEDIA_AUDIO (unconfirmed against a real head unit)" ;;
        5) echo "aasdk convention: SPEECH_AUDIO (unconfirmed against a real head unit)" ;;
        6) echo "aasdk convention: SYSTEM_AUDIO (unconfirmed against a real head unit)" ;;
        7) echo "aasdk convention: AV_INPUT (unconfirmed against a real head unit)" ;;
        8) echo "aasdk convention: BLUETOOTH (unconfirmed against a real head unit)" ;;
        255) echo "NONE" ;;
        *) echo "no aasdk convention for this id -- real id is negotiated via ServiceDiscoveryResponse" ;;
    esac
}

decode_flags() {
    local flags=$1
    local bits=()
    (( flags & 0x01 )) && bits+=("FIRST")
    (( flags & 0x02 )) && bits+=("LAST")
    (( flags & 0x04 )) && bits+=("MessageType::CONTROL (NOTE: observed unset on a real VERSION_REQUEST -- open discrepancy, see doc)")
    (( flags & 0x08 )) && bits+=("ENCRYPTED")
    if [ "${#bits[@]}" -eq 0 ]; then
        echo "MIDDLE, SPECIFIC, PLAIN (no bits set)"
    else
        local IFS=", "
        echo "${bits[*]}"
    fi
}

hex_dump() {
    local out=""
    for byte in "$@"; do
        out+=$(printf '%02x ' "$byte")
    done
    echo "$out"
}

# --- read input into a flat array of byte values (0-255) ---
if [ "$#" -gt 0 ]; then
    input="$*"
else
    input="$(cat)"
fi

# strip commas/brackets so pasted output like "[0, 3, 0, 6]" also works
input=$(echo "$input" | tr -d '[],')

bytes=()
for tok in $input; do
    if [[ "$tok" == 0x* || "$tok" == 0X* ]]; then
        bytes+=("$(( tok ))")
    else
        bytes+=("$(( 10#$tok ))")
    fi
done

total=${#bytes[@]}
i=0
frame_num=0

echo "Decoded ${total} bytes:"
echo

while [ "$i" -lt "$total" ]; do
    remaining=$(( total - i ))
    if [ "$remaining" -lt 4 ]; then
        echo "-- ${remaining} trailing byte(s), too short for a header+size: $(hex_dump "${bytes[@]:i:remaining}")"
        break
    fi

    channel=${bytes[i]}
    flags=${bytes[i+1]}

    # EXTENDED size field applies only to a true FIRST-without-LAST frame
    # (0x01) -- BULK (FIRST|LAST=0x03), a complete single-frame message,
    # uses SHORT. See FrameSize.cpp/MessageInStream.cpp.
    if [ "$(( flags & 0x03 ))" -eq 1 ]; then
        size_kind="EXTENDED"
        size_field_len=6
    else
        size_kind="SHORT"
        size_field_len=2
    fi

    if [ "$remaining" -lt $(( 2 + size_field_len )) ]; then
        echo "-- ${remaining} trailing byte(s), too short for a ${size_kind} size field: $(hex_dump "${bytes[@]:i:remaining}")"
        break
    fi

    size=$(( (bytes[i+2] << 8) | bytes[i+3] ))
    if [ "$size_kind" = "EXTENDED" ]; then
        total_size=$(( (bytes[i+4] << 24) | (bytes[i+5] << 16) | (bytes[i+6] << 8) | bytes[i+7] ))
    fi
    payload_start=$(( i + 2 + size_field_len ))
    available_payload=$(( total - payload_start ))
    consumed_this_frame=$(( available_payload < size ? available_payload : size ))

    frame_num=$(( frame_num + 1 ))
    echo "Frame #${frame_num} (bytes ${i}-$(( payload_start + consumed_this_frame - 1 ))):"
    echo "  channel_id = ${channel}  ($(aasdk_channel_hint "$channel"))"
    echo "  flags      = ${flags} (0x$(printf '%02x' "$flags")) -> $(decode_flags "$flags")"
    if [ "$size_kind" = "EXTENDED" ]; then
        echo "  size       = ${size} (EXTENDED, total message size = ${total_size})"
    else
        echo "  size       = ${size} (SHORT)"
    fi

    if [ "$available_payload" -lt "$size" ]; then
        echo "  payload    = only ${available_payload}/${size} declared bytes present -- PARTIAL: $(hex_dump "${bytes[@]:payload_start:available_payload}")"
        i=$total
        continue
    fi

    payload=("${bytes[@]:payload_start:size}")

    is_control_channel=0
    [ "$channel" -eq 0 ] && is_control_channel=1
    is_encrypted=$(( flags & 0x08 ))

    if [ "$is_control_channel" -eq 1 ] && [ "$is_encrypted" -eq 0 ] && [ "$size" -ge 2 ]; then
        message_id=$(( (payload[0] << 8) | payload[1] ))
        message_name=$(control_message_name "$message_id")
        echo "  messageId  = ${message_id} (0x$(printf '%04x' "$message_id")) -> ${message_name}"
        body=("${payload[@]:2}")
        if [ "${#body[@]}" -gt 0 ]; then
            echo "  body       = $(hex_dump "${body[@]}")"
            if [ "$message_id" -eq 1 ] && [ "${#body[@]}" -eq 4 ]; then
                major=$(( (body[0] << 8) | body[1] ))
                minor=$(( (body[2] << 8) | body[3] ))
                echo "  version    = ${major}.${minor} (VersionRequest body, confirmed layout)"
            elif [ "$message_id" -eq 2 ] && [ "${#body[@]}" -eq 6 ]; then
                major=$(( (body[0] << 8) | body[1] ))
                minor=$(( (body[2] << 8) | body[3] ))
                status=$(( (body[4] << 8) | body[5] ))
                status_name="MISMATCH"
                [ "$status" -eq 0 ] && status_name="MATCH"
                echo "  version    = ${major}.${minor}, status=${status} (${status_name}) (VersionResponse body, confirmed layout)"
            fi
        else
            echo "  body       = (empty)"
        fi
    else
        echo "  payload    = $(hex_dump "${payload[@]}")  (not a plain CONTROL-channel message -- not decoded further by this script)"
    fi

    echo
    i=$(( payload_start + size ))
done

echo "Consumed ${i}/${total} bytes across ${frame_num} frame(s)."
