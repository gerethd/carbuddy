package com.example.aaphone.protocol.handshake;

import com.example.aaphone.protocol.transport.Transport;

import java.io.ByteArrayOutputStream;

/**
 * In-memory {@link Transport} test double: reads come from a pre-loaded
 * sequence of chunks, writes are captured.
 *
 * Each chunk simulates one discrete physical USB transfer -- a single
 * {@link #read} call never spans across a chunk boundary, matching the real
 * USB accessory descriptor's transfer-oriented semantics (see
 * docs/design/0004-frame-codec-byte-layout.md). Mirrors
 * {@code framing.FakeTransport} -- duplicated here rather than shared since
 * that one is package-private to its own test package.
 */
class FakeTransport implements Transport {

    private final byte[][] chunks;
    private int chunkIndex = 0;
    private final ByteArrayOutputStream written = new ByteArrayOutputStream();

    FakeTransport(byte[]... chunks) {
        this.chunks = chunks;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) {
        if (chunkIndex >= chunks.length) {
            return -1;
        }
        byte[] chunk = chunks[chunkIndex];
        chunkIndex++;
        int toCopy = Math.min(length, chunk.length);
        System.arraycopy(chunk, 0, buffer, offset, toCopy);
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
