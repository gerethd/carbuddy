package com.example.aaphone.protocol.handshake;

/**
 * Confirmed values from aasdk's {@code ControlMessageIdsEnum.proto} — see
 * docs/design/0003-aasdk-protocol-notes.md. Only the handful needed for the
 * handshake are listed here; the rest (ping, navigation focus, shutdown,
 * audio focus, service discovery, channel open) belong wherever
 * {@code ChannelMultiplexer} ends up owning general control-channel
 * dispatch (task #4) — duplicated here would drift.
 */
public final class ControlMessageId {
    public static final int VERSION_REQUEST = 0x0001;
    public static final int VERSION_RESPONSE = 0x0002;
    public static final int SSL_HANDSHAKE = 0x0003;
    public static final int AUTH_COMPLETE = 0x0004;

    private ControlMessageId() {
    }
}
