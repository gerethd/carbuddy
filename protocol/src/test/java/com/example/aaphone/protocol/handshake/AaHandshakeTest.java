package com.example.aaphone.protocol.handshake;

import static org.junit.Assert.assertFalse;

import android.util.Log;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Simulates the real SSL handshake exchange captured off a live run (see
 * protocol/src/test/resources/SSLHandshake.log), replaying the exact bytes
 * through {@link AaHandshake#performHandshake()} against a {@link FakeTransport}.
 *
 * This exists to reproduce, deterministically and without needing DHU/a
 * vehicle, the "expected message 0x3 but got 0x4" failure: a real
 * ClientHello arrives (correctly parsed), then a message with MessageId=4
 * (AUTH_COMPLETE) arrives immediately after -- with no evidence in the log
 * of our own ServerHello ever having been sent in between.
 *
 * Two things confirmed straight from the captured bytes, not assumed:
 *   - The ClientHello record is well-formed (content-type 0x16, record
 *     version 0x0301, declared length 225 -- matches the captured length
 *     exactly).
 *   - The "AUTH_COMPLETE" body is 11 bytes (tag 0x08 + a 10-byte varint),
 *     not the 2-byte OK(0)/FAIL(1) body {@link AaHandshake} assumes --
 *     decoding the varint gives status = -3, not a simple OK/FAIL value.
 *     receiveAuthComplete()'s `body.length != 2` check is wrong for this
 *     real data regardless of the routing bug below.
 */
public class AaHandshakeTest {

    // Real VERSION_REQUEST capture (see docs/design/0003) -- performHandshake()
    // reads this first regardless of what we're actually trying to exercise.
    private static final byte[] VERSION_REQUEST_FRAME = {
        0, 3, 0, 6, 0, 1, 0, 1, 0, 7
    };

    // Frame 1 from SSLHandshake.log line 2: channel=CONTROL, flags=BULK, size=232,
    // payload = MessageId(SSL_HANDSHAKE=3) + a real, well-formed ClientHello record.
    private static final byte[] CLIENT_HELLO_FRAME = {
        0, 3, 0, (byte) 232, 0, 3, 22, 3, 1, 0, (byte) 225, 1, 0, 0, (byte) 221, 3, 3, 95, 47, 50, 16,
        (byte) 176, (byte) 242, 28, 38, (byte) 167, (byte) 183, 93, 54, 104, (byte) 174, 125, 71, 30, 70,
        (byte) 232, 66, (byte) 255, (byte) 242, (byte) 148, 105, 127, 52, (byte) 210, (byte) 131, (byte) 157,
        3, (byte) 158, (byte) 172, 32, (byte) 234, 37, 65, (byte) 163, (byte) 161, 79, 114, 80, 125, 14,
        (byte) 254, 98, 60, (byte) 211, (byte) 175, 16, (byte) 225, (byte) 137, (byte) 231, 72, (byte) 227,
        0, 42, 124, 81, (byte) 150, (byte) 138, 16, 107, (byte) 155, 13, 85, 0, 36, 19, 1, 19, 2, 19, 3,
        (byte) 192, 43, (byte) 192, 47, (byte) 192, 44, (byte) 192, 48, (byte) 204, (byte) 169, (byte) 204,
        (byte) 168, (byte) 192, 9, (byte) 192, 19, (byte) 192, 10, (byte) 192, 20, 0, (byte) 156, 0,
        (byte) 157, 0, 47, 0, 53, 0, 10, 1, 0, 0, 112, 0, 23, 0, 0, (byte) 255, 1, 0, 1, 0, 0, 10, 0, 8,
        0, 6, 0, 29, 0, 23, 0, 24, 0, 11, 0, 2, 1, 0, 0, 35, 0, 0, 0, 13, 0, 20, 0, 18, 4, 3, 8, 4, 4, 1,
        5, 3, 8, 5, 5, 1, 8, 6, 6, 1, 2, 1, 0, 51, 0, 38, 0, 36, 0, 29, 0, 32, 109, 106, (byte) 233,
        (byte) 219, 44, 107, 44, 85, 124, (byte) 164, 64, (byte) 252, 9, 118, (byte) 149, (byte) 223,
        (byte) 148, (byte) 168, (byte) 174, 13, 13, 68, (byte) 228, (byte) 220, 113, (byte) 223, 125, 74,
        (byte) 224, (byte) 143, (byte) 191, 43, 0, 45, 0, 2, 1, 1, 0, 43, 0, 5, 4, 3, 4, 3, 3
    };

    // Frame 2 from SSLHandshake.log line 3: channel=CONTROL, flags=BULK, size=13,
    // payload = MessageId(AUTH_COMPLETE=4) + an 11-byte body (tag 0x08 + 10-byte
    // varint decoding to status = -3, NOT the assumed 2-byte OK/FAIL body).
    private static final byte[] AUTH_COMPLETE_LIKE_FRAME = {
        0, 3, 0, 13, 0, 4, 8, (byte) 253, (byte) 255, (byte) 255, (byte) 255, (byte) 255, (byte) 255,
        (byte) 255, (byte) 255, (byte) 255, 1
    };

    private MockedStatic<Log> logMock;

    @Before
    public void mockAndroidLog() {
        logMock = Mockito.mockStatic(Log.class);
    }

    @After
    public void closeAndroidLogMock() {
        logMock.close();
    }

    /**
     * Encodes the CORRECT desired behavior, not the current one -- this
     * should fail until the real bug is fixed, and turn green once it is,
     * so a fix can be verified without needing DHU/a vehicle:
     * {@code performHandshake()} should recognize the head unit's
     * {@code AUTH_COMPLETE} (status = -3, decoded from the real captured
     * varint -- not the OK/FAIL values we'd assumed) as a controlled,
     * legitimate "not authenticated" signal and return {@code false} --
     * not throw an unrelated low-level TLS parsing exception.
     *
     * As of writing this currently throws {@code IllegalStateException}
     * wrapping {@code SSLProtocolException: Input record too big} from
     * inside {@code sslEngine.unwrap()} instead, for (at least) two
     * compounding reasons documented elsewhere:
     *   - the primary NEED_UNWRAP branch has no check for an early
     *     AUTH_COMPLETE (only the BUFFER_UNDERFLOW retry branch does),
     *   - receiveAuthComplete() assumes a 2-byte body (tag + 1-byte
     *     varint); the real body is 11 bytes (tag + a full 10-byte varint).
     */
    @Test
    public void performHandshakeRecognizesAuthCompleteAndReturnsFalseForNonOkStatus() {
        FakeTransport transport = new FakeTransport(
            VERSION_REQUEST_FRAME,
            CLIENT_HELLO_FRAME,
            AUTH_COMPLETE_LIKE_FRAME
        );

        boolean result = new AaHandshake(transport).performHandshake();

        assertFalse("status -3 is not OK(0); performHandshake() should report failure, not throw", result);
    }
}
