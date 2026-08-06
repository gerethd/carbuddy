package com.example.aaphone.protocol.channel;

/**
 * Video sink channel — required by every head unit regardless of which
 * content channels (nav/media/messaging) are wired up, since head units
 * expect an H.264 video stream to render.
 *
 * Out of scope for the current milestone focus (nav/media/messaging) but
 * left stubbed so {@link com.example.aaphone.protocol.framing.ChannelMultiplexer}
 * has a complete channel set to register. Milestone M1 can get away with
 * emitting a single static encoded frame; see docs/ROADMAP.md.
 */
public class VideoChannel implements Channel {

    @Override
    public int getId() {
        return ChannelId.VIDEO;
    }

    @Override
    public void onOpen() {
        throw new UnsupportedOperationException("Negotiate resolution/codec, then start emitting H.264 frames");
    }

    @Override
    public void onMessage(byte[] payload) {
        throw new UnsupportedOperationException("Handle video-channel control messages (e.g. focus/unfocus)");
    }

    @Override
    public void onClose() {
        throw new UnsupportedOperationException("Stop encoder");
    }
}
