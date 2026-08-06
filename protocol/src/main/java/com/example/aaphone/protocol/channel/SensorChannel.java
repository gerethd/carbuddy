package com.example.aaphone.protocol.channel;

/**
 * Sensor channel — reports driving status / gear / speed data the head unit
 * requests, which some head units use to gate UI (e.g. disable text entry
 * while driving).
 *
 * Stubbed for now; not the current milestone focus. See docs/ROADMAP.md.
 */
public class SensorChannel implements Channel {

    @Override
    public int getId() {
        return ChannelId.SENSOR;
    }

    @Override
    public void onOpen() {
        // no-op for now
    }

    @Override
    public void onMessage(byte[] payload) {
        throw new UnsupportedOperationException("Handle sensor subscription requests from the head unit");
    }

    @Override
    public void onClose() {
        // no-op for now
    }
}
