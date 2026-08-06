package com.example.aaphone.protocol.framing;

import com.example.aaphone.protocol.transport.Transport;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Reads/writes framed messages over a {@link Transport}: demultiplexes
 * incoming frames to the handler registered for each frame's channel id, and
 * reassembles messages that span multiple fragments.
 *
 * Not implemented yet — this is the skeleton milestone M1 fills in. See
 * {@link FrameHeader} for why the byte-level details must come from a
 * verified source rather than being hand-rolled.
 */
public class ChannelMultiplexer {

    private final Transport transport;
    private final Map<Integer, Consumer<byte[]>> handlers = new HashMap<>();

    public ChannelMultiplexer(Transport transport) {
        this.transport = transport;
    }

    public void registerChannel(int channelId, Consumer<byte[]> onMessage) {
        handlers.put(channelId, onMessage);
    }

    /** Frames, encrypts (post-handshake), and writes {@code payload} on {@code channelId}. */
    public void sendMessage(int channelId, byte[] payload) {
        throw new UnsupportedOperationException(
            "Frame + encrypt + write payload over transport per verified FrameHeader layout");
    }

    /** Reads one frame from the transport, decrypts/reassembles as needed, and dispatches it. */
    public void pumpOnce() {
        throw new UnsupportedOperationException(
            "Read frame, decrypt, reassemble fragments, dispatch to handlers[channelId]");
    }
}
