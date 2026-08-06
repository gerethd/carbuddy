package com.example.aaphone.protocol.channel;

public class MockMediaSource implements MediaSource {

    private boolean playing = true;

    @Override
    public NowPlaying nowPlaying() {
        return new NowPlaying("Track Title", "Artist Name", playing);
    }

    @Override
    public void onPlaybackControl(PlaybackCommand command) {
        switch (command) {
            case PLAY:
                playing = true;
                break;
            case PAUSE:
                playing = false;
                break;
            case NEXT:
            case PREVIOUS:
                // no real playlist yet
                break;
        }
    }
}
