package com.example.aaphone.protocol.channel;

/**
 * Placeholder channel-id constants.
 *
 * Per docs/design/0003-aasdk-protocol-notes.md: only {@link #CONTROL} is
 * actually protocol-fixed. Every other value here is aasdk's own choice when
 * it acts as a head-unit emulator, not a protocol-mandated number — real
 * channel ids for anything else must be read from the head unit's
 * ServiceDiscoveryResponse at connection time. These constants exist so the
 * rest of the skeleton (registration, mock content) has something concrete
 * to compile against during milestone M1.
 */
public final class ChannelId {
    public static final int CONTROL = 0;
    public static final int INPUT = 1;
    public static final int SENSOR = 2;
    public static final int VIDEO = 3;
    public static final int AUDIO_MEDIA = 4;
    public static final int AUDIO_SPEECH = 5;
    public static final int MEDIA_STATUS = 6;
    public static final int NAVIGATION_STATUS = 7;
    public static final int MESSAGING = 8;

    private ChannelId() {
    }
}
