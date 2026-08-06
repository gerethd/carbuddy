package com.example.aaphone.protocol.framing;

import java.util.Objects;

/**
 * Header for a single AA protocol frame.
 *
 * IMPORTANT — placeholder, not verified: the field set below (channel id,
 * encrypted/fragment flags, length prefix, optional total-message length for
 * the first fragment of a multi-frame message) reflects the general shape of
 * the protocol as reverse-engineered and described in public write-ups, but
 * the exact byte offsets, sizes, and bit assignments have NOT been checked
 * byte-for-byte here. Before this talks to a real head unit, cross-check
 * every field against an authoritative open-source reference implementation:
 *   - aasdk: https://github.com/f1xpl/aasdk (the SSL/framing layer used by OpenAuto)
 *   - OpenAuto: https://github.com/f1xpl/openauto
 *   - aa-proxy-rs (head-unit-facing side, closest analog to this project's direction)
 *
 * See docs/design/0003-aasdk-protocol-notes.md — the channel-id/flags shape
 * here is now confirmed against aasdk; frame-size byte widths are still not.
 *
 * Do not hand-derive these constants from memory or guesswork — port them
 * from source.
 */
public final class FrameHeader {

    private final int channelId;
    private final boolean encrypted;
    private final boolean firstFragment;
    private final boolean lastFragment;
    private final int payloadLength;
    private final Integer totalMessageLength;

    public FrameHeader(
        int channelId,
        boolean encrypted,
        boolean firstFragment,
        boolean lastFragment,
        int payloadLength,
        Integer totalMessageLength
    ) {
        this.channelId = channelId;
        this.encrypted = encrypted;
        this.firstFragment = firstFragment;
        this.lastFragment = lastFragment;
        this.payloadLength = payloadLength;
        this.totalMessageLength = totalMessageLength;
    }

    public FrameHeader(
        int channelId,
        boolean encrypted,
        boolean firstFragment,
        boolean lastFragment,
        int payloadLength
    ) {
        this(channelId, encrypted, firstFragment, lastFragment, payloadLength, null);
    }

    public int getChannelId() {
        return channelId;
    }

    public boolean isEncrypted() {
        return encrypted;
    }

    public boolean isFirstFragment() {
        return firstFragment;
    }

    public boolean isLastFragment() {
        return lastFragment;
    }

    public int getPayloadLength() {
        return payloadLength;
    }

    /** Only present when {@link #isFirstFragment()} is true and the message spans multiple frames. */
    public Integer getTotalMessageLength() {
        return totalMessageLength;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FrameHeader)) return false;
        FrameHeader that = (FrameHeader) o;
        return channelId == that.channelId
            && encrypted == that.encrypted
            && firstFragment == that.firstFragment
            && lastFragment == that.lastFragment
            && payloadLength == that.payloadLength
            && Objects.equals(totalMessageLength, that.totalMessageLength);
    }

    @Override
    public int hashCode() {
        return Objects.hash(channelId, encrypted, firstFragment, lastFragment, payloadLength, totalMessageLength);
    }

    @Override
    public String toString() {
        return "FrameHeader{"
            + "channelId=" + channelId
            + ", encrypted=" + encrypted
            + ", firstFragment=" + firstFragment
            + ", lastFragment=" + lastFragment
            + ", payloadLength=" + payloadLength
            + ", totalMessageLength=" + totalMessageLength
            + '}';
    }
}
