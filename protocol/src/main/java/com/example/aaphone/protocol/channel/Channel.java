package com.example.aaphone.protocol.channel;

/** A single AA protocol channel (video, audio, input, sensor, navigation, media, messaging, ...). */
public interface Channel {
    int getId();

    void onOpen();

    void onMessage(byte[] payload);

    void onClose();
}
