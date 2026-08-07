package com.example.aaphone.protocol.framing;

import android.util.Log;

import com.example.aaphone.protocol.transport.Transport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Serializable;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Encodes/decodes AA protocol frames per the confirmed layout in
 * docs/design/0003-aasdk-protocol-notes.md:
 * <pre>
 *   byte 0        channel_id
 *   byte 1        flags (bit0=FIRST, bit1=LAST, bit2=MessageType::CONTROL, bit3=ENCRYPTED)
 *   size field    SHORT (2 bytes, big-endian uint16) unless flags is EXACTLY
 *                 FIRST-without-LAST (0x01), in which case EXTENDED (2-byte
 *                 frame size + 4-byte big-endian uint32 total message size)
 *   payload       `size` bytes
 * </pre>
 *
 * Writing only ever produces single-frame BULK messages — the confirmed max
 * SHORT payload (65535 bytes) comfortably covers everything this app sends
 * (version/handshake/auth messages are all tiny), so there's no need to
 * fragment on the way out. Reading DOES reassemble FIRST/MIDDLE/LAST
 * sequences, since the head unit is free to fragment its own messages to us.
 */
public final class FrameCodec {

    private FrameCodec() {
    }

    private static final class ReadBuffer {
        private final byte[] byteBuffer;
        private final int bytesRead;

        public ReadBuffer(byte[] byteBuffer, int bytesRead) {
            this.byteBuffer = byteBuffer;
            this.bytesRead = bytesRead;
        }

        public byte[] getByteBuffer() {
            return byteBuffer;
        }

        public int getBytesRead() {
            return bytesRead;
        }
    }

    /** One physical frame as read off the wire — may be a fragment of a larger logical message. */
    public static final class PhysicalFrame implements Serializable {
        public final int channelId;
        public final boolean firstFragment;
        public final boolean lastFragment;
        public final boolean encrypted;
        public final byte[] payload;

        PhysicalFrame(int channelId, boolean firstFragment, boolean lastFragment, boolean encrypted, byte[] payload) {
            this.channelId = channelId;
            this.firstFragment = firstFragment;
            this.lastFragment = lastFragment;
            this.encrypted = encrypted;
            //release any unneeded memory by keeping large buffer allocated
            ByteArrayOutputStream accumulator = new ByteArrayOutputStream();
            accumulator.writeBytes(payload);
            this.payload = accumulator.toByteArray();

        }

        String serialize() {
            return "Channel ID: " + channelId
                    + " First Fragement: " + firstFragment
                    + " Last Fragment: " + lastFragment
                    + " Encrypted: " + encrypted
                    + " Payload: " + new String(payload);
         }
    }

    /** A fully reassembled logical message (one or more physical frames concatenated). */
    public static final class Message {
        public final int channelId;
        public final boolean encrypted;
        public final byte[] payload;

        Message(int channelId, boolean encrypted, byte[] payload) {
            this.channelId = channelId;
            this.encrypted = encrypted;
            this.payload = payload;
        }
    }

    /** Writes a single-frame (BULK), plain (unencrypted) message: 2-byte big-endian MessageId + body. */
    public static void writePlainMessage(Transport transport, int channelId, int messageId, byte[] body) {
        byte[] payload = new byte[2 + body.length];
        payload[0] = (byte) ((messageId >> 8) & 0xFF);
        payload[1] = (byte) (messageId & 0xFF);
        System.arraycopy(body, 0, payload, 2, body.length);
        writeBulkFrame(transport, channelId, false, payload);
    }

    /** Writes a single-frame (BULK) message with an already-framed payload (e.g. raw TLS handshake bytes). */
    public static void writeBulkFrame(Transport transport, int channelId, boolean encrypted, byte[] payload) {
        if (payload.length > 0xFFFF) {
            throw new IllegalArgumentException(
                "payload of " + payload.length + " bytes exceeds the 65535-byte SHORT frame limit"
                    + " -- fragmentation on write isn't implemented, see FrameCodec's class javadoc");
        }
        int flags = 0x01 | 0x02 | (encrypted ? 0x08 : 0); // BULK = FIRST|LAST
        byte[] frame = new byte[4 + payload.length];
        frame[0] = (byte) channelId;
        frame[1] = (byte) flags;
        frame[2] = (byte) ((payload.length >> 8) & 0xFF);
        frame[3] = (byte) (payload.length & 0xFF);
        System.arraycopy(payload, 0, frame, 4, payload.length);
        transport.write(frame);
    }

    /** Reads and reassembles one complete logical message, following FIRST/MIDDLE/LAST fragmentation. */
    public static Message readMessage(Transport transport) {
        ByteArrayOutputStream accumulator = new ByteArrayOutputStream();
        PhysicalFrame frame = readPhysicalFrame(transport);
        Log.d("CarBuddy", frame.serialize());
        int channelId = frame.channelId;
        boolean encrypted = frame.encrypted;
        accumulator.writeBytes(frame.payload);
        while(!frame.lastFragment){
            frame = readPhysicalFrame(transport);
            accumulator.writeBytes(frame.payload);
        }
        return new Message(channelId, encrypted, accumulator.toByteArray());
    }

    private static PhysicalFrame readPhysicalFrame(Transport transport) {
        ReadBuffer frameReadBuffer = readFully(transport);
        byte[] frameBytes = frameReadBuffer.getByteBuffer();
        int channelId = frameBytes[0] & 0xFF;
        int flags = frameBytes[1] & 0xFF;
        boolean first = (flags & 0x01) != 0;
        boolean last = (flags & 0x02) != 0;
        boolean encrypted = (flags & 0x08) != 0;

        boolean extended = first & !last;
        int size = ((frameBytes[2] & 0xFF) << 8) | (frameBytes[3] & 0xFF);
        byte[] payload;
        int offset = extended? 8 : 4;
        if (size + offset > frameReadBuffer.getBytesRead()) {
            throw new IllegalStateException("Payload last index of " + (size + offset) + " cannot be greater than the number of bytes read " + frameReadBuffer.getBytesRead());
        }
        payload= Arrays.copyOfRange(frameBytes, offset, size + offset);

        return new PhysicalFrame(channelId, first, last, encrypted, payload);
    }

    private static ReadBuffer readFully(Transport transport) {
        byte[] buffer = new byte[131072];
        final AtomicInteger offset = new AtomicInteger(0);
        CompletableFuture<Integer> future = CompletableFuture.supplyAsync(() -> transport.read(buffer, offset.get(), buffer.length));
        int read;
        try {
            read = future.get(5, TimeUnit.SECONDS);
        } catch (TimeoutException ex) {
            try {
                transport.close();
            } catch (IOException e) {
                throw new RuntimeException("Failed to close transport on error.", e);
            }
            throw new IllegalStateException("Timed out while attempting to read frame from head unit");
        } catch (ExecutionException | InterruptedException e) {
            throw new RuntimeException(e);
        }
        if (read <= 0) {
            throw new IllegalStateException(
                "Transport returned " + read + " while reading a frame (" + offset
                        + "/ bytes so far) -- connection likely closed");
        }
        return new ReadBuffer(buffer, read);
    }
}
