package com.example.aaphone.protocol.framing;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.util.Log;

import android.util.Log;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Covers {@link FrameCodec} against the confirmed frame layout in
 * docs/design/0003-aasdk-protocol-notes.md -- including a direct regression
 * test against the real byte trace captured off the vehicle's head unit.
 */
public class FrameCodecTest {

    // FrameCodec.readMessage() calls android.util.Log.d(...) for on-device
    // debugging. Log throws by design in plain JVM unit tests (it's a stub
    // outside a real Android runtime / Robolectric) -- statically mocking it
    // for the duration of each test keeps that logging in production code
    // without breaking these tests.
    private MockedStatic<Log> logMock;

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
        try(MockedStatic<Log> log = mockStatic(Log.class)) {
            log.when(() -> Log.d(anyString(), anyString())).thenReturn(0);

            FrameCodec.Message message = FrameCodec.readMessage(transport);

            assertEquals(0, message.channelId);
            assertFalse(message.encrypted);
            // payload is messageId(2 bytes) + body -- MessageId=1, body=major(1)/minor(7)
            assertArrayEquals(new byte[]{0, 1, 0, 1, 0, 7}, message.payload);
        }
    }

    @Test
    public void readMessageReassemblesAFirstExtendedThenLastShortFragmentPair() {
        // Two separate chunks -- each fragment is its own physical USB transfer,
        // matching how the real head unit would actually send a fragmented message.
        // Frame 1: FIRST-only (0x01) -> EXTENDED size field (frameSize=3, totalSize=5), payload 0xAA 0xBB 0xCC
        // Frame 2: LAST-only (0x02) -> SHORT size field (frameSize=2), payload 0xDD 0xEE
        FakeTransport transport = new FakeTransport(
            new byte[]{0, 1, 0, 3, 0, 0, 0, 5, (byte) 0xAA, (byte) 0xBB, (byte) 0xCC},
            new byte[]{0, 2, 0, 2, (byte) 0xDD, (byte) 0xEE}
        );
        try(MockedStatic<Log> log = mockStatic(Log.class)) {
            log.when(() -> Log.d(anyString(), anyString())).thenReturn(0);
            FrameCodec.Message message = FrameCodec.readMessage(transport);

            assertEquals(0, message.channelId);
            assertArrayEquals(
                    new byte[]{(byte) 0xAA, (byte) 0xBB, (byte) 0xCC, (byte) 0xDD, (byte) 0xEE},
                    message.payload
            );
        }
    }

    @Test
    public void writeBulkFrameRejectsPayloadsOverTheShortFrameLimit() {
        FakeTransport transport = new FakeTransport(new byte[0]);
        byte[] tooBig = new byte[0x10000]; // 65536 > the 65535 SHORT limit

        assertThrows(IllegalArgumentException.class,
            () -> FrameCodec.writeBulkFrame(transport, 0, false, tooBig));
    }

    @Test
    public void writeBulkFrameSetsTheEncryptedFlagBitWhenRequested() {
        FakeTransport transport = new FakeTransport(new byte[0]);

        FrameCodec.writeBulkFrame(transport, 0, /* encrypted= */ true, new byte[]{1});

        byte[] written = transport.writtenBytes();
        // header: channel(1) flags(1) size(2), then payload -- flags should be
        // BULK (0x03) | ENCRYPTED (0x08) = 0x0B.
        assertEquals(0x0B, written[1] & 0xFF);
    }

    @Test
    public void readMessageParsesTheEncryptedFlagBit() {
        // channel=0, flags=BULK|ENCRYPTED(0x0B), size=1, payload=0xAA
        FakeTransport transport = new FakeTransport(new byte[]{0, 0x0B, 0, 1, (byte) 0xAA});
        try (MockedStatic<Log> log = mockStatic(Log.class)) {
            log.when(() -> Log.d(anyString(), anyString())).thenReturn(0);

            FrameCodec.Message message = FrameCodec.readMessage(transport);

            assertTrue(message.encrypted);
            assertArrayEquals(new byte[]{(byte) 0xAA}, message.payload);
        }
    }

    @Test
    public void readPhysicalFrameRejectsADeclaredSizeLargerThanBytesActuallyRead() {
        // Declares size=10 but the transport only actually hands back 5 bytes
        // total (header(4) + 1 payload byte) -- FakeTransport.read() returns
        // exactly what's in the chunk, so this simulates a truncated/corrupt frame.
        FakeTransport transport = new FakeTransport(new byte[]{0, 3, 0, 10, (byte) 0xAA});

        assertThrows(IllegalStateException.class, () -> FrameCodec.readMessage(transport));
    }
}
