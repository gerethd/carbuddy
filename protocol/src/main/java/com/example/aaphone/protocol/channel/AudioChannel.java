package com.example.aaphone.protocol.channel;

/**
 * Audio source/sink channel (media playback audio, voice/nav prompt audio,
 * and mic capture for voice commands are separate channel instances in the
 * real protocol — see {@link ChannelId}).
 *
 * Stubbed for now; not the current milestone focus. See docs/ROADMAP.md.
 */
public class AudioChannel implements Channel {

    private final int id;

    public AudioChannel(int id) {
        this.id = id;
    }

    @Override
    public int getId() {
        return id;
    }

    @Override
    public void onOpen() {
        throw new UnsupportedOperationException("Start streaming decoded/encoded audio for this channel's role");
    }

    @Override
    public void onMessage(byte[] payload) {
        throw new UnsupportedOperationException("Handle audio-channel control/focus messages");
    }

    @Override
    public void onClose() {
        throw new UnsupportedOperationException("Stop audio stream");
    }
}
