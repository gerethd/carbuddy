package com.example.aaphone.protocol.handshake;

import com.example.aaphone.protocol.framing.FrameCodec;
import com.example.aaphone.protocol.transport.Transport;

import java.nio.ByteBuffer;
import java.util.Arrays;

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
        runTlsHandshake();
        return receiveAuthComplete();
    }

    /** The now-established session, for ChannelMultiplexer to use once encrypted traffic starts (task #4). */
    public SSLEngine getSslEngine() {
        return sslEngine;
    }

    private void exchangeVersion() {
        FrameCodec.Message request = FrameCodec.readMessage(transport);
        System.out.println("" + Arrays.toString(request.payload));
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

    private void runTlsHandshake() {
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
        ByteBuffer appIn = ByteBuffer.allocate(sslEngine.getSession().getApplicationBufferSize());

        try {
            runHandshakeLoop(netIn, netOut, appIn, emptyOut);
        } catch (SSLException ex) {
            throw new IllegalStateException("TLS handshake failed: " + ex.getMessage(), ex);
        }
    }

    private void runHandshakeLoop(ByteBuffer netIn, ByteBuffer netOut, ByteBuffer appIn, ByteBuffer emptyOut)
        throws SSLException {
        HandshakeStatus status = sslEngine.getHandshakeStatus();
        while (status != HandshakeStatus.FINISHED && status != HandshakeStatus.NOT_HANDSHAKING) {
            switch (status) {
                case NEED_UNWRAP: {
                    // NOTE: Android's SSLEngineResult.HandshakeStatus has no
                    // NEED_UNWRAP_AGAIN (that's a newer-JDK-only addition) -- so
                    // "retry with bytes already buffered in netIn" is handled by
                    // simply not advancing `status` below, which re-enters this
                    // same case next iteration with netIn still holding them.
                    if (!netIn.hasRemaining()) {
                        byte[] handshakeBytes = readHandshakeMessageBody();
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
                        byte[] moreBytes = readHandshakeMessageBody();
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
    }

    private byte[] readHandshakeMessageBody() {
        FrameCodec.Message message = FrameCodec.readMessage(transport);
        requireMessageId(message.payload, ControlMessageId.SSL_HANDSHAKE);
        return extractBody(message.payload);
    }

    private boolean receiveAuthComplete() {
        FrameCodec.Message message = FrameCodec.readMessage(transport);
        requireMessageId(message.payload, ControlMessageId.AUTH_COMPLETE);
        byte[] body = extractBody(message.payload);
        // AuthCompleteIndication is a one-field protobuf message: required
        // enums.Status.Enum status = 1. Hand-decoded rather than pulling in a
        // full protobuf runtime for one two-byte message: tag byte 0x08 (field
        // 1, varint wire type) followed by a single-byte varint value (OK=0,
        // FAIL=1 -- both fit in one byte, so no multi-byte varint decoding needed).
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

    private static byte[] extractBody(byte[] payload) {
        byte[] body = new byte[payload.length - 2];
        System.arraycopy(payload, 2, body, 0, body.length);
        return body;
    }

    private static void requireMessageId(byte[] payload, int expected) {
        int actual = readMessageId(payload);
        if (actual != expected) {
            throw new IllegalStateException(
                "Expected message 0x" + Integer.toHexString(expected) + " but got 0x" + Integer.toHexString(actual));
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x ", b));
        }
        return sb.toString().trim();
    }
}
