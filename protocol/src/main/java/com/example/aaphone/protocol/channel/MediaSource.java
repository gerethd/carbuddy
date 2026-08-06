package com.example.aaphone.protocol.channel;

public interface MediaSource {
    NowPlaying nowPlaying();

    void onPlaybackControl(PlaybackCommand command);
}
