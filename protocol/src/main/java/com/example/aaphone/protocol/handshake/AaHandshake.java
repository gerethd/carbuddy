package com.example.aaphone.protocol.handshake;

import android.util.Log;

import com.example.aaphone.protocol.channel.MessagingChannel;
import com.example.aaphone.protocol.framing.FrameCodec;
import com.example.aaphone.protocol.transport.Transport;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Optional;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLEngineResult.Status;
import javax.net.ssl.SSLException;

/**
 * Performs the AA control-channel handshake: version negotiation, TLS
 * handshake, then waits for the head unit's auth-complete indication.
 *
 * Roles, confirmed from aasdk's {@code Cryptor.cpp}/{@code ControlServiceChannel.cpp}
 * (see docs/design/0003-aasdk-protocol-notes.md):
 * <ul>
 *   <li>The head unit sends {@code VERSION_REQUEST} first (matches our own
 *       empirical capture); we reply with {@code VERSION_RESPONSE}.
 *   <li>aasdk (acting as the head unit) sets itself up as the TLS
 *       <b>client</b> — so we are the TLS <b>server</b>, using our own
 *       identity (see {@link AaServerIdentity}), not a copy of the head
 *       unit's.
 *   <li>{@code AUTH_COMPLETE} is sent by the head unit, not by us — we only
 *       receive and check it, sent unencrypted (confirmed).
 * </ul>
 *
 * This has been built from sourced protocol facts, not guesswork, but has
 * NOT yet been exercised against a real head unit end-to-end. Exceptions
 * are allowed to propagate rather than being swallowed, deliberately, so
 * failures during this validation phase are loud rather than silent.
 *
 * Must not be called from the main/UI thread — every step here is a
 * blocking read.
 */
public class AaHandshake {

    private static final int CONTROL_CHANNEL = 0; // ChannelId.CONTROL

    private final Transport transport;
    private SSLEngine sslEngine;

    public AaHandshake(Transport transport) {
        this.transport = transport;
    }

    /** Runs the full handshake sequence and blocks until it completes or throws. */
    public boolean performHandshake() {
        exchangeVersion();
        return runTlsHandshake();

    }

    /** The now-established session, for ChannelMultiplexer to use once encrypted traffic starts (task #4). */
    public SSLEngine getSslEngine() {
        return sslEngine;
    }

    private void exchangeVersion() {
        FrameCodec.Message request = FrameCodec.readMessage(transport);
        requireMessageId(request.payload, ControlMessageId.VERSION_REQUEST);
        byte[] body = extractBody(request.payload);
        if (body.length != 4) {
            throw new IllegalStateException("VERSION_REQUEST body was " + body.length + " bytes, expected 4");
        }
        int major = ((body[0] & 0xFF) << 8) | (body[1] & 0xFF);
        int minor = ((body[2] & 0xFF) << 8) | (body[3] & 0xFF);

        // We mirror the head unit's own declared version back as MATCH: we have no
        // independent basis to claim a different version, and matching is the
        // safest choice to avoid a MISMATCH-triggered abort. See docs/design/0003.
        byte[] response = {
            (byte) (major >> 8), (byte) major,
            (byte) (minor >> 8), (byte) minor,
            0x00, 0x00, // status = MATCH (VersionResponseStatusEnum.MATCH = 0)
        };
        FrameCodec.writePlainMessage(transport, CONTROL_CHANNEL, ControlMessageId.VERSION_RESPONSE, response);
    }

    private boolean runTlsHandshake() {
        SSLContext context = AaServerIdentity.buildServerContext();
        sslEngine = context.createSSLEngine();
        sslEngine.setUseClientMode(false); // we are the TLS server -- see class javadoc
        sslEngine.setNeedClientAuth(false);
        try {
            sslEngine.beginHandshake();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to begin TLS handshake", e);
        }

        ByteBuffer emptyOut = ByteBuffer.allocate(0);
        ByteBuffer netOut = ByteBuffer.allocate(sslEngine.getSession().getPacketBufferSize());
        ByteBuffer netIn = ByteBuffer.allocate(sslEngine.getSession().getPacketBufferSize());
        netIn.limit(0);
        ByteBuffer appIn = ByteBuffer.allocate(sslEngine.getSession().getApplicationBufferSize());
        Log.d("CarBuddySSLNetIn", Arrays.toString(netIn.array()));
        Log.d("CarBuddySSLAppIn", Arrays.toString(appIn.array()));
        try {
            return runHandshakeLoop(netIn, netOut, appIn, emptyOut);
        } catch (SSLException ex) {
            throw new IllegalStateException("TLS handshake failed: " + ex.getMessage(), ex);
        }
    }

