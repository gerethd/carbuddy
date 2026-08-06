package com.example.aaphone.protocol.framing;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

/**
 * Covers {@link FrameCodec} against the confirmed frame layout in
 * docs/design/0003-aasdk-protocol-notes.md -- including a direct regression
 * test against the real byte trace captured off the vehicle's head unit.
 */
public class FrameCodecTest {

    // The real VERSION_REQUEST captured off the head unit: channel=CONTROL,
    // flags=BULK, size=6, messageId=VERSION_REQUEST(1), body=major(1)/minor(7).
    private static final byte[] REAL_VERSION_REQUEST_CAPTURE = {
        0, 3, 0, 6, 0, 1, 0, 1, 0, 7
    };

    @Test
    public void writePlainMessageReproducesTheRealCapturedVersionRequestBytes() {
        FakeTransport transport = new FakeTransport(new byte[0]);

        // major=1, minor=7 -- the exact values decoded from the real capture.
        byte[] body = {0, 1, 0, 7};
        FrameCodec.writePlainMessage(transport, /* channelId= */ 0, /* messageId= */ 1, body);

        assertArrayEquals(REAL_VERSION_REQUEST_CAPTURE, transport.writtenBytes());
    }

    @Test
    public void readMessageParsesTheRealCapturedVersionRequest() {
        FakeTransport transport = new FakeTransport(REAL_VERSION_REQUEST_CAPTURE);

        FrameCodec.Message message = FrameCodec.readMessage(transport);

        assertEquals(0, message.channelId);
        assertFalse(message.encrypted);
        // payload is messageId(2 bytes) + body -- MessageId=1, body=major(1)/minor(7)
        assertArrayEquals(new byte[]{0, 1, 0, 1, 0, 7}, message.payload);
    }

    @Test
    public void readMessageReassemblesAFirstExtendedThenLastShortFragmentPair() {
        // Frame 1: FIRST-only (0x01) -> EXTENDED size field (frameSize=3, totalSize=5), payload 0xAA 0xBB 0xCC
        // Frame 2: LAST-only (0x02) -> SHORT size field (frameSize=2), payload 0xDD 0xEE
        byte[] input = {
            0, 1, 0, 3, 0, 0, 0, 5, (byte) 0xAA, (byte) 0xBB, (byte) 0xCC,
            0, 2, 0, 2, (byte) 0xDD, (byte) 0xEE,
        };
        FakeTransport transport = new FakeTransport(input);

        FrameCodec.Message message = FrameCodec.readMessage(transport);

        assertEquals(0, message.channelId);
        assertArrayEquals(
            new byte[]{(byte) 0xAA, (byte) 0xBB, (byte) 0xCC, (byte) 0xDD, (byte) 0xEE},
            message.payload
        );
    }

    @Test
    public void writeBulkFrameRejectsPayloadsOverTheShortFrameLimit() {
        FakeTransport transport = new FakeTransport(new byte[0]);
        byte[] tooBig = new byte[0x10000]; // 65536 > the 65535 SHORT limit

        assertThrows(IllegalArgumentException.class,
            () -> FrameCodec.writeBulkFrame(transport, 0, false, tooBig));
    }
}
