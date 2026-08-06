package com.example.aaphone.protocol.channel;

/**
 * Input channel — receives touch/button/rotary events from the head unit.
 *
 * Stubbed for now; not the current milestone focus. See docs/ROADMAP.md.
 */
public class InputChannel implements Channel {

    @Override
    public int getId() {
        return ChannelId.INPUT;
    }

    @Override
    public void onOpen() {
        // no-op for now
    }

    @Override
    public void onMessage(byte[] payload) {
        throw new UnsupportedOperationException(
            "Parse touch/button/rotary event and route to whatever screen is focused");
    }

    @Override
    public void onClose() {
        // no-op for now
    }
}
