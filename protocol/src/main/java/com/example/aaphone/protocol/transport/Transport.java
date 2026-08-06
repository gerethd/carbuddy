package com.example.aaphone.protocol.transport;

import java.io.Closeable;

/**
 * Abstraction over the underlying byte transport used to talk to a head unit —
 * a wired USB accessory connection or a wireless (Wi-Fi Direct/TCP) socket.
 *
 * This is intentionally just a raw duplex byte stream. AA framing, encryption,
 * and channel multiplexing all live above this layer (see
 * {@code com.example.aaphone.protocol.framing}), so a new transport only needs
 * to implement read/write here to plug into the rest of the stack.
 *
 * Implementations should wrap checked I/O failures in
 * {@link java.io.UncheckedIOException} rather than declaring checked
 * exceptions on {@link #read}/{@link #write}.
 */
public interface Transport extends Closeable {

    /** Blocks until at least one byte is available; returns bytes read into {@code buffer}. */
    int read(byte[] buffer, int offset, int length);

    /** Convenience overload reading into the whole buffer. */
    default int read(byte[] buffer) {
        return read(buffer, 0, buffer.length);
    }

    /** Blocks until all {@code length} bytes from {@code buffer} have been written. */
    void write(byte[] buffer, int offset, int length);

    /** Convenience overload writing the whole buffer. */
    default void write(byte[] buffer) {
        write(buffer, 0, buffer.length);
    }

    boolean isConnected();
}
