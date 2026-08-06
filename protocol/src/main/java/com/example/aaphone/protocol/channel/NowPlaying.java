package com.example.aaphone.protocol.channel;

import java.util.Objects;

public final class NowPlaying {
    private final String title;
    private final String artist;
    private final boolean playing;

    public NowPlaying(String title, String artist, boolean playing) {
        this.title = title;
        this.artist = artist;
        this.playing = playing;
    }

    public String getTitle() {
        return title;
    }

    public String getArtist() {
        return artist;
    }

    public boolean isPlaying() {
        return playing;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof NowPlaying)) return false;
        NowPlaying that = (NowPlaying) o;
        return playing == that.playing && Objects.equals(title, that.title) && Objects.equals(artist, that.artist);
    }

    @Override
    public int hashCode() {
        return Objects.hash(title, artist, playing);
    }

    @Override
    public String toString() {
        return "NowPlaying{title='" + title + "', artist='" + artist + "', playing=" + playing + '}';
    }
}
