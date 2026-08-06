package com.example.aaphone.protocol.framing;

import com.example.aaphone.protocol.transport.Transport;

import java.io.ByteArrayOutputStream;

/** In-memory {@link Transport} test double: reads come from a pre-loaded byte queue, writes are captured. */
class FakeTransport implements Transport {

    private final byte[] toRead;
    private int readOffset = 0;
    private final ByteArrayOutputStream written = new ByteArrayOutputStream();

    FakeTransport(byte[] toRead) {
        this.toRead = toRead;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) {
        if (readOffset >= toRead.length) {
            return -1;
        }
        int available = toRead.length - readOffset;
        int toCopy = Math.min(length, available);
        System.arraycopy(toRead, readOffset, buffer, offset, toCopy);
        readOffset += toCopy;
        return toCopy;
    }

    @Override
    public void write(byte[] buffer, int offset, int length) {
        written.write(buffer, offset, length);
    }

    @Override
    public boolean isConnected() {
        return true;
    }

    @Override
    public void close() {
        // no-op
    }

    byte[] writtenBytes() {
        return written.toByteArray();
    }
}
