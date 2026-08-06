package com.example.aaphone.protocol.channel;

/**
 * Media status/browse channel — scaffolded shape only.
 *
 * Milestone M1: backed by {@link MockMediaSource} so the channel path can be
 * validated without a real player.
 *
 * CORRECTION (see docs/design/0003-aasdk-protocol-notes.md): there is no
 * dedicated media-metadata wire channel in the real protocol — {@code MEDIA}
 * is just an {@code AudioType} label on the ordinary audio channel. Real
 * Android Auto renders the now-playing bar/controls as pixels into the video
 * stream and gets taps back over the input channel. This class's "send
 * NowPlaying over a dedicated channel" design does not correspond to
 * anything real and needs to be redesigned as part of M2 (task #7) around
 * rendering into {@link VideoChannel}'s output and interpreting
 * {@link InputChannel} taps, keeping {@link MediaSource} (a real
 * {@code MediaBrowserServiceCompat} client) as the actual content source.
 */
public class MediaChannel implements Channel {

    private final MediaSource source;

    public MediaChannel() {
        this(new MockMediaSource());
    }

    public MediaChannel(MediaSource source) {
        this.source = source;
    }

    @Override
    public int getId() {
        return ChannelId.MEDIA_STATUS;
    }

    @Override
    public void onOpen() {
        throw new UnsupportedOperationException(
            "Subscribe to source.nowPlaying() and forward NowPlaying as a framed message");
    }

    @Override
    public void onMessage(byte[] payload) {
        throw new UnsupportedOperationException(
            "Parse inbound playback-control message (play/pause/skip/select) and dispatch to source");
    }

    @Override
    public void onClose() {
        throw new UnsupportedOperationException("Unsubscribe from source");
    }
}
