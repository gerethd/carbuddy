package com.example.aaphone.protocol.handshake;

import android.util.Log;

import com.example.aaphone.protocol.channel.MessagingChannel;
import com.example.aaphone.protocol.framing.FrameCodec;
import com.example.aaphone.protocol.transport.Transport;

import java.nio.ByteBuffer;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLEngineResult.Status;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLPeerUnverifiedException;

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
    private final FrameCodec frameCode;
    private SSLEngine sslEngine;

    public AaHandshake(Transport transport, FrameCodec frameCodec) {
        this.transport = transport;
        this.frameCode = frameCodec;
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
        List<FrameCodec.Message> requestMessages = frameCode.readMessage(transport);
        FrameCodec.Message request = requestMessages.get(0);
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
        // Mutual TLS: the real Gearhead app requires this too (confirmed by
        // decompiling it -- see docs/design/0003, "TLS trust model, confirmed
        // from the real Gearhead app"). Without it we never ask the head unit
        // for its own certificate during the handshake at all.
        sslEngine.setNeedClientAuth(true);
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
                        // BUG FIX: this used to take message.payload directly, which
                        // still has the 2-byte MessageId prefix -- that injected two
                        // stray bytes into the middle of the reassembled TLS record,
                        // corrupting it (caught by
                        // AaHandshakeErrorPathsTest#handshakeSurvivesAClientHelloRecordSplitAcrossTwoHandshakeMessages,
                        // which failed with "SSLException: Insufficient space in the
                        // buffer, may be cause by an unexpected end of handshake data"
                        // before this fix). The initial (non-underflow) branch above
                        // already does this correctly.
                        byte[] moreBytes = extractBody(message.payload);
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
        // The TLS handshake itself completing (FINISHED, or NOT_HANDSHAKING if
        // this JSSE implementation reports it that way once done) is not the
        // end of the story: per docs/design/0003, the head unit still owes us
        // a plain AUTH_COMPLETE control message before the session counts as
        // authenticated. Previously this method returned false unconditionally
        // the moment the loop exited here -- meaning even a fully successful
        // TLS handshake was reported as a failure, because nothing ever read
        // that final message. The early-return paths above (AUTH_COMPLETE
        // arriving mid-NEED_UNWRAP, before the TLS handshake itself finishes --
        // the real failure captured in AaHandshakeTest) are unaffected by this;
        // this is specifically the success path.
        logPeerCertificate();
        return receiveAuthComplete(readAuthCompleteMessage());
    }

    /**
     * Logs the head unit's leaf certificate the same way the real Gearhead
     * app does (see docs/design/0003, "TLS trust model, confirmed from the
     * real Gearhead app") -- subject DN and serial number -- so a real
     * capture gives us direct evidence of what the head unit actually sent,
     * instead of just an opaque AUTH_COMPLETE status code.
     */
    private void logPeerCertificate() {
        try {
            Certificate[] peerCertificates = sslEngine.getSession().getPeerCertificates();
            if (peerCertificates.length == 0 || !(peerCertificates[0] instanceof X509Certificate)) {
                return;
            }
            X509Certificate peerCertificate = (X509Certificate) peerCertificates[0];
            Log.i("CarBuddySSL", "Peer certificate subject name: " + peerCertificate.getSubjectDN().getName());
            Log.i("CarBuddySSL", "Peer certificate serial number: " + peerCertificate.getSerialNumber());
        } catch (SSLPeerUnverifiedException e) {
            // setNeedClientAuth(true) should mean this never happens once we
            // reach FINISHED, but this is diagnostic logging, not a control
            // path -- don't let it turn into an unrelated crash.
            Log.w("CarBuddySSL", "TLS handshake finished but peer did not present a certificate", e);
        }
    }

    private FrameCodec.Message readHandshakeMessageBody() {
        List<FrameCodec.Message> messages = frameCode.readMessage(transport);
        FrameCodec.Message message = messages.get(0);
        requireMessageId(message.payload, ControlMessageId.SSL_HANDSHAKE, Optional.of(ControlMessageId.AUTH_COMPLETE));
        return message;
    }

    /**
     * Reads the message expected right after the TLS handshake itself has
     * finished. Unlike {@link #readHandshakeMessageBody()}, no SSL_HANDSHAKE
     * alternate is accepted here -- once TLS is done, the only legitimate
     * next control message is AUTH_COMPLETE.
     */
    private FrameCodec.Message readAuthCompleteMessage() {
        List<FrameCodec.Message> messages = frameCode.readMessage(transport);
        FrameCodec.Message message = messages.get(0);
        requireMessageId(message.payload, ControlMessageId.AUTH_COMPLETE);
        return message;
    }

    private boolean receiveAuthComplete(FrameCodec.Message message) {
        byte[] body = extractBody(message.payload);
        if (body.length > 11 || (body[0] & 0xFF) != 0x08) {
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