    private boolean runHandshakeLoop(ByteBuffer netIn, ByteBuffer netOut, ByteBuffer appIn, ByteBuffer emptyOut)
        throws SSLException {
        HandshakeStatus status = sslEngine.getHandshakeStatus();
        Log.d("CarBuddySSLStatus", status.toString());
        while (status != HandshakeStatus.FINISHED && status != HandshakeStatus.NOT_HANDSHAKING) {
            switch (status) {
                case NEED_UNWRAP: {
                    // NOTE: Android's SSLEngineResult.HandshakeStatus has no
                    // NEED_UNWRAP_AGAIN (that's a newer-JDK-only addition) -- so
                    // "retry with bytes already buffered in netIn" is handled by
                    // simply not advancing `status` below, which re-enters this
                    // same case next iteration with netIn still holding them.
                    if (!netIn.hasRemaining()) {
                        FrameCodec.Message message = readHandshakeMessageBody();
                        if (readMessageId(message.payload) == ControlMessageId.AUTH_COMPLETE) {
                            return receiveAuthComplete(message);
                        }
                        byte[] handshakeBytes = extractBody(message.payload);
                        netIn.clear();
                        netIn.put(handshakeBytes);
                        netIn.flip();
                    }
                    appIn.clear();
                    SSLEngineResult result = sslEngine.unwrap(netIn, appIn);
                    if (result.getStatus() == Status.BUFFER_UNDERFLOW) {
                        // The TLS record was incomplete -- append another SSL_HANDSHAKE
                        // message's bytes onto what's left in netIn and retry (status
                        // deliberately left as NEED_UNWRAP, see note above).
                        FrameCodec.Message message = readHandshakeMessageBody();
                        if (readMessageId(message.payload) == ControlMessageId.AUTH_COMPLETE) {
                            return receiveAuthComplete(message);
                        }
                        byte[] moreBytes = message.payload;
                        ByteBuffer grown = ByteBuffer.allocate(netIn.remaining() + moreBytes.length);
                        grown.put(netIn);
                        grown.put(moreBytes);
                        grown.flip();
                        netIn = grown;
                    } else {
                        status = result.getHandshakeStatus();
                    }
                    break;
                }
                case NEED_WRAP: {
                    netOut.clear();
                    SSLEngineResult result = sslEngine.wrap(emptyOut, netOut);
                    netOut.flip();
                    if (netOut.hasRemaining()) {
                        byte[] outBytes = new byte[netOut.remaining()];
                        netOut.get(outBytes);
                        FrameCodec.writePlainMessage(
                            transport, CONTROL_CHANNEL, ControlMessageId.SSL_HANDSHAKE, outBytes);
                    }
                    status = result.getHandshakeStatus();
                    break;
                }
                case NEED_TASK: {
                    Runnable task;
                    while ((task = sslEngine.getDelegatedTask()) != null) {
                        task.run();
                    }
                    status = sslEngine.getHandshakeStatus();
                    break;
                }
                default:
                    throw new IllegalStateException("Unexpected TLS handshake status: " + status);
            }
        }
        return false;
    }

    private FrameCodec.Message readHandshakeMessageBody() {
        FrameCodec.Message message = FrameCodec.readMessage(transport);
        requireMessageId(message.payload, ControlMessageId.SSL_HANDSHAKE, Optional.of(ControlMessageId.AUTH_COMPLETE));
        return message;
    }


    private boolean receiveAuthComplete(FrameCodec.Message message) {
        byte[] body = extractBody(message.payload);
        if (body.length != 2 || (body[0] & 0xFF) != 0x08) {
            throw new IllegalStateException("Unexpected AuthCompleteIndication body: " + bytesToHex(body));
        }
        int statusValue = body[1] & 0xFF;
        return statusValue == 0; // Status.Enum.OK = 0
    }

    private static int readMessageId(byte[] payload) {
        if (payload.length < 2) {
            throw new IllegalStateException("Message payload too short to contain a MessageId");
        }
        return ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF);
    }

    /**
     * Removes message id from the payload
     * @param payload
     * @return message body without payload
     */
    private static byte[] extractBody(byte[] payload) {
        byte[] body = new byte[payload.length - 2];
        System.arraycopy(payload, 2, body, 0, body.length);
        return body;
    }

    /**
     * Checks for a valid message id, throws exception if
     * @param payload payload the message id is extracted from
     * @param expected -- expected message id
     * @param alternate -- an alternative valid message id
     */
    private static void requireMessageId(byte[] payload, int expected, Optional<Integer> alternate) {
        int actual = readMessageId(payload);
        if (actual != expected && alternate.orElse(-1) != actual) {
            throw new IllegalStateException(
                "Expected message 0x" + Integer.toHexString(expected) + " but got 0x"
                        + Integer.toHexString(actual) + " and " + alternate.orElse(-1));
        }
    }

    /**
     * Convenience Method to set default for case of no alternate
     */
    private static void requireMessageId(byte[] payload, int expected) {
        requireMessageId(payload, expected, Optional.empty());
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x ", b));
        }
        return sb.toString().trim();
    }
}
